<#
.SYNOPSIS
  Short same-material concurrent purchase: limited stock, duplicate clientKeys,
  wait for MQ drain, reconcile stock/orders/entitlements; record QPS/TPS/e2e latency.

.NOTES
  Java must run with async sale MQ (sync-settle=false, producer+consumer enabled).
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$Stock = 8,
  [int]$Buyers = 24,
  [int]$DuplicateExtra = 1,
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [string]$EvidenceDir = 'docs/perf-evidence/commerce',
  [string]$MysqlContainer = 'content-mysql-1',
  [string]$MysqlUser = 'content',
  [string]$MysqlPassword = '11111',
  [string]$MysqlDb = 'content',
  [int]$DrainSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runId = Get-Date -Format 'yyyyMMddHHmmss'
$outDir = Join-Path $EvidenceDir "sale-short-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$raw = Join-Path $outDir 'raw.ndjson'
$summaryPath = Join-Path $outDir 'summary.json'

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null)
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=90 }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  try {
    $r=Invoke-WebRequest @p
    return [pscustomobject]@{ Ok=$true; Code=[int]$r.StatusCode; Data=($r.Content|ConvertFrom-Json); Err=$null }
  } catch {
    $code=0; try { $code=[int]$_.Exception.Response.StatusCode } catch {}
    return [pscustomobject]@{ Ok=$false; Code=$code; Data=$null; Err="$_" }
  }
}
function Sql([string]$q) {
  $out = & docker exec -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -N "-u$MysqlUser" $MysqlDb -e $q 2>&1
  if ($LASTEXITCODE -ne 0) { throw "mysql: $out" }
  return (($out | ForEach-Object { "$_" }) | Out-String).Trim()
}

Write-Host "short-load stock=$Stock buyers=$Buyers dups=$DuplicateExtra"
$author = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="sl_a_$runId"; password='password-a1' }).Data
$HA = @{ Authorization = "Bearer $($author.token)" }
$work = Join-Path $env:TEMP "com-short-$runId"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') { 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' } else { 'docker' }
& $docker run --rm -v "${work}:/work" -w /work --entrypoint ffmpeg $FfmpegImage -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p v1.mp4 | Out-Null
$meta = @{ title="short $runId"; description='short'; tags=@('load'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$mat = (Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item $mp4 } -TimeoutSec 180).Content | ConvertFrom-Json
$postId = [long]$mat.postId
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
  $d = (Invoke-Json GET "$ApiBase/api/materials/$postId" $HA).Data
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { break }
  Start-Sleep -Milliseconds 500
}
$pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion = 0 } | Out-Null
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campaign = (Invoke-Json POST "$ApiBase/api/materials/$postId/sale-campaigns" $HA @{
  priceCents=100; stockCap=$Stock; startMs=($now-1000); endMs=($now+3600000)
}).Data

$sessions = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
for ($i=0; $i -lt $Buyers; $i++) {
  $s = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="sl_b_${runId}_$i"; password='password-b1' }).Data
  Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" @{ Authorization = "Bearer $($s.token)" } | Out-Null
  [void]$sessions.Add($s)
}

# Build work items: each buyer once + DuplicateExtra retries with same clientKey
$jobs = [System.Collections.Generic.List[object]]::new()
foreach ($s in $sessions) {
  $ck = "short-$($s.userId)"
  $jobs.Add([pscustomobject]@{ session=$s; clientKey=$ck })
  for ($d=0; $d -lt $DuplicateExtra; $d++) {
    $jobs.Add([pscustomobject]@{ session=$s; clientKey=$ck })
  }
}

$acceptProcessing = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$success = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$bizReject = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$sysErr = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$e2eMs = [System.Collections.Concurrent.ConcurrentBag[object]]::new()

$sw = [Diagnostics.Stopwatch]::StartNew()
$jobs | ForEach-Object -Parallel {
  $ApiBase = $using:ApiBase
  $campaignId = $using:campaign.id
  $raw = $using:raw
  $job = $_
  $H = @{ Authorization = "Bearer $($job.session.token)" }
  $t0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  try {
    $body = @{ clientKey = $job.clientKey } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/sale-campaigns/$campaignId/purchase" -Headers $H -ContentType 'application/json' -Body $body -TimeoutSec 60
    $p = $r.Content | ConvertFrom-Json
    if ($p.state -eq 'PROCESSING') { ($using:acceptProcessing).Add(1) | Out-Null }
    $deadline = (Get-Date).AddSeconds(90)
    while ($p.state -eq 'PROCESSING' -and (Get-Date) -lt $deadline) {
      Start-Sleep -Milliseconds 250
      $q = Invoke-WebRequest -Method Get -Uri "$ApiBase/api/purchase-requests/$($p.reservationId)" -Headers $H -TimeoutSec 30
      $p = $q.Content | ConvertFrom-Json
    }
    $ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $t0
    $line = (@{ userId=$job.session.userId; clientKey=$job.clientKey; accept=$p.state; reject=$p.rejectReason; reservationId=$p.reservationId; ms=$ms } | ConvertTo-Json -Compress)
    Add-Content -Path $raw -Value $line
    ($using:e2eMs).Add($ms) | Out-Null
    if ($p.state -eq 'SUCCEEDED') { ($using:success).Add($job.session.userId) | Out-Null }
    elseif ($p.state -eq 'REJECTED') { ($using:bizReject).Add("$($p.rejectReason)") | Out-Null }
    else { ($using:sysErr).Add("$($p.state)") | Out-Null }
  } catch {
    ($using:sysErr).Add("$_") | Out-Null
  }
} -ThrottleLimit 16
$sw.Stop()

# Drain: wait until no PROCESSING for this campaign
$drainSw = [Diagnostics.Stopwatch]::StartNew()
$drainDeadline = (Get-Date).AddSeconds($DrainSeconds)
while ((Get-Date) -lt $drainDeadline) {
  $pending = Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($campaign.id) AND state='PROCESSING'"
  if ($pending -eq '0') { break }
  Start-Sleep -Milliseconds 500
}
$drainSw.Stop()

$sold = [int](Sql "SELECT sold_count FROM material_sale_campaigns WHERE id=$($campaign.id)")
$paid = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($campaign.id) AND state='PAID'")
$ents = [int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$($campaign.id) AND e.state='ACTIVE'")
$uniqUsers = [int](Sql "SELECT COUNT(DISTINCT user_id) FROM material_orders WHERE campaign_id=$($campaign.id)")
$reqTerminal = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($campaign.id) AND state IN ('SUCCEEDED','REJECTED')")

function Pct($arr, $p) {
  $s = @($arr | Sort-Object)
  if ($s.Count -eq 0) { return $null }
  $idx = [Math]::Min($s.Count-1, [Math]::Floor(($p/100.0)*($s.Count-1)))
  return $s[$idx]
}

$elapsed = [Math]::Max(0.001, $sw.Elapsed.TotalSeconds)
$uniqueSuccess = @($success | Select-Object -Unique).Count
$report = [ordered]@{
  runId = $runId
  mode = 'async-rocketmq-short'
  stock = $Stock
  buyers = $Buyers
  duplicateExtraPerBuyer = $DuplicateExtra
  totalRequests = $jobs.Count
  elapsedSec = [Math]::Round($elapsed, 3)
  requestQps = [Math]::Round($jobs.Count / $elapsed, 2)
  settleTps = [Math]::Round($uniqueSuccess / $elapsed, 2)
  acceptProcessingCount = $acceptProcessing.Count
  uniqueSuccessOrders = $uniqueSuccess
  businessRejects = $bizReject.Count
  systemErrors = $sysErr.Count
  e2eP50Ms = (Pct $e2eMs 50)
  e2eP95Ms = (Pct $e2eMs 95)
  e2eP99Ms = (Pct $e2eMs 99)
  drainSec = [Math]::Round($drainSw.Elapsed.TotalSeconds, 3)
  reconcile = @{
    sold_count = $sold
    paid_orders = $paid
    active_purchase_entitlements = $ents
    distinct_order_users = $uniqUsers
    terminal_requests = $reqTerminal
    no_oversell = ($sold -le $Stock -and $paid -le $Stock)
    one_order_per_user = ($uniqUsers -eq $paid)
    sold_matches_paid = ($sold -eq $paid)
  }
  rejectReasons = @($bizReject | Group-Object | ForEach-Object { @{ reason=$_.Name; count=$_.Count } })
  boundaries = @{ payment='simulated'; refund='simulated'; ffmpegContainerCleanup='unverified' }
}
$report | ConvertTo-Json -Depth 8 | Set-Content $summaryPath -Encoding utf8
Write-Host ($report | ConvertTo-Json -Depth 8)
if (-not $report.reconcile.no_oversell) { throw 'OVERSELL detected' }
if (-not $report.reconcile.one_order_per_user) { throw 'duplicate orders per user' }
if (-not $report.reconcile.sold_matches_paid) { throw 'sold_count != paid orders' }
Write-Host "GREEN evidence=$outDir"



