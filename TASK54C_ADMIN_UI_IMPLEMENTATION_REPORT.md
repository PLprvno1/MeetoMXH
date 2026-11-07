# MEETO — TASK 54C: PREMIUM ADMIN CENTER UI/UX IMPLEMENTATION REPORT

**Workspace:** `F:\MeetoMXH`  
**Ngày:** 07/11/2026, Asia/Bangkok  
**Tác giả:** Senior Android UI/UX Designer & Java/XML Engineer  
**Trạng thái:** **SOURCE IMPLEMENTED / SOURCE VERIFIED. CLOUD VERIFIED: NO. RUNTIME VERIFIED: NO.**  
*(Tuân thủ nguyên tắc không chạy Gradle build, không chạy unit test, không mở emulator, không deploy Cloud, không push Git)*.

---

## 1. TỔNG QUAN NHIỆM VỤ

Task 54C đã hoàn thành việc đại tu toàn diện giao diện **Admin Center của Meeto**, biến hệ thống quản trị rời rạc trước đây thành một **Trung tâm Quản trị Di động Cao cấp (Premium Mobile Admin Dashboard & Moderation Center)** đạt chuẩn Material 3, hiện đại, tối giản và có tính ứng dụng thực tế cao.

Giao diện kế thừa trọn vẹn Design System của Meeto (màu hồng thương hiệu `#E1306C`, hỗ trợ đầy đủ Light/Dark/System theme, typography phân cấp rõ ràng, bo góc có hệ thống 10–14dp, visual hierarchy sắc nét).

Toàn bộ logic nghiệp vụ, quyền truy cập và dữ liệu kết nối trực tiếp với **Task 54B** (`AdminModels`, `AdminRepository`, `AdminReportActions`), sử dụng chính xác các RPC v2, không tạo dữ liệu giả lập (mock data), không tự sửa backend.

---

## 2. DANH SÁCH MÀN HÌNH ĐÃ TRIỂN KHAI

### Màn hình A — Admin Dashboard (Tổng quan)
- **Vị trí:** Tab "Tổng quan" trong `AdminPanelActivity`.
- **Thành phần:**
  - Header với tiêu đề "Trung tâm quản trị", huy hiệu vai trò ("Quản trị viên hệ thống"), nút làm mới, nút đăng xuất an toàn và nút quay về Meeto.
  - Hệ thống **Metric KPI Cards** bo góc 14dp với icon nền tròn, số liệu lớn rõ ràng (`users_total`, `users_active`, `posts_total`, `reels_total`, `reports_pending`, `reports_resolved`), nhãn phụ ngữ nghĩa. Mỗi card hỗ trợ ripple và chuyển tab nhanh đến màn hình tương ứng.
  - Phân vùng **Báo cáo cần chú ý** (`recent_reports`) và **Thao tác gần đây** (`recent_actions`) hiển thị dưới dạng card thông tin trực quan.

### Màn hình B — Report Center (Trung tâm Báo cáo)
- **Vị trí:** Tab "Báo cáo" trong `AdminPanelActivity` & Tab "Báo cáo" trong `ModerationDashboardActivity`.
- **Thành phần:**
  - Thanh tìm kiếm từ khóa bo góc pill (`bg_pill_search`) với nút xóa nhanh `X`.
  - Hàng Filter Chips mượt mà: "Chờ xử lý" (`open`), "Đang xử lý" (`assigned`), "Đã giải quyết" (`resolved`), "Đã bác bỏ" (`dismissed`), và chip lọc "Chỉ của tôi" (`mine`).
  - Danh sách thẻ báo cáo (`item_admin_report.xml`): Huy hiệu loại đối tượng (Bài viết, Reels, Bình luận, Story, Tin nhắn, Hồ sơ), mã lý do vi phạm, huy hiệu trạng thái có màu sắc ngữ nghĩa riêng biệt, trích đoạn nội dung, thời gian và ID rút gọn.
  - **Quy trình chi tiết và hành động báo cáo (`AdminReportActions`):**
    - Mở báo cáo **KHÔNG tự động nhận xử lý (claim)**.
    - Hiển thị đầy đủ thông tin: chi tiết phản ánh, đối tượng bị báo cáo, trạng thái bằng chứng văn bản (`evidence_status`), trạng thái tệp đính kèm (`media_status`).
    - Nút xem ảnh (`ChatImageViewerActivity`), video (`VideoPlayerActivity`), và trình phát âm thanh bảo mật (`openAudio`) với `MediaPlayer` giải phóng an toàn khi đóng.
    - Nút "Nhận xử lý" (`admin_claim_report_v2`) xuất hiện độc lập khi báo cáo chưa có người nhận.
    - Nút "Quyết định xử lý": Liệt kê chính xác `permitted_actions` được cấp quyền từ server ("Ghi nhận cảnh cáo (chưa gửi)", "Ẩn nội dung", "Gỡ nội dung, giữ bằng chứng", "Khóa đến khi được mở lại", "Bác bỏ báo cáo").
    - Hộp thoại xác nhận quyết định (Confirmation Dialog) bắt buộc nhập lý do (1–1000 ký tự), bảo toàn Request ID khi thử lại.

### Màn hình C — User Management (Quản lý Người dùng)
- **Vị trí:** Mục "Người dùng" trong tab "Quản lý" của `AdminPanelActivity`.
- **Thành phần:**
  - Tìm kiếm theo tên hoặc UID chính xác với cơ chế debounce 350ms.
  - Thẻ người dùng (`item_admin_user.xml`): Avatar tròn với fallback avatar sạch sẽ, Tên hiển thị (bold), UID định dạng monospace, Huy hiệu trạng thái tài khoản ("Đang hoạt động" - xanh, "Đã khóa" - đỏ, "Đang xóa" - cam), Ngày tham gia.
  - Xem chi tiết người dùng (`admin_subject_detail_v2(account)`):
    - Thao tác khóa tài khoản với nhãn chuẩn: **"Khóa đến khi được mở lại"** (không ghi thời hạn 7 ngày vì backend chưa có cơ chế expiry).
    - Thao tác mở khóa: "Khôi phục / mở khóa".
    - Bắt buộc nhập lý do và lưu vào audit log.

### Màn hình D — Content Management (Quản lý Nội dung)
- **Vị trí:** Các mục "Bài viết", "Reels", "Bình luận" trong tab "Quản lý" của `AdminPanelActivity`.
- **Thành phần:**
  - Hàng Filter Chips chuyển đổi giữa: **Bài viết**, **Reels**, **Bình luận**.
  - Thanh tìm kiếm nội dung theo từ khóa.
  - Thẻ nội dung (`item_admin_content.xml`): Huy hiệu loại nội dung, tên tác giả, trạng thái xuất bản ("Đang hiển thị", "Đã ẩn", "Đã gỡ"), văn bản xem trước tối đa 2 dòng, ngày đăng.
  - Thao tác kiểm duyệt trực tiếp (`admin_moderate_subject_v2`): "Ẩn nội dung" (`hide`), "Khôi phục nội dung" (`restore`), "Gỡ nội dung, giữ bằng chứng" (`remove`).

### Màn hình E — Audit History (Nhật ký Quản trị)
- **Vị trí:** Tab "Nhật ký & Thêm" trong `AdminPanelActivity` & Tab "Nhật ký kiểm duyệt" trong `ModerationDashboardActivity`.
- **Thành phần:**
  - Thẻ nhật ký (`item_admin_audit.xml`):
    - Huy hiệu thao tác (WARN, HIDE, REMOVE, SUSPEND, RESTORE, CLAIM, REASSIGN, DISMISS).
    - Loại đối tượng được kiểm duyệt.
    - Sơ đồ chuyển đổi trạng thái: `old_state → new_state` rõ ràng.
    - Lý do kiểm duyệt.
    - Actor ID định dạng rút gọn và mã báo cáo liên quan (Report Ref).
    - Thời gian ghi nhận.
  - Hỗ trợ phân trang mượt mà qua `admin_audit_page_v2` và nút "Tải thêm".

### Màn hình F — More / Settings / Account (Tài khoản & Phiên làm việc)
- **Vị trí:** Phần đầu tab "Nhật ký & Thêm" trong `AdminPanelActivity` và tab "Tài khoản" trong `ModerationDashboardActivity`.
- **Thành phần:**
  - Thẻ thông tin phiên làm việc: Email tài khoản quản trị viên (`SessionUser`), vai trò hệ thống đã xác minh từ server.
  - Thông tin phiên bản ứng dụng Meeto Admin (`BuildConfig.VERSION_NAME`).
  - Nút quay về ứng dụng Meeto và nút "Đăng xuất" quản trị kèm hộp thoại xác nhận an toàn.

---

## 3. DANH SÁCH FILE ĐÃ THÊM VÀ SỬA ĐỔI

### File Java (3 files chỉnh sửa & nâng cấp):
1. `app/src/main/java/com/example/meetomxh/AdminPanelActivity.java` (47,090 bytes)
2. `app/src/main/java/com/example/meetomxh/ModerationDashboardActivity.java` (20,037 bytes)
3. `app/src/main/java/com/example/meetomxh/supabase/AdminReportActions.java` (25,003 bytes)

### File Layout XML mới & nâng cấp (7 files):
1. `app/src/main/res/layout/activity_admin_panel.xml` — Layout trung tâm cho Admin Center.
2. `app/src/main/res/layout/activity_moderation_dashboard.xml` — Layout chuyên biệt cho Moderator Center.
3. `app/src/main/res/layout/item_admin_dashboard_metric.xml` — Thẻ chỉ số KPI Dashboard.
4. `app/src/main/res/layout/item_admin_report.xml` — Thẻ hiển thị báo cáo.
5. `app/src/main/res/layout/item_admin_user.xml` — Thẻ quản lý người dùng.
6. `app/src/main/res/layout/item_admin_content.xml` — Thẻ quản lý nội dung (Post/Reel/Comment).
7. `app/src/main/res/layout/item_admin_audit.xml` — Thẻ lịch sử kiểm duyệt (Audit).

### File Drawable XML mới (11 files):
1. `app/src/main/res/drawable/ic_dashboard.xml` — Icon Vector 4 ô Dashboard.
2. `app/src/main/res/drawable/ic_shield.xml` — Icon Vector Khiên bảo mật / Kiểm duyệt.
3. `app/src/main/res/drawable/ic_history.xml` — Icon Vector Đồng hồ / Nhật ký kiểm duyệt.
4. `app/src/main/res/drawable/ic_filter.xml` — Icon Vector Phễu lọc.
5. `app/src/main/res/drawable/ic_block.xml` — Icon Vector Khóa tài khoản.
6. `app/src/main/res/drawable/bg_badge_open.xml` — Shape badge trạng thái Chờ xử lý (màu cam mềm).
7. `app/src/main/res/drawable/bg_badge_assigned.xml` — Shape badge trạng thái Đang xử lý (màu hồng/xanh mềm).
8. `app/src/main/res/drawable/bg_badge_resolved.xml` — Shape badge trạng thái Đã giải quyết (màu xanh lá mềm).
9. `app/src/main/res/drawable/bg_badge_dismissed.xml` — Shape badge trạng thái Đã bác bỏ (màu xám trung tính).
10. `app/src/main/res/drawable/bg_chip_selected.xml` — Shape Filter Chip được chọn (nền hồng Meeto).
11. `app/src/main/res/drawable/bg_chip_unselected.xml` — Shape Filter Chip chưa chọn (nền surface variant).

---

## 4. HỆ THỐNG COMPONENT VÀ THEME

- **Màu sắc & Tokens:** Kế thừa từ `colors.xml` và `values-night/colors.xml`:
  - Màu chủ đạo: `@color/brand_blue` (`#E1306C` — hồng Meeto).
  - Nền ứng dụng: `@color/app_bg` (`#FFFFFF` trên Light, `#000000` trên Dark).
  - Nền thẻ & toolbar: `@color/surface_bg` (`#FFFFFF` trên Light, `#121212` trên Dark).
  - Phân cách & viền: `@color/divider_color` (`#EFEFEF` trên Light, `#262626` trên Dark).
  - Trạng thái thành công: `@color/status_success` (`#10D876`).
  - Trạng thái cảnh báo: `@color/status_warning` (`#FF9800`).
  - Trạng thái nguy hiểm/xóa: `@color/status_error` (`#ED4956`).
- **Typography:** Tiêu đề màn hình (18–20sp bold/black), Tiêu đề thẻ (15–16sp semibold), Nội dung mô tả (13–14sp), Nhãn trạng thái/Mã định danh (11–12sp monospace/bold).
- **Trạng thái giao diện:**
  - Loading: ProgressBar với màu hồng Meeto (`indeterminateTint`).
  - Empty: Icon lớn mờ (`brand_blue_soft`) kèm tiêu đề và hướng dẫn trực quan ở trung tâm.
  - Error: Icon cảnh báo đỏ (`status_error`) kèm thông điệp lỗi và nút "Thử lại".
  - Pagination: Nút "Tải thêm" (`adminLoadMore` / `modLoadMore`) dạng soft pill button ở cuối danh sách.

---

## 5. LUỒNG ĐIỀU HƯỚNG VÀ PHÂN QUYỀN

```
[Khởi động / Xác minh vai trò (RoleResolutionActivity)]
  │
  ├─► system_admin: Điều hướng tới AdminPanelActivity (Toàn quyền 4 Tabs: Tổng quan, Báo cáo, Quản lý, Thêm)
  │
  └─► User thường / Moderator: Điều hướng tới MainActivity
        │
        └─► Mở Cài đặt (SettingsActivity)
              │
              └─► Nếu có quyền can_review_reports = true:
                    Hiện nút "Bảng kiểm duyệt nội dung" ──► Mở ModerationDashboardActivity (Dành riêng cho Moderator)
```

- **Quy tắc phân quyền:**
  - Moderator chỉ xem được báo cáo trong phạm vi cho phép và không có quyền khóa tài khoản trực tiếp hoặc can thiệp bài viết tự do ngoài báo cáo.
  - System Admin có quyền truy cập đầy đủ các công cụ quản lý tài khoản, nội dung và xem toàn bộ nhật ký.
  - Khi quyền bị thu hồi hoặc phiên hết hạn (`401` / `403`), hệ thống tự động dọn dẹp dữ liệu nhạy cảm và điều hướng về màn hình đăng nhập hoặc xác minh lại vai trò.

---

## 6. KẾT NỐI REPOSITORY VÀ RPC THỰC TẾ

| Màn hình / Tính năng | RPC kết nối | Đối tượng / Model |
| :--- | :--- | :--- |
| **Xác minh quyền** | `admin_get_capabilities` | `AdminModels.Capabilities` |
| **Tổng quan Dashboard** | `admin_dashboard_v2` | `users_total`, `users_active`, `posts_total`, `reels_total`, `reports_pending`, `reports_resolved`, `recent_reports`, `recent_actions` |
| **Danh sách Báo cáo** | `admin_reports_page_v2` | `AdminModels.Page` (exact state, query, mine filter, cursor paging) |
| **Chi tiết Báo cáo** | `admin_report_detail_v2` | `AdminModels.Report` (read-only, scoped, evidence status, media links) |
| **Nhận xử lý Báo cáo** | `admin_claim_report_v2` | Atomic receipt, gán `assigned_to` |
| **Quyết định Báo cáo** | `admin_decide_report_v2` | `AdminModels.Receipt`, locked transaction, action delta, audit log |
| **Kiểm tra biên nhận** | `admin_action_receipt` | Đọc lại receipt theo Request ID để đảm bảo idempotency |
| **Danh sách Người dùng** | `admin_users_page_v2` | `AdminModels.Page` (query tên/UID, state filter, cursor paging) |
| **Chi tiết Đối tượng** | `admin_subject_detail_v2` | `AdminModels.Account` hoặc `AdminModels.Content` |
| **Thao tác Đối tượng** | `admin_moderate_subject_v2` | Locked transition (suspend, restore, hide, remove) |
| **Nhật ký Kiểm duyệt** | `admin_audit_page_v2` | `AdminModels.Page` (`AdminModels.AuditEntry`, cursor paging) |

---

## 7. CÁC ĐIỂM CẦN LƯU Ý CHO TASK 54D (KIỂM TRA TÍCH HỢP & RUNTIME)

1. **Kiểm tra hiển thị Theme DayNight:** Mở cả hai màn hình `AdminPanelActivity` và `ModerationDashboardActivity` trên cả Light Mode và Dark Mode để kiểm tra độ tương phản văn bản và màu các badge.
2. **Kiểm tra nghe âm thanh bằng chứng:** Kiểm tra phát audio đính kèm trong báo cáo trên thiết bị thật để xác thực codec và luồng Supabase Storage URL.
3. **Kiểm tra phân quyền tài khoản Moderator:** Đăng nhập bằng tài khoản chỉ có quyền `moderator` để xác nhận rằng tài khoản này không thể truy cập các tính năng khóa tài khoản hay quản lý bài viết ngoài luồng báo cáo.
4. **Kiểm tra Idempotency khi mất mạng:** Thử ngắt mạng tạm thời khi bấm xác nhận thao tác để kiểm tra việc lưu giữ bản nháp lý do và tái sử dụng Request ID mà không sinh trùng lặp hành động.
5. **Chi tiết các phụ thuộc logic backend:** Đã được lập tài liệu riêng tại `TASK54C_LOGIC_DEPENDENCIES.md` để Codex tiếp tục xử lý.
