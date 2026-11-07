# Task 42 — Stories

Triển khai tại `F:\MeetoMXH`. Chỉ rà soát source/XML/resource/diff; **không build, chạy test/emulator, export APK/AAB, upload thử hoặc deploy Cloud**. Chưa xác nhận biên dịch/runtime.

## Thay đổi

- **Ring:** bỏ viền tím cố định ở Profile; thêm trạng thái có quyền xem, còn hạn và media đọc được cho Profile/Feed/tray. Batch tối đa 200 tác giả, cache 30 giây, tự kiểm tra expiry và invalidation sau đăng/xóa/quay lại Profile. Avatar Messenger/bình luận giữ hành vi cũ. Bỏ tải media vào ImageView ẩn của tray.
- **Viewer:** ảnh/video trong cùng Activity nền tối, insets, chạm trái/phải qua từng tin; vuốt ngang qua người đăng; giữ để pause; mở menu, keyboard, viewer sheet hoặc background đều pause. Từng người được tải đầy đủ metadata tin theo thứ tự backend; progress Canvas chia đúng số đoạn. Ảnh chạy 5 giây sau khi tải; video dùng position/duration thật và Ended, không dùng timer video riêng.
- **Playback:** một player tại một thời điểm; dùng lại monitor surface/first-frame từ Task 38B mà không sửa Reels. Dừng/detach đúng lifecycle, token loại callback cũ, recovery một lần, lỗi có thể bỏ qua. Không tải MP4 hai lần qua Glide chỉ để lấy poster; schema chưa có cover Story video nên dùng placeholder đến first frame.
- **Like/Reply:** một loại tim, optimistic + rollback, khóa request khi đang gửi. Like đi bảng `story_reactions` riêng và notification server có chống trùng. Reply giữ `reply_to_story`, request ID, Block/Private/Message Requests và liên kết Story trong Messenger hiện có; không tạo chat mới riêng.
- **Views:** chỉ gửi view sau ảnh tải hoặc video render frame; dùng RPC idempotent hiện có, không cộng chủ tin. Owner footer hiển thị số thật theo từng tin. Bottom sheet có avatar/tên/thời gian/tim, phân trang 30 người mới xem trước, loading/empty/retry; backend kiểm tra chính chủ. Không hiển thị UUID.
- **Creator:** Photo Picker một lần hoặc CameraX chụp ảnh/quay video; sao chép nguồn vào draft riêng. Canvas 9:16 có nhiều lớp chữ, kéo/pinch/xoay/sửa/xóa, màu/font/cỡ/căn lề/nền/shadow, brush/tẩy/độ dày/xóa/undo/redo. Filter Original/Bright/Warm/Cool/Vintage/B&W/Contrast; fit hoặc crop giữa 9:16 và xoay 90°.
- **Export/publish:** ảnh thành JPEG mới; video dùng timeline thumbnail đã có, trim 1–60 giây, mute, Media3 effects + bitmap overlay, H.264/AAC 720×1280 mục tiêu 3 Mbps. Chữ/nét vẽ được ghép vào thành phẩm. Tiếp mở preview file đã xuất; Đăng mới upload. Kiểm tra size/duration, giữ uploader/finalize/auth và `create_story`; retry giữ snapshot/request ID. Chỉ đóng sau backend xác nhận. Draft có lưu nguồn/composition/bước hiện tại khi Activity tạo lại.

## Thư viện và file

Dùng AndroidX/CameraX, Glide, Media3 ExoPlayer/Transformer/effect 1.11.1; Gradle thêm explicit `media3-effect`. Đã nghiên cứu [PhotoEditor](https://github.com/burhanrashid52/PhotoEditor) (MIT, có Java API), chọn Canvas để dùng chung một model layer và hình học export ảnh/video, tránh thêm một hệ layer/filter riêng.

Java sửa trong `app/src/main/java/com/example/meetomxh/`:

- `StoryViewActivity.java`, `StoryCreateActivity.java`, `ProfileActivity.java`, `NotificationsActivity.java`.
- `adapters/FeedAdapter.java`, `adapters/StoryAdapter.java`, `fragments/ProfileFragment.java`.
- `models/Story.java`, `models/NotificationItem.java`, `supabase/StoryClient.java`.
- Mới: `StoryCameraActivity.java`, `fragments/StoryViewersSheet.java` và `ui/StoryCanvasView.java`, `StoryComposition.java`, `StoryMediaExporter.java`, `StoryProgressView.java`, `StoryRings.java`, `StoryTime.java`.

XML sửa: `AndroidManifest.xml`; layout `activity_story_create`, `activity_story_view`, `activity_profile`, `fragment_profile`; drawable `shape_story_ring`. Thêm `values/story_ids.xml` và 7 vector `ic_story_undo`, `redo`, `brush`, `filter`, `crop`, `rotate`, `eye`. Dependency sửa tại `app/build.gradle.kts`. Các thay đổi Reels từ Task 38B vẫn giữ nguyên trong worktree; Task 42 không sửa source Reels.

## Migration owner cần triển khai

`supabase/migrations/20261106000800_story_experience.sql`, sau các migration Task 36/37 hiện có. Không chạy trong lượt này.

- Bổ sung `get_story`, `get_story_author_stories`, `get_story_states`, `set_story_like`, `get_story_viewers_page`.
- Bổ sung liked và owner-only views_count vào projection hiện có; tái sử dụng bảng Story/views/reactions và notification hiện có.
- RPC chỉ grant authenticated; dùng actor từ phiên đăng nhập, kiểm tra quyền/media/expiry, viewer list chỉ chủ tin. Không mở public bucket, cấp DML trực tiếp hay thay capability guard/ledger.

## Giới hạn

- Cần owner triển khai migration và tự sync/build/kiểm tra. Thiếu RPC mới sẽ hiện lỗi hoặc ring trung tính; không giả dữ liệu thành công.
- Discovery tray vẫn dùng giới hạn 100 tin của RPC hiện có; viewer tải đầy đủ tin của tác giả khi vào nhóm. Quyền được kiểm tra khi mở/resume và tái xác minh mỗi 15 giây khi xem, không có push tức thì cho mọi thay đổi quyền.
- Crop hiện là fit/center-crop 9:16; không crop tự do, AR, kho nhạc hay timeline nhiều track. Layer chạy toàn bộ đoạn video. Tối đa 30 lớp chữ, 200 nét/4096 điểm, 25 bước undo; history undo không được phục hồi qua process recreation.
- Codec/HDR, camera từng máy, gesture và vị trí overlay sau export cần xác nhận thực tế. Export/upload đang chạy bị hủy khi Activity bị hủy; nguồn và composition đã lưu được giữ để thử lại. Không bảo đảm phục hồi bản quay chưa finalize nếu tiến trình chết.
- Client chặn thành phẩm trên 50 MiB; bucket có giới hạn thấp hơn vẫn do pipeline verifier hiện có kiểm tra. Chưa có contract trả giới hạn bucket để preflight riêng.

## Checklist owner — chưa thực hiện

- [ ] Hai tài khoản: ring khi chưa có/đăng/xóa tin cuối/hết hạn; Private/Block và avatar Profile/Feed/tray.
- [ ] Một/nhiều tin ảnh/video: tap, swipe theo người, segmented progress, giữ/pause/menu/keyboard/background; không phát tiếng sai tin.
- [ ] Like/unlike nhanh, offline rollback; notification mở đúng Story; Reply direct/request/rejected và liên kết trong Messenger.
- [ ] Xem lại không tăng view; tự xem không cộng; count từng tin; viewer list hơn 30 người và tim; tài khoản khác gọi thống kê bị từ chối.
- [ ] Gallery/camera chọn một lần, quyền từ chối, tạo lại Activity; nhiều lớp chữ kéo/pinch/xoay, brush/tẩy/undo/redo, filter/crop/rotate.
- [ ] Đối chiếu ảnh/video thành phẩm với preview: overlay, màu, orientation, âm thanh và đoạn trim; kiểm tra nguồn lớn/thành phẩm quá giới hạn.
- [ ] Upload/finalize/create lỗi rồi retry, double-tap Đăng không tạo trùng; chỉ báo thành công khi server xác nhận; tin bị xóa/expired/đổi quyền không tiếp tục hiển thị.
