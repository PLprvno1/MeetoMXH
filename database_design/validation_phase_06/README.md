# Nhiệm vụ 06 — tái lập Phase B local

Chỉ `F:\MeetoMXH`, Docker labels project/workdir MeetoMXH, API `127.0.0.1:54321`.
Không remote/link/login, không đọc `local.properties`, không reset/xóa volumes, không stubs.
Các báo cáo và evidence 04/05/05B được giữ nguyên. Không chạy các runner 05B có kỳ vọng ledger 3 trên stack Phase B.

```powershell
$cli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe"
& $cli start --exclude edge-runtime,logflare,vector
.\supabase\scripts\Invoke-PhaseBDeployment.ps1 -SupabaseCli $cli -DryRun
.\supabase\scripts\Invoke-PhaseBDeployment.ps1 -SupabaseCli $cli
$mediaTools=.\database_design\validation_phase_06\Prepare-MediaTools.ps1
.\supabase\tests\Invoke-PhaseBHttp.ps1 -SupabaseCli $cli -FfmpegDirectory $mediaTools
# Chạy SAU HTTP B; expiry stop/start services, không chạy song song suites/worker.
.\database_design\validation_phase_06\Invoke-PhaseARegression.ps1 -SupabaseCli $cli -IncludeExpiry
.\database_design\validation_phase_06\Test-Handoff.ps1 -SupabaseCli $cli
```

`http-results.json`: **473** assertions, gồm 7 real JWT HTTP races; số lần request/check không là load benchmark.
Race dùng operator global advisory lock làm barrier, quan sát hai authenticator connections đợi Lock, rồi thả hai request JWT thực. Delete/comment cho phép hai serialization hợp lệ: comment trước delete cùng 200 hoặc delete trước, comment 404. Counters đối soát toàn bộ rows.
`phase-a-http-results.json`: **174** assertions của suite A gốc; `phase-a-sql-results.json`: **87** SQL assertions rollback; `phase-a-expiry-results.json`: **7** assertions token GoTrue hết hạn thật. Không cộng chúng thành chứng nhận production. SQL identities/GUC không là HTTP evidence.

`cleanup-backlog.sql` là probe SQL operator rollback riêng:101 assets ready cũ nhưng còn attach không được chiếm batch LIMIT và bỏ sót orphan kế tiếp. Evidence1 assertion riêng `cleanup-backlog-results.json`, không cộng vào473 HTTP. Migration009 thêm lọc candidate trước LIMIT, vẫn giữ recheck liên kết sau asset lock của006. Handoff kiểm không còn fixed fixture IDs. Cuối cùng có9 migrations,3 A giữ nguyên và6 B mới.

Adapter SQL tái sử dụng runner 05B trong memory: giữ guard fixed IDs/container/managed schema; chỉ bỏ hai điều kiện “posts/comments chưa có column SELECT” vì B đã bật read RLS. Messages/device subscriptions vẫn đóng. Không sửa file/evidence A/05B. Expiry adapter chỉ đổi output sang 06; khôi phục bytes config TTL3600 và start giữ volumes trong finally. Nếu process bị kill, kiểm/khôi phục TTL3600 trước start.

Worker một lượt có thể chạy riêng:

```powershell
.\supabase\scripts\Invoke-MediaWorker.ps1 -SupabaseCli $cli -FfmpegDirectory $mediaTools
```

Worker chỉ SQL operator trong container đúng labels và HTTP loopback. Local service-role key ở memory, không truyền trên command line/log/file. User upload dùng JWT GoTrue. Worker kiểm magic, ffprobe dimensions/codec/duration và full ffmpeg decode rồi ghi metadata whitelist. Client gọi finalize sau lượt worker; trước đó 409 verification_pending. Không cần Edge Runtime hay background daemon. Bản worker này là local operator executable, chưa là scheduler Cloud.

`-TestDeleteFailure` là fault injection local để kiểm backoff/dead-letter, không phải outage Storage thật. Tests ép attempt=4 và lease quá hạn bằng operator trên đúng fixture. Sau test phục hồi checkpoint, gọi Storage API thật, trusted GET xác nhận object đã mất. Không DELETE SQL `storage.objects`. Object chưa upload và DELETE trả missing được xử lý idempotent.

Tool tạm FFmpeg 9.0.2 essentials lấy từ [publisher Gyan](https://www.gyan.dev/ffmpeg/builds/), SHA256 .7z `4705843ccaaf54257c16ad90f3e952ece33c17df964ecf7bfdbb0f49c7171077`; Windows tar giải nén, không cài service/PATH. ZIP download đầu chậm đã bỏ, .7z đạt checksum. Binaries nằm Temp, có thể bị OS dọn; không thuộc repo. Fixture ảnh/video được FFmpeg tạo, xóa đúng thư mục fixture sau suite. Không dùng dữ liệu cá nhân.

Đọc [implementation](../MEETO_PHASE_B_IMPLEMENTATION.md) và [báo cáo](../../baocao_nhiemvu06.txt). Supabase để chạy khi bàn giao, portable PG không khởi động. Khi cần dừng giữ dữ liệu: `& $cli stop`.
