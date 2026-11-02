param([Parameter(Mandatory=$true)][string]$PortableRoot,[int]$Port=55439)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Invoke-PhaseALocal.ps1" -PortableRoot $PortableRoot -Port $Port | Out-Null
function Q($connection,[string]$sql){
 $r=[MeetoAuditNative]::Query($connection,$sql)
 if($r.Status -notin 1,2){throw ($r.SqlState+': '+$r.Error)}
 return $r
}
$c=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_phase05 user=meeto_audit_owner connect_timeout=5")
$checks=New-Object 'System.Collections.Generic.List[string]'
$manifest=Join-Path $PSScriptRoot '../../supabase/migrations.sha256.json'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('MeetoPhaseA-manifest-'+[guid]::NewGuid().ToString('N')+'.json')
try {
 $bad=Get-Content $manifest -Raw -Encoding UTF8|ConvertFrom-Json
 $bad[0].sha256='0'*64
 [IO.File]::WriteAllText($temp,($bad|ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
 $rejected=$false
 try{& (Join-Path $PSScriptRoot '../../supabase/scripts/Test-MigrationManifest.ps1') -ManifestPath $temp | Out-Null}
 catch {if($_.Exception.Message -like 'Migration checksum mismatch:*'){$rejected=$true}else{throw}}
 if(!$rejected){throw 'Manifest accepted tampered checksum'}
 $checks.Add('manifest checksum tampering rejected')
 $old=(Q $c "SELECT sha256 FROM private.meeto_local_migrations WHERE version='20261101000100'").Rows[0][0]
 try{
  Q $c "UPDATE private.meeto_local_migrations SET sha256=repeat('0',64) WHERE version='20261101000100'"|Out-Null
  $rejected=$false
  try{& "$PSScriptRoot/Invoke-PhaseALocal.ps1" -PortableRoot $PortableRoot -Port $Port -ApplyMigrations|Out-Null}
  catch{if($_.Exception.Message -eq 'Applied migration checksum drift'){$rejected=$true}else{throw}}
  if(!$rejected){throw 'Ledger accepted checksum drift'}
  $checks.Add('applied ledger checksum drift rejected')
 }finally{Q $c "UPDATE private.meeto_local_migrations SET sha256='$old' WHERE version='20261101000100'"|Out-Null}
 $r=[MeetoAuditNative]::Query($c,'BEGIN; CREATE TABLE private.phase_a_rollback_probe(id integer); SELECT 1/0; COMMIT;')
 if($r.SqlState -ne '22012'){throw 'Expected deliberate migration failure'}
 Q $c 'ROLLBACK;'|Out-Null
 $r=Q $c "SELECT to_regclass('private.phase_a_rollback_probe') IS NULL,(SELECT count(*)=3 FROM private.meeto_local_migrations)"
 if($r.Rows[0][0] -ne 't' -or $r.Rows[0][1] -ne 't'){throw 'Failed transaction left DDL or ledger changes'}
 $checks.Add('failed migration transaction rolls back DDL and leaves ledger intact')
 $r=& "$PSScriptRoot/Invoke-PhaseALocal.ps1" -PortableRoot $PortableRoot -Port $Port -ApplyMigrations
 if(($r -join '') -notmatch '"Applied":0,"Skipped":3'){throw 'Replay did not skip applied migrations'}
 $checks.Add('migration replay no-op 0 applied 3 skipped')
 [pscustomobject]@{passed=$checks.Count;failed=0;scope='local ledger harness, not Supabase CLI remote ledger';checks=$checks}|ConvertTo-Json -Depth 4 -Compress
}finally{
 [MeetoAuditNative]::PQfinish($c)
 if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}
}
