# Meeto — Supabase Phase B, nhiệm vụ 06

Ngày 01/11/2026, Asia/Bangkok. Workspace `F:\MeetoMXH`. **CODED + LOCAL TESTED** cho Posts, Friends, Comments, Reactions, private profile/social Storage. **STAGING VERIFIED: chưa. PRODUCTION DEPLOYED: chưa.** Android vẫn Firebase; chưa cutover.

## Phạm vi và migration

Runtime được kiểm lại trước thao tác: 9 container local chạy, container có healthcheck healthy. PostgreSQL 17.11, Auth/PostgREST/Storage thật, CLI 2.122.0. Không suy trạng thái từ báo cáo 05B. Giữ nguyên 36 bảng, 79 FK, 104 CHECK và 110 indexes; 3 migration A byte-for-byte không đổi.

| Migration mới | Nội dung |
|---|---|
| `20261101000400_social.sql` | Social RPC, projection, SELECT column grants/RLS, lock chung với block |
| `20261101000500_media.sql` | Hai private buckets, Storage operation policies, intent/finalize/worker ABI |
| `20261101000600_media_cleanup_safety.sql` | Cleanup khóa asset rồi kiểm lại liên kết; giới hạn purpose Phase B |
| `20261101000700_friend_request_receipts.sql` | Receipt của mọi UUID send thành công, cả duplicate-pair/crossed requests |
| `20261101000800_media_quota_reservations.sql` | Giữ reserved bytes cả pending_delete/quarantined tới khi purge thành công |
| `20261101000900_media_cleanup_fairness.sql` | Lọc linked rows trước batch LIMIT, tránh starvation orphan sau backlog |

Áp bằng CLI `db push --local`, ledger thật tổng 9, không sửa ledger. Manifest SHA256 có 9 entries; checker kiểm toàn bộ. 004/005 rehearsal trong transaction rollback gặp cú pháp IF/CASE ở size check, đã dừng, sửa trước apply, rehearsal thành công rồi mới apply. Các migration đã áp không chỉnh lại; sửa tiếp bằng 006–009. Không DROP CASCADE/reset hoặc managed stubs. Wrapper deployment mới chỉ local và kiểm ledger prefix A→B.

## RPC đã triển khai

Tổng **23 public RPC B mới**, cộng **8 RPC A giữ hoạt động = 31**. Public là invoker fixed arguments; implementation private definer `search_path=''`, explicit grants; actor lấy `auth.uid()`. Các helper nhận actor/trusted verification không được API roles execute.

| Nhóm | Public entry points và tham số chính |
|---|---|
| Friends (9) | `send_friend_request(p_recipient,p_client_request_id)`; `accept_friend_request(p_request_id)`; `decline_friend_request(p_request_id)`; `cancel_friend_request(p_request_id)`; `unfriend(p_other)`; `get_friends`, `get_received_requests`, `get_sent_requests`, `get_suggestions` |
| Posts (7) | `create_post(p_body,p_audience,p_assets,p_client_request_id)`; `share_post(p_original,p_body,p_audience,p_client_request_id)`; `edit_post(p_post_id,p_body,p_audience,p_assets,p_expected_version)`; `delete_post(p_post_id,p_expected_version)`; `get_post(p_post_id)`; `get_feed(p_mode='all',cursor,limit)`; `get_user_posts(p_author,cursor,limit)` |
| Reactions (1) | `set_post_reaction(p_post_id,p_reaction)`; null bỏ reaction |
| Comments (4) | `create_comment(p_post_id,p_parent_id,p_body,p_client_request_id)`; `edit_comment(p_comment_id,p_body,p_expected_version)`; `delete_comment(p_comment_id,p_expected_version)`; `list_comments(p_post_id,p_parent_id=null,cursor,limit)` |
| Media (2) | `prepare_upload(p_purpose,p_mime,p_client_request_id)`; `finalize_upload(p_asset_id)` |

Tên `edit_post/edit_comment/list_comments` theo catalog; không thêm alias update/get trùng. Các list dùng tham số chính xác `p_after_time timestamptz`, `p_after_id uuid`, `p_limit integer=20`, tối đa50, cursor phải có cả hai hoặc null cả hai. Thứ tự `(created_at DESC,id DESC)`, strict tuple `<`; mốc trả trong item, trang đủ limit thì gọi tiếp. Friends list dùng peer profile ID làm tie-break, request_id riêng. Chỉ list graph của caller. Suggestions lọc profile ACL, block, friendship, pending; chưa ranking nâng cao.

Post input full desired state, text≤20000 hoặc 1–10 media ready có thứ tự. Audience public/friends/private; public vẫn cần authenticated active/onboarded/verified. Group posting giữ schema nhưng RPC từ chối. Hash canonical JSON giữ immutable payload tạo; cùng UUID/hash trả cùng row hiện tại, khác hash409. Edit/delete owner, version conflict409, desired state giống hiện tại no-op; delete tombstone body rỗng, không resurrect. Attach kiểm owner/purpose/ready, unique asset, rollback cả post nếu attach lỗi. Media bỏ khỏi edit được retire.

Share chỉ original personal readable, không share-chain/snapshot. Source visibility luôn kiểm lại; reader khác mất quyền source thì share unavailable. Share owner còn shell body của chính mình, projection original_post_id=null/original_available=false nếu source không xem được; không copy original text/media. Raw own-share row có source UUID do người đó đã cung cấp lúc tạo, không cấp thêm quyền source. shares_count đếm share published, không tăng lại do retry.

Comments parent immutable, cùng post, tối đa8 cấp; không API reparent nên không tạo cycle. Root/replies đọc riêng qua list_comments(parent); root tombstone body=null, con giữ thread. Edit/delete chỉ owner khi parent post tương tác được; post đã xóa không tạo/sửa comment. comments_count đếm published comments, gồm replies, không gồm tombstones/hidden. reactions_count là số user rows; switch type delta0, unset absent delta0. Các totals là persisted aggregate, có thể gồm user đang bị viewer block; chúng không phải totals theo viewer và không cam kết che timing.

Friend request chỉ recipient accept/decline, requester cancel. Crossed send trả incoming, không tự accept. Một pending/cặp qua partial unique; accepted retry chỉ thành công nếu friendship còn, không phục hồi sau unfriend. Block dùng implementation A cũ qua wrapper có lock chung, tiếp tục hủy pending/xóa friendship/follows; unblock không phục hồi. Completed outbox receipt cho mọi successful send UUID lưu request ID, không body/profile data; retry sau cancel không tạo request mới. Receipt chưa có purge tự động; staging cần retention nhất quán request/receipt.

## Transaction và RLS

Mutation B + set_block dùng transaction advisory lock `6061101` rồi actor/Auth/profile/resource locks. Cách serialize này giữ các race B/block nhất quán, **giới hạn throughput toàn B**; chưa phải giải pháp scale. PostgreSQL constraints vẫn làm backstop. Profile A giữ lock order Auth→status→profile→assets. Operator suspension không là Auth ban: JWT cũ bị DB gate deny; Auth vẫn có thể cấp session theo behavior A.

RLS có sẵn được giữ, chỉ grant safe SELECT cho posts/comments/post_media/reactions/requests/friendships/media registry. Không direct DML social/counter/owner/status. Hash cột posts/comments và media verification metadata không được client đọc. Không profile_private join trong social projection; post author display/avatar chỉ khi can_read_profile. Friends danh sách có thể giữ ID quan hệ của caller nhưng tên/avatar=null khi profile ACL từ chối. Private schema không exposed. Account active/onboarding/confirmation được kiểm từ DB, không tin claim confirmed cũ.

## Storage và worker thực

| Bucket | Private, MIME và limits |
|---|---|
| `profile-media` | PNG/JPEG/WebP, bucket10MiB, purpose avatar/cover |
| `social-media` | PNG/JPEG/WebP/MP4, bucket50MiB; worker image10MiB, video50MiB; purpose post |

Không tạo chat/group buckets khi chưa cần dependency; các purpose future bị prepare_upload từ chối. Giới hạn video50MiB có chủ đích theo config local50MiB giữ nguyên, thấp hơn đề xuất thiết kế100MiB. Worker chấp nhận ảnh/video tối đa40 triệu pixels, video≤300s và codec h264/hevc/av1/mpeg4. Giới hạn count100 assets chưa deleted, pending≤10; budget500MiB/caller reserve worst-case bucket cả pending/pending_delete/quarantined, ready dùng size verified. Pending hết15 phút từ chối upload/finalize, cleanup orphan sau24h.

Luồng thực: `prepare_upload` cấp UUID/path server `<uid>/<asset>/original.ext` → user POST bytes bằng JWT, upsert=false → operator chạy worker → caller finalize → create/edit post hoặc A update_profile liên kết asset. Trước worker, finalize409. Worker FFmpeg/ffprobe kiểm magic, actual size, dimensions, codec/duration, full decode bằng file-only protocol, timeout30s/process, threads1 decode, không tin MIME/size do client khai. Registry chỉ metadata whitelist sha256/object ID/dimensions/duration, không copy EXIF tags. **Không sanitize/re-encode EXIF trong bytes**, không antivirus hoặc transcoding pipeline; private original chỉ cho user được ACL.

Worker `Invoke-MediaWorker.ps1` là executable local một lượt, không Edge hay daemon. Dùng local service role trong memory để tải/xóa object, operator psql guarded để ghi verified marker. Client không tự đặt verified/size/state. Finalize khóa asset, so owner/object ID/size/marker và idempotent ready; ready chưa attach không owner bypass đọc. On-demand worker cần chạy trong vòng intent15 phút. Production scheduler/runtime/sandbox không được chứng nhận.

`meeto_b_insert`: exact pending registry, JWT owner_id, path, active. `meeto_b_get`: chỉ operation `object.get_authenticated`/`object.get_authenticated_info` và parent ACL. Không INSERT anon, UPDATE/DELETE/copy/move/list/sign policies. Không mở storage.buckets writes. Migration005 từ chối inventory nếu đã có storage.objects policies, tránh permissive OR bypass. Helper operation ABI đối chiếu [Supabase docs](https://supabase.com/docs/guides/storage/schema/helper-functions); [access control](https://supabase.com/docs/guides/storage/security/access-control).

Authenticated GET/Range recheck RLS mỗi yêu cầu. Sign và batch sign không phát URL (batch có thể200 với từng signedURL=null/error), public URL private bucket bị deny. Browser/device bytes đã tải và stream đang chạy không thu hồi được; tests dùng cache-control0, **chưa chứng nhận CDN/cache eviction**. Client cutover phải dùng authenticated loader, clear cache theo user/logout/revoke; không đưa URL public cũ vào DTO mới.

Delete/edit media/replace profile: transaction deny registry trước, tạo dedup event `media.retire`; old profile giữ bytes24h theo A nhưng mất read ngay. Worker schedule orphan recheck sau asset lock, không dọn asset ready còn gắn profile/post. Claim tối đa batch100 với SKIP LOCKED, lease2 phút, reclaim lease hết hạn; max5 attempts, exponential backoff, dead, sanitized last_error; ACK/failure fenced theo attempt. Storage API DELETE ngoài transaction, missing idempotent, rồi state deleted/checkpoint done. Không SQL DELETE storage.objects. Failed decode retire, network lỗi download retry; không lặng lẽ mark invalid khi lỗi operational. Fault injection test retry/dead khác outage thật. Chưa scheduler/dead-letter UI/alert service; operator có thể đọc/requeue checkpoint sau xử lý root cause.

Completed request receipts không dispatch. Media retire có worker thật. Các event block cũ của A còn pending vì transport thuộc phase sau; B không tạo notification/push dispatcher. **Feed/comments dùng polling/refetch**, không publication hay Broadcast mới; không có lời hứa Realtime business delivery ở B.

## Bằng chứng thực chạy

| Bộ kiểm | Kết quả |
|---|---|
| B HTTP với 4 accounts/lần, JWT GoTrue | **473 assertions, 0 fail** |
| B races, nằm trong473 | **7**, observer thấy hai authenticator connections đồng thời đợi Lock |
| RLS/JWT matrix B | Cả23 RPC: missing/forged JWT, actor spoof, incomplete, suspended, deleting, unverified với token thật đã cấp |
| Storage trong473 | PNG/JPEG/WebP/H264 upload/decode/finalize/attach, profile replace, audience/block/revoke, public/sign/batch/list/copy/move/delete/upsert deny, MIME/size, Range206, orphan/physical purge, quota/retry/dead/lease |
| Phase A HTTP gốc | **174 assertions, 0 fail**, đủ8 RPC/Auth/reset/onboarding/block |
| Phase A SQL adapter PG17 | **87 assertions, 0 fail**, rollback fixtures; chỉ đổi kỳ vọng posts/comments nay là B read |
| Phase A expiry thực | **7 assertions, 0 fail**, GoTrue TTL60, đợi exp+35; restore bytes config TTL3600 |
| Catalog/ACL | **25 assertions, 0 fail**, schema kiến trúc giữ nguyên, public RPC31, future grants đóng, worker ABI không API grant |
| Cleanup backlog SQL rollback | **1 assertion**,101 linked assets cũ không làm starvation orphan tiếp theo; linked assets không bị retire; không là HTTP evidence |

Không cộng các môi trường/cumulative assertions thành production certificate. Evidence, runners, protected hashes và handoff tại [validation_phase_06](validation_phase_06/README.md). Tests dùng synthetic users/content/bytes, không managed stubs hoặc fake JWT mint. Operator chỉ setup state/recovery/barrier/checkpoint và đối soát; không thay user mutation HTTP.

## Android mapping, giới hạn và readiness

Đã khảo sát Post/Comment models, FriendRepository, CreatePostActivity, PostAdapter, CommentsBottomSheetFragment và SupabaseStorage. Không sửa Java/XML/Gradle/local.properties/Firebase/ImgBB. Post.visibility→audience; content→body; normal→original; imageUrls/postImage→ordered media asset IDs; likesCount→reactions_count, isLiked→my_reaction; timestamp server ISO UTC cần adapter đổi epoch/UI. OriginalPost fetch riêng qua get_post và xử lý unavailable. Comment DTO cần thêm parent/version/state và resolve author profile theo ACL; không lưu authorName/avatar snapshot tin từ client. Friends dùng canonical relation/request_id, SELF và pending direction do adapter tính từ caller. Storage cũ dùng anon bearer/public meeto-media không tương thích buckets mới, chưa được đổi hoặc xóa.

Trước chuyển Android: Supabase Auth session/refresh/deep link an toàn; repository DTO/UUID/version/idempotency queue; authenticated image/video loader Range/cache; media verification polling và UX409; feature flags/relogin/mapping Firebase UID; staging integration, cutover/freeze/rollback rehearsal và retire public URL thật theo inventory. Không dual-write để giả transaction.

Readiness Phase C: có nền local cho social/profile/media, đủ bắt đầu thiết kế và triển khai C riêng. Chat/Realtime/private channels/history/receipts, chat-media, notifications/groups/moderation **chưa triển khai**, không tự bật. Full Phase A account-delete/retention/operational gates cũ vẫn mở. Cần review global-lock throughput, worker sandbox/quota/rate limit, malicious-media fuzz/load/large-video, auth recovery, backup/restore/retention và staging owners/ABI/cache trước mở Cloud. Không có phê duyệt Cloud ở nhiệm vụ06.

Trạng thái bàn giao: Supabase local9 containers RUNNING, TTL3600; PG portable không khởi động, STOPPED. Giữ synthetic data trong volumes; có ready avatar/cover, pending intents và profile retired bytes chờ TTL24h theo lifecycle, không tự xóa accounts/volumes. FFmpeg tool ở Temp, không service nền. Dừng giữ dữ liệu bằng CLI stop khi chủ dự án cần.
