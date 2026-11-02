param([Parameter(Mandatory=$true)][string]$SupabaseCli)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
$ErrorActionPreference='Continue'
$raw=& $SupabaseCli status -o json 2>$null
$code=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($code -ne 0){throw 'Local stack status unavailable'}
$state=$raw|Out-String|ConvertFrom-Json
if($state.API_URL -ne 'http://127.0.0.1:54321'){throw 'Unexpected API endpoint'}
$info=docker info --format '{{json .}}'|ConvertFrom-Json
$containers=@(docker ps -q --filter 'label=com.supabase.cli.project=MeetoMXH'|ForEach-Object {
 $item=(docker inspect $_|ConvertFrom-Json)[0]
 if($item.Config.Labels.'com.supabase.cli.workdir' -ne $workspace){throw 'Wrong container workspace'}
 [pscustomobject]@{name=$item.Name.TrimStart('/');image=$item.Config.Image;imageId=$item.Image;running=$item.State.Running;health=$item.State.Health.Status}
})
$checks=@()
foreach($path in '/auth/v1/health','/rest/v1/','/storage/v1/status'){
 try{$r=Invoke-WebRequest -UseBasicParsing -Uri ($state.API_URL+$path) -Headers @{apikey=$state.ANON_KEY;Authorization=('Bearer '+$state.ANON_KEY)} -TimeoutSec 15;$status=[int]$r.StatusCode}
 catch{if(!$_.Exception.Response){throw 'Local service unavailable'};$status=[int]$_.Exception.Response.StatusCode}
 $checks+=@{endpoint=$path;status=$status}
 if($status -ne 200){throw "Service health failed: $path HTTP $status"}
}
foreach($url in 'http://127.0.0.1:54323','http://127.0.0.1:54324/api/v1/messages'){
 $r=Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 15
 $checks+=@{endpoint=$url;status=[int]$r.StatusCode}
}
$ws=New-Object Net.WebSockets.ClientWebSocket
try{
 $uri=[uri]('ws://127.0.0.1:54321/realtime/v1/websocket?apikey='+$state.ANON_KEY+'&vsn=1.0.0')
 $task=$ws.ConnectAsync($uri,[Threading.CancellationToken]::None)
 if(!$task.Wait(15000) -or $ws.State -ne [Net.WebSockets.WebSocketState]::Open){throw 'Realtime WebSocket handshake failed'}
 $checks+=@{endpoint='Realtime WebSocket (key omitted)';status=101}
}finally{$ws.Dispose()}
$os=Get-CimInstance Win32_OperatingSystem
$evidence=[ordered]@{
 date='2026-11-01 Asia/Bangkok';scope='LOCAL ONLY; no Cloud connection'
 dockerClient=(docker --version|Out-String).Trim();dockerServer=$info.ServerVersion;dockerCompose=(docker compose version|Out-String).Trim()
 dockerContext=(docker context show|Out-String).Trim();osType=$info.OSType;kernel=$info.KernelVersion;dockerMemoryBytes=$info.MemTotal
 hostMemoryKiB=$os.TotalVisibleMemorySize;hostFreeMemoryKiB=$os.FreePhysicalMemory
 diskFreeBytes=@{C=(Get-PSDrive C).Free;F=(Get-PSDrive F).Free}
 supabaseCli=(& $SupabaseCli --version|Out-String).Trim();containers=$containers;endpoints=$checks
 finalState='RUNNING; PostgreSQL portable STOPPED';jwtExpiry=3600
}
$evidence|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 "$PSScriptRoot/environment-results.json"
Write-Host ('PASS '+$containers.Count+' local containers and '+$checks.Count+' HTTP/WebSocket endpoints')
