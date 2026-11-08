# MUSIC TASK 05 — Reels Music Integration

## 1. Mục tiêu và trạng thái

Hoàn thiện source Reels có nhạc Meeto: chọn/quay video, chọn nhạc tùy ý, chỉnh offset/volume, preview, loop hữu hạn, export và publish metadata v2. Ngày thực hiện: 2026-11-08 (Asia/Bangkok).

**SOURCE IMPLEMENTED / BUILD NOT VERIFIED / CLOUD RUNTIME NOT VERIFIED / ANDROID RUNTIME NOT VERIFIED.** Theo yêu cầu, toàn bộ kiểm thử do owner thực hiện. Không chạy Gradle/compile, unit/integration/UI/smoke/E2E, ADB/emulator, APK/AAB, ffprobe hay gọi Cloud trong Task này. Kết quả build Task 03–04 không chứng minh thay đổi Task 05.

## 2. File tạo/chỉnh sửa

Trong `app/src/main/`:

- `java/com/example/meetomxh/CreateReelActivity.java`: trạng thái Picker/Editor, preview sau trim, snapshot export, metadata từ bản render, probe thành phẩm và cleanup/cancel.
- `java/com/example/meetomxh/ui/ReelClipExporter.java`: lease cache, kiểm tra dung lượng, generation fence và cleanup kể cả lỗi codec.
- `java/com/example/meetomxh/CreatePostActivity.java`: hủy editor giữ metadata của video trước đó.
- `java/com/example/meetomxh/models/Post.java`: nhãn bài hát đọc `artist` v2, fallback `creator` v1.
- `res/layout/activity_reel_create.xml`: “Thêm nhạc”, “Tắt tiếng gốc”.
- `res/layout/activity_create_post.xml`: “Cắt video / thêm nhạc”.

Tạo báo cáo này. Không sửa shared music engine Task 04, migration, dependency, Android Story, catalog/Storage, Admin hoặc Media Cleanup. Không Git push.

## 3. Luồng chọn nhạc

Video chọn từ thư viện hoặc đường dẫn capture vẫn được sao chép vào bản nháp riêng. Reels editor hiển thị “Thêm nhạc”; Create Post mở cùng editor qua “Cắt video / thêm nhạc”. Picker/cache/repository Task 03 được dùng nguyên trạng: catalog, tìm tại máy, nghe thử, tải từng bài và xác minh cache.

Đăng không nhạc giữ luồng cũ và tối ưu copy video đủ điều kiện. Có nhạc luôn đi qua composition/export; nút Tiếp không bỏ qua Editor đang mở hoặc draft Editor chưa lưu. Cờ Picker ngăn mở lặp và ngăn khôi phục Editor phía sau Picker. Đổi bài giữ volume đang chỉnh; chọn lại cùng ID giữ offset. Hủy Picker khôi phục draft Editor nếu có; bỏ nhạc trả về video nguồn/trim hiện tại.

## 4. Music Editor và preview

Tái sử dụng `OpenverseMusicEditor`, `MusicMiniPlayer`, `MusicLibraryTrack` và adapter hiện có. Offset v2 có thể sát EOF, target theo đoạn Reel 1–60 giây; volume nhạc/nguồn 0–100. Mute chỉ áp lên nguồn video trong composition. Slider nguồn có thể bật lại tiếng gốc khi lưu; video không có audio ghi source volume bằng 0.

Preview chính và preview Editor dùng `MusicComposition.video` cùng factory player có audio focus Task 04. Kết thúc thao tác trim dựng lại composition theo range mới. Release player cũ trước mở Picker/Editor hoặc đổi composition; lifecycle host/Editor giữ và giải phóng pin/lease theo cơ chế Task 03–04. Preview dùng bản copy track, tránh sửa lựa chọn đã lưu.

## 5. Loop và phối âm

Không tạo pipeline loop riêng: dùng nguyên `MusicLoopTimeline` và `MusicComposition` Task 04. V2 nối tail từ offset đến EOF, rồi head/full-track cho tới đủ target; danh sách hữu hạn, đoạn cuối cắt đúng phần còn lại. Ví dụ track 36s, offset 30s, Reel 15s: 6s cuối + 9s đầu. V1 giữ giới hạn không loop.

Timeline dùng duration nguồn microsecond đối chiếu catalog với dung sai 1 ms, ramp 2 ms tại join nội bộ, gain theo volume và không thêm thời gian vào timeline. Đây là thiết kế source; chưa chứng minh decoder/encoder không sinh gap/click hoặc preview/export nghe giống nhau trên thiết bị.

## 6. Xuất MP4

Mỗi lần xuất chụp source, trim, kích thước/hướng hiển thị, mute và bản copy nhạc. Composition chỉ cập nhật planned duration/offset trên copy của lần render đó. Transformer 1.11.1 hiện có được yêu cầu H.264/AAC, tone-map HDR và giữ cách scale FIT theo tỷ lệ video, tối đa cạnh dài 1280/cạnh ngắn 720, 30 fps như pipeline cũ.

Trước render yêu cầu ít nhất 64 MiB trống, từ chối source/destination trùng nhau và giữ lease OGG đã xác minh. Sau render, trước nhận output/upload: kiểm tra file không rỗng, giới hạn byte hiện hữu, container duration ≤60s và lệch target ≤250 ms. Với nhạc, MediaExtractor phải thấy đúng một H.264 và một AAC; video duration lệch ≤300 ms; audio phủ target, dung sai thiếu 50 ms/dư 300 ms. Kích thước hiển thị sau rotation phải khớp target scale trong 2 px. Sai codec fallback/thiếu duration/thiếu audio bị chặn.

Các probe là logic production mới viết, chưa chạy. Có audio stream đủ dài không chứng minh waveform đúng bài hay không im lặng; cả hai volume bằng 0 là lựa chọn im lặng có chủ ý.

## 7. Publish và metadata v2

Chỉ nhận output và metadata sau khi probe và validator metadata đạt ở runtime. Metadata được tạo từ track copy đã render; source volume bằng 0 nếu mute hoặc nguồn không audio. V2 giữ đúng 16 trường qua `MusicLibraryTrack.metadata`: text track ID, catalog version/checksum/license/source, offset và used duration theo composition. Không đưa local path, signed URL hay key thứ 17 vào metadata.

Giữ publish capability gate, upload-policy gate, session/JWT transport, receipt và RequestIds hiện hữu. Upload video hoàn tất rồi upload cover và gọi `create_reel_with_music`; nhánh Create Post nhận MP4/metadata, upload rồi gọi `create_post_reel_with_music`. Source SQL `00900` gọi create/bind trong cùng transaction; `01200` kiểm tra catalog và active khi bind. Chỉ đọc SQL để đối chiếu, không thay đổi/chạy SQL. Publish lỗi giữ thành phẩm, caption và asset/request identity để thử lại, không rơi sang RPC không nhạc.

## 8. Hiển thị sau đăng

`Dto.post` giữ `music_metadata`; `Post.getMusicLabel` nay đọc title + artist v2 (ví dụ Abstraction), fallback creator v1. `ReelsFragment` tiếp tục dùng nhãn này và attribution links hiện có. Player lấy media MP4 từ Storage và phát audio nhúng; không thêm player OGG hoặc phụ thuộc cache nhạc khi xem bài đã đăng. Không sửa luồng like/comment/share/save.

## 9. Lỗi và lifecycle

- Cache thiếu/hỏng bị chặn trước preview/export; có thể chọn lại/tải lại/bỏ nhạc. Output đã có metadata hợp lệ có thể tiếp tục được dùng dù cache nguồn không còn.
- Exporter giải phóng lease và file tạm khi lỗi/hủy; generation fence bỏ callback cũ, không xóa output lần xuất mới. Host theo dõi cả file đang chờ probe; copy có kiểm tra generation/interruption để dừng và dọn khi cancel/destroy.
- Cancel giữ video nguồn và các chỉnh sửa để retry. Xóa bản nháp riêng khi chủ động thoát vẫn theo hành vi cũ; không xóa video thư viện của người dùng. Lease host có thể giữ bài tới destroy theo thiết kế Task 03.
- Mất mạng/JWT tiếp tục dùng cơ chế repository/Storage/RPC hiện có, không thêm retry vòng lặp. Upload/publish thất bại giữ trạng thái retry; đổi tài khoản đóng màn hình và hủy tác vụ hiện hữu.

## 10. Chưa kiểm thử

Chỉ đọc và rà logic source/call sites, DTO/viewer và RPC SQL. Không có kết quả compile, media export, audio playback, UI/lifecycle, endpoint/policy, upload hoặc publish Task 05. Cloud 50 active và 50/50 SHA-256 là bằng chứng owner cung cấp từ Task 02, không phải kiểm thử Reels Music của agent.

## 11. Rủi ro còn lại

Codec/rotation/HDR/encoder delay khác nhau giữa thiết bị; probe nghiêm có thể từ chối fallback, duration hoặc shape không đạt. Join AAC cần nghe/đo thực tế; ramp có thể tạo dip ngắn. Trộn hai nguồn 100% có thể clipping. Dung lượng tối thiểu không bảo đảm còn đủ nếu ứng dụng khác chiếm bộ nhớ trong lúc export. Chưa có bằng chứng build hoặc runtime để loại trừ lỗi API/lifecycle.

MP4 trả về Create Post là bản đã trộn phẳng. Mở lại editor bằng MP4 đó không khôi phục được các stem/offset gốc; âm nhạc cũ trở thành một phần tiếng nguồn. Đổi/bỏ bài và trim trong cùng phiên Reels editor vẫn dùng video nguồn riêng. Nếu cần chỉnh không phá hủy sau khi đã trả MP4 về composer, cần lưu project/source riêng trong task tiếp theo, không suy ra khả năng tách nhạc từ MP4.

## 12. Checklist owner

1. Build/compile và kiểm thử theo môi trường dự án trước demo; Task này chưa chạy bất kỳ kiểm thử nào.
2. Chọn và quay video; đăng không nhạc; mở thêm nhạc từ Reels và Create Post, kiểm tra luồng đăng nhanh không bỏ lựa chọn.
3. Tìm/nghe/tải nhạc; hủy Picker, đổi bài, bỏ bài, mở lại Editor, background/xoay màn hình; giữ video/trim/offset và chỉ một player.
4. Track 36s + offset 30s + Reel 15s, rồi 60s/offset sát EOF; nghe/đo quanh join, seek qua join và kiểm tra kết thúc đúng target.
5. Trim có offset video, video dọc/ngang/rotation/HDR và video không audio; đối chiếu preview/export và tỷ lệ/hướng.
6. Volume 0/35/100, mute nguồn vẫn nghe nhạc, slider bật lại tiếng nguồn, cả hai nguồn lớn tiếng; kiểm tra focus/tai nghe.
7. Xuất MP4 thực, kiểm tra H.264/AAC, audio đủ thời lượng và waveform thực; xác nhận metadata 16 trường/offset/used/volume đúng bản đã trộn.
8. Cache hỏng/mất, thiếu dung lượng, codec lỗi; cancel trong encode/probe/copy rồi xuất lại; không mất video nguồn hoặc nhận callback cũ.
9. Mất mạng/JWT, upload/cover/publish lỗi rồi retry cùng receipt; tài khoản đổi/đăng xuất, track bị deactive hoặc Cloud từ chối phải chặn publish.
10. Đăng qua cả hai RPC; xem bằng tài khoản khác có quyền, không có cache OGG: nghe audio MP4, thấy title/Abstraction, không double audio; thử like/comment/share/save và nội dung v1 cũ.

## 13. Điều kiện Task 06

Source sẵn để owner bắt đầu kiểm thử tích hợp. Trước nghiệm thu/demo cần owner xác nhận build, endpoint và publish/read-back bằng user active, audio loop/volume/codec trên thiết bị và hồi quy Story/shared engine. Nếu phát sinh lỗi thì sửa theo bằng chứng thực tế; hiện không có cơ sở ghi BUILD/CLOUD/ANDROID PASS.
