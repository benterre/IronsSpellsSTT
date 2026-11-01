[CmdletBinding()]
param([string[]]$Case=@('119-oldest','119-v20','119-v3','119-v31','120-oldest','120-current'),[switch]$Resume,[switch]$SkipClient)
$ErrorActionPreference='Stop'
$taskWorkspace=Split-Path -Parent $PSScriptRoot
$taskLauncher=Join-Path $taskWorkspace 'gradlew.bat'
$taskEvidence=Join-Path $taskWorkspace '.research/publication'
New-Item -ItemType Directory -Force -Path $taskEvidence | Out-Null
$definitions=@{
    '119-oldest'=@{Minecraft='1.19.2';Profile='oldest';Classic=$true}
    '119-v20'=@{Minecraft='1.19.2';Profile='v20';Classic=$true}
    '119-v2'=@{Minecraft='1.19.2';Profile='v2';Classic=$true}
    '119-v3'=@{Minecraft='1.19.2';Profile='v3';Classic=$true}
    '119-v31'=@{Minecraft='1.19.2';Profile='v31';Classic=$true}
    '120-oldest'=@{Minecraft='1.20.1';Profile='oldest';Classic=$true}
    '118-current'=@{Minecraft='1.18.2';Profile='';Classic=$false}
    '119-current'=@{Minecraft='1.19.2';Profile='';Classic=$false}
    '120-current'=@{Minecraft='1.20.1';Profile='';Classic=$false}
    '120-neoforge'=@{Minecraft='1.20.1';Profile='';Classic=$false;Neo=$true;Mutable=$true}
}
$sourceFiles=@(Get-ChildItem (Join-Path $taskWorkspace 'src/main'),(Join-Path $taskWorkspace 'src/gameTest'),(Join-Path $taskWorkspace 'src/clientProbe'),(Join-Path $taskWorkspace 'ports/legacy/src') -Recurse -File)+@(Get-Item (Join-Path $taskWorkspace 'ports/legacy/build.gradle'),(Join-Path $taskWorkspace 'ports/legacy/port-sources.gradle'))
$fingerprint=($sourceFiles | Sort-Object FullName | ForEach-Object {$_.FullName.Substring($taskWorkspace.Length).Replace('\','/')+':'+(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}) -join "`n"
$sourceHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($fingerprint)))
$reportPath=Join-Path $taskEvidence 'boundary-results.json'
$results=if($Resume -and (Test-Path -LiteralPath $reportPath)) {@(Get-Content -Raw $reportPath | ConvertFrom-Json)} else {@()}
foreach($name in $Case) {
    if(-not $definitions.ContainsKey($name)) {throw "Unknown port case: $name"}
    if($Resume -and ($results | Where-Object {$_.case -eq $name -and $_.sourceSha256 -eq $sourceHash -and $_.passed -and ($SkipClient -or $_.client)})) {continue}
    $definition=$definitions[$name];$taskArguments=@('-p','ports/legacy',('-PtargetMc='+$definition.Minecraft),'-PgameTests')
    if($definition.Profile) {$taskArguments+=('-PdependencyProfile='+$definition.Profile)}
    if($definition.Classic) {$taskArguments+='-PclassicTests'}
    if($definition.Neo) {$taskArguments+='-PlegacyNeo'}
    if($definition.Mutable) {$taskArguments+='-PmutableIron'}
    if(-not $SkipClient) {$taskArguments+='-PclientProbe'}
    $taskArguments+='runGameTestServer';if(-not $SkipClient) {$taskArguments+='runClientProbe'}
    if(-not $definition.Profile) {$taskArguments+='build'}
    $log=Join-Path $taskEvidence ('boundary-'+$name+'.log');$started=[DateTimeOffset]::UtcNow
    Write-Host "Testing $name client=$(-not $SkipClient)"
    Push-Location $taskWorkspace
    try { & $taskLauncher @taskArguments *> $log;$code=$LASTEXITCODE } finally {Pop-Location}
    $text=Get-Content -Raw -LiteralPath $log
    $server=$text -match 'All (\d+) required tests passed';$count=if($server){[int]$Matches[1]}else{0}
    $client= -not $SkipClient -and $text.Contains('STS_CLIENT_PROBE_PASS') -and $text.Contains('STS_HOTKEY_PROBE_PASS')
    $passed=$code -eq 0 -and $server -and ($SkipClient -or $client)
    $row=[pscustomobject]@{case=$name;minecraft=$definition.Minecraft;loader=$(if($definition.Neo){'neoforge'}else{'forge'});profile=$definition.Profile;sourceSha256=$sourceHash;startedUtc=$started.ToString('o');elapsedSeconds=[Math]::Round(([DateTimeOffset]::UtcNow-$started).TotalSeconds,2);serverTests=$count;client=$client;passed=$passed;exitCode=$code;log=[IO.Path]::GetFileName($log)}
    $results=@($results | Where-Object {$_.case -ne $name})+@($row);$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Host "$name passed=$passed nativeTests=$count client=$client"
    if(-not $passed) {throw "Port case $name failed; inspect $log"}
}
