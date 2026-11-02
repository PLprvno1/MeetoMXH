# One bounded pass. Local operator SQL + local Storage HTTP only. No persistent service.
param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",
 [Parameter(Mandatory=$true)][string]$FfmpegDirectory,[int]$BatchSize=20,[switch]$TestDeleteFailure)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/PhaseB-Local.ps1"
Assert-MeetoLocal
if($BatchSize -lt 1 -or $BatchSize -gt 100){throw 'Invalid batch size'}
$ffmpeg=Join-Path $FfmpegDirectory 'ffmpeg.exe'; $ffprobe=Join-Path $FfmpegDirectory 'ffprobe.exe'
if(!(Test-Path $ffmpeg) -or !(Test-Path $ffprobe)){throw 'Provide verified FFmpeg/ffprobe binaries'}
$state=Get-MeetoLocalState $SupabaseCli
$headers=@{apikey=$state.ANON_KEY;Authorization=('Bearer '+$state.SERVICE_ROLE_KEY)}
$api='http://127.0.0.1:54321/storage/v1'
$summary=[ordered]@{verified=0;rejected=0;deleted=0;retry=0;dead=0}
function Run-MediaTool([string]$exe,[string]$arguments){
 $p=New-Object Diagnostics.Process
 $p.StartInfo.FileName=$exe;$p.StartInfo.Arguments=$arguments;$p.StartInfo.UseShellExecute=$false
 $p.StartInfo.CreateNoWindow=$true;$p.StartInfo.RedirectStandardOutput=$true;$p.StartInfo.RedirectStandardError=$true
 try{
  $null=$p.Start();$stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
  if(!$p.WaitForExit(30000)){$p.Kill();throw 'media_decode_timeout'}
  return @{code=$p.ExitCode;output=$stdout.Result;errors=$stderr.Result}
 }finally{$p.Dispose()}
}
$rows=Invoke-MeetoSql "SELECT coalesce(json_agg(q),'[]') FROM (SELECT a.id,a.bucket_id,a.object_path,a.mime_type,o.id AS object_id FROM public.media_assets a JOIN storage.objects o ON o.bucket_id=a.bucket_id AND o.name=a.object_path WHERE a.state='pending' AND a.created_at>now()-interval '15 minutes' AND a.metadata->>'verified' IS DISTINCT FROM 'true' ORDER BY a.created_at,a.id LIMIT $BatchSize) q;"
$assets=($rows -join '')|ConvertFrom-Json
foreach($a in $assets){
 $file=Join-Path $env:TEMP ('MeetoPhase06-media-'+[guid]::NewGuid().ToString('N')+'.bin')
 try{
  try{Invoke-WebRequest -UseBasicParsing -Uri ($api+'/object/authenticated/'+$a.bucket_id+'/'+$a.object_path) -Headers $headers -OutFile $file -TimeoutSec 30 | Out-Null}catch{throw 'media_download_failed'}
  $length=(Get-Item -LiteralPath $file).Length
  $max=if($a.mime_type -eq 'video/mp4'){52428800}else{10485760}
  if($length -le 0 -or $length -gt $max){throw 'media_size_rejected'}
  $bytes=[IO.File]::ReadAllBytes($file)
  $magic=([BitConverter]::ToString($bytes,0,[Math]::Min(16,$bytes.Length))).Replace('-','')
  $magicOk=switch($a.mime_type){
   'image/png' {$magic.StartsWith('89504E470D0A1A0A')}
   'image/jpeg' {$magic.StartsWith('FFD8FF')}
   'image/webp' {$bytes.Length -ge 12 -and [Text.Encoding]::ASCII.GetString($bytes,0,4) -eq 'RIFF' -and [Text.Encoding]::ASCII.GetString($bytes,8,4) -eq 'WEBP'}
   'video/mp4' {$bytes.Length -ge 12 -and [Text.Encoding]::ASCII.GetString($bytes,4,4) -eq 'ftyp'}
   default {$false}
  }
  if(!$magicOk){throw 'media_magic_rejected'}
  $probe=Run-MediaTool $ffprobe ('-v error -protocol_whitelist file -show_streams -show_format -of json "'+$file+'"')
  if($probe.code -ne 0){throw 'media_probe_rejected'}
  $info=$probe.output|ConvertFrom-Json;$video=@($info.streams|Where-Object codec_type -eq 'video')
  if($video.Count -ne 1 -or $video[0].width -le 0 -or $video[0].height -le 0 -or ([long]$video[0].width*$video[0].height) -gt 40000000){throw 'media_dimensions_rejected'}
  $duration=0
  if($a.mime_type -eq 'video/mp4'){
   $duration=[double]::Parse([string]$info.format.duration,[Globalization.CultureInfo]::InvariantCulture)
   if($duration -le 0 -or $duration -gt 300 -or $video[0].codec_name -notin @('h264','hevc','av1','mpeg4')){throw 'media_video_rejected'}
  }
  # Full decode, fail on corrupt bytes. Network protocols disabled; bounded process time.
  $decode=Run-MediaTool $ffmpeg ('-v error -xerror -threads 1 -protocol_whitelist file -i "'+$file+'" -map 0:v:0 -f null -')
  if($decode.code -ne 0){throw 'media_decode_rejected'}
  $sha=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
  $dur=$duration.ToString([Globalization.CultureInfo]::InvariantCulture)
  $null=Invoke-MeetoSql "SELECT private.record_media_verification('$($a.id)','$($a.object_id)',$length,'$sha',$($video[0].width),$($video[0].height),$dur);"
  $summary.verified++
 }catch{
  # Download/operational failures retry next pass. Invalid bytes retire immediately.
  if($_.Exception.Message -eq 'media_download_failed'){$summary.retry++}
  elseif($_.Exception.Message -match '^media_(size|magic|probe|dimensions|video|decode)_rejected$|^media_decode_timeout$'){
   $null=Invoke-MeetoSql "SELECT private.retire_asset('$($a.id)');";$summary.rejected++
  }else{throw 'Media verification worker operational failure (details suppressed)'}
 }finally{if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file}}
}
$null=Invoke-MeetoSql 'SELECT private.schedule_media_cleanup();'
$jobs=Invoke-MeetoSql @"
WITH batch AS (SELECT e.id FROM public.event_outbox e JOIN public.media_assets a ON a.id=e.aggregate_id WHERE e.event_type='media.retire' AND (e.state='pending' OR (e.state='processing' AND e.lease_until<now())) AND e.available_at<=now() AND e.attempts<5 AND a.state='pending_delete' AND coalesce(a.delete_after,now())<=now() ORDER BY e.available_at,e.id LIMIT $BatchSize FOR UPDATE OF e SKIP LOCKED), claimed AS
(UPDATE public.event_outbox e SET state='processing',attempts=attempts+1,lease_until=now()+interval '2 minutes' FROM batch b WHERE e.id=b.id RETURNING e.*)
SELECT coalesce(json_agg(q),'[]') FROM (SELECT c.id,c.attempts,c.lease_until,a.id AS asset_id,a.bucket_id,a.object_path FROM claimed c JOIN public.media_assets a ON a.id=c.aggregate_id WHERE a.state='pending_delete' AND coalesce(a.delete_after,now())<=now()) q;
"@
foreach($job in (($jobs -join '')|ConvertFrom-Json)){
 $success=$false
 try{
  $body=@{prefixes=@($job.object_path)}|ConvertTo-Json -Compress
  if($TestDeleteFailure){throw 'local_worker_fault_injection'}
  $null=Invoke-WebRequest -UseBasicParsing -Method Delete -Uri ($api+'/object/'+$job.bucket_id) -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 30
  $success=$true
 }catch{if($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404){$success=$true}}
 if($success){
  $null=Invoke-MeetoSql "WITH owned AS (UPDATE public.event_outbox SET state='done',processed_at=now(),lease_until=NULL WHERE id='$($job.id)' AND state='processing' AND attempts=$($job.attempts) RETURNING aggregate_id) UPDATE public.media_assets SET state='deleted',metadata='{}' WHERE id IN (SELECT aggregate_id FROM owned) AND state='pending_delete';"
  $summary.deleted++
 }else{
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET state=CASE WHEN attempts>=5 THEN 'dead' ELSE 'pending' END,available_at=now()+make_interval(secs=>least(3600,30*power(2,attempts)::integer)),lease_until=NULL,payload=jsonb_build_object('last_error','storage_delete_failed') WHERE id='$($job.id)' AND state='processing' AND attempts=$($job.attempts);"
  $summary.retry++;if($job.attempts -ge 5){$summary.dead++}
 }
}
[pscustomobject]$summary
