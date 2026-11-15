<#
.SYNOPSIS
  Summarize JMeter CSV JTL: request QPS, accept QPS, biz reject vs system error, P50/P95/P99.
#>
#Requires -Version 7
param(
  [Parameter(Mandatory)][string]$JtlPath,
  [string]$OutJson = '',
  [string]$LabelFilter = '',
  [double]$WindowSec = 0
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $JtlPath) -or (Get-Item $JtlPath).Length -lt 10) { throw "empty or missing JTL: $JtlPath" }
$rows = @(Import-Csv $JtlPath)
if ($LabelFilter) { $rows = @($rows | Where-Object { $_.label -eq $LabelFilter -or $_.Label -eq $LabelFilter }) }
if ($rows.Count -eq 0) { throw "no rows in $JtlPath" }

function Col($r, [string[]]$names) {
  foreach ($n in $names) { if ($r.PSObject.Properties.Name -contains $n -and $null -ne $r.$n -and $r.$n -ne '') { return $r.$n } }
  return $null
}

$parsed = foreach ($r in $rows) {
  $ts = [long](Col $r @('timeStamp','TimeStamp'))
  $elapsed = [long](Col $r @('elapsed','Elapsed'))
  $label = [string](Col $r @('label','Label'))
  $code = [string](Col $r @('responseCode','ResponseCode'))
  $ok = [string](Col $r @('success','Success'))
  $msg = [string](Col $r @('responseMessage','ResponseMessage'))
  $bodyCode = $null
  $data = Col $r @('responseData','ResponseData')
  if ($data -match '"code"\s*:\s*"([^"]+)"') { $bodyCode = $Matches[1] }
  $http = 0; [void][int]::TryParse($code, [ref]$http)
  $cls = 'OK'
  $codeStr = [string]$code
  if ($codeStr -match 'HttpHostConnectException|ConnectException|Connection refused' -or "$msg" -match 'Connection refused') {
    $cls = 'CONNECT_FAIL'
  } elseif ($codeStr -match 'SocketTimeout|Timeout' -or "$msg" -match 'timed out|Timeout') {
    $cls = 'TIMEOUT'
  } elseif ($http -eq 503 -or $bodyCode -eq 'FEED_UNAVAILABLE') {
    $cls = 'HTTP_503'
  } elseif ($ok -eq 'false' -or $ok -eq 'FALSE') {
    if ($http -ge 500 -or $http -eq 0 -or $codeStr -match 'Non HTTP') { $cls = 'SYSTEM' }
    elseif ($http -ge 400) { $cls = "BIZ:$bodyCode" }
    else { $cls = 'SYSTEM' }
  } elseif ($http -ge 400 -and $http -lt 500) {
    $cls = "BIZ:$bodyCode"
  }
  [pscustomobject]@{ ts=$ts; elapsed=$elapsed; label=$label; http=$http; cls=$cls; bodyCode=$bodyCode; msg=$msg }
}

$t0 = ($parsed | Measure-Object ts -Minimum).Minimum
$t1 = ($parsed | Measure-Object ts -Maximum).Maximum
$spanMs = [Math]::Max(1, $t1 - $t0)
if ($WindowSec -gt 0) { $spanMs = [Math]::Max(1, [int]($WindowSec * 1000)) }
$spanSec = $spanMs / 1000.0

function Pct($arr, $p) {
  $s = @($arr | Sort-Object)
  if ($s.Count -eq 0) { return $null }
  $idx = [Math]::Min($s.Count-1, [Math]::Floor(($p/100.0)*($s.Count-1)))
  return $s[$idx]
}

$lat = @($parsed | ForEach-Object { $_.elapsed })
$biz = @($parsed | Where-Object { $_.cls -like 'BIZ*' }).Count
$sys = @($parsed | Where-Object { $_.cls -in @('SYSTEM','CONNECT_FAIL','TIMEOUT','HTTP_503') }).Count
$connectFail = @($parsed | Where-Object { $_.cls -eq 'CONNECT_FAIL' }).Count
$timeouts = @($parsed | Where-Object { $_.cls -eq 'TIMEOUT' }).Count
$http503 = @($parsed | Where-Object { $_.cls -eq 'HTTP_503' }).Count
$okn = @($parsed | Where-Object { $_.cls -eq 'OK' }).Count

$summary = [ordered]@{
  jtl = (Resolve-Path $JtlPath).Path
  samples = $parsed.Count
  spanSec = [Math]::Round($spanSec, 3)
  requestQps = [Math]::Round($parsed.Count / $spanSec, 2)
  acceptQps = [Math]::Round($okn / $spanSec, 2)
  businessRejects = $biz
  systemErrors = $sys
  connectFail = $connectFail
  timeouts = $timeouts
  http503 = $http503
  ok = $okn
  p50Ms = (Pct $lat 50)
  p95Ms = (Pct $lat 95)
  p99Ms = (Pct $lat 99)
  byLabel = @($parsed | Group-Object label | ForEach-Object {
    $g = $_.Group
    $gs = [Math]::Max(0.001, ((($g | Measure-Object ts -Maximum).Maximum) - (($g | Measure-Object ts -Minimum).Minimum)) / 1000.0)
    @{ label=$_.Name; n=$g.Count; qps=[Math]::Round($g.Count/$gs,2); p95=(Pct @($g.elapsed) 95) }
  })
  byClass = @($parsed | Group-Object cls | ForEach-Object { @{ cls=$_.Name; count=$_.Count } })
}
$json = $summary | ConvertTo-Json -Depth 6
if ($OutJson) { $json | Set-Content $OutJson -Encoding utf8 }
Write-Host $json
