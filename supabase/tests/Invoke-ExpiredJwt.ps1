# LOCAL ONLY. Temporarily shorten token lifetime, preserve all Docker volumes,
# and restore config bytes and normal services in finally. No credentials on disk.
param([Parameter(Mandatory=$true)][string]$SupabaseCli)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath("$PSScriptRoot/../..")
Set-Location $workspace
$config=Join-Path $workspace 'supabase/config.toml'
$original=[IO.File]::ReadAllBytes($config)
$container=(docker inspect supabase_db_MeetoMXH|ConvertFrom-Json)[0]
if($container.Config.Labels.'com.supabase.cli.workdir' -ne $workspace){throw 'Wrong local stack'}
function Invoke-LocalCli([string[]]$arguments){
 $ErrorActionPreference='Continue'
 $null=& $SupabaseCli @arguments 2>&1
 $code=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 if($code -ne 0){throw 'Local CLI lifecycle command failed (output suppressed)'}
}
function Call([string]$path,$body,[string]$key,[string]$token=''){
 $headers=@{apikey=$key};if($token){$headers.Authorization='Bearer '+$token}
 try{
  $r=Invoke-WebRequest -UseBasicParsing -Uri ('http://127.0.0.1:54321'+$path) -Method POST -ContentType application/json -Headers $headers -Body ($body|ConvertTo-Json -Compress) -TimeoutSec 15
  return @{status=[int]$r.StatusCode;data=($r.Content|ConvertFrom-Json)}
 }catch{if(!$_.Exception.Response){throw 'Local HTTP unavailable'};return @{status=[int]$_.Exception.Response.StatusCode;data=$null}}
}
$checks=New-Object 'System.Collections.Generic.List[object]'
function Check([string]$label,[bool]$ok){if(!$ok){throw ('Expiry assertion failed: '+$label)};$checks.Add(@{test=$label;passed=$true})}
try{
 $text=[Text.Encoding]::UTF8.GetString($original)
 if($text -notmatch '(?m)^jwt_expiry = 3600\r?$'){throw 'Unexpected original JWT expiry'}
 $short=[regex]::Replace($text,'(?m)^jwt_expiry = 3600','jwt_expiry = 60')
 [IO.File]::WriteAllText($config,$short,(New-Object Text.UTF8Encoding($false)))
 Invoke-LocalCli @('stop')
 Invoke-LocalCli @('start','--exclude','edge-runtime,logflare,vector')
 Write-Host 'Short-lifetime local Auth ready; obtaining real GoTrue JWT.'
 $ErrorActionPreference='Continue'
 $rawState=& $SupabaseCli status -o json 2>$null
 $code=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 if($code -ne 0){throw 'Local status unavailable'}
 $state=$rawState|Out-String|ConvertFrom-Json
 $email='meeto-a05-exp-'+[guid]::NewGuid().ToString('N')+'@example.test'
 $password='M!exp-'+[guid]::NewGuid().ToString('N')
 $created=Call '/auth/v1/admin/users' @{email=$email;password=$password;email_confirm=$true} $state.SERVICE_ROLE_KEY $state.SERVICE_ROLE_KEY
 Check 'local Auth admin creates expiry fixture' ($created.status -eq 200)
 $login=Call '/auth/v1/token?grant_type=password' @{email=$email;password=$password} $state.ANON_KEY
 Check 'normal password login issues expiry JWT' ($login.status -eq 200 -and !!$login.data.access_token)
 $token=$login.data.access_token
 $payload=$token.Split('.')[1].Replace('-','+').Replace('_','/')
 $payload=$payload.PadRight($payload.Length+(4-$payload.Length%4)%4,'=')
 $claims=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payload))|ConvertFrom-Json
 Check 'GoTrue-issued JWT lifetime is 60 seconds' (($claims.exp-$claims.iat) -eq 60)
 Check 'same JWT accepted before expiry' ((Call '/rest/v1/rpc/get_my_profile' @{} $state.ANON_KEY $token).status -eq 200)
 Write-Host 'Waiting for the real issued JWT to expire (no signature or clock manipulation).'
 # PostgREST permits 30 seconds of clock skew for exp/iat/nbf.
 # https://docs.postgrest.org/en/v14/references/auth.html#jwt-claims-validation
 while([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() -le ($claims.exp+35)){Start-Sleep -Seconds 1}
 Check 'expired GoTrue JWT rejected by PostgREST 401' ((Call '/rest/v1/rpc/get_my_profile' @{} $state.ANON_KEY $token).status -eq 401)
 $refresh=Call '/auth/v1/token?grant_type=refresh_token' @{refresh_token=$login.data.refresh_token} $state.ANON_KEY
 Check 'refresh succeeds after access JWT expiry' ($refresh.status -eq 200)
 Check 'new GoTrue JWT accepted after refresh' ((Call '/rest/v1/rpc/get_my_profile' @{} $state.ANON_KEY $refresh.data.access_token).status -eq 200)
 $result=@{environment='LOCAL SUPABASE HTTP; genuine GoTrue expiration';passed=$checks.Count;failed=0;tests=$checks}
 $result|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 "$workspace/database_design/validation_phase_05b/expiry-results.json"
 Write-Host ('PASS '+$checks.Count+' genuine token expiry assertions')
}finally{
 [IO.File]::WriteAllBytes($config,$original)
 Invoke-LocalCli @('stop')
 Invoke-LocalCli @('start','--exclude','edge-runtime,logflare,vector')
 Write-Host 'Original config restored; normal local Supabase stack running.'
}
