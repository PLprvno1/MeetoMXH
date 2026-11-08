# Music preview and Reel export duration fix

Confirmed from the device's actual cached catalog, Ogg files, Reel drafts and `MeetoMusic` logs on 2026-11-08.

## Causes

1. The existing duration check allowed only 1,000 microseconds of difference. Android MediaExtractor reported these durations for checksum-verified catalog files:

| Track | Catalog duration (ms) | Extractor duration (µs) | Difference (ms) |
| --- | ---: | ---: | ---: |
| meeto_music_002 | 144055 | 144061791 | +6.791 |
| meeto_music_018 | 47998 | 48005215 | +7.215 |
| meeto_music_020 | 63993 | 64000000 | +7.000 |

All three were incorrectly rejected as changed files. The regression test reproduced three composition failures before the fix. Duration validation now permits up to 50 ms of reader/container variation, still rejects absent or substantially different durations, and retains the cache's size/SHA-256 integrity checks. Library loop planning clamps a near-end offset to the actual source boundary while retaining catalog metadata identity.

2. The editor uses MediaMetadataRetriever's millisecond container endpoint, while `MusicComposition` supplied only MediaExtractor's microsecond duration to Media3. Two actual drafts reported 1,548 ms versus 1,547,500 µs and 2,361 ms versus 2,360,900 µs. The selected endpoint exceeded the declared source duration by 500 µs or 100 µs. Media3 1.11.1 `EditedMediaItem.getClippedDuration()` rejects `endPositionUs > durationUs`, causing the synchronous IllegalArgumentException during `CompositionPlayer.setComposition()` shown in the screenshot.

`mediaDurationUs()` now uses the maximum of the full container and extracted track durations. It still declares source duration before clipping, consistent with the [Media3 EditedMediaItem.Builder contract](https://developer.android.com/reference/androidx/media3/transformer/EditedMediaItem.Builder).

Both preview and export share these fixes through `MusicComposition`; no catalog migration, RPC or publishing behavior was changed.

## Verification

- Debug build installed successfully on API 37.
- **26 music unit tests passed**, including two new tests covering the observed duration differences and rejecting missing/truncated/incompatible durations.
- **Two real-file instrumentation tests passed** after the fix. Composition construction/serialization succeeded across all seven cached tracks, three existing video drafts, offset zero and a near-end loop offset (42 combinations).
- Real CompositionPlayer previews advanced beyond 300 ms for `meeto_music_001` with looping and `meeto_music_002` without looping. Both exported locally through the production `ReelClipExporter`.
- Both MP4s contained one H.264 video track and one AAC audio track. Their 2,309 ms output duration matched the selected 2,262 ms interval within the app's existing validation tolerances. Music publishing metadata validation passed. Test output files were removed; no Reel was uploaded or published.
- A later expanded run attempted actual preview/export for both tracks on all three videos. The emulator was closed during that run, so the expanded run is **incomplete**, not reported as passed. The production code was unchanged between the successful run and this expansion.

The integration tests intentionally use real, already downloaded device fixtures. They require a cached catalog, music tracks 001/002 and existing `files/reel-drafts/*/source.video` files; no credentials or user media are committed to the repository.

```powershell
$env:JAVA_HOME='F:/android/jbr'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests 'com.example.meetomxh.music.*' -I scripts/music-composition-tests.init.gradle --no-configuration-cache --console=plain
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
F:/uddd/platform-tools/adb.exe shell am instrument -w -r -e class com.example.meetomxh.music.MusicCompositionIntegrationTest,com.example.meetomxh.MusicPreviewTest com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner
```
