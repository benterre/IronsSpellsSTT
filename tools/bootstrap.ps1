[CmdletBinding()]
param([switch]$IncludeAddons)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content -Raw -LiteralPath (Join-Path $projectRoot 'dependency-manifest.json') | ConvertFrom-Json
foreach ($artifact in $manifest) {
    if ($artifact.directory -eq 'test-mods' -and !$IncludeAddons) { continue }
    if ($artifact.directory -notin @('libs','test-mods') -or [IO.Path]::GetFileName($artifact.filename) -ne $artifact.filename) {
        throw 'Invalid artifact path in dependency manifest'
    }
    $directory = Join-Path $projectRoot $artifact.directory
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $destination = Join-Path $directory $artifact.filename
    if (!(Test-Path -LiteralPath $destination) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash -ne $artifact.sha256) {
        Write-Host "Downloading $($artifact.filename)"
        Invoke-WebRequest -Uri $artifact.url -OutFile "$destination.download"
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath "$destination.download").Hash -ne $artifact.sha256) {
            throw "Downloaded artifact checksum differs: $($artifact.filename)"
        }
        Move-Item -LiteralPath "$destination.download" -Destination $destination -Force
    }
    Write-Host "Verified $($artifact.filename)"
}
