param([Parameter(Mandatory=$true)][string]$PortableRoot)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
if([IO.Path]::GetFullPath($PortableRoot) -notmatch '\\Temp\\MeetoAudit04-[a-f0-9]{32}$'){throw 'Wrong isolated cluster'}
$evidence=[ordered]@{environment='PostgreSQL 16 portable; SQL identity stubs; NOT Supabase HTTP';steps=@()}
function Run([string]$label,[string[]]$arguments){
 $ErrorActionPreference='Continue'
 $output=& powershell -NoProfile -ExecutionPolicy Bypass -File @arguments 2>&1
 $ErrorActionPreference='Stop'
 if($LASTEXITCODE -ne 0){throw "$label failed: $output"}
 $script:evidence.steps+=@{label=$label;output=($output -join "`n");passed=$true}
 Write-Host "PASS $label"
}
try{
 & "$PortableRoot/pgsql/bin/pg_ctl.exe" -D "$PortableRoot/data" -l "$PortableRoot/postgres.log" -o '-h 127.0.0.1 -p 55439 -c timezone=UTC' -w start
 if($LASTEXITCODE -ne 0){throw 'Portable start failed'}
 $runner='database_design/validation_phase_a/Invoke-PhaseALocal.ps1'
 Run 'recreate only synthetic meeto_phase05' @($runner,'-PortableRoot',$PortableRoot,'-RecreateAuditDatabase')
 Run 'Phase A stubs' @($runner,'-PortableRoot',$PortableRoot,'-SqlFile','database_design/validation_phase_a/00_stubs.sql')
 Run '3 migrations' @($runner,'-PortableRoot',$PortableRoot,'-ApplyMigrations')
 foreach($name in '01_auth_profile_tests.sql','02_extended_tests.sql','03_block_cleanup_tests.sql'){
  Run $name @($runner,'-PortableRoot',$PortableRoot,'-SqlFile',"database_design/validation_phase_a/$name")
 }
 Run '3 Phase A races' @('database_design/validation_phase_a/Invoke-PhaseAConcurrency.ps1','-PortableRoot',$PortableRoot)
 Run '4 migration controls' @('database_design/validation_phase_a/Test-MigrationControls.ps1','-PortableRoot',$PortableRoot)
 $runner='database_design/validation/Invoke-MeetoLocalSql.ps1'
 Run 'recreate only synthetic audit04 final' @($runner,'-PortableRoot',$PortableRoot,'-Database','meeto_audit04_final','-RecreateAuditDatabase')
 foreach($name in '00_local_supabase_stubs.sql','01_schema_local.sql','02_rls_local.sql','03_synthetic_fixtures.sql','04_constraints_rls_tests.sql','07_regression_tests.sql'){
  Run $name @($runner,'-PortableRoot',$PortableRoot,'-Database','meeto_audit04_final','-SqlFile',"database_design/validation/$name")
 }
 Run '3 audit04 race patterns' @('database_design/validation/Invoke-MeetoConcurrency.ps1','-PortableRoot',$PortableRoot)
}finally{
 & "$PortableRoot/pgsql/bin/pg_ctl.exe" -D "$PortableRoot/data" -m fast -w stop
 $evidence.portableStopped=($LASTEXITCODE -eq 0)
 $evidence|ConvertTo-Json -Depth 8|Set-Content -Encoding UTF8 "$PSScriptRoot/portable-results.json"
}
