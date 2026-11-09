param(
  [string]$Tag = 'RELEASE.2025-04-22T22-12-26Z',
  [string]$Commit = '0d7408fc9969caf07de6a8c3a84f9fbb10a6739e',
  [string]$SourceDir = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
if (-not $SourceDir) {
  $SourceDir = Join-Path $root "target\minio-src-$Tag"
}
if (-not (Test-Path (Join-Path $SourceDir 'main.go'))) {
  throw "MinIO source missing at $SourceDir — clone tag $Tag first"
}
Push-Location $SourceDir
try {
  $actual = (git rev-parse HEAD).Trim()
  if ($actual -ne $Commit) {
    throw "Source commit $actual does not match pinned $Commit"
  }
} finally { Pop-Location }

$image = "content-minio:$Tag"
$dockerfile = Join-Path $PSScriptRoot 'Dockerfile'
$logDir = Join-Path $root 'docs\minio-evidence'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir "build-$Tag.log"

$golangDigest = (docker image inspect golang:1.24-bookworm --format '{{index .RepoDigests 0}}' 2>$null)
$alpineDigest = (docker image inspect alpine:3.20 --format '{{index .RepoDigests 0}}' 2>$null)
@"
tag=$Tag
commit=$Commit
sourceDir=$SourceDir
golangDigest=$golangDigest
alpineDigest=$alpineDigest
started=$(Get-Date -Format o)
"@ | Set-Content -Encoding utf8 (Join-Path $logDir "build-$Tag-meta.txt")

Write-Host "Building $image from $SourceDir"
docker build `
  -f $dockerfile `
  --build-arg "RELEASE=$Tag" `
  --build-arg "GIT_COMMIT=$Commit" `
  -t $image `
  $SourceDir 2>&1 | Tee-Object -FilePath $log

if ($LASTEXITCODE -ne 0) { throw "docker build failed; see $log" }

docker image inspect $image --format 'Id={{.Id}} RepoTags={{json .RepoTags}} Created={{.Created}}' |
  Tee-Object -FilePath (Join-Path $logDir "image-$Tag.txt")
Write-Host "Built $image"
Write-Host "IMAGE_ID=$((docker image inspect $image --format '{{.Id}}'))"
