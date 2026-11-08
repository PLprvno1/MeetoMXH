# MEETO — Dữ liệu bàn giao vẽ sơ đồ

08/11/2026, Asia/Bangkok. Không có sơ đồ được vẽ trong file này. Đây là node, tác nhân, quan hệ và thứ tự lời gọi từ source; tác giả ChatGPT tự chọn/vẽ UML/ERD/architecture/activity/sequence, không giới hạn số lượng. Các flow là SOURCE INTEGRATED, không runtime hoặc deployment PASS. Cấu trúc dữ liệu đầy đủ ở MEETO_ERD_SCHEMA_INVENTORY.md; screen/entry đầy đủ ở MEETO_UI_SCREEN_INVENTORY.md.

Quy ước Java/SQL/XML theo file audit chính. Với từng sequence: UI/main-thread → Api background → transport/RPC → transaction DB; callback quay UI chỉ khi Subscription/session generation còn hợp lệ. Có những Activity gọi thẳng RpcClient, không chèn Repository giả vào sơ đồ.

## D01 — System Architecture / Component Dependencies

| Node | Trách nhiệm | Dependency / cạnh dữ liệu |
|---|---|---|
| Người dùng Social / Moderator / System Admin | Tác nhân ứng dụng | Activity/Fragment đúng role, không truy DB trực tiếp. |
| MeetoApplication | Init session/theme/asset loader/lifecycle guard | SessionManager, AppCompatDelegate, Glide registry. |
| Login/Register/AuthCallback/RoleResolution | Auth và routing | SupabaseAuthClient/ProfileClient/AdminRoleResolver. |
| Main shell | Năm tab và drawer/header | Feed/Reels/Inbox/Search/Profile fragments, các Activity composer/settings/viewer. |
| Admin shell | Dashboard/report/user/content/audit | AdminRepository/AdminReportActions/AdminModels, capability gates. |
| Models/Dto | JSON và asset references | Repository/UI; không giữ quyền server chỉ nhờ model. |
| Repository/client | Feed/Reels/Chat/Search/Notifications/Profile/Story/Comments | Api, RpcClient, SupabaseStorage. |
| Api/Subscription | Background, main callback, cancel/epoch | Executor, SessionManager, HttpClient. |
| SessionManager/EncryptedVault | User session+refresh/identity; local AES-GCM Keystore | SupabaseAuthClient/Auth REST; Api fences. |
| SupabaseHttpClient/RpcClient | HTTPS REST/RPC với user JWT | Supabase Auth, PostgREST, Storage API. |
| SupabaseStorage/PrivateAssetLoader | Reserve/upload/finalize/authenticated bytes | RPC/media_assets → Storage; Glide cho ảnh. |
| CameraX | Ảnh/video capture finalized private files | ReelsCamera/StoryCamera → editor. |
| Media3 layer | Player/CompositionPlayer/Transformer | Local media/music cache → preview/MP4, output probe. |
| Music library/cache | Catalog active + SHA/lease | get_music_* RPC → private music-library GET. |
| Supabase Auth | Signup/login/verify/recovery/refresh | auth.users/session, profile bootstrap được gọi từ app. |
| PostgreSQL/PostgREST | RPC/RLS/transactions/constraints | public/private tables, Storage registry/ACL, outbox. |
| Supabase Storage | Private object bytes | Auth/RLS/purpose/path; buckets, không app service key. |
| RealtimeClient/Observer | Private user broadcasts+refetch+poll30s | Realtime WebSocket → subscribers → RPC. |
| pg_cron/pg_net/Edge cleanup | Dispatch/claim/delete/observe/finish | event_outbox/private.worker_invocations/Storage/cleanup audit. |

Căn cứ: MainActivity, MeetoApplication, Api/RpcClient/SupabaseHttpClient/SessionManager/EncryptedVault, storage/, music/, RealtimeClient/Observer; migrations010002/010016/07001050/070011/070012. Không vẽ Firebase, FCM push, commerce server hoặc Community Groups như thành phần hiện hành; không vẽ Android có service_role.

## D02 — Use Case Social: tác nhân và dữ liệu đặc tả

Tác nhân chính: khách chưa có phiên; tài khoản đã xác thực active; chủ nội dung/hồ sơ/album; người xem được phép; người tham gia chat; owner/admin chat nhóm. “Người theo dõi” là quan hệ giữa tài khoản, không mặc định vai trò quản trị. Supabase Auth/Storage/Realtime và worker là hệ thống phụ trợ, không người dùng.

| ID / Use Case | Actor; tiền điều kiện / mục tiêu | Luồng chính → hậu điều kiện | Ngoại lệ / điểm kết thúc | Source / RPC |
|---|---|---|---|---|
| UC-S01 Đăng ký/xác minh | Khách; email/password/profile hợp lệ | Form→signup→confirmation/resend→callback→session/profile→role gate; có tài khoản/phiên nếu Auth cấp | Mismatch/weak/duplicate/expired callback; giữ form hoặc retry, không giả email đã tới | Register/EmailConfirmation/AuthCallback; SupabaseAuthClient |
| UC-S02 Đăng nhập/khôi phục phiên | Khách hoặc phiên lưu; vào đúng shell | login/refresh→self profile active→onboarding nếu thiếu→capability→Main/Admin |401→login; profile/capability lỗi→retry; suspended→sign out | Login/RoleResolution/SessionManager/AdminRoleResolver |
| UC-S03 Tạo bài có media | Active; nội dung/quyền hợp lệ | Chọn media→probe→reserve/upload/finalize→create_post_with_mentions→Post JSON | Upload/finalize/ACL/metadata lỗi không publish, giữ draft/receipt | CreatePost/SupabaseStorage; posts/media/mentions/tags |
| UC-S04 Xem/tương tác Feed | Active và được đọc bài | get_feed cursor→read card/media→reaction/save/share/comments→count authoritative | Bài unavailable/rights changed→ẩn/refetch; network error/retry | FeedRepository/FeedAdapter/PostDetails/PostUpdates |
| UC-S05 Bình luận/reply/@ | Được xem/interact post; parent published | Tree page→nhập/@ suggestion→chọn span→create_comment_with_mentions→merge tree/count | Parent deleted, invalid range/version/target cannot read→lỗi không giả success | CommentsBottomSheet/CommentClient/MentionComposer;070010 |
| UC-S06 Đăng Story | Active; ảnh/video finalized | Camera/gallery→canvas/edit/audience/music optional→export/probe→upload/create→Story | Codec/audio/shape/cache/ACL/network lỗi→giữ draft; expiry24h ở DB | StoryCreate/Camera/Exporter/StoryClient |
| UC-S07 Xem Story | Có Story live được phép đọc | Discovery/ID→get_story→authorized bytes→progress5s/video→next author; view/like/reply tùy quyền | Expired/unavailable→skip/close; contact request cho reply; lỗi tải retry | StoryView/StoryClient/StoryRings |
| UC-S08 Tạo/xem Reel | Active; video1–60s/có quyền đọc | Capture/gallery→trim/music→export/probe→upload/create→Reel card; viewer autoplay/tương tác | Codec/validation/upload/gate lỗi giữ draft; quyền bị thu hồi dừng player | ReelsCamera/CreateReel/ReelsFragment/ReelsRepository |
| UC-S09 Chọn/phối nhạc | Active; catalog/capabilities enabled | Library search/listen/download→SHA/cache lease→offset/gains→finite composition→preview/export | Denied/offline thiếu cache/SHA fail/decode/composition lỗi→không publish giả | LibraryRepository/Cache/Picker/Editor/Composition |
| UC-S10 Nhắn tin riêng | Active; accepted contact hoặc cần request | Profile contact policy→chat hoặc gửi request→accepted chat→send_message→read markers | Pending/rejected/blocked/inactive→không gửi thẳng; source media fail→giữ draft | SocialActions/RequestCenter/ChatActivity/Repository |
| UC-S11 Chat nhóm | Member active; tạo cần lựa chọn/invitation hợp lệ | SmartGroupPicker→create/invite→accept→Chat; owner/admin quản member/title/avatar | Duplicate/unauthorized/pending invitation/owner transfer guard→error | SmartGroupPicker/ChatControls/GroupMembers;060002/070003 |
| UC-S12 Chuyển tiếp văn bản | Có quyền đọc source và destination | Open picker→query/page→checkbox→forward_text_message theo đích→đánh dấu gửi | Một đích lỗi giữ retry UUID; nguồn recall/rights revoked bị chặn; không forward media qua text RPC | ForwardMessagePicker;080007/030014 |
| UC-S13 Follow/private requests | Active, không self/block | Public→set_follow có hiệu lực; private→request→owner accept/reject/cancel; list/count | Block/inactive/privacy restriction→không lộ danh sách; private graph owner-only | Profile/FollowList/RequestCenter;060001/080001 |
| UC-S14 Search/profile/edit | Active; own write hoặc readable profile | Query name/username/posts→open profile→edit own version/privacy/assets | Username duplicate/invalid/field visibility/version conflict→giữ input | SearchFragment/AdvancedSearch/ProfileEdit;080004 |
| UC-S15 Featured albums | Owner write, reader có quyền từng ảnh | Multi-select/name→atomic save→cover/name/+N→Story-like viewer→next album | Empty/inaccessible album skip; concurrent edit/asset missing rollback; exact-file duplicates only picker | FeaturedAlbumsUi/Viewer;080005/080009 |
| UC-S16 Notifications | Active recipient | list/count→read one/all→navigate target→reauthorize | Missing target/legacy kind/ACL→fallback; no fabricated OS push | Notifications/Repository;030004/060003 |
| UC-S17 Settings/privacy/password | Chủ tài khoản | Load prefs→chọn/save; password verify-current→update same UID; deletion request timestamp | Invalid current password/version/session failure→giữ screen; request không xóa ngay | Settings/ChangePassword;Auth REST/request_account_deletion |
| UC-S18 Báo cáo vi phạm | Active được report target | ReportContent→reason/details/request ID→submit_content_report→case queued | Invalid/unreadable/self/protected target theo server→error | ReportContent/030007/070011 |

**Include/extend:** source không chứa mô hình UML. Các quan hệ chắc chắn là lời gọi/điều kiện, tác giả có thể biểu diễn nhưng không gán tùy ý: tạo bài **có media** bắt buộc reserve/upload/finalize; publish có music bắt buộc validate/bind metadata; thêm music là nhánh tùy chọn của Story/Reel; reply chỉ thực hiện khi người xem chọn và contact policy cho phép; private follow mở nhánh request, public follow không bắt buộc duyệt; mọi mutation yêu cầu auth/status nhưng refresh token không chạy ở mọi lần. Không vẽ mọi nút trở thành include hoặc coi UI kế tiếp là extend.

## D03 — Use Case Admin / Moderator

Actors: system_admin; moderator/reviewer trong scope assigned report; người dùng gửi report là actor phía Social có case đầu vào. Worker/Storage cung cấp evidence, không admin đăng nhập.

| ID | Actor, tiền điều kiện, mục tiêu | Các bước chính / hậu điều kiện | Nhánh lỗi | Source |
|---|---|---|---|---|
| UC-A01 Vào Admin | Active system_admin, xác minh quyền | RoleResolution→admin_get_capabilities→AdminPanel | Không quyền→Social hoặc retry theo resolver; không fallback Admin khi lỗi | AdminRoleResolver |
| UC-A02 Dashboard | System_admin | admin_dashboard_v2→KPIs/recent reports/actions→mở tab | Contract/error/loading/empty riêng | AdminPanel/070011/080003 |
| UC-A03 Quản người dùng/nội dung | System_admin | Query/page→subject_detail→permitted_actions→reason/confirm→moderate | Protected self/admin, stale expected-state, suspended session→fail closed | AdminRepository/admin_moderate_subject_v2 |
| UC-A04 Claim/reassign report | Reviewer hợp lệ hoặc admin theo server | List case→claim/reassign→assignment/audit commit | Người khác claim/stale assignee/permission change→conflict | AdminReportActions/admin_claim_report_v2/admin_reassign_report |
| UC-A05 Xem bằng chứng | Admin/reviewer scoped | report_detail→scoped media resolver→ảnh/video/text evidence | Missing/retired/inaccessible evidence→status, không nới public ACL | AdminReportActions/070011 |
| UC-A06 Quyết định report | Allowed action + expected state + lý do | decide→transition target→action+case state+audit+receipt transaction→reload | RPC rollback nếu lỗi; network unknown→query receipt/retry same identity | admin_decide_report_v2/AdminRepository |
| UC-A07 Audit | System_admin hoặc scoped reviewer | audit_page_v2→actor/operation/state/reason/time/report | Old missing fields unknown; không tự backfill state | AdminUi/ModerationDashboard |
| UC-A08 Yêu cầu tài khoản | System_admin; active account có deletion timestamp | Queue→subject detail→chọn action hiện có→audit | Request≠hard-delete; re-open active có timestamp có thể trở lại queue |080002/AdminPanel |

Không có căn cứ Use Case quản role tùy ý, xóa dữ liệu vật lý tức thì, gửi cảnh cáo thành công hoặc audit tamper-proof. Warn recorded-only. Moderator không có toàn bộ quyền system_admin; chọn action server trả về, không suy từ menu local.

## D04 — Authentication / Role Activity và Sequence

Node: User→Login/Register/AuthCallback→SupabaseAuthClient→Auth API→SessionManager/Vault→ProfileClient→RPC/DB→AdminRoleResolver→Main/Admin/Retry.

1. Start launcher Login: có phiên lưu? Nếu có, refresh khi cần; nếu không nhận email/password.
2. SupabaseAuthClient.login → POST Auth token-password; error giữ Login. Signup có confirmation khác, không giả có session trước verify.
3. SessionManager.accept validate response; UID đổi tăng generation, cùng UID refresh giữ epoch; persist encrypted vault; jobs cũ bị fence.
4. RoleResolution resolve → ProfileClient.self/get_my_profile: inactive→sign out+Login; missing onboarding→OnboardingFlow.completeProfileFromPending→complete_onboarding_with_location.
5. Optional social setup pending→đánh dấu done trước ProfileEdit assets-only→PeopleSuggestions→role resolution tiếp. Account đã hoàn tất không hỏi lại mỗi lần login.
6. AdminRoleResolver.resolve → admin_get_capabilities. system_admin→AdminPanel, thường→Main,401→Login, lỗi/contract→Retry, không fail-open.
7. End shell mở đúng role hoặc user dừng ở Login/Retry. Auth callback exported cần pending flow/account matching/PKCE hoặc verify; không coi token trong Intent là quyền quản trị.
8. Password change là sequence riêng: current password grant→verify same UID/epoch→accept same-user session→PUT /auth/v1/user→success clear+back, lỗi giữ form. Back không update.

Căn cứ: LoginActivity/RoleResolutionActivity/OnboardingFlow/PeopleSuggestionsActivity/AuthCallbackActivity/SupabaseAuthClient/SessionManager/EncryptedVault/AdminRoleResolver;010003/060005/070011.

## D05 — Navigation Map (dữ liệu cạnh)

- Login→Register/ForgotPassword/EmailConfirmation/RoleResolution; callback external→AuthCallback→route phù hợp. Main và Admin internal; AuthCallback và SharedPostLink exported theo Manifest.
- Main bottom→Feed/Reels/Inbox/Search/ProfileFragment. Drawer→CreatePost/StoryCreate/ReelsCamera/Saved/Settings; header avatar→ProfileActivity current UID; Notifications header→NotificationsActivity.
- Feed/Post/Search/Saved→PostDetails; ảnh post→PostMediaViewer; video→VideoPlayer; comment→CommentsBottomSheet. Share link external→SharedPostLink→session/role-aware PostDetails.
- Story row/avatar→StoryView; creator→StoryCamera/gallery→StoryCreate; music→Picker→Editor→creator. Reel camera/gallery→CreateReel; CreatePost editor-only→CreateReel→result về composer.
- ProfileFragment→ProfileActivity/Saved/Settings; ProfileActivity→ProfileEdit/FollowList/Chat-contact/report; featured cover→FeaturedAlbumViewer; owner long-press→Editor.
- Inbox→Chat/RequestCenter/group picker/archive; Chat header→ConversationDetails→GroupMembers/ConversationSearch/SharedPosts/SharedMediaGallery/Customization; media→ChatImageViewer/VideoPlayer.
- Settings→ChangePassword/ProfileEdit/TagManagement/RequestCenter/ModerationDashboard; dialogs privacy/account/block/theme/notifications/deletion.
- AdminPanel→detail/action/evidence/receipt dialogs; refresh/logout; không có header back. ModerationDashboard từ Settings theo capability.
- ProfileAlbumsActivity/UserPostsActivity và standalone SearchActivity chưa có caller external được tìm thấy; ProfilePhotoViewerActivity chủ yếu cung cấp static intent trỏ ChatImageViewer. Tách registered nhưng chưa integrated khỏi màn hoạt động. UI inventory có registry đầy đủ.

## D06 — Database ERD

Dữ liệu node/columns/PK/FK/cardinality120 mappings nằm trong MEETO_ERD_SCHEMA_INVENTORY.md; không lặp DDL ở đây. Gợi ý các nhóm ERD riêng:
1. Auth/Profile/Follow/requests/block/settings/status/roles.
2. Post/media/reaction/save/comments/tree/mentions/tags.
3. Story/views/reactions và Reel qua posts/reel_views.
4. Conversation/membership/message/media/consent/invitations/mutes.
5. Profile albums/items/featured/pinned.
6. Notifications/outbox và moderation/actions/audit/holds/evidence/receipts.
7. Music catalog; chỉ cạnh logic metadata→track/version, không FK giả.
8. Private worker/recovery/audit và legacy Community/Friendship/Memories tách rõ.

Các FK composite comments(parent_id,post_id) và messages(reply_to_message_id,conversation_id) giữ tuple. Các ID polymorphic/content metadata/address Storage không FK. auth/storage/realtime/cron là schema managed, không bảng ứng dụng tự tạo. Không đủ căn cứ vẽ live schema đã deploy; ghi source-model khi chưa có export.

## D07 — Post/Media Sequence + Activity

Participants: User/CreatePostActivity→Api/SupabaseStorage→upload-policy/reserve RPC→PostgreSQL media_assets→Storage→finalize RPC→create_post_with_mentions→Postgres posts/post_media/mentions/tags→Feed/PostDetails.

1. Start chọn text/media/audience/tags; snapshot draft/request IDs. Không media: bỏ qua upload, đi create.
2. Media có hợp lệ? probe MIME/bytes/duration, output edit nếu cần; invalid→giữ form/error, end retry/cancel.
3. Storage client kiểm policy, prepare_direct_upload; DB reserve pending/object path, response intent; unauthorized/quota/file limit→no upload.
4. Upload authenticated direct hoặc TUS, progress; network/cancel không bind content. Finalize xác minh object/owner/purpose, chỉ nhận ready; voice-pending không thuộc Post path.
5. CreatePost gọi create_post_with_mentions với body/audience/ready asset IDs/client UUID/spans/tag IDs; private create/bind+sync trong transaction. Consent/span/asset failure rollback post mutation, giữ retry identity.
6. DB trả canonical Post; UI chỉ success sau kết quả hợp lệ. FeedRepository/get_feed/get_post re-read; PrivateAssetLoader kiểm authorized media. Saved/share target gốc không được nâng quyền.
7. End Post hiện theo ACL; cancel/error kết thúc ở draft có thể retry, không success giả.

Alt video duy nhất + Share to Reels: editor-only nếu phải cắt → create_post_reel_with_mentions hoặc music variant → một posts row is_reel; không vẽ hai transactions tạo Feed và Reel riêng. Nguồn CreatePostActivity/SupabaseStorage;030012/060010/070005.

## D08 — Story/Reels Music Sequence + Activity

Participants: Creator→OpenverseMusicPickerActivity→MusicLibraryRepository→MusicPublishingClient/RPC catalog→Storage OGG→MusicLibraryCache→Editor/MiniPlayer→MusicComposition/MusicLoopTimeline→CompositionPlayer/Transformer→output checks→SupabaseStorage→publish RPC/DB→viewer.

1. Start chọn ảnh/video và target duration1–60s; không chọn nhạc→luồng cũ.
2. require publish capability + library capability; get_music_catalog active rows; offline snapshot chỉ khi hợp lệ. Gate/denied/schema missing→dừng nhạc hoặc user bỏ nhạc, không âm thầm publish thiếu metadata.
3. Chọn track: cache SHA/size valid? có→lease; không→GET authenticated OGG→verify→atomic cache+lease. Mất mạng/hash fail→không preview/export giả.
4. Editor chỉnh offset/source/music volume; bản copy phục vụ preview; MusicLoopTimeline finite tail+head. CompositionPlayer dùng composition cùng timeline export, focus/lifecycle/seek/generation guards.
5. User save editor→snapshot config; ensure storage free64MiB, source không trùng destination, giữ lease. Story ảnh flatten canvas; Story video/Reel giữ range/effects/rotation.
6. Transformer H.264/AAC→MP4→MediaExtractor/Retriever probe. Story display720×1280 theo rotation; Reel scale FIT giới hạn theo source; audio thật/duration/codec phải đạt. False→xóa output tạm đúng generation, giữ source/draft.
7. Build metadata từ snapshot render v2 đúng16 fields; ảnh/mute/no source audio→source_volume0. V1 range non-loop giữ nguyên.
8. Upload/finalize MP4→create_story_with_music/create_reel_with_music/create_post_reel_with_music→DB create+bind/hash/catalog-active cùng transaction. Lỗi giữ output/receipt; không fallback plain RPC.
9. Viewer lấy MP4 theo ACL và phát audio nhúng; không phát thêm OGG, không phụ thuộc cache nhạc tài khoản khác.

Alt/error: cache missing, OGG decode, composition build/timeline, focus suppression, stale callback, codec failure, insufficient space, metadata/Cloud deny, token/network retry. BUG01 rotation source fix chưa owner retest; BUG02 mixed preview chưa root cause. Căn cứ music source, StoryCreate/CreateReel/Exporters,070009/070012 và Music06A.
## D09 — Messenger/Realtime Sequence + Activity

Participants: Sender ChatActivity→ChatRepository/Api→send_message RPC→c_lock/contact guards→messages/message_media/conversations/event_outbox→cron private.meeto_dispatch_chat_outbox→realtime.send→recipient RealtimeClient→Observer/ChatRepository→get_messages→Chat UI.

1. Start contact: SocialActions.direct_contact_policy quyết định accepted chat hay message request. Group invitation phải accepted/active membership. Không bypass pending private consent.
2. Sender nhập text hoặc chọn media; media cần prepare_direct_message_upload/upload/finalize ready theo conversation scope. Voice recording entry hiện disabled; không vẽ voice happy-path như tính năng đã dùng được.
3. ChatRepository.send → send_message(client UUID,conversation,body/kind/assets/reply). c_lock serialization/active member/contact trigger/block; reply phải thuộc conversation/history. Duplicate UUID+same fingerprint trả message cũ, payload khác conflict.
4. DB seq tăng theo conversation, INSERT message/media, cập nhật last_message và outbox transaction; failure rollback. Sender callback nhận message ID/result và reconcile.
5. Cron dispatcher claim due outbox, tính recipients từ source, realtime.send private user channel với event_id/scope; kiểm kết quả/persist status. Retry/dead/lease theo SQL. Không coi send RPC hoặc WebSocket transport như delivery receipt đã tới người nhận.
6. Recipient RealtimeClient nhận broadcast, EventDeduplicator.accept, invalidate subscriber; Observer hoặc ChatRepository tải messages/inbox từ RPC. Observer poll30s nếu không có event. Old epoch/job bị loại.
7. Render seq-sorted/history bounded, cập nhật delivered/read qua RPC/member markers; sender refetch markers. Presence/last-seen có privacy, không phải Realtime presence channel vì client config presence disabled.
8. End message được commit/hiện nếu được đọc; offline/network/expired auth→error/retry/refetch, không đánh đã đọc chỉ vì event tới.

Forward sub-sequence: long-press text→ForwardMessagePicker hiện ngay→list_message_forward_targets(query/cursor)→select<=20→forward_text_message theo đích với stable UUID→partial successes marked/disabled, failures retained. Source recall/rights changed sau chọn vẫn bị server chặn; không tạo chat mới tự động; error ở một đích không rollback các đích đã commit. IDs chỉ giữ trong cửa sổ, chưa bền qua recreation.

Căn cứ ChatActivity/ChatRepository/ForwardMessagePicker/RealtimeClient/Observer;010010/010011/010013/010016/030014/060002/070003/080007.

## D10 — Admin Moderation Sequence + Activity

Participants: Reporter ReportContentActivity→submit_content_report→reports; Admin/Moderator→AdminPanel/ModerationDashboard→AdminRepository/AdminReportActions→capability/list/detail/claim/decision RPC→private.admin_transition/admin_commit_action→target tables+actions/audit/receipt→UI reload.

1. Start reporter chọn target/reason/details/request identity; server xác minh reportable scope, tạo case open. Không mặc định report là quyền đọc tất cả media sau này.
2. Reviewer load capabilities/list; filter mine/state/query; open detail chỉ trường được phép. Claim case nếu cần; assignment conflict→reload.
3. Detail trả target/evidence/permitted_actions. Media evidence scoped theo report/role; unauthorized/missing→status, không chuyển Storage public.
4. Chọn action có trong server result; nhập reason; xác nhận; snapshot expected_state và request UUID qua AdminRepository. Request có receipt? Có→đối chiếu và nhận kết quả cũ; không→execute decision.
5. Server kiểm active/role/assignment/source state/self/admin protections; row locks; transition target, report resolve/dismiss, moderation_action, audit old/new/reason và private receipt trong transaction.
6. Exception→rollback target/case/audit/receipt của mutation; client giữ draft/lý do khi hợp lệ, hiện typed error; không ghi audit thành công cho lần thất bại. Network unknown→query receipt, không đổi UUID vô hạn.
7. Commit→UI kiểm contract/result, reload counters/list/detail. Permission revoke→clear sensitive UI/re-route. Warn chỉ record, delivery chưa có.
8. End target/case/audit nhất quán hoặc không thay đổi sau rollback. Cleanup hold/text evidence là nhánh bảo toàn chứng cứ, không xóa media lập tức khi hide.

Căn cứ AdminRepository.submit/receiptFor, AdminReportActions, AdminPanelActivity, AdminUi;070011/080002/080003. Owner request account là loại timestamp+audit, không báo cáo nội dung `reports` mặc định; Admin queue đi subject/account action hiện có.

## D11 — Media Cleanup Lifecycle / State Activity

Participants: Owner/content mutation→PostgreSQL retire_asset/schedule_media_cleanup→event_outbox→cron invocation→Edge meeto-background-cleanup→Storage→worker observation/finish RPC→media registry/audit.

State nodes: pending upload; ready bound/unbound; pending_delete; deleted; event pending/processing/done/dead; storage observation not_observed/absent/present/unknown. Không gộp state asset với state event.

1. Start xóa post/Reel/Story/message hoặc thay avatar/cover/album reference; các đường delete/detach có transaction, version/ACL.
2. Decision còn live reference hoặc moderation_media_holds? Có→không retire/xóa object; không→mark pending_delete và schedule event. Story expiry loại khỏi đọc không chứng minh object đã xóa.
3. Cron/worker invocation token có hạn→claim batch; event lease+attempt fence. Không claim được/race/expired invocation→retry/no mutation tùy code.
4. Worker validate contract bucket/path/purpose/MIME; DELETE exact Storage object nếu contract hợp lệ. Regardless delete result, observe exact object thành absent/present/unknown; không đánh success chỉ vì2xx DELETE.
5. meeto_worker_record_cleanup_observation persist outcome, sau đó meeto_worker_finish_cleanup(event,attempt,success/error). Absent+recorded+live refs safe→registry deleted và event done; present/unknown→retry/dead theo attempts; stale attempt→fence reject.
6. private attempt audit/recovery log giữ observed/finish outcome. Hết lease/max attempts cần recovery source có bounded owner preflight/receipt, không mặc định reset attempts hoặc purge reference.
7. End done chỉ khi DB/Storage reconciliation đạt; dead/blocked/unknown là kết thúc cần xử lý, không giấu trong “cleanup thành công”. Invocation completed chỉ run-level.

Căn cứ010006/010009/010011/010016/010017/07001050/070011; functions/meeto-background-cleanup/index.ts/audio_verifier.ts. Task54B2 source recovery chưa Cloud confirmed; owner snapshot paused/backlog là lịch sử. Không vẽ cron/worker như đang vận hành đã xác nhận.

## D12 — Follow / Private Account Activity (sơ đồ bổ sung)

Start xem Profile→get_profile/get_follow_counts→self? self→edit/list controls; other→block/account/status check→public? public→set_follow hiệu lực; private→pending request→RequestCenter owner accept/reject; cancel requester/unfollow/remove follower là nhánh riêng. Khi accept ghi user_follows, không Friendship. Follow pending không cho quyền content. List followers/following: self hoặc target public mới cho mở; target private other bị chặn kể cả approved follower theo080001. Backend và UI cùng guard, migration chưa deploy không đủ chặn RPC trực tiếp. End canonical state/count hoặc error giữ trạng thái hiện tại. Source ProfileActivity/FollowListActivity/RequestCenterActivity;060001/080001.

## D13 — Featured Albums / Slideshow Activity (sơ đồ bổ sung)

Start Profile owner nhấn+→list candidates paged→group same size→ExactPhotoFingerprint SHA authenticated streaming nếu cần→bỏ lựa chọn file-byte trùng, giữ representative đã chọn→title/1–50 images→save_profile_featured_album(UUID stable,expected_version)→validate từng source image→atomic album/items commit→cover/name/+N. Cancel không đổi album. Hash/size thiếu hoặc đọc lỗi giữ ứng viên; ảnh nén/crop khác giữ riêng. Trùng chỉ được đối chiếu trong picker, không xóa Storage/Post.

Viewer: profile cover click→FeaturedAlbumViewerActivity(owner/album IDs)→list_profile_featured_albums ACL→lọc album rỗng→selected album/current image→Glide load success→progress5s. Tap right next image, hết album→album kế; tap left ở đầu→ảnh cuối album trước; ở đầu toàn bộ giữ ảnh đầu; hết cuối đóng. Hold/manual pause/background dừng timer; resume reauthorize; periodic15s recheck; RPC denied clears list/image; image load error cho retry/skip. Owner long-press giữ editor rename/remove, không biến viewer thành Story thật có expiry/view rows.

Source FeaturedAlbumsUi/FeaturedAlbumViewerActivity/ExactPhotoFingerprint/StoryProgressView;080005/080009. No runtime evidence cho source mới. Có thể tách thành sequence create/edit/preview và activity viewer.

## D14 — Mention/Tag Consent Activity (sơ đồ bổ sung)

- Start PostTagPicker hoặc comment input `@`→debounce→search_mention_people(query có thể rỗng)→confirmed follows incoming OR outgoing + active/unblocked/profile readable→avatar/name/username suggestions.
- Picker bài: chọn<=10, can_tag=false chỉ giải thích/không chọn thêm; giữ selected khi tìm. Comment: chọn person chèn MentionSpan(userId,label,start/end UTF-16), không parse text tùy ý thành mention có quyền.
- Create/edit→sync_content_mentions kiểm ranges/label/target readability; sync_post_tags kiểm policy/target xem được post. followers policy cho quan hệ ít nhất một chiều, everyone vẫn cho theo policy, nobody giữ chặn. Default everyone chỉ row mới080008, không reset quyền cũ.
- Review-required→pending→recipient accept/reject; removed/rejected không tự hồi sinh khi edit. request_post_retag là explicit reconsent có cooldown7 ngày. Failure→rollback mutation, giữ draft phù hợp.
- Source MentionComposer/PostTagPicker/TagManagementActivity;030012/060001/080006/080008. Tag-management listing STABLE/b_actor còn risk vì070008 trống, không nhầm lỗi listing với suggestion fix đã có source.

## D15 — Notification Activity (sơ đồ bổ sung)

Start mutation/react/comment/follow/message/moderation→server trigger/preference/scope→notifications+outbox→dispatch→private user broadcast→Observer/NotificationRepository list/count→badge/all/unread. User chọn→mark read→target-specific navigation→RPC kiểm lại quyền target. Missing/retired target hoặc unsupported type→message/fallback; không mở nội dung từ payload mà bỏ ACL. Mark all read chỉ recipient. End unread count authoritative; transport lỗi giữ retry. Không có căn cứ vẽ FCM/OS push từ device_subscriptions đơn thuần.

## Quy tắc biên tập hình của tác giả

- Node/lifeline và thứ tự ở đây là dữ liệu đầu vào, không phải sơ đồ đã nghiệm thu. Include/extend chỉ gắn khi phù hợp điều kiện đã mô tả, không biến sequence gọi function thành quan hệ UML một cách máy móc.
- Thể hiện async callback/transaction/loop hữu hạn/alt lỗi và role boundary. UI→Repository không bắt buộc ở mọi flow vì source có direct RPC call.
- Legacy và server-only operational nodes có vùng riêng. Tách source architecture với deployed topology chưa có bằng chứng.
- Không số lượng sơ đồ tối đa: có thể tách các phần15 nhóm trên thành nhiều hình cho từng actor/flow hoặc bảng ERD con. Không vẽ/cardinality giả để lấp thiếu evidence.