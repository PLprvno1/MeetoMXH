# Meeto — Android Supabase cutover, nhiệm vụ 08

Ngày 02/11/2026, Asia/Bangkok. Workspace `F:\MeetoMXH`. Java/XML, Supabase local thật; không truy cập Firebase/ImgBB từ xa hoặc Supabase Cloud.

**FIREBASE REMOVED FROM ANDROID: YES** — target source/plugin/dependency/config đã loại bỏ; scan source, resolved runtime graph và APK tại evidence08.

**IMGBB REMOVED FROM ANDROID: YES** — không có ImgBB runtime path/dependency/URL trong target; loader mới chỉ dùng asset UUID và private Storage.

**ANDROID SUPABASE LOCAL TESTED: YES** — APK chạy trên emulator với UI, GoTrue JWT, HTTP RPC, media worker, native video và private WebSocket thật. Coverage dưới đây phân biệt tap UI và integration qua Java client.

**CLOUD DEPLOYED: NO. PRODUCTION READY: NO.** Cutover code/local đã triển khai; chứng nhận đầy đủ mọi thao tác UI, thiết bị thật, staging và migrate dữ liệu người dùng cũ còn **PARTIAL**. Không xem counts local là production certificate.

## 1. Kết quả và phạm vi

Android đích dùng Auth/Profile A, Posts/Friends/Comments/Reactions/private media B, Messenger/private group/Broadcast C. Không còn Firebase fallback, public bucket loader, ImgBB upload hoặc dummy feed che lỗi. Các màn hình tương lai báo chưa hỗ trợ. Giữ nguyên 15 migration, manifest và evidence/báo cáo05–07.

Thay đổi backend duy nhất là allowlist callback local `meeto://auth/callback` trong config Auth. TTL tạm60 khi test expiry, sau đó khôi phục **đúng bytes cấu hình trước lượt expiry**, TTL3600; private-only Realtime được cấu hình lại sau restart. Không reset/drop/delete volumes, ledger repair hoặc managed stubs.

## 2. File Android và mapping

Danh sách đầy đủ đường dẫn/hash: [android-file-inventory.json](validation_phase_08/android-file-inventory.json), [android-files.txt](validation_phase_08/android-files.txt). Bản đồ màn hình → class → legacy → RPC → coverage: [SCREEN_MAPPING.md](validation_phase_08/SCREEN_MAPPING.md).

Đổi các Activity Auth/Profile/CreatePost/Chat/Viewer/Main; các Fragment Feed/Friends/Inbox/Profile/Comments; ba repository Feed/Friend/Chat, adapters/models, Manifest, debug network config và Gradle. Thêm AuthCallbackActivity, ProfileEditActivity, UserPostsActivity, MeetoApplication, các Java clients, instrumentation tests và fixture MP4 synthetic. Hai google-services.json root/app đã xóa khỏi Android đích. Backup source trước sửa nằm ngoài target, không đóng gói vào APK; không dùng nó làm fallback.

## 3. Client và session

`SupabaseConfig`, `SupabaseHttpClient`, `RpcClient`, `SupabaseAuthClient`, `ProfileClient`, `CommentClient`, `SessionManager`, `EncryptedVault`, `Api`, `ErrorMapper`, `RequestIds`, `MessageOutbox`, `RealtimeClient`, `Observer`, `EventDeduplicator`, `ServerTime`, `Dto`, `ChatControls`, `PostDetails`, `Forms`, `Subscription`.

OkHttp timeout/cancellation, background IO và callback main thread. Request gắn session generation; logout/account switch hủy calls và chặn phản hồi cũ. Refresh đồng bộ trong SessionManager, dùng lại token đã refresh khi nhiều 401 cùng token, retry một lần. Refresh bị Auth revoke làm clear identity/outbox/cache và chuyển màn hình bảo vệ về Login. Logout không refresh bằng network trên UI thread.

Access/refresh token cùng pending PKCE/outbox nằm trong AES-GCM Android Keystore vault tại noBackupFilesDir; không lưu plaintext token trong SharedPreferences. SharedPreferences request_ids chỉ lưu hash account/op/payload và UUID. Outbox mã hóa giữ immutable request payload/UUID đến ACK; nút thử gửi lại thực hiện lại đúng RPC. Không hiển thị tin failed là committed. Logout xóa outbox; đổi account không gửi lại hàng đợi người cũ.

## 4. Auth/Profile

Email/password GoTrue thật; signup cần email confirmation; PKCE verifier mã hóa; callback kiểm scheme/host/path và pending verifier trước khi đổi session. Bootstrap sau password login và PKCE exchange; onboarding/profile dùng version và current DB ACL. Phone/OTP chưa có backend và hiện thông báo chưa hỗ trợ. Password recovery qua Mailpit → PKCE → PUT user → logout → login mật khẩu mới đã chạy bằng UI.

Profile editor tách public/private fields, chọn avatar/cover và chỉ attach asset sau verifier/finalize. Không gửi owner/status/verified flags do client tự đặt. Profile người khác không render DOB/gender khi projection không trả. Block/unblock dùng set_block; bỏ chặn không khôi phục quan hệ. Không thêm account-delete workflow vì backend lifecycle chưa nghiệm thu.

## 5. Feed/Posts/Comments

Feed keyset `(created_at,id)`, refresh/load-more, empty/error/retry thật. Create/edit dùng desired body/audience/ordered assets, server version và idempotency UUID; delete qua RPC. Reaction desired state, share tham chiếu source và mở source bằng get_post mới; unavailable nếu mất quyền. UserPosts có keyset riêng. Counts từ server; không direct DML counter/owner/time.

Comments lấy roots/replies theo parent và keyset; tạo/sửa/xóa version, tombstone giữ thread. Author name/avatar resolve qua get_profile theo ACL mỗi lần tải, chỉ cache trong một lượt fetch; không lấy author snapshot tin từ client. Feed/profile/comments dùng refetch/poll khi phù hợp; chỉ Messenger có private Broadcast. Search profile theo contract prefix, chat theo literal substring server.

## 6. Friends

Canonical peer/request_id/direction; received/sent/accepted/suggestions và profile search qua RPC. Accept/decline/cancel/unfriend/block không suy UUID relationship Firebase. Không đọc friend graph người khác. Crossed request và idempotency tuân backend hiện có. Group chat auto-add chỉ mutual friends theo C, không giả invitation consent.

## 7. Storage

Flow thật: system URI → prepare_upload/prepare_message_upload → JWT POST bytes `upsert=false` → local FFmpeg worker verify → finalize polling → asset UUID → attach RPC. Registry path/context do server cấp. Ready chưa attach vẫn deny GET. Wrong purpose/conversation/owner không bypass được.

Glide custom asset loader resolve registry theo RLS rồi authenticated GET bằng JWT; Glide disk/memory cache tắt cho media, key tách account; logout clear cache. Native VideoView nhận authenticated headers; Range và H264 decode được kiểm trên emulator. Không sử dụng public/signed URL cũ. Bytes đã tải/buffer/stream đang chạy không thể thu hồi; cache/CDN/thiết bị thật chưa chứng nhận. Video lỗi phát báo lỗi; chưa có adaptive streaming/transcoding hoặc đảm bảo seamless refresh giữa một stream dài.

PNG/JPEG/WebP/MP4, giới hạn image10MiB/video50MiB theo verifier B/C. Không mở PDF/other files, EXIF sanitization, antivirus hay malicious-media production sandbox ngoài phạm vi worker hiện có. Cancel hủy upload; timeout verification không báo ready giả. Upload sau process death có thể cần chọn lại URI; không có persistent background transfer/WorkManager.

## 8. Messenger/Realtime

Private/group/inbox/history/search/reply/recall/hide/admin/receipts qua C RPC. Server seq/version; persisted request UUID; encrypted retry outbox. Delivery ACK khi app nhận message thật; read ACK từ viewport khi Activity đang hiển thị. Receipt menu lấy từng recipient từ get_read_receipts; summary UI không được coi là bằng chứng người thật đã đọc.

Own-user private receive-only Broadcast chỉ invalidate hint; client refetch HTTP truth. Dedup event IDs, reconnect backoff, JWT refresh reconnect, foreground lifecycle unsubscribe. History giữ lowest loaded seq và refetch ascending keyset từ mốc đó, giữ cửa sổ khi có burst hơn50 và loại content hidden/recalled/revoked. Tải cũ hơn mở rộng cửa sổ. Sau process death tải lại cửa sổ mới nhất; không có message cache plaintext/offline history database. Inbox moving rows refetch, không hứa snapshot xuyên pages. Search hiển thị tối đa100 kết quả đầu theo server; chưa có UI phân trang kết quả search sâu hơn.

## 9. Emulator evidence

Pixel_4 headless, system image Android37.0 Google APIs Play Store x86_64, API37 (Android17), SDK `F:\android\Sdk`. Dùng userdata riêng evidence08, không xóa userdata AVD gốc. AVD cũ tham chiếu image37.2 không có; lần chạy này override sysdir image đã cài37.0.

Debug kết nối **127.0.0.1 qua ADB reverse**54321/54324; đường10.0.2.2 ban đầu timeout tới Mailpit. Host API vẫn loopback54321. Allow cleartext chỉ hai loopback/gateway ở debug; release yêu cầu HTTPS và config riêng, không nhúng local service-role.

| Evidence08 | Kết quả thực |
|---|---|
| core / replay-SupabaseCutoverTest | 48 checks PASS: UI signup/PKCE/onboarding/profile edit/post create/chat send/login-switch; RPC/JWT social/friends/group/media/privacy; actual Android Broadcast |
| media-messenger / replay-MediaAndMessengerTest | 24 checks PASS: JPEG/WebP/avatar/cover, H264 native video/Range, chat PNG/media/recall, group promote/demote/kick/rejoin, unfriend revoke/outbox |
| session restore | PASS sau explicit force-stop; real RPC/refresh từ vault khôi phục, encrypted-at-rest checks |
| recovery | PASS email recovery PKCE/reset UI/logout/new-password UI login |
| history | PASS initial50, disconnected55, tải đủ115 unique, server search100; race cửa sổ đã sửa |
| feed rendering | PASS feed RPC/DTO, actual post text trên RecyclerView; loading/error/retry UI không che composer |
| comments UI | PASS bottom sheet tạo root, reply, delete và author resolve qua profile ACL |
| client security | 9 checks PASS: missing/tampered JWT, spoof args/DML/guessed ID, own/wrong-user/public channel, client publish deny; transport failure không tính là auth denial |
| network | PASS ngắt ADB bridge thật, RPC fail, feed error/tap retry UI, outbox retained, restore bridge/RPC/private WS, một retry commit, actual Broadcast |
| expiry | PASS GoTrue TTL60 thật; server401 sau exp+35; auto401 refresh; refresh revoke clears identity/outbox; config restore bytes/TTL3600 |

Tổng10 kịch bản instrumentation PASS, ghi [verification-summary.json](validation_phase_08/verification-summary.json); counts check trong từng suite không phải tổng certificate. Các thao tác RPC trong instrumentation chạy qua cùng Java client của APK và JWT GoTrue thật. Không gọi chúng là tap UI. Coverage UI chưa bao phủ từng reaction/comment-edit/friend/group menu, system picker, mọi lifecycle/background/rotation và mọi thiết bị. Không cộng assertion môi trường khác hoặc lần chạy lặp.

## 10. Build/unit/lint

JDK `F:\android\jbr`, Java11 source compatibility, AGP9.3.3, target37/min24. `clean assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` đã chạy; unit10/0 fail. Lint **0 errors,226 warnings** (hardcoded text, accessibility/layout/deprecated APIs và các mục cũ); xem build-summary/lint report, không coi là zero-warning. APK cài và chạy được; checksum audit cuối trong apk-security-scan.json.

## 11–13. Loại legacy và dependency inventory

Remove Firebase Auth/Firestore/BOM, google-services plugin root/app và hai google-services.json. Source target scan không còn Firebase/Firestore/ImgBB/google-services references; resolved debugRuntimeClasspath không có Firebase/ImgBB. APK scan đối chiếu local service-role/JWT secret trong memory: không tìm thấy; public local anon key được phép có. Evidence: legacy-scan.json, apk-security-scan.json, gradle-runtime-dependencies.txt.

Runtime chính giữ AndroidX/Material, OkHttp4.12, Glide4.16, Shimmer/Lottie. Không đưa Supabase service-role hoặc operator psql vào Android. `supabase.debug.properties` bị gitignore; initializer chỉ ghi public local anon. Không đọc local.properties bằng mã/script nhiệm vụ08; AGP có thể tự dùng SDK config theo cơ chế chuẩn.

## 14. Chưa có backend/UI nghiệm thu

Stories, video-feed, notifications/background push, saved, memories, marketplace hiển thị chưa hỗ trợ; không demo data. Community Groups/Stories/moderation chưa triển khai. Group Messenger có backend C riêng. Dark mode local giữ nguyên. Các setting privacy không có public mutation RPC chưa mở bằng direct table writes. Chưa migrate Firebase users/posts/media thật.

## 15. Lỗi được phát hiện và sửa

Compile: Glide RequestManager options chain, binding timestamp/ImageView API. Lint: Toast constant, includeSubdomains và app:tint. Runtime: PKCE thiếu bootstrap; Login singleTask không chạy lại routing sau callback; Glide cleanup dùng destroyed Activity gây crash; cửa sổ50 bị đẩy mất phần lịch sử khi gửi đồng thời refresh; load older phải mở rộng từ actual loaded count. Đã sửa và chạy lại các suites liên quan.

Runner PowerShell ban đầu coi javac deprecation stderr là terminating error dù cần xét exit code; runner cuối capture stderr và kiểm exit code. Initial CLI start có một lần xuất status chứa **credentials local** ra terminal trong lúc dựng môi trường; đã dừng cách gọi đó, lifecycle/worker/init sau đó capture raw status trong memory và không in. Không có production key/Cloud access; không tuyên bố chưa từng có local diagnostic exposure.

## 16. Workers local

`Invoke-AndroidDevWorkers.ps1`: bounded runner tối đa180 phút, không daemon bền; chạy verifier/cleanup và dispatcher B/C thật, chỉ host có local operator/service-role trong memory. Scheduler/lease/retry/dead semantics giữ backend. FFmpeg9.0.2 ở Temp có thể mất sau cleanup OS; cần cung cấp lại binary đã kiểm. Worker phải chạy trong intent15 phút; worker tắt thì UI vẫn pending, không finalize giả. Chưa có durable cloud scheduler, alerts/dead-letter UI hoặc push service.

## 17–18. Cloud và migrate dữ liệu cũ

Trước Cloud: owner-approved environment/anon public config, release signing/HTTPS/App Links/recovery SMTP, staging RLS/Storage/Realtime private-only sau restart, durable sandboxed workers, rate/quota/retention/backup-restore/load tests, real-device reconnect/background/cache/video/refresh, account-delete/admin recovery, public URL retirement, monitoring và rollback rehearsal. Lint warnings/strings/accessibility cần xử lý trước phát hành rộng.

Nếu có dữ liệu cũ: owner inventory/export được phê duyệt; map Firebase UID→Supabase UUID; buộc re-auth/recovery hoặc quy trình migration Auth được duyệt (không coi password Firebase có thể đọc lại); transform posts/comments/relations/chat IDs/time/receipts theo server contract; download media được phép, verify/ingest private assets và thay URL thành asset IDs; validate counts/ACL/orphans; freeze writes, rehearsal trên staging, checksum/backup, phased feature flag/cutover và rollback reconciliation. Không dual-write để giả transaction. Nhiệm vụ08 không đọc/xóa dữ liệu từ xa và chưa thực hiện kế hoạch đó.

## 19–20. Handoff và readiness

Runtime/hash/ledger cuối: [handoff-checks.json](validation_phase_08/handoff-checks.json), evidence/runners: [validation_phase_08](validation_phase_08/README.md). Supabase local9 containers running, TTL3600, private-only true, PG portable stopped/listener55439 absent. Emulator giữ lại để review; synthetic data và volumes giữ nguyên. Dev worker bounded được chạy lại60 phút để review upload/chat live, PID7632, dự kiến dừng khoảng16:02 Asia/Bangkok ngày02/11; thời điểm chính xác tại [dev-worker-handoff.json](validation_phase_08/dev-worker-handoff.json). Đây không phải service bền; sau hết hạn cần chạy runner lại.

Sẵn sàng review Android Supabase local và mở nhiệm vụ staging/real-device QA riêng. **Production/data cutover chưa hoàn thành**; coverage UI đầy đủ và operational gates nêu trên còn mở. Dừng local giữ dữ liệu bằng CLI `stop`, không `db reset`, `--no-backup` hoặc xóa volumes.
