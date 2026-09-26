[CmdletBinding()]
param(
    [string] $JunctionBase = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

function Test-AsciiPath([string] $Path) {
    return $Path -cmatch '^[\x00-\x7F]+$'
}

function Get-NormalizedPath([string] $Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
}

function Assert-SameFile([string] $Source, [string] $ViaJunction) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf) -or
        -not (Test-Path -LiteralPath $ViaJunction -PathType Leaf)) {
        throw "Workspace identity file is missing: $Source"
    }
    $sourceHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
    $junctionHash = (Get-FileHash -LiteralPath $ViaJunction -Algorithm SHA256).Hash
    if ($sourceHash -cne $junctionHash) { throw "Workspace junction identity mismatch: $Source" }
}

$repoRoot = Get-NormalizedPath (Split-Path -Parent $PSScriptRoot)
$gitRoot = (& git -C $repoRoot rev-parse --show-toplevel 2>$null | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($gitRoot)) { throw "Repository root is not a Git worktree: $repoRoot" }
$gitRoot = Get-NormalizedPath $gitRoot
if (-not $gitRoot.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    # Git canonicalizes a directory junction to the physical worktree. The
    # wrapper sets FITNESS_GRADLE_ASCII_ACTIVE before re-entering, so this case
    # is only useful for a direct diagnostic invocation through a junction.
    $item = Get-Item -LiteralPath $repoRoot -Force
    if ($item.LinkType -ne 'Junction' -or
        -not (Get-NormalizedPath ([string] $item.Target)).Equals($gitRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Resolver must run from its owning repository: $gitRoot != $repoRoot"
    }
}

$policy = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $repoRoot 'release-policy.properties') -Encoding UTF8) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*[#!]') { continue }
    $separator = $line.IndexOf('=')
    if ($separator -le 0) { throw 'Malformed release-policy.properties.' }
    $policy[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
}
$expectedJavaHash = ([string] $policy['javaExecutableSha256']).ToUpperInvariant()
if ($expectedJavaHash -notmatch '^[0-9A-F]{64}$') { throw 'Release policy Java SHA-256 is missing or malformed.' }

$javaCandidates = [Collections.Generic.List[string]]::new()
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $javaCandidates.Add($env:JAVA_HOME) }
$localPropertiesPath = Join-Path $repoRoot 'local.properties'
if (Test-Path -LiteralPath $localPropertiesPath -PathType Leaf) {
    $jdkLine = Get-Content -LiteralPath $localPropertiesPath -Encoding UTF8 |
        Where-Object { $_ -match '^jdk\.dir=' } | Select-Object -First 1
    if ($null -ne $jdkLine) {
        $jdkCandidate = $jdkLine.Substring('jdk.dir='.Length) -replace '\\:', ':' -replace '\\\\', '\'
        $javaCandidates.Add($jdkCandidate)
    }
}
$pathJava = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $pathJava) { $javaCandidates.Add((Split-Path -Parent (Split-Path -Parent $pathJava.Source))) }
foreach ($commonHome in @(
    (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
    (Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr')
)) {
    if (-not [string]::IsNullOrWhiteSpace($commonHome)) { $javaCandidates.Add($commonHome) }
}
$gradleJdks = Join-Path $env:USERPROFILE '.gradle\jdks'
if (Test-Path -LiteralPath $gradleJdks -PathType Container) {
    foreach ($java in Get-ChildItem -LiteralPath $gradleJdks -Filter 'java.exe' -File -Recurse -ErrorAction SilentlyContinue) {
        $javaCandidates.Add((Split-Path -Parent (Split-Path -Parent $java.FullName)))
    }
}
$resolvedJavaHome = $null
foreach ($candidate in @($javaCandidates | Select-Object -Unique)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $candidateHome = Get-NormalizedPath $candidate
    $javaExecutable = Join-Path $candidateHome 'bin\java.exe'
    if ((Test-Path -LiteralPath $javaExecutable -PathType Leaf) -and
        ((Get-FileHash -LiteralPath $javaExecutable -Algorithm SHA256).Hash.ToUpperInvariant() -ceq $expectedJavaHash)) {
        $resolvedJavaHome = $candidateHome
        break
    }
}
if ($null -eq $resolvedJavaHome) {
    throw 'No JDK matching release-policy.properties javaExecutableSha256 was found via JAVA_HOME, local.properties jdk.dir, PATH, Android Studio, or Gradle JDKs.'
}
Write-Output "FITNESS_JAVA_HOME=$resolvedJavaHome"

if (Test-AsciiPath $repoRoot) {
    Write-Output 'FITNESS_ASCII_WORKSPACE=DIRECT'
    exit 0
}

$repoHashBytes = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($repoRoot))
$repoHash = [Convert]::ToHexString($repoHashBytes).Substring(0, 12).ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($JunctionBase)) {
    $candidate = Join-Path (Split-Path -Parent $repoRoot) '.fitness-ledger-build-links'
    if (-not (Test-AsciiPath $candidate)) { $candidate = Join-Path $env:PUBLIC '.fitness-ledger-build-links' }
    $JunctionBase = $candidate
}
$JunctionBase = Get-NormalizedPath $JunctionBase
if (-not (Test-AsciiPath $JunctionBase)) { throw "ASCII workspace base is not ASCII: $JunctionBase" }
if (-not (Test-Path -LiteralPath $JunctionBase -PathType Container)) {
    New-Item -ItemType Directory -Path $JunctionBase | Out-Null
}
$junctionPath = Join-Path $JunctionBase "fitness-ledger-$repoHash"
if (Test-Path -LiteralPath $junctionPath) {
    $junction = Get-Item -LiteralPath $junctionPath -Force
    if ($junction.LinkType -ne 'Junction') { throw "Refusing to reuse a non-junction path: $junctionPath" }
    $target = Get-NormalizedPath ([string] $junction.Target)
    if (-not $target.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Existing junction targets '$target', expected '$repoRoot'"
    }
} else {
    [void](New-Item -ItemType Junction -Path $junctionPath -Target $repoRoot)
}

foreach ($identityPath in @('gradlew.bat', 'settings.gradle.kts', 'app\build.gradle.kts')) {
    Assert-SameFile (Join-Path $repoRoot $identityPath) (Join-Path $junctionPath $identityPath)
}
$sourceRevision = (& git -C $repoRoot rev-parse HEAD | Select-Object -First 1)
$junctionRevision = (& git -C $junctionPath rev-parse HEAD | Select-Object -First 1)
if ($sourceRevision -notmatch '^[0-9a-f]{40}$' -or $sourceRevision -cne $junctionRevision) {
    throw 'Git revision differs between source workspace and ASCII junction.'
}

Write-Output "FITNESS_ASCII_WORKSPACE=$junctionPath"
exit 0
