param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[Parameter(Mandatory=$true)][string]$FfmpegDirectory,[int]$DurationMinutes=120)
$ErrorActionPreference='Stop'
Set-Location F:\MeetoMXH
. "$PSScriptRoot/PhaseB-Local.ps1"
Assert-MeetoLocal
& "$PSScriptRoot/Configure-ChatRealtimeLocal.ps1"|Out-Null
if($DurationMinutes -lt 1 -or $DurationMinutes -gt 180){throw 'Development runner maximum is 180 minutes'}
$end=[DateTime]::UtcNow.AddMinutes($DurationMinutes)
while([DateTime]::UtcNow -lt $end){
 try{
  $media=& "$PSScriptRoot/Invoke-MediaWorker.ps1" -SupabaseCli $SupabaseCli -FfmpegDirectory $FfmpegDirectory -BatchSize 20
  $chat=& "$PSScriptRoot/Invoke-ChatDispatcher.ps1" -SupabaseCli $SupabaseCli -BatchSize 100
  if($media.verified -or $media.deleted -or $chat.done){[pscustomobject]@{time=[DateTime]::UtcNow.ToString('o');verified=$media.verified;deleted=$media.deleted;chatDone=$chat.done;published=$chat.published}|ConvertTo-Json -Compress}
 }catch{Write-Output 'Local development worker failed; retrying bounded pass without logging credentials'}
 Start-Sleep -Seconds 1
}
