# Meeto — thiết kế chuyển Firebase sang Supabase
DRAFT — không export/import dữ liệu thật, không Auth Admin call, không SQL execution, không sửa Android/Firebase Console.

## 1. Điều biết và chưa biết
Có source contracts của Users, Posts/Reactions/Comments, Users/Friends, Chats/Messages; mock Stories và fallback feed. Không có production export, inventory Auth users, deployed rules/index, bucket object listing hoặc dữ liệu user thực trong workspace được khảo sát. Bản local config hiện có thuộc tính Supabase nhưng không dùng để kết nối/xác minh project mới.

Do đó không khẳng định số bản ghi, ID collision, orphan count, passwords có thể dùng lại, media còn truy cập hoặc account nào là admin. Không import pravatar/picsum và p1-p3/s0-s4 như production content chỉ vì chúng xuất hiện trong source.

## 2. Identity, password và phiên
- Firebase UID là string, Supabase Auth UID là UUID. Tạo identity qua cơ chế Admin Auth được duyệt, lấy UUID thật rồi map **một identity đã chứng minh**; không cast/substring/hash UID thành auth.users UUID.
- migration_id_map(source_system,entity_type,legacy_id)->new_id, batch_id, source_checksum, migrated_at. user legacy_id=Firebase UID; document nested legacy_id dùng full Firestore path; media dùng nguồn bucket/object path hoặc canonical approved legacy URL, không querystring token.
- Hai Firebase accounts cùng email/SĐT không được auto-merge. Chuẩn hóa contact để phát hiện collision, quarantine và xác minh ownership/provider. Không gộp chỉ vì fullName/avatar giống.
- Không giả định chuyển trực tiếp plaintext password/hash/session. Đề xuất mặc định re-enrollment email password reset/invite hoặc OTP xác minh lại sau cutover; người dùng đăng nhập lại. Không copy Firebase refresh/access tokens.
- Nếu ưu tiên giữ password: cần nhiệm vụ migration Auth riêng, export hợp lệ với hash parameters và thử nghiệm hỗ trợ migration/middleware trong isolated staging. Không tự INSERT hash Firebase vào encrypted_password Supabase. Tài liệu Supabase có quy trình Firebase Auth migration và phương án nâng cao, nhưng khả năng account cụ thể chưa xác minh. [Supabase Firebase Auth migration](https://supabase.com/kb/guides/migrate-from-firebase-auth)
- Email/phone verification lấy export Auth trusted và quy trình import được hỗ trợ, không lấy Users.contactVerified=true do Android tự ghi. Identity provider metadata chỉ import field được duyệt; không import user_metadata role claims.
- Firebase Auth tồn tại nhưng thiếu Users -> tạo onboarding-incomplete profile tối thiểu sau xác minh identity, không bịa DOB/name. Users tồn tại mà không có Auth identity -> quarantine/claim process; không tự cấp login.
- Phone-only account không có email/password credential giả. Migration không giải quyết lỗi LoginActivity hiện tại; adapter Auth mới phải cung cấp OTP login phù hợp sau này.
- Supabase Auth multi-device sessions là nguồn truth; Android secure storage/refresh rotation/revoke flow cần triển khai, không tạo session mirror tables.

## 3. Field map chính
| Nguồn source-contract | Đích | Chuyển đổi/guard |
|---|---|---|
| Users.uid + document ID | profiles.id | Map Firebase UID -> Supabase Auth UUID, mismatch quarantine |
| fullName, bio | profiles.display_name,bio | trim/limit; không default name thành email vì email private |
| dateOfBirth dd/MM/yyyy, gender | profile_private.birth_date,gender | strict date/calendar/locale independent, tuổi/phạm vi check; invalid ->quarantine/null, không guessed date |
| email, phoneNumber | Supabase Auth contact | Không đưa public profiles; provider/verification trusted reconciliation |
| avatarUrl,coverUrl | media_assets + profiles.avatar_asset_id/cover_asset_id | inventory/copy private object, verify bytes/hash/MIME, owned purpose |
| profileCompleted/contactVerified | onboarding_completed / Auth verified | boolean client chỉ hint, không authority; đối chiếu required fields/identity |
| Users.createdAt/updatedAt long millis | created_at/updated_at timestamptz | to_timestamp(millis/1000.0), UTC; bounds; zero/null không giả timestamp chính xác |
| Posts.postId/doc ID | posts.id | UUID map; retain old path in private migration_id_map |
| authorUid | posts.author_id | user mapping bắt buộc, orphan review trước import |
| userName,userAvatar | JOIN profiles/media | Không source identity mới; không cần tiếp tục lưu denormalized PII |
| content | posts.body | preserve Unicode, length validation; long content quarantine hoặc approved transform |
| type,originalPostId | kind,original_post_id | normal->original,share->share; resolve root/no cycle/no source access escalation |
| postImage,imageUrls,mediaType | post_media.position + media_assets | dedup same URL, detect video/image; source chỉ1 media không tạo giả multi-images |
| visibility | audience | public/friends/private; missing/unknown ->private quarantine review, không model-default public |
| likesCount/commentsCount/sharesCount | normalized-derived counts | Recompute từ accepted rows; không copy stale counter làm truth |
| Reactions/{uid}.type/createdAt | post_reactions.reaction/created_at | LIKE->like; one per user/post, server timestamp decode |
| Comments.authorUid/content/createdAt | comments.author_id/body/created_at | map IDs, no parent ->parent_id NULL; no fake replies |
| Friends.{otherUid}.status | friend_requests / friendships | pair reconciliation bên dưới, không migrate2 rows trực tiếp |
| Chats.participants/type | conversations + conversation_members | only valid2 distinct mapped users với source private; malformed/group không được suy bừa |
| Chats doc ID SHA256 | conversations.id | UUID map; unique pair giữ1 conversation, duplicate source pair có merge plan |
| Messages.senderId/receiverId | messages.sender_id + conversation membership | Sender/receiver phải thuộc source pair; receiver không đẩy vào chat-group schema |
| Messages.type,text,imageUrl | messages.kind/body + message_media | text/image->text/media; recalled=true ->body NULL/media denied, không tái tạo bytes đã thu hồi |
| Messages.createdAt Firestore Timestamp | messages.created_at,seq | preserve seconds+nanos theo precision PG; sort tie bằng source path; seq assign dưới offline import transaction |
| replyToMessageId/replyToSenderId/replyPreview | reply_to_message_id | cùng conversation; không import stale quote plaintext; missing target ->NULL/placeholders có log |
| deletedFor[] | message_hidden rows | map từng UID phải member; unknown IDs quarantine |
| lastReadAtByUid / lastReadMessageIdByUid | conversation_members.last_read_seq | Ưu tiên message ID map visible; timestamp fallback conservative; không auto mark tất cả read |
| lastIncoming* maps | derived message queries | Không copy map tạo trạng thái trái membership; recompute unread |
| lastMessage* summary | last_message_id,next_seq + inbox query | Recompute last valid/visible record, không tiếp tục copy preview đã thu hồi/hidden |
| mock Story cards / notification/admin chưa có | không import | FUTURE không phải data đang tồn tại |
| FCM tokens/sessions | không import | Device đăng ký endpoint push mới nếu chọn provider không FCM |

request_hash của imported post/comment/message được tính bởi importer từ canonical original payload đã chấp nhận, không lấy hash client. client_request_id cho dữ liệu cũ dùng UUID mapping ổn định (random lưu map trước retry hoặc UUIDv5 namespace kiểm soát với full legacy path); phải không đổi giữa import runs. Không dùng hash deterministic identity thay Auth UID.

## 4. Friendship/request reconciliation
Cho mỗi cặp UID sau map, giữ một canonical pair và phân tích hai documents:
- ACCEPTED cả hai ->one friendship; accepted một phía/một phía pending/mất đối xứng ->quarantine discrepancy, **không mở Friends Only chỉ vì một phía nói accepted**.
- PENDING_SENT(A->B) và PENDING_RECEIVED(B<-A) khớp ->one pending request A->B.
- Pending chỉ1 phía, cả hai PENDING_SENT, invalid friendId/self relation hoặc blocked ->quarantine/cancel theo policy được duyệt, không silently autoaccept.
- createdAt chọn thời gian hợp lệ sớm nhất; updatedAt/resolvedAt chuẩn hóa; ghi checksum nguồn/mapping/batch. Không dựng lịch sử decline/cancel không tồn tại.
- Friendship SQL pair unique và pending unique là integrity backstop, không là quy tắc tự sửa dữ liệu hỏng.

## 5. Chat merge/order/read integrity
1. Validate source Chats có private type + đúng2 participants tồn tại Auth mapping; document thiếu participant/sender không được gán bừa.
2. Group source chats cùng canonical pair: propose merge one destination; mapping cho mọi old chat ID cùng new UUID. Bảo toàn tất cả message paths, không dedup chỉ vì text/time bằng nhau (hai tin giống nhau có thể có thật).
3. Cấp message UUID map theo full path; sort createdAt hợp lệ, tie source path; seq contiguous1..N để pagination. created_at giữ timestamp gốc, seq mới là canonical conversation order khi tie.
4. Verify sender in members, receiver là peer; import recalled=generic tombstone/bodyNULL, hidden UID rows; reply FK resolve cùng conversation sau mapping. Cross-chat reply/quarantine, không cho link tạo lỗ hổng.
5. Imported membership history_from_seq=1 cho source2 người để không mất lịch sử hợp lệ; new member group sau cutover dùng next_seq+1. Không giả có group source.
6. Read marker: mapped lastReadMessageId có tồn tại và sender phù hợp thì derive seq; fallback timestamp chỉ mark <=confirmed boundary; không dựng delivery ACK từ DB timestamps. last_delivered_seq>=last_read_seq để constraint nhưng gắn ý nghĩa migrated inference, không tuyên bố device-delivered được chứng minh.
7. Compute next_seq/last_message_id và per-viewer inbox từ final rows, không trust source summary. Không import source lastMessage text để tránh resurrect recalled content.
8. Validate counts/sample hashes và query read boundary bằng impersonated memberships, không superuser SELECT pass.

## 6. Media migration
- Inventory metadata source URLs trước, không network fetch arbitrary URLs từ client data. Trusted host allowlist, signed source URL confidential xử lý server, deny SSRF/private IP/redirect escape. Không ghi full signed source URLs/token vào logs/mapping.
- Copy sang new private buckets bằng worker được duyệt, bytes hash/MIME/size và count verify. Không public URL mới; map legacy object->assetUUID+owner+purpose; duplicate shared URL giữa owners/contexts phải tạo asset context riêng hoặc review ownership, không gộp sai ACL.
- Không import mock image URLs như production. URL không còn tồn tại ->unavailable metadata/task review, không download ảnh thay thế giả.
- Asset đã recall/xóa không cần copy bytes; insert tombstone hoặc bỏ attachment theo source proof; không giữ media chỉ để mọi người còn link cũ.
- Old public objects phải retire theo approved cleanup+CDN window; không thể bảo đảm block chặn URL cũ cho tới bước đó. Không xóa old project trong rollback window; điều này tạo cửa sổ security cần cách ly old bucket access/objects có kế hoạch, không bỏ qua.
- Nếu giữ old public bucket cho rollback, không được chứng nhận confidentiality mới cho media cũ. Chọn staging/private old backup thay public exposure.

## 7. Quy trình theo giai đoạn, có rollback
| Stage | Hành động sau phê duyệt | Gate/rollback |
|---|---|---|
| M0 Inventory | Read-only exports Auth/Firestore/objects được cấp quyền, sizes/rules/providers; encrypted snapshots + checksum + access log | Snapshot là backup, không phải thiết kế source có dữ liệu; fail ->không import |
| M1 Staging/Auth | Chọn re-enrollment hoặc approved Auth import; tạo identity map theo batch | Contact collision/verification tests, không reset production |
| M2 Staging entities | Profiles/private/settings/status/media metadata ->social pairs ->posts/comments/reactions ->conversations/members/messages ->refs/hidden/counters | Deferred FK giải quyết cycles; orphan/invalid report, no silent drop |
| M3 Objects and validation | Copy private media, verify ACL/RLS/Storage/functions/load queries, compare counts/hashes | RLS allow/deny, blocked URL, expiry/recall tests; reset staging batch nếu fail |
| M4 Candidate client | Supabase adapters Java+OkHttp, secure sessions; Firebase path vẫn trong branch until cutover approval | Cross-feature test, no runtime mixing credentials; chưa loại Firebase trong nhiệm vụ03 |
| M5 Cutover rehearsal | Maintenance window, stop old writes enforced server/min-client-version, final export/delta import, consistency checks | Freeze stage, read-only comparison, explicit go/no-go |
| M6 Production cutover | Chỉ được làm sau phê duyệt; Supabase authoritative, new sessions, gated features | Prewrite rollback quay Firebase snapshot/client; sau new writes áp quy trình delta bên dưới |
| M7 Observation/retirement | Monitor errors/RLS/counters/media/push; clean legacy URLs, retain secure backups, remove Firebase code/config/dependency cuối cùng | Khi rollback window đóng, decommission Firebase services/FCM fully có kế hoạch riêng |

Không dual-write Android cho hai database để giả atomic migration. Không để old client tiếp tục viết Firestore trong lúc Supabase authoritative; chỉ chặn UI version không đủ, cần old backend write freeze/rules/governance khi được duyệt.

### Rollback thực tế sau khi Supabase đã nhận writes
Không có rollback “đổi endpoint là xong”. Cần:
1. Freeze new Supabase writes, snapshot Auth/DB/objects; preserve outbox/mappings/tombstones, new rows và version watermarks. Chưa bật FUTURE feature làm schema không reverse được trong rollback window.
2. Chạy **reverse transform đã diễn tập** từ new/changed rows (không chỉ generic outbox invalidations vì chúng không chứa full state). Preserve new IDs bằng mapping ngược, reconcile counters/private URLs/created times; no destructive purge trước khi window đóng.
3. New Supabase Auth identities không có Firebase password/session tương đương: phải có re-enrollment/reset strategy riêng hoặc giữ Supabase identity bridge trong giai đoạn rollback được chấp thuận. Không hứa sessions/password chuyển ngược tự động.
4. Nếu không reverse an toàn được, chọn forward-fix/restore Supabase snapshot và replay bounded deltas, thông báo maintenance. Document data-loss window/RPO<=maintenance freeze delta và được owner phê duyệt.
5. Validate source/destination counts/checksums/privacy rồi mới reopen authoritative writes. Mappings và snapshots không xóa cho tới kết thúc signed-off window.

Khả năng rollback là **kế hoạch cần diễn tập**, chưa có scripts/tests hoặc backup thật được tạo. Không đưa dữ liệu production vào workspace để “minh họa”.

## 8. Acceptance/reconciliation checklist
- Identity: one mapped Auth UUID/source UID, collisions/quarantine accounted; không account mất verification policy hoặc tự có admin.
- Referential integrity:0 orphan FK (trừ explicit nullable purged refs), same-post comments/same-chat reply, exactly2 private members, canonical pairs.
- Counts: số user/post/comment/message/reaction chuyển + số quarantine + số intentionally excluded phải bằng source inventory, không chỉ so tổng.
- Timestamps: UTC, integer millis vs seconds, Firestore nanos rounding documented, không sort theo giờ thiết bị mới.
- Privacy: missing visibility default private review, friend mismatch không tăng quyền, group/private not inferred; hidden/recall remains hidden.
- Media: object ready có bytes hợp lệ, URL cũ security gate, no orphan ready exposure, quota not exceeded.
- Auth: email/phone reset/relogin/deep links multi-device/logout, no Firebase session accepted as Supabase JWT.
- Runtime: đủ18 scenario design review + Android test accounts staging + concurrent RPC and reconnect.
- Teardown: only after approval, remove Firebase code/dependencies/project links; không giữ FCM như hidden dependency trong push.

Các bước này đều chưa thực thi trong nhiệm vụ thiết kế.


## 9. Gate bổ sung từ nhiệm vụ04
- Importer phải tuân UNIQUE stories.media_asset_id; không dedup một object giữa nhiều story contexts nếu cleanup độc lập. Purpose/owner sai sẽ bị RLS deny dù FK đơn hợp lệ; báo quarantine thay vì mở policy cho data sai.
- Migrate user_settings chỉ whitelist loại notification/boolean,<=4096 byte; không copy arbitrary JSON role/provider endpoint vào preferences.
- Migration execution cần version ledger/checksum/transaction và chốt object owner/default grants. Full draft không idempotent rerun: local thử lại trả42P07 rồi rollback, không “IF NOT EXISTS” che drift.
- Root baocao.txt giữ lịch sử nhiệm vụ01/03, kết quả04 ghi file báo cáo mới. Local synthetic Auth users không là migration account thật, không dùng key project thật.
