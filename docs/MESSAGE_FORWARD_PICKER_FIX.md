# Bảng chọn nơi chuyển tiếp tin nhắn

SOURCE FIX IMPLEMENTED / BUILD NOT VERIFIED / ANDROID RUNTIME NOT VERIFIED / CLOUD RUNTIME NOT VERIFIED.

## Nguyên nhân và thay đổi

Luồng cũ trong `ChatActivity` gọi `loadCurrentUserChats`, tải tuần tự thành viên của mọi cuộc trò chuyện rồi chờ callback tên hồ sơ trước khi hiện bảng chọn. Lỗi ở một lần tải thành viên có thể làm cả danh sách thất bại. Dialog cuối dùng `setMessage` và `setItems` cùng lúc; phần message của AlertDialog có thể chiếm vùng nội dung và không hiển thị danh sách như mong muốn. Không có avatar/checkbox và chỉ chọn một nơi.

- `ChatActivity.java`: mở `ForwardMessagePicker` ngay sau khi chọn chuyển tiếp, chặn mở lặp và đóng helper khi destroy. Giữ điều kiện chỉ chuyển tiếp tin nhắn văn bản chưa thu hồi, không phải bài viết chia sẻ. Không thêm chuyển tiếp ảnh/video/voice vào RPC văn bản.
- `ui/ForwardMessagePicker.java`: Material dialog bo góc, preview ngắn, tìm kiếm, danh sách có avatar tròn/tên/checkbox bên phải, chọn tối đa 20 nơi, nút chuyển tiếp. Tải theo trang 30, giữ lựa chọn khi tìm/tải thêm, có trạng thái trống/lỗi/thử lại. Không phải chờ N lần gọi hồ sơ/thành viên để mở dialog.
- `res/layout/item_forward_target.xml`: hàng Material card bo góc 14dp, avatar 46dp và checkbox 48dp. Avatar lấy asset đã xác thực, fallback preset hoặc icon nhóm.
- Migration `20261108000700_message_forward_targets.sql`: RPC đọc danh sách nơi nhận có sẵn, tên/avatar từ metadata hội thoại/thành viên hiện có. Chỉ hội thoại active, thành viên active, quyền đọc hợp lệ; tin riêng phải được chấp nhận, loại hội thoại nguồn, nhóm có admin hệ thống và người bị chặn theo điều kiện message-contact trigger. Bao gồm hội thoại mà cá nhân đã lưu trữ nếu hội thoại vẫn active, không tự bỏ lưu trữ. Có query tên/biệt danh nhóm/người và cursor updated_at/id, giữ ACL khi đọc avatar/tên. Không tạo hội thoại mới hoặc tự chấp nhận yêu cầu tin nhắn.
- Gửi bằng nguyên `forward_text_message`. Server tiếp tục xác minh nguồn văn bản chưa thu hồi/không phải shared-post, thành viên và chính sách nhận tin. Bảng nơi nhận chỉ là snapshot: thay đổi quyền sau khi chọn vẫn có thể bị server từ chối lúc gửi.
- Mỗi nơi có UUID request riêng, giữ khi retry trong cùng cửa sổ. Gửi từng nơi, đánh dấu thành công; lỗi một nơi không làm gửi lại nơi đã thành công. Giữ các nơi lỗi để bỏ chọn/thử lại, hiển thị lý do trên hàng. Không đóng bằng thao tác thường trong lúc đang gửi; destroy hủy request/callback/timer qua helper/lifecycle. Api vẫn có session-generation fence.

## Owner triển khai và thử lại

1. Áp dụng migration mới sau advanced messenger, messaging consent và chat customization. File chỉ được tạo local, chưa chạy trên Cloud. RPC chưa có sẽ báo lỗi trong bảng, không giả danh sách trống.
2. Nhấn giữ tin văn bản → Chuyển tiếp văn bản: bảng phải hiện ngay, avatar/tên/checkbox rõ, bo góc đúng, cuộn/tìm kiếm/tải thêm hoạt động với bàn phím mở.
3. Chọn nhiều hội thoại riêng/nhóm, đổi query, bỏ chọn, gửi và kiểm tra nhãn “Đã chuyển tiếp” phía nhận. Không hiển thị hội thoại nguồn, tin riêng đang chờ, nhóm đã rời/không active hoặc bị chặn theo điều kiện RPC.
4. Thử lỗi mạng, nguồn bị thu hồi, bị chặn/rời nhóm sau khi chọn, một số đích gửi thành công còn đích khác lỗi. Retry trong cùng cửa sổ dùng lại request ID; không gửi lại các đích đã thành công. Sau khi Activity bị hủy/tạo lại, trạng thái đích/request ID chưa được lưu bền; cần kiểm tra lịch sử nhận trước khi chủ động mở lại và gửi lần mới.
5. Kiểm tra hủy khi chưa gửi, đóng/destroy/đổi tài khoản khi tải; không có callback cũ hoặc popup giữ Activity. Thử đủ 20 nơi và danh sách nhiều hơn một trang.

Chưa chạy Gradle/compile/test/ADB/emulator, Cloud, migration hoặc Git push. Chưa xác nhận hoạt động thực tế trên thiết bị. Không thay đổi RPC gửi tin, trigger consent, Storage hoặc quyền nhắn tin hiện có.
