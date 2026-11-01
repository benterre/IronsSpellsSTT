[CmdletBinding()]
param([switch]$Refresh,[ValidateSet('all','iron','voice')][string]$Group='all',[int]$ThrottleLimit=4)
$ErrorActionPreference='Stop'
$taskWorkspace=Split-Path -Parent $PSScriptRoot
$taskAudit=Join-Path $taskWorkspace '.research/publication'
$taskJars=Join-Path $taskAudit 'catalog-jars'
$taskHeaders=@{'User-Agent'='benterre/IronsSpellsSTT unpublished compatibility audit'}
New-Item -ItemType Directory -Force -Path $taskAudit,$taskJars | Out-Null
if($Refresh) {
    $games=[uri]::EscapeDataString('["1.18.2","1.19.2","1.20.1","1.21","1.21.1"]')
    $loaders=[uri]::EscapeDataString('["forge","neoforge"]')
    foreach($project in @(@{Id='s4OWxYQQ';File='iron-versions.json'},@{Id='9eGKb6K1';File='voicechat-versions.json'})) {
        Invoke-WebRequest -Uri ('https://api.modrinth.com/v2/project/'+$project.Id+'/version?game_versions='+$games+'&loaders='+$loaders) -OutFile (Join-Path $taskAudit $project.File) -Headers $taskHeaders
    }
    $files=@();$page=0
    do {
        $reply=Invoke-RestMethod -Uri "https://www.curseforge.com/api/v1/mods/855414/files?pageIndex=$page&pageSize=50&sort=dateCreated&sortDescending=true&removeAlphas=false" -Headers $taskHeaders
        $batch=@($reply.data);$files+=$batch;$page++
    } while($batch.Count -eq 50)
    $files | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $taskAudit 'curseforge-iron-all.json') -Encoding utf8
}
$entries=@()
foreach($project in @(@{Name='iron';File='iron-versions.json'},@{Name='voice';File='voicechat-versions.json'})) {
    if($Group -ne 'all' -and $Group -ne $project.Name) { continue }
    $releases=Get-Content (Join-Path $taskAudit $project.File) -Raw | ConvertFrom-Json
    foreach($release in $releases) {
        $file=@($release.files | Where-Object primary)[0];if(-not $file) {$file=$release.files[0]}
        $entries+=[pscustomobject]@{project=$project.Name;provider='Modrinth';id=$release.id;version=$release.version_number;filename=$file.filename;url=$file.url;sha512=$file.hashes.sha512;bytes=$file.size;games=@($release.game_versions);loaders=@($release.loaders)}
    }
}
if($Group -in @('all','iron')) {
    $files=Get-Content (Join-Path $taskAudit 'curseforge-iron-all.json') -Raw | ConvertFrom-Json
    foreach($file in $files) {
        if($entries.filename -contains $file.fileName -or -not ($file.gameVersions | Where-Object {$_ -in @('1.18.2','1.19.2','1.20.1','1.21','1.21.1')})) { continue }
        $entries+=[pscustomobject]@{project='iron';provider='CurseForge';id=('cf'+$file.id);version=$file.displayName;filename=$file.fileName;url=('https://mediafilez.forgecdn.net/files/'+[int][Math]::Floor($file.id/1000)+'/'+($file.id%1000)+'/'+[uri]::EscapeDataString($file.fileName));sha512=$null;bytes=$file.fileLength;games=@($file.gameVersions | Where-Object {$_ -match '^1\.'});loaders=@($file.gameVersions | Where-Object {$_ -in @('Forge','NeoForge')} | ForEach-Object {$_.ToLowerInvariant()})}
    }
}
Write-Host ('Checking {0} unique official artifacts ({1}).' -f $entries.Count,$Group)
$results=@($entries | ForEach-Object -Parallel {
    $ErrorActionPreference='Stop';$entry=$_;$folder=$using:taskJars
    if([IO.Path]::GetFileName($entry.filename) -ne $entry.filename -or ([uri]$entry.url).Host -notin @('cdn.modrinth.com','mediafilez.forgecdn.net')) {throw 'Invalid official artifact origin'}
    $destination=Join-Path $folder $entry.filename
    $valid=(Test-Path -LiteralPath $destination) -and (Get-Item -LiteralPath $destination).Length -eq $entry.bytes
    if($valid -and $entry.sha512) {$valid=(Get-FileHash -LiteralPath $destination -Algorithm SHA512).Hash -eq $entry.sha512}
    if(-not $valid) {
        $download=$destination+'.download';$done=$false
        for($attempt=1;$attempt -le 3 -and -not $done;$attempt++) {
            try { Invoke-WebRequest -Uri $entry.url -OutFile $download -Headers $using:taskHeaders -TimeoutSec 120;$done=$true }
            catch {if($attempt -eq 3) {throw}}
        }
        if((Get-Item -LiteralPath $download).Length -ne $entry.bytes) {throw ('Length mismatch: '+$entry.filename)}
        if($entry.sha512 -and (Get-FileHash -LiteralPath $download -Algorithm SHA512).Hash -ne $entry.sha512) {throw ('Hash mismatch: '+$entry.filename)}
        Move-Item -LiteralPath $download -Destination $destination -Force
    }
    $entry | Add-Member -NotePropertyName sha256 -NotePropertyValue (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    $zip=[IO.Compression.ZipFile]::OpenRead($destination)
    try {
        $metadata=$zip.GetEntry('META-INF/neoforge.mods.toml');if(-not $metadata) {$metadata=$zip.GetEntry('META-INF/mods.toml')}
        $text='';if($metadata) { $reader=[IO.StreamReader]::new($metadata.Open());try {$text=$reader.ReadToEnd()} finally {$reader.Dispose()} }
        $api=if($entry.project -eq 'iron') {if($zip.GetEntry('io/redspace/ironsspellbooks/api/spells/AbstractSpell.class')) {'registry'} else {'classic'}} else {'voicechat'}
        $entry | Add-Member -NotePropertyName api -NotePropertyValue $api
        $text | Set-Content -LiteralPath (Join-Path $folder ($entry.filename+'.toml')) -Encoding utf8
    } finally {$zip.Dispose()}
    $entry
} -ThrottleLimit $ThrottleLimit)
if($results.Count -ne $entries.Count) {throw 'Incomplete official catalog audit'}
$report=Join-Path $taskAudit ('catalog-'+$Group+'.json');$results | Sort-Object project,filename | ConvertTo-Json -Depth 8 | Set-Content $report -Encoding utf8
Write-Host ('Verified {0} artifacts. Saved private metadata and portable checksum report.' -f $results.Count)
