# Meeto — triển khai foundation và Auth/Profile, nhiệm vụ 05

Ngày 01/11/2026, Asia/Bangkok. Workspace `F:\MeetoMXH`.

## Cập nhật nhiệm vụ 05B — trạng thái hiện tại

**SUPABASE LOCAL INTEGRATION TESTED cho foundation và 8 RPC Auth/Profile. Chưa STAGING VERIFIED; chưa PRODUCTION DEPLOYED.** Các mục 1–11 bên dưới lưu mô tả/bằng chứng nhiệm vụ 05; thông tin “thiếu Docker/chưa chạy HTTP” trong các mục đó là lịch sử và được thay thế bởi cập nhật này. Báo cáo nhiệm vụ 04/05 giữ nguyên.

Docker Client/Engine **29.9.2**, Compose **v5.6.1**, context `desktop-linux`, WSL 2 kernel `6.18.54.1-microsoft-standard-WSL2`, RAM Engine khoảng 7,7 GiB. CLI chính thức **2.122.0** dùng lại từ nhiệm vụ 05. Trước start: không container/image/volume cũ, không listener trên cổng 54320–54330; không reset dữ liệu cũ. Start với `--exclude edge-runtime,logflare,vector` để giảm tài nguyên, giữ Auth/PostgREST/Storage/Realtime/Studio/Mailpit. Không thay Windows/WSL settings.

3 migration hiện hành áp dụng sạch trên Supabase PostgreSQL **17.11**; manifest không đổi. Catalog xác nhận 36 bảng, 79 FK, 104 CHECK, 110 indexes, 36 RLS, 21 boolean authorization helpers (20 SQL + 1 PL/pgSQL), 8 public RPC. Owner private definer là `postgres` có BYPASSRLS; `auth.users` thuộc `supabase_auth_admin`. Thực signup GoTrue chạy bootstrap trigger thành công. Không function ứng dụng mở EXECUTE cho PUBLIC; FUTURE không table/column grants ngoài phạm vi.

| Kiểm chứng mới | Kết quả thực chạy | Evidence |
|---|---|---|
| Auth/PostgREST HTTP với JWT GoTrue | **174 assertions đạt**; cả 8 RPC, allow/deny/retry/rollback, email confirmation, recovery/password reset, login/refresh/logout, RLS, spoof actor, privacy, incomplete/unverified/suspended/deleting | `validation_phase_05b/http-results.json` |
| Concurrency qua HTTP/JWT thật | **3 races đạt**, nằm trong 174 assertions: version conflict 200/409; bootstrap/edit 200/200; cùng UID bootstrap 200/200. Owner chỉ giữ Auth row làm barrier; observer thấy 2 HTTP requests đợi Lock | cùng evidence HTTP |
| JWT hết hạn thật | **7 assertions đạt**: GoTrue cấp JWT 60 giây, trước hạn accepted, sau `exp + 35s` bị PostgREST 401, refresh cấp token mới accepted | `validation_phase_05b/expiry-results.json` |
| Catalog/ACL/managed compatibility | **25 assertions đạt**, probes rollback | `validation_phase_05b/catalogue-results.json` |
| SQL Phase A trên Supabase PG17 thật | **87 assertions đạt**, tái dùng bodies cũ với adapter local, real managed schemas; identities/GUC là SQL fixtures, toàn transaction rollback, không là HTTP/JWT evidence | `validation_phase_05b/stack-sql-results.json` |
| CLI migration failure/replay | Deliberate 22012 rollback cả DDL và ledger; bỏ đúng probe file; replay upToDate, không migration mới | `validation_phase_05b/cli-*-results.json` |
| Portable regression tách biệt | 87 Phase A + 99 audit04, 3 RPC races + 3 audit patterns, 4 migration controls đạt; portable đã dừng | `validation_phase_05b/portable-results.json` |
| Service readiness | 9 containers; Auth/Data API/Storage/Studio/Mailpit HTTP 200, Realtime WebSocket handshake 101 | `validation_phase_05b/environment-results.json` |

JWT hết hạn được kiểm bằng thời gian thực, không sửa chữ ký/claims hoặc clock. Script tạm đổi TTL local thành 60 rồi stop/start **giữ volumes**, finally khôi phục nguyên bytes config với `jwt_expiry=3600` và start lại. PostgREST có 30 giây clock-skew tolerance; kiểm `exp+3` ban đầu chưa đủ, đã sửa test theo [tài liệu PostgREST](https://docs.postgrest.org/en/v14/references/auth.html#jwt-claims-validation). Logout thu hồi refresh token; JWT access đã ký vẫn được Data API chấp nhận đến hạn theo cơ chế stateless, không tuyên bố revoke access ngay. Suspended/deleting vẫn có thể Auth login nhưng database gate chặn truy cập/mutation; get/bootstrap chỉ trả envelope 2 fields.

Các lỗi sửa nằm ở **test harness**, không cần đổi migration/RPC/RLS: CLI `db query` dùng prepared query trả rows, DML/file batch không phù hợp fixture; dùng psql qua Docker với nhãn project/workdir được kiểm; PowerShell stderr của CLI không được biến notice thành lỗi giả; HTTP error parser giữ code để assert; báo lỗi assertion có nhãn nhưng không token/body; invalid email thực trả 400, password yếu 422. Registry 429 khi tải image được CLI retry thành công. Một alias PowerShell `cli` trùng Clear-Item trong script mới đã được đổi tên trước lần chạy đạt.

Storage **v1.84.19** khởi động, `storage.allow_any_operation(text[])`, `storage.operation()` và `storage.objects.owner_id text` tồn tại; không bucket/policy media ứng dụng. Realtime **v2.147.1** healthy, WebSocket mở, `realtime.topic()` tồn tại; không publish bảng ứng dụng, không thử private authorization/channel delivery. Vì vậy chưa chứng nhận upload/download/sign/list/RLS bytes, cache/CDN, worker hoặc business Realtime. Chưa thấy xung đột managed schemas ở các kiểm tra đã chạy; trước B/C vẫn phải kiểm ABI từng route và private-channel settings.

**Bàn giao:** Supabase local **đang chạy**, portable PostgreSQL **đã dừng**. API `http://127.0.0.1:54321`, DB `127.0.0.1:54322`, Studio `http://127.0.0.1:54323`, Mailpit `http://127.0.0.1:54324`. Image versions/digests và trạng thái trong environment evidence. Dữ liệu test chỉ local, được giữ để xem xét; không tự xóa Auth accounts. Không credentials trong báo cáo/evidence; local keys chỉ trong bộ nhớ.

Đủ bằng chứng local để chuẩn bị review staging Auth/Profile và lập kế hoạch B; **không tự chuyển sang B hoặc deploy staging**. Full DoD Phase A trong roadmap còn media/account deletion/operational/product gates; SMTP production/SMS/Android PKCE/deep links, rate-limit/load/backup-restore và shared staging vẫn chưa kiểm. Không sửa Android/Gradle/Firebase/ImgBB/local.properties hay SQL Storage cũ. Hướng dẫn tái lập và lệnh trong [validation_phase_05b/README.md](validation_phase_05b/README.md); bàn giao [baocao_nhiemvu05B.txt](../baocao_nhiemvu05B.txt).

---

## Hồ sơ nhiệm vụ 05 (lịch sử)

**Đã viết mã và kiểm thử PostgreSQL cục bộ. Chưa xác minh Supabase stack/staging và chưa triển khai production.** Máy thiếu Docker/Podman; nhiệm vụ cho phép bàn giao code cùng kiểm chứng khả thi trong trường hợp này. Không coi Auth stubs là Supabase Auth thật.

## 1. Môi trường và trạng thái triển khai

| Hạng mục | Trạng thái | Bằng chứng / giới hạn |
|---|---|---|
| Schema foundation, 21 authorization helpers, 8 RPC Auth/Profile | CODED, LOCAL TESTED | PostgreSQL 16.11 portable, database riêng `meeto_phase05`, localhost:55439 |
| Supabase CLI 2.122.0 Windows x64 | INSTALLED, LOCAL TESTED | Binary từ GitHub release chính thức; SHA-256 so khớp `checksums.txt`; `--version` thành công |
| Supabase config, Auth email/password/confirmation/reset | CODED | Dùng GoTrue chuẩn; không viết password/session service riêng |
| HTTP integration với JWT thật | CODED, BLOCKED | Suite có signup, email verification, login/refresh/reset, RPC và RLS; chưa chạy vì stack không khởi động |
| Supabase local stack / PostgreSQL 17 image | BLOCKED | CLI `start` báo `DockerLifecycleInspectError`; Docker và Podman không có; WSL chưa cài |
| STAGING VERIFIED | CHƯA | Không có staging project được xác nhận; không tự dùng URL/key trong `local.properties` |
| PRODUCTION DEPLOYED | CHƯA | Không login/link/push remote, không sửa project thật |

Portable PostgreSQL của nhiệm vụ 04 được dùng lại trong Temp, với database mới chỉ chứa dữ liệu tổng hợp. `auth.uid`, `auth.jwt` và managed schemas tại database này là stubs SQL. Chúng kiểm chứng thân RPC, constraint, transaction và RLS; không kiểm chữ ký JWT, GoTrue, PostgREST hay HTTP. Local Supabase config dùng PG **17** theo bản CLI được cài; CLI không chấp nhận `major_version=16`. Test engine portable **16.11** được ghi riêng, không đánh đồng hai môi trường.

CLI nằm tại `%LOCALAPPDATA%\MeetoTools\supabase\2.122.0\supabase.exe`; không sửa PATH, Android dependency hoặc cài Windows service. Native Supabase runtime hiện không hỗ trợ Windows; không tự cài WSL/Docker hoặc chuyển sang project remote để thử.

## 2. Migration, version và checksum

| Migration | Nội dung |
|---|---|
| `20261101000100_foundation.sql` | Preflight Auth và schema rỗng; 36 bảng, FK/constraints/index, default privilege deny, timestamp trigger settings |
| `20261101000200_authorization.sql` | 21 helper dependency closure; RLS; chỉ cấp quyền Phase A; không cài Storage/Realtime managed policies |
| `20261101000300_auth_profile.sql` | Trigger bootstrap, recovery, validation, transactional RPC, explicit EXECUTE, profile onboarding visibility fix |

SHA-256 từng file nằm trong [migrations.sha256.json](../supabase/migrations.sha256.json). [Test-MigrationManifest.ps1](../supabase/scripts/Test-MigrationManifest.ps1) kiểm tra tập file và checksum trước apply. Đây là checksum nội dung, không phải credential. Sau khi migration được dùng ở môi trường chia sẻ, sửa bằng migration mới; không regenerate manifest để che drift đã áp dụng.

Không đơn thuần gỡ guard bản draft: foundation giữ cấu trúc đã kiểm chứng, thêm preflight/default ACL theo scope owner; authorization bỏ toàn bộ future table **và column** grants, preference writes Phase D và managed policies chưa kiểm ABI; migration riêng viết RPC thật. Hai draft lịch sử vẫn giữ guard/ROLLBACK.

Supabase CLI quản lý lịch sử trong `supabase_migrations.schema_migrations`. Manifest được kiểm ở bước triển khai; CLI ledger không tự bảo đảm SHA-256 file cũ. Harness PostgreSQL dùng thêm `private.meeto_local_migrations(version,sha256)` để kiểm replay/drift; bảng này **chỉ do harness local tạo**, không phải bảng nghiệp vụ thứ 37 hoặc thay thế CLI ledger khi deploy. Local harness apply mỗi migration + ledger row trong cùng transaction; lỗi rollback. Chưa kiểm replay qua CLI thực vì stack không có.

## 3. Bảng foundation được tạo

36 bảng public giữ kiến trúc và tên đã thống nhất, 267 cột, 79 FK, 104 CHECK, 50 explicit indexes / 110 indexes gồm PK/UNIQUE. Toàn bộ bật RLS.

- Account/foundation: `profiles`, `profile_private`, `user_settings`, `account_status`, `user_blocks`, `media_assets`, `event_outbox`.
- Social scaffold: `friend_requests`, `friendships`, `posts`, `post_reactions`, `comments`, `post_media`.
- Chat scaffold: `conversations`, `conversation_members`, `messages`, `message_hidden`, `message_media`.
- Advanced scaffold: `user_follows`, `saved_posts`, `hidden_posts`, `stories`, `story_views`, `story_reactions`, `notifications`, `device_subscriptions`.
- Group/moderation scaffold: `groups`, `group_members`, `group_join_requests`, `system_roles`, `reports`, `moderation_actions`, `moderation_audit`, `appeals`.
- Operations: `migration_id_map`; optional scaffold: `comment_reactions`.

Full scaffold cần thiết vì SQL helper graph resolve các bảng ngay khi CREATE. Chỉ 6 bảng có SELECT grants cho authenticated: profiles, profile_private, settings, own status, own blocks và ACL-scoped media registry. FUTURE và các module B/C còn lại không có app read/write grants; không có mutation API nghiệp vụ tương ứng. Owner test fixtures không làm feature đó thành đã triển khai.

## 4. RPC/function thực sự đã lập trình

Tất cả public RPC trả JSONB. Public wrapper `SECURITY INVOKER`, implementation private `SECURITY DEFINER`, `search_path=''`, tham chiếu schema đầy đủ; chỉ `authenticated` có EXECUTE. Private không được expose trong Data API config. Function nội bộ nhận actor UUID không được cấp EXECUTE cho client.

| RPC public | Input | Behavior / idempotency |
|---|---|---|
| `bootstrap_profile()` | JWT caller | Tạo/bù 4 row profile/private/settings/status bằng ON CONFLICT; không reset profile, role hoặc trạng thái khóa; retry cùng UID an toàn |
| `get_my_profile()` | JWT caller | Active self nhận hồ sơ/private/settings và verified flag để tiếp tục wizard; suspended/deleting chỉ nhận ID + status |
| `complete_onboarding(...)` | name, DATE DOB, gender nullable, expected version | Auth email confirmed + active; tên 1–100, DOB hữu hạn >=1900, đủ 13 tuổi tính UTC; atomically private + profile flag/version; cùng payload đã hoàn tất trả trạng thái hiện tại |
| `update_profile(...)` | full desired name/bio/avatar/cover IDs, expected version | Active/onboarded self; name/bio validation, lock assets theo UUID, ready/owner/purpose/profile bucket; retire old refs + outbox cùng transaction |
| `update_profile_private(...)` | DOB, gender, expected version | Self; server validate tuổi/calendar; tăng chung profile version; không có contact/password mirror |
| `get_profile(p_target)` | target UUID | Self dùng envelope; người khác phải active, đúng audience/block và target onboarded; không trả DOB/gender/settings/contact |
| `search_profiles(...)` | prefix, cursor name+UUID, limit 1–50 | Case-insensitive literal prefix, keyset `(lower(name),id)`, không wildcard injection; chỉ public projection có quyền |
| `set_block(target,boolean)` | desired state | Pair transaction lock; active self; tạo block/cancel pending/remove friendship và follows; unblock không khôi phục; retry desired state an toàn |

`bootstrap_identity` dùng chung cho `AFTER INSERT ON auth.users` và RPC recovery. Trigger không đọc user metadata để gán name/role/onboarding. Thất bại trigger sẽ rollback Auth insert; integration với Auth service vẫn là gate cần chạy. Auth user có trước migration có thể gọi bootstrap; không chạy backfill remote tự động.

Lock order Phase A: pair advisory transaction lock khi block → own Auth row → account_status → profiles → private/media rows (media sắp UUID). Bootstrap cũng lock Auth row trước insert/recovery; tránh thứ tự khóa đảo với update. Suspension từ trusted operator cập nhật cùng status row; request đã commit trước suspension không thể bị hoàn tác bằng việc khóa sau đó.

Các mutation dùng full desired state. Retry cùng trạng thái trả version hiện tại; payload khác với version cũ trả conflict. Một request cũ quay lại sau khi đã có nhiều thay đổi khác không được coi là replay được lưu vĩnh viễn; client refetch khi 409. Retry `40001`/`40P01` có giới hạn, giữ nguyên inputs/version; không tự ghi đè bằng version mới khi chưa refetch.

SQLSTATE: `PT401` identity thiếu/anonymous/không có Auth user; `PT403` inactive/unverified/incomplete; `PT404` unavailable; `PT409` version/bootstrap/state conflict; `PT422` validation. PostgREST hỗ trợ PTxxx HTTP mapping; kiểm chứng mapping thực nằm trong HTTP suite chưa chạy. Invalid PostgreSQL DATE/UUID literal có thể bị parser từ chối 400 trước function. DB CHECK settings trả 23514, không phải RPC giả success.

## 5. RLS và quyền đã kiểm thử

Policy có grants trong Phase A: `profiles_select`, `profile_private_select`, `user_settings_select`, `user_settings_update`, `account_status_select`, `user_blocks_select`, `media_assets_select`. Các SELECT policy tương lai vẫn có trong dependency baseline nhưng không có table/column grants. `event_outbox` và migration mapping server-only.

Kiểm PostgreSQL thực bằng role `anon`/`authenticated` NOBYPASSRLS: anon data/RPC deny; không sửa trực tiếp profile/onboarding/DOB/status/role; self settings chỉ các cột được phép; private target filtered; incomplete tự đọc/hoàn tất qua RPC riêng, không cần `is_active` trước bootstrap; suspended/deleting không update/search, không tự reactivate bằng bootstrap; actor-spoof internal function deny; future grants đóng cả cột.

PUBLIC EXECUTE của app functions được revoke mặc định **global và public-schema defaults**, đồng thời revoke default grants anon/authenticated vốn có thể được Supabase cấu hình. Mỗi entry có grants tường minh; không sửa ACL của Auth/Storage managed functions. Scope default privilege theo migration owner; deployment phải dùng owner nhất quán và inventory grants từ owner/role khác. Local owner là `meeto_audit_owner` superuser; thực tế Supabase owner/BYPASSRLS/inherited ACL phải kiểm ở stack/staging, chưa được chứng nhận.

## 6. Auth và Profile

Auth email/password, xác minh email, refresh rotation, logout và recovery/reset dùng service Supabase chuẩn. `supabase/config.toml`: public-only API schema, auto-expose false, email confirmation bật, minimum password 8, anonymous login tắt, SMS signup tắt, Mailpit local. Redirect URL localhost chỉ dùng thử nghiệm; Android PKCE/deep-link không được đổi trong nhiệm vụ này.

Hồ sơ không cần email verification để bootstrap và đọc wizard state; complete onboarding bắt buộc email confirmed authoritative trong auth.users. Không tin `user_metadata.role`, `contactVerified` hoặc `onboarding_completed` gửi từ client. Phone OTP chưa bật và cần SMS provider/test riêng; không tạo password giả cho phone identity.

Đã kiểm profile text/private/settings và attachment validation/retirement bằng registry fixtures. **Chưa có pipeline upload/finalize/cleanup bytes:** prepare/finalize thuộc Phase B trong catalog. Không tạo bucket/policy operation-aware khi chưa kiểm Storage ABI. Không tuyên bố upload avatar/cover end-to-end đã chạy. Outbox retire được ghi thật, worker xử lý bytes chưa chạy/triển khai.

Không bật request/delete-account RPC/worker: reauth, retention private chat và last group admin chưa được duyệt. State `deleting` đã có gate và test deny nhưng chưa là tính năng self-delete. Suspension/unlock vẫn trusted operator; không mở API moderator/role management của Phase E.

## 7. Phát hiện và sửa trong implementation

1. `can_read_profile` draft cho active viewer thấy placeholder của signup chưa onboarding nếu default audience public. Migration 003 thêm target `onboarding_completed`; raw SELECT, get và search đã test. Dictionary có note đồng bộ; draft gốc giữ lịch sử.
2. REVOKE table-level không tự gỡ column SELECT. Khi tách Phase A, bỏ hẳn future column grants tại migration 002, bổ sung assertion `has_any_column_privilege` trên posts/comments/messages/devices.
3. Bootstrap recovery và update có thể dùng thứ tự resource locks khác nhau. Tất cả mutation Phase A lock Auth identity trước status/profile; race bootstrap/edit đã đạt bằng 2 connections và observer riêng.
4. CLI mới không nhận local PG16 setting; config giữ supported PG17. PostgreSQL portable PG16 evidence tách riêng, không sửa dependencies Android để giải quyết môi trường.

## 8. Kết quả kiểm thử và lệnh thực hiện

| Bộ test | Thực chạy | Kết quả |
|---|---|---|
| Audit04 baseline + regressions trên DB riêng | PostgreSQL 16.11 stubs | 99 assertions đạt; 3 race patterns cũ đạt |
| Phase A schema/RPC/RLS/validation + extended + block cleanup | PostgreSQL 16.11 stubs | **87 assertions đạt**, 0 fail |
| Phase A concurrent RPC | 2 client connections + owner observer | **3 đạt**: optimistic version conflict, bootstrap vs edit, bootstrap retry |
| Migration controls | Local harness + manifest checker | **4 đạt**: manifest mismatch, applied checksum drift, failed DDL rollback, replay 0 applied/3 skipped |
| Fresh database final migrations | Local harness | 3 migrations applied; 36 public tables / 79 FK / 104 CHECK |
| PowerShell syntax | AST parser | Scripts parse; không thay cho chạy HTTP integration |
| Supabase CLI `start` | Thực gọi | BLOCKED: không Docker/Podman |
| CLI `db push --local --dry-run --skip-vault` | Thực gọi | Config đọc được; ECONNREFUSED localhost:54322; không kết nối remote |
| Auth/PostgREST JWT HTTP | Suite đã viết | **CHƯA CHẠY**; không có số pass giả |

Không cộng các số trên thành một chứng nhận backend production. Chi tiết assertion/result: [validation_phase_a/](validation_phase_a/). Audit04 chạy riêng schema target đọc đa module; Phase A intentionally đóng grants các module chưa bật. Đạt audit04 không nghĩa Phase A đã mở Chat/Stories.

```powershell
# $auditRoot là Temp\MeetoAudit04-<32hex> đã chuẩn bị portable PG16.11.
# Cluster bind 127.0.0.1:55439, user meeto_audit_owner; không dùng remote DSN.
powershell -NoProfile -File database_design/validation_phase_a/Invoke-PhaseALocal.ps1 -PortableRoot $auditRoot -CreateAuditDatabase
powershell -NoProfile -File database_design/validation_phase_a/Invoke-PhaseALocal.ps1 -PortableRoot $auditRoot -SqlFile database_design/validation_phase_a/00_stubs.sql
powershell -NoProfile -File database_design/validation_phase_a/Invoke-PhaseALocal.ps1 -PortableRoot $auditRoot -ApplyMigrations
# Theo thứ tự 01_auth_profile_tests.sql, 02_extended_tests.sql, 03_block_cleanup_tests.sql:
powershell -NoProfile -File database_design/validation_phase_a/Invoke-PhaseALocal.ps1 -PortableRoot $auditRoot -SqlFile <test.sql>
powershell -NoProfile -File database_design/validation_phase_a/Invoke-PhaseAConcurrency.ps1 -PortableRoot $auditRoot
powershell -NoProfile -File database_design/validation_phase_a/Test-MigrationControls.ps1 -PortableRoot $auditRoot
```

Không chạy `00_stubs.sql` trên Supabase. Runner chặn database/cluster khác; recreate chỉ synthetic `meeto_phase05`. Dùng PowerShell subprocess khi tái lập để exit code của lệnh native trước không làm sai kết luận script cũ.

## 9. Chưa hoàn tất và điều kiện nghiệm thu còn mở

- Docker/local Supabase Auth/PostgREST/Mailpit chưa có; chưa có JWT được service cấp/xác minh thật, expired-token/session-revocation test và PostgreSQL 17 stack test.
- Trigger chạy dưới GoTrue transaction/owner thực, grants/roles thực, managed extensions/old policies và CLI migration replay chưa kiểm trên stack/staging.
- SMTP thật, redirect allowlist, password recovery PKCE Android, rate limit/CAPTCHA, SMS provider chưa cấu hình/test; config local không thay remote Auth settings.
- Profile media bytes/Storage permissions/cache, worker cleanup, account deletion/retention chưa hoàn tất; không chuyển sang B/C/D/E để bù.
- Cần duyệt public audience authenticated-only, privacy fields và operational recovery. Không có guest data grant.

Vì vậy Phase A **CODED + PostgreSQL LOCAL TESTED**, chưa đủ nghiệm thu `STAGING VERIFIED` hoặc đưa Android/production sang Supabase.

## 10. Quy trình chủ dự án tự triển khai an toàn

1. Chuẩn bị Docker Desktop/Podman theo tài liệu chính thức; start local stack. Cố định CLI 2.122.0 trong môi trường nhóm/CI; dùng config PG17 hiện hành. Nếu chọn CLI khác phải validate config lại.
2. Kiểm manifest rồi apply **local**. Nếu stack đã tự apply migrations khi start thì push chỉ kiểm ledger; không reset database có dữ liệu cần giữ.
3. Cấp local anon/publishable key cho biến `MEETO_LOCAL_ANON_KEY` trong phiên shell riêng; không lưu vào source, không xuất `status` JSON có keys ra log CI công khai. Chạy `supabase/tests/Invoke-AuthProfileHttp.ps1`. Suite chỉ gọi localhost, dùng Mailpit, tạo test users `example.test`; không chạy remote và không tự xóa Auth accounts.
4. Test SMTP/verification/recovery/JWT/owner/default privileges, trigger failures, migration replay trên stack. Đọc assertion failures nội bộ; script không in Auth bodies/tokens. Chạy manual expired token, reset redirect PKCE và rate-limit cases.
5. Xác nhận một **staging project mới/rỗng** cụ thể; inventory Auth/public schema/policies/function owners/default grants. Chủ dự án chạy `supabase login` và `supabase link --project-ref <confirmed-staging-ref>` trực tiếp. Không gửi access token/database password vào chat/file/command có log.
6. Dùng wrapper mặc định **dry-run**; chỉ truyền `-Apply` sau review snapshot/manifest/owner và xác nhận staging. Wrapper kiểm exact linked ref, không đọc local.properties, không có mode Production. Không chạy wrapper remote trong nhiệm vụ này.

```powershell
$cli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe"
& $cli start
.\supabase\scripts\Invoke-PhaseADeployment.ps1 -SupabaseCli $cli -Environment Local
.\supabase\scripts\Invoke-PhaseADeployment.ps1 -SupabaseCli $cli -Environment Local -Apply
.\supabase\tests\Invoke-AuthProfileHttp.ps1 -SupabaseCli $cli
# Chỉ sau xác nhận staging và login/link trực tiếp:
.\supabase\scripts\Invoke-PhaseADeployment.ps1 -SupabaseCli $cli -Environment Staging -ExpectedStagingRef <confirmed-ref>
# Review dry-run, snapshot/backup, owner/grants và test plan rồi mới thêm -Apply.
```

Wrapper dùng `--skip-vault` để migration database không đồng thời cập nhật Vault secrets. CLI ledger/remote checksum phải đối chiếu với artifact manifest đã review. Existing schema không phù hợp baseline sẽ fail, không dùng `migration repair`/`IF NOT EXISTS` để giả đã đồng bộ. Production là nhiệm vụ riêng có phê duyệt và kế hoạch phục hồi.

## 11. Phục hồi khi migration thất bại

- Local harness: transaction rollback cả DDL/ledger; đã test deliberate 22012, không còn probe table. Replay migrations đã applied cùng checksum no-op. Bản checksum khác fail trước apply.
- CLI: dừng triển khai ở lỗi, đối chiếu ledger/catalog và logs đã lọc secrets; cần kiểm transaction behavior trên stack thực trước remote. Foundation và authorization ở các version riêng; scaffold sau 001 vẫn deny-by-default nếu 002/003 lỗi. Không mở grants để chạy tạm.
- Trigger 003 lỗi có thể làm signup mới fail nguyên transaction; giữ maintenance/disable signup trong rollout staging đến khi smoke test qua. Existing Auth users dùng RPC recovery sau migration thành công.
- Baseline này chỉ cho môi trường rỗng. Trước remote snapshot/backup Auth+DB/config, thử restore vào staging khác; không DROP/CASCADE public/auth để rollback project đang có dữ liệu. Sau khi đã có writes, dùng forward fix hoặc restore/reconcile có kế hoạch; không chạy down migration xóa 36 bảng.
- Nếu phát hiện grant/RPC lỗi: đóng signup hoặc revoke entry EXECUTE bằng migration khắc phục đã review, bảo toàn dữ liệu; không xóa Firebase/old app path. Cutover Android chưa diễn ra ở nhiệm vụ 05.

Tài liệu tham chiếu: [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started), [native runtime/Windows limitations](https://supabase.com/docs/guides/local-development/docker-and-native-runtimes), [user management](https://supabase.com/docs/guides/auth/managing-user-data), [database functions](https://supabase.com/docs/guides/database/functions), [Auth test flows/Mailpit](https://supabase.com/docs/guides/auth/testing-auth-flows).

Bàn giao cuối: 3 manifest checksum khớp; 19 artifact UTF-8 không rỗng; PowerShell parser 0 lỗi. PostgreSQL portable đã dừng, pg_ctl status trả 3. Không để server thử nghiệm nền. Báo cáo mới tại [baocao_nhiemvu05.txt](../baocao_nhiemvu05.txt).
