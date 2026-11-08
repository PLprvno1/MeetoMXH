# Crash safety audit — 2026-11-08

The audit used lifecycle/callback and null-data searches across the Android source tree, Android Lint, unit tests and targeted emulator tests. This is a defensive stability pass, not a guarantee that every runtime crash has been eliminated. No Supabase SQL, authorization rule, RPC contract or product workflow was changed.

## Changes

- Settings, request center, tag management, group members, saved posts, shared posts, user posts and report screens now use the existing `ActivityRequests` helper. Requests are cancelled on destruction and callbacks cannot update a finished Activity. Saved-post mutation callbacks are guarded too.
- `ViewSafety` unwraps themed contexts to reject finished/destroyed Activity hosts before avatar/thumbnail requests. Cleanup still uses the application context, so recycling after Activity destruction remains valid.
- Feed shared-post requests are cancelled when their holder is rebound or recycled. Late responses check the host before loading images, and shared media entries are checked before dereferencing.
- Chat video thumbnail tasks are cancelled on rebind/recycle. Cancelled results dispose their bitmap, and late results cannot load into a destroyed screen.
- Feed view destruction stops shimmer, detaches the adapter and clears scroll listeners. Feed/inbox navigation and comment opening reject saved FragmentManager state.
- Reels seek, pause and retry handlers obtain the holder's current adapter position instead of retaining a position from binding time.
- Post/message DTO parsing handles null or non-object media entries, including shared-post and quoted-message media. Valid data retains its previous behavior.
- Core library desugaring supplies the existing `java.time` calls on API 24/25. Password autofill is guarded at API 26. The project previously declared minSdk 24 without these compatibility safeguards. Configuration follows [Android's Java API desugaring guidance](https://developer.android.com/studio/write/java8-support).
- Share preview callbacks additionally reject destroyed Activities and null media entries.

## Validation and remaining findings

- Debug APK built and installed on the API 37 emulator.
- **14/14 targeted instrumentation tests passed:** `CrashSafetyTest`, `ProfileLifecycleTest`, `FeedReactionRenderingTest`, `ReelsRenderingTest`, `MusicPreviewTest`.
- **81/82 unit tests passed.** `AdminRoleResolverTest.onlyExplicitSystemAdminDenialResolvesSocial` expects SOCIAL for `system_admin_required`, while the unchanged resolver returns RETRY. Authentication/role routing was deliberately left for a separate functional change.
- Lint findings fell from **362 errors / 1,240 warnings** to **325 errors / 1,241 warnings**. All 36 NewApi errors and the captured RecyclerView position error were eliminated. Lint still fails: remaining errors comprise Media3 unstable-API opt-in declarations (280), AppCompat tint declarations (23), raw constant annotations (17), legacy back handling (4), and one layout orientation declaration. These are retained and reported, not hidden with a baseline or global suppression.
- Instrumentation uses the selected test source set because unrelated older instrumentation files do not compile against the current app APIs.
- API 24/25 compatibility is checked through build configuration and lint; no API 24/25 emulator was available for runtime verification.

## Reproduce

```powershell
$env:JAVA_HOME='F:/android/jbr'
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -I scripts/crash-safety-tests.init.gradle --no-configuration-cache --console=plain
./gradlew.bat :app:testDebugUnitTest --no-configuration-cache --console=plain
./gradlew.bat :app:lintDebug --no-configuration-cache --console=plain
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
F:/uddd/platform-tools/adb.exe install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
F:/uddd/platform-tools/adb.exe shell am instrument -w -r -e class com.example.meetomxh.CrashSafetyTest,com.example.meetomxh.ProfileLifecycleTest,com.example.meetomxh.FeedReactionRenderingTest,com.example.meetomxh.ReelsRenderingTest,com.example.meetomxh.MusicPreviewTest com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner
```

Lint output: `app/build/reports/lint-results-debug.html`. Unit-test output: `app/build/reports/tests/testDebugUnitTest/index.html`.
