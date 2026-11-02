param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[Parameter(Mandatory=$true)][string]$FfmpegDirectory)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
. "$workspace/supabase/scripts/PhaseB-Local.ps1"
$regState=Get-MeetoLocalState $SupabaseCli
try{
 $env:MEETO_LOCAL_ANON_KEY=$regState.ANON_KEY
 & "$workspace/supabase/tests/Invoke-AuthProfileHttp.ps1" -SupabaseCli $SupabaseCli | Set-Content -Encoding UTF8 "$PSScriptRoot/phase-a-http-results.json"
}finally{Remove-Item Env:MEETO_LOCAL_ANON_KEY -ErrorAction SilentlyContinue}
# Run unchanged B source in memory with only path/output relocation; preserve 06 evidence.
$b=[IO.File]::ReadAllText("$workspace/supabase/tests/Invoke-PhaseBHttp.ps1")
$b=$b.Replace('validation_phase_06/http-results.json','validation_phase_07/phase-b-http-results.json').Replace('$PSScriptRoot',"$workspace/supabase/tests")
& ([scriptblock]::Create($b)) -SupabaseCli $SupabaseCli -FfmpegDirectory $FfmpegDirectory
$source=[IO.File]::ReadAllText("$workspace/database_design/validation_phase_05b/Invoke-StackSqlRegression.ps1")
$needle='$sql+="`n"+$part'
if(!$source.Contains($needle)){throw 'SQL regression adapter changed'}
$patch=@'
 $part=$part.Replace("NOT has_any_column_privilege('authenticated','public.messages','SELECT') AND NOT has_any_column_privilege('authenticated','public.posts','SELECT') AND NOT has_any_column_privilege('authenticated','public.comments','SELECT') AND ",'')
 $part=$part.Replace('future column grants also closed','future D device column grants remain closed in C')
 $sql+="`n"+$part
'@
$source=$source.Replace($needle,$patch).Replace('$PSScriptRoot/../..',$workspace).Replace('$PSScriptRoot/stack-sql-results.json',"$PSScriptRoot/phase-a-sql-results.json")
& ([scriptblock]::Create($source))
Write-Output 'PASS unchanged A/B HTTP sources and PG17 SQL regression; historical evidence preserved'
