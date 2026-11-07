# MEETO — Task 52C: Openverse search recovery & Music Picker hardening

Ngày bàn giao: 07/11/2026 (Asia/Bangkok). Phạm vi: sửa source Android local hiện tại sau Task 52B; không build, chạy test, emulator, export APK hoặc deploy Cloud.

## 1. HTTP 400: đã xác nhận tại Search

Khi mở Picker, source cũ mặc định `chill music`, page 1, page_size 15, gọi duy nhất Audio Search. Detail chỉ được gọi khi người dùng nghe thử/chọn bài. Sau Detail mới kiểm tra trang nguồn, giấy phép và tải audio; không có 15 Detail requests khi render danh sách.

Request cũ được dựng bằng `HttpUrl.Builder`, GET:

```text
https://api.openverse.org/v1/audio/?q=chill%20music&license=cc0&category=music&page_size=15&page=1&filter_dead=true&mature=false&unstable__include_sensitive_results=false
```

Header source Android: `Accept: application/json, audio/*, application/octet-stream` và `User-Agent: Meeto-Android/1.0 OpenverseMusic`. Client OkHttp riêng, không interceptor/session header Supabase. Query builder đã encode một lần; không tìm thấy double encoding.

Đã replay URL cũ bằng GET đọc dữ liệu từ workspace. Server trả **HTTP 400** với body:

```json
{"detail":{"unstable__include_sensitive_results":["`mature` and `unstable__include_sensitive_results` must not both be defined."]}}
```

**Root cause CONFIRMED:** Search gửi đồng thời hai tham số mutually exclusive, kể cả khi cả hai là `false`. Đây là xác nhận bằng response của URL trong source, không phải Logcat Android. Chưa chạy app để chứng minh mọi lỗi HTTP 400 trên thiết bị đều đã hết.

Request mới mặc định, cùng header, GET:

```text
https://api.openverse.org/v1/audio/?q=music&license=cc0&page_size=15&page=1
```

Đã GET request mới với thứ tự query tương đương: **HTTP 200**, `result_count=240`, `page_count=16`, `page_size=15`; header burst `20/min`. Không còn `category`, `filter_dead`, `mature` hoặc `unstable__include_sensitive_results`. Không tự retry 400, 429, 500 hay response rỗng. Retry là thao tác người dùng, có budget/backoff trước request.

Chẩn đoán debug tag `OpenverseMusic`: stage Search/Detail, method, HTTP status, license/page/page_size, số ký tự query, request generation, retry mode và thời gian cooldown. Body lỗi chỉ peek tối đa 2 KiB, ánh xạ lỗi đã biết thành mã chẩn đoán cố định; lỗi khác redacted. Không log query nguyên văn, raw response, creator, URL nguồn/audio, signed query, header Authorization hay token. Giữ riêng lỗi 400 request, 429 Openverse/local budget và lỗi kết nối/HTTP khác.

## 2. CC0, category null và duration

Ba lớp cũ đều yêu cầu category music: query Search, vòng lọc Search và parser (Detail còn kiểm lại category). Chúng làm mất track CC0 hợp lệ có category null. Loại filter category này độc lập với sửa HTTP 400: HTTP 200 rỗng khi lọc category không phải lỗi request.

Search vẫn gửi `license=cc0`; parser Search và Detail vẫn kiểm license CC0, ID UUID, title/provider/source, CC0 license URL chuẩn, HTTPS source/audio/license và duration 1.000–3.600.000 ms. Published metadata tiếp tục yêu cầu đúng 13 khóa và license thường `cc0`, range thực, thời lượng dùng 1–60 giây theo Task 52B. Không mở rộng sang license khác.

Category music được giữ. Khi category thiếu/null/khác, phải có dấu hiệu âm nhạc trong title, tags hoặc genres: music, melody, piano, ambient, instrumental, beat, guitar, chill, jazz, synth, soundtrack và các từ liên quan. Tags hỗ trợ object `name` hoặc string; genres/category null an toàn. Không so title với nguyên văn query; không tự coi mọi CC0 là bài hát. Đây là heuristic metadata, không phải bộ phân loại âm thanh: vẫn có thể bỏ sót nhạc hoặc giữ một sound effect có tag âm nhạc. Không có track hardcoded hoặc truy vấn fallback tự động.

**Lỗi duration riêng đã sửa:** parser cũ nhân Openverse `duration` với 1.000 dù API đã trả milliseconds. Track `Music Box` có duration `69746` bị biến thành `69746000` ms và vượt giới hạn một giờ. Parser mới giữ `69746` ms. Android MediaExtractor trả microseconds nên đường xác minh file vẫn chia 1.000 để ra milliseconds; không nhầm hai đơn vị.

GET thứ ba và cuối cùng tới Audio Detail:

```text
https://api.openverse.org/v1/audio/eec0d244-b4e7-463b-81fe-1920abdf88e9/
```

Response thành công xác nhận `Music Box`, cùng ID, CC0, category/genres null, tag music, duration `69746`, provider/source freesound, license URL HTTPS CC0 chuẩn và `url` HTTPS MP3 trên CDN. `foreign_landing_url` là trang nguồn riêng. Không tải audio hoặc truy cập hàng loạt nguồn trong task này. Hai web-tool open ban đầu không đọc được endpoint; ba GET qua PowerShell ở trên là bằng chứng HTTP thực.

Khi nghe thử/chọn, Detail là metadata authoritative: phải cùng ID, qua CC0/URL/duration/musical checks; không tin Search nếu Detail đổi license hoặc thiếu field bắt buộc. Được dùng metadata Detail thay cho metadata Search nếu hợp lệ. Creator thiếu/null thành chuỗi rỗng, UI ghi Không rõ tác giả; không bịa creator. Không dùng detail URL/landing page thay audio URL. Filetype/alt_files không được dùng để khẳng định codec hoặc tự chuyển sang một URL thay thế chưa xác minh; `url` là đường tải chính, MIME và codec được kiểm tra từ response/file thực.

## 3. Rate limit và cache

- Ngân sách bảo thủ dùng chung trong process: tối đa **18 API requests/phút**, **180 requests/24 giờ**, khoảng cách tối thiểu 500 ms. Timestamp và cooldown được lưu SharedPreferences để Activity/process recreation không reset ngân sách. Budget chỉ đếm host API Openverse, không trộn HEAD/GET đến provider hoặc Creative Commons.
- Đọc `X-RateLimit-Limit-anon_burst` và `X-RateLimit-Limit-anon_sustained`, hạ trần nếu server báo thấp hơn. HTTP 429 thiết lập cooldown; đọc `Retry-After` dạng giây hoặc HTTP-date, mặc định 60 giây nếu không có/không parse được. Không gửi request lại tự động sau cooldown; UI chỉ mở lại thao tác retry. Không chủ động tạo 429 để thử.
- Debounce 500 ms; CallHolder và request generation chặn request/callback cũ. Một executor trong Picker, hủy Call hiện tại trước request mới. Tắt OkHttp connection retry tự động.
- Search cache 5 phút, tối đa 40 entries; key gồm query đã trim, page, CC0, phiên bản bộ lọc và page_size. Cache cả trang rỗng, không gọi fallback. ViewModel giữ các trang đã tải qua configuration redraw, không gọi lại Search chỉ để render.
- Detail cache đã xác minh 15 phút, tối đa 40 IDs; chỉ truy cập khi nghe thử/chọn bài. Cache này cũng ghi nhận kiểm tra trang nguồn/license thành công. Khi TTL hết, xác minh lại; không HEAD/preload cả trang.
- Audio cache giữ giới hạn hiện có 120 MiB/24 giờ không truy cập. Thêm fingerprint audio URL + API duration + license URL để không lấy file cũ chỉ vì Detail vẫn cùng ID nhưng đổi media. Cache hợp lệ không tải lại; file vẫn được kiểm codec/duration trước trả về. Nếu kiểm tra file cache lỗi, xóa file để lần thử thủ công sau có thể tải lại.
- UI và client giữ cap 20 trang; UI không gọi page > page_count. Retry trang lỗi giữ đúng trang đó; page_count=0 hoặc trang cuối không hiện Xem thêm. Ghép trang deduplicate theo ID.

Giới hạn: quota anonymous có thể dùng chung IP với thiết bị/app khác; ngân sách local không bảo đảm server còn quota. Dữ liệu persisted dựa đồng hồ thiết bị. Không tuyên bố đã kiểm chứng 429/runtime hoặc qua process restart.

## 4. Audio safety, lifecycle và Editor

Giữ HTTPS, giới hạn bốn redirect, cấm userinfo/port ngoài 443 và địa chỉ nội bộ. Mỗi redirect được kiểm lại; thêm DNS validation ngay lúc OkHttp resolve để không chỉ dựa vào một lần DNS preflight. Chặn loopback/link-local/site-local/multicast, IPv6 unique-local và IPv4 shared-address space.

Tải theo stream buffer 32 KiB, kiểm Content-Length và byte count tối đa 25 MiB, không đọc file audio vào RAM. MIME phải audio/ogg/octet-stream; MediaExtractor + decoder availability xác nhận audio track và duration thực 1 giây–1 giờ, nên HTML giả MP3 không chỉ cần đổi extension/MIME là qua được. HEAD 403/405/501 cho trang nguồn/license được fallback sang GET chỉ kiểm response headers rồi đóng body, không gọi `body.string()` hay tải toàn bộ trang. Audio luôn tải GET, không phụ thuộc HEAD. Không thay landing page bằng audio URL.

CallHolder cũ bị clear ngay sau nhận headers nên cancel không ngắt body download. Nay giữ Call trong suốt streaming để cancel ngắt I/O, ngoài check hủy/session trong vòng đọc. Đổi bài hủy download cũ và giải phóng player; chỉ một preview, có nghe/tạm dừng/tiếp tục, giải phóng khi kết thúc/lỗi. onStop hủy request/debounce và player, requestVersion chặn callback đến muộn mở lại player hoặc trả track cũ. Logout/đổi identity hủy Call, xóa Search/Detail cache và đóng Picker; ngân sách anonymous không reset theo tài khoản.

Editor có thông báo rõ và chặn nghe thử/lưu khi đoạn nhạc thiếu quá 300 ms so với media; người dùng có thể Đổi bài, giảm thời lượng Story ảnh hoặc quay lại cắt video. Không để tới Media3 mới phát sinh lỗi khó hiểu. Editor dismiss/giải phóng preview khi Activity ON_STOP. Giữ tolerance Task 52B và không thêm looping.

## 5. Music Picker Light/Dark

Dùng token DayNight sẵn có (`app_bg`, `surface_variant`, `text_primary`, `text_secondary`, `primary_container`, `on_primary_container`, `outline_color`) thay màu nền/chữ cố định. Search background riêng, bo 16dp, stroke và hint tương phản. Chip Material với selector checked/unchecked và margin thống nhất, cuộn ngang; giữ bốn chip Chill, Piano, Vui vẻ, Nhẹ nhàng bằng search keywords.

Danh sách có title, creator/provider, duration, CC0/source, nghe/tạm dừng và chọn bài. Link nguồn/license/ẩn chuyển sang hàng cuộn riêng để không bị năm nút chen ngoài màn hình. Progress dùng INVISIBLE thay GONE, status có minHeight để hạn chế nhảy layout. Loading, empty, lỗi request, lỗi mạng và cooldown có thông báo riêng. Retry/Xem thêm dùng màu Meeto. Root nhận system bars/display cutout/IME insets; manifest adjustResize hiện có được giữ. Chưa chụp screenshot hoặc đo tương phản trên thiết bị.

## 6. File thay đổi

1. `app/src/main/java/com/example/meetomxh/music/OpenverseMusicClient.java`
2. `app/src/main/java/com/example/meetomxh/music/OpenverseMusicCache.java`
3. `app/src/main/java/com/example/meetomxh/music/OpenverseMusicPickerActivity.java`
4. `app/src/main/java/com/example/meetomxh/music/OpenverseMusicEditor.java`
5. `app/src/main/res/layout/activity_openverse_music_picker.xml`
6. `app/src/main/res/drawable/bg_music_search.xml` (mới)
7. `app/src/main/res/color/music_chip_background.xml` (mới)
8. `app/src/main/res/color/music_chip_text.xml` (mới)
9. `TASK52C_OPENVERSE_RECOVERY_REPORT.md` (mới)

Đã đọc `OpenverseTrack`, `MusicComposition`, `MusicPublishingClient`, `MusicAttribution`, các layout Music, manifest/themes và các đường gọi Picker từ CreateReel/StoryCreate. Không đổi 13-key metadata, result extra/draft contract, MusicComposition, exporter, upload, RPC, retry/idempotency hoặc capability probe. Reel thường, EXTRA_EDIT_ONLY/Create Post, Story video và Story ảnh tiếp tục dùng cùng picker/editor. Các flow này chỉ nhận lựa chọn đã xác minh và duration thực như trước. Migration 00900 đã triển khai và toàn bộ Task 53 Comments không bị sửa.

## 7. Kiểm chứng và các case bàn giao

Đã thực hiện: ba HTTP GET đọc dữ liệu nêu trên; parse XML 6/6 (picker, drawable, hai selector mới, editor layout và manifest); lexical delimiter check 4/4 file Java sửa; rà tĩnh query builder, cache keys, pagination, generation, metadata range, lifecycle và các call sites. Delimiter check không phải Java compilation. Shell không có git nên không chạy được git status/diff; không reset hoặc push.

| Case yêu cầu | Bằng chứng / tình trạng |
|---|---|
| 1. music + CC0 | GET thực 200, 240 kết quả; Android chưa chạy |
| 2. category null | Search và Detail thực có null; source mới giữ khi title/tag có dấu hiệu nhạc |
| 3. non-CC0 | Parser Search/Detail từ chối; chỉ rà source |
| 4. 200 + [] | Empty state, không tự retry/fallback; chỉ rà source |
| 5. 400 | Replay source cũ tái hiện chính xác; source mới bỏ cặp flags xung đột |
| 6. 429 | Persisted backoff + Retry-After + UI guard; chưa kích hoạt lỗi thật |
| 7. 500/offline | Không auto retry, retry thủ công có budget; chưa giả lập mạng |
| 8. Tiếng Việt | HttpUrl.addQueryParameter nhận raw Unicode một lần; chưa gọi API cho case này |
| 9. Chill/Piano | Chip search terms, không ép category, empty có gợi ý; chưa GET từng chip |
| 10. Trang cuối | Guard page_count/cap 20 và retry đúng trang; chưa duyệt API nhiều trang |
| 11. Audio dưới 15 giây | Không yêu cầu nguồn >=15 giây; Editor chặn đoạn media dài hơn nguồn; chưa decode thật |
| 12. Audio trên 60 giây | Detail thực có 69.746 giây; source cho phép tới 1 giờ và đoạn dùng <=60 giây |
| 13. URL nguồn hỏng | HTTPS/redirect/reachability errors fail closed; chưa thử provider lỗi |
| 14. Detail khác Search | Cùng ID, Detail authoritative; cache media fingerprint; chỉ rà source |
| 15. Đổi bài lúc tải | Cancel Call cả body + generation; chỉ rà source |
| 16. Đóng khi preview | onStop/onDestroy release và chặn callback cũ; chỉ rà source |
| 17. Light/Dark | XML hợp lệ và token/selector hai theme; chưa kiểm UI runtime |
| 18. Về Editor giữ lựa chọn | Draft/result extra và callback contract giữ nguyên; chưa kiểm navigation runtime |

Không chạy Gradle, unit test, E2E, emulator, export APK hoặc Cloud theo yêu cầu. Chưa xác minh Java/API compatibility bằng compiler, codec trên máy, provider HEAD/GET thực tế, download/cache runtime, Media3 playback/mix, UI/navigation/keyboard, account switch, export và publishing end-to-end. Các nhãn FIXED dưới đây chỉ tình trạng sửa source và bằng chứng API độc lập, không phải Android runtime PASS.

- API SEARCH: FIXED.
- HTTP 400 ROOT CAUSE: CONFIRMED.
- CC0 FILTERING: FIXED.
- RATE LIMIT: FIXED.
- MUSIC PICKER UI: FIXED.
- RUNTIME VERIFIED: NO.
