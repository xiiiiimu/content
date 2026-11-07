param(
    [ValidateSet('media')]
    [string[]] $Profile = @()
)

. (Join-Path $PSScriptRoot 'content-compose.ps1')
$services = @('mysql', 'redis', 'namesrv', 'broker')
if ($Profile -contains 'media') {
    $configuration = Invoke-ContentCompose -Profile $Profile -CommandArguments @('config', '--format', 'json') | ConvertFrom-Json
    if ($configuration.services.minio.environment.MINIO_ROOT_PASSWORD.Length -lt 8) {
        throw 'Set CONTENT_MINIO_PASSWORD to at least eight characters before enabling media.'
    }
    $services += 'minio'
}
Invoke-ContentCompose -Profile $Profile -CommandArguments (@('up', '--detach', '--wait', '--wait-timeout', '240') + $services)
