param([ValidateSet('iron','voice','both')][string]$Group='both', [int]$Limit=0, [switch]$StopOnFailure, [switch]$Resume,
      [string]$Fixture='sts-integration-test-final.jar', [string]$OnlyVersions='', [int]$RequiredTests=12,
      [string]$ReportName='', [string]$VoiceCounterpart='')
$ErrorActionPreference='Stop'
if ($RequiredTests -lt 1) { throw 'RequiredTests must be positive' }
$taskWorkspace=Split-Path -Parent $PSScriptRoot
$taskRoot=Join-Path $taskWorkspace '.research/compatibility'
$taskEvidence=Join-Path $taskWorkspace 'docs/evidence/compatibility'
$taskJava='C:\Program Files\Java\jdk-21.0.12\bin\java.exe'
if ([IO.Path]::GetFileName($Fixture) -ne $Fixture) {throw 'Fixture must be a local jar filename'}
$taskTestJar=Join-Path $taskRoot $Fixture
if (-not (Test-Path -LiteralPath $taskTestJar)) { throw 'Build the fixed integration-test jar before running this matrix' }
$taskHash=(Get-FileHash -LiteralPath $taskTestJar -Algorithm SHA256).Hash
$taskCatalog=@(Get-Content (Join-Path $taskEvidence 'catalog.json') -Raw | ConvertFrom-Json)
$taskBase=@(Get-Content (Join-Path $taskWorkspace 'build/moddev/gameTestServerLegacyClasspath.txt') | Where-Object {
    $_ -notmatch '[\\/]libs[\\/](irons_spellbooks-|voicechat-)'
})
$taskNewestIron=($taskCatalog | Where-Object project -eq 's4OWxYQQ' | Sort-Object published)[-1]
$taskNewestVoice=($taskCatalog | Where-Object project -eq '9eGKb6K1' | Sort-Object published)[-1]
if ($VoiceCounterpart) {
    $taskMatches=@($taskCatalog | Where-Object { $_.project -eq '9eGKb6K1' -and $_.version -eq $VoiceCounterpart })
    if ($taskMatches.Count -ne 1) { throw 'VoiceCounterpart must identify one catalog release' }
    $taskNewestVoice=$taskMatches[0]
}
$taskRows=@()
if ($ReportName -and [IO.Path]::GetFileName($ReportName) -ne $ReportName) { throw 'ReportName must be a local filename' }
$taskCsv=Join-Path $taskEvidence $(if ($ReportName) { $ReportName } else { 'runtime-'+$Group+'.csv' })
if ($Resume -and (Test-Path -LiteralPath $taskCsv)) {
    $taskRows=@(Import-Csv -LiteralPath $taskCsv | Where-Object testJarSha256 -eq $taskHash)
}
$taskSelected=@($taskCatalog | Where-Object { $Group -eq 'both' -or ($Group -eq 'iron' -and $_.project -eq 's4OWxYQQ') -or ($Group -eq 'voice' -and $_.project -eq '9eGKb6K1') } | Sort-Object project,published)
if ($Limit -gt 0) { $taskSelected=@($taskSelected | Select-Object -First $Limit) }
if ($OnlyVersions) { $taskSelected=@($taskSelected | Where-Object { ($OnlyVersions -split ',') -contains $_.version }) }
foreach ($taskRelease in $taskSelected) {
    if ($taskRows | Where-Object { $_.project -eq $taskRelease.project -and $_.version -eq $taskRelease.version -and $_.log -eq ($taskRelease.project+'-'+$taskRelease.id+'.log') }) { continue }
    $taskIron=if ($taskRelease.project -eq 's4OWxYQQ') {$taskRelease} else {$taskNewestIron}
    $taskVoice=if ($taskRelease.project -eq '9eGKb6K1') {$taskRelease} else {$taskNewestVoice}
    $taskName=$taskRelease.project+'-'+$taskRelease.id
    if ($VoiceCounterpart) { $taskName+='-voice-'+$taskVoice.id }
    $taskRun=Join-Path (Join-Path $taskWorkspace 'run/compatibility') $taskName
    New-Item -ItemType Directory -Force -Path $taskRun,(Join-Path $taskRun 'config/voicechat'),(Join-Path $taskRun 'config/sts') | Out-Null
    "port=0`nbind_address=127.0.0.1`n" | Set-Content (Join-Path $taskRun 'config/voicechat/voicechat-server.properties') -Encoding utf8
    "grimoireless_cast_any_spell=true`ngrimoireless_cast_at_max_level=true`ncast_without_wand=true`n" | Set-Content (Join-Path $taskRun 'config/sts/options.toml') -Encoding utf8
    # NeoForge's legacy list contains libraries, not mods: adding a mod here hides it from discovery.
    $taskClasspath=@($taskBase)
    $taskClasspathFile=Join-Path $taskRun 'classpath.txt'
    $taskClasspath | Set-Content -LiteralPath $taskClasspathFile -Encoding utf8NoBOM
    $taskVm=@(Get-Content (Join-Path $taskWorkspace 'build/moddev/gameTestServerRunVmArgs.txt') | ForEach-Object {
        if ($_ -like '-DlegacyClassPath.file=*') { '-DlegacyClassPath.file='+$taskClasspathFile.Replace('\','/') } else {$_}
    })
    $taskRealClasspath=@(Get-Content (Join-Path $taskWorkspace 'build/compatibility-launch/classpath.txt') | Where-Object {
        $_ -notmatch '[\\/](libs[\\/](irons_spellbooks-|voicechat-)|test-mods[\\/]|build[\\/](classes|resources)[\\/])'
    })+(Join-Path $taskRoot ('jars/'+$taskIron.filename))+(Join-Path $taskRoot ('jars/'+$taskVoice.filename))+$taskTestJar
    $taskRealClasspath += @(Get-ChildItem (Join-Path $taskRoot 'dependencies') -Filter '*.jar' | ForEach-Object FullName)
    $taskIronZip=[IO.Compression.ZipFile]::OpenRead((Join-Path $taskRoot ('jars/'+$taskIron.filename)))
    try {
        $taskReader=[IO.StreamReader]::new($taskIronZip.GetEntry('META-INF/neoforge.mods.toml').Open())
        try { $taskIronMetadata=$taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
        $taskStream=$taskIronZip.GetEntry('io/redspace/ironsspellbooks/api/util/Utils.class').Open()
        $taskMemory=[IO.MemoryStream]::new()
        try { $taskStream.CopyTo($taskMemory); $taskLegacyCurios=[Text.Encoding]::UTF8.GetString($taskMemory.ToArray()).Contains('getCuriosHelper') }
        finally {$taskStream.Dispose();$taskMemory.Dispose()}
    } finally {$taskIronZip.Dispose()}
    if ($taskLegacyCurios) {
        $taskRealClasspath=@($taskRealClasspath | Where-Object {$_ -notmatch '[\\/]libs[\\/]curios-'})
        $taskRealClasspath+=@(Get-ChildItem (Join-Path $taskRoot 'legacy-curios') -Filter '*.jar' | ForEach-Object FullName)
    }
    $taskIronLibrary='1.21.1-2.2.0'
    $taskLegacyLibrary=$null
    if ($taskIronMetadata -match 'modId\s*=\s*"irons_patreon_lib"') {
        $taskLegacyLibrary='irons_patreon_lib-1.21.1-1.0.1.jar'
        $taskIronLibrary='patreon-1.21.1-1.0.1'
    } elseif ($taskIronMetadata -match '(?s)modId\s*=\s*"irons_lib".*?versionRange\s*=\s*"\[1\.21\.1-1,1\.21\.1-2\)"') {
        $taskLegacyLibrary='irons_lib-1.21.1-1.1.0.jar'
        $taskIronLibrary='1.21.1-1.1.0'
    }
    if ($taskLegacyLibrary) {
        $taskRealClasspath=@($taskRealClasspath | Where-Object {$_ -notmatch '[\\/]libs[\\/]irons_lib-'})
        $taskRealClasspath+=Join-Path (Join-Path $taskRoot 'legacy-iron-libs') $taskLegacyLibrary
    }
    @('-Xmx1400m','-Xms256m','-cp',('"'+($taskRealClasspath -join ';').Replace('\','/')+'"'))+$taskVm | Set-Content (Join-Path $taskRun 'vm.txt') -Encoding utf8NoBOM
    Copy-Item -LiteralPath (Join-Path $taskWorkspace 'build/moddev/gameTestServerRunProgramArgs.txt') -Destination (Join-Path $taskRun 'program.txt')
    $taskStart=[DateTimeOffset]::UtcNow
    $taskOldModClasses=$env:MOD_CLASSES
    try {
        $env:MOD_CLASSES=''
        $taskProcess=Start-Process -FilePath $taskJava -ArgumentList '@vm.txt','@program.txt' -WorkingDirectory $taskRun -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRun 'stdout.log') -RedirectStandardError (Join-Path $taskRun 'stderr.log')
        $taskDone=$taskProcess.WaitForExit(300000)
        if (-not $taskDone) { $taskProcess.Kill(); $taskProcess.WaitForExit() }
    } finally { $env:MOD_CLASSES=$taskOldModClasses }
    $taskText=Get-Content (Join-Path $taskRun 'stdout.log') -Raw
    $taskError=Get-Content (Join-Path $taskRun 'stderr.log') -Raw
    $taskPassed=$taskDone -and $taskProcess.ExitCode -eq 0 -and $taskText -match ('All '+$RequiredTests+' required tests passed')
    Copy-Item -LiteralPath (Join-Path $taskRun 'stdout.log') -Destination (Join-Path $taskEvidence ($taskName+'.log'))
    Copy-Item -LiteralPath (Join-Path $taskRun 'stderr.log') -Destination (Join-Path $taskEvidence ($taskName+'.stderr.log'))
    $taskRow=[pscustomobject]@{ project=$taskRelease.project; version=$taskRelease.version; iron=$taskIron.version; voice=$taskVoice.version; curios=$(if($taskLegacyCurios){'9.2.0+1.21.1'}else{'9.5.1+1.21.1'}); ironLibrary=$taskIronLibrary; passed=$taskPassed; exitCode=$taskProcess.ExitCode; timedOut=(-not $taskDone); seconds=[Math]::Round(([DateTimeOffset]::UtcNow-$taskStart).TotalSeconds,2); testJarSha256=$taskHash; log=$taskName+'.log' }
    $taskRows+=$taskRow
    $taskRows | Export-Csv -LiteralPath $taskCsv -NoTypeInformation -Encoding utf8
    Write-Host ('{0}/{1}: {2} => {3} ({4}s)' -f $taskRows.Count,$taskSelected.Count,$taskRelease.version,$taskPassed,$taskRow.seconds)
    if ($StopOnFailure -and -not $taskPassed) { throw ('Integration failure: '+$taskRelease.version) }
}
