param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[string]$Adb='F:\android\Sdk\platform-tools\adb.exe')
$ErrorActionPreference='Stop'
. "$PSScriptRoot/PhaseB-Local.ps1"
Assert-MeetoLocal
if((Get-Location).Path -ne 'F:\MeetoMXH'){throw 'Workspace guard'}
$state=Get-MeetoLocalState $SupabaseCli
try {
 if(!$state.ANON_KEY){throw 'Local anon key missing'}
 # This ignored debug file contains only the public local anon key. Never copy service role.
 [IO.File]::WriteAllText('F:\MeetoMXH\supabase.debug.properties',"url=http://127.0.0.1:54321`n"+'anonKey='+$state.ANON_KEY+"`n",[Text.UTF8Encoding]::new($false))
}finally{$state=$null}
& $Adb -s emulator-5554 reverse tcp:54321 tcp:54321 | Out-Null
if($LASTEXITCODE -ne 0){throw 'Local emulator ADB bridge failed'}
& $Adb -s emulator-5554 reverse tcp:54324 tcp:54324 | Out-Null
if($LASTEXITCODE -ne 0){throw 'Local emulator Mailpit bridge failed'}
& "$PSScriptRoot/Configure-ChatRealtimeLocal.ps1" | Out-Null
Write-Output 'Local debug anon configuration and ADB bridges ready; no credentials printed'
