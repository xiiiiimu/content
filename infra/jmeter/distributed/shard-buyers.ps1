#Requires -Version 7
param(
  [Parameter(Mandatory)][string]$SourceCsv,
  [int]$Shards = 5,
  [string]$OutDir = 'target/jmeter-data/shards'
)
$ErrorActionPreference = 'Stop'
if ($Shards -lt 1) { throw 'Shards >= 1' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$header = Get-Content $SourceCsv -TotalCount 1
$rows = @(Get-Content $SourceCsv | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' })
if ($rows.Count -lt $Shards) { throw "rows=$($rows.Count) < shards=$Shards" }
for ($s=0; $s -lt $Shards; $s++) {
  $path = Join-Path $OutDir ("buyers-shard-{0:d2}.csv" -f $s)
  Set-Content $path -Value $header -Encoding utf8
}
for ($i=0; $i -lt $rows.Count; $i++) {
  $s = $i % $Shards
  $path = Join-Path $OutDir ("buyers-shard-{0:d2}.csv" -f $s)
  Add-Content $path -Value $rows[$i]
}
$counts = 0..($Shards-1) | ForEach-Object {
  $p = Join-Path $OutDir ("buyers-shard-{0:d2}.csv" -f $_)
  $n = @(Get-Content $p | Select-Object -Skip 1).Count
  [pscustomobject]@{ shard=$_; path=(Resolve-Path $p).Path; buyers=$n }
}
$counts | ConvertTo-Json
Write-Host "sharded $($rows.Count) buyers into $Shards files under $OutDir"
