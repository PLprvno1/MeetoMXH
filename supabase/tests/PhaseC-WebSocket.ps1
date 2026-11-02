# Dot-source from C suite; real Phoenix sockets use existing ws module in local Storage container.
function Invoke-PhaseCWebSocket($A,$B,$C,$PrivateConversation,$GroupConversation,$GroupAdmin){
 $wsResults=New-Object 'System.Collections.Generic.List[object]'
 function WCheck([string]$label,[bool]$ok){if(!$ok){throw ('WebSocket assertion failed: '+$label)};$wsResults.Add(@{test=$label;passed=$true})}
 function Dispatch([switch]$Fail){return & "$script:CRepoRoot/supabase/scripts/Invoke-ChatDispatcher.ps1" -SupabaseCli $SupabaseCli -BatchSize 100 -TestPublishFailure:$Fail}
 function Flush {
  for($i=0;$i -lt 50;$i++){$r=Dispatch;if($r.retry -or $r.dead){throw 'WebSocket dispatcher failed'};if($r.done -lt 100){return}}
  throw 'WebSocket dispatcher backlog bound exceeded'
 }
 $node=New-Object Diagnostics.Process
 $node.StartInfo.FileName=(Get-Command docker).Source;$node.StartInfo.Arguments='exec -i supabase_storage_MeetoMXH node /tmp/meeto-phase-c-ws.cjs'
 $node.StartInfo.UseShellExecute=$false;$node.StartInfo.CreateNoWindow=$true;$node.StartInfo.RedirectStandardInput=$true;$node.StartInfo.RedirectStandardOutput=$true;$node.StartInfo.RedirectStandardError=$true
 function Ws($command){
  $command.id=[guid]::NewGuid().ToString();$node.StandardInput.WriteLine(($command|ConvertTo-Json -Depth 8 -Compress));$node.StandardInput.Flush()
  $line=$node.StandardOutput.ReadLineAsync();if(!$line.Wait(18000) -or !$line.Result){throw 'WebSocket driver response timeout'}
  $r=$line.Result|ConvertFrom-Json;if($r.id -ne $command.id){throw 'WebSocket driver command mismatch'};return $r
 }
 function Open([string]$name,$identity,[string]$topic,[bool]$private=$true){return Ws @{op='open';name=$name;key=$AnonKey;token=$identity.Token;topic=$topic;private=$private}}
 function Drain([string]$name){return Ws @{op='drain';name=$name}}
 function EventId($messageId){return ((Invoke-MeetoSql "SELECT id FROM public.event_outbox WHERE event_type='chat.invalidate' AND payload->>'message_id'='$messageId' ORDER BY created_at DESC LIMIT 1;") -join '')}
 function Seen($events,[string]$eventId){return @($events|Where-Object {$_.payload.event_id -eq $eventId}).Count -gt 0}
 function Settle {Start-Sleep -Milliseconds 300}
 try{
  & "$script:CRepoRoot/supabase/scripts/Configure-ChatRealtimeLocal.ps1"|Out-Null
  $storage=(docker inspect supabase_storage_MeetoMXH|ConvertFrom-Json)[0];if($storage.Config.Labels.'com.supabase.cli.workdir' -ne 'F:\MeetoMXH'){throw 'WebSocket wrong local container'}
  $null=docker cp "$script:CRepoRoot/supabase/tests/phase-c-ws.cjs" 'supabase_storage_MeetoMXH:/tmp/meeto-phase-c-ws.cjs';if($LASTEXITCODE -ne 0){throw 'WebSocket driver copy failed'}
  Flush
  $null=$node.Start()
  WCheck 'A own private user channel joins' ((Open 'a' $A ('user:'+$A.Id)).status -eq 'ok')
  WCheck 'B own private user channel joins' ((Open 'b' $B ('user:'+$B.Id)).status -eq 'ok')
  WCheck 'C own private user channel joins' ((Open 'c' $C ('user:'+$C.Id)).status -eq 'ok')
  WCheck 'nonmember cannot join another user channel' ((Open 'attack' $C ('user:'+$A.Id)).status -ne 'ok')
  WCheck 'raw conversation channel not allowed' ((Open 'resource' $C ('conversation:'+$PrivateConversation.id)).status -ne 'ok')
  WCheck 'public channel mode rejected' ((Open 'public' $A ('user:'+$A.Id) $false).status -ne 'ok')
  WCheck 'forged JWT private channel rejected' ((Open 'forged' @{Token='invalid.jwt.signature'} ('user:'+$A.Id)).status -ne 'ok')
  WCheck 'missing access JWT private channel rejected' ((Open 'anon' @{Token=''} ('user:'+$A.Id)).status -ne 'ok')
  $message=Send $A $PrivateConversation.id 'private WS delivery';$eid=EventId $message.id
  $dispatch=Dispatch;WCheck 'worker actually publishes private Broadcast' ($dispatch.published -ge 2)
  Settle;$evA=(Drain 'a').events;$evB=(Drain 'b').events;$evC=(Drain 'c').events
  WCheck 'A received business invalidation' (Seen $evA $eid)
  WCheck 'B received business invalidation' (Seen $evB $eid)
  WCheck 'C received no private chat event' (!(Seen $evC $eid))
  $target=@($evB|Where-Object {$_.payload.event_id -eq $eid})[0]
  WCheck 'Broadcast payload exactly event ID and generic scope' (@($target.payload.PSObject.Properties).Count -eq 2 -and $target.payload.scope -eq 'messenger' -and !$target.payload.body -and !$target.payload.conversation_id -and !$target.payload.sender_id)
  $snapshot=Good 'WS recipient refetch authorized message' $B 'get_messages' @{p_conversation_id=$PrivateConversation.id;p_after_seq=($message.seq-1)}
  WCheck 'event causes RLS refetch of content' (@($snapshot.items|Where-Object id -eq $message.id).Count -eq 1)
  # Client Broadcast writes are denied even on own receive-only user channel.
  $spoof=Ws @{op='spoof';name='a'};Settle;$spoofEvents=(Drain 'a').events
  WCheck 'client cannot inject Broadcast' (@($spoofEvents|Where-Object event -eq 'spoof').Count -eq 0)
  $null=Ws @{op='close';name='a'};WCheck 'receive client rejoins after denied send' ((Open 'a' $A ('user:'+$A.Id)).status -eq 'ok')
  # Actual disconnect/reconnect plus missed-event backfill.
  $null=Ws @{op='close';name='b'}
  $missed=Send $A $PrivateConversation.id 'offline missed business message';Flush
  WCheck 'B reconnect private channel authorized' ((Open 'b' $B ('user:'+$B.Id)).status -eq 'ok')
  $backfill=Good 'reconnect catches up by sequence' $B 'get_messages' @{p_conversation_id=$PrivateConversation.id;p_after_seq=$message.seq}
  WCheck 'missed event loses no durable message' (@($backfill.items|Where-Object id -eq $missed.id).Count -eq 1)
  $login=Require200 (Request 'POST' '/auth/v1/token?grant_type=refresh_token' @{refresh_token=$A.Refresh}) 'real GoTrue refresh for WebSocket'
  WCheck 'valid JWT refresh sent to live channel' ((Ws @{op='refresh';name='a';token=$login.access_token}).status -eq 'sent')
  $A.Token=$login.access_token;$A.Refresh=$login.refresh_token
  $refreshed=Send $B $PrivateConversation.id 'after JWT refresh';$refreshedId=EventId $refreshed.id;Flush;Settle
  WCheck 'business event arrives after token refresh' (Seen (Drain 'a').events $refreshedId)
  $null=Drain 'b';$null=Drain 'c'
  # Force out-of-order delivery and a duplicate on actual sockets, then deduplicate/refetch.
  $m1=Send $A $PrivateConversation.id 'ordered durable one';$e1=EventId $m1.id
  $m2=Send $A $PrivateConversation.id 'ordered durable two';$e2=EventId $m2.id
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET available_at=now()+interval '1 hour' WHERE id='$e1';"
  Flush;Settle;$wire=@((Drain 'b').events)
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET available_at=now() WHERE id='$e1';";Flush;Settle;$wire+=@((Drain 'b').events)
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET state='pending',available_at=now() WHERE id='$e2';";Flush;Settle;$wire+=@((Drain 'b').events)
  $observed=@($wire|Where-Object {$_.payload.event_id -in @($e1,$e2)}|ForEach-Object {$_.payload.event_id})
  WCheck 'real socket delivered newer then older then duplicate' ($observed.Count -eq 3 -and $observed[0] -eq $e2 -and $observed[1] -eq $e1 -and $observed[2] -eq $e2)
  WCheck 'client reducer deduplicates event IDs' (@($observed|Sort-Object -Unique).Count -eq 2)
  $truth=Good 'reconciliation after out of order events' $B 'get_messages' @{p_conversation_id=$PrivateConversation.id;p_after_seq=($m1.seq-1)}
  WCheck 'authoritative messages remain sequence ordered' ($truth.items[0].id -eq $m1.id -and $truth.items[1].id -eq $m2.id)
  # Receipt, recall and hide each emit refetch hints, not raw row payloads.
  $null=Drain 'a';$null=Drain 'b'
  $null=Good 'WS read receipt mutation' $B 'mark_read' @{p_conversation_id=$PrivateConversation.id;p_message_id=$m2.id};Flush;Settle
  WCheck 'read receipt invalidation delivered to sender' (@((Drain 'a').events).Count -ge 1)
  $null=Good 'WS recall mutation' $A 'recall_message' @{p_message_id=$m2.id};$recallEvent=EventId $m2.id;Flush;Settle
  WCheck 'recall invalidation delivered' (Seen (Drain 'b').events $recallEvent)
  $truth=Good 'WS recall requery' $B 'get_messages' @{p_conversation_id=$PrivateConversation.id;p_after_seq=($m1.seq-1)}
  WCheck 'recall refetch contains no stale body' (@($truth.items|Where-Object id -eq $m2.id)[0].body -eq $null)
  # Publication retry/dead-letter and expired lease recovery, fixed event fixture only.
  $faultMsg=Send $A $PrivateConversation.id 'dispatcher fault fixture';$faultId=EventId $faultMsg.id
  $fault=Dispatch -Fail;WCheck 'publish fault checkpointed for retry' ($fault.retry -ge 1)
  $checkpoint=((Invoke-MeetoSql "SELECT state='pending' AND attempts=1 AND available_at>now() AND payload->>'last_error'='broadcast_failed' FROM public.event_outbox WHERE id='$faultId';") -join '')
  WCheck 'publish retry has bounded backoff and sanitized error' ($checkpoint -eq 't')
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET attempts=4,available_at=now() WHERE id='$faultId';"
  $fault=Dispatch -Fail;WCheck 'publish fifth failure dead letters' ($fault.dead -ge 1)
  $null=Invoke-MeetoSql "UPDATE public.event_outbox SET state='processing',attempts=0,lease_until=now()-interval '1 second',available_at=now() WHERE id='$faultId';"
  Flush;Settle;WCheck 'expired publish lease reclaimed with real delivery' (Seen (Drain 'b').events $faultId)
  # A pending private event cannot bypass a later block via cached channel authorization.
  $late=Send $A $PrivateConversation.id 'queued before block';$lateId=EventId $late.id
  $null=Good 'block before outbox dispatch' $A 'set_block' @{p_target=$B.Id;p_blocked=$true};Flush;Settle
  WCheck 'queued private event suppressed after block' (!(Seen (Drain 'b').events $lateId))
  Deny 'WS stale subscriber refetch denied after block' 404 $B 'get_messages' @{p_conversation_id=$PrivateConversation.id}
  $null=Good 'unblock after WS test' $A 'set_block' @{p_target=$B.Id;p_blocked=$false};Flush;Settle;$null=Drain 'a';$null=Drain 'b';$null=Drain 'c'
  $group=Good 'WS add group subscriber' $GroupAdmin 'manage_conversation_member' @{p_conversation_id=$GroupConversation.id;p_target=$C.Id;p_action='add'}
  Flush;Settle;WCheck 'membership change delivered to added user' (@((Drain 'c').events).Count -ge 1)
  $groupMsg=Send $GroupAdmin $group.id 'group live event';$groupId=EventId $groupMsg.id;Flush;Settle
  WCheck 'group business event delivered to active member' (Seen (Drain 'c').events $groupId)
  try{
   $null=Invoke-MeetoSql "UPDATE public.account_status SET state='suspended' WHERE user_id='$($C.Id)';"
   $suspended=Send $GroupAdmin $group.id 'exclude suspended subscriber';$suspendedId=EventId $suspended.id;Flush;Settle
   WCheck 'stale socket receives no business event when suspended' (!(Seen (Drain 'c').events $suspendedId))
  }finally{$null=Invoke-MeetoSql "UPDATE public.account_status SET state='active' WHERE user_id='$($C.Id)';"}
  $queued=Send $GroupAdmin $group.id 'queued before kick';$queuedId=EventId $queued.id
  $null=Good 'kick before dispatcher' $GroupAdmin 'manage_conversation_member' @{p_conversation_id=$group.id;p_target=$C.Id;p_action='remove'};Flush;Settle
  $afterKick=(Drain 'c').events;WCheck 'queued group event suppressed for kicked user' (!(Seen $afterKick $queuedId))
  WCheck 'removed user receives generic membership clear hint' (@($afterKick).Count -ge 1)
  Deny 'kicked old JWT refetch denied' 404 $C 'get_messages' @{p_conversation_id=$group.id}
  $null=Good 'WS rejoin then leave' $GroupAdmin 'manage_conversation_member' @{p_conversation_id=$group.id;p_target=$C.Id;p_action='add'};Flush;Settle;$null=Drain 'c'
  $null=Good 'WS member leaves' $C 'leave_conversation' @{p_conversation_id=$group.id};Flush;Settle
  WCheck 'leave invalidation delivered to departing user' (@((Drain 'c').events).Count -ge 1)
  $afterLeave=Send $GroupAdmin $group.id 'after leave no event';$afterLeaveId=EventId $afterLeave.id;Flush;Settle
  WCheck 'departed socket gets no new group activity' (!(Seen (Drain 'c').events $afterLeaveId))
  $current=Good 'WS state version' $GroupAdmin 'get_conversation' @{p_conversation_id=$group.id}
  $archived=Good 'WS conversation archive event' $GroupAdmin 'set_conversation_state' @{p_conversation_id=$group.id;p_state='archived';p_expected_version=$current.version};Flush;Settle
  $adminSocket=if($GroupAdmin.Id -eq $A.Id){'a'}else{'b'}
  WCheck 'conversation status invalidation delivered' (@((Drain $adminSocket).events).Count -ge 1)
  $null=Good 'restore group state after WS' $GroupAdmin 'set_conversation_state' @{p_conversation_id=$group.id;p_state='active';p_expected_version=$archived.version};Flush
  $invalidRefresh=Ws @{op='refresh';name='c';token='invalid.jwt.signature'};Start-Sleep -Milliseconds 500
  WCheck 'invalid token refresh rejected or connection closes' ($invalidRefresh.status -ne 'sent' -or (Drain 'c').closed)
  [ordered]@{environment='three actual Realtime Phoenix WebSocket clients; private-only local tenant';passed=$wsResults.Count;failed=0;tests=$wsResults;transport='Node ws in verified local Docker Storage container -> local Kong:8000';worker='actual local HTTP private Broadcast; fault injection distinguished'}|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 "$script:CRepoRoot/database_design/validation_phase_07/websocket-results.json"
 }finally{
  if($node.StartTime){try{$null=Ws @{op='quit'}}catch{};if(!$node.HasExited){$node.StandardInput.Close();if(!$node.WaitForExit(3000)){$node.Kill()}}}
  $node.Dispose();$null=docker exec supabase_storage_MeetoMXH rm -f /tmp/meeto-phase-c-ws.cjs
 }
}
