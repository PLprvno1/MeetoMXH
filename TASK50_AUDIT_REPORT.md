# Meeto — Task 50: rà soát source và văn bản tiếng Việt

**Ngày:** 07/11/2026 · **Cách rà soát:** chỉ đọc source, không kết nối Supabase Cloud.

## Phạm vi và kết quả

Đã rà 9 nhóm: xác thực/đăng ký/onboarding; hồ sơ/follow/privacy/block; Feed/bài viết/tương tác; Reels/camera/xuất video; Stories; Messenger/nhóm/realtime; thông báo/tìm kiếm/kiểm duyệt; repository/RPC/SQL/RLS/Storage; điều hướng/lifecycle/loading/error. Inventory gồm **141 Java, 177 XML và 55 migration SQL** (373 file).

| Mức độ | Số phát hiện |
|---|---:|
| Critical | 0 |
| High | 0 |
| Medium | 3 |
| Low | 1 |

## Phát hiện

### AUD-001 — Medium · Tag Management / Supabase transaction

**Loại:** lỗi xác định từ source; chưa quan sát lỗi trên Cloud. **Vị trí:** supabase/migrations/20261103001200_tag_mentions.sql:565-569, 619-621; supabase/migrations/20261101000400_social.sql:4-7; transaction read-only evidence tại supabase/migrations/20261107000200_story_viewer_rpc_transactions.sql:2-4; client app/src/main/java/com/example/meetomxh/TagManagementActivity.java:111.

private.list_my_post_tags và public wrapper đều là STABLE; hàm khởi tạo actor bằng private.b_actor(), hàm này lấy transaction advisory lock. PostgREST chạy RPC STABLE trong transaction chỉ đọc, nên PostgreSQL có thể từ chối thao tác khóa (25006) khi tải danh sách bài viết gắn thẻ.

**Điều kiện/ảnh hưởng:** Task 36 và migration tag đã có trên Cloud; người dùng mở Tag Management. **Đề xuất:** migration tiến mới điều chỉnh volatility hoặc dùng guard đọc không khóa nhưng giữ nguyên auth/capability checks. **Trạng thái:** chưa sửa (logic/backend ngoài phạm vi được phép).

### AUD-002 — Medium · Authentication / đổi tài khoản

**Loại:** rủi ro xác định từ luồng source, tác động cần kiểm tra bằng hai tài khoản. **Vị trí:** app/src/main/java/com/example/meetomxh/supabase/SessionManager.java:15-17, 28; app/src/main/java/com/example/meetomxh/AuthCallbackActivity.java:25-30; app/src/main/AndroidManifest.xml:59; app/src/main/java/com/example/meetomxh/supabase/Api.java:11, 14; app/src/main/java/com/example/meetomxh/repositories/ReelsRepository.java:72.

SessionManager.accept() có thể thay UID/token nhưng chỉ clear() mới tăng generation. Activity callback được export nhận token từ intent rồi gọi accept() trực tiếp. Api.Job và cache repository dùng generation để loại công việc/cache của phiên cũ.

**Điều kiện/ảnh hưởng:** đang có phiên A, callback hợp lệ đăng nhập phiên B mà không đi qua clear(). Công việc đang chạy hoặc cache của A có thể vẫn được dùng sau khi UID đổi. **Đề xuất:** khi UID đổi, tăng generation và vô hiệu cache/job theo tài khoản; giữ nguyên generation khi chỉ refresh token cùng UID. **Trạng thái:** chưa sửa; chưa kiểm thử runtime.

### AUD-003 — Medium · Search media / giới hạn giải mã thumbnail

**Loại:** lỗi xác định khi hủy tác vụ. **Vị trí:** app/src/main/java/com/example/meetomxh/ui/MediaThumbnailLoader.java:58, 72; app/src/main/java/com/example/meetomxh/supabase/Api.java:11.

DECODERS.acquire() nằm trong try, nhưng finally luôn gọi DECODERS.release(). Nếu job bị hủy khi đang chờ semaphore, Future.cancel(true) có thể làm acquire() ném InterruptedException; permit chưa được lấy vẫn bị trả lại.

**Điều kiện/ảnh hưởng:** cuộn nhanh danh sách Reels/tìm kiếm khi cả hai decoder đang bận. Sau nhiều lần hủy, số permit có thể vượt 2 và tạo nhiều MediaMetadataRetriever đồng thời, tăng tải bộ nhớ/CPU. **Đề xuất:** chỉ release khi acquire thành công; thu hồi bitmap nếu tác vụ bị hủy sau decode. **Trạng thái:** chưa sửa; chưa đo trên thiết bị.

### AUD-004 — Low · Task 49 / thời gian khóa khi tạo index

**Loại:** rủi ro triển khai, phụ thuộc lưu lượng/kích thước Cloud. **Vị trí:** supabase/migrations/20261107000700_search_reels_posts.sql:2, 22-24.

Migration tạo ba GIN index thường trong cùng transaction. CREATE INDEX thường khóa ghi trên bảng trong lúc dựng; với bảng lớn hoặc đang có bài đăng, thao tác ghi có thể chờ migration.

**Điều kiện/ảnh hưởng:** deploy khi traffic hoặc số profile/post đã lớn. **Đề xuất:** owner xem kích thước bảng và thời điểm deploy; nếu cần, tách migration index dùng CREATE INDEX CONCURRENTLY ngoài transaction. **Trạng thái:** Cloud chưa xác minh; không deploy trong Task 50.

## Ưu tiên trước Task 48 Openverse

Không thấy blocker Critical/High được chứng minh. Nên xử lý AUD-001 trước khi dựa vào Tag Management; xem AUD-002 trước khi thêm luồng callback/đăng nhập mới; sửa AUD-003 trước khi tăng tải Reels Search. AUD-004 là quyết định triển khai.

## Migration và Cloud cần owner xác minh

Các migration 20261107000100–20261107000700 có trong source local; không có bằng chứng migration ledger/catalog Cloud. Owner cần xác minh trạng thái deploy, chữ ký/grant RPC và pg_trgm trong schema extensions, đặc biệt: Story viewer (20261107000200), Chat customization (20261107000300), onboarding suggestions (20261107000400), unified video (20261107000500), Story privacy (20261107000600) và Search/Reels Search (20261107000700). Không suy ra Cloud đã cập nhật từ file local.

## Chuỗi văn bản đã sửa

Đã sửa **52 chuỗi UI/accessibility trong 12 file**. Không đổi ID, placeholder, RPC, enum hay nội dung do người dùng tạo.

| File | Trước → sau |
|---|---|
| app/src/main/java/com/example/meetomxh/ProfileActivity.java | Pinned post updated → Đã cập nhật bài viết ghim; Loading Reels... → Đang tải Reels…; No Reels available for this profile. → Hồ sơ này chưa có Reel nào.; Could not load Reels. Tap to retry. → Không thể tải Reels. Chạm để thử lại.; Featured profile photo → Ảnh nổi bật trên hồ sơ; Remove this featured photo? → Gỡ ảnh nổi bật này?; Cancel → Hủy (2 nút); Remove → Gỡ; Create a personal post with a photo first. → Trước tiên, hãy tạo bài viết cá nhân có ảnh.; Photo · → Ảnh ·; Choose featured photo → Chọn ảnh nổi bật; ðŸ“Œ Pinned · → Bài viết đã ghim ·; Photo post → Bài viết có ảnh; Could not load posts. Pull down to retry. → Không thể tải bài viết. Kéo xuống để thử lại. |
| app/src/main/java/com/example/meetomxh/ChatActivity.java | Media reference is invalid. → Tệp đính kèm không hợp lệ.; Message failed · → Gửi tin nhắn thất bại ·; Tap to retry voice message → Chạm để gửi lại tin nhắn thoại; Tap to retry attachment → Chạm để gửi lại tệp; Tap to retry: → Chạm để gửi lại: |
| app/src/main/java/com/example/meetomxh/ProfileAlbumsActivity.java | Album photo → Ảnh trong album |
| app/src/main/java/com/example/meetomxh/ModerationDashboardActivity.java | Đang tải audit log… → Đang tải nhật ký kiểm duyệt…; Không thể tải audit log: → Không thể tải nhật ký kiểm duyệt:; Type: → Loại:; Target: → Đối tượng:; Reason: → Lý do:; Details: → Chi tiết:; State: → Trạng thái:; Report → Báo cáo; Close → Đóng; Claim report → Nhận xử lý; Review… → Xem xét…; Could not claim report: → Không thể nhận xử lý báo cáo:; Warn → Cảnh cáo; Dismiss → Bác bỏ |
| app/src/main/java/com/example/meetomxh/supabase/OnboardingSuggestionsClient.java | Máy chủ trả cursor gợi ý không hợp lệ. Hãy thử lại sau khi cập nhật RPC. → Máy chủ trả mốc trang gợi ý không hợp lệ. Hãy thử lại sau khi cập nhật.; Máy chủ thiếu RPC gợi ý mới. Owner cần triển khai migration hotfix gợi ý sau đăng ký. → Máy chủ chưa có chức năng gợi ý mới. Chủ dự án cần triển khai bản cập nhật gợi ý sau đăng ký.; RPC gợi ý đang dùng khóa trong transaction chỉ đọc. Owner cần triển khai migration hotfix. → Tính năng gợi ý chưa tương thích với máy chủ hiện tại. Chủ dự án cần cập nhật máy chủ. |
| app/src/main/java/com/example/meetomxh/supabase/StoryClient.java | Máy chủ thiếu RPC Story. Cần triển khai cập nhật Story trước khi xem. → Máy chủ chưa hỗ trợ xem Story. Cần cập nhật máy chủ trước khi xem.; Cloud chưa có chỉnh sửa quyền Story. Owner cần triển khai migration 20261107000600. → Máy chủ chưa hỗ trợ chỉnh sửa quyền Story. Chủ dự án cần triển khai bản cập nhật 20261107000600. |
| app/src/main/java/com/example/meetomxh/supabase/ChatCustomizationClient.java | Chat cần migration Task 44. Vui lòng thử lại sau khi máy chủ cập nhật. → Tùy chỉnh trò chuyện cần bản cập nhật Task 44. Vui lòng thử lại sau khi máy chủ được cập nhật.; Chat cần migration Task 44. Owner chưa triển khai bản cập nhật máy chủ. → Máy chủ chưa được cập nhật Task 44. Chủ dự án cần triển khai bản cập nhật trước khi dùng tùy chỉnh trò chuyện. |
| app/src/main/res/layout/activity_chat.xml | Stop recording → Dừng ghi âm; Cancel voice recording → Hủy ghi âm; Preview voice message → Nghe thử tin nhắn thoại; Delete voice recording → Xóa bản ghi âm; Send voice message → Gửi tin nhắn thoại; Hold to record a voice message → Nhấn giữ để ghi âm tin nhắn thoại |
| app/src/main/res/layout/item_chat_message.xml | Play voice message → Phát tin nhắn thoại |
| app/src/main/res/layout/item_post.xml | Video thumbnail → Ảnh xem trước video |
| app/src/main/res/layout/activity_reels_camera.xml | Camera Reel → Camera tạo Reel |
| app/src/main/res/layout/activity_report_content.xml | Báo cáo được ghi nhận trực quan trên thiết bị và sẵn sàng đồng bộ hàng đợi kiểm duyệt (Moderation Queue) khi kích hoạt backend. → Báo cáo được ghi nhận trên thiết bị và sẵn sàng đồng bộ với hàng đợi kiểm duyệt khi máy chủ được kích hoạt. |

## Giới hạn kiểm chứng

Không build, chạy unit test, emulator, E2E, upload hay gọi/deploy Cloud theo yêu cầu. Đã parse tĩnh **177/177 XML** thành công; không sửa resource ID hay placeholder. Chữ ký RPC được đối chiếu giữa client liên quan và migration local, nhưng chưa xác nhận catalog/RLS/Storage trên Cloud. Lifecycle, callback và playback vẫn cần owner kiểm tra trên thiết bị.
