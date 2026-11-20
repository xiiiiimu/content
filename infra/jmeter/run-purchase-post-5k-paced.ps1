<#
.SYNOPSIS
  Fixed-arrival-rate control vs retained barrier 5k in-stock run.
  Same 5000 buyers, NEW campaign stock=5000, same Tomcat config.
  Only emit rhythm changes: NO start barrier; threads fire as they ramp (≈ Threads/RampSec req/s).
  Uses ClientKeyMode=unique so cross-campaign byClient(jm-{userId}) cannot short-circuit.
  Seed/login outside window; poll/drain after load. Does not alter Tomcat sizing.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$EvidenceRoot = 'docs/perf-evidence/commerce/jmeter',
  [int]$Threads = 5000,
  [int]$RampSec = 100,  # ≈50 arrivals/sec for 5000 threads
  [int]$TimeoutMs = 30000,
  [int]$ThrottleLimit = 6,
  [string]$ReuseBuyersCsv = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $env:CONTENT_BENCH_DB_PASSWORD) { throw 'CONTENT_BENCH_DB_PASSWORD is not set: set it to the local MySQL password before running this script.' }
# Local MySQL password comes only from the environment, never from this file or the docker command line.
function Invoke-BenchMysql([string]$q) {
  if (-not $env:CONTENT_BENCH_DB_PASSWORD) { throw 'CONTENT_BENCH_DB_PASSWORD is not set: set it to the local MySQL password before running this script.' }
  $previous = $env:MYSQL_PWD; $env:MYSQL_PWD = $env:CONTENT_BENCH_DB_PASSWORD
  try { & docker exec -e MYSQL_PWD content-mysql-1 mysql -N -ucontent content -e $q 2>&1 } finally { $env:MYSQL_PWD = $previous }
}
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '../..')).Path
Set-Location $repo

function Sql([string]$q) {
  $o = Invoke-BenchMysql $q
  if ($LASTEXITCODE -ne 0) { throw "mysql: $o" }
  return (($o | ForEach-Object { "$_" }) | Out-String).Trim()
}

function HostSnap {
  $os = Get-CimInstance Win32_OperatingSystem
  [ordered]@{
    at = (Get-Date).ToString('o')
    freeRamGB = [Math]::Round($os.FreePhysicalMemory / 1MB, 2)
    tcpListen = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
      Where-Object { $_.LocalPort -eq 28080 } |
      Select-Object LocalAddress,LocalPort,State)
    tcpEstablished28080 = @(Get-NetTCPConnection -State Established -ErrorAction SilentlyContinue |
      Where-Object { $_.LocalPort -eq 28080 -or $_.RemotePort -eq 28080 }).Count
  }
}

function Drain-Campaign([long]$CampaignId, [int]$TimeoutSec = 900) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $dl = (Get-Date).AddSeconds($TimeoutSec)
  $peakProc = 0
  while ((Get-Date) -lt $dl) {
    $p = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='PROCESSING'")
    if ($p -gt $peakProc) { $peakProc = $p }
    if ($p -eq 0) { break }
    Start-Sleep -Milliseconds 250
  }
  $sw.Stop()
  $left = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='PROCESSING'")
  return [ordered]@{ drainSec = [Math]::Round($sw.Elapsed.TotalSeconds, 3); processingLeft = $left; peakProcessingObserved = $peakProc }
}

function Reconcile([long]$CampaignId) {
  $sold = [int](Sql "SELECT sold_count FROM material_sale_campaigns WHERE id=$CampaignId")
  $cap = [int](Sql "SELECT stock_cap FROM material_sale_campaigns WHERE id=$CampaignId")
  $paid = [int](Sql "SELECT COUNT(DISTINCT id) FROM material_orders WHERE campaign_id=$CampaignId AND state='PAID'")
  $paidBuyers = [int](Sql "SELECT COUNT(DISTINCT user_id) FROM material_orders WHERE campaign_id=$CampaignId AND state='PAID'")
  $succ = [int](Sql "SELECT COUNT(DISTINCT reservation_id) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='SUCCEEDED'")
  $ents = [int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$CampaignId AND e.state='ACTIVE'")
  $proc = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='PROCESSING'")
  $byState = Sql "SELECT state, COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId GROUP BY state"
  $ok = ($sold -eq $paid) -and ($paid -eq $succ) -and ($succ -eq $ents) -and ($proc -eq 0) -and ($sold -le $cap) -and ($paid -eq $paidBuyers)
  return [ordered]@{
    sold = $sold; paidOrdersDistinct = $paid; paidBuyersDistinct = $paidBuyers; succReq = $succ
    ents = $ents; processing = $proc; stockCap = $cap; byState = $byState; ok = $ok
  }
}

function Classify-Jtl([string]$JtlPath) {
  $rows = @(Import-Csv $JtlPath | Where-Object { $_.label -eq 'POST-purchase' })
  $refused = 0; $reset = 0; $connTimeout = 0; $localSock = 0; $readTimeout = 0
  $ok = 0; $e401 = 0; $e409 = 0; $e5xx = 0; $other = 0
  $latOk = [System.Collections.Generic.List[int]]::new()
  foreach ($r in $rows) {
    $code = [string]$r.responseCode
    $msg = [string]$r.responseMessage
    $el = [int]$r.elapsed
    if ($msg -match 'Connection refused' -or $code -match 'HttpHostConnect') { $refused++; continue }
    if ($msg -match 'Connection reset|ConnectionReset') { $reset++; continue }
    if ($msg -match 'connect timed out|ConnectTimeout' -or $code -match 'ConnectTimeout') { $connTimeout++; continue }
    if ($msg -match 'Address already in use|No buffer space|Broken pipe') { $localSock++; continue }
    if ($msg -match 'Read timed out' -or $code -match 'SocketTimeout') { $readTimeout++; continue }
    $http = 0; [void][int]::TryParse($code, [ref]$http)
    if ($http -eq 200) { $ok++; $latOk.Add($el) | Out-Null; continue }
    if ($http -eq 401) { $e401++; continue }
    if ($http -eq 409) { $e409++; continue }
    if ($http -ge 500) { $e5xx++; continue }
    $other++
  }
  $ts = @($rows | ForEach-Object { [long]$_.timeStamp } | Sort-Object)
  $ends = @($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Sort-Object)
  $emitSec = [Math]::Max(0.001, ($ts[-1] - $ts[0]) / 1000.0)
  $winSec = [Math]::Max(0.001, ($ends[-1] - $ts[0]) / 1000.0)
  function Pct($a, $p) {
    if ($a.Count -eq 0) { return $null }
    $s = @($a | Sort-Object)
    return $s[[Math]::Min($s.Count - 1, [Math]::Floor(($s.Count - 1) * ($p / 100.0)))]
  }
  return [ordered]@{
    samples = $rows.Count
    emitSpanSec = [Math]::Round($emitSec, 3)
    windowSec = [Math]::Round($winSec, 3)
    achievedEmitRate = [Math]::Round($rows.Count / $emitSec, 2)
    targetEmitRate = [Math]::Round($Threads / [Math]::Max(1, $RampSec), 2)
    http200 = $ok; http401 = $e401; http409 = $e409; http5xx = $e5xx
    connectionRefused = $refused; connectionReset = $reset; connectTimeout = $connTimeout
    localSocket = $localSock; readTimeout = $readTimeout; other = $other
    acceptQps = [Math]::Round($ok / $winSec, 2)
    acceptP95 = (Pct $latOk 95); acceptP99 = (Pct $latOk 99)
    peakAllThreads = ($rows | ForEach-Object { [int]$_.allThreads } | Measure-Object -Maximum).Maximum
    # sample_variables columns when present
    hasReservationCol = ($rows.Count -gt 0 -and ($rows[0].PSObject.Properties.Name -contains 'reservationId'))
    hasUserIdCol = ($rows.Count -gt 0 -and ($rows[0].PSObject.Properties.Name -contains 'userId'))
    hasEffCkCol = ($rows.Count -gt 0 -and ($rows[0].PSObject.Properties.Name -contains 'effClientKey'))
  }
}

# --- Seed outside window: reuse same 5000 accounts, NEW material/campaign ---
if (-not $ReuseBuyersCsv) {
  $ReuseBuyersCsv = (Get-ChildItem target/jmeter-data -Filter 'buyers-p5kstk*.csv' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not $ReuseBuyersCsv -or -not (Test-Path $ReuseBuyersCsv)) {
  throw "ReuseBuyersCsv not found (expected prior stock 5k buyers CSV)"
}
Write-Host "PACED_5K reuseCsv=$ReuseBuyersCsv threads=$Threads rampSec=$RampSec targetRate≈$([Math]::Round($Threads/$RampSec,1))/s barrier=OFF clientKeyMode=unique"

$metaPath = & (Join-Path $here 'seed-commerce-data.ps1') -ApiBase $ApiBase -Buyers $Threads -Stock $Threads `
  -Prefix 'p5kpaced' -OutDir 'target/jmeter-data' -ThrottleLimit $ThrottleLimit `
  -ReuseCsvGlob $ReuseBuyersCsv -ReturnMetaPath

$meta = Get-Content $metaPath -Raw | ConvertFrom-Json
Write-Host "seeded campaign=$($meta.campaignId) stock=$($meta.stock) buyers=$($meta.buyers) reused=$($meta.buyersReused)"

# TCP snapshot before formal emit
$tcpBefore = HostSnap
try {
  $tcpBefore | ConvertTo-Json -Depth 5 | Set-Content 'target/jmeter-data/paced-tcp-before.json' -Encoding utf8
} catch {}

$band = "purchase-5k-paced-t$Threads"
$runId = [guid]::NewGuid().ToString('N')
& (Join-Path $here 'run-commerce-jmeter.ps1') -ApiBase $ApiBase -Scenario purchase `
  -Threads $Threads -RampSec $RampSec -DurationSec 0 -Loops 1 `
  -MetaJson $metaPath -Band $band -EvidenceDir $EvidenceRoot `
  -WarmupThreads 0 -TimeoutMs $TimeoutMs -ClientKeyMode csv -SkipHtml `
  -RunId $runId -CapacityEvidence
  # NOTE: BarrierEnabled omitted → false

$dir = Get-Item -LiteralPath (Join-Path $EvidenceRoot "$band-purchase-t$Threads-$runId")
$tcpAfter = HostSnap
$cls = Classify-Jtl (Join-Path $dir.FullName 'results.jtl')
$win = Get-Content (Join-Path $dir.FullName 'window.json') -Raw | ConvertFrom-Json
$hm = Get-Content (Join-Path $dir.FullName 'host-metrics.json') -Raw | ConvertFrom-Json

Write-Host "POST-load drain campaign=$($meta.campaignId)"
$t0 = [long]$win.formalWindow.startEpochMs
$t1 = [long]$win.formalWindow.endEpochMs
$drain = Drain-Campaign ([long]$meta.campaignId) 900
$tDrain = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$recon = Reconcile ([long]$meta.campaignId)
$paidDrain = [int](Sql "SELECT COUNT(DISTINCT id) FROM material_orders WHERE campaign_id=$($meta.campaignId) AND state='PAID'")
$fullSec = [Math]::Max(0.001, ($tDrain - $t0) / 1000.0)

# Optional JTL↔DB join when sample vars present
$join = $null
if ($cls.hasReservationCol) {
  $rows = @(Import-Csv (Join-Path $dir.FullName 'results.jtl') | Where-Object { $_.label -eq 'POST-purchase' })
  $withRid = @($rows | Where-Object { $_.reservationId -and $_.reservationId.Trim() -ne '' })
  $join = [ordered]@{
    jtlWithReservationId = $withRid.Count
    jtlHttp200 = $cls.http200
    note = 'Join key reservationId from RegexExtractor when body present'
  }
}

$result = [ordered]@{
  band = $band
  kind = 'in-stock-firstbuy-paced-ramp'
  comparedTo = 'purchase-5k-stock-t5000-purchase-t5000-20261116025653 (barrier retained)'
  dir = $dir.Name
  configuredThreads = $Threads
  rampSec = $RampSec
  barrierEnabled = $false
  clientKeyMode = 'campaign-scoped-csv'
  clientKeyNote = 'jm-c<campaign>-u<buyer> avoids cross-campaign replay while preserving within-campaign idempotency'
  measuredPeakActiveThreads = $win.jmeter.peakActiveThreads
  peakHttpInFlight = $hm.httpInFlight.observedPeakHttpInFlight
  emitSpanMs = $win.jmeter.emitSpanMs
  samples = $win.jmeter.samples
  classification = $cls
  campaignId = $meta.campaignId
  stockCap = $meta.stock
  drain = $drain
  reconcile = $recon
  tcpBefore = $tcpBefore
  tcpAfter = $tcpAfter
  join = $join
  throughput = [ordered]@{
    targetEmitRate = $cls.targetEmitRate
    achievedEmitRate = $cls.achievedEmitRate
    acceptQpsFromHttp = $cls.acceptQps
    paidThroughDrain = $paidDrain
    settleFullTps = [Math]::Round($paidDrain / $fullSec, 2)
    fullWallSec = [Math]::Round($fullSec, 3)
    backlogPeakProcessing = $drain.peakProcessingObserved
    drainSec = $drain.drainSec
  }
  tomcatUnchanged = @{ threadsMax = 5000; acceptCount = 5000; note = 'not raised for this control' }
}
$result | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $dir.FullName 'purchase-5k-paced-report.json') -Encoding utf8
$out = Join-Path $EvidenceRoot ("purchase-post-5k-paced-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.json')
$result | ConvertTo-Json -Depth 10 | Set-Content $out -Encoding utf8
Write-Host ("PACED_RESULT peakActive={0} samples={1} emitRate={2}/s http200={3} refused={4} paid={5} reconOk={6}" -f `
  $result.measuredPeakActiveThreads, $cls.samples, $cls.achievedEmitRate, $cls.http200, $cls.connectionRefused, $paidDrain, $recon.ok)
Write-Host "PACED_5K_DONE $out"
