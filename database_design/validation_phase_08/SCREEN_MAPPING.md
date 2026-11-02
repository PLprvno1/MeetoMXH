# Android cutover 08 — bản đồ thực thi

Workspace F:\MeetoMXH; Java/XML; Supabase local, không Cloud. Mapping từ source Android trước chuyển đổi (archive `android-before.zip`) đến source đích. Các evidence A/B/C cũ giữ nguyên.

| Màn hình/luồng | Class đích | Legacy được thay | Supabase / trạng thái | Bằng chứng 08 |
|---|---|---|---|---|
| Đăng nhập, restore/logout | LoginActivity, SessionManager | Firebase Auth/currentUser | GoTrue password/refresh/logout; AES-GCM Android Keystore; clear task/cache/outbox | core, restore, expiry, recovery |
| Đăng ký, xác minh, onboarding | RegisterActivity, AuthCallbackActivity | Firebase registration/phone wizard | Email PKCE callback; bootstrap_profile, complete_onboarding; phone OTP hiển thị chưa hỗ trợ | core UI + Mailpit thật |
| Quên/đặt lại mật khẩu | ForgotPasswordActivity | Firebase reset email | GoTrue recover PKCE, PUT user, logout, login mật khẩu mới | recovery UI |
| Profile cá nhân/người khác | ProfileActivity, ProfileFragment, ProfileClient | Firestore users, public image URL | get_my_profile/get_profile, version, private fields theo ACL; chặn/bỏ chặn | core UI, media-messenger, RPC block |
| Sửa profile | ProfileEditActivity | Thiếu editor hoàn chỉnh/Firestore fields | update_profile/update_profile_private; avatar/cover ready assets | core UI + JPEG/WebP app client |
| Feed | FeedFragment, FeedViewModel, FeedRepository, FeedAdapter | Firestore posts/listener, dummy Stories | get_feed keyset, refresh/error/empty/retry; persisted counts/my_reaction | core UI/create; backend read đối soát |
| Bài viết người dùng / share source | UserPostsActivity, PostDetails | Chưa có server projection phù hợp | get_user_posts keyset; get_post mỗi lần mở source, unavailable theo quyền hiện tại | API integration; UI đường vào đã triển khai, chưa có riêng test UI |
| Tạo/sửa/xóa post | CreatePostActivity, FeedAdapter | Firestore writes, client timestamps/owner | create_post/edit_post/delete_post; desired audience/assets, expected version, persisted request UUID | core UI create; media-messenger; edit/delete qua contract, chưa riêng E2E UI |
| Reaction/share | FeedRepository, FeedAdapter | Firestore counters/like array | set_post_reaction desired state; share_post immutable UUID; không client counter DML | core app-client integration |
| Comment/reply/thread | CommentsBottomSheetFragment, CommentAdapter, CommentClient | Firestore comments/user joins | list_comments parent/keyset; get_profile author ACL; create/edit/delete version, tombstone | core app-client root/reply/edit/delete; component UI root/reply/delete/name PASS, edit chưa riêng tap UI |
| Friends/search/requests | FriendsFragment, FriendRepository, FriendAdapter | Firestore relationships/users | A search_profiles + 9 RPC B; canonical request_id/direction; không đọc graph người khác | core + media-messenger app-client cancel/decline/accept/unfriend/block |
| Picker/upload/download | SupabaseStorage, PrivateAssetLoader, ChatImageViewerActivity | Public bucket URL/anon bearer loader | private intent → JWT POST → local verifier → finalize → attach; authenticated GET/Range; cache off | PNG/JPEG/WebP/MP4, actual native video decode, revoke/range; chưa tự động tap system picker |
| Inbox/private chat | InboxFragment, ChatActivity, ChatRepository | Firebase snapshot listeners/chat summaries | get_or_create_private, inbox/history/search RPC; server seq/version/unread; polling/refetch | core UI send/read + actual Android Broadcast; history suite |
| Reply/recall/hide/receipts | ChatActivity, ChatControls, ChatMessageAdapter | Firestore message mutations/summary read timestamps | C RPCs; explicit device delivery/read; per-recipient receipt dialog; dynamic reply redaction | core, media-messenger; chưa riêng tap UI mọi menu |
| Group Messenger | ChatControls, InboxFragment | Không có backend group tương thích | create/member roles/rename/archive/leave; mutual-friend scope, history boundary | core + media-messenger RPC/JWT; group button/menu implemented, chưa riêng full UI automation |
| Reconnect/retry | RealtimeClient, Observer, MessageOutbox, RequestIds | Firebase offline/listener behavior | own-user private receive-only Broadcast; dedup hint, HTTP truth, seq catch-up + loaded-window refetch; encrypted pending payload/UUID | actual bridge outage/reconnect/outbox, history, unit dedup |
| Dark mode | ProfileFragment | Local preference | Giữ local UI preference, không giả backend setting | code retained; chưa UI regression riêng |
| Stories, video-feed, notifications/push, saved, memories, marketplace | MainActivity, ProfileFragment, FeedAdapter | Dummy/placeholder entry points | Hiển thị chưa hỗ trợ; không dummy content hay Firebase fallback. Video trong post/chat vẫn hỗ trợ | source scan; chưa có backend tương ứng |
| Community Groups/moderation/account-delete | Chưa có Android flow hoàn chỉnh | Không nâng scope thành backend mới | Không triển khai; group Messenger là C riêng | readiness gate |

Test APK thực hiện cả thao tác UI và gọi Java client từ instrumentation. Hai loại được ghi rõ ở trên; không coi mọi RPC integration là tap UI. Không cộng counts với evidence nhiệm vụ 05–07.
