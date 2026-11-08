# MUSIC TASK 06A — Story Export & Reels Music Preview

Ngày: 2026-11-08, Asia/Bangkok.

**SOURCE FIX IMPLEMENTED / BUILD NOT VERIFIED / ANDROID RUNTIME NOT VERIFIED / CLOUD RUNTIME NOT VERIFIED.** Chỉ đọc source, source JAR Media3 1.11.1 có sẵn và sửa code. Không chạy build/compile, bất kỳ test, ADB/emulator, ffprobe/export, APK/AAB hoặc gọi Cloud. Chưa xác nhận hết hai lỗi trên thiết bị.

## 1. Hai lỗi owner phát hiện

- Story ảnh có chữ/nét vẽ và nhạc nghe được ở Picker, nhưng khi xuất bị chặn bởi thông báo gộp “H.264/AAC hoặc khung Story”. Chưa đến bước upload/publish.
- Reel chọn/nghe nhạc ở Picker được, nhưng Music Editor báo “Không thể nghe thử bản phối…”. Owner cho biết đăng Reel được; đây không phải kết quả agent kiểm thử lại.

## 2. Nguyên nhân và mức bằng chứng

**BUG 01 — xác định được lỗi logic validator:** `StoryCreateActivity.validate` đọc width/height mã hóa từ MediaExtractor; `StoryMusicExportChecks` bắt buộc đúng 720×1280 nhưng không nhận rotation. Source `VideoEncoderWrapper.getSurfaceInfo` của Media3 1.11.1 nêu rõ ưu tiên encode landscape: có thể đổi output portrait sang 1280×720 và mux rotation 90° để hiển thị 720×1280. Thành phẩm hợp lệ như vậy bị validator cũ từ chối. Đã sửa lỗi này; chưa có thông số MP4 của lần owner gặp lỗi để khẳng định đây là nguyên nhân duy nhất. MIME sai hoặc encoder fallback ra kích thước khác cũng có thể đi vào chính thông báo cũ.

**BUG 02 — chưa đủ bằng chứng kết luận root cause:** câu owner thấy được đặt trong catch đồng bộ của Editor, có thể đến từ factory xây composition, tạo/setComposition/prepare player hoặc Mini Player seek/play. Lỗi decoder bất đồng bộ trước đây có thông báo khác. Chọn nhạc trong Picker thành công chưa chứng minh composition video/mix chạy được. Không có exception/cause/stack hoặc error code gốc nên chưa quy lỗi cho OGG, HDR, loop, audio focus hay surface; không đổi pipeline theo phỏng đoán.

Đã sửa hai lỗi vòng đời có bằng chứng từ source: listener Mini Player cũ không kiểm tra danh tính player trước khi close, và catch Editor có thể release trực tiếp player mà Mini Player đã nhận ownership. Chưa chứng minh các lỗi này gây triệu chứng owner báo. Root cause BUG 02 vẫn cần log lần chạy tiếp theo.

## 3. Đường đi source

Story: `StoryCreateActivity.render` → flatten ảnh/chụp draft → `StoryMediaExporter.photoWithMusic` hoặc `.video` → `MusicComposition` → Transformer H.264/AAC → callback success → MediaMetadataRetriever/MediaExtractor → `StoryMusicExportChecks` → metadata → nhận output → upload → `create_story_with_music`. Validation lỗi chỉ dọn thành phẩm/ảnh flatten tạm, không nhận output/metadata để đăng; ảnh/video nguồn và canvas model vẫn còn để thử lại.

Reels Editor: callback preview → kiểm tra cache → `CreateReelActivity` preview factory → `MusicComposition.video` → audio sequence từ `MusicLoopTimeline` → `CompositionPlayer.setComposition/prepare` → `MusicMiniPlayer.show/seek/play`. Nút nghe dùng local file URI và lease đã có, không tải nhạc qua HTTP tại bước này. Source không có audio dùng sequence video-only và sequence nhạc riêng như export. Mute/gain/offset/loop vẫn đi qua engine chung.

Đã đọc source JAR `media3-transformer/1.11.1`: `CompositionPlayer` yêu cầu duration cho mỗi EditedMediaItem, hỗ trợ seek trong composition và xử lý audio focus riêng; shared engine hiện đã cung cấp duration trước clipping. Không tìm thấy bằng chứng đủ để đổi `EditedMediaItemSequence` hoặc thuật toán loop trong Task này.

## 4. File sửa/tạo

Trong `app/src/main/java/com/example/meetomxh/`:

- `StoryCreateActivity.java`: đọc rotation, log container/track probe, kiểm tra MP4 và truyền rotation cho validator.
- `music/StoryMusicExportChecks.java`: kiểm tra kích thước hiển thị sau rotation; tách lỗi video codec/audio codec/rotation/shape.
- `ui/StoryMediaExporter.java`: log bước hoàn tất/lỗi/start, hiển thị mã lỗi export Media3.
- `CreateReelActivity.java`: log riêng composition/create-player/setComposition/prepare trong factory Editor; release khi prepare lỗi có bảo vệ.
- `music/OpenverseMusicEditor.java`: log request/cache/lease/failure stage, thông báo phân biệt chuẩn bị và bắt đầu phát, chuyển ownership để chỉ một bên release.
- `music/MusicMiniPlayer.java`: listener riêng mỗi player, bỏ callback cũ, log state/suppression/error code, release an toàn.
- `music/MusicComposition.java`: chỉ thêm log cấu hình nguồn và timeline; không đổi loop, gain hoặc sequence.
- Tạo `music/MusicDiagnostics.java`: log cause/stack location/code có giới hạn, không in exception message, URI, path, metadata bài đăng hoặc credentials.

Tạo báo cáo này. Không sửa dependency, tests, metadata contract, RPC/migration/catalog/bucket, Admin/Cleanup hoặc các chức năng ngoài phạm vi.

## 5. Giải pháp kỹ thuật

Validator mới nhận coded width/height + rotation 0/90/180/270; swap hai chiều khi rotation 90/270, rồi vẫn bắt buộc khung **hiển thị** đúng 720×1280. Ví dụ coded 1280×720 + 90° hợp lệ; coded 1280×720 + 0° vẫn bị chặn. Rotation ưu tiên MediaFormat, fallback rotation từ retriever nếu không có key. Overload cũ vẫn tồn tại, mặc định rotation 0. Không ép encoder xuất sai hướng để khớp validator.

Preview giữ nguyên composition chung với export. Log bắt exception ở từng bước để phân biệt lỗi đọc media/tạo timeline và lỗi player. Mini Player chỉ nhận event/error từ player hiện hành; khi show đã nhận player thì catch gọi close qua Mini Player, không release thêm trực tiếp. Lỗi hiển thị vẫn giữ draft/lease của Editor cho retry, đổi bài hoặc bỏ nhạc. Không thay preview thành hai player phát nguồn/OGG độc lập.

## 6. Validation vẫn bảo toàn

Với Story có nhạc: container MP4; đúng **một video H.264 và một audio AAC**; file >0 và ≤50 MiB; duration container 1–60s, lệch target ≤300 ms; video duration lệch ≤300 ms; audio phủ target, dung sai thiếu 50 ms/dư 300 ms; displayed frame đúng 720×1280. Không chấp nhận file thiếu audio, codec fallback sai, track dư hoặc chỉ có metadata nhạc. Probe không chứng minh waveform nghe được; owner vẫn cần nghe thực tế.

Giữ nhánh Story không nhạc, Reel không nhạc, validation v1 không loop, v2 đúng 16 trường, kiểm tra catalog/active phía server và publish gates hiện hữu. Không đổi mute/volume/offset/used duration hoặc luồng upload/bind Cloud. Chưa kiểm chứng hồi quy các nhánh này trên thiết bị.

## 7. Log chẩn đoán

Lọc tag **`MeetoMusic`** trong Logcat của Android Studio, lấy từ lúc bấm nghe hoặc bắt đầu xuất đến lúc lỗi:

| Stage | Thông tin |
|---|---|
| `story_transformer_completed`, `story_container`, `story_tracks` | Byte count, MIME, duration, track count, coded dimensions, rotation extractor/retriever |
| `story_output_validate_metadata`, `story_transformer`, `story_transformer_start` | Lớp exception/cause, vị trí source, mã Media3 nếu có |
| `editor_cache_lease`, `editor_cache` | Lease/cache bị từ chối, không in đường dẫn |
| `editor_preview_request`, `video_composition`, `music_timeline` | Có source audio, mute/volume, offset/target, source duration, số đoạn loop, kích thước file |
| `reel_editor_composition`, `reel_editor_player_create`, `reel_editor_set_composition`, `reel_editor_prepare` | Bước factory phát sinh exception, cause và vị trí source |
| `editor_preview_factory`, `editor_mini_attach_seek_play` | Catch ngoài factory hoặc khi gắn Mini Player/seek/play |
| `mini_player_state`, `mini_player_suppression`, `mini_player_async` | Trạng thái/phát bị tạm ngăn (có thể do focus), playback error code/cause |

Log do Task này thêm không chứa token, key, URL ký, đường dẫn media, caption hoặc exception message tự do. Không cần gửi toàn bộ Logcat chứa thông tin tài khoản/network khác.

## 8. Chưa kiểm chứng / giới hạn

Chưa compile hoặc chạy bất kỳ kiểm thử theo yêu cầu. Chưa có MP4 output, rotation thực, probe result, exception stack hay error code từ lần owner tái hiện. BUG 01 có sửa source với bằng chứng API; BUG 02 mới sửa lỗi ownership/callback và bổ sung diagnostics, **chưa kết luận đã sửa nguyên nhân owner gặp**. Không suy ra runtime PASS từ source review hoặc kết quả task cũ.

## 9. Owner kiểm thử lại

**Story:** lặp lại ảnh + chữ + nét vẽ + nhạc; giữ offset/target đã gây lỗi. Kiểm tra preview thành phẩm, đăng, xem từ tài khoản có quyền khác và nghe audio nhúng. Nếu còn lỗi, gửi thông báo mới cùng các dòng `story_container`, `story_tracks`, `story_output_validate_metadata`/`story_transformer`. Kiểm tra thêm video có nhạc, video rotated và Story không nhạc; thử cancel rồi export lại, nguồn/model không được mất.

**Reels:** dùng cùng video/bài/offset đã lỗi, bấm nghe trong Editor. Gửi các dòng `editor_preview_request` đến failure stage cuối cùng và cause/stack/code. Kèm video có tiếng gốc hay không, mute/volume, trim duration/offset và lỗi xảy ra ngay hay sau buffering. So sánh không loop và offset sát EOF cần loop; video không tiếng gốc; source volume 0/100; pause/seek/background/đổi bài/đóng-mở Editor. Đối chiếu preview với audio MP4 sau export, giữ bài đăng/thông tin nhạc và thử Reel không nhạc/v1 cũ nếu có.

Owner thực hiện build và runtime verification; Task này không chạy các thao tác đó. Nếu preview vẫn lỗi, log theo stage là dữ liệu cần thiết để sửa đúng root cause ở vòng tiếp theo.
