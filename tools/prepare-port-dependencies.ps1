[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('1.18.2','1.19.2','1.20.1','1.21','1.21.1')][string]$MinecraftVersion,
    [ValidateSet('forge','neoforge')][string]$Loader = 'forge',
    [string]$IronVersion,
    [ValidatePattern('^[a-z0-9-]+$')][string]$Profile,
    [switch]$Refresh
)
$ErrorActionPreference = 'Stop'
$taskWorkspace = Split-Path -Parent $PSScriptRoot
$targetKey = "$MinecraftVersion-$Loader"
if($Profile) { $targetKey+='-'+$Profile }
if($MinecraftVersion -eq '1.21' -and -not $IronVersion) { $IronVersion='1.21-3.4.4' }
$targetDirectory = Join-Path $taskWorkspace ".research/ports/$targetKey"
$manifestPath = Join-Path $taskWorkspace 'ports/dependency-manifest.json'
$headers = @{'User-Agent'='benterre/IronsSpellsSTT unpublished compatibility audit'}
New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $manifestPath -Parent) | Out-Null
$manifest = if (Test-Path -LiteralPath $manifestPath) { @(Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json) } else { @() }
$entries = @($manifest | Where-Object { $_.target -eq $targetKey })
if ($Refresh -or -not $entries.Count -or $IronVersion) {
    $entries = @()
    $projects = @(
        @{Id='s4OWxYQQ';Name='iron'}, @{Id='9eGKb6K1';Name='voicechat'},
        @{Id='vvuO3ImH';Name='curios'}, @{Id='8BmcQJ2H';Name='geckolib'},
        @{Id='gedNE4y2';Name='playeranimator'}, @{Id='40FYwb4z';Name='caelus'}
    )
    if($MinecraftVersion -eq '1.21') { ($projects | Where-Object Name -eq 'curios').Id='BaqCltvf' }
    if ($MinecraftVersion -in @('1.20.1','1.21.1')) { $projects += @{Id='9nfaJPtX';Name='irons_lib'} }
    foreach ($project in $projects) {
        if($MinecraftVersion -eq '1.21' -and $project.Name -eq 'playeranimator') {
            $known=$manifest | Where-Object {$_.target -eq '1.21-neoforge' -and $_.project -eq 'playeranimator' -and $_.version -eq '1.0.2-rc1+1.21'} | Select-Object -First 1
            if($known) {$copy=$known | Select-Object *;$copy.target=$targetKey;$entries+=$copy;continue}
        }
        $query = 'https://api.modrinth.com/v2/project/'+$project.Id+'/version?game_versions='+[uri]::EscapeDataString('["'+$MinecraftVersion+'"]')+'&loaders='+[uri]::EscapeDataString('["'+$Loader+'"]')
        $releases = Invoke-RestMethod -Uri $query -Headers $headers -TimeoutSec 45
        $releases = @($releases)
        if ($project.Name -eq 'iron' -and $IronVersion) { $releases = @($releases | Where-Object { $_.version_number -eq $IronVersion -or $_.version_number -eq "$MinecraftVersion-$IronVersion" -or $_.id -eq $IronVersion }) }
        if ($project.Name -eq 'geckolib') {
            # Iron 1.18.2 and 1.19.2 use GeckoLib 3; the 1.20.1 branch uses GeckoLib 4.
            $major = if ($MinecraftVersion -in @('1.18.2','1.19.2')) { 3 } else { 4 }
            $releases = @($releases | Where-Object { $_.version_number -match ('(?:^|[-:])'+$major+'\.') })
        }
        $release = $releases | Sort-Object date_published | Select-Object -Last 1
        if (-not $release) {
            if ($project.Name -eq 'iron' -and $IronVersion -and (Test-Path (Join-Path $taskWorkspace '.research/publication/catalog-all.json'))) {
                $catalog=Get-Content (Join-Path $taskWorkspace '.research/publication/catalog-all.json') -Raw | ConvertFrom-Json
                $pinned=$catalog | Where-Object { $_.project -eq 'iron' -and $_.games -contains $MinecraftVersion -and ($_.id -eq $IronVersion -or $_.filename -eq "irons_spellbooks-$MinecraftVersion-$IronVersion.jar" -or $_.filename -eq "irons_spellbooks-$IronVersion.jar") } | Select-Object -First 1
                if($pinned) {
                    $cached=Join-Path $taskWorkspace ('.research/publication/catalog-jars/'+$pinned.filename)
                    if((Get-FileHash -LiteralPath $cached -Algorithm SHA256).Hash -ne $pinned.sha256) {throw 'Historical catalog checksum mismatch'}
                    $entries += [pscustomobject]@{target=$targetKey;project='iron';projectId='s4OWxYQQ';version=$pinned.version;versionId=$pinned.id;filename=$pinned.filename;url=$pinned.url;sha512=(Get-FileHash -LiteralPath $cached -Algorithm SHA512).Hash;bytes=$pinned.bytes}
                    continue
                }
            }
            if ($project.Name -in @('iron','voicechat')) { throw "No $($project.Name) release for $targetKey" }
            Write-Host "No $($project.Name) publisher entry for $targetKey; inspect mandatory metadata separately."
            continue
        }
        if ($release.version_number -isnot [string] -or $release.id -isnot [string]) { throw 'Publisher release selection must yield one scalar version' }
        $file = @($release.files | Where-Object primary)[0]
        if (-not $file) { $file=$release.files[0] }
        if ([IO.Path]::GetFileName($file.filename) -ne $file.filename -or ([uri]$file.url).Host -ne 'cdn.modrinth.com') { throw 'Unexpected publisher filename or origin' }
        $entries += [pscustomobject]@{target=$targetKey;project=$project.Name;projectId=$project.Id;version=$release.version_number;versionId=$release.id;filename=$file.filename;url=$file.url;sha512=$file.hashes.sha512;bytes=$file.size}
    }
}
$selectedEntries=[Collections.Generic.List[object]]::new()
$needsIronLibrary=$true
foreach ($entry in $entries) {
    if($entry.project -eq 'irons_lib' -and -not $needsIronLibrary) {
        $unneeded=Join-Path $targetDirectory $entry.filename
        if(Test-Path -LiteralPath $unneeded) {
            $cacheDirectory=Join-Path $taskWorkspace '.research/port-unused'
            New-Item -ItemType Directory -Force -Path $cacheDirectory | Out-Null
            Move-Item -LiteralPath $unneeded -Destination (Join-Path $cacheDirectory ($targetKey+'-'+$entry.filename)) -Force
        }
        continue
    }
    if ([IO.Path]::GetFileName($entry.filename) -ne $entry.filename -or ([uri]$entry.url).Host -notin @('cdn.modrinth.com','mediafilez.forgecdn.net','maven.kosmx.dev')) { throw 'Invalid pinned artifact' }
    $destination = Join-Path $targetDirectory $entry.filename
    if (-not (Test-Path -LiteralPath $destination) -or (Get-FileHash -LiteralPath $destination -Algorithm SHA512).Hash -ne $entry.sha512) {
        Invoke-WebRequest -Uri $entry.url -OutFile ($destination+'.download') -Headers $headers -TimeoutSec 120
        if ((Get-FileHash -LiteralPath ($destination+'.download') -Algorithm SHA512).Hash -ne $entry.sha512) { throw 'Downloaded dependency checksum mismatch' }
        Move-Item -LiteralPath ($destination+'.download') -Destination $destination -Force
    }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA512).Hash -ne $entry.sha512) { throw 'Pinned dependency checksum mismatch' }
    if($entry.project -eq 'iron') {
        $zip=[IO.Compression.ZipFile]::OpenRead($destination)
        try {
            $metadata=$zip.GetEntry('META-INF/neoforge.mods.toml');if(-not $metadata) {$metadata=$zip.GetEntry('META-INF/mods.toml')}
            $reader=[IO.StreamReader]::new($metadata.Open());try {$text=$reader.ReadToEnd()} finally {$reader.Dispose()}
            $needsIronLibrary=$text -match 'modId\s*=\s*"irons_lib"'
        } finally {$zip.Dispose()}
    }
    $selectedEntries.Add($entry)
    Write-Host "Verified $targetKey / $($entry.project): $($entry.filename)"
}
$entries=@($selectedEntries.ToArray())
@(@($manifest | Where-Object { $_.target -ne $targetKey }) + $entries) | Sort-Object target,project | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
Write-Host "Prepared $($entries.Count) pinned dependencies for $targetKey. Publisher metadata must still be validated by the runtime."
