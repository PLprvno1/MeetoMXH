# MEETO — Task 54B: Admin Center logic hardening

Ngày: **07/11/2026, Asia/Bangkok**. Workspace: `F:\MeetoMXH`.

**SOURCE IMPLEMENTED / SOURCE REVIEWED. CLOUD VERIFIED: NO. RUNTIME VERIFIED: NO.** Migration được chuẩn bị ở local, chưa chạy PostgreSQL hoặc deploy Cloud. Không chạy Gradle, compiler, unit test, emulator, E2E, APK/AAB, Edge Functions hoặc Git reset/push.

## A. Implementation

Đã đối chiếu các đường Admin/Moderator với Task 54A và triển khai logic cùng sửa nhẹ màn Forms/Admin hiện hữu. Chưa triển khai shell, Fragment, RecyclerView hoặc redesign toàn bộ Task 54C.

### File thêm

1. `supabase/migrations/20261107001100_admin_logic_hardening.sql`.
2. `app/src/main/java/com/example/meetomxh/supabase/AdminModels.java`.
3. `app/src/main/java/com/example/meetomxh/supabase/AdminRepository.java`.
4. `app/src/main/java/com/example/meetomxh/supabase/AdminReportActions.java`.
5. `TASK54B_ADMIN_PREDEPLOY_READONLY.sql`.
6. `TASK54B_ADMIN_POSTDEPLOY_READONLY.sql`.
7. Báo cáo này.

### File sửa

1. `AdminPanelActivity.java`.
2. `ModerationDashboardActivity.java`.
3. `supabase/AdminRoleResolver.java`.
4. `SettingsActivity.java`: probe moderation entry bằng capabilities, không tải hàng đợi để kiểm quyền.

Không sửa migration đã có, Music Task 52, Comments Task 53, XML, package/applicationId, transport Supabase dùng chung hoặc Android Messenger. Hai helper cleanup được override trong migration mới vì là dependency trực tiếp của việc giữ bằng chứng; xem B/C/F.

### Đối chiếu các phát hiện 54A

| Phát hiện | Thay đổi source |
|---|---|
| F01 mở report tự claim | Mở chỉ gọi detail/đọc receipt của ý định trước; nút Nhận xử lý riêng mới gọi mutation. |
| F02 Moderator thiếu evidence/actions | Hai màn dùng `AdminReportActions`, Detail scoped và `permitted_actions` từ backend. Moderator có hide/remove khi hợp lệ, không suspend. |
| F03 search report không tác dụng | Report page v2 tìm trên server; exact state có queue assigned riêng. |
| F04 remove khác nhau | Direct và report gọi chung locked transition, cùng text/media/counter policy. |
| F05 audit/missing target | Suspend dùng account delta; Story/Message kiểm tồn tại, state và UPDATE thực. Missing target chỉ dismiss; không ghi gỡ thành công giả. |
| F06 UUID thay khi retry | RequestIds theo tài khoản/RPC/payload chuẩn hóa; receipt actor/request và payload binding ở DB. |
| F07 refresh giữ cursor | Mutation thành công reset trang đầu; reload không dùng cursor append. |
| F08 audit thiếu liên kết | Projection v2 có actor/action/report/request/subject, reason, old/new state. |
| F09 hết trang vẫn More | Các page v2 lấy limit+1, trả has_more và next_cursor; append rỗng ẩn More. |
| F10 lỗi quyền bị thành media/upload | AdminError xử lý HttpError trước ErrorMapper chung; theo status/code, không tìm chữ 403 trong bản dịch. |
| F11 claim audit / notice | Claim/reassign có audit; warning/receipt/detail ghi notification chưa triển khai. Chưa bổ sung delivery workflow. |
| F12 nhãn khóa tạm thời | Khóa đến khi được mở lại, suspended_until=NULL, không scheduler. |
| F13 Story caption | Shared report detail đọc caption, media và evidence_status. |

### Tương thích

Giữ public signature cũ của list/claim/resolve/detail/direct account/content. **`list_platform_reports(p_state='open')` vẫn gộp open+assigned**; client mới dùng page v2 với state exact. Legacy resolve giữ terminal tuple replay cùng actor/action/reason. Direct request đã commit trước 54B có thể replay khi action/reason/subject khớp audit; không chạy lại target mutation. Payload khác cùng request ID trả conflict.

Legacy claim vẫn trả target/reason/details sau khi đã được nhận hợp lệ. Private implementation đổi sang shared primitive nhưng wrapper cũ giữ signature. Old direct/report remove được thống nhất theo mặc định bảo toàn bằng chứng: đây là thay đổi hành vi có chủ đích, không giữ hành vi retire file ngay của direct remove cũ.

Client mới **cần migration 54B**. Thiếu RPC/schema cache trả lỗi contract/retry; không fallback sang auto-claim hoặc tự coi lỗi mạng là user thường. User active bình thường nhận capabilities với các cờ false; Moderator vẫn đi social shell/Settings, chưa chuyển routing sang shell 54C.

## B. Database và triển khai owner

Migration mới có BEGIN/COMMIT, prerequisite guards, 13 public RPC wrappers sinh từ signature private đã định nghĩa, ACL explicit và NOTIFY reload schema. Guard kiểm dependency và một số cột trọng yếu; **không chứng minh live definitions giống source**.

### Objects

| Object | Mục đích / quyền |
|---|---|
| `private.admin_action_receipts` | PK actor_id/request_id; payload JSONB và committed receipt. Actor reference không FK để không chặn xóa profile. Không cấp table privileges cho client/service_role. |
| `private.moderation_media_holds` | PK asset_id, FK RESTRICT; giữ file chờ owner chốt retention. Không endpoint client tạo/xóa hold. |
| `private.moderation_text_evidence` | PK target_kind/target_id; giữ body/caption khi gỡ. Không FK target để tránh cascade làm mất bản lưu. Không public projection/table grants. |
| `media_assets_moderation_hold` | BEFORE UPDATE OF state, ngăn đưa held asset sang pending_delete/deleted qua đường ghi khác. |
| Internal helpers | target projection, permitted actions, locked transition và action transaction; owner-only EXECUTE. |

Cả ba bảng private bật RLS, không có client policies/grants. PK cung cấp index cho lookup/idempotency. Không thêm index tối ưu search/dashboard khi chưa có EXPLAIN. Không sửa system_roles, auth.users, social RLS hoặc policy đọc cả conversation.

Public wrappers SECURITY INVOKER; private implementation SECURITY DEFINER, `search_path=''`. Read entries STABLE; mutations VOLATILE. Entry functions chỉ GRANT EXECUTE authenticated; role/active/assignment vẫn kiểm trong body. Internal helpers, `retire_asset`, `schedule_media_cleanup` revoke external EXECUTE. Migration cũng chuẩn hóa ACL các legacy entry đã override. Owner phải đối chiếu owner/BYPASSRLS, inherited rights và private schema exposure bằng catalog.

### Dependencies / thứ tự áp dụng

1. Owner chạy `TASK54B_ADMIN_PREDEPLOY_READONLY.sql` trên môi trường đích, xem từng SELECT; phần ledger chỉ chạy khi relation ledger tồn tại. Không sửa ledger bằng tay.
2. Đối chiếu foundation/auth/RLS/identity/writer guards `20261101000100`–`20261101000400`, schema media/chat/story, Reel `20261103001600` (is_reel/cover), reports `20261103000700`, Admin `20261104000100` + read-only fix `20261104000200`, privacy/consent `20261106000100`/`06000200`, final moderation `06000300` và detail/media/cleanup `06000400`. Đối chiếu các override sau đó theo thứ tự migration thực, đặc biệt Reel lifecycle/cover trigger `06000900` và worker `20261101001600` nếu vận hành cleanup.
3. Music `00900`/Comment `01000` giữ nguyên. Sự hiện diện của Task 53 **không** chứng minh các Admin prerequisite đã deploy. Music provenance dùng `to_jsonb(...)->'music_metadata'`, không gọi Openverse hoặc sửa metadata contract.
4. Nếu definitions/ACL/triggers drift, owner xử lý prerequisite/drift trước; không deploy chắp vá bằng cách bỏ guards. Owner cần xem capacity/retention và worker đang xử lý file; không thể phục hồi object đã xóa bằng migration này.
5. Owner áp dụng migration `20261107001100` đúng một lần qua cơ chế migration chuẩn. Không rerun file CREATE như script idempotent, không cherry-pick statements ngoài transaction.
6. Owner chạy `TASK54B_ADMIN_POSTDEPLOY_READONLY.sql`, kiểm ledger, wrapper/private volatility/path/owner, effective ACL, ba bảng/constraints và trigger enabled. PostgREST reload schema trước khi dùng client mới.
7. Sau đó mới nghiệm thu bằng tài khoản/target thử riêng được owner cho phép. Các script catalog không thay thế test RPC/Storage/runtime.

Hai file kiểm tra chỉ SELECT catalog/definitions/signatures; không SET role/JWT, không gọi business RPC, không chọn token, auth.users rows, message bodies hoặc private evidence rows. **Chưa chạy cả hai file.**

## C. Logic correctness ở source

### Report lifecycle và claim

Read detail không ghi assignment/state/action/audit. Admin xem case hiện có; Moderator xem summary unassigned open/assigned nhưng target/details/media/history bị redacted. Bằng chứng đầy đủ chỉ sau assigned cho chính Moderator. Case assigned người khác trả unavailable với Moderator.

Ảnh/video tiếp tục mở viewer authenticated sẵn có. Attachment audio mở dialog nghe riêng trong shared Admin controller, lấy media_assets qua SupabaseStorage/RLS, dùng user headers và một MediaPlayer; không đưa audio vào image viewer, không mở conversation hay thêm quyền media. Dialog đóng/onStop/đổi identity giải phóng player; metadata/network callback kiểm generation/session. Chưa xác minh codec/phát âm thanh trên máy.

Explicit claim lấy writer guard rồi khóa report FOR UPDATE. Open/assigned chưa có assignee mới được nhận; claimant khác nhận conflict. Cùng actor claim lại assigned case thành công nhưng không thêm audit. Assigned case mất assignee do FK có thể được nhận lại, không bị giấu vĩnh viễn. `admin_claim_report_v2` dùng cùng implementation, tránh probe social Task36 cho thao tác Admin mới; server role/assignment guards vẫn giữ. Media viewer vẫn dùng authenticated Storage/RLS hiện có.

Reassign/release là API Admin-only, reason 1–1000, expected_assignee, request ID, terminal guard và audit assignment. New assignee phải có role thực, active/onboarded và email confirmed theo writer guard hiện có. `p_assignee=NULL` release về open. Không tự takeover khi mở case. Chưa thêm UI reassign đầy đủ ở màn cũ; 54C dùng contract này.

### Chuyển trạng thái

| Target | Hành động / precondition |
|---|---|
| Post/Reel | hide published→hidden; restore hidden→published ở direct Admin; remove trạng thái còn tồn tại trừ deleted/rejected→deleted. Reel report vẫn canonical target_kind=post. |
| Comment | hide/restore tương tự; remove không nhận deleted/rejected. Chỉ thay comment này; body tombstone=NULL, ancestry/parent giữ nguyên. |
| Story | Report remove active/archived→deleted; expires_at độc lập. Không thêm direct Story mutation hoặc restore. |
| Reported Message | Report remove sent→removed; body public NULL. Không cho remove recalled/removed như một thay đổi mới. |
| Account | Admin suspend active→suspended; restore suspended→active; suspended_until=NULL. Deleting không restore. |
| Warn | Target/owner phải còn hợp lệ, ghi action/audit; old/new không bịa thành đã đổi state hay đã gửi notice. |
| Dismiss | Đóng case kể cả target đã mất/Community Group retired; không giả là gỡ nội dung thành công. |

Self/system-admin owner được bảo vệ cho các mutation non-dismiss. Moderator không được direct quản lý tài khoản/content hoặc suspend. Actor lấy từ identity, không từ input Android. Expected target state được đối chiếu khi có; client mới gửi state đọc lúc xác nhận. Chưa thêm optimistic version token cho sửa nội dung giữ nguyên state; không hứa chống mọi thay đổi body giữa xem và quyết định.

Target được khóa trước đổi state; UPDATE chính có FOUND checks. Account suspend đọc/khóa account state riêng nên audit không nhầm state Post/Comment thành account state. Target/action, report terminal state, action row, audit và receipt cùng transaction; lỗi rollback tất cả.

### Bằng chứng / counters / cleanup

Remove giữ Post body và media links. Đồng thời giữ bản text riêng khi gỡ Post/Reel/Story/Comment/Message. Comment/Message phải NULL body trên bảng public vì tombstone/REST cũ còn có đường đọc; private projection chỉ trả bản giữ lại trong scope Admin/case được phân công. Không sửa `can_read_comment`, `list_comments`, `list_comment_tree` hoặc Messenger projection để lách ACL.

Giữ ready media liên kết, bao gồm Reel cover, bằng hold. Backfill hold cho ready assets đang được report tham chiếu. Không tải/copy file, không hồi sinh pending/deleted assets. Mutation gặp linked media pending_delete trả conflict `evidence_cleanup_in_progress` để tránh giả rằng file đang được worker xóa đã được giữ an toàn.

Cleanup bỏ qua held asset. `retire_asset` hoãn retirement của held asset và không tạo outbox retire mới; author delete flows dùng helper này vẫn có thể xóa social reference, file được giữ. Trigger chặn đường UPDATE state khác vượt hold. Đây là dependency trực tiếp tới cleanup, không phải thiết kế lại Messenger/Music/worker.

Mặc định hold không tự hết hạn, **không có release RPC hoặc scheduler mới**. Owner phải duyệt retention và quy trình giải phóng an toàn trước khi thu hồi storage. Asset đã mất trước migration không được phục hồi; nếu typed target FK đã NULL thì detail báo target_unavailable, không tự dựng target từ text snapshot. Snapshot lúc gỡ không phải immutable snapshot tại thời điểm user gửi report, cũng không giữ toàn bộ conversation/lịch sử chỉnh sửa.

Share count giảm đúng một lần khi shared Post được remove. Hide không giảm share count, restore hidden không cộng lại giả. Comment count thay đổi khi qua ranh giới published; replies còn published không bị gỡ theo ancestor. Direct/report dùng cùng primitive nên không có hai công thức cleanup/counters khác nhau. Không tự sửa counters lịch sử đã lệch.

### Idempotency và retry

Android chuẩn hóa thứ tự key payload trước lấy RequestIds (gắn UID/RPC/payload), không tạo UUID mới ở mỗi lần confirm/retry. Lý do/target/action/expected state đổi là ý định khác. ID pending giữ qua process bằng RequestIds; dialog draft/reason giữ qua Bundle/configuration và onStop/resume. Thành công mới complete intent; double tap bị disable khi gửi.

Trước submit, client đọc receipt theo request ID. Nếu có committed receipt, không gửi mutation nữa. Backend khóa writer/request, unique actor/request, so JSONB payload, trả saved receipt/replayed; payload khác conflict. Cùng target qua hai case khác nhau không tự đóng các case còn lại. State precondition chặn hide/remove lặp không còn hợp lệ; transaction không giữ lock qua network.

Receipt đọc lại được đánh dấu replayed. UI phân biệt kết quả đã lưu với một mutation mới và luôn reload trạng thái hiện tại: receipt của lần suspend cũ không được dùng để khẳng định tài khoản vẫn suspended sau khi người khác đã restore. Chỉ sau khi đối chiếu xong ý định cũ mới complete ID; thao tác xác nhận mới về sau có ID mới.

Report draft được khôi phục có thể đọc receipt của ý định cũ trước khi mở lại detail, kể cả case đã terminal sau timeout. Không tự retry mutation khi resume. Direct draft giữ expected state cũ, kiểm receipt khi người dùng retry. Nếu response/callback bị hủy sau commit, receipt vẫn ở DB; không suy từ Toast hay local state rằng đã commit.

Receipt RPC chỉ actor hiện tại có role/active hợp lệ, không nhận actor ID; không dùng request người khác đọc kết quả. Revoke role/inactive chặn cả read receipt. Legacy terminal tuple replay giữ tương thích; không nâng nó thành request-ID guarantee cho app cũ vốn không gửi ID.

### Audit, notifications và errors

Audit v2 có actor/action/report/request/subject/time/reason/old/new. Claim/reassign là assignment events, có thể không có moderation action_id; request của reassign lấy từ metadata. Dữ liệu cũ thiếu old/new/delivery tiếp tục để unknown/null, không backfill state suy đoán. Audit chỉ là committed history, không giả có failure-attempt log trong transaction đã rollback, không tamper-proof với DB owner.

**Notification delivery: NOT IMPLEMENTED.** Không tạo Edge Function/outbox moderation hoặc thông báo cho suspended user. Detail/receipt mới ghi `notification_status=not_implemented`; UI gọi “Ghi nhận cảnh cáo (chưa gửi)”. Không có câu “Đã gửi cảnh cáo”. Policy suspended-user notice/appeal/delivery còn cần owner quyết định trước task riêng.

AdminRepository giữ typed ErrorKind/status/code: session 401, permission 403, unavailable 404, conflict 409, retired 410, validation 400/422, contract PGRST202/JSON shape, network, server. Không dump serverMessage/raw response/reason vào log. Màn Admin dùng enum để reverify/clear dữ liệu, không tìm chuỗi thông báo. Shared ErrorMapper và các màn khác giữ nguyên.

Repository sở hữu Subscription và generation; reset filter/page hủy jobs cũ, onStop hủy request/debounce, đóng dialog và xóa rows nhạy cảm. Resume reverify/reload. Append error giữ list đang có và cho retry đúng trang; có loading/error/empty riêng. Không optimistic mutate hoặc đếm thành công trước receipt. Đây là kiểm soát từ source, chưa tái hiện race/lifecycle trên thiết bị.

Identity listener xóa draft/data nhạy cảm, hủy jobs/player và chuyển về RoleResolution để xác minh tài khoản mới; listener được gỡ khi destroy. Token playback chỉ nằm trong request/player headers, không lưu vào draft, receipt hoặc log.

## D. Contract cho Task 54C

Các RPC mới trả JSONB, dùng tên parameter bên dưới trong migration. `contract_version=2` trên capabilities/detail/pages/decision receipt. Page = `{items,has_more,next_cursor:{created_at,id}|null}`; cursor time/id phải cùng null hoặc cùng có; sort `(created_at DESC,id DESC)`. Page limit 1–50; riêng content 1–49 vì tái dùng list cũ với limit+1; client hiện gửi 30.

| Màn / nhu cầu | RPC / input | Output và quyền |
|---|---|---|
| Gate/Phiên | `admin_get_capabilities()` | Actor reference, roles/can_* thực, version; active user không role nhận false. Network/shape lỗi là retry. |
| Dashboard | `admin_dashboard_v2()` | Counts cũ thật + audit recent v2. Admin-only; users_active là account state, không DAU/online. Client kiểm field thay vì mặc định 0 khi thiếu. |
| Report Center | `admin_reports_page_v2(p_state,p_kind,p_query,p_mine,p_after_time,p_after_id,p_limit)` | Exact state, target-kind, own assignment filter; search reason literal substring/ID chính xác, details chỉ trong scope có quyền. Không search reporter identity. |
| Report Detail | `admin_report_detail_v2(p_report_id)` | Report/assignment, target state/version khi loại có version, caption/body/media, evidence status, history và permitted_actions; read-only, scoped. |
| Claim | `admin_claim_report_v2(p_report_id)` | Atomic receipt với assigned_to/state; chỉ CTA rõ ràng, conflict reload. |
| Decision | `admin_decide_report_v2(p_report_id,p_action,p_reason,p_request_id,p_expected_state)` | Committed receipt, target/account delta + terminal case state; Moderator assigned chỉ permitted actions, Admin được suspend. |
| Receipt | `admin_action_receipt(p_request_id)` | Own committed receipt hoặc null; không mutation. |
| Recovery assignment | `admin_reassign_report(p_report_id,p_expected_assignee,p_assignee,p_reason,p_request_id)` | Admin-only; expected assignment, stable replay; null assignee là release. |
| User list | `admin_users_page_v2(p_query,p_state,p_after_time,p_after_id,p_limit)` | Admin-only, tên substring hoặc UID chính xác, active/suspended/deleting filter, roles thật. Không email/phone/auth metadata. |
| Content list | `admin_content_page_v2(p_kind,p_query,p_state,p_after_time,p_after_id,p_limit)` | Admin-only; plural posts/reels/comments, per-type states. Groups vẫn PT410. Không thêm global messages list. |
| User/Content detail | `admin_subject_detail_v2(p_kind,p_target_id)` | Admin-only; singular account/post/reel/comment/story, target, permitted actions, roles khi account, history preview tối đa 30. Story read-only qua entry này; messages chỉ Report Detail. |
| Direct action | `admin_moderate_subject_v2(p_kind,p_target_id,p_action,p_reason,p_request_id,p_expected_state)` | Admin-only; cùng transaction/receipt/transition với report. Direct Story mutation chưa hỗ trợ. |
| Audit | `admin_audit_page_v2(p_after_time,p_after_id,p_limit)` | Admin all; Moderator chỉ case được assigned hiện tại. Actor/action/report/subject refs + state delta/reason. |

Models: `Capabilities`, `Report`, `Content`, `Account/AccountState`, `Receipt`, `AuditEntry`, `Page`. `Page` kiểm required items/cursor shape; unknown AccountState được giữ là UNKNOWN. Shared old screens dùng các model ở boundary cần thiết; 54C có thể chuyển rendering JSON còn lại sang typed adapters mà không đổi contract.

54C confirmation phải giữ lý do và ý định khi timeout, hiển thị target/current state, dùng allowed actions của detail, không tự claim/auto-next. Missing target, claim_required, protected/retired, closed case và network error là các trạng thái khác nhau. Sau receipt: invalidate list/detail/dashboard và tải page 1; append lỗi giữ list. Từng capability trong UI vẫn cần server kiểm lại ở RPC.

Giới hạn projection cần giữ rõ: User/Subject history chỉ preview 30, chưa có related-report/content cursors chuyên biệt; audit page hiện có cursor nhưng chưa có filter actor/date/subject. Direct detail không cấp quyền Storage mới cho hidden media không có case; preview protected media qua report-scoped authorization. Đây là các phần mở rộng 54C/contract sau nếu cần, không có nút giả trong task này.

## E. Verification

| Nhãn | Kết quả |
|---|---|
| SOURCE IMPLEMENTED | YES: logic/Android/migration/contracts trong phạm vi trên. |
| SOURCE REVIEWED | YES: đối chiếu guard/scope, legacy signatures, shared transitions, tombstone redaction, cleanup references, receipt/pagination/lifecycle call sites. |
| CLOUD VERIFIED | **NO**: không có catalog/ledger kết quả live; chưa deploy migration hoặc chạy pre/post SQL. |
| RUNTIME VERIFIED | **NO**: chưa compile Java/SQL, chưa chạy Android/Storage/worker/API actions. |
| BLOCKED / NEED OWNER ACTION | Deploy/integration: owner đối chiếu drift/prerequisites/retention/worker, áp dụng migration và nghiệm thu trên target thử riêng. Source độc lập đã triển khai. |

Đã kiểm delimiter Java trên 7 file sửa/thêm và kiểm hai script chỉ có SELECT sau loại comment/string. Đã rà function read/write volatility và đối chiếu tên RPC client với definitions/allowlist wrapper. Những kiểm tra lexical/call-site này **không phải compiler, SQL parser hoặc test runtime**. Shell không có git/psql/Python/Node được tìm thấy qua PATH; không dùng GitHub thay local source, không cài tool hay chạy build để lách giới hạn.

Các case owner cần nghiệm thu sau deployment: Admin/Moderator/User/suspended/unonboarded; unassigned detail không mutate; concurrent claim; Moderator xem case khác; scoped reported message; protected/self target; missing Story/Message; duplicate request cùng/khác payload/actor; timeout sau commit rồi receipt; account suspend/restore cạnh tranh; share/comment counters; tombstone không lộ body qua legacy REST/list; cleanup/cover trigger/held files; report state/search/last page; rotate/stop/resume/session switch; old app wrappers. Mọi case này hiện **UNVERIFIED**, không ghi PASS từ static source.

## F. Remaining risks / owner decisions

- Chưa biết Cloud drift, function owner, default/inherited ACL, trigger overrides hoặc PostgREST schema exposure thực tế. Guard không thay thế preflight và so definitions. Client mới phải phát hành sau migration, không chỉ sau merge Android.
- Hold vô thời hạn làm tăng Storage; owner cần retention/deletion policy, capacity và quy trình release, kể cả tài khoản yêu cầu xóa. Không tự giải phóng media vì case đã đóng. Metadata/receipt/text evidence cũng cần retention riêng.
- File đã bị worker claim/xóa trước deployment không thể cứu bằng hold. Ready-registry không chứng minh object còn trong Storage. Backend owner/manual writes có thể vượt quy trình ứng dụng; cần operational coordination.
- Text lưu lúc moderation remove, không snapshot tất cả versions/tại report submit. FK target NULL vẫn có thể mất khả năng nối detail về target; audit/receipt giữ subject reference đã commit nhưng không dựng bằng chứng giả. Không sửa dữ liệu/audit/counters lịch sử đã lệch.
- Giữ global writer advisory lock hiện có để tương thích; chưa benchmark. Không claim throughput, EXPLAIN hoặc deadlock acceptance đã đạt. Expected state chưa bảo vệ riêng version của nội dung được sửa cùng state.
- Read guard hiện cho email hoặc phone confirmed; writer lock_profile vẫn yêu cầu email confirmed. Không nới auth guard trong 54B; phone-only privileged accounts cần owner quyết định riêng.
- Request IDs persist; reason/draft restore phụ thuộc Bundle/Activity state được Android lưu. Force-stop/xóa app data hoặc bỏ hẳn draft có thể mất ngữ cảnh local; server receipt/terminal state vẫn là nguồn kết quả. Chưa kiểm device/process recreation.
- Notification/notice delivery, suspended-user decision notice/appeal, role assignment và full 54C navigation chưa triển khai. Warning hiện là committed moderation record, không có chứng cứ đã tới người nhận.
- Chưa xác minh compiler/API compatibility, dynamic wrapper SQL trên PostgreSQL thật, RLS/Storage access sau revoke, generic media viewer runtime hoặc các regression ngoài Admin. Cần acceptance thực, không thể kết luận chỉ từ delimiter checks.
