[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string[]] $Tasks = @(':app:assembleRelease'),
    [string[]] $GradleArguments = @(),
    [string] $JunctionBase = '',
    [string] $DpapiBlobPath = '',
    [switch] $Offline,
    [switch] $EnableRepositoryMirrors,
    [switch] $IsolateBuildState,
    [switch] $KeepIsolatedBuildState
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

[Console]::Error.WriteLine(
    'HARD-DISABLED: Gradle release artifacts must remain unsigned. Use Publish-TaggedRelease.ps1 for post-Gradle external signing.'
)
exit 1
