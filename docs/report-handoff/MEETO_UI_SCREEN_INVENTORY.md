# MEETO — Danh mục màn hình và kế hoạch ảnh giao diện

08/11/2026, Asia/Bangkok. Đây là kế hoạch chụp ảnh thật cho tác giả, không tạo/chụp ảnh trong audit. Tên hình là đề xuất; không có ảnh giả hoặc ảnh ứng dụng khác. SOURCE INTEGRATED không có nghĩa UI/runtime đã được kiểm thử. Dùng tài khoản thử do owner quản lý; che email/UID thật, token hoặc thông tin nhạy cảm trước đưa vào báo cáo.

## 1. Điều hướng và cách phân loại

- Social có năm tab: Feed, Reels, Inbox, Search, ProfileFragment. Notifications là màn riêng/header. ProfileFragment không đồng nhất ProfileActivity.
- Admin là shell riêng sau RoleResolution, không tab thứ sáu. Moderator có màn scoped review từ Settings.
- Một số màn dựng bằng Java/Forms, không lấy XML trùng tên nhưng không được inflate làm ảnh giao diện thật.
- Registered-only/helper là SOURCE PRESENT, không đưa vào bộ ảnh tính năng đang hoạt động nếu không tìm được đường mở. Không đưa Community Groups/Friendship/Memories/Marketplace hoặc nút ghi voice bị ẩn vào hình demo current product.

## 2. Màn hình Activity trong Manifest hiện tại

Bảng tự kiểm kê cuối file cung cấp tên Activity/source, layout references thực sự và caller .class. Các layout item/helper không phải màn riêng. Bảng nghiệp vụ dưới đây xác định đường mở, thao tác và đích tiếp theo.

| Màn hình / Activity | Entry và chức năng chính | Thao tác/đích kế tiếp | Nhóm / trạng thái |
|---|---|---|---|
| LoginActivity | Launcher, email/password, eye toggle | Login→RoleResolution; register/forgot; phiên lưu→restore | Auth integrated |
| RegisterActivity | Login→đăng ký form Java | Name/email/password+confirm/date/gender/location→confirmation/onboarding | Auth integrated; XML register cũ không làm nguồn UI |
| EmailConfirmationActivity | Signup chưa xác minh | Resend/mở email/callback→role route | Auth integrated, delivery chưa verified |
| AuthCallbackActivity | External meeto://auth/callback | PKCE/hash/validated token flow→confirmation/recovery/role | Auth exported gate; không screenshot token URL |
| ForgotPasswordActivity | Login | Send recover link→callback→password form | Auth integrated, Forms Java |
| RoleResolutionActivity | Login/restore/callback | Profile/onboarding/capabilities→Main/Admin hoặc retry/logout | Auth gate Java, không Social tab |
| PeopleSuggestionsActivity | First social setup | Search/select follow/skip→role route | Auth/onboarding integrated, không lặp mỗi login |
| MainActivity | Verified Social route | Bottom tabs/drawer/header/create/notifications | Social shell integrated |
| ProfileActivity | Profile card/avatar/search/mentions/drawer header | Posts/Reels/About, followers/following, editor/contact/report, featured viewer | Social integrated |
| ProfileEditActivity | Own profile/Settings/onboarding assets-only | Avatar/cover/name/nickname/bio/field audiences→save/back | Social integrated |
| FollowListActivity | Follower/following count của profile | Refresh/page/follow controls→Profile; private-other denied | Social integrated,080001 Cloud unknown |
| FeaturedAlbumViewerActivity | Featured album cover | Full-screen progress5s/left-right/hold/pause/album next/close | Social integrated, Java UI; không Story expiry |
| CreatePostActivity | Feed/header/drawer | Text/media/audience/tags/share-to-Reels→editor/publish | Social integrated |
| PostDetailsActivity | Feed/share/saved/search/notification/link | Authorized post/media/comments/reactions/save/share | Social integrated |
| PostMediaViewerActivity | Click photo trong post | Swipe/zoom/count/caption/interactions/download→comments/menu | Social integrated; ảnh thật theo ACL |
| PostPhoto/Video: VideoPlayerActivity | Media action, video attachment, ChatImageViewer delegation | Play/pause/seek/errors/close | Shared player integrated |
| ReelsCameraActivity | Drawer/create Reel | Camera/gallery/finalized video→CreateReel | Social integrated, activity_reels_camera |
| CreateReelActivity | ReelsCamera hoặc CreatePost editor-only | Trim/mute/cover/music/preview/export→publish hoặc result về composer | Social integrated, activity_reel_create |
| music.OpenverseMusicPickerActivity | Story/Reel add music | Catalog/search/listen/download/select→Editor/creator | Social integrated, hiện thư viện Meeto |
| StoryCreateActivity | Drawer/Feed add Story | Gallery/camera/canvas/effect/text/music/audience→export/publish | Social integrated |
| StoryCameraActivity | Story creator camera | Capture photo/video/mic/front→finalized file trở về creator | Social integrated, Java CameraX UI |
| StoryViewActivity | Story row/avatar/notification | Progress/pause/next/previous/like/reply/viewers/menu | Social integrated, activity_story_view |
| ChatActivity | Inbox/profile contact/group invitation/notification | Text/media/reply/edit/recall/hide/forward/share, header→details | Social integrated, voice-record entry disabled |
| ConversationDetailsActivity | Chat header/avatar | Theme/nickname/group title/avatar/members/media/posts/search/mute/archive/leave | Messenger integrated |
| ConversationSearchActivity | Chat details | Search messages/page→jump vào chat result | Messenger integrated |
| GroupMembersActivity | Group details | List/invite/roles/remove/leave guards→profile/details | Messenger group only |
| SharedPostsActivity | Chat details | Shared post cards→reauthorized PostDetails | Messenger integrated |
| SharedMediaGalleryActivity | Chat details | Media grid→image/video viewer | Messenger integrated, Java layout |
| ChatImageViewerActivity | Chat/album image reference | Swipe/pinch/double-tap; video redirect→VideoPlayer | Shared viewer integrated |
| RequestCenterActivity | Settings/Inbox/request notification UI | Mode follow/message/group invitation tùy entry→accept/reject/cancel/contact | Social integrated, activity_request_center |
| NotificationsActivity | Main/Feed header | All/unread/read-one/all/cursor→target screen | Social integrated |
| SavedPostsActivity | Drawer/Profile shortcut | Saved list/actions→PostDetails/media/comments | Social integrated |
| SettingsActivity | Drawer/Profile shortcut | Account/profile/privacy/theme/notifications/tags/requests/moderation/delete request/logout | Social integrated |
| ChangePasswordActivity | Settings riêng | Current/new/confirm/eye→confirm hoặc back | Social integrated, Java Forms |
| TagManagementActivity | Settings connection card | Policy/review/pending/accepted tags/approve/reject/remove | Social integrated; listing transaction risk |
| ReportContentActivity | Viewer/profile/report menu | Reason/details→submit case→return | Social integrated |
| SharedPostLinkActivity | External meeto://post | Resolve session/role/source→PostDetails | Exported router; không source-trust direct media |
| AdminPanelActivity | RoleResolution(system_admin) | Overview/reports/management/audit, detail/actions/evidence, refresh/logout | Admin integrated, header back đã bỏ |
| ModerationDashboardActivity | Settings có reviewer capability | Assigned reports/claim/decision/audit/evidence | Scoped Admin integrated, không full system_admin |
| ProfileAlbumsActivity | Còn Manifest/source; không tìm caller mở ngoài class | Generic album CRUD/grid/viewer trong source | Registered-only/entry NOT VERIFIED, không lẫn featured mới |
| UserPostsActivity | Còn Manifest/source; không tìm caller mở ngoài class | Generic posts list trong code | Registered-only/entry NOT VERIFIED |
| SearchActivity | Còn standalone wrapper Manifest; current Main dùng SearchFragment trực tiếp | SearchFragment host nếu mở | Registered-only entry chưa thấy, không tính thêm một search feature |
| ProfilePhotoViewerActivity | Static intent helper được gọi nhưng trả Intent ChatImageViewerActivity | Compatibility viewer helper | Không chứng minh own onCreate đang là route hiện hành |

## 3. Fragment và overlay có entry thật

| View | XML/source | Mở từ / thao tác |
|---|---|---|
| FeedFragment | fragment_feed / FeedAdapter / StoryRings | Tab Trang chủ, refresh/page/post/story/create. |
| ReelsFragment | fragment_reels + Reel controls | Tab Reels, feed player/search/owner-viewer menus. |
| InboxFragment | fragment_inbox / conversation rows | Tab Tin nhắn, refresh/search/archive/group/request/chat. |
| SearchFragment | fragment_search / SearchPostAdapter | Tab Tìm kiếm, Posts/Users tabs/query/clear/load-more; SearchActivity wrapper không bắt buộc. |
| ProfileFragment | fragment_profile | Tab Trang cá nhân, card→ProfileActivity/Saved/Settings/logout. |
| CommentsBottomSheetFragment | Fragment binding layout xác nhận trong index dưới | Post/Reel/notification, roots/replies/@/edit/delete/react/page. |
| StoryViewersSheet | Fragment + Java/layout source | Owner Story menu/viewers, pagination/profile. |
| StoryPrivacySheet | Fragment + Java/layout source | Owner Story audience edit/save/cancel. |
| Music Editor / MiniPlayer | music/OpenverseMusicEditor/MusicMiniPlayer | Creator/Picker, range/offset/gains/preview; lỗi mixed preview cần screenshot chẩn đoán, không giả PASS. |
| PostTagPicker/@ popup | ui/PostTagPicker/MentionComposer | Composer/comment, avatar/name/username/follow candidates; chọn nhiều tags hoặc insert span. |
| Forward picker | ui/ForwardMessagePicker + item_forward_target.xml | Long-press text→checkbox danh sách avatar/name, search, send/partial retry. |
| Featured editor | ui/FeaturedAlbumsUi | Profile+, tên/grid multi-select/count, load more, rename/gỡ owner long-press. |
| Admin detail/action/evidence | AdminReportActions/AdminUi/AdminPanel | Allowed-actions buttons rõ, reason/confirm/scoped evidence/audit. |
| Settings option dialogs | card_settings_options/item_settings_option | Theme/privacy/location/account-info/notifications/blocked/deletion request. |

## 4. Danh sách ảnh chụp đề xuất (chỉ chụp thật sau audit)

| ID / tên hình đề xuất | Vị trí và nội dung cần xuất hiện | Trạng thái/nhánh nên có |
|---|---|---|
| UI-01 — Đăng nhập Meeto | Email/password masked + eye toggle, login/register/forgot | Default và validation lỗi, không hiện mật khẩu thật. |
| UI-02 — Đăng ký và xác minh | Form thực Java + confirmation/resend | Chỉ ghi gửi/verify thành công nếu owner đã làm thực. |
| UI-03 — Phân luồng vai trò | Gate retry/loading và Admin/Social đích | Không dùng screenshot để suy role security toàn hệ thống. |
| UI-04 — Trang chủ và năm tab | Feed/story row/header/create/bottom nav | Ảnh full shell rõ đúng5 tab. |
| UI-05 — Đăng bài có media | Caption/ảnh/audience/tags và video Share-to-Reels toggle khi đúng điều kiện | Draft; lỗi upload nếu có. |
| UI-06 — Chi tiết/tương tác bài | Reactions/comments/share/save, owner/viewer menus khác nhau | Nguồn có quyền xem. |
| UI-07 — Cây bình luận và nhắc tên | Root/reply/load more, @ suggestions với keyboard | Tên/avatar/span, không chỉ text @ tự gõ. |
| UI-08 — Xem ảnh bài viết | Fullscreen/caption/count/zoom/interactions | Ảnh thật, index và menu nguồn. |
| UI-09 — Reels viewer | Video playing, caption/music attribution, counts/actions/search | Có ảnh loading/error để phân biệt nếu phát lỗi. |
| UI-10 — Quay/chỉnh Reel | Camera/gallery/trim/mute/cover/next | Editor-only từ CreatePost nếu cần hình phụ. |
| UI-11 — Thư viện nhạc Meeto | Catalog/search/play/download/cache state | Không dùng giao diện Openverse cũ để minh họa nguồn nhạc mới. |
| UI-12 — Chỉnh bản phối | Offset/music/source volume/mute/preview | Nếu lỗi preview còn, ghi đúng lỗi chưa retest, không minh họa thành công giả. |
| UI-13 — Tạo Story | Canvas text/draw/filter/crop/music/audience | Export/validation error hoặc success theo owner thực. |
| UI-14 — Story viewer | Progress/like/reply/pause và owner viewers/audience | Hình riêng cho viewer/owner nếu cần. |
| UI-15 — Inbox/Archive | Avatars/names/read status/search/pull refresh/archive | Archive cập nhật ngay và rollback/error nếu gặp. |
| UI-16 — Chat riêng và replies | Tin text/media/reply/read markers/details entry | Dùng hai tài khoản thử nếu mô tả giao nhận thực. |
| UI-17 — Chat nhóm/chi tiết | Group name/avatar/member roles/invitation/theme/nickname | Không gọi Community Groups. |
| UI-18 — Chuyển tiếp văn bản | Picker bo góc, avatar/name/checkbox phải và nút gửi | Selected nhiều nơi; partial failure/retry nếu kiểm được. |
| UI-19 — Media và bài được chia sẻ | Details shared gallery/posts, click reauthorize source | Không lộ ảnh private ngoài quyền. |
| UI-20 — Tìm kiếm | Users by name/nickname và Posts/Explore/Reels search | Query/result/empty/error đúng tình huống. |
| UI-21 — Hồ sơ cá nhân | Avatar/name/@nickname/bio, stats, Posts/Reels/About selected state | Không crop bỏ quyền xem/owner distinction quan trọng. |
| UI-22 — Followers/Following | Danh sách avatar/name/action hiện hành | Public view và private-other denied theo đúng account thử. |
| UI-23 — Chỉnh hồ sơ | Nhãn Biệt danh, Tiểu sử riêng, fields/audiences | Có hình save/version error nếu cần, che thông tin thật. |
| UI-24 — Ảnh nổi bật/album | Cover bo góc/name/+N, editor multi-select/rename | Duplicate exact-file check chỉ ghi verified nếu owner đã thử. |
| UI-25 — Slideshow album | Fullscreen progress/album-name/index/pause/close | Chuyển giữa hai album thật, không screenshot Story thay thế. |
| UI-26 — Notifications | All/unread/count/read-all, target cards | Không dùng OS notification giả thay in-app. |
| UI-27 — Settings | Ba mục đầu đồng bộ card, account/profile/privacy/theme/security | Light/dark nếu owner thực kiểm. |
| UI-28 — Đổi mật khẩu | Current/new/confirm/eye/confirm/back | Tất cả password masked, không lưu ảnh secret. |
| UI-29 — Quyền gắn thẻ/yêu cầu | Policy everyone/review; requests accept/reject; block list | Default everyone chỉ tài khoản mới, không tuyên bố reset toàn bộ account. |
| UI-30 — Gửi báo cáo | Target/reason/details/send | Nội dung report ngoài Admin phải khớp details khi có quyền. |
| UI-31 — Admin Overview | KPIs/recent reports/actions/account requests | Không header back, refresh/logout; che email thật. |
| UI-32 — Admin Reports | State filter/search/cards có report details | Open/assigned/resolved/dismissed tùy dữ liệu thật. |
| UI-33 — Admin case/evidence | Scope/allowed actions/evidence/video/claim | Thiếu evidence phải giữ state thiếu, không ảnh minh họa thay. |
| UI-34 — Admin decision | Action rõ/reason/confirm/state-conflict/receipt | Chụp success chỉ sau owner test được phép, không tạo mutation để chụp trong audit. |
| UI-35 — Quản lý tài khoản/nội dung | User/content list/subject detail/protected guards | Không đồng nhất request deletion với xóa hoàn tất. |
| UI-36 — Admin audit | Nhãn Việt/actor/time/old→new/reason | Fields cũ thiếu giữ unknown, không sửa ảnh để đẹp. |
| UI-37 — Scoped moderation | Màn Moderator từ Settings | Phân biệt full Admin shell, không vẽ mọi quyền giống nhau. |

Không cần chụp mọi trạng thái vào một ảnh; có thể tách mỗi ID thành hình con. Số hình/sơ đồ không giới hạn. Số liệu động/tài khoản/thời gian chỉ là fixture thật tại lúc owner chụp, không hard-code thành chỉ tiêu hệ thống.

## 5. Những hình không nên đưa như tính năng hoàn thành

- Friendship, Community Groups, Memories, Marketplace đã removed; nếu nhắc lịch sử thì phải ghi retired, không nằm flow demo hiện hành.
- Voice recording/send disabled; không dùng preview local lịch sử như bằng chứng voice E2E.
- Màn chỉ đăng ký hoặc XML không inflate; các template/list item không phải Activity độc lập.
- Cloud worker/cron cần ảnh owner có bằng chứng, không tạo dashboard giả hoặc hiển thị key/service account thật. Audit này không đăng nhập Cloud để lấy ảnh.
- Music mixed preview và Story output lỗi chưa owner retest; không viết caption “đã sửa thành công” từ source mới.

## Phụ lục — Registry Manifest, layout references và caller tĩnh

Caller `.class` là evidence tham chiếu, không tự động chứng minh đường đi lúc runtime. Static intent helpers/callback navigation khác được mô tả ở bảng nghiệp vụ; thiếu caller không chứng minh file không có giá trị lịch sử. Programmatic có thể dùng Forms/helper, không mặc định thiếu UI.
| Manifest Activity / exported | Source | Layout references trong source (gồm item/helper) | Caller .class ngoài chính file |
|---|---|---|---|
| `.LoginActivity` / true | `app/src/main/java/com/example/meetomxh/LoginActivity.java` | `app/src/main/res/layout/activity_login.xml` | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java`; `app/src/main/java/com/example/meetomxh/AuthCallbackActivity.java`; `app/src/main/java/com/example/meetomxh/EmailConfirmationActivity.java`; `app/src/main/java/com/example/meetomxh/ForgotPasswordActivity.java`; `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/MeetoApplication.java`; `app/src/main/java/com/example/meetomxh/ModerationDashboardActivity.java`; `app/src/main/java/com/example/meetomxh/PeopleSuggestionsActivity.java`; `app/src/main/java/com/example/meetomxh/RegisterActivity.java`; `app/src/main/java/com/example/meetomxh/RoleResolutionActivity.java`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java`; `app/src/main/java/com/example/meetomxh/fragments/ProfileFragment.java` |
| `.RoleResolutionActivity` / false | `app/src/main/java/com/example/meetomxh/RoleResolutionActivity.java` | Dựng Java/Forms hoặc router; đọc source | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java`; `app/src/main/java/com/example/meetomxh/LoginActivity.java`; `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/ModerationDashboardActivity.java`; `app/src/main/java/com/example/meetomxh/PeopleSuggestionsActivity.java`; `app/src/main/java/com/example/meetomxh/RegisterActivity.java` |
| `.RegisterActivity` / false | `app/src/main/java/com/example/meetomxh/RegisterActivity.java` | Dựng Java/Forms hoặc router; đọc source | `app/src/main/java/com/example/meetomxh/LoginActivity.java` |
| `.ForgotPasswordActivity` / false | `app/src/main/java/com/example/meetomxh/ForgotPasswordActivity.java` | Dựng Java/Forms hoặc router; đọc source | `app/src/main/java/com/example/meetomxh/AuthCallbackActivity.java`; `app/src/main/java/com/example/meetomxh/LoginActivity.java` |
| `.MainActivity` / false | `app/src/main/java/com/example/meetomxh/MainActivity.java` | `app/src/main/res/layout/activity_main.xml` | `app/src/main/java/com/example/meetomxh/PostDetailsActivity.java`; `app/src/main/java/com/example/meetomxh/RoleResolutionActivity.java`; `app/src/main/java/com/example/meetomxh/supabase/PostDetails.java` |
| `.PostDetailsActivity` / false | `app/src/main/java/com/example/meetomxh/PostDetailsActivity.java` | `app/src/main/res/layout/activity_post_details.xml` | `app/src/main/java/com/example/meetomxh/supabase/PostDetails.java` |
| `.PostMediaViewerActivity` / false | `app/src/main/java/com/example/meetomxh/PostMediaViewerActivity.java` | `app/src/main/res/layout/activity_post_media_viewer.xml` | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.SearchActivity` / false | `app/src/main/java/com/example/meetomxh/SearchActivity.java` | `app/src/main/res/layout/activity_search.xml` | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.ProfileActivity` / false | `app/src/main/java/com/example/meetomxh/ProfileActivity.java` | `app/src/main/res/layout/activity_profile.xml`; `app/src/main/res/layout/item_profile_reel.xml` | `app/src/main/java/com/example/meetomxh/ChatActivity.java`; `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java`; `app/src/main/java/com/example/meetomxh/FollowListActivity.java`; `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/NotificationsActivity.java`; `app/src/main/java/com/example/meetomxh/PostDetailsActivity.java`; `app/src/main/java/com/example/meetomxh/SavedPostsActivity.java`; `app/src/main/java/com/example/meetomxh/adapters/FeedAdapter.java`; `app/src/main/java/com/example/meetomxh/fragments/FeedFragment.java`; `app/src/main/java/com/example/meetomxh/fragments/ProfileFragment.java`; `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java`; `app/src/main/java/com/example/meetomxh/fragments/SearchFragment.java`; `app/src/main/java/com/example/meetomxh/fragments/StoryViewersSheet.java`; `app/src/main/java/com/example/meetomxh/ui/MentionText.java` |
| `.ProfileEditActivity` / false | `app/src/main/java/com/example/meetomxh/ProfileEditActivity.java` | `app/src/main/res/layout/activity_profile_edit.xml` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java`; `app/src/main/java/com/example/meetomxh/RegisterActivity.java`; `app/src/main/java/com/example/meetomxh/RoleResolutionActivity.java`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java` |
| `.UserPostsActivity` / false | `app/src/main/java/com/example/meetomxh/UserPostsActivity.java` | Dựng Java/Forms hoặc router; đọc source | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.EmailConfirmationActivity` / false | `app/src/main/java/com/example/meetomxh/EmailConfirmationActivity.java` | `app/src/main/res/layout/activity_email_confirmation.xml` | `app/src/main/java/com/example/meetomxh/AuthCallbackActivity.java`; `app/src/main/java/com/example/meetomxh/RegisterActivity.java` |
| `.PeopleSuggestionsActivity` / false | `app/src/main/java/com/example/meetomxh/PeopleSuggestionsActivity.java` | Dựng Java/Forms hoặc router; đọc source | `app/src/main/java/com/example/meetomxh/ProfileEditActivity.java` |
| `.AuthCallbackActivity` / true | `app/src/main/java/com/example/meetomxh/AuthCallbackActivity.java` | Dựng Java/Forms hoặc router; đọc source | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.SharedPostLinkActivity` / true | `app/src/main/java/com/example/meetomxh/SharedPostLinkActivity.java` | Dựng Java/Forms hoặc router; đọc source | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.ChatActivity` / false | `app/src/main/java/com/example/meetomxh/ChatActivity.java` | `app/src/main/res/layout/activity_chat.xml` | `app/src/main/java/com/example/meetomxh/ConversationSearchActivity.java`; `app/src/main/java/com/example/meetomxh/NotificationsActivity.java`; `app/src/main/java/com/example/meetomxh/RequestCenterActivity.java`; `app/src/main/java/com/example/meetomxh/fragments/InboxFragment.java`; `app/src/main/java/com/example/meetomxh/supabase/ChatControls.java`; `app/src/main/java/com/example/meetomxh/supabase/SocialActions.java` |
| `.ConversationDetailsActivity` / false | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java` | `app/src/main/res/layout/activity_conversation_details.xml` | `app/src/main/java/com/example/meetomxh/ChatActivity.java` |
| `.ConversationSearchActivity` / false | `app/src/main/java/com/example/meetomxh/ConversationSearchActivity.java` | `app/src/main/res/layout/activity_conversation_search.xml`; `app/src/main/res/layout/item_conversation_search_result.xml` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java` |
| `.SharedPostsActivity` / false | `app/src/main/java/com/example/meetomxh/SharedPostsActivity.java` | `app/src/main/res/layout/activity_shared_posts.xml`; `app/src/main/res/layout/item_shared_post.xml` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java` |
| `.GroupMembersActivity` / false | `app/src/main/java/com/example/meetomxh/GroupMembersActivity.java` | `app/src/main/res/layout/activity_group_members.xml`; `app/src/main/res/layout/item_group_member.xml` | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java` |
| `.SharedMediaGalleryActivity` / false | `app/src/main/java/com/example/meetomxh/SharedMediaGalleryActivity.java` | Dựng Java/Forms hoặc router; đọc source | `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java` |
| `.ChatImageViewerActivity` / false | `app/src/main/java/com/example/meetomxh/ChatImageViewerActivity.java` | `app/src/main/res/layout/activity_chat_image_viewer.xml` | `app/src/main/java/com/example/meetomxh/ChatActivity.java`; `app/src/main/java/com/example/meetomxh/ProfilePhotoViewerActivity.java`; `app/src/main/java/com/example/meetomxh/SharedMediaGalleryActivity.java`; `app/src/main/java/com/example/meetomxh/supabase/AdminReportActions.java` |
| `.VideoPlayerActivity` / false | `app/src/main/java/com/example/meetomxh/VideoPlayerActivity.java` | `app/src/main/res/layout/activity_video_player.xml` | `app/src/main/java/com/example/meetomxh/ChatImageViewerActivity.java`; `app/src/main/java/com/example/meetomxh/SharedMediaGalleryActivity.java`; `app/src/main/java/com/example/meetomxh/adapters/ChatMessageAdapter.java`; `app/src/main/java/com/example/meetomxh/supabase/AdminReportActions.java` |
| `.ReelsCameraActivity` / false | `app/src/main/java/com/example/meetomxh/ReelsCameraActivity.java` | `app/src/main/res/layout/activity_reels_camera.xml` | `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java` |
| `.CreateReelActivity` / false | `app/src/main/java/com/example/meetomxh/CreateReelActivity.java` | `app/src/main/res/layout/activity_reel_create.xml`; `android.R.layout.simple_spinner_dropdown_item` (framework) | `app/src/main/java/com/example/meetomxh/CreatePostActivity.java`; `app/src/main/java/com/example/meetomxh/ReelsCameraActivity.java` |
| `.music.OpenverseMusicPickerActivity` / false | `app/src/main/java/com/example/meetomxh/music/OpenverseMusicPickerActivity.java` | `app/src/main/res/layout/activity_openverse_music_picker.xml`; `app/src/main/res/layout/item_openverse_music.xml` | `app/src/main/java/com/example/meetomxh/CreateReelActivity.java`; `app/src/main/java/com/example/meetomxh/StoryCreateActivity.java` |
| `.ProfileAlbumsActivity` / false | `app/src/main/java/com/example/meetomxh/ProfileAlbumsActivity.java` | Dựng Java/Forms hoặc router; đọc source | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.ProfilePhotoViewerActivity` / false | `app/src/main/java/com/example/meetomxh/ProfilePhotoViewerActivity.java` | Dựng Java/Forms hoặc router; đọc source | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.FeaturedAlbumViewerActivity` / false | `app/src/main/java/com/example/meetomxh/FeaturedAlbumViewerActivity.java` | Dựng Java/Forms hoặc router; đọc source | Chưa tìm .class caller ngoài file; xem static helper/external route |
| `.CreatePostActivity` / false | `app/src/main/java/com/example/meetomxh/CreatePostActivity.java` | `app/src/main/res/layout/activity_create_post.xml` | `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/PostMediaViewerActivity.java`; `app/src/main/java/com/example/meetomxh/adapters/FeedAdapter.java`; `app/src/main/java/com/example/meetomxh/fragments/FeedFragment.java`; `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java` |
| `.SettingsActivity` / false | `app/src/main/java/com/example/meetomxh/SettingsActivity.java` | `app/src/main/res/layout/activity_settings.xml`; `app/src/main/res/layout/card_settings_options.xml` | `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/fragments/ProfileFragment.java` |
| `.ChangePasswordActivity` / false | `app/src/main/java/com/example/meetomxh/ChangePasswordActivity.java` | Dựng Java/Forms hoặc router; đọc source | `app/src/main/java/com/example/meetomxh/SettingsActivity.java` |
| `.TagManagementActivity` / false | `app/src/main/java/com/example/meetomxh/TagManagementActivity.java` | `app/src/main/res/layout/activity_tag_management.xml`; `app/src/main/res/layout/item_tagged_post.xml` | `app/src/main/java/com/example/meetomxh/SettingsActivity.java` |
| `.StoryCreateActivity` / false | `app/src/main/java/com/example/meetomxh/StoryCreateActivity.java` | `app/src/main/res/layout/activity_story_create.xml`; `android.R.layout.simple_spinner_dropdown_item` (framework) | `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/adapters/StoryAdapter.java` |
| `.StoryCameraActivity` / false | `app/src/main/java/com/example/meetomxh/StoryCameraActivity.java` | Dựng Java/Forms hoặc router; đọc source | `app/src/main/java/com/example/meetomxh/StoryCreateActivity.java` |
| `.StoryViewActivity` / false | `app/src/main/java/com/example/meetomxh/StoryViewActivity.java` | `app/src/main/res/layout/activity_story_view.xml` | `app/src/main/java/com/example/meetomxh/NotificationsActivity.java`; `app/src/main/java/com/example/meetomxh/adapters/ChatMessageAdapter.java`; `app/src/main/java/com/example/meetomxh/ui/StoryNavigation.java` |
| `.NotificationsActivity` / false | `app/src/main/java/com/example/meetomxh/NotificationsActivity.java` | `app/src/main/res/layout/activity_notifications.xml` | `app/src/main/java/com/example/meetomxh/MainActivity.java` |
| `.SavedPostsActivity` / false | `app/src/main/java/com/example/meetomxh/SavedPostsActivity.java` | `app/src/main/res/layout/activity_saved_posts.xml` | `app/src/main/java/com/example/meetomxh/MainActivity.java`; `app/src/main/java/com/example/meetomxh/fragments/ProfileFragment.java` |
| `.RequestCenterActivity` / false | `app/src/main/java/com/example/meetomxh/RequestCenterActivity.java` | `app/src/main/res/layout/activity_request_center.xml`; `app/src/main/res/layout/item_request_center.xml` | `app/src/main/java/com/example/meetomxh/NotificationsActivity.java`; `app/src/main/java/com/example/meetomxh/SettingsActivity.java`; `app/src/main/java/com/example/meetomxh/fragments/InboxFragment.java` |
| `.FollowListActivity` / false | `app/src/main/java/com/example/meetomxh/FollowListActivity.java` | `app/src/main/res/layout/activity_follow_list.xml`; `app/src/main/res/layout/item_follow_person.xml` | `app/src/main/java/com/example/meetomxh/ProfileActivity.java` |
| `.ReportContentActivity` / false | `app/src/main/java/com/example/meetomxh/ReportContentActivity.java` | `app/src/main/res/layout/activity_report_content.xml` | `app/src/main/java/com/example/meetomxh/ChatActivity.java`; `app/src/main/java/com/example/meetomxh/ConversationDetailsActivity.java`; `app/src/main/java/com/example/meetomxh/ProfileActivity.java`; `app/src/main/java/com/example/meetomxh/RequestCenterActivity.java`; `app/src/main/java/com/example/meetomxh/StoryViewActivity.java`; `app/src/main/java/com/example/meetomxh/adapters/FeedAdapter.java`; `app/src/main/java/com/example/meetomxh/adapters/StoryAdapter.java`; `app/src/main/java/com/example/meetomxh/fragments/CommentsBottomSheetFragment.java`; `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java` |
| `.ModerationDashboardActivity` / false | `app/src/main/java/com/example/meetomxh/ModerationDashboardActivity.java` | `app/src/main/res/layout/activity_moderation_dashboard.xml`; `app/src/main/res/layout/item_admin_audit.xml`; `app/src/main/res/layout/item_admin_report.xml` | `app/src/main/java/com/example/meetomxh/SettingsActivity.java` |
| `.AdminPanelActivity` / false | `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java` | `app/src/main/res/layout/activity_admin_panel.xml`; `app/src/main/res/layout/item_admin_audit.xml`; `app/src/main/res/layout/item_admin_content.xml`; `app/src/main/res/layout/item_admin_dashboard_metric.xml`; `app/src/main/res/layout/item_admin_report.xml`; `app/src/main/res/layout/item_admin_user.xml` | `app/src/main/java/com/example/meetomxh/RoleResolutionActivity.java` |

Số Activity declarations trong Manifest source: 43. Không phải số màn có entry thực tế; danh mục nghiệp vụ phía trên quyết định trạng thái.

### Fragment layout references

| Fragment | Source | Layout/binding thực trong source |
|---|---|---|
| CommentsBottomSheetFragment | `app/src/main/java/com/example/meetomxh/fragments/CommentsBottomSheetFragment.java` | layout_comments_bottom_sheet |
| FeedFragment | `app/src/main/java/com/example/meetomxh/fragments/FeedFragment.java` | fragment_feed |
| InboxFragment | `app/src/main/java/com/example/meetomxh/fragments/InboxFragment.java` | fragment_inbox |
| ProfileFragment | `app/src/main/java/com/example/meetomxh/fragments/ProfileFragment.java` | fragment_profile |
| ReelsFragment | `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java` | fragment_reels, item_reel, android.R.layout.simple_spinner_dropdown_item (framework) |
| SearchFragment | `app/src/main/java/com/example/meetomxh/fragments/SearchFragment.java` | fragment_search, item_inbox |
| StoryPrivacySheet | `app/src/main/java/com/example/meetomxh/fragments/StoryPrivacySheet.java` |  |
| StoryViewersSheet | `app/src/main/java/com/example/meetomxh/fragments/StoryViewersSheet.java` |  |
