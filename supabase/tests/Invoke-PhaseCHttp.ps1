param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",[Parameter(Mandatory=$true)][string]$FfmpegDirectory,[switch]$WithWebSocket)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/../scripts/PhaseB-Local.ps1"
$state=Get-MeetoLocalState $SupabaseCli;$AnonKey=$state.ANON_KEY;$api='http://127.0.0.1:54321';$mail='http://127.0.0.1:54324'
$results=New-Object 'System.Collections.Generic.List[object]'
$script:CRepoRoot=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
function Import-Helpers([string]$file,[string[]]$names){
 $tok=$null;$err=$null;$tree=[Management.Automation.Language.Parser]::ParseFile($file,[ref]$tok,[ref]$err)
 foreach($name in $names){$fn=$tree.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true);if(!$fn){throw 'Missing shared test helper'}; . ([scriptblock]::Create($fn.Extent.Text));Set-Item -Path ('function:script:'+$name) -Value (Get-Item ('function:'+$name)).ScriptBlock}
}
Import-Helpers "$PSScriptRoot/Invoke-AuthProfileHttp.ps1" @('Check','Request','Require200','Rpc','Confirm-Mail','New-TestIdentity')
Import-Helpers "$PSScriptRoot/Invoke-PhaseBHttp.ps1" @('Good','Deny','Id','Upload','Download','Worker')
function Worker {return & "$script:CRepoRoot/supabase/scripts/Invoke-MediaWorker.ps1" -SupabaseCli $SupabaseCli -FfmpegDirectory $FfmpegDirectory -BatchSize 100}
# Real HTTP race helper, only the operator barrier and permitted serial outcomes change.
$tok=$null;$err=$null;$tree=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/Invoke-PhaseBHttp.ps1",[ref]$tok,[ref]$err)
$race=$tree.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Race'},$true).Extent.Text
$race=$race.Replace('BEGIN; SELECT pg_advisory_xact_lock(6061101); SELECT ''ready'';','BEGIN; $script:ChatBarrier; SELECT ''ready'';')
$race=$race.Replace("if(`$label -eq 'delete versus comment')", "if(`$label -in @('send and block','send and leave','send and join')){`$valid=`$statuses[0] -in @(200,404) -and `$statuses[1] -eq 200}; if(`$label -eq 'delete versus comment')")
. ([scriptblock]::Create($race))
function Barrier($cid){$script:ChatBarrier="SELECT id FROM public.conversations WHERE id='$cid' FOR UPDATE"}
function Pair-Barrier($a,$b){$script:ChatBarrier="SELECT pg_advisory_xact_lock(hashtextextended(least('$($a.Id)'::uuid,'$($b.Id)'::uuid)::text||':'||greatest('$($a.Id)'::uuid,'$($b.Id)'::uuid)::text,0))"}
function Send($u,$cid,[string]$body='synthetic chat text'){return Good 'send text' $u 'send_message' @{p_conversation_id=$cid;p_client_request_id=(Id);p_kind='text';p_body=$body;p_assets=@();p_reply_to=$null}}
function Friend($u,$v){$r=Good 'friend invitation prerequisite' $u 'send_friend_request' @{p_recipient=$v.Id;p_client_request_id=(Id)};$null=Good 'friend accept prerequisite' $v 'accept_friend_request' @{p_request_id=$r.id}}
function No-Rows([string]$label,$u,[string]$path){$r=Request 'GET' $path $null $u.Token;Check $label ($r.Status -eq 200 -and ($null -eq $r.Data -or @($r.Data).Count -eq 0))}
function Independent-Conversations($identity,$blockedId,$freeId){
 Add-Type -AssemblyName System.Net.Http
 $p=New-Object Diagnostics.Process;$p.StartInfo.FileName=(Get-Command docker).Source
 $p.StartInfo.Arguments='exec -i supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atq -v ON_ERROR_STOP=1'
 $p.StartInfo.UseShellExecute=$false;$p.StartInfo.CreateNoWindow=$true;$p.StartInfo.RedirectStandardInput=$true;$p.StartInfo.RedirectStandardOutput=$true;$p.StartInfo.RedirectStandardError=$true
 $http=New-Object Net.Http.HttpClient;$http.Timeout=[TimeSpan]::FromSeconds(20)
 try{
  $null=$p.Start();$p.StandardInput.WriteLine("BEGIN; SELECT pg_advisory_xact_lock(6061101); SELECT id FROM public.conversations WHERE id='$blockedId' FOR UPDATE; SELECT 'ready';");$p.StandardInput.Flush()
  do{$line=$p.StandardOutput.ReadLineAsync();if(!$line.Wait(10000)){throw 'race barrier timeout'}}while($line.Result -ne 'ready')
  $jobs=@()
  foreach($cid in @($blockedId,$freeId)){
   $req=New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Post,($api+'/rest/v1/rpc/send_message'))
   $req.Headers.Add('apikey',$AnonKey);$req.Headers.Authorization=New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$identity.Token)
   $payload=@{p_conversation_id=$cid;p_client_request_id=(Id);p_kind='text';p_body='independent conversation lock probe';p_assets=@()}|ConvertTo-Json -Compress
   $req.Content=New-Object Net.Http.StringContent($payload,[Text.Encoding]::UTF8,'application/json');$jobs+=@($http.SendAsync($req))
  }
  Check 'same actor sends to unrelated conversation while B global and other parent held' ($jobs[1].Wait(4000) -and [int]$jobs[1].Result.StatusCode -eq 200 -and !$jobs[0].IsCompleted)
  $p.StandardInput.WriteLine('COMMIT;');$p.StandardInput.Close()
  Check 'blocked parent send succeeds after row lock release' ([int]$jobs[0].GetAwaiter().GetResult().StatusCode -eq 200)
 }finally{if(!$p.HasExited){$p.StandardInput.Close();if(!$p.WaitForExit(2000)){$p.Kill()}};$p.Dispose();$http.Dispose()}
}
$temp=Join-Path $env:TEMP ('MeetoPhase07-fixtures-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory $temp|Out-Null
try{
 $a=New-TestIdentity 'C synthetic A';$b=New-TestIdentity 'C synthetic B';$c=New-TestIdentity 'C synthetic C';$d=New-TestIdentity 'C synthetic D';$incomplete=New-TestIdentity 'C incomplete' $false
 $ab=Good 'private create' $a 'get_or_create_private' @{p_peer=$b.Id};$ab2=Good 'reverse private lookup' $b 'get_or_create_private' @{p_peer=$a.Id}
 Check 'same canonical private conversation' ($ab.id -eq $ab2.id)
 Deny 'self private denied' 422 $a 'get_or_create_private' @{p_peer=$a.Id}
 Deny 'guessed conversation' 404 $c 'get_conversation' @{p_conversation_id=$ab.id}
 No-Rows 'nonmember raw conversations filtered' $c ('/rest/v1/conversations?select=id&id=eq.'+$ab.id)
 Deny 'nonmember send' 404 $c 'send_message' @{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body='forbidden';p_assets=@()}
 $one=Send $a $ab.id 'alpha searchable';$two=Send $b $ab.id 'beta searchable'
 Check 'server sequence strictly increases' ($one.seq -eq 1 -and $two.seq -eq 2)
 $initialReceipts=Good 'commit is not delivery' $a 'get_read_receipts' @{p_conversation_id=$ab.id;p_message_id=$one.id}
 Check 'receiver not delivered until ACK' (!$initialReceipts[0].delivered -and !$initialReceipts[0].read)
 $null=Good 'device delivery ACK' $b 'acknowledge_delivery' @{p_conversation_id=$ab.id;p_message_id=$one.id}
 $receipt=Good 'delivery status' $a 'get_read_receipts' @{p_conversation_id=$ab.id;p_message_id=$one.id};Check 'delivery is not read' ($receipt[0].delivered -and !$receipt[0].read)
 $null=Good 'mark actual message read' $b 'mark_read' @{p_conversation_id=$ab.id;p_message_id=$one.id}
 $receipt=Good 'read receipt visible to sender' $a 'get_read_receipts' @{p_conversation_id=$ab.id;p_message_id=$one.id};Check 'read also implies delivery marker' ($receipt[0].read -and $receipt[0].delivered)
 $reply=Good 'reply same conversation' $b 'send_message' @{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body='reply alpha';p_assets=@();p_reply_to=$one.id}
 Check 'reply preview authorized' ($reply.reply.body -eq 'alpha searchable')
 $partial=Good 'literal substring search preserves Android contract' $a 'search_messages' @{p_conversation_id=$ab.id;p_query='SEARCHAB'};Check 'case insensitive partial word matches' (@($partial.items).Count -eq 2)
 Deny 'null search invalid' 422 $a 'search_messages' @{p_conversation_id=$ab.id;p_query=$null}
 $cd=Good 'independent private conversation' $c 'get_or_create_private' @{p_peer=$d.Id};$foreign=Send $c $cd.id 'different conversation'
 Deny 'cross-conversation reply' 404 $a 'send_message' @{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body='wrong reply';p_assets=@();p_reply_to=$foreign.id}
 Deny 'system message forgery' 422 $a 'send_message' @{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='system';p_body='fake admin';p_assets=@()}
 Deny 'empty text' 422 $a 'send_message' @{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body=' ';p_assets=@()}
 foreach($table in 'conversations','conversation_members','messages','message_hidden','message_media'){
  Check ('no direct '+$table+' insert') ((Request 'POST' ('/rest/v1/'+$table) @{} $a.Token).Status -eq 403)
 }
 Check 'no raw request hash read' ((Request 'GET' '/rest/v1/messages?select=request_hash' $null $a.Token).Status -eq 403)
 Check 'no raw global last-message pointer read' ((Request 'GET' '/rest/v1/conversations?select=last_message_id' $null $a.Token).Status -eq 403)
 Check 'cannot set another user read marker' ((Request 'PATCH' ('/rest/v1/conversation_members?user_id=eq.'+$b.Id) @{last_read_seq=999} $a.Token).Status -eq 403)
 Check 'cannot edit sender/body directly' ((Request 'PATCH' ('/rest/v1/messages?id=eq.'+$two.id) @{body='stolen';sender_id=$a.Id} $a.Token).Status -eq 403)
 Deny 'non-sender recall' 404 $a 'recall_message' @{p_message_id=$two.id}
 $null=Good 'hide root only for B' $b 'hide_message_for_me' @{p_message_id=$one.id};$null=Good 'hide retry' $b 'hide_message_for_me' @{p_message_id=$one.id}
 No-Rows 'hidden raw message never reappears' $b ('/rest/v1/messages?select=id,body&id=eq.'+$one.id)
 $history=Good 'reply preview after own hide' $b 'get_messages' @{p_conversation_id=$ab.id};$r=@($history.items|Where-Object id -eq $reply.id)[0]
 Check 'hidden source has no reply ID/body leak' ($null -eq $r.reply -and $null -eq $r.reply_to_message_id)
 $search=Good 'search obeys hidden preference' $b 'search_messages' @{p_conversation_id=$ab.id;p_query='alpha'};Check 'hidden original absent from search' (@($search.items|Where-Object id -eq $one.id).Count -eq 0)
 $null=Good 'owner can still read peer-hidden message' $a 'get_messages' @{p_conversation_id=$ab.id}
 $recall=Good 'recall message' $b 'recall_message' @{p_message_id=$two.id};Check 'recall redacts body' ($recall.state -eq 'recalled' -and $null -eq $recall.body)
 $null=Good 'recall retry' $b 'recall_message' @{p_message_id=$two.id}
 $search=Good 'search recalled excluded' $a 'search_messages' @{p_conversation_id=$ab.id;p_query='beta'};Check 'recalled absent from search' (@($search.items).Count -eq 0)
 Barrier $ab.id
 $retryPayload=@{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body='lost ACK';p_assets=@()}
 $race=Race 'same UUID simultaneous' $a 'send_message' $retryPayload $a 'send_message' $retryPayload @(200,200)
 Check 'same UUID same message and seq' ($race[0].id -eq $race[1].id -and $race[0].seq -eq $race[1].seq)
 $retry=Good 'retry after simulated lost ACK' $a 'send_message' $retryPayload;Check 'lost ACK never allocates again' ($retry.id -eq $race[0].id -and $retry.seq -eq $race[0].seq)
 $conflict=$retryPayload.Clone();$conflict.p_body='different';Deny 'UUID payload mismatch' 409 $a 'send_message' $conflict
 Barrier $ab.id
 $ra=@{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body='concurrent A';p_assets=@()};$rb=$ra.Clone();$rb.p_client_request_id=Id;$rb.p_body='concurrent B'
 $both=Race 'two senders unique sequence' $a 'send_message' $ra $b 'send_message' $rb @(200,200)
 Check 'two devices get adjacent unique seq' ([math]::Abs($both[0].seq-$both[1].seq) -eq 1)
 $rr=Send $a $ab.id 'recall read race';Barrier $ab.id
 $null=Race 'recall and read' $a 'recall_message' @{p_message_id=$rr.id} $b 'mark_read' @{p_conversation_id=$ab.id;p_message_id=$rr.id} @(200,200)
 $low=Send $a $ab.id 'low marker';$high=Send $a $ab.id 'high marker';Barrier $ab.id
 $null=Race 'out of order read markers' $b 'mark_read' @{p_conversation_id=$ab.id;p_message_id=$high.id} $b 'mark_read' @{p_conversation_id=$ab.id;p_message_id=$low.id} @(200,200)
 $view=Good 'read markers monotonic' $b 'get_conversation' @{p_conversation_id=$ab.id};Check 'max read survives stale ACK' ($view.last_read_seq -eq $high.seq -and $view.last_delivered_seq -ge $high.seq)
 Pair-Barrier $a $b
 $null=Race 'send and block' $b 'send_message' @{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body='racing block';p_assets=@()} $a 'set_block' @{p_target=$b.Id;p_blocked=$true} @(200,200)
 Deny 'blocked private history' 404 $b 'get_messages' @{p_conversation_id=$ab.id}
 Deny 'blocked private lookup' 404 $b 'get_or_create_private' @{p_peer=$a.Id}
 $null=Good 'unblock private peer' $a 'set_block' @{p_target=$b.Id;p_blocked=$false}
 # Friend-only group invitations, current roster maximum50; community Groups stay inert.
 Friend $a $b;Friend $a $c;Friend $a $d
 $groupPayload=@{p_title='Synthetic group';p_members=@($b.Id);p_client_request_id=(Id)}
 $g=Good 'create group chat' $a 'create_group_conversation' $groupPayload
 $gRetry=Good 'group UUID retry' $a 'create_group_conversation' $groupPayload;Check 'same group ID on retry' ($gRetry.id -eq $g.id)
 $badGroup=$groupPayload.Clone();$badGroup.p_title='different';Deny 'group UUID conflict' 409 $a 'create_group_conversation' $badGroup
 Deny 'member cannot add' 403 $b 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='add'}
 $old=Send $a $g.id 'before join secret'
 $g=Good 'admin adds member' $a 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='add'}
 $cg=Good 'new member history boundary' $c 'get_messages' @{p_conversation_id=$g.id};Check 'new join does not read older messages' (@($cg.items).Count -eq 0)
 No-Rows 'new member raw old message filtered' $c ('/rest/v1/messages?select=id,body&id=eq.'+$old.id)
 $afterJoin=Send $b $g.id 'after join visible'
 $cg=Good 'new member sees new message' $c 'get_messages' @{p_conversation_id=$g.id};Check 'only message after join visible' (@($cg.items).Count -eq 1 -and $cg.items[0].id -eq $afterJoin.id)
 $null=Good 'member leave' $c 'leave_conversation' @{p_conversation_id=$g.id};$null=Good 'leave retry' $c 'leave_conversation' @{p_conversation_id=$g.id}
 Deny 'left member history' 404 $c 'get_messages' @{p_conversation_id=$g.id}
 $gap=Send $a $g.id 'while absent'
 $g=Good 'rejoin new boundary' $a 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='add'}
 $cg=Good 'rejoin old access not restored' $c 'get_messages' @{p_conversation_id=$g.id};Check 'rejoin resets to new history' (@($cg.items).Count -eq 0)
 $fresh=Send $c $g.id 'own fresh joined'
 $g=Good 'kick member' $a 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='remove'}
 Deny 'old JWT after kick denied' 404 $c 'get_conversation' @{p_conversation_id=$g.id}
 Deny 'kicked sender cannot retry old UUID' 404 $c 'send_message' @{p_conversation_id=$g.id;p_client_request_id=(Id);p_kind='text';p_body='no access';p_assets=@()}
 $g=Good 'promote second admin' $a 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$b.Id;p_action='promote'}
 $members=Good 'active member roster' $b 'get_conversation_members' @{p_conversation_id=$g.id};Check 'removed member omitted from roster' (@($members|Where-Object user_id -eq $c.Id).Count -eq 0)
 $g=Good 'rename group version' $a 'rename_conversation' @{p_conversation_id=$g.id;p_title='New group name';p_expected_version=$g.version}
 Deny 'stale rename rejected' 409 $b 'rename_conversation' @{p_conversation_id=$g.id;p_title='stale name';p_expected_version=1}
 Barrier $g.id
 $null=Race 'two admins leave protects final admin' $a 'leave_conversation' @{p_conversation_id=$g.id} $b 'leave_conversation' @{p_conversation_id=$g.id} @(200,409)
 $roster=Invoke-MeetoSql "SELECT user_id FROM public.conversation_members WHERE conversation_id='$($g.id)' AND state='active' AND role='admin';"
 Check 'exactly one operative admin remains' ($roster.Count -eq 1)
 $admin=if(($roster -join '') -eq $a.Id){$a}else{$b};$other=if($admin.Id -eq $a.Id){$b}else{$a}
 # Group membership race uses a member the retained admin is friends with.
 if($admin.Id -eq $b.Id){Friend $b $c}
 $g=Good 're-add race member' $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='add'}
 try{
  $null=Invoke-MeetoSql "UPDATE public.profiles SET onboarding_completed=false WHERE id='$($c.Id)';"
  Deny 'incomplete target cannot be promoted to admin' 404 $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='promote'}
 }finally{$null=Invoke-MeetoSql "UPDATE public.profiles SET onboarding_completed=true WHERE id='$($c.Id)';"}
 $g=Good 'second operative admin fixture' $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='promote'}
 try{
  $null=Invoke-MeetoSql "UPDATE public.profiles SET onboarding_completed=false WHERE id='$($c.Id)';"
  Deny 'incomplete other admin does not permit final operative admin leave' 409 $admin 'leave_conversation' @{p_conversation_id=$g.id}
 }finally{$null=Invoke-MeetoSql "UPDATE public.profiles SET onboarding_completed=true WHERE id='$($c.Id)';"}
 $g=Good 'restore second member role fixture' $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='demote'}
 Barrier $g.id
 $null=Race 'send and leave' $c 'send_message' @{p_conversation_id=$g.id;p_client_request_id=(Id);p_kind='text';p_body='leave race';p_assets=@()} $c 'leave_conversation' @{p_conversation_id=$g.id} @(200,200)
 Barrier $g.id
 $null=Race 'send and join' $c 'send_message' @{p_conversation_id=$g.id;p_client_request_id=(Id);p_kind='text';p_body='join race';p_assets=@()} $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='add'} @(200,200)
 $null=Good 'leave after join race fixture' $c 'leave_conversation' @{p_conversation_id=$g.id}
 $g=Good 'archive metadata' $admin 'get_conversation' @{p_conversation_id=$g.id}
 $g=Good 'archive conversation' $admin 'set_conversation_state' @{p_conversation_id=$g.id;p_state='archived';p_expected_version=$g.version}
 Deny 'archived send denied' 404 $admin 'send_message' @{p_conversation_id=$g.id;p_client_request_id=(Id);p_kind='text';p_body='archived';p_assets=@()}
 $null=Good 'archived history still readable' $admin 'get_messages' @{p_conversation_id=$g.id}
 $g=Good 'restore conversation active' $admin 'set_conversation_state' @{p_conversation_id=$g.id;p_state='active';p_expected_version=$g.version}
 for($i=0;$i -lt 55;$i++){$null=Send $a $ab.id ('pagination needle '+$i)}
 $seen=@();$cursor=@{p_conversation_id=$ab.id;p_limit=11}
 do{$page=Good 'history keyset page' $a 'get_messages' $cursor;foreach($m in $page.items){Check 'message page no duplicate' ($m.id -notin $seen);$seen+=@($m.id)};if(@($page.items).Count){$cursor.p_before_seq=@($page.items)[-1].seq}}while(@($page.items).Count -eq 11)
 Check 'history more than50 traversed' ($seen.Count -gt 55)
 $forward=Good 'catch-up ascending sequence' $b 'get_messages' @{p_conversation_id=$ab.id;p_after_seq=$high.seq;p_limit=100}
 $seqs=@($forward.items|ForEach-Object seq);$ascending=$true;for($i=1;$i -lt $seqs.Count;$i++){if($seqs[$i] -le $seqs[$i-1]){$ascending=$false}};Check 'catch-up strictly ascending' $ascending
 Deny 'history cursor conflict' 422 $a 'get_messages' @{p_conversation_id=$ab.id;p_before_seq=50;p_after_seq=1}
 $null=Good 'scoped FTS search' $a 'search_messages' @{p_conversation_id=$ab.id;p_query='needle';p_limit=5}
 Deny 'nonmember search' 404 $d 'search_messages' @{p_conversation_id=$ab.id;p_query='needle'}
 $inbox=Good 'per-viewer inbox summary' $b 'get_inbox' @{p_limit=50};$item=@($inbox|Where-Object id -eq $ab.id)[0]
 Check 'inbox has visible last message and unread' ($null -ne $item.last_message.id -and $item.unread_count -gt 0)
 $lastId=$item.last_message.id;$null=Good 'hide last message' $b 'hide_message_for_me' @{p_message_id=$lastId}
 $inbox=Good 'inbox recomputes per viewer after hide' $b 'get_inbox' @{p_limit=50};Check 'hidden last not global pointer fallback' (@($inbox|Where-Object id -eq $ab.id)[0].last_message.id -ne $lastId)
 # Real media, shared Phase B verifier; registry retains conversation context through verify.
 $ff=Join-Path $FfmpegDirectory 'ffmpeg.exe'
 $null=& $ff -v error -f lavfi -i 'color=c=red:s=32x32' -frames:v 1 -threads 1 "$temp/chat.png" 2>&1;if($LASTEXITCODE -ne 0){throw 'Synthetic PNG failed'}
 $null=& $ff -v error -f lavfi -i 'color=c=red:s=32x32:r=2' -t 1 -c:v libx264 -pix_fmt yuv420p "$temp/chat.mp4" 2>&1;if($LASTEXITCODE -ne 0){throw 'Synthetic MP4 failed'}
 $asset=Good 'message upload intent' $a 'prepare_message_upload' @{p_conversation_id=$ab.id;p_mime='image/png';p_client_request_id=(Id)}
 $uploadSign=Request 'POST' ('/storage/v1/object/upload/sign/'+$asset.bucket_id+'/'+$asset.object_path) @{} $a.Token
 Check 'pending intent cannot mint signed upload capability' ($uploadSign.Status -in @(400,403))
 $video=Good 'message video intent' $a 'prepare_message_upload' @{p_conversation_id=$ab.id;p_mime='video/mp4';p_client_request_id=(Id)}
 Deny 'nonmember media intent' 404 $c 'prepare_message_upload' @{p_conversation_id=$ab.id;p_mime='image/png';p_client_request_id=(Id)}
 Deny 'ordinary file not enabled' 422 $a 'prepare_message_upload' @{p_conversation_id=$ab.id;p_mime='application/pdf';p_client_request_id=(Id)}
 Check 'wrong owner chat upload denied' ((Upload $b $asset "$temp/chat.png") -ge 400)
 Check 'real chat PNG uploaded' ((Upload $a $asset "$temp/chat.png") -eq 200)
 Check 'real chat MP4 uploaded' ((Upload $a $video "$temp/chat.mp4" 'video/mp4') -eq 200)
 $worker=Worker;Check 'chat verifier decoded both files' ($worker.verified -ge 2)
 $null=Good 'finalize chat image' $a 'finalize_upload' @{p_asset_id=$asset.id};$null=Good 'finalize chat video' $a 'finalize_upload' @{p_asset_id=$video.id}
 Check 'ready chat unattached not readable' ((Download $a $asset) -ge 400)
 $ad=Good 'other conversation for context validation' $a 'get_or_create_private' @{p_peer=$d.Id}
 Independent-Conversations $a $ab.id $ad.id
 Deny 'wrong conversation attachment' 422 $a 'send_message' @{p_conversation_id=$ad.id;p_client_request_id=(Id);p_kind='media';p_body='';p_assets=@($asset.id)}
 $media=Good 'send image video ordered' $a 'send_message' @{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='media';p_body='';p_assets=@($asset.id,$video.id)}
 Check 'recipient downloads chat image' ((Download $b $asset) -eq 200)
 Check 'nonmember guessed object path denied' ((Download $c $asset) -ge 400)
 $null=Good 'recipient hides chat media' $b 'hide_message_for_me' @{p_message_id=$media.id}
 Check 'hide revokes only caller media' ((Download $b $asset) -ge 400 -and (Download $a $asset) -eq 200)
 $null=Good 'sender recalls media' $a 'recall_message' @{p_message_id=$media.id}
 Check 'recall denies sender new GET' ((Download $a $asset) -ge 400)
 $worker=Worker;Check 'chat recall purges two objects' ($worker.deleted -ge 2)
 $orphan=Good 'chat orphan intent' $a 'prepare_message_upload' @{p_conversation_id=$ab.id;p_mime='image/png';p_client_request_id=(Id)}
 Check 'chat orphan uploaded' ((Upload $a $orphan "$temp/chat.png") -eq 200)
 $null=Invoke-MeetoSql "UPDATE public.media_assets SET created_at=now()-interval '25 hours' WHERE id='$($orphan.id)';"
 $worker=Worker;Check 'chat orphan cleaned' ($worker.deleted -ge 1)
 $gm=Good 'group media intent' $admin 'prepare_message_upload' @{p_conversation_id=$g.id;p_mime='image/png';p_client_request_id=(Id)}
 Check 'group image upload real bytes' ((Upload $admin $gm "$temp/chat.png") -eq 200)
 $null=Worker;$null=Good 'group finalize' $admin 'finalize_upload' @{p_asset_id=$gm.id}
 $g=Good 'group media receiver joins' $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='add'}
 $gmsg=Good 'group media message' $admin 'send_message' @{p_conversation_id=$g.id;p_client_request_id=(Id);p_kind='media';p_body='';p_assets=@($gm.id)}
 Check 'active group member downloads media' ((Download $c $gm) -eq 200)
 $null=Good 'remove group media receiver' $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='remove'}
 Check 'kicked member old JWT media denied' ((Download $c $gm) -ge 400)
 $null=Good 'media receiver rejoin' $admin 'manage_conversation_member' @{p_conversation_id=$g.id;p_target=$c.Id;p_action='add'}
 Check 'rejoin does not restore old attachment read' ((Download $c $gm) -ge 400)
 $null=Good 'leave group media fixture' $c 'leave_conversation' @{p_conversation_id=$g.id}
 $null=Good 'recall group media' $admin 'recall_message' @{p_message_id=$gmsg.id};$null=Worker
 # Shared quota arithmetic fixtures have no objects; operator setup is separate from HTTP JWT.
 $quotaIds=@();$quotaRequest=Id
 try{
  for($i=0;$i -lt 10;$i++){
   $qid=Id;$quotaIds+=@($qid);$qstate=if($i -lt 8){'ready'}else{'pending'};$qsize=if($i -lt 8){'52428800'}else{'NULL'}
   $null=Invoke-MeetoSql "INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,state,mime_type,size_bytes,metadata) VALUES('$qid','$($a.Id)',gen_random_uuid(),'chat-media','$($a.Id)/$qid/original.mp4','message','$qstate','video/mp4',$qsize,jsonb_build_object('conversation_id','$($ab.id)'));"
  }
  Deny 'B cannot bypass quota reserved by C pending video' 429 $a 'prepare_upload' @{p_purpose='post';p_mime='image/png';p_client_request_id=$quotaRequest}
 }finally{
  $quoted=($quotaIds|ForEach-Object {"'$_'::uuid"}) -join ','
  if($quoted){$null=Invoke-MeetoSql "DELETE FROM public.media_assets WHERE id IN ($quoted); DELETE FROM public.media_assets WHERE owner_id='$($a.Id)' AND client_request_id='$quotaRequest';"}
 }
 $seenConversations=@();$inboxCursor=@{p_limit=1}
 do{$window=Good 'inbox keyset window' $a 'get_inbox' $inboxCursor;if($null -eq $window){break};foreach($entry in $window){Check 'inbox cursor never repeats an ID in stable window' ($entry.id -notin $seenConversations);$seenConversations+=@($entry.id)};if(@($window).Count){$last=@($window)[-1];$inboxCursor.p_after_time=$last.updated_at;$inboxCursor.p_after_id=$last.id}}while(@($window).Count -eq 1)
 Check 'inbox includes both own private conversations' ($ab.id -in $seenConversations -and $ad.id -in $seenConversations)
 # All18 new RPC authentication/state matrix. Actor fields are never accepted.
 $cases=@{
  get_or_create_private=@{p_peer=$b.Id};create_group_conversation=@{p_title='matrix';p_members=@($b.Id);p_client_request_id=(Id)}
  get_conversation=@{p_conversation_id=$ab.id};get_inbox=@{}
  send_message=@{p_conversation_id=$ab.id;p_client_request_id=(Id);p_kind='text';p_body='matrix';p_assets=@()}
  get_messages=@{p_conversation_id=$ab.id};search_messages=@{p_conversation_id=$ab.id;p_query='matrix'}
  mark_read=@{p_conversation_id=$ab.id;p_message_id=$one.id};acknowledge_delivery=@{p_conversation_id=$ab.id;p_message_id=$one.id}
  recall_message=@{p_message_id=$one.id};hide_message_for_me=@{p_message_id=$one.id}
  get_read_receipts=@{p_conversation_id=$ab.id;p_message_id=$one.id};get_conversation_members=@{p_conversation_id=$ab.id}
  manage_conversation_member=@{p_conversation_id=$g.id;p_target=$d.Id;p_action='add'};leave_conversation=@{p_conversation_id=$g.id}
  rename_conversation=@{p_conversation_id=$g.id;p_title='matrix';p_expected_version=1};set_conversation_state=@{p_conversation_id=$g.id;p_state='active';p_expected_version=1}
  prepare_message_upload=@{p_conversation_id=$ab.id;p_mime='image/png';p_client_request_id=(Id)}
 }
 foreach($rpc in $cases.Keys){
  Check ($rpc+' missing JWT') ((Rpc $rpc $cases[$rpc] '').Status -in @(401,403))
  Check ($rpc+' forged JWT') ((Rpc $rpc $cases[$rpc] 'invalid.jwt.signature').Status -eq 401)
  Deny ($rpc+' incomplete') 403 $incomplete $rpc $cases[$rpc]
  $spoof=$cases[$rpc].Clone();$spoof.p_actor_id=$b.Id;Deny ($rpc+' spoof actor') 404 $a $rpc $spoof
 }
 foreach($status in 'suspended','deleting','unverified'){
  try{
   if($status -eq 'unverified'){$null=Invoke-MeetoSql "UPDATE auth.users SET email_confirmed_at=NULL WHERE id='$($a.Id)';"}else{$null=Invoke-MeetoSql "UPDATE public.account_status SET state='$status' WHERE user_id='$($a.Id)';"}
   foreach($rpc in $cases.Keys){Deny ($rpc+' '+$status+' real JWT') 403 $a $rpc $cases[$rpc]}
  }finally{$null=Invoke-MeetoSql "UPDATE auth.users SET email_confirmed_at=now() WHERE id='$($a.Id)'; UPDATE public.account_status SET state='active' WHERE user_id='$($a.Id)';"}
 }
 if($WithWebSocket){. "$PSScriptRoot/PhaseC-WebSocket.ps1";Invoke-PhaseCWebSocket -A $a -B $b -C $c -PrivateConversation $ab -GroupConversation $g -GroupAdmin $admin}
 [ordered]@{environment='LOCAL Docker Supabase, actual GoTrue JWT';passed=$results.Count;failed=0;tests=$results;fixtureUsers=@($a.Id,$b.Id,$c.Id,$d.Id,$incomplete.Id);concurrency='8 overlapping real JWT races plus independent-parent/global-lock probe';webSocketRequested=[bool]$WithWebSocket}|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 "$PSScriptRoot/../../database_design/validation_phase_07/http-results.json"
 Write-Output ('PASS C HTTP assertions: '+$results.Count)
}catch{
 $safe=$_.Exception.Message
 if($safe -notmatch '^(HTTP assertion failed:|race barrier|Synthetic|WebSocket)'){$safe='C harness at line '+$_.InvocationInfo.ScriptLineNumber+'; last passed '+($results|Select-Object -Last 1).test+'; '+$_.Exception.GetType().Name}
 throw $safe
}finally{
 if((Split-Path $temp -Parent) -ne $env:TEMP -or (Split-Path $temp -Leaf) -notmatch '^MeetoPhase07-fixtures-[0-9a-f]{32}$'){throw 'Unsafe C fixture cleanup'}
 if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Recurse}
 $state=$null;$AnonKey=$null
}
