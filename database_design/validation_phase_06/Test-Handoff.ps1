param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe")
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
. "$workspace/supabase/scripts/PhaseB-Local.ps1"
Assert-MeetoLocal
$snapshot=Get-Content "$PSScriptRoot/protected-hashes.json" -Raw -Encoding UTF8|ConvertFrom-Json
foreach($entry in $snapshot){
 if(!(Test-Path -LiteralPath $entry.path) -or (Get-FileHash -LiteralPath $entry.path).Hash -ne $entry.sha256){throw ('Protected source/evidence changed: '+$entry.path)}
}
& "$workspace/supabase/scripts/Test-MigrationManifest.ps1"
$backlog=Invoke-MeetoSql ([IO.File]::ReadAllText("$PSScriptRoot/cleanup-backlog.sql"))
@($backlog|Where-Object {$_ -like '{*'})[-1]|Set-Content -Encoding UTF8 "$PSScriptRoot/cleanup-backlog-results.json"
$backlogResult=Get-Content "$PSScriptRoot/cleanup-backlog-results.json" -Raw|ConvertFrom-Json
if($backlogResult.passed -ne 1 -or $backlogResult.failed -ne 0){throw 'Cleanup backlog invariant failed'}
$catalog=Invoke-MeetoSql ([IO.File]::ReadAllText("$PSScriptRoot/catalogue.sql"))
$json=@($catalog|Where-Object {$_ -like '{*'})[-1]
$json|Set-Content -Encoding UTF8 "$PSScriptRoot/catalogue-results.json"
foreach($spec in @(@{name='http-results.json';n=473},@{name='phase-a-http-results.json';n=174},@{name='phase-a-sql-results.json';n=87},@{name='phase-a-expiry-results.json';n=7},@{name='catalogue-results.json';n=25})){
 $result=Get-Content "$PSScriptRoot/$($spec.name)" -Raw -Encoding UTF8|ConvertFrom-Json
 if($result.passed -ne $spec.n -or $result.failed -ne 0){throw ('Incomplete evidence: '+$spec.name)}
}
$source=[IO.File]::ReadAllText("$workspace/database_design/validation_phase_05b/Invoke-EnvironmentEvidence.ps1")
$source=$source.Replace('$PSScriptRoot/../..',$workspace).Replace('$PSScriptRoot/environment-results.json',"$PSScriptRoot/environment-results.json")
& ([scriptblock]::Create($source)) -SupabaseCli $SupabaseCli
$envResult=Get-Content "$PSScriptRoot/environment-results.json" -Raw|ConvertFrom-Json
if(@($envResult.containers).Count -ne 9 -or @($envResult.containers|Where-Object {!$_.running -or ($_.health -and $_.health -ne 'healthy')}).Count){throw 'Local container health failure'}
if((Get-Content "$workspace/supabase/config.toml" -Raw) -notmatch '(?m)^jwt_expiry = 3600\r?$'){throw 'JWT expiry not restored'}
if(Get-NetTCPConnection -LocalPort 55439 -State Listen -ErrorAction SilentlyContinue){throw 'Portable PostgreSQL listener unexpectedly running'}
$sqlClean=Invoke-MeetoSql "SELECT to_regnamespace('test_a') IS NULL AND NOT EXISTS(SELECT 1 FROM auth.users WHERE id::text LIKE '10000000-0000-0000-0000-%') AND NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id::text LIKE '06000000-%' OR id='ffffffff-0000-0000-0000-000000000006') AND NOT EXISTS(SELECT 1 FROM public.posts WHERE id::text LIKE '07000000-%');"
if(($sqlClean -join '') -ne 't'){throw 'SQL regression fixtures leaked'}
$scripts=@(Get-ChildItem "$workspace/supabase/scripts","$workspace/supabase/tests",$PSScriptRoot -Filter '*.ps1' -File)
foreach($file in $scripts){
 $tokens=$null;$errors=$null;$null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)
 if($errors.Count){throw ('PowerShell syntax error: '+$file.Name)}
}
$files=@(Get-ChildItem $PSScriptRoot -File|Where-Object Name -ne 'handoff-checks.json')
$files+=@(Get-ChildItem "$workspace/supabase/migrations" -File)
$files+=@(Get-Item "$workspace/baocao_nhiemvu06.txt","$workspace/database_design/MEETO_PHASE_B_IMPLEMENTATION.md","$workspace/supabase/scripts/Invoke-MediaWorker.ps1","$workspace/supabase/scripts/Invoke-PhaseBDeployment.ps1","$workspace/supabase/scripts/PhaseB-Local.ps1","$workspace/supabase/tests/Invoke-PhaseBHttp.ps1")
$utf8=New-Object Text.UTF8Encoding($false,$true)
foreach($file in $files){
 $text=$utf8.GetString([IO.File]::ReadAllBytes($file.FullName));if(!$text){throw 'Empty artifact'}
 if($text -match 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|sb_(secret|publishable)_[A-Za-z0-9_-]{20,}') {throw ('Credential-shaped value: '+$file.Name)}
}
$replay=& "$workspace/supabase/scripts/Invoke-PhaseBDeployment.ps1" -SupabaseCli $SupabaseCli
$replayJson=@($replay|Where-Object {$_ -like '{*'})[-1]|ConvertFrom-Json
if(!$replayJson.upToDate -or @($replayJson.migrations).Count -ne 0){throw 'Migration replay not up to date'}
$replayJson|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 "$PSScriptRoot/migration-replay-results.json"
@{scope='LOCAL ONLY';protectedFilesUnchanged=@($snapshot).Count;phaseAOriginalMigrationsUnchanged=3;manifestMigrations=9;publicRpc=31;applicationTables=36;powershellParsed=$scripts.Count;utf8Artifacts=$files.Count;credentialScan='no credential-shaped values';containersRunning=9;portableListenerAbsent=$true;jwtExpiry=3600;sqlFixturesRolledBack=$true;replay='upToDate';cloudDeployment=$false;tests=@{phaseB=473;phaseAHttp=174;phaseASql=87;phaseAExpiry=7;catalogue=25}}|ConvertTo-Json -Depth 6|Set-Content -Encoding UTF8 "$PSScriptRoot/handoff-checks.json"
Write-Host 'PASS protected sources/evidence, 9 migrations, UTF8/parser, credentials, regression fixtures, container health and replay'
