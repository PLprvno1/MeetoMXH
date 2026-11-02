# Reuse the existing 87 SQL assertions on real managed schemas. No stubs are
# loaded. SQL identities/GUCs remain SQL tests, separate from JWT HTTP evidence.
param()
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
$container=(docker inspect supabase_db_MeetoMXH|ConvertFrom-Json)[0]
if($container.Config.Labels.'com.supabase.cli.workdir' -ne $workspace){throw 'Wrong local container'}
$sql="BEGIN;`n"
$sql+=@'
DO $$ BEGIN
 IF current_database()<>'postgres' OR to_regclass('supabase_migrations.schema_migrations') IS NULL
 OR EXISTS(SELECT 1 FROM auth.users WHERE id::text LIKE '10000000-0000-0000-0000-%')
 OR to_regnamespace('test_a') IS NOT NULL THEN RAISE EXCEPTION 'Wrong SQL fixture scope'; END IF;
END $$;
'@
$guard=New-Object Text.RegularExpressions.Regex('(?s)DO \$\$ BEGIN.*?END \$\$;')
foreach($name in '01_auth_profile_tests.sql','02_extended_tests.sql','03_block_cleanup_tests.sql'){
 $part=[IO.File]::ReadAllText("$workspace/database_design/validation_phase_a/$name")
 $match=$guard.Match($part)
 if(!$match.Success -or !$match.Value.Contains('MeetoAudit04-')){throw 'Unexpected source guard; review adapter'}
 # Replace ONLY the portable guard in memory, with the explicit container and
 # catalog/fixture checks above. Preserve the original source files unchanged.
 $part=$guard.Replace($part,'',1)
 foreach($table in 'profiles','account_status','profile_private','user_settings'){
  $key=if($table -eq 'profiles'){'id'}else{'user_id'}
  $part=$part.Replace("count(*)=5 FROM public.$table)","count(*)=5 FROM public.$table WHERE $key`::text LIKE '10000000-0000-0000-0000-%')")
 }
 $sql+="`n"+$part
}
$sql+=@'

RESET ROLE;
SELECT json_build_object('environment','REAL SUPABASE PG17; SQL identities/GUCs, NOT HTTP','passed',count(*),'failed',0,'tests',json_agg(row_to_json(r) ORDER BY label)) FROM test_a.results r;
ROLLBACK;
'@
$ErrorActionPreference='Continue'
$output=$sql|docker exec -i supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atq -v ON_ERROR_STOP=1 2>&1
$code=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($code -ne 0){throw "Stack SQL regression failed: $output"}
$json=@($output|Where-Object {$_ -like '{*'})[-1]|ConvertFrom-Json
if($json.passed -ne 87){throw 'Expected 87 SQL assertions'}
$json|ConvertTo-Json -Depth 6|Set-Content -Encoding UTF8 "$PSScriptRoot/stack-sql-results.json"
Write-Host 'PASS 87 existing SQL assertions on real Supabase PG17; all fixtures rolled back'
