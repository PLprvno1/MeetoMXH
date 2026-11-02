param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe")
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
. "$workspace/supabase/scripts/PhaseB-Local.ps1"
Assert-MeetoLocal
$protected=Get-Content "$PSScriptRoot/protected-hashes.json" -Raw -Encoding UTF8|ConvertFrom-Json
foreach($entry in $protected){if(!(Test-Path -LiteralPath $entry.path) -or (Get-FileHash -LiteralPath $entry.path).Hash -ne $entry.sha256){throw ('Protected artifact changed: '+$entry.path)}}
& "$workspace/supabase/scripts/Test-MigrationManifest.ps1"
& "$workspace/supabase/scripts/Configure-ChatRealtimeLocal.ps1"
foreach($spec in @(@{file='http-results.json';n=511},@{file='websocket-results.json';n=40},@{file='phase-a-http-results.json';n=174},@{file='phase-b-http-results.json';n=473},@{file='phase-a-sql-results.json';n=87})){
 $r=Get-Content "$PSScriptRoot/$($spec.file)" -Raw -Encoding UTF8|ConvertFrom-Json
 if($r.passed -ne $spec.n -or $r.failed -ne 0){throw ('Incomplete actual-run evidence: '+$spec.file)}
}
$catalog=Invoke-MeetoSql ([IO.File]::ReadAllText("$PSScriptRoot/catalogue.sql"))
$catalogJson=@($catalog|Where-Object {$_ -like '{*'})[-1]|ConvertFrom-Json
if($catalogJson.passed -ne 33 -or $catalogJson.failed -ne 0){throw 'Incomplete C catalog/invariants'}
$catalogJson|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 "$PSScriptRoot/catalogue-results.json"
$source=[IO.File]::ReadAllText("$workspace/database_design/validation_phase_05b/Invoke-EnvironmentEvidence.ps1")
$source=$source.Replace('$PSScriptRoot/../..',$workspace).Replace('$PSScriptRoot/environment-results.json',"$PSScriptRoot/environment-results.json")
& ([scriptblock]::Create($source)) -SupabaseCli $SupabaseCli
$envResult=Get-Content "$PSScriptRoot/environment-results.json" -Raw|ConvertFrom-Json
if(@($envResult.containers).Count -ne 9 -or @($envResult.containers|Where-Object {!$_.running -or ($_.health -and $_.health -ne 'healthy')}).Count){throw 'Unhealthy local container'}
if((Get-Content "$workspace/supabase/config.toml" -Raw) -notmatch '(?m)^jwt_expiry = 3600\r?$'){throw 'JWT expiry drift'}
if(Get-NetTCPConnection -LocalPort 55439 -State Listen -ErrorAction SilentlyContinue){throw 'Portable PostgreSQL unexpectedly running'}
$clean=Invoke-MeetoSql "SELECT to_regnamespace('test_a') IS NULL AND NOT EXISTS(SELECT 1 FROM auth.users WHERE id::text LIKE '10000000-0000-0000-0000-%');"
if(($clean -join '') -ne 't'){throw 'SQL regression fixtures leaked'}
$replay=& "$workspace/supabase/scripts/Invoke-PhaseCDeployment.ps1" -SupabaseCli $SupabaseCli
$replayJson=@($replay|Where-Object {$_ -like '{*'})[-1]|ConvertFrom-Json
if(!$replayJson.upToDate -or @($replayJson.migrations).Count -ne 0){throw 'CLI replay not up to date'}
$replayJson|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 "$PSScriptRoot/migration-replay-results.json"
$dry=& "$workspace/supabase/scripts/Invoke-PhaseCDeployment.ps1" -SupabaseCli $SupabaseCli -DryRun
$dryJson=@($dry|Where-Object {$_ -like '{*'})[-1]|ConvertFrom-Json
if(!$dryJson.upToDate -or !$dryJson.dryRun){throw 'Dry-run replay failed'}
$dryJson|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 "$PSScriptRoot/migration-dry-run-results.json"
$data=Invoke-MeetoSql @'
SELECT json_build_object('scope','local synthetic/existing data retained','users',(SELECT count(*) FROM auth.users),'conversations',(SELECT count(*) FROM public.conversations),'messages',(SELECT count(*) FROM public.messages),'hiddenPreferences',(SELECT count(*) FROM public.message_hidden),'mediaAssets',(SELECT count(*) FROM public.media_assets),'storageObjects',(SELECT count(*) FROM storage.objects),'pendingChatEvents',(SELECT count(*) FROM public.event_outbox WHERE event_type='chat.invalidate' AND state='pending'),'deadChatEvents',(SELECT count(*) FROM public.event_outbox WHERE event_type='chat.invalidate' AND state='dead'),'pendingMediaRetire',(SELECT count(*) FROM public.event_outbox WHERE event_type='media.retire' AND state='pending'));
'@
$data|Where-Object {$_ -like '{*'}|Set-Content -Encoding UTF8 "$PSScriptRoot/runtime-data-counts.json"
$scripts=@(Get-ChildItem "$workspace/supabase/scripts","$workspace/supabase/tests",$PSScriptRoot -File -Filter '*.ps1')
foreach($file in $scripts){$tok=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tok,[ref]$errors);if($errors.Count){throw ('PowerShell parser: '+$file.Name)}}
$nodeSource=Get-Content "$workspace/supabase/tests/phase-c-ws.cjs" -Raw
$nodeSource|docker exec -i supabase_storage_MeetoMXH node --check
if($LASTEXITCODE -ne 0){throw 'Node driver syntax error'}
$utf8=New-Object Text.UTF8Encoding($false,$true)
$files=@(Get-ChildItem $PSScriptRoot -File|Where-Object Name -ne 'handoff-checks.json')
$files+=@(Get-ChildItem "$workspace/supabase/migrations" -File)
$files+=@(Get-Item "$workspace/baocao_nhiemvu07.txt","$workspace/database_design/MEETO_PHASE_C_IMPLEMENTATION.md","$workspace/supabase/tests/Invoke-PhaseCHttp.ps1","$workspace/supabase/tests/PhaseC-WebSocket.ps1","$workspace/supabase/tests/phase-c-ws.cjs","$workspace/supabase/scripts/Invoke-ChatDispatcher.ps1","$workspace/supabase/scripts/Configure-ChatRealtimeLocal.ps1","$workspace/supabase/scripts/Invoke-PhaseCDeployment.ps1")
foreach($file in $files){
 $text=$utf8.GetString([IO.File]::ReadAllBytes($file.FullName));if(!$text){throw ('Empty artifact: '+$file.Name)}
 if($text -match 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|sb_(secret|publishable)_[A-Za-z0-9_-]{20,}'){throw ('Credential-shaped artifact: '+$file.Name)}
}
[ordered]@{scope='LOCAL ONLY';protectedFilesUnchanged=@($protected).Count;originalMigrationsUnchanged=9;manifestMigrations=15;applicationTables=36;publicRpc=49;containersRunning=9;jwtExpiry=3600;realtimePrivateOnly=$true;applicationPublications=0;portableListenerAbsent=$true;sqlFixturesRolledBack=$true;powershellParsed=$scripts.Count;nodeSyntax='PASS';utf8Artifacts=$files.Count;credentialScan='no credential-shaped values';replay='upToDate';cloudDeployment=$false;tests=@{cHttp=511;websocket=40;aHttp=174;bHttp=473;aSql=87;catalogue=33}}|ConvertTo-Json -Depth 6|Set-Content -Encoding UTF8 "$PSScriptRoot/handoff-checks.json"
Write-Host 'PASS protected A/B sources/evidence,15 migrations,49 RPC,UTF8/parser/credentials,9 healthy containers,private-only Realtime and CLI replay'
