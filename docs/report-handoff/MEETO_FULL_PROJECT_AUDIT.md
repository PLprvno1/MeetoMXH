# MEETO — Kiểm kê kỹ thuật toàn dự án

Ngày khảo sát: 08/11/2026, Asia/Bangkok. Đối tượng: source trên đĩa tại `F:\MeetoMXH`, kể cả thay đổi local. Đây là thông tin kỹ thuật bàn giao, không phải chương báo cáo đồ án hoặc biên bản nghiệm thu.

## 1. Phạm vi và quy tắc bằng chứng

| Nhãn | Ý nghĩa |
|---|---|
| SOURCE PRESENT | Có file/khai báo/DDL, chưa chứng minh có đường mở hoặc chạy được. |
| SOURCE INTEGRATED | Có entry point và đường gọi trong source; không đồng nghĩa runtime PASS. |
| HISTORICAL BUILD EVIDENCE | Báo cáo lưu kết quả build/test của mốc trước, không bao phủ source mới. |
| OWNER RUNTIME EVIDENCE | Owner cung cấp kết quả/ảnh/triệu chứng, chỉ áp dụng tình huống đó. |
| CLOUD DEPLOYMENT CONFIRMED | Cần chứng cứ triển khai cụ thể, không suy từ tên migration. |
| NOT VERIFIED | Chưa đủ bằng chứng, kể cả chức năng đã có source đầy đủ. |

Không chạy build/compiler/test/ADB/emulator/ffprobe/export, SQL/Cloud/Docker/server/deployment/Git push. Không tạo hình, sơ đồ, Word/PDF hoặc nội dung chương thay tác giả. Chỉ tạo bốn Markdown bàn giao. Không trích key/JWT/mật khẩu hoặc dữ liệu tài khoản thật. Git executable không có trong PATH của phiên này, nên không xác lập commit/diff/clean tree. Source hiện tại trên đĩa được ưu tiên hơn báo cáo cũ. Không có schema export hoặc migration ledger Cloud mới.

Quy ước đường dẫn: Java = `app/src/main/java/com/example/meetomxh/`; XML = `app/src/main/res/`; SQL = `supabase/migrations/`. ERD inventory chứa bảng/cột/PK/FK và ledger ALTER; Diagram handoff chứa bước gọi/nhánh; UI inventory chứa màn hình/ảnh cần chụp.

## 2. Sản phẩm và thống kê xác định được

**Năm tab Social:** Trang chủ/FeedFragment; Reels/ReelsFragment; Tin nhắn/InboxFragment; Tìm kiếm/SearchFragment; Trang cá nhân/ProfileFragment. Căn cứ: `MainActivity.java`, `res/menu/bottom_nav_menu.xml`. Notifications là màn riêng mở từ header; branch Java `nav_notifications` không chứng minh có tab thứ sáu.

Login → RoleResolution → AdminRoleResolver → `admin_get_capabilities`: system_admin vào AdminPanel, người dùng thường vào Main. Moderator không mặc nhiên là system_admin; màn review mở từ Settings theo capability. Intent role-verified chỉ phục vụ navigation, server kiểm quyền từng RPC.

| Phạm vi | Trạng thái và căn cứ |
|---|---|
| Follow/Followers/Following | Hiện hành; private follow có duyệt. `20261106000100_follow_only_privacy.sql`, ProfileActivity/FollowListActivity/RequestCenterActivity. |
| Friendship | Legacy/inactive; `are_friends` trả false. Không lấy bảng lịch sử làm tính năng hoạt động. |
| Community Groups | REMOVED FROM PRODUCT theo Task36 và helpers disabled; groups/group_members vẫn còn dữ liệu/schema. |
| Messenger Group Chat | Hiện hành: conversations.kind=group, conversation_members/invitations, owner/admin/avatar. Khác hoàn toàn Community Groups. |
| Memories/Marketplace | Removed; Task35 từng có UI, Marketplace không có backend thương mại được xác nhận. Manifest/menu hiện tại không có entry. |
| Voice | SOURCE PRESENT nhưng DEFERRED, `ChatActivity` đặt btnVoiceRecord GONE/disabled; recorder/player/SQL/verifier còn. Không công bố gửi/nhận voice đã hoạt động. |
| Openverse API nhạc mới | Legacy v1; Picker hiện hành dùng catalog Meeto dù tên class Openverse còn. |
| Album hồ sơ cũ | ProfileAlbumsActivity còn đăng ký, không tìm thấy caller mở từ ngoài class. FeaturedAlbumsUi mới có entry thật từ ProfileActivity. |

Kiểm kê văn bản: **68 migration SQL; 58 tên bảng CREATE TABLE tường minh (52 public, 6 private)**, không tính auth/storage/realtime managed tables. Có **222 tên hàm public khác nhau từng được CREATE FUNCTION tường minh**, không phải số RPC cuối cùng/live: có DROP/REPLACE/overload/wrapper sinh động/retired. Có 53 tên CREATE POLICY tường minh khác nhau, không phải policy hiệu lực cuối. Không quảng bá các số khai báo lịch sử này như số API/policy hoạt động.

Source tham chiếu năm bucket ID: profile-media, social-media, chat-media, group-media, music-library. Không chứng minh năm bucket đang tồn tại/đều hiện hành; group-media gắn Community Groups legacy. Avatar chat nhóm dùng purpose avatar/profile-media. Bucket limits/private ở Cloud cần bằng chứng riêng.

## 3. Kiến trúc thực tế

| Thành phần | Trách nhiệm/dependency | Source |
|---|---|---|
| Presentation/navigation | Java Activity/Fragment, XML/ViewBinding và form dựng Java; Material/RecyclerView; Main thay Fragment, composer/viewer/Admin mở Activity riêng. Không gọi toàn dự án là MVVM thuần. | Manifest, MainActivity, res/layout |
| Model/DTO | User/Post/Comment/Chat/ChatMessage/Story/NotificationItem; JSON → model, asset ID → asset://. | models/, supabase/Dto.java |
| Repository/client | Feed/Reels/Chat/AdvancedSearch/NotificationRepository; ProfileClient/StoryClient/CommentClient/AdminRepository; nhiều Activity gọi RPC trực tiếp. | repositories/, supabase/ |
| Auth/session | SupabaseAuthClient gọi Auth REST; SessionManager token/refresh/identity generation; EncryptedVault AES-GCM Android Keystore, noBackupFilesDir. | SupabaseAuthClient, SessionManager, EncryptedVault |
| Networking | Api background Callable + main callback/cancel/epoch; RpcClient → SupabaseHttpClient → PostgREST, user JWT + public project key. Không service_role Android. | Api/RpcClient/SupabaseHttpClient |
| Media | Reserve intent → upload direct/TUS → finalize → ready → bind; PrivateAssetLoader cho Glide, authenticated video resolver. | storage/SupabaseStorage, PrivateAssetLoader, MediaThumbnailLoader |
| Player/export | CameraX; ExoPlayer; CompositionPlayer/Transformer; cache lease và output probe. | music/, ui/ReelClipExporter, StoryMediaExporter |
| Database | RPC wrappers/private helpers, SECURITY DEFINER với search_path rỗng, active/block/ownership/audience/membership; RLS và grants. Có public SECURITY DEFINER trực tiếp ở hotfix, không phải mọi wrapper đều INVOKER. | SQL authorization/follow/consent/Admin/hotfix |
| Storage | Private objects + media_assets registry; purpose/state/owner. Music catalog riêng, không user media_assets. | media/direct upload/music migrations |
| Realtime | Private user broadcast channel, event-ID dedup, invalidation → RPC refetch; Observer polling 30 giây. Không phải mọi UI nghe trực tiếp postgres_changes. | RealtimeClient, Observer, cloud_workers |
| Background | event_outbox, claim/lease/retry/attempt fence; cron + Edge cleanup/audio verifier; credentials chỉ môi trường server. | 010016/010017/07001050, functions/meeto-background-cleanup |

Cấu hình source: Java 11; minSdk24/compileSdk37/targetSdk37; app1.0; AGP9.3.3; Material1.14.0/AppCompat1.8.0; OkHttp4.12.0; Media3 1.11.1; CameraX1.6.2; Glide4.16.0; TUS0.5.1. Căn cứ `app/build.gradle.kts`, `gradle/libs.versions.toml`; không chứng minh resolve/build hiện tại. Gradle hiện tại không tích hợp Firebase làm backend; tài liệu Firebase là lịch sử chuyển đổi.

## 4. Chín phân hệ được khảo sát

### S01 — Auth/tài khoản

- Entry: Login launcher; Register/ForgotPassword/EmailConfirmation; callback meeto://auth/callback; RoleResolution. Register dựng form bằng Forms, không suy màn thực tế từ activity_register.xml legacy.
- Có login/signup/confirm/resend/recovery/PKCE callback, refresh/sign out, khôi phục phiên, role gate, onboarding profile+avatar+gợi ý follow lần đầu. OnboardingFlow lưu pending/done theo UID để không lặp mỗi lần login.
- Source: LoginActivity/RegisterActivity/AuthCallbackActivity/RoleResolutionActivity/OnboardingFlow; SupabaseAuthClient/ProfileClient/SessionManager.
- API Auth: /signup, /token?grant_type=password hoặc pkce, /verify, /resend, /recover, /user; RPC get_my_profile/bootstrap_profile/complete_onboarding_with_location/admin_get_capabilities. Data auth.users, profiles/profile_private/user_settings/account_status/system_roles; avatar/cover profile-media.
- ChangePasswordActivity: ba ô current/new/confirm, back; xác minh mật khẩu hiện tại và cùng UID rồi update. Account info bỏ shortcut trùng; Login có eye toggle.
- SOURCE INTEGRATED; settings email từng được đọc ở Task36, không đủ chứng minh delivery end-to-end. Các sửa password/one-time onboarding mới chưa build/runtime.

### S02 — Feed/Post/comments/share/save

- Entry FeedFragment; CreatePost từ header/drawer; card → PostDetails/PostMediaViewer; comments → CommentsBottomSheetFragment.
- Chức năng: cursor refresh/load more, caption/ảnh/video/album/audience, reaction, comment tree/reply/reaction/edit/delete, share feed/chat/link, save/unsave/hidden, edit/delete versioned, UTF-16 mentions và user tags có consent.
- Source FeedRepository/FeedAdapter/CreatePostActivity; CommentClient/CommentAdapter/CommentsBottomSheetFragment; PostDetails/PostUpdates/PostViewerUpdates; ShareActions.
- RPC get_feed/get_post/create_post_with_mentions/edit_post_with_mentions/delete_post/set_post_reaction/list_comment_tree/create_comment_with_mentions/change_comment_with_mentions/delete_comment/set_comment_reaction/share_post/set_saved_post. Data posts/post_media/media_assets/reactions/comments/saved_posts/hidden_posts/content_mentions/post_user_tags. Storage social-media.
- Shared/saved reference không tăng quyền đọc nguồn; viewer refetch theo ACL. Video đơn có tùy chọn Feed+Reels, dùng một row is_reel, không hai bài độc lập.
- SOURCE INTEGRATED. Core có bằng chứng lịch sử chọn lọc; tree/viewer/tags mới chưa runtime. Không xác nhận hashtag entity/parser chuyên dụng. Tag-management listing còn rủi ro transaction ở mục 9.

### S03 — Reels

- Entry tab Reels; drawer → ReelsCameraActivity → CreateReelActivity; CreatePost mở editor-only trả MP4/metadata.
- Chọn/quay, trim 1–60s, cover/mute/nhạc/offset/volumes, preview/export/publish; autoplay/pause/seek, like/comment/save/share/follow/views, owner audience/delete, server search/cover fallback.
- Source ReelsFragment/ReelsRepository/ReelsCameraActivity/CreateReelActivity, ReelClipExporter/ReelTrimTimelineView/MediaThumbnailLoader/ReelPlaybackMonitor.
- RPC đọc/search/record_reel_view/create_reel/create_reel_with_music/create_post_reel_with_mentions/create_post_reel_with_music/set_reel_audience/delete_reel; đối chiếu index caller cho tên read. Data posts.is_reel/reel_cover_asset_id/reel_duration_ms/reel_views + tương tác Post, **không có bảng reels riêng**. MP4/cover social-media.
- Không nhạc có nhánh copy đủ điều kiện; có nhạc luôn composition/export. Retry giữ caption/asset/request identity, không fallback publish không nhạc sau lỗi music.
- SOURCE INTEGRATED; owner chỉ nói đăng được ở tình huống đã thử; mixed preview còn lỗi mở. MP4 đã trộn phẳng từ editor không khôi phục stems khi chỉnh phiên mới.

### S04 — Stories

- Entry Story row/avatar ring/creator/drawer; StoryCreateActivity → StoryCameraActivity CameraX ảnh/video; StoryViewActivity full-screen.
- Canvas text/draw/filter/fit/crop/trim, nhạc/audience, export/probe/publish; progress/tap left-right/hold/pause/nhóm tác giả, like/viewers paged/reply/contact gate, owner privacy/delete.
- Source StoryClient, StoryCanvasView/StoryComposition/StoryMediaExporter/StoryRings/StoryNavigation, StoryPrivacySheet/StoryViewersSheet.
- RPC get_stories/get_story/get_story_states/get_story_author_stories/create_story/create_story_with_music/mark_story_viewed/set_story_like/get_story_viewers_page/reply_to_story/set_story_audience/delete_story. Data stories/views/reactions/media_assets, message/request khi reply; social-media.
- DB expiry24h không đồng nghĩa object xóa đúng giây hết hạn. SOURCE INTEGRATED; validator rotation fix06A chưa owner retest; không tự ghi Story không nhạc PASS.

### S05 — Messenger

- Entry InboxFragment/header → ChatActivity; Profile → contact gate; group picker; ConversationDetails → GroupMembers/ConversationSearch/SharedPosts/SharedMediaGallery/customization.
- Direct accepted contact/message requests, text/ảnh/video/reply/edit/recall/hide/copy/share; forward **text** có bảng avatar/name/checkbox; read/delivery seq/presence, groups/invitations/owner/admin/avatar/title/theme/nickname, search, per-member archive/mute/leave/remove. Inbox refresh và optimistic archive có rollback.
- Source ChatRepository/ChatActivity/InboxFragment/ChatControls/ChatCustomizationClient/SmartGroupPicker/ForwardMessagePicker.
- RPC get_inbox/get_conversation/get_conversation_members/get_messages/send_message/edit_message/recall_message/hide_message/mark_read; consent/group/invitation/customization/search_conversations_v36; list_message_forward_targets/forward_text_message.
- Data conversations/conversation_members/messages/message_media/message_hidden/conversation_mutes/message_requests/conversation_invitations/event_outbox. chat-media; avatar nhóm profile-media.
- Realtime invalidation+authoritative refetch/poll, không hứa instantaneous delivery. Forward IDs ổn định trong cửa sổ, chưa lưu bền qua recreation.
- Voice source còn nhưng entry GONE/disabled. Task35 preview local được, finalize409 verification_pending chưa tới send_message; Task36 owner hoãn. SOURCE INTEGRATED chỉ các mục hiện hành, chat đa tài khoản/group media/latest forwarding chưa runtime confirmed.

### S06 — Search/Profile/Follow/featured

- SearchFragment/AdvancedSearchRepository tìm người theo tên/token/accent/username, Post/Explore cursor; Reels search riêng. Username form đổi nhãn Biệt danh, bio phía dưới Tiểu sử. Search username đã có trước080004; migration mới không tự chứng minh Cloud triển khai.
- ProfileFragment là shortcut landing, ProfileActivity là hồ sơ thật: posts/Reels/About, follow/count/list/request/cancel/remove follower/block/report. FollowList kiểm owner/public; private chỉ chủ xem graph, kể cả follower được duyệt theo080001. Server migration cần có để chặn RPC trực tiếp.
- ProfileEdit: name/username/bio/avatar/cover/location/date/gender visibility; server uniqueness/version. Data profiles/profile_private/settings/follows/requests/blocks.
- FeaturedAlbumsUi/FeaturedAlbumViewerActivity: grid cover bo góc/name/+N, multi-image/rename/gỡ, slideshow5s/tap/hold/qua album. Data profile_albums.is_featured/profile_album_items; không Story expiry. ExactPhotoFingerprint so SHA-256 cho file cùng size, không perceptual dedup, không xóa ảnh bài gốc.
- ProfileAlbumsActivity/UserPostsActivity còn source/Manifest, chưa tìm thấy entry ngoài class; không coi chúng như album mới đã tích hợp. SOURCE INTEGRATED cho flow có entry, fixes08/11 chưa build/runtime. social-media/profile-media.

### S07 — Notifications/Settings

- Header → NotificationsActivity: all/unread/read one/all/pagination/refresh/badge/Observer. Target post→PostDetails, comment→comments theo post context, profile→Profile, conversation→Chat, story→StoryView; unsupported có thông báo.
- NotificationRepository/Adapter/Item; get_notifications/get_notification_unread_count/mark_notification_read/mark_all_notifications_read/get_notification_settings/update_notification_settings; notifications/settings/outbox. Kind follow/request/message/group_invite/reaction/comment/reply/story/mention/tag/moderation theo source/preferences; legacy friendship/community kinds bị filter.
- Settings: light/dark/system, edit profile/account info/change password, account private/activity/location audiences, blocked users, notifications, tag/follow/message requests, moderation có quyền, request deletion và logout.
- Request deletion chỉ timestamp+audit, không xóa/suspend ngay. Queue Admin dùng active+timestamp; mở khóa có thể hiện yêu cầu cũ lại vì chưa có trạng thái duyệt riêng.
- SOURCE INTEGRATED, late fixes NOT VERIFIED. Không xác nhận OS push/FCM: device_subscriptions có bảng nhưng client hiện in-app/Realtime, không có bằng chứng FCM tích hợp.

### S08 — Admin/Moderation

- Role gate → AdminPanel(system_admin); tabs Overview/Reports/Management/Audit+More, refresh/logout; header back đã bỏ. ModerationDashboard qua Settings/reviewer capability; ReportContentActivity phía Social.
- AdminRoleResolver/AdminRepository/AdminModels/AdminReportActions/AdminUi; admin_get_capabilities/admin_dashboard_v2/admin_*_page_v2/admin_*_detail_v2/admin_claim_report_v2/admin_reassign_report/admin_decide_report_v2/admin_moderate_subject_v2/admin_action_receipt.
- Data reports/actions/audit/status/roles + private.admin_action_receipts/moderation_media_holds/moderation_text_evidence; targets posts/comments/stories/messages. Scoped evidence không cấp media toàn cục, holds chặn cleanup chứng cứ.
- Allowed actions/state từ server, reason/confirm/version/idempotent receipt; target+report+action/audit+receipt trong transaction; bảo vệ self/system_admin. Hide/remove/restore/warn/suspend/unsuspend/dismiss tùy loại/state/capability.
- Warn chỉ recorded, chưa chứng minh delivery; committed audit không phải failure log/tamper-proof với DB owner. Chưa xác nhận UI quản role tùy ý hoặc hard-delete dữ liệu. Queue account requests/report.details ngoài card có migration riêng.
- SOURCE INTEGRATED; Task54B/C Cloud/runtime NO. Task33 build không bao phủ54v2/hotfix mới.

### S09 — Music/media/background và phụ trợ

Music ở mục5; media/background ở mục7. Tag/mentions không đồng nghĩa hashtag engine. Cache/catalog offline không đồng nghĩa publish offline. Presence/read seq/avatar loading là cross-cutting services, không friendship cũ.

## 5. Music Library mới nhất

| Khâu | Source fact |
|---|---|
| Catalog | 50 track CC0 trong070012; PK(track_id text,catalog_version), OGG/Vorbis/audio/ogg, duration/bytes/SHA/license/source/attribution/is_active. Seed inactive, không tự xác nhận activation/Storage. |
| Gates | get_music_publish_capabilities contract1 và get_music_library_capabilities/get_music_catalog contract2. Gate version khác metadata version. |
| Storage | music-library private, OGG <=5MiB theo migration; GET public project key+user JWT. Active-account/catalog/object path guards; app không được write. Không key bí mật Android. |
| Cache | MusicLibraryCache app-private LRU120MiB, SHA-256+bytes trước nhận/reuse, pin/lease giữ file đang dùng. Catalog offline theo UID, audio catalog cache dùng chung immutable key; tải mới/publish vẫn qua server. Không tải sẵn50 bài. |
| Picker | OpenverseMusicPickerActivity tên legacy, hiện dùng MusicLibraryRepository: tìm tại máy/listen/download bài chọn. Offline snapshot khi lỗi mạng đủ điều kiện; HTTP deny không được coi như offline success. MusicMiniPlayer/Editor giữ leases/focus/lifecycle. |
| Metadata v1 | Openverse13 trường, numeric ID/creator; không loop, giữ range cũ. Client/cache còn phục vụ tương thích, không phải nguồn tìm bài mới. |
| Metadata v2 | Đúng16 trường: contract_version/source/track_id/catalog_version/title/artist/duration_ms/start_offset_ms/used_duration_ms/music_volume/source_volume/license_id/license_url/source_url/attribution/sha256. contract_version2, source meeto_library. Không key17 loop_enabled/path/signed URL. |
| Finite loop | MusicLoopTimeline.plan dùng microsecond, offset trong nguồn, target1–60s, tối đa64 đoạn. Tail offset→EOF rồi head/full-track đến đủ target; cắt cuối đúng phần còn lại. Ví dụ36s/offset30s/target15s =6s cuối+9s đầu. Không repeat player vô hạn. |
| Gain/join | MusicComposition dùng duration nguồn trước clipping; catalog/source duration dung sai1ms, gain0–100; ramp2ms nội bộ, không thêm thời gian. Chưa đo gap/click/AAC priming/clipping thực. |
| Preview | CompositionPlayer/Media3 audio focus, cùng composition/timeline export; ảnh giữ canvas+audio; release cũ và copy selection. Mute chỉ source; ảnh/nguồn không audio ghi source_volume0. |
| Story export | Canvas flatten hoặc video trim/effect → StoryMediaExporter Transformer H.264/AAC → probe bytes/duration/audio/rotation/display720×1280. |
| Reel export | Snapshot source/trim/shape/mute/music → ReelClipExporter, H.264/AAC, FIT scale/frame rate theo source. Có nhạc luôn export; kiểm container/video/audio/duration/shape trước nhận output. |
| Publish/view | MP4 có audio nhúng → social-media upload/finalize → create_story_with_music/create_reel_with_music/create_post_reel_with_music; create/bind metadata/hash/active catalog cùng transaction. Viewer phát MP4, không OGG song song hoặc cần cache nhạc. Title+artist, fallback creator v1. |

Căn cứ Java: music/MusicLibraryTrack, MusicLibraryRepository/Cache, MusicLoopTimeline, MusicComposition, MusicMiniPlayer/OpenverseMusicEditor, StoryCreateActivity/CreateReelActivity, ui/StoryMediaExporter/ReelClipExporter; SQL070009/070012; docs/music-reports/MUSIC_TASK_02_REPORT.md đến06A.

**Owner BUG01:** Story chọn/nghe nhạc được, publish báo H.264/AAC/frame sai. Source06A thêm rotation vào StoryMusicExportChecks và production probe: encoded1280×720+rotation90 có thể display720×1280. Vẫn bắt codec/audio/duration; chưa có MP4 lần lỗi để khẳng định nguyên nhân duy nhất, chưa owner retest.

**Owner BUG02:** Picker phát được, Music Editor Reel mixed preview lỗi. Owner nói đăng Reel được ở tình huống đã thử. Source06A thêm diagnostic stage/cache/timeline/CompositionPlayer, listener identity guards và ownership release. **Root cause mixed preview chưa xác định**; không đổi mò timeline/sequence, không khẳng định hết lỗi.

Task03 ghi compile/resources+11 unit PASS; Task04 ghi compile/resources+24 unit PASS, Android/Cloud publish NOT VERIFIED. Task05/06A BUILD NOT VERIFIED. Probe giả/unit không tạo MP4 thật. Không lấy Task04 chứng minh Task05/06A build. Task03 “Editor chưa loop” là mốc trước Task04, không phủ nhận engine finite-loop hiện tại.

## 6. RPC, RLS và Storage

| Nhóm | Caller | Quyền, bảng và kết quả | SQL chính |
|---|---|---|---|
| Auth profile: bootstrap_profile/get_my_profile/get_profile/update_profile_public_v36/update_profile_v37 | Auth/ProfileClient/ProfileEdit | Identity/active/owner/version/field audience → profiles/private/settings → profile JSON. |010003/060001/060005|
| Follow: set_follow/list_follows/counts/review/cancel/remove | Profile/FollowList/RequestCenter | Confirmed follows, privacy/block/self guards, owner/public graph restriction → follows/requests → states/count/cursor. |060001/080001|
| Feed/Post create/edit/read/delete | FeedRepository/CreatePost/PostDetails | Actor/content ACL/assets ready/owner/version/request hash → posts/media/mentions/tags → canonical Post JSON. |010004/030012/060001/070005|
| Reactions/save/share | Repository/ShareActions | View/interact ACL, source recheck → reaction/save/share refs/counts → result/Post. |010004/030003/030011/060001|
| Comment tree/create/change/delete/reaction | CommentClient/Comments | Parent published, same post, UTF-16 ranges, version/ACL → comments/mentions/reactions → items+has_more/mutation. |030010/030012/070010|
| Reels create/read/search/audience/delete/views | Reels/CreatePost | is_reel, MP4 ready owner, duration<=60s, audience/version → posts/reel_views/media → reel card/count/state. |030016/060007/060009/060010/070005|
| Story create/read/view/like/viewers/audience/delete | StoryClient/creator/viewer | Live24h/audience/follow, owner/scoped viewers → stories/views/reactions → Story/page/result. |030002/060008/070002/070006|
| Music capabilities/catalog/bind | PublishingClient/LibraryRepository/creators | Active caller/catalog, v1/v2 validators/hash, MP4 owner/ready → tracks/posts/stories metadata → capabilities/catalog/content JSON. |070009/070012|
| Inbox/detail/messages/members/read markers | Inbox/ChatRepository | Active membership/private consent/block/history seq → conversation/messages/member/media → pages/detail. |010010/010011/060002/070003|
| Message send/edit/recall/hide | ChatRepository/ChatActivity | c_lock/contact trigger, source scope, version/edit time → messages/media/member/outbox → message/result. |010010/010013/030014/060002|
| Direct requests/group/invitation/membership/customization | Contact/SmartGroupPicker/RequestCenter/ChatControls | Policy/owner/admin/accepted membership → conversations/members/requests/invitations → state/conversation JSON. |060002/060004/070003|
| list_message_forward_targets/forward_text_message | ForwardMessagePicker | Candidate current authorized active chats, final source+target check; stable per-target UUID → messages/outbox → Message JSON. |080007/030014/060002|
| Notifications list/count/read/settings | NotificationRepository | Recipient identity/allowed kind/readability → notifications/settings → page/count/result. |030004/060003/060002|
| search_advanced_people/posts/search_reels/get_explore_posts | AdvancedSearchRepository | Query normalized, privacy/block/cursor → profiles/posts → rows/cards. |030008/060001/070007/080004|
| search_mention_people/sync_post_tags/request_post_retag | MentionComposer/PostTagPicker/create/edit | Incoming/outgoing confirmed follow suggestions, nobody/review preserved, target can read; terminal consent/cooldown → spans/tags. |030012/060001/080006/080008|
| Featured album list/save/delete/candidates | FeaturedAlbumsUi/viewer | Owner+version+post/media validity, per-item read ACL → albums/items → named arrays/receipt. |030013/080005/080009|
| request_account_deletion/privacy/notification/tag settings | Settings | Own identity/enum checks → status/settings/audit → requested_at/prefs. Không xóa ngay. |030007/060001/080008|
| Admin *_v2/capabilities/receipts | AdminRepository/AdminReportActions | System-admin hoặc reviewer/assignment, permitted action/expected state/request identity → target+report+action/audit/receipt → v2 page/detail/receipt. |070011/080002/080003|
| prepare_direct_upload/message_upload/finalize/upload policy | SupabaseStorage/hosts | Owner/purpose/object/type/bucket, ready retry, audio verifier → media_assets → intent/ready asset/policy. |030001/030015/060010/070001/070005|
| Worker dispatch/claim/finish/recovery | Cron/Edge/owner tooling | Worker scope, invocation token/lease/attempt fence, exact-object observations/live refs/holds → outbox/assets/private audit → batch/bool/receipt. |010016/010017/07001050/070011|

Tên đầy đủ và caller literal nằm ở phụ lục cuối; chữ viết tắt trong cột SQL là hậu tố migration cùng thư mục. Bảng ERD liệt kê tên file chính xác theo thực thể. Không coi private helpers là public RPC. Kiểm tra late override/grants khi viết một API cụ thể.

RLS/phân quyền: direct writes phần lớn REVOKE, SELECT theo policies hoặc RPC; mutations SECURITY DEFINER kiểm actor/status/block/audience/member/owner và transaction. JWT hợp lệ không tự vượt suspension. Public wrappers thường INVOKER, nhưng RPC public mới (featured/forward target) trực tiếp DEFINER vẫn có search_path rỗng + ACL explicit. Không nói RLS là lớp duy nhất, không nói service_role có quyền giống app.

Storage private, URL/path không phải quyền truy cập; loader vẫn user JWT và Storage policy. Bucket profile-media cho avatar/cover; social-media cho posts/stories/reels; chat-media cho message media; group-media legacy; music-library catalog read-only app. Không suy dung lượng tổng quota cũ còn áp dụng:070001 bỏ per-account quotas; giới hạn file/MIME/bucket/size/owner/finalize vẫn còn.

## 7. Media và background: nghiệp vụ bằng văn bản

1. Chọn URI/camera → draft riêng → probe MIME/size/duration/shape và upload-policy gate. Nếu trim/effect/music thì export/probe trước upload, không chỉ tin extension.
2. SupabaseStorage reserve intent UUID → media_assets pending/object path → authenticated direct/TUS upload → finalize storage metadata → ready; audio còn verifier, không giả ready.
3. Create RPC bind ready asset/nội dung/mentions/music cùng transaction; version/request-hash/idempotency tránh stale/duplicate. Lỗi upload/finalize không báo publish; lỗi bind giữ draft/receipt đủ điều kiện retry. Read-back get_post/reel/story + media ACL.
4. Xóa/replace không DELETE Storage ngay: detach/retire khi không còn live references → pending_delete+outbox → worker claim lease/attempt → Storage DELETE → observe exact object → record outcome → finish fenced → done/dead/retry. Shared refs/avatar/album/moderation holds có guards.
5. Source worker mới phân biệt absent/present/unknown, kiểm boolean finish; HTTP2xx DELETE/invocation completed không chứng minh từng object đã mất. Recovery source có audit cho exhausted processing, không retry vô hạn hoặc xóa refs để “cho qua”.
6. Cron source010016: dispatch mỗi phút, cleanup mỗi5 phút;010017 timeout;07001050 recovery +070011 holds thay lifecycle. Task54B2 ghi owner báo cron cleanup paused,01050/01100 chưa có ở mốc đó,17 processing attempts5+12 pending. Đó là snapshot lịch sử, không số live hiện tại hoặc recovery PASS.
7. Message commit/outbox → dispatcher private-user broadcast → RealtimeClient dedup/invalidate → Observer/ChatRepository RPC refetch. Không Android service_role/ghi notifications tùy ý, không direct postgres_changes của mọi UI.

## 8. Mâu thuẫn và cập nhật so với Task33/tài liệu cũ

| Mốc/tài liệu | Kết luận cần dùng |
|---|---|
| Foundation: CURRENT friendship/FUTURE follow/story | Nhãn lịch sử, không trạng thái sản phẩm. Follow/Story/Reel integrated, Friendship retired. |
| Task33 build22 unit/Admin search25006 blocked | Historical only, không bao phủ Admin54v2 hoặc08/11. |
| Task35 Memories/Marketplace/voice preview | Task36 removed Memories/Marketplace và deferred voice; Marketplace trước đó UI-only. |
| Task36:40 migration, debug/instrumentation+30 JVM+109 localSQL+4 targeted UI smoke | Source nay68 migrations và late changes; không mang số/count/PASS đó sang snapshot mới. |
| Task50 session identity/thumbnail semaphore | Task51/source đã tăng generation khi UID đổi và có acquired flag/tryAcquire/release đúng. Chỉ source fix, chưa runtime hiện tại. |
| Task51 nói có migration tag-list transaction fix |070008 hiện **1 byte**. list_my_post_tags030012 vẫn STABLE, b_actor→lock_profile có SELECT FOR UPDATE.080006 không sửa listing. Không ghi lỗi đã sửa; còn risk read-only transaction25006. |
| Openverse52/v1 không loop | V1 giữ không-loop; Library v2/Tasks03–05 có finite loop/cache/source gain. Tên class legacy không phải nguồn nhạc mới. |
| Music03 chưa loop | Mốc trước Music04, không phủ nhận MusicLoopTimeline hiện tại. |
| Music04 build verified | Không chứng minh Music05/06A/hotfix08/11 build hoặc audio thực. |
| Admin54B/C/Cleanup source verified | Không Cloud/runtime PASS. Không có ledger live mới. |
| UI ngày08/11 | Drawer/avatar/shortcuts, onboarding-once, eye/password, Settings polish, public/private follow lists, Admin nhãn Việt/back removal/account queue/details, nickname search, featured albums/slideshow/dedup, follow tags/@/default everyone và forward multi-target. Có call sites, chủ yếu source-only. |

## 9. Ma trận kết quả và lỗi mở

| Phân hệ | Source | Historical build | Owner runtime | Cloud | Ghi chú |
|---|---|---|---|---|---|
| Auth/onboarding/password | Integrated |33/35/36 mốc cũ|Owner thấy onboarding lặp, source mới chưa retest|Task36 đọc settings email hạn chế; delivery/callback mới unknown|Password/eye/once mới chưa build.|
| Feed/Post/comments/tags | Integrated |Core35/36, tree/tag mới source-only|Ảnh UI thật, tag bị phản ánh không dùng được|Late migrations unknown|Tag-list STABLE risk còn.|
| Reels/music | Integrated |Core lịch sử; Music05/06A not verified|Picker nghe được, đăng được tình huống owner; mixed preview lỗi|Catalog limited evidence; publish tổng thể unknown|Root cause preview chưa kết luận.|
| Stories/music | Integrated |Music04 compile/resources+24 unit theo report|Picker nghe được, output validation lỗi trước06A|Publish music NOT VERIFIED|Rotation fix chưa retest.|
| Messenger/group/forward | Integrated, voice deferred |35/36 chọn lọc|Owner phản ánh archive/forward; source mới chưa retest|Two-user/consent/group/realtime/latest RPC unknown|Không voice E2E PASS.|
| Search/Profile/featured | Integrated current entry |36 targeted; late UI chưa build|Owner ảnh profile/picker có trùng|080001/004/005/009 unknown|Dedup byte-equal, không ảnh nén/crop khác.|
| Notifications/Settings | Integrated |Lịch sử chọn lọc|Screenshots không bao phủ mọi kind|Latest preferences/privacy unknown|Không công bố OS push.|
| Admin/Moderation | Integrated |33/36 lịch sử, không54v2|Screenshots Admin+queue issue|54B/C ghi NO, hotfix local unknown|Warn recorded only.|
| Music cache/catalog/background | Integrated source |Music03=11 unit,04=24 unit theo report|Owner catalog50 active/hash50/50; âm thanh join chưa test|Catalog/storage limited owner evidence, cleanup paused snapshot|Không Cloud PASS toàn pipeline.|

Music Tasks02–03 lưu owner evidence50 active/Storage đúng50/GET+SHA50/50. Audit không tạo chứng cứ mới, không retest endpoints bằng user active. Ảnh owner chứng minh màn/tình huống xuất hiện, không chứng minh code mới hoặc backend toàn bộ.

Chưa đủ căn cứ: live migration ledger/schema/grants/policies/bucket/Edge version/cron; build source hiện tại; Story MP4 thực sau rotation fix; CompositionPlayer mixed preview; loop join/seek/volumes/AAC delay/cross-account audio; email delivery/callback/switch user; notifications/Realtimes hai máy; cleanup từng object/backlog; UI forward/featured/dedup/@/password/follow-lists mới. Phải giữ NOT VERIFIED khi viết báo cáo.

## 10. Cách dùng và index source

Lấy PK/FK/DDL ở ERD inventory, actor/steps/branches ở Diagram handoff, màn hình và vị trí ảnh ở UI inventory. Số sơ đồ không bị giới hạn. Schema source phải đọc ledger ALTER theo filename; schema live cần export/owner confirmation bổ sung. Không biến source presence thành runtime/deployment evidence.

### Phụ lục — RPC declarations/callers

Index tự kiểm kê dưới đây là tên từng được khai báo và caller literal, không phải final/live API inventory. Overload, DROP, wrappers sinh SQL và helpers phải đối chiếu file dẫn nguồn.
| Tên public từng khai báo | CREATE/REPLACE tĩnh cuối tìm được |
|---|---|
| `public.accept_friend_request` | `supabase/migrations/20261101001300_chat_lock_order.sql:96` |
| `public.acknowledge_delivery` | `supabase/migrations/20261101001000_messenger.sql:276` |
| `public.add_profile_album_photos` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:361` |
| `public.admin_audit_page` | `supabase/migrations/20261104000100_admin_panel.sql:304` |
| `public.admin_dashboard` | `supabase/migrations/20261104000100_admin_panel.sql:291` |
| `public.admin_list_content` | `supabase/migrations/20261104000100_admin_panel.sql:295` |
| `public.admin_moderate_content` | `supabase/migrations/20261104000100_admin_panel.sql:298` |
| `public.admin_report_detail` | `supabase/migrations/20261104000100_admin_panel.sql:302` |
| `public.admin_search_users` | `supabase/migrations/20261104000100_admin_panel.sql:293` |
| `public.admin_set_account_status` | `supabase/migrations/20261104000100_admin_panel.sql:300` |
| `public.bootstrap_profile` | `supabase/migrations/20261101000300_auth_profile.sql:242` |
| `public.cancel_friend_request` | `supabase/migrations/20261101001300_chat_lock_order.sql:98` |
| `public.cancel_group_invite` | `supabase/migrations/20261103000600_community_groups_completion.sql:464` |
| `public.cancel_group_request` | `supabase/migrations/20261103000600_community_groups_completion.sql:462` |
| `public.change_comment_with_mentions` | `supabase/migrations/20261103001200_tag_mentions.sql:613` |
| `public.chat_picker_people` | `supabase/migrations/20261107000300_chat_customization_picker.sql:94` |
| `public.claim_platform_report` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:225` |
| `public.clear_profile_pinned_post` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:369` |
| `public.clear_search_history` | `supabase/migrations/20261103000800_advanced_search.sql:204` |
| `public.complete_onboarding` | `supabase/migrations/20261101000300_auth_profile.sql:244` |
| `public.complete_onboarding_with_location` | `supabase/migrations/20261103000300_saved_follows_profile_location.sql:246` |
| `public.create_comment` | `supabase/migrations/20261101000400_social.sql:287` |
| `public.create_comment_with_mentions` | `supabase/migrations/20261103001200_tag_mentions.sql:610` |
| `public.create_community_group` | `supabase/migrations/20261103000500_community_groups.sql:270` |
| `public.create_community_post` | `supabase/migrations/20261103000500_community_groups.sql:281` |
| `public.create_community_post_with_mentions` | `supabase/migrations/20261103001200_tag_mentions.sql:607` |
| `public.create_group_conversation` | `supabase/migrations/20261101001000_messenger.sql:269` |
| `public.create_post` | `supabase/migrations/20261101000400_social.sql:279` |
| `public.create_post_reel_with_mentions` | `supabase/migrations/20261107000500_unified_video_publishing.sql:149` |
| `public.create_post_reel_with_music` | `supabase/migrations/20261107000900_openverse_music_metadata.sql:191` |
| `public.create_post_with_mentions` | `supabase/migrations/20261103001200_tag_mentions.sql:601` |
| `public.create_profile_album` | `supabase/migrations/20261106000100_follow_only_privacy.sql:543` |
| `public.create_reel` | `supabase/migrations/20261107000500_unified_video_publishing.sql:138` |
| `public.create_reel_with_music` | `supabase/migrations/20261107000900_openverse_music_metadata.sql:186` |
| `public.create_story` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:446` |
| `public.create_story_with_music` | `supabase/migrations/20261107000900_openverse_music_metadata.sql:196` |
| `public.decline_friend_request` | `supabase/migrations/20261101001300_chat_lock_order.sql:97` |
| `public.delete_comment` | `supabase/migrations/20261101000400_social.sql:289` |
| `public.delete_community_post` | `supabase/migrations/20261103000600_community_groups_completion.sql:218` |
| `public.delete_post` | `supabase/migrations/20261101000400_social.sql:282` |
| `public.delete_profile_album` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:357` |
| `public.delete_reel` | `supabase/migrations/20261106000900_unified_video_reel_management.sql:134` |
| `public.delete_search_history_item` | `supabase/migrations/20261103000800_advanced_search.sql:202` |
| `public.delete_story` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:452` |
| `public.direct_contact_policy` | `supabase/migrations/20261106000200_messaging_consent.sql:409` |
| `public.edit_comment` | `supabase/migrations/20261103001200_tag_mentions.sql:633` |
| `public.edit_message` | `supabase/migrations/20261103001400_advanced_messenger.sql:157` |
| `public.edit_post` | `supabase/migrations/20261103001200_tag_mentions.sql:629` |
| `public.edit_post_with_mentions` | `supabase/migrations/20261103001200_tag_mentions.sql:604` |
| `public.finalize_group_cover_upload` | `supabase/migrations/20261103000600_community_groups_completion.sql:460` |
| `public.finalize_upload` | `supabase/migrations/20261103000100_direct_storage_upload.sql:161` |
| `public.forward_text_message` | `supabase/migrations/20261103001400_advanced_messenger.sql:160` |
| `public.get_chat_customization` | `supabase/migrations/20261107000300_chat_customization_picker.sql:45` |
| `public.get_community_group` | `supabase/migrations/20261103000500_community_groups.sql:273` |
| `public.get_community_posts` | `supabase/migrations/20261103000500_community_groups.sql:283` |
| `public.get_conversation` | `supabase/migrations/20261101001000_messenger.sql:270` |
| `public.get_conversation_invite_candidates_excluded` | `supabase/migrations/20261106000300_notifications_moderation.sql:15` |
| `public.get_conversation_members` | `supabase/migrations/20261101001000_messenger.sql:280` |
| `public.get_conversation_mute` | `supabase/migrations/20261103001000_messenger_ux.sql:214` |
| `public.get_explore_posts` | `supabase/migrations/20261106000100_follow_only_privacy.sql:222` |
| `public.get_feed` | `supabase/migrations/20261101000400_social.sql:284` |
| `public.get_follow_counts` | `supabase/migrations/20261103000300_saved_follows_profile_location.sql:256` |
| `public.get_friends` | `supabase/migrations/20261101000400_social.sql:275` |
| `public.get_hidden_memories` | `supabase/migrations/20261103000900_memories.sql:224` |
| `public.get_inbox` | `supabase/migrations/20261101001000_messenger.sql:271` |
| `public.get_memories_by_year` | `supabase/migrations/20261103000900_memories.sql:220` |
| `public.get_memories_settings` | `supabase/migrations/20261103000900_memories.sql:212` |
| `public.get_message_unread_count` | `supabase/migrations/20261103000400_notifications_center.sql:259` |
| `public.get_messages` | `supabase/migrations/20261101001000_messenger.sql:273` |
| `public.get_music_catalog` | `supabase/migrations/20261107001200_music_catalog_storage.sql:340` |
| `public.get_music_library_capabilities` | `supabase/migrations/20261107001200_music_catalog_storage.sql:345` |
| `public.get_music_publish_capabilities` | `supabase/migrations/20261107000900_openverse_music_metadata.sql:184` |
| `public.get_my_profile` | `supabase/migrations/20261101000300_auth_profile.sql:243` |
| `public.get_notification_settings` | `supabase/migrations/20261103000400_notifications_center.sql:257` |
| `public.get_notification_unread_count` | `supabase/migrations/20261103000400_notifications_center.sql:254` |
| `public.get_notifications` | `supabase/migrations/20261103000400_notifications_center.sql:251` |
| `public.get_onboarding_suggestions` | `supabase/migrations/20261107000400_onboarding_follow_suggestions_hotfix.sql:52` |
| `public.get_or_create_private` | `supabase/migrations/20261101001000_messenger.sql:268` |
| `public.get_platform_audit` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:229` |
| `public.get_post` | `supabase/migrations/20261101000400_social.sql:283` |
| `public.get_profile` | `supabase/migrations/20261101000300_auth_profile.sql:247` |
| `public.get_profile_album` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:359` |
| `public.get_profile_pinned_post` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:370` |
| `public.get_read_receipts` | `supabase/migrations/20261101001000_messenger.sql:279` |
| `public.get_received_requests` | `supabase/migrations/20261101000400_social.sql:276` |
| `public.get_reel_access` | `supabase/migrations/20261107000500_unified_video_publishing.sql:184` |
| `public.get_reel_upload_policy` | `supabase/migrations/20261107000500_unified_video_publishing.sql:169` |
| `public.get_saved_posts` | `supabase/migrations/20261103000300_saved_follows_profile_location.sql:252` |
| `public.get_search_history` | `supabase/migrations/20261103000800_advanced_search.sql:198` |
| `public.get_sent_requests` | `supabase/migrations/20261101000400_social.sql:277` |
| `public.get_shared_media` | `supabase/migrations/20261103001000_messenger_ux.sql:218` |
| `public.get_stories` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:444` |
| `public.get_story` | `supabase/migrations/20261106000800_story_experience.sql:19` |
| `public.get_story_author_stories` | `supabase/migrations/20261106000800_story_experience.sql:38` |
| `public.get_story_privacy` | `supabase/migrations/20261107000600_story_audience_edit.sql:14` |
| `public.get_story_states` | `supabase/migrations/20261106000800_story_experience.sql:27` |
| `public.get_story_viewers` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:450` |
| `public.get_story_viewers_page` | `supabase/migrations/20261106000800_story_experience.sql:62` |
| `public.get_suggestions` | `supabase/migrations/20261101000400_social.sql:278` |
| `public.get_tagging_settings` | `supabase/migrations/20261103001200_tag_mentions.sql:618` |
| `public.get_today_memories` | `supabase/migrations/20261103000900_memories.sql:217` |
| `public.get_user_posts` | `supabase/migrations/20261101000400_social.sql:285` |
| `public.get_user_reels` | `supabase/migrations/20261103001600_reels_short_videos.sql:228` |
| `public.hide_memory` | `supabase/migrations/20261103000900_memories.sql:227` |
| `public.hide_message_for_me` | `supabase/migrations/20261101001000_messenger.sql:278` |
| `public.invite_to_community_group` | `supabase/migrations/20261103000500_community_groups.sql:278` |
| `public.join_community_group` | `supabase/migrations/20261103000500_community_groups.sql:274` |
| `public.leave_community_group` | `supabase/migrations/20261103000500_community_groups.sql:275` |
| `public.leave_conversation` | `supabase/migrations/20261101001000_messenger.sql:282` |
| `public.list_blocked_users` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:215` |
| `public.list_comment_tree` | `supabase/migrations/20261107001000_comment_tree_paging.sql:291` |
| `public.list_comments` | `supabase/migrations/20261103001000_messenger_ux.sql:212` |
| `public.list_community_members` | `supabase/migrations/20261103000500_community_groups.sql:276` |
| `public.list_conversation_invitations` | `supabase/migrations/20261106000200_messaging_consent.sql:424` |
| `public.list_follow_requests` | `supabase/migrations/20261106000100_follow_only_privacy.sql:228` |
| `public.list_follows` | `supabase/migrations/20261103000300_saved_follows_profile_location.sql:258` |
| `public.list_group_bans` | `supabase/migrations/20261103000600_community_groups_completion.sql:467` |
| `public.list_group_invites` | `supabase/migrations/20261103000600_community_groups_completion.sql:463` |
| `public.list_group_reports` | `supabase/migrations/20261103000600_community_groups_completion.sql:469` |
| `public.list_group_requests` | `supabase/migrations/20261103000600_community_groups_completion.sql:461` |
| `public.list_message_forward_targets` | `supabase/migrations/20261108000700_message_forward_targets.sql:12` |
| `public.list_message_requests` | `supabase/migrations/20261106000200_messaging_consent.sql:418` |
| `public.list_my_post_tags` | `supabase/migrations/20261103001200_tag_mentions.sql:619` |
| `public.list_platform_reports` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:222` |
| `public.list_profile_albums` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:358` |
| `public.list_profile_featured` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:367` |
| `public.list_profile_featured_albums` | `supabase/migrations/20261108000900_featured_photo_duplicate_sizes.sql:32` |
| `public.list_profile_media` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:364` |
| `public.list_profile_photo_candidates` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:360` |
| `public.list_reels` | `supabase/migrations/20261103001600_reels_short_videos.sql:223` |
| `public.manage_conversation_member` | `supabase/migrations/20261101001000_messenger.sql:281` |
| `public.manage_group_member` | `supabase/migrations/20261103000600_community_groups_completion.sql:465` |
| `public.mark_all_notifications_read` | `supabase/migrations/20261103000400_notifications_center.sql:256` |
| `public.mark_notification_read` | `supabase/migrations/20261103000400_notifications_center.sql:255` |
| `public.mark_read` | `supabase/migrations/20261101001000_messenger.sql:275` |
| `public.mark_story_viewed` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:448` |
| `public.meeto_worker_claim_cleanup` | `supabase/migrations/20261107001050_media_cleanup_recovery.sql:514` |
| `public.meeto_worker_claim_voice_verification` | `supabase/migrations/20261103001500_voice_messaging.sql:370` |
| `public.meeto_worker_consume_token` | `supabase/migrations/20261101001600_cloud_workers.sql:192` |
| `public.meeto_worker_finish_cleanup` | `supabase/migrations/20261107001050_media_cleanup_recovery.sql:520` |
| `public.meeto_worker_finish_run` | `supabase/migrations/20261101001600_cloud_workers.sql:210` |
| `public.meeto_worker_finish_voice_verification` | `supabase/migrations/20261103001500_voice_messaging.sql:376` |
| `public.meeto_worker_record_cleanup_observation` | `supabase/migrations/20261107001050_media_cleanup_recovery.sql:536` |
| `public.onboarding_suggestions` | `supabase/migrations/20261107000400_onboarding_follow_suggestions_hotfix.sql:63` |
| `public.prepare_direct_message_upload` | `supabase/migrations/20261103000100_direct_storage_upload.sql:157` |
| `public.prepare_direct_story_upload` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:210` |
| `public.prepare_direct_upload` | `supabase/migrations/20261103000100_direct_storage_upload.sql:153` |
| `public.prepare_group_cover_upload` | `supabase/migrations/20261103000600_community_groups_completion.sql:459` |
| `public.prepare_message_upload` | `supabase/migrations/20261101001100_chat_media_realtime.sql:70` |
| `public.prepare_upload` | `supabase/migrations/20261101000500_media.sql:48` |
| `public.recall_message` | `supabase/migrations/20261101001000_messenger.sql:277` |
| `public.record_reel_view` | `supabase/migrations/20261106000700_reel_view_counts.sql:48` |
| `public.remove_follower` | `supabase/migrations/20261106000100_follow_only_privacy.sql:227` |
| `public.remove_profile_album_photo` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:362` |
| `public.remove_profile_featured` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:366` |
| `public.rename_conversation` | `supabase/migrations/20261101001000_messenger.sql:283` |
| `public.reply_to_story` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:454` |
| `public.report_group_target` | `supabase/migrations/20261103000600_community_groups_completion.sql:468` |
| `public.request_account_deletion` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:217` |
| `public.request_post_retag` | `supabase/migrations/20261103001200_tag_mentions.sql:624` |
| `public.resolve_conversation_invitation` | `supabase/migrations/20261106000200_messaging_consent.sql:421` |
| `public.resolve_follow_request` | `supabase/migrations/20261106000100_follow_only_privacy.sql:226` |
| `public.resolve_group_invite` | `supabase/migrations/20261103000500_community_groups.sql:279` |
| `public.resolve_group_report` | `supabase/migrations/20261103000600_community_groups_completion.sql:470` |
| `public.resolve_group_request` | `supabase/migrations/20261103000500_community_groups.sql:277` |
| `public.resolve_message_request` | `supabase/migrations/20261106000200_messaging_consent.sql:415` |
| `public.resolve_platform_report` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:227` |
| `public.review_community_post` | `supabase/migrations/20261103000500_community_groups.sql:282` |
| `public.review_post_tag` | `supabase/migrations/20261103001200_tag_mentions.sql:622` |
| `public.save_profile_featured_album` | `supabase/migrations/20261108000500_featured_photo_albums.sql:43` |
| `public.save_search_query` | `supabase/migrations/20261103000800_advanced_search.sql:200` |
| `public.search_advanced_groups` | `supabase/migrations/20261103000800_advanced_search.sql:196` |
| `public.search_advanced_people` | `supabase/migrations/20261103000800_advanced_search.sql:191` |
| `public.search_advanced_posts` | `supabase/migrations/20261103000800_advanced_search.sql:193` |
| `public.search_community_groups` | `supabase/migrations/20261103000500_community_groups.sql:272` |
| `public.search_conversations` | `supabase/migrations/20261103001400_advanced_messenger.sql:163` |
| `public.search_conversations_v36` | `supabase/migrations/20261106000200_messaging_consent.sql:430` |
| `public.search_mention_people` | `supabase/migrations/20261103001200_tag_mentions.sql:599` |
| `public.search_messages` | `supabase/migrations/20261101001400_chat_search_contract.sql:23` |
| `public.search_profiles` | `supabase/migrations/20261101000300_auth_profile.sql:248` |
| `public.search_reels` | `supabase/migrations/20261107000700_search_reels_posts.sql:75` |
| `public.send_friend_request` | `supabase/migrations/20261101001300_chat_lock_order.sql:95` |
| `public.send_message` | `supabase/migrations/20261101001000_messenger.sql:272` |
| `public.send_message_request` | `supabase/migrations/20261106000200_messaging_consent.sql:412` |
| `public.send_shared_post` | `supabase/migrations/20261103001100_advanced_share.sql:231` |
| `public.set_avatar_preset` | `supabase/migrations/20261103000200_stories_avatar_preset.sql:456` |
| `public.set_block` | `supabase/migrations/20261101001100_chat_media_realtime.sql:88` |
| `public.set_chat_nickname` | `supabase/migrations/20261107000300_chat_customization_picker.sql:52` |
| `public.set_chat_theme` | `supabase/migrations/20261107000300_chat_customization_picker.sql:70` |
| `public.set_comment_reaction` | `supabase/migrations/20261103001000_messenger_ux.sql:210` |
| `public.set_community_member_role` | `supabase/migrations/20261103000500_community_groups.sql:280` |
| `public.set_conversation_archived` | `supabase/migrations/20261106000200_messaging_consent.sql:427` |
| `public.set_conversation_avatar` | `supabase/migrations/20261106000400_group_chat_avatar.sql:14` |
| `public.set_conversation_muted` | `supabase/migrations/20261103001000_messenger_ux.sql:216` |
| `public.set_conversation_state` | `supabase/migrations/20261101001000_messenger.sql:284` |
| `public.set_follow` | `supabase/migrations/20261103000300_saved_follows_profile_location.sql:254` |
| `public.set_post_reaction` | `supabase/migrations/20261101000400_social.sql:286` |
| `public.set_profile_album_cover` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:363` |
| `public.set_profile_featured` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:365` |
| `public.set_profile_pinned_post` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:368` |
| `public.set_reel_audience` | `supabase/migrations/20261106000900_unified_video_reel_management.sql:77` |
| `public.set_saved_post` | `supabase/migrations/20261103000300_saved_follows_profile_location.sql:250` |
| `public.set_story_audience` | `supabase/migrations/20261107000600_story_audience_edit.sql:30` |
| `public.set_story_like` | `supabase/migrations/20261106000800_story_experience.sql:47` |
| `public.share_community_post` | `supabase/migrations/20261103001100_advanced_share.sql:234` |
| `public.share_post` | `supabase/migrations/20261101000400_social.sql:280` |
| `public.submit_content_report` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:219` |
| `public.task36_capabilities` | `supabase/migrations/20261106000500_profile_field_visibility.sql:176` |
| `public.task44_chat_capabilities` | `supabase/migrations/20261107000300_chat_customization_picker.sql:43` |
| `public.transfer_group_owner` | `supabase/migrations/20261103000600_community_groups_completion.sql:466` |
| `public.unfriend` | `supabase/migrations/20261101000400_social.sql:274` |
| `public.unhide_memory` | `supabase/migrations/20261103000900_memories.sql:229` |
| `public.update_community_group` | `supabase/migrations/20261103000500_community_groups.sql:271` |
| `public.update_memories_settings` | `supabase/migrations/20261103000900_memories.sql:214` |
| `public.update_notification_settings` | `supabase/migrations/20261103000400_notifications_center.sql:258` |
| `public.update_privacy_settings` | `supabase/migrations/20261103000700_account_privacy_moderation.sql:212` |
| `public.update_profile` | `supabase/migrations/20261101000300_auth_profile.sql:245` |
| `public.update_profile_album` | `supabase/migrations/20261103001300_advanced_profile_albums.sql:356` |
| `public.update_profile_private` | `supabase/migrations/20261101000300_auth_profile.sql:246` |
| `public.update_profile_public_v36` | `supabase/migrations/20261106000100_follow_only_privacy.sql:238` |
| `public.update_profile_v37` | `supabase/migrations/20261106000500_profile_field_visibility.sql:168` |
| `public.update_profile_with_location` | `supabase/migrations/20261103000300_saved_follows_profile_location.sql:242` |
| `public.update_tagging_settings` | `supabase/migrations/20261103001200_tag_mentions.sql:616` |

Wrappers Admin sinh động trong070011: admin_get_capabilities/admin_decide_report_v2/admin_moderate_subject_v2/admin_action_receipt/admin_reassign_report/admin_claim_report_v2/admin_reports_page_v2/admin_audit_page_v2/admin_report_detail_v2/admin_subject_detail_v2/admin_users_page_v2/admin_content_page_v2/admin_dashboard_v2. Không đếm thêm nếu đã có khai báo tĩnh; kiểm actual signature trong migration.

| RPC literal trong Java (không đảm bảo caller còn entry) | Các file gọi |
|---|---|
| `acknowledge_delivery` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:39` |
| `admin_action_receipt` | `app/src/main/java/com/example/meetomxh/supabase/AdminRepository.java:77` |
| `admin_audit_page_v2` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java:421` |
| `admin_claim_report_v2` | `app/src/main/java/com/example/meetomxh/supabase/AdminReportActions.java:300` |
| `admin_content_page_v2` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java:411` |
| `admin_dashboard_v2` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java:379` |
| `admin_get_capabilities` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java:309`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java:50`; `app/src/main/java/com/example/meetomxh/supabase/AdminRoleResolver.java:25` |
| `admin_report_detail_v2` | `app/src/main/java/com/example/meetomxh/supabase/AdminReportActions.java:118` |
| `admin_reports_page_v2` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java:389` |
| `admin_subject_detail_v2` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java:841` |
| `admin_users_page_v2` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java:401` |
| `bootstrap_profile` | `app/src/main/java/com/example/meetomxh/AuthCallbackActivity.java:59`; `app/src/main/java/com/example/meetomxh/RegisterActivity.java:105`; `app/src/main/java/com/example/meetomxh/supabase/SupabaseAuthClient.java:24`; `app/src/main/java/com/example/meetomxh/supabase/SupabaseAuthClient.java:71` |
| `change_comment_with_mentions` | `app/src/main/java/com/example/meetomxh/fragments/CommentsBottomSheetFragment.java:550` |
| `chat_picker_people` | `app/src/main/java/com/example/meetomxh/supabase/ChatCustomizationClient.java:21` |
| `clear_profile_pinned_post` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java:250` |
| `clear_search_history` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:70` |
| `complete_onboarding_with_location` | `app/src/main/java/com/example/meetomxh/OnboardingFlow.java:27`; `app/src/main/java/com/example/meetomxh/RegisterActivity.java:79` |
| `create_comment_with_mentions` | `app/src/main/java/com/example/meetomxh/fragments/CommentsBottomSheetFragment.java:378` |
| `create_group_conversation` | `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java:50` |
| `create_post_reel_with_mentions` | `app/src/main/java/com/example/meetomxh/CreatePostActivity.java:443` |
| `create_post_reel_with_music` | `app/src/main/java/com/example/meetomxh/CreatePostActivity.java:442` |
| `create_post_with_mentions` | `app/src/main/java/com/example/meetomxh/CreatePostActivity.java:323` |
| `create_reel` | `app/src/main/java/com/example/meetomxh/CreateReelActivity.java:768` |
| `create_reel_with_music` | `app/src/main/java/com/example/meetomxh/CreateReelActivity.java:767` |
| `create_story` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:40` |
| `create_story_with_music` | `app/src/main/java/com/example/meetomxh/StoryCreateActivity.java:262` |
| `delete_comment` | `app/src/main/java/com/example/meetomxh/fragments/CommentsBottomSheetFragment.java:597` |
| `delete_post` | `app/src/main/java/com/example/meetomxh/PostMediaViewerActivity.java:163` |
| `delete_profile_album` | `app/src/main/java/com/example/meetomxh/ui/FeaturedAlbumsUi.java:112` |
| `delete_search_history_item` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:67` |
| `delete_story` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:44` |
| `direct_contact_policy` | `app/src/main/java/com/example/meetomxh/supabase/SocialActions.java:16` |
| `edit_post_with_mentions` | `app/src/main/java/com/example/meetomxh/CreatePostActivity.java:319` |
| `finalize_group_cover_upload` | `app/src/main/java/com/example/meetomxh/storage/SupabaseStorage.java:513` |
| `finalize_upload` | `app/src/main/java/com/example/meetomxh/storage/SupabaseStorage.java:512` |
| `forward_text_message` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:50`; `app/src/main/java/com/example/meetomxh/ui/ForwardMessagePicker.java:105` |
| `get_chat_customization` | `app/src/main/java/com/example/meetomxh/supabase/ChatCustomizationClient.java:19` |
| `get_conversation` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:18` |
| `get_conversation_invite_candidates_excluded` | `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java:30` |
| `get_conversation_members` | `app/src/main/java/com/example/meetomxh/GroupMembersActivity.java:85`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:18`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:28`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:29`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:51`; `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java:24`; `app/src/main/java/com/example/meetomxh/supabase/MessengerRecipientSheet.java:130` |
| `get_conversation_mute` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:52` |
| `get_explore_posts` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:36` |
| `get_feed` | `app/src/main/java/com/example/meetomxh/repositories/FeedRepository.java:27` |
| `get_follow_counts` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java:499` |
| `get_inbox` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:28`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:29`; `app/src/main/java/com/example/meetomxh/supabase/MessengerRecipientSheet.java:120` |
| `get_message_unread_count` | `app/src/main/java/com/example/meetomxh/MainActivity.java:260` |
| `get_messages` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:19`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:31`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:36`; `app/src/main/java/com/example/meetomxh/SharedPostsActivity.java:82` |
| `get_music_publish_capabilities` | `app/src/main/java/com/example/meetomxh/music/MusicPublishingClient.java:13` |
| `get_my_profile` | `app/src/main/java/com/example/meetomxh/supabase/ProfileClient.java:5` |
| `get_notification_settings` | `app/src/main/java/com/example/meetomxh/repositories/NotificationRepository.java:20` |
| `get_notification_unread_count` | `app/src/main/java/com/example/meetomxh/MainActivity.java:259`; `app/src/main/java/com/example/meetomxh/repositories/NotificationRepository.java:17` |
| `get_notifications` | `app/src/main/java/com/example/meetomxh/repositories/NotificationRepository.java:11` |
| `get_onboarding_suggestions` | `app/src/main/java/com/example/meetomxh/supabase/OnboardingSuggestionsClient.java:39` |
| `get_or_create_private` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:26` |
| `get_post` | `app/src/main/java/com/example/meetomxh/adapters/FeedAdapter.java:590`; `app/src/main/java/com/example/meetomxh/CreatePostActivity.java:168`; `app/src/main/java/com/example/meetomxh/storage/PostImageDownload.java:19`; `app/src/main/java/com/example/meetomxh/supabase/PostDetails.java:68`; `app/src/main/java/com/example/meetomxh/supabase/PostUpdates.java:23`; `app/src/main/java/com/example/meetomxh/supabase/PostViewerUpdates.java:30`; `app/src/main/java/com/example/meetomxh/supabase/ShareActions.java:49` |
| `get_profile` | `app/src/main/java/com/example/meetomxh/FollowListActivity.java:38`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java:107`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java:93` |
| `get_profile_pinned_post` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java:326` |
| `get_read_receipts` | `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java:65` |
| `get_reel_access` | `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java:339`; `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java:383`; `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java:421` |
| `get_reel_upload_policy` | `app/src/main/java/com/example/meetomxh/CreatePostActivity.java:267`; `app/src/main/java/com/example/meetomxh/CreatePostActivity.java:417`; `app/src/main/java/com/example/meetomxh/CreateReelActivity.java:703`; `app/src/main/java/com/example/meetomxh/storage/SupabaseStorage.java:154`; `app/src/main/java/com/example/meetomxh/StoryCreateActivity.java:245` |
| `get_saved_posts` | `app/src/main/java/com/example/meetomxh/SavedPostsActivity.java:75` |
| `get_search_history` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:61` |
| `get_shared_media` | `app/src/main/java/com/example/meetomxh/SharedMediaGalleryActivity.java:150` |
| `get_stories` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:28` |
| `get_story` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:82` |
| `get_story_author_stories` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:29` |
| `get_story_privacy` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:47` |
| `get_story_states` | `app/src/main/java/com/example/meetomxh/ui/StoryRings.java:77` |
| `get_story_viewers` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:116` |
| `get_story_viewers_page` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:98` |
| `get_tagging_settings` | `app/src/main/java/com/example/meetomxh/TagManagementActivity.java:54` |
| `get_user_posts` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java:363`; `app/src/main/java/com/example/meetomxh/ProfileActivity.java:518`; `app/src/main/java/com/example/meetomxh/UserPostsActivity.java:11` |
| `get_user_reels` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java:283` |
| `leave_conversation` | `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java:33` |
| `list_blocked_users` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java:194`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java:162` |
| `list_comment_tree` | `app/src/main/java/com/example/meetomxh/supabase/CommentClient.java:34` |
| `list_follows` | `app/src/main/java/com/example/meetomxh/FollowListActivity.java:51` |
| `list_message_forward_targets` | `app/src/main/java/com/example/meetomxh/ui/ForwardMessagePicker.java:87` |
| `list_my_post_tags` | `app/src/main/java/com/example/meetomxh/TagManagementActivity.java:111` |
| `list_profile_featured_albums` | `app/src/main/java/com/example/meetomxh/FeaturedAlbumViewerActivity.java:100`; `app/src/main/java/com/example/meetomxh/ui/FeaturedAlbumsUi.java:72` |
| `list_profile_photo_candidates` | `app/src/main/java/com/example/meetomxh/ui/FeaturedAlbumsUi.java:200` |
| `list_reels` | `app/src/main/java/com/example/meetomxh/repositories/ReelsRepository.java:74` |
| `manage_conversation_member` | `app/src/main/java/com/example/meetomxh/GroupMembersActivity.java:216`; `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java:31` |
| `mark_all_notifications_read` | `app/src/main/java/com/example/meetomxh/repositories/NotificationRepository.java:19` |
| `mark_notification_read` | `app/src/main/java/com/example/meetomxh/repositories/NotificationRepository.java:18` |
| `mark_read` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:40` |
| `mark_story_viewed` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:43` |
| `record_reel_view` | `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java:410` |
| `rename_conversation` | `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java:32` |
| `reply_to_story` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:79` |
| `report_group_target` | `app/src/main/java/com/example/meetomxh/ReportContentActivity.java:47` |
| `request_account_deletion` | `app/src/main/java/com/example/meetomxh/SettingsActivity.java:174` |
| `review_post_tag` | `app/src/main/java/com/example/meetomxh/TagManagementActivity.java:180` |
| `save_profile_featured_album` | `app/src/main/java/com/example/meetomxh/ui/FeaturedAlbumsUi.java:221` |
| `save_search_query` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:64` |
| `search_advanced_people` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:29`; `app/src/main/java/com/example/meetomxh/supabase/PeoplePicker.java:47` |
| `search_advanced_posts` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:36` |
| `search_conversations_v36` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:51` |
| `search_mention_people` | `app/src/main/java/com/example/meetomxh/ui/MentionComposer.java:106`; `app/src/main/java/com/example/meetomxh/ui/PostTagPicker.java:75` |
| `search_messages` | `app/src/main/java/com/example/meetomxh/ConversationSearchActivity.java:159`; `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:54` |
| `search_reels` | `app/src/main/java/com/example/meetomxh/repositories/AdvancedSearchRepository.java:54` |
| `send_message` | `app/src/main/java/com/example/meetomxh/repositories/ChatRepository.java:46`; `app/src/main/java/com/example/meetomxh/supabase/MessageOutbox.java:10`; `app/src/main/java/com/example/meetomxh/supabase/MessageOutbox.java:9` |
| `send_message_request` | `app/src/main/java/com/example/meetomxh/supabase/SocialActions.java:41` |
| `send_shared_post` | `app/src/main/java/com/example/meetomxh/supabase/ShareActions.java:80` |
| `set_block` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java:331`; `app/src/main/java/com/example/meetomxh/ProfileActivity.java:338`; `app/src/main/java/com/example/meetomxh/RequestCenterActivity.java:198`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java:164` |
| `set_chat_nickname` | `app/src/main/java/com/example/meetomxh/supabase/ChatCustomizationUi.java:49` |
| `set_chat_theme` | `app/src/main/java/com/example/meetomxh/supabase/ChatCustomizationUi.java:81` |
| `set_comment_reaction` | `app/src/main/java/com/example/meetomxh/fragments/CommentsBottomSheetFragment.java:691` |
| `set_conversation_archived` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java:112` |
| `set_conversation_avatar` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java:358` |
| `set_conversation_muted` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java:303` |
| `set_follow` | `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java:679`; `app/src/main/java/com/example/meetomxh/ProfileActivity.java:529`; `app/src/main/java/com/example/meetomxh/supabase/OnboardingSuggestionsClient.java:56` |
| `set_post_reaction` | `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java:662`; `app/src/main/java/com/example/meetomxh/PostDetailsActivity.java:55`; `app/src/main/java/com/example/meetomxh/PostMediaViewerActivity.java:137`; `app/src/main/java/com/example/meetomxh/ProfileActivity.java:138`; `app/src/main/java/com/example/meetomxh/repositories/FeedRepository.java:39` |
| `set_profile_pinned_post` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java:197` |
| `set_reel_audience` | `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java:286` |
| `set_saved_post` | `app/src/main/java/com/example/meetomxh/PostMediaViewerActivity.java:145`; `app/src/main/java/com/example/meetomxh/supabase/SavedPostClient.java:9` |
| `set_story_audience` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:59` |
| `set_story_like` | `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java:95` |
| `share_post` | `app/src/main/java/com/example/meetomxh/repositories/FeedRepository.java:40`; `app/src/main/java/com/example/meetomxh/supabase/ShareActions.java:54` |
| `submit_content_report` | `app/src/main/java/com/example/meetomxh/ReportContentActivity.java:49` |
| `task44_chat_capabilities` | `app/src/main/java/com/example/meetomxh/supabase/ChatCustomizationClient.java:11` |
| `update_notification_settings` | `app/src/main/java/com/example/meetomxh/repositories/NotificationRepository.java:22` |
| `update_privacy_settings` | `app/src/main/java/com/example/meetomxh/SettingsActivity.java:143` |
| `update_profile_v37` | `app/src/main/java/com/example/meetomxh/ProfileEditActivity.java:269` |
| `update_tagging_settings` | `app/src/main/java/com/example/meetomxh/TagManagementActivity.java:89` |
