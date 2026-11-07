param(
    [ValidateSet('media', '*')]
    [string[]] $Profile = @('*')
)

. (Join-Path $PSScriptRoot 'content-compose.ps1')
Invoke-ContentCompose -Profile $Profile -CommandArguments @('ps', '--all')
