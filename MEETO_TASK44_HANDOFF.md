# MEETO — Task 44: Chat nicknames, themes, smart group picker

Trạng thái: source đã triển khai và rà soát tĩnh. **Chưa build/test/emulator/E2E; chưa chạy SQL hay deploy Cloud.** Không thay Gradle, manifest, Reels, Stories hoặc Feed trong Task 44. Các thay đổi từ nhiệm vụ trước trong working tree được giữ nguyên.

## 1. Màn hình

- Chat Details: Biệt danh, Chủ đề đoạn chat, Tạo nhóm chat (Direct Chat); giữ quản lý nhóm/lời mời hiện có.
- Group Picker: mở là tải gợi ý Follow thật; nhãn Đang theo dõi / Theo dõi bạn / Theo dõi lẫn nhau, username, avatar thật, checkbox toàn dòng, chips bỏ từng người, số đã chọn, phân trang 30 người, debounce 300ms, xóa từ khóa, loading/empty/error/retry. Tài khoản chỉ xuất hiện một lần. Tìm theo một phần tên/username, dùng `private.social_search_text` có sẵn để bỏ dấu tiếng Việt và escape wildcard. Không dùng Friendship.
- ChatActivity: preview theme ngay trên chat khi chọn trong BottomSheet; chỉ Áp dụng mới ghi backend; hủy khôi phục màu đã lưu rồi refetch trạng thái. Tên header Direct Chat và tên người gửi Group Chat ưu tiên nickname.
- Group Members, Inbox: ưu tiên tên hội thoại từ projection; avatar thật được giữ, không dùng nickname thay tên Profile.

## 2. File Task 44

Java thêm mới:

- `supabase/ChatCustomizationClient.java`
- `supabase/ChatCustomizationUi.java`
- `supabase/SmartGroupPicker.java`
- `ui/ChatThemeRegistry.java`

Các đường dẫn Java dưới `app/src/main/java/com/example/meetomxh/`.

Java sửa: `ChatActivity.java`, `ConversationDetailsActivity.java`, `GroupMembersActivity.java`, `models/Chat.java`, `repositories/ChatRepository.java`, `adapters/ChatMessageAdapter.java`, `fragments/InboxFragment.java`, `supabase/ChatControls.java`, `supabase/PeoplePicker.java`, `supabase/Dto.java`.

XML sửa: `app/src/main/res/layout/activity_chat.xml` (ID header/composer), `item_chat_message.xml` (tên người gửi). Giữ ảnh/video không có khung và thumbnail composer từ yêu cầu trước. Không thêm dependency hoặc sửa Gradle.

SQL mới: `supabase/migrations/20261107000300_chat_customization_picker.sql`. Không sửa migration lịch sử.

## 3. Lưu trữ và quyền

- `conversation_members.nickname`: null hoặc 1–40 ký tự, trim, không control characters. Mọi thành viên active có thể đặt/xóa nickname cho chính mình hoặc thành viên active khác nếu target còn được ACL cho phép. Không ghi `profiles.display_name`.
- `conversations.theme_id`: allowlist 10 ID ổn định, mặc định `meeto_pink`. Dùng chung theo Conversation ID, không SharedPreferences.
- `conversations.customization_version`: optimistic concurrency riêng cho nickname/theme; gửi tin nhắn mới không gây conflict giả cho phần customization. JSON settings trả trường `version` của customization. Mutations đồng thời khác trả 409 và UI yêu cầu đóng/mở lại lấy trạng thái mới.
- Mutations dùng `private.c_lock`, actor từ JWT, membership/consent/active state và System Admin exclusion. Fixed `search_path=''`; không direct DML hoặc grant đọc các cột mới. Read projection giữ `can_read_conversation` / `can_read_profile`.
- Client vẫn qua Task 36 guard và kiểm `task44_chat_capabilities` trước các thao tác mới. Cloud thiếu migration sẽ hiện lỗi cập nhật, không fallback sang quyền cũ hoặc giả lưu thành công.
- Dùng `private.c_event(...,'state')` và Observer/Realtime hiện có, kèm polling 30 giây hiện hữu. Không tạo Realtime thứ hai, không giả system-message đổi nickname/theme. Broadcast nhanh phụ thuộc dispatcher hiện có của môi trường; chưa xác minh delivery trên Cloud.

## 4. Themes

| ID | Tên |
|---|---|
| meeto_pink | Meeto Pink |
| ocean_blue | Ocean Blue |
| lavender | Lavender |
| midnight | Midnight |
| forest | Forest |
| sunset | Sunset |
| rose | Rose |
| graphite | Graphite |
| coffee | Coffee |
| aurora | Aurora |

Registry tập trung palette Light/Dark thiết kế riêng: nền, sent/received, text/secondary, accent, input/border. Preview và chat lấy cùng registry. Chữ trên bubble dùng `ColorUtils.calculateContrast` chọn trắng khi đạt >=4.5:1, ngược lại chọn đen; timestamp/receipt/reply/link dùng màu opaque tương phản tương ứng. Text chính/phụ trên nền sáng dùng màu tối; nền tối dùng màu sáng. Icon/control dùng accent đậm hoặc sáng theo mode, input/card có border. Video play badge có nền/foreground riêng theo palette. Chưa chứng nhận hiển thị/WCAG toàn màn trên thiết bị; owner cần kiểm cả Light/Dark, disabled state, font scale, reply/link/shared post/media.

## 5. Tạo nhóm từ Direct Chat

Chat Details → Tạo nhóm chat → picker gọi backend để kiểm người đang chat và chọn sẵn nếu đủ điều kiện → chọn ít nhất hai người ngoài creator (có thể bỏ người đã chọn sẵn) → nhập tên và xác nhận.

Tái sử dụng `create_group_conversation` và `manage_conversation_member(...,'add')`. Không chuyển đổi Direct Chat; không sửa/xóa lịch sử. Creator là active member; người nhận là invitation pending, phải Accept theo Task 36. Điều hướng dùng ID backend trả về. Giữ request ID qua lỗi/network/callback bị hủy, chỉ complete sau điều hướng thành công để tránh tạo nhóm trùng khi thử lại cùng payload.

## 6. Gợi ý và quyền mời

RPC mới truy vấn `private.follows` / `public.user_follows` hiện có. Hợp nhất quan hệ bằng profile ID, cursor `(social_search_text(display_name),id)` tăng dần. Khi không nhập chỉ lấy người có Follow một hoặc hai chiều; khi nhập tìm thêm người khác trong profile ACL. Preselect được resolve riêng từ backend, không tự tin dữ liệu Intent.

Eligibility gọi lại `private.c_invitee`; picker nhóm hiện có còn kiểm quyền admin, thành viên và invitation pending/rejected. UI vô hiệu hóa người không đủ điều kiện. Đây là thông tin gợi ý; mutation tạo/mời vẫn xác thực lại tại thời điểm ghi. Follow không tự cấp membership, consent hoặc quyền mời. UI không tự xóa quan hệ/block hay bỏ kiểm tra tài khoản private.

## 7. Owner triển khai

Áp migration mới sau nền Task 36/37 (Follow-only/privacy, messaging consent/invitations, capabilities, group avatar) và các migration hiện hữu theo thứ tự ledger. Agent chưa chạy migration hoặc gọi Cloud.

RPC mới, chỉ authenticated được execute:

- `task44_chat_capabilities()`
- `get_chat_customization(p_conversation_id uuid)`
- `set_chat_nickname(p_conversation_id uuid,p_target uuid,p_nickname text,p_expected_version bigint)`
- `set_chat_theme(p_conversation_id uuid,p_theme_id text,p_expected_version bigint)`
- `chat_picker_people(p_query text='',p_after_name text=null,p_after_id uuid=null,p_limit integer=30,p_conversation_id uuid=null,p_target uuid=null)`

Migration mở rộng projection của `private.c_conversation` và `private.get_conversation_members`, giữ chữ ký/grants, media/read ACL, unread count, history, avatar và consent cũ. Helper eligibility private không cấp API execute.

## 8. Chưa xác minh

Chỉ đọc source, rà soát diff, parse XML; không chạy Gradle/unit tests/emulator/E2E/Docker hay SQL. Owner cần xác minh compile/binding và UX trên thiết bị, SQL apply vào schema Cloud thật, RPC/RLS/JWT matrices, version conflict, đổi membership/block trong lúc picker mở, invitation Accept/Reject/Leave, Unicode search, pagination dữ liệu lớn, nickname/theme giữa nhiều thiết bị, reconnect/polling/dispatcher, hủy preview/lưu lỗi/Activity recreation và độ tương phản thực tế. Không tuyên bố runtime PASS.
