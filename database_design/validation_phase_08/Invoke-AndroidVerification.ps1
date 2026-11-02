param([switch]$IncludeExpiry)
$ErrorActionPreference='Stop'
if((Get-Location).Path -ne 'F:\MeetoMXH'){throw 'Workspace guard'}
$env:JAVA_HOME='F:\android\jbr';$env:ANDROID_HOME='F:\android\Sdk'
$adb='F:\android\Sdk\platform-tools\adb.exe'
& .\supabase\scripts\Initialize-AndroidLocal.ps1
& .\supabase\scripts\Test-MigrationManifest.ps1
$ErrorActionPreference='Continue'
& .\gradlew.bat :app:clean :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug *> "$PSScriptRoot/replay-build.log"
$buildExit=$LASTEXITCODE;$ErrorActionPreference='Stop'
if($buildExit -ne 0){throw 'Android build/unit/lint failed'}
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk | Out-Null
if($LASTEXITCODE -ne 0){throw 'Target APK install failed'}
& $adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | Out-Null
if($LASTEXITCODE -ne 0){throw 'Test APK install failed'}
$tests=@(
 @{name='SupabaseCutoverTest';artifact='cutover-results.json'},
 @{name='FeedRenderingTest';artifact='feed-rendering-results.json'},
 @{name='CommentsUiTest';artifact='comments-ui-results.json'},
 @{name='SessionRestoreTest';artifact='session-restore-results.json'},
 @{name='RecoveryTest';artifact='recovery-results.json'},
 @{name='MediaAndMessengerTest';artifact='media-messenger-results.json'},
 @{name='ChatHistoryTest';artifact='history-results.json'},
 @{name='ClientSecurityTest';artifact='client-security-results.json'}
)
foreach($test in $tests){
 if($test.name -eq 'SessionRestoreTest'){& $adb -s emulator-5554 shell am force-stop com.example.meetomxh}
 $log=Join-Path $PSScriptRoot ('replay-'+$test.name+'.log')
 & $adb -s emulator-5554 shell am instrument -w -e class ('com.example.meetomxh.'+$test.name) com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner *> $log
 if(!(Select-String -LiteralPath $log -SimpleMatch 'OK (1 test)' -Quiet)){throw ('Device test failed: '+$test.name)}
 & $adb -s emulator-5554 pull ('/sdcard/Android/data/com.example.meetomxh/files/'+$test.artifact) (Join-Path $PSScriptRoot ('replay-'+$test.artifact)) | Out-Null
 Write-Output ($test.name+' PASS')
}
& "$PSScriptRoot/Invoke-AndroidNetworkTest.ps1"
if($IncludeExpiry){& "$PSScriptRoot/Invoke-AndroidExpiryTest.ps1"}
Write-Output 'Android replay finished. Real media and chat workers must remain running during suites.'
