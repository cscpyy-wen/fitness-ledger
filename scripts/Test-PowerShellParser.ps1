[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}

$scripts = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Filter '*.ps1' | Sort-Object Name)
if ($scripts.Count -eq 0) { throw 'No PowerShell release scripts were found.' }
foreach ($scriptFile in $scripts) {
    $tokens = $null
    $errors = $null
    [void][Management.Automation.Language.Parser]::ParseFile(
        $scriptFile.FullName,
        [ref] $tokens,
        [ref] $errors
    )
    if (@($errors).Count -ne 0) {
        throw "PowerShell parser rejected $($scriptFile.Name): $($errors.Message -join '; ')"
    }
    Write-Host "PARSE PASS: $($scriptFile.Name)"
}
Write-Host "PASS: parsed $($scripts.Count) PowerShell scripts with zero syntax errors."
exit 0
