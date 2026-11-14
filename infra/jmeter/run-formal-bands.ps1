<#
.SYNOPSIS
  Orchestrate smoke then 5k/10k bands for scenarios A–D.
  Records actual achieved concurrency; does not claim configured threads as capacity.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [string]$EvidenceRoot = 'docs/perf-evidence/commerce/jmeter',
  [int]$SmokeThreads = 20,
  [int]$Band5k = 5000,
  [int]$Band10k = 10000,
  [int]$MaxLocalThreads = 0,
  [switch]$SkipHeavy
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$here = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $here '../..')).Path
Set-Location $repo

# Cap threads to something the host can attempt; still report configured vs actual.
if ($MaxLocalThreads -le 0) {
  $cores = [Math]::Max(1, (Get-CimInstance Win32_Processor | Measure-Object NumberOfLogicalProcessors -Sum).Sum)
  $MaxLocalThreads = [Math]::Min(2000, [Math]::Max(100, $cores * 64))
}
Write-Host "MaxLocalThreads=$MaxLocalThreads (OS attempt cap; not a product capacity claim)"

function Seed([int]$Buyers,[int]$Stock,[string]$Prefix) {
  & (Join-Path $here 'seed-commerce-data.ps1') -ApiBase $ApiBase -Buyers $Buyers -Stock $Stock -Prefix $Prefix -OutDir 'target/jmeter-data'
  $meta = Get-ChildItem 'target/jmeter-data' -Filter "meta-$Prefix.json" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  return $meta.FullName
}

function RunBand([string]$Band,[string]$Scenario,[int]$WantThreads,[string]$Meta,[int]$StockHint,[int]$Duration=0,[int]$Loops=1,[int]$Ramp=5) {
  $t = [Math]::Min($WantThreads, $MaxLocalThreads)
  if ($t -lt $WantThreads) {
    Write-Host "WARN band=$Band scenario=$Scenario configuredWant=$WantThreads actualLaunchThreads=$t (host cap)"
  }
  & (Join-Path $here 'run-commerce-jmeter.ps1') -ApiBase $ApiBase -Scenario $Scenario -Threads $t `
    -RampSec $Ramp -DurationSec $Duration -Loops $Loops -MetaJson $Meta -Band $Band -EvidenceDir $EvidenceRoot
  # Drain PROCESSING for flash/purchase/mixed
  if ($Scenario -in @('flash','purchase','mixed')) {
    $m = Get-Content $Meta -Raw | ConvertFrom-Json
    $deadline = (Get-Date).AddSeconds(180)
    while ((Get-Date) -lt $deadline) {
      $pending = & docker exec -e "MYSQL_PWD=11111" content-mysql-1 mysql -N -ucontent content `
        -e "SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=$($m.campaignId) AND state='PROCESSING'" 2>$null
      if ("$pending".Trim() -eq '0') { break }
      Start-Sleep -Seconds 1
    }
    $sold = & docker exec -e "MYSQL_PWD=11111" content-mysql-1 mysql -N -ucontent content `
      -e "SELECT sold_count, (SELECT COUNT(*) FROM material_orders WHERE campaign_id=$($m.campaignId) AND state='PAID') FROM material_sale_campaigns WHERE id=$($m.campaignId)" 2>$null
    Write-Host "reconcile campaign=$($m.campaignId) $sold stockHint=$StockHint"
  }
}

$orchestratorLog = Join-Path $EvidenceRoot ("orchestrator-" + (Get-Date -Format 'yyyyMMddHHmmss'))
New-Item -ItemType Directory -Force -Path $orchestratorLog | Out-Null

# Smoke
$smokeMeta = Seed -Buyers ([Math]::Max($SmokeThreads,50)) -Stock 20 -Prefix ("smoke-" + (Get-Date -Format 'HHmmss'))
RunBand 'smoke' 'detail' $SmokeThreads $smokeMeta 20 -Duration 10 -Loops 0 -Ramp 2
RunBand 'smoke' 'flash' $SmokeThreads $smokeMeta 20 -Loops 1 -Ramp 1
RunBand 'smoke' 'mixed' ([Math]::Min(10,$SmokeThreads)) $smokeMeta 20 -Loops 1 -Ramp 1

if ($SkipHeavy) {
  Write-Host "SkipHeavy set — smoke only. Evidence=$EvidenceRoot"
  return
}

foreach ($band in @(@{name='band5k'; n=$Band5k}, @{name='band10k'; n=$Band10k})) {
  $n = $band.n
  $launch = [Math]::Min($n, $MaxLocalThreads)
  # A: per-API QPS (duration window)
  $metaA = Seed -Buyers $launch -Stock ([Math]::Max(100,$launch)) -Prefix ("$($band.name)-A-" + (Get-Date -Format 'HHmmss'))
  foreach ($sc in @('detail','purchase','order','sign')) {
    RunBand $band.name $sc $n $metaA ([Math]::Max(100,$launch)) -Duration 20 -Loops 0 -Ramp 5
  }
  # poll needs reservation ids — seed one purchase then use first reservation via property file note
  # B: flash same material limited stock
  $stockB = [Math]::Max(50, [Math]::Min(500, [int]($launch/10)))
  $metaB = Seed -Buyers $launch -Stock $stockB -Prefix ("$($band.name)-B-" + (Get-Date -Format 'HHmmss'))
  $start = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 5000
  & (Join-Path $here 'run-commerce-jmeter.ps1') -ApiBase $ApiBase -Scenario 'flash' -Threads $launch `
    -RampSec 0 -Loops 1 -StartEpochMs $start -MetaJson $metaB -Band "$($band.name)-flash" -EvidenceDir $EvidenceRoot

  # C: settle TPS with ample stock (purchase only, then drain); refunds separately if orders seeded
  $metaC = Seed -Buyers $launch -Stock $launch -Prefix ("$($band.name)-C-" + (Get-Date -Format 'HHmmss')) -CreatePaidOrders -PaidOrders 0
  RunBand "$($band.name)-tps" 'purchase' $n $metaC $launch -Loops 1 -Ramp 2

  # D: mixed
  $metaD = Seed -Buyers $launch -Stock $launch -Prefix ("$($band.name)-D-" + (Get-Date -Format 'HHmmss'))
  RunBand "$($band.name)-mixed" 'mixed' $n $metaD $launch -Loops 1 -Ramp 5
}

Write-Host "formal bands complete → $EvidenceRoot"
Write-Host "NOTE: payment/refund simulated; FFmpeg container cleanup unverified."
