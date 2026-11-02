param([Parameter(Mandatory=$true)][string]$SupabaseCli)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
$container=(docker inspect supabase_db_MeetoMXH|ConvertFrom-Json)[0]
if($container.Config.Labels.'com.supabase.cli.workdir' -ne $workspace){throw 'Wrong local container'}
& "$workspace/supabase/scripts/Test-MigrationManifest.ps1"
$ErrorActionPreference='Continue'
$catalog=Get-Content -Encoding UTF8 "$PSScriptRoot/catalogue.sql" -Raw | docker exec -i supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atq -v ON_ERROR_STOP=1 2>&1
$code=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($code -ne 0){throw "Catalog checks failed: $catalog"}
$json=@($catalog|Where-Object {$_ -like '{*'})[-1]
$json|ConvertFrom-Json|ConvertTo-Json -Depth 6|Set-Content -Encoding UTF8 "$PSScriptRoot/catalogue-results.json"
Write-Host 'PASS real Supabase catalog checks'
$probe=Join-Path $workspace 'supabase/migrations/20261101000400_integration_rollback_probe.sql'
if(Test-Path -LiteralPath $probe){throw 'Unexpected existing probe migration'}
try{
 [IO.File]::WriteAllText($probe,"CREATE TABLE private.integration_ddl_rollback_probe(id integer);`nSELECT 1/0;`n",(New-Object Text.UTF8Encoding($false)))
 $ErrorActionPreference='Continue'
 $failure=& $SupabaseCli db push --local --skip-vault 2>&1
 $code=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 if($code -eq 0 -or ($failure -join '') -notmatch '22012|division by zero'){throw 'Expected deliberate CLI DDL failure'}
 $rolled= docker exec supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atc "SELECT to_regclass('private.integration_ddl_rollback_probe') IS NULL AND NOT EXISTS(SELECT 1 FROM supabase_migrations.schema_migrations WHERE version='20261101000400');"
 if($LASTEXITCODE -ne 0 -or $rolled -ne 't'){throw 'CLI left partial DDL or ledger'}
 @{test='actual CLI failed migration rolls back DDL and ledger';passed=$true;expectedSqlstate='22012';scope='local only'}|ConvertTo-Json|Set-Content -Encoding UTF8 "$PSScriptRoot/cli-rollback-results.json"
 Write-Host 'PASS actual CLI migration rollback'
}finally{if(Test-Path -LiteralPath $probe){Remove-Item -LiteralPath $probe}}
& "$workspace/supabase/scripts/Test-MigrationManifest.ps1"
$ErrorActionPreference='Continue'
$replay=& $SupabaseCli db push --local --skip-vault 2>&1
$code=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($code -ne 0 -or ($replay -join '') -notmatch 'upToDate'){throw 'CLI replay failed'}
@{test='actual CLI replay up to date';passed=$true;output=($replay -join "`n")}|ConvertTo-Json|Set-Content -Encoding UTF8 "$PSScriptRoot/cli-replay-results.json"
Write-Host 'PASS CLI migration replay'
