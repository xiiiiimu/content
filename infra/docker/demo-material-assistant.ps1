#Requires -Version 5.1
param(
  [string]$ApiBase = "http://127.0.0.1:28080",
  [string]$LogFile = "target/demo-material-assistant-run.log"
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
function Log($m){ $line = "[{0}] {1}" -f (Get-Date -Format o), $m; $line | Tee-Object -FilePath $LogFile -Append | Write-Host }
function Json($o){ ($o | ConvertTo-Json -Compress -Depth 8) }
function Req($method,$path,$token=$null,$body=$null,$headers=@{}){
  $h = @{ "Content-Type"="application/json" } + $headers
  if($token){ $h["Authorization"] = "Bearer $token" }
  $params = @{ Method=$method; Uri="$ApiBase$path"; Headers=$h }
  if($null -ne $body){ $params.Body = (Json $body) }
  return Invoke-RestMethod @params
}
$suffix = Get-Date -Format "yyyyMMddHHmmss"
Log "START UserSuffix=$suffix"
$author = Req POST /api/auth/register $null @{ username="asst_a_$suffix"; password="password-a1" }
$viewer = Req POST /api/auth/register $null @{ username="asst_v_$suffix"; password="password-b1" }
Log "registered author=$($author.userId) viewer=$($viewer.userId)"

# Multipart material create is covered by MaterialFlowIT; this demo focuses on assistant comment path after materials exist.
Log "NOTE: create material via existing demo-material-v11.ps1 or IT; then POST comments containing @助手"
Log "Python health (optional): curl http://127.0.0.1:28090/healthz"
Log "DONE"
