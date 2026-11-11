<#
.SYNOPSIS
  Repeatable V1.1 community API demo: register → follow → draft/edit/publish → feed → comment → reply → notify → offline.

.NOTES
  Coverage vs CommunityFlowIT:
  - CommunityFlowIT exercises real HTTP publish/comment/reply and offline→404, but materializes
    CommentCreated by calling DistributionInbox.accept directly (skips Relay → RocketMQ → consumer).
  - This script requires pipeline profile (Relay + MQ consumer). Bounded Wait-Until polls assert
    following-feed visibility and COMMENT_ON_POST / REPLY_TO_COMMENT after real MQ delivery.
  - Failures: unexpected HTTP status, poll timeout, missing readAtMs/parentId/target, or missing
    cross-recipient / post-offline 404 → non-zero exit ($ErrorActionPreference=Stop + throw).

  Startup (once per machine session), from repo root in PowerShell 7:

    # 1) Compose deps (MySQL/Redis/RocketMQ)
    .\infra\docker\start.ps1
    .\infra\docker\create-relay-topic.ps1

    # 2) App env — passwords must match infra/docker/.env; keys >=32 UTF-8 bytes and distinct
    $env:CONTENT_MYSQL_PASSWORD = (Get-Content infra\docker\.env | ? { $_ -match '^CONTENT_MYSQL_PASSWORD=' } | % { ($_ -split '=',2)[1] })
    $env:CONTENT_CURSOR_KEY   = 'cursor-local-key-32-bytes-minimum-0001'
    $env:CONTENT_IDENTITY_KEY = 'identity-local-key-32-bytes-minimum-001'
    $env:CONTENT_REDIS_URI    = 'redis://127.0.0.1:26379'

    # 3) Optional: rebuild Redis candidates if index was never READY
    #    mvn -q -DskipTests package
    #    .\infra\docker\feed-ops.ps1 -Action rebuild -Reason 'local community demo'

    # 4) API + Relay + Pipeline consumer (notifications need CommentCreated → MQ → Inbox)
    mvn -q spring-boot:run "-Dspring-boot.run.profiles=pipeline"

    # 5) This script (second terminal; wait until readiness is UP)
    powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\demo-community-v11.ps1 -PollSeconds 60

  Proven local run (2026-11-12, exit 0, ~35s) against pipeline on :28080:
    command: powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\docker\demo-community-v11.ps1 -PollSeconds 60
    result:  readiness=UP; alice.userId=3 bob.userId=4; postId=1 version=2; feed.items=1;
             commentId=1 → notifA COMMENT_ON_POST id=1; reply → notifB REPLY_TO_COMMENT id=2;
             cross-recipient target=404; post_after_offline=404 target_after_offline=404;
             OK community demo UserSuffix=20261112005021 postId=1
#>
param(
  [string] $Base = 'http://127.0.0.1:28080',
  [int] $PollSeconds = 45,
  [int] $PollIntervalMs = 500,
  [string] $UserSuffix = (Get-Date -Format 'yyyyMMddHHmmss')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Json {
  param(
    [Parameter(Mandatory)][ValidateSet('Get','Post','Put','Patch','Delete')][string] $Method,
    [Parameter(Mandatory)][string] $Url,
    [hashtable] $Headers = @{},
    [object] $Body = $null,
    [int[]] $ExpectStatus = @(200)
  )
  $params = @{
    Method          = $Method
    Uri             = $Url
    Headers         = $Headers
    TimeoutSec      = 30
    UseBasicParsing = $true
  }
  if ($null -ne $Body) {
    $params.ContentType = 'application/json; charset=utf-8'
    $params.Body = if ($Body -is [string]) { $Body } else { ($Body | ConvertTo-Json -Compress -Depth 6) }
  }
  $code = 0
  $content = $null
  try {
    $resp = Invoke-WebRequest @params
    $code = [int]$resp.StatusCode
    $content = $resp.Content
  } catch {
    $ex = $_.Exception
    $r = $null
    if ($ex.PSObject.Properties.Name -contains 'Response') { $r = $ex.Response }
    if ($null -eq $r -and $ex.InnerException -and ($ex.InnerException.PSObject.Properties.Name -contains 'Response')) {
      $r = $ex.InnerException.Response
    }
    if ($null -eq $r) { throw }
    $code = [int]$r.StatusCode
    try {
      $stream = $r.GetResponseStream()
      if ($stream) {
        $reader = [System.IO.StreamReader]::new($stream)
        $content = $reader.ReadToEnd()
        $reader.Dispose()
      }
    } catch { $content = $null }
  }
  if ($ExpectStatus -notcontains $code) {
    throw "HTTP $code for $Method $Url (expected $($ExpectStatus -join ',')); body=$content"
  }
  $data = $null
  if ($content -and $content.Trim().Length -gt 0) {
    $data = $content | ConvertFrom-Json
  }
  return [pscustomobject]@{ StatusCode = $code; Content = $content; Data = $data }
}

function Wait-Until {
  param(
    [Parameter(Mandatory)][string] $Name,
    [Parameter(Mandatory)][scriptblock] $Probe,
    [int] $TimeoutSec = $PollSeconds,
    [int] $IntervalMs = $PollIntervalMs
  )
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $last = & $Probe
    if ($last) { return $last }
    Start-Sleep -Milliseconds $IntervalMs
  }
  throw "Timed out waiting for $Name after ${TimeoutSec}s. Last=$last"
}

Write-Host "== readiness =="
$ready = Wait-Until -Name 'actuator readiness' -Probe {
  try {
    $h = Invoke-RestMethod -Uri "$Base/actuator/health/readiness" -TimeoutSec 3
    if ($h.status -eq 'UP') { $h } else { $null }
  } catch { $null }
}
Write-Host "readiness=$($ready.status)"

$aliceUser = "alice_$UserSuffix"
$bobUser = "bob_$UserSuffix"
$H = @{ 'Content-Type' = 'application/json' }

Write-Host "== register $aliceUser / $bobUser =="
$A = (Invoke-Json -Method Post -Url "$Base/api/auth/register" -Headers $H -Body @{ username = $aliceUser; password = 'password-a1' }).Data
$B = (Invoke-Json -Method Post -Url "$Base/api/auth/register" -Headers $H -Body @{ username = $bobUser; password = 'password-b1' }).Data
if (-not $A.token -or -not $B.token) { throw 'register did not return tokens' }
$HA = @{ Authorization = "Bearer $($A.token)"; 'Content-Type' = 'application/json' }
$HB = @{ Authorization = "Bearer $($B.token)"; 'Content-Type' = 'application/json' }
Write-Host "alice.userId=$($A.userId) bob.userId=$($B.userId)"

Write-Host "== B follows A =="
$follow = (Invoke-Json -Method Put -Url "$Base/api/follows/$($A.userId)" -Headers $HB).Data
if (-not $follow.following) { throw 'follow failed' }

Write-Host "== draft → edit → publish =="
$draft = (Invoke-Json -Method Post -Url "$Base/api/posts" -Headers $HA -Body @{ body = 'hello draft' }).Data
$postId = [long]$draft.postId
$ver = [long]$draft.version
$edit = (Invoke-Json -Method Patch -Url "$Base/api/posts/$postId" -Headers $HA -Body @{
  expectedVersion = $ver
  body = "hello published body $UserSuffix"
}).Data
$ver = [long]$edit.version
$pubHdr = @{ Authorization = $HA.Authorization; 'Content-Type' = 'application/json'; 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$pub = (Invoke-Json -Method Post -Url "$Base/api/posts/$postId/publish" -Headers $pubHdr -Body @{ expectedVersion = $ver }).Data
# Publish bumps version by 1; keep authoritative version for later offline.
$ver = if ($null -ne $pub.postVersion) { [long]$pub.postVersion } else { $ver + 1 }
Write-Host "postId=$postId published=$($pub.postId) version=$ver"

$needle = "hello published body $UserSuffix"
Write-Host "== poll following feed for post body (bounded ${PollSeconds}s) =="
$feedHit = Wait-Until -Name "feed contains post $postId" -Probe {
  $feed = (Invoke-Json -Method Get -Url "$Base/api/feed/following?limit=20" -Headers $HB).Data
  $items = @($feed.items)
  $hit = $items | Where-Object {
    ([long]$_.postId -eq $postId) -or ("$($_.body)$($_)" -like "*$needle*")
  } | Select-Object -First 1
  if ($hit) { $feed } else { $null }
}
Write-Host "feed.items=$($feedHit.items.Count)"

Write-Host "== B comments on post =="
$cHdr = @{ Authorization = $HB.Authorization; 'Content-Type' = 'application/json'; 'Idempotency-Key' = "c-b-$UserSuffix" }
$comment = (Invoke-Json -Method Post -Url "$Base/api/posts/$postId/comments" -Headers $cHdr -Body @{ body = 'nice post' }).Data
$commentId = [long]$comment.commentId
Write-Host "commentId=$commentId"

Write-Host "== poll A notifications for COMMENT_ON_POST (bounded ${PollSeconds}s) =="
$notifA = Wait-Until -Name 'COMMENT_ON_POST for alice' -Probe {
  $list = @((Invoke-Json -Method Get -Url "$Base/api/notifications?limit=50" -Headers $HA).Data)
  $list | Where-Object { $_.kind -eq 'COMMENT_ON_POST' -and [long]$_.postId -eq $postId -and [long]$_.commentId -eq $commentId } | Select-Object -First 1
}
Write-Host "notifA.id=$($notifA.id) kind=$($notifA.kind)"

Write-Host "== A replies =="
$rHdr = @{ Authorization = $HA.Authorization; 'Content-Type' = 'application/json'; 'Idempotency-Key' = "c-a-reply-$UserSuffix" }
$reply = (Invoke-Json -Method Post -Url "$Base/api/posts/$postId/comments" -Headers $rHdr -Body @{
  body = 'thanks'
  parentId = $commentId
}).Data
if ([long]$reply.parentId -ne $commentId) { throw "reply parentId mismatch: $($reply.parentId)" }
$replyId = [long]$reply.commentId

Write-Host "== poll B notifications for REPLY_TO_COMMENT (bounded ${PollSeconds}s) =="
$notifB = Wait-Until -Name 'REPLY_TO_COMMENT for bob' -Probe {
  $list = @((Invoke-Json -Method Get -Url "$Base/api/notifications?limit=50" -Headers $HB).Data)
  $list | Where-Object { $_.kind -eq 'REPLY_TO_COMMENT' -and [long]$_.commentId -eq $replyId } | Select-Object -First 1
}
Write-Host "notifB.id=$($notifB.id) kind=$($notifB.kind)"

Write-Host "== mark read + target (recipient-scoped) =="
$read = (Invoke-Json -Method Post -Url "$Base/api/notifications/$($notifB.id)/read" -Headers $HB).Data
if (-not $read.readAtMs) { throw 'readAtMs missing after mark-read' }
$target = (Invoke-Json -Method Get -Url "$Base/api/notifications/$($notifA.id)/target" -Headers $HA).Data
if ([long]$target.postId -ne $postId) { throw 'target postId mismatch' }

# Wrong recipient must not see the other user's notification
$deny = Invoke-Json -Method Get -Url "$Base/api/notifications/$($notifB.id)/target" -Headers $HA -ExpectStatus @(404)
Write-Host "cross-recipient target status=$($deny.StatusCode) (expect 404)"

Write-Host "== offline → post/target 404 =="
$authorView = (Invoke-Json -Method Get -Url "$Base/api/posts/$postId" -Headers $HA).Data
$offlineVer = if ($null -ne $authorView.version) { [long]$authorView.version } else { $ver }
Invoke-Json -Method Post -Url "$Base/api/posts/$postId/offline" -Headers $HA -Body @{ expectedVersion = $offlineVer } | Out-Null

$gonePost = Invoke-Json -Method Get -Url "$Base/api/posts/$postId" -Headers $HB -ExpectStatus @(404)
$goneTarget = Invoke-Json -Method Get -Url "$Base/api/notifications/$($notifB.id)/target" -Headers $HB -ExpectStatus @(404)
Write-Host "post_after_offline=$($gonePost.StatusCode) target_after_offline=$($goneTarget.StatusCode)"

Write-Host "OK community demo UserSuffix=$UserSuffix postId=$postId"
exit 0
