# Meeto — Supabase Phase C, nhiệm vụ 07

Ngày 01/11/2026, Asia/Bangkok. Workspace `F:\MeetoMXH`.

**CODED + LOCAL TESTED**: private/group Messenger, sequence/idempotency, history/inbox/search, reply/recall/hide, explicit delivery/read ACK, private Broadcast, message media và workers local thực. **STAGING VERIFIED: chưa. PRODUCTION DEPLOYED: chưa.** Không sửa Android/Gradle/local.properties/Firebase/ImgBB, không kết nối Cloud, không triển khai community Groups/Stories/Moderation/push.

## Baseline và migrations

Đã xác minh runtime trước triển khai: 9 containers MeetoMXH đang chạy, managed PG17.11/Auth/PostgREST/Storage/Realtime thật; ledger001–009 và manifest đúng. Giữ nguyên 9 migration đã áp và evidence/báo cáo A/B. Không reset/delete volumes, không stubs/ledger repair. Schema vẫn **36 tables,79 FK,104 CHECK,110 indexes**; không cần thêm bảng/cột/FK/index, do đó không sửa ERD/dictionary kiến trúc.

| Migration mới | Kết quả |
|---|---|
| 20261101001000_messenger.sql | Core private/group RPCs, per-viewer projections, raw read columns/RLS, transactional chat events |
| 20261101001100_chat_media_realtime.sql | Private chat-media, conversation-bound intent/finalize, shared verifier/cleanup extension, own-user receive-only Realtime policy, dispatcher eligibility |
| 20261101001200_shared_media_quota.sql | B quota tính cả pending chat-media đúng worst-case50MiB |
| 20261101001300_chat_lock_order.sql | Shared actor guards cho C, pair/resource order tương thích writers A/B |
| 20261101001400_chat_search_contract.sql | Literal case-insensitive substring search theo Android, null/length validation |
| 20261101001500_chat_upload_admin_safety.sql | Standard upload-only restrictive policy; chỉ operative/onboarded/verified admin mới tính cho last-admin/promote |

Rehearsal010/011 trong transaction rollback thành công rồi CLI `db push --local` áp thật; các sửa tiếp sau apply bằng version mới. Ledger/manifest cuối15, không chỉnh bytes migration đã áp. Wrapper `Invoke-PhaseCDeployment.ps1` chỉ local, kiểm prefix ledger9→15, checksum và configure private-only sau apply. Các wrapper/handoff cũ A/B có expected ledger/grants lịch sử, không dùng trên stack C. Recovery: dừng ở lỗi, inventory ledger/catalog, sửa bằng forward migration; phục hồi backup/reconcile nếu có sự cố dữ liệu, không tự drop/reset.

## RPC và hợp đồng

**18 public RPC C mới**, cộng31 A/B = **49**. `finalize_upload` cũ được mở rộng theo purpose, không tính là RPC mới. Public wrappers invoker fixed arguments; private implementations definer `search_path=''`, owner-only internals không exposed. Actor từ auth.uid và verified current DB identity; không nhận sender/actor/admin/counter/time do client tự ghi.

| Nhóm | Entry points/tham số |
|---|---|
| Private | `get_or_create_private(p_peer)` |
| Group create | `create_group_conversation(p_title,p_members uuid[],p_client_request_id)` |
| Conversation reads | `get_conversation(p_conversation_id)`, `get_inbox(p_after_time=null,p_after_id=null,p_limit=20,p_include_archived=false)`, `get_conversation_members(p_conversation_id)` |
| Messages | `send_message(p_conversation_id,p_client_request_id,p_kind,p_body,p_assets uuid[],p_reply_to=null)` |
| History/search | `get_messages(p_conversation_id,p_before_seq=null,p_after_seq=null,p_limit=50)`, `search_messages(p_conversation_id,p_query,p_before_seq=null,p_limit=50)` |
| Receipts | `acknowledge_delivery(p_conversation_id,p_message_id)`, `mark_read(p_conversation_id,p_message_id)`, `get_read_receipts(p_conversation_id,p_message_id)` |
| Recall/hide | `recall_message(p_message_id)`, `hide_message_for_me(p_message_id)` |
| Group administration | `manage_conversation_member(p_conversation_id,p_target,p_action)`, `leave_conversation(p_conversation_id)`, `rename_conversation(p_conversation_id,p_title,p_expected_version)`, `set_conversation_state(p_conversation_id,p_state,p_expected_version)` |
| Media | `prepare_message_upload(p_conversation_id,p_mime,p_client_request_id)`, rồi public `finalize_upload(p_asset_id)` hiện có |

Private pair canonical unique, đúng2 matching active members, existing lookup hai phía trả cùng ID. Không cần friendship để chat private, nhưng peer active/onboarded/verified và không block. Private leave/archive tùy ý chưa bật; có hide từng message. Account suspension làm private conversation unavailable cho cả hai theo lựa chọn fail-closed.

Group chat dùng cùng conversations/members/messages, tách biệt community Groups. Group creator admin;1–49 invitees, tổng tối đa50 active members. Default an toàn trong phạm vi schema hiện có: chỉ **mutual friends của admin đang active/onboarded/verified, không block với inviter** được auto-add; chưa có invitation-consent workflow riêng. Người được thêm có thể leave. Không cam kết che roster của những người bị block cùng group: ID/role thành viên active vẫn là group metadata, tên/avatar chỉ khi profile ACL cho phép; message/media của blocked sender bị lọc theo viewer.

`p_action` chỉ add/remove/promote/demote; admin scope đúng conversation, không self-manage bằng target API. Leave tự thân, retry state left no-op; remove retry no-op. Thêm lại luôn role member, history_from_seq=next_seq+1, markers=0, joined_at mới; không khôi phục history/role/read markers cũ. Parent conversation row lock bảo vệ last operative admin; hai admin leave đồng thời chỉ một thành công, còn lại409. Không tự elevate user hoặc tự unsuspend. Operator khóa/de-onboard admin cuối vẫn có thể làm group không quản trị được: staff recovery/retention policy là operational gate chưa triển khai, không invariant cưỡng chế mọi trusted operator write.

Title1–150 không control characters, optimistic version; same desired state no-op. Group state chỉ active/archived, archive chặn gửi và upload nhưng thành viên hiện tại còn đọc lịch sử; inbox mặc định bỏ archived, có include flag. Restore phải admin, expected version. Không client hard-delete conversation/membership/history.

## Message, inbox, receipts và RLS

Sequence cấp dưới row lock conversation, không dựa timestamp client. Text body1–10000, media1–10 ordered ready assets; media caption có thể rỗng nhưng truyền string, assets text phải[]. kind system bị từ chối. Canonical immutable hash(kind/body/ordered assets/reply) và UNIQUE(conversation,sender,client_request_id): lost ACK/retry cùng payload trả cùng ID/seq; khác409. Recalled retry còn visible trả tombstone; hidden hoặc trước history boundary không được khôi phục bằng retry. Reply chỉ cùng conversation và sent/readable tại lúc tạo, không snapshot body. Projection recheck source ở mỗi fetch: hidden/blocked/outside history thì reply ID/preview=null, recalled body=null.

History max100; before_seq là strict descending older page; after_seq strict ascending catch-up, không truyền đồng thời. Envelope `{items,high_water,conversation_version}`; tombstones vẫn có version. Inbox max50, keyset(updated_at,id), strict descending. Item chứa own role/history/read/delivery markers, latest message **được viewer đọc** và unread_count: incoming sent, seq>own last_read, không hidden/blocked/prejoin. Không trả shared last_message_id pointer raw vì có thể là tin viewer không được đọc. Moving inbox rows cần dedup IDs/refetch trang đầu; không cam kết snapshot xuyên nhiều HTTP pages.

Search là **literal case-insensitive substring**, trim query2–100, không wildcard/SQL injection; only sent text thuộc current conversation ACL/history và không hidden/blocked. Quyết định này giữ hành vi `ChatMessage.matchesVisibleTextQuery().contains()` của Android. GIN FTS index scaffold vẫn giữ nhưng query contains không dùng nó; cần benchmark/trigram nếu volume đòi hỏi, chưa thêm index vô căn cứ.

Recall sender-only khi còn current access; không recall hộ member bằng group admin vì moderation chưa scope. Không hạn giờ tự đặt khi sản phẩm chưa chốt; recall scrub body, preserve hash/seq/reply graph, revoke assets trước purge. Hide một phía idempotent, không xóa bytes cho peers; raw messages/search/inbox/reply/media đều lọc user hidden. Không public edit_message; raw PATCH body/sender bị deny.

DB commit chỉ là sent/committed. Delivery chỉ khi device gọi acknowledge_delivery cho actual visible message ID; read khi client gọi mark_read, đồng thời advances delivery. `max(old,new)` nên stale/out-of-order ACK không giảm marker trong cùng membership episode. Markers là client assertion, không bằng chứng con người thực sự nhìn màn hình. `get_read_receipts` chỉ sender đang đọc được message, trả active recipients có history boundary bao gồm message, không blocked; mỗi recipient delivered/read boolean độc lập. Dispatcher/Broadcast ACK không tự set device receipt.

RLS/grants bật safe columns cho5 chat tables; hashes/requestUUID và raw reply/global summary pointer không client SELECT. Không direct DML/marker edits/member edits. Nonmember, guessed UUID, old JWT sau kick/leave, prejoin/rejoin history, own hidden/raw/private media đều deny. Archived chỉ read, không mutation ngoài state restore. Email verification/onboarding/account_status đọc DB, không tin stale JWT claim. Future community Groups/Stories/Notifications/Moderation grants tiếp tục đóng.

## Locks và concurrency

Private operations: canonical pair transaction advisory lock giống A/B block, rồi shared actor guards (Auth FOR SHARE, account status FOR SHARE, profile FOR KEY SHARE), rồi conversation row, assets UUID order. Group operations: shared actor guards→conversation→members/assets. C send, receipts, recall, hide, group management và message intent/finalize **không lấy global lock6061101**. C shared guards cho phép cùng actor gửi ở conversation khác trong khi một parent đang bị khóa.

Migration013 normalize B send/resolve request wrappers theo global→pair→actor, giữ nguyên receipt/state implementation; block đã cùng thứ tự. B social vẫn global serialize như06; C không chờ global đó. Worker verify khóa asset, cleanup dùng row locks + SKIP LOCKED và fresh linkage recheck, không global C bottleneck. Không có claim throughput/load production: probe chỉ chứng minh không serialize bởi global/unrelated parent, không là benchmark hiệu năng hay đảm bảo zero deadlock dưới mọi trusted operator workflow.

## Realtime thực và dispatcher

Private **Broadcast `user:<auth.uid>`**, event `invalidate`, payload chỉ `{event_id,scope:'messenger'}`; không conversation/message/sender IDs, body, media URL, hash hoặc roster. Client refetch inbox và loaded conversation windows qua RLS. `meeto_c_receive` trên managed realtime.messages chỉ SELECT extension=broadcast, own topic, active/verified/onboarded. Không INSERT/presence/publish client policy. Không public/resource-wide chat channels, không app Postgres Changes publication.

`Configure-ChatRealtimeLocal.ps1` gọi service API `Realtime.Api.update_tenant_by_external_id` trong đúng local Realtime container để bật private_only, cập nhật tenant cache/disconnect permissions. Không in tenant struct/JWT secret. Source pinned Realtime2.147.1; không remote admin API. Phải chạy lại sau CLI start/recreate: seed default có thể set private_only=false. Chỉ UPDATE DB rồi restart không đủ và có thể bị seed ghi đè, nên đã bỏ cách đó. Script kiểm DB flag và integration kiểm public join thực bị từ chối. Không coi handshake101 là channel auth.

Chat event cùng transaction nghiệp vụ; send retry/no-op không thêm send event. Completed group-create hash receipt state done không dispatch. `Invoke-ChatDispatcher.ps1` local một lượt claim chat.invalidate SKIP LOCKED, lease2 phút, fenced ACK/failure bằng attempt, max5 retry/backoff, dead với sanitized last_error. Reclaim expired lease, có replay/requeue bằng operator scope event ID sau xử lý nguyên nhân. Worker dùng local service-role key memory, POST private Broadcast loopback; không credentials trên arguments/disk/logs. Eligibility recompute current active verified account/member/history/block/hidden trước dispatch. Membership/leave/kick/state/block có generic clear hint tới snapshot recipients đã liên quan; event messages chỉ current eligible readers.

At-least-once: worker crash sau HTTP accept trước ACK có thể duplicate; DB message vẫn bền nếu worker dừng, realtime hint không là durable client delivery. Offline socket không backfill Broadcast: reconnect lấy seq>last synchronized và refetch loaded windows/inbox để nhận recall/hide/old-seq changes. Dedup event_id, đừng append theo event order. Cached channel authorization hoặc in-flight generic hint có thể còn tới sau revoke; payload không tiết lộ resource/content, data refetch RLS deny. Không cam kết che hoàn toàn generic timing hoặc thu hồi bytes đã nhận. Đây là local executable, chưa cloud durable service/scheduler/monitoring/push.

Nguồn ABI đối chiếu: [Realtime authorization](https://supabase.com/docs/guides/realtime/authorization), [Broadcast](https://supabase.com/docs/guides/realtime/broadcast), [v2.147.1 tenant update/cache](https://github.com/supabase/realtime/blob/v2.147.1/lib/realtime/api.ex).

## Media và Storage

`chat-media` private, PNG/JPEG/WebP/MP4, bucket50MiB; verifier image10MiB/video50MiB, ≤40M pixels, video≤300s theo B. PDF/text/other files chưa thiết kế bật, trả422; không giả file scanning. Giữ nguyên worker FFmpeg9.0.2 của B, mở rộng SQL ABI giữ metadata conversation_id server-only khi ghi verified. Không re-encode/sanitize EXIF bytes, antivirus/CDN/cache certification, transcoding hoặc large-video benchmark.

prepare_message_upload dùng current conversation permission/active state và quota chung≤10 pending/100 chưa deleted/500MiB reserved. Context gắn trong registry metadata server, không thêm cột/bảng; client không update metadata. User JWT standard POST bytes →shared worker decode/verify →finalize theo current membership →send_message checks ready/owner/purpose/context/single attachment. Ready chưa attach deny GET cả owner. Wrong conversation/purpose/owner không attach được. Khi kick/leave/rejoin mất read old assets vì message history boundary, không owner bypass. Recall registry deny trước Storage API delete; hide chỉ deny caller, peers giữ quyền. Orphan message assets24h cleanup, sent linked media không bị sweep.

Storage SELECT chỉ authenticated GET/info operation + registry/message ACL. INSERT chat policy exact intent+owner; **restrictive standard upload-only policy cho cả profile/social/chat** loại sign-upload, TUS/S3/copy operation khỏi INSERT allowlist; upsert/update/delete không có policy. Download sign/list/public route không mở. Signed upload capability endpoint được test deny ngay cả owned pending intent. Normal B upload vẫn regression pass. Operation tên đối chiếu [Storage v1.84.19](https://github.com/supabase/storage/blob/v1.84.19/src/http/routes/operations.ts). Range/cache giới hạn B vẫn áp dụng; request mới bị revoke không thu hồi được cached/downloaded/in-flight bytes.

## Evidence thực chạy

| Evidence07 | Kết quả |
|---|---|
| http-results.json | **511 HTTP assertions,0 fail**, gồm actual GoTrue JWT matrix18 RPC, private/group flows, security/media,8 overlapping races + independence probe; có HTTP refetch dùng trong WS suite |
| websocket-results.json | **40 WS assertions,0 fail**,3 actual clients: delivery/auth/reconnect/refresh/invalid JWT/public deny/read-only publish/receipts/recall/membership/state/block/kick/leave/suspension/duplicate-out-of-order/refetch |
| phase-a-http-results.json | **174 HTTP assertions,0 fail**, unchanged A test source, đủ8 Auth/Profile RPC và real Auth/reset/onboarding/block |
| phase-b-http-results.json | **473 HTTP assertions,0 fail**, unchanged B test source chạy in-memory chỉ relocate output/root; social/storage/races đầy đủ |
| phase-a-sql-results.json | **87 SQL assertions PG17,0 fail**, rollback; adapter chỉ cập nhật future-grant expectation vì posts/comments/messages đã mở read ở B/C |
| catalogue-results.json | **33 catalog/SQL assertions,0 fail**, architecture/ACL/defaults/future closure, pair/seq/marker/send-event/admin invariants, private-only tenant/storage operation gate |

HTTP và WS assertions tách evidence/count, không cộng SQL/GUC vào real JWT/WebSocket results. No fake JWT success: users signup/verify Mailpit/password login thật; operator chỉ fixtures/barrier/state/lease control, không substitute user business calls. WS dùng Node ws có sẵn trong Storage container đã guard labels→Kong nội bộ project,3 sockets độc lập; host HTTP loopback54321. Keys/tokens qua memory/stdin, không arguments/artifacts. Driver copy vào /tmp và xóa sau test; không cài node dependency hay image khác.

8 races: same UUID, two senders, recall/read, out-of-order read, send/block, send/leave, send/join, two admin leave. Observer thấy2 authenticator connections đợi Lock rồi release. Send vs join/leave/block chấp nhận serialization hợp lệ200 hoặc404, không giả một thứ tự deterministic. Independence probe giữ B global+parentAB, cùng actor gửi AD xong trong khi AB vẫn blocked, rồi AB thành công sau release. WS test cố tình wire newer→older→duplicate, reducer dedup2 IDs và HTTP reconcile ordered truth; fault injection phân biệt với outage thật, rồi real publish/lease recovery thành công. Invalid refresh đóng Phoenix channel dù TCP socket có thể còn mở; driver theo dõi phx_error/phx_close, không chỉ TCP close.

## Bàn giao và gates

Artifacts/runners: [validation_phase_07](validation_phase_07/README.md), [báo cáo07](../baocao_nhiemvu07.txt). Runtime cuối9 containers RUNNING/healthy cho container có healthcheck, PostgREST HTTP thực, TTL3600 config bytes nguyên, Realtime private_only=true và no app publications. Portable PG không khởi động, listener55439 absent. Giữ mọi existing/synthetic data trong volumes; media retired chờ TTL/retry theo lifecycle. Worker/driver không daemon nền; không xóa Auth accounts hoặc reset volumes.

Đã đối chiếu ChatRepository/ChatActivity/InboxFragment/Chat/ChatMessage/ChatMessageAdapter/InboxAdapter/ChatImageViewer/SupabaseStorage. Android cần Supabase Auth sessions/relogin/UUID mapping; repository DTO UUID/seq/version/client request UUID persisted for retry; parse server UTC timestamps; group membership/role/history UX; reply dynamic redaction/tombstone; device delivery/read ACK từ actual render/receive; private channel/JWT refresh/reconnect/refetch reducer; authenticated image/video loader, cache per user/logout/revoke và media verification polling. Existing Firebase listeners/public Storage URL validation không tự dùng với backend mới. Chưa sửa một dòng Android/Gradle/local.properties.

Readiness: có backend Messenger local thực, đủ review và chuẩn bị Android/staging bằng nhiệm vụ riêng. **Chưa production-ready**: throughput/lock/load, real-device reconnect/cache/background lifecycle, malicious media sandbox/rate/quota/retention, operative-admin recovery, worker durable scheduler/alerts/dead-letter operations, backup/restore, SMTP/Auth mobile deep links, old-publicURL retirement, remote owners/ABI/private-only settings/CDN cần nghiệm thu. Phase D/E community features chưa triển khai; core group chat được đưa từ nhánh E dự kiến sang C theo nhiệm vụ07. Không background push service.
