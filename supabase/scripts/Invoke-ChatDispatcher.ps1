# One local bounded pass. No keys in arguments/logs, no private bodies in Broadcast.
param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[int]$BatchSize=100,[switch]$TestPublishFailure)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/PhaseB-Local.ps1"
Assert-MeetoLocal
if($BatchSize -lt 1 -or $BatchSize -gt 100){throw 'Invalid dispatcher batch size'}
$s=Get-MeetoLocalState $SupabaseCli
$gate=Invoke-MeetoSql "SELECT private_only FROM _realtime.tenants WHERE external_id='realtime-dev';"
if(($gate -join '') -ne 't'){throw 'Configure private-only Realtime before dispatch'}
$jobs=Invoke-MeetoSql @"
WITH batch AS (SELECT id FROM public.event_outbox WHERE event_type='chat.invalidate' AND (state='pending' OR (state='processing' AND lease_until<now())) AND attempts<5 AND available_at<=now() ORDER BY available_at,id LIMIT $BatchSize FOR UPDATE SKIP LOCKED), claimed AS
(UPDATE public.event_outbox e SET state='processing',attempts=attempts+1,lease_until=now()+interval '2 minutes' FROM batch b WHERE e.id=b.id RETURNING e.id,e.attempts)
SELECT coalesce(json_agg(c),'[]') FROM claimed c;
"@
$result=[ordered]@{done=0;published=0;retry=0;dead=0}
foreach($job in (($jobs -join '')|ConvertFrom-Json)){
 $recipients=((Invoke-MeetoSql "SELECT private.chat_dispatch_recipients('$($job.id)');") -join '')|ConvertFrom-Json
 $messages=@(foreach($uid in $recipients){
  if([string]$uid -notmatch '^[0-9a-f-]{36}$'){throw 'Invalid dispatch recipient'}
  @{topic=('user:'+$uid);event='invalidate';private=$true;payload=@{event_id=$job.id;scope='messenger'}}
 })
 $ok=$true
 if($messages.Count){
  try{
   if($TestPublishFailure){throw 'local_publish_fault_injection'}
   $body=@{messages=$messages}|ConvertTo-Json -Depth 6 -Compress
   $r=Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:54321/realtime/v1/api/broadcast' -Headers @{apikey=$s.SERVICE_ROLE_KEY;Authorization=('Bearer '+$s.SERVICE_ROLE_KEY)} -ContentType 'application/json' -Body $body -TimeoutSec 15
   if([int]$r.StatusCode -notin @(200,202,204)){throw 'Broadcast not accepted'}
   $result.published+=$messages.Count
  }catch{$ok=$false}
 }
 if($ok){
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET state='done',lease_until=NULL,processed_at=now() WHERE id='$($job.id)' AND state='processing' AND attempts=$($job.attempts);"
  $result.done++
 }else{
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET state=CASE WHEN attempts>=5 THEN 'dead' ELSE 'pending' END,lease_until=NULL,available_at=now()+make_interval(secs=>least(3600,30*power(2,attempts)::integer)),payload=payload||jsonb_build_object('last_error','broadcast_failed') WHERE id='$($job.id)' AND state='processing' AND attempts=$($job.attempts);"
  $result.retry++;if($job.attempts -ge 5){$result.dead++}
 }
}
[pscustomobject]$result
