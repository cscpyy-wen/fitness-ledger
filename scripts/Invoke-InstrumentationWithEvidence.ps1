[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._:-]+$')]
    [string] $Serial,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int] $ExpectedTestCount,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string] $ExpectedRevision,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $OutputDirectory,

    [string] $ReleaseApkPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ReleaseSigning.Common.ps1')
. (Join-Path $PSScriptRoot 'ProcessEnvironment.Common.ps1')

function Invoke-GitText([string[]] $Arguments) {
    $output = @(& git -C $script:RepoRoot @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
    return @($output | ForEach-Object { ([string] $_).Trim() } | Where-Object { $_ -ne '' })
}

function Find-AndroidSdkRoot {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $propertiesPath = Join-Path $script:RepoRoot 'local.properties'
    $line = Get-Content -LiteralPath $propertiesPath -Encoding UTF8 |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($null -eq $line) { throw 'Android SDK not found.' }
    $candidate = $line.Substring('sdk.dir='.Length) -replace '\\:', ':' -replace '\\\\', '\'
    if (-not (Test-Path -LiteralPath $candidate -PathType Container)) { throw "Android SDK path does not exist: $candidate" }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Invoke-AdbText([string] $Adb, [string[]] $Arguments) {
    $output = @(& $Adb -s $script:Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb -s $script:Serial $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
    return ($output | ForEach-Object { [string] $_ }) -join "`n"
}

function Write-Utf8([string] $Path, [string] $Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

$RepoRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
$headRevision = (Invoke-GitText @('rev-parse', 'HEAD') | Select-Object -First 1)
if ($headRevision -cne $ExpectedRevision) { throw "HEAD '$headRevision' does not match explicitly expected revision '$ExpectedRevision'." }
$status = @(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all'))
if ($status.Count -ne 0) { throw "Instrumentation evidence requires a clean source tree: $($status -join ', ')" }

$OutputDirectory = Assert-PathOutsideRepository $RepoRoot $OutputDirectory 'Instrumentation evidence output'
if (Test-Path -LiteralPath $OutputDirectory) { throw "Refusing to reuse instrumentation evidence directory: $OutputDirectory" }
$outputParent = Split-Path -Parent $OutputDirectory
if (-not (Test-Path -LiteralPath $outputParent -PathType Container)) { New-Item -ItemType Directory -Path $outputParent | Out-Null }
$stagingDirectory = "$OutputDirectory.staging-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $stagingDirectory | Out-Null

$sdkRoot = Find-AndroidSdkRoot
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw "adb.exe is missing: $adb" }
$adbHash = (Get-FileHash -LiteralPath $adb -Algorithm SHA256).Hash.ToUpperInvariant()
$adbVersion = @(& $adb version 2>&1 | ForEach-Object { [string] $_ })
if ($LASTEXITCODE -ne 0) { throw "adb version failed: $($adbVersion -join [Environment]::NewLine)" }

$deviceList = @(& $adb devices -l 2>&1 | ForEach-Object { [string] $_ })
if ($LASTEXITCODE -ne 0) { throw "adb devices failed: $($deviceList -join [Environment]::NewLine)" }
$deviceMatches = @($deviceList | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device(?:\s|$)" })
if ($deviceMatches.Count -ne 1) { throw "Target '$Serial' is not exactly one online adb device: $($deviceList -join [Environment]::NewLine)" }

$device = [ordered]@{
    serial = $Serial
    state = (Invoke-AdbText $adb @('get-state')).Trim()
    apiLevel = [int]((Invoke-AdbText $adb @('shell', 'getprop', 'ro.build.version.sdk')).Trim())
    androidRelease = (Invoke-AdbText $adb @('shell', 'getprop', 'ro.build.version.release')).Trim()
    fingerprint = (Invoke-AdbText $adb @('shell', 'getprop', 'ro.build.fingerprint')).Trim()
    manufacturer = (Invoke-AdbText $adb @('shell', 'getprop', 'ro.product.manufacturer')).Trim()
    model = (Invoke-AdbText $adb @('shell', 'getprop', 'ro.product.model')).Trim()
    abi = (Invoke-AdbText $adb @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
}
$deviceText = @(
    "serial=$($device.serial)",
    "state=$($device.state)",
    "apiLevel=$($device.apiLevel)",
    "androidRelease=$($device.androidRelease)",
    "fingerprint=$($device.fingerprint)",
    "manufacturer=$($device.manufacturer)",
    "model=$($device.model)",
    "abi=$($device.abi)"
) -join "`n"
Write-Utf8 (Join-Path $stagingDirectory 'device.properties') ($deviceText + "`n")

$releaseArtifact = $null
if (-not [string]::IsNullOrWhiteSpace($ReleaseApkPath)) {
    $ReleaseApkPath = (Resolve-Path -LiteralPath $ReleaseApkPath).Path
    $ReleaseApkPath = Assert-PathOutsideRepository $RepoRoot $ReleaseApkPath 'ReleaseApkPath preserved copy'
    $releaseArtifact = [ordered]@{
        fileName = [IO.Path]::GetFileName($ReleaseApkPath)
        sha256 = (Get-FileHash -LiteralPath $ReleaseApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
        bytes = (Get-Item -LiteralPath $ReleaseApkPath).Length
        directlyExercisedByThisRun = $false
    }
}

$startedUtc = [DateTime]::UtcNow
$previousSerial = Get-FitnessProcessEnvironmentVariableState 'ANDROID_SERIAL'
$gradleLogPath = Join-Path $stagingDirectory 'gradle-connected-debug-android-test.log'
try {
    [Environment]::SetEnvironmentVariable('ANDROID_SERIAL', $Serial, 'Process')
    $gradleOutput = @(& (Join-Path $PSScriptRoot 'Invoke-GradleVerified.ps1') `
        -Tasks @('clean', ':app:connectedDebugAndroidTest') `
        -GradleArguments @('--rerun-tasks') 2>&1 | ForEach-Object { [string] $_ })
    $gradleExitCode = $LASTEXITCODE
    Write-Utf8 $gradleLogPath (($gradleOutput -join [Environment]::NewLine) + [Environment]::NewLine)
} finally {
    Restore-FitnessProcessEnvironmentVariable $previousSerial
}
if ($gradleExitCode -ne 0) { throw "connectedDebugAndroidTest failed with exit code $gradleExitCode; raw log retained in staging: $gradleLogPath" }
$finishedUtc = [DateTime]::UtcNow
$postHeadRevision = (Invoke-GitText @('rev-parse', 'HEAD') | Select-Object -First 1)
$postStatus = @(Invoke-GitText @('status', '--porcelain=v1', '--untracked-files=all'))
if ($postHeadRevision -cne $ExpectedRevision -or $postStatus.Count -ne 0) {
    throw "Source/index changed during instrumentation: revision=$postHeadRevision entries=$($postStatus -join ', ')"
}
if ($null -ne $releaseArtifact) {
    $postReleaseHash = (Get-FileHash -LiteralPath $ReleaseApkPath -Algorithm SHA256).Hash.ToUpperInvariant()
    $postReleaseBytes = (Get-Item -LiteralPath $ReleaseApkPath).Length
    if ($postReleaseHash -cne [string] $releaseArtifact.sha256 -or
        $postReleaseBytes -ne [long] $releaseArtifact.bytes) {
        throw 'The preserved release APK changed during instrumentation.'
    }
}

$resultRoot = Join-Path $RepoRoot 'app\build\outputs\androidTest-results\connected'
$xmlFiles = @(
    Get-ChildItem -LiteralPath $resultRoot -File -Filter 'TEST-*.xml' -Recurse -ErrorAction Stop |
        Where-Object { $_.LastWriteTimeUtc -ge $startedUtc.AddSeconds(-2) }
)
if ($xmlFiles.Count -eq 0) { throw "No instrumentation JUnit XML was produced under $resultRoot" }
$rawResults = Join-Path $stagingDirectory 'raw-junit'
New-Item -ItemType Directory -Path $rawResults | Out-Null
$tests = 0
$failures = 0
$errors = 0
$skipped = 0
$xmlEvidence = [Collections.Generic.List[object]]::new()
foreach ($file in $xmlFiles) {
    [xml] $xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    $suites = @($xml.SelectNodes('/testsuite'))
    if ($suites.Count -ne 1) { throw "Expected one top-level testsuite in $($file.FullName)." }
    $suite = $suites[0]
    $tests += [int] $suite.tests
    $failures += [int] $suite.failures
    $errors += [int] $suite.errors
    $skipped += [int] $suite.skipped
    $relative = [IO.Path]::GetRelativePath($resultRoot, $file.FullName)
    $destination = Join-Path $rawResults $relative
    $destinationParent = Split-Path -Parent $destination
    if (-not (Test-Path -LiteralPath $destinationParent -PathType Container)) { New-Item -ItemType Directory -Path $destinationParent | Out-Null }
    Copy-Item -LiteralPath $file.FullName -Destination $destination
    $xmlEvidence.Add([ordered]@{
        path = ([IO.Path]::GetRelativePath($stagingDirectory, $destination) -replace '\\', '/')
        sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToUpperInvariant()
        bytes = (Get-Item -LiteralPath $destination).Length
    })
}
if ($tests -ne $ExpectedTestCount -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
    throw "Instrumentation totals do not match the required clean run: expected=$ExpectedTestCount tests=$tests failures=$failures errors=$errors skipped=$skipped"
}

$appApk = Join-Path $RepoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk = Join-Path $RepoRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
foreach ($path in @($appApk, $testApk)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Expected tested APK is missing: $path" }
}
$testedArtifactDirectory = Join-Path $stagingDirectory 'tested-artifacts'
New-Item -ItemType Directory -Path $testedArtifactDirectory | Out-Null
$evidenceAppApk = Join-Path $testedArtifactDirectory 'app-debug.apk'
$evidenceTestApk = Join-Path $testedArtifactDirectory 'app-debug-androidTest.apk'
Copy-Item -LiteralPath $appApk -Destination $evidenceAppApk
Copy-Item -LiteralPath $testApk -Destination $evidenceTestApk
$testedArtifacts = @(
    [ordered]@{
        role = 'debug-app-under-test'
        fileName = [IO.Path]::GetFileName($evidenceAppApk)
        path = 'tested-artifacts/app-debug.apk'
        sha256 = (Get-FileHash -LiteralPath $evidenceAppApk -Algorithm SHA256).Hash.ToUpperInvariant()
        bytes = (Get-Item -LiteralPath $evidenceAppApk).Length
    },
    [ordered]@{
        role = 'debug-instrumentation-test'
        fileName = [IO.Path]::GetFileName($evidenceTestApk)
        path = 'tested-artifacts/app-debug-androidTest.apk'
        sha256 = (Get-FileHash -LiteralPath $evidenceTestApk -Algorithm SHA256).Hash.ToUpperInvariant()
        bytes = (Get-Item -LiteralPath $evidenceTestApk).Length
    }
)
$manifestPath = Join-Path $stagingDirectory 'instrumentation-evidence.json'
$manifest = [ordered]@{
    schema = 'fitness-ledger-instrumentation-evidence-v1'
    status = 'PASS'
    source = [ordered]@{
        revision = $ExpectedRevision
        revisionAfterRun = $postHeadRevision
        clean = $true
        cleanBeforeRun = $true
        cleanAfterRun = $true
    }
    invocation = [ordered]@{
        tasks = @('clean', ':app:connectedDebugAndroidTest')
        expectedTestCount = $ExpectedTestCount
        startedUtc = $startedUtc.ToString('o')
        finishedUtc = $finishedUtc.ToString('o')
        androidSerialWasExplicit = $true
        outputLocationOutsideRepository = $true
        releaseReferenceOutsideRepository = ($null -ne $releaseArtifact)
    }
    device = $device
    result = [ordered]@{ tests = $tests; failures = $failures; errors = $errors; skipped = $skipped }
    testedArtifacts = $testedArtifacts
    releaseArtifactReference = $releaseArtifact
    tools = [ordered]@{
        adbSha256 = $adbHash
        adbVersion = $adbVersion
        verifiedGradleEntryPoint = 'scripts/Invoke-GradleVerified.ps1'
    }
    rawEvidence = [ordered]@{
        gradleLog = [ordered]@{
            path = 'gradle-connected-debug-android-test.log'
            sha256 = (Get-FileHash -LiteralPath $gradleLogPath -Algorithm SHA256).Hash.ToUpperInvariant()
            bytes = (Get-Item -LiteralPath $gradleLogPath).Length
        }
        deviceProperties = [ordered]@{
            path = 'device.properties'
            sha256 = (Get-FileHash -LiteralPath (Join-Path $stagingDirectory 'device.properties') -Algorithm SHA256).Hash.ToUpperInvariant()
            bytes = (Get-Item -LiteralPath (Join-Path $stagingDirectory 'device.properties')).Length
        }
        junitXml = @($xmlEvidence)
    }
    boundary = 'This run directly exercises the recorded debug app/test APKs. A referenced release APK is hash-bound only and is not claimed to have been instrumented.'
}
Write-Utf8 $manifestPath (($manifest | ConvertTo-Json -Depth 20) + "`n")

$checksumFiles = @(Get-ChildItem -LiteralPath $stagingDirectory -File -Recurse | Sort-Object FullName)
$checksumLines = foreach ($file in $checksumFiles) {
    $relative = [IO.Path]::GetRelativePath($stagingDirectory, $file.FullName) -replace '\\', '/'
    "$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToUpperInvariant())  $relative"
}
Write-Utf8 (Join-Path $stagingDirectory 'SHA256SUMS.txt') (($checksumLines -join "`n") + "`n")
Move-Item -LiteralPath $stagingDirectory -Destination $OutputDirectory

Write-Host 'PASS: instrumentation run and machine-readable evidence captured.'
Write-Host "  device=$Serial"
Write-Host "  api=$($device.apiLevel)"
Write-Host "  revision=$ExpectedRevision"
Write-Host "  tests=$tests"
Write-Host '  failures=0'
Write-Host '  errors=0'
Write-Host '  skipped=0'
Write-Host "  evidence=$OutputDirectory"
exit 0
