. (Join-Path $PSScriptRoot 'ProcessEnvironment.Common.ps1')

function Read-FitnessKeyValueFile {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

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

function ConvertTo-FitnessBase36 {
    param(
        [Parameter(Mandatory = $true)]
        [Numerics.BigInteger] $Value
    )

    if ($Value -lt [Numerics.BigInteger]::Zero) { throw 'Base-36 input cannot be negative.' }
    if ($Value -eq [Numerics.BigInteger]::Zero) { return '0' }
    $characters = '0123456789abcdefghijklmnopqrstuvwxyz'
    $result = ''
    $divisor = [Numerics.BigInteger]::new(36)
    while ($Value -gt [Numerics.BigInteger]::Zero) {
        $remainder = [int]($Value % $divisor)
        $result = $characters[$remainder] + $result
        $Value = [Numerics.BigInteger]::Divide($Value, $divisor)
    }
    return $result
}

function Get-FitnessGradleDistributionCacheKey {
    param(
        [Parameter(Mandatory = $true)]
        [string] $DistributionUrl
    )

    $digest = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes($DistributionUrl))
    $number = [Numerics.BigInteger]::new($digest, $true, $true)
    return ConvertTo-FitnessBase36 $number
}

function Copy-FitnessVerifiedGradleDistribution {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ArchivePath,
        [Parameter(Mandatory = $true)]
        [string] $GradleUserHome,
        [Parameter(Mandatory = $true)]
        [string] $WrapperPropertiesPath,
        [Parameter(Mandatory = $true)]
        [string] $RepositoryRoot,
        [Parameter(Mandatory = $true)]
        [string] $JunctionPath
    )

    if (-not [IO.Path]::IsPathFullyQualified($ArchivePath)) {
        throw 'The verified Gradle distribution archive must use an absolute path.'
    }
    $ArchivePath = (Resolve-Path -LiteralPath $ArchivePath -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) {
        throw "The verified Gradle distribution archive is not a file: $ArchivePath"
    }
    foreach ($unsafeBoundary in @($RepositoryRoot, $JunctionPath)) {
        if (Test-FitnessPathWithinBoundary $ArchivePath $unsafeBoundary) {
            throw "The verified Gradle distribution archive must stay outside source paths: $ArchivePath"
        }
    }

    $wrapper = Read-FitnessKeyValueFile $WrapperPropertiesPath
    foreach ($required in @('distributionUrl', 'distributionSha256Sum')) {
        if (-not $wrapper.ContainsKey($required) -or [string]::IsNullOrWhiteSpace([string] $wrapper[$required])) {
            throw "Gradle wrapper property is missing: $required"
        }
    }
    $distributionUrl = ([string] $wrapper.distributionUrl).Replace('\:', ':')
    $uri = [Uri]::new($distributionUrl, [UriKind]::Absolute)
    if ($uri.Scheme -cne 'https') { throw 'The pinned Gradle distribution URL must use HTTPS.' }
    $expectedSha256 = ([string] $wrapper.distributionSha256Sum).ToUpperInvariant()
    if ($expectedSha256 -notmatch '^[0-9A-F]{64}$') { throw 'The Gradle distribution SHA-256 is malformed.' }
    $sourceSha256 = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($sourceSha256 -cne $expectedSha256) {
        throw "The local Gradle distribution archive does not match the wrapper SHA-256: $sourceSha256"
    }

    $archiveName = [IO.Path]::GetFileName($uri.AbsolutePath)
    if ($archiveName -notmatch '^[0-9A-Za-z._-]+\.zip$') { throw "Unsafe Gradle distribution archive name: $archiveName" }
    $distributionName = $archiveName.Substring(0, $archiveName.Length - '.zip'.Length)
    $normalizedDistributionUrl = $uri.AbsoluteUri
    $cacheKey = Get-FitnessGradleDistributionCacheKey $normalizedDistributionUrl
    $destinationDirectory = Join-Path $GradleUserHome "wrapper\dists\$distributionName\$cacheKey"
    New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
    $destination = Join-Path $destinationDirectory $archiveName
    if (Test-Path -LiteralPath $destination) { throw "Refusing to replace a seeded Gradle distribution: $destination" }
    Copy-Item -LiteralPath $ArchivePath -Destination $destination
    $destinationSha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($destinationSha256 -cne $expectedSha256) {
        throw 'The copied Gradle distribution archive failed its post-copy SHA-256 check.'
    }

    return [pscustomobject]@{
        distributionUrl = $normalizedDistributionUrl
        archiveName = $archiveName
        cacheKey = $cacheKey
        sha256 = $destinationSha256
        bytes = (Get-Item -LiteralPath $destination).Length
        destination = $destination
    }
}
