param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[switch]$DryRun)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/PhaseB-Local.ps1"
Assert-MeetoLocal
& "$PSScriptRoot/Test-MigrationManifest.ps1"
$expected=@('20261101000100','20261101000200','20261101000300','20261101000400','20261101000500','20261101000600','20261101000700','20261101000800','20261101000900')
$versions=Invoke-MeetoSql 'SELECT version FROM supabase_migrations.schema_migrations ORDER BY version;'
if($versions.Count -lt 3 -or $versions.Count -gt 9){throw 'Expected existing Phase A local ledger; inventory required'}
for($i=0;$i -lt $versions.Count;$i++){if($versions[$i] -ne $expected[$i]){throw 'Unexpected migration ledger'}}
$cliArgs=@('db','push','--local');if($DryRun){$cliArgs+=@('--dry-run')}
$ErrorActionPreference='Continue';$out=& $SupabaseCli @cliArgs 2>&1;$code=$LASTEXITCODE;$ErrorActionPreference='Stop'
if($code -ne 0){throw ('Local migration failed; inspect and correct before retry: '+($out|Out-String))}
$out | ForEach-Object { $_.ToString() }
