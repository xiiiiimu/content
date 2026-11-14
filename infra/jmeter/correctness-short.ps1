<#
.SYNOPSIS
  Re-run stock=8, 24 buyers, 2x same clientKey (48 attempts) with full classification.
  Preserves prior evidence; writes a new evidence folder + classification CSV.
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
$outDir = Join-Path $EvidenceDir "sale-short-classified-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$raw = Join-Path $outDir 'raw.ndjson'
$csvPath = Join-Path $outDir 'per-request.csv'
$summaryPath = Join-Path $outDir 'summary.json'

function Invoke-SaleHttp {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null,[int]$TimeoutSec=60)
  $sw = [Diagnostics.Stopwatch]::StartNew()
  try {
    $p = @{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=$TimeoutSec }
    if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
    $r = Invoke-WebRequest @p
    $sw.Stop()
    $data = $null; try { $data = $r.Content | ConvertFrom-Json } catch {}
    return [pscustomobject]@{
      Kind='ok'; Http=[int]$r.StatusCode; Code=$null; Data=$data; Err=$null; Ms=$sw.ElapsedMilliseconds
    }
  } catch {
    $sw.Stop()
    $ex = $_.Exception
    if ($ex -is [System.Net.Http.HttpRequestException] -or $ex.InnerException -is [System.Net.Sockets.SocketException]) {
      return [pscustomobject]@{ Kind='connect'; Http=0; Code=$null; Data=$null; Err="$ex"; Ms=$sw.ElapsedMilliseconds }
    }
    if ($ex -is [System.Threading.Tasks.TaskCanceledException] -or "$ex" -match 'timed out|Timeout') {
      return [pscustomobject]@{ Kind='timeout'; Http=0; Code=$null; Data=$null; Err="$ex"; Ms=$sw.ElapsedMilliseconds }
    }
    $resp = $ex.Response
    if ($null -eq $resp) {
      return [pscustomobject]@{ Kind='unknown'; Http=0; Code=$null; Data=$null; Err="$_"; Ms=$sw.ElapsedMilliseconds }
    }
    $code = [int]$resp.StatusCode
    $c = $null
    try {
      if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $c = $_.ErrorDetails.Message }
      elseif ($resp -is [System.Net.Http.HttpResponseMessage]) { $c = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
    } catch {}
    $data = $null; $biz = $null
    try { if ($c) { $data = $c | ConvertFrom-Json; if ($data.code) { $biz = [string]$data.code } } } catch {}
    $kind = if ($code -ge 500) { 'http5xx' } elseif ($code -ge 400) { 'biz_http' } else { 'unknown' }
    return [pscustomobject]@{ Kind=$kind; Http=$code; Code=$biz; Data=$data; Err=$c; Ms=$sw.ElapsedMilliseconds }
  }
}

function ClassifyRow([pscustomobject]$row) {
  # Order: system/transport first, then idempotent, then expected biz, then success.
  if ($row.kind -eq 'timeout') { return 'TIMEOUT' }
  if ($row.kind -eq 'connect') { return 'CONNECT_FAIL' }
  if ($row.kind -eq 'http5xx' -or ($row.http -ge 500)) { return 'HTTP_5XX' }
  if ($row.kind -eq 'unknown' -and $row.http -eq 0) { return 'UNKNOWN' }
  if ($row.finalState -eq 'SUCCEEDED' -and $row.idempotentReplay) { return 'IDEMPOTENT_REPLAY' }
  if ($row.finalState -eq 'SUCCEEDED') { return 'SUCCEEDED' }
  if ($row.finalState -eq 'REJECTED') {
    $r = [string]$row.rejectReason
    if ($r -in @('SOLD_OUT','BLACKLISTED','ALREADY_OWNED','CAMPAIGN_CLOSED','ENDED','NOT_STARTED')) { return "BIZ_REJECTED:$r" }
    return "BIZ_REJECTED:$r"
  }
  if ($row.http -in @(400,401,403,404,409) -and $row.code) {
    if ($row.code -in @('SOLD_OUT','ALREADY_RESERVED','ALREADY_OWNED','CAMPAIGN_CLOSED','NOT_OPEN','ENDED','NOT_STARTED','RESERVE_REJECTED','BLACKLISTED')) {
      return "BIZ_HTTP:$($row.code)"
    }
    return "BIZ_HTTP:$($row.code)"
  }
  if ($row.http -ge 400 -and $row.http -lt 500) { return "BIZ_HTTP:$($row.http)" }
  return 'UNKNOWN'
}

function Sql([string]$q) {
  $out = & docker exec -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -N "-u$MysqlUser" $MysqlDb -e $q 2>&1
  if ($LASTEXITCODE -ne 0) { throw "mysql: $out" }
  return (($out | ForEach-Object { "$_" }) | Out-String).Trim()
}

Write-Host "classified-short stock=$Stock buyers=$Buyers dups=$DuplicateExtra"
$author = (Invoke-SaleHttp POST "$ApiBase/api/auth/register" @{} @{ username="cls_a_$runId"; password='password-a1' }).Data
$HA = @{ Authorization = "Bearer $($author.token)" }
$work = Join-Path $env:TEMP "com-cls-$runId"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') { 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' } else { 'docker' }
& $docker run --rm -v "${work}:/work" -w /work --entrypoint ffmpeg $FfmpegImage -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p v1.mp4 | Out-Null
$meta = @{ title="cls $runId"; description='cls'; tags=@('load'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$mat = (Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item $mp4 } -TimeoutSec 180).Content | ConvertFrom-Json
$postId = [long]$mat.postId
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
  $d = (Invoke-SaleHttp GET "$ApiBase/api/materials/$postId" $HA).Data
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { break }
  Start-Sleep -Milliseconds 500
}
$pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
[void](Invoke-SaleHttp POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion = 0 })
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campaign = (Invoke-SaleHttp POST "$ApiBase/api/materials/$postId/sale-campaigns" $HA @{
  priceCents=100; stockCap=$Stock; startMs=($now-1000); endMs=($now+3600000)
}).Data

$sessions = [System.Collections.Generic.List[object]]::new()
for ($i=0; $i -lt $Buyers; $i++) {
  $s = (Invoke-SaleHttp POST "$ApiBase/api/auth/register" @{} @{ username="cls_b_${runId}_$i"; password='password-b1' }).Data
  [void](Invoke-SaleHttp PUT "$ApiBase/api/follows/$($author.userId)" @{ Authorization = "Bearer $($s.token)" })
  $sessions.Add($s) | Out-Null
}

$jobs = [System.Collections.Generic.List[object]]::new()
foreach ($s in $sessions) {
  $ck = "cls-$($s.userId)"
  $jobs.Add([pscustomobject]@{ seq=$jobs.Count; session=$s; clientKey=$ck; attempt=0 })
  for ($d=0; $d -lt $DuplicateExtra; $d++) {
    $jobs.Add([pscustomobject]@{ seq=$jobs.Count; session=$s; clientKey=$ck; attempt=($d+1) })
  }
}

$bag = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$sw = [Diagnostics.Stopwatch]::StartNew()
$jobs | ForEach-Object -Parallel {
  $ApiBase = $using:ApiBase
  $campaignId = $using:campaign.id
  $raw = $using:raw
  $bag = $using:bag
  $job = $_
  $H = @{ Authorization = "Bearer $($job.session.token)" }
  $t0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

  function Invoke-SaleHttpLocal {
    param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null,[int]$TimeoutSec=60)
    $swl = [Diagnostics.Stopwatch]::StartNew()
    try {
      $p = @{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=$TimeoutSec }
      if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
      $r = Invoke-WebRequest @p
      $swl.Stop()
      $data = $null; try { $data = $r.Content | ConvertFrom-Json } catch {}
      return [pscustomobject]@{ Kind='ok'; Http=[int]$r.StatusCode; Code=$null; Data=$data; Err=$null; Ms=$swl.ElapsedMilliseconds }
    } catch {
      $swl.Stop()
      $ex = $_.Exception
      $code = 0
      try {
        if ($ex.Response -and $ex.Response.StatusCode) { $code = [int]$ex.Response.StatusCode }
        elseif ($ex.InnerException -and $ex.InnerException.Response) { $code = [int]$ex.InnerException.Response.StatusCode }
      } catch {}
      $c = $null
      try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $c = $_.ErrorDetails.Message } } catch {}
      if (-not $c -and $code -eq 0 -and ("$ex" -match '\((4\d\d|5\d\d)\)')) { $code = [int]$Matches[1] }
      $data = $null; $biz = $null
      try { if ($c) { $data = $c | ConvertFrom-Json; if ($data.code) { $biz = [string]$data.code } } } catch {}
      if ($code -eq 0 -and ("$ex" -match 'timed out|Timeout|TaskCanceled')) {
        return [pscustomobject]@{ Kind='timeout'; Http=0; Code=$null; Data=$null; Err="$ex"; Ms=$swl.ElapsedMilliseconds }
      }
      if ($code -eq 0 -and ("$ex" -match 'actively refused|No connection|Connection reset|failed to respond')) {
        return [pscustomobject]@{ Kind='connect'; Http=0; Code=$null; Data=$null; Err="$ex"; Ms=$swl.ElapsedMilliseconds }
      }
      if ($code -eq 0) {
        return [pscustomobject]@{ Kind='unknown'; Http=0; Code=$null; Data=$null; Err="$_"; Ms=$swl.ElapsedMilliseconds }
      }
      $kind = if ($code -ge 500) { 'http5xx' } elseif ($code -ge 400) { 'biz_http' } else { 'unknown' }
      return [pscustomobject]@{ Kind=$kind; Http=$code; Code=$biz; Data=$data; Err=$c; Ms=$swl.ElapsedMilliseconds }
    }
  }

  $first = Invoke-SaleHttpLocal POST "$ApiBase/api/sale-campaigns/$campaignId/purchase" $H @{ clientKey = $job.clientKey }
  $finalState = $null; $reject = $null; $resId = $null; $idempotent = $false
  if ($first.Kind -eq 'ok' -and $first.Data) {
    $p = $first.Data
    $resId = $p.reservationId
    $acceptState = [string]$p.state
    $deadline = (Get-Date).AddSeconds(90)
    while ($p.state -eq 'PROCESSING' -and (Get-Date) -lt $deadline) {
      Start-Sleep -Milliseconds 250
      $q = Invoke-SaleHttpLocal GET "$ApiBase/api/purchase-requests/$($p.reservationId)" $H
      if ($q.Kind -ne 'ok') { $first = $q; break }
      $p = $q.Data
    }
    $finalState = [string]$p.state
    $reject = $p.rejectReason
    if ($job.attempt -gt 0 -and $finalState -in @('SUCCEEDED','REJECTED','PROCESSING')) { $idempotent = $true }
    if ($acceptState -in @('SUCCEEDED','REJECTED') -and $job.attempt -gt 0) { $idempotent = $true }
  }
  $ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $t0
  $row = [ordered]@{
    seq=$job.seq; userId=$job.session.userId; clientKey=$job.clientKey; attempt=$job.attempt
    kind=$first.Kind; http=$first.Http; code=$first.Code
    reservationId=$resId; finalState=$finalState; rejectReason=$reject
    idempotentReplay=$idempotent; ms=$ms
  }
  $line = ($row | ConvertTo-Json -Compress)
  Add-Content -Path $raw -Value $line
  $bag.Add([pscustomobject]$row) | Out-Null
} -ThrottleLimit 24
$sw.Stop()

$drainSw = [Diagnostics.Stopwatch]::StartNew()
$drainDeadline = (Get-Date).AddSeconds($DrainSeconds)
while ((Get-Date) -lt $drainDeadline) {
  $pending = Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($campaign.id) AND state='PROCESSING'"
  if ($pending -eq '0') { break }
  Start-Sleep -Milliseconds 500
}
$drainSw.Stop()

$rows = @($bag)
$classified = foreach ($r in $rows) {
  $cls = & {
    if ($r.kind -eq 'timeout') { 'TIMEOUT' }
    elseif ($r.kind -eq 'connect') { 'CONNECT_FAIL' }
    elseif ($r.kind -eq 'http5xx' -or $r.http -ge 500) { 'HTTP_5XX' }
    elseif ($r.kind -eq 'unknown' -and $r.http -eq 0) { 'UNKNOWN' }
    elseif ($r.finalState -eq 'SUCCEEDED' -and $r.idempotentReplay) { 'IDEMPOTENT_REPLAY' }
    elseif ($r.finalState -eq 'SUCCEEDED') { 'SUCCEEDED' }
    elseif ($r.finalState -eq 'REJECTED') { "BIZ_REJECTED:$($r.rejectReason)" }
    elseif ($r.http -ge 400 -and $r.http -lt 500 -and $r.code) { "BIZ_HTTP:$($r.code)" }
    elseif ($r.http -ge 400 -and $r.http -lt 500) { "BIZ_HTTP:$($r.http)" }
    else { 'UNKNOWN' }
  }
  [pscustomobject]@{
    seq=$r.seq; userId=$r.userId; clientKey=$r.clientKey; attempt=$r.attempt
    http=$r.http; code=$r.code; finalState=$r.finalState; rejectReason=$r.rejectReason
    classification=$cls; ms=$r.ms; reservationId=$r.reservationId
  }
}
$classified | Sort-Object seq | Export-Csv -Path $csvPath -NoTypeInformation -Encoding utf8

$sold = [int](Sql "SELECT sold_count FROM material_sale_campaigns WHERE id=$($campaign.id)")
$paid = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($campaign.id) AND state='PAID'")
$ents = [int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$($campaign.id) AND e.state='ACTIVE'")
$uniqUsers = [int](Sql "SELECT COUNT(DISTINCT user_id) FROM material_orders WHERE campaign_id=$($campaign.id)")
$reqTerminal = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($campaign.id) AND state IN ('SUCCEEDED','REJECTED')")
$redisRem = try { (Invoke-SaleHttp GET "$ApiBase/api/materials/$postId/sale-campaigns/current" $HA).Data.remainingStock } catch { $null }

$byClass = @($classified | Group-Object classification | ForEach-Object { @{ classification=$_.Name; count=$_.Count } })
$bizReject = @($classified | Where-Object { $_.classification -like 'BIZ_*' }).Count
$sysErr = @($classified | Where-Object { $_.classification -in @('HTTP_5XX','TIMEOUT','CONNECT_FAIL','UNKNOWN') }).Count
$success = @($classified | Where-Object { $_.classification -eq 'SUCCEEDED' }).Count
$idem = @($classified | Where-Object { $_.classification -eq 'IDEMPOTENT_REPLAY' }).Count

$report = [ordered]@{
  runId = $runId
  mode = 'async-rocketmq-short-classified'
  priorSuspectEvidence = 'docs/perf-evidence/commerce/sale-short-20261115042949'
  stock = $Stock; buyers = $Buyers; duplicateExtraPerBuyer = $DuplicateExtra
  totalRequests = $jobs.Count
  elapsedSec = [Math]::Round($sw.Elapsed.TotalSeconds, 3)
  classificationCounts = $byClass
  succeeded = $success
  idempotentReplay = $idem
  businessRejectOrHttp = $bizReject
  systemErrors = $sysErr
  drainSec = [Math]::Round($drainSw.Elapsed.TotalSeconds, 3)
  reconcile = @{
    sold_count = $sold; paid_orders = $paid; active_purchase_entitlements = $ents
    distinct_order_users = $uniqUsers; terminal_requests = $reqTerminal
    redis_remaining = $redisRem
    no_oversell = ($sold -le $Stock -and $paid -le $Stock)
    one_order_per_user = ($uniqUsers -eq $paid)
    sold_matches_paid = ($sold -eq $paid)
  }
  boundaries = @{ payment='simulated'; refund='simulated'; ffmpegContainerCleanup='unverified' }
}
$report | ConvertTo-Json -Depth 8 | Set-Content $summaryPath -Encoding utf8
Write-Host ($report | ConvertTo-Json -Depth 8)
if (-not $report.reconcile.no_oversell) { throw 'OVERSELL' }
if (-not $report.reconcile.one_order_per_user) { throw 'duplicate orders' }
if (-not $report.reconcile.sold_matches_paid) { throw 'sold!=paid' }
Write-Host "GREEN classified evidence=$outDir"
