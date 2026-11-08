# Music preview audit

Fixed in `MusicMiniPlayer` and `OpenverseMusicPickerActivity`:

- Toggle uses playback intent, rather than audible playback, so a tap pauses a buffering or audio-focus-suppressed player. Ended tracks still restart from zero.
- The mini player owns a supplied player throughout attachment, seeking and playback setup, and closes it on setup failure. The picker releases only players it has not transferred, avoiding a second release on the same error path.
- An error that predates listener attachment is reported immediately. Late callbacks from a replaced player are ignored, and a failed preview cannot be marked open again by the picker.
- Freshly downloaded/cached audio is already checksum-verified and leased by the repository worker. It goes directly to playback instead of repeating the whole-file checksum on the UI thread. Restored preview files retain their existing validation.
- If a track disappears from a refreshed catalog during a download, its row is rendered again after loading stops.

Validation: debug build succeeded, 24 existing music unit tests passed, and four instrumentation tests passed on the API 37 emulator. The instrumentation tests use controlled Player doubles to exercise buffering, replay, replacement, late errors, preexisting errors and setup failures; they do not claim coverage of every real file/decoder or network failure.

```powershell
$env:JAVA_HOME='F:/android/jbr'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests 'com.example.meetomxh.music.*' -I scripts/music-preview-tests.init.gradle --no-configuration-cache --console=plain
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
F:/uddd/platform-tools/adb.exe shell am instrument -w -r -e class com.example.meetomxh.MusicPreviewTest com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner
```
