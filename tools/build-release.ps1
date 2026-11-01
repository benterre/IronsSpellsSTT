[CmdletBinding()]
param([switch]$UseExistingBuilds)
$ErrorActionPreference='Stop'
$taskWorkspace=Split-Path -Parent $PSScriptRoot
$properties=Get-Content -LiteralPath (Join-Path $taskWorkspace 'gradle.properties')
$version=($properties | Where-Object {$_ -match '^mod_version='}) -replace '^mod_version=',''
if($version -notmatch '^[a-zA-Z0-9_.+-]+$') {throw 'Invalid release version'}
$output=[IO.Path]::GetFullPath((Join-Path $taskWorkspace ('releases/'+$version)))
if(-not $output.StartsWith($taskWorkspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Release directory outside workspace'}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$targets=@(
    @{Minecraft='1.18.2';Loader='forge';Java=17;Project='ports/legacy';Arguments=@('-PtargetMc=1.18.2');Source="ports/legacy/build/libs/sts-forge-1.18.2-$version.jar"}
    @{Minecraft='1.19.2';Loader='forge';Java=17;Project='ports/legacy';Arguments=@('-PtargetMc=1.19.2');Source="ports/legacy/build/libs/sts-forge-1.19.2-$version.jar"}
    @{Minecraft='1.20.1';Loader='forge';Java=17;Project='ports/legacy';Arguments=@('-PtargetMc=1.20.1');Source="ports/legacy/build/libs/sts-forge-1.20.1-$version.jar"}
    @{Minecraft='1.20.1';Loader='neoforge';Java=17;Project='ports/legacy';Arguments=@('-PtargetMc=1.20.1','-PlegacyNeo');Source="ports/legacy/build/libs/sts-neoforge-1.20.1-$version.jar"}
    @{Minecraft='1.21';Loader='neoforge';Java=21;Project='ports/neo';Arguments=@();Source="ports/neo/build/libs/sts-neoforge-1.21-$version.jar"}
    @{Minecraft='1.21.1';Loader='neoforge';Java=21;Project='.';Arguments=@();Source="build/libs/sts-$version.jar"}
)
$launcher=Join-Path $taskWorkspace $(if($IsWindows){'gradlew.bat'}else{'gradlew'})
$rows=@()
foreach($target in $targets) {
    if(-not $UseExistingBuilds) {
        Write-Host "Building $($target.Loader) $($target.Minecraft)"
        Push-Location $taskWorkspace
        try { & $launcher -p $target.Project @($target.Arguments) build;if($LASTEXITCODE -ne 0) {throw 'Gradle release build failed'} } finally {Pop-Location}
    }
    $source=Join-Path $taskWorkspace $target.Source
    $zip=[IO.Compression.ZipFile]::OpenRead($source)
    try {
        $invalid=@($zip.Entries | Where-Object {$_.FullName -match '(?:^|/)gametest/|(?:Grimoire|Hotkey)ClientProbe|\.(?:jar|wav|pcm|flac|opus)$'})
        if($invalid.Count) {throw ('Test/private/embedded dependency content in release: '+$invalid[0].FullName)}
        foreach($required in @('META-INF/LICENSE','META-INF/AI_PROMPTS.md','dev/sts/StsMod.class','icon.png')) {if(-not $zip.GetEntry($required)) {throw "Missing release entry $required"}}
        $metadata=$zip.GetEntry('META-INF/neoforge.mods.toml');if(-not $metadata) {$metadata=$zip.GetEntry('META-INF/mods.toml')}
        $reader=[IO.StreamReader]::new($metadata.Open());try {$text=$reader.ReadToEnd()} finally {$reader.Dispose()}
        if($text -notmatch 'authors\s*=\s*"benterre"' -or $text -notmatch 'license\s*=\s*"MIT"' -or -not $text.Contains('version="'+$version+'"')) {throw 'Incorrect author, license or version in release metadata'}
        if($text -notmatch 'logoFile\s*=\s*"icon\.png"') {throw 'Missing icon reference in release metadata'}
        $stream=$zip.GetEntry('dev/sts/StsMod.class').Open();try {$header=[byte[]]::new(8);for($i=0;$i -lt 8;$i++) {$header[$i]=$stream.ReadByte()};$major=$header[6]*256+$header[7]} finally {$stream.Dispose()}
        if($major -ne ($target.Java+44)) {throw 'Wrong Java bytecode target in release'}
        $reader=[IO.StreamReader]::new($zip.GetEntry('pack.mcmeta').Open());try {$pack=$reader.ReadToEnd() | ConvertFrom-Json} finally {$reader.Dispose()}
        $expectedPack=@{'1.18.2'=8;'1.19.2'=9;'1.20.1'=15;'1.21'=34;'1.21.1'=34}[$target.Minecraft]
        if($pack.pack.pack_format -ne $expectedPack) {throw 'Wrong resource pack format in release'}
    } finally {$zip.Dispose()}
    $name="sts-$($target.Loader)-$($target.Minecraft)-$version.jar"
    $destination=Join-Path $output $name;Copy-Item -LiteralPath $source -Destination $destination -Force
    $rows+=[pscustomobject]@{file=$name;minecraft=$target.Minecraft;loader=$target.Loader;java=$target.Java;bytes=(Get-Item -LiteralPath $destination).Length;sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash;source=$target.Source;voiceEffectsDefault=$false;voiceEffectsStatus='work in progress'}
}
$rows | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding utf8
$rows | ForEach-Object {$_.sha256.ToLowerInvariant()+'  '+$_.file} | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ascii
Write-Host "Prepared $($rows.Count) family JARs in $output. Nothing uploaded."
