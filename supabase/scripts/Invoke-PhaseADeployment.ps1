param(
 [ValidateSet('Local','Staging')][string]$Environment='Local',
 [string]$SupabaseCli='supabase',
 [string]$ExpectedStagingRef,
 [switch]$Apply
)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Test-MigrationManifest.ps1')
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $workspace
try {
 if($Environment -eq 'Staging'){
  if($ExpectedStagingRef -notmatch '^[a-z]{20}$'){throw 'An explicitly confirmed staging project ref is required'}
  $link=Join-Path $workspace 'supabase/.temp/project-ref'
  if(!(Test-Path -LiteralPath $link) -or (Get-Content -LiteralPath $link -Raw).Trim() -ne $ExpectedStagingRef){throw 'Linked project differs from confirmed staging ref'}
  # User performs CLI login/link directly. Never take secrets as command arguments here.
  & $SupabaseCli migration list --linked
  if($LASTEXITCODE -ne 0){throw 'Remote ledger inspection failed'}
  & $SupabaseCli db push --linked --dry-run --skip-vault
  if($LASTEXITCODE -ne 0){throw 'Remote migration preflight failed'}
  if($Apply){& $SupabaseCli db push --linked --skip-vault}
 } else {
  & $SupabaseCli db push --local --dry-run --skip-vault
  if($LASTEXITCODE -ne 0){throw 'Local migration preflight failed'}
  if($Apply){& $SupabaseCli db push --local --skip-vault}
 }
 if($LASTEXITCODE -ne 0){throw 'CLI migration command failed'}
} finally {Pop-Location}
