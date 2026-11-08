# Hiển thị yêu cầu tài khoản trong Admin

Trạng thái: SOURCE FIX IMPLEMENTED / BUILD NOT VERIFIED / ANDROID RUNTIME NOT VERIFIED / CLOUD RUNTIME NOT VERIFIED.

## Nguyên nhân từ source

Settings gọi `request_account_deletion`. RPC ghi `account_status.deletion_requested_at`, giữ trạng thái tài khoản hiện tại và ghi `account_deletion_requested` vào nhật ký. Đây không phải báo cáo nội dung trong bảng `reports`, nên không xuất hiện ở tab Báo cáo. `admin_users_page_v2` trước đây không trả thời điểm yêu cầu và không hỗ trợ lọc yêu cầu. Android cũng không có danh sách riêng.

## Thay đổi

- `AdminPanelActivity.java`: lối vào “Yêu cầu tài khoản” ở Tổng quan và Quản lý; dùng danh sách người dùng có phân trang và tìm kiếm. Hiển thị ngày gửi, nhãn chờ xử lý và thông tin yêu cầu trong chi tiết tài khoản. Dùng nguyên quyền và thao tác khóa hiện có, có xác nhận và lý do. Khóa không đồng nghĩa xóa dữ liệu.
- `supabase/migrations/20261108000200_admin_account_requests.sql`: mở rộng implementation private của RPC người dùng, thêm trường `deletion_requested_at` và bộ lọc `deletion_requested`. Giữ kiểm tra tài khoản hoạt động, quyền `system_admin`, giới hạn trang, cursor, tìm kiếm, wrapper và EXECUTE ACL hiện hữu. Không sửa dữ liệu, không tạo yêu cầu hay báo cáo trùng lặp. Các yêu cầu cũ được đọc từ timestamp đã lưu.
- Danh sách đang chờ gồm tài khoản `active` có timestamp yêu cầu. Tài khoản đã khóa/xóa không nằm trong danh sách này; ngày yêu cầu vẫn được giữ ở danh sách người dùng/chi tiết. Schema hiện tại không có trạng thái duyệt riêng; nếu Admin mở khóa lại tài khoản có timestamp cũ thì nó lại thỏa bộ lọc này. Chưa bổ sung quy trình duyệt/từ chối/xóa dữ liệu mới.

## Owner triển khai và kiểm tra

1. Áp dụng migration bổ sung sau migration Admin hardening `20261107001100`. File mới chỉ được tạo trong workspace, chưa chạy trên Cloud. Android mới cần migration này để truy vấn bộ lọc mới; server cũ sẽ trả lỗi và UI hiển thị lỗi, không giả danh sách trống.
2. Với tài khoản đã gửi yêu cầu: mở Admin → Tổng quan → Yêu cầu tài khoản → Xem, hoặc Quản lý → Yêu cầu tài khoản; kéo xuống/tải lại. Không cần gửi lại yêu cầu cũ.
3. Kiểm tra tên, ngày yêu cầu, tìm kiếm và tải thêm; tài khoản chưa gửi không xuất hiện. Mở chi tiết để kiểm tra timestamp và các thao tác theo quyền.
4. Nếu chọn khóa, kiểm tra xác nhận/lý do, kết quả và nhật ký; tài khoản không còn `active` sẽ ra khỏi danh sách chờ. Kiểm tra danh sách Người dùng, Báo cáo và các tab nội dung vẫn hoạt động.
5. Kiểm tra tài khoản thường không có quyền gọi RPC Admin.

Chỉ đọc và chỉnh source. Không chạy build, kiểm thử, ADB, Cloud hoặc Git push; chưa xác nhận dữ liệu và hành vi thực tế trên thiết bị.
