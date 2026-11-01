$ErrorActionPreference='Stop'
$taskWorkspace=Split-Path -Parent $PSScriptRoot
$taskRoot=Join-Path $taskWorkspace '.research/compatibility'
$taskCatalogPath=Join-Path $taskWorkspace 'docs/evidence/compatibility/catalog.json'
$taskCatalog=@(Get-Content -LiteralPath $taskCatalogPath -Raw | ConvertFrom-Json)
$taskFiles=@(Get-Content (Join-Path $taskRoot 'curseforge-iron-all.json') -Raw | ConvertFrom-Json)
$taskMissing=@($taskFiles | Where-Object { $taskCatalog.filename -notcontains $_.fileName })
$taskExtra=@($taskMissing | ForEach-Object -Parallel {
    $ErrorActionPreference='Stop'
    $taskFile=$_
    if ([IO.Path]::GetFileName($taskFile.fileName) -ne $taskFile.fileName) {throw 'Invalid publisher filename'}
    $taskUrl='https://mediafilez.forgecdn.net/files/'+[int][Math]::Floor($taskFile.id/1000)+'/'+($taskFile.id%1000)+'/'+[Uri]::EscapeDataString($taskFile.fileName)
    $taskTarget=Join-Path (Join-Path $using:taskRoot 'jars') $taskFile.fileName
    if (-not (Test-Path -LiteralPath $taskTarget)) { Invoke-WebRequest -Uri $taskUrl -OutFile $taskTarget }
    if ((Get-Item -LiteralPath $taskTarget).Length -ne $taskFile.fileLength) {throw ('Publisher length mismatch: '+$taskFile.fileName)}
    $taskVersion=$taskFile.fileName -replace '^irons_spellbooks-','' -replace '(?:-all)?\.jar$',''
    [pscustomobject]@{ project='s4OWxYQQ'; version=$taskVersion; id=('cf'+$taskFile.id); filename=$taskFile.fileName;
        url=$taskUrl; published=$taskFile.dateCreated; sha256=(Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash;
        provider='CurseForge'; publisherFileBytes=$taskFile.fileLength }
} -ThrottleLimit 4)
if ($taskExtra.Count -ne $taskMissing.Count) {throw 'Incomplete publisher-only release download'}
@($taskCatalog)+@($taskExtra) | Sort-Object project,published | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $taskCatalogPath -Encoding utf8
Write-Host ('Added {0} publisher-only Iron versions; catalog now contains {1} unique release jars.' -f $taskExtra.Count,($taskCatalog.Count+$taskExtra.Count))
