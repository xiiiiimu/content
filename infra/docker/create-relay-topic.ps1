param([string]$BrokerContainer='content-broker-1')
$ErrorActionPreference='Stop'
$output=& docker exec $BrokerContainer sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:30911 -t content-post-published-v1 -r 4 -w 4 2>&1
if ($LASTEXITCODE -ne 0 -or (($output -join "`n") -notmatch 'create topic .* success')) { throw "Relay Topic creation failed: $output" }
$output
