# Nhiệm vụ 07 — Messenger local integration

Chỉ `F:\MeetoMXH`, labels project/workdir MeetoMXH; host API loopback54321. Không remote/link/login, không đọc local.properties, không reset/delete volumes hoặc managed stubs. Giữ nguyên migrations001–009 và reports/evidence A/B. Migrations mới010–015, tổng15,49 public RPC,36 application tables.

```powershell
$cli="$env:LOCALAPPDATA\MeetoTools\supabase\2.122.0\supabase.exe"
& $cli start --exclude edge-runtime,logflare,vector
.\supabase\scripts\Invoke-PhaseCDeployment.ps1 -SupabaseCli $cli -DryRun
.\supabase\scripts\Invoke-PhaseCDeployment.ps1 -SupabaseCli $cli
# Phải configure lại sau CLI start/recreate; seed có thể bật lại public access.
.\supabase\scripts\Configure-ChatRealtimeLocal.ps1
$mediaTools=.\database_design\validation_phase_06\Prepare-MediaTools.ps1
.\supabase\tests\Invoke-PhaseCHttp.ps1 -SupabaseCli $cli -FfmpegDirectory $mediaTools -WithWebSocket
.\database_design\validation_phase_07\Invoke-Regression.ps1 -SupabaseCli $cli -FfmpegDirectory $mediaTools
.\database_design\validation_phase_07\Test-Handoff.ps1 -SupabaseCli $cli
```

Không chạy đồng thời suite khác/deployment: races có operator barriers, fixtures trạng thái và outbox checkpoint control. Không chạy runner handoff/deploy A/B với expected ledger cũ trên C. `Invoke-Regression.ps1` chạy unchanged A/B HTTP source, B in-memory chỉ relocate root/output; SQL adapter guard giữ nguyên với future-column expectation cập nhật messages đã thuộc C. Chỉ ghi output07, không overwrite06/05B. JWT expiry suite7 của06 là historical evidence, **không chạy lại expiry trong07**; token refresh/invalid JWT real WS đã kiểm ở07. Không cộng lại7 expiry cũ vào kết quả07.

Evidence tách riêng: C HTTP511; WebSocket40; A HTTP174; B HTTP473; A SQL87; catalog/invariants33. Không cộng chúng thành production certificate. HTTP C gồm8 races + same actor independent-conversation probe; mọi user business request dùng JWT GoTrue từ signup/Mailpit verification/password login. SQL operator setup/observer/lease/quota fixtures không là JWT evidence. SQL quota fixtures không có object bytes, được xóa đúng IDs trong finally; fixed SQL regression fixtures rollback.

Node driver `phase-c-ws.cjs` dùng ws module có sẵn của local Storage container, không npm install. Guard local labels, copy đúng /tmp/meeto-phase-c-ws.cjs, keys/tokens stdin memory; connect internal Kong:8000 trong cùng Docker local project, không external host.3 clients chính independent; thêm unauthorized/public/forged test joins. Driver xóa sau test, không daemon. Message content chỉ HTTP RLS refetch; WS assertions chỉ event/scope. Invalid JWT refresh có thể đóng channel qua phx_error/phx_close trong khi TCP socket vẫn mở; đây là protocol-level denial được kiểm, không chỉ handshake.

Workers riêng:

```powershell
.\supabase\scripts\Invoke-MediaWorker.ps1 -SupabaseCli $cli -FfmpegDirectory $mediaTools
.\supabase\scripts\Invoke-ChatDispatcher.ps1 -SupabaseCli $cli -BatchSize 100
```

Chạy từng pass có giới hạn; offline worker không mất messages/outbox. B media worker executable giữ nguyên, SQL verification giữ conversation context và cleanup thêm message lifecycle. Chat dispatcher max5/backoff/lease2m/attempt fencing, private-only gate; scoped checkpoint requeue sau xử lý root cause. `-TestPublishFailure` là local fault injection để kiểm retry/dead, không outage thật. Tests requeue/reclaim đúng event fixture rồi publish thật và quan sát socket delivery.

Realtime private-only config gọi API của service chạy qua release RPC, fixed tenant realtime-dev, không in tenant secret struct. PostgreSQL app role không có quyền UPDATE tenant managed; cập nhật DB thủ công rồi restart bị seed ghi đè và cache có thể cũ. Cách cuối dùng service API đồng bộ DB/cache/disconnect và kiểm private/public channel integration. Không sửa Cloud hoặc credentials/tenant JWT secret.

Local service role và anon key chỉ lấy từ CLI status đã guard endpoint, giữ memory. Không in raw status/containers env. Auth emails/passwords/JWT không lưu artifact. FFmpeg temporary publisher-checksummed tools tái dùng06, ảnh/video synthetic xóa đúng fixture directory sau suite. Không cài phần mềm hệ thống hoặc sửa Android.

Tổng migration15 phải checksum/replay upToDate; protected-hashes kiểm source Android/old migrations/reports/evidence. Runtime khi bàn giao Supabase9 containers RUNNING,private_only=true,TTL3600; portable PG STOPPED. Dừng giữ dữ liệu khi cần: `& $cli stop`.

Chi tiết semantics/limits/authorization/Android gates: [implementation C](../MEETO_PHASE_C_IMPLEMENTATION.md), [báo cáo07](../../baocao_nhiemvu07.txt).
