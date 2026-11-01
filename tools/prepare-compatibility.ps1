param([ValidateSet('iron','voice','both')][string]$Group='both', [string]$OnlyVersions='')
$ErrorActionPreference='Stop'
$taskWorkspace=Split-Path -Parent $PSScriptRoot
$taskCache=Join-Path $taskWorkspace '.research/compatibility'
$taskEvidence=Join-Path $taskWorkspace 'docs/evidence/compatibility'
$taskInputs=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'compatibility-dependencies.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path $taskCache,$taskEvidence | Out-Null
$taskSelected=@($taskInputs.releases | Where-Object {
    ($Group -eq 'both' -or ($Group -eq 'iron' -and $_.project -eq 's4OWxYQQ') -or ($Group -eq 'voice' -and $_.project -eq '9eGKb6K1')) -and
    (-not $OnlyVersions -or ($OnlyVersions -split ',') -contains $_.version)
})
if ($taskSelected.Count -eq 0) { throw 'No matching pinned release inputs' }
$taskDownloads=@($taskSelected | ForEach-Object { [pscustomobject]@{ filename=$_.filename; url=$_.url; sha256=$_.sha256; folder='jars' } })+@($taskInputs.helpers)
$taskCompleted=0
foreach ($taskFile in $taskDownloads) {
    $taskUri=[Uri]$taskFile.url
    if ([IO.Path]::GetFileName($taskFile.filename) -ne $taskFile.filename -or $taskFile.folder -notin @('jars','dependencies','legacy-curios','legacy-iron-libs') -or
        $taskUri.Scheme -ne 'https' -or $taskUri.Host -notin @('cdn.modrinth.com','mediafilez.forgecdn.net') -or $taskFile.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'Invalid pinned compatibility dependency'
    }
    $taskFolder=Join-Path $taskCache $taskFile.folder
    New-Item -ItemType Directory -Force -Path $taskFolder | Out-Null
    $taskTarget=Join-Path $taskFolder $taskFile.filename
    if (-not (Test-Path -LiteralPath $taskTarget) -or (Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash -ne $taskFile.sha256) {
        Invoke-WebRequest -Uri $taskFile.url -OutFile $taskTarget -Headers @{'User-Agent'='STS-regression-dependencies/0.3'}
    }
    if ((Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash -ne $taskFile.sha256) { throw ('Checksum mismatch: '+$taskFile.filename) }
    $taskCompleted++
    Write-Host ('Verified {0}/{1}: {2}' -f $taskCompleted,$taskDownloads.Count,$taskFile.filename)
}
$taskInputs.releases | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskEvidence 'catalog.json') -Encoding utf8NoBOM
