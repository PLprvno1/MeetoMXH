param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[switch]$DryRun)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/PhaseB-Local.ps1"
Assert-MeetoLocal
& "$PSScriptRoot/Test-MigrationManifest.ps1"
$manifest=Get-Content "$PSScriptRoot/../migrations.sha256.json" -Raw -Encoding UTF8|ConvertFrom-Json
$expected=@($manifest|ForEach-Object {$_.file.Substring(0,14)})
if($expected.Count -ne 15 -or $expected[-1] -ne '20261101001500'){throw 'Unexpected Phase C deployment artifact'}
$versions=Invoke-MeetoSql 'SELECT version FROM supabase_migrations.schema_migrations ORDER BY version;'
if($versions.Count -lt 9 -or $versions.Count -gt 15){throw 'Expected existing A/B local stack; inventory required'}
for($i=0;$i -lt $versions.Count;$i++){if($versions[$i] -ne $expected[$i]){throw 'Unexpected applied local version'}}
$argsLocal=@('db','push','--local');if($DryRun){$argsLocal+=@('--dry-run')}
$ErrorActionPreference='Continue';$out=& $SupabaseCli @argsLocal 2>&1;$code=$LASTEXITCODE;$ErrorActionPreference='Stop'
if($code -ne 0){throw ('Migration failed; inspect before retry: '+($out|Out-String))}
$out|ForEach-Object {$_.ToString()}
if(!$DryRun){& "$PSScriptRoot/Configure-ChatRealtimeLocal.ps1"}
