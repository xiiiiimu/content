<#
.SYNOPSIS
  CLI JMeter runner. Seed/preflight/warmup are OUTSIDE the formal timed window.
  Formal runs: no View Results Tree. Samples HTTP + settle in-flight peaks separately.
#>
#Requires -Version 7
param(
  [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
  [string]$JMeterHome = '',
  [string]$Scenario = 'flash',
  [int]$Threads = 20,
  [int]$RampSec = 2,
  [int]$DurationSec = 0,
  [int]$Loops = 1,
  [int]$TimeoutMs = 10000,
  [long]$StartEpochMs = 0,
  [string]$CsvUsers = '',
  [long]$CampaignId = 0,
  [long]$PostId = 0,
  [string]$MetaJson = '',
  [string]$EvidenceDir = 'docs/perf-evidence/commerce/jmeter',
  [string]$Band = 'smoke',
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [switch]$SkipPreflight,
  [int]$WarmupThreads = 5,
  [int]$WarmupSec = 3,
  [string]$ClientKeyMode = 'csv',
  [switch]$SkipHtml
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Set-Location $RepoRoot

if (-not $JMeterHome) {
  $cand = Join-Path $RepoRoot 'tools/apache-jmeter-5.6.3'
  if (Test-Path (Join-Path $cand 'bin/jmeter.bat')) { $JMeterHome = $cand }
  else { throw 'JMeter not found — set -JMeterHome or install tools/apache-jmeter-5.6.3' }
}
$jmeter = Join-Path $JMeterHome 'bin/jmeter.bat'

$meta = $null
if ($MetaJson -and (Test-Path $MetaJson)) {
  $meta = Get-Content $MetaJson -Raw | ConvertFrom-Json
  if (-not $CampaignId) { $CampaignId = [long]$meta.campaignId }
  if (-not $PostId) { $PostId = [long]$meta.postId }
  if (-not $CsvUsers) {
    # poll/order/sign prefer reservation/order CSV; others use buyers
    if ($Scenario -eq 'poll' -and $meta.reservationsCsv -and (Test-Path ([string]$meta.reservationsCsv))) {
      $CsvUsers = [string]$meta.reservationsCsv
    } elseif ($Scenario -in @('order','sign') -and $meta.ordersCsv -and (Test-Path ([string]$meta.ordersCsv))) {
      $CsvUsers = [string]$meta.ordersCsv
    } else {
      $CsvUsers = [string]$meta.buyersCsv
    }
  }
}
if (-not $CsvUsers) { throw 'CsvUsers or MetaJson with buyersCsv required' }
if ($CampaignId -le 0 -or $PostId -le 0) { throw 'campaignId/postId required' }

# --- OUTSIDE formal window: preflight ---
$preflight = $null
if (-not $SkipPreflight) {
  if (-not $MetaJson) { throw 'MetaJson required for preflight' }
  $preflightOut = @( & (Join-Path $PSScriptRoot 'preflight-commerce.ps1') -MetaJson $MetaJson -ApiBase $ApiBase -MinBuyers ([Math]::Min(1,$Threads)) )
  $preflight = $preflightOut | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties.Name -contains 'buyers' } | Select-Object -Last 1
  if (-not $preflight) { throw "preflight returned no object: $preflightOut" }
  Write-Host "preflight OK buyers=$($preflight.buyers) campaign=$($preflight.campaignId)"
}

$runId = Get-Date -Format 'yyyyMMddHHmmss'
$outDir = Join-Path $EvidenceDir "$Band-$Scenario-t$Threads-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$jtl = Join-Path $outDir 'results.jtl'
$html = Join-Path $outDir 'html'
$jmlog = Join-Path $outDir 'jmeter.log'
$summary = Join-Path $outDir 'summary.json'
$hostMetrics = Join-Path $outDir 'host-metrics.json'
$windowNote = Join-Path $outDir 'window.json'

# CSV headers define vars (see commerce-sale.jmx CSVDataSet); do not pass -JcsvVars (commas break __P).

function Invoke-JMeter([hashtable]$P, [string]$JtlPath, [string]$LogPath, [string]$HtmlPath) {
  $props = @(
    "-JapiBase=$($P.apiBase)",
    "-Jthreads=$($P.threads)",
    "-JrampSec=$($P.rampSec)",
    "-JdurationSec=$($P.durationSec)",
    "-Jloops=$($P.loops)",
    "-JtimeoutMs=$($P.timeoutMs)",
    "-Jscenario=$($P.scenario)",
    "-JcsvUsers=$($P.csvUsers)",
    "-JcampaignId=$($P.campaignId)",
    "-JpostId=$($P.postId)",
    "-JstartEpochMs=$($P.startEpochMs)",
    "-JclientKeyMode=$($P.clientKeyMode)"
  )
  $jmx = Join-Path $PSScriptRoot 'commerce-sale.jmx'
  # Quote every -J so paths with spaces survive cmd.exe; capture error bodies for 503 classification.
  $props += '-Jjmeter.save.saveservice.response_data_on_error=true'
  $propStr = ($props | ForEach-Object { '"{0}"' -f $_ }) -join ' '
  $cmd = "`"$jmeter`" -n -t `"$jmx`" -l `"$JtlPath`" -j `"$LogPath`" $propStr"
  if ($HtmlPath) { $cmd += " -e -o `"$HtmlPath`"" }
  Write-Host $cmd
  # cmd stdout must not enter the success stream or $jmExit becomes text+code.
  cmd /c $cmd 2>&1 | ForEach-Object { Write-Host $_ }
  return [int]$LASTEXITCODE
}

# Steady-state: duration>0 ⇒ loops=-1 (forever until scheduler). Once-through: loops=1, duration cap.
$effectiveLoops = $Loops
$durationProp = 3600
if ($DurationSec -gt 0) {
  $durationProp = $DurationSec
  if ($Loops -le 0) { $effectiveLoops = -1 }
} elseif ($Loops -le 0) {
  $effectiveLoops = 1
}

# --- OUTSIDE formal window: reset peaks + warmup ---
try { Invoke-RestMethod -Method Post -Uri "$ApiBase/api/sale-debug/reset-inflight-peaks" -ContentType 'application/json' -Body '{}' -TimeoutSec 3 | Out-Null } catch {}
try { Invoke-RestMethod -Method Post -Uri "$ApiBase/api/sale-debug/hold-settle" -ContentType 'application/json' -Body '{"arm":false}' -TimeoutSec 3 | Out-Null } catch {}

$warmupDir = Join-Path $outDir 'warmup'
New-Item -ItemType Directory -Force -Path $warmupDir | Out-Null
if ($WarmupThreads -gt 0 -and $WarmupSec -gt 0 -and $Scenario -in @('detail','purchase','order','sign','poll','mixed')) {
  Write-Host "warmup threads=$WarmupThreads sec=$WarmupSec (excluded from formal window)"
  [void](Invoke-JMeter @{
    apiBase=$ApiBase; threads=$WarmupThreads; rampSec=1; durationSec=$WarmupSec; loops=-1
    timeoutMs=$TimeoutMs; scenario=$Scenario; csvUsers=$CsvUsers
    campaignId=$CampaignId; postId=$PostId; startEpochMs=0; clientKeyMode=$ClientKeyMode
  } (Join-Path $warmupDir 'warmup.jtl') (Join-Path $warmupDir 'warmup.log') $null)
  try { Invoke-RestMethod -Method Post -Uri "$ApiBase/api/sale-debug/reset-inflight-peaks" -ContentType 'application/json' -Body '{}' -TimeoutSec 3 | Out-Null } catch {}
}

# Sample peak HTTP/settle in-flight during formal run
$peakProbe = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
$probeStop = $false
$probeJob = Start-ThreadJob -ScriptBlock {
  param($ApiBase,$Bag,$StopRef)
  while (-not $StopRef.Value) {
    try {
      $x = Invoke-RestMethod "$ApiBase/api/sale-debug/inflight" -TimeoutSec 2
      $Bag.Add([pscustomobject]@{
        at=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        http=$x.httpInFlight; peakHttp=$x.peakHttpInFlight
        settle=$x.settleInFlight; peakSettle=$x.peakSettleInFlight
      }) | Out-Null
    } catch {}
    Start-Sleep -Milliseconds 200
  }
} -ArgumentList $ApiBase,$peakProbe,([ref]$probeStop)

$sw = [Diagnostics.Stopwatch]::StartNew()
$cpuBefore = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
$memBefore = Get-CimInstance Win32_OperatingSystem | Select-Object FreePhysicalMemory,TotalVisibleMemorySize
$formalStartMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$htmlOut = if ($SkipHtml) { $null } else { $html }
$jmExit = Invoke-JMeter @{
  apiBase=$ApiBase; threads=$Threads; rampSec=$RampSec; durationSec=$durationProp; loops=$effectiveLoops
  timeoutMs=$TimeoutMs; scenario=$Scenario; csvUsers=$CsvUsers
  campaignId=$CampaignId; postId=$PostId; startEpochMs=$StartEpochMs; clientKeyMode=$ClientKeyMode
} $jtl $jmlog $htmlOut

$sw.Stop()
$formalEndMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$probeStop = $true
try { Wait-Job $probeJob -Timeout 5 | Out-Null; Receive-Job $probeJob | Out-Null; Remove-Job $probeJob -Force } catch {}

$cpuAfter = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
$memAfter = Get-CimInstance Win32_OperatingSystem | Select-Object FreePhysicalMemory,TotalVisibleMemorySize
$inflightAfter = $null
try { $inflightAfter = Invoke-RestMethod "$ApiBase/api/sale-debug/inflight" -TimeoutSec 3 } catch {}

$probeArr = @($peakProbe)
$obsPeakHttp = if ($probeArr.Count) { ($probeArr | Measure-Object -Property peakHttp -Maximum).Maximum } else { $null }
$obsPeakSettle = if ($probeArr.Count) { ($probeArr | Measure-Object -Property peakSettle -Maximum).Maximum } else { $null }

# Active threads from JTL
$activePeak = $null
$firstTs = $null; $lastTs = $null; $samples = 0
if (Test-Path $jtl) {
  $rows = @(Import-Csv $jtl)
  $samples = $rows.Count
  if ($samples -gt 0) {
    $firstTs = ($rows | Measure-Object timeStamp -Minimum).Minimum
    $lastTs = ($rows | Measure-Object timeStamp -Maximum).Maximum
    $activePeak = ($rows | ForEach-Object { [int]$_.allThreads } | Measure-Object -Maximum).Maximum
  }
}

$expectedMinSamples = if ($DurationSec -gt 0 -and $effectiveLoops -lt 0) { $Threads * 2 } else { $Threads }
$multiIterOk = ($samples -ge $expectedMinSamples)

@{
  formalWindow = @{
    startEpochMs = $formalStartMs
    endEpochMs = $formalEndMs
    wallSec = [Math]::Round($sw.Elapsed.TotalSeconds,3)
    seedAndPreflightExcluded = $true
    warmupExcluded = $true
  }
  jmeter = @{
    configuredThreads = $Threads
    rampSec = $RampSec
    durationSec = $durationProp
    loops = $effectiveLoops
    samples = $samples
    firstSampleTs = $firstTs
    lastSampleTs = $lastTs
    emitSpanMs = $(if ($firstTs -and $lastTs) { [long]$lastTs - [long]$firstTs } else { $null })
    peakActiveThreads = $activePeak
    multiIterCheck = @{ expectedMinSamples = $expectedMinSamples; ok = $multiIterOk }
  }
} | ConvertTo-Json -Depth 6 | Set-Content $windowNote -Encoding utf8

$hostReport = [ordered]@{
  configuredThreads = $Threads
  peakActiveThreadsObserved = $activePeak
  jmeterElapsedSec = [Math]::Round($sw.Elapsed.TotalSeconds, 3)
  cpuLoadPctBefore = $cpuBefore
  cpuLoadPctAfter = $cpuAfter
  freeMemMBBefore = [Math]::Round($memBefore.FreePhysicalMemory/1024,1)
  freeMemMBAfter = [Math]::Round($memAfter.FreePhysicalMemory/1024,1)
  totalMemMB = [Math]::Round($memBefore.TotalVisibleMemorySize/1024,1)
  httpInFlight = @{
    measurement = 'SaleHttpInFlightFilter + GET /api/sale-debug/inflight peakHttpInFlight (polled ~200ms)'
    endSnapshot = $inflightAfter
    observedPeakHttpInFlight = $obsPeakHttp
  }
  settleInFlight = @{
    measurement = 'SaleFaultHooks settle enter/leave; peakSettleInFlight'
    endSnapshot = $inflightAfter
    observedPeakSettleInFlight = $obsPeakSettle
  }
  note = 'Do not treat -Jthreads as capacity ceiling. Connection-refused at ramp = CONNECT_FAIL system error.'
}
$hostReport | ConvertTo-Json -Depth 8 | Set-Content $hostMetrics -Encoding utf8
if ($preflight) { $preflight | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outDir 'preflight.json') -Encoding utf8 }

& (Join-Path $PSScriptRoot 'summarize-jtl.ps1') -JtlPath $jtl -OutJson $summary
Write-Host "jmeterExit=$jmExit samples=$samples multiIterOk=$multiIterOk out=$outDir"
if ($jmExit -ne 0) { throw "jmeter exit $jmExit" }
if (-not $multiIterOk -and $DurationSec -gt 0) {
  Write-Warning "multi-iter check failed: samples=$samples expectedMin=$expectedMinSamples (loops may still be wrong)"
}
