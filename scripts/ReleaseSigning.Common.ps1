Set-StrictMode -Version Latest

$script:ReleaseSigningEnvironmentNames = @(
    'FITNESS_RELEASE_STORE_FILE',
    'FITNESS_RELEASE_STORE_PASSWORD',
    'FITNESS_RELEASE_KEY_ALIAS',
    'FITNESS_RELEASE_KEY_PASSWORD'
)
$script:ReleaseSigningEntropy = [Text.Encoding]::UTF8.GetBytes(
    'com.personal.fitnessledger/release-signing/dpapi/v1'
)
$script:ReleaseTagSigningEntropy = [Text.Encoding]::UTF8.GetBytes(
    'com.personal.fitnessledger/release-tag-signing/dpapi/v1'
)

function Assert-WindowsDpapiAvailable {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'The local signing-secret store requires Windows DPAPI. Use the documented environment variables on other platforms.'
    }
    Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
}

function Get-ReleaseRepositoryRoot {
    return [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd(
        [IO.Path]::DirectorySeparatorChar
    )
}

function Assert-PathOutsideRepository(
    [string] $RepositoryRoot,
    [string] $CandidatePath,
    [string] $Label
) {
    $root = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $candidate = [IO.Path]::GetFullPath($CandidatePath).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $rootPrefix = $root + [IO.Path]::DirectorySeparatorChar
    if ($candidate.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
        $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label must be outside the repository."
    }
    return $candidate
}

function Assert-DeliveryApkPlacement(
    [string] $DeliveryDirectory,
    [string] $ArchiveDirectory,
    [string[]] $AllowedApkPaths
) {
    $delivery = [IO.Path]::GetFullPath($DeliveryDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $archive = [IO.Path]::GetFullPath($ArchiveDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $deliveryPrefix = $delivery + [IO.Path]::DirectorySeparatorChar
    $archivePrefix = $archive + [IO.Path]::DirectorySeparatorChar
    if (-not $archive.StartsWith($deliveryPrefix, [StringComparison]::Ordinal)) {
        throw 'Delivery archive must be a child of the delivery directory.'
    }

    foreach ($root in @($delivery, $archive)) {
        $rootItem = Get-Item -LiteralPath $root -Force
        if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Delivery and archive roots must not be reparse points: $root"
        }
    }

    $allowed = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in @($AllowedApkPaths)) {
        $full = [IO.Path]::GetFullPath($path)
        if (-not $full.StartsWith($deliveryPrefix, [StringComparison]::Ordinal) -or
            $full.StartsWith($archivePrefix, [StringComparison]::Ordinal) -or
            -not $allowed.Add($full) -or
            -not (Test-Path -LiteralPath $full -PathType Leaf)) {
            throw "Allowed delivery APK path is missing, duplicated, archived, or outside delivery: $path"
        }
    }

    $reparsePoints = @(
        Get-ChildItem -LiteralPath $delivery -Force -Recurse |
            Where-Object {
                $full = [IO.Path]::GetFullPath($_.FullName)
                -not $full.StartsWith($archivePrefix, [StringComparison]::Ordinal) -and
                    ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
            }
    )
    if ($reparsePoints.Count -ne 0) {
        throw "Reparse points outside the delivery archive are forbidden: $($reparsePoints.FullName -join ', ')"
    }

    $unexpected = @(
        Get-ChildItem -LiteralPath $delivery -Force -File -Recurse |
            Where-Object { $_.Extension.Equals('.apk', [StringComparison]::OrdinalIgnoreCase) } |
            Where-Object {
                $full = [IO.Path]::GetFullPath($_.FullName)
                -not $full.StartsWith($archivePrefix, [StringComparison]::Ordinal) -and
                    -not $allowed.Contains($full)
            }
    )
    if ($unexpected.Count -ne 0) {
        throw "APK files outside the exact current install, release evidence, instrumentation evidence, or history archive are forbidden: $($unexpected.FullName -join ', ')"
    }
}

function Assert-FilesByteIdentical(
    [string] $FirstPath,
    [string] $SecondPath,
    [string] $Label
) {
    foreach ($path in @($FirstPath, $SecondPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "$Label file is missing: $path"
        }
    }
    $first = Get-Item -LiteralPath $FirstPath
    $second = Get-Item -LiteralPath $SecondPath
    if ($first.Length -ne $second.Length -or
        (Get-FileHash -LiteralPath $FirstPath -Algorithm SHA256).Hash.ToUpperInvariant() -cne
            (Get-FileHash -LiteralPath $SecondPath -Algorithm SHA256).Hash.ToUpperInvariant()) {
        throw "$Label files are not byte-for-byte identical."
    }
}

function ConvertTo-UnsecuredString([Security.SecureString] $Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Protect-ReleaseSigningPayload(
    [hashtable] $Payload,
    [string] $Destination
) {
    Assert-WindowsDpapiAvailable
    $required = @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')
    foreach ($name in $required) {
        if (-not $Payload.ContainsKey($name) -or [string]::IsNullOrWhiteSpace([string] $Payload[$name])) {
            throw "Signing payload field '$name' is missing."
        }
    }

    $destinationPath = [IO.Path]::GetFullPath($Destination)
    $destinationDirectory = Split-Path -Parent $destinationPath
    if (-not (Test-Path -LiteralPath $destinationDirectory -PathType Container)) {
        New-Item -ItemType Directory -Path $destinationDirectory | Out-Null
    }

    $plainBytes = $null
    $protectedBytes = $null
    try {
        $json = [ordered]@{
            format = 'fitness-ledger-signing-payload-v1'
            storeFile = [string] $Payload['storeFile']
            storePassword = [string] $Payload['storePassword']
            keyAlias = [string] $Payload['keyAlias']
            keyPassword = [string] $Payload['keyPassword']
        } | ConvertTo-Json -Compress
        $plainBytes = [Text.Encoding]::UTF8.GetBytes($json)
        $protectedBytes = [Security.Cryptography.ProtectedData]::Protect(
            $plainBytes,
            $script:ReleaseSigningEntropy,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        $envelope = [ordered]@{
            format = 'fitness-ledger-dpapi-envelope-v1'
            scope = 'CurrentUser'
            protectedPayload = [Convert]::ToBase64String($protectedBytes)
        } | ConvertTo-Json
        $temporaryPath = "$destinationPath.tmp-$([Guid]::NewGuid().ToString('N'))"
        try {
            [IO.File]::WriteAllText($temporaryPath, $envelope, [Text.UTF8Encoding]::new($false))
            Move-Item -LiteralPath $temporaryPath -Destination $destinationPath -Force
        } finally {
            if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
                Remove-Item -LiteralPath $temporaryPath -Force
            }
        }
    } finally {
        if ($null -ne $plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
        if ($null -ne $protectedBytes) { [Array]::Clear($protectedBytes, 0, $protectedBytes.Length) }
    }
}

function Unprotect-ReleaseSigningPayload([string] $Source) {
    Assert-WindowsDpapiAvailable
    $sourcePath = [IO.Path]::GetFullPath($Source)
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "DPAPI signing-secret blob is missing: $sourcePath"
    }

    $envelope = Get-Content -LiteralPath $sourcePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($envelope.format -cne 'fitness-ledger-dpapi-envelope-v1' -or $envelope.scope -cne 'CurrentUser') {
        throw 'Unsupported signing-secret envelope format or scope.'
    }

    $protectedBytes = $null
    $plainBytes = $null
    try {
        $protectedBytes = [Convert]::FromBase64String([string] $envelope.protectedPayload)
        $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect(
            $protectedBytes,
            $script:ReleaseSigningEntropy,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        $payload = [Text.Encoding]::UTF8.GetString($plainBytes) | ConvertFrom-Json
        if ($payload.format -cne 'fitness-ledger-signing-payload-v1') {
            throw 'Unsupported decrypted signing payload format.'
        }
        foreach ($name in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
            if ([string]::IsNullOrWhiteSpace([string] $payload.$name)) {
                throw "Decrypted signing payload field '$name' is missing."
            }
        }
        return $payload
    } catch [Security.Cryptography.CryptographicException] {
        throw 'DPAPI could not decrypt the signing blob for the current Windows user.'
    } finally {
        if ($null -ne $protectedBytes) { [Array]::Clear($protectedBytes, 0, $protectedBytes.Length) }
        if ($null -ne $plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
    }
}

function Protect-ReleaseTagSigningPrivateKey(
    [byte[]] $PrivateKeyBytes,
    [string] $Destination
) {
    Assert-WindowsDpapiAvailable
    if ($null -eq $PrivateKeyBytes -or $PrivateKeyBytes.Length -lt 64) {
        throw 'Release tag signing private key is empty or malformed.'
    }
    $protectedBytes = $null
    try {
        $protectedBytes = [Security.Cryptography.ProtectedData]::Protect(
            $PrivateKeyBytes,
            $script:ReleaseTagSigningEntropy,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        $envelope = [ordered]@{
            format = 'fitness-ledger-release-tag-key-dpapi-v1'
            scope = 'CurrentUser'
            protectedPayload = [Convert]::ToBase64String($protectedBytes)
        } | ConvertTo-Json
        $destinationPath = [IO.Path]::GetFullPath($Destination)
        $temporaryPath = "$destinationPath.tmp-$([Guid]::NewGuid().ToString('N'))"
        try {
            [IO.File]::WriteAllText($temporaryPath, $envelope, [Text.UTF8Encoding]::new($false))
            Move-Item -LiteralPath $temporaryPath -Destination $destinationPath
        } finally {
            if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
                Remove-Item -LiteralPath $temporaryPath -Force
            }
        }
    } finally {
        if ($null -ne $protectedBytes) { [Array]::Clear($protectedBytes, 0, $protectedBytes.Length) }
    }
}

function Unprotect-ReleaseTagSigningPrivateKey([string] $Source) {
    Assert-WindowsDpapiAvailable
    $sourcePath = [IO.Path]::GetFullPath($Source)
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "DPAPI release tag signing key is missing: $sourcePath"
    }
    $envelope = Get-Content -LiteralPath $sourcePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($envelope.format -cne 'fitness-ledger-release-tag-key-dpapi-v1' -or
        $envelope.scope -cne 'CurrentUser') {
        throw 'Unsupported release tag signing key envelope format or scope.'
    }
    $protectedBytes = $null
    try {
        $protectedBytes = [Convert]::FromBase64String([string] $envelope.protectedPayload)
        return [Security.Cryptography.ProtectedData]::Unprotect(
            $protectedBytes,
            $script:ReleaseTagSigningEntropy,
            [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
    } catch [Security.Cryptography.CryptographicException] {
        throw 'DPAPI could not decrypt the release tag signing key for the current Windows user.'
    } finally {
        if ($null -ne $protectedBytes) { [Array]::Clear($protectedBytes, 0, $protectedBytes.Length) }
    }
}

function Assert-ReleaseTagSignature(
    [string] $RepositoryRoot,
    [string] $Tag,
    [string] $ExpectedPrincipal,
    [string] $ExpectedFingerprint
) {
    if ($ExpectedPrincipal -notmatch '^[A-Za-z0-9._@+-]+$' -or
        $ExpectedFingerprint -notmatch '^SHA256:[A-Za-z0-9+/]+$') {
        throw 'Release tag signing principal or fingerprint policy is malformed.'
    }
    $allowedSignersPath = Join-Path $RepositoryRoot 'release-tag-allowed-signers'
    $allowedLines = @(
        Get-Content -LiteralPath $allowedSignersPath -Encoding UTF8 |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch '^\s*#' }
    )
    if ($allowedLines.Count -ne 1) {
        throw 'Release tag allowed-signers file must contain exactly one trust root.'
    }
    $parts = $allowedLines[0].Trim() -split '\s+'
    if ($parts.Count -ne 3 -or $parts[0] -cne $ExpectedPrincipal -or
        $parts[1] -cne 'ssh-ed25519') {
        throw 'Release tag allowed-signers entry does not match the pinned principal/algorithm.'
    }
    $keyBytes = $null
    $digest = $null
    try {
        $keyBytes = [Convert]::FromBase64String($parts[2])
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $digest = $sha.ComputeHash($keyBytes) } finally { $sha.Dispose() }
        $actualFingerprint = 'SHA256:' + [Convert]::ToBase64String($digest).TrimEnd('=')
    } catch [FormatException] {
        throw 'Release tag allowed-signers public key is malformed.'
    } finally {
        if ($null -ne $keyBytes) { [Array]::Clear($keyBytes, 0, $keyBytes.Length) }
        if ($null -ne $digest) { [Array]::Clear($digest, 0, $digest.Length) }
    }
    if ($actualFingerprint -cne $ExpectedFingerprint) {
        throw "Release tag signer '$actualFingerprint' does not match policy '$ExpectedFingerprint'."
    }
    $verifyOutput = @(& git -C $RepositoryRoot `
        -c gpg.format=ssh `
        -c "gpg.ssh.allowedSignersFile=$allowedSignersPath" `
        verify-tag --raw $Tag 2>&1 | ForEach-Object { [string] $_ })
    $expectedGoodSignature = 'Good "git" signature for ' + [regex]::Escape($ExpectedPrincipal) + '(?:\s|$)'
    if ($LASTEXITCODE -ne 0 -or
        ($verifyOutput -join "`n") -notmatch $expectedGoodSignature) {
        throw "Release tag '$Tag' has no valid signature from the pinned trust root: $($verifyOutput -join [Environment]::NewLine)"
    }
    return [ordered]@{
        principal = $ExpectedPrincipal
        fingerprint = $actualFingerprint
        allowedSignersFile = 'release-tag-allowed-signers'
    }
}

function Assert-ReleaseApkNativeLibraries(
    [string] $ApkPath,
    [string[]] $AllowedAbis,
    [string[]] $RequiredLibraries
) {
    if ($AllowedAbis.Count -eq 0 -or $RequiredLibraries.Count -eq 0 -or
        @($AllowedAbis | Where-Object { $_ -notmatch '^[A-Za-z0-9_-]+$' }).Count -ne 0 -or
        @($RequiredLibraries | Where-Object { $_ -notmatch '^lib[A-Za-z0-9_.+-]+\.so$' }).Count -ne 0) {
        throw 'Release native ABI/library policy is malformed.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $nativeEntries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
        $duplicates = @($nativeEntries | Group-Object FullName | Where-Object Count -ne 1)
        if ($duplicates.Count -ne 0) {
            throw "APK has duplicate native-library entries: $($duplicates.Name -join ', ')"
        }
        $actualAbis = @(
            $nativeEntries | ForEach-Object { ($_.FullName -split '/')[1] } |
                Sort-Object -Unique
        )
        $expectedAbis = @($AllowedAbis | Sort-Object -Unique)
        if (($actualAbis -join ',') -cne ($expectedAbis -join ',')) {
            throw "APK ABI set '$($actualAbis -join ',')' does not match policy '$($expectedAbis -join ',')'."
        }
        foreach ($abi in $AllowedAbis) {
            foreach ($library in $RequiredLibraries) {
                $entryName = "lib/$abi/$library"
                if (@($archive.Entries | Where-Object { $_.FullName -ceq $entryName }).Count -ne 1) {
                    throw "APK is missing required native library '$entryName'."
                }
            }
        }
    } finally {
        $archive.Dispose()
    }
    return [ordered]@{
        abis = @($AllowedAbis)
        requiredLibrariesPerAbi = @($RequiredLibraries)
    }
}

function Resolve-EvidenceRelativeFile(
    [string] $EvidenceRoot,
    [string] $RelativePath,
    [Collections.Generic.HashSet[string]] $SeenPaths,
    [string] $Label
) {
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
        $RelativePath.Contains('\') -or
        [IO.Path]::IsPathRooted($RelativePath) -or
        ($RelativePath -split '/') -contains '..' -or
        -not $SeenPaths.Add($RelativePath)) {
        throw "$Label has an unsafe or duplicate relative path: $RelativePath"
    }
    $rootPrefix = [IO.Path]::GetFullPath($EvidenceRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar
    ) + [IO.Path]::DirectorySeparatorChar
    $fullPath = [IO.Path]::GetFullPath((
        Join-Path $EvidenceRoot ($RelativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
    ))
    if (-not $fullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "$Label path is missing or escapes its evidence root: $RelativePath"
    }
    return $fullPath
}

function Assert-EvidenceFileEntry(
    [string] $EvidenceRoot,
    [object] $Entry,
    [Collections.Generic.HashSet[string]] $SeenPaths,
    [string] $Label,
    [switch] $RequireBytes
) {
    $relative = [string] $Entry.path
    $fullPath = Resolve-EvidenceRelativeFile $EvidenceRoot $relative $SeenPaths $Label
    $expectedHash = ([string] $Entry.sha256).ToUpperInvariant()
    if ($expectedHash -notmatch '^[0-9A-F]{64}$') {
        throw "$Label has a malformed SHA-256: $relative"
    }
    $file = Get-Item -LiteralPath $fullPath
    if ($RequireBytes -and $null -eq $Entry.bytes) {
        throw "$Label has no byte length: $relative"
    }
    if ($null -ne $Entry.bytes -and [long] $Entry.bytes -ne $file.Length) {
        throw "$Label byte length is stale: $relative"
    }
    $actualHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualHash -cne $expectedHash) {
        throw "$Label SHA-256 is stale: $relative"
    }
    return [ordered]@{ path = $relative; fullPath = $fullPath; bytes = $file.Length; sha256 = $actualHash }
}

function Assert-ExactEvidenceChecksumFile(
    [string] $EvidenceRoot,
    [string] $ChecksumFileName,
    [string] $Label
) {
    $checksumPath = Join-Path $EvidenceRoot $ChecksumFileName
    if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
        throw "$Label checksum file is missing: $checksumPath"
    }
    $expectedLines = @(
        Get-ChildItem -LiteralPath $EvidenceRoot -File -Recurse |
            Where-Object { $_.FullName -cne $checksumPath } |
            Sort-Object FullName |
            ForEach-Object {
                $relative = [IO.Path]::GetRelativePath($EvidenceRoot, $_.FullName) -replace '\\', '/'
                "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant())  $relative"
            }
    )
    $actualLines = @(
        Get-Content -LiteralPath $checksumPath -Encoding UTF8 |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if (($actualLines -join "`n") -cne ($expectedLines -join "`n")) {
        throw "$Label checksum file is incomplete, stale, or out of order."
    }
    return $checksumPath
}

function ConvertFrom-EvidenceTimestamp([object] $Value, [string] $Label) {
    if ($Value -is [DateTimeOffset]) { return [DateTimeOffset] $Value }
    if ($Value -is [DateTime]) { return [DateTimeOffset] ([DateTime] $Value) }
    $text = [string] $Value
    if ($text -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{7}(?:Z|[+-]\d{2}:\d{2})$') {
        throw "$Label is not an ISO-8601 round-trip timestamp."
    }
    try {
        return [DateTimeOffset]::Parse($text, [Globalization.CultureInfo]::InvariantCulture)
    } catch {
        throw "$Label is not a valid timestamp."
    }
}

function Get-JUnitEvidenceTotals([string] $Path, [string] $Label) {
    [xml] $xml = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    $suites = @($xml.SelectNodes('//testsuite[not(ancestor::testsuite)]'))
    if ($suites.Count -lt 1) { throw "$Label has no top-level testsuite." }
    $totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($suite in $suites) {
        if (@($suite.SelectNodes('.//testsuite')).Count -ne 0) {
            throw "$Label contains nested testsuites whose aggregate counts are ambiguous."
        }
        $cases = @($suite.SelectNodes('./testcase'))
        $declared = [ordered]@{
            tests = [int] $suite.tests
            failures = [int] $suite.failures
            errors = [int] $suite.errors
            skipped = [int] $suite.skipped
        }
        if (@($declared.Values | Where-Object { $_ -lt 0 }).Count -ne 0 -or
            $declared.tests -ne $cases.Count) {
            throw "$Label testsuite counts are negative or do not match its testcase children."
        }
        $actualFailures = 0
        $actualErrors = 0
        $actualSkipped = 0
        foreach ($case in $cases) {
            $caseFailures = @($case.SelectNodes('./failure')).Count
            $caseErrors = @($case.SelectNodes('./error')).Count
            $caseSkipped = @($case.SelectNodes('./skipped')).Count
            if ($caseFailures -gt 1 -or $caseErrors -gt 1 -or $caseSkipped -gt 1 -or
                ($caseFailures + $caseErrors + $caseSkipped) -gt 1) {
                throw "$Label testcase has multiple contradictory outcomes."
            }
            $actualFailures += $caseFailures
            $actualErrors += $caseErrors
            $actualSkipped += $caseSkipped
        }
        if ($actualFailures -ne $declared.failures -or
            $actualErrors -ne $declared.errors -or
            $actualSkipped -ne $declared.skipped -or
            @($suite.SelectNodes('.//failure')).Count -ne $actualFailures -or
            @($suite.SelectNodes('.//error')).Count -ne $actualErrors -or
            @($suite.SelectNodes('.//skipped')).Count -ne $actualSkipped) {
            throw "$Label testsuite summary does not match testcase outcome nodes."
        }
        foreach ($key in @('tests', 'failures', 'errors', 'skipped')) {
            $totals[$key] += [int] $declared[$key]
        }
    }
    return $totals
}

function Assert-ReleaseLintXml([string] $Path) {
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = $null
    try {
        $reader = [Xml.XmlReader]::Create($Path, $settings)
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
    } catch {
        throw "Release lint XML is malformed or unreadable: $Path"
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
    }
    $root = $document.DocumentElement
    if ($null -eq $root -or $root.Name -cne 'issues' -or
        $root.NamespaceURI -cne '' -or $root.GetAttribute('format') -notmatch '^[1-9][0-9]*$') {
        throw 'Release lint XML must contain a lint issues root with a valid format.'
    }
    $issues = @($root.SelectNodes('./issue'))
    $elements = @($root.ChildNodes | Where-Object { $_.NodeType -eq [Xml.XmlNodeType]::Element })
    if ($elements.Count -ne $issues.Count -or @($root.SelectNodes('.//issue')).Count -ne $issues.Count) {
        throw 'Release lint XML contains unexpected or nested issue elements.'
    }
    foreach ($issue in $issues) {
        $severity = $issue.GetAttribute('severity')
        if ($severity -cin @('Error', 'Fatal')) {
            throw "Release lint XML contains a blocking $severity issue: $($issue.GetAttribute('id'))"
        }
        # Baseline filtering remains the lint task's responsibility. Warnings
        # and informational findings are valid evidence, not release failures.
        if ($severity -cnotin @('Warning', 'Information', 'Informational', 'Hint', 'Ignore')) {
            throw "Release lint XML contains a missing or unsupported issue severity: $severity"
        }
    }
}

function Assert-ReleaseVerificationEvidenceBundle(
    [string] $EvidenceRoot,
    [string] $ExpectedRevision
) {
    $manifestPath = Join-Path $EvidenceRoot 'release-verification-evidence.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Release verification manifest is missing: $manifestPath"
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schema -cne 'fitness-ledger-release-verification-evidence-v1' -or
        $manifest.status -cne 'PASS' -or
        [string] $manifest.revision -cne $ExpectedRevision -or
        $manifest.cleanSource -ne $true) {
        throw 'Release verification manifest is failed, stale, or malformed.'
    }

    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $validatedFiles = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($entry in @($manifest.files)) {
        $validated = Assert-EvidenceFileEntry $EvidenceRoot $entry $seen 'Release verification evidence' -RequireBytes
        $validatedFiles.Add([string] $validated.path, $validated)
    }
    $excluded = @('release-verification-evidence.json', 'SHA256SUMS.txt')
    $actualRawPaths = @(
        Get-ChildItem -LiteralPath $EvidenceRoot -File -Recurse |
            Where-Object {
                ([IO.Path]::GetRelativePath($EvidenceRoot, $_.FullName) -replace '\\', '/') -notin $excluded
            } |
            Sort-Object FullName |
            ForEach-Object { [IO.Path]::GetRelativePath($EvidenceRoot, $_.FullName) -replace '\\', '/' }
    )
    if ($seen.Count -ne $actualRawPaths.Count -or
        @($actualRawPaths | Where-Object { -not $seen.Contains($_) }).Count -ne 0) {
        throw 'Release verification manifest does not cover exactly every raw result file.'
    }

    $requiredRuns = [ordered]@{
        'jvm-debug-tests' = 'jvm-debug-tests.log'
        'jvm-release-tests' = 'jvm-release-tests.log'
        'lint-release' = 'lint-release.log'
        'dependency-verification-gate' = 'dependency-verification-gate.log'
        'powershell-parser-gate' = 'powershell-parser-gate.log'
    }
    if (@($manifest.runs).Count -ne $requiredRuns.Count) {
        throw 'Release verification manifest must contain exactly the five required runs.'
    }
    foreach ($runName in $requiredRuns.Keys) {
        $matches = @($manifest.runs | Where-Object { [string] $_.name -ceq $runName })
        if ($matches.Count -ne 1) { throw "Release verification run is missing or duplicated: $runName" }
        $run = $matches[0]
        $expectedLog = [string] $requiredRuns[$runName]
        if ([int] $run.exitCode -ne 0 -or [string] $run.log -cne $expectedLog -or
            -not $validatedFiles.ContainsKey($expectedLog) -or
            ([string] $run.logSha256).ToUpperInvariant() -cne $validatedFiles[$expectedLog].sha256) {
            throw "Release verification run is failed or not bound to its exact log: $runName"
        }
        $started = ConvertFrom-EvidenceTimestamp $run.startedUtc "Release verification run '$runName' start"
        $finished = ConvertFrom-EvidenceTimestamp $run.finishedUtc "Release verification run '$runName' finish"
        if ($finished -lt $started) { throw "Release verification run finishes before it starts: $runName" }
    }

    if (-not $validatedFiles.ContainsKey('tool-versions.json')) {
        throw 'Release verification evidence has no bound tool-versions.json.'
    }
    $tools = Get-Content -LiteralPath $validatedFiles['tool-versions.json'].fullPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([int] $tools.java.versionExitCode -ne 0 -or
        [int] $tools.gradle.versionExitCode -ne 0 -or
        ([string] $tools.java.executableSha256).ToUpperInvariant() -notmatch '^[0-9A-F]{64}$' -or
        ([string] $tools.gradle.wrapperSha256).ToUpperInvariant() -notmatch '^[0-9A-F]{64}$' -or
        ([string] $tools.powershell.executableSha256).ToUpperInvariant() -notmatch '^[0-9A-F]{64}$' -or
        @($tools.java.versionOutput).Count -lt 1 -or @($tools.gradle.versionOutput).Count -lt 1) {
        throw 'Release verification tool-version evidence is failed or malformed.'
    }

    $resultNames = @($manifest.jvmResults.PSObject.Properties.Name)
    if ((@($resultNames | Sort-Object) -join ',') -cne 'debug,release') {
        throw 'Release verification must summarize exactly debug and release JVM results.'
    }
    foreach ($variant in @('debug', 'release')) {
        $prefix = "raw-results/jvm-$variant-junit/"
        $junitPaths = @($validatedFiles.Keys | Where-Object {
            $_.StartsWith($prefix, [StringComparison]::Ordinal) -and $_ -match '/TEST-[^/]+\.xml$'
        })
        if ($junitPaths.Count -lt 1) { throw "Release verification has no $variant JVM JUnit XML." }
        $tests = 0
        $failures = 0
        $errors = 0
        $skipped = 0
        foreach ($junitPath in $junitPaths) {
            $junitTotals = Get-JUnitEvidenceTotals $validatedFiles[$junitPath].fullPath "JVM JUnit XML '$junitPath'"
            $tests += [int] $junitTotals.tests
            $failures += [int] $junitTotals.failures
            $errors += [int] $junitTotals.errors
            $skipped += [int] $junitTotals.skipped
        }
        $summary = $manifest.jvmResults.$variant
        if ($tests -ne [int] $summary.tests -or
            $failures -ne [int] $summary.failures -or
            $errors -ne [int] $summary.errors -or
            $skipped -ne [int] $summary.skipped) {
            throw "Release verification $variant JVM summary does not match raw JUnit XML."
        }
        if ($tests -lt 1 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
            throw "Release verification $variant JVM JUnit results are empty or failed."
        }
    }
    $lintXmlPath = 'raw-results/lint/lint-results-release.xml'
    if (-not $validatedFiles.ContainsKey($lintXmlPath)) {
        throw 'Release verification has no bound release lint XML report.'
    }
    Assert-ReleaseLintXml $validatedFiles[$lintXmlPath].fullPath
    [void](Assert-ExactEvidenceChecksumFile $EvidenceRoot 'SHA256SUMS.txt' 'Release verification evidence')
    return $manifest
}

function Assert-InstrumentationEvidenceBundle(
    [string] $EvidenceRoot,
    [string] $ExpectedRevision,
    [string] $ExpectedReleaseApkSha256,
    [long] $ExpectedReleaseApkBytes
) {
    $manifestPath = Join-Path $EvidenceRoot 'instrumentation-evidence.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Instrumentation manifest is missing: $manifestPath"
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $expectedReleaseHash = $ExpectedReleaseApkSha256.ToUpperInvariant()
    $tasks = @($manifest.invocation.tasks | ForEach-Object { [string] $_ })
    if ($manifest.schema -cne 'fitness-ledger-instrumentation-evidence-v1' -or
        $manifest.status -cne 'PASS' -or
        [string] $manifest.source.revision -cne $ExpectedRevision -or
        [string] $manifest.source.revisionAfterRun -cne $ExpectedRevision -or
        $manifest.source.clean -ne $true -or
        $manifest.source.cleanBeforeRun -ne $true -or
        $manifest.source.cleanAfterRun -ne $true -or
        ($tasks -join ',') -cne 'clean,:app:connectedDebugAndroidTest' -or
        $manifest.invocation.androidSerialWasExplicit -ne $true -or
        $manifest.invocation.outputLocationOutsideRepository -ne $true -or
        $manifest.invocation.releaseReferenceOutsideRepository -ne $true -or
        [int] $manifest.invocation.expectedTestCount -lt 1 -or
        [int] $manifest.result.tests -ne [int] $manifest.invocation.expectedTestCount -or
        [int] $manifest.result.failures -ne 0 -or
        [int] $manifest.result.errors -ne 0 -or
        [int] $manifest.result.skipped -ne 0 -or
        ([string] $manifest.releaseArtifactReference.sha256).ToUpperInvariant() -cne $expectedReleaseHash -or
        [long] $manifest.releaseArtifactReference.bytes -ne $ExpectedReleaseApkBytes -or
        [string] $manifest.releaseArtifactReference.fileName -cne 'app-release.apk' -or
        $manifest.releaseArtifactReference.directlyExercisedByThisRun -ne $false) {
        throw 'Instrumentation evidence is failed, stale, overstated, or not bound to the release APK.'
    }

    $started = ConvertFrom-EvidenceTimestamp $manifest.invocation.startedUtc 'Instrumentation invocation start'
    $finished = ConvertFrom-EvidenceTimestamp $manifest.invocation.finishedUtc 'Instrumentation invocation finish'
    if ($finished -lt $started) { throw 'Instrumentation evidence finishes before it starts.' }

    $roles = @($manifest.testedArtifacts | ForEach-Object { [string] $_.role })
    if ($roles.Count -ne 2 -or
        (@($roles | Sort-Object) -join ',') -cne 'debug-app-under-test,debug-instrumentation-test') {
        throw 'Instrumentation evidence must identify exactly the debug app and test APK roles.'
    }

    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    [void](Assert-EvidenceFileEntry $EvidenceRoot $manifest.rawEvidence.gradleLog $seen 'Instrumentation Gradle log' -RequireBytes)
    [void](Assert-EvidenceFileEntry $EvidenceRoot $manifest.rawEvidence.deviceProperties $seen 'Instrumentation device properties' -RequireBytes)
    $expectedArtifactFiles = [ordered]@{
        'debug-app-under-test' = 'app-debug.apk'
        'debug-instrumentation-test' = 'app-debug-androidTest.apk'
    }
    foreach ($artifact in @($manifest.testedArtifacts)) {
        $role = [string] $artifact.role
        $expectedFileName = [string] $expectedArtifactFiles[$role]
        if ([string]::IsNullOrWhiteSpace($expectedFileName) -or
            [string] $artifact.fileName -cne $expectedFileName -or
            [string] $artifact.path -cne "tested-artifacts/$expectedFileName") {
            throw 'Instrumentation tested-artifact identity is malformed.'
        }
        [void](Assert-EvidenceFileEntry $EvidenceRoot $artifact $seen "Instrumentation tested artifact $role" -RequireBytes)
    }
    $junitFiles = [Collections.Generic.List[object]]::new()
    foreach ($entry in @($manifest.rawEvidence.junitXml)) {
        $junitFiles.Add((Assert-EvidenceFileEntry $EvidenceRoot $entry $seen 'Instrumentation JUnit XML' -RequireBytes))
    }
    if ($junitFiles.Count -lt 1) { throw 'Instrumentation evidence has no JUnit XML.' }

    $devicePropertiesPath = Join-Path $EvidenceRoot ([string] $manifest.rawEvidence.deviceProperties.path -replace '/', [IO.Path]::DirectorySeparatorChar)
    $deviceProperties = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($line in Get-Content -LiteralPath $devicePropertiesPath -Encoding UTF8) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed device.properties line: $line" }
        $key = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        if (-not $deviceProperties.TryAdd($key, $value)) { throw "Duplicate device.properties key: $key" }
    }
    $deviceKeys = @('serial', 'state', 'apiLevel', 'androidRelease', 'fingerprint', 'manufacturer', 'model', 'abi')
    if ($deviceProperties.Count -ne $deviceKeys.Count -or
        @($deviceProperties.Keys | Where-Object { $_ -notin $deviceKeys }).Count -ne 0) {
        throw 'device.properties must contain exactly the eight recorded device fields.'
    }
    foreach ($key in $deviceKeys) {
        if (-not $deviceProperties.ContainsKey($key) -or
            [string] $deviceProperties[$key] -cne [string] $manifest.device.$key) {
            throw "Instrumentation device summary does not match device.properties: $key"
        }
    }
    if ($deviceProperties['state'] -cne 'device' -or [int] $deviceProperties['apiLevel'] -lt 28 -or
        [string]::IsNullOrWhiteSpace($deviceProperties['serial']) -or
        [string]::IsNullOrWhiteSpace($deviceProperties['fingerprint']) -or
        [string]::IsNullOrWhiteSpace($deviceProperties['abi'])) {
        throw 'Instrumentation device evidence is not a valid online Android target.'
    }
    if (([string] $manifest.tools.adbSha256).ToUpperInvariant() -notmatch '^[0-9A-F]{64}$' -or
        @($manifest.tools.adbVersion).Count -lt 1 -or
        [string] $manifest.tools.verifiedGradleEntryPoint -cne 'scripts/Invoke-GradleVerified.ps1') {
        throw 'Instrumentation tool identity is malformed.'
    }

    $actualRawPaths = @(
        Get-ChildItem -LiteralPath $EvidenceRoot -File -Recurse |
            Where-Object {
                ([IO.Path]::GetRelativePath($EvidenceRoot, $_.FullName) -replace '\\', '/') -notin @(
                    'instrumentation-evidence.json', 'SHA256SUMS.txt'
                )
            } |
            Sort-Object FullName |
            ForEach-Object { [IO.Path]::GetRelativePath($EvidenceRoot, $_.FullName) -replace '\\', '/' }
    )
    if ($seen.Count -ne $actualRawPaths.Count -or
        @($actualRawPaths | Where-Object { -not $seen.Contains($_) }).Count -ne 0) {
        throw 'Instrumentation manifest does not cover exactly every raw evidence file.'
    }

    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($junitFile in $junitFiles) {
        $junitTotals = Get-JUnitEvidenceTotals $junitFile.fullPath "Instrumentation JUnit XML '$($junitFile.path)'"
        $tests += [int] $junitTotals.tests
        $failures += [int] $junitTotals.failures
        $errors += [int] $junitTotals.errors
        $skipped += [int] $junitTotals.skipped
    }
    if ($tests -ne [int] $manifest.result.tests -or
        $failures -ne [int] $manifest.result.failures -or
        $errors -ne [int] $manifest.result.errors -or
        $skipped -ne [int] $manifest.result.skipped) {
        throw 'Instrumentation JUnit totals do not match the signed PASS summary.'
    }
    [void](Assert-ExactEvidenceChecksumFile $EvidenceRoot 'SHA256SUMS.txt' 'Instrumentation evidence')
    return $manifest
}
