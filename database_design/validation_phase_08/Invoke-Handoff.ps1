param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe")
$ErrorActionPreference='Stop'
if((Get-Location).Path -ne 'F:\MeetoMXH'){throw 'Workspace guard'}
. "$PSScriptRoot/../../supabase/scripts/PhaseB-Local.ps1"
Assert-MeetoLocal
& "$PSScriptRoot/../../supabase/scripts/Test-MigrationManifest.ps1" | Out-Null
$baseline=Get-Content "$PSScriptRoot/protected-backend-hashes.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$unexpected=@($baseline|Where-Object {$_.path -ne 'F:\MeetoMXH\supabase\config.toml' -and (Get-FileHash -LiteralPath $_.path -Algorithm SHA256).Hash -ne $_.sha256}|ForEach-Object {$_.path})
if($unexpected.Count){throw 'Historical protected file changed'}
$before=[IO.File]::ReadAllText("$PSScriptRoot/config-before.toml").Replace("`r`n","`n")
$current=[IO.File]::ReadAllText('F:\MeetoMXH\supabase\config.toml').Replace("`r`n","`n")
$expected=[regex]::Replace($before,'(?m)^additional_redirect_urls = .*$', 'additional_redirect_urls = ["http://127.0.0.1:3000/auth/callback", "meeto://auth/callback"]')
if($current -ne $expected){throw 'Unexpected config changes beyond local callback allowlist'}
$ledger=((Invoke-MeetoSql 'SELECT count(*) FROM supabase_migrations.schema_migrations;') -join '').Trim()
if($ledger -ne '15'){throw 'Expected actual ledger15'}
$tables=((Invoke-MeetoSql "SELECT count(*) FROM pg_tables WHERE schemaname='public';") -join '').Trim()
$rpc=((Invoke-MeetoSql "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND has_function_privilege('authenticated',p.oid,'EXECUTE');") -join '').Trim()
if($tables -ne '36' -or $rpc -ne '49'){throw 'Unexpected Phase C table/RPC inventory'}
$privateOnly=((Invoke-MeetoSql "SELECT private_only FROM _realtime.tenants WHERE external_id='realtime-dev';") -join '').Trim()
if($privateOnly -ne 't'){throw 'Private-only Realtime required'}
$containers=@(docker ps --format '{{.Names}}'|Where-Object {$_ -match '^supabase_.+_MeetoMXH$'})
if($containers.Count -ne 9){throw 'Expected nine local containers'}
$health=@(foreach($name in $containers){$c=(docker inspect $name|ConvertFrom-Json)[0];[pscustomobject]@{name=$name;running=$c.State.Running;health=if($c.State.Health){$c.State.Health.Status}else{'no-healthcheck'};image=$c.Image}})
if(@($health|Where-Object {!$_.running -or $_.health -notin @('healthy','no-healthcheck')}).Count){throw 'Unhealthy local container'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$state=Get-MeetoLocalState $SupabaseCli
$apk='F:\MeetoMXH\app\build\outputs\apk\debug\app-debug.apk'
$zip=[IO.Compression.ZipFile]::OpenRead($apk);$service=$false;$secret=$false;$anon=$false;$legacy=$false
try{foreach($entry in $zip.Entries){if($entry.FullName -notmatch '\.(dex|xml|json|properties)$'){continue};$reader=[IO.StreamReader]::new($entry.Open());try{$value=$reader.ReadToEnd()}finally{$reader.Dispose()};if($state.SERVICE_ROLE_KEY -and $value.Contains($state.SERVICE_ROLE_KEY)){$service=$true};if($state.JWT_SECRET -and $value.Contains($state.JWT_SECRET)){$secret=$true};if($value.Contains($state.ANON_KEY)){$anon=$true};if($value -match 'com/google/firebase|com\.google\.firebase|api\.imgbb\.com|i\.ibb\.co'){$legacy=$true}}}finally{$zip.Dispose();$state=$null;$value=$null}
if($service -or $secret -or $legacy -or !$anon){throw 'APK security/legacy scan failed'}
[pscustomobject]@{apkSha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash;serviceRoleFound=$service;localJwtSecretFound=$secret;localAnonPresent=$anon;legacyRuntimeMarkers=$legacy}|ConvertTo-Json|Set-Content -Encoding UTF8 "$PSScriptRoot/apk-security-scan.json"
$ErrorActionPreference='Continue'
$hits=@(rg -n -i 'firebase|firestore|imgbb|google-services' app/src/main app/src/debug app/build.gradle.kts build.gradle.kts gradle/libs.versions.toml 2>$null)
$ErrorActionPreference='Stop'
if($hits.Count -or (Test-Path google-services.json) -or (Test-Path app/google-services.json)){throw 'Target legacy source/config scan failed'}
[pscustomobject]@{targetLegacyReferences=$hits.Count;rootGoogleConfigPresent=$false;appGoogleConfigPresent=$false;date=(Get-Date -Format o)}|ConvertTo-Json|Set-Content -Encoding UTF8 "$PSScriptRoot/legacy-scan.json"
$tests=0;$fails=0
Get-ChildItem app/build/test-results/testDebugUnitTest -Filter '*.xml'|ForEach-Object {$xml=[xml](Get-Content $_.FullName -Raw);$tests+=[int]$xml.testsuite.tests;$fails+=[int]$xml.testsuite.failures}
$lint=(Get-Content app/build/reports/lint-results-debug.txt -Tail 2|Out-String).Trim()
$dependencies=@(Select-String -Path "$PSScriptRoot/gradle-runtime-dependencies.txt" -Pattern 'com.google.firebase|firebase-bom|imgbb').Count
if($tests -lt 10 -or $fails -ne 0 -or $lint -notmatch '^0 errors,' -or $dependencies -ne 0){throw 'Required checks not passed'}
[pscustomobject]@{tests=$tests;failures=$fails;lint=$lint;runtimeLegacyDependencyHits=$dependencies}|ConvertTo-Json|Set-Content -Encoding UTF8 "$PSScriptRoot/build-summary.json"
[pscustomobject]@{date=(Get-Date -Format o);protectedFiles=$baseline.Count;historicalChanges=$unexpected.Count;configOnlyLocalCallback=$true;ttl=3600;ledger=[int]$ledger;publicTables=[int]$tables;authenticatedPublicRpc=[int]$rpc;privateOnly=$true;portableListener55439=@(Get-NetTCPConnection -State Listen -LocalPort 55439 -ErrorAction SilentlyContinue).Count;emulatorBoot=((& 'F:\android\Sdk\platform-tools\adb.exe' -s emulator-5554 shell getprop sys.boot_completed)|Out-String).Trim();containers=$health}|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 "$PSScriptRoot/handoff-checks.json"
Write-Output 'Handoff PASS: historical hashes/manifest15/config/stack/APK/legacy/build checks'
