# Gắn thẻ và gợi ý @ theo kết nối follow

SOURCE FIX IMPLEMENTED / BUILD NOT VERIFIED / ANDROID RUNTIME NOT VERIFIED / CLOUD RUNTIME NOT VERIFIED.

## Nguyên nhân từ source

- `PostTagPicker` loại mọi kết quả không có `is_friend=true`. Hệ thống follow-only đã đặt `private.are_friends` luôn false, nên danh sách không thể chọn ai.
- `search_mention_people` vẫn ưu tiên bạn bè, tìm tiền tố tên và từ chối query rỗng. `MentionComposer` cũng không tìm ngay khi chỉ có `@`. Popup luôn mở bên dưới ô nhập, dễ bị bàn phím che ở vùng nhập bình luận dưới màn hình.

## Thay đổi

- Migration `20261108000600_follow_tags_mentions.sql`: cập nhật `search_mention_people` để trả người mình theo dõi **hoặc** người theo dõi mình bằng quan hệ `user_follows` thực tế. Không tính yêu cầu follow chưa được duyệt. Query rỗng cho danh sách ban đầu; hỗ trợ tên/biệt danh, không phân biệt hoa thường/dấu và tìm các từ trong chuỗi. Giữ giới hạn tối đa 12 người, active/profile ACL/chặn hai chiều, loại chính mình. Thêm `following`, `follows_you`, `username`, `can_tag`.
- `PostTagPicker.java`: tải danh sách ngay khi mở, tìm theo tên/biệt danh, bỏ bộ lọc `is_friend`; ảnh đại diện, dấu chọn và chip, tối đa 10 người. Người đặt quyền không nhận thẻ vẫn xuất hiện nhưng không chọn thêm được; có giải thích. Kết quả chọn vẫn giữ khi đổi từ khóa. Hủy request/debounce khi thay query/đóng/destroy; kiểm tra session generation.
- `MentionComposer.java`: gõ `@` để gợi ý ngay; tiếp tục nhập để lọc. Theo dõi con trỏ để không chèn vào vị trí cũ. Popup có cuộn, giới hạn chiều cao và chọn vùng trên/dưới ô nhập theo phần màn hình còn nhìn thấy. Hủy request và bỏ callback cũ khi sửa/xóa token, mất focus, detach/dispose; kiểm tra phiên khi chọn. Span vẫn dùng user ID, label và UTF-16 range hiện hữu. Dùng chung cho tạo bài, bình luận mới, trả lời và sửa bình luận qua các call sites hiện có, không cần viết lại comments fragment.
- `sync_post_tags` và `request_post_retag`: chính sách `followers` cho phép quan hệ follow ít nhất một chiều. Giữ `everyone` và `nobody`, duyệt trước, trạng thái rejected/removed, explicit re-consent và cooldown 7 ngày. Không đổi cài đặt người nhận trong dữ liệu. Nhãn chính sách trên Android đổi thành “Người theo dõi / Đang theo dõi”.
- `ErrorMapper`: giải thích trường hợp người nhận không nhận thẻ hoặc không thể xem nội dung. Chỉ xuất hiện trong gợi ý không đồng nghĩa có quyền xem bài: validator lưu thẻ/mention vẫn kiểm tra quyền xem bài/bình luận, trạng thái và block. Không cấp quyền đọc nội dung qua tag/mention.

Không thay đổi giới hạn mention, validator UTF-16, notification triggers, RPC tạo/sửa bài và bình luận, transport JWT, bucket hay engine nhạc. Mention có sẵn (ví dụ nhắc tác giả khi trả lời) vẫn qua validator hiện hữu; chỉ nguồn gợi ý được giới hạn theo mạng follow, không ép các mention cũ phải trở thành quan hệ follow.

## Owner triển khai và kiểm tra

1. Áp dụng migration mới sau các migration tags/mentions và follow-only privacy. File chỉ được tạo trong workspace; chưa chạy trên Cloud. Android mới cần query rỗng và các trường mới từ RPC cập nhật.
2. Chuẩn bị người chỉ mình follow, người chỉ follow mình, người follow hai chiều và người không có kết nối. Bộ chọn/gợi ý phải trả ba nhóm đầu, không trả người không liên hệ, chính mình, tài khoản bị chặn/không hoạt động hay yêu cầu follow chưa duyệt.
3. Tạo và sửa bài: mở Gắn thẻ khi chưa gõ từ khóa, tìm tên/biệt danh có/không dấu, chọn/bỏ nhiều người, đổi từ khóa giữ lựa chọn, hủy và mở lại; thử giới hạn 10. Đăng rồi xem danh sách thẻ và quyền duyệt. Người chọn “Không ai” phải tiếp tục bị chặn nhận thẻ; không tự đổi quyền của họ để làm cho thao tác thành công.
4. Bình luận mới/trả lời/sửa: gõ riêng `@`, `@biệtdanh`, chọn người, thêm chữ trước/sau, di chuyển con trỏ, xóa/sửa nhãn và chèn nhiều mention. Kiểm tra bàn phím mở mà vẫn chọn gợi ý được, dữ liệu lưu đúng label/range và chạm nhắc tên mở đúng hồ sơ.
5. Kiểm tra emoji trước mention, submit/rerender, restore draft, đóng sheet/editor trong lúc tìm và đổi tài khoản. Không được nhận callback cũ hay mất thẻ đã chọn.
6. Nếu target không được xem bài riêng tư, lưu phải bị chặn có thông báo, không cấp quyền nhờ tag. Kiểm tra từ chối/gỡ thẻ rồi sửa bài không tự hồi sinh thẻ; re-consent vẫn cần đủ điều kiện/cooldown. Việc bị block/unfollow/thay đổi quyền giữa lúc chọn và lưu phải tiếp tục qua validator server.

Chỉ đọc và chỉnh source. Không chạy Gradle/compile/test/ADB/emulator, kiểm thử Cloud, migration hay Git push. Các tình huống UI/bàn phím/notification/RPC trên thiết bị vẫn cần owner xác nhận.
