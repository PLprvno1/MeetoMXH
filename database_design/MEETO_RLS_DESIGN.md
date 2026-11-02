# Meeto — RLS và authorization design
**DRAFT, chưa kiểm thử allow/deny trên PostgreSQL/Supabase.** SQL read-policy/helper/grants trong MEETO_RLS_DRAFT.sql; chưa có body RPC write. Không production-ready migration.

## 1. Actor và cổng truy cập
| Actor | Quyền mặc định |
|---|---|
| anon / guest | Không SELECT/INSERT/UPDATE/DELETE social tables, không Storage/Realtime. Chỉ dùng Auth endpoints |
| authenticated anonymous Auth account | Cũng deny social; JWT is_anonymous không trở thành user verified |
| authenticated verified active | SELECT theo owner/friend/group/member; CRUD preference giới hạn |
| owner | Không sửa role/status/counters/created_at/owner_id; quyền sửa nội dung cũng phải theo parent access và block/group state |
| friend | Đọc Friends Only nếu còn friendship, không bị block và author active |
| Group Member | Private group content chỉ khi active membership; removed/banned/left mất quyền |
| Group Admin/Moderator | Quyền nhóm đó qua RPC; không được cấp system role hoặc xem private chat |
| Conversation Member | Chỉ active member, seq>=history_from_seq, không hidden/blocked; direct chat block deny cả conversation |
| Moderator | Case metadata assigned + report_context tối thiểu, không đọc case người khác; không quyền SELECT mọi post/message |
| System Admin | Triage case metadata/role operations qua privileged workflow; không blanket SELECT profile_private/chats |
| Suspended/deleting | Social deny từ account_status; JWT còn hạn không bypass; chỉ self status và support Edge được kiểm soát |
| Worker/operator | Credential server, scope thủ tục nội bộ tối thiểu, không phân phối Android; service_role bypass nên mọi Edge input phải validate |

Auth verification lấy auth.users confirmed timestamps; user_metadata/Android fields không là nguồn authority. anon key/publishable key không phải user JWT. JWT caller luôn auth.uid(); request JSON actor_id chỉ bị bỏ qua/reject.
Social is_active còn yêu cầu profiles.onboarding_completed=true. complete_onboarding RPC được xác minh riêng bằng account_ok + Auth confirmed, validate tên/DOB/tuổi rồi set cờ server-side; avatar/cover optional upload sau khi hoàn tất thông tin bắt buộc. Không grant UPDATE cờ này cho Android và không tạo vòng lặp prerequisite onboarding/upload.

**Quyết định public**: logged-in users, không anonymous Internet. Block được kiểm tra hai hướng; group chung là ngoại lệ metadata/membership đã nêu, không exception cho direct messages.

## 2. Ma trận theo từng bảng
S=SELECT theo predicate; X=deny direct Android; RPC=server transaction hợp đồng, **chưa có grant/body write trong draft**. Các ordinary writes đã có policy draft chỉ user_settings UPDATE và saved/hidden INSERT/DELETE.

| Bảng | SELECT | INSERT | UPDATE | DELETE | RPC/guard cụ thể |
|---|---|---|---|---|---|
| profiles | S: profile audience/self | X → RPC | X → RPC | X → RPC | bootstrap_profile / update_profile / request_account_deletion |
| profile_private | S: self only | X → RPC | X → RPC | X → RPC | update_profile_private (validate tuổi/calendars phía server) |
| user_settings | S: owner/parent ACL, xem SQL | X → RPC | Owner, column whitelist | X → RPC | bootstrap; GET/PATCH preference columns owner; timestamp trigger |
| account_status | S: self kể cả locked | X → RPC | X → RPC | X → RPC | bootstrap / suspend_account / unsuspend_account / request_account_deletion |
| user_blocks | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | set_block(target,true|false) trong pair lock |
| user_follows | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | set_follow(target,true|false) trong pair lock |
| friend_requests | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | send_friend_request / accept_friend_request / resolve_friend_request |
| friendships | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | accept_friend_request / unfriend / set_block |
| groups | S: group scope | X → RPC | X → RPC | X → RPC | create_group / update_group / archive_group / delete_group |
| group_members | S: group scope | X → RPC | X → RPC | X → RPC | join_group / approve_group_request / manage_group_member / leave_group / transfer_group_admin |
| group_join_requests | S: group scope | X → RPC | X → RPC | X → RPC | request_group_join / resolve_group_join_request |
| posts | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | create_post / edit_post / share_post / delete_post |
| post_reactions | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | set_post_reaction(post,type|null) |
| comments | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | create_comment / edit_comment / delete_comment |
| comment_reactions | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | set_comment_reaction(comment,type|null) |
| saved_posts | S: owner/parent ACL, xem SQL | Owner + readable post | X → RPC | Owner | owner INSERT/DELETE; duplicate PK -> idempotent conflict handling |
| hidden_posts | S: owner/parent ACL, xem SQL | Owner + readable post | X → RPC | Owner | owner INSERT/DELETE; feed anti-join |
| stories | S: story scope/expiry | X → RPC | X → RPC | X → RPC | create_story / delete_story / archive_story (archive chưa bật) |
| story_views | S: story scope/expiry | X → RPC | X → RPC | X → RPC | record_story_view (UPSERT no-op) |
| story_reactions | S: story scope/expiry | X → RPC | X → RPC | X → RPC | set_story_reaction(story,type|null) |
| conversations | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | get_or_create_private / create_group_conversation / archive_conversation |
| conversation_members | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | manage_conversation_member / leave_conversation / mark_read / acknowledge_delivery |
| messages | S: member+boundary+block+hidden, không request_hash | X → RPC | X → RPC | X → RPC | send_message / recall_message / remove_message_by_case; edit FUTURE |
| message_hidden | S: owner/parent ACL, xem SQL | X → RPC | X → RPC | X → RPC | hide_message_for_me (không unhide MVP) |
| media_assets | S: parent ACL | X → RPC | X → RPC | X → RPC | prepare_upload / finalize_upload / attach via parent RPC / cleanup worker |
| post_media | S: parent ACL | X → RPC | X → RPC | X → RPC | create_post/edit_post gắn ready owned assets trong cùng transaction |
| message_media | S: parent ACL | X → RPC | X → RPC | X → RPC | send_message gắn assets; recall disables registry, cleanup worker delete link sau retention |
| notifications | S: recipient only | X → RPC | X → RPC | X → RPC | outbox worker create; mark_notifications_read owner; retention worker delete |
| device_subscriptions | S: owner projection, không endpoint | X → RPC | X → RPC | X → RPC | Edge register_device / revoke_device; endpoint không trả raw |
| event_outbox | X; server only | X; worker/operator | X; worker/operator | X; worker/operator | domain transaction INSERT; worker claim/lease/process/retry/purge |
| system_roles | S: self roles | X → RPC | X → RPC | X → RPC | server-only grant_system_role/revoke_system_role có approval/audit |
| reports | S: assigned Moderator / System Admin | X → RPC | X → RPC | X → RPC | submit_report / assign_report / resolve_report; reporter receipt RPC redacted |
| moderation_actions | S: case/appeal scope | X → RPC | X → RPC | X → RPC | apply_moderation cùng report/target/audit/outbox transaction |
| moderation_audit | S: case/appeal scope | X → RPC | X → RPC | X → RPC | append server event; purge retention authorized, không client CRUD |
| appeals | S: case/appeal scope | X → RPC | X → RPC | X → RPC | submit_appeal / assign_appeal / resolve_appeal |
| migration_id_map | X; server only | X; worker/operator | X; worker/operator | X; worker/operator | migration operator INSERT/UPSERT trong staging, verify, purge sau rollback window |

User_settings được UPDATE các cột profile_audience,last_seen_audience,notifications_enabled,notification_preferences; không user_id/updated_at. Trigger ghi server updated_at. profile_private **không direct UPDATE** vì phải enforce calendar/tuổi/field policy ở RPC. Birthdate CHECK1900 không thay age validation server.

Saved/hidden INSERT chỉ grant columns(user_id,post_id); created_at luôn default server, không cho client override timestamp. Không cho override row ownership vì WITH CHECK auth.uid.

Profiles/media/reactions/comments/memberships/messages/friendships/counters/status/roles không có DML grant/policy mở. New-table RLS ON và REVOKE trong schema draft. Server roles không được vào client; SQL draft không tạo custom worker database login/secret.

## 3. Parent ACL bắt buộc
- Post reader = active author-self/public/friends hoặc group reader, không block author; share cũng check source original trừ owner shell. Content source vẫn RLS riêng; không snapshot source vào share.
- Interaction = post readable và group active member nếu group; author-owned UI không vượt group ban.
- Comment child/reaction phải kế thừa post; hidden comment không SELECT, deleted body=NULL tombstone; blocked commenter bị lọc.
- Story = state active + chưa expiry + audience; archive owner-only có kiểm soát server; viewer list không public.
- Conversation = active membership/private-unblocked. Messages thêm history boundary + not hidden + sender not blocked; recalled/removed chỉ tombstone body=NULL. FK ID đoán được không thêm quyền.
- Media ready chỉ readable nếu còn gắn parent được đọc; **không owner bypass** sau recall/delete. Pending owner read trong15 phút để preview/retry. Android media link metadata SELECT không đủ download nếu object policy khác.
- Notifications chỉ recipient và ID metadata; click/refetch kiểm tra quyền mới.
- Report RLS không lấy reporter-visible whole case; reporter dùng receipt RPC projection state cơ bản. report_context không ban cho Android arbitrary object_id; đọc đúng target của case assigned và ghi audit.
- Bất kỳ revoke/block/leave được áp dụng ở DB request mới. Request đang chạy có quyền theo snapshot/linearization; cache/bytes đã tải không thể lấy lại.

## 4. SECURITY DEFINER và grants
Helpers boolean đặt private schema (không exposed qua Data API), SECURITY DEFINER SET search_path='', schema-qualified mọi relation. Owner có BYPASSRLS để tránh recursion policies memberships/roles. Không FORCE RLS lên tables rồi vẫn giả định helper bypass; cần role/owner review ở staging.
- REVOKE EXECUTE FROM PUBLIC,anon; chỉ grant21 helper predicates cho authenticated cần RLS. Không grant SELECT bảng private hoặc dynamic arbitrary query.
- Mutation implementation tương lai đặt private, actor-bound, explicit role/target/old-new checks; public exposed wrappers SECURITY INVOKER, hoặc Edge validated actor. Không cho wrapper chạy hàm definer nhận actor_id giả.
- Quyền quan trọng đọc system_roles trong DB mỗi request, không tin JWT user_metadata.role. System role provisioning manual approval vận hành và audit; không dựa UI ẩn nút.
- Mỗi chức năng mới phải revoke default public EXECUTE và review owner/grant signature. Không dùng dynamic SQL với tên bảng/column client; params type rõ.
- Các views tương lai phải security_invoker=true hoặc không expose; không nối private PII thành view public.
Các quy tắc này phù hợp hướng dẫn function security và RLS chính thức. [Supabase database functions](https://github.com/supabase/supabase/blob/master/apps/docs/content/guides/database/functions.mdx), [Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security)

## 5. Transaction pseudocode cho thao tác nhạy cảm
Đây là thuật toán thiết kế, chưa function body triển khai.

### send_friend_request(A,B,request_uuid)
1. Require A=auth.uid, active/confirmed; validate B exists/active/A!=B.
2. pg_advisory_xact_lock(canonical pair); recheck block và states sau lock.
3. Same (A,request_uuid) trả row cũ nếu cùng B; khác B ->409.
4. friendship tồn tại ->already_friends; pending pair tồn tại ->same_direction_pending hoặc incoming_request_exists, không auto accept.
5. INSERT pending; outbox event unique(action,recipient); commit. Unique partial là backstop.

### accept_friend_request(id)
1. Load request ids không public leak; pair-lock; SELECT request FOR UPDATE; require recipient=auth.uid và active+unblocked.
2. pending -> insert canonical friendship ON CONFLICT DO NOTHING, request accepted/resolved_at, outbox.
3. accepted+friendship tồn tại ->idempotent success. accepted sau unfriend hoặc declined/cancelled ->409; không tạo lại.
4. Không có request ->404/deny. Không thể accept lời mời không tồn tại như merge Firestore.

### send_message(conversation,client_uuid,payload)
1. Require caller active; load conversation canonical private peer để pair-lock nếu private.
2. Lock conversation FOR UPDATE, recheck active membership/boundary, peer block/recipient status; group chat member changes cũng lock conversation.
3. Tra unique(sender,conversation,client_uuid). So hash canonical payload: same ->return old message identity (nếu recalled thì tombstone metadata, không trả text), different ->409.
4. Validate content/media owned/ready/purpose, reply cùng conversation và visible, Story reply quyền; seq=next_seq+1.
5. INSERT message+assets; update next_seq,last_message_id,version,updated_at; INSERT outbox IDs; commit. No network inside tx.
6. ACK mất: retry cùngUUID, không allocate seq mới. Edit/recall không thay original request_hash nên retry không resurrect body.

### set_post_reaction(post,desired_type_or_null)
Pair lock(author,caller) ->group lock nếu group ->post row FOR UPDATE; recheck can_interact_post. Insert mới delta+1; type change delta0; delete có row delta-1; delete absent delta0. Atomic update counter and outbox cùng tx. Không dùng get từ Android rồi increment tách API. Block/unfriend serialize cùng pair; moderation/delete cùng parent lock.

### recall / delete content / last admin
- Recall: lock conversation/message, require sender hoặc case action được duyệt; mark recalled/body=NULL, media state pending_delete, delete_after=now; keep tombstone; update inbox version, outbox; physical deletion async.
- Delete post: lock group nếu có rồi post, version/state transition một lần; state deleted, body scrub theo policy; disable assets; counters/children query không read parent; cleanup chunked; source share không copy bytes.
- Last admin: lock group trước tính active admin; không permit remove/demote/leave cuối trừ transfer/archive cùng tx; Auth deletion dùng cùng invariant.

## 6. Quyền media/Realtime không là policy DB table thông thường
Storage SELECT chỉ allow authenticated object GET operations, không cho sign/list tùy ý. Không có signed URLs client tự tạo TTL dài; nếu phiên bản Storage không hỗ trợ operation helpers, deny direct object SELECT, dùng Edge proxy reauthorize. Không “fallback” bằng SELECT true.
Realtime private Broadcast receive theo user:<auth.uid> và post-comments:<post UUID> nếu đọc được post khi join; server chỉ generic invalidation no body/URL/request_hash/author, không client send. Shared comment topic có side-channel thời điểm sau revoke tới JWT refresh; không dùng nó truyền dữ liệu. Membership revoke không dựa cache channel, publisher tính recipients hiện tại + client refetch RLS. Xem STORAGE_REALTIME.

## 7. Acceptance tests bắt buộc trước triển khai
Thực thi sau bằng auth.uid JWT user thật hoặc impersonation test harness, không superuser query để tuyên bố RLS pass:
- Roles anon, anonymous Auth, unverified, active A/B/C, suspended, deleting.
- Own/other SELECT và row move UPDATE; thử sửa user_id/author_id/created_at/counters/state/role/assigned_to qua REST.
- Friend vs nonfriend vs pending vs blocked; group public/private/member/removed/banned/admin và role escalation.
- Conversation member/nonmember/new join boundary/removed; hidden/raw SELECT/media; guess ID; search FTS phải cùng RLS.
- Auth JWT còn hạn sau khóa và sau role revoke; channel vẫn mở nhưng không private payload.
- Column SELECT request_hash (posts/comments/messages)/endpoint và SELECT * phải denied nếu gồm cột cấm; explicit safe projection pass.
- Private helper không exposed endpoint; public function grant enumeration; malicious input/UUID/unsupported operation.
- Storage direct sign/list/upsert/delete denied; authenticated GET allowed đúng parent và denied sau revoke; no owner bypass recall.
- Moderator case chưa assigned deny, assigned allow context tối thiểu; admin không đọc mọi private chat; reporter receipt không internal notes.
- 18 tình huống bảng DESIGN_REVIEW dùng hai connections/barrier cho concurrency, không chỉ sequential unit tests.

Chưa test RLS runtime, chưa kiểm tra compatibility helpers với project mới. Pending RPC bodies không có nghĩa quyền đã được thực thi cho mutation; draft fail-closed là chủ ý.


## 8. Kiểm chứng nhiệm vụ04
Đã chạy policy SQL với role anon/authenticated NOBYPASSRLS trên PostgreSQL16.11 local;99 assertions gồm constraint/read/deny/column-grants và16 regression assertions, thêm3 race patterns. Không dùng superuser SELECT để chứng nhận RLS; owner chỉ seed fixtures và kiểm integrity. Supabase Auth/JWT HTTP, Storage routes và Realtime websocket chưa được test.
- Global default EXECUTE của migration owner được revoke (không IN SCHEMA); owner khác không tự được kế thừa thay đổi này. Các21 helpers vẫn explicit grant; anon deny.
- media-assets predicate yêu cầu purpose/owner đúng profile/post/story/message. Group cover chỉ purpose=group vì uploader có thể là admin khác creator. Sai FK do operator/import không được cấp read private object.
- user_settings JSON boolean/key/size constraints bảo vệ direct UPDATE được phép; không cập nhật quyền fieldrole/counter.
- Hợp đồng mutation47 nhóm: MEETO_RPC_IMPLEMENTATION_CATALOG.md; table SELECT pass không chứng minh business RPC hoàn thành.
- Supabase compatibility còn gate private schema exposed-settings, ownership/BYPASSRLS thực, old permissive policies, inherited grants, managed Storage operation ABI và public-channel setting.
