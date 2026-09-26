function Get-FitnessProcessEnvironmentVariableState {
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
        [string] $Name
    )

    return [pscustomobject]@{
        name = $Name
        wasPresent = Test-Path -LiteralPath "Env:$Name"
        value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
}

function Clear-FitnessProcessEnvironmentVariable {
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
        [string] $Name
    )

    Remove-Item -LiteralPath "Env:$Name" -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath "Env:$Name") {
        throw "Could not clear process environment variable: $Name"
    }
}

function Restore-FitnessProcessEnvironmentVariable {
    param(
        [Parameter(Mandatory = $true)]
        [object] $State
    )

    if ([string]::IsNullOrWhiteSpace([string] $State.name)) {
        throw 'Environment-variable restore state has no name.'
    }
    if ([bool] $State.wasPresent) {
        [Environment]::SetEnvironmentVariable([string] $State.name, [string] $State.value, 'Process')
    } else {
        Clear-FitnessProcessEnvironmentVariable ([string] $State.name)
    }
}

function Test-FitnessPathWithinBoundary {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Candidate,
        [Parameter(Mandatory = $true)]
        [string] $Boundary
    )

    $candidatePath = [IO.Path]::GetFullPath($Candidate).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $boundaryPath = [IO.Path]::GetFullPath($Boundary).TrimEnd([IO.Path]::DirectorySeparatorChar)
    return $candidatePath.Equals($boundaryPath, [StringComparison]::OrdinalIgnoreCase) -or
        $candidatePath.StartsWith($boundaryPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-FitnessGradleUserHomeSafe {
    param(
        [Parameter(Mandatory = $true)]
        [object] $State,
        [Parameter(Mandatory = $true)]
        [string] $RepositoryRoot,
        [Parameter(Mandatory = $true)]
        [string] $JunctionPath
    )

    if (-not [bool] $State.wasPresent) { return }
    $value = [string] $State.value
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw 'GRADLE_USER_HOME is present but empty; Gradle would treat the current workspace as its user home.'
    }
    if (-not [IO.Path]::IsPathFullyQualified($value)) {
        throw "GRADLE_USER_HOME must be an absolute path: $value"
    }
    $resolved = [IO.Path]::GetFullPath($value).TrimEnd([IO.Path]::DirectorySeparatorChar)
    foreach ($unsafeBoundary in @($RepositoryRoot, $JunctionPath)) {
        if (Test-FitnessPathWithinBoundary $resolved $unsafeBoundary) {
            throw "GRADLE_USER_HOME must stay outside the repository and its ASCII junction: $resolved"
        }
    }
}
