# Shared LOCAL-ONLY guard; never prints raw CLI status/keys or Auth responses.
function Assert-MeetoLocal {
 if([IO.Path]::GetFullPath((Get-Location).Path).TrimEnd('\') -ne 'F:\MeetoMXH'){throw 'Expected F:\MeetoMXH'}
 $c=(docker inspect supabase_db_MeetoMXH | ConvertFrom-Json)[0]
 if($LASTEXITCODE -ne 0 -or $c.Config.Labels.'com.supabase.cli.project' -ne 'MeetoMXH' -or $c.Config.Labels.'com.supabase.cli.workdir' -ne 'F:\MeetoMXH' -or !$c.State.Running){throw 'Wrong local database container'}
}
function Get-MeetoLocalState([string]$SupabaseCli) {
 Assert-MeetoLocal
 $ErrorActionPreference='Continue'; $raw=& $SupabaseCli status -o json 2>$null; $code=$LASTEXITCODE; $ErrorActionPreference='Stop'
 if($code -ne 0){throw 'Local stack unavailable'}
 $s=$raw|Out-String|ConvertFrom-Json
 if($s.API_URL -ne 'http://127.0.0.1:54321'){throw 'Nonlocal API rejected'}
 return $s
}
function Invoke-MeetoSql([string]$Sql) {
 Assert-MeetoLocal
 $ErrorActionPreference='Continue'
 $out=$Sql | docker exec -i supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atq -v ON_ERROR_STOP=1 --single-transaction 2>&1
 $code=$LASTEXITCODE; $ErrorActionPreference='Stop'
 if($code -ne 0){throw ('Local SQL failed: '+($out|Out-String))}
 return @($out | ForEach-Object {"$_"})
}
