# Messenger list + chia sẻ qua tin nhắn

## A. Đã sửa gì

Danh sách Tin nhắn: tap mở chat như cũ; giữ lâu có haptic và bottom sheet Meeto với icon, ripple, màu Light/Dark. Có Tắt/Bật thông báo, Lưu trữ/Bỏ lưu trữ, Xóa đoạn chat; chỉ chat private có Chặn. Chặn/xóa cần xác nhận. Sau RPC thành công, item cập nhật ngay; polling cũ tạm thời không ghi đè mute/archive vừa cập nhật. Mute dùng icon riêng, không ghép emoji vào tên.

Chia sẻ Messenger: thay dialog checkbox mặc định bằng bottom sheet có search, RecyclerView và nút Hủy/Gửi. Mỗi item có avatar tròn 48dp bên trái, tên giữa, MaterialCheckBox bên phải. Avatar thật dùng private asset loader hiện có; thiếu/lỗi ảnh dùng nền tròn màu Meeto + chữ đầu tên, nhóm dùng icon nhóm riêng. Click cả dòng để chọn; nền/border/checkbox phản ánh selection. Tìm theo tên không gọi lại API, giữ selection khi lọc/tải thêm; Gửi chỉ bật khi đã chọn ít nhất một nơi.

### File sửa

- `app/src/main/java/com/example/meetomxh/adapters/InboxAdapter.java`
- `app/src/main/java/com/example/meetomxh/fragments/InboxFragment.java`
- `app/src/main/java/com/example/meetomxh/supabase/ShareActions.java`
- `app/src/main/res/layout/item_inbox.xml`

### File thêm

- `app/src/main/java/com/example/meetomxh/supabase/ConversationListActions.java`
- `app/src/main/java/com/example/meetomxh/supabase/ConversationVisibility.java`
- `app/src/main/java/com/example/meetomxh/supabase/MessengerRecipientSheet.java`
- `app/src/main/res/layout/item_messenger_action.xml`
- `app/src/main/res/layout/item_messenger_recipient.xml`
- `app/src/main/res/layout/bottom_sheet_messenger_recipients.xml`
- `app/src/main/res/drawable/ic_messenger_block.xml`
- `app/src/main/res/drawable/bg_messenger_avatar.xml`
- `app/src/main/res/drawable/bg_messenger_recipient.xml`
- `app/src/main/res/color/messenger_checkbox_tint.xml`
- Báo cáo này.

## B. Logic áp dụng

| Action | Cách thực hiện |
|---|---|
| Chặn | Private chat, xác định peer từ participant hiện có; gọi `set_block(p_target,p_blocked=true)` như Conversation Details. Không hiện với group, không đổi follow/privacy logic. Item bị chặn được loại khỏi UI trong khi chờ snapshot backend; unblocking ở luồng hiện có vẫn có thể làm item xuất hiện lại. |
| Mute/unmute | Tái sử dụng `set_conversation_muted`, server lưu `conversation_mutes` theo conversation/user; đổi icon ngay sau success. Không đổi unread/send/receive hay realtime. |
| Archive | Tái sử dụng `set_conversation_archived`, server cập nhật `conversation_members.archived_at` của actor. Item rời Inbox chính, vẫn có thể truy cập qua mục Đã lưu trữ hiện có; long press ở đó có Bỏ lưu trữ. Policy backend hiện tại giữ archive khi có tin mới. |
| Xóa đoạn chat | **Ẩn local theo tài khoản trên thiết bị**, lưu SharedPreferences key conversation + high_water đã thấy. Cả Inbox/search và share recipient sheet loại conversation có high_water không vượt mốc. Tin nhắn mới tăng sequence làm item hiện lại; archive filter vẫn được tôn trọng. Không xóa conversation, membership, message, media, read marker hoặc lịch sử phía người khác. |

Không tìm thấy RPC hide/delete conversation for self hiện hành. Chọn local persistence vì yêu cầu cho phép hide-for-self và tránh migration/backend không cần thiết. Màn xác nhận nói rõ “chỉ ẩn trên thiết bị này”. Đây **không phải** xóa lịch sử server, cấm truy cập chat cũ hoặc đồng bộ trạng thái xóa trên mọi thiết bị. Mở qua hồ sơ vẫn có thể đọc lịch sử theo quyền server hiện hành.

Recipient loader gọi `get_inbox` từng trang 30, không lấy archive; private chat dùng `get_conversation_members` hiện có để lấy tên/biệt danh và avatar đã ACL-filter, group dùng title/avatar của conversation. Tải thêm là thao tác người dùng, không crawl tự động toàn hộp thư. Search lọc danh sách đã tải; empty state nhắc tải thêm nếu còn trang. Selection gắn với conversation ID, không gắn vị trí checkbox.

Gửi giữ nguyên `send_shared_post(p_conversation_id,p_original,p_client_request_id)`, canonical post/reel source và `RequestIds` fingerprint cũ. Gửi tuần tự; khi lỗi, retry tiếp đúng index chưa thành công, không gửi lại các nơi trước đó. Thông báo lỗi nêu số nơi đã gửi. Thêm session generation guard xuyên chuỗi gửi/retry; sheet thuộc lifecycle Activity, hủy callback/request tải khi dismiss và đóng khi đổi identity.

## C. Source audit và phần đã nối logic

Đã nối callback long press, các RPC mute/archive/block, local hide/filter, recipient selection và đường gửi share thực trong source. Không có action giả/mock hoặc nút chỉ đổi UI mà không gọi persistence tương ứng.

Đã đối chiếu source SQL mute ở `20261103001000_messenger_ux.sql`, per-user archive ở `20261106000200_messaging_consent.sql`, participant avatar/nickname projection ở `20261107000300_chat_customization_picker.sql`; tái sử dụng `AvatarViews.loadWithoutPersonFallback` và `ChatCustomizationUi.own`.

Kiểm tra tĩnh: XML 8/8 parse được; lexical delimiter check 6/6 Java file sửa/thêm; rà callback/session, selection theo ID và payload share. Đây không phải Java compilation hoặc UI/runtime PASS. Không sửa migration, deploy Cloud, chạy build/test/emulator hoặc tạo APK trong lượt này. Không thay thuật toán polling/realtime, Music, Comments hoặc Admin.

## D. Lưu ý owner kiểm thử thủ công

- Private/group long press; tap bình thường; confirm/hủy Chặn/Xóa; mute icon và mở app lại.
- Archive/Bỏ lưu trữ và trạng thái riêng từng người; đối phương vẫn thấy chat như cũ.
- Local hide tồn tại sau mở lại app, không ảnh hưởng tài khoản khác; tin mới làm hiện lại; archive vẫn ẩn khỏi Inbox chính.
- Avatar thật/thiếu/lỗi, tên/biệt danh nhóm/cá nhân; Light/Dark, font lớn, keyboard và màn hình nhỏ.
- Search giữ chọn, tải thêm/empty/error, Gửi disabled khi chưa chọn; gửi Post/Reel nhiều nơi, timeout/retry và đổi account.
- Cloud phải có các RPC hiện có đúng contract. Xóa local không đồng bộ đa thiết bị; nếu muốn xóa lịch sử cho riêng mình trên mọi thiết bị thì cần một task backend forward-only riêng, chưa được thêm ở đây.

**SOURCE IMPLEMENTED: YES. RUNTIME VERIFIED: NO.**
