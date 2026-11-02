param([string]$ManifestPath=(Join-Path $PSScriptRoot '../migrations.sha256.json'))
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../migrations'))
$manifest=Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$files=@(Get-ChildItem -LiteralPath $root -Filter *.sql | Sort-Object Name)
if($files.Count -ne @($manifest).Count){throw 'Migration manifest file count mismatch'}
$names=@($manifest | ForEach-Object {$_.file})
if(@($names | Sort-Object -Unique).Count -ne $names.Count){throw 'Duplicate migration manifest filename'}
if(Compare-Object @($files.Name | Sort-Object) @($names | Sort-Object)){throw 'Migration manifest file set mismatch'}
foreach($entry in $manifest){
 if($entry.file -notmatch '^\d{14}_[a-z_]+\.sql$'){throw 'Invalid migration filename'}
 $path=Join-Path $root $entry.file
 if(!(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $entry.sha256){
  throw ('Migration checksum mismatch: '+$entry.file)
 }
}
Write-Output ('Verified migration checksums: '+$files.Count)
