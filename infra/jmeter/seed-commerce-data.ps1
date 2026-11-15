<#
.SYNOPSIS
  Pre-generate buyers CSV (userId,token,clientKey), author token, campaign + optional order rows.
  Writes under target/jmeter-data/ (not committed). No real credentials in repo.
#>
#Requires -Version 7
param(
  [string]$ApiBase = 'http://127.0.0.1:28080',
  [int]$Buyers = 100,
  [int]$Stock = 50,
  [string]$OutDir = 'target/jmeter-data',
  [string]$FfmpegImage = 'linuxserver/ffmpeg:version-7.1-cli',
  [string]$Prefix = '',
  [switch]$CreatePaidOrders,
  [int]$PaidOrders = 0
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Auth usernames: [a-zA-Z0-9_]{3,32} — strip hyphens, keep short, add entropy to avoid collisions.
$base = if ($Prefix) { ($Prefix -replace '[^a-zA-Z0-9_]', '') } else { Get-Date -Format 'yyyyMMddHHmmss' }
$entropy = -join ((48..57 + 97..122) | Get-Random -Count 4 | ForEach-Object { [char]$_ })
$runId = ($base + $entropy)
if ($runId.Length -gt 14) { $runId = $runId.Substring(0,14) }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null)
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=90 }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  $r = Invoke-WebRequest @p
  return ($r.Content | ConvertFrom-Json)
}

$swReg = [Diagnostics.Stopwatch]::StartNew()
$author = Invoke-Json POST "$ApiBase/api/auth/register" @{} @{ username="jma$runId"; password='password-a1' }
$HA = @{ Authorization = "Bearer $($author.token)" }
$swReg.Stop()

$work = Join-Path $env:TEMP "jmeter-seed-$runId"; New-Item -ItemType Directory -Force -Path $work | Out-Null
$mp4 = Join-Path $work 'v1.mp4'
$docker = if (Test-Path 'C:\Program Files\Docker\Docker\resources\bin\docker.exe') { 'C:\Program Files\Docker\Docker\resources\bin\docker.exe' } else { 'docker' }
$swMedia = [Diagnostics.Stopwatch]::StartNew()
& $docker run --rm -v "${work}:/work" -w /work --entrypoint ffmpeg $FfmpegImage -y -f lavfi -i testsrc=size=64x64:rate=5 -t 2 -c:v libx264 -pix_fmt yuv420p v1.mp4 | Out-Null
$meta = @{ title="jm $runId"; description='jmeter'; tags=@('jm'); shareMode='PUBLIC_PREVIEW'; licenseDownload=$true; licenseCommercial=$true } | ConvertTo-Json -Compress
$mat = (Invoke-WebRequest -Method Post -Uri "$ApiBase/api/materials" -Headers $HA -Form @{ meta=$meta; file=Get-Item $mp4 } -TimeoutSec 180).Content | ConvertFrom-Json
$postId = [long]$mat.postId
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline) {
  $d = Invoke-Json GET "$ApiBase/api/materials/$postId" $HA
  if (@($d.versions) | Where-Object { $_.state -eq 'READY' }) { break }
  Start-Sleep -Milliseconds 400
}
$pub = $HA.Clone(); $pub['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-Json POST "$ApiBase/api/materials/$postId/publish" $pub @{ expectedVersion = 0 } | Out-Null
$swMedia.Stop()

$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$campaign = Invoke-Json POST "$ApiBase/api/materials/$postId/sale-campaigns" $HA @{
  priceCents=100; stockCap=$Stock; startMs=($now-1000); endMs=($now+7200000)
}

$swBuyers = [Diagnostics.Stopwatch]::StartNew()
$buyersPath = Join-Path $OutDir "buyers-$runId.csv"
"userId,token,clientKey" | Set-Content $buyersPath -Encoding utf8
$authorUserId = $author.userId
$bag = [System.Collections.Concurrent.ConcurrentBag[object]]::new()
0..([Math]::Max(0,$Buyers-1)) | ForEach-Object -Parallel {
  $ApiBase = $using:ApiBase
  $runId = $using:runId
  $authorUserId = $using:authorUserId
  $i = $_
  # jmb + runId(<=12) + i keeps under 32 chars for i < 1e6
  $uname = "jmb${runId}$i"
  if ($uname.Length -gt 32) { $uname = $uname.Substring(0,32) }
  $body = @{ username=$uname; password='password-b1' } | ConvertTo-Json -Compress
  $r = Invoke-WebRequest -Method Post -Uri "$ApiBase/api/auth/register" -ContentType 'application/json' -Body $body -TimeoutSec 90
  $s = $r.Content | ConvertFrom-Json
  $ok = $false
  for ($t=0; $t -lt 5 -and -not $ok; $t++) {
    try {
      Invoke-WebRequest -Method Put -Uri "$ApiBase/api/follows/$authorUserId" -Headers @{ Authorization = "Bearer $($s.token)" } -TimeoutSec 60 | Out-Null
      $ok = $true
    } catch { Start-Sleep -Milliseconds (200 * ($t+1)) }
  }
  if (-not $ok) { throw "follow failed for $($s.userId)" }
  ($using:bag).Add([pscustomobject]@{ userId=$s.userId; token=$s.token; clientKey="jm-$($s.userId)" }) | Out-Null
} -ThrottleLimit 4
$buyerObjs = @($bag)
if ($buyerObjs.Count -lt $Buyers) { throw "seed buyers incomplete got=$($buyerObjs.Count) want=$Buyers" }
foreach ($b in ($buyerObjs | Sort-Object userId)) {
  Add-Content $buyersPath -Value "$($b.userId),$($b.token),$($b.clientKey)"
}
$swBuyers.Stop()

$ordersPath = Join-Path $OutDir "orders-$runId.csv"
$reservationsPath = Join-Path $OutDir "reservations-$runId.csv"
"orderId,userId,token,postId,reservationId" | Set-Content $ordersPath -Encoding utf8
"reservationId,userId,token,postId,state" | Set-Content $reservationsPath -Encoding utf8
$swOrders = [Diagnostics.Stopwatch]::StartNew()
# Paid/reservation seed is OUTSIDE the formal JMeter window (caller must not include this in load timing).
$nPaid = 0
if ($CreatePaidOrders -or $PaidOrders -gt 0) {
  $nPaid = if ($PaidOrders -gt 0) { $PaidOrders } else { [Math]::Min(10, $Buyers, $Stock) }
}
for ($i=0; $i -lt $nPaid; $i++) {
  $b = $buyerObjs[$i]
  $H = @{ Authorization = "Bearer $($b.token)" }
  $p = Invoke-Json POST "$ApiBase/api/sale-campaigns/$($campaign.id)/purchase" $H @{ clientKey = $b.clientKey }
  $deadline = (Get-Date).AddSeconds(90)
  while ($p.state -eq 'PROCESSING' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 200
    $p = Invoke-Json GET "$ApiBase/api/purchase-requests/$($p.reservationId)" $H
  }
  Add-Content $reservationsPath -Value "$($p.reservationId),$($b.userId),$($b.token),$postId,$($p.state)"
  if ($p.state -eq 'SUCCEEDED' -and $p.orderId) {
    Add-Content $ordersPath -Value "$($p.orderId),$($b.userId),$($b.token),$postId,$($p.reservationId)"
  }
}
$swOrders.Stop()

$metaOut = [ordered]@{
  runId = $runId
  apiBase = $ApiBase
  authorUserId = $author.userId
  authorToken = $author.token
  postId = $postId
  campaignId = $campaign.id
  stock = $Stock
  buyers = $Buyers
  paidSeeded = $nPaid
  buyersCsv = (Resolve-Path $buyersPath).Path
  ordersCsv = (Resolve-Path $ordersPath).Path
  reservationsCsv = (Resolve-Path $reservationsPath).Path
  timingsSec = @{
    registerAuthor = [Math]::Round($swReg.Elapsed.TotalSeconds,3)
    mediaPublish = [Math]::Round($swMedia.Elapsed.TotalSeconds,3)
    registerBuyers = [Math]::Round($swBuyers.Elapsed.TotalSeconds,3)
    seedOrders = [Math]::Round($swOrders.Elapsed.TotalSeconds,3)
    note = 'seed timings excluded from formal JMeter window'
  }
}
$metaPath = Join-Path $OutDir "meta-$runId.json"
$metaOut | ConvertTo-Json -Depth 5 | Set-Content $metaPath -Encoding utf8
# sanitize copy without tokens for archival
$safe = [ordered]@{}
foreach ($k in $metaOut.Keys) { $safe[$k] = $metaOut[$k] }
$safe.authorToken = '<redacted>'
$safe | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutDir "meta-$runId.public.json") -Encoding utf8
Write-Host ($metaOut | ConvertTo-Json -Depth 5)
Write-Host "seeded buyers=$buyersPath meta=$metaPath"
