# MEETO — Task 43: bàn giao source

Workspace thực tế: `F:\MeetoMXH`. Chỉ sửa source và rà soát tĩnh; không build, chạy tests/emulator, xuất APK/AAB, upload thử hoặc triển khai Cloud. Không có kết luận runtime PASS.

## 1. File thay đổi trong Task 43

- `app/src/main/java/com/example/meetomxh/CreatePostActivity.java`
- `app/src/main/java/com/example/meetomxh/CreateReelActivity.java`
- `app/src/main/java/com/example/meetomxh/ProfileActivity.java`
- `app/src/main/java/com/example/meetomxh/fragments/FeedFragment.java`
- `app/src/main/java/com/example/meetomxh/fragments/ReelsFragment.java`
- `app/src/main/java/com/example/meetomxh/repositories/FeedRepository.java`
- `app/src/main/java/com/example/meetomxh/repositories/ReelsRepository.java`
- `app/src/main/java/com/example/meetomxh/storage/SupabaseStorage.java`
- `app/src/main/java/com/example/meetomxh/supabase/PostUpdates.java`
- `app/src/main/java/com/example/meetomxh/supabase/ErrorMapper.java`
- `app/src/main/res/layout/activity_create_post.xml`
- `app/src/main/res/drawable/ic_reel_privacy.xml`
- `app/src/main/res/drawable/ic_reel_delete.xml`
- `supabase/migrations/20261106000900_unified_video_reel_management.sql`
- Báo cáo này: `MEETO_TASK43_HANDOFF.md`.

Đây là phần bổ sung trên source Task 38B/42 đang có trong working tree, không thay thế báo cáo hoặc thay đổi của các task trước. Không xử lý những tài liệu/PPT đang bị xóa ngoài phạm vi task.

## 2. Đăng video chung Feed/Reels

Audit xác nhận Reel chính là `public.posts` với `is_reel=true`; `get_feed` không loại các bản ghi này. Dùng một post ID, một caption/audience, một video trong `post_media`, chung Like/Comment/Share/Save và thống kê. Không tạo Reel thứ hai, không liên kết hai bản sao nội dung.

Create Post có switch Material “Đồng thời chia sẻ lên Reels”, mặc định tắt. Text/ảnh không có switch. Chỉ bài mới có đúng một video cục bộ được hỗ trợ; album hoặc ảnh/video trộn có giải thích rõ. Tắt switch tiếp tục gọi RPC đăng bài hiện có.

Metadata được đọc ở background. Video đủ điều kiện đi thẳng vào upload, không encode. Nếu dài hơn 60 giây hoặc vượt 50 MiB, bật switch mở chính `CreateReelActivity` ở chế độ editor-only. Có nút chỉnh video cho video ngắn. Editor nhận URI hiện có, dùng timeline/export Media3 Task 38B, trả file MP4 riêng về Create Post và tuyệt đối không upload/publish trong chế độ này. Hủy giữ nguyên video và caption của bài viết. Đoạn sau cắt được dùng cho cả Feed lẫn Reels. Editor chỉ sao chép bytes khi chọn toàn bộ MP4 đã hợp lệ, không bật mute; thay đổi đoạn/mute mới encode.

Trước upload chung, RPC policy trả giới hạn thực tế `min(50 MiB, social-media.file_size_limit)`, xác nhận bucket private và cho phép MP4. Vượt giới hạn sẽ dừng trước upload và cho chỉnh đoạn ngắn hơn; không tự đổi Private thành Public. Server finalize/create_reel tiếp tục kiểm tra metadata/object/owner/MIME/bucket hiện hữu.

## 3. Retry, upload và chống trùng

`create_post_reel_with_mentions` gọi nghiệp vụ `private.create_reel`, bind request hash cho mentions/tags, rồi trả `reel_card` trong cùng transaction. Lỗi bước nào rollback nội dung; không có hai bước đăng riêng với thông báo thành công giả. Caption tối đa 2.000 ký tự; audience chỉ public/followers/private.

Create Post cố định snapshot caption/audience/duration/mentions/tags khi bắt đầu upload chung; khóa chỉnh nội dung đến khi hoàn tất hoặc người dùng thoát. Request ID gắn với UUID bản nháp, không gắn với asset có thể upload lại. Chỉ complete request sau callback thành công có `id` và `is_reel` hợp lệ. Private SharedPreferences lưu snapshot và asset đã nhận trước khi gọi create RPC; khôi phục cùng bản nháp qua Bundle sẽ ưu tiên biên nhận này để tránh khoảng trống giữa save-state và callback upload. Lỗi lưu biên nhận dừng trước create RPC.

Uploader Post giữ upload request key đến sau callback giao asset cho UI. Migration cho phép khôi phục reservation Post đã READY hoặc có object upload xong: revalidate bằng finalizer hiện có, trả READY; Android kiểm tra size và bỏ qua upload bytes. Nhánh pending chưa có object vẫn dùng uploader/TUS hiện có. Không bypass quota/finalizer; các mục đích Story/Message/avatar giữ cơ chế hoàn tất request trước đó. Video và thumbnail/cover là các file khác nhau; luồng đăng chung dùng cover nullable, không upload thêm bản video.

## 4. Menu Owner / Viewer

Owner được xác định bằng `post.authorUid == authenticated uid`, không theo adapter position. Owner có quyền riêng tư, xóa, chia sẻ và đóng; không có Không quan tâm/Báo cáo chính mình. Menu quản lý dùng BottomSheet hiện hữu, icon khóa/thùng rác, accent hồng; privacy có loading/error/save. Viewer giữ Quan tâm/Không quan tâm trên thiết bị, Share và có Báo cáo qua hệ thống moderation hiện hữu, target `post`. Like/Comment/Save/Follow, caption, search và autoplay tiếp tục dùng các đường đi hiện tại.

## 5. Quyền riêng tư và đồng bộ

`set_reel_audience` khóa row, kiểm tra active actor, owner, is_reel, published, expected version và audience; chỉ sửa audience/version/updated_at. Không detach media hoặc sửa owner/media ID/mentions/tags. Tài khoản private không được đổi sang public. BottomSheet ghi rõ quyền hiện tại và tác động chung Feed/Reels; chỉ báo thành công sau khi ID/audience trả về khớp.

`PostUpdates.content` cập nhật item/cache Reels hoặc gỡ item theo ID và refresh Feed. Repository chặn kết quả tải cũ ghi đè sau mutation. Profile reload grid Reels khi quay lại; Saved/Search/Feed giữ các lượt reload khi resume hiện có. Reels xác minh quyền trước mỗi lần prepare/resume và probe Reel hiện tại mỗi 15 giây. Nếu server xác nhận unavailable, dừng/clear/detach player và gỡ item, chuyển Reel kế tiếp nếu có. Lỗi mạng được giữ riêng, không diễn giải thành xóa. Probe cũng đối chiếu Like/Comment/Save/counts khi quay lại Reels. Đổi privacy thành công tại máy Owner không tạo lại player hay đổi vị trí cuộn.

Các truy vấn, media URL và link cũ tiếp tục qua `can_read_post`/`can_read_media` và resolver hiện có. Không mở public bucket hoặc nhúng service_role.

## 6. Xóa và media dùng chung

Dialog giải thích đây là cùng nội dung Feed/Reels và xóa tác động cả hai nơi; Hủy/Xóa Reel, khóa nút khi đang gọi RPC, lỗi giữ item và cho retry. `delete_reel` chỉ owner active được gọi, kiểm tra version, soft-delete idempotent. Admin tiếp tục qua RPC moderation có role guard hiện có; không cấp thêm quyền admin cho RPC owner.

Sau soft-delete, kiểm tra tham chiếu còn sống ở posts (published/hidden), reel cover, profile, conversation avatar, stories, messages và album dựa trên source post. Chỉ asset không còn tham chiếu mới được đưa vào `private.retire_asset`/outbox cleanup hiện có. Cover trigger có cùng kiểm tra. `private.delete_post` legacy chuyển riêng Reel sang lifecycle mới, phần bài thường giữ hành vi cũ. Android không xóa Storage. Feed/Profile cũng giải thích tác động chung khi xóa Reel từ card bài viết.

## 7. Owner cần triển khai backend

Migration forward-only: **`supabase/migrations/20261106000900_unified_video_reel_management.sql`**, chưa chạy.

RPC mới: `get_reel_upload_policy()`, `create_post_reel_with_mentions(...)`, `set_reel_audience(...)`, `delete_reel(...)`, `get_reel_access(...)`. Migration còn cập nhật `private.prepare_direct_upload`, `private.delete_post`, cover-delete trigger function và thêm helper kiểm tra tham chiếu. Tất cả RPC mới chỉ authenticated; actor/owner kiểm tra ở backend, không thêm table DML grant hay đổi RLS. Các lời gọi Android vẫn qua Task 36 `SocialContract`.

Cần inventory/deploy đầy đủ migrations nền tảng Reels, direct upload, mentions/albums và Task 36/37 theo thứ tự, sau đó áp dụng migration này trước khi dùng client mới. Reels playback nay phụ thuộc `get_reel_access`; thiếu RPC sẽ hiện lỗi/retry thay vì phát khi chưa kiểm tra quyền. Migration không đòi apply riêng Task 42 Stories nếu các dependency nền tảng đã đủ.

## 8. Giới hạn và các điểm owner cần tự kiểm tra

- Chỉ hỗ trợ bài mới có một video; không chuyển bài cũ/album hỗn hợp sang Reel. Không tự cắt caption dài hơn 2.000 ký tự.
- Trim/export, codec/HDR/audio, PhotoPicker/SAF, quay màn hình khi upload/khôi phục Activity và các UI thật chưa được chạy. Nhà cung cấp URI không báo size bị từ chối chia sẻ Reels, vẫn có thể đăng bài thường.
- Bản nháp được khôi phục khi Android trả Bundle tương ứng; chưa có màn quản lý draft để tìm lại sau force-stop/mở Activity mới. Bản nháp đã chọn/asset chưa attach bỏ dở phụ thuộc cleanup hiện có; không cam kết phục hồi recording/process death ngoài contract Task 38B.
- Preflight giới hạn bucket là snapshot tại thời điểm gọi; thay đổi cấu hình/quota sau đó vẫn bị Storage/finalizer chặn. Video duration được Android đọc lại và output được xác thực; backend direct-upload hiện có xác minh Storage metadata, không giải mã video bằng Edge Function.
- Thu hồi quyền ở thiết bị khác không có push tức thời: nội dung đã buffer có độ trễ tối đa chu kỳ probe khi app đang foreground và mạng hoạt động. Khi background/resume/swipe/link mới đều kiểm tra lại. Không thể thu hồi bytes đã được tải xuống thiết bị trước đó.
- Cần tự xác nhận hai tài khoản với public/followers/private/block, Like/Comment/Save chung ID, retry mất phản hồi create/upload, bucket nhỏ hơn 50 MiB, version conflict, xóa Reel đầu/giữa/cuối/duy nhất, Profile/Saved/Search và link trong Messenger sau thu hồi quyền/xóa.
- Rà soát tĩnh chỉ bao gồm XML, ID/resource, luồng callback/lifecycle/SQL và diff; không thay thế Java compilation, SQL execution hay runtime validation.
