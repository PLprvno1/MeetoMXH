# Meeto — kế hoạch triển khai A–E sau audit04
DRAFT; nhiệm vụ04 không deploy Supabase thật, không sửa Android. Xem MEETO_PRE_IMPLEMENTATION_AUDIT.md và catalog47 nhóm operation.

## Dependency của baseline SQL
Hai draft hiện là **full target schema36 bảng + full helper graph**, không phải5 migration độc lập. Các helper SQL được PostgreSQL resolve khi CREATE FUNCTION, nên không thể cài full helpers trong A chỉ với bảng profiles rồi mong các bảng stories/messages sẽ có sau.

Phương án khuyến nghị cho project mới: **A0 tạo full36 bảng inert/deny-by-default trong staging và baseline mới sau phê duyệt**, rồi A1/B/C/D/E mới triển khai APIs, jobs, grants và Android theo feature. Tạo bảng FUTURE không đồng nghĩa triển khai UI/logic FUTURE. Không import data hoặc mở ghi FUTURE trước phase tương ứng. Khi cần physical schema tối thiểu từng phase, phải tạo migration subset dependency-closed cùng helper variants/FK ALTER riêng; không copy một phần draft hiện tại và không bỏ FK để cho chạy.

36 bảng chia:
- Foundation: profiles, profile_private, user_settings, account_status, user_blocks, media_assets, event_outbox.
- Social: friend_requests, friendships, posts, post_reactions, comments, post_media.
- Chat: conversations, conversation_members, messages, message_hidden, message_media.
- Advanced: user_follows, saved_posts, hidden_posts, stories, story_views, story_reactions, notifications, device_subscriptions.
- Groups/moderation: groups, group_members, group_join_requests, system_roles, reports, moderation_actions, moderation_audit, appeals.
- Operations: migration_id_map (migration-only; không API Android).
- OPTIONAL: comment_reactions.

## Giai đoạn A — foundation/Auth/profile/RLS
**Bảng cần tạo:** A0 baseline36 bảng mới (hoặc migration subset đã review riêng),79 FK,104 CHECK,110 indexes gồm50 explicit. A1 chỉ bật foundation data/APIs; migration_id_map chỉ operator. Giữ future rows trống; revoke direct writes saved_posts/hidden_posts cho đến D nếu dùng full RLS target snapshot.

**RPC/API:** Supabase Auth chuẩn; bootstrap_profile,complete_onboarding,update_profile,update_profile_private,get_profile/search_profiles,set_block; account-delete request/worker khi retention đã duyệt. Không tự lưu password/session trong bảng ứng dụng.
**RLS/Storage:** current migration owner global default EXECUTE deny;21 helpers explicit grants,anon/anonymous/unverified/incomplete/locked deny. Private bucket profile-media và registry/read/intent basics; notification preference checks ở DB. Asset URLs authenticated GET/proxy, chưa media social.
**Tests:** fresh DDL apply; constraints/catalog; owner/other/locked/onboarding/column tests; Auth email/reset/deeplink/phone nếu chọn; API JWT hết hạn/forged; actual Storage owner/operation ABI và HTTP GET/sign/list; không suy mocks là service pass.
**Android:** chỉ chuẩn bị/thay Auth/Profile repository adapter trong branch/staging sau phê duyệt triển khai. Giữ Java/XML và Firebase path cho rollback; không sửa ở nhiệm vụ04.
**Nghiệm thu:** Auth/profile thật ở staging, private field không leak, no self-role escalation, upload-cancel/cache/logout đúng; baseline migration owner/checksum/grants inventory; all prerequisite gates đóng.
**Rollback:** A staging reset chỉ dataset tổng hợp; prod baseline mới trước public writes có backup/maintenance; source Firebase chưa authoritative switch. Không DROP schema người dùng thật.

## Giai đoạn B — posts/comments/reactions/friends/Storage
**Bảng bật dùng:** friend_requests,friendships,posts,post_reactions,comments,post_media; media_assets/outbox foundation. groups/Stories anchors chưa có mutation APIs, post group mode và Story media denied vì feature chưa bật.
**RPC:** pair-locked request/accept/resolve/unfriend/block; create/edit/share/delete post; set_reaction desired state; comment create/edit/delete/list; feed/detail; prepare/finalize/cleanup media.
**RLS/Storage:** Friends Only enforce query+DB, block both directions; same-post replies; ready asset owned+purpose; social-media private MIME/size/quota; no share snapshot hoặc direct counter DML.
**Tests:** 3+ accounts public/friends/private; lostACK/retryhash, reciprocal requests, reaction/comment/delete races, parent depth/cycle attempts; assetpurpose/owner confusion; source share deleted/permission changed; pagination>30 posts; actual media routes.
**Android:** thay Feed/Friend/CreatePost/Comments data adapter theo feature flag trên staging. Loại mock fallback trong nhiệm vụ implementation riêng; không dual-write Android để giả atomic migration.
**Nghiệm thu:** counter reconcile=actual rows, repeat action no double delta; zero unauthorized access; no orphan unbounded uploads; byte quotas/maxrecords.
**Rollback:** chưa enable future data; giữ field mapping/source backups và old-write freeze policy; trước cutover dùngfeatureflag, sau writes mới phải freeze/reconcile reverse delta hoặc forward recovery, không đổi endpoint mù.

## Giai đoạn C — chat/inbox/media/read/Realtime
**Bảng bật dùng:** conversations,conversation_members,messages,message_hidden,message_media,chat-media. Private chat trước; group chat feature chỉ E.
**RPC:** private get/create,send_message,inbox/messages/search,mark_read/deliveryACK,recall/hide; media flows; publisher/outbox lease.
**RLS/Storage/Realtime:** actual member/history boundary/block; hash/endpoint columns deny; media revoked as parent state; user private topics only generic invalidation; Realtime Dashboard public access setting và token refresh tested.
**Tests:** two real devices/accounts staging; serverACKlost sameUUID, send/recall/hide/search/new-old page, read max monotonic; stop/start/rotate/logout; reconnect old-seq recall reconciliation; actual StorageRange/cache/owner; forbidden channels.
**Android:** ChatRepository/Inbox adapter + Authenticated Glide/OkHttp; pending requestUUID queue/secure sessions; không giữ Firestore listener song song trên authoritative Supabase data.
**Nghiệm thu:** old messages pagination>50, no wrong-recipient/unread/previewleak, same privatepair conversation, no raw message payload/hash in Realtime; delivery status chỉ khi deviceACK thật.
**Rollback:** preserve mappings/seq/requests/tombstones. Không purge media/metadata cần rollback trước window đóng. Chat new-user Auth identity không có Firebase password/session mặc định.

## Giai đoạn D — Stories/notifications/advanced social
**Bảng bật dùng:** stories,story_views,story_reactions,notifications,device_subscriptions,user_follows,saved_posts,hidden_posts. comment_reactions chỉ OPTIONAL khi duyệt.
**RPC:** story CRUD/view/reaction/reply; follow/preference; notification list/read; register/revoke devices; workers dedup/push; cleanup TTL.
**RLS/Storage/Realtime:** Story expiry server24h, viewer-list owner; asset UNIQUE per Story; no client long signedURLs; notification recipient-only; endpointsecret/SSRF validation. Comment topic generic timing side-channel được chủ dự án chấp nhận hoặc thay per-user watcher routing.
**Tests:** Story vừa expiry giữa player, signed/public legacy links không được giả revoke; duplicateview/reaction; notificationduplicatedelivery; real distributor/OEMbackground Doze nếu chọn push; subscriber staleJWT và reconnect.
**Android:** Story UI/notification UI/deeplink/searchadv trong nhiệm vụ feature; không coi tạo bảng là đã có giao diện.
**Nghiệm thu:** durable notification + privacy, targeted unavailable links handled; background delivery chỉ công bố trên devices/provider đã test; archive/retention approved.
**Rollback:** feature disable trước physical teardown, preserve notification/read state; no drop anchors referenced by messages.reply_story_id; provider subscriptions revoke gracefully.

## Giai đoạn E — groups/moderation/admin/extensions
**Bảng bật dùng:** groups,group_members,group_join_requests,system_roles,reports,moderation_actions,moderation_audit,appeals (appeals sau coremoderation). posts.group context và group conversation mode đã có schema nhưng chỉ bật APIs tại đây.
**RPC:** scoped group membership/content/transfer; groupchat memberadmin/history; report/assignment/context/action; suspension/role provisioning/audit; appeals theo policy.
**RLS/Storage:** GroupAdmin khác SystemAdmin/Moderator; actual private member access, group-media private; no last admin abandon; suspend/deleting operative admin recovery phải chốt trước bật feature; no broad private-data admin endpoint.
**Tests:** last2admins leave đồng thời, transfer to inactive/blocked user, kicked/banned user media/query/searchdeny, newgroupchat history/rejoin, reportwrongassignment/privateretarget, caseconcurrentresolution, accountdelete/suspend finaladmin.
**Android:** thêm Groups/Moderator UI sau API permission tests; UI không tự cấp role hoặc gọi service key.
**Nghiệm thu:** reportaction audited transaction, no escalation crossgroup/system, active group's admin recovery defined, retention/appeals approved và actual rolecontexts pass.
**Rollback:** disable mutations first, preserve cases/audit/role journal; no irreversible massdelete; hardpurge only outside accepted recovery window.

## Khi nào chuyển và xóa Firebase/ImgBB
- **Bắt đầu đổi source:** A Auth/Profile adapters sau giao implementation; B/C chuyển từng repository trong test build. Task04 không đổi source.
- **Cutover authoritative:** sau A+B+C real staging pass và migration rehearsal/export/freeze/delta/RPO được duyệt; chặn old-client Firestore writes ở backend khi triển khai, không chỉ ẩn UI.
- **Xóa Firebase Auth/Firestore code/dependencies/config:** chỉ sau observation/rollbackwindow, Auth re-login/mapping chuẩn và không còn path cũ. Nếu mục tiêu không Firebase thì không giữ FCM bí mật.
- **ImgBB:** source khảo sát không có uploader hiện hành; chỉ remove legacyURL/config sau inventory/retention/backup đã duyệt. Không xóa service/object thật trong audit.
- **Migration rerun:** dùng versionledger+checksum, từng migration trong transaction, no-op theo ledger. Full baseline CREATE không reentrant; local thử lại42P07 rollback đúng, không thêm IF NOT EXISTS để che drift. Failed security phase không được để data/public grants mở.

## Trạng thái sau nhiệm vụ 05
A0 scaffold36 + helpers và A Auth/Profile RPC đã CODED, PostgreSQL local tested. Chưa Supabase local stack/staging verified do Docker/Podman thiếu; chưa có JWT service integration pass. Không chuyển sang B và không sửa Android.
Migration A chỉ bật6 bảng read-scoped và owner settings update; future SELECT column grants cũng bị bỏ, saved/hidden writes không cài. Private Storage/media bytes, account deletion workflow và actual Auth integration vẫn là gates ghi tại MEETO_PHASE_A_IMPLEMENTATION.md. Phase A chưa đạt toàn bộ DoD staging nêu ở trên.

## Cập nhật nhiệm vụ 06 — trạng thái local hiện hành

Phần lịch sử05 bên trên không còn mô tả runtime hiện tại. 05B đã kiểm Auth/Profile thật;06 tiếp tục **B CODED + LOCAL TESTED**:23 RPC social/media,2 private profile/social buckets,worker verify/decode/cleanup thực,473 HTTP assertions gồm7 races JWT. Regression A174 HTTP/87 SQL/7 expiry đạt lại. Tổng9 migrations,36 public tables giữ kiến trúc,31 public RPC. Không STAGING VERIFIED hoặc PRODUCTION DEPLOYED; Android/Firebase chưa thay.

Feed/comments dùng polling; global mutation/block transaction lock là giới hạn throughput cần benchmark. Worker local một lượt chưa là Cloud scheduler; media cache/CDN/sandbox/antivirus/EXIF sanitization và operational/retention/backup gates còn mở. Chưa Chat/Stories/Groups/Notifications/Moderation business logic. Có nền local để bắt đầu nhiệm vụ C riêng, chưa đạt DoD staging/cutover toàn sản phẩm. Xem [implementation B](MEETO_PHASE_B_IMPLEMENTATION.md) và [evidence06](validation_phase_06/README.md).

## Cập nhật nhiệm vụ 07 — Phase C Messenger

**C CODED + LOCAL TESTED**:private/group chat,18 RPC mới,seq/idempotency/history/inbox/search/reply/recall/hide/delivery/read,private per-user Broadcast+dispatcher,conversation-bound image/video media.Tổng15 migrations/49 public RPC/36 tables;511 HTTP C+40 WS assertions,regression A174/B473/SQL87 vàcatalog33 đạt.Evidence [validation_phase_07](validation_phase_07/README.md),[implementation](MEETO_PHASE_C_IMPLEMENTATION.md).

Theo nhiệm vụ07,core group chat create/member/roles/leave/title/history/state đi từ nhánhE dự kiến sangC;**community Groups/Moderation/Stories/Notifications D/E vẫn chưa triển khai**.C send/receipt/member operations dùng pair/conversation/shared actor locks,không globalB serialize;independent-parent probe đã kiểm.GlobalB vẫn cần benchmark;C workload chưa load-tested.Workers là local executables,chưa cloud durable service;SMTP/retention/account deletion/admin recovery/media sandbox/CDN/cache/backup gates còn mở.

Không Android/Cloud changes,không STAGING VERIFIED/PRODUCTION DEPLOYED.Đủ nền local để review Messenger integration,nhiệm vụstaging/Android riêng cầnowner/ABI/privateonly/cache/durableworker/load/recovery vàidentity migration rehearsal trước cutover.
