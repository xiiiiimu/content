<#
.SYNOPSIS
  CLI JMeter runner for commerce-sale.jmx → CSV JTL + HTML report + summarize.
  Formal runs: no View Results Tree. Records launch timing + optional inflight peak.
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
  [string]$ApiBase = 'http://127.0.0.1:28080'
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

if ($MetaJson -and (Test-Path $MetaJson)) {
  $meta = Get-Content $MetaJson -Raw | ConvertFrom-Json
  if (-not $CampaignId) { $CampaignId = [long]$meta.campaignId }
  if (-not $PostId) { $PostId = [long]$meta.postId }
  if (-not $CsvUsers) { $CsvUsers = [string]$meta.buyersCsv }
}
if (-not $CsvUsers) { throw 'CsvUsers or MetaJson with buyersCsv required' }
if ($CampaignId -le 0 -or $PostId -le 0) { throw 'campaignId/postId required' }

$runId = Get-Date -Format 'yyyyMMddHHmmss'
$outDir = Join-Path $EvidenceDir "$Band-$Scenario-t$Threads-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$jtl = Join-Path $outDir 'results.jtl'
$html = Join-Path $outDir 'html'
$jmlog = Join-Path $outDir 'jmeter.log'
$summary = Join-Path $outDir 'summary.json'
$hostMetrics = Join-Path $outDir 'host-metrics.json'

# reset peak if fault-hooks present
try { Invoke-RestMethod -Method Post -Uri "$ApiBase/api/sale-debug/hold-settle" -ContentType 'application/json' -Body '{"arm":false}' -TimeoutSec 3 | Out-Null } catch {}

# Scheduler always on. Timed QPS: durationSec>0 & loops=-1. Once-through: loops=1 & durationSec=3600 cap.
$effectiveLoops = $Loops
$durationProp = 3600
if ($DurationSec -gt 0) {
  $durationProp = $DurationSec
  if ($Loops -eq 0) { $effectiveLoops = -1 }
} elseif ($Loops -eq 0) {
  $effectiveLoops = 1
}

$props = @(
  "-JapiBase=$ApiBase",
  "-Jthreads=$Threads",
  "-JrampSec=$RampSec",
  "-JdurationSec=$durationProp",
  "-Jloops=$effectiveLoops",
  "-JtimeoutMs=$TimeoutMs",
  "-Jscenario=$Scenario",
  "-JcsvUsers=$CsvUsers",
  "-JcampaignId=$CampaignId",
  "-JpostId=$PostId",
  "-JstartEpochMs=$StartEpochMs"
)

$sw = [Diagnostics.Stopwatch]::StartNew()
$cpuBefore = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
$memBefore = Get-CimInstance Win32_OperatingSystem | Select-Object FreePhysicalMemory,TotalVisibleMemorySize
$inflightBefore = $null
try { $inflightBefore = Invoke-RestMethod "$ApiBase/api/sale-debug/inflight" -TimeoutSec 3 } catch {}

# Build a single cmdline so cmd.exe/jmeter.bat does not drop negative -Jloops=-1.
$jmx = Join-Path $PSScriptRoot 'commerce-sale.jmx'
$propStr = ($props -join ' ')
$cmd = "`"$jmeter`" -n -t `"$jmx`" -l `"$jtl`" -j `"$jmlog`" -e -o `"$html`" $propStr"
Write-Host $cmd
cmd /c $cmd
$jmExit = $LASTEXITCODE
$sw.Stop()

$cpuAfter = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
$memAfter = Get-CimInstance Win32_OperatingSystem | Select-Object FreePhysicalMemory,TotalVisibleMemorySize
$inflightAfter = $null
try { $inflightAfter = Invoke-RestMethod "$ApiBase/api/sale-debug/inflight" -TimeoutSec 3 } catch {}

$hostReport = [ordered]@{
  configuredThreads = $Threads
  jmeterElapsedSec = [Math]::Round($sw.Elapsed.TotalSeconds, 3)
  cpuLoadPctBefore = $cpuBefore
  cpuLoadPctAfter = $cpuAfter
  freeMemMBBefore = [Math]::Round($memBefore.FreePhysicalMemory/1024,1)
  freeMemMBAfter = [Math]::Round($memAfter.FreePhysicalMemory/1024,1)
  totalMemMB = [Math]::Round($memBefore.TotalVisibleMemorySize/1024,1)
  serverInFlight = @{
    measurement = 'GET /api/sale-debug/inflight (SaleFaultHooks AtomicLong; settle enter/leave)'
    before = $inflightBefore
    after = $inflightAfter
  }
  note = 'Report actual peak active threads from JMeter jmeter.log / HTML; do not treat -Jthreads as capacity ceiling.'
}
$hostReport | ConvertTo-Json -Depth 6 | Set-Content $hostMetrics -Encoding utf8

& (Join-Path $PSScriptRoot 'summarize-jtl.ps1') -JtlPath $jtl -OutJson $summary
Write-Host "jmeterExit=$jmExit out=$outDir"
if ($jmExit -ne 0) { throw "jmeter exit $jmExit" }
