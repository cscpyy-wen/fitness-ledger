[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'GradleDistribution.Common.ps1')

$actualUrl = 'https://services.gradle.org/distributions/gradle-8.14.3-all.zip'
$actualCacheKey = Get-FitnessGradleDistributionCacheKey $actualUrl
if ($actualCacheKey -cne '10utluxaxniiv4wxiphsi49nj') {
    throw "Gradle distribution cache-key implementation drifted: $actualCacheKey"
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) "fitness-gradle-seed-test-$([Guid]::NewGuid().ToString('N'))"
$expectedPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar + 'fitness-gradle-seed-test-'
$testRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $testRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing unsafe Gradle seed test path: $testRoot"
}

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $source = Join-Path $testRoot 'source.zip'
    [IO.File]::WriteAllBytes($source, [byte[]](1, 3, 3, 7, 9))
    $sha256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    $wrapper = Join-Path $testRoot 'gradle-wrapper.properties'
    [IO.File]::WriteAllText(
        $wrapper,
        "distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-all.zip`ndistributionSha256Sum=$sha256`n",
        [Text.UTF8Encoding]::new($false)
    )
    $gradleHome = Join-Path $testRoot 'gradle-home'
    $result = Copy-FitnessVerifiedGradleDistribution `
        -ArchivePath $source `
        -GradleUserHome $gradleHome `
        -WrapperPropertiesPath $wrapper `
        -RepositoryRoot (Join-Path $testRoot 'unrelated-repository') `
        -JunctionPath (Join-Path $testRoot 'unrelated-junction')
    if ($result.cacheKey -cne '10utluxaxniiv4wxiphsi49nj' -or
        $result.sha256 -cne $sha256.ToUpperInvariant() -or
        $result.bytes -ne 5 -or
        -not (Test-Path -LiteralPath $result.destination -PathType Leaf)) {
        throw 'Verified Gradle distribution seeding produced unexpected metadata or bytes.'
    }
} finally {
    if (Test-Path -LiteralPath $testRoot -PathType Container) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}

Write-Host 'PASS: Gradle distribution cache key and verified local seeding are deterministic.'
exit 0
