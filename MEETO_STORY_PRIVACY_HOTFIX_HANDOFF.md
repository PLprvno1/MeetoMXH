# Story privacy hotfix — 07/11/2026

Chỉ triển khai source/rà soát tĩnh. Không build, test, emulator hoặc thao tác Cloud.

Files: `StoryViewActivity.java`, `supabase/StoryClient.java` (sửa); `fragments/StoryPrivacySheet.java`, `supabase/migrations/20261107000600_story_audience_edit.sql` và tài liệu này (thêm). Java nằm trong `app/src/main/java/com/example/meetomxh/`. Không sửa Story Editor, Reels, Feed hoặc RPC xóa.

Menu của chủ Story có Chỉnh sửa quyền riêng tư và Xóa tin; Xóa tin vẫn mở xác nhận và gọi delete_story như cũ. Người xem khác vẫn chỉ có menu báo cáo. Material BottomSheet tạm dừng Story, đọc quyền từ backend, đánh dấu quyền hiện tại và giữ lựa chọn qua tái tạo giao diện. Có loading, error, tải lại, chống nhấn trùng. Chỉ cập nhật audience trong Viewer/groups sau xác nhận đúng ID/quyền/owner từ backend. Kiểm tra định kỳ cũng đối soát audience. Không reload media hoặc reset tiến trình chỉ vì lưu quyền.

Owner cần review và triển khai migration forward-only **20261107000600_story_audience_edit.sql**, sau Task 36/42. Story Viewer hiện tại vẫn cần migration transaction **20261107000200**. Không phụ thuộc migration video Task 47. Android thiếu RPC sẽ thông báo rõ và không tự sửa local hoặc đăng lại tin.

- `get_story_privacy(p_story_id uuid)` trả story_id, audience và allowed_audiences, chỉ chủ Story active/chưa hết hạn đọc được. VOLATILE vì guard c_actor dùng row locks.
- `set_story_audience(p_story_id uuid,p_audience text,p_expected_audience text)` dùng b_actor/b_active, khóa Story theo owner, active/expiry, kiểm tra audience. Nếu trạng thái hiện tại khác expected thì 409; desired giống hiện tại là no-op. Chỉ UPDATE audience. Trả story_json cùng ID; lỗi projection rollback toàn transaction.
- Giá trị mới chỉ public/followers/private. Followers dùng private.follows, không tính follow request pending. Tài khoản riêng tư không được đổi sang public; lựa chọn Công khai bị vô hiệu hóa. Legacy friends được hiển thị nhãn quyền cũ, không tự đổi hoặc cấp lại quyền friends.
- Giữ Story ID/media/created_at/expires_at/state, lượt xem, reactions và các message Reply. Không có DELETE/INSERT nội dung, không mở RLS, grant bảng, signing hay bucket public.

Quyền truy cập: private.can_read_story (Task 36) kiểm audience/follows/block/account; private.can_read_media kế thừa Story cha, asset Story có UNIQUE media_asset_id. Storage meeto_b_get giới hạn authenticated GET/info; SupabaseStorage.url dùng object/authenticated với JWT, không phát signed URL. Sau commit, yêu cầu Story/metadata/media mới, kể cả dùng lại đường dẫn cũ, kiểm ACL mới. Giữ ngoại lệ moderation hợp lệ hiện có. Byte đã tải, ảnh đã hiển thị, stream đang chạy hoặc signed URL từng được phát bằng operator/luồng ngoài contract không thể thu hồi bằng UPDATE audience; không chứng nhận CDN eviction. Viewer khác đang mở đối soát quyền theo chu kỳ 15 giây hoặc khi mở lại. Không bổ sung Realtime.

Đồng bộ giao diện: sau xác nhận lưu, set audience cho đúng Story trong Viewer, invalidate StoryRings. Story Tray hiện có tự tải lại khi Activity nguồn resume; vòng viền lấy get_story_states theo ACL. Không sửa Feed hoặc Story Adapter. Thiết bị khác nhận quyền mới khi truy cập/refetch, không có push tức thời.

Chưa xác minh thực thi SQL/Cloud ABI/RLS, Material UI/rotation/process death, xung đột hai thiết bị, Save khi mất mạng, private/public/follower/pending/block, GET/Range qua đường dẫn cũ và dữ liệu cache. Nếu request đã commit nhưng response bị mất, giao diện không tuyên bố thành công; Tải lại quyền hiện tại/get_story đối soát lại. Hủy request khi destroy view không đảm bảo rollback một commit đã hoàn tất. Owner cần kiểm thủ công cả Story ảnh/video, lượt xem/Like/Reply trước/sau và Xóa tin. Không báo runtime PASS.
