# Meeto — Task 52: Openverse CC0 Music

## Kết quả

Đã nối luồng chọn nhạc Openverse vào editor Reel và Story. Nhạc được tải về cache riêng của ứng dụng, trộn vào file MP4 cuối trên thiết bị, rồi file thành phẩm được upload như media thông thường. Không upload audio gốc riêng, không tạo post/story thứ hai. Chưa deploy migration hoặc truy cập Supabase Cloud.

## File đã thêm và sửa

- Thêm module `music`: `OpenverseTrack`, `OpenverseMusicClient`, `OpenverseMusicCache`, `MusicComposition`, `MusicPublishingClient`, `MusicAttribution`, `OpenverseMusicPickerActivity`, `OpenverseMusicEditor`.
- Thêm giao diện picker/editor và icon: `activity_openverse_music_picker.xml`, `bottom_sheet_music_editor.xml`, `ic_music_note.xml`; cập nhật `activity_reel_create.xml`, `activity_story_create.xml`, `item_post.xml` và `AndroidManifest.xml`.
- Nối xuất bản, preview, draft và metadata trong `CreateReelActivity.java`, `CreatePostActivity.java`, `StoryCreateActivity.java`, `ReelClipExporter.java`, `StoryMediaExporter.java`, `FeedAdapter.java`, `ReelsFragment.java`, `StoryViewActivity.java`, `Post.java`, `Story.java`, `Dto.java`, `StoryClient.java`.
- Thêm migration forward-only `supabase/migrations/20261107000900_openverse_music_metadata.sql`.

## API, xác minh và picker

Client gọi Openverse audio search/detail trực tiếp, không API key và không gửi Supabase token. Search giới hạn CC0, danh mục music, trang 15 kết quả, debounce 500 ms, tối đa 20 trang và 30 request/phút; kết quả tìm kiếm cache 5 phút. Track được kiểm tra lại ở endpoint detail, nguồn và trang CC0 phải truy cập được qua HTTPS, redirect không được hạ xuống HTTP hoặc trỏ vào địa chỉ nội bộ. Audio tải theo luồng, tối đa 25 MiB, kiểm Content-Type, track giải mã và thời lượng qua Android media APIs; cache giới hạn 120 MiB và 24 giờ. Picker có tìm kiếm, danh mục, nghe thử, phân trang, link nguồn/giấy phép, retry và danh sách ẩn track/source.

Openverse cho phép anonymous API access và hỗ trợ lọc audio theo license/category cùng phân trang tại [API reference](https://api.openverse.org/v1/). Ứng dụng chỉ chấp nhận `cc0` và xác minh lại metadata; việc Openverse lập chỉ mục CC0 không phải bảo đảm pháp lý rằng người tải lên sở hữu mọi quyền. Picker nêu rõ giới hạn này và cho phép ẩn nguồn hoặc track.

## Editor, trộn âm thanh và xuất bản

Editor dùng chung cho phép nghe thử đoạn đã cắt, đổi/bỏ nhạc, chọn điểm bắt đầu và mức âm lượng 0–100%; video có audio gốc còn có điều chỉnh riêng. Reel và Story video dùng cùng một Media3 `Composition` cho CompositionPlayer preview và Transformer export. Khi video nguồn không có audio, composition dùng sequence video-only cộng sequence nhạc. Story ảnh giữ toàn bộ nội dung Canvas, render thành MP4 H.264/AAC với thời lượng mặc định 15 giây, điều chỉnh được 5–60 giây. Preview của Story ảnh phát chính MP4 đã render.

Reel thường và `EXTRA_EDIT_ONLY` đều xuất MP4 đã ghép nhạc; `EXTRA_EDIT_ONLY` trả đúng file/duration/metadata về `CreatePostActivity`, nơi tiếp tục tạo một canonical Feed/Reel post. Story ảnh không có nhạc vẫn là JPEG; Story video không có nhạc giữ đường xuất hiện tại. Sau export, ứng dụng kiểm tra video/audio track, thời lượng và dung lượng trước khi upload. Reel giữ mục tiêu dưới 39 MB; Story kiểm tra giới hạn bucket video Task 47 cho bản có nhạc và giữ trần 50 MiB. Không tải nhạc lên Supabase riêng.

Draft lưu lựa chọn và metadata; upload lỗi giữ output và asset để retry bằng request ID hiện có. Khi cache nhạc hết hạn, output đã render vẫn dùng được nếu metadata còn hợp lệ; nếu cần render lại, ứng dụng yêu cầu tải lại hoặc bỏ nhạc thay vì xuất âm thầm không nhạc. Thay đổi tài khoản hủy request/player và chặn callback cũ.

## Backend và việc owner cần làm

Migration thêm metadata có cấu trúc cùng hash lên `posts` và `stories`, kiểm tra shape/CC0/URL/offset/thời lượng, gắn metadata với đúng nội dung do caller sở hữu trong cùng transaction và trả metadata qua projection đã ACL-gate. RPC mới: `get_music_publish_capabilities`, `create_reel_with_music`, `create_post_reel_with_music`, `create_story_with_music`. Client kiểm tra capability trước upload có nhạc; nếu Cloud chưa có migration thì không publish bằng RPC cũ, nhưng vẫn có thể bỏ nhạc và dùng luồng hiện tại.

Owner cần rà soát rồi triển khai migration `20261107000900_openverse_music_metadata.sql` sau các prerequisite Story, Task 43/unified Reel và Task 47. Migration chưa được chạy trên local hay Cloud trong Task này. RPC xác thực cấu trúc và ownership, nhưng không tự gọi Openverse từ PostgreSQL để chứng thực lại nguồn; client là nơi kiểm tra Openverse và nguồn truy cập được.

## Kiểm chứng và giới hạn

Đã parse tĩnh các XML/manifest đã sửa: **7/7 hợp lệ**. Không chạy Gradle build, test, emulator, E2E hoặc Cloud theo yêu cầu. Vì vậy chưa xác minh biên dịch trên dependency/device thực, codec và chất lượng mix trên từng thiết bị, playback Story/Reel runtime, hoặc trạng thái migration/RLS thực trên Cloud. Media3 ghi nhận CompositionPlayer là API early preview và giới hạn preview hiện tại gồm một video sequence cùng một audio sequence ([Android docs](https://developer.android.com/media/media3/transformer/compositionplayer)); thiết bị vẫn cần kiểm thử playback/export trước khi phát hành.
