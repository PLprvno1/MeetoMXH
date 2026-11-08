# Reels rendering improvements

- Reaction, bookmark, follow and comment updates bind only the social controls. They preserve the video surface, cover request, caption layout and gesture detector.
- Periodic access checks use the same partial update when the content version, caption and audience have not changed. Actual content changes still perform a full bind; unavailable content is still removed.
- Recording a view no longer rebinds a page whose UI does not display view counts.
- Full-screen pages have no RecyclerView change animations and retain two nearby page views. Playback still uses one player; no additional video decoders or video downloads are started for cached views.
- Token renewal for playback runs in the existing cancellable media worker. The UI creates the player without making an authentication request; authorized playback receives the current token before preparation.

Validation:

```powershell
$env:JAVA_HOME='F:/android/jbr'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -I scripts/reels-rendering-tests.init.gradle --no-configuration-cache --console=plain
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
F:/uddd/platform-tools/adb.exe shell am instrument -w -r -e class com.example.meetomxh.ReelsRenderingTest com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner
```

The local instrumentation fixture checks that engagement and unchanged-content access reconciliation preserve the holder, image request, gesture detector and player surface ownership while updating counts. Editing a caption still causes a full bind. It issues no mutation RPCs. The isolated test script avoids unrelated legacy instrumentation compilation errors.

These changes reduce unnecessary UI work; they do not eliminate network buffering or establish an app-wide FPS benchmark.
