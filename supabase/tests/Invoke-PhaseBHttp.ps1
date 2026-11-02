param([string]$SupabaseCli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe",
 [Parameter(Mandatory=$true)][string]$FfmpegDirectory)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/../scripts/PhaseB-Local.ps1"
$state=Get-MeetoLocalState $SupabaseCli
$AnonKey=$state.ANON_KEY;$api='http://127.0.0.1:54321';$mail='http://127.0.0.1:54324'
$results=New-Object 'System.Collections.Generic.List[object]'
# Reuse reviewed Auth/Mailpit helpers, with original source preserved byte-for-byte.
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/Invoke-AuthProfileHttp.ps1",[ref]$tokens,[ref]$errors)
foreach($name in 'Check','Request','Require200','Rpc','Confirm-Mail','New-TestIdentity'){
 $fn=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name},$true)
 . ([scriptblock]::Create($fn.Extent.Text))
}
function Good([string]$label,$u,[string]$rpc,$payload){return Require200 (Rpc $rpc $payload $u.Token) $label}
function Deny([string]$label,[int]$status,$u,[string]$rpc,$payload){$r=Rpc $rpc $payload $u.Token; Check ($label+' ['+$r.Status+']') ($r.Status -eq $status)}
function Id {return [guid]::NewGuid().ToString()}
function Post($u,[string]$audience='public',[string]$body='synthetic post') {return Good 'create post' $u 'create_post' @{p_body=$body;p_audience=$audience;p_assets=@();p_client_request_id=(Id)}}
function Race([string]$label,$u1,[string]$rpc1,$p1,$u2,[string]$rpc2,$p2,[int[]]$expected){
 Add-Type -AssemblyName System.Net.Http
 $p=New-Object Diagnostics.Process
 $p.StartInfo.FileName=(Get-Command docker).Source
 $p.StartInfo.Arguments='exec -i supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atq -v ON_ERROR_STOP=1'
 $p.StartInfo.UseShellExecute=$false;$p.StartInfo.CreateNoWindow=$true
 $p.StartInfo.RedirectStandardInput=$true;$p.StartInfo.RedirectStandardOutput=$true;$p.StartInfo.RedirectStandardError=$true
 $client=New-Object Net.Http.HttpClient;$client.Timeout=[TimeSpan]::FromSeconds(25)
 try{
  $null=$p.Start();$p.StandardInput.WriteLine("BEGIN; SELECT pg_advisory_xact_lock(6061101); SELECT 'ready';");$p.StandardInput.Flush()
  $ready=$p.StandardOutput.ReadLineAsync();if(!$ready.Wait(10000)){throw 'race barrier timeout'}
  if($ready.Result -ne 'ready'){$ready=$p.StandardOutput.ReadLineAsync();if(!$ready.Wait(10000) -or $ready.Result -ne 'ready'){throw 'race barrier failed'}}
  $tasks=@()
  foreach($s in @(@{u=$u1;rpc=$rpc1;p=$p1},@{u=$u2;rpc=$rpc2;p=$p2})){
   $m=New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Post,($api+'/rest/v1/rpc/'+$s.rpc))
   $m.Headers.Add('apikey',$AnonKey);$m.Headers.Authorization=New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$s.u.Token)
   $m.Content=New-Object Net.Http.StringContent(($s.p|ConvertTo-Json -Depth 8 -Compress),[Text.Encoding]::UTF8,'application/json')
   $tasks+=@($client.SendAsync($m))
  }
  $overlap=$false
  for($i=0;$i -lt 40;$i++){
   $q=Invoke-MeetoSql "SELECT count(*) FROM pg_stat_activity WHERE usename='authenticator' AND wait_event_type='Lock' AND query LIKE '%$rpc1%';"
   $q2=Invoke-MeetoSql "SELECT count(*) FROM pg_stat_activity WHERE usename='authenticator' AND wait_event_type='Lock';"
   if([int]($q2 -join '') -ge 2){$overlap=$true;break};Start-Sleep -Milliseconds 100
  }
  Check ($label+' real JWT requests overlap at DB lock') $overlap
  $p.StandardInput.WriteLine('COMMIT;');$p.StandardInput.Close()
  $responses=@();$statuses=@()
  foreach($t in $tasks){$r=$t.GetAwaiter().GetResult();$statuses+=@([int]$r.StatusCode);$responses+=@(($r.Content.ReadAsStringAsync().Result|ConvertFrom-Json))}
  $valid=!(Compare-Object @($expected|Sort-Object) @($statuses|Sort-Object))
  if($label -eq 'delete versus comment'){$valid=$statuses[0] -eq 200 -and $statuses[1] -in @(200,404)}
  Check ($label+' status outcomes '+($statuses -join '/')) $valid
  return ,$responses
 }finally{if(!$p.HasExited){$p.StandardInput.Close();if(!$p.WaitForExit(3000)){$p.Kill()}};$p.Dispose();$client.Dispose()}
}
function Upload($u,$intent,[string]$file,[string]$mime='image/png'){
 try{$r=Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($api+'/storage/v1/object/'+$intent.bucket_id+'/'+$intent.object_path) -Headers @{apikey=$AnonKey;Authorization=('Bearer '+$u.Token);'cache-control'='0';'x-upsert'='false'} -ContentType $mime -InFile $file -TimeoutSec 30;return [int]$r.StatusCode}
 catch{if(!$_.Exception.Response){throw 'Storage upload transport failed'};return [int]$_.Exception.Response.StatusCode}
}
function Download($u,$intent,[bool]$public=$false){
 $route=if($public){'public'}else{'authenticated'}
 try{$r=Invoke-WebRequest -UseBasicParsing -Uri ($api+'/storage/v1/object/'+$route+'/'+$intent.bucket_id+'/'+$intent.object_path) -Headers @{apikey=$AnonKey;Authorization=('Bearer '+$u.Token)} -TimeoutSec 15;return [int]$r.StatusCode}
 catch{if(!$_.Exception.Response){throw 'Storage download transport failed'};return [int]$_.Exception.Response.StatusCode}
}
function Worker {return & "$PSScriptRoot/../scripts/Invoke-MediaWorker.ps1" -SupabaseCli $SupabaseCli -FfmpegDirectory $FfmpegDirectory -BatchSize 100}
function Intent($u,[string]$purpose='post',[string]$mime='image/png'){return Good 'prepare upload' $u 'prepare_upload' @{p_purpose=$purpose;p_mime=$mime;p_client_request_id=(Id)}}
$temp=Join-Path $env:TEMP ('MeetoPhase06-fixtures-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp|Out-Null
try{
 $a=New-TestIdentity 'B synthetic A';$b=New-TestIdentity 'B synthetic B';$c=New-TestIdentity 'B synthetic C';$d=New-TestIdentity 'B incomplete' $false
 $pub=Post $a;$friends=Post $a 'friends';$priv=Post $a 'private'
 $null=Good 'public post reader' $b 'get_post' @{p_post_id=$pub.id}
 Deny 'nonfriend friends post' 404 $b 'get_post' @{p_post_id=$friends.id}
 Deny 'other private post' 404 $b 'get_post' @{p_post_id=$priv.id}
 Deny 'missing UUID' 404 $b 'get_post' @{p_post_id=(Id)}
 Deny 'incomplete mutation' 403 $d 'create_post' @{p_body='x';p_audience='public';p_assets=@();p_client_request_id=(Id)}
 Deny 'actor spoof rejected' 404 $a 'create_post' @{p_body='x';p_audience='public';p_assets=@();p_client_request_id=(Id);p_author_id=$b.Id}
 Deny 'group posting disabled' 422 $a 'create_post' @{p_body='x';p_audience='group';p_assets=@();p_client_request_id=(Id)}
 Deny 'empty post' 422 $a 'create_post' @{p_body=' ';p_audience='public';p_assets=@();p_client_request_id=(Id)}
 $rollbackId=Id
 Deny 'missing media aborts post transaction' 422 $a 'create_post' @{p_body='rollback';p_audience='public';p_assets=@((Id));p_client_request_id=$rollbackId}
 $rolled=Invoke-MeetoSql "SELECT NOT EXISTS(SELECT 1 FROM public.posts WHERE author_id='$($a.Id)' AND client_request_id='$rollbackId');"
 Check 'no partial post survives failed attachment' (($rolled -join '') -eq 't')
 Deny 'foreign edit' 404 $b 'edit_post' @{p_post_id=$pub.id;p_body='stolen';p_audience='public';p_assets=@();p_expected_version=1}
 Deny 'foreign delete' 404 $b 'delete_post' @{p_post_id=$pub.id;p_expected_version=1}
 foreach($table in 'posts','comments','post_reactions','post_media','friend_requests','friendships','media_assets'){
  Check ('direct insert denied '+$table) ((Request 'POST' ('/rest/v1/'+$table) @{} $a.Token).Status -eq 403)
 }
 Check 'counter PATCH denied' ((Request 'PATCH' ('/rest/v1/posts?id=eq.'+$pub.id) @{reactions_count=500} $a.Token).Status -eq 403)
 Check 'request hash column denied' ((Request 'GET' '/rest/v1/posts?select=request_hash' $null $a.Token).Status -eq 403)
 $rawPrivate=Request 'GET' ('/rest/v1/posts?id=eq.'+$priv.id+'&select=id') $null $b.Token
 Check 'raw private rows filtered' ($null -eq $rawPrivate.Data -or @($rawPrivate.Data).Count -eq 0)
 Deny 'self request' 422 $a 'send_friend_request' @{p_recipient=$a.Id;p_client_request_id=(Id)}
 $fr=Good 'send friend request' $a 'send_friend_request' @{p_recipient=$b.Id;p_client_request_id=(Id)}
 $dup=Good 'duplicate pair request' $a 'send_friend_request' @{p_recipient=$b.Id;p_client_request_id=(Id)}
 Check 'one pending request per pair' ($dup.id -eq $fr.id)
 $cross=Good 'crossed request returns incoming' $b 'send_friend_request' @{p_recipient=$a.Id;p_client_request_id=(Id)}
 Check 'cross request direction retained' ($cross.id -eq $fr.id -and $cross.requester_id -eq $a.Id)
 Deny 'request sender cannot accept' 403 $a 'accept_friend_request' @{p_request_id=$fr.id}
 Deny 'unrelated cannot accept' 404 $c 'accept_friend_request' @{p_request_id=$fr.id}
 Deny 'missing request accept' 404 $b 'accept_friend_request' @{p_request_id=(Id)}
 $null=Good 'accept' $b 'accept_friend_request' @{p_request_id=$fr.id}
 $null=Good 'accept retry' $b 'accept_friend_request' @{p_request_id=$fr.id}
 $null=Good 'friend reads friends post' $b 'get_post' @{p_post_id=$friends.id}
 foreach($rpc in 'get_friends','get_received_requests','get_sent_requests','get_suggestions'){$null=Good $rpc $a $rpc @{p_limit=2}}
 $null=Good 'unfriend' $a 'unfriend' @{p_other=$b.Id};$null=Good 'unfriend retry' $a 'unfriend' @{p_other=$b.Id}
 Deny 'old accepted request cannot restore friend' 409 $b 'accept_friend_request' @{p_request_id=$fr.id}
 Deny 'unfriend revokes friends post' 404 $b 'get_post' @{p_post_id=$friends.id}
 $r2=Good 'request for decline' $c 'send_friend_request' @{p_recipient=$a.Id;p_client_request_id=(Id)}
 $null=Good 'decline' $a 'decline_friend_request' @{p_request_id=$r2.id};$null=Good 'decline retry' $a 'decline_friend_request' @{p_request_id=$r2.id}
 $cancelPayload=@{p_recipient=$a.Id;p_client_request_id=(Id)}
 $r3=Good 'request for cancel' $c 'send_friend_request' $cancelPayload
 $crossPayload=@{p_recipient=$c.Id;p_client_request_id=(Id)}
 $crossReceipt=Good 'cross receipt fixture' $a 'send_friend_request' $crossPayload
 $null=Good 'cancel' $c 'cancel_friend_request' @{p_request_id=$r3.id};$null=Good 'cancel retry' $c 'cancel_friend_request' @{p_request_id=$r3.id}
 $receiptRetry=Good 'cross UUID retry after cancel returns original terminal row' $a 'send_friend_request' $crossPayload
 Check 'cross receipt never recreates request' ($receiptRetry.id -eq $r3.id -and $receiptRetry.state -eq 'cancelled')
 $conflictPayload=$crossPayload.Clone();$conflictPayload.p_recipient=$b.Id
 Deny 'cross receipt UUID cannot change target' 409 $a 'send_friend_request' $conflictPayload
 $payload=@{p_body='retry';p_audience='public';p_assets=@();p_client_request_id=(Id)}
 $race=Race 'create lost ACK retry' $a 'create_post' $payload $a 'create_post' $payload @(200,200)
 Check 'same create ID from concurrent retry' ($race[0].id -eq $race[1].id)
 $payload.p_body='different';Deny 'request hash conflict' 409 $a 'create_post' $payload
 $reaction=@{p_post_id=$pub.id;p_reaction='like'}
 $null=Race 'concurrent likes same user' $b 'set_post_reaction' $reaction $b 'set_post_reaction' $reaction @(200,200)
 $r=Good 'reaction type switch' $b 'set_post_reaction' @{p_post_id=$pub.id;p_reaction='love'};Check 'type switch keeps count' ($r.reactions_count -eq 1)
 $null=Race 'concurrent likes different users' $b 'set_post_reaction' $reaction $c 'set_post_reaction' $reaction @(200,200)
 $r=Good 'read counter' $a 'get_post' @{p_post_id=$pub.id};Check 'two unique users count' ($r.reactions_count -eq 2)
 $null=Good 'unset reaction' $b 'set_post_reaction' @{p_post_id=$pub.id;p_reaction=$null};$r=Good 'unset retry' $b 'set_post_reaction' @{p_post_id=$pub.id;p_reaction=$null};Check 'unset once' ($r.reactions_count -eq 1)
 Deny 'bad reaction' 422 $c 'set_post_reaction' @{p_post_id=$pub.id;p_reaction='bad'}
 Deny 'private reaction' 404 $b 'set_post_reaction' @{p_post_id=$priv.id;p_reaction='like'}
 $commentPayload=@{p_post_id=$pub.id;p_parent_id=$null;p_body='root';p_client_request_id=(Id)}
 $root=Good 'create root' $b 'create_comment' $commentPayload
 $retry=Good 'comment retry' $b 'create_comment' $commentPayload;Check 'comment ID stable' ($retry.id -eq $root.id)
 $reply=Good 'create reply' $c 'create_comment' @{p_post_id=$pub.id;p_parent_id=$root.id;p_body='reply';p_client_request_id=(Id)}
 Deny 'cross post parent' 404 $a 'create_comment' @{p_post_id=$priv.id;p_parent_id=$root.id;p_body='cross';p_client_request_id=(Id)}
 Deny 'foreign comment edit' 404 $a 'edit_comment' @{p_comment_id=$root.id;p_body='x';p_expected_version=1}
 Deny 'private comments guessed' 404 $b 'list_comments' @{p_post_id=$priv.id}
 $edited=Good 'edit root' $b 'edit_comment' @{p_comment_id=$root.id;p_body='edited';p_expected_version=1}
 Deny 'stale comment edit' 409 $b 'edit_comment' @{p_comment_id=$root.id;p_body='stale';p_expected_version=1}
 $tomb=Good 'delete root' $b 'delete_comment' @{p_comment_id=$root.id;p_expected_version=$edited.version}
 Check 'tombstone body redacted' ($null -eq $tomb.body -and $tomb.state -eq 'deleted')
 $null=Good 'delete comment retry' $b 'delete_comment' @{p_comment_id=$root.id;p_expected_version=1}
 $list=Good 'replies survive tombstone' $a 'list_comments' @{p_post_id=$pub.id;p_parent_id=$root.id};Check 'reply retained' (@($list).Count -eq 1)
 $parent=$reply.id
 for($depth=3;$depth -le 8;$depth++){$node=Good 'depth within eight' $a 'create_comment' @{p_post_id=$pub.id;p_parent_id=$parent;p_body='depth';p_client_request_id=(Id)};$parent=$node.id}
 Deny 'depth nine denied' 422 $a 'create_comment' @{p_post_id=$pub.id;p_parent_id=$parent;p_body='too deep';p_client_request_id=(Id)}
 Deny 'parent mutation no such parameter' 404 $a 'edit_comment' @{p_comment_id=$parent;p_body='cycle';p_expected_version=1;p_parent_id=$parent}
 $share=Good 'share readable original' $b 'share_post' @{p_original=$pub.id;p_body='share';p_audience='public';p_client_request_id=(Id)}
 Deny 'share chain denied' 404 $c 'share_post' @{p_original=$share.id;p_body='';p_audience='public';p_client_request_id=(Id)}
 Deny 'private share denied' 404 $b 'share_post' @{p_original=$priv.id;p_body='';p_audience='public';p_client_request_id=(Id)}
 $updated=Good 'revoke original audience' $a 'edit_post' @{p_post_id=$pub.id;p_body='synthetic post';p_audience='private';p_assets=@();p_expected_version=1}
 Deny 'share hidden from third reader after revoke' 404 $c 'get_post' @{p_post_id=$share.id}
 $shell=Good 'share owner gets unavailable shell' $b 'get_post' @{p_post_id=$share.id};Check 'shell has no source reference' (!$shell.original_available -and $null -eq $shell.original_post_id)
 Deny 'revoked comment creation' 404 $b 'create_comment' @{p_post_id=$pub.id;p_parent_id=$null;p_body='denied';p_client_request_id=(Id)}
 Deny 'stale post edit' 409 $a 'edit_post' @{p_post_id=$pub.id;p_body='stale';p_audience='public';p_assets=@();p_expected_version=1}
 $null=Good 'publish original again' $a 'edit_post' @{p_post_id=$pub.id;p_body='synthetic post';p_audience='public';p_assets=@();p_expected_version=$updated.version}
 $null=Race 'cross friend requests' $a 'send_friend_request' @{p_recipient=$b.Id;p_client_request_id=(Id)} $b 'send_friend_request' @{p_recipient=$a.Id;p_client_request_id=(Id)} @(200,200)
 $sent=Good 'get pending cross request' $a 'get_sent_requests' @{}
 $received=Good 'get incoming cross request' $a 'get_received_requests' @{}
 $pending=@(@($sent)+@($received)|Where-Object id -eq $b.Id)[0]
 $requester=if(@($sent|Where-Object id -eq $b.Id).Count){$a}else{$b};$recipient=if($requester.Id -eq $a.Id){$b}else{$a}
 $null=Race 'accept cancel' $recipient 'accept_friend_request' @{p_request_id=$pending.request_id} $requester 'cancel_friend_request' @{p_request_id=$pending.request_id} @(200,409)
 $del=Post $a
 $null=Race 'delete versus comment' $a 'delete_post' @{p_post_id=$del.id;p_expected_version=1} $b 'create_comment' @{p_post_id=$del.id;p_parent_id=$null;p_body='race';p_client_request_id=(Id)} @(200,404)
 Deny 'deleted post read' 404 $a 'get_post' @{p_post_id=$del.id}
 $null=Good 'delete post retry' $a 'delete_post' @{p_post_id=$del.id;p_expected_version=1}
 # More than 30 rows verifies real keyset boundaries/tie-breaks.
 for($i=0;$i -lt 32;$i++){$null=Post $c 'public' ('page '+$i)}
 $seen=@();$cursor=@{p_author=$c.Id;p_limit=7}
 do{$page=Good 'keyset page' $a 'get_user_posts' $cursor;foreach($p in $page){Check 'page ID never repeated' ($p.id -notin $seen);$seen+=@($p.id)};if(@($page).Count){$last=@($page)[-1];$cursor.p_after_time=$last.created_at;$cursor.p_after_id=$last.id}}while(@($page).Count -eq 7)
 Check 'all 32 author posts traversed' ($seen.Count -eq 32)
 $null=Good 'friends feed mode' $a 'get_feed' @{p_mode='friends';p_limit=2}
 Deny 'incomplete cursor denied' 422 $a 'get_feed' @{p_after_id=(Id)}
 Deny 'overlarge limit denied' 422 $a 'get_feed' @{p_limit=51}
 # Real bytes: generated PNG, JPEG, WebP, H264 MP4. No personal media.
 $ff=Join-Path $FfmpegDirectory 'ffmpeg.exe'
 foreach($ext in 'png','jpg','webp'){
  $null=& $ff -v error -f lavfi -i 'color=c=blue:s=32x32' -frames:v 1 -threads 1 "$temp/test.$ext" 2>&1
  if($LASTEXITCODE -ne 0){throw 'Synthetic image generation failed'}
 }
 $null=& $ff -v error -f lavfi -i 'color=c=blue:s=32x32:r=2' -t 1 -c:v libx264 -pix_fmt yuv420p "$temp/test.mp4" 2>&1
 if($LASTEXITCODE -ne 0){throw 'Synthetic video generation failed'}
 $png=Intent $a;$video=Intent $a 'post' 'video/mp4';$avatar=Intent $a 'avatar' 'image/jpeg';$cover=Intent $a 'cover' 'image/webp'
 Deny 'future story media denied' 422 $a 'prepare_upload' @{p_purpose='story';p_mime='image/png';p_client_request_id=(Id)}
 Deny 'SVG intent denied' 422 $a 'prepare_upload' @{p_purpose='post';p_mime='image/svg+xml';p_client_request_id=(Id)}
 $oversize=Intent $a 'avatar'
 $large=[IO.File]::Create("$temp/large.png");try{$large.SetLength(10485761)}finally{$large.Dispose()}
 Check 'profile bucket size limit enforced by HTTP' ((Upload $a $oversize "$temp/large.png") -ge 400)
 Deny 'finalize without object' 409 $a 'finalize_upload' @{p_asset_id=$png.id}
 Check 'foreign upload path denied' ((Upload $b $png "$temp/test.png") -ge 400)
 Check 'anonymous upload denied' ((Upload @{Token=$AnonKey} $png "$temp/test.png") -ge 400)
 Check 'PNG upload real bytes' ((Upload $a $png "$temp/test.png") -eq 200)
 Check 'video upload real bytes' ((Upload $a $video "$temp/test.mp4" 'video/mp4') -eq 200)
 Check 'avatar JPEG upload' ((Upload $a $avatar "$temp/test.jpg" 'image/jpeg') -eq 200)
 Check 'cover WebP upload' ((Upload $a $cover "$temp/test.webp" 'image/webp') -eq 200)
 Check 'immutable object duplicate upload denied' ((Upload $a $png "$temp/test.png") -ge 400)
 $upsertStatus=0
 try{
  $r=Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($api+'/storage/v1/object/'+$png.bucket_id+'/'+$png.object_path) -Headers @{apikey=$AnonKey;Authorization=('Bearer '+$a.Token);'x-upsert'='true'} -ContentType 'image/png' -InFile "$temp/test.png" -TimeoutSec 20
  $upsertStatus=[int]$r.StatusCode
 }catch{if(!$_.Exception.Response){throw 'Storage upsert transport failed'};$upsertStatus=[int]$_.Exception.Response.StatusCode}
 Check 'upsert cannot overwrite pending object' ($upsertStatus -ge 400)
 Deny 'other finalize denied' 404 $b 'finalize_upload' @{p_asset_id=$png.id}
 $worker=Worker;Check 'worker decoded image and video fixtures' ($worker.verified -ge 4)
 $null=Race 'same media finalize' $a 'finalize_upload' @{p_asset_id=$png.id} $a 'finalize_upload' @{p_asset_id=$png.id} @(200,200)
 foreach($i in @($video,$avatar,$cover)){$null=Good 'finalize verified media' $a 'finalize_upload' @{p_asset_id=$i.id}}
 Check 'ready unattached media denied even owner' ((Download $a $png) -ge 400)
 $mediaPost=Good 'ordered image and video post' $a 'create_post' @{p_body='';p_audience='public';p_assets=@($video.id,$png.id);p_client_request_id=(Id)}
 Check 'attachment order retained' ($mediaPost.media[0].asset_id -eq $video.id -and $mediaPost.media[1].position -eq 1)
 Check 'authenticated reader downloads media' ((Download $b $png) -eq 200)
 Check 'private bucket public URL denied' ((Download $b $png $true) -ge 400)
 Deny 'foreign asset attach' 422 $b 'create_post' @{p_body='x';p_audience='public';p_assets=@($png.id);p_client_request_id=(Id)}
 Deny 'asset cannot attach twice' 422 $a 'create_post' @{p_body='x';p_audience='public';p_assets=@($png.id);p_client_request_id=(Id)}
 Check 'signed URL denied' ((Request 'POST' ('/storage/v1/object/sign/'+$png.bucket_id+'/'+$png.object_path) @{expiresIn=3600} $b.Token).Status -ge 400)
 $batchSign=Request 'POST' ('/storage/v1/object/sign/'+$png.bucket_id) @{expiresIn=3600;paths=@($png.object_path)} $b.Token
 Check 'batch signing issues no bearer URL' ($batchSign.Status -ge 400 -or @($batchSign.Data|Where-Object {$_.signedURL -or $_.signedUrl}).Count -eq 0)
 Check 'client object delete denied' ((Request 'DELETE' ('/storage/v1/object/'+$png.bucket_id) @{prefixes=@($png.object_path)} $a.Token).Status -ge 400 -or (Download $a $png) -eq 200)
 Check 'client copy denied' ((Request 'POST' '/storage/v1/object/copy' @{bucketId=$png.bucket_id;sourceKey=$png.object_path;destinationKey=($a.Id+'/forbidden.png')} $a.Token).Status -ge 400)
 Check 'client move denied' ((Request 'POST' '/storage/v1/object/move' @{bucketId=$png.bucket_id;sourceKey=$png.object_path;destinationKey=($a.Id+'/forbidden.png')} $a.Token).Status -ge 400)
 $rangeClient=New-Object Net.Http.HttpClient
 try{
  $rangeRequest=New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Get,($api+'/storage/v1/object/authenticated/'+$video.bucket_id+'/'+$video.object_path))
  $rangeRequest.Headers.Add('apikey',$AnonKey);$rangeRequest.Headers.Authorization=New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$b.Token)
  $rangeRequest.Headers.Range=New-Object Net.Http.Headers.RangeHeaderValue(0,15)
  $range=$rangeClient.SendAsync($rangeRequest).GetAwaiter().GetResult();$rangeBytes=$range.Content.ReadAsByteArrayAsync().Result
  Check 'authenticated video Range serves 16 bytes' ([int]$range.StatusCode -eq 206 -and $rangeBytes.Length -eq 16)
 }finally{$rangeClient.Dispose()}
 $listing=Request 'POST' ('/storage/v1/object/list/'+$png.bucket_id) @{prefix=$a.Id;limit=100} $a.Token
 Check 'object listing reveals no objects' ($listing.Status -ge 400 -or $null -eq $listing.Data -or @($listing.Data).Count -eq 0)
 $mp=Good 'revoke media post' $a 'edit_post' @{p_post_id=$mediaPost.id;p_body='';p_audience='private';p_assets=@($video.id,$png.id);p_expected_version=1}
 Check 'existing authenticated URL denied after revoke' ((Download $b $png) -ge 400)
 Check 'private owner still downloads' ((Download $a $png) -eq 200)
 $profile=Good 'self for profile media' $a 'get_my_profile' @{}
 $profile=Good 'attach avatar cover' $a 'update_profile' @{p_display_name=$profile.display_name;p_bio=$profile.bio;p_avatar_asset_id=$avatar.id;p_cover_asset_id=$cover.id;p_expected_version=$profile.version}
 Check 'profile media allowed reader' ((Download $b $avatar) -eq 200)
 $newAvatar=Intent $a 'avatar' 'image/jpeg';Check 'replacement upload' ((Upload $a $newAvatar "$temp/test.jpg" 'image/jpeg') -eq 200)
 $null=Worker;$null=Good 'replacement finalize' $a 'finalize_upload' @{p_asset_id=$newAvatar.id}
 $profile=Good 'replace avatar retires old' $a 'update_profile' @{p_display_name=$profile.display_name;p_bio=$profile.bio;p_avatar_asset_id=$newAvatar.id;p_cover_asset_id=$cover.id;p_expected_version=$profile.version}
 Check 'old avatar denied immediately' ((Download $a $avatar) -ge 400)
 $bad=Intent $a;[IO.File]::WriteAllText("$temp/bad.png",'<script>synthetic invalid media</script>')
 Check 'declared PNG malformed bytes upload accepted for scan' ((Upload $a $bad "$temp/bad.png") -eq 200)
 $badMime=Intent $a;Check 'bucket rejects disallowed MIME' ((Upload $a $badMime "$temp/bad.png" 'text/html') -ge 400)
 $worker=Worker;Check 'spoofed magic rejected and bytes cleaned' ($worker.rejected -ge 1 -and $worker.deleted -ge 1)
 Deny 'rejected media cannot finalize' 410 $a 'finalize_upload' @{p_asset_id=$bad.id}
 $null=Good 'delete media parent' $a 'delete_post' @{p_post_id=$mediaPost.id;p_expected_version=$mp.version}
 Check 'delete parent denies media before worker' ((Download $a $png) -ge 400)
 $worker=Worker;Check 'delete worker purged linked objects' ($worker.deleted -ge 2)
 $orphan=Intent $a;Check 'orphan upload' ((Upload $a $orphan "$temp/test.png") -eq 200)
 $null=Invoke-MeetoSql "UPDATE public.media_assets SET created_at=now()-interval '25 hours' WHERE id='$($orphan.id)';"
 Deny 'expired intent' 410 $a 'finalize_upload' @{p_asset_id=$orphan.id}
 $worker=Worker;Check 'orphan cleanup deletes physical object' ($worker.deleted -ge 1)
 $retryAsset=Intent $a;Check 'retry fixture uploaded' ((Upload $a $retryAsset "$temp/test.png") -eq 200)
 $null=Invoke-MeetoSql "SELECT private.retire_asset('$($retryAsset.id)');"
 $fault=& "$PSScriptRoot/../scripts/Invoke-MediaWorker.ps1" -SupabaseCli $SupabaseCli -FfmpegDirectory $FfmpegDirectory -TestDeleteFailure
 Check 'worker fault injection records retry' ($fault.retry -ge 1)
 $backoff=Invoke-MeetoSql "SELECT state='pending' AND attempts=1 AND available_at>now() AND payload->>'last_error'='storage_delete_failed' FROM public.event_outbox WHERE event_key='media-retire:$($retryAsset.id)';"
 Check 'retry backoff and sanitized failure checkpoint' (($backoff -join '') -eq 't')
 $null=Invoke-MeetoSql "UPDATE public.event_outbox SET attempts=4,available_at=now() WHERE event_key='media-retire:$($retryAsset.id)';"
 $fault=& "$PSScriptRoot/../scripts/Invoke-MediaWorker.ps1" -SupabaseCli $SupabaseCli -FfmpegDirectory $FfmpegDirectory -TestDeleteFailure
 Check 'fifth failed delete dead letters' ($fault.dead -ge 1)
 $null=Invoke-MeetoSql "UPDATE public.event_outbox SET state='processing',attempts=0,lease_until=now()-interval '1 second',available_at=now() WHERE event_key='media-retire:$($retryAsset.id)';"
 $worker=Worker;Check 'expired worker lease reclaimed and Storage delete succeeds' ($worker.deleted -ge 1)
 $physical=Invoke-MeetoSql "SELECT NOT EXISTS(SELECT 1 FROM storage.objects WHERE bucket_id='$($retryAsset.bucket_id)' AND name='$($retryAsset.object_path)');"
 Check 'Storage API removed object metadata with bytes' (($physical -join '') -eq 't')
 Check 'trusted Storage GET also confirms physical purge' ((Request 'GET' ('/storage/v1/object/authenticated/'+$retryAsset.bucket_id+'/'+$retryAsset.object_path) $null $state.SERVICE_ROLE_KEY).Status -ge 400)
 $quotaIntents=@();for($i=0;$i -lt 10;$i++){$quotaIntents+=@(Intent $c)}
 Deny 'eleventh pending intent quota' 429 $c 'prepare_upload' @{p_purpose='post';p_mime='image/png';p_client_request_id=(Id)}
 $null=Invoke-MeetoSql "UPDATE public.media_assets SET state='pending_delete',delete_after=now()+interval '1 day' WHERE owner_id='$($c.Id)' AND state='pending';"
 Deny 'byte reservations survive failed or deferred deletion' 429 $c 'prepare_upload' @{p_purpose='post';p_mime='image/png';p_client_request_id=(Id)}
 $null=Invoke-MeetoSql "UPDATE public.media_assets SET created_at=now()-interval '25 hours',delete_after=now() WHERE owner_id='$($c.Id)' AND state='pending_delete';"
 $worker=Worker;Check 'abandoned intents without objects cleaned idempotently' ($worker.deleted -ge 10)
 # Block integration with A; block removes friendship and hides posts/media both directions.
 $null=Good 'block peer' $a 'set_block' @{p_target=$b.Id;p_blocked=$true}
 Deny 'blocked request' 404 $b 'send_friend_request' @{p_recipient=$a.Id;p_client_request_id=(Id)}
 Deny 'blocked post read' 404 $b 'get_post' @{p_post_id=$pub.id}
 Check 'blocked profile media download' ((Download $b $newAvatar) -ge 400)
 $null=Good 'unblock peer' $a 'set_block' @{p_target=$b.Id;p_blocked=$false}
 $counts=Invoke-MeetoSql "SELECT bool_and(p.reactions_count=(SELECT count(*) FROM public.post_reactions r WHERE r.post_id=p.id) AND p.comments_count=(SELECT count(*) FROM public.comments c WHERE c.post_id=p.id AND c.state='published') AND p.shares_count=(SELECT count(*) FROM public.posts s WHERE s.original_post_id=p.id AND s.state='published')) FROM public.posts p;"
 Check 'all counters reconcile against actual rows' (($counts -join '') -eq 't')
 $cases=@{
  send_friend_request=@{p_recipient=$b.Id;p_client_request_id=(Id)};accept_friend_request=@{p_request_id=$fr.id};decline_friend_request=@{p_request_id=$fr.id};cancel_friend_request=@{p_request_id=$fr.id};unfriend=@{p_other=$b.Id}
  get_friends=@{};get_received_requests=@{};get_sent_requests=@{};get_suggestions=@{}
  create_post=@{p_body='matrix';p_audience='public';p_assets=@();p_client_request_id=(Id)}
  share_post=@{p_original=$pub.id;p_body='';p_audience='public';p_client_request_id=(Id)}
  edit_post=@{p_post_id=$pub.id;p_body='matrix';p_audience='public';p_assets=@();p_expected_version=1}
  delete_post=@{p_post_id=$pub.id;p_expected_version=1};get_post=@{p_post_id=$pub.id};get_feed=@{};get_user_posts=@{p_author=$a.Id}
  set_post_reaction=@{p_post_id=$pub.id;p_reaction='like'}
  create_comment=@{p_post_id=$pub.id;p_parent_id=$null;p_body='matrix';p_client_request_id=(Id)}
  edit_comment=@{p_comment_id=$root.id;p_body='matrix';p_expected_version=1};delete_comment=@{p_comment_id=$root.id;p_expected_version=1};list_comments=@{p_post_id=$pub.id}
  prepare_upload=@{p_purpose='post';p_mime='image/png';p_client_request_id=(Id)};finalize_upload=@{p_asset_id=$png.id}
 }
 foreach($rpc in $cases.Keys){
  Check ($rpc+' missing JWT') ((Rpc $rpc $cases[$rpc] '').Status -in @(401,403))
  Check ($rpc+' forged JWT') ((Rpc $rpc $cases[$rpc] 'invalid.jwt.signature').Status -eq 401)
  Deny ($rpc+' incomplete identity') 403 $d $rpc $cases[$rpc]
  $spoof=$cases[$rpc].Clone();$spoof.p_actor_id=$b.Id;Deny ($rpc+' actor parameter forbidden') 404 $a $rpc $spoof
 }
 foreach($status in 'suspended','deleting','unverified'){
  try{
   if($status -eq 'unverified'){$null=Invoke-MeetoSql "UPDATE auth.users SET email_confirmed_at=NULL WHERE id='$($a.Id)';"}
   else{$null=Invoke-MeetoSql "UPDATE public.account_status SET state='$status' WHERE user_id='$($a.Id)';"}
   foreach($rpc in $cases.Keys){Deny ($rpc+' '+$status+' token gate') 403 $a $rpc $cases[$rpc]}
  }finally{
   $null=Invoke-MeetoSql "UPDATE auth.users SET email_confirmed_at=now() WHERE id='$($a.Id)'; UPDATE public.account_status SET state='active' WHERE user_id='$($a.Id)';"
  }
 }
 $output=[ordered]@{environment='LOCAL Supabase Docker; real GoTrue JWT';passed=$results.Count;failed=0;tests=$results;fixtureUsers=@($a.Id,$b.Id,$c.Id,$d.Id);concurrency='7 barriers, real overlapping HTTP JWT requests';storage='synthetic PNG JPEG WebP H264 MP4; decoded by trusted local worker'}
 $output|ConvertTo-Json -Depth 7|Set-Content -Encoding UTF8 "$PSScriptRoot/../../database_design/validation_phase_06/http-results.json"
 Write-Output ('PASS Phase B HTTP assertions: '+$results.Count)
}catch{
 $safe=$_.Exception.Message
 if($safe -notmatch '^(HTTP assertion failed:|race barrier|Synthetic)'){$safe='Phase B harness failed at line '+$_.InvocationInfo.ScriptLineNumber+'; last passed '+($results|Select-Object -Last 1).test+'; type '+$_.Exception.GetType().Name}
 throw $safe
}finally{
 # Exact generated fixture directory only; never recursive-delete a computed workspace.
 if((Split-Path $temp -Parent) -ne $env:TEMP -or (Split-Path $temp -Leaf) -notmatch '^MeetoPhase06-fixtures-[0-9a-f]{32}$'){throw 'Unsafe fixture cleanup path'}
 if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Recurse}
 $state=$null;$AnonKey=$null
}
