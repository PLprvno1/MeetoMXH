# Meeto — Storage, Realtime và push
DRAFT, ngày01/11/2026. Không tạo bucket/policy/publication/channel/function trên project thật.

## 1. Buckets và đường dẫn
Mặc định **tất cả4 bucket private**. Public audience không có nghĩa public object URL. Đường dẫn do server cấp: `<auth_uuid>/<asset_uuid>/original.<extension>`, immutable, không chứa tên/email/SĐT/Firebase UID cũ. Variant thumbnail dùng cùng asset root + tên biến thể cố định do server tạo; metadata registry phải ghi variant whitelist, không tự cho client upload path bất kỳ.

| Bucket | Purpose | Quyền đọc | Upload/MIME/size đề xuất | Update/Delete |
|---|---|---|---|---|
| profile-media | avatar, cover | profile ACL gồm block/privacy; owner đang active | JPEG/PNG/WebP, <=10MiB/file | Không overwrite; replace bằng asset mới, server retire old |
| social-media | post, story | Post ACL hoặc Story ACL/expiry | image JPEG/PNG/WebP<=10MiB; MP4 video<=100MiB; tối đa10 media/post,1/story | Server lifecycle; story expiry deny trước cleanup |
| chat-media | message image/video/file | active member + history boundary + not hidden/blocked + message sent | ảnh<=10MiB; video<=100MiB; file PDF/text<=25MiB khi FUTURE duyệt; không APK/HTML/SVG/script/executable | Recall deny download ngay ở metadata; bytes async purge |
| group-media | group cover | public-group authenticated reader hoặc private active member | JPEG/PNG/WebP<=10MiB | Group manager RPC, không cho member tự sửa cover |

Giới hạn là đề xuất sản phẩm, không khẳng định đã cấu hình Supabase plan/bucket. max100MiB CHECK registry là trần chung, bucket/Edge giới hạn nhỏ hơn theo MIME/purpose. File extension/resolver MIME không đáng tin: kiểm byte magic/MIME thực, dimensions/duration, quota; file scan trước ready, response Content-Disposition an toàn. Video/file chat chưa bật ở Android hiện hành.

## 2. Quy trình media không giả transaction object storage
1. `prepare_upload(purpose,parent_context)`: caller active, quota/context permission; server tạo media_assets pending, UUID/path/bucket/MIME allowlist, intent15 phút. Không gắn asset vào post/message trước khi finalize.
2. Upload object bằng user **Supabase Auth access JWT**, không dùng anon key như identity. INSERT policy chỉ pending registry owned, bucket/path match và owner_id JWT.
3. Edge finalize kiểm tra object tồn tại/kích thước/MIME và readiness/scan; set ready. ready_at/size không nhận từ Android làm authority.
4. create_post/send_message/update_profile transaction checks asset owner,purpose,ready,context,not attached elsewhere, positions và content invariant; links chỉ tạo trong transaction đó.
5. Nếu upload thành công nhưng create parent fail: object chưa gắn được đọc chỉ trong window cần thiết; orphan job sau24h retire và delete Storage bằng API. Nếu ready nhưng chưa gắn thì can_read_media false, không dùng owner bypass.
6. Replace ảnh: tạo asset mới, atomically đổi profile/group FK, asset cũ pending_delete sau kiểm tra không còn link; cache eviction theo assetID/version.
7. Delete/recall parent: transaction disable asset trước, outbox cleanup; worker gọi Storage API delete idempotently,404 coi success, metadata state=deleted; retry backoff. Không DELETE storage.objects bằng SQL vì không bảo đảm xóa bytes vật lý.
8. Reconcile pending/ready registry và objects bằng worker trusted, không quét/đổi remote trong nhiệm vụ này. Chống asset gắn2 parent bằng purpose + UNIQUE attachment + RPC exclusive attach lock; FK một mình không enforce xuyên cả3 bảng.

## 3. Storage RLS và cách tải ảnh
- Policy INSERT authenticated chỉ registry pending đúng caller và path; anon không có write. Không client UPDATE/upsert/copy/move/delete hoặc bucket listing; thay thế tạo path mới.
- SELECT chỉ authenticated GET/info operations + parent ACL; owner cũng mất read ảnh recalled/deleted. Tránh SELECT policy chỉ owner vì nó làm người gửi tiếp tục đọc ảnh đã recall.
- Không cấp blanket public read; registry path UUID khó đoán không thay authorization. API key client chỉ định project; Authorization dùng user access token.
- SQL draft dùng operation-aware `storage.allow_any_operation` để **không cho SELECT của thao tác sign/list**. Tài liệu Storage phân biệt quyền theo operation; phải kiểm tra compatibility project trước khi áp dụng. [Storage access control](https://supabase.com/docs/guides/storage/security/access-control)
- Nếu Storage version không có helper phân biệt GET/sign: **không nới SELECT**. Dùng Edge authenticated download proxy; verify JWT + current DB ACL mỗi request/range, stream bytes với service key ở server, không trả upstream URL. Kiểm thử bandwidth/range video và giới hạn thời gian Edge trước chọn proxy cho video lớn.
- Android Glide/OkHttp adapter cần Authorization header hoặc stream proxy, cache keyed user+asset+version; logout/block/revoke clear relevant cache. Không cache private media lên public disk backup; TTL UI/cache nhỏ. Chưa sửa adapter.
- JWT revoke/suspend/account_status và block/leave kiểm tra tại mỗi yêu cầu mới. Request stream đang chạy hoặc bytes đã tải không thể thu hồi. Không tuyên bố DRM/E2EE.

### Signed URL là tradeoff, không lời giải revoke tức thì
MVP confidential media dùng authenticated GET/proxy, **không cấp URL signed cho chat/story/private posts**. Nếu sau này dùng signed URL cho profile/public-to-authenticated content, chỉ server sign sau authz, TTL ngắn<=60s và Cache-Control hạn chế; client không tự sign dài hạn. JWT revocation không tự vô hiệu bearer URL đã phát. [Supabase serving downloads](https://supabase.com/docs/guides/storage/serving/downloads)

CDN/browser/device cache có thể tiếp tục giữ bytes; cần kiểm thử object deletion/cache invalidation và không hứa hết TTL là chắc chắn xóa cache. [Supabase Smart CDN](https://supabase.com/docs/guides/storage/cdn/smart-cdn)

### URL cũ của bucket public
Đổi URL trong database hoặc block user **không bảo vệ các bản copy public cũ**. Migration phải copy sang bucket private, kiểm ACL, sau đó xóa/retire object/bucket public cũ theo kế hoạch được duyệt và xét CDN propagation. Nếu chủ dự án còn giữ public object, case “B giữ URL cũ” chưa được giải quyết. File đã tải/screenshot không thu hồi được. Đây là acceptance gate, không được ghi “đã an toàn” chỉ vì registry mới private.

## 4. Đánh giá supabase/storage.sql cũ
| Thành phần cũ | Quyết định thiết kế | Lý do |
|---|---|---|
| Tạo meeto-media public=true | Không giữ cho media mới; dự kiến4 bucket private | Một bucket public trộn avatar/private post/chat không phản ánh ACL |
| INSERT to anon,authenticated chỉ bucket_id | Loại khỏi kiến trúc đích khi cutover được duyệt | Firebase identity không ràng buộc object; nguy cơ upload tùy ý/chi phí |
| Public URL lưu Firestore | Đổi sang media_assets UUID+bucket/path | URL không phải quyền; hỗ trợ đổi audience/recall |
| Không DELETE policy/client overwrite | Tiếp tục không cho client arbitrary delete/upsert, nhưng bổ sung server lifecycle | Tránh phá object người khác, vẫn cần cleanup bytes thực |
| Không user JWT validation | Thay Supabase Auth JWT/pending intent | Identity thống nhất Auth/RLS/Storage |

**Giữ nguyên file cũ trong workspace nhiệm vụ này**, không chỉnh/xóa policy đã deploy (chưa biết deploy hay chưa). RLS nháp mới không tự thay được mọi policy cũ vì PostgreSQL permissive policies OR với nhau; triển khai sau phải inventory và retire policy permissive cũ đúng bucket, không chỉ thêm policy chặt rồi nghĩ đã chặn.

## 5. Realtime được chọn theo workload
| Luồng | MVP/thiết kế | Payload và quyền |
|---|---|---|
| Chat/new/recall/hide | Server Broadcast private user:<uid>, outbox invalidation | event_id+scope+version, không body/imageURL/request_hash; Android fetch latest seq via RLS |
| Inbox/read receipts | Cùng per-user Broadcast, coalesce | Refetch conversations/member projection; no raw members publication toàn schema |
| Notifications | Private user invalidation | Recipient query notifications cursor; không push dữ liệu private qua channel |
| Comments | Private Broadcast post-comments:<post UUID>, authorization can_read_post lúc join | Chỉ event_id + scope_version generic invalidation; refetch comments qua RLS, không raw row/request_hash |
| Posts/feed | Không subscribe toàn Posts MVP | Refetch cursor window khi compose/interaction/foreground; future invalidation hoặc filtered subscription nếu tải cho phép |
| Group membership revoke | Transaction outbox gửi invalidation cho user bị ảnh hưởng | User refetch, RLS deny; không broadcast roster/role toàn bộ |
| Typing/online | Presence FUTURE/OPTIONAL ở client | Không persistent status authoritative, phải last_seen preference/block/member auth và TTL |

Postgres Changes chỉ cân nhắc cho bảng projection không có field nhạy cảm sau này; không bật cho posts/comments/messages vì chúng chứa request_hash và cột grants không được mặc định lọc payload replication. Hard DELETE event không có cùng bảo đảm row authorization. [Supabase Realtime troubleshooting](https://supabase.com/kb/troubleshooting/realtime-messages-not-arriving)

**Private-channel gate**: publisher và subscriber phải private:true; deployment phải tắt Allow public access trong Realtime Settings; không giả policy private bảo vệ public channel. SQL nháp chưa đổi Dashboard settings.

**Comment topic sau revoke**: authorization của Broadcast có cache theo connection/JWT. User từng có quyền có thể nhận generic activity timing ở post-comments topic cho tới reauth, nhưng không được body/author/URL/hash; query mới RLS deny. Android nhận invalidation ->refetch/clear window, foreground/reconnect/heartbeat<=60s refetch; expire token short, disconnect on known revoke. Nếu sản phẩm đòi che cả activity timing sau block thì **không dùng shared resource topic**: phải thêm server-managed per-user watcher routing hoặc polling, cần thiết kế/benchmark riêng, không tuyên bố draft hiện giải quyết bảo mật metadata tuyệt đối.

Cơ chế cache quyền là giới hạn platform được ghi rõ; không truyền confidential payload qua shared channel. Chat/Inbox/notifications dùng per-user topic, publisher recompute recipients hiện tại. [Supabase Realtime Authorization](https://supabase.com/docs/guides/realtime/authorization)

## 6. Android reconnect, ordering và tải
- WebSocket subscribe chỉ khi lifecycle feature cần; unsubscribe khi stop/logout, refresh JWT/Reauthenticate channel, không keep socket vô hạn để thay push.
- Initial snapshot + buffer event rồi reconcile high-water; tránh snapshot/subscription gap. Broadcast là hint, có thể mất/dup/out-of-order; idempotency event_id/version, fetch DB là truth.
- Chat keyset seq; lưu last synchronized high-water và oldest loaded cursor riêng. Reconnect fetch seq>last_seen (max100/request), page until caught up, rồi reconcile loaded window cho recall/hide edits có seq cũ; chỉ backfill message mới sẽ bỏ sót recall cũ.
- Read receipts lấy max seq/monotonic không dùng timestamp thiết bị; DB order khác thời điểm network event. Pending UUID local không render “server sent” trước ACK/authoritative fetch.
- Notifications cursor(created_at,id); persist last fetch watermark, dedup primary ID; reconnect refresh unread state. Stop/end feature không mất dữ liệu vì notification row bền.
- Comments merge ID/version và refetch thread window; event ngoài page không append phá sort. Pagination/hard-delete cleanup chỉ worker; soft tombstone khi còn clients.
- Không đặt FULL replica identity/mở mọi table publication máy móc. Cần staging load tests số subscriber x event rate x RLS. Broadcast/outbox giúp tách payload khỏi bảng messages chứa hash; không expose hashing field qua replication chỉ vì SELECT column grant đã chặn REST.
- User bị khóa có thể còn kết nối cũ nhưng publisher bỏ qua user inactive và data refetch deny. In-flight generic invalidation có thể còn đến; không chứa private text/URL.

## 7. Push không Firebase/FCM
PostgreSQL notifications = bản ghi bền. Realtime = WebSocket khi app kết nối. Background push = delivery platform riêng, **Supabase Realtime không tự đánh thức Android**.

### Lựa chọn đề xuất
1. **UnifiedPush + distributor không dùng FCM** (ưu tiên prototype Android khi người dùng chấp nhận cài/chọn distributor): Android đăng ký endpoint, lưu device_subscriptions; Edge worker POST payload generic IDs/opaque event tới endpoint. Dùng distributor/nhà cung cấp đã xác minh chế độ không FCM, có thể self-host ntfy stack phù hợp. Phụ thuộc app/distributor và server, không hứa mọi bản build distributor đều không FCM. [UnifiedPush distributors](https://unifiedpush.org/users/distributors/), [ntfy distributor](https://unifiedpush.org/users/distributors/ntfy/)
2. **OEM push** FUTURE như adapter provider OEM được chọn theo device: SDK/vendor credentials phía server, fragmentation, vendor delivery terms phải nghiên cứu ở nhiệm vụ riêng. Chưa chọn/cài vendor SDK nào.
3. **MVP không background push**: in-app realtime + refresh khi mở app; không phụ thuộc distributor, nhưng người dùng không được báo tức thời khi app bị đóng.
4. Self persistent socket/foreground service chỉ là tradeoff pin/thông báo thường trực, không default. WorkManager/polling là eventual sync, không lời hứa realtime push. Android Doze hạn chế network nền, không thể suy socket app giữ sống. [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

Cần chủ dự án quyết định distributor/OEM/MVP không push; không âm thầm dùng FCM để đạt độ tin cậy. Feature này FUTURE và là rủi ro trải nghiệm sản phẩm, không phải chỉ tạo một cột token.

### Push security/consistency
- device endpoint là capability secret; column grant không cho Android SELECT endpoint, không in log/report. Register Edge validate caller + installation; không cho gửi tới arbitrary recipient.
- Chặn SSRF: HTTPS allowlist provider/host hoặc approved self-host instances, resolve public IP lại khi dispatch, deny private/linklocal/metadata addresses, không follow redirects. Không để service-role worker POST bất kỳ URL người dùng đưa.
- Worker claim outbox lease FOR UPDATE SKIP LOCKED, retry backoff/jitter/max attempts/dead; recompute block/membership/account state/preferences trước dispatch. External ACK không chứng minh user đã xem.
- notification unique recipient/event_key, push at-least-once; device dedup notification/event ID; generic “Có hoạt động mới”, tải nội dung qua Auth/RLS khi mở.
- Logout revoke installation before token purge nếu mạng; server endpoint lease/expiry/rebind xử lý offline logout và account switch. Không cross-user reuse endpoint khi old owner vẫn active, server transfer có proof installation.
- Content đã push tới lock screen không lấy lại được, vì vậy mặc định không gửi message text/ảnh/dob trong push payload.


## 8. Kết quả audit04 và các gate thực tế
Local policy tests sử dụng SQL stubs mô phỏng owner_id/operation/topic; chưa chạy Storage HTTP server hoặc Realtime service, không được gọi upload/download/push thành công từ kết quả này.
- Đã xác minh local recalled media không đọc được qua liên kết avatar sai purpose sau bổ sung predicate purpose+owner. Storage.object INSERT pending/GET/sign/list trong tests chỉ là RLS predicate, không là real object bytes/CDN test.
- prepare_upload phải giới hạn số pending intents và reserve worst-case bytes theo bucket, không chỉ tin size/MIME client; bucket size limit + quota/rate limit/scan/finalize vẫn cần cấu hình/test thật.
- Chỉ giữ metadata whitelist dimensions/duration/checksum do processor, không expose EXIF location/device/private filename trong media_assets.metadata.
- Verify managed function storage.allow_any_operation và operation names trên instance/version thực; test GET authenticated/info, sign, batch sign, list, upload/upsert, copy/move, transforms, delete, resumable và Range routes. Nếu một route không mang operation/context như kỳ vọng thì deny/proxy, không thêm SELECT true.
- Đối chiếu tài liệu chính thức: [Storage helper functions](https://supabase.com/docs/guides/storage/schema/helper-functions), [ownership](https://supabase.com/docs/guides/storage/security/ownership). Đây là hợp đồng platform cần integration test, không bằng chứng project mới đã có helper tương ứng.
- Object owner phụ thuộc token thật do server xác minh, không phải field Android tự khai; dùng service upload không có owner mặc định có thể làm policy fail, finalize phải xử lý ownership explicit server-side.
