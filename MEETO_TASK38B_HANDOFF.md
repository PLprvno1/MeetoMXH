# Task 38B — bàn giao source

Workspace thực tế: `F:\MeetoMXH`. Chỉ sửa source và rà soát tĩnh; không build, xuất APK/AAB, chạy test/emulator/camera, upload thử hay deploy Cloud trong Task 38B.

## Nguyên nhân và thay đổi

- **Chọn video hai lần:** callback thư viện của camera gửi `Intent.data`, nhưng composer cũ chỉ đọc `EXTRA_PATH`. Composer không nhận nguồn nên để người dùng chọn lại. Nay đọc URI thư viện, URI camera và extra cũ; chỉ mở picker khi người dùng nhấn **Thư viện/Đổi video**.
- **Giữ video:** sao chép một lần sang thư mục bản nháp riêng trong internal files; không biến `content://` thành filesystem path. Camera giữ file đến khi finalize và bàn giao; editor chỉ xóa bản quay tạm do app sở hữu sau khi sao chép thành công. Bundle giữ nguồn, đoạn cắt, cover, mute, vị trí, bước hiện tại và các asset đã tải lên khi Activity được tạo lại.
- **Preview:** thay `VideoView` bằng Media3 `PlayerView`/ExoPlayer, FIT, poster Glide, loading, phát/tạm dừng, seek chính xác và lỗi có Thử lại. Editor và thông tin đăng là hai bước riêng trong `CreateReelActivity`, không thêm Activity trùng chức năng.
- **Black screen từ source:** ảnh bìa bị ẩn tại `READY` trước first frame; surface ownership phân tán trong bind/recycle; callback media cũ và resume có thể chạy khi holder đã đổi hoặc không attached. Nay một monitor quản lý surface bằng `PlayerView.switchTargetView`, pause/detach khi holder rời cửa sổ, token loại callback cũ, khôi phục vị trí khi quay lại. Giữ `surface_view`, không đổi app sang TextureView.
- **Chẩn đoán/recovery:** debug log chỉ có media ID nội bộ, state, track, kích thước, attachment, first frame và mã lỗi. Sau 5 giây playback READY có thời gian tăng mà chưa có first frame: khôi phục surface một lần; nếu vẫn lỗi thì pause, hiện thông báo/Thử lại. Video không có track hoặc decoder lỗi được phân biệt; không dò màu pixel để suy đoán video đen. Nút/sheet không được tự phát lại audio sau lỗi render.
- **Camera:** thay các Button ký tự Unicode/inset mặc định bằng ImageButton với vector trắng và nền tối; segmented 15/30/60s, shutter tròn với vòng progress, pause/resume, finish, micro và menu hẹn giờ 3/5/10s. Preview giữ tỷ lệ; áp dụng insets và viewport chung khi CameraX cung cấp. Không đổi thời lượng giữa lúc quay. Chỉ đi Editor sau finalize; nếu finalize khi background thì đợi resume mới điều hướng.
- **Timeline:** tối đa 12 thumbnail nhỏ trích trên worker, cache giới hạn 2 MiB, fallback khi không lấy được ảnh. Hai tay nắm điều khiển range tối thiểu 1 giây/tối đa 60 giây; vạch trên điều khiển playhead, kéo vùng chọn hoặc thước dưới di chuyển cả đoạn đến vị trí khác của video dài. Model dùng milliseconds. Preview seek khi kéo, playhead cập nhật khi phát; đến End thì dừng và quay về Start.
- **Xuất và đăng:** Tiếp gọi Transformer 1.11.1, buộc encode video theo timestamp đã chọn sang H.264/AAC, cạnh dài tối đa 1280, mục tiêu 4 Mbps; HDR được yêu cầu tone-map sang SDR. Kiểm tra video track, duration đầu ra và kích thước trước khi mở bước đăng. Chỉ upload file thành phẩm, không upload nguồn. Giữ uploader/auth/verifier và `create_reel` hiện có; retry tiếp tục các bước còn thiếu. Snapshot caption/privacy và request ID được giữ ổn định cho lần đăng; chỉ báo thành công sau RPC xác nhận.

## File sửa/thêm trong Task 38B

Các đường dẫn Java dưới `app/src/main/java/com/example/meetomxh/`:

- `CreateReelActivity.java`, `ReelsCameraActivity.java`, `ReelsCameraController.java`, `ReelsCaptureSession.java`.
- `fragments/ReelsFragment.java`.
- Mới: `ui/ReelPlaybackMonitor.java`, `ui/ReelTrimTimelineView.java`, `ui/ReelClipExporter.java`, `ui/ReelRecordButton.java`.

Các tài nguyên dưới `app/src/main/res/`:

- `layout/activity_reel_create.xml`, `layout/activity_reels_camera.xml`, `layout/item_reel.xml`.
- Mới: `values/reel_camera_styles.xml`.
- Mới trong `drawable/`: `bg_reel_camera_action.xml`, `bg_reel_duration.xml`, `ic_reel_close.xml`, `ic_reel_flash.xml`, `ic_reel_flip.xml`, `ic_reel_gallery.xml`, `ic_reel_mic.xml`, `ic_reel_mic_off.xml`, `ic_reel_pause.xml`, `ic_reel_play.xml`, `ic_reel_timer.xml`, `ic_reel_video.xml`.

Dependency: `app/build.gradle.kts` thêm `media3-transformer:1.11.1`, cùng phiên bản playback hiện có. Không đổi Manifest, applicationId, SQL, backend hay capability guards. `fragment_reels.xml` là thay đổi từ yêu cầu Reels trước đó, không chỉnh thêm trong Task 38B.

## Giới hạn và việc owner cần xác nhận

- Chỉ có bằng chứng đọc source, đọc XML và `git diff --check`; chưa xác nhận biên dịch hoặc runtime, chưa thể khẳng định đã loại bỏ mọi black screen trên thiết bị.
- Owner cần sync dependency rồi build theo môi trường của mình. Khả năng codec, tone-map HDR (nhất là API dưới 29), export, camera, grant của từng photo provider và surface trên từng máy chưa được kiểm tra thực tế. Không thêm TextureView fallback khi chưa có bằng chứng thiết bị cần nó.
- Giới hạn client là 50 MiB theo pipeline/migration hiện có. Backend verifier vẫn kiểm tra giới hạn bucket thực tế; contract hiện tại không trả giới hạn bucket thấp hơn để client preflight trước khi gửi byte. Không bổ sung RPC/schema hoặc tự truy cập cấu hình Cloud.
- Export/upload bị ngắt khi Activity bị hủy cần nhấn Tiếp/Đăng lại; nguồn và lựa chọn đã lưu được giữ. Nếu tiến trình chết giữa lúc CameraX đang ghi/finalize, không bảo đảm phục hồi bản quay chưa hoàn tất.
- Không lấy thumbnail thư viện gần nhất vì chưa có quyền đọc thư viện diện rộng; dùng icon Gallery. Không làm filter, text overlay, nhạc nâng cao hoặc waveform.

## Checklist thủ công cho owner — chưa chạy

- [ ] Thư viện: chọn một video → vào Editor ngay, có hình/tiếng; hủy picker, Đổi video và Back từ bước đăng không làm mất lựa chọn.
- [ ] Camera: quyền cho phép/từ chối, micro bật/tắt, flash thật, đổi camera, 15/30/60s và hẹn giờ; quay → pause/resume → Xong → Editor sau finalize.
- [ ] Timeline: video dọc/ngang, dưới/trên 60 giây; kéo hai tay nắm, playhead, thước để chuyển đoạn; kiểm tra Start/End/Duration và dừng tại End.
- [ ] Xuất: chọn đoạn không trùng keyframe, mute/không mute, ảnh bìa đúng clip; kiểm tra file thành phẩm đúng nội dung, duration và xoay hình.
- [ ] Lifecycle: background/resume và tạo lại Activity tại Editor/bước đăng; hủy export rồi tiếp tục; không mở picker tự động.
- [ ] Đăng bằng tài khoản được phép: privacy, lỗi upload/cover/create và retry; bấm nhiều lần không tạo nhiều bài; không báo thành công sớm; video nguồn lớn hơn 50 MiB vẫn chỉnh được, thành phẩm quá giới hạn bị chặn.
- [ ] Reels: mở lần đầu, vuốt nhanh, quay lại từ Profile/Background; chỉ một player, không có audio tiếp diễn sau lỗi render; thử video codec không hỗ trợ và nút Thử lại.
- [ ] Like, double-tap, comment sheet, share, save, follow và tự chuyển khi hết reel vẫn hoạt động.
