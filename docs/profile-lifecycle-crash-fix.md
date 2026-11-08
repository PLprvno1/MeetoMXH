# Profile navigation crash — 2026-11-08

Device log confirmed `Unable to destroy activity ProfileActivity`, caused by
`Glide.with(image)` in `MediaThumbnailLoader.clear()` during RecyclerView recycling
from `ProfileActivity.onDestroy()`. Glide rejects an already destroyed Activity.

Changes:

- Clear thumbnail requests through Glide's application RequestManager, while retaining
  Activity ownership for new loads. Preserve frame cancellation and bitmap disposal.
- Apply the same cleanup fix to follower avatars.
- Scope profile and album requests to their Activity; cancel outstanding work and
  suppress late success/error callbacks after finish, destruction or identity changes.
- Clear profile adapters, Story ring binding and view binding on destruction.
- Guard profile comment opening against saved FragmentManager state and duplicate sheets.
- Handle a missing first photo object when rendering a featured album cover.

Validation:

- Debug APK built successfully and installed on the API 37 emulator.
- `ProfileLifecycleTest`: 4 tests passed on the emulator (populated Reel grid teardown,
  clearing after destruction, late success/error suppression, pending request cancellation,
  and follower avatar recycling after destruction).
- Manually opened another user's profile from Feed, opened a Reel from its Reels grid,
  confirmed playback `first_frame` in logs, then pressed Back to profile and Feed.
  Process stayed alive; no new AndroidRuntime exception for that process.
- Opened the featured album viewer from the same profile. This is a limited smoke check;
  it does not establish coverage of every profile action or network condition.

The existing full Android test source set fails compilation on removed search view IDs
and `CommentClient.thread()`. The targeted test was built independently:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -I scripts/profile-lifecycle-tests.init.gradle --no-configuration-cache
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.example.meetomxh.ProfileLifecycleTest com.example.meetomxh.test/androidx.test.runner.AndroidJUnitRunner
```

The tests require a signed-in emulator and do not create or modify server content.
