$ErrorActionPreference = 'Stop'
$taskWorkspace = Split-Path -Parent $PSScriptRoot
$taskRoot = Join-Path $taskWorkspace '.research/compatibility'
$taskJobs = @()
foreach ($taskProject in @('s4OWxYQQ', '9eGKb6K1')) {
    foreach ($taskVersion in (Get-Content -LiteralPath (Join-Path $taskRoot ($taskProject+'-versions.json')) -Raw | ConvertFrom-Json)) {
        $taskFile = @($taskVersion.files | Where-Object primary)[0]
        if (-not $taskFile) { $taskFile = $taskVersion.files[0] }
        if ([IO.Path]::GetFileName($taskFile.filename) -ne $taskFile.filename -or ([Uri]$taskFile.url).Host -ne 'cdn.modrinth.com') {
            throw 'Unexpected release file origin or filename'
        }
        $taskJobs += [pscustomobject]@{ project=$taskProject; version=$taskVersion.version_number; id=$taskVersion.id;
            filename=$taskFile.filename; url=$taskFile.url; sha512=$taskFile.hashes.sha512; published=$taskVersion.date_published }
    }
}
New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'jars') | Out-Null
$taskResults = @($taskJobs | ForEach-Object -Parallel {
    $ErrorActionPreference = 'Stop'
    $taskTarget = Join-Path (Join-Path $using:taskRoot 'jars') $_.filename
    if (-not (Test-Path -LiteralPath $taskTarget) -or (Get-FileHash -LiteralPath $taskTarget -Algorithm SHA512).Hash -ne $_.sha512) {
        Invoke-WebRequest -Uri $_.url -OutFile $taskTarget -Headers @{'User-Agent'='STS-compatibility-audit/0.2'}
    }
    if ((Get-FileHash -LiteralPath $taskTarget -Algorithm SHA512).Hash -ne $_.sha512) { throw ('Checksum mismatch: '+$_.filename) }
    $_ | Add-Member -NotePropertyName sha256 -NotePropertyValue (Get-FileHash -LiteralPath $taskTarget -Algorithm SHA256).Hash -PassThru
} -ThrottleLimit 4)
if ($taskResults.Count -ne $taskJobs.Count) { throw 'Incomplete release download' }
New-Item -ItemType Directory -Force -Path (Join-Path $taskWorkspace 'docs/evidence/compatibility') | Out-Null
$taskResults | Sort-Object project,published | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskWorkspace 'docs/evidence/compatibility/catalog.json') -Encoding utf8
Write-Host ('Verified {0} official release jars with SHA-512; SHA-256 catalog saved.' -f $taskResults.Count)
