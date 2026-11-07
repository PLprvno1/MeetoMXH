# MEETO — TASK 52B: Music SQL migration review

## Phạm vi và kết luận

Đã rà source migration `20261107000900_openverse_music_metadata.sql` cùng các projection và RPC hiện hành từ Task 36, 42, 43, 47, Search và Tag; đối chiếu `MusicPublishingClient`, `MusicComposition`, `OpenverseTrack`, `CreateReelActivity`, `CreatePostActivity` và `StoryCreateActivity`.

Migration đã được chỉnh để ràng buộc CC0 chính xác, chặn đoạn audio vượt nguồn, so thời lượng metadata với thời lượng media mà client gửi, và chỉ trả metadata qua projection đã áp ACL. Các RPC tạo Post/Reel/Story có nhạc vẫn gắn metadata trong transaction tạo nội dung. Không đổi RPC tạo nội dung cũ, audience, bucket, hay luồng không nhạc.

**Kết luận source:** sẵn sàng cho owner kiểm tra prerequisite/catalog Cloud trước khi triển khai. Đây là đánh giá tĩnh, không phải SQL runtime PASS. Duration được đối chiếu với `p_duration_ms`, nhưng hiện upload flow chỉ xác minh Storage object metadata; database không có duration MP4 được decoder server xác minh. Vì vậy check này ngăn metadata và tham số lệch nhau, nhưng không chứng thực chống client cố ý khai sai cả hai.

## 1. Validation và JSON contract

`p->>'license'<>'cc0'` có thể cho kết quả SQL `NULL` khi giá trị là JSON `null` hoặc thiếu. `IF` không xem điều kiện `NULL` là true, nên validator cũ có thể tiếp tục. Validator mới yêu cầu `jsonb_typeof(p->'license') IS NOT DISTINCT FROM 'string'` và `p->>'license' IS NOT DISTINCT FROM 'cc0'`. Do đó chỉ nhận đúng chuỗi thường `cc0`; từ chối SQL NULL, JSON null, thiếu field, chuỗi rỗng, chữ hoa và mọi license khác. Điều kiện khác dùng kiểm tra kiểu `IS DISTINCT FROM` và predicate có `COALESCE` để không dựa vào SQL three-valued logic.

Schema yêu cầu object đúng 13 khóa, không khóa dư; metadata tối đa 16 KiB; ba thời lượng phải là JSON number dạng số nguyên; ID, URL, độ dài chuỗi và giá trị null/thiếu được kiểm tra riêng. License URL phải là CC0 URL theo path `/publicdomain/zero/1.0`. Android `validPublishedMetadata` được siết theo cùng danh sách khóa, kiểu số nguyên JSON, license viết thường chính xác, URL CC0 và giới hạn bytes.

Hash giữ hợp đồng cũ: SHA-256 dạng lowercase hex của `music_metadata::text` theo biểu diễn JSONB. Check constraint chỉ nhận cặp metadata/hash cùng NULL (nội dung cũ) hoặc cả hai có mặt, metadata hợp lệ và hash khớp. Metadata null JSON không tương đương SQL NULL và bị từ chối.

## 2. Audio range và thời lượng thành phẩm

Validator giữ `duration_ms` nguồn trong 1.000–3.600.000 ms và `used_duration_ms` trong 1.000–60.000 ms. Audio range chỉ hợp lệ khi `start_offset_ms <= duration_ms - used_duration_ms`; giới hạn số chữ số trước khi cast giúp tránh số ngoài miền. Không chấp nhận metadata mô tả đoạn dài hơn nguồn.

`MusicComposition` trước đây bật loop cho sequence nhạc. Loop đã được bỏ khỏi hai composition có nhạc. Composition chọn đoạn có thật trong tệp audio và từ chối nếu phần thiếu so với video vượt 300 ms. Metadata sau export dùng thời lượng đoạn đã chọn/cắt; `OpenverseMusicClient` kiểm tra range và giới hạn sai số export 300 ms.

Hai RPC Reel so `used_duration_ms` với `p_duration_ms`, yêu cầu cả hai 1.000–60.000 ms và chênh tối đa 300 ms. Story music RPC mới nhận thêm `p_duration_ms` và áp cùng check. Android Story lấy thời lượng từ MP4 đã xuất, gồm Story ảnh đã render thành MP4; chỉ gửi music RPC nếu tệp là `video/mp4`, và kiểm tra duration, video/audio track cùng giới hạn media cục bộ.

Giới hạn quan trọng: `p_duration_ms` do Android gửi. Task 47 direct-upload và Story finalizer xác nhận object, owner, MIME, dung lượng và bucket, nhưng không decode MP4 để ghi duration đáng tin cậy vào `media_assets.metadata`. SQL hiện không thể xác minh độc lập duration thật của tệp. Không có Openverse request từ PostgreSQL.

## 3. Task 47 identity, retry và transaction

- `create_reel_with_music` gọi `public.create_reel`, giữ một canonical Post ID và một Reel trên cùng Post.
- `create_post_reel_with_music` vẫn gọi `public.create_post_reel_with_mentions`; giữ nguyên mentions, tags, audience và fingerprint/idempotency của Task 47.
- `create_story_with_music` vẫn gọi `public.create_story`; chỉ thêm tham số duration cho nhạc. Story phải trả `mime_type='video/mp4'` trước khi metadata được gắn. Story ảnh không nhạc vẫn đi qua RPC cũ.
- Binder khóa hàng Post/Story `FOR UPDATE`, kiểm owner và trạng thái, rồi gắn metadata một lần. Cùng hash là retry idempotent; hash khác trả conflict và không ghi đè metadata cũ.
- Các lời gọi nested nằm trong transaction của RPC. Nếu binder, projection hoặc validation lỗi sau khi tạo content/mentions/tags, exception làm rollback toàn bộ lần gọi.
- Không thay audience; các hàm nền hiện hành tiếp tục xác thực actor, media owner, quyền truy cập, trạng thái và request ID.

## 4. Projection trước và sau

Đã so projection hiện hành với các định nghĩa gần nhất: `post_json` sau Task 36/mentions/privacy và `story_json` sau Task 42 Story experience. Task 47 giữ Reel/Post ID; Task Search dùng lại `post_json`/`reel_card`; Task Tag giữ projection `mentions`/`tags`.

| Projection | Field trước migration | Sau migration |
|---|---|---|
| `private.post_json` | `id`, `author_id`, `group_id`, `state`, `body`, `audience`, `kind`, `is_reel`, `original_post_id`, `original_available`, `author`, `reactions_count`, `comments_count`, `shares_count`, `my_reaction`, `is_saved`, `version`, `created_at`, `updated_at`, `media`, `mentions`, `tags` | Toàn bộ field cũ, thêm `music_metadata` |
| `private.story_json` | `id`, `author_id`, `author_name`, `avatar_asset_id`, `avatar_preset`, `media_asset_id`, `mime_type`, `caption`, `audience`, `created_at`, `expires_at`, `viewed`, `liked`, `views_count`, `own` | Toàn bộ field cũ, thêm `music_metadata` |

`private.reel_card` không bị thay; nó nối `post_json` và thêm projection Reel (`reel_duration_ms`, cover, view count, following), nên metadata đi theo đúng ACL Post hiện có. Search cards và các projection Feed/Profile tiếp tục gọi `post_json`/`reel_card`. Story getters tiếp tục gọi `story_json`.

Hai function thay thế vẫn `STABLE SECURITY DEFINER SET search_path=''`. `post_json` giữ `private.can_read_post`; `story_json` giữ active/expiry, `private.can_read_story` và `private.can_read_media`. Metadata không được đưa vào kết quả khi caller không đọc được Post/Story. Tên/avatar, mentions, tags, media và share source giữ nguyên điều kiện riêng. `CREATE OR REPLACE` giữ ACL function hiện có; migration không grant bảng hay metadata columns.

## 5. RPC, volatility và quyền

| RPC | Signature | Volatility / security | API access |
|---|---|---|---|
| `get_music_publish_capabilities` | `()` | STABLE, SECURITY DEFINER, `search_path=''` | authenticated |
| `create_reel_with_music` | `(uuid,uuid,text,text,integer,integer,uuid,jsonb)` | VOLATILE, SECURITY DEFINER, `search_path=''` | authenticated |
| `create_post_reel_with_music` | `(uuid,text,text,integer,jsonb,uuid[],uuid,jsonb)` | VOLATILE, SECURITY DEFINER, `search_path=''` | authenticated |
| `create_story_with_music` | `(uuid,text,text,uuid,integer,jsonb)` | VOLATILE, SECURITY DEFINER, `search_path=''` | authenticated |

Các private bind/create helpers VOLATILE, SECURITY DEFINER, fixed empty search path; validator IMMUTABLE, SECURITY INVOKER; hai projection STABLE, SECURITY DEFINER. Private functions bị REVOKE khỏi PUBLIC/anon/authenticated/service_role. Public RPC bị REVOKE khỏi PUBLIC/anon/service_role rồi chỉ GRANT EXECUTE cho authenticated. Không có grant trực tiếp DML hoặc SELECT các cột metadata.

Capability RPC giữ STABLE. `private.b_active()` cũng STABLE và chỉ gọi `require_identity()`/`is_active()`; các helper này chỉ đọc trạng thái, không row lock. Vì vậy guard này tương thích transaction READ ONLY. Các RPC mutate gọi guard actor phù hợp bên trong VOLATILE function.

## 6. Storage, RLS và nội dung cũ

Migration không tạo hoặc đổi bucket, không đổi RLS/policy, không cấp quyền đọc/ghi cột. Story upload hiện dùng `social-media` private bucket; finalizer kiểm owner, object, MIME, bucket private và giới hạn hiện hành. Task 47 policy giới hạn video theo `min(50,000,000 bytes, bucket limit)` và yêu cầu MP4. Reel base RPC xác nhận asset/object, owner, trạng thái và size. Music vẫn nằm trong MP4 cuối; không upload audio riêng.

Các hàng Post/Story cũ được phép giữ `music_metadata` và hash cùng NULL; không backfill. Không thay đổi behavior của Post/Story không nhạc. Metadata hash không được trả trong projection.

## 7. Cloud prerequisites

Trước deploy, đối chiếu migration ledger/catalog Cloud. Prerequisites được kiểm tra trong migration gồm Story (`get_story`, `create_story`, Story tables/projection/ACL), Reel/Task 43 + hotfix (`create_reel`, `get_reel_upload_policy`, `reel_card`), Task 47 (`create_post_reel_with_mentions` và private reel/mention/tag helpers), cùng các bảng/projection/ACL Post và media. Các helper actor, ACL và Tag phải hiện diện. Bảo đảm `social-media` là private, cho phép `video/mp4`, và effective size limit phù hợp client.

Migration dừng nếu metadata columns đã tồn tại; trước retry phải inventory attempt trước thay vì chạy mù. Apply theo thứ tự sau các migrations prerequisite; không deploy hoặc chạy SQL Cloud trong Task 52B.

## 8. File thay đổi và kiểm chứng

- `supabase/migrations/20261107000900_openverse_music_metadata.sql`
- `app/src/main/java/com/example/meetomxh/music/MusicComposition.java`
- `app/src/main/java/com/example/meetomxh/music/OpenverseMusicClient.java`
- `app/src/main/java/com/example/meetomxh/music/OpenverseTrack.java`
- `app/src/main/java/com/example/meetomxh/music/OpenverseMusicEditor.java`
- `app/src/main/java/com/example/meetomxh/CreateReelActivity.java`
- `app/src/main/java/com/example/meetomxh/CreatePostActivity.java`
- `app/src/main/java/com/example/meetomxh/StoryCreateActivity.java`

Chỉ đọc và đối chiếu source. Không chạy SQL, Supabase Cloud, Gradle build, test, emulator hoặc tạo APK. Shell hiện không có lệnh `git`, nên `git status`/`git diff --check` không chạy được. Không tuyên bố runtime PASS.
