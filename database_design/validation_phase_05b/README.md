# Nhiệm vụ 05B — bằng chứng Supabase local

Chỉ `F:\MeetoMXH`, Docker project `MeetoMXH`, API loopback 54321. Không dùng linked/remote URL/key, không đọc local.properties. Các báo cáo 04/05 và evidence cũ được giữ nguyên. Không chạy `00_stubs.sql` trên Supabase.

```powershell
$cli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe"
.\supabase\scripts\Test-MigrationManifest.ps1
& $cli start --exclude edge-runtime,logflare,vector
# Không xuất raw status vì có credentials. Chỉ giữ local anon key trong memory.
$ErrorActionPreference='Continue'
$raw=& $cli status -o json 2>$null
$code=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($code -ne 0){throw 'Local stack unavailable'}
$state=$raw|Out-String|ConvertFrom-Json
$env:MEETO_LOCAL_ANON_KEY=$state.ANON_KEY
try {
 .\supabase\tests\Invoke-AuthProfileHttp.ps1 -SupabaseCli $cli |
  Set-Content -Encoding UTF8 database_design/validation_phase_05b/http-results.json
} finally {Remove-Item Env:MEETO_LOCAL_ANON_KEY -ErrorAction SilentlyContinue}
powershell -NoProfile -File database_design/validation_phase_05b/Invoke-StackChecks.ps1 -SupabaseCli $cli
powershell -NoProfile -File database_design/validation_phase_05b/Invoke-StackSqlRegression.ps1
powershell -NoProfile -File supabase/tests/Invoke-ExpiredJwt.ps1 -SupabaseCli $cli
powershell -NoProfile -File database_design/validation_phase_05b/Invoke-EnvironmentEvidence.ps1 -SupabaseCli $cli
```

HTTP: 174 assertions, gồm 3 real JWT HTTP races. GoTrue thực tạo/verify/login/refresh/recover/reset/logout; Mailpit chỉ giữ email local. Unverified RPC matrix dùng token thật đã cấp rồi operator gỡ email_confirmed_at, chứng minh DB không tin confirmed claim cũ; login unverified cũng bị Auth chặn. 8 RPC không nhận actor ID từ client. `bootstrap_profile`/`get_my_profile` cố ý cho incomplete/unverified và envelope locked. SQL operator chỉ setup states/recovery/barriers, không dùng làm bằng chứng JWT.

Expiry: 7 assertions, mint JWT bằng password login thực. Admin Auth API chỉ tạo fixture riêng, không mint token giả. Script tạm TTL 60, stop/start giữ volumes; chờ exp+35 vì PostgREST tolerance 30; restore bytes config TTL3600 trong finally và start stack. Mất khoảng vài phút; không chạy song song HTTP suite vì restart services. Nếu process bị kill trước finally, kiểm `jwt_expiry` và khôi phục 3600 trước start; không reset volumes.

StackChecks: 25 catalog/ACL assertions rollback. Thêm duy nhất migration probe 004 tạm để cố ý lỗi 22012 qua `db push --local`; kiểm cả DDL/ledger không tồn tại rồi finally xóa đúng probe. Đây không phải migration thứ 4 bàn giao. Không chạy đồng thời deployment khác. Replay phải upToDate; manifest vẫn đúng 3 file.

StackSqlRegression: 87 SQL assertions trên PostgreSQL17/managed Auth thật. Adapter bỏ guard portable **chỉ trong memory**, thay bằng guard nhãn container/catalog/fixed fixture IDs; điều chỉnh count fixtures để không tính users của HTTP suite. Không thay file test cũ, không cài managed stubs; toàn bộ schema test_a/fixtures/changes rollback. Identities SQL/GUC không được tính vào 174 HTTP assertions.

PortableRegression dùng cluster Temp chuẩn bị ở nhiệm vụ04; **chỉ recreate hai DB tổng hợp được runner guard**, không Supabase volumes. Chạy riêng:

```powershell
powershell -NoProfile -File database_design/validation_phase_05b/Invoke-PortableRegression.ps1 `
 -PortableRoot "$env:TEMP\MeetoAudit04-cd2f7cba6acc4ad59dc8d4cd1ec341af"
```

Kết quả 87 Phase A +99 audit04, 3+3 races,4 migration controls; portable finally stop. Không cộng số assertions tích lũy 59/83/87. Các DB chỉ synthetic và package Temp có thể đã bị OS cleanup khi tái lập sau này; xem validation/README.md để chuẩn bị cluster.

Environment: chỉ lưu versions, image IDs, container health, endpoint status, RAM/disk. Kiểm Storage status/managed ABI và Realtime WebSocket handshake, không upload/media/private channel business tests. `handoff-checks.json` ghi parse/UTF8/hash bảo vệ source và manifest cuối.

Cuối 05B: **Supabase local đang chạy** với 9 containers, PG portable **đã dừng**. Để dừng local và giữ dữ liệu khi chủ dự án cần: `& $cli stop`; không dùng `--no-backup`, `db reset` hoặc xóa volumes. Không Cloud/staging deployment. Báo cáo đầy đủ: ../../baocao_nhiemvu05B.txt và ../MEETO_PHASE_A_IMPLEMENTATION.md.
