# LOCAL AUDIT ONLY. Không đọc local.properties hay kết nối Supabase.
param(
 [Parameter(Mandatory=$true)][string]$PortableRoot,
 [int]$Port=55439,
 [ValidateSet('meeto_phase05')][string]$Database='meeto_phase05',
 [string]$SqlFile,
 [string]$SqlText,
 [switch]$ApplyMigrations,
 [switch]$CreateAuditDatabase,
 [switch]$RecreateAuditDatabase
)
$ErrorActionPreference='Stop'
$resolved=[System.IO.Path]::GetFullPath($PortableRoot)
if($resolved -notmatch '\\Temp\\MeetoAudit04-[a-f0-9]{32}$'){throw 'Only the isolated MeetoAudit04 temporary cluster is permitted'}
if($Port -lt 1024 -or $Port -gt 65535){throw 'Invalid local port'}
$lib=Join-Path $resolved 'pgsql\bin\libpq.dll'
if(-not (Test-Path -LiteralPath (Join-Path $resolved 'data\PG_VERSION'))){throw 'Audit cluster missing'}
$bin=Join-Path $resolved 'pgsql\bin'
if(-not ('MeetoAuditNative' -as [type])){
 $cs=@"
using System;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using System.Text;
public class MeetoQueryResult {
 public int Status; public string SqlState; public string Error; public string[][] Rows;
}
public static class MeetoAuditNative {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] public static extern bool SetDllDirectory(string p);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern IntPtr PQconnectdb(string p);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern int PQstatus(IntPtr c);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern void PQfinish(IntPtr c);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern IntPtr PQerrorMessage(IntPtr c);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern IntPtr PQexec(IntPtr c,byte[] q);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern int PQresultStatus(IntPtr r);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern IntPtr PQresultErrorMessage(IntPtr r);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern IntPtr PQresultErrorField(IntPtr r,int f);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern int PQntuples(IntPtr r);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern int PQnfields(IntPtr r);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern int PQgetisnull(IntPtr r,int row,int col);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern IntPtr PQgetvalue(IntPtr r,int row,int col);
 [DllImport(@"$lib",CallingConvention=CallingConvention.Cdecl)] public static extern void PQclear(IntPtr r);
 public static string S(IntPtr p) { if(p==IntPtr.Zero)return ""; int n=0;while(Marshal.ReadByte(p,n)!=0)n++;byte[] b=new byte[n];Marshal.Copy(p,b,0,n);return Encoding.UTF8.GetString(b); }
 public static MeetoQueryResult Query(IntPtr c,string sql) {
  IntPtr r=PQexec(c,Encoding.UTF8.GetBytes(sql+"\0"));
  try {
   var x=new MeetoQueryResult();x.Status=PQresultStatus(r);
   x.SqlState=S(PQresultErrorField(r,67));x.Error=S(PQresultErrorMessage(r));
   int n=PQntuples(r),m=PQnfields(r);x.Rows=new string[n][];
   for(int i=0;i<n;i++){x.Rows[i]=new string[m];for(int j=0;j<m;j++)x.Rows[i][j]=PQgetisnull(r,i,j)==1?null:S(PQgetvalue(r,i,j));}
   return x;
  } finally {PQclear(r);}
 }
}
"@
 Add-Type -TypeDefinition $cs
}
[MeetoAuditNative]::SetDllDirectory($bin) | Out-Null
$db=if($CreateAuditDatabase -or $RecreateAuditDatabase){'postgres'}else{$Database}
$conn=[MeetoAuditNative]::PQconnectdb("host=127.0.0.1 port=$Port dbname=$db user=meeto_audit_owner connect_timeout=5 application_name=MeetoAudit04")
try{
 if([MeetoAuditNative]::PQstatus($conn) -ne 0){throw [MeetoAuditNative]::S([MeetoAuditNative]::PQerrorMessage($conn))}
 $location=[MeetoAuditNative]::Query($conn,'SHOW data_directory;')
 if($location.Status -ne 2 -or [System.IO.Path]::GetFullPath($location.Rows[0][0]) -ne (Join-Path $resolved 'data')){throw 'Connected server is not the isolated audit cluster'}
 if($RecreateAuditDatabase){
  if($Database -ne 'meeto_phase05'){throw 'Only synthetic Phase A database may be recreated'}
  $drop=[MeetoAuditNative]::Query($conn,'DROP DATABASE IF EXISTS meeto_phase05 WITH (FORCE);')
  if($drop.Status -ne 1){throw $drop.Error}
  $result=[MeetoAuditNative]::Query($conn,'CREATE DATABASE meeto_phase05;')
 }
 elseif($CreateAuditDatabase){$result=[MeetoAuditNative]::Query($conn,('CREATE DATABASE '+$Database+';'))}

 elseif($ApplyMigrations){
  & (Join-Path $PSScriptRoot '../../supabase/scripts/Test-MigrationManifest.ps1')
  $ledger=[MeetoAuditNative]::Query($conn,'CREATE SCHEMA IF NOT EXISTS private; CREATE TABLE IF NOT EXISTS private.meeto_local_migrations(version text PRIMARY KEY,sha256 text NOT NULL); REVOKE ALL ON TABLE private.meeto_local_migrations FROM PUBLIC,anon,authenticated;')
  if($ledger.Status -notin 1,2){throw $ledger.Error}
  $applied=0;$skipped=0
  $migrations=Get-ChildItem (Join-Path $PSScriptRoot '../../supabase/migrations') -Filter *.sql | Sort-Object Name
  foreach($m in $migrations){
   $version=$m.BaseName.Split('_')[0];$hash=(Get-FileHash $m.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
   $old=[MeetoAuditNative]::Query($conn,"SELECT sha256 FROM private.meeto_local_migrations WHERE version='$version'")
   if($old.Rows.Count){if($old.Rows[0][0] -ne $hash){throw 'Applied migration checksum drift'};$skipped++;continue}
   $text=[IO.File]::ReadAllText($m.FullName)
   $result=[MeetoAuditNative]::Query($conn,"BEGIN; $text`nINSERT INTO private.meeto_local_migrations VALUES('$version','$hash'); COMMIT;")
   if($result.Status -notin 1,2){[MeetoAuditNative]::Query($conn,'ROLLBACK')|Out-Null;throw $result.Error}
   $applied++
  }
  [pscustomobject]@{Applied=$applied;Skipped=$skipped;Environment='PostgreSQL local with managed stubs'}|ConvertTo-Json -Compress
  return
 }
 elseif($SqlText){$result=[MeetoAuditNative]::Query($conn,$SqlText)} elseif($SqlFile){$sql=[System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $SqlFile),[System.Text.Encoding]::UTF8);$result=[MeetoAuditNative]::Query($conn,$sql)}
 else {$result=[MeetoAuditNative]::Query($conn,'SELECT version(),current_database(),inet_server_addr(),inet_server_port();')}
 $result | ConvertTo-Json -Depth 8 -Compress
 if($result.Status -notin 1,2){exit 1}
}finally{[MeetoAuditNative]::PQfinish($conn)}

