<#
.SYNOPSIS
  Repeatable V1.1 material-post demo (Batch 1): create → publish → discover → preview comment@8s → new version → accept → unlock/revoke → offline.

.NOTES
  Coverage:
  - MaterialFlowIT: Testcontainers acceptance (primary GREEN evidence).
  - This script: live HTTP against pipeline + content.media.enabled Object store.

  Startup (repo root):
    .\infra\docker\start.ps1
    # configure CONTENT_MYSQL_PASSWORD, CONTENT_CURSOR_KEY, CONTENT_IDENTITY_KEY, CONTENT_REDIS_URI
    # enable media Object store (same as MediaPipelineIT / local S3 mock or MinIO profile)
    mvn -q spring-boot:run "-Dspring-boot.run.profiles=pipeline" ...

  IT evidence (2026-11-12):
    mvn -B -ntp "-Dtest=MaterialFlowIT,MySqlMigrationIT,InteractionsAndTrendingIT" test
    → BUILD SUCCESS; MaterialFlowIT 1/0/0
#>
param(
  [string] $Base = 'http://127.0.0.1:28080',
  [int] $PollSeconds = 90,
  [string] $UserSuffix = (Get-Date -Format 'yyyyMMddHHmmss')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Json {
  param([string]$Method,[string]$Url,[hashtable]$Headers=@{},[object]$Body=$null,[int[]]$ExpectStatus=@(200))
  $p=@{ Method=$Method; Uri=$Url; Headers=$Headers; TimeoutSec=60; UseBasicParsing=$true }
  if ($null -ne $Body) { $p.ContentType='application/json; charset=utf-8'; $p.Body=($Body|ConvertTo-Json -Compress -Depth 8) }
  try { $r=Invoke-WebRequest @p; $code=[int]$r.StatusCode; $c=$r.Content }
  catch {
    $resp=$_.Exception.Response; if ($null -eq $resp) { throw }
    $code=[int]$resp.StatusCode; $c=$null
    try { $s=$resp.GetResponseStream(); if ($s) { $c=[IO.StreamReader]::new($s).ReadToEnd() } } catch {}
  }
  if ($ExpectStatus -notcontains $code) { throw "HTTP $code $Method $Url body=$c" }
  [pscustomobject]@{ StatusCode=$code; Data= $(if ($c) { $c|ConvertFrom-Json } else { $null }) }
}

function Wait-Until([string]$Name,[scriptblock]$Probe) {
  $deadline=(Get-Date).AddSeconds($PollSeconds)
  while ((Get-Date) -lt $deadline) { $x=& $Probe; if ($x) { return $x }; Start-Sleep -Milliseconds 700 }
  throw "Timed out waiting for $Name"
}

Write-Host '== readiness =='
Wait-Until 'readiness' { try { $h=Invoke-RestMethod "$Base/actuator/health/readiness" -TimeoutSec 3; if ($h.status -eq 'UP') { $h } } catch { $null } } | Out-Null

$alice="malice_$UserSuffix"; $bob="mbob_$UserSuffix"
$H=@{ 'Content-Type'='application/json' }
$A=(Invoke-Json Post "$Base/api/auth/register" $H @{ username=$alice; password='password-a1' }).Data
$B=(Invoke-Json Post "$Base/api/auth/register" $H @{ username=$bob; password='password-b1' }).Data
$HA=@{ Authorization="Bearer $($A.token)" }
$HB=@{ Authorization="Bearer $($B.token)" }
Invoke-Json Put "$Base/api/follows/$($A.userId)" $HB | Out-Null

$tmp=Join-Path $env:TEMP "mat-$UserSuffix.bin"
[IO.File]::WriteAllBytes($tmp,[Text.Encoding]::UTF8.GetBytes("demo-video-$UserSuffix"))
$meta=@{
  title="Sunset drone $UserSuffix"
  description='coastal wind'
  tags=@('drone','sunset')
  shareMode='PASSWORD_ORIGINAL'
  password='share-secret'
  licenseDownload=$true
  licenseCommercial=$false
} | ConvertTo-Json -Compress

Write-Host '== create material draft (multipart) =='
$create=Invoke-WebRequest -Method Post -Uri "$Base/api/materials" -Headers $HA -Form @{
  meta = $meta
  file = Get-Item -LiteralPath $tmp
} -TimeoutSec 120
$created=$create.Content | ConvertFrom-Json
$postId=[long]$created.postId
$v1=[long]$created.versionId
Write-Host "postId=$postId v1=$v1"

Write-Host '== poll version READY =='
Wait-Until "version $v1 READY" {
  $d=(Invoke-Json Get "$Base/api/materials/$postId" $HA).Data
  $hit=@($d.versions)|Where-Object { [long]$_.versionId -eq $v1 -and $_.state -eq 'READY' }|Select-Object -First 1
  if ($hit) { $d } else { $null }
} | Out-Null

$pubHdr=$HA.Clone(); $pubHdr['Idempotency-Key']=[guid]::NewGuid().ToString(); $pubHdr['Content-Type']='application/json'
Invoke-Json Post "$Base/api/materials/$postId/publish" $pubHdr @{ expectedVersion = 0 } | Out-Null

Write-Host '== discover =='
$needle="Sunset drone $UserSuffix"
$disc=Wait-Until 'discover hit' {
  $page=(Invoke-Json Get "$Base/api/materials/discover?q=Sunset&tag=drone&limit=20" $HB).Data
  $hit=@($page.items)|Where-Object { $_.title -eq $needle }|Select-Object -First 1
  if ($hit) { $hit } else { $null }
}
Write-Host "discovered postId=$($disc.postId)"

$preview=(Invoke-Json Post "$Base/api/materials/$postId/preview/sign" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ versionId=$v1; ttlMillis=60000 }).Data
Write-Host "preview=$($preview.purpose)"

$cHdr=$HB.Clone(); $cHdr['Idempotency-Key']="mat-c-$UserSuffix"; $cHdr['Content-Type']='application/json'
$comment=(Invoke-Json Post "$Base/api/posts/$postId/comments" $cHdr @{ body='cut wind'; versionId=$v1; offsetMs=8000 }).Data
Write-Host "commentId=$($comment.commentId) offset=$($comment.offsetMs)"

$tmp2=Join-Path $env:TEMP "mat2-$UserSuffix.bin"
[IO.File]::WriteAllBytes($tmp2,[Text.Encoding]::UTF8.GetBytes("demo-video-v2-$UserSuffix"))
$v2resp=Invoke-WebRequest -Method Post -Uri "$Base/api/materials/$postId/versions" -Headers $HA -Form @{ file = Get-Item -LiteralPath $tmp2 } -TimeoutSec 120
$v2=($v2resp.Content | ConvertFrom-Json)
$v2id=[long]$v2.versionId
Wait-Until "version $v2id default" {
  $d=(Invoke-Json Get "$Base/api/materials/$postId" $HA).Data
  if ([long]$d.defaultVersionId -eq $v2id) { $d } else { $null }
} | Out-Null

Invoke-Json Post "$Base/api/posts/$postId/comments/$($comment.commentId)/accept" $HA | Out-Null
# old comment still points at v1@8s (server-side; verified in MaterialFlowIT)
$still=(Invoke-Json Post "$Base/api/materials/$postId/preview/sign" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ versionId=$v1; ttlMillis=60000 }).Data
if ($still.purpose -ne 'PREVIEW') { throw 'old version preview failed' }

Invoke-Json Post "$Base/api/materials/$postId/unlock" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ password='wrong' } -ExpectStatus @(403) | Out-Null
$unlock=(Invoke-Json Post "$Base/api/materials/$postId/unlock" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ password='share-secret' }).Data
Invoke-Json Post "$Base/api/materials/$postId/original/sign" (@{ Authorization=$HB.Authorization; 'Content-Type'='application/json' }) @{ grant=$unlock.grant; versionId=$v1; ttlMillis=60000 } | Out-Null

$detail=(Invoke-Json Get "$Base/api/posts/$postId" $HA).Data
Invoke-Json Post "$Base/api/posts/$postId/offline" (@{ Authorization=$HA.Authorization; 'Content-Type'='application/json' }) @{ expectedVersion=[long]$detail.version } | Out-Null
Invoke-Json Get "$Base/api/materials/$postId" $HB -ExpectStatus @(404) | Out-Null

Write-Host "OK material demo UserSuffix=$UserSuffix postId=$postId v1=$v1 v2=$v2id"
exit 0
