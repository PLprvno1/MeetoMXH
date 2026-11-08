[CmdletBinding()]
param(
    [string]$SourceRoot = (Join-Path $PSScriptRoot '..\MeetoMusicSource\MeetoMusicReady'),
    [string]$ManifestPath = (Join-Path $PSScriptRoot '..\music_library_manifest.json'),
    [switch]$ConfirmUpload,
    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

if ($ConfirmUpload -and $VerifyOnly) {
    throw 'Choose either -ConfirmUpload or -VerifyOnly, not both.'
}
$script:verifyOnly = [bool]$VerifyOnly

function Get-Sha256FromStream([System.IO.Stream]$Stream) {
    $hash = [System.Security.Cryptography.IncrementalHash]::CreateHash(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        [byte[]]$buffer = New-Object byte[] 65536
        [long]$length = 0
        while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $hash.AppendData($buffer, 0, $read)
            $length += $read
        }
        $digest = [BitConverter]::ToString($hash.GetHashAndReset()).Replace('-', '').ToLowerInvariant()
        return [pscustomobject]@{ sha256 = $digest; size_bytes = $length }
    }
    finally {
        $hash.Dispose()
    }
}

function New-StorageRequest([string]$Method, [string]$Url, [System.Net.Http.HttpContent]$Content = $null) {
    if ($script:verifyOnly -and $Method -ne 'GET') {
        throw 'VerifyOnly permits GET requests only.'
    }
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Url)
    [void]$request.Headers.TryAddWithoutValidation('apikey', $script:serviceKey)
   if (-not $script:serviceKey.StartsWith('sb_secret_')) {
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $script:serviceKey)
}
    if ($null -ne $Content) { $request.Content = $Content }
    return $request
}

function Get-RemoteObject([string]$ObjectUrl) {
    $request = New-StorageRequest 'GET' $ObjectUrl
    try {
        $response = $script:http.SendAsync(
            $request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        try {
            $status = [int]$response.StatusCode
            if ($status -eq 404) { return [pscustomobject]@{ status = 404; sha256 = $null; size_bytes = 0; mime_type = $null } }
            if (-not $response.IsSuccessStatusCode) {
                return [pscustomobject]@{ status = $status; sha256 = $null; size_bytes = 0; mime_type = $null }
            }
            $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
            try { $digest = Get-Sha256FromStream $stream }
            finally { $stream.Dispose() }
            $mime = if ($null -ne $response.Content.Headers.ContentType) {
                $response.Content.Headers.ContentType.MediaType
            } else { $null }
            return [pscustomobject]@{
                status = $status; sha256 = $digest.sha256; size_bytes = $digest.size_bytes; mime_type = $mime
            }
        }
        finally { $response.Dispose() }
    }
    finally { $request.Dispose() }
}

function Send-StorageObject([string]$ObjectUrl, [string]$FilePath) {
    $file = [System.IO.File]::OpenRead($FilePath)
    $content = [System.Net.Http.StreamContent]::new($file)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('audio/ogg')
    $request = New-StorageRequest 'POST' $ObjectUrl $content
    [void]$request.Headers.TryAddWithoutValidation('x-upsert', 'false')
    try {
        $response = $script:http.SendAsync($request).GetAwaiter().GetResult()
        try { return [int]$response.StatusCode }
        finally { $response.Dispose() }
    }
    finally { $request.Dispose() }
}

function Get-StorageListing([string]$ListUrl) {
    $body = @{ prefix = 'v1'; limit = 1000; offset = 0 } | ConvertTo-Json -Compress
    $content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
    $request = New-StorageRequest 'POST' $ListUrl $content
    try {
        $response = $script:http.SendAsync($request).GetAwaiter().GetResult()
        try {
            if (-not $response.IsSuccessStatusCode) {
                throw "Storage list API failed with HTTP $([int]$response.StatusCode)."
            }
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            return ,(@($text | ConvertFrom-Json))
        }
        finally { $response.Dispose() }
    }
    finally { $request.Dispose() }
}

$manifestFile = (Resolve-Path -LiteralPath $ManifestPath).Path
$manifest = Get-Content -Raw -LiteralPath $manifestFile | ConvertFrom-Json

if ($VerifyOnly) {
    if ($manifest.manifest_version -ne 1 -or $manifest.bucket -ne 'music-library' -or
        $manifest.mime_type -ne 'audio/ogg' -or $manifest.object_prefix -ne 'v1' -or
        [string]::IsNullOrWhiteSpace([string]$manifest.catalog_version) -or $manifest.tracks.Count -ne 50) {
        throw 'Manifest contract mismatch; no Storage requests were made.'
    }
    if (@($manifest.tracks.track_id | Sort-Object -Unique).Count -ne 50 -or
        @($manifest.tracks.object_path | Sort-Object -Unique).Count -ne 50) {
        throw 'Manifest IDs or object paths are not unique; no Storage requests were made.'
    }
    foreach ($entry in $manifest.tracks) {
        if ($entry.track_id -cnotmatch '^meeto_music_[0-9]{3}$' -or
            $entry.catalog_version -cne $manifest.catalog_version -or
            $entry.bucket -cne 'music-library' -or $entry.mime_type -cne 'audio/ogg' -or
            $entry.object_path -cne "v1/$($entry.track_id).ogg" -or
            $entry.codec -cne 'vorbis' -or $entry.container -cne 'ogg' -or
            $entry.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            [long]$entry.size_bytes -lt 1 -or [long]$entry.size_bytes -gt 5242880) {
            throw "Manifest entry $($entry.track_id) is invalid; no Storage requests were made."
        }
    }
}
else {
    $sourcePath = (Resolve-Path -LiteralPath $SourceRoot).Path
    $catalogFile = Join-Path $sourcePath 'music_catalog.json'
    $musicPath = Join-Path $sourcePath 'music'
    $catalog = Get-Content -Raw -LiteralPath $catalogFile | ConvertFrom-Json

    if ($manifest.manifest_version -ne 1 -or $manifest.bucket -ne 'music-library' -or
        $manifest.mime_type -ne 'audio/ogg' -or $manifest.object_prefix -ne 'v1' -or
        $manifest.catalog_version -ne $catalog.catalog_id -or $manifest.tracks.Count -ne 50 -or
        $catalog.track_count -ne 50 -or $catalog.tracks.Count -ne 50) {
        throw 'Manifest/catalog contract mismatch; no Storage requests were made.'
    }
    if (@($manifest.tracks.track_id | Sort-Object -Unique).Count -ne 50 -or
        @($manifest.tracks.object_path | Sort-Object -Unique).Count -ne 50) {
        throw 'Manifest IDs or object paths are not unique; no Storage requests were made.'
    }

    $sourceNames = @((Get-ChildItem -LiteralPath $musicPath -File -Filter '*.ogg').Name | Sort-Object)
    $manifestNames = @($manifest.tracks.filename | Sort-Object)
    if ($sourceNames.Count -ne 50 -or (Compare-Object $sourceNames $manifestNames)) {
        throw 'The local OGG set differs from the 50-track manifest; no Storage requests were made.'
    }

    # Validate every source byte and its catalog mapping before contacting Supabase.
    foreach ($entry in $manifest.tracks) {
        $track = @($catalog.tracks | Where-Object { $_.id -ceq $entry.track_id })
        if ($track.Count -ne 1 -or $entry.catalog_version -ne $catalog.catalog_id -or
            $entry.bucket -ne 'music-library' -or $entry.mime_type -ne 'audio/ogg' -or
            $entry.object_path -ne "v1/$($entry.track_id).ogg" -or
            $entry.filename -cne $track[0].filename -or $entry.sha256 -cne $track[0].sha256 -or
            [long]$entry.size_bytes -ne [long]$track[0].size_bytes -or
            [int]$entry.duration_ms -ne [int]$track[0].duration_ms -or
            $entry.codec -cne 'vorbis' -or $entry.container -cne 'ogg') {
            throw "Manifest entry $($entry.track_id) does not match the source catalog; no Storage requests were made."
        }
        $localFile = Join-Path $musicPath $entry.filename
        $localHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $localFile).Hash.ToLowerInvariant()
        $localSize = (Get-Item -LiteralPath $localFile).Length
        if ($localHash -cne $entry.sha256 -or $localSize -ne [long]$entry.size_bytes) {
            throw "Local source verification failed for $($entry.track_id); no Storage requests were made."
        }
    }
}

if (-not $ConfirmUpload -and -not $VerifyOnly) {
    Write-Output 'Local source and manifest checks passed for 50 tracks. No Supabase request was made; rerun with -ConfirmUpload only after owner preflight.'
    return
}

$supabaseUrl = $env:SUPABASE_URL
$script:serviceKey = $env:SUPABASE_SERVICE_ROLE_KEY
if ([string]::IsNullOrWhiteSpace($supabaseUrl) -or [string]::IsNullOrWhiteSpace($script:serviceKey)) {
    throw 'Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY in a secure process environment before running this helper.'
}
if ($supabaseUrl -notmatch '^https://[^/]+/?$') { throw 'SUPABASE_URL must be an HTTPS project URL.' }
$supabaseUrl = $supabaseUrl.TrimEnd('/')
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromMinutes(3)
$outcomes = [System.Collections.Generic.List[string]]::new()
$failures = [System.Collections.Generic.List[string]]::new()

if ($VerifyOnly) {
    $verified = 0
    try {
        foreach ($entry in $manifest.tracks) {
            $escapedPath = (($entry.object_path -split '/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
            $objectUrl = "$supabaseUrl/storage/v1/object/authenticated/music-library/$escapedPath"

            try {
                $remote = Get-RemoteObject $objectUrl
            }
            catch {
                $failures.Add("$($entry.track_id): Storage GET failed ($($_.Exception.GetType().Name))")
                continue
            }

            if ($remote.status -ne 200) {
                $failures.Add("$($entry.track_id): Storage GET returned HTTP $($remote.status)")
                continue
            }
            $mismatches = [System.Collections.Generic.List[string]]::new()
            if ($remote.sha256 -cne $entry.sha256) { $mismatches.Add('SHA-256') }
            if ($remote.size_bytes -ne [long]$entry.size_bytes) { $mismatches.Add('size') }
            if ($remote.mime_type -cne 'audio/ogg') { $mismatches.Add('MIME') }
            if ($mismatches.Count -gt 0) {
                $failures.Add("$($entry.track_id): mismatch in $($mismatches -join ', ')")
                continue
            }
            $verified++
            $outcomes.Add("$($entry.track_id): verified")
        }
    }
    finally {
        $script:http.Dispose()
        $script:serviceKey = $null
    }

    $outcomes | ForEach-Object { Write-Output $_ }
    if ($failures.Count -gt 0) {
        $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
        throw "Storage verification incomplete: $verified/50 objects passed; $($failures.Count) issue(s). No Storage writes were issued."
    }
    Write-Output "Verified $verified/50 music-library objects by GET, SHA-256, size and MIME. No Storage writes were issued."
    Write-Output 'Inventory of extra objects is not checked by this GET-only mode; confirm it with the read-only Cloud SQL inventory query.'
    return
}

try {
    foreach ($entry in $manifest.tracks) {
        $escapedPath = (($entry.object_path -split '/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
        $objectUrl = "$supabaseUrl/storage/v1/object/music-library/$escapedPath"
        $localFile = Join-Path $musicPath $entry.filename
        $remote = Get-RemoteObject $objectUrl

        if ($remote.status -eq 404) {
            $uploadStatus = Send-StorageObject $objectUrl $localFile
            $remote = Get-RemoteObject $objectUrl
            if ($remote.status -ne 200 -or $remote.sha256 -cne $entry.sha256 -or
                $remote.size_bytes -ne [long]$entry.size_bytes -or $remote.mime_type -cne 'audio/ogg') {
                $failures.Add("$($entry.track_id): upload HTTP $uploadStatus; object verification did not match")
                continue
            }
            $outcomes.Add("$($entry.track_id): uploaded and verified")
            continue
        }

        if ($remote.status -ne 200) {
            $failures.Add("$($entry.track_id): Storage GET returned HTTP $($remote.status)")
            continue
        }
        if ($remote.sha256 -cne $entry.sha256 -or $remote.size_bytes -ne [long]$entry.size_bytes -or
            $remote.mime_type -cne 'audio/ogg') {
            $failures.Add("$($entry.track_id): existing object differs; left untouched")
            continue
        }
        $outcomes.Add("$($entry.track_id): existing object verified")
    }

    try {
        $listing = Get-StorageListing "$supabaseUrl/storage/v1/object/list/music-library"
        $listedNames = @($listing | ForEach-Object { ([string]$_.name -split '/')[-1] } | Sort-Object)
        $expectedNames = @($manifest.tracks | ForEach-Object { [IO.Path]::GetFileName($_.object_path) } | Sort-Object)
        if ($listedNames.Count -ne 50 -or (Compare-Object $listedNames $expectedNames)) {
            $failures.Add("v1 inventory: expected exactly 50 matching objects; Storage listed $($listedNames.Count)")
        }
        else { $outcomes.Add('v1 inventory: exactly 50 expected objects') }
    }
    catch { $failures.Add("v1 inventory: $($_.Exception.Message)") }
}
finally {
    $script:http.Dispose()
    $script:serviceKey = $null
}

$outcomes | ForEach-Object { Write-Output $_ }
if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    throw "Music library upload/verification incomplete: $($failures.Count) issue(s). Existing conflicting objects were not overwritten."
}
Write-Output 'Verified all 50 music-library objects. Review the read-only post-upload SQL before activating catalog rows.'
