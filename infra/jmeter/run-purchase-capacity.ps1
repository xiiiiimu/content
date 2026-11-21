#Requires -Version 7
param(
  [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9-]{1,64}$')][string]$RunId,
  [Parameter(Mandatory)][string]$MetaJson,
  [ValidateRange(1,5000)][int]$Threads = 8,
  [ValidateRange(1,3600)][int]$RampSec = 8,
  [ValidateRange(1,1000)][int]$StartsPerSecond = 2,
  [ValidateRange(2,60)][int]$ObservationIntervalSec = 5,
  [ValidateRange(5,900)][int]$DrainTimeoutSec = 120,
  [switch]$AllowCapacityLoad
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Set-Location (Join-Path $PSScriptRoot '../..')
if ($Threads -gt 20 -and -not $AllowCapacityLoad) { throw 'More than 20 threads requires explicit -AllowCapacityLoad' }
$metaPath = (Resolve-Path -LiteralPath $MetaJson).Path
$meta = Get-Content -LiteralPath $metaPath -Raw | ConvertFrom-Json
$campaign = [long]$meta.campaignId
if ($campaign -le 0 -or $meta.paidSeeded -ne 0 -or $meta.buyers -ne $Threads -or $meta.stock -lt $Threads) {
  throw 'Use a fresh in-stock seed, with exactly one distinct buyer per thread and no pre-paid orders'
}
$buyers = @(Import-Csv -LiteralPath $meta.buyersCsv)
$invalid = @($buyers | Where-Object {
  $_.userId -notmatch '^[1-9][0-9]*$' -or $_.clientKey -cne "jm-c$campaign-u$($_.userId)" -or -not $_.token
})
if ($buyers.Count -ne $Threads -or @($buyers.userId | Sort-Object -Unique).Count -ne $Threads -or $invalid.Count) {
  throw 'Buyer CSV violates the distinct campaign-scoped first-buy contract'
}

function Get-CampaignCounts {
  $sql = "SELECT sold_count, (SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$campaign)," +
    "(SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$campaign AND state='PROCESSING') " +
    "FROM material_sale_campaigns WHERE id=$campaign;"
  $data = $sql | docker exec -i content-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB content'
  if ($LASTEXITCODE -ne 0 -or "$data" -notmatch '^\d+\s+\d+\s+\d+$') { throw 'Campaign query failed' }
  return ,@("$data" -split '\s+' | ForEach-Object { [int]$_ })
}

$counts = Get-CampaignCounts
if ($counts[0] -ne 0 -or $counts[1] -ne 0) { throw 'Campaign already used' }
$root = (Resolve-Path target).Path
$dir = Join-Path $root "purchase-capacity-$RunId"
New-Item -ItemType Directory -Path $dir | Out-Null
$out = Join-Path $dir "capacity-purchase-t$Threads-$RunId"
$python = (Get-Command python -CommandType Application | Select-Object -First 1).Source
$observerScript = Join-Path $PSScriptRoot 'purchase_capacity.py'
$manifest = @{runId=$RunId; campaignId=$campaign; postId=[long]$meta.postId; threads=$Threads; rampSec=$RampSec; startsPerSecond=$StartsPerSecond;
  observationIntervalSec=$ObservationIntervalSec; drainTimeoutSec=$DrainTimeoutSec; expectedKeyPattern='jm-c<campaign>-u<buyer>'}
$manifest | ConvertTo-Json | Set-Content (Join-Path $dir 'manifest.json') -Encoding utf8
$observer = Start-Process $python -ArgumentList @(('"' + $observerScript + '"'),'observe',('"' + $dir + '"'),"$campaign",'--interval',"$ObservationIntervalSec") `
  -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dir 'observer.log') -RedirectStandardError (Join-Path $dir 'observer.err.log')
try {
  $obs = Join-Path $dir 'observations.ndjson'
  $ready = (Get-Date).AddSeconds(40)
  while (-not ((Test-Path $obs) -and (Get-Item $obs).Length -gt 0)) {
    if ($observer.HasExited -or (Get-Date) -ge $ready) { throw 'Observer did not produce a baseline sample' }
    Start-Sleep -Milliseconds 200
  }

  $failure = $null
  try {
    & (Join-Path $PSScriptRoot 'run-commerce-jmeter.ps1') -Scenario purchase -MetaJson $metaPath -Threads $Threads `
      -RampSec $RampSec -Loops 1 -TimeoutMs 30000 -WarmupThreads 0 -SkipHtml -CapacityEvidence `
      -EvidenceDir $dir -Band capacity -RunId $RunId -PurchaseStartsPerSecond $StartsPerSecond
  } catch { $failure = $_ }
  $deadline = (Get-Date).AddSeconds($DrainTimeoutSec)
  do {
    $counts = Get-CampaignCounts
    if ($counts[2] -eq 0) { break }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  $drainedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $manifest['drainEndEpochMs'] = $drainedAt
  $manifest['processingAtDrainEnd'] = $counts[2]
  $manifest | ConvertTo-Json | Set-Content (Join-Path $dir 'manifest.json') -Encoding utf8
  if ((Test-Path (Join-Path $out 'results.jtl')) -and (Test-Path (Join-Path $out 'window.json'))) {
    & $python $observerScript reconcile $out $campaign --threads $Threads --buyers $meta.buyersCsv --drained-at $drainedAt
    if ($LASTEXITCODE -ne 0) { throw 'Capacity gate failed; inspect capacity-report.json and run logs' }
  } elseif (-not $failure) {
    throw 'Capacity gate failed: JTL or formal window is missing'
  }
  if ($failure) { throw $failure }
  if ($observer.HasExited) { throw 'Observer stopped before the end of the run' }
  Start-Sleep -Seconds $ObservationIntervalSec
} finally {
  New-Item -ItemType File -Path (Join-Path $dir 'observer.stop') | Out-Null
  if (-not $observer.WaitForExit(65000)) { $observer.Kill(); throw 'Observer did not stop within the cleanup budget' }
}
Write-Host "CAPACITY_GATE_PASS $dir"
