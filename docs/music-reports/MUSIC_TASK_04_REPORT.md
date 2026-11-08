# MUSIC TASK 04 — Story Music Integration & Audio Loop

## 1. Mục tiêu và trạng thái

Triển khai source cho Story ảnh/video có nhạc Meeto, chọn offset bất kỳ, phối volume, loop hữu hạn, preview và export chung timeline. Ngày kiểm tra: 2026-11-08, Asia/Bangkok.

**SOURCE IMPLEMENTED / BUILD VERIFIED. STORY MUSIC CLOUD PUBLISH NOT VERIFIED / ANDROID RUNTIME NOT VERIFIED.** Không có thiết bị/emulator kết nối; chưa chứng minh đăng thành công và nghe lại trên tài khoản khác.

## 2. File tạo/chỉnh sửa

- Tạo `music/MusicLoopTimeline.java`, `music/StoryMusicExportChecks.java` trong `app/src/main/java/com/example/meetomxh/`.
- Sửa `music/MusicComposition.java`, `music/OpenverseMusicEditor.java`, `ui/StoryMediaExporter.java`, `StoryCreateActivity.java`.
- Sửa `res/layout/bottom_sheet_music_editor.xml` (nhãn nút nghe/tạm dừng).
- Sửa `supabase/StoryClient.java` đúng phần đọc artist v2, fallback creator v1.
- Tạo `app/src/test/java/com/example/meetomxh/music/MusicLoopTimelineTest.java` và `StoryMusicExportChecksTest.java`; tạo báo cáo này.

Không thêm dependency, không sửa migration/catalog/Storage, Admin/Cleanup hoặc `CreateReelActivity`. Repository/cache Task 03 được tái sử dụng. Các thành phần composition/editor dùng chung vẫn giữ signatures để Reels compile; cần kiểm thử Reels ở Task 05.

## 3. Thiết kế loop/timeline

Đã đọc source JAR **Media3 Transformer/Common 1.11.1 đang có trong Gradle cache**, không giả định API:

- `EditedMediaItem.Builder.setDurationUs` nhận duration nguồn **trước clipping**. Sửa chỗ cũ truyền duration đoạn nhạc, có thể làm timeline preview sai khi offset khác 0.
- `EditedMediaItemSequence.withAudioFrom(List)` hỗ trợ nối các item audio. `MediaItem.ClippingConfiguration` có setter microsecond. `GainProcessor.GainProvider` có automation theo sample.
- `SequenceAssetLoader` cấp offset 0 khi đổi item export; audio sink của CompositionPlayer cấp vị trí tương đối item khi seek. Các gain processor vì vậy dùng tọa độ của đoạn đã cắt.

V2 tạo danh sách hữu hạn: đoạn từ offset tới EOF, rồi đoạn từ đầu file cho tới đủ thời lượng Story; đoạn cuối được cắt đúng phần còn lại. Ví dụ 36s, offset 30s, dùng 15s → `[30,36)` + `[0,9)`. Các output offset liên tiếp, tổng duration đúng target, không chèn gap/crossfade/chồng item và không bật repeat vô hạn. Giới hạn target 1–60s và tối đa 64 đoạn chặn vòng lặp không giới hạn.

Timeline dùng duration nguồn microsecond do MediaExtractor đọc; đối chiếu duration catalog với dung sai tối đa 1 ms do catalog làm tròn. Nhờ đó không chèn khoảng trống dưới 1 ms khi EOF thực nằm giữa hai mốc ms. Metadata vẫn giữ duration/offset theo ms của catalog; sai số lượng tử ở mỗi điểm wrap tối đa 1 ms. Với 50 bài hiện tại dài ít nhất 36s, tối đa 3 item cho đoạn 60s bắt đầu gần EOF.

Mỗi nối nội bộ có ramp biên độ 2 ms trên nhạc để giảm tiếng bật do bước nhảy waveform; ramp không đổi sample position hoặc tổng thời lượng, không thêm đoạn im lặng. Video gốc không bị áp ramp. Chưa có bằng chứng nghe/đo waveform AAC thực tế tại điểm nối; không coi unit test là chứng minh hết click/gap trên codec thiết bị.

V1 vẫn một đoạn, giữ giới hạn nguồn đủ dài và validation 13 trường. Chỉ v2 library được loop và giữ nguyên start offset thay vì tự kéo offset về trước.

## 4. Story ảnh/video export

Ảnh được flatten bằng `StoryComposition.drawPhoto`: nền 720×1280, fit/crop theo lựa chọn, giữ tỷ lệ và rotation, kèm chữ/filter/nét vẽ. JPEG đã flatten trở thành image item duration 5–60s, frame rate 30; ghép audio sequence và xuất MP4 H.264/AAC bằng Transformer hiện hữu.

Video giữ trim, fit/crop, rotation, overlay, tone-map HDR hiện có; mix tiếng gốc và nhạc bằng gain 0–100. Export dùng bản copy lựa chọn nhạc đã đóng băng, không đọc cấu hình đang bị thay sau cancel. Khi tắt/không có tiếng gốc hoặc là ảnh, metadata v2 ghi `source_volume=0`.

Trước upload, probe **file xuất**: đúng một video H.264 và một audio AAC, 720×1280, ≤50 MiB, duration 1–60s và lệch target ≤300 ms. Audio phải đủ target (cho phép thiếu tối đa 50 ms hoặc dư tối đa 300 ms do lượng tử codec), không chỉ có một audio stream ngắn. Codec fallback sai yêu cầu bị từ chối. Kiểm tra này không thể chứng minh nội dung waveform thực sự đúng nhạc hoặc điểm nối không có gap.

Giữ tối thiểu 64 MiB trống trước Transformer, giữ lease file nhạc trong export, đóng lease/overlay/player khi hoàn tất/lỗi/hủy. Generation fence chặn callback export cũ xóa trạng thái run mới. Host dọn cả file đang chờ validation/flatten, ngăn worker ảnh đã hủy khởi động Transformer muộn. Export lỗi/hủy chỉ bỏ thành phẩm tạm; source và chỉnh sửa còn để retry.

## 5. Preview và Editor

Slider v2 chọn `0..duration_ms-1`, dùng đủ target dù phần còn lại ngắn. Hiển thị thông báo tự lặp, title/artist, volume nguồn/nhạc và thời lượng. Nút nghe/tạm dừng cùng Mini Player điều khiển một player; đổi điều khiển/đóng Editor sẽ release preview. Editor có lease riêng và đóng khi dismiss, cùng protection theo host đã có từ Task 03.

Preview video dùng composition mix; preview ảnh dùng cùng audio composition với ảnh vẫn trên canvas. Editor ảnh dùng CompositionPlayer audio-only cùng sequence/gain của export. Trim video kết thúc thao tác sẽ dựng lại bản phối đúng range. CompositionPlayer có audio focus; các player cũ được release trước khi đổi luồng.

Đã sửa lỗi nút tắt tiếng gốc làm tắt luôn MP4 thành phẩm: bản xuất phát ở volume 1, vì lựa chọn mute/volume đã được encode trong audio mix. Slider nguồn có thể bật lại tiếng gốc khi lưu Editor; bỏ/đổi nhạc không làm mất media nguồn.

## 6. Metadata/publish/playback

Giữ v2 đúng 16 trường; không thêm loop key, URL ký hoặc local path. Metadata lấy từ snapshot **đã render**, start offset giữ nguyên, used duration bằng timeline đã phối; container duration được đối chiếu theo tolerance RPC. Song identity/version/hash từ model/catalog Task 03, volume phản ánh mix đã xuất.

Source SQL `00900` xác nhận `create_story_with_music` gọi create/bind trong transaction, bắt `video/mp4`, duration/used lệch tối đa 300 ms; `01200` bổ sung catalog/active validation v2. Không sửa/chạy lại SQL. Giữ `get_music_publish_capabilities`, library capability trong Picker và upload-policy gate hiện tại.

Chỉ gán `output`/metadata sau khi validate thành phẩm; không upload/post khi export thiếu audio hoặc thất bại. Ảnh có nhạc đi nhánh `uploadVideo`, purpose `story`, rồi `create_story_with_music`. Không nhạc vẫn dùng luồng Story cũ. Retry upload/publish giữ thành phẩm và RequestIds hiện hữu.

Đã đọc `StoryClient.media`/`StoryViewActivity.prepareVideo`: viewer tải MP4 bằng quyền user và phát audio nhúng qua ExoPlayer, không cần local OGG/cache hoặc phát metadata thành nguồn thứ hai. Chỉ bổ sung đọc artist v2, không viết lại viewer. Khả năng mở trên tài khoản/thiết bị khác vẫn cần kiểm thử thực tế.

## 7. Build/tests/runtime

Đã chạy JDK sẵn có `F:/android/jbr`:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:processDebugResources :app:testDebugUnitTest --tests 'com.example.meetomxh.music.*' --console=plain
```

Java/XML/resources PASS; **24/24 unit tests PASS**: 11 Task 03 + 13 mới. Không assemble/export APK/AAB, không tạo server/Docker.

| Mức | Bằng chứng |
|---|---|
| SOURCE IMPLEMENTED | Loop/preview/export, file validation, metadata/publish wiring và cancel fences |
| BUILD VERIFIED | Compile và 24 unit tests, không có failures/errors |
| CLOUD VERIFIED | Chỉ owner cung cấp backend 50 active và GET/hash 50/50; agent chưa gọi RPC hoặc publish Story bằng user active |
| ANDROID RUNTIME VERIFIED | Chưa; `adb devices` không có thiết bị/emulator |

Có ffprobe trong môi trường nhưng không có MP4 do Android/Media3 mới xuất để probe hoặc nghe. Không tạo video qua pipeline desktop khác để thay bằng chứng Android.

## 8. Đã kiểm tra / chưa kiểm tra

Unit đã chạy: không wrap; 6s cuối + 9s đầu; track 1s dùng 60s; offset sát EOF; EOF chính xác không sinh item rỗng; duration microsecond; giới hạn/guard hữu hạn; ramp biên độ; v2 16 trường/volume/offset/used; v1 không loop; probe giả của Story ảnh/video H.264/AAC hợp lệ và từ chối audio ngắn/thiếu/sai codec/shape/duration. Probe giả không tạo MP4 và không test decoder.

Chưa runtime: ảnh → MP4, video → MP4, âm thanh quanh điểm nối, AAC encoder delay, seek qua join, mute/volume thực, audio focus/tai nghe, cancel ở flatten/Transformer/validation rồi retry, mất mạng/JWT/upload retry, đăng và mở từ user khác, Reels shared-component regression. Cancellation fences/cleanup đã review source, chưa chạy bằng codec thực.

## 9. Rủi ro và bước nghiệm thu demo

Không có blocker compile/unit test. **Blocker nghiệm thu end-to-end là chưa có runtime evidence**. Cần user active trên thiết bị, tải bài 36s, chọn offset 30s cho Story ảnh 15s; xác minh hình 9:16 và nghe 6s cuối + 9s đầu. Làm lại với video trim, source/music volume khác nhau và mute gốc; nhạc vẫn phải nghe trong MP4.

Sau đó thử ảnh 60s/offset gần EOF, cancel/retry, upload lỗi/retry và xem Story từ tài khoản có quyền khác. Xuất MP4 từ thiết bị để chạy:

```text
ffprobe -v error -show_entries stream=codec_name,codec_type,width,height,duration:format=duration,size -of json <exported_story.mp4>
```

Nghe/đo waveform vùng quanh mốc wrap (ví dụ 5.8–6.2s), không chỉ nhìn audio stream. Kiểm tra codec/priming/gap ở thiết bị thực; ramp 2 ms có thể tạo dip rất ngắn. Mix cả hai nguồn 100% có thể bị clipping ở đoạn lớn tiếng; chưa đo loudness thực. Validation nghiêm có thể từ chối encoder fallback hoặc duration không đạt, giữ source cho retry.

## 10. Điều kiện bắt đầu Task 05

Source/build sẵn sàng tiếp tục Reels Music. Dùng chung timeline hữu hạn, giữ v1, kiểm tra source mute/metadata và preview/export trong Reels. Trước nghiệm thu tổng cần hoàn thành smoke test Story/Cloud/cross-account và kiểm tra audio quanh join; không suy ra runtime PASS từ source hoặc unit tests hiện tại.
