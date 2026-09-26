[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9A-Za-z][0-9A-Za-z._-]*$')]
    [string] $ExpectedVersionName,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int] $ExpectedVersionCode,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int] $ExpectedDatabaseVersion,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedRevision,
    [string] $DeliveryDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
. (Join-Path $PSScriptRoot 'ProcessEnvironment.Common.ps1')

function Read-Policy([string] $Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*[#!]') { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed policy line: $line" }
        $values[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

function Clear-SecretEnvironment([string] $Name) {
    Remove-Item -LiteralPath "Env:$Name" -Force -ErrorAction SilentlyContinue
}

function Write-Utf8([string] $Path, [string] $Value) {
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

$repoRoot = Get-ReleaseRepositoryRoot
$presentReleaseEnvironment = @($script:ReleaseSigningEnvironmentNames | Where-Object {
    -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process'))
})
if ($presentReleaseEnvironment.Count -ne 0) {
    throw 'Delivery evidence finalization requires the local DPAPI signing identity and refuses inherited FITNESS_RELEASE_* secrets.'
}
if ([string]::IsNullOrWhiteSpace($DeliveryDirectory)) { $DeliveryDirectory = Join-Path $repoRoot '交付' }
$DeliveryDirectory = [IO.Path]::GetFullPath($DeliveryDirectory)
$tag = "v$ExpectedVersionName"
$evidenceDirectory = Join-Path $DeliveryDirectory "$tag-release-evidence"
if (-not (Test-Path -LiteralPath $evidenceDirectory -PathType Container)) {
    throw "Release evidence directory is missing: $evidenceDirectory"
}
$deliveryDocumentsDirectory = Join-Path $evidenceDirectory 'delivery-documents'
if (-not (Test-Path -LiteralPath $deliveryDocumentsDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $deliveryDocumentsDirectory | Out-Null
}
$deliveryDocumentNames = @('安装说明.md', '验收报告.md')
foreach ($documentName in $deliveryDocumentNames) {
    $sourceDocument = Join-Path $DeliveryDirectory $documentName
    $signedDocumentCopy = Join-Path $deliveryDocumentsDirectory $documentName
    if (Test-Path -LiteralPath $signedDocumentCopy) {
        Assert-FilesByteIdentical $sourceDocument $signedDocumentCopy "Delivery document $documentName"
    } else {
        Copy-Item -LiteralPath $sourceDocument -Destination $signedDocumentCopy
    }
}
$actualDeliveryDocuments = @(Get-ChildItem -LiteralPath $deliveryDocumentsDirectory -File)
if ($actualDeliveryDocuments.Count -ne $deliveryDocumentNames.Count -or
    @($actualDeliveryDocuments.Name | Where-Object { $_ -notin $deliveryDocumentNames }).Count -ne 0) {
    throw 'Signed delivery-documents directory must contain exactly 安装说明.md and 验收报告.md.'
}
$verificationDirectory = Join-Path $evidenceDirectory 'release-verification'
$verificationManifestPath = Join-Path $verificationDirectory 'release-verification-evidence.json'
$instrumentationManifestPath = Join-Path $evidenceDirectory 'instrumentation\instrumentation-evidence.json'
foreach ($required in @(
    $verificationManifestPath,
    (Join-Path $verificationDirectory 'jvm-debug-tests.log'),
    (Join-Path $verificationDirectory 'jvm-release-tests.log'),
    (Join-Path $verificationDirectory 'lint-release.log'),
    (Join-Path $verificationDirectory 'dependency-verification-gate.log'),
    (Join-Path $verificationDirectory 'powershell-parser-gate.log'),
    (Join-Path $verificationDirectory 'tool-versions.json'),
    $instrumentationManifestPath
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required raw release verification evidence is missing: $required"
    }
}
$instrumentationManifest = Get-Content -LiteralPath $instrumentationManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$evidenceApkPath = Join-Path $evidenceDirectory 'app-release.apk'
$evidenceApkHash = (Get-FileHash -LiteralPath $evidenceApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
$evidenceApkBytes = (Get-Item -LiteralPath $evidenceApkPath).Length
$instrumentationDirectory = Split-Path -Parent $instrumentationManifestPath
$instrumentationManifest = Assert-InstrumentationEvidenceBundle `
    $instrumentationDirectory `
    $ExpectedRevision `
    $evidenceApkHash `
    $evidenceApkBytes
$verificationManifest = Assert-ReleaseVerificationEvidenceBundle $verificationDirectory $ExpectedRevision
$toolVersions = Get-Content -LiteralPath (Join-Path $verificationDirectory 'tool-versions.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ([int] $toolVersions.java.versionExitCode -ne 0 -or
    [int] $toolVersions.gradle.versionExitCode -ne 0 -or
    [string] $toolVersions.java.executableSha256 -notmatch '^[0-9A-F]{64}$' -or
    [string] $toolVersions.gradle.wrapperSha256 -notmatch '^[0-9A-F]{64}$' -or
    [string] $toolVersions.powershell.executableSha256 -notmatch '^[0-9A-F]{64}$') {
    throw 'Release verification tool-version evidence has a failed exit or malformed executable hash.'
}
$requiredRuns = @('jvm-debug-tests', 'jvm-release-tests', 'lint-release', 'dependency-verification-gate', 'powershell-parser-gate')
foreach ($name in $requiredRuns) {
    $matches = @($verificationManifest.runs | Where-Object { $_.name -ceq $name })
    if ($matches.Count -ne 1 -or [int] $matches[0].exitCode -ne 0) {
        throw "Required release verification run '$name' did not record one successful exit."
    }
    $runLogPath = Join-Path $verificationDirectory ([string] $matches[0].log)
    if (-not (Test-Path -LiteralPath $runLogPath -PathType Leaf) -or
        ([string] $matches[0].logSha256).ToUpperInvariant() -cne
            (Get-FileHash -LiteralPath $runLogPath -Algorithm SHA256).Hash.ToUpperInvariant()) {
        throw "Release verification run '$name' raw log hash does not match its manifest."
    }
}
$verificationPrefix = [IO.Path]::GetFullPath($verificationDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$verificationPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($entry in @($verificationManifest.files)) {
    $relative = [string] $entry.path
    if ([string]::IsNullOrWhiteSpace($relative) -or $relative.Contains('\') -or
        [IO.Path]::IsPathRooted($relative) -or ($relative -split '/') -contains '..' -or
        -not $verificationPaths.Add($relative)) {
        throw "Unsafe or duplicate raw release verification path: $relative"
    }
    $fullPath = [IO.Path]::GetFullPath((Join-Path $verificationDirectory ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)))
    if (-not $fullPath.StartsWith($verificationPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Raw release verification file is missing or escapes its evidence root: $relative"
    }
    $file = Get-Item -LiteralPath $fullPath
    $hash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ([long] $entry.bytes -ne $file.Length -or ([string] $entry.sha256).ToUpperInvariant() -cne $hash) {
        throw "Raw release verification file hash/length mismatch: $relative"
    }
}

$deliveryCaptureDirectory = Join-Path $evidenceDirectory 'delivery-gate'
if (-not (Test-Path -LiteralPath $deliveryCaptureDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $deliveryCaptureDirectory | Out-Null
}
$deliveryTranscript = Join-Path $deliveryCaptureDirectory 'delivery-gate-pre-signature.log'
$deliveryResult = Join-Path $deliveryCaptureDirectory 'delivery-gate-pre-signature.json'
$hasDeliveryTranscript = Test-Path -LiteralPath $deliveryTranscript -PathType Leaf
$hasDeliveryResult = Test-Path -LiteralPath $deliveryResult -PathType Leaf
if ($hasDeliveryTranscript -xor $hasDeliveryResult) {
    throw 'Delivery-gate capture is partial; preserve it for diagnosis and explicitly remove both files before retrying.'
}
if ($hasDeliveryTranscript) {
    $existingDeliveryResult = Get-Content -LiteralPath $deliveryResult -Raw -Encoding UTF8 | ConvertFrom-Json
    $existingTranscriptHash = (Get-FileHash -LiteralPath $deliveryTranscript -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($existingDeliveryResult.schema -cne 'fitness-ledger-delivery-gate-capture-v1' -or
        $existingDeliveryResult.mode -cne 'all-independent-checks-before-detached-signature' -or
        [int] $existingDeliveryResult.exitCode -ne 0 -or
        [string] $existingDeliveryResult.revision -cne $ExpectedRevision -or
        [string] $existingDeliveryResult.transcript -cne 'delivery-gate-pre-signature.log' -or
        ([string] $existingDeliveryResult.transcriptSha256).ToUpperInvariant() -cne $existingTranscriptHash) {
        throw 'Existing delivery-gate capture is failed, stale, or modified; preserve it for diagnosis and explicitly remove both files before retrying.'
    }
    $deliveryStartedAt = ConvertFrom-EvidenceTimestamp $existingDeliveryResult.startedUtc 'Delivery-gate capture start'
    $deliveryFinishedAt = ConvertFrom-EvidenceTimestamp $existingDeliveryResult.finishedUtc 'Delivery-gate capture finish'
    if ($deliveryFinishedAt -lt $deliveryStartedAt) {
        throw 'Existing delivery-gate capture finishes before it starts.'
    }
} else {
    $deliveryStarted = [DateTime]::UtcNow
    $deliveryOutput = @(& (Join-Path $PSScriptRoot 'Test-DeliveryMetadata.ps1') `
        -ExpectedVersionName $ExpectedVersionName `
        -ExpectedVersionCode $ExpectedVersionCode `
        -ExpectedDatabaseVersion $ExpectedDatabaseVersion `
        -ExpectedRevision $ExpectedRevision `
        -DeliveryDirectory $DeliveryDirectory `
        -PreSignatureCapture 2>&1 | ForEach-Object { [string] $_ })
    $deliveryExitCode = $LASTEXITCODE
    $deliveryFinished = [DateTime]::UtcNow
    Write-Utf8 $deliveryTranscript (($deliveryOutput -join [Environment]::NewLine) + [Environment]::NewLine)
    $deliveryResultValue = [ordered]@{
        schema = 'fitness-ledger-delivery-gate-capture-v1'
        mode = 'all-independent-checks-before-detached-signature'
        exitCode = $deliveryExitCode
        revision = $ExpectedRevision
        startedUtc = $deliveryStarted.ToString('o')
        finishedUtc = $deliveryFinished.ToString('o')
        transcript = 'delivery-gate-pre-signature.log'
        transcriptSha256 = (Get-FileHash -LiteralPath $deliveryTranscript -Algorithm SHA256).Hash.ToUpperInvariant()
    }
    Write-Utf8 $deliveryResult (($deliveryResultValue | ConvertTo-Json -Depth 8) + "`n")
    if ($deliveryExitCode -ne 0) { throw "Pre-signature delivery gate failed; transcript: $deliveryTranscript" }
}

$manifestPath = Join-Path $evidenceDirectory 'app-release.apk.provenance-manifest.json'
$signaturePath = Join-Path $evidenceDirectory 'app-release.apk.provenance-signature.bin'
$certificatePath = Join-Path $evidenceDirectory 'app-release.apk.provenance-certificate.der'
$checksumPath = Join-Path $evidenceDirectory 'app-release.apk.evidence.sha256'
foreach ($path in @($manifestPath, $signaturePath, $certificatePath)) {
    if (Test-Path -LiteralPath $path) { throw "Refusing to replace cryptographic evidence: $path" }
}

$excludedNames = @(
    [IO.Path]::GetFileName($manifestPath),
    [IO.Path]::GetFileName($signaturePath),
    [IO.Path]::GetFileName($certificatePath),
    [IO.Path]::GetFileName($checksumPath)
)
$coveredFiles = @(
    Get-ChildItem -LiteralPath $evidenceDirectory -File -Recurse |
        Where-Object { $_.DirectoryName -ne $evidenceDirectory -or $_.Name -notin $excludedNames } |
        Sort-Object FullName
)
if ($coveredFiles.Count -lt 10) { throw 'Release evidence is unexpectedly incomplete.' }
$policy = Read-Policy (Join-Path $repoRoot 'release-policy.properties')
$tagObject = (& git -C $repoRoot rev-parse "refs/tags/$tag").Trim()
$manifest = [ordered]@{
    schema = 'fitness-ledger-signed-provenance-manifest-v1'
    signatureScheme = [string] $policy.provenanceSignatureScheme
    release = [ordered]@{
        versionName = $ExpectedVersionName
        versionCode = $ExpectedVersionCode
        databaseVersion = $ExpectedDatabaseVersion
        revision = $ExpectedRevision
        tag = $tag
        tagObject = $tagObject
        gitTagSigningSshKeyFingerprint = [string] $policy.gitTagSigningSshKeyFingerprint
        apkSigningCertificateSha256 = [string] $policy.signingCertificateSha256
    }
    files = @($coveredFiles | ForEach-Object {
        [ordered]@{
            path = ([IO.Path]::GetRelativePath($evidenceDirectory, $_.FullName) -replace '\\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
        }
    })
    trustBoundary = 'Repository-pinned local APK certificate and SSH tag public key; no public transparency log or third-party timestamp.'
}
Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 20) + "`n")

$payload = $null
$storePassword = $null
$keyPassword = $null
$storeEnv = 'FITNESS_APKSIGNER_STORE_PASSWORD_TEMP'
$keyEnv = 'FITNESS_APKSIGNER_KEY_PASSWORD_TEMP'
$storeEnvBefore = Get-FitnessProcessEnvironmentVariableState $storeEnv
$keyEnvBefore = Get-FitnessProcessEnvironmentVariableState $keyEnv
try {
    $payload = Unprotect-ReleaseSigningPayload (Join-Path $repoRoot '.signing\release-signing.dpapi.json')
    $keystore = Join-Path (Join-Path $repoRoot '.signing') ([string] $payload.storeFile)
    $storePassword = [string] $payload.storePassword
    $keyPassword = [string] $payload.keyPassword
    [Environment]::SetEnvironmentVariable($storeEnv, $storePassword, 'Process')
    [Environment]::SetEnvironmentVariable($keyEnv, $keyPassword, 'Process')
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    $helper = Join-Path $PSScriptRoot 'ReleaseProvenanceSignature.java'
    $signOutput = @(& $java $helper sign $keystore ([string] $payload.keyAlias) $manifestPath $signaturePath $certificatePath 2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0) { throw "Detached provenance signing failed: $($signOutput -join [Environment]::NewLine)" }
} finally {
    Clear-SecretEnvironment $storeEnv
    Clear-SecretEnvironment $keyEnv
    Restore-FitnessProcessEnvironmentVariable $storeEnvBefore
    Restore-FitnessProcessEnvironmentVariable $keyEnvBefore
    $payload = $null
    $storePassword = $null
    $keyPassword = $null
}

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$verifyOutput = @(& $java (Join-Path $PSScriptRoot 'ReleaseProvenanceSignature.java') verify `
    $manifestPath $signaturePath $certificatePath ([string] $policy.signingCertificateSha256) 2>&1 | ForEach-Object { [string] $_ })
if ($LASTEXITCODE -ne 0) { throw "Detached provenance verification failed: $($verifyOutput -join [Environment]::NewLine)" }

$allEvidenceFiles = @(
    Get-ChildItem -LiteralPath $evidenceDirectory -File -Recurse |
        Where-Object { $_.FullName -cne $checksumPath } |
        Sort-Object FullName
)
$checksumLines = @($allEvidenceFiles | ForEach-Object {
    "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant())  $([IO.Path]::GetRelativePath($evidenceDirectory, $_.FullName) -replace '\\', '/')"
})
Write-Utf8 $checksumPath (($checksumLines -join "`n") + "`n")

$finalOutput = @(& (Join-Path $PSScriptRoot 'Test-DeliveryMetadata.ps1') `
    -ExpectedVersionName $ExpectedVersionName `
    -ExpectedVersionCode $ExpectedVersionCode `
    -ExpectedDatabaseVersion $ExpectedDatabaseVersion `
    -ExpectedRevision $ExpectedRevision `
    -DeliveryDirectory $DeliveryDirectory 2>&1 | ForEach-Object { [string] $_ })
if ($LASTEXITCODE -ne 0) { throw "Final signed delivery gate failed: $($finalOutput -join [Environment]::NewLine)" }
$finalOutput | ForEach-Object { Write-Host $_ }
Write-Host 'PASS: release evidence is complete and detached-signature verified.'
Write-Host "  manifest=$manifestPath"
Write-Host "  signature=$signaturePath"
exit 0
