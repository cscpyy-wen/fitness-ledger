[CmdletBinding()]
param(
    [string] $ExpectedTag = '',
    [string] $ExpectedVersionName = '',
    [string] $ExpectedRevision = '',
    [int] $ExpectedVersionCode = 0,
    [int] $ExpectedDatabaseVersion = 0,
    [string] $DeliveryDirectory = '',
    [switch] $FinalizeDeliveryEvidence,
    [switch] $Offline,
    [switch] $EnableRepositoryMirrors,
    [string] $GradleDistributionArchive = '',
    [switch] $KeepIsolatedBuildState
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')

if ($FinalizeDeliveryEvidence) {
    if ($ExpectedVersionName -notmatch '^[0-9A-Za-z][0-9A-Za-z._-]*$' -or
        $ExpectedRevision -notmatch '^[0-9a-f]{40}$' -or
        $ExpectedVersionCode -lt 1 -or $ExpectedDatabaseVersion -lt 1) {
        throw 'Finalization requires exact versionName, versionCode, databaseVersion and 40-character revision.'
    }
    $arguments = @{
        ExpectedVersionName = $ExpectedVersionName
        ExpectedVersionCode = $ExpectedVersionCode
        ExpectedDatabaseVersion = $ExpectedDatabaseVersion
        ExpectedRevision = $ExpectedRevision
    }
    if (-not [string]::IsNullOrWhiteSpace($DeliveryDirectory)) {
        $arguments.DeliveryDirectory = $DeliveryDirectory
    }
    & (Join-Path $PSScriptRoot 'Finalize-ReleaseEvidence.ps1') @arguments
    exit $LASTEXITCODE
}

function Invoke-GitText([string[]] $Arguments) {
    $output = @(& git -C $script:RepoRoot @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)"
    }
    return @($output | ForEach-Object { ([string] $_).Trim() } | Where-Object { $_ -ne '' })
}

function Read-KeyValueFile([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required policy/configuration file is missing: $Path"
    }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*[#!]') { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed key/value line in $Path" }
        $name = $line.Substring(0, $separator).Trim()
        if ($values.ContainsKey($name)) { throw "Duplicate key '$name' in $Path" }
        $values[$name] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

function Require-Value([hashtable] $Values, [string] $Name) {
    if (-not $Values.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace([string] $Values[$Name])) {
        throw "Required value '$Name' is missing."
    }
    return [string] $Values[$Name]
}

function Read-Provenance([string] $ApkPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $entries = @($archive.Entries | Where-Object { $_.FullName -ceq 'assets/build-provenance.properties' })
        if ($entries.Count -ne 1) {
            throw "APK must contain exactly one assets/build-provenance.properties entry; found $($entries.Count)."
        }
        $reader = [IO.StreamReader]::new($entries[0].Open(), [Text.Encoding]::UTF8, $true)
        try {
            $text = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }

    $values = @{}
    foreach ($line in ($text -split "\r?\n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { throw "Malformed provenance line: $line" }
        $key = $line.Substring(0, $separator)
        if ($values.ContainsKey($key)) { throw "Duplicate provenance key: $key" }
        $values[$key] = $line.Substring($separator + 1)
    }
    foreach ($required in @('revision', 'versionName', 'versionCode')) {
        if (-not $values.ContainsKey($required)) { throw "Missing provenance key: $required" }
    }
    return $values
}

function Find-AndroidSdkRoot {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $propertiesPath = Join-Path $script:RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $propertiesPath -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $propertiesPath |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $candidate = $sdkLine.Substring('sdk.dir='.Length)
            $candidate = $candidate -replace '\\:', ':'
            $candidate = $candidate -replace '\\\\', '\'
            if (Test-Path -LiteralPath $candidate) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
    }
    throw 'Android SDK not found. Set ANDROID_SDK_ROOT or provide local.properties sdk.dir.'
}

function Find-BuildToolSet([string] $SdkRoot, [string] $ExpectedVersion) {
    $candidate = Join-Path (Join-Path $SdkRoot 'build-tools') $ExpectedVersion
    foreach ($relativeTool in @('aapt2.exe', 'lib\apksigner.jar', 'zipalign.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $candidate $relativeTool) -PathType Leaf)) {
            throw "Pinned Android build-tools $ExpectedVersion is missing $relativeTool under $candidate"
        }
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Assert-FileSha256([string] $Path, [string] $Expected, [string] $Label) {
    $normalizedExpected = ($Expected -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
    if ($normalizedExpected -notmatch '^[0-9A-F]{64}$') {
        throw "Pinned SHA-256 for $Label is malformed."
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actual -cne $normalizedExpected) {
        throw "$Label SHA-256 '$actual' does not match release policy '$normalizedExpected'."
    }
    return $actual.ToLowerInvariant()
}

function Get-GitBlobInfo([string] $Revision, [string] $RepoRelativePath) {
    if ($RepoRelativePath -notmatch '^[A-Za-z0-9._/-]+$') {
        throw "Unsafe repository-relative material path: $RepoRelativePath"
    }
    $objectId = (Invoke-GitText @('rev-parse', "${Revision}:$RepoRelativePath") | Select-Object -First 1)
    if ($objectId -notmatch '^[0-9a-f]{40}$') {
        throw "Unexpected Git object id for ${Revision}:$RepoRelativePath"
    }
    if ((Invoke-GitText @('cat-file', '-t', $objectId) | Select-Object -First 1) -cne 'blob') {
        throw "Release material is not a Git blob: $RepoRelativePath"
    }

    # Read the object through a binary stream. PowerShell string capture would
    # decode native stdout and reintroduce the CRLF/code-page ambiguity this
    # evidence is intended to remove.
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
    if ($process.ExitCode -ne 0) {
        throw "git cat-file failed for ${Revision}:${RepoRelativePath}: $stderr"
    }
    $process.Dispose()
    return [ordered]@{ objectId = $objectId; sha256 = $digest }
}

function New-AttestedGitMaterial([string] $Name, [object] $Info, [string] $Revision) {
    return [ordered]@{
        name = $Name
        source = [ordered]@{
            type = 'git-blob'
            revision = $Revision
            objectId = $Info.objectId
        }
        digest = [ordered]@{ sha256 = $Info.sha256 }
    }
}

function Write-Utf8Json([string] $Path, [object] $Value) {
    $json = $Value | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Clear-ProcessEnvironmentVariable([string] $Name) {
    Remove-Item -LiteralPath "Env:$Name" -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath "Env:$Name") {
        throw "Could not clear process environment variable: $Name"
    }
}

function Restore-ProcessEnvironmentVariable([string] $Name, [AllowNull()] [string] $Value) {
    if ($null -eq $Value) {
        Clear-ProcessEnvironmentVariable $Name
    } else {
        [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
    }
}

$RepoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd([IO.Path]::DirectorySeparatorChar)
$releaseSigningEnvironmentBefore = @{}
foreach ($name in $script:ReleaseSigningEnvironmentNames) {
    $releaseSigningEnvironmentBefore[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$presentReleaseSigningNames = @(
    $script:ReleaseSigningEnvironmentNames | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string] $releaseSigningEnvironmentBefore[$_])
    }
)
if ($presentReleaseSigningNames.Count -ne 0 -and
    $presentReleaseSigningNames.Count -ne $script:ReleaseSigningEnvironmentNames.Count) {
    throw "Partial release signing environment detected. Provide all four variables or clear all of them: $($script:ReleaseSigningEnvironmentNames -join ', ')"
}
$useCiSigningEnvironment = $presentReleaseSigningNames.Count -eq $script:ReleaseSigningEnvironmentNames.Count

$temporaryPasswordEnvironmentNames = @(
    'FITNESS_APKSIGNER_STORE_PASSWORD_TEMP',
    'FITNESS_APKSIGNER_KEY_PASSWORD_TEMP'
)
$temporaryPasswordEnvironmentBefore = @{}
foreach ($name in $temporaryPasswordEnvironmentNames) {
    $temporaryPasswordEnvironmentBefore[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$payload = $null
$signingStorePassword = $null
$signingKeyPassword = $null
$signingKeyAlias = $null
$keystorePath = $null
$credentialSource = $null
$alignedUnsignedPath = $null
$stagedSignedPath = $null
$finalApkPath = $null
$finalApkCreated = $false
$publishSucceeded = $false
$generatedReleaseEvidencePaths = [Collections.Generic.List[string]]::new()
$releaseVerificationStagingDirectory = $null
$releaseVerificationDestination = $null

try {
    # SECURITY ORDER: clear every known signing environment before Gradle starts.
    foreach ($name in $script:ReleaseSigningEnvironmentNames + $temporaryPasswordEnvironmentNames) {
        Clear-ProcessEnvironmentVariable $name
    }
    foreach ($name in $script:ReleaseSigningEnvironmentNames + $temporaryPasswordEnvironmentNames) {
        if (Test-Path -LiteralPath "Env:$name") {
            throw "Could not clear signing environment before Gradle: $name"
        }
    }

if ($Offline) {
    throw 'Formal releases require a fresh isolated Gradle state and cannot use -Offline.'
}

$policyPath = Join-Path $RepoRoot 'release-policy.properties'
$policy = Read-KeyValueFile $policyPath
if ((Require-Value $policy 'policyFormat') -cne 'fitness-ledger-release-policy-v1') {
    throw 'Unsupported release policy format.'
}
$expectedApplicationId = Require-Value $policy 'applicationId'
$expectedMinSdk = [int](Require-Value $policy 'minSdk')
$expectedTargetSdk = [int](Require-Value $policy 'targetSdk')
$tagPrefix = Require-Value $policy 'releaseTagPrefix'
if ($tagPrefix -cne 'v') { throw 'Only the canonical v-prefixed release tag policy is supported.' }
$expectedCertificate = ((Require-Value $policy 'signingCertificateSha256') -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
if ($expectedCertificate -notmatch '^[0-9A-F]{64}$') {
    throw 'The repository release policy certificate must contain exactly 64 hexadecimal digits.'
}
if ((Require-Value $policy 'requireNoBuildCache') -cne 'true') {
    throw 'The repository release policy must require --no-build-cache.'
}
if ((Require-Value $policy 'requireNoConfigurationCache') -cne 'true') {
    throw 'The repository release policy must require --no-configuration-cache.'
}
if ((Require-Value $policy 'requireStrictDependencyVerification') -cne 'true') {
    throw 'The repository release policy must require strict Gradle dependency verification.'
}
if ((Require-Value $policy 'releaseArtifactSigningMode') -cne 'external-apksigner') {
    throw 'The repository release policy must require external apksigner signing.'
}
if ((Require-Value $policy 'releaseSignatureSchemes') -cne 'v2-only') {
    throw 'The repository release policy must require v2-only APK signing.'
}
if ((Require-Value $policy 'materialHashMode') -cne 'git-blob-bytes-sha256') {
    throw 'The repository release policy must hash source materials from exact Git blob bytes.'
}
$releaseAbis = @((Require-Value $policy 'releaseAbis') -split ',')
$requiredNativeLibraries = @((Require-Value $policy 'requiredNativeLibraries') -split ',')
if (($releaseAbis -join ',') -cne 'arm64-v8a,armeabi-v7a,x86_64') {
    throw 'Release policy must exclude unsupported 32-bit x86.'
}
if ((Require-Value $policy 'provenanceSignatureScheme') -cne 'jca-detached-sha256') {
    throw 'Release policy must require detached JCA SHA-256 provenance signatures.'
}

$headRevision = (Invoke-GitText @('rev-parse', 'HEAD') | Select-Object -First 1)
if ($headRevision -notmatch '^[0-9a-f]{40}$') { throw "Unexpected HEAD revision: $headRevision" }
$status = @(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all'))
if ($status.Count -ne 0) {
    throw "Release builds require a clean commit. Dirty entries:`n$($status -join [Environment]::NewLine)"
}

$headTags = @(Invoke-GitText @('tag', '--points-at', 'HEAD'))
if ($headTags.Count -ne 1) {
    throw "HEAD must have exactly one tag, found: $($headTags -join ', ')"
}
$releaseTag = $headTags[0]
if (-not [string]::IsNullOrWhiteSpace($ExpectedTag) -and $ExpectedTag -cne $releaseTag) {
    throw "Expected tag '$ExpectedTag' does not equal the unique HEAD tag '$releaseTag'."
}
if ([string]::IsNullOrWhiteSpace($ExpectedVersionName)) {
    $ExpectedVersionName = $releaseTag.Substring($tagPrefix.Length)
}
if ($releaseTag -cne "$tagPrefix$ExpectedVersionName") {
    throw "Release tag '$releaseTag' does not match versionName '$ExpectedVersionName'."
}
if ((Require-Value $policy 'requireAnnotatedTag') -cne 'true') {
    throw 'The repository release policy must require annotated tags.'
}
$tagObjectType = (Invoke-GitText @('cat-file', '-t', "refs/tags/$releaseTag") | Select-Object -First 1)
if ($tagObjectType -cne 'tag') { throw "Release tag '$releaseTag' is not annotated." }
$tagObjectSha = (Invoke-GitText @('rev-parse', "refs/tags/$releaseTag") | Select-Object -First 1)
$tagCommit = (Invoke-GitText @('rev-list', '-n', '1', $releaseTag) | Select-Object -First 1)
if ($tagCommit -cne $headRevision) { throw "Release tag '$releaseTag' does not resolve to HEAD." }
$tagSignature = Assert-ReleaseTagSignature `
    $RepoRoot `
    $releaseTag `
    (Require-Value $policy 'gitTagSigningPrincipal') `
    (Require-Value $policy 'gitTagSigningSshKeyFingerprint')

$rootBuildText = Get-Content -LiteralPath (Join-Path $RepoRoot 'build.gradle.kts') -Raw -Encoding UTF8
$agpMatch = [regex]::Match($rootBuildText, 'id\("com\.android\.application"\)\s+version\s+"([^"]+)"')
$kotlinMatch = [regex]::Match($rootBuildText, 'id\("org\.jetbrains\.kotlin\.android"\)\s+version\s+"([^"]+)"')
if (-not $agpMatch.Success -or -not $kotlinMatch.Success) { throw 'Could not parse pinned AGP/Kotlin versions.' }
$agpVersion = $agpMatch.Groups[1].Value
$kotlinVersion = $kotlinMatch.Groups[1].Value
if ($agpVersion -cne (Require-Value $policy 'androidGradlePluginVersion')) {
    throw 'AGP version does not match release policy.'
}
if ($kotlinVersion -cne (Require-Value $policy 'kotlinPluginVersion')) {
    throw 'Kotlin plugin version does not match release policy.'
}

$wrapperPath = Join-Path $RepoRoot 'gradle\wrapper\gradle-wrapper.properties'
$wrapper = Read-KeyValueFile $wrapperPath
$distributionUrl = Require-Value $wrapper 'distributionUrl'
$gradleMatch = [regex]::Match($distributionUrl, 'gradle-([0-9.]+)-')
if (-not $gradleMatch.Success) { throw 'Could not parse Gradle wrapper version.' }
$gradleVersion = $gradleMatch.Groups[1].Value
if ($gradleVersion -cne (Require-Value $policy 'gradleVersion')) {
    throw 'Gradle wrapper version does not match release policy.'
}
$gradleNetworkTimeout = Require-Value $wrapper 'networkTimeout'
if ($gradleNetworkTimeout -cne (Require-Value $policy 'gradleDistributionNetworkTimeoutMs')) {
    throw 'Gradle wrapper network timeout does not match release policy.'
}
if ((Require-Value $wrapper 'validateDistributionUrl') -cne 'true') {
    throw 'Gradle wrapper must validate the distribution URL.'
}
$gradleDistributionAcquisitionMode = 'network-with-pinned-sha256'
$gradleDistributionArchiveSha256 = $null
$gradleDistributionArchiveBytes = 0L
if (-not [string]::IsNullOrWhiteSpace($GradleDistributionArchive)) {
    if ((Require-Value $policy 'allowVerifiedLocalGradleDistributionArchive') -cne 'true') {
        throw 'Release policy does not allow a verified local Gradle distribution archive.'
    }
    $GradleDistributionArchive = (Resolve-Path -LiteralPath $GradleDistributionArchive -ErrorAction Stop).Path
    $GradleDistributionArchive = Assert-PathOutsideRepository $RepoRoot $GradleDistributionArchive 'GradleDistributionArchive'
    if (-not (Test-Path -LiteralPath $GradleDistributionArchive -PathType Leaf)) {
        throw "GradleDistributionArchive is not a file: $GradleDistributionArchive"
    }
    $gradleDistributionArchiveSha256 = (Get-FileHash -LiteralPath $GradleDistributionArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($gradleDistributionArchiveSha256 -cne (Require-Value $wrapper 'distributionSha256Sum').ToLowerInvariant()) {
        throw 'GradleDistributionArchive does not match the wrapper distribution SHA-256.'
    }
    $gradleDistributionArchiveBytes = (Get-Item -LiteralPath $GradleDistributionArchive).Length
    $gradleDistributionAcquisitionMode = 'verified-local-archive-with-pinned-sha256'
}

$verifiedGradle = Join-Path $PSScriptRoot 'Invoke-GradleVerified.ps1'
$gradleParameters = @{
    Tasks = @('clean', ':app:assembleRelease', ':app:generateReleaseSbom')
    GradleArguments = @('--no-daemon', '--stacktrace', '--dependency-verification=strict')
    IsolateBuildState = $true
}
if ($EnableRepositoryMirrors) { $gradleParameters['EnableRepositoryMirrors'] = $true }
if ($KeepIsolatedBuildState) { $gradleParameters['KeepIsolatedBuildState'] = $true }
if (-not [string]::IsNullOrWhiteSpace($GradleDistributionArchive)) {
    $gradleParameters['GradleDistributionArchive'] = $GradleDistributionArchive
}
$buildStartedUtc = [DateTime]::UtcNow
& $verifiedGradle @gradleParameters
$gradleExitCode = $LASTEXITCODE
if ($gradleExitCode -ne 0) {
    throw "Unsigned release Gradle build failed with exit code $gradleExitCode"
}
$gradleFinishedUtc = [DateTime]::UtcNow
# SECURITY ORDER: Gradle has exited; signing credentials may only be resolved below this point.

# A concurrent persistent source/index change after the pre-build gate must
# invalidate the release instead of producing evidence for a stale HEAD label.
$postBuildRevision = (Invoke-GitText @('rev-parse', 'HEAD') | Select-Object -First 1)
if ($postBuildRevision -cne $headRevision) {
    throw "Git HEAD changed during the release build: $headRevision -> $postBuildRevision"
}
$postBuildStatus = @(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all'))
if ($postBuildStatus.Count -ne 0) {
    throw "Source/index changed during the release build. Dirty entries:`n$($postBuildStatus -join [Environment]::NewLine)"
}

$releaseVerificationStagingDirectory = Join-Path $RepoRoot "build\release-verification-$headRevision"
if (Test-Path -LiteralPath $releaseVerificationStagingDirectory) {
    throw "Refusing to reuse release verification evidence: $releaseVerificationStagingDirectory"
}
& (Join-Path $PSScriptRoot 'Invoke-ReleaseVerificationEvidence.ps1') `
    -ExpectedRevision $headRevision `
    -OutputDirectory $releaseVerificationStagingDirectory
if ($LASTEXITCODE -ne 0) { throw 'Release verification evidence capture failed.' }
$postVerificationStatus = @(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all'))
if ($postVerificationStatus.Count -ne 0) {
    throw "Verification tasks changed source/index: $($postVerificationStatus -join [Environment]::NewLine)"
}

$releaseDirectory = Join-Path $RepoRoot 'app\build\outputs\apk\release'
$apks = @(Get-ChildItem -LiteralPath $releaseDirectory -Filter '*.apk' -File -ErrorAction SilentlyContinue)
if ($apks.Count -ne 1) {
    throw "Expected exactly one release APK in $releaseDirectory, found $($apks.Count)"
}
$unsignedApk = $apks[0]
if ($unsignedApk.Name -notmatch '-unsigned\.apk$') {
    throw "Gradle must produce exactly one unsigned release APK, found: $($unsignedApk.Name)"
}
if ($unsignedApk.LastWriteTimeUtc -lt $buildStartedUtc.AddSeconds(-2)) {
    throw "Unsigned release APK predates this clean build: $($unsignedApk.FullName)"
}

$provenance = Read-Provenance $unsignedApk.FullName
if ($provenance['revision'] -cne $headRevision) {
    throw "Embedded revision '$($provenance['revision'])' does not match HEAD '$headRevision'"
}
if ($provenance['versionName'] -cne $ExpectedVersionName) {
    throw "Embedded versionName '$($provenance['versionName'])' does not match expected '$ExpectedVersionName'"
}
if ($provenance['versionCode'] -notmatch '^\d+$') {
    throw "Embedded versionCode is invalid: $($provenance['versionCode'])"
}

$sdkRoot = Find-AndroidSdkRoot
$pinnedBuildToolsVersion = Require-Value $policy 'androidBuildToolsVersion'
$buildTools = Find-BuildToolSet $sdkRoot $pinnedBuildToolsVersion
$buildToolsVersion = Split-Path -Leaf $buildTools
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$apksignerJar = Join-Path $buildTools 'lib\apksigner.jar'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
$aapt2Sha256 = Assert-FileSha256 $aapt2 (Require-Value $policy 'androidAapt2Sha256') 'Android aapt2.exe'
$apksignerJarSha256 = Assert-FileSha256 $apksignerJar (Require-Value $policy 'androidApkSignerJarSha256') 'Android apksigner.jar'
$zipalignSha256 = Assert-FileSha256 $zipalign (Require-Value $policy 'androidZipAlignSha256') 'Android zipalign.exe'
$javaExecutableSha256 = Assert-FileSha256 $javaExecutable (Require-Value $policy 'javaExecutableSha256') 'JDK java.exe'

$badging = @(& $aapt2 dump badging $unsignedApk.FullName 2>&1)
if ($LASTEXITCODE -ne 0) { throw "aapt2 dump badging failed: $($badging -join [Environment]::NewLine)" }
$packageLine = [string] ($badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
if ($packageLine -notmatch "name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'") {
    throw "Could not parse APK package/version metadata: $packageLine"
}
$manifestApplicationId = $Matches[1]
$manifestVersionCode = $Matches[2]
$manifestVersionName = $Matches[3]
if ($manifestApplicationId -cne $expectedApplicationId) {
    throw "APK applicationId '$manifestApplicationId' does not match policy '$expectedApplicationId'"
}
if ($manifestVersionCode -cne $provenance['versionCode']) {
    throw "Manifest versionCode '$manifestVersionCode' does not match provenance '$($provenance['versionCode'])'"
}
if ($manifestVersionName -cne $provenance['versionName']) {
    throw "Manifest versionName '$manifestVersionName' does not match provenance '$($provenance['versionName'])'"
}
$minSdkLine = [string] ($badging | Where-Object { $_ -match '^minSdkVersion:' } | Select-Object -First 1)
$targetSdkLine = [string] ($badging | Where-Object { $_ -match '^targetSdkVersion:' } | Select-Object -First 1)
if ($minSdkLine -notmatch "^minSdkVersion:'(\d+)'$") { throw "Could not parse APK minSdk: $minSdkLine" }
$manifestMinSdk = [int] $Matches[1]
if ($targetSdkLine -notmatch "^targetSdkVersion:'(\d+)'$") { throw "Could not parse APK targetSdk: $targetSdkLine" }
$manifestTargetSdk = [int] $Matches[1]
if ($manifestMinSdk -ne $expectedMinSdk) {
    throw "APK minSdk '$manifestMinSdk' does not match release policy '$expectedMinSdk'"
}
if ($manifestTargetSdk -ne $expectedTargetSdk) {
    throw "APK targetSdk '$manifestTargetSdk' does not match release policy '$expectedTargetSdk'"
}

$unsignedSignatureOutput = @(& $javaExecutable -jar $apksignerJar verify --verbose $unsignedApk.FullName 2>&1)
if ($LASTEXITCODE -eq 0) {
    throw 'Gradle release output is unexpectedly signed; Gradle must only produce unsigned APKs.'
}
$nativeLibraryPolicy = Assert-ReleaseApkNativeLibraries $unsignedApk.FullName $releaseAbis $requiredNativeLibraries

$gradleUnsignedSha256 = (Get-FileHash -LiteralPath $unsignedApk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$alignedUnsignedPath = Join-Path $releaseDirectory 'app-release-aligned-unsigned.apk'
$stagedSignedPath = Join-Path $releaseDirectory 'app-release-signed-staging.apk'
$finalApkPath = Join-Path $releaseDirectory 'app-release.apk'
foreach ($path in @($alignedUnsignedPath, $stagedSignedPath, $finalApkPath)) {
    if (Test-Path -LiteralPath $path) {
        throw "Refusing to overwrite an unexpected release staging artifact: $path"
    }
}

$alignArguments = @('-f', '-P', '16', '-v', '4', $unsignedApk.FullName, $alignedUnsignedPath)
$alignmentOutput = @(& $zipalign @alignArguments 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "Unsigned APK zipalign failed: $($alignmentOutput -join [Environment]::NewLine)"
}
$alignedUnsignedSha256 = (Get-FileHash -LiteralPath $alignedUnsignedPath -Algorithm SHA256).Hash.ToLowerInvariant()

# SECURITY ORDER: resolve credentials only after Gradle exit, unsigned validation, and zipalign.
if ($useCiSigningEnvironment) {
    $storeFileSetting = [string] $releaseSigningEnvironmentBefore['FITNESS_RELEASE_STORE_FILE']
    $signingStorePassword = [string] $releaseSigningEnvironmentBefore['FITNESS_RELEASE_STORE_PASSWORD']
    $signingKeyAlias = [string] $releaseSigningEnvironmentBefore['FITNESS_RELEASE_KEY_ALIAS']
    $signingKeyPassword = [string] $releaseSigningEnvironmentBefore['FITNESS_RELEASE_KEY_PASSWORD']
    $keystorePath = if ([IO.Path]::IsPathRooted($storeFileSetting)) {
        [IO.Path]::GetFullPath($storeFileSetting)
    } else {
        [IO.Path]::GetFullPath((Join-Path $RepoRoot $storeFileSetting))
    }
    $credentialSource = 'complete CI process environment input'
} else {
    $dpapiBlobPath = Join-Path $RepoRoot '.signing\release-signing.dpapi.json'
    $payload = Unprotect-ReleaseSigningPayload $dpapiBlobPath
    $signingDirectory = [IO.Path]::GetFullPath((Join-Path $RepoRoot '.signing')).TrimEnd('\')
    $keystorePath = [IO.Path]::GetFullPath((Join-Path $signingDirectory ([string] $payload.storeFile)))
    $keystoreParent = [IO.Path]::GetFullPath((Split-Path -Parent $keystorePath)).TrimEnd('\')
    if (-not $keystoreParent.Equals($signingDirectory, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'DPAPI signing payload must reference a keystore directly inside .signing.'
    }
    $signingStorePassword = [string] $payload.storePassword
    $signingKeyAlias = [string] $payload.keyAlias
    $signingKeyPassword = [string] $payload.keyPassword
    $credentialSource = 'Windows DPAPI CurrentUser blob'
}
if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
    throw "Release keystore is missing: $keystorePath"
}
foreach ($requiredSigningValue in @($signingStorePassword, $signingKeyAlias, $signingKeyPassword)) {
    if ([string]::IsNullOrWhiteSpace([string] $requiredSigningValue)) {
        throw 'Resolved release signing credentials contain an empty required field.'
    }
}

$storePasswordEnvironmentName = $temporaryPasswordEnvironmentNames[0]
$keyPasswordEnvironmentName = $temporaryPasswordEnvironmentNames[1]
$signArguments = @(
    'sign',
    '--ks', $keystorePath,
    '--ks-key-alias', $signingKeyAlias,
    '--ks-pass', "env:$storePasswordEnvironmentName",
    '--key-pass', "env:$keyPasswordEnvironmentName",
    '--v1-signing-enabled', 'false',
    '--v2-signing-enabled', 'true',
    '--v3-signing-enabled', 'false',
    '--v4-signing-enabled', 'false',
    '--debuggable-apk-permitted', 'false',
    '--alignment-preserved', 'true',
    '--out', $stagedSignedPath,
    '--in', $alignedUnsignedPath
)
$signExitCode = 1
try {
    [Environment]::SetEnvironmentVariable($storePasswordEnvironmentName, $signingStorePassword, 'Process')
    [Environment]::SetEnvironmentVariable($keyPasswordEnvironmentName, $signingKeyPassword, 'Process')
    & $javaExecutable -jar $apksignerJar @signArguments
    $signExitCode = $LASTEXITCODE
} finally {
    # Do not restore any caller values until all post-sign verification has finished.
    Clear-ProcessEnvironmentVariable $storePasswordEnvironmentName
    Clear-ProcessEnvironmentVariable $keyPasswordEnvironmentName
}
if ($signExitCode -ne 0) {
    throw "External apksigner process failed with exit code $signExitCode"
}

$signatureOutput = @(& $javaExecutable -jar $apksignerJar verify -Werr --verbose --print-certs $stagedSignedPath 2>&1)
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $($signatureOutput -join [Environment]::NewLine)" }
foreach ($expectedSchemeLine in @(
    'Verifies',
    'Verified using v1 scheme (JAR signing): false',
    'Verified using v2 scheme (APK Signature Scheme v2): true',
    'Verified using v3 scheme (APK Signature Scheme v3): false',
    'Verified using v3.1 scheme (APK Signature Scheme v3.1): false',
    'Verified using v4 scheme (APK Signature Scheme v4): false',
    'Number of signers: 1'
)) {
    if ($expectedSchemeLine -notin @($signatureOutput | ForEach-Object { [string] $_ })) {
        throw "APK signature scheme policy check failed: missing '$expectedSchemeLine'"
    }
}
$certificateLines = @($signatureOutput | Where-Object { $_ -match 'Signer #[0-9]+ certificate SHA-256 digest:' })
if ($certificateLines.Count -ne 1) { throw "Expected exactly one APK signer, found $($certificateLines.Count)." }
$actualCertificate = ([string] $certificateLines[0] -replace '^.*digest:\s*', '' -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
if ($actualCertificate -cne $expectedCertificate) {
    throw "APK signer certificate '$actualCertificate' does not match the repository release policy"
}
[void](Assert-ReleaseApkNativeLibraries $stagedSignedPath $releaseAbis $requiredNativeLibraries)

$alignmentOutput = @(& $zipalign -c -P 16 -v 4 $stagedSignedPath 2>&1)
if ($LASTEXITCODE -ne 0 -or 'Verification successful' -notin @($alignmentOutput | ForEach-Object { [string] $_ })) {
    throw "APK zipalign verification failed: $($alignmentOutput -join [Environment]::NewLine)"
}
Assert-FileSha256 $apksignerJar (Require-Value $policy 'androidApkSignerJarSha256') 'Android apksigner.jar after signing' | Out-Null
Assert-FileSha256 $zipalign (Require-Value $policy 'androidZipAlignSha256') 'Android zipalign.exe after signing' | Out-Null
Assert-FileSha256 $javaExecutable (Require-Value $policy 'javaExecutableSha256') 'JDK java.exe after signing' | Out-Null

Move-Item -LiteralPath $stagedSignedPath -Destination $finalApkPath
$finalApkCreated = $true
$apk = Get-Item -LiteralPath $finalApkPath

$sbomSource = Join-Path $RepoRoot 'app\build\reports\sbom\release-runtime.cdx.json'
if (-not (Test-Path -LiteralPath $sbomSource -PathType Leaf)) { throw 'Release SBOM was not generated.' }
$sbom = Get-Content -LiteralPath $sbomSource -Raw -Encoding UTF8 | ConvertFrom-Json
if ($sbom.bomFormat -cne 'CycloneDX' -or $sbom.specVersion -cne '1.6') {
    throw 'Generated release SBOM is not CycloneDX 1.6.'
}
if (@($sbom.components).Count -eq 0) { throw 'Generated release SBOM has no runtime components.' }
$revisionProperty = @($sbom.metadata.component.properties |
    Where-Object { $_.name -ceq 'fitness.git.revision' } | Select-Object -First 1)
if ($revisionProperty.Count -ne 1 -or $revisionProperty[0].value -cne $headRevision) {
    throw 'Generated release SBOM revision does not match HEAD.'
}

$sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$shaFile = "$($apk.FullName).sha256"
[IO.File]::WriteAllText(
    $shaFile,
    "$sha256 *$($apk.Name)$([Environment]::NewLine)",
    [Text.UTF8Encoding]::new($false)
)
[void] $generatedReleaseEvidencePaths.Add($shaFile)

$sbomDestination = "$($apk.FullName).sbom.cdx.json"
Copy-Item -LiteralPath $sbomSource -Destination $sbomDestination -Force
[void] $generatedReleaseEvidencePaths.Add($sbomDestination)
$sbomSha256 = (Get-FileHash -LiteralPath $sbomDestination -Algorithm SHA256).Hash.ToLowerInvariant()
$releaseVerificationDestination = "$($apk.FullName).release-verification"
if (Test-Path -LiteralPath $releaseVerificationDestination) {
    throw "Refusing to replace release verification evidence: $releaseVerificationDestination"
}
Move-Item -LiteralPath $releaseVerificationStagingDirectory -Destination $releaseVerificationDestination
$releaseVerificationStagingDirectory = $null
$releaseVerificationManifest = Join-Path $releaseVerificationDestination 'release-verification-evidence.json'
if (-not (Test-Path -LiteralPath $releaseVerificationManifest -PathType Leaf)) {
    throw 'Release verification manifest is missing after capture.'
}
$releaseVerificationManifestSha256 = (Get-FileHash -LiteralPath $releaseVerificationManifest -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceMaterialInfos = [ordered]@{
    'release-policy.properties' = Get-GitBlobInfo $headRevision 'release-policy.properties'
    'gradle/verification-metadata.xml' = Get-GitBlobInfo $headRevision 'gradle/verification-metadata.xml'
    'gradle/wrapper/gradle-wrapper.properties' = Get-GitBlobInfo $headRevision 'gradle/wrapper/gradle-wrapper.properties'
    'gradlew.bat' = Get-GitBlobInfo $headRevision 'gradlew.bat'
    'scripts/Invoke-GradleVerified.ps1' = Get-GitBlobInfo $headRevision 'scripts/Invoke-GradleVerified.ps1'
    'scripts/GradleDistribution.Common.ps1' = Get-GitBlobInfo $headRevision 'scripts/GradleDistribution.Common.ps1'
    'scripts/ProcessEnvironment.Common.ps1' = Get-GitBlobInfo $headRevision 'scripts/ProcessEnvironment.Common.ps1'
    'scripts/Publish-TaggedRelease.ps1' = Get-GitBlobInfo $headRevision 'scripts/Publish-TaggedRelease.ps1'
    'scripts/Resolve-GradleAsciiWorkspace.ps1' = Get-GitBlobInfo $headRevision 'scripts/Resolve-GradleAsciiWorkspace.ps1'
    'scripts/Test-DeliveryMetadata.ps1' = Get-GitBlobInfo $headRevision 'scripts/Test-DeliveryMetadata.ps1'
    'scripts/Test-ReleaseDependencyVerificationGate.ps1' = Get-GitBlobInfo $headRevision 'scripts/Test-ReleaseDependencyVerificationGate.ps1'
    'scripts/ReleaseSigning.Common.ps1' = Get-GitBlobInfo $headRevision 'scripts/ReleaseSigning.Common.ps1'
    'scripts/ReleaseProvenanceSignature.java' = Get-GitBlobInfo $headRevision 'scripts/ReleaseProvenanceSignature.java'
    'scripts/Invoke-ReleaseVerificationEvidence.ps1' = Get-GitBlobInfo $headRevision 'scripts/Invoke-ReleaseVerificationEvidence.ps1'
    'scripts/Invoke-InstrumentationWithEvidence.ps1' = Get-GitBlobInfo $headRevision 'scripts/Invoke-InstrumentationWithEvidence.ps1'
    'scripts/Test-ReleaseEvidenceIntegrity.ps1' = Get-GitBlobInfo $headRevision 'scripts/Test-ReleaseEvidenceIntegrity.ps1'
    'scripts/Test-GradleDistributionSeeding.ps1' = Get-GitBlobInfo $headRevision 'scripts/Test-GradleDistributionSeeding.ps1'
    'scripts/Test-ProcessEnvironmentRestoration.ps1' = Get-GitBlobInfo $headRevision 'scripts/Test-ProcessEnvironmentRestoration.ps1'
    'scripts/Test-PowerShellParser.ps1' = Get-GitBlobInfo $headRevision 'scripts/Test-PowerShellParser.ps1'
    'scripts/New-SignedReleaseTag.ps1' = Get-GitBlobInfo $headRevision 'scripts/New-SignedReleaseTag.ps1'
    'scripts/Finalize-ReleaseEvidence.ps1' = Get-GitBlobInfo $headRevision 'scripts/Finalize-ReleaseEvidence.ps1'
    'release-tag-allowed-signers' = Get-GitBlobInfo $headRevision 'release-tag-allowed-signers'
}
$javaLines = @(& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | ForEach-Object { [string] $_ })
$javaVersion = ($javaLines | Select-Object -First 1).Trim()
$buildFinishedUtc = [DateTime]::UtcNow
$attestedGradleArguments = @(
    'clean',
    ':app:assembleRelease',
    ':app:generateReleaseSbom',
    '--no-configuration-cache',
    '--no-build-cache',
    '--console=plain'
)
if ($EnableRepositoryMirrors) {
    $attestedGradleArguments += '-Pfitness.enableRepositoryMirrors=true'
}
$attestedGradleArguments += @(
    '--no-daemon',
    '--stacktrace',
    '--dependency-verification=strict'
)
$attestedGradleArguments += @(
    '--project-cache-dir',
    '<isolated-project-cache>',
    '-Pkotlin.project.persistent.dir=<isolated-kotlin-persistent>'
)
$attestedMaterials = [Collections.Generic.List[object]]::new()
foreach ($materialName in $sourceMaterialInfos.Keys) {
    $attestedMaterials.Add((New-AttestedGitMaterial $materialName $sourceMaterialInfos[$materialName] $headRevision))
}
$attestedMaterials.Add([ordered]@{ name = "android-build-tools/$buildToolsVersion/aapt2.exe"; digest = [ordered]@{ sha256 = $aapt2Sha256 } })
$attestedMaterials.Add([ordered]@{ name = "android-build-tools/$buildToolsVersion/lib/apksigner.jar"; digest = [ordered]@{ sha256 = $apksignerJarSha256 } })
$attestedMaterials.Add([ordered]@{ name = "android-build-tools/$buildToolsVersion/zipalign.exe"; digest = [ordered]@{ sha256 = $zipalignSha256 } })
$attestedMaterials.Add([ordered]@{ name = 'jdk/bin/java.exe'; digest = [ordered]@{ sha256 = $javaExecutableSha256 } })

$attestationPath = "$($apk.FullName).attestation.json"
$attestation = [ordered]@{
    _type = 'https://in-toto.io/Statement/v1'
    subject = @([ordered]@{
        name = $apk.Name
        digest = [ordered]@{ sha256 = $sha256 }
    })
    predicateType = 'https://com.personal.fitnessledger/attestation/release/v1'
    predicate = [ordered]@{
        materialHashMode = 'git-blob-bytes-sha256'
        release = [ordered]@{
            applicationId = $expectedApplicationId
            versionName = $provenance['versionName']
            versionCode = $provenance['versionCode']
            gitCommit = $headRevision
            gitTag = $releaseTag
            gitTagObject = $tagObjectSha
            gitTagSigningPrincipal = $tagSignature.principal
            gitTagSigningSshKeyFingerprint = $tagSignature.fingerprint
            signingCertificateSha256 = $actualCertificate
        }
        build = [ordered]@{
            entryPoint = 'scripts/Publish-TaggedRelease.ps1'
            arguments = $attestedGradleArguments
            isolatedGradleUserHome = $true
            isolatedProjectCache = $true
            gradleDistributionAcquisitionMode = $gradleDistributionAcquisitionMode
            gradleDistributionArchiveSha256 = $gradleDistributionArchiveSha256
            gradleDistributionArchiveBytes = $gradleDistributionArchiveBytes
            dependencyCachesSeeded = $false
            gradleBuildCacheEnabled = $false
            gradleConfigurationCacheEnabled = $false
            gradleDependencyVerificationMode = 'strict'
            kotlinTaskCachingEnabled = $false
            kotlinIncrementalCompilationEnabled = $false
            kotlinCompilerExecutionStrategy = 'in-process'
            startedOn = $buildStartedUtc.ToString('o')
            gradleFinishedOn = $gradleFinishedUtc.ToString('o')
            finishedOn = $buildFinishedUtc.ToString('o')
            invocationId = [Guid]::NewGuid().ToString()
        }
        externalSigning = [ordered]@{
            mode = 'external-apksigner-after-gradle-exit'
            credentialSource = $credentialSource
            gradleArtifactWasUnsigned = $true
            gradleSigningEnvironmentCleared = $true
            gradleProcessExitedBeforeCredentialResolution = $true
            unsignedGradleApkSha256 = $gradleUnsignedSha256
            alignedUnsignedApkSha256 = $alignedUnsignedSha256
            zipalignArguments = @(
                '-f', '-P', '16', '-v', '4',
                '<gradle-unsigned-apk>', '<aligned-unsigned-apk>'
            )
            apksignerArguments = @(
                'sign',
                '--ks', '<redacted-keystore-path>',
                '--ks-key-alias', '<redacted-key-alias>',
                '--ks-pass', "env:$storePasswordEnvironmentName",
                '--key-pass', "env:$keyPasswordEnvironmentName",
                '--v1-signing-enabled', 'false',
                '--v2-signing-enabled', 'true',
                '--v3-signing-enabled', 'false',
                '--v4-signing-enabled', 'false',
                '--debuggable-apk-permitted', 'false',
                '--alignment-preserved', 'true',
                '--out', '<signed-staging-apk>',
                '--in', '<aligned-unsigned-apk>'
            )
            passwordTransport = 'temporary dedicated process environment names; values omitted'
            passwordValuesPresentInArgumentsOrEvidence = $false
        }
        toolchain = [ordered]@{
            gradle = $gradleVersion
            gradleDistributionSha256 = Require-Value $wrapper 'distributionSha256Sum'
            androidGradlePlugin = $agpVersion
            kotlinGradlePlugin = $kotlinVersion
            java = $javaVersion
            javaExecutableSha256 = $javaExecutableSha256
            androidBuildTools = $buildToolsVersion
            androidAapt2Sha256 = $aapt2Sha256
            androidApkSignerJarSha256 = $apksignerJarSha256
            androidZipAlignSha256 = $zipalignSha256
        }
        materials = @($attestedMaterials)
        byproducts = @([ordered]@{
            name = [IO.Path]::GetFileName($sbomDestination)
            mediaType = 'application/vnd.cyclonedx+json'
            digest = [ordered]@{ sha256 = $sbomSha256 }
        }, [ordered]@{
            name = 'release-verification/release-verification-evidence.json'
            mediaType = 'application/json'
            digest = [ordered]@{ sha256 = $releaseVerificationManifestSha256 }
        })
        trustBoundary = [ordered]@{
            externallySigned = $false
            attestationCryptographicallySigned = $true
            apkSignedOutsideGradle = $true
            apkSigningCertificatePinnedAndVerified = $true
            externalTrustRoot = 'repository-pinned local APK certificate and SSH tag public key; no public transparency log'
            gitTagSignatureVerified = $true
            statement = 'The final detached provenance manifest is signed by the long-term APK key after delivery-gate capture. This is a repository-pinned local identity, not an external authority, public transparency log, or third-party timestamp.'
        }
    }
}
Write-Utf8Json $attestationPath $attestation
[void] $generatedReleaseEvidencePaths.Add($attestationPath)
$attestationSha256 = (Get-FileHash -LiteralPath $attestationPath -Algorithm SHA256).Hash.ToLowerInvariant()

$evidenceChecksumPath = "$($apk.FullName).evidence.sha256"
$evidenceLines = @(
    "$sha256 *$($apk.Name)",
    "$sbomSha256 *$([IO.Path]::GetFileName($sbomDestination))",
    "$attestationSha256 *$([IO.Path]::GetFileName($attestationPath))"
) -join [Environment]::NewLine
[IO.File]::WriteAllText(
    $evidenceChecksumPath,
    $evidenceLines + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false)
)
[void] $generatedReleaseEvidencePaths.Add($evidenceChecksumPath)

Write-Host 'PASS: tagged release provenance, certificate policy, SBOM, and local attestation gate'
Write-Host "  tag=$releaseTag"
Write-Host "  revision=$headRevision"
Write-Host "  versionName=$($provenance['versionName'])"
Write-Host "  versionCode=$($provenance['versionCode'])"
Write-Host "  certificateSha256=$actualCertificate"
Write-Host "  apkSha256=$sha256"
Write-Host "  apk=$($apk.FullName)"
Write-Host "  sbom=$sbomDestination"
Write-Host "  attestation=$attestationPath"
Write-Host "  evidenceChecksums=$evidenceChecksumPath"
Write-Warning 'Build phase complete. The artifact is not deliverable until Publish-TaggedRelease.ps1 -FinalizeDeliveryEvidence creates and verifies the detached provenance signature.'

Remove-Item -LiteralPath $unsignedApk.FullName -Force
$publishSucceeded = $true
} finally {
    foreach ($name in $temporaryPasswordEnvironmentNames) {
        Clear-ProcessEnvironmentVariable $name
    }
    if (-not $publishSucceeded) {
        foreach ($path in $generatedReleaseEvidencePaths) {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
        if ($null -ne $releaseVerificationDestination -and
            (Test-Path -LiteralPath $releaseVerificationDestination -PathType Container)) {
            Remove-Item -LiteralPath $releaseVerificationDestination -Recurse -Force -ErrorAction SilentlyContinue
        }
        if ($finalApkCreated -and $null -ne $finalApkPath -and
            (Test-Path -LiteralPath $finalApkPath -PathType Leaf)) {
            Remove-Item -LiteralPath $finalApkPath -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($path in @($alignedUnsignedPath, $stagedSignedPath)) {
        if ($null -ne $path -and (Test-Path -LiteralPath $path -PathType Leaf)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
    if ($null -ne $releaseVerificationStagingDirectory -and
        (Test-Path -LiteralPath $releaseVerificationStagingDirectory -PathType Container)) {
        Remove-Item -LiteralPath $releaseVerificationStagingDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
    foreach ($name in $temporaryPasswordEnvironmentNames) {
        Restore-ProcessEnvironmentVariable $name $temporaryPasswordEnvironmentBefore[$name]
    }
    foreach ($name in $script:ReleaseSigningEnvironmentNames) {
        Restore-ProcessEnvironmentVariable $name $releaseSigningEnvironmentBefore[$name]
    }
    $payload = $null
    $signingStorePassword = $null
    $signingKeyPassword = $null
    $signingKeyAlias = $null
}
exit 0
