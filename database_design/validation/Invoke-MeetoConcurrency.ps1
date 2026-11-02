# LOCAL synthetic concurrency checks; these are DB patterns, not implemented app RPCs.
param([Parameter(Mandatory=$true)][string]$PortableRoot,[int]$Port=55439)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Invoke-MeetoLocalSql.ps1" -PortableRoot $PortableRoot -Port $Port -Database meeto_audit04_final | Out-Null
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
 $a=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_audit04_final user=meeto_audit_owner connect_timeout=5")
 $b=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_audit04_final user=meeto_audit_owner connect_timeout=5")
 try{
  $pidB=(Q $b 'SELECT pg_backend_pid()').Rows[0][0]
  Q $a ("BEGIN; SET LOCAL lock_timeout='5s'; "+$sqlA) | Out-Null
  $ok=[MeetoAuditAsync]::PQsendQuery($b,[System.Text.Encoding]::UTF8.GetBytes("BEGIN; SET LOCAL lock_timeout='5s'; "+$sqlB+" COMMIT;"+[char]0))
  if($ok -ne 1){throw 'Async send failed'}
  $blocked=$false
  for($i=0;$i -lt 60;$i++){
   $state=(Q $a ("SELECT coalesce(wait_event_type,'') FROM pg_stat_activity WHERE pid="+$pidB)).Rows[0][0]
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
 }finally{[MeetoAuditNative]::PQfinish($a);[MeetoAuditNative]::PQfinish($b)}
}
$out=@()
$out+=Run-Race 'pending_pair_cross_request' "INSERT INTO public.friend_requests(requester_id,recipient_id,client_request_id) VALUES(audit.id(3),audit.id(4),audit.id(9801));" "INSERT INTO public.friend_requests(requester_id,recipient_id,client_request_id) VALUES(audit.id(4),audit.id(3),audit.id(9802));" '23505'
$out+=Run-Race 'message_retry_same_request' "INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,body) VALUES(audit.id(401),4,audit.id(1),audit.id(9803),repeat('c',64),'synthetic retry');" "INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,body) VALUES(audit.id(401),5,audit.id(1),audit.id(9803),repeat('c',64),'synthetic retry');" '23505'
$out+=Run-Race 'reaction_counter_two_writers' "SELECT 1 FROM public.posts WHERE id=audit.id(101) FOR UPDATE; INSERT INTO public.post_reactions(post_id,user_id,reaction) VALUES(audit.id(101),audit.id(1),'like'); UPDATE public.posts SET reactions_count=reactions_count+1 WHERE id=audit.id(101);" "SELECT 1 FROM public.posts WHERE id=audit.id(101) FOR UPDATE; INSERT INTO public.post_reactions(post_id,user_id,reaction) VALUES(audit.id(101),audit.id(2),'like'); UPDATE public.posts SET reactions_count=reactions_count+1 WHERE id=audit.id(101);" 'success'
$c=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=meeto_audit04_final user=meeto_audit_owner connect_timeout=5")
try{
 $row=(Q $c 'SELECT reactions_count,(SELECT count(*) FROM public.post_reactions WHERE post_id=audit.id(101)) FROM public.posts WHERE id=audit.id(101)').Rows[0]
 if($row[0] -ne '2' -or $row[1] -ne '2'){throw 'Counter differs from cardinality'}
 $out | ConvertTo-Json -Depth 6 -Compress
}finally{[MeetoAuditNative]::PQfinish($c)}

