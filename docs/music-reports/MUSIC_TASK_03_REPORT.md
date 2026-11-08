# MUSIC TASK 03 — Android Music Library & Cache

## 1. Mục tiêu

Thay nguồn lựa chọn nhạc mới bằng Meeto Library, dùng Java/XML và giữ Picker, Mini Player, Editor cùng các entry point Story/Reels hiện hữu. Phạm vi là catalog, tải/cache, nghe thử và chuyển lựa chọn v2 vào Editor. Loop trong composition/export tiếp tục thuộc Task 04–05.

## 2. File tạo/chỉnh sửa

Tạo trong `app/src/main/java/com/example/meetomxh/music/`:

- `MusicLibraryTrack.java`: catalog DTO, metadata v2 đúng 16 trường, validation kiểu/giới hạn/path/provenance.
- `MusicLibraryRepository.java`: capability/catalog RPC, cursor, snapshot theo tài khoản, authenticated Storage GET, retry JWT, tải theo nhu cầu.
- `MusicLibraryCache.java`: SHA-256/size, file tạm và atomic rename, giới hạn 120 MiB, LRU và lease/lifecycle protection.

Sửa cùng thư mục: `OpenverseTrack.java`, `OpenverseMusicCache.java`, `OpenverseMusicClient.java`, `OpenverseMusicPickerActivity.java`, `MusicMiniPlayer.java`, `OpenverseMusicEditor.java`, `MusicComposition.java`, `MusicAttribution.java`.

Sửa `CreateReelActivity.java`, `StoryCreateActivity.java` để kiểm tra cache theo loại track và mở Picker có thể dùng snapshot offline. Publish gate cũ vẫn giữ tại bước đăng; Picker kiểm tra cả publish capability v1 và library capability v2 khi làm mới online.

Sửa XML `activity_openverse_music_picker.xml`, `item_openverse_music.xml`. Thêm `org.json:json:20240303` chỉ vào `testImplementation` trong `app/build.gradle.kts`; không thêm dependency media cho Android.

Tạo ba file test trong `app/src/test/java/com/example/meetomxh/music/`: `MusicLibraryFixtures.java`, `MusicLibraryRepositoryTest.java`, `MusicLibraryCacheTest.java`. File báo cáo này cũng được tạo mới. Không sửa migration, upload helper, Admin hoặc Media Cleanup.

## 3. Repository/cache

- Identity cache là SHA-256 của `catalog_version + track_id + sha256`; không dùng signed URL hoặc UUID Openverse giả.
- File hoàn chỉnh nằm ở app-private `files/music-library/`. Chỉ tải bài được nghe/chọn; mở Picker không tải audio hàng loạt.
- Kiểm tra response MIME `audio/ogg`, giới hạn byte count, SHA-256 và kích thước so catalog. Ghi `.part`, sync, xác minh rồi rename trong cùng thư mục; file không đạt hoặc tải lỗi/hủy không được đưa vào playback và part được dọn.
- Khóa theo cache key gộp các lượt tải đồng thời cùng bài: lượt sau kiểm tra lại cache khi lấy được khóa. Timeout connect 10s/read 20s/toàn call 120s; không retry mạng tự động, người dùng chủ động thử lại.
- Giới hạn audio hoàn chỉnh 120 MiB. Evict bài cũ không được pin; bảo vệ file mới dùng thêm 60s cho khoảng chuyển Picker → host. Khi toàn bộ phần cần evict đang được giữ, tải mới báo lỗi thay vì xóa file đang dùng hoặc vượt giới hạn. Part cũ quá một giờ được dọn; part đang tải tối đa 5 MiB mỗi lượt.
- Lease bảo vệ kết quả tải trong lúc giao callback; callback bị hủy cũng đóng lease. Host/Editor/Picker giữ pin theo lifecycle và giải phóng khi destroy. Snapshot/status scan không làm mới LRU; sử dụng thực sự mới cập nhật thời điểm truy cập.
- Snapshot catalog lưu bằng AtomicFile, tách theo hash user ID và chỉ được ghi từ kết quả đã qua capability gates. Khởi động hiển thị snapshot rồi refresh; lỗi mạng cho phép dùng snapshot và bài đã cache. HTTP từ chối hoặc dữ liệu sai không fallback âm thầm sang snapshot cũ. Đăng xuất/đổi tài khoản hủy jobs, dừng player và đóng Picker.

## 4. Picker/Mini Player/Editor

Picker mang tên “Thư viện nhạc Meeto”; giữ class/intent extra cũ để các host tiếp tục hoạt động. Danh sách tên/nghệ sĩ/thời lượng, tìm tại máy theo tên hoặc nghệ sĩ, chip Tất cả/Đã tải, trạng thái chưa tải/đang tải/đã tải/lỗi và nút tải lại đã được triển khai ở source. Bài đã cache được xác minh lại trước sử dụng.

Mini Player phát local file, seek, phát/tạm dừng, audio focus và xử lý tháo tai nghe. Chỉ sở hữu một player, close/release khi đổi bài hoặc rời màn hình; khi quay lại có thể khôi phục vị trí ở trạng thái dừng. Jobs gắn Api scope và session generation; callback cũ/hủy không đưa kết quả vào Activity đã dừng.

Lựa chọn trả về draft giữ catalog identity, path cache và metadata v2; Editor sử dụng được adapter cache mới. Editor vẫn yêu cầu nguồn đủ dài cho đoạn phối hiện tại và hiển thị giải thích khi cần loop. Không triển khai loop segment hoặc thay timeline export trong Task 03. `MusicComposition` chỉ thêm dung sai 1 ms cho duration library: catalog dùng ms làm tròn, MediaExtractor hiện lấy phần nguyên của microsecond; kiểm tra duration legacy giữ nguyên.

## 5. Luồng Supabase

Online: `get_music_publish_capabilities()` vẫn phải enabled/contract 1 → `get_music_library_capabilities()` contract 2/source/bucket/delivery/giới hạn hợp lệ → `get_music_catalog` từng trang tối đa 50 bài, kiểm tra dữ liệu, trùng identity/sort order, cursor cuối trang và tổng số bài khớp capabilities. Giới hạn an toàn 20 trang/1.000 bài cho các lần cập nhật sau này; không hardcode 50 là tổng tối đa.

RPC dùng `RpcClient`/`SupabaseHttpClient` và `SessionManager` hiện hữu. Audio GET `/storage/v1/object/authenticated/music-library/<catalog-path>` dùng public app key và session JWT của user, chỉ tới project URL cấu hình, không theo redirect. Session được refresh theo cơ chế hiện có; Storage 401 refresh/retry tối đa một lần, 403/404 và lỗi khác báo lỗi. Không đọc bảng catalog trực tiếp, không dùng service key trong Android, không lưu JWT/URL ký vào snapshot hay metadata.

Catalog disabled/rỗng trả UI trống. JSON/cursor sai bị từ chối. Offline chỉ có các bài đã cache là nghe được; lần mở đầu chưa có snapshot hoặc bài chưa tải cần kết nối. Khi offline không thể biết thay đổi active/quyền vừa xảy ra trên Cloud; snapshot là trạng thái hợp lệ gần nhất. Server vẫn kiểm tra active/quyền khi tải mới hoặc publish, không coi snapshot là bằng chứng quyền hiện tại.

## 6. Kiểm tra và bằng chứng

Ngày kiểm tra: 2026-11-08 (Asia/Bangkok).

Đã chạy với JDK có sẵn `F:/android/jbr`, không cài môi trường:

```powershell
.\gradlew.bat :app:compileDebugJavaWithJavac :app:processDebugResources :app:testDebugUnitTest --tests 'com.example.meetomxh.music.*' --console=plain
```

Java/resources compile thành công. 11 unit tests đạt, bao gồm catalog 50 bài qua hai capability gates, trang 50+1 với cursor, disabled, MIME/cursor/trùng/thiếu record, metadata v2 đúng 16 trường/loop bounds/draft round-trip, draft v1 và giới hạn v1, cache hit/miss, hỏng hash dù cùng size, stream lỗi/thiếu/dư byte/sai checksum/hủy và cleanup part, hai lease bảo vệ eviction, identity gồm version/ID/checksum.

Test dùng fake RPC responses và byte stream trong thư mục tạm; không gọi Supabase và không chạy local server. Đây không phải test codec OGG, ExoPlayer, refresh JWT thực tế hoặc UI lifecycle trên thiết bị.

`adb devices` không có thiết bị/emulator kết nối. Không có smoke test mở Picker/Editor, audio focus, phát OGG/offline hoặc network/session trên Android trong lần này. Không assemble/export APK/AAB.

| Mức bằng chứng | Trạng thái |
|---|---|
| SOURCE IMPLEMENTED | Catalog/cache/Picker/model v2 và adapter Editor đã triển khai |
| BUILD VERIFIED | Java, XML/resources và 11 unit tests PASS |
| CLOUD VERIFIED | Owner cung cấp: 50 active, Storage đúng 50, GET/hash 50/50 PASS; agent chưa kiểm thử hai RPC/Storage bằng user authenticated active |
| ANDROID RUNTIME VERIFIED | Chưa xác minh; không có thiết bị/emulator kết nối |

## 7. Giới hạn còn lại

Không có blocker compile/unit test. Cần smoke test thực tế với tài khoản active để xác nhận endpoint/policy, 401 refresh, 403/404, preview OGG, seek/focus/tai nghe, xoay màn hình/background, hủy tải, mất mạng sau cache và chuyển sang Editor. Test đơn vị cache xác nhận byte integrity và reuse, không chứng minh âm thanh đã phát.

Editor/export hiện chưa lặp nhạc khi media dài hơn phần nhạc nguồn. Có thể chọn/nghe thử mọi bài trong Picker và mở Editor; đoạn cần loop chưa lưu/phối như đoạn đủ dài. Model/backend v2 đã chuẩn bị loop mặc định, không thêm `loop_enabled` thành trường thứ 17. Không tuyên bố MP4 mới có loop chính xác.

File pin được giữ cho tới khi host destroy; bài đã bỏ khỏi một draft có thể còn được pin trong phần đời còn lại của host để tránh ảnh hưởng player/export cũ. Khi cache đầy, giữ an toàn có thể khiến lượt tải mới cần thử lại sau khi đóng host. Audio cache dùng chung các tài khoản vì là fixed catalog; danh sách offline tách theo tài khoản, tải mới/publish vẫn qua quyền server.

## 8. Tương thích Openverse

Giữ tên Activity/extra và carrier `OpenverseTrack` để tránh đổi call sites không cần thiết. Carrier có library DTO tùy chọn; legacy constructor, cache 24 giờ, restore draft và metadata v1 13 trường vẫn còn. Validator dispatch v2 bằng `contract_version`; validation v1 không được nới thời lượng. Picker không gọi API tìm kiếm/download Openverse cho lựa chọn mới.

Nội dung đã đăng vẫn đọc MP4/metadata cũ theo luồng hiện hữu. Attribution nhận thêm `artist` của v2 và fallback `creator` v1. Không sửa feed/player Story/Reels hoặc RPC publish. Publish capability gate cũ vẫn được kiểm tra khi đăng và khi repository làm mới catalog.

## 9. Điều kiện bắt đầu Task 04

Source/build đủ để tiếp tục Story Music. Trước khi chốt nghiệm thu tích hợp cần smoke test active user trên thiết bị; giữ gate publish cũ và server validation v2. Task 04 cần triển khai timeline loop thực tế, preview/export đồng nhất, phối source audio/volume cho Story ảnh/video, kiểm tra MP4 có audio/duration đúng và publish/read lại v2. Task 05 tiếp tục Reels/integration. Các kết quả runtime này chưa được Task 03 chứng minh.
