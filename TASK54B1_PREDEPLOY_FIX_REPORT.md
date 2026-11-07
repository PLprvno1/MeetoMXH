# MEETO — Task 54B.1: Predeploy Critical Fix Review

Ngày: 07/11/2026, Asia/Bangkok. Workspace: `F:\MeetoMXH`.

**SOURCE VERIFIED: YES, trong phạm vi đối chiếu source bên dưới. CLOUD VERIFIED: NO. SQL/ANDROID RUNTIME VERIFIED: NO.** Không chạy migration, business RPC hoặc script kiểm tra trên Cloud; không build/test Android/emulator, deploy Edge Functions hay push/reset Git.

Đã đọc báo cáo 54B và migration hiện tại, đối chiếu dependency trực tiếp trong các migration gốc. Bản local `20261107001100` được sửa theo ngữ cảnh **chưa deploy** của yêu cầu. Chưa đọc live ledger nên không xác nhận được trạng thái Cloud. Migration sửa lại có guard từ chối nếu ledger đã có 54B hoặc đã tồn tại object 54B. Nếu owner đã deploy bản cũ, **phải dùng migration mới**, không chạy lại file này hoặc sửa ledger để vượt guard.

## 1. Kết quả và thay đổi

| Điểm | Source xác nhận | Xử lý 54B.1 |
|---|---|---|
| Active/writer guard | Claim/reassign đã gọi `b_actor()` ở initializer. Helper cuối cùng gọi `lock_profile()`, khóa auth/account/profile và từ chối account không active, chưa email-confirmed/onboarded. `has_role()` cũng yêu cầu active. Không xác nhận được bypass inactive từ source ban đầu. | Thêm `PERFORM private.b_active()` đầu BEGIN của cả hai để thống nhất với mutation Admin khác, trước đọc/replay/assignment. Giữ nguyên writer lock và role checks. |
| Legacy/v2 claim | Public legacy wrapper là INVOKER → private claim; public v2 INVOKER → private v2 DEFINER → cùng private claim. Không có nhánh mutation riêng bỏ guard. | Giữ signature và delegate, cả hai dùng guard đã bổ sung. Reassign vẫn Admin-only; Moderator chỉ claim và xử lý case đúng scope. |
| Schema/EXECUTE | Foundation đã GRANT USAGE private cho authenticated; chỉ GRANT EXECUTE trong 54B không tự bảo đảm USAGE nếu live ACL drift. | Explicit GRANT **USAGE** trên public/private cho authenticated. Không GRANT CREATE, table access hoặc helper EXECUTE cho client. Thêm effective-privilege assertions và catalog checks. |
| Cleanup reference graph | Cleanup 54B chép các kiểm tra cũ từ 060004, thiếu Reel cover/album references có trong `private.task43_asset_live(uuid)` ở 060009. | Dùng graph cuối này ở cả candidate selection và recheck. Bổ sung prerequisite rõ ràng tới 060009. |
| Rollout/worker | Hold backfill và trigger cũ không tự đồng bộ toàn bộ quá trình deployment. Worker thực hiện Storage DELETE sau claim, trước finish RPC. Trigger/transaction không thể thu hồi HTTP DELETE đã gửi. | Lấy advisory writer lock 6061101 và table locks media_assets/event_outbox trước backfill; từ chối deployment nếu còn bất kỳ media.retire processing event, kể cả lease hết hạn. Owner phải pause/drain worker, không reset lease để lách guard. |
| Hold/retirement race | Hold creation trước đây chưa khóa rõ từng media row trước kiểm tra/ghi hold; retire helper kiểm hold trước UPDATE nhưng chưa khóa asset trước đọc hold. | Khóa asset rows theo UUID trong moderation transition; retire helper khóa asset trước kiểm hold. Không enqueue cho asset không tồn tại/đã deleted. |
| Lock order | Voice verification worker có thể đã giữ asset row trước khi gọi retire_asset; Reel cover trigger cũng khóa asset trước gọi helper. | Không thêm global advisory lock vào retire_asset vì sẽ đảo thứ tự row→global so với writer/cleanup global→row. Global lock giữ ở entry/cleanup/rollout như contract hiện có; helper dùng asset row lock. |
| Overload/owner drift | Vòng sinh wrapper theo proname có thể chọn overload ngoài dự kiến. CREATE OR REPLACE giữ owner cũ, trong khi helper mới thuộc migration role. | Whitelist 13 signature đầy đủ, kiểm return jsonb/DEFINER và đếm đúng 13 wrappers; dùng column alias rõ cho generate_series. Guard legacy overloads, exact legacy signatures, toàn bộ cột trực tiếp được dùng và owner của bảy private functions bị replace. Không tự ALTER OWNER/cấp quyền rộng để che drift. |
| SQL expression | Legacy replay có CASE trực tiếp trong IF expression dài. Chưa có PostgreSQL compiler để kết luận lỗi parse đã tái hiện. | Bọc CASE trong ngoặc để loại bỏ rủi ro PL/pgSQL đọc nhầm THEN. Đây là hardening source, không phải kết quả SQL runtime. |
| Notifications | Không có moderation delivery workflow trong scope 54B. | Giữ nguyên `not_implemented`; không thêm notification/outbox delivery, không tuyên bố đã gửi cảnh cáo. |

Các đính chính về dependency/cleanup/guard trong báo cáo này cập nhật phần tương ứng của báo cáo 54B trước đó. Không thay đổi contract metadata Music, Comment tree, Android Messenger hoặc UI 54C.

## 2. Quyền tối thiểu và fail-fast

Authenticated gọi public SECURITY INVOKER wrapper cần USAGE public, EXECUTE wrapper, USAGE private và EXECUTE private **entry function**. Sau đó private SECURITY DEFINER chạy với owner để truy cập bảng/helper nội bộ. `auth.uid()` vẫn là actor của JWT; DEFINER không đổi actor thành owner. Body vẫn kiểm identity/active/platform role/assignment.

Không cần cấp authenticated quyền SELECT/DML ba bảng private, hoặc EXECUTE `admin_target`, `admin_permitted_actions`, `admin_transition`, `admin_commit_action`, hold trigger function, retire_asset hay schedule_media_cleanup. Các quyền này vẫn revoke. Việc có USAGE không cho phép gọi helper nếu thiếu EXECUTE.

Migration thêm assertions trước COMMIT:

- Authenticated có USAGE public/private nhưng không CREATE private.
- Effective EXECUTE của RPC mới và legacy được override đúng authenticated-only; anon/service_role không có EXECUTE.
- Effective EXECUTE helper nội bộ bị chặn cho cả ba client roles, kể cả inherited/default ACL drift.
- Không có table/column privileges cho client roles trên ba bảng private, gồm REFERENCES/TRIGGER/TRUNCATE.
- Owner của worker/cover-trigger caller còn đủ USAGE/EXECUTE helper cần gọi. Worker broker chạy DEFINER có thể gọi helper bằng **owner**, không cần mở helper cho service_role.

Nếu không đạt, transaction abort. Không sửa role memberships, không mở bảng private và không đổi owner tự động. Predeploy yêu cầu bảy private replacement functions có owner bằng migration role; đây là điều kiện bảo thủ để owner xử lý mixed ownership trước, không phải chứng minh mọi mixed-owner setup đều sai.

Private schema vẫn phải không được expose trong cấu hình PostgREST. `current_setting('pgrst.db_schemas',true)` chỉ phản ánh setting khả dụng ở session; NULL không chứng minh cấu hình dịch vụ an toàn.

## 3. Media, worker cũ và yêu cầu xóa tài khoản

### Các đường đã đối chiếu

- `retire_asset`: helper gốc 010004; các callers Post/Story/Message, Reel cover trigger và voice verification worker.
- `schedule_media_cleanup`: override trước 54B ở 060004; reference graph `task43_asset_live` và cover trigger cuối ở 060009.
- Worker claim/finish SQL ở 010016 và voice worker 030015; TypeScript `supabase/functions/meeto-background-cleanup/index.ts` gọi claim → Storage DELETE → finish.
- Trigger `profile_media_retirement_cleanup` ở 030013 chạy khi media thật sự đổi sang pending_delete/deleted.
- Request deletion cuối ở 030007; FK media owner/reference trong foundation và các bảng private 54B.

### Invariants sau sửa, ở mức source

Hold mới/backfill chỉ lấy ready assets. Media pending_delete đang được cleanup không được chuyển thành hold bằng moderation; transition trả conflict và rollback. Hold, text evidence, target state/action/audit/receipt vẫn trong cùng transaction.

Held asset được bỏ qua bởi schedule và retire helper. Do không đổi state nên trigger album retirement không bị kích hoạt nhầm chỉ vì một yêu cầu retire được hoãn. Đường UPDATE state khác cố đưa held asset sang pending_delete/deleted bị hold trigger từ chối; hard DELETE registry bị FK RESTRICT chặn. Không nới Storage/RLS hoặc copy file ra nơi công khai.

Worker cũ chỉ claim asset ở pending_delete. Với hold ở ready và trigger chặn retirement, worker không nhận held asset qua luồng bình thường. Finish RPC giữ attempt/state fences cũ; không đổi broker signatures hay worker payload. Điều này phụ thuộc live worker/definitions đúng source và không có Storage DELETE đã được gửi từ trước. Không tuyên bố tương thích với custom worker bỏ qua state checks.

Cleanup dùng graph Task43 để giữ Post media, Reel cover, profile/conversation avatars, Story active chưa hết hạn, Message sent và album references có Post còn live. Actor/asset locks được giữ trong DB transaction; không giữ DB lock qua network.

### Account deletion: không tự hứa purge

`request_account_deletion()` hiện chỉ ghi deletion_requested_at/audit và trả pending_review; không đổi account state, không xóa media hoặc auth.users. Vì vậy deletion request vẫn hoạt động theo source cũ và không bị hold trigger chặn. Account có request nhưng **state vẫn active** không tự trở thành inactive; claim/reassign không được sửa để tự suy diễn một chính sách khóa mới. Account state suspended/deleting bị writer/active guards từ chối.

FK `media_assets.owner_id` là ON DELETE SET NULL. Hold FK trỏ asset, không trỏ profile; receipts/text evidence không FK profile/target. Do đó hold không tự chặn xóa profile chỉ vì quan hệ owner; nhưng file và private evidence có thể còn giữ. Một quy trình purge trực tiếp UPDATE/DELETE held media sẽ bị chặn hoặc phải hoãn theo policy. Không auto-release hold khi có deletion request hoặc report đóng.

**Giới hạn có sẵn của worker cũ:** claim SQL kiểm object_path bằng owner_id và whitelist purpose/MIME. Owner bị SET NULL có thể làm asset không được claim sau khi hold được release; Story/audio cũng cần owner đối chiếu hỗ trợ của worker thực tế. Không sửa worker/purge hoặc bịa luồng account erasure hoàn chỉnh trong 54B.1. Owner cần chốt retention, handling owner-less objects và quy trình purge/release riêng trước khi cam kết xóa toàn bộ dữ liệu.

## 4. Transaction, signature và dependencies

Migration vẫn có một BEGIN/COMMIT, DDL thông thường và NOTIFY chỉ có hiệu lực khi commit; không CREATE INDEX CONCURRENTLY, VACUUM, external HTTP hay COMMIT trong function/DO. Guard PostgreSQL 12+ phù hợp MATERIALIZED CTE/prokind; đồng thời yêu cầu `pg_catalog.gen_random_uuid()` vì mutation dùng tên UUID generator với search_path rỗng. Version tối thiểu không thay thế kiểm tra function/dependency cụ thể. Đây là kiểm tra cấu trúc source, **chưa xác nhận deploy thành công trên PostgreSQL thật**.

Đã đối chiếu tên/parameter/return type ở source cho legacy replacements và 13 v2 entries. Không đổi public signatures. Cột trực tiếp dùng bởi SQL mới được liệt kê đầy đủ theo bảng ở prerequisite guard, kể cả auth.users metadata columns được cần cho eligibility check; không chọn auth.users business rows trong script kiểm tra.

Phụ thuộc bổ sung đáng chú ý: **`private.task43_asset_live(uuid)` từ `20261106000900_unified_video_reel_management.sql`**, cùng dependencies của graph đó (Reel cover/profile albums/chat avatar/Story/Message). Guard chỉ chứng minh object/cột có mặt khi owner chạy, không chứng minh body live giống local. Owner phải đối chiếu definitions, constraints, triggers, owners và ACL; không suy deployment Admin từ Task 53.

Triển khai lần đầu: owner chạy predeploy read-only → xác nhận chưa có 54B → đối chiếu prerequisites/owner/overloads/retention → pause và drain cleanup thực sự → áp dụng migration nguyên transaction → postdeploy read-only → chỉ tiếp tục worker khi invariants đạt và owner đã kiểm tình trạng job. Không drop evidence/reset lease/sửa ledger để ép migration chạy.

Nếu 54B đã deploy: dừng sử dụng file amended này, đưa các delta cần thiết vào **migration timestamp mới** sau kiểm tra live state. Task này chưa tạo migration fix sau deployment vì yêu cầu/ngữ cảnh hiện tại là predeploy và chưa có bằng chứng Cloud đã cài 54B.

## 5. Predeploy/postdeploy checks

Đã bổ sung vào hai file hiện có:

- `TASK54B_ADMIN_PREDEPLOY_READONLY.sql`: phát hiện mọi tên object 54B đã tồn tại; exact legacy/Task43 signatures, lock_profile/worker/deletion definitions; schema USAGE/CREATE, effective EXECUTE và owner, overload count thực, processing leases, ready Reel covers ngoài post_media, aggregate deletion requests/media và FK definitions.
- `TASK54B_ADMIN_POSTDEPLOY_READONLY.sql`: kiểm đủ 13 wrapper/private pairs, INVOKER/DEFINER, volatility/argument names, schema/EXECUTE; table/column/REFERENCES/TRIGGER/TRUNCATE privileges; owner-to-helper dependencies, guard/deletion/worker definitions; aggregate held states/processing events/owner-null/deletion requests và FKs.

Các file chỉ SELECT hoặc WITH … SELECT, gồm catalog và aggregate counts. Không business RPC, SET role/JWT, UPDATE queue, Storage calls, message/evidence body, token, object path hoặc UUID người dùng trong kết quả aggregate. **Chưa execute hai script.** Các counts có thể cần scan bảng; owner chọn thời điểm phù hợp cùng predeploy review.

Postdeploy cần điều tra nếu held asset ở pending_delete/deleted, worker processing event trỏ held asset, helper EXECUTE mở cho client, private table/column privileges hoặc owner không gọi được helper. Held ready có stale pending event không tự đồng nghĩa file bị xóa: worker còn kiểm asset state; không tự sửa queue để làm số liệu đẹp.

## 6. Verification và blocker

| Nhãn | Tình trạng |
|---|---|
| SOURCE VERIFIED | YES: các guard/call chains, schema/EXECUTE contract, signatures/columns, cleanup graph và deletion semantics nêu trên. |
| SQL SOURCE CHECKED | Kiểm cấu trúc transaction, delimiter, whitelist 13 signatures, read/write separation và SELECT-only artifacts; không phải PostgreSQL parser/compiler. |
| CLOUD VERIFIED | **NO**: không đọc live ledger/catalog, không chạy SQL hoặc thay Cloud. |
| SQL RUNTIME VERIFIED | **NO**: psql/postgres/pg_ctl không có qua PATH; Docker CLI có nhưng Linux engine pipe không tồn tại. Không bật service, cài PostgreSQL, pull image hoặc dùng DB có sẵn không rõ môi trường. |
| ANDROID RUNTIME VERIFIED | **NO**: không sửa Android trong 54B.1, không build/test/emulator. |
| BLOCKER / OWNER ACTION | Chưa biết installed status/drift, thiếu PostgreSQL khả dụng để compile, owner/ACL/prerequisites chưa được xác minh live; cần pause/drain worker và duyệt retention/account-purge policy trước rollout. |

Các negative/race cases chỉ được rà theo source: inactive/suspended/deleting caller qua legacy/v2 claim hoặc replay reassign; user thường/Moderator gọi reassign; hold vs retirement/worker lease; missing/extra overload và mixed owner; request deletion khi có hold. Chưa chạy các case này, không ghi runtime PASS.

Kết quả kiểm tra source thực hiện trong phiên: delimiter/outer transaction hợp lệ ở mức lexical; 25 function blocks được nhận diện, 12 STABLE bodies không có writer call/DML trực tiếp; 13/13 signature trong wrapper whitelist khớp definitions; hai mutation có b_actor+b_active; 7/7 private replacement signatures/return types khớp predecessor cuối theo thứ tự migration local; hai script chỉ SELECT/WITH … SELECT sau loại comment/string. Các kiểm tra này không thực thi SQL và không thay thế compiler/catalog live.

### Files trong 54B.1

1. Sửa `supabase/migrations/20261107001100_admin_logic_hardening.sql` theo giả định chưa deploy.
2. Bổ sung `TASK54B_ADMIN_PREDEPLOY_READONLY.sql`.
3. Bổ sung `TASK54B_ADMIN_POSTDEPLOY_READONLY.sql`.
4. Thêm báo cáo này.

Không sửa migration cũ, source Android, Music/Messenger, worker TypeScript hoặc UI 54C. Notification delivery tiếp tục **not_implemented**.
