[CmdletBinding()]
param(
    [string] $SigningDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd(
    [IO.Path]::DirectorySeparatorChar
)
if ([string]::IsNullOrWhiteSpace($SigningDirectory)) {
    $SigningDirectory = Join-Path $repoRoot '.signing'
}
$signingPath = [IO.Path]::GetFullPath($SigningDirectory).TrimEnd(
    [IO.Path]::DirectorySeparatorChar
)
$expectedSigningPath = [IO.Path]::GetFullPath((Join-Path $repoRoot '.signing')).TrimEnd(
    [IO.Path]::DirectorySeparatorChar
)
if (-not $signingPath.Equals($expectedSigningPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to change ACL outside the repository .signing directory: $signingPath"
}
if (-not (Test-Path -LiteralPath $signingPath -PathType Container)) {
    New-Item -ItemType Directory -Path $signingPath | Out-Null
}

$currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$administratorsSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$allowedSids = @($currentSid.Value, $systemSid.Value, $administratorsSid.Value)

function Set-RestrictedAcl([string] $Path, [bool] $IsDirectory) {
    $inheritanceOutput = @(& icacls $Path '/inheritance:r' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not disable ACL inheritance on $Path`: $($inheritanceOutput -join [Environment]::NewLine)"
    }

    $existingAcl = Get-Acl -LiteralPath $Path
    $existingRules = @($existingAcl.GetAccessRules(
        $true,
        $true,
        [Security.Principal.SecurityIdentifier]
    ))
    foreach ($rule in $existingRules) {
        $sidValue = $rule.IdentityReference.Value
        if ($sidValue -notin $allowedSids) {
            $removeOutput = @(& icacls $Path '/remove:g' "*$sidValue" '/remove:d' "*$sidValue" 2>&1)
            if ($LASTEXITCODE -ne 0) {
                throw "Could not remove ACL principal $sidValue from $Path`: $($removeOutput -join [Environment]::NewLine)"
            }
        }
    }

    $grantArguments = if ($IsDirectory) {
        @(
            $Path,
            '/grant:r',
            "*$($currentSid.Value):(OI)(CI)(F)",
            "*$($systemSid.Value):(OI)(CI)(F)",
            "*$($administratorsSid.Value):(OI)(CI)(F)"
        )
    } else {
        @(
            $Path,
            '/grant:r',
            "*$($currentSid.Value):(F)",
            "*$($systemSid.Value):(F)",
            "*$($administratorsSid.Value):(F)"
        )
    }
    $grantOutput = @(& icacls @grantArguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not grant restricted ACL on $Path`: $($grantOutput -join [Environment]::NewLine)"
    }
}

Set-RestrictedAcl $signingPath $true
Get-ChildItem -LiteralPath $signingPath -Force -Recurse | ForEach-Object {
    Set-RestrictedAcl $_.FullName $_.PSIsContainer
}

$sandboxSid = $null
try {
    $sandboxAccount = [Security.Principal.NTAccount]::new("$env:COMPUTERNAME", 'CodexSandboxUsers')
    $sandboxSid = $sandboxAccount.Translate([Security.Principal.SecurityIdentifier]).Value
} catch [Security.Principal.IdentityNotMappedException] {
    $sandboxSid = $null
}

$targets = @((Get-Item -LiteralPath $signingPath -Force)) + @(
    Get-ChildItem -LiteralPath $signingPath -Force -Recurse
)
foreach ($target in $targets) {
    $acl = Get-Acl -LiteralPath $target.FullName
    if (-not $acl.AreAccessRulesProtected) {
        throw "ACL inheritance remains enabled: $($target.FullName)"
    }
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    foreach ($rule in $rules) {
        $sid = $rule.IdentityReference.Value
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) {
            throw "Unexpected deny ACL entry on $($target.FullName): $sid"
        }
        if ($sid -notin $allowedSids) {
            throw "Unexpected ACL principal on $($target.FullName): $sid"
        }
    }
    if ($null -ne $sandboxSid -and $rules.IdentityReference.Value -contains $sandboxSid) {
        throw "CodexSandboxUsers still has an ACL entry on $($target.FullName)"
    }
}

Write-Host 'PASS: .signing ACL is protected and limited to the current user, SYSTEM, and Administrators.'
Write-Host "  currentUserSid=$($currentSid.Value)"
Write-Host "  CodexSandboxUsersAcePresent=False"
exit 0
