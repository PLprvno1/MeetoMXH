# LOCAL synthetic concurrency checks; these are DB patterns, not implemented app RPCs.
param([Parameter(Mandatory=$true)][string]$PortableRoot,[int]$Port=55439)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Invoke-PhaseALocal.ps1" -PortableRoot $PortableRoot -Port $Port -Database meeto_phase05 | Out-Null
$lib=Join-Path $PortableRoot 'pgsql\bin\libpq.dll'
$src=@"
using System;
using System.Runtime.InteropServices;
public static class MeetoAuditAsync {
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern int PQsendQuery(IntPtr c,byte[] q);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern IntPtr PQgetResult(IntPtr c);
}
"@
Add-Type -TypeDefinition $src
function Q($c,[string]$sql){
 $r=[MeetoAuditNative]::Query($c,$sql)
 if($r.Status -notin 1,2){throw ($r.SqlState+': '+$r.Error)}
 return $r
}
function Run-Race([string]$name,[string]$sqlA,[string]$sqlB,[string]$wanted){
 $a=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_phase05 user=meeto_audit_owner connect_timeout=5")
 $b=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_phase05 user=meeto_audit_owner connect_timeout=5")
 $observer=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_phase05 user=meeto_audit_owner connect_timeout=5")
 try{
  $pidB=(Q $b 'SELECT pg_backend_pid()').Rows[0][0]
  Q $a ("BEGIN; SET LOCAL lock_timeout='5s'; "+$sqlA) | Out-Null
  $ok=[MeetoAuditAsync]::PQsendQuery($b,[System.Text.Encoding]::UTF8.GetBytes("BEGIN; SET LOCAL lock_timeout='5s'; "+$sqlB+" COMMIT;"+[char]0))
  if($ok -ne 1){throw 'Async send failed'}
  $blocked=$false
  for($i=0;$i -lt 60;$i++){
   $state=(Q $observer ("SELECT coalesce(wait_event_type,'') FROM pg_stat_activity WHERE pid="+$pidB)).Rows[0][0]
   if($state -eq 'Lock'){$blocked=$true;break}
   [System.Threading.Thread]::Sleep(25)
  }
  if(-not $blocked){throw 'Did not observe real lock overlap'}
  Q $a 'COMMIT;' | Out-Null
  $errors=@()
  while($true){
   $p=[MeetoAuditAsync]::PQgetResult($b)
   if($p -eq [IntPtr]::Zero){break}
   try{ if([MeetoAuditNative]::PQresultStatus($p) -notin 1,2){$errors += [MeetoAuditNative]::S([MeetoAuditNative]::PQresultErrorField($p,67))} }
   finally{[MeetoAuditNative]::PQclear($p)}
  }
  if($wanted -eq 'success'){
   if($errors.Count){throw ('Unexpected errors '+($errors -join ','))}
  }elseif($errors -notcontains $wanted){throw ('Expected '+$wanted+' got '+($errors -join ','))}
  return [pscustomobject]@{Test=$name;Passed=$true;ObservedLockOverlap=$blocked;Expected=$wanted;SqlStates=$errors}
 }finally{[MeetoAuditNative]::PQfinish($a);[MeetoAuditNative]::PQfinish($b);[MeetoAuditNative]::PQfinish($observer)}
}

$out=@()
$setup=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_phase05 user=meeto_audit_owner connect_timeout=5")
try { Q $setup "UPDATE public.profiles SET display_name='Bob',bio='',version=2 WHERE id='10000000-0000-0000-0000-000000000002';" | Out-Null }
finally { [MeetoAuditNative]::PQfinish($setup) }
$actor="SET LOCAL ROLE authenticated; SELECT set_config('request.jwt.claim.sub','10000000-0000-0000-0000-000000000002',true),set_config('request.jwt.claims','{}',true); "
$out+=Run-Race 'profile optimistic version conflict' ($actor+"SELECT public.update_profile('Bob first','',NULL,NULL,2);") ($actor+"SELECT public.update_profile('Bob second','',NULL,NULL,2);") 'PT409'
$out+=Run-Race 'bootstrap versus edit serializes' ($actor+'SELECT public.bootstrap_profile();') ($actor+"SELECT public.update_profile('Bob final','',NULL,NULL,3);") 'success'
$incomplete="SET LOCAL ROLE authenticated; SELECT set_config('request.jwt.claim.sub','10000000-0000-0000-0000-000000000006',true),set_config('request.jwt.claims','{}',true); "
$out+=Run-Race 'same identity bootstrap retry' ($incomplete+'SELECT public.bootstrap_profile();') ($incomplete+'SELECT public.bootstrap_profile();') 'success'
$c=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_phase05 user=meeto_audit_owner connect_timeout=5")
try{
 $r=(Q $c "SELECT version,display_name FROM public.profiles WHERE id='10000000-0000-0000-0000-000000000002'").Rows[0]
 if($r[0] -ne '4' -or $r[1] -ne 'Bob final'){throw 'Unexpected post-race profile state'}
 $out|ConvertTo-Json -Depth 6 -Compress
}finally{[MeetoAuditNative]::PQfinish($c)}
