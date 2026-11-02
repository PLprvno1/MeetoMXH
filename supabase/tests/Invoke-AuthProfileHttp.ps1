# Real GoTrue/PostgREST JWT tests, LOCAL Supabase + Mailpit ONLY. Never remote.
# Tokens, passwords, confirmation hashes and Auth response bodies stay in memory.
param([string]$SupabaseCli='supabase',[string]$AnonKey=$env:MEETO_LOCAL_ANON_KEY)
$ErrorActionPreference='Stop'
$api='http://127.0.0.1:54321';$mail='http://127.0.0.1:54324'
if([string]::IsNullOrWhiteSpace($AnonKey)){throw 'Set MEETO_LOCAL_ANON_KEY privately from local CLI status; do not paste it into source/logs'}
$results=New-Object 'System.Collections.Generic.List[object]'
function Check([string]$label,[bool]$condition){
 if(!$condition){throw ('HTTP assertion failed: '+$label)}
 $results.Add([pscustomobject]@{test=$label;passed=$true})
}
function Request([string]$method,[string]$path,$body=$null,[string]$token=''){
 $headers=@{apikey=$AnonKey};if($token){$headers.Authorization='Bearer '+$token}
 $args=@{UseBasicParsing=$true;Uri=($api+$path);Method=$method;Headers=$headers;ContentType='application/json';TimeoutSec=15}
 if($null -ne $body){$args.Body=($body|ConvertTo-Json -Depth 8 -Compress)}
 try{$r=Invoke-WebRequest @args;return [pscustomobject]@{Status=[int]$r.StatusCode;Data=if($r.Content){$r.Content|ConvertFrom-Json}else{$null}}}
 catch{
  $response=$_.Exception.Response
  if($null -eq $response){throw 'Local Supabase HTTP unavailable; no remote fallback attempted'}
  # Do not emit raw exceptions/body/headers: Auth responses may contain secrets.
  $data=$null
  try{$data=$_.ErrorDetails.Message|ConvertFrom-Json}catch{}
  return [pscustomobject]@{Status=[int]$response.StatusCode;Data=$data}
 }
}
function Require200($response,[string]$label){Check $label ($response.Status -ge 200 -and $response.Status -lt 300);return $response.Data}
function Rpc([string]$name,$payload,[string]$token){return Request 'POST' ('/rest/v1/rpc/'+$name) $payload $token}
function Confirm-Mail([string]$email,[string]$kind){
 for($attempt=0;$attempt -lt 40;$attempt++){
  try{
   $messages=Invoke-RestMethod ($mail+'/api/v1/messages') -TimeoutSec 5
   foreach($m in @($messages.messages)){
    if(@($m.To | Where-Object {$_.Address -eq $email}).Count -eq 0){continue}
    $detail=Invoke-RestMethod ($mail+'/api/v1/message/'+$m.ID) -TimeoutSec 5
    $text=[Net.WebUtility]::HtmlDecode(($detail.Text+' '+$detail.HTML))
    $links=[regex]::Matches($text,'https?://[^\s<>"'']+/auth/v1/verify\?[^\s<>"'']+')
    foreach($link in $links){
     $uri=[uri]$link.Value
     if($uri.Host -notin @('127.0.0.1','localhost') -or $uri.Port -ne 54321){throw 'Unexpected nonlocal confirmation URL'}
     $query=@{};foreach($pair in $uri.Query.TrimStart('?').Split('&')){$p=$pair.Split('=',2);$query[$p[0]]=[uri]::UnescapeDataString($p[1])}
     if($query.type -ne $kind){continue}
     return Require200 (Request 'POST' '/auth/v1/verify' @{token_hash=$query.token;type=$kind}) ($kind+' email verified')
    }
   }
  }catch{if($_.Exception.Message -eq 'Unexpected nonlocal confirmation URL'){throw}}
  Start-Sleep -Milliseconds 250
 }
 throw 'Local Mailpit verification message unavailable (hash/body suppressed)'
}
function New-TestIdentity([string]$name,[bool]$onboard=$true){
 $email='meeto-a05-'+[guid]::NewGuid().ToString('N')+'@example.test'
 $password='M!a05-'+[guid]::NewGuid().ToString('N')
 $signup=Require200 (Request 'POST' '/auth/v1/signup' @{email=$email;password=$password;data=@{role='system_admin';onboarding_completed=$true}}) 'signup accepted'
 Check 'confirmation required before session' (!$signup.access_token)
 Check 'unverified login denied' ((Request 'POST' '/auth/v1/token?grant_type=password' @{email=$email;password=$password}).Status -eq 400)
 $verified=Confirm-Mail $email 'signup'
 $login=Require200 (Request 'POST' '/auth/v1/token?grant_type=password' @{email=$email;password=$password}) 'password login'
 Check 'verified access token issued' (![string]::IsNullOrWhiteSpace($login.access_token))
 $bootstrap=Require200 (Rpc 'bootstrap_profile' @{} $login.access_token) 'bootstrap with valid JWT'
 Check 'metadata did not auto-complete onboarding' (!$bootstrap.onboarding_completed)
 if(!$onboard){return @{Email=$email;Password=$password;Token=$login.access_token;Refresh=$login.refresh_token;Id=[string]$bootstrap.profile_id}}
 $profile=Require200 (Rpc 'complete_onboarding' @{p_display_name=$name;p_birth_date='2000-01-01';p_gender=$null;p_expected_version=1} $login.access_token) 'onboarding with valid JWT'
 return @{Email=$email;Password=$password;Token=$login.access_token;Refresh=$login.refresh_token;Id=[string]$profile.profile_id}
}
function Local-Operator([string]$sql){
 # CLI 2.122 db query uses row-returning prepared statements; DML/file batches
 # are unsuitable for fixtures. Use psql ONLY in this verified local container.
 $container=(docker inspect supabase_db_MeetoMXH | ConvertFrom-Json)[0]
 $workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
 if($container.Config.Labels.'com.supabase.cli.project' -ne 'MeetoMXH' -or
    $container.Config.Labels.'com.supabase.cli.workdir' -ne $workspace){throw 'Local operator fixture command failed: container scope mismatch'}
 $ErrorActionPreference='Continue'
 $null=$sql | docker exec -i supabase_db_MeetoMXH psql -X -U postgres -d postgres -v ON_ERROR_STOP=1 --single-transaction 2>&1
 $exitCode=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 if($exitCode -ne 0){throw 'Local operator fixture command failed'}
}
function Http-Race([string]$label,$identity,[string]$rpcA,$payloadA,[string]$rpcB,$payloadB,[int[]]$expected){
 # An operator lock is only a barrier. Both business requests enter PostgREST
 # concurrently with actual GoTrue JWTs; observer proves both waited on locks.
 Add-Type -AssemblyName System.Net.Http
 $process=New-Object Diagnostics.Process
 $process.StartInfo.FileName=(Get-Command docker).Source
 $process.StartInfo.Arguments='exec -i supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atq -v ON_ERROR_STOP=1'
 $process.StartInfo.UseShellExecute=$false
 $process.StartInfo.CreateNoWindow=$true
 $process.StartInfo.RedirectStandardInput=$true
 $process.StartInfo.RedirectStandardOutput=$true
 $process.StartInfo.RedirectStandardError=$true
 $client=New-Object Net.Http.HttpClient
 $client.Timeout=[TimeSpan]::FromSeconds(20)
 try{
  $null=$process.Start()
  $process.StandardInput.WriteLine("BEGIN; SELECT id FROM auth.users WHERE id='"+$identity.Id+"' FOR UPDATE;")
  $process.StandardInput.Flush()
  $ready=$process.StandardOutput.ReadLineAsync()
  if(!$ready.Wait(10000) -or $ready.Result -ne $identity.Id){throw 'HTTP race barrier failed'}
  $tasks=@()
  foreach($spec in @(@{rpc=$rpcA;payload=$payloadA},@{rpc=$rpcB;payload=$payloadB})){
   $message=New-Object Net.Http.HttpRequestMessage([Net.Http.HttpMethod]::Post,($api+'/rest/v1/rpc/'+$spec.rpc))
   $message.Headers.Add('apikey',$AnonKey)
   $message.Headers.Authorization=New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$identity.Token)
   $message.Content=New-Object Net.Http.StringContent(($spec.payload|ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json')
   $tasks+=@($client.SendAsync($message))
  }
  $overlap=$false
  for($i=0;$i -lt 40;$i++){
   $waiting=docker exec supabase_db_MeetoMXH psql -X -U postgres -d postgres -Atc "SELECT count(*) FROM pg_stat_activity WHERE usename='authenticator' AND wait_event_type='Lock';"
   if([int]$waiting -ge 2){$overlap=$true;break}
   Start-Sleep -Milliseconds 50
  }
  Check ($label+' observed two HTTP requests blocked concurrently') $overlap
  $process.StandardInput.WriteLine('COMMIT;')
  $process.StandardInput.WriteLine('\q')
  $process.StandardInput.Flush()
  $statuses=@($tasks|ForEach-Object {[int]$_.GetAwaiter().GetResult().StatusCode}|Sort-Object)
  Check ($label+' expected HTTP outcomes') (($statuses -join ',') -eq (($expected|Sort-Object) -join ','))
 }finally{
  if(!$process.HasExited){$process.StandardInput.Close();if(!$process.WaitForExit(5000)){$process.Kill()}}
  $process.Dispose();$client.Dispose()
 }
}
try{
 Check 'anon data denied' ((Request 'GET' '/rest/v1/profiles?select=id').Status -in 401,403)
 Check 'invalid JWT denied' ((Rpc 'get_my_profile' @{} 'invalid.jwt.signature').Status -eq 401)
 $a=New-TestIdentity 'HTTP Alice';$b=New-TestIdentity 'HTTP Bob'
 Check 'invalid signup email rejected 400' ((Request 'POST' '/auth/v1/signup' @{email='invalid';password=('M!'+[guid]::NewGuid().ToString('N'))}).Status -eq 400)
 Check 'weak signup password rejected' ((Request 'POST' '/auth/v1/signup' @{email=('weak-'+[guid]::NewGuid().ToString('N')+'@example.test');password='x'}).Status -eq 422)
 Check 'wrong password rejected' ((Request 'POST' '/auth/v1/token?grant_type=password' @{email=$a.Email;password='wrong-password'}).Status -eq 400)
 $duplicate=Request 'POST' '/auth/v1/signup' @{email=$a.Email;password=$a.Password}
 Check 'duplicate signup returns no session' ($duplicate.Status -in 200,422 -and !$duplicate.Data.access_token)
 $parts=$a.Token.Split('.')
 if($parts.Count -ne 3){throw 'Unexpected JWT format'}
 # Preserve valid header/payload but corrupt the signature; service must verify it.
 $badSig=if($parts[2].StartsWith('A')){'B'+$parts[2].Substring(1)}else{'A'+$parts[2].Substring(1)}
 Check 'forged JWT signature denied' ((Rpc 'get_my_profile' @{} ($parts[0]+'.'+$parts[1]+'.'+$badSig)).Status -eq 401)
 if($a.Id -notmatch '^[a-f0-9-]{36}$' -or $b.Id -notmatch '^[a-f0-9-]{36}$'){throw 'Unexpected Auth UUID format'}
 $payloads=[ordered]@{
  bootstrap_profile=@{};get_my_profile=@{}
  complete_onboarding=@{p_display_name='HTTP Alice';p_birth_date='2000-01-01';p_gender=$null;p_expected_version=2}
  update_profile=@{p_display_name='HTTP Alice';p_bio='';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=2}
  update_profile_private=@{p_birth_date='2000-01-01';p_gender=$null;p_expected_version=2}
  get_profile=@{p_target=$b.Id}
  search_profiles=@{p_prefix='HTTP';p_after_name=$null;p_after_id=$null;p_limit=20}
  set_block=@{p_target=$b.Id;p_blocked=$true}
 }
 foreach($rpc in $payloads.Keys){
  Check ("$rpc missing JWT denied") ((Rpc $rpc $payloads[$rpc] '').Status -in 401,403)
  Check ("$rpc forged JWT denied") ((Rpc $rpc $payloads[$rpc] ($parts[0]+'.'+$parts[1]+'.'+$badSig)).Status -eq 401)
  $spoof=@{};foreach($key in $payloads[$rpc].Keys){$spoof[$key]=$payloads[$rpc][$key]};$spoof.p_actor=$b.Id
  $r=Rpc $rpc $spoof $a.Token
  Check ("$rpc client actor parameter rejected") ($r.Status -eq 404 -and $r.Data.code -eq 'PGRST202')
 }
 $public=Require200 (Rpc 'get_profile' @{p_target=$b.Id} $a.Token) 'get public other profile'
 Check 'other profile has no private fields' (!$public.PSObject.Properties['birth_date'] -and !$public.PSObject.Properties['gender'] -and !$public.PSObject.Properties['settings'])
 $found=Require200 (Rpc 'search_profiles' $payloads.search_profiles $a.Token) 'search profiles via real JWT'
 Check 'search includes public peer without private fields' (@($found|Where-Object profile_id -eq $b.Id).Count -eq 1 -and !($found|ConvertTo-Json -Depth 5).Contains('birth_date'))
 Check 'search invalid limit 422' ((Rpc 'search_profiles' @{p_prefix='HTTP';p_limit=51} $a.Token).Status -eq 422)
 Check 'block self 422' ((Rpc 'set_block' @{p_target=$a.Id;p_blocked=$true} $a.Token).Status -eq 422)
 $null=Require200 (Rpc 'set_block' $payloads.set_block $a.Token) 'block peer'
 $null=Require200 (Rpc 'set_block' $payloads.set_block $a.Token) 'block retry'
 Check 'block hides profile both directions' ((Rpc 'get_profile' @{p_target=$a.Id} $b.Token).Status -eq 404 -and (Rpc 'get_profile' @{p_target=$b.Id} $a.Token).Status -eq 404)
 $hidden=Require200 (Rpc 'search_profiles' $payloads.search_profiles $a.Token) 'blocked search'
 Check 'blocked peer absent from search' (@($hidden|Where-Object profile_id -eq $b.Id).Count -eq 0)
 $null=Require200 (Rpc 'set_block' @{p_target=$b.Id;p_blocked=$false} $a.Token) 'unblock peer'
 $null=Require200 (Rpc 'set_block' @{p_target=$b.Id;p_blocked=$false} $a.Token) 'unblock retry'
 # Auth issues this token while confirmed. Removing confirmation as local operator
 # checks authoritative auth.users, even though the signed token remains valid.
 Local-Operator ("UPDATE auth.users SET email_confirmed_at=NULL WHERE id='"+$a.Id+"';")
 foreach($rpc in $payloads.Keys){
  $r=Rpc $rpc $payloads[$rpc] $a.Token
  $expected=if($rpc -in 'bootstrap_profile','get_my_profile'){200}else{403}
  Check ("$rpc unverified account gate") ($r.Status -eq $expected)
 }
 Local-Operator ("UPDATE auth.users SET email_confirmed_at=now() WHERE id='"+$a.Id+"';")
 $c=New-TestIdentity 'HTTP Incomplete' $false
 foreach($rpc in $payloads.Keys){
  if($rpc -eq 'complete_onboarding'){continue}
  $r=Rpc $rpc $payloads[$rpc] $c.Token
  $expected=if($rpc -in 'bootstrap_profile','get_my_profile'){200}else{403}
  Check ("$rpc incomplete onboarding gate") ($r.Status -eq $expected)
 }
 Check 'incomplete onboarding invalid fields 422' ((Rpc 'complete_onboarding' @{p_display_name='';p_birth_date='2000-01-01';p_gender=$null;p_expected_version=1} $c.Token).Status -eq 422)
 Check 'active cannot see incomplete placeholder' ((Rpc 'get_profile' @{p_target=$c.Id} $a.Token).Status -eq 404)
 Local-Operator ("DELETE FROM public.profiles WHERE id='"+$c.Id+"';")
 Check 'Auth account without profile reports bootstrap required' ((Rpc 'get_my_profile' @{} $c.Token).Status -eq 409)
 $recovered=Require200 (Rpc 'bootstrap_profile' @{} $c.Token) 'recover entirely missing profile after real signup'
 $recoveredAgain=Require200 (Rpc 'bootstrap_profile' @{} $c.Token) 'full bootstrap recovery retry'
 Check 'full recovery retains identity and stable initial version' ($recovered.profile_id -eq $c.Id -and $recoveredAgain.version -eq 1 -and !$recoveredAgain.onboarding_completed)
 foreach($state in 'suspended','deleting'){
  Local-Operator ("UPDATE public.account_status SET state='"+$state+"' WHERE user_id='"+$a.Id+"';")
  $login=Require200 (Request 'POST' '/auth/v1/token?grant_type=password' @{email=$a.Email;password=$a.Password}) ("$state Auth login still issues JWT; application gate required")
  foreach($rpc in $payloads.Keys){
   $r=Rpc $rpc $payloads[$rpc] $login.access_token
   $expected=if($rpc -in 'bootstrap_profile','get_my_profile'){200}else{403}
   Check ("$rpc $state account gate") ($r.Status -eq $expected)
   if($expected -eq 200){Check ("$rpc $state minimal envelope") ($r.Data.account_state -eq $state -and @($r.Data.PSObject.Properties).Count -eq 2)}
  }
  $raw=Require200 (Request 'GET' '/rest/v1/profiles?select=id' $null $login.access_token) ("$state raw RLS request")
  Check ("$state raw profiles empty") (@($raw).Count -eq 0)
 }
 Local-Operator ("UPDATE public.account_status SET state='active' WHERE user_id='"+$a.Id+"';")
 $retry=Require200 (Rpc 'complete_onboarding' @{p_display_name='HTTP Alice';p_birth_date='2000-01-01';p_gender=$null;p_expected_version=1} $a.Token) 'onboard retry'
 Check 'onboard retry version stable' ($retry.version -eq 2)
 Check 'underage private update rejected' ((Rpc 'update_profile_private' @{p_birth_date=(Get-Date).ToString('yyyy-MM-dd');p_gender=$null;p_expected_version=2} $a.Token).Status -eq 422)
 $validPrivate=Require200 (Rpc 'update_profile_private' @{p_birth_date='2001-02-03';p_gender='local';p_expected_version=2} $b.Token) 'valid private update'
 Check 'private update increments version' ($validPrivate.version -eq 3)
 $privateRetry=Require200 (Rpc 'update_profile_private' @{p_birth_date='2001-02-03';p_gender='local';p_expected_version=2} $b.Token) 'private update retry'
 Check 'private retry stable version' ($privateRetry.version -eq 3)
 $conflict=Rpc 'update_profile_private' @{p_birth_date='2002-02-03';p_gender=$null;p_expected_version=2} $b.Token
 Check 'private stale version maps PT409' ($conflict.Status -eq 409 -and $conflict.Data.code -eq 'PT409')
 $badUpdate=Rpc 'update_profile' @{p_display_name='SHOULD ROLLBACK';p_bio='';p_avatar_asset_id=[guid]::NewGuid().ToString();p_cover_asset_id=$null;p_expected_version=3} $b.Token
 Check 'asset validation maps PT422' ($badUpdate.Status -eq 422 -and $badUpdate.Data.code -eq 'PT422')
 $rolled=Require200 (Rpc 'get_my_profile' @{} $b.Token) 'profile after rejected asset'
 Check 'rejected asset leaves profile unchanged' ($rolled.version -eq 3 -and $rolled.display_name -eq 'HTTP Bob')
 foreach($table in 'profiles','profile_private','account_status','system_roles'){
  $patch=switch($table){profiles{@{onboarding_completed=$true}};profile_private{@{gender='spoof'}};account_status{@{state='active'}};system_roles{@{role='system_admin'}}}
  Check ('direct mutation denied '+$table) ((Request 'PATCH' ('/rest/v1/'+$table+'?'+$(if($table -eq 'profiles'){'id'}else{'user_id'})+'=eq.'+$b.Id) $patch $a.Token).Status -in 401,403)
 }
 $settings=Require200 (Request 'PATCH' ('/rest/v1/user_settings?user_id=eq.'+$a.Id) @{profile_audience='private'} $a.Token) 'own settings update'
 $null=Require200 (Request 'PATCH' ('/rest/v1/user_settings?user_id=eq.'+$b.Id) @{profile_audience='private'} $a.Token) 'other settings PATCH filtered by RLS'
 $bSettings=Require200 (Rpc 'get_my_profile' @{} $b.Token) 'other settings after unauthorized PATCH'
 Check 'other settings unchanged by RLS-filtered PATCH' ($bSettings.settings.profile_audience -eq 'public')
 $badSettings=Request 'PATCH' ('/rest/v1/user_settings?user_id=eq.'+$a.Id) @{profile_audience='public';notification_preferences=@{unknown=$true}} $a.Token
 Check 'settings JSON constraint enforced through HTTP' ($badSettings.Status -eq 400 -and $badSettings.Data.code -eq '23514')
 $ownSettings=Require200 (Rpc 'get_my_profile' @{} $a.Token) 'settings after rejected JSON'
 Check 'invalid settings PATCH rolls back all columns' ($ownSettings.settings.profile_audience -eq 'private')
 Check 'private other profile denied' ((Rpc 'get_profile' @{p_target=$a.Id} $b.Token).Status -eq 404)
 $private=Require200 (Request 'GET' ('/rest/v1/profile_private?user_id=eq.'+$a.Id) $null $b.Token) 'private raw query permitted but filtered'
 Check 'private fields filtered by RLS' (!$private -or @($private).Count -eq 0)
 $before=Require200 (Rpc 'get_my_profile' @{} $b.Token) 'B profile before A edit'
 $edit=Require200 (Rpc 'update_profile' @{p_display_name='HTTP Alice edited';p_bio='local fixture';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=2} $a.Token) 'A own edit'
 $editRetry=Require200 (Rpc 'update_profile' @{p_display_name='HTTP Alice edited';p_bio='local fixture';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=2} $a.Token) 'own edit retry'
 Check 'own edit retry stable version' ($editRetry.version -eq $edit.version)
 $stale=Rpc 'update_profile' @{p_display_name='stale';p_bio='';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=2} $a.Token
 Check 'public stale version maps PT409' ($stale.Status -eq 409 -and $stale.Data.code -eq 'PT409')
 $after=Require200 (Rpc 'get_my_profile' @{} $b.Token) 'B profile after A edit'
 Check 'A edit leaves B unchanged' ($before.version -eq $after.version -and $after.display_name -eq 'HTTP Bob')
 $refresh=Require200 (Request 'POST' '/auth/v1/token?grant_type=refresh_token' @{refresh_token=$a.Refresh}) 'refresh rotation'
 $a.Token=$refresh.access_token;$a.Refresh=$refresh.refresh_token
 $null=Require200 (Rpc 'get_my_profile' @{} $a.Token) 'refreshed JWT valid'
 Http-Race 'optimistic profile race' $b 'update_profile' @{p_display_name='HTTP Bob race A';p_bio='';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=3} 'update_profile' @{p_display_name='HTTP Bob race B';p_bio='';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=3} @(200,409)
 Http-Race 'bootstrap versus edit race' $b 'bootstrap_profile' @{} 'update_profile' @{p_display_name='HTTP Bob final';p_bio='';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=4} @(200,200)
 Http-Race 'same UID bootstrap retry race' $c 'bootstrap_profile' @{} 'bootstrap_profile' @{} @(200,200)
 $postRace=Require200 (Rpc 'get_my_profile' @{} $b.Token) 'profile after real HTTP races'
 Check 'HTTP races preserve final version and desired state' ($postRace.version -eq 5 -and $postRace.display_name -eq 'HTTP Bob final')
 Local-Operator ("UPDATE public.account_status SET state='suspended' WHERE user_id='"+$a.Id+"'::uuid;")
 Check 'suspended valid JWT cannot mutate' ((Rpc 'update_profile' @{p_display_name='Blocked';p_bio='';p_avatar_asset_id=$null;p_cover_asset_id=$null;p_expected_version=3} $a.Token).Status -eq 403)
 $locked=Require200 (Rpc 'get_my_profile' @{} $a.Token) 'suspended status envelope'
 Check 'suspended no private payload' ($locked.account_state -eq 'suspended' -and !$locked.PSObject.Properties['birth_date'])
 $boot=Require200 (Rpc 'bootstrap_profile' @{} $a.Token) 'suspended recovery request'
 Check 'bootstrap cannot reactivate' ($boot.account_state -eq 'suspended')
 Local-Operator ("UPDATE public.account_status SET state='active' WHERE user_id='"+$a.Id+"'::uuid; DELETE FROM public.profile_private WHERE user_id='"+$b.Id+"'::uuid;")
 Check 'missing profile component detected' ((Rpc 'get_my_profile' @{} $b.Token).Status -eq 409)
 $null=Require200 (Rpc 'bootstrap_profile' @{} $b.Token) 'missing component recovered'
 $null=Require200 (Request 'POST' '/auth/v1/recover' @{email=$a.Email}) 'password recovery email requested'
 $recovery=Confirm-Mail $a.Email 'recovery'
 $newPassword='M!reset-'+[guid]::NewGuid().ToString('N')
 $null=Require200 (Request 'PUT' '/auth/v1/user' @{password=$newPassword} $recovery.access_token) 'password reset through Auth'
 $null=Require200 (Request 'POST' '/auth/v1/token?grant_type=password' @{email=$a.Email;password=$newPassword}) 'login with reset password'
 Check 'old password fails after reset' ((Request 'POST' '/auth/v1/token?grant_type=password' @{email=$a.Email;password=$a.Password}).Status -eq 400)
 $null=Require200 (Request 'POST' '/auth/v1/logout' @{} $b.Token) 'sign out'
 Check 'logout revokes refresh token' ((Request 'POST' '/auth/v1/token?grant_type=refresh_token' @{refresh_token=$b.Refresh}).Status -eq 400)
 Check 'signed access JWT remains usable until expiry after logout' ((Rpc 'get_my_profile' @{} $b.Token).Status -eq 200)
 # No user cleanup here: no unapproved Auth deletion/retention workflow. Reset ONLY local
 # synthetic stack manually when finished; the suite never resets a remote database.
 [pscustomobject]@{environment='LOCAL SUPABASE HTTP';passed=$results.Count;failed=0;tests=$results}|ConvertTo-Json -Depth 5
}catch{
 $safe=$_.Exception.Message
 if($safe -notmatch '^(HTTP assertion failed: |Local operator fixture command failed|Local Mailpit verification message unavailable|Unexpected Auth UUID format|Unexpected JWT format|Unexpected nonlocal confirmation URL)'){$safe='Local HTTP suite failed: '+$_.Exception.GetType().Name+' at line '+$_.InvocationInfo.ScriptLineNumber+'; last passed: '+($results|Select-Object -Last 1).test}
 throw $safe
}
