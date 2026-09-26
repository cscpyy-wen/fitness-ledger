[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string[]] $Tasks = @(':app:testDebugUnitTest'),
    [string[]] $GradleArguments = @(),
    [string] $JunctionBase = '',
    [string] $GradleDistributionArchive = '',
    [switch] $Offline,
    [switch] $EnableRepositoryMirrors,
    [switch] $IsolateBuildState,
    [switch] $KeepIsolatedBuildState
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ProcessEnvironment.Common.ps1')
. (Join-Path $PSScriptRoot 'GradleDistribution.Common.ps1')

function Test-AsciiPath([string] $Path) {
    return $Path -cmatch '^[\x00-\x7F]+$'
}

function Get-NormalizedPath([string] $Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
}

function Assert-SameFile([string] $Source, [string] $ViaJunction) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Source identity file is missing: $Source"
    }
    if (-not (Test-Path -LiteralPath $ViaJunction -PathType Leaf)) {
        throw "Junction identity file is missing: $ViaJunction"
    }
    $sourceHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
    $junctionHash = (Get-FileHash -LiteralPath $ViaJunction -Algorithm SHA256).Hash
    if ($sourceHash -cne $junctionHash) {
        throw "Junction identity mismatch: $Source"
    }
}

$repoRoot = Get-NormalizedPath (Split-Path -Parent $PSScriptRoot)
$gitRoot = (& git -C $repoRoot rev-parse --show-toplevel 2>$null | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($gitRoot)) {
    throw "Repository root is not a Git worktree: $repoRoot"
}
$gitRoot = Get-NormalizedPath $gitRoot
if (-not $gitRoot.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Script must run from its owning repository. Expected $repoRoot, Git returned $gitRoot"
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME must point to a supported JDK (17 or newer).'
}

if ($IsolateBuildState -and $Offline) {
    throw 'An isolated build state starts empty and cannot be combined with -Offline.'
}
if (-not [string]::IsNullOrWhiteSpace($GradleDistributionArchive) -and -not $IsolateBuildState) {
    throw 'A verified local Gradle distribution archive is only valid with -IsolateBuildState.'
}
$javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw "JAVA_HOME does not contain bin\java.exe: $env:JAVA_HOME"
}

$repoHashBytes = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($repoRoot))
$repoHash = [Convert]::ToHexString($repoHashBytes).Substring(0, 12).ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($JunctionBase)) {
    $candidate = Join-Path (Split-Path -Parent $repoRoot) '.fitness-ledger-build-links'
    if (-not (Test-AsciiPath $candidate)) {
        $candidate = Join-Path $env:PUBLIC '.fitness-ledger-build-links'
    }
    $JunctionBase = $candidate
}
$JunctionBase = Get-NormalizedPath $JunctionBase
if (-not (Test-AsciiPath $JunctionBase)) {
    throw "The junction base must contain ASCII characters only: $JunctionBase"
}

if (-not (Test-Path -LiteralPath $JunctionBase)) {
    New-Item -ItemType Directory -Path $JunctionBase | Out-Null
}
$junctionPath = Join-Path $JunctionBase "fitness-ledger-$repoHash"
if (Test-Path -LiteralPath $junctionPath) {
    $junction = Get-Item -LiteralPath $junctionPath -Force
    if ($junction.LinkType -ne 'Junction') {
        throw "Refusing to reuse a non-junction path: $junctionPath"
    }
    $junctionTarget = Get-NormalizedPath ([string] $junction.Target)
    if (-not $junctionTarget.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Existing junction targets '$junctionTarget', expected '$repoRoot'"
    }
} else {
    $junction = New-Item -ItemType Junction -Path $junctionPath -Target $repoRoot
}

foreach ($identityPath in @('gradlew.bat', 'settings.gradle.kts', 'app\build.gradle.kts')) {
    Assert-SameFile (Join-Path $repoRoot $identityPath) (Join-Path $junctionPath $identityPath)
}
$sourceRevision = (& git -C $repoRoot rev-parse HEAD | Select-Object -First 1)
$junctionRevision = (& git -C $junctionPath rev-parse HEAD | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($sourceRevision) -or $sourceRevision -cne $junctionRevision) {
    throw 'Git revision differs between the source path and ASCII junction.'
}

$arguments = [Collections.Generic.List[string]]::new()
foreach ($task in $Tasks) { $arguments.Add($task) }
$arguments.Add('--no-configuration-cache')
$arguments.Add('--no-build-cache')
$arguments.Add('--console=plain')
if ($Offline) { $arguments.Add('--offline') }
if ($EnableRepositoryMirrors) { $arguments.Add('-Pfitness.enableRepositoryMirrors=true') }
foreach ($argument in $GradleArguments) {
    if ($argument -in @('--build-cache', '--configuration-cache') -or
        $argument -match '^-[DP]org\.gradle\.(caching|configuration-cache)=true$') {
        throw "Verified Gradle entry point refuses cache-enabling argument: $argument"
    }
    $arguments.Add($argument)
}

Write-Host "Verified source: $repoRoot"
Write-Host "ASCII build path: $junctionPath"
$isolatedRoot = $null
$previousGradleUserHome = Get-FitnessProcessEnvironmentVariableState 'GRADLE_USER_HOME'
if (-not $IsolateBuildState) {
    Assert-FitnessGradleUserHomeSafe $previousGradleUserHome $repoRoot $junctionPath
}
if ($IsolateBuildState) {
    $temporaryRoot = Get-NormalizedPath ([IO.Path]::GetTempPath())
    $isolatedRoot = Join-Path $temporaryRoot "fitness-ledger-release-build-$([Guid]::NewGuid().ToString('N'))"
    $isolatedRoot = Get-NormalizedPath $isolatedRoot
    $expectedPrefix = $temporaryRoot + [IO.Path]::DirectorySeparatorChar + 'fitness-ledger-release-build-'
    if (-not $isolatedRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing unsafe isolated-build path: $isolatedRoot"
    }
    New-Item -ItemType Directory -Path $isolatedRoot | Out-Null

    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $aclArguments = @(
        $isolatedRoot,
        '/inheritance:r',
        '/grant:r',
        "*$currentSid`:(OI)(CI)(F)",
        '*S-1-5-18:(OI)(CI)(F)',
        '*S-1-5-32-544:(OI)(CI)(F)'
    )
    $aclOutput = @(& icacls @aclArguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not restrict isolated build directory ACL: $($aclOutput -join [Environment]::NewLine)"
    }

    $gradleUserHome = Join-Path $isolatedRoot 'gradle-user-home'
    $projectCache = Join-Path $isolatedRoot 'project-cache'
    $kotlinPersistent = Join-Path $isolatedRoot 'kotlin-persistent'
    if (-not [string]::IsNullOrWhiteSpace($GradleDistributionArchive)) {
        $seededDistribution = Copy-FitnessVerifiedGradleDistribution `
            -ArchivePath $GradleDistributionArchive `
            -GradleUserHome $gradleUserHome `
            -WrapperPropertiesPath (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.properties') `
            -RepositoryRoot $repoRoot `
            -JunctionPath $junctionPath
        Write-Host "Seeded verified Gradle distribution: sha256=$($seededDistribution.sha256) bytes=$($seededDistribution.bytes)"
    }
    [Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', $gradleUserHome, 'Process')
    $arguments.Add('--project-cache-dir')
    $arguments.Add($projectCache)
    $arguments.Add("-Pkotlin.project.persistent.dir=$kotlinPersistent")
    Write-Host "Isolated build state: $isolatedRoot"
}
$gradleExitCode = 1
try {
    Push-Location $junctionPath
    try {
        & (Join-Path $junctionPath 'gradlew.bat') @arguments
        $gradleExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    Restore-FitnessProcessEnvironmentVariable $previousGradleUserHome
    if ($null -ne $isolatedRoot -and -not $KeepIsolatedBuildState) {
        $temporaryRoot = Get-NormalizedPath ([IO.Path]::GetTempPath())
        $expectedPrefix = $temporaryRoot + [IO.Path]::DirectorySeparatorChar + 'fitness-ledger-release-build-'
        if (-not $isolatedRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing unsafe isolated-build cleanup path: $isolatedRoot"
        }
        if (Test-Path -LiteralPath $isolatedRoot -PathType Container) {
            Remove-Item -LiteralPath $isolatedRoot -Recurse -Force
        }
    } elseif ($null -ne $isolatedRoot) {
        Write-Warning "Isolated build state retained for inspection: $isolatedRoot"
    }
}
if ($gradleExitCode -ne 0) {
    [Console]::Error.WriteLine("Gradle failed with exit code $gradleExitCode")
    exit $gradleExitCode
}

$junctionBuild = Join-Path $junctionPath 'app\build'
$sourceBuild = Join-Path $repoRoot 'app\build'
$artifactSamples = @(
    Get-ChildItem -LiteralPath $junctionBuild -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName |
        Select-Object -First 8
)
foreach ($artifact in $artifactSamples) {
    $relative = [IO.Path]::GetRelativePath($junctionBuild, $artifact.FullName)
    Assert-SameFile (Join-Path $sourceBuild $relative) $artifact.FullName
}

if ($artifactSamples.Count -eq 0) {
    Write-Host 'PASS: Gradle exit code 0; this task set produced no app/build artifact to sample.'
} else {
    Write-Host "PASS: Gradle exit code 0; verified $($artifactSamples.Count) build artifacts through source and junction paths."
}
exit 0
