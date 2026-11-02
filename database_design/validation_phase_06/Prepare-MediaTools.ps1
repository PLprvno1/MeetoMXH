# Isolated temporary tooling, no PATH/package/Android changes. Pinned publisher checksum.
param([string]$Destination=(Join-Path $env:TEMP 'MeetoPhase06-tools'))
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($Destination)
if($root -ne [IO.Path]::GetFullPath((Join-Path $env:TEMP 'MeetoPhase06-tools'))){throw 'Use the fixed temporary tool directory'}
New-Item -ItemType Directory -Force $root|Out-Null
$archive=Join-Path $root 'ffmpeg.7z'
$sha='4705843ccaaf54257c16ad90f3e952ece33c17df964ecf7bfdbb0f49c7171077'
if(!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive).Hash -ne $sha){
 & curl.exe -L --fail --max-time 600 --silent --show-error 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.7z' -o $archive
 if($LASTEXITCODE -ne 0){throw 'Media tool download failed'}
}
if((Get-FileHash -LiteralPath $archive).Hash -ne $sha){throw 'Media tool publisher SHA-256 mismatch'}
& tar.exe -xf $archive -C $root
if($LASTEXITCODE -ne 0){throw 'Media tool extraction failed'}
Join-Path $root 'ffmpeg-9.0.2-essentials_build/bin'
