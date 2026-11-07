# Meeto — Task 51: Session integrity & media stability

**Ngày:** 07/11/2026 · **Phạm vi:** rà source và sửa client; không kết nối/deploy Supabase Cloud. Không build, test, emulator, E2E hay tạo APK/AAB.

## AUD-002 — Session và đổi tài khoản: đã sửa

Nguyên nhân gốc là `SessionManager.accept()` ghi UID/token mới nhưng không đổi `generation`; chỉ `clear()` đổi generation. Vì `Api.Job`, callback, upload và một số cache dùng generation để tách phiên, một lần accept tài khoản khác có thể để request/cache của phiên cũ tồn tại. Luồng callback còn có nhánh nhận access/refresh token từ Intent; GET `/auth/v1/user` chỉ xác nhận token được Supabase phát hành, chưa ràng buộc nó với PKCE hoặc yêu cầu xác minh đang chờ.

`accept()` nay lưu trạng thái kế tiếp trước khi công bố; đổi UID làm tăng generation, hủy OkHttp calls, xóa cache phiên và phát tín hiệu dọn cache; accept lặp lại hoặc refresh cùng UID không tăng generation. `clear()` tăng generation. Chỉ API job xác thực đã nhận session mới được chuyển sang generation mới để tiếp tục bootstrap; job khác bị chặn ở bước gửi HTTP, khi giao callback và ở các repository. Dữ liệu session được kiểm tra trước khi lưu.

Cache Reels được xóa ngay khi identity đổi, gồm cursor/loading của hai tab. Feed repository vốn reset theo generation; Post Viewer và Story Rings nay cũng dọn cache theo identity. Feed image cache có UID trong key; private asset fetch có UID/generation trong key/guard. Realtime event được kiểm generation cả lúc nhận lẫn lúc phát tín hiệu lên UI. Pagination/API callback cũ không được giao cho tài khoản mới.

Callback `AuthCallbackActivity` nay chỉ nhận `code` PKCE hoặc `token_hash` với loại xác minh hợp lệ; đã bỏ nhánh nhận access/refresh token tùy ý từ fragment Intent. Flow lưu trong vault email, UID khởi tạo, loại flow và PKCE verifier. Callback phải khớp generation hiện tại, UID, loại flow và email trong session do Supabase trả về; callback cũ/sai không ghi đè session. Không thêm log token hay secret. Đường code PKCE tiếp tục dùng exchange endpoint; OTP/token-hash vẫn qua Supabase verify.

## AUD-003 — Thumbnail: đã gia cố; nhận định permit của AUD-003 chưa được xác nhận

Đọc lại source Task 50 cho thấy `DECODERS.acquire()` nằm **trước** `try/finally`. Nếu bị interrupt khi đang chờ, luồng thoát trước khi vào `finally`, nên không release permit dư như AUD-003 mô tả. Phần này là báo cáo sai so với source hiện tại.

Lỗi tài nguyên có thật: `Api.run()` có thể bỏ callback khi job bị hủy/đổi session nhưng không dọn kết quả thành công; nếu kết quả là Bitmap, bitmap bị bỏ rơi. `MediaThumbnailLoader.clear()` cũng không recycle bitmap đã gắn trước khi ViewHolder dùng lại. Nay job dùng `tryAcquire` có kiểm tra hủy mỗi 250 ms, giữ acquired flag, phục hồi interrupt, release permit đúng một lần và release `MediaMetadataRetriever` trong `finally`. Bitmap được recycle khi ViewHolder đổi media, target không còn đúng, callback bị loại do hủy/session mới, hoặc task lỗi. Binding vẫn so sánh đúng object `Load`; chiến lược cover → frame → icon và decode ngoài Main Thread không đổi.

Semaphore công bằng giới hạn tối đa **2** lần tải/giải mã thumbnail đồng thời. Việc hủy khi chờ permit phản hồi trong tối đa một nhịp 250 ms. `MediaMetadataRetriever.setDataSource(url, headers)` vẫn dùng network nội bộ native: không có timeout/cancel đáng tin cậy ở mức API này. Nếu nó treo trong native call, job giữ một trong hai permit cho đến khi native call trả về; giới hạn đồng thời vẫn giữ nhưng không thể khẳng định dừng tức thời. Supabase `info`/OkHttp dùng timeout của client, nhưng timeout đó không áp dụng cho phần tải do retriever thực hiện. Token lấy trước khi mở URL có thể hết hạn hoặc ACL đổi trong lúc đọc; lỗi sẽ dùng icon fallback, không retry lặp. Không thêm retry decode liên tục.

## AUD-001 — Tag Management: lỗi được xác nhận từ source

Public `list_my_post_tags` và private implementation hiện đều `STABLE`. Client gọi RPC bằng POST; theo quy tắc PostgREST, RPC `STABLE` chạy transaction chỉ đọc. Advisory lock trong `b_actor()` tự nó không được dùng làm căn cứ kết luận. Đường gây xung đột là `b_actor()` gọi `lock_profile()`, nơi có `SELECT ... FOR UPDATE` trên `auth.users`, `account_status` và `profiles`; row lock này không tương thích với transaction chỉ đọc. PostgreSQL ghi rõ lệnh trong `STABLE` chạy read-only, còn PostgREST chọn access mode RPC theo volatility. [PostgreSQL SPI/volatility](https://www.postgresql.org/docs/17/spi-visibility.html), [PostgREST transaction rules](https://docs.postgrest.org/en/v13/references/transactions.html).

Đã thêm migration tiến `20261107000800_tag_management_rpc_transactions.sql`: đổi volatility của cả private function và public wrapper sang `VOLATILE`, giữ nguyên body, auth/capability guards, signature, quyền và response. **Owner cần triển khai migration này lên Cloud; chưa deploy.** Runtime Cloud chưa được xác minh.

## AUD-004 — GIN index: không thay đổi migration

`20261107000700_search_reels_posts.sql` tạo ba GIN index thường trong một transaction: một trên `posts`, hai trên `profiles`. PostgreSQL dùng SHARE lock cho `CREATE INDEX` thường; lock này chặn INSERT/UPDATE/DELETE trong khi đọc vẫn chạy. Do các index nằm cùng transaction, lock của bảng đã index có thể được giữ tới COMMIT trong lúc các bước migration còn lại chạy. PostgreSQL xác nhận build thường có thể khóa writer; `CONCURRENTLY` tránh khóa writer nhưng cần thêm lượt quét và có ràng buộc transaction riêng. [PostgreSQL CREATE INDEX](https://www.postgresql.org/docs/17/sql-createindex.html), [table lock modes](https://www.postgresql.org/docs/17/explicit-locking.html).

Owner nên xem kích thước `posts`/`profiles`, tốc độ ghi và lịch sử migration Cloud trước khi deploy; chọn thời điểm ghi thấp, đặt giới hạn chờ khóa phù hợp và theo dõi lock wait. Nếu bảng lớn, cần thiết kế cách tạo concurrent riêng và xác nhận migration runner cho phép chạy ngoài transaction; không sửa migration đã có khả năng deploy. Kích thước/lưu lượng Cloud chưa biết nên chưa thể ước lượng thời gian khóa.

## File thay đổi

- `app/src/main/java/com/example/meetomxh/supabase/SessionManager.java`
- `app/src/main/java/com/example/meetomxh/supabase/Api.java`
- `app/src/main/java/com/example/meetomxh/supabase/SupabaseHttpClient.java`
- `app/src/main/java/com/example/meetomxh/supabase/SupabaseAuthClient.java`
- `app/src/main/java/com/example/meetomxh/supabase/RealtimeClient.java`
- `app/src/main/java/com/example/meetomxh/AuthCallbackActivity.java`
- `app/src/main/java/com/example/meetomxh/repositories/ReelsRepository.java`
- `app/src/main/java/com/example/meetomxh/supabase/PostViewerUpdates.java`
- `app/src/main/java/com/example/meetomxh/ui/StoryRings.java`
- `app/src/main/java/com/example/meetomxh/ui/MediaThumbnailLoader.java`
- `supabase/migrations/20261107000800_tag_management_rpc_transactions.sql` — owner deploy
- `TASK51_HOTFIX_REPORT.md`

## Rà soát tĩnh và giới hạn

Đã lần theo source cho các trường hợp: login A → refresh A; logout A → login B; request A còn pending khi B được nhận; reset Reels cache/cursor và Feed state; Realtime event A đến muộn; callback cũ khi generation/identity đổi; khôi phục vault sau restart; thumbnail acquire/release, interrupt lúc chờ, decode lỗi, target đã recycle, job bị loại trước callback. Đây là rà source, **không phải kiểm chứng runtime**.

Không build/test/emulator theo yêu cầu. Cần owner kiểm tra thủ công login/signup/email verification/resend/recovery/deep link sau khi xác nhận Cloud redirect đang trả `code` hoặc `token_hash` + `type`; deep link cũ chỉ trả access token trong URL fragment nay sẽ bị từ chối. Cần kiểm tra hai tài khoản, refresh không reset Reels, logout/session expiry, realtime reconnect, cuộn nhanh Reels Search và rời màn hình trong khi thumbnail đang tải. MediaMetadataRetriever native không bảo đảm hủy/timeout tức thời. Chữ ký/grant/volatility trong Cloud và trạng thái migration chưa được kiểm tra.

**Kết quả:** AUD-002 **FIXED** theo rà source; AUD-003 **FIXED** cho vòng đời permit/Bitmap, đồng thời đính chính over-release chưa được source xác nhận; AUD-001 **CONFIRMED** với migration owner cần triển khai; AUD-004: review size/traffic và thời điểm deploy, cân nhắc phương án concurrent riêng nếu cần.
