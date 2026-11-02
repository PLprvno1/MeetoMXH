param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[switch]$IncludeExpiry)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
. "$workspace/supabase/scripts/PhaseB-Local.ps1"
$state=Get-MeetoLocalState $SupabaseCli
try{
 $env:MEETO_LOCAL_ANON_KEY=$state.ANON_KEY
 & "$workspace/supabase/tests/Invoke-AuthProfileHttp.ps1" -SupabaseCli $SupabaseCli |
  Set-Content -Encoding UTF8 "$PSScriptRoot/phase-a-http-results.json"
}finally{Remove-Item Env:MEETO_LOCAL_ANON_KEY -ErrorAction SilentlyContinue}
# Existing 05B adapter preserved, with one Phase-specific expected-grant update.
# Only posts/comments are now B. Messages/device subscriptions remain future closed.
$source=[IO.File]::ReadAllText("$workspace/database_design/validation_phase_05b/Invoke-StackSqlRegression.ps1")
$needle='$sql+="`n"+$part'
if(!$source.Contains($needle)){throw 'SQL adapter source changed'}
$patch=@'
 $part=$part.Replace(" AND NOT has_any_column_privilege('authenticated','public.posts','SELECT') AND NOT has_any_column_privilege('authenticated','public.comments','SELECT')",'')
 $part=$part.Replace('future column grants also closed','future C/D column grants remain closed in B')
 $sql+="`n"+$part
'@
$source=$source.Replace($needle,$patch).Replace('$PSScriptRoot/../..',$workspace).Replace('$PSScriptRoot/stack-sql-results.json',"$PSScriptRoot/phase-a-sql-results.json")
& ([scriptblock]::Create($source))
if($IncludeExpiry){
 $expiry=[IO.File]::ReadAllText("$workspace/supabase/tests/Invoke-ExpiredJwt.ps1")
 $expiry=$expiry.Replace('$PSScriptRoot/../..',$workspace).Replace('validation_phase_05b/expiry-results.json','validation_phase_06/phase-a-expiry-results.json')
 & ([scriptblock]::Create($expiry)) -SupabaseCli $SupabaseCli
}
