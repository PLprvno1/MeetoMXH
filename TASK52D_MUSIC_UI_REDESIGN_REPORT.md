# MEETO — Task 52D: Music UI/UX chung cho Story và Reels

Ngày bàn giao: 07/11/2026 (Asia/Bangkok).

**SOURCE IMPLEMENTED. RUNTIME VERIFIED: NO.** Chỉ sửa và rà source; không chạy Gradle, unit test, emulator, E2E hoặc tạo APK.

## 1. Phạm vi và các entry point

Đã tìm toàn bộ call site của `OpenverseMusicPickerActivity` và `OpenverseMusicEditor.show` trong Java source. Có hai host: `CreateReelActivity` và `StoryCreateActivity`. `CreatePostActivity` mở chính `CreateReelActivity` với `EXTRA_EDIT_ONLY`, vì vậy dùng cùng Picker, item, mini player và Editor với Reel trực tiếp. Story ảnh/video cũng dùng một host và một Editor; khác nhau ở ngữ cảnh media.

Không thêm Picker riêng hoặc lưu lựa chọn nhạc vào singleton dùng chung giữa Story/Reel. Các lựa chọn đang chỉnh thuộc ViewModel của từng Activity và được gắn với đường dẫn media draft cùng session generation.

## 2. Danh sách nhạc chung

Thêm `item_openverse_music.xml`, được inflate bởi Picker cho mọi entry point:

- Tên bài tối đa hai dòng.
- Tác giả · nguồn · thời lượng · CC0.
- Đúng hai nút: **Nghe thử**, **Chọn bài**.

Đã bỏ nút Nguồn/Giấy phép/Ẩn và các handler tương ứng khỏi từng item. Metadata nguồn, license, creator, attribution và xác minh CC0 vẫn do client Task 52C xử lý. Thông báo CC0/bản quyền ở đầu Picker vẫn được giữ. Các exclusion preferences đã lưu trước đây vẫn được tôn trọng khi lọc kết quả; task này không xóa dữ liệu cũ của người dùng.

## 3. Một mini player, hai chế độ

`MusicMiniPlayer.java` cùng `view_music_mini_player.xml` được dùng trong cả Picker và Editor. Component sở hữu một Media3 Player tại một thời điểm, cập nhật vị trí, hỗ trợ seek, play/pause, phát lại sau khi kết thúc và nút ×. Đóng component sẽ release player, không xóa cấu hình nhạc.

**A — Preview bài trong danh sách:** hiển thị tên/tác giả, timeline toàn bài, play/pause, × và **Chọn nhạc**. Chỉ mở sau `downloadValidated`; không preload danh sách. Chọn nhạc gọi cùng đường `validateAndOpen(track, true)` với nút Chọn bài trong item, giữ cơ chế Detail TTL/cache/xác minh hiện hành rồi trả `EXTRA_TRACK_JSON` về đúng host.

**B — Preview đoạn đang chỉnh:** hiển thị tên bài, timeline bắt đầu từ 0 trong phạm vi đoạn sử dụng, play/pause, × và **Xác nhận**; không có Chọn nhạc. Xác nhận đóng preview và nhắc nhấn Lưu để áp dụng. Không gọi upload, export hoặc publish. Nút × chỉ đóng preview, giữ các slider và working draft. Thay đổi slider đóng bản nghe thử cũ để lần nghe tiếp theo dùng đúng cấu hình mới.

Với Reel và Story video, host cung cấp preview factory dùng lại `MusicComposition.video` + `CompositionPlayer`, cùng source clip, start/end, trạng thái mute và hai mức âm lượng. Không sửa thuật toán mix/export; preview mini không thêm video effects vì không hiển thị hình, còn preview hình/hiệu ứng và export của host được giữ nguyên. Host player được release trước khi mở Music Editor; guard chặn tự khởi động player nền khi sheet đang mở.

Với Story ảnh, dùng ExoPlayer phát file nhạc local với clipping start/end và musicVolume; không tạo audio video gốc giả. Timeline clip được tính theo duration Story ảnh, không theo toàn bộ bài. Kiểm tra nguồn đủ dài và tolerance 300 ms được giữ, không bật loop.

## 4. Music Editor và vòng đời draft

Editor chung tiếp tục có điểm bắt đầu nhạc, âm lượng nhạc, âm lượng video gốc, đổi bài, bỏ nhạc và Lưu. Story ảnh ẩn điều khiển âm lượng gốc, giữ slider thời lượng 5–60 giây. Video dùng đúng độ dài clip của host; không thay đổi start/end của video để ép nhạc vừa.

Các slider cập nhật một bản sao `working`, không sửa lựa chọn đã lưu trong host. **Lưu** mới gọi `callback.saved`; **Bỏ nhạc** gọi `removed`. Xác nhận mini player không bỏ qua Lưu. Đổi bài giữ working draft riêng; nếu hủy Picker, Editor có thể khôi phục các giá trị đang chỉnh thay vì tự lưu chúng vào media draft.

Working draft được lưu qua Activity ViewModel và Bundle, kèm media path/session generation. Editor dừng preview khi ON_STOP và mở lại cấu hình khi host resume hoặc hoàn tất khôi phục media. Picker giữ track đã xác minh và vị trí nghe qua ViewModel/Bundle; sau recreation/resume, mini player mở ở trạng thái tạm dừng nếu file cache còn hợp lệ. Không tự tải lại audio để khôi phục preview. Không tiếp tục phát ngầm khi Activity dừng.

Các callback cũ vẫn bị chặn bởi request generation/session guard. State Editor không giữ Activity/player trong ViewModel; listener lifecycle được gỡ khi dismiss. Lưu/đổi/bỏ nhạc đóng và release mini player trước callback host. Session counter tránh dismiss callback cũ ghi đè trạng thái sheet mới. Host resume preview sau Lưu hoặc đóng sheet; không phát hai player song song theo đường điều khiển source.

Nếu cache hết hạn, yêu cầu đổi/bỏ nhạc được hiển thị; không giả lập preview hoặc đổi metadata để bỏ qua validation. Việc khôi phục khi process bị hệ điều hành hủy vẫn phụ thuộc media draft/cache local còn tồn tại.

## 5. Light/Dark, màn hình nhỏ và insets

Item và mini player dùng cùng token DayNight của Meeto: surface, primary container, text primary/secondary và outline có sẵn. Hai action chính có cùng kích thước, khoảng cách và kiểu chữ; title/creator được giới hạn dòng. Không thêm màu nền trắng cố định.

Picker đặt mini player dưới vùng danh sách, trong root xử lý system bar/cutout/IME insets hiện có. Khi bắt đầu preview/chọn bài, bỏ focus và đóng keyboard để dành chỗ cho mini player. Editor chuyển sang NestedScrollView, mở sheet expanded, nhận bottom system/IME inset và cuộn tới mini player khi hiển thị. Các nút chỉnh và Lưu vẫn nằm trong nội dung cuộn, không bị đặt bên ngoài sheet.

Đây là cấu trúc source; chưa đo/chụp UI trên thiết bị, màn hình nhỏ hoặc font scale lớn.

## 6. Ma trận xác nhận source-level

**SI = SOURCE IMPLEMENTED / đã đối chiếu call site. Mọi ô dưới đây đều chưa RUNTIME VERIFIED.**

| Tính năng | Reel | Create Post video | Story ảnh | Story video |
|---|---|---|---|---|
| Music Picker chung | SI | SI, qua EXTRA_EDIT_ONLY | SI | SI |
| Item chỉ 2 nút | SI | SI | SI | SI |
| Mini player nghe thử | SI, mode A | SI, mode A | SI, mode A | SI, mode A |
| Chọn bài | SI, result extra | SI, result extra | SI, result extra | SI, result extra |
| Mini player nghe đoạn đã chỉnh | SI, mode B bản phối | SI, mode B bản phối | SI, mode B nhạc cắt | SI, mode B bản phối |
| Lưu lựa chọn | SI, callback.saved | SI, callback.saved | SI, callback.saved | SI, callback.saved |
| Quay lại Editor đúng draft | SI, media path/session | SI, giữ output-path/result contract | SI, giữ canvas model | SI, giữ canvas/source clip |

Các đường điều hướng đã đối chiếu:

1. Reel Editor → Picker → nghe thử → chọn item/mini → Music Editor → Lưu → Reel Editor.
2. Create Post → CreateReelActivity(EXTRA_EDIT_ONLY) → cùng Picker/Editor → quay lại Reel Editor → luồng trả output/metadata về đúng Create Post draft hiện có.
3. Story ảnh → cùng Picker/Editor → Lưu → cập nhật music, giữ canvas chữ/filter/sticker, tiếp tục đường render MP4 hiện có.
4. Story video → cùng Picker/Editor → Lưu → giữ source/start/end/canvas, tiếp tục preview/export hiện có.

Create Post không cần thêm UI riêng; không thay launcher/result handler, caption/mentions/tags, output path hoặc metadata receipt. Story không có thao tác reset canvas trong các thay đổi UI này.

## 7. File thay đổi

Thêm:

- `app/src/main/java/com/example/meetomxh/music/MusicMiniPlayer.java`
- `app/src/main/res/layout/item_openverse_music.xml`
- `app/src/main/res/layout/view_music_mini_player.xml`
- `TASK52D_MUSIC_UI_REDESIGN_REPORT.md`

Sửa:

- `app/src/main/java/com/example/meetomxh/music/OpenverseMusicPickerActivity.java`
- `app/src/main/java/com/example/meetomxh/music/OpenverseMusicEditor.java`
- `app/src/main/java/com/example/meetomxh/CreateReelActivity.java`
- `app/src/main/java/com/example/meetomxh/StoryCreateActivity.java`
- `app/src/main/res/layout/activity_openverse_music_picker.xml`
- `app/src/main/res/layout/bottom_sheet_music_editor.xml`

Không sửa Supabase Cloud, migration 00900/01000, Comments Task 53, `OpenverseMusicClient`, `OpenverseMusicCache`, `OpenverseTrack`, `MusicComposition`, exporter, RPC/upload hoặc Create Post publishing. Các API request, CC0 rules, rate-limit/cache Task 52C và metadata Task 52B giữ nguyên.

## 8. Kiểm chứng và giới hạn

Đã parse XML 4/4 layout thay đổi/thêm; kiểm cấu trúc item có đúng 2 Button; lexical delimiter check 5/5 Java file thay đổi/thêm; rà toàn bộ Picker/Editor call sites và lifecycle/result flow. Không tìm thấy handler/nút Nguồn, Giấy phép, Ẩn trong item/Picker mới. Đây không phải Java compilation hoặc Android runtime test.

Owner cần kiểm thử cả bốn luồng trong ma trận, đặc biệt CompositionPlayer preview không có video surface, seek trên đoạn clip, tỷ lệ âm lượng/mute, keyboard/bottom-sheet, xoay màn hình khi đang chỉnh/đang nghe, hủy Picker khi Đổi bài, cache hết hạn và logout. Cần xác minh tiếp preview/export/upload trên dependency/device thực; task này không tuyên bố các bước đó PASS.

- SOURCE IMPLEMENTED: YES, cả bốn luồng.
- RUNTIME VERIFIED: NO.
