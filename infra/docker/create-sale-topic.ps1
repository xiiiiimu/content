param(
  [string]$BrokerContainer = 'content-broker-1',
  [string]$Topic = 'content-sale'
)
$ErrorActionPreference = 'Stop'
$output = & docker exec $BrokerContainer sh mqadmin updateTopic -n namesrv:9876 -b 127.0.0.1:30911 -t $Topic -r 4 -w 4 2>&1
if ($LASTEXITCODE -ne 0 -or (($output -join "`n") -notmatch 'create topic .* success')) {
  throw "Sale topic creation failed: $output"
}
$output
