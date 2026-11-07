# MEETO — Task 46: Fullscreen Post Viewer

Source đã triển khai, chỉ rà soát tĩnh. **Chưa build/unit test/emulator/E2E; chưa runtime PASS.** Không chạy SQL hoặc deploy Cloud. Không thêm dependency/Gradle, không sửa Reels/Stories/Messenger/Camera hoặc Post publishing.

## 1. Files

Thêm mới:

- `app/src/main/java/com/example/meetomxh/PostMediaViewerActivity.java`
- `app/src/main/java/com/example/meetomxh/storage/PostImageDownload.java`
- `app/src/main/java/com/example/meetomxh/supabase/PostViewerUpdates.java`
- `app/src/main/res/layout/activity_post_media_viewer.xml`

Sửa:

- `app/src/main/AndroidManifest.xml`: internal Activity, WRITE_EXTERNAL_STORAGE chỉ maxSdkVersion 28.
- `adapters/FeedAdapter.java`: click ảnh thường/shared original → viewer mới; dùng lifecycle observer cập nhật đúng card.
- `repositories/FeedRepository.java`: cập nhật đúng row trong cache, giữ cursor và các trang đã tải.
- `fragments/FeedFragment.java`, `ProfileActivity.java`, `SavedPostsActivity.java`: khi trở lại từ viewer không reset trang/scroll; Saved loại row vừa bỏ lưu, Profile bỏ row vừa xóa.

Các Java rút gọn đường dẫn ở trên nằm dưới `app/src/main/java/com/example/meetomxh/`. Working tree có thay đổi từ các task trước; không coi toàn bộ git diff là Task 46.

## 2. Viewer và dữ liệu

- Nền đen, icon trắng, tim hồng, header đóng/more 52dp, system bars/insets; ảnh và thanh tương tác là các vùng riêng.
- Nhận Post ID + asset reference/index; không nhận album URL làm nguồn chân lý. Tải lại bằng `PostDetails.resolve` / `get_post`, resolve shared source và giữ ACL hiện tại.
- Album chỉ gồm ảnh được backend trả về, theo thứ tự media; vuốt ngang, chỉ số ảnh, pinch/double-tap zoom qua `ZoomableImageView` hiện có. Video giữ đường mở player cũ; Reel bị từ chối nếu đi vào Activity mới qua Intent không đúng.
- Glide/asset loader hiện có; chỉ decode ảnh hiện tại theo kích thước màn hình, không tải toàn bộ full-resolution album vào RAM. Không prefetch cả album; disk cache NONE, skip memory cache. Zoom phóng ảnh decode theo màn hình, chưa phải tiled original-resolution zoom.
- Thông tin author/time/audience, caption 2 dòng và Xem thêm/Thu gọn, vùng nội dung cuộn giới hạn khoảng 1/4 màn hình.
- Loading, lỗi ảnh/retry riêng và lỗi bài/quyền xem; không ảnh giả. Lưu index/ref/caption expansion qua recreation. Request generation và clear Glide ngăn callback ảnh cũ ghi đè ảnh mới.
- Refetch khi resume/lấy lại window focus, và mỗi 30 giây khi Activity resumed. Nếu backend không đọc được bài, xóa ảnh hiện tại khỏi view và vô hiệu hóa tương tác; không bypass ACL. Đây là polling/refetch, không hứa thu hồi bytes đã tải ngay tức thì.

## 3. Actions

- Like/Unlike: `set_post_reaction(p_post_id,p_reaction)`, optimistic heart/count tại viewer, rollback khi RPC lỗi; sau ACK dùng count/reaction backend và refetch.
- Comment: `CommentsBottomSheetFragment` hiện có với đúng canonical Post ID; giữ index ảnh khi đóng; nhận count qua `PostUpdates.counts` hiện có.
- Share: `ShareActions.show`, cả Feed share/Messenger/link theo workflow cũ. Gửi Post ID gốc, không gửi ảnh rời hay private media URL.
- Save/Unsave: `set_saved_post`, kiểm backend trả đúng post_id/saved rồi mới đổi UI. Không optimistic báo lưu thành công giả. RPC idempotent và unique saved row hiện có.
- Menu có Tải ảnh, Chia sẻ, Lưu/Bỏ lưu. Chủ bài có lối chỉnh sửa bằng composer hiện có và xóa bằng RPC/version/confirm hiện có; không sửa publishing code. Menu quản lý tại màn nguồn vẫn giữ.

`shares_count` có sẵn trong post projection: hiển thị số backend, không tăng giả khi mở Share Sheet, copy link hoặc gửi Messenger. Counter phản ánh share published theo hợp đồng backend; không đồng nghĩa số lần gửi Messenger.

## 4. Download

Chỉ chạy sau thao tác Tải ảnh của người dùng. Đóng băng Post ID + ảnh đang chọn tại lúc nhấn, kể cả khi sau đó vuốt ảnh khác. Worker:

1. `get_post` kiểm ảnh còn thuộc bài và MIME ảnh; `SupabaseStorage.info` kiểm asset ACL.
2. Authenticated GET bytes qua JWT/apikey trong bộ nhớ, không public/sign URL hoặc truyền JWT cho DownloadManager/app ngoài. Không follow redirect có thể mang Authorization sang host khác.
3. Kiểm Content-Type/giới hạn 50 MiB, stream buffer 32 KiB sang MediaStore; không giữ full image bytes trong RAM.
4. Kiểm lại quyền bài/asset trước khi công bố file; lỗi/hủy thì cleanup đúng URI tạm vừa tạo.

Android 10+ dùng `Pictures/Meeto`, `RELATIVE_PATH` và `IS_PENDING`; không xin storage permission. Android 7–9 xin WRITE_EXTERNAL_STORAGE chỉ khi người dùng yêu cầu tải, rồi ghi MediaStore với đường dẫn Pictures/Meeto. Có trạng thái tải/thành công/lỗi. Từ chối quyền không chặn xem ảnh hoặc tương tác. Lưu selection khi đang chờ permission qua recreation.

Cơ chế permission/MediaStore đối chiếu [Android Developers — shared media](https://developer.android.com/training/data-storage/shared/media). Bytes đã lưu thành công vào thư viện là bản sao do người dùng yêu cầu; RLS không thu hồi bản sao thiết bị. Chưa có bảo đảm cleanup khi process bị kill hoặc thiết bị tắt giữa lúc ghi.

## 5. Đồng bộ và scroll

`PostViewerUpdates` giữ tối đa 50 snapshots sự kiện theo session; không lưu private content ra disk. FeedAdapter observe theo lifecycle và remove observer khi detach. Kết quả đúng Post ID cập nhật likes/comments/shares/reaction/saved, nội dung/audience/media mới khi version phù hợp. Không tăng counter bằng số hardcode.

FeedRepository áp dụng đúng row mà không reset cursor; Feed/Profile/Saved bỏ lần reload toàn danh sách khi quay về từ viewer. Saved loại row bỏ lưu; delete ACK loại card. Khi đóng viewer có một refetch tĩnh không giữ Activity để hòa giải kết quả mutation bị mất callback. Khi mạng lỗi, refetch có thể không hòa giải ngay; manual refresh/mở lại sẽ lấy backend mới. Không thêm Realtime thứ hai.

## 6. Backend và giới hạn xác minh

Không cần migration mới. Dùng RPC/Storage/Task 36 guard hiện có. Không đổi bucket, RLS, grants hoặc tạo Likes/Comments/Save mới.

Đã rà soát diff/entry points/RPC args và parse XML; chưa chạy Java compiler/Gradle, unit test, emulator/E2E hoặc Cloud requests. Owner cần kiểm:

- Compile và ViewBinding, Pixel 4/insets/font scale/dark bars.
- Zoom/vuốt nhanh, image load lỗi/retry, đổi quyền/block/delete trong lúc xem, shared original unavailable.
- Like ACK/lỗi/network ambiguity; comments/replies/count; Share Feed/Messenger; Save/Unsave và scroll Feed/Profile/Saved qua nhiều trang.
- Caption dài và mixed image/video albums (viewer ảnh chỉ duyệt ảnh).
- Gallery download Android 7–9 permission, Android 10+ scoped storage, storage-full, network failure, cancel/recreation/process death, MIME/size và private ACL.
- Owner edit/delete và các policy nhóm trên schema Cloud thật. Không tuyên bố những nhánh này đã runtime PASS.
