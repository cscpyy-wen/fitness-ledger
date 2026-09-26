[CmdletBinding()]
param(
    [string] $JunctionBase = '',
    [switch] $SkipClean
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

$repoRoot = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
if ($repoRoot -cmatch '^[\x00-\x7F]+$') {
    throw "This regression must be executed from a workspace whose real path contains non-ASCII characters: $repoRoot"
}
if ([string]::IsNullOrWhiteSpace($JunctionBase)) {
    $JunctionBase = Join-Path $env:PUBLIC 'Fitness Ledger Path Regression'
}
$JunctionBase = [IO.Path]::GetFullPath($JunctionBase).TrimEnd([IO.Path]::DirectorySeparatorChar)
if ($JunctionBase -cnotmatch '^[\x00-\x7F]+$' -or -not $JunctionBase.Contains(' ', [StringComparison]::Ordinal)) {
    throw "Regression junction base must be ASCII and contain a space: $JunctionBase"
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or
    -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
    throw 'JAVA_HOME must point to the pinned JDK before running the path regression.'
}

$tasks = [Collections.Generic.List[string]]::new()
if (-not $SkipClean) { [void] $tasks.Add('clean') }
[void] $tasks.Add(':app:testDebugUnitTest')
$verifiedGradle = Join-Path $PSScriptRoot 'Invoke-GradleVerified.ps1'
& $verifiedGradle `
    -Tasks $tasks.ToArray() `
    -GradleArguments @('--rerun-tasks') `
    -JunctionBase $JunctionBase
if ($LASTEXITCODE -ne 0) { throw "Unicode/space path Gradle regression failed with exit code $LASTEXITCODE." }

$resultDirectory = Join-Path $repoRoot 'app\build\test-results\testDebugUnitTest'
$xmlFiles = @(Get-ChildItem -LiteralPath $resultDirectory -File -Filter 'TEST-*.xml' -ErrorAction Stop)
if ($xmlFiles.Count -eq 0) { throw "No JUnit XML was produced under $resultDirectory" }
$tests = 0
$failures = 0
$errors = 0
$skipped = 0
foreach ($file in $xmlFiles) {
    $raw = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    if ($raw.Contains('ClassNotFoundException', [StringComparison]::Ordinal)) {
        throw "JUnit worker classpath regression returned in $($file.Name)."
    }
    [xml] $xml = $raw
    $suite = $xml.testsuite
    if ($null -eq $suite) { throw "Unexpected JUnit XML root in $($file.Name)." }
    $tests += [int] $suite.tests
    $failures += [int] $suite.failures
    $errors += [int] $suite.errors
    $skipped += [int] $suite.skipped
}
if ($tests -le 0 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
    throw "JUnit regression totals are not clean: tests=$tests failures=$failures errors=$errors skipped=$skipped"
}

Write-Host 'PASS: non-ASCII source workspace and ASCII build path containing spaces completed a clean forced JVM test run.'
Write-Host "  sourcePath=$repoRoot"
Write-Host "  junctionBase=$JunctionBase"
Write-Host "  tests=$tests"
Write-Host '  failures=0'
Write-Host '  errors=0'
Write-Host '  skipped=0'
exit 0
