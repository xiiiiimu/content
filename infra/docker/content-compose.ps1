function Invoke-ContentCompose {
    param(
        [string[]] $CommandArguments,
        [ValidateSet('media', '*')]
        [string[]] $Profile = @()
    )

    $configFile = Join-Path $PSScriptRoot 'compose.yml'
    $envFile = Join-Path $PSScriptRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        throw 'Copy infra/docker/.env.example to infra/docker/.env and fill in the passwords.'
    }
    $composeArguments = @('compose', '--project-name', 'content', '--env-file', $envFile, '--file', $configFile)
    foreach ($profileName in $Profile) {
        $composeArguments += @('--profile', $profileName)
    }
    & docker @composeArguments @CommandArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Content Docker Compose failed (exit $LASTEXITCODE)."
    }
}
