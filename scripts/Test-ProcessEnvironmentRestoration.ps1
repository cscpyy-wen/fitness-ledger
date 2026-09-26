[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

. (Join-Path $PSScriptRoot 'ProcessEnvironment.Common.ps1')

$name = 'FITNESS_ENVIRONMENT_RESTORE_SELF_TEST'
$original = Get-FitnessProcessEnvironmentVariableState $name
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd([IO.Path]::DirectorySeparatorChar)
$junction = Join-Path ([IO.Path]::GetTempPath()) 'fitness-environment-restore-self-test-junction'
$external = Join-Path ([IO.Path]::GetTempPath()) 'fitness-environment-restore-self-test-gradle-home'

try {
    Clear-FitnessProcessEnvironmentVariable $name
    $absent = Get-FitnessProcessEnvironmentVariableState $name
    [Environment]::SetEnvironmentVariable($name, 'temporary-value', 'Process')
    Restore-FitnessProcessEnvironmentVariable $absent
    if (Test-Path -LiteralPath "Env:$name") {
        throw 'An originally absent process environment variable was restored as present.'
    }

    [Environment]::SetEnvironmentVariable($name, 'outside-value', 'Process')
    $present = Get-FitnessProcessEnvironmentVariableState $name
    [Environment]::SetEnvironmentVariable($name, 'changed-value', 'Process')
    Restore-FitnessProcessEnvironmentVariable $present
    if ([Environment]::GetEnvironmentVariable($name, 'Process') -cne 'outside-value') {
        throw 'A present process environment variable was not restored byte-for-byte.'
    }

    $safeState = [pscustomobject]@{ name = 'GRADLE_USER_HOME'; wasPresent = $true; value = $external }
    Assert-FitnessGradleUserHomeSafe $safeState $repoRoot $junction

    foreach ($unsafeValue in @('', '.', $repoRoot, (Join-Path $repoRoot 'caches'), $junction, (Join-Path $junction 'daemon'))) {
        $unsafeState = [pscustomobject]@{ name = 'GRADLE_USER_HOME'; wasPresent = $true; value = $unsafeValue }
        $rejected = $false
        try {
            Assert-FitnessGradleUserHomeSafe $unsafeState $repoRoot $junction
        } catch {
            $rejected = $true
        }
        if (-not $rejected) { throw "Unsafe GRADLE_USER_HOME was accepted: '$unsafeValue'" }
    }
} finally {
    Restore-FitnessProcessEnvironmentVariable $original
}

Write-Host 'PASS: process environment restoration preserves absence/presence and rejects unsafe Gradle homes.'
exit 0
