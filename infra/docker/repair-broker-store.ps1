$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'content-compose.ps1')

# Verify the target before changing ownership in the mounted directory.
$configuration = Invoke-ContentCompose -CommandArguments @('config', '--format', 'json') | ConvertFrom-Json
$storeMount = @($configuration.services.broker.volumes | Where-Object { $_.target -eq '/home/rocketmq/store' })
if ($configuration.name -ne 'content' -or $storeMount.Count -ne 1 -or
    $storeMount[0].type -ne 'volume' -or $storeMount[0].source -ne 'rocketmq-data' -or
    $configuration.volumes.'rocketmq-data'.name -ne 'content_rocketmq-data') {
    throw 'Expected the Content Broker store to use content_rocketmq-data. Repair stopped.'
}

Invoke-ContentCompose -CommandArguments @('stop', 'broker')

# chown resolves account names using the image's UID/GID database.
# Invoke it directly to avoid Windows PowerShell rewriting shell quotes.
Invoke-ContentCompose -CommandArguments @(
    'run', '--rm', '--no-deps', '--user', '0', '--entrypoint', 'chown',
    'broker', '-R', 'rocketmq:rocketmq', '/home/rocketmq/store'
)

$verify = @'
set -eu
id
test -w /home/rocketmq/store
if test -e /home/rocketmq/store/lock; then test -w /home/rocketmq/store/lock; fi
echo 'Broker store ownership repaired; rocketmq user has write permission.'
'@
Invoke-ContentCompose -CommandArguments @(
    'run', '--rm', '--no-deps', '--user', 'rocketmq', '--entrypoint', 'sh', 'broker', '-c', $verify
)
