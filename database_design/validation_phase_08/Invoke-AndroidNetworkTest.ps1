param()
$ErrorActionPreference='Stop'
if((Get-Location).Path -ne 'F:\MeetoMXH'){throw 'Workspace guard'}
$adb='F:\android\Sdk\platform-tools\adb.exe'
if((& $adb -s emulator-5554 shell getprop sys.boot_completed).Trim() -ne '1'){throw 'Expected booted local emulator'}
$remote='/sdcard/Android/data/com.example.meetomxh/files/'
& $adb -s emulator-5554 shell rm -f ($remote+'network-phase.txt') ($remote+'network-control.txt') | Out-Null
$process=Start-Process -FilePath $adb -ArgumentList @('-s','emulator-5554','shell','am','instrument','-w','-e','class','com.example.meetomxh.NetworkRecoveryTest','com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$PSScriptRoot/device-network.log" -RedirectStandardError "$PSScriptRoot/device-network-errors.log"
$stage=0;$deadline=[DateTime]::UtcNow.AddMinutes(4)
try {
 while(!$process.HasExited -and [DateTime]::UtcNow -lt $deadline){
  $ErrorActionPreference='Continue';$phase=(& $adb -s emulator-5554 shell cat ($remote+'network-phase.txt') 2>$null|Out-String).Trim();$ErrorActionPreference='Stop'
  if($stage -eq 0 -and $phase -eq 'ready'){
   & $adb -s emulator-5554 reverse --remove tcp:54321 | Out-Null
   & $adb -s emulator-5554 shell "echo offline > ${remote}network-control.txt" | Out-Null
   $stage=1;Write-Output 'ADB bridge removed; server and volumes remain running'
  }
  if($stage -eq 1 -and $phase -eq 'offline-verified'){
   & $adb -s emulator-5554 reverse tcp:54321 tcp:54321 | Out-Null
   & $adb -s emulator-5554 shell "echo online > ${remote}network-control.txt" | Out-Null
   $stage=2;Write-Output 'ADB bridge restored after device observed real network failure'
  }
  Start-Sleep -Milliseconds 500
 }
 if(!$process.HasExited){throw 'Bounded emulator network test timed out'}
 if($stage -ne 2 -or !(Select-String -LiteralPath "$PSScriptRoot/device-network.log" -SimpleMatch 'OK (1 test)' -Quiet)){throw 'Network recovery device test failed; inspect sanitized log'}
 & $adb -s emulator-5554 pull ($remote+'network-recovery-results.json') "$PSScriptRoot/network-recovery-results.json" | Out-Null
 Write-Output 'Actual network loss/reconnect/outbox device test PASS'
}finally { & $adb -s emulator-5554 reverse tcp:54321 tcp:54321 | Out-Null }
