<#
.SYNOPSIS
  Formal 5,000-account same-material purchase-POST on local JMeter CLI.
  - Seeds outside the load window (bounded concurrency; reuses prior buyers).
  - Passes -Jthreads=5000 with NO MaxLocalThreads clamp / silent downscale.
  - Start barrier with limited wait; records readyCount + releaseEpochMs.
  - Limited-stock race first; if reconcile OK, separate in-stock campaign.
  - Archives measured peakActive / syserr; never reports configured as achieved.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$EvidenceRoot = 'docs/perf-evidence/commerce/jmeter',
  [int]$Threads = 5000,
  [int]$LimitedStock = 100,
  [int]$TimeoutMs = 30000,
  [int]$BarrierTimeoutMs = 180000,
  [int]$RampSec = 90,
  [int]$ThrottleLimit = 4,
  [switch]$SkipLimited,
  [switch]$SkipInStock
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

# Refuse silent downscale — this script never rewrites $Threads.
Write-Host "FORMAL_5K configuredThreads=$Threads (no clamp). Free RAM check before seed."

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
    totalRamGB = [Math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
    cpuLoad = (Get-CimInstance Win32_Processor | Measure-Object LoadPercentage -Average).Average
  }
}

function Classify-PurchaseJtl([string]$JtlPath) {
  $rows = @(Import-Csv $JtlPath | Where-Object { $_.label -eq 'POST-purchase' })
  if ($rows.Count -eq 0) { throw "no POST-purchase rows in $JtlPath" }
  $ts = @($rows | ForEach-Object { [long]$_.timeStamp } | Sort-Object)
  $ends = @($rows | ForEach-Object { [long]$_.timeStamp + [long]$_.elapsed } | Sort-Object)
  $spanStartSec = [Math]::Max(0.001, ($ts[-1] - $ts[0]) / 1000.0)
  $spanSec = [Math]::Max(0.001, ($ends[-1] - $ts[0]) / 1000.0)
  $soldOut = 0; $accept = 0; $idempotent = 0; $bizOther = 0
  $sys = 0; $timeout = 0; $connect = 0; $http5xx = 0
  $latOk = [System.Collections.Generic.List[int]]::new()
  $latSold = [System.Collections.Generic.List[int]]::new()
  $latAll = [System.Collections.Generic.List[int]]::new()
  foreach ($r in $rows) {
    $code = [string]$r.responseCode
    $msg = [string]$r.responseMessage
    $ok = [string]$r.success
    $el = [int]$r.elapsed
    $latAll.Add($el) | Out-Null
    $body = ''
    foreach ($n in @('responseData','failureMessage','responseMessage')) {
      if ($r.PSObject.Properties.Name -contains $n -and $r.$n) { $body += [string]$r.$n }
    }
    if ($code -match 'ConnectException|HttpHostConnect' -or $msg -match 'Connection refused') { $connect++; $sys++; continue }
    if ($code -match 'SocketTimeout|Timeout' -or $msg -match 'timed out|Timeout') { $timeout++; $sys++; continue }
    $http = 0; [void][int]::TryParse($code, [ref]$http)
    if ($http -ge 500) { $http5xx++; $sys++; continue }
    if ($ok -eq 'true' -or $ok -eq 'TRUE') {
      $accept++; $latOk.Add($el) | Out-Null
      continue
    }
    if ($http -eq 409) {
      if ($body -match 'ALREADY_OWNED|BLACKLISTED|CAMPAIGN_CLOSED') { $bizOther++ }
      else { $soldOut++; $latSold.Add($el) | Out-Null }
      continue
    }
    if ($http -ge 400 -and $http -lt 500) {
      if ($body -match 'IDEMPOTENT|EXISTING' -or $msg -match 'IDEMPOTENT') { $idempotent++ }
      else { $bizOther++ }
      continue
    }
    $sys++
  }
  function Pct([System.Collections.Generic.List[int]]$a, [double]$p) {
    if ($a.Count -eq 0) { return $null }
    $s = @($a | Sort-Object)
    $i = [Math]::Min($s.Count - 1, [Math]::Floor(($s.Count - 1) * ($p / 100.0)))
    return $s[$i]
  }
  return [ordered]@{
    postPurchaseSamples = $rows.Count
    windowSec = [Math]::Round($spanSec, 3)
    emitSpanSec = [Math]::Round($spanStartSec, 3)
    requestQps = [Math]::Round($rows.Count / $spanSec, 2)
    soldOut = $soldOut
    soldOutQps = [Math]::Round($soldOut / $spanSec, 2)
    soldOutP95 = (Pct $latSold 95)
    soldOutP99 = (Pct $latSold 99)
    acceptHttpOk = $accept
    acceptQps = [Math]::Round($accept / $spanSec, 2)
    acceptP95 = (Pct $latOk 95)
    acceptP99 = (Pct $latOk 99)
    overallP95 = (Pct $latAll 95)
    overallP99 = (Pct $latAll 99)
    idempotentReplay = $idempotent
    bizOther = $bizOther
    systemErrors = $sys
    timeouts = $timeout
    connectFail = $connect
    http5xx = $http5xx
    peakAllThreads = ($rows | ForEach-Object { [int]$_.allThreads } | Measure-Object -Maximum).Maximum
  }
}

function Drain-Campaign([long]$CampaignId, [int]$TimeoutSec = 600) {
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
  $paid = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$CampaignId AND state='PAID'")
  $succ = [int](Sql "SELECT COUNT(DISTINCT reservation_id) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='SUCCEEDED'")
  $ents = [int](Sql "SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$CampaignId AND e.state='ACTIVE'")
  $proc = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='PROCESSING'")
  $reqTotal = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId")
  $byState = Sql "SELECT state, COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId GROUP BY state"
  $ok = ($sold -eq $paid) -and ($paid -eq $succ) -and ($succ -eq $ents) -and ($proc -eq 0) -and ($sold -le $cap)
  return [ordered]@{
    sold = $sold; paid = $paid; succReq = $succ; ents = $ents; processing = $proc
    stockCap = $cap; requestTotal = $reqTotal; byState = $byState; ok = $ok
  }
}

function Invoke-SeedWithRetry([scriptblock]$SeedCall, [int]$Attempts = 4) {
  $last = $null
  for ($a = 1; $a -le $Attempts; $a++) {
    try {
      $h = Invoke-RestMethod "$ApiBase/actuator/health/readiness" -TimeoutSec 5
      if ($h.status -ne 'UP') { throw "readiness=$($h.status)" }
      return & $SeedCall
    } catch {
      $last = $_
      Write-Warning "seed attempt $a/$Attempts failed: $($_.Exception.Message)"
      Start-Sleep -Seconds (2 * $a)
    }
  }
  throw $last
}

function Seed-Campaign([int]$Buyers, [int]$Stock, [string]$Prefix, [int]$PaidOrders = 0) {
  return Invoke-SeedWithRetry {
    $seedParams = @{
      ApiBase = $ApiBase
      Buyers = $Buyers
      Stock = $Stock
      Prefix = $Prefix
      OutDir = 'target/jmeter-data'
      ThrottleLimit = $ThrottleLimit
      ReuseCsvGlob = 'target/jmeter-data/buyers-*.csv'
    }
    if ($PaidOrders -gt 0) { $seedParams.PaidOrders = $PaidOrders }
    $metaPath = & (Join-Path $here 'seed-commerce-data.ps1') @seedParams -ReturnMetaPath
    if (-not $metaPath) { throw "meta not found for prefix=$Prefix" }
    $meta = Get-Content $metaPath -Raw | ConvertFrom-Json
    Write-Host "seeded campaign=$($meta.campaignId) stock=$Stock buyers=$($meta.buyers) reused=$($meta.buyersReused) registered=$($meta.buyersRegistered)"
    $metaPath
  }
}

function Run-5k([string]$Band, [string]$Meta, [int]$StockCap, [string]$Kind) {
  $hostBefore = HostSnap
  Write-Host "=== $Band kind=$Kind configuredThreads=$Threads stockCap=$StockCap freeRam=$($hostBefore.freeRamGB)GB ==="
  if ($hostBefore.freeRamGB -lt 2.0) {
    Write-Warning "freeRam=$($hostBefore.freeRamGB)GB may under-launch $Threads threads — will archive measured peakActive, not configured"
  }

  $runId = [guid]::NewGuid().ToString('N')
  & (Join-Path $here 'run-commerce-jmeter.ps1') -ApiBase $ApiBase -Scenario purchase `
    -Threads $Threads -RampSec $RampSec -DurationSec 0 -Loops 1 `
    -MetaJson $Meta -Band $Band -EvidenceDir $EvidenceRoot `
    -WarmupThreads 0 -TimeoutMs $TimeoutMs -ClientKeyMode csv -SkipHtml `
    -BarrierEnabled -BarrierGroupSize $Threads -BarrierTimeoutMs $BarrierTimeoutMs -RunId $runId -CapacityEvidence

  $dir = Get-Item -LiteralPath (Join-Path $EvidenceRoot "$Band-purchase-t$Threads-$runId")
  $hostAfter = HostSnap
  $cls = Classify-PurchaseJtl (Join-Path $dir.FullName 'results.jtl')
  $win = Get-Content (Join-Path $dir.FullName 'window.json') -Raw | ConvertFrom-Json
  $hm = Get-Content (Join-Path $dir.FullName 'host-metrics.json') -Raw | ConvertFrom-Json
  $m = Get-Content $Meta -Raw | ConvertFrom-Json

  Write-Host "POST-load drain/poll campaign=$($m.campaignId) (outside emit window)"
  $t0 = [long]$win.formalWindow.startEpochMs
  $t1 = [long]$win.formalWindow.endEpochMs
  $drain = Drain-Campaign $m.campaignId 900
  $tDrain = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $recon = Reconcile $m.campaignId

  $paidWindow = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($m.campaignId) AND state='PAID' AND paid_at_ms>=$t0 AND paid_at_ms<$t1")
  $paidDrain = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($m.campaignId) AND state='PAID' AND paid_at_ms>=$t0 AND paid_at_ms<=$tDrain")
  $procAccept = [int](Sql "SELECT COUNT(DISTINCT reservation_id) FROM material_purchase_requests WHERE campaign_id=$($m.campaignId) AND created_at_ms>=$t0 AND created_at_ms<=$tDrain")
  $windowSec = [Math]::Max(0.001, ($t1 - $t0) / 1000.0)
  $fullSec = [Math]::Max(0.001, ($tDrain - $t0) / 1000.0)

  $underLaunch = $null -eq $win.jmeter.peakActiveThreads -or ([int]$win.jmeter.peakActiveThreads -lt [int]($Threads * 0.8))
  $result = [ordered]@{
    band = $Band
    kind = $Kind
    dir = $dir.Name
    configuredThreads = $Threads
    measuredPeakActiveThreads = $win.jmeter.peakActiveThreads
    underLaunch = [bool]$underLaunch
    note = 'Do not treat configuredThreads as achieved concurrency; use measuredPeakActiveThreads'
    startBarrier = $win.startBarrier
    peakHttpInFlight = $hm.httpInFlight.observedPeakHttpInFlight
    peakSettleInFlight = $hm.settleInFlight.observedPeakSettleInFlight
    emitSpanMs = $win.jmeter.emitSpanMs
    samples = $win.jmeter.samples
    hostBefore = $hostBefore
    hostAfter = $hostAfter
    classification = $cls
    campaignId = $m.campaignId
    stockCap = $StockCap
    drain = $drain
    reconcile = $recon
    throughput = [ordered]@{
      label = $Kind
      uniqueProcessingAccepts = $procAccept
      acceptQpsFromHttp = $cls.acceptQps
      soldOutQps = $cls.soldOutQps
      requestQps = $cls.requestQps
      paidInFormalWindow = $paidWindow
      settleSteadyTps = [Math]::Round($paidWindow / $windowSec, 2)
      paidThroughDrain = $paidDrain
      settleFullTps = [Math]::Round($paidDrain / $fullSec, 2)
      formalWindowSec = [Math]::Round($windowSec, 3)
      fullWallSec = [Math]::Round($fullSec, 3)
      backlogPeakProcessing = $drain.peakProcessingObserved
      drainSec = $drain.drainSec
    }
  }
  $result | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $dir.FullName 'purchase-5k-report.json') -Encoding utf8
  Write-Host ("RESULT band={0} cfg={1} peakActive={2} samples={3} sys={4} connect={5} timeout={6} soldOut={7} accept={8} reconOk={9} barrierReady={10}" -f `
    $Band, $Threads, $result.measuredPeakActiveThreads, $cls.postPurchaseSamples, $cls.systemErrors, `
    $cls.connectFail, $cls.timeouts, $cls.soldOut, $cls.acceptHttpOk, $recon.ok, `
    $(if ($win.startBarrier) { $win.startBarrier.readyCount } else { 'n/a' }))
  return $result
}

$index = [System.Collections.Generic.List[object]]::new()
$runStamp = Get-Date -Format 'yyyyMMddHHmmss'
$host0 = HostSnap
Write-Host "host0 freeRam=$($host0.freeRamGB)GB total=$($host0.totalRamGB)GB"

$limitedOk = $false
if (-not $SkipLimited) {
  $metaLim = Seed-Campaign $Threads $LimitedStock "p5klim" 0
  $lim = Run-5k "purchase-5k-limited-t$Threads" $metaLim $LimitedStock 'limited-stock-firstbuy-barrier'
  $index.Add($lim) | Out-Null
  $limitedOk = [bool]$lim.reconcile.ok
  if (-not $limitedOk) {
    Write-Warning "limited-stock reconcile FAILED — archiving attempt; NOT auto-falling back to lower thread count"
  }
  if ($lim.underLaunch -or $lim.classification.systemErrors -gt 0) {
    Write-Warning "5k attempt issues underLaunch=$($lim.underLaunch) sys=$($lim.classification.systemErrors) connect=$($lim.classification.connectFail) timeout=$($lim.classification.timeouts) — evidence kept as-is"
  }
}

if (-not $SkipInStock) {
  if (-not $limitedOk -and -not $SkipLimited) {
    Write-Host "SKIP in-stock 5k: limited-stock reconcile did not pass (per plan: only after reconcile OK)"
  } else {
    $metaSt = Seed-Campaign $Threads $Threads "p5kstk" 0
    $st = Run-5k "purchase-5k-stock-t$Threads" $metaSt $Threads 'in-stock-firstbuy-barrier'
    $index.Add($st) | Out-Null
  }
}

$out = Join-Path $EvidenceRoot ("purchase-post-5k-$runStamp.json")
@{
  runStamp = $runStamp
  configuredThreads = $Threads
  limitedStock = $LimitedStock
  hostAtStart = $host0
  runs = @($index)
  policy = @{
    noSilentDownscale = $true
    noAutoFallbackToLowerBand = $true
    reportMeasuredNotConfigured = $true
    remoteInjectorNotUsed = $true
  }
} | ConvertTo-Json -Depth 12 | Set-Content $out -Encoding utf8
Write-Host "PURCHASE_5K_DONE $out"
