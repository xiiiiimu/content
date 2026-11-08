param([Parameter(Mandatory=$true)][ValidateRange(1,[long]::MaxValue)][long]$UserId,[ValidateRange(1,3600)][int]$TtlSeconds=900)
$ErrorActionPreference='Stop'
$identityKeyBytes=[Text.Encoding]::UTF8.GetBytes($env:CONTENT_IDENTITY_KEY)
if($identityKeyBytes.Length -lt 32){throw 'CONTENT_IDENTITY_KEY must contain at least 32 bytes'}
function To-Base64Url([byte[]]$value){[Convert]::ToBase64String($value).TrimEnd('=').Replace('+','-').Replace('/','_')}
$issuedMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$claims=@{version=1;reader=$UserId;issuedAt=$issuedMs;expiresAt=($issuedMs+1000*$TtlSeconds)} | ConvertTo-Json -Compress
$body=To-Base64Url ([Text.Encoding]::UTF8.GetBytes($claims))
$signer=[Security.Cryptography.HMACSHA256]::new($identityKeyBytes)
try{$signature=To-Base64Url ($signer.ComputeHash([Text.Encoding]::ASCII.GetBytes("IDENTITY`n$body"))); "$body.$signature"}finally{$signer.Dispose()}
