param(
    [Parameter(Mandatory=$true)][string]$CredentialRequestPath,
    [string]$Adb = 'F:\android\Sdk\platform-tools\adb.exe',
    [ValidateSet('Task11CTwoUserUiTest','Task11CAuthStorageTest')][string]$TestClass='Task11CTwoUserUiTest',
    [switch]$MessengerOnly
)
$ErrorActionPreference='Stop'
# The owner-provided attachment is read into memory. Credentials are never command arguments.
$request=[IO.File]::ReadAllText($CredentialRequestPath,[Text.Encoding]::UTF8)
$emails=@([regex]::Matches($request,'(?im)^Email:\s*`([^`]+)`') | ForEach-Object {$_.Groups[1].Value})
$password=[regex]::Match($request,'Password:\s*`([^`]+)`').Groups[1].Value
if($emails.Count -ne 2 -or $password.Length -lt 8){throw 'Credential input is unavailable'}
$job=$null
$client=$null
$passed=$false
try {
    & $Adb forward tcp:38711 tcp:38711 | Out-Null
    if($LASTEXITCODE -ne 0){throw 'ADB test channel unavailable'}
    $job=Start-Job -ArgumentList $Adb,$TestClass,([bool]$MessengerOnly) -ScriptBlock {
        param($Tool,$Class,$OnlyMessenger)
        & $Tool shell am instrument -w -e class "com.example.meetomxh.$Class" -e messenger_only $OnlyMessenger.ToString().ToLowerInvariant() com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(90)
    $ready=$false
    while([DateTime]::UtcNow -lt $deadline -and $job.State -eq 'Running'){
        $lines=@(Receive-Job $job)
        foreach($line in $lines){
            Write-Output $line
            if([string]$line -match 'Credential receiver ready'){$ready=$true}
        }
        if($ready){break}
        Wait-Job $job -Timeout 1 | Out-Null
    }
    if(-not $ready){throw 'Android credential receiver did not become ready'}
    while([DateTime]::UtcNow -lt $deadline){
        $client=[Net.Sockets.TcpClient]::new()
        try {
            $client.Connect('127.0.0.1',38711)
            $writer=[IO.StreamWriter]::new($client.GetStream(),[Text.UTF8Encoding]::new($false))
            $writer.WriteLine((@{emails=$emails;password=$password}|ConvertTo-Json -Compress))
            $writer.Flush()
            $writer.Dispose()
            break
        } catch {
            $client.Dispose()
            $client=$null
            Start-Sleep -Milliseconds 250
        }
    }
    if($null -eq $client){throw 'Android test credential receiver unavailable'}
    $password=$null;$emails=$null;$request=$null
    while($job.State -eq 'Running'){
        foreach($line in @(Receive-Job $job)){
            Write-Output $line
            if([string]$line -match '^OK \(1 test\)'){$passed=$true}
        }
        Wait-Job $job -Timeout 2 | Out-Null
    }
    foreach($line in @(Receive-Job $job)){
        Write-Output $line
        if([string]$line -match '^OK \(1 test\)'){$passed=$true}
    }
    if(-not $passed){throw 'Instrumentation did not report OK; see sanitized test output'}
} finally {
    $password=$null;$emails=$null;$request=$null
    if($client){$client.Dispose()}
    if($job){Stop-Job $job -ErrorAction SilentlyContinue;Remove-Job $job -Force -ErrorAction SilentlyContinue}
    & $Adb forward --remove tcp:38711 | Out-Null
}
