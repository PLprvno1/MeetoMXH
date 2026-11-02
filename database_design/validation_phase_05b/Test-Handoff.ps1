param([Parameter(Mandatory=$true)][string]$ProtectedSnapshot,[Parameter(Mandatory=$true)][string]$PortableRoot)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
$snapshot=Get-Content -LiteralPath $ProtectedSnapshot -Raw -Encoding UTF8|ConvertFrom-Json
foreach($item in $snapshot){
 if(!(Test-Path -LiteralPath $item.path) -or (Get-FileHash -LiteralPath $item.path -Algorithm SHA256).Hash -ne $item.sha256){throw 'Protected file changed'}
}
$current=@(foreach($path in 'app','gradle','baocao.txt','baocao_nhiemvu04.txt','baocao_nhiemvu05.txt','local.properties','supabase/storage.sql','build.gradle.kts','settings.gradle.kts','gradle.properties','google-services.json'){
 Get-ChildItem -LiteralPath $path -File -Recurse|ForEach-Object {$_.FullName}
})
if(@(Compare-Object @($snapshot.path) $current).Count -ne 0){throw 'Protected file inventory changed'}
Write-Host ('PASS '+$snapshot.Count+' protected file hashes and inventory')
& "$workspace/supabase/scripts/Test-MigrationManifest.ps1"
$artifacts=@(Get-ChildItem -LiteralPath $PSScriptRoot -File|Where-Object Name -ne 'handoff-checks.json')
$artifacts+=Get-Item 'baocao_nhiemvu05B.txt','database_design/MEETO_PHASE_A_IMPLEMENTATION.md','supabase/tests/Invoke-AuthProfileHttp.ps1','supabase/tests/Invoke-ExpiredJwt.ps1'
$utf8=New-Object Text.UTF8Encoding($false,$true)
foreach($file in $artifacts){
 if($file.Length -eq 0){throw 'Empty handoff artifact'}
 $text=$utf8.GetString([IO.File]::ReadAllBytes($file.FullName))
 if($text -match 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|sb_(secret|publishable)_[A-Za-z0-9_-]{20,}'){
  throw 'Credential-shaped value in handoff artifact (details suppressed)'
 }
}
$scripts=@(Get-ChildItem 'supabase/scripts','supabase/tests',$PSScriptRoot -File -Filter '*.ps1')
foreach($file in $scripts){
 $tokens=$null;$parseErrors=$null
 $null=[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$parseErrors)
 if($parseErrors.Count -ne 0){throw 'PowerShell parser failure'}
}
foreach($pair in @(@{file='http-results.json';passed=174},@{file='expiry-results.json';passed=7},@{file='catalogue-results.json';passed=25},@{file='stack-sql-results.json';passed=87})){
 $result=Get-Content -LiteralPath "$PSScriptRoot/$($pair.file)" -Raw -Encoding UTF8|ConvertFrom-Json
 if($result.passed -ne $pair.passed -or $result.failed -ne 0){throw 'Incomplete evidence'}
}
$portable=Get-Content -LiteralPath "$PSScriptRoot/portable-results.json" -Raw -Encoding UTF8|ConvertFrom-Json
if(!$portable.portableStopped -or @($portable.steps|Where-Object {!$_.passed}).Count){throw 'Portable regression incomplete'}
$null=& "$PortableRoot/pgsql/bin/pg_ctl.exe" -D "$PortableRoot/data" status
if($LASTEXITCODE -ne 3){throw 'Portable server still running'}
if((Get-Content 'supabase/config.toml' -Raw) -notmatch '(?m)^jwt_expiry = 3600\r?$'){throw 'JWT config not restored'}
$clean=docker exec supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atc "SELECT to_regnamespace('test_a') IS NULL AND to_regclass('private.integration_ddl_rollback_probe') IS NULL AND to_regclass('private.integration_default_table') IS NULL AND to_regprocedure('private.integration_default_probe()') IS NULL AND NOT EXISTS(SELECT 1 FROM auth.users WHERE id::text LIKE '10000000-0000-0000-0000-%') AND (SELECT count(*)=3 FROM supabase_migrations.schema_migrations);"
if($LASTEXITCODE -ne 0 -or $clean -ne 't'){throw 'SQL probe cleanup incomplete'}
$result=[ordered]@{protectedFilesUnchanged=$snapshot.Count;protectedInventoryUnchanged=$true;utf8NonemptyArtifacts=$artifacts.Count;credentialScan='no credential-shaped values found';powershellScriptsParsed=$scripts.Count;parseErrors=0;migrationManifest='3 unchanged checksums verified';portableStatusExitCode=3;sqlProbeCleanup=$true;jwtExpiry=3600;supabaseLocal='RUNNING';remoteDeployment=$false}
$result|ConvertTo-Json -Depth 4|Set-Content -Encoding UTF8 "$PSScriptRoot/handoff-checks.json"
Write-Host 'PASS final UTF-8/parser/credential scan/evidence/config/probe cleanup checks'
