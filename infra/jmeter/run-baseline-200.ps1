<#
.SYNOPSIS
  Formal 200-thread baseline: seed OUTSIDE window → preflight → warmup → steady/flash/mixed + TPS.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$EvidenceRoot = 'docs/perf-evidence/commerce/jmeter',
  [int]$Threads = 200,
  [int]$DurationSec = 20,
  [int]$RampSec = 15
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '../..')).Path
Set-Location $repo
$band = "baseline200-retest"
$index = [System.Collections.Generic.List[object]]::new()

function Seed([int]$Buyers,[int]$Stock,[string]$Prefix,[int]$Paid=0) {
  if ($Paid -gt 0) {
    & (Join-Path $here 'seed-commerce-data.ps1') -ApiBase $ApiBase -Buyers $Buyers -Stock $Stock -Prefix $Prefix -OutDir 'target/jmeter-data' -PaidOrders $Paid
  } else {
    & (Join-Path $here 'seed-commerce-data.ps1') -ApiBase $ApiBase -Buyers $Buyers -Stock $Stock -Prefix $Prefix -OutDir 'target/jmeter-data'
  }
  $m = Get-ChildItem target/jmeter-data -Filter "meta-*.json" | Where-Object { $_.Name -notlike '*.public*' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  return $m.FullName
}

function RunSc([string]$Scenario,[string]$Meta,[int]$Dur,[int]$Loops,[int]$Ramp) {
  & (Join-Path $here 'run-commerce-jmeter.ps1') -ApiBase $ApiBase -Scenario $Scenario -Threads $Threads `
    -RampSec $Ramp -DurationSec $Dur -Loops $Loops -MetaJson $Meta -Band $band -EvidenceDir $EvidenceRoot `
    -WarmupThreads 10 -WarmupSec 4 -TimeoutMs 20000
  $dir = Get-ChildItem $EvidenceRoot -Directory | Where-Object { $_.Name -like "$band-$Scenario-*" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $sum = Get-Content (Join-Path $dir.FullName 'summary.json') -Raw | ConvertFrom-Json
  $win = Get-Content (Join-Path $dir.FullName 'window.json') -Raw | ConvertFrom-Json
  $hostm = Get-Content (Join-Path $dir.FullName 'host-metrics.json') -Raw | ConvertFrom-Json
  $index.Add([pscustomobject]@{
    scenario=$Scenario; dir=$dir.Name; samples=$sum.samples; qps=$sum.requestQps
    p50=$sum.p50Ms; p95=$sum.p95Ms; p99=$sum.p99Ms
    biz=$sum.businessRejects; sys=$sum.systemErrors; connectFail=$sum.connectFail
    multiIterOk=$win.jmeter.multiIterCheck.ok
    peakActive=$hostm.peakActiveThreadsObserved
    peakHttp=$hostm.httpInFlight.observedPeakHttpInFlight
    peakSettle=$hostm.settleInFlight.observedPeakSettleInFlight
  }) | Out-Null
}

Write-Host "=== seed (outside formal windows) ==="
$metaSteady = Seed -Buyers $Threads -Stock ([Math]::Max(500,$Threads)) -Prefix 'b200s' -Paid 80
$metaFlash = Seed -Buyers $Threads -Stock 80 -Prefix 'b200f'
$metaMixed = Seed -Buyers $Threads -Stock $Threads -Prefix 'b200m'

Write-Host "=== steady single-API @ $Threads threads duration=$DurationSec ramp=$RampSec ==="
foreach ($sc in @('detail','purchase','poll','order','sign')) {
  $meta = if ($sc -in @('poll','order','sign')) { $metaSteady } else { $metaSteady }
  RunSc $sc $meta $DurationSec 0 $RampSec
}

Write-Host "=== flash limited stock ==="
$start = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 5000
& (Join-Path $here 'run-commerce-jmeter.ps1') -ApiBase $ApiBase -Scenario flash -Threads $Threads `
  -RampSec 0 -Loops 1 -StartEpochMs $start -MetaJson $metaFlash -Band "$band-flash" -EvidenceDir $EvidenceRoot `
  -WarmupThreads 0 -WarmupSec 0
# drain
$m = Get-Content $metaFlash -Raw | ConvertFrom-Json
$dl=(Get-Date).AddSeconds(180)
while ((Get-Date) -lt $dl) {
  $p = & docker exec -e "MYSQL_PWD=11111" content-mysql-1 mysql -N -ucontent content -e "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($m.campaignId) AND state='PROCESSING'" 2>$null
  if ("$p".Trim() -eq '0') { break }
  Start-Sleep -Seconds 1
}
$recon = & docker exec -e "MYSQL_PWD=11111" content-mysql-1 mysql -N -ucontent content -e "SELECT sold_count,(SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($m.campaignId) AND state='PAID'),(SELECT COUNT(*) FROM material_entitlements e JOIN material_orders o ON e.source_kind='PURCHASE' AND e.source_id=o.id WHERE o.campaign_id=$($m.campaignId) AND e.state='ACTIVE') FROM material_sale_campaigns WHERE id=$($m.campaignId)" 2>$null
Write-Host "flash reconcile: $recon"

Write-Host "=== mixed ==="
RunSc 'mixed' $metaMixed 0 1 8

Write-Host "=== TPS windows ==="
& (Join-Path $here 'run-tps-window.ps1') -ApiBase $ApiBase -Buyers 100 -Stock 100 -WindowSec 20 -EvidenceDir $EvidenceRoot
& (Join-Path $here 'run-tps-giveaway-auto.ps1') -ApiBase $ApiBase -Buyers 6 -EvidenceDir $EvidenceRoot

$tablePath = Join-Path $EvidenceRoot "baseline200-summary-$(Get-Date -Format 'yyyyMMddHHmmss').json"
$index | ConvertTo-Json -Depth 5 | Set-Content $tablePath -Encoding utf8
Write-Host "BASELINE200_DONE table=$tablePath"
$index | Format-Table -AutoSize
