param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe")
$ErrorActionPreference='Stop'
if((Get-Location).Path -ne 'F:\MeetoMXH'){throw 'Workspace guard'}
. "$PSScriptRoot/../../supabase/scripts/PhaseB-Local.ps1"
Assert-MeetoLocal
$config=Join-Path (Get-Location).Path 'supabase/config.toml'
$original=[IO.File]::ReadAllBytes($config)
$hashBefore=(Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash
$adb='F:\android\Sdk\platform-tools\adb.exe'
function Stack([string]$verb){
 $ErrorActionPreference='Continue'
 if($verb -eq 'start'){$captured=& $SupabaseCli start --exclude edge-runtime,logflare,vector 2>&1}else{$captured=& $SupabaseCli stop 2>&1}
 $result=$LASTEXITCODE;$ErrorActionPreference='Stop';$captured=$null
 if($result -ne 0){throw 'Local lifecycle failed (raw status suppressed)'}
}
$pass=$false
try {
 $text=[Text.Encoding]::UTF8.GetString($original)
 if(([regex]::Matches($text,'(?m)^jwt_expiry = 3600\r?$')).Count -ne 1){throw 'Expected baseline TTL3600'}
 [IO.File]::WriteAllText($config,($text -replace '(?m)^jwt_expiry = 3600','jwt_expiry = 60'),[Text.UTF8Encoding]::new($false))
 Stack stop; Stack start
 & "$PSScriptRoot/../../supabase/scripts/Configure-ChatRealtimeLocal.ps1" | Out-Null
 & $adb -s emulator-5554 shell am force-stop com.example.meetomxh
 & $adb -s emulator-5554 shell am instrument -w -e class com.example.meetomxh.ExpiryTest com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner *> "$PSScriptRoot/device-expiry.log"
 $pass=Select-String -LiteralPath "$PSScriptRoot/device-expiry.log" -SimpleMatch 'OK (1 test)' -Quiet
 if($pass){& $adb -s emulator-5554 pull /sdcard/Android/data/com.example.meetomxh/files/expiry-results.json "$PSScriptRoot/expiry-results.json" | Out-Null}
}finally {
 [IO.File]::WriteAllBytes($config,$original)
 Stack stop; Stack start
 & "$PSScriptRoot/../../supabase/scripts/Configure-ChatRealtimeLocal.ps1" | Out-Null
 $restored=(Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash -eq $hashBefore
 [pscustomobject]@{devicePass=$pass;configBytesRestored=$restored;ttl=3600;localStackRunning=$true;privateOnly=$true}|ConvertTo-Json|Set-Content -Encoding UTF8 "$PSScriptRoot/expiry-handoff.json"
 if(!$restored){throw 'Config bytes restoration failed'}
}
if(!$pass){throw 'Actual Android expiry test failed; stack restored, inspect sanitized log'}
Write-Output 'Actual expired JWT/401 retry/revoked refresh device test PASS; TTL3600 restored'
