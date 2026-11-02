# Meeto — tự đánh giá thiết kế
Ngày01/11/2026. **Tabletop/design review, không phải test backend thành công.** Không kết nối Supabase/PostgreSQL, không execute SQL, không dùng fake data để công bố runtime pass.

## 1. Kết luận review
Thiết kế bao phủ8 nhóm nghiệp vụ ở mức schema và hợp đồng:36 bảng (13 CURRENT/6 REQUIRED/16 FUTURE/1 OPTIONAL). DDL và RLS nháp có guard chặn thực thi; chưa là migration được duyệt. Chưa có mutation RPC bodies/Edge worker/client integration, vì vậy **chưa thể triển khai chạy app chỉ với hai SQL này**.

“Đạt theo thiết kế” trong các tình huống dưới nghĩa constraint+policy+transaction contract có đường xử lý, **không nghĩa function đã được cài hoặc test đồng thời đã qua**. Mọi RPC mutation là acceptance gate của giai đoạn triển khai.

## 2. 18 tình huống giả lập
| # | Tình huống | Bảng/constraints | RLS/quyền | Transaction/function dự kiến | Kết quả kỳ vọng và giới hạn |
|---|---|---|---|---|---|
| 1 | A tạo Friends Only, B nonfriend | posts.audience=friends; friendships canonical PK | can_read_post_base ->are_friends(B,A)=false | create_post/set_reaction/create_comment kiểm cùng ACL | B SELECT/search/media/share denied; không dựa filter Android; metadata cache cũ đã tải không thu hồi |
| 2 | A block B | user_blocks PK/no self; friendship/request/follows | is_blocked xét hai chiều; profile/post/direct conversation/message/media predicate | set_block pair-lock, cancel pending/remove relations; private sends dùng cùng lock | New requests/view/DM A-B denied; shared group metadata ngoại lệ có chủ đích; block chưa hủy bytes response đã bắt đầu |
| 3 | A và B gửi lời mời chéo đồng thời | friend_requests partial unique pending(pair); request UUID unique | actor only, no direct DML | send_friend_request canonical advisory xact lock | One pending request; request thứ2 nhận incoming_request_exists; không auto friendship; accept phải do recipient |
| 4 | Hai người like cùng bài, hoặc một người retry | post_reactions PK(post,user); count>=0 | can_interact_post; direct counter writes denied | set_post_reaction locks post; actual insert delta+1, type-change0, delete existing-1 | Hai users tăng2; same user/request desired-state không tăng2; no read-before-batch race |
| 5 | Send xong server commit, client mất ACK | messages unique(conv,sender,clientUUID), seq unique, request_hash | active member/read bounds; hash không grant select | send_message transaction + outbox; retry cùngUUID/hash | Same message ID/seq, không tin mới; UUID reuse khácpayload409; client phải persist UUID trước send |
| 6 | Recall ảnh chat | messages recalled/bodyNULL CHECK; message_media; media_assets state | can_read_media requires sent message; no owner ready bypass; object GET predicate | recall_message lock conversation/message, disable registry+outbox cùng tx; cleanup API async | Raw text/image GET request mới denied/tombstone; owner cũng denied; downloaded bytes/signed legacy URL là giới hạn không thu hồi |
| 7 | Member bị kick nhóm private | group_members state=removed; group lock | group_member false; posts/comments/media parent deny | manage_group_member + outbox invalidate removed user | SELECT/insert/comment/download mới denied kể cả biếtID; old Broadcast generic activity hint có cửa sổ cache nếu đã subscribe comment topic, không private body |
| 8 | Admin cuối rời group | group_members(group,user) PK, role state | No direct UPDATE/DELETE membership | leave_group/transfer_group_admin group row lock, count active admins | Reject last admin leave/demote hoặc transfer/archive atomically; hai admin leave đồng thời chỉ một leave pass; Auth deletion cũng đi workflow |
| 9 | Người mới vào group chat | conversation_members history_from_seq; messages seq unique | can_read_message seq>=join boundary | manage_conversation_member locks conversation, assign next_seq+1 | Không đọc/search/quote/media trước join; rejoin reset theo policy; all-history mode chưa duyệt |
|10 | Story hết hạn lúc đang mở | stories expiry=created+24h CHECK; active vs deleted/archive | can_read_story now()<expires_at; media same parent | record_view/reaction/reply recheck server time; cleanup job riêng | Request mới denied ngay dù cron chưa chạy; UI timer đóng; buffer/file đã tải không biến mất, archive chỉ owner nếu feature bật |
|11 | User xóa account, còn share/comment | author FK SET NULL; profiles FK Auth CASCADE; original_post RESTRICT; comments parent composite | state deleting deny caller ngay; original deleted deny source | phased account deletion, transfer group admin, private chat purge policy, scrub PII/body, recompute counters | Không dangling FK; share shell chỉ tác giả share thấy lời bình, source tombstone tối thiểu; comments tombstone, no DOB/contact giữ public; private-chat retention cần owner duyệt |
|12 | Moderator đọc report không assigned | reports.assigned_to/index; system_roles | can_review_report false nếu không own assignment/admin | report_context/apply_moderation verify case assignment+scope | SELECT denied, no evidence; guessed targetID không bypass. Group Admin không system Moderator |
|13 | User đổi role thành System Admin | system_roles PK; role không nằm public profile/settings | no Android DML roles/status, no user_metadata authority | grant_system_role privileged approved/audited only | REST update/insert/function actor-spoof denied; tự đổi JWT signed không hợp lệ; operator service key ngoài model client |
|14 | Đoán message ID của chat khác | messages conversation FK, reply same-conv composite FK | can_read_conversation false; message/member/media checks | hide/recall/read/send validate actual member before action | Raw SELECT/FTS/reply/media denied, UUID khó đoán không là kiểm soát duy nhất |
|15 | B bị block giữ URL media cũ | registry ready + parent ACL, private buckets | authenticated GET rechecks block; object.sign/list deny | set_block; server legacy migration/retire objects/CDN policy | New authenticated URL request denied; **legacy public/signed URL còn hiệu lực hoặc cache đã tải không tự hết quyền**. Gate retire old public objects; không hứa xóa bản sao |
|16 | Gửi lại request sau timeout | message/post/comment hash+UUID; group/conversation create UUID; natural desired-state keys | no arbitrary multi-row DML | same tx/RPC retry, consistent lock order, SQLSTATE40001/40P01 bounded retry | Same committed result, conflicting payload409, no duplicate event via event_key; terminal request idempotency bounded retention; client không tạo UUID mới mỗi retry |
|17 | Xóa post đang có nhiều comments/reactions | post state, child FK CASCADE khi purge, share RESTRICT | post ACL deny ->children/media deny; body không publish | delete_post lock parent+group; state transition once+disable media+outbox; chunk cleanup/recompute | Không cần xóa100k child synchronously; concurrent comment/reaction sau delete deny; existing tx theo lock order; tombstone refs xử lý trước hard purge |
|18 | Reconnect nhận event trùng/out of order | messages seq/id/version; notification ID/eventkey unique | subscriptions own/private scoped; refetch RLS | sync_cursor + refresh loaded windows; event dedup, snapshot/buffer/reconcile | Không duplicate row, read markers monotonic; query lại recall/hide ở seq cũ, không chỉ fetch new seq; Broadcast không là durable queue |

## 3. Những lỗi thiết kế đã sửa trong vòng tự review
- Không dùng raw comments Postgres Changes sau khi thêm request_hash: replication không được mặc định lọc theo column grants; chuyển comments sang generic private Broadcast invalidation.
- Không cấp SELECT Storage chung cho parent-readable user rồi cho phép tự tạo signed URL dài hạn; giới hạn authenticated GET operations; thiếu helper compatibility thì dùng proxy/fail-closed.
- Không cho owner đọc mọi ready asset của mình sau recall; access phải đi parent và state=sent/ready.
- Không cấp UPDATE birth_date trực tiếp vì có thể bỏ qua validate tuổi/phạm vi server; chỉ RPC.
- INSERT saved/hidden chỉ user_id/post_id để timestamp không do client tự chọn.
- Thêm immutable request_hash post/comment/message; unique create request cho groups/group conversations/media intent; metadata hash không SELECT Android.
- Same-post/same-conversation composite FK thay single parent/message reference; không auto copy reply preview nội dung cũ.
- No “public URL đổi sang private URL là bảo vệ link cũ”; legacy object retirement được ghi thành gate.
- Không thiết kế rollback một bước sau cutover; new Auth identities/sessions và new writes cần reverse rehearsal hoặc forward recovery.
- Không tự tuyên bố last-admin/expected2-members/content+media là CHECK đã enforce; RPC contracts và direct-DML denial được phân biệt rõ.
- Social guard bao gồm onboarding_completed. RPC complete_onboarding có gate riêng account active + Auth confirmed, validate required fields rồi set cờ; ảnh optional upload sau để tránh vòng prerequisite onboarding/media.

## 4. Mức đầy đủ và debt còn mở
| Câu hỏi review | Đánh giá |
|---|---|
| Đủ8 nhóm? | Có ở mức schema/contracts; nhiều feature FUTURE chưa triển khai |
| Bảng thừa? | Không có table riêng cho emoji/counters/warnings/notification types;36 bảng có reason trong Dictionary; comment_reactions duy nhất OPTIONAL |
| Thiếu lịch sử membership? | Một membership row giữ current boundary đủ policy mặc định. Nếu cần nhiều episode history/previous-member retained access thì phải bổ sung bảng intervals trước bật policy đó |
| FK xung đột? | Cycles profile/media và conversation/message dùng ALTER sau +nullable pointer/deferred FK. Referential delete workflow phải theo thứ tự, không direct Auth delete |
| Self-reference cycle? | DDL chỉ chặn self/same-parent; create RPC parent immutable +existing ancestor +depth limit; service/operator bypass có thể tạo cycle nếu không dùng contracts |
| RLS escalation? | No user role/status/counter DML; actor-bound helpers. Function ownership/default EXECUTE/exposed schema và policy cũ vẫn cần integration review |
| Race? | Locks/unique/desired-state/hash thiết kế cho core; chưa concurrency test, chưa RPC code; maintenance bypass là risk |
| Counters? | RPC một writer; purge Auth cascades cần recompute totals, không đồng thời trigger và RPC delta. Chưa reconciliation worker |
| Realtime revoke? | Generic comment topic có activity timing side-channel cho stale authorized connection tới reauth; nếu yêu cầu tuyệt đối thì per-user watcher routing hoặc polling. Data read/media request mới vẫn deny |
| Media revoke? | GET mới deny; existing response/download/signed public legacy/CDN có giới hạn vật lý. Chưa test Storage helpers/proxy range runtime |
| Auth migration? | No password/session direct transfer assumption; reset/OTP default; collision/provider verification chưa inventory |
| Android background push? | UnifiedPush/OEM/no-push cần lựa chọn; không bảo đảm độ tin cậy toàn Android không có distributor/provider |
| Archive? | Group archived fail-closed ở read helper draft, archive read view là feature contract riêng; Story archive tùy duyệt |
| Data minimization? | Contact ở Auth, DOB private, no quoted body snapshots; audit minimal; retention proposal chưa policy pháp lý/chủ dự án chốt |
| Scale? | Index/cursor/natural keys phù hợp giả định MVP; hot parent locks và RLS fan-out cần benchmark trước tăng quy mô |

## 5. Ma trận năng lực vai trò cần cố định trước viết RPC
- Group Admin/Moderator: membership và group content trong nhóm được giao; không reports toàn hệ thống, không suspend Auth account.
- System Moderator: assigned case hide/remove/restore/warn đúng nội dung case; account suspension/unlock mặc định **reserved System Admin** sau escalation. Không tự sửa role/claim.
- System Admin: assign/triage/role provisioning qua workflow có approval/audit; không bulk arbitrary private-data endpoint. Evidence private chỉ report_context gắn case hợp lệ, minimal necessary.
- Group report gửi central reports; Group Admin xử lý group content qua scoped RPC, không direct SELECT mọi reports. Nếu muốn giao group reports trực tiếp cho Group Admin thì phải thiết kế case scope bổ sung, không tự OR role group vào reports policy.
- Locked/deleting user không thể self-unlock. Appeals/support endpoint phải authenticatesuspended identity và chỉ thao tác case của mình, rate-limit, không cấp lại social SELECT.

## 6. Kiểm tra kỹ thuật đã làm và chưa làm
Đã thực hiện static cross-check trên file SQL/text:
-36 CREATE TABLE tên duy nhất; mọi public FK trỏ bảng được khai báo.
-50 explicit indexes, tên không trùng (chưa tính index implicit từ PK/UNIQUE).
-36 bảng RLS ON và REVOKE app permissions;34 bảng có SELECT policy,2 bảng server-only (event_outbox,migration_id_map).
-21 boolean authorization helpers private với SET search_path an toàn/explicit revoke và grants. Trigger user_settings timestamp là function riêng, không helper authorization.
- posts/comments/messages không grant SELECT request_hash; device_subscriptions không grant endpoint.
- Guard RAISE EXCEPTION và ROLLBACK trong cả2 SQL. Không đặt trong supabase/migrations.
- Kiểm tra UTF-8/nonempty, liên kết FK/Dictionary/status counts, report cũ giữ nguyên prefix và hash source/config ở cuối nhiệm vụ.

Chưa có psql/postgres/docker trên PATH kiểm tra; **không execute SQL**, không parse bằng PostgreSQL engine, chưa pgTAP/EXPLAIN/concurrency/RLS/Storage/Realtime/push runtime test. Static checks không phát hiện mọi lỗi syntax/type/catalog/version. Bắt buộc chuyển thành reviewed migrations và chạy staging tests trước triển khai. Không chạy lại Gradle vì không thay Android source/dependency và test Android cũ không xác nhận thiết kế database.

## 7. Quyết định cần chủ dự án phê duyệt
1. Public audience logged-in hay guest Internet; profile/private fields và block metadata ngoại lệ trong shared groups.
2. Email-only trước/phone OTP; reset/re-enrollment hay Auth import/middleware; identity collisions và incomplete onboarding.
3. Private/group chat history/rejoin; delivered receipt feature; recall deadline và account deletion retention private conversation.
4. Strict revoke tới mức nào: authenticated GET/proxy; legacy public object retirement; chấp nhận metadata timing stale Broadcast hay cần per-user watcher routing.
5. UnifiedPush không FCM có distributor, OEM adapters, hay MVP không background push.
6. Quotas media/types/mixed posts/files, retention delete/archive/audit/notifications và quyền restore.
7. Role/suspension/report assignment/evidence scopes; appeals/statistics priority; comment_reactions OPTIONAL.
8. Migration maintenance/rollback window/RPO, staging/test accounts và approver vận hành.

Bản thiết kế sẵn để AI/kiến trúc sư khác phản biện. Không có database thật được thay đổi, không sửa Java/XML/dependencies/Firebase code/local.properties hoặc storage.sql cũ.

Kiểm tra bàn giao cuối:10 tệp UTF-8 không rỗng;36 bảng/267 cột/50 explicit indexes/21 authorization helpers/34 SELECT policies và tên policy không trùng. Kiểm tra delimiter/quote/parentheses ở SQL và21 function bodies không thấy mất cân bằng; đây không phải PostgreSQL grammar/runtime validation. So sánh SHA-256 của130 tệp source/config với đầu nhiệm vụ:0 thay đổi.107.595 byte báo cáo cũ giữ nguyên hash, phần16 được nối thêm đúng1 lần. So khớp giá trị cấu hình local với nội dung bàn giao:không có giá trị URL/key thực được đưa vào tài liệu/SQL/báo cáo.


## 8. Bổ sung kiểm chứng nhiệm vụ04 (thay thế giới hạn “chưa chạy PostgreSQL” ở review03)
Đã chạy PostgreSQL16.11 local, DDL/RLS compile,99 assertions và3 patterns concurrent. Auth/Storage/Realtime là interfaces SQL tổng hợp, chưa Supabase integration. Xem MEETO_PRE_IMPLEMENTATION_AUDIT.md và validation/ để đọc evidence/commands/limits.
Đã sửa: JSON settings thiếu validation, Story reuse asset, media read purpose/owner confusion, default EXECUTE tương lai; thử per-schema revoke vẫn fail test nên dùng global revoke đúng owner. ERD bỏ4 cạnh FOREIGN giả,79 FK thực. Không thay36 bảng vì phong cách đặt tên.
Readiness tổng thể: NOT READY cho database Supabase thật; foundation draft có thể chuyển sang staging review có điều kiện. Mutation RPC, worker, service compatibility, migration approvals và product choices vẫn là gate, không được bỏ qua bằng mở direct DML Android.
