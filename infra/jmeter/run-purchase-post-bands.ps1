<#
.SYNOPSIS
  Purchase-POST-only local bands: smoke → sold-out continuous → in-stock first-buy burst.
  Uses run-commerce-jmeter.ps1 directly (NO MaxLocalThreads clamp from run-formal-bands.ps1).
  Does not re-run the 200-thread multi-API baseline.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$EvidenceRoot = 'docs/perf-evidence/commerce/jmeter',
  [int]$Band1 = 500,
  [int]$Band2 = 1000,
  [int]$SmokeThreads = 20,
  [int]$SoldOutDurationSec = 20,
  [int]$TimeoutMs = 20000,
  [switch]$Try2000After1000,
  [switch]$SmokeOnly,
  [switch]$SkipSmoke
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '../..')).Path
Set-Location $repo
$index = [System.Collections.Generic.List[object]]::new()

function Sql([string]$q) {
  $o = & docker exec -e 'MYSQL_PWD=11111' content-mysql-1 mysql -N -ucontent content -e $q 2>&1
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
  # Prefer first-start→last-end so concurrent Loops=1 bursts are not inflated by ~0 start-ts span.
  $spanStartSec = [Math]::Max(0.001, ($ts[-1] - $ts[0]) / 1000.0)
  $spanSec = [Math]::Max(0.001, ($ends[-1] - $ts[0]) / 1000.0)
  $spanEndSec = $spanSec
  $soldOut = 0; $accept = 0; $idempotent = 0; $bizOther = 0
  $sys = 0; $timeout = 0; $connect = 0; $http5xx = 0
  $latOk = [System.Collections.Generic.List[int]]::new()
  $latSold = [System.Collections.Generic.List[int]]::new()
  foreach ($r in $rows) {
    $code = [string]$r.responseCode
    $msg = [string]$r.responseMessage
    $ok = [string]$r.success
    $el = [int]$r.elapsed
    $body = ''
    foreach ($n in @('responseData','failureMessage','responseMessage')) {
      if ($r.PSObject.Properties.Name -contains $n -and $r.$n) { $body += [string]$r.$n }
    }
    if ($code -match 'ConnectException|HttpHostConnect' -or $msg -match 'Connection refused') { $connect++; $sys++; continue }
    if ($code -match 'SocketTimeout|Timeout' -or $msg -match 'timed out|Timeout') { $timeout++; $sys++; continue }
    $http = 0; [void][int]::TryParse($code, [ref]$http)
    if ($http -ge 500) { $http5xx++; $sys++; continue }
    if ($ok -eq 'true' -or $ok -eq 'TRUE') {
      if ($body -match 'PROCESSING' -or $http -eq 200) { $accept++; $latOk.Add($el) | Out-Null }
      else { $accept++; $latOk.Add($el) | Out-Null }
      continue
    }
    # JTL often omits response body; 409 on sold-out unique-ck runs is SOLD_OUT (ALREADY_OWNED is settle-time).
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
    startTsSpanSec = [Math]::Round($spanStartSec, 3)
    windowEndInclusiveSec = [Math]::Round($spanEndSec, 3)
    requestQps = [Math]::Round($rows.Count / $spanSec, 2)
    soldOut = $soldOut
    soldOutQps = [Math]::Round($soldOut / $spanSec, 2)
    soldOutP95 = (Pct $latSold 95)
    soldOutP99 = (Pct $latSold 99)
    acceptHttpOk = $accept
    acceptQps = [Math]::Round($accept / $spanSec, 2)
    acceptP95 = (Pct $latOk 95)
    acceptP99 = (Pct $latOk 99)
    idempotentReplay = $idempotent
    bizOther = $bizOther
    systemErrors = $sys
    timeouts = $timeout
    connectFail = $connect
    http5xx = $http5xx
    peakAllThreads = ($rows | ForEach-Object { [int]$_.allThreads } | Measure-Object -Maximum).Maximum
  }
}

function Drain-Campaign([long]$CampaignId, [int]$TimeoutSec = 180) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $dl = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $dl) {
    $p = Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='PROCESSING'"
    if ("$p".Trim() -eq '0') { break }
    Start-Sleep -Milliseconds 250
  }
  $sw.Stop()
  $left = [int](Sql "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='PROCESSING'")
  return [ordered]@{ drainSec = [Math]::Round($sw.Elapsed.TotalSeconds, 3); processingLeft = $left }
}

function Reconcile([long]$CampaignId) {
  $row = Sql @"
SELECT sold_count,
 (SELECT COUNT(*) FROM material_orders WHERE campaign_id=$CampaignId AND state='PAID'),
 (SELECT COUNT(DISTINCT reservation_id) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='SUCCEEDED'),
 (SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$CampaignId AND e.state='ACTIVE'),
 (SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$CampaignId AND state='PROCESSING'),
 stock_cap
FROM material_sale_campaigns WHERE id=$CampaignId
"@
  $p = @($row -split '\t')
  $sold = [int]$p[0]; $paid = [int]$p[1]; $succ = [int]$p[2]; $ents = [int]$p[3]; $proc = [int]$p[4]; $cap = [int]$p[5]
  $ok = ($sold -eq $paid) -and ($paid -eq $succ) -and ($succ -eq $ents) -and ($proc -eq 0) -and ($sold -le $cap)
  return [ordered]@{ sold=$sold; paid=$paid; succReq=$succ; ents=$ents; processing=$proc; stockCap=$cap; ok=$ok; raw=$row }
}

function Invoke-SeedWithRetry([scriptblock]$SeedCall, [int]$Attempts = 4) {
  $last = $null
  for ($a = 1; $a -le $Attempts; $a++) {
    try {
      # Probe API before seed
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

function Seed-SoldOut([int]$LoadBuyers, [string]$Prefix) {
  # stockCap=1 + PaidOrders=1 → remaining 0, campaign stays OPEN.
  # Load uses clientKeyMode=unique so every POST hits Redis SOLD_OUT (not idempotent replay).
  return Invoke-SeedWithRetry {
    & (Join-Path $here 'seed-commerce-data.ps1') -ApiBase $ApiBase -Buyers $LoadBuyers -Stock 1 -Prefix $Prefix `
      -OutDir 'target/jmeter-data' -PaidOrders 1
    $metaPath = (Get-ChildItem target/jmeter-data -Filter "meta-$Prefix*.json" | Where-Object { $_.Name -notlike '*.public*' } |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    $meta = Get-Content $metaPath -Raw | ConvertFrom-Json
    $metaObj = [ordered]@{}
    foreach ($p in $meta.PSObject.Properties) { $metaObj[$p.Name] = $p.Value }
    $metaObj['mode'] = 'sold-out'
    $metaObj | ConvertTo-Json -Depth 6 | Set-Content $metaPath -Encoding utf8
    $rem = Sql "SELECT stock_cap - sold_count FROM material_sale_campaigns WHERE id=$($metaObj.campaignId)"
    if ([int]$rem -ne 0) { throw "sold-out seed remaining=$rem expected 0" }
    Write-Host "sold-out seed campaign=$($metaObj.campaignId) remaining=$rem buyers=$LoadBuyers"
    $metaPath
  }
}

function Seed-InStock([int]$Buyers, [string]$Prefix) {
  return Invoke-SeedWithRetry {
    & (Join-Path $here 'seed-commerce-data.ps1') -ApiBase $ApiBase -Buyers $Buyers -Stock $Buyers -Prefix $Prefix -OutDir 'target/jmeter-data'
    (Get-ChildItem target/jmeter-data -Filter "meta-$Prefix*.json" | Where-Object { $_.Name -notlike '*.public*' } |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
  }
}

function Run-Purchase([string]$Band, [string]$Meta, [int]$Threads, [int]$Duration, [int]$Loops, [string]$CkMode, [string]$Kind) {
  $hostBefore = HostSnap
  Write-Host "=== $Band kind=$Kind threads=$Threads duration=$Duration loops=$Loops ck=$CkMode ==="
  Write-Host "hostBefore freeRam=$($hostBefore.freeRamGB)GB cpu=$($hostBefore.cpuLoad)"
  & (Join-Path $here 'run-commerce-jmeter.ps1') -ApiBase $ApiBase -Scenario purchase `
    -Threads $Threads -RampSec ([Math]::Min(60, [Math]::Max(10, [int]($Threads / 20)))) `
    -DurationSec $Duration -Loops $Loops -MetaJson $Meta -Band $Band `
    -EvidenceDir $EvidenceRoot -WarmupThreads 0 -TimeoutMs $TimeoutMs `
    -ClientKeyMode $CkMode -SkipHtml
  $dir = Get-ChildItem $EvidenceRoot -Directory | Where-Object { $_.Name -like "$Band-purchase-*" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $hostAfter = HostSnap
  $cls = Classify-PurchaseJtl (Join-Path $dir.FullName 'results.jtl')
  $win = Get-Content (Join-Path $dir.FullName 'window.json') -Raw | ConvertFrom-Json
  $hm = Get-Content (Join-Path $dir.FullName 'host-metrics.json') -Raw | ConvertFrom-Json
  $m = Get-Content $Meta -Raw | ConvertFrom-Json
  $result = [ordered]@{
    band = $Band; kind = $Kind; dir = $dir.Name
    configuredThreads = $Threads
    peakActiveThreads = $win.jmeter.peakActiveThreads
    peakHttpInFlight = $hm.httpInFlight.observedPeakHttpInFlight
    peakSettleInFlight = $hm.settleInFlight.observedPeakSettleInFlight
    emitSpanMs = $win.jmeter.emitSpanMs
    hostBefore = $hostBefore; hostAfter = $hostAfter
    classification = $cls
    campaignId = $m.campaignId
  }
  if ($Kind -eq 'in-stock-burst') {
    $t0 = [long]$win.formalWindow.startEpochMs
    $t1 = [long]$win.formalWindow.endEpochMs
    $drain = Drain-Campaign $m.campaignId 240
    $tDrain = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $paidWindow = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($m.campaignId) AND state='PAID' AND paid_at_ms>=$t0 AND paid_at_ms<$t1")
    $paidDrain = [int](Sql "SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($m.campaignId) AND state='PAID' AND paid_at_ms>=$t0 AND paid_at_ms<=$tDrain")
    $procAccept = [int](Sql "SELECT COUNT(DISTINCT reservation_id) FROM material_purchase_requests WHERE campaign_id=$($m.campaignId) AND created_at_ms>=$t0 AND created_at_ms<=$tDrain")
    $windowSec = [Math]::Max(0.001, ($t1 - $t0) / 1000.0)
    $fullSec = [Math]::Max(0.001, ($tDrain - $t0) / 1000.0)
    $recon = Reconcile $m.campaignId
    $result.throughput = [ordered]@{
      label = 'burst (Loops=1 first-buy); not steady-window accept QPS unless buyers/stock cover full duration'
      uniqueProcessingAccepts = $procAccept
      acceptQpsFromHttp = $cls.acceptQps
      paidInFormalWindow = $paidWindow
      settleSteadyTps = [Math]::Round($paidWindow / $windowSec, 2)
      paidThroughDrain = $paidDrain
      settleFullTps = [Math]::Round($paidDrain / $fullSec, 2)
      drain = $drain
      formalWindowSec = [Math]::Round($windowSec, 3)
      fullWallSec = [Math]::Round($fullSec, 3)
    }
    $result.reconcile = $recon
    if (-not $recon.ok) {
      $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $dir.FullName 'purchase-band-report.json') -Encoding utf8
      throw "RECONCILE_FAIL band=$Band sold/paid/succ/ents=$($recon.raw)"
    }
  } else {
    $result.throughput = [ordered]@{
      label = 'sold-out continuous; SOLD_OUT QPS over POST-purchase window'
      soldOutQps = $cls.soldOutQps
      soldOutP95 = $cls.soldOutP95
      soldOutP99 = $cls.soldOutP99
      requestQps = $cls.requestQps
    }
  }
  $result | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $dir.FullName 'purchase-band-report.json') -Encoding utf8
  $index.Add($result) | Out-Null
  if ($Threads -gt 0 -and $win.jmeter.peakActiveThreads -lt [int]($Threads * 0.8)) {
    Write-Warning "thread under-launch configured=$Threads peakActive=$($win.jmeter.peakActiveThreads) — see $($dir.FullName)\jmeter.log"
  }
  return $result
}

Write-Host "NOTE: run-commerce-jmeter has no MaxLocalThreads clamp; run-formal-bands.ps1 does (avoided here)."

# --- Smoke (few requests) ---
if (-not $SkipSmoke) {
  $smokeMeta = Seed-InStock $SmokeThreads 'psmoke'
  $smoke = Run-Purchase 'purchase-smoke' $smokeMeta $SmokeThreads 0 1 'csv' 'in-stock-burst'
  Write-Host "SMOKE ok reconcile=$($smoke.reconcile.ok) accept=$($smoke.classification.acceptHttpOk) sys=$($smoke.classification.systemErrors)"
  if ($SmokeOnly) {
    $out = Join-Path $EvidenceRoot ("purchase-post-bands-smoke-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.json')
    $index | ConvertTo-Json -Depth 10 | Set-Content $out -Encoding utf8
    Write-Host "PURCHASE_BANDS_SMOKE_DONE $out"
    return
  }
}

$bandList = @($Band1, $Band2) | Where-Object { $_ -gt 0 }

foreach ($n in $bandList) {
  $pfxSo = "pso$n"
  $pfxSt = "pst$n"
  $metaSo = Seed-SoldOut $n $pfxSo
  [void](Run-Purchase "purchase-soldout-t$n" $metaSo $n $SoldOutDurationSec 0 'unique' 'sold-out-continuous')

  $metaSt = Seed-InStock $n $pfxSt
  $stockRun = Run-Purchase "purchase-stock-t$n" $metaSt $n 0 1 'csv' 'in-stock-burst'
  if (-not $stockRun.reconcile.ok) { throw "stop before next band; reconcile failed at $n" }

  if ($n -eq 1000 -and $Try2000After1000) {
    $peak = [int]$stockRun.peakActiveThreads
    $drainOk = [int]$stockRun.throughput.drain.processingLeft -eq 0
    if ($peak -ge 800 -and $drainOk) {
      Write-Host '1000 effective — attempting 2000'
      $metaSo2 = Seed-SoldOut 2000 'pso2000'
      [void](Run-Purchase 'purchase-soldout-t2000' $metaSo2 2000 $SoldOutDurationSec 0 'unique' 'sold-out-continuous')
      $metaSt2 = Seed-InStock 2000 'pst2000'
      [void](Run-Purchase 'purchase-stock-t2000' $metaSt2 2000 0 1 'csv' 'in-stock-burst')
    } else {
      Write-Host "skip 2000: peakActive=$peak drainOk=$drainOk"
    }
  }
}

$out = Join-Path $EvidenceRoot ("purchase-post-bands-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.json')
$index | ConvertTo-Json -Depth 10 | Set-Content $out -Encoding utf8
Write-Host "PURCHASE_BANDS_DONE $out"
$index | ForEach-Object {
  "band=$($_.band) kind=$($_.kind) cfg=$($_.configuredThreads) peakActive=$($_.peakActiveThreads) peakHttp=$($_.peakHttpInFlight) sys=$($_.classification.systemErrors)"
}
