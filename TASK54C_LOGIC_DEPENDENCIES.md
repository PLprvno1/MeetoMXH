# MEETO — TASK 54C: LOGIC & BACKEND DEPENDENCIES REPORT

**Workspace:** `F:\MeetoMXH`  
**Ngày lập:** 07/11/2026  
**Agent:** Senior Android UI/UX Designer & Java/XML Engineer  
**Đối tượng bàn giao:** Codex (Task 54D — Cloud & Integration Verification)

---

## 1. MỤC ĐÍCH

Báo cáo này liệt kê các điểm thiếu hụt logic, các giới hạn projection và các hợp đồng backend chưa có hoặc chưa hoàn chỉnh được phát hiện trong quá trình triển khai giao diện **Admin Center (Task 54C)**.

Theo đúng nguyên tắc nhiệm vụ UI:
- **Tuyệt đối không tự ý viết thêm RPC, không sửa SQL migration, không sửa RLS hay giả lập dữ liệu mock.**
- Giao diện đã được điều chỉnh để khớp 100% với dữ liệu thực tế hiện có từ Task 54B (`AdminModels`, `AdminRepository`, `AdminReportActions`).

---

## 2. DANH SÁCH CÁC DEPENDENCY CẦN BÀN GIAO CODEX (TASK 54D)

### Dependency 1: Cơ chế phát thông báo kết quả kiểm duyệt (Moderation Notification Delivery)
- **Màn hình liên quan:** Màn hình B (Report Center) & Chi tiết báo cáo (`AdminReportActions`).
- **Hành động:** Khi Moderator/Admin chọn quyết định "Ghi nhận cảnh cáo" (`warn`) hoặc "Gỡ nội dung" (`remove`) hoặc "Khóa tài khoản" (`suspend`).
- **Hiện trạng:** Backend Task 54B ghi nhận hành động vào `moderation_actions` và `moderation_audit` với cờ `notification_status = 'not_implemented'`. Chưa có notification trigger, outbox job hoặc Edge Function để gửi thông báo đẩy/inbox tới người dùng bị xử lý.
- **Xử lý ở tầng UI Task 54C:** UI hiển thị chính xác nhãn: *"Ghi nhận cảnh cáo (chưa gửi)"* và dòng chú thích rõ ràng: *"Cảnh cáo chỉ được ghi nhận; hệ thống chưa gửi thông báo."* Tuyệt đối không hiển thị "Đã gửi cảnh cáo".
- **Đề xuất bàn giao Codex Task 54D:** Nếu Owner yêu cầu có thông báo gửi tới người dùng, Codex cần thiết lập workflow tạo bản ghi trong bảng `notifications` hoặc bảng outbox sự kiện tương ứng trong giao dịch kiểm duyệt.

---

### Dependency 2: Quản lý Story trực tiếp (Direct Story Moderation)
- **Màn hình liên quan:** Màn hình D (Content Management).
- **Hành động:** Liệt kê và kiểm duyệt Story trực tiếp ngoài luồng Báo cáo.
- **Hiện trạng:** RPC `admin_content_page_v2` hiện chỉ hỗ trợ các loại `posts`, `reels`, `comments` (loại `groups` đã bị khai tử bằng HTTP 410). Story hiện chỉ được xem và gỡ thông qua báo cáo vi phạm (`admin_report_detail_v2` và `admin_decide_report_v2`). Chưa có RPC danh sách Story hệ thống `admin_list_stories` hay cơ chế khôi phục Story.
- **Xử lý ở tầng UI Task 54C:** Tab Quản lý nội dung chỉ hiển thị 3 danh mục thực tế: **Bài viết**, **Reels**, **Bình luận**. Không thêm nút giả "Quản lý Story trực tiếp".
- **Đề xuất bàn giao Codex Task 54D:** Xem xét bổ sung RPC liệt kê Story cho Admin nếu có nhu cầu giám sát chủ động ngoài luồng report.

---

### Dependency 3: Cơ chế thời hạn khóa tài khoản (Suspension Duration / Auto-expiry)
- **Màn hình liên quan:** Màn hình C (User Management) & Màn hình B (Report Center).
- **Hành động:** Khóa tài khoản người dùng (`suspend`).
- **Hiện trạng:** RPC backend hiện thiết lập `account_status.state = 'suspended'` với `suspended_until = NULL`. Chưa có cơ chế cron job/scheduler tự động mở khóa sau một khoảng thời gian (như 7 ngày, 30 ngày).
- **Xử lý ở tầng UI Task 54C:** UI sử dụng nhãn chính xác: **"Khóa đến khi được mở lại"**. Tuyệt đối không ghi "Khóa tạm thời 7 ngày" để tránh gây hiểu lầm cho Admin.
- **Đề xuất bàn giao Codex Task 54D:** Nếu cần tính năng khóa có thời hạn, cần bổ sung cột `suspended_until`, cron worker quét trạng thái tài khoản và quy trình mở khóa tự động.

---

### Dependency 4: Bộ lọc nâng cao cho Nhật ký kiểm duyệt (Audit Page Filtering)
- **Màn hình liên quan:** Màn hình E (Audit History / Nhật ký & Thêm).
- **Hành động:** Tìm kiếm và lọc nhật ký theo Actor ID, ngày tháng hoặc loại hành động cụ thể.
- **Hiện trạng:** RPC `admin_audit_page_v2(p_after_time, p_after_id, p_limit)` hiện chỉ hỗ trợ phân trang theo con trỏ thời gian (`created_at`, `id`), chưa nhận tham số lọc `p_actor_id`, `p_operation`, hoặc `p_date_range`.
- **Xử lý ở tầng UI Task 54C:** Hiển thị danh sách audit phân trang mượt mà với đầy đủ thông tin chi tiết (Actor, Operation, State transition `old_state → new_state`, Reason, Reference), không tạo bộ lọc giả không hoạt động ở client.
- **Đề xuất bàn giao Codex Task 54D:** Bổ sung các tham số filter vào `admin_audit_page_v2` khi tối ưu chỉ mục database.

---

### Dependency 5: Quản lý vai trò quản trị (Role Management) & Kháng nghị (Appeals)
- **Màn hình liên quan:** Màn hình F (More / Settings).
- **Hành động:** Cấp/hạ quyền Admin/Moderator và xem xét kháng nghị từ người dùng.
- **Hiện trạng:**
  - Bảng `system_roles` không có RPC client nào cho phép tự phong quyền (vai trò được gán trực tiếp qua database migration/SQL).
  - Bảng `appeals` tồn tại trong database nhưng chưa có workflow RPC client cho phía người dùng gửi kháng nghị hoặc Admin duyệt kháng nghị.
- **Xử lý ở tầng UI Task 54C:** Không tạo màn hình hoặc nút "Quản lý vai trò" hay "Duyệt kháng nghị" giả lập. Màn hình Thêm chỉ hiển thị thông tin vai trò hiện tại đã được xác nhận từ server.
- **Đề xuất bàn giao Codex Task 54D:** Giữ nguyên phạm vi này cho các đợt phát triển tiếp theo khi có yêu cầu nghiệp vụ rõ ràng từ Owner.

---

## 3. TỔNG KẾT
Tất cả 5 điểm phụ thuộc trên đã được UI xử lý an toàn bằng cách hiển thị đúng bản chất hợp đồng, bảo toàn bằng chứng, và bảo vệ tính toàn vẹn của hệ thống Meeto.
