param()
$ErrorActionPreference='Stop'
. "$PSScriptRoot/PhaseB-Local.ps1"
Assert-MeetoLocal
$count=Invoke-MeetoSql "SELECT count(*) FROM _realtime.tenants WHERE external_id='realtime-dev';"
if(($count -join '') -ne '1'){throw 'Unexpected local Realtime tenant inventory'}
$before=Invoke-MeetoSql "SELECT private_only FROM _realtime.tenants WHERE external_id='realtime-dev';"
if(($before -join '') -ne 't'){
 $c=(docker inspect supabase_realtime_MeetoMXH|ConvertFrom-Json)[0]
 if($c.Config.Labels.'com.supabase.cli.workdir' -ne 'F:\MeetoMXH'){throw 'Wrong local Realtime container'}
 # Use the running service API to update DB + cache + disconnect permissions.
 # Fixed tenant bytes avoid shell quoting and prevent any secret-bearing result struct.
 $expr='case Realtime.Api.update_tenant_by_external_id(List.to_string([114,101,97,108,116,105,109,101,45,100,101,118]), %{private_only: true}) do {:ok, _} -> :ok; _ -> raise ArgumentError end'
 $null=docker exec supabase_realtime_MeetoMXH /app/bin/realtime rpc $expr
 if($LASTEXITCODE -ne 0){throw 'Local managed Realtime API configuration failed'}
}
$after=Invoke-MeetoSql "SELECT private_only FROM _realtime.tenants WHERE external_id='realtime-dev';"
if(($after -join '') -ne 't'){throw 'Private-only Realtime setting did not persist'}
Write-Output 'Local Realtime private_only=true; no public channels'
