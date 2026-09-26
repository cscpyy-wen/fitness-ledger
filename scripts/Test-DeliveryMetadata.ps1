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

    [string] $DeliveryDirectory = '',

    [switch] $PreSignatureCapture
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')

function Invoke-GitText([string[]] $Arguments) {
    $output = @(& git -C $script:RepoRoot @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return @($output | ForEach-Object { ([string] $_).Trim() } | Where-Object { $_ -ne '' })
}

function Read-KeyValueText([string] $Text, [string] $Label) {
    $values = @{}
    foreach ($line in ($Text -split "\r?\n")) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*[#!]') { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed key/value line in ${Label}: $line" }
        $name = $line.Substring(0, $separator).Trim()
        if ($values.ContainsKey($name)) { throw "Duplicate key '$name' in $Label" }
        $values[$name] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

function Require-Value([hashtable] $Values, [string] $Name, [string] $Label = 'release policy') {
    if (-not $Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace([string] $Values[$Name])) {
        throw "Required value '$Name' is missing from $Label."
    }
    return [string] $Values[$Name]
}

function Assert-FileSha256([string] $Path, [string] $Expected, [string] $Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is missing: $Path" }
    $normalizedExpected = ($Expected -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($normalizedExpected -notmatch '^[0-9A-F]{64}$') { throw "Pinned SHA-256 for $Label is malformed." }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actual -cne $normalizedExpected) {
        throw "$Label SHA-256 '$actual' does not match tagged release policy '$normalizedExpected'."
    }
    return $actual
}

function Find-AndroidSdkRoot {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $propertiesPath = Join-Path $script:RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $propertiesPath -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $propertiesPath -Encoding UTF8 |
            Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($null -ne $sdkLine) {
            $candidate = $sdkLine.Substring('sdk.dir='.Length)
            $candidate = $candidate -replace '\\:', ':'
            $candidate = $candidate -replace '\\\\', '\'
            if (Test-Path -LiteralPath $candidate -PathType Container) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
    }
    throw 'Android SDK not found. Set ANDROID_SDK_ROOT or provide local.properties sdk.dir.'
}

function Read-ApkProvenance([string] $ApkPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -ceq 'assets/build-provenance.properties' })
        if ($entries.Count -ne 1) {
            throw "APK must contain exactly one assets/build-provenance.properties entry; found $($entries.Count)."
        }
        $reader = [IO.StreamReader]::new($entries[0].Open(), [Text.Encoding]::UTF8, $true)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        $archive.Dispose()
    }
    return Read-KeyValueText $text 'APK build provenance'
}

function Get-GitBlobInfo([string] $Revision, [string] $RepoRelativePath) {
    if ($RepoRelativePath -notmatch '^[A-Za-z0-9._/-]+$') { throw "Unsafe material path: $RepoRelativePath" }
    $objectId = (Invoke-GitText @('rev-parse', "${Revision}:$RepoRelativePath") | Select-Object -First 1)
    if ($objectId -notmatch '^[0-9a-f]{40}$') { throw "Unexpected Git object id for ${Revision}:$RepoRelativePath" }
    if ((Invoke-GitText @('cat-file', '-t', $objectId) | Select-Object -First 1) -cne 'blob') {
        throw "Material is not a Git blob: $RepoRelativePath"
    }

    $gitExecutable = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $gitExecutable
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @('-C', $script:RepoRoot, 'cat-file', 'blob', $objectId)) {
        [void] $startInfo.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Could not start git cat-file.' }
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = [Convert]::ToHexString($sha.ComputeHash($process.StandardOutput.BaseStream)).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    $process.WaitForExit()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "git cat-file failed for ${Revision}:${RepoRelativePath}: $stderr" }
    $process.Dispose()
    return [ordered]@{ objectId = $objectId; sha256 = $digest }
}

function Assert-Contains([string] $Text, [string] $Expected, [string] $Label) {
    if (-not $Text.Contains($Expected, [StringComparison]::Ordinal)) {
        throw "$Label is missing exact current value: $Expected"
    }
}

$RepoRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
$gitRoot = (Invoke-GitText @('rev-parse', '--show-toplevel') | Select-Object -First 1)
if (-not ([IO.Path]::GetFullPath($gitRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)).Equals($RepoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Script repository identity mismatch: $gitRoot != $RepoRoot"
}
if ([string]::IsNullOrWhiteSpace($DeliveryDirectory)) { $DeliveryDirectory = Join-Path $RepoRoot '交付' }
$DeliveryDirectory = (Resolve-Path -LiteralPath $DeliveryDirectory).Path

$expectedTag = "v$ExpectedVersionName"
$apkName = "健身减脂-$expectedTag.apk"
$apkPath = Join-Path $DeliveryDirectory $apkName
$archiveDirectory = Join-Path $DeliveryDirectory '历史版本'
$evidenceDirectory = Join-Path $DeliveryDirectory "$expectedTag-release-evidence"
$attestationPath = Join-Path $evidenceDirectory 'app-release.apk.attestation.json'
$evidenceApkPath = Join-Path $evidenceDirectory 'app-release.apk'
$evidenceApkShaPath = Join-Path $evidenceDirectory 'app-release.apk.sha256'
$sbomPath = Join-Path $evidenceDirectory 'app-release.apk.sbom.cdx.json'
$evidenceChecksumsPath = Join-Path $evidenceDirectory 'app-release.apk.evidence.sha256'
$provenanceManifestPath = Join-Path $evidenceDirectory 'app-release.apk.provenance-manifest.json'
$provenanceSignaturePath = Join-Path $evidenceDirectory 'app-release.apk.provenance-signature.bin'
$provenanceCertificatePath = Join-Path $evidenceDirectory 'app-release.apk.provenance-certificate.der'
$installationPath = Join-Path $DeliveryDirectory '安装说明.md'
$acceptancePath = Join-Path $DeliveryDirectory '验收报告.md'
$signedInstallationCopy = Join-Path $evidenceDirectory 'delivery-documents\安装说明.md'
$signedAcceptanceCopy = Join-Path $evidenceDirectory 'delivery-documents\验收报告.md'
$checksumsPath = Join-Path $DeliveryDirectory 'SHA256SUMS.txt'

$topLevelApks = @(Get-ChildItem -LiteralPath $DeliveryDirectory -File -Filter '*.apk')
if ($topLevelApks.Count -ne 1 -or $topLevelApks[0].Name -cne $apkName) {
    throw "Delivery root must expose exactly one current install APK '$apkName'. Found: $($topLevelApks.Name -join ', '). Move historical APKs into '$archiveDirectory'."
}
$topLevelEvidenceDirectories = @(
    Get-ChildItem -LiteralPath $DeliveryDirectory -Directory |
        Where-Object { $_.Name -match '^v.+-release-evidence$' }
)
if ($topLevelEvidenceDirectories.Count -ne 1 -or $topLevelEvidenceDirectories[0].Name -cne "$expectedTag-release-evidence") {
    throw "Delivery root must retain only current release evidence '$expectedTag-release-evidence'. Move old evidence directories into '$archiveDirectory'."
}
$allowedApkPaths = @(
    $apkPath,
    $evidenceApkPath,
    (Join-Path $evidenceDirectory 'instrumentation\tested-artifacts\app-debug.apk'),
    (Join-Path $evidenceDirectory 'instrumentation\tested-artifacts\app-debug-androidTest.apk')
)
[void](Assert-DeliveryApkPlacement $DeliveryDirectory $archiveDirectory $allowedApkPaths)

foreach ($requiredPath in @(
    $apkPath, $attestationPath, $evidenceApkPath, $evidenceApkShaPath, $sbomPath,
    $evidenceChecksumsPath, $installationPath, $acceptancePath,
    $signedInstallationCopy, $signedAcceptanceCopy, $checksumsPath
)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "Required delivery file is missing: $requiredPath" }
}
[void](Assert-FilesByteIdentical $installationPath $signedInstallationCopy 'Signed installation document')
[void](Assert-FilesByteIdentical $acceptancePath $signedAcceptanceCopy 'Signed acceptance document')
if (-not $PreSignatureCapture) {
    foreach ($requiredPath in @($provenanceManifestPath, $provenanceSignaturePath, $provenanceCertificatePath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "Required signed provenance file is missing: $requiredPath"
        }
    }
}

$tagObjectType = (Invoke-GitText @('cat-file', '-t', "refs/tags/$expectedTag") | Select-Object -First 1)
if ($tagObjectType -cne 'tag') { throw "Release tag '$expectedTag' is not an annotated tag object." }
$tagObjectSha = (Invoke-GitText @('rev-parse', "refs/tags/$expectedTag") | Select-Object -First 1)
if ($tagObjectSha -notmatch '^[0-9a-f]{40}$') { throw "Unexpected annotated tag object id: $tagObjectSha" }
$tagCommit = (Invoke-GitText @('rev-parse', "$expectedTag`^{commit}") | Select-Object -First 1)
if ($tagCommit -cne $ExpectedRevision) {
    throw "Annotated tag '$expectedTag' peels to '$tagCommit', not explicitly expected revision '$ExpectedRevision'."
}

$sourceMaterialNames = @(
    'release-policy.properties',
    'gradle/verification-metadata.xml',
    'gradle/wrapper/gradle-wrapper.properties',
    'gradlew.bat',
    'scripts/Invoke-GradleVerified.ps1',
    'scripts/GradleDistribution.Common.ps1',
    'scripts/ProcessEnvironment.Common.ps1',
    'scripts/Publish-TaggedRelease.ps1',
    'scripts/Resolve-GradleAsciiWorkspace.ps1',
    'scripts/Test-DeliveryMetadata.ps1',
    'scripts/Test-ReleaseDependencyVerificationGate.ps1'
    'scripts/ReleaseSigning.Common.ps1'
    'scripts/ReleaseProvenanceSignature.java'
    'scripts/Invoke-ReleaseVerificationEvidence.ps1'
    'scripts/Invoke-InstrumentationWithEvidence.ps1'
    'scripts/Test-ReleaseEvidenceIntegrity.ps1'
    'scripts/Test-GradleDistributionSeeding.ps1'
    'scripts/Test-ProcessEnvironmentRestoration.ps1'
    'scripts/Test-PowerShellParser.ps1'
    'scripts/New-SignedReleaseTag.ps1'
    'scripts/Finalize-ReleaseEvidence.ps1'
    'release-tag-allowed-signers'
)
$policyRelativePath = 'release-policy.properties'
$policyAtRevision = (Invoke-GitText @('show', "${ExpectedRevision}:$policyRelativePath")) -join "`n"
$policy = Read-KeyValueText $policyAtRevision "tagged $policyRelativePath"
$wrapperAtRevision = (Invoke-GitText @('show', "${ExpectedRevision}:gradle/wrapper/gradle-wrapper.properties")) -join "`n"
$wrapperPolicy = Read-KeyValueText $wrapperAtRevision 'tagged gradle/wrapper/gradle-wrapper.properties'
$expectedGradleDistributionSha256 = (Require-Value $wrapperPolicy 'distributionSha256Sum').ToUpperInvariant()
if ($expectedGradleDistributionSha256 -notmatch '^[0-9A-F]{64}$') { throw 'Tagged Gradle distribution SHA-256 is malformed.' }
if ((Require-Value $policy 'policyFormat') -cne 'fitness-ledger-release-policy-v1') { throw 'Unsupported release policy format.' }
$expectedApplicationId = Require-Value $policy 'applicationId'
$expectedMinSdk = [int](Require-Value $policy 'minSdk')
$expectedTargetSdk = [int](Require-Value $policy 'targetSdk')
if ($expectedMinSdk -lt 1 -or $expectedTargetSdk -lt $expectedMinSdk) { throw 'Tagged SDK policy values are invalid.' }
$expectedCertificate = ((Require-Value $policy 'signingCertificateSha256') -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
if ($expectedCertificate -notmatch '^[0-9A-F]{64}$') { throw 'Tagged release certificate SHA-256 is malformed.' }
if ((Require-Value $policy 'releaseTagPrefix') -cne 'v') { throw 'Only canonical v-prefixed release tags are supported.' }
if ((Require-Value $policy 'releaseArtifactSigningMode') -cne 'external-apksigner') { throw 'Tagged policy does not require external apksigner.' }
if ((Require-Value $policy 'releaseSignatureSchemes') -cne 'v2-only') { throw 'Tagged policy does not require v2-only APK signing.' }
if ((Require-Value $policy 'materialHashMode') -cne 'git-blob-bytes-sha256') { throw 'Tagged policy does not pin source materials to Git blob bytes.' }
$releaseAbis = @((Require-Value $policy 'releaseAbis') -split ',')
$requiredNativeLibraries = @((Require-Value $policy 'requiredNativeLibraries') -split ',')
if (($releaseAbis -join ',') -cne 'arm64-v8a,armeabi-v7a,x86_64') { throw 'Tagged release ABI policy is invalid.' }
if ((Require-Value $policy 'provenanceSignatureScheme') -cne 'jca-detached-sha256') { throw 'Tagged policy does not require detached provenance signatures.' }
$tagSignature = Assert-ReleaseTagSignature `
    $RepoRoot `
    $expectedTag `
    (Require-Value $policy 'gitTagSigningPrincipal') `
    (Require-Value $policy 'gitTagSigningSshKeyFingerprint')

foreach ($materialName in $sourceMaterialNames) {
    & git -C $RepoRoot diff --no-ext-diff --quiet -- $materialName
    if ($LASTEXITCODE -ne 0) { throw "Working-tree critical release material differs from HEAD: $materialName" }
    & git -C $RepoRoot diff --cached --no-ext-diff --quiet -- $materialName
    if ($LASTEXITCODE -ne 0) { throw "Index critical release material differs from HEAD: $materialName" }
    $headBlob = (Invoke-GitText @('rev-parse', "HEAD:$materialName") | Select-Object -First 1)
    $releaseBlob = (Invoke-GitText @('rev-parse', "${ExpectedRevision}:$materialName") | Select-Object -First 1)
    if ($headBlob -cne $releaseBlob) { throw "Current critical release material is not the tagged Git blob: $materialName" }
}

$buildToolsVersion = Require-Value $policy 'androidBuildToolsVersion'
$sdkRoot = Find-AndroidSdkRoot
$buildTools = Join-Path (Join-Path $sdkRoot 'build-tools') $buildToolsVersion
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$apksignerJar = Join-Path $buildTools 'lib\apksigner.jar'
$zipalign = Join-Path $buildTools 'zipalign.exe'
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME is required for pinned apksigner verification.' }
$javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
$aapt2Sha256 = Assert-FileSha256 $aapt2 (Require-Value $policy 'androidAapt2Sha256') 'Android aapt2.exe'
$apksignerJarSha256 = Assert-FileSha256 $apksignerJar (Require-Value $policy 'androidApkSignerJarSha256') 'Android apksigner.jar'
$zipalignSha256 = Assert-FileSha256 $zipalign (Require-Value $policy 'androidZipAlignSha256') 'Android zipalign.exe'
$javaExecutableSha256 = Assert-FileSha256 $javaExecutable (Require-Value $policy 'javaExecutableSha256') 'JDK java.exe'

$apk = Get-Item -LiteralPath $apkPath
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToUpperInvariant()
$evidenceHash = (Get-FileHash -LiteralPath $evidenceApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
if ($evidenceHash -cne $apkHash) { throw "Top-level APK and release-evidence APK differ: $apkHash != $evidenceHash" }
[void](Assert-InstrumentationEvidenceBundle `
    (Join-Path $evidenceDirectory 'instrumentation') `
    $ExpectedRevision `
    $apkHash `
    $apk.Length)
[void](Assert-ReleaseVerificationEvidenceBundle `
    (Join-Path $evidenceDirectory 'release-verification') `
    $ExpectedRevision)

$badging = @(& $aapt2 dump badging $apkPath 2>&1 | ForEach-Object { [string] $_ })
if ($LASTEXITCODE -ne 0) { throw "aapt2 dump badging failed: $($badging -join [Environment]::NewLine)" }
$packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ($null -eq $packageLine -or $packageLine -notmatch "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'") {
    throw 'Could not parse package/version from the actual APK.'
}
$actualApplicationId = $Matches[1]
$actualVersionCode = $Matches[2]
$actualVersionName = $Matches[3]
$minSdkLine = $badging | Where-Object { $_ -match '^minSdkVersion:' } | Select-Object -First 1
$targetSdkLine = $badging | Where-Object { $_ -match '^targetSdkVersion:' } | Select-Object -First 1
if ($null -eq $minSdkLine -or $minSdkLine -notmatch "^minSdkVersion:'(\d+)'$") { throw 'Could not parse minSdk from the actual APK.' }
$actualMinSdk = [int] $Matches[1]
if ($null -eq $targetSdkLine -or $targetSdkLine -notmatch "^targetSdkVersion:'(\d+)'$") { throw 'Could not parse targetSdk from the actual APK.' }
$actualTargetSdk = [int] $Matches[1]
if ($actualApplicationId -cne $expectedApplicationId) { throw "APK package '$actualApplicationId' does not match tagged policy '$expectedApplicationId'." }
if ($actualVersionName -cne $ExpectedVersionName) { throw "APK versionName '$actualVersionName' does not match '$ExpectedVersionName'." }
if ($actualVersionCode -cne [string] $ExpectedVersionCode) { throw "APK versionCode '$actualVersionCode' does not match '$ExpectedVersionCode'." }
if ($actualMinSdk -ne $expectedMinSdk) { throw "APK minSdk '$actualMinSdk' does not match tagged policy '$expectedMinSdk'." }
if ($actualTargetSdk -ne $expectedTargetSdk) { throw "APK targetSdk '$actualTargetSdk' does not match tagged policy '$expectedTargetSdk'." }

$signatureOutput = @(& $javaExecutable -jar $apksignerJar verify -Werr --verbose --print-certs $apkPath 2>&1 | ForEach-Object { [string] $_ })
if ($LASTEXITCODE -ne 0) { throw "apksigner verification failed: $($signatureOutput -join [Environment]::NewLine)" }
foreach ($requiredLine in @(
    'Verifies',
    'Verified using v1 scheme (JAR signing): false',
    'Verified using v2 scheme (APK Signature Scheme v2): true',
    'Verified using v3 scheme (APK Signature Scheme v3): false',
    'Verified using v3.1 scheme (APK Signature Scheme v3.1): false',
    'Verified using v4 scheme (APK Signature Scheme v4): false',
    'Number of signers: 1'
)) {
    if (-not ($signatureOutput -ccontains $requiredLine)) { throw "Actual APK signature output is missing exact line: $requiredLine" }
}
$certificateLine = $signatureOutput | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
if ($null -eq $certificateLine -or $certificateLine -notmatch '^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$') {
    throw 'Could not parse the actual APK signer certificate SHA-256.'
}
$actualCertificate = $Matches[1].ToUpperInvariant()
if ($actualCertificate -cne $expectedCertificate) { throw "APK certificate '$actualCertificate' does not match tagged policy '$expectedCertificate'." }
[void](Assert-ReleaseApkNativeLibraries $apkPath $releaseAbis $requiredNativeLibraries)

$alignmentOutput = @(& $zipalign -c -P 16 -v 4 $apkPath 2>&1 | ForEach-Object { [string] $_ })
if ($LASTEXITCODE -ne 0 -or -not ($alignmentOutput -ccontains 'Verification successful')) {
    throw "16 KiB zipalign verification failed: $($alignmentOutput -join [Environment]::NewLine)"
}

$provenance = Read-ApkProvenance $apkPath
foreach ($required in @('revision', 'versionName', 'versionCode')) { [void](Require-Value $provenance $required 'APK build provenance') }
if ($provenance['revision'] -cne $ExpectedRevision) { throw "APK provenance revision '$($provenance['revision'])' does not match '$ExpectedRevision'." }
if ($provenance['versionName'] -cne $ExpectedVersionName) { throw 'APK provenance versionName does not match the actual/expected version.' }
if ($provenance['versionCode'] -cne [string] $ExpectedVersionCode) { throw 'APK provenance versionCode does not match the actual/expected version.' }

# Attestation is supplemental: every security fact above was independently
# derived from the APK, Git objects and policy-pinned binaries first.
$attestation = Get-Content -LiteralPath $attestationPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (@($attestation.subject).Count -ne 1) { throw 'Attestation must contain exactly one subject.' }
$release = $attestation.predicate.release
if ([string] $release.applicationId -cne $actualApplicationId -or
    [string] $release.versionName -cne $actualVersionName -or
    [string] $release.versionCode -cne $actualVersionCode -or
    [string] $release.gitTag -cne $expectedTag -or
    [string] $release.gitCommit -cne $ExpectedRevision -or
    [string] $release.gitTagObject -cne $tagObjectSha -or
    [string] $release.gitTagSigningPrincipal -cne $tagSignature.principal -or
    [string] $release.gitTagSigningSshKeyFingerprint -cne $tagSignature.fingerprint -or
    ([string] $release.signingCertificateSha256).ToUpperInvariant() -cne $actualCertificate) {
    throw 'Attestation release fields do not match independently verified APK/Git facts.'
}
if ($attestation.predicate.trustBoundary.externallySigned -ne $false -or
    $attestation.predicate.trustBoundary.attestationCryptographicallySigned -ne $true -or
    $attestation.predicate.trustBoundary.gitTagSignatureVerified -ne $true) {
    throw 'Attestation does not require both pinned tag and detached provenance signatures.'
}
if (([string] $attestation.subject[0].digest.sha256).ToUpperInvariant() -cne $apkHash) { throw 'Attestation subject digest does not match the actual APK.' }
if ([string] $attestation.predicate.materialHashMode -cne 'git-blob-bytes-sha256') {
    throw 'Attestation does not declare Git-blob-byte source material hashing.'
}
$attestedBuild = $attestation.predicate.build
if ($attestedBuild.isolatedGradleUserHome -ne $true -or
    $attestedBuild.isolatedProjectCache -ne $true -or
    $attestedBuild.dependencyCachesSeeded -ne $false) {
    throw 'Attestation does not describe an isolated, dependency-cache-empty Gradle build.'
}
$distributionMode = [string] $attestedBuild.gradleDistributionAcquisitionMode
if ($distributionMode -ceq 'verified-local-archive-with-pinned-sha256') {
    if (([string] $attestedBuild.gradleDistributionArchiveSha256).ToUpperInvariant() -cne $expectedGradleDistributionSha256 -or
        [long] $attestedBuild.gradleDistributionArchiveBytes -lt 1) {
        throw 'Attested local Gradle distribution archive does not match the tagged wrapper identity.'
    }
} elseif ($distributionMode -ceq 'network-with-pinned-sha256') {
    if ($null -ne $attestedBuild.gradleDistributionArchiveSha256 -or
        [long] $attestedBuild.gradleDistributionArchiveBytes -ne 0) {
        throw 'Network Gradle distribution acquisition must not claim a local archive.'
    }
} else {
    throw "Unsupported Gradle distribution acquisition mode: $distributionMode"
}
if (([string] $attestation.predicate.toolchain.gradleDistributionSha256).ToUpperInvariant() -cne $expectedGradleDistributionSha256) {
    throw 'Attested Gradle distribution SHA-256 does not match the tagged wrapper.'
}
foreach ($materialName in $sourceMaterialNames) {
    $materialMatches = @($attestation.predicate.materials | Where-Object { $_.name -ceq $materialName })
    if ($materialMatches.Count -ne 1) { throw "Attestation must contain exactly one source material '$materialName'." }
    $actualBlob = Get-GitBlobInfo $ExpectedRevision $materialName
    if ([string] $materialMatches[0].source.type -cne 'git-blob' -or
        [string] $materialMatches[0].source.revision -cne $ExpectedRevision -or
        [string] $materialMatches[0].source.objectId -cne $actualBlob.objectId -or
        ([string] $materialMatches[0].digest.sha256).ToLowerInvariant() -cne $actualBlob.sha256) {
        throw "Attested source material '$materialName' does not match exact Git blob bytes."
    }
}
$attestedToolHashes = @{
    androidAapt2Sha256 = $aapt2Sha256
    androidApkSignerJarSha256 = $apksignerJarSha256
    androidZipAlignSha256 = $zipalignSha256
    javaExecutableSha256 = $javaExecutableSha256
}
foreach ($key in $attestedToolHashes.Keys) {
    if (([string] $attestation.predicate.toolchain.$key).ToUpperInvariant() -cne $attestedToolHashes[$key]) {
        throw "Attestation toolchain field '$key' does not match the independently hashed tool."
    }
}
$releaseVerificationManifestPath = Join-Path $evidenceDirectory 'release-verification\release-verification-evidence.json'
if (-not (Test-Path -LiteralPath $releaseVerificationManifestPath -PathType Leaf)) {
    throw 'Raw release verification manifest is missing.'
}
$verificationByproduct = @($attestation.predicate.byproducts | Where-Object {
    $_.name -ceq 'release-verification/release-verification-evidence.json'
})
if ($verificationByproduct.Count -ne 1 -or
    ([string] $verificationByproduct[0].digest.sha256).ToUpperInvariant() -cne
        (Get-FileHash -LiteralPath $releaseVerificationManifestPath -Algorithm SHA256).Hash.ToUpperInvariant()) {
    throw 'Attested release verification manifest hash is missing or stale.'
}

$apkShaLines = @(Get-Content -LiteralPath $evidenceApkShaPath -Encoding UTF8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($apkShaLines.Count -ne 1 -or $apkShaLines[0] -cne "$($apkHash.ToLowerInvariant()) *app-release.apk") {
    throw 'Release-evidence APK checksum file is stale or malformed.'
}
$sbomHash = (Get-FileHash -LiteralPath $sbomPath -Algorithm SHA256).Hash.ToLowerInvariant()
$attestationHash = (Get-FileHash -LiteralPath $attestationPath -Algorithm SHA256).Hash.ToLowerInvariant()
$actualEvidenceLines = @(Get-Content -LiteralPath $evidenceChecksumsPath -Encoding UTF8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($PreSignatureCapture) {
    $expectedEvidenceLines = @(
        "$($apkHash.ToLowerInvariant()) *app-release.apk",
        "$sbomHash *app-release.apk.sbom.cdx.json",
        "$attestationHash *app-release.apk.attestation.json"
    )
    if ($actualEvidenceLines.Count -ne $expectedEvidenceLines.Count) { throw 'Pre-signature release evidence checksum manifest contains unexpected entries.' }
    foreach ($line in $expectedEvidenceLines) {
        if (-not ($actualEvidenceLines -ccontains $line)) { throw "Pre-signature evidence checksum manifest is missing: $line" }
    }
} else {
    $signatureVerification = @(& $javaExecutable `
        (Join-Path $PSScriptRoot 'ReleaseProvenanceSignature.java') verify `
        $provenanceManifestPath $provenanceSignaturePath $provenanceCertificatePath $expectedCertificate `
        2>&1 | ForEach-Object { [string] $_ })
    if ($LASTEXITCODE -ne 0 -or ($signatureVerification -join "`n") -notmatch 'PASS: detached provenance signature verified') {
        throw "Detached provenance signature verification failed: $($signatureVerification -join [Environment]::NewLine)"
    }
    $signedManifest = Get-Content -LiteralPath $provenanceManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($signedManifest.schema -cne 'fitness-ledger-signed-provenance-manifest-v1' -or
        [string] $signedManifest.signatureScheme -cne 'jca-detached-sha256' -or
        [string] $signedManifest.release.versionName -cne $ExpectedVersionName -or
        [int] $signedManifest.release.versionCode -ne $ExpectedVersionCode -or
        [int] $signedManifest.release.databaseVersion -ne $ExpectedDatabaseVersion -or
        [string] $signedManifest.release.revision -cne $ExpectedRevision -or
        [string] $signedManifest.release.tag -cne $expectedTag -or
        [string] $signedManifest.release.tagObject -cne $tagObjectSha -or
        [string] $signedManifest.release.gitTagSigningSshKeyFingerprint -cne $tagSignature.fingerprint -or
        ([string] $signedManifest.release.apkSigningCertificateSha256).ToUpperInvariant() -cne $expectedCertificate) {
        throw 'Signed provenance manifest release identity is stale or malformed.'
    }
    $excludedFullPaths = @(
        [IO.Path]::GetFullPath($provenanceManifestPath),
        [IO.Path]::GetFullPath($provenanceSignaturePath),
        [IO.Path]::GetFullPath($provenanceCertificatePath),
        [IO.Path]::GetFullPath($evidenceChecksumsPath)
    )
    $actualCoveredFiles = @(
        Get-ChildItem -LiteralPath $evidenceDirectory -File -Recurse |
            Where-Object { [IO.Path]::GetFullPath($_.FullName) -notin $excludedFullPaths } |
            Sort-Object FullName
    )
    $manifestEntries = @($signedManifest.files)
    if ($manifestEntries.Count -ne $actualCoveredFiles.Count) {
        throw 'Signed provenance manifest does not cover exactly every required evidence file.'
    }
    $seenPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $evidencePrefix = [IO.Path]::GetFullPath($evidenceDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    foreach ($entry in $manifestEntries) {
        $relative = [string] $entry.path
        if ([string]::IsNullOrWhiteSpace($relative) -or $relative.Contains('\') -or
            [IO.Path]::IsPathRooted($relative) -or ($relative -split '/') -contains '..' -or
            -not $seenPaths.Add($relative)) {
            throw "Unsafe or duplicate signed evidence path: $relative"
        }
        $fullPath = [IO.Path]::GetFullPath((Join-Path $evidenceDirectory ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)))
        if (-not $fullPath.StartsWith($evidencePrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "Signed evidence path escapes or is missing: $relative"
        }
        $file = Get-Item -LiteralPath $fullPath
        $actualHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToUpperInvariant()
        if ([long] $entry.bytes -ne $file.Length -or ([string] $entry.sha256).ToUpperInvariant() -cne $actualHash) {
            throw "Signed evidence hash/length mismatch: $relative"
        }
    }
    $actualRelativePaths = @($actualCoveredFiles | ForEach-Object { [IO.Path]::GetRelativePath($evidenceDirectory, $_.FullName) -replace '\\', '/' })
    if (@($actualRelativePaths | Where-Object { -not $seenPaths.Contains($_) }).Count -ne 0) {
        throw 'At least one evidence file is not covered by the detached signature.'
    }
    $deliveryGateResultPath = Join-Path $evidenceDirectory 'delivery-gate\delivery-gate-pre-signature.json'
    $deliveryGateTranscriptPath = Join-Path $evidenceDirectory 'delivery-gate\delivery-gate-pre-signature.log'
    if (-not (Test-Path -LiteralPath $deliveryGateTranscriptPath -PathType Leaf)) {
        throw 'Signed delivery-gate transcript is missing.'
    }
    $deliveryGateResult = Get-Content -LiteralPath $deliveryGateResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $deliveryGateTranscriptHash = (Get-FileHash -LiteralPath $deliveryGateTranscriptPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($deliveryGateResult.schema -cne 'fitness-ledger-delivery-gate-capture-v1' -or
        $deliveryGateResult.mode -cne 'all-independent-checks-before-detached-signature' -or
        [int] $deliveryGateResult.exitCode -ne 0 -or
        [string] $deliveryGateResult.revision -cne $ExpectedRevision -or
        [string] $deliveryGateResult.transcript -cne 'delivery-gate-pre-signature.log' -or
        ([string] $deliveryGateResult.transcriptSha256).ToUpperInvariant() -cne $deliveryGateTranscriptHash) {
        throw 'Signed delivery-gate transcript result is missing or failed.'
    }
    $deliveryGateStartedAt = ConvertFrom-EvidenceTimestamp $deliveryGateResult.startedUtc 'Signed delivery-gate capture start'
    $deliveryGateFinishedAt = ConvertFrom-EvidenceTimestamp $deliveryGateResult.finishedUtc 'Signed delivery-gate capture finish'
    if ($deliveryGateFinishedAt -lt $deliveryGateStartedAt) {
        throw 'Signed delivery-gate capture finishes before it starts.'
    }
    $expectedChecksumLines = @(
        Get-ChildItem -LiteralPath $evidenceDirectory -File -Recurse |
            Where-Object { [IO.Path]::GetFullPath($_.FullName) -cne [IO.Path]::GetFullPath($evidenceChecksumsPath) } |
            Sort-Object FullName |
            ForEach-Object {
                "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToUpperInvariant())  $([IO.Path]::GetRelativePath($evidenceDirectory, $_.FullName) -replace '\\', '/')"
            }
    )
    if (($actualEvidenceLines -join "`n") -cne ($expectedChecksumLines -join "`n")) {
        throw 'Final release evidence checksum manifest is incomplete, stale, or out of order.'
    }
}

$installation = Get-Content -LiteralPath $installationPath -Raw -Encoding UTF8
$acceptance = Get-Content -LiteralPath $acceptancePath -Raw -Encoding UTF8
$checksums = @(Get-Content -LiteralPath $checksumsPath -Encoding UTF8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$sizeText = [string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:N0}', $apk.Length)
foreach ($document in @(
    @{ Name = '安装说明.md'; Text = $installation },
    @{ Name = '验收报告.md'; Text = $acceptance }
)) {
    Assert-Contains $document.Text $ExpectedVersionName $document.Name
    Assert-Contains $document.Text $apkName $document.Name
    Assert-Contains $document.Text $sizeText $document.Name
    Assert-Contains $document.Text $apkHash $document.Name
    Assert-Contains $document.Text $expectedTag $document.Name
    Assert-Contains $document.Text $ExpectedRevision $document.Name
    Assert-Contains $document.Text $actualCertificate $document.Name
    Assert-Contains $document.Text "数据库 v$ExpectedDatabaseVersion" $document.Name
}
$expectedChecksumLine = "$apkHash  $apkName"
if ($checksums.Count -ne 1 -or $checksums[0] -cne $expectedChecksumLine) {
    throw "Top-level SHA256SUMS.txt must contain exactly the current install APK line: $expectedChecksumLine"
}

# Detect tool replacement during the gate itself.
[void](Assert-FileSha256 $aapt2 (Require-Value $policy 'androidAapt2Sha256') 'Android aapt2.exe after verification')
[void](Assert-FileSha256 $apksignerJar (Require-Value $policy 'androidApkSignerJarSha256') 'Android apksigner.jar after verification')
[void](Assert-FileSha256 $zipalign (Require-Value $policy 'androidZipAlignSha256') 'Android zipalign.exe after verification')
[void](Assert-FileSha256 $javaExecutable (Require-Value $policy 'javaExecutableSha256') 'JDK java.exe after verification')

Write-Host 'PASS: actual APK identity, SDKs, v2 signer, 16 KiB alignment, annotated tag, provenance, Git-blob materials, evidence, docs, and unique install entry agree.'
Write-Host "  package=$actualApplicationId"
Write-Host "  versionName=$actualVersionName"
Write-Host "  versionCode=$actualVersionCode"
Write-Host "  minSdk=$actualMinSdk"
Write-Host "  targetSdk=$actualTargetSdk"
Write-Host "  tag=$expectedTag"
Write-Host "  tagObject=$tagObjectSha"
Write-Host "  revision=$ExpectedRevision"
Write-Host "  certificateSha256=$actualCertificate"
Write-Host "  apkSha256=$apkHash"
Write-Host "  apkBytes=$($apk.Length)"
