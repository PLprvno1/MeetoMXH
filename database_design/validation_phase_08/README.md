# Nhiệm vụ08 — Android Supabase local evidence

Chỉ `F:\MeetoMXH`, Docker project MeetoMXH, host API loopback54321. Không Cloud, reset/delete volumes hoặc fake JWT success. Java/XML APK thực trên Pixel_4/API37. Dùng ADB reverse127.0.0.1:54321 và54324 (Mailpit); gateway10.0.2.2 timeout ở lần chuẩn bị ban đầu.

Báo cáo: [Android cutover](../MEETO_ANDROID_SUPABASE_CUTOVER.md), [báo cáo08](../../baocao_nhiemvu08.txt). Mapping/coverage: [SCREEN_MAPPING.md](SCREEN_MAPPING.md). Android source paths/hashes: [inventory](android-file-inventory.json).

## Chạy lại

Docker Desktop và emulator-5554 phải boot. SDK `F:\android\Sdk`, JDK `F:\android\jbr`; image đã cài `android-37.0/google_apis_playstore/x86_64`. Lần này dùng userdata riêng `emulator-userdata.img`, không xóa/sửa userdata AVD gốc. Nếu khởi động emulator mới, dùng đúng AVD/image có thật trên máy; package Temp/AVD image có thể thay đổi ngoài nhiệm vụ.

```powershell
$cli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe"
# CLI start/status có thể chứa local credentials: capture, không in raw.
$ErrorActionPreference='Continue'
$captured=& $cli start --exclude edge-runtime,logflare,vector 2>&1
$exitCode=$LASTEXITCODE
$captured=$null
$ErrorActionPreference='Stop'
if($exitCode -ne 0){throw 'Local start failed; raw status suppressed'}
.\supabase\scripts\Initialize-AndroidLocal.ps1 -SupabaseCli $cli
```

Initializer guarded labels/local status, chỉ ghi URL/public **local anon** vào ignored `supabase.debug.properties`, không service-role và không đọc local.properties. Sau CLI start phải configure private-only Realtime; initializer và dev runner làm việc đó.

Worker thật phải chạy cùng lúc với suites, ở terminal riêng hoặc process hidden có log sanitized. Chỉ host có service-role/operator trong memory:

```powershell
powershell -NoProfile -File supabase/scripts/Invoke-AndroidDevWorkers.ps1 `
 -FfmpegDirectory "$env:TEMP\MeetoPhase06-tools\ffmpeg-9.0.2-essentials_build\bin" `
 -DurationMinutes 120
# Terminal khác:
powershell -NoProfile -File database_design/validation_phase_08/Invoke-AndroidVerification.ps1
# Tùy chọn thêm expiry thật (restart services, không chạy HTTP suite song song):
powershell -NoProfile -File database_design/validation_phase_08/Invoke-AndroidVerification.ps1 -IncludeExpiry
powershell -NoProfile -File database_design/validation_phase_08/Invoke-Handoff.ps1
```

Không chạy runner nhiều bản chồng nhau. Replays tạo thêm synthetic users/content và giữ nguyên existing data. FFmpeg Temp phải tồn tại; không tải production media/keys. Build/run mới ghi `replay-*`; evidence các nhiệm vụ05–07 giữ nguyên. Test nào fail sẽ dừng runner, không thay bằng demo data.

## Evidence và cách đọc

- `replay-build.log`, `build-summary.json`: clean assembleDebug/assembleDebugAndroidTest/unit/lint;10 unit PASS; lint0 errors, warnings ghi thật.
- `replay-cutover-results.json`:48 core checks; UI email signup/PKCE/onboard/profile/post/chat/login-switch và app Java RPCs/JWT, real Broadcast.
- `replay-feed-rendering-results.json`: feed RPC/DTO và row thật trên Main RecyclerView; không dùng dummy feed.
- `replay-media-messenger-results.json`:24 media/social/group client checks, PNG/JPEG/WebP/H264 decode/finalize/attach, native video/Range/revoke.
- `replay-session-restore-results.json`, `replay-recovery-results.json`: process death encrypted restore, recovery PKCE/reset/new-password login bằng UI.
- `replay-history-results.json`:50 initial +55 disconnected, older page115 unique; sequence window không mất rows khi refresh đồng thời.
- `replay-client-security-results.json`:9 checks missing/tampered JWT, spoof args/DML/guessed ID, own/wrong-user/public/private publish authorization thật. JWT tamper chỉ dùng cho negative case; không dùng nó cho success.
- `network-recovery-results.json`, `device-network.log`: host thực gỡ/nối ADB54321 bridge; server vẫn chạy; device thấy RPC failure, giữ outbox rồi retry một committed row, private WS rejoin/Broadcast; phiên bản cuối còn kiểm feed error/retry UI.
- `device-comments-ui.log`, `comments-ui-results.json`: kiểm UI root/reply/delete và resolve author theo profile ACL (test thêm sau replay chung).
- `device-expiry.log`, `expiry-results.json`, `expiry-handoff.json`: JWT GoTrue TTL60 thật, đợi exp+35, actual server401/automatic refresh/revoked refresh clear; finally restore config bytes/TTL3600 và private-only.
- `legacy-scan.json`, `gradle-runtime-dependencies.txt`, `apk-security-scan.json`: target source/dependency/APK scan; local anon được phép, service-role/JWT secret không trong APK.
- `handoff-checks.json`: hashes nguồn/evidence cũ, ledger15, manifest, exact config allowlist delta, runtime local/portable/emulator. `emulator-feed.png`, `emulator-inbox.png`: ảnh UI local thực, synthetic content.

Counts không cộng với nhiệm vụ05–07 hoặc các replay/SQL thành certificate. Instrumentation bao gồm tap UI **và** Java-client integration, phạm vi tách rõ ở mapping. Chưa full automation mọi menu, system picker, background lifecycle/rotation, thiết bị thật, production SMTP/CDN/load/worker scheduler.

Các log `device-instrumentation-*`, `device-history.log`, `build-check.log` đầu ghi các lỗi đã tìm và sửa; không phải lượt nghiệm thu cuối. Archive source trước sửa và userdata là local backup/fixture ngoài APK, đã gitignore; không phải legacy fallback. Không đọc/xóa dữ liệu Firebase/ImgBB từ xa.

Giữ Supabase local9 containers và emulator để review. Worker bàn giao là process bounded, trạng thái/thời hạn ghi `dev-worker-handoff.json`; quá hạn phải chạy lại nếu muốn verify upload/dispatch live. Stop Supabase giữ dữ liệu bằng CLI `stop`; không dùng `--no-backup`, `db reset` hoặc delete volumes. Cloud/staging chưa triển khai.
