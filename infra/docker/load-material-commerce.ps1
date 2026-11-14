<#
.SYNOPSIS
  Same-campaign concurrent purchase load; records QPS / settle TPS / e2e P95/P99 / rejects / reconcile.

.NOTES
  Requires running API with content.sale.sync-settle=true (or MQ settle). Creates N buyers + 1 campaign.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$Stock = 20,
  [int]$Buyers = 40,
  [string]$EvidenceDir = 'docs/perf-evidence/commerce',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $EvidenceDir "sale-load-$runId"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$raw = Join-Path $outDir 'raw.ndjson'
$summary = Join-Path $outDir 'summary.json'

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null)
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=60 }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  $sw = [Diagnostics.Stopwatch]::StartNew()
  try {
    $r=Invoke-WebRequest @p
    $sw.Stop()
    return [pscustomobject]@{ Ok=$true; Code=[int]$r.StatusCode; Ms=$sw.ElapsedMilliseconds; Data=($r.Content|ConvertFrom-Json); Err=$null }
  } catch {
    $sw.Stop()
    $code = 0; $body=$null
    try { $code=[int]$_.Exception.Response.StatusCode; $body=$_.ErrorDetails.Message } catch {}
    return [pscustomobject]@{ Ok=$false; Code=$code; Ms=$sw.ElapsedMilliseconds; Data=$null; Err="$($_.Exception.Message) $body" }
  }
}

$author = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="load_a_$runId"; password='password-a1' }).Data
$HA = @{ Authorization = "Bearer $($author.token)" }
$work = Join-Path $env:TEMP "com-load-$runId"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') { 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' } else { 'docker' }
& $docker run --rm -v "${work}:/work" -w /work --entrypoint ffmpeg $FfmpegImage -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p v1.mp4 | Out-Null
$meta = @{ title="load $runId"; description='load'; tags=@('load'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$mat = (Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item $mp4 } -TimeoutSec 180).Content | ConvertFrom-Json
$postId = [long]$mat.postId
Start-Sleep -Seconds 8
$pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion = 0 } | Out-Null
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campaign = (Invoke-Json POST "$ApiBase/api/materials/$postId/sale-campaigns" $HA @{
  priceCents=100; stockCap=$Stock; startMs=($now-1000); endMs=($now+3600000)
}).Data

$buyers = @()
for ($i=0; $i -lt $Buyers; $i++) {
  $s = (Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="load_b_${runId}_$i"; password='password-b1' }).Data
  Invoke-Json PUT "$ApiBase/api/follows/$($author.userId)" @{ Authorization = "Bearer $($s.token)" } | Out-Null
  $buyers += $s
}

$latencies = [System.Collections.Concurrent.ConcurrentBag]::new()
$bizReject = [System.Collections.Concurrent.ConcurrentBag]::new()
$sysErr = [System.Collections.Concurrent.ConcurrentBag]::new()
$success = [System.Collections.Concurrent.ConcurrentBag]::new()
$swAll = [Diagnostics.Stopwatch]::StartNew()
$buyers | ForEach-Object -Parallel {
  $ApiBase = $using:ApiBase
  $campaignId = $using:campaign.id
  $raw = $using:raw
  $session = $_
  $H = @{ Authorization = "Bearer $($session.token)" }
  $t0 = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  try {
    $body = @{ clientKey = "load-$($session.userId)" } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/sale-campaigns/$campaignId/purchase" -Headers $H -ContentType 'application/json' -Body $body -TimeoutSec 60
    $p = $r.Content | ConvertFrom-Json
    $deadline = (Get-Date).AddSeconds(60)
    while ($p.state -eq 'PROCESSING' -and (Get-Date) -lt $deadline) {
      Start-Sleep -Milliseconds 200
      $q = Invoke-WebRequest -Method Get -Uri "$ApiBase/api/purchase-requests/$($p.reservationId)" -Headers $H -TimeoutSec 30
      $p = $q.Content | ConvertFrom-Json
    }
    $ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $t0
    $line = (@{ userId=$session.userId; state=$p.state; reject=$p.rejectReason; ms=$ms; t0=$t0 } | ConvertTo-Json -Compress)
    Add-Content -Path $raw -Value $line
    if ($p.state -eq 'SUCCEEDED') { ($using:success).Add($ms) | Out-Null }
    elseif ($p.state -eq 'REJECTED') { ($using:bizReject).Add($p.rejectReason) | Out-Null; ($using:latencies).Add($ms) | Out-Null }
    else { ($using:sysErr).Add($p.state) | Out-Null }
  } catch {
    ($using:sysErr).Add("$_") | Out-Null
  }
} -ThrottleLimit 20
$swAll.Stop()

function Pct($arr, $p) {
  if ($arr.Count -eq 0) { return $null }
  $s = $arr | Sort-Object
  $idx = [Math]::Min($s.Count-1, [Math]::Floor(($p/100.0)*($s.Count-1)))
  return $s[$idx]
}

$allMs = @($success) + @($latencies)
$elapsedSec = [Math]::Max(0.001, $swAll.Elapsed.TotalSeconds)
$report = [ordered]@{
  runId = $runId
  stock = $Stock
  buyers = $Buyers
  elapsedSec = [Math]::Round($elapsedSec, 3)
  requestQps = [Math]::Round($Buyers / $elapsedSec, 2)
  settleTps = [Math]::Round(($success.Count) / $elapsedSec, 2)
  successOrders = $success.Count
  businessRejects = $bizReject.Count
  systemErrors = $sysErr.Count
  e2eP95Ms = (Pct $allMs 95)
  e2eP99Ms = (Pct $allMs 99)
  rejectReasons = @($bizReject | Group-Object | ForEach-Object { @{ reason=$_.Name; count=$_.Count } })
  note = 'MySQL sold_count authoritative; sync-settle or wait MQ drain before reading final stock'
}
$report | ConvertTo-Json -Depth 6 | Set-Content -Path $summary -Encoding utf8
Write-Host ($report | ConvertTo-Json -Depth 6)
Write-Host "evidence=$outDir"
