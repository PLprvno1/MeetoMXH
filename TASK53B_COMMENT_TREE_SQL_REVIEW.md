# TASK 53B — Comment Tree SQL Review

## Phạm vi

Đã rà soát tĩnh migration `supabase/migrations/20261107001000_comment_tree_paging.sql`, thứ tự các migration tạo comments/authorization/mentions sau Task 36/42/43/47 và các đường gọi Android của `CommentClient`, `Comment`, `Dto`, `CommentAdapter`, `CommentsBottomSheetFragment`.

Không chạy SQL, không deploy Cloud, không chạy Gradle hoặc runtime test. Kết luận dưới đây là đánh giá source, không phải runtime PASS.

## 1. Lỗi đã xác nhận và cách sửa

1. `private.can_read_comment(uuid)` cho phép đọc `published` và `deleted` để giữ tombstone trong cấu trúc. `private.create_comment(uuid,uuid,text,uuid)` dùng hàm này để duyệt toàn chuỗi ancestor nên trước đây chấp nhận cả parent trực tiếp đã xóa. Migration mới thêm `BEFORE INSERT` trigger `private.require_published_comment_parent()`: parent trực tiếp phải cùng Post, `published` và đọc được. Trigger khóa parent bằng `FOR SHARE`; việc kiểm tra actor, quyền tương tác Post, giới hạn depth, idempotency và mentions vẫn do các RPC hiện hành thực hiện.
2. Projection trước đây chuyển `root_id` vật lý từ ancestor qua các descendants. Khi root bị xóa, UI nhận UUID root không còn hiển thị; `parent_id` cũng có thể tiết lộ UUID tombstone. Nay root và `display_depth` được suy ra từ ancestor chain có thể đọc; `parent_id` chỉ trả khi parent vật lý đang published và đọc được, còn `display_parent_id` là quan hệ dùng cho cây UI. Parent trong database không bị sửa.
3. Client từng quyết định `display_depth` đầu ra bằng `p_display_depth`. RPC vẫn giữ chữ ký tương thích nhưng kiểm tra giá trị này bằng depth được tính từ parent chain; depth trả về cho từng item luôn lấy từ server.
4. `comment_tree_node_json` trước đây duyệt children hai lần cho count và preview, rồi đệ quy dựng preview lồng nhau. Nay count/latest preview dùng chung một tập children materialized và JSON chỉ chứa một cấp preview. Node preview vẫn có `reply_count`; các cấp sâu được tải theo yêu cầu.

## 2. Pagination và preview

Pagination giữ thứ tự roots mới nhất trước và replies trong nhánh cũ đến mới. Cursor vẫn là cặp `(created_at,id)`; filter theo hướng so sánh `<` cho roots và `>` cho replies. Candidate thường và candidate được promote từ nhánh ẩn được giới hạn riêng ở `p_limit+1`, sau đó hợp nhất theo cùng thứ tự để tính `has_more`.

Giới hạn RPC là 1–30 item; Android hiện dùng `PAGE_SIZE=30`. Mỗi item chỉ nhúng tối đa một preview reply. Count và preview đều lấy từ các descendants `published` mà `private.can_read_comment` cho phép; hidden/deleted rows không được tính hay serialize. Android hợp nhất preview với trang đã tải theo comment ID và sắp replies tăng dần, do đó preview mới nhất không bị nhân đôi hoặc làm mất khi nằm ngoài trang đầu.

Hành vi theo số lượng replies: 0 trả count 0/preview null; 1 trả đúng một preview; từ 2 trở lên hiển thị preview cùng trạng thái mở nhánh; tải trang theo cursor, hợp nhất ID và giữ preview cuối trang nếu chưa tải tới. Thu gọn/mở lại giữ cache/cursor hiện tại; retry trang tiếp tục theo cursor. Đây là đọc mã tĩnh, chưa xác nhận bằng tương tác runtime.

## 3. Idempotency, transaction và luồng reply sau khi xóa

`create_comment` và `create_comment_with_mentions` không bị thay thế. `create_comment` vẫn xác thực Post/actor và tìm request idempotency trước vòng duyệt parent và trước INSERT. Retry thành công trả lại comment đã tạo nên không chạy trigger lần nữa; request hash khác vẫn lỗi conflict. Khi INSERT mới, trigger từ chối parent trực tiếp `deleted`, `hidden`, không đọc được, hoặc thuộc Post khác.

Reply trực tiếp vào tombstone bị từ chối. Reply vào comment `published` có ancestor `deleted` vẫn được chấp nhận: vòng cũ có thể đọc tombstone để duyệt ancestry, trong khi trigger chỉ kiểm tra parent trực tiếp. Replies lịch sử không bị cập nhật/xóa. Xóa comment tiếp tục là tombstone, vì vậy descendants vẫn được phân trang và có thể tương tác theo quyền hiện hành. Nếu ancestor là `hidden`, `create_comment` hiện hành không cho duyệt qua ancestor đó do `can_read_comment=false`; migration không nới chính sách ghi này.

Nếu tạo comment hoặc bind mentions lỗi trong RPC bọc mentions, PostgreSQL rollback toàn transaction như trước. Trigger không sửa mentions hay request IDs. Android sau khi xóa sẽ tạm nâng descendants đã tải và tính lại `display_parent_id`, `root_id`, `display_depth`; dữ liệu chưa tải được lấy lại từ projection server.

## 4. Root bị xóa nhưng descendants còn tồn tại

Với `A (deleted) -> B (published) -> C (published)`, trang roots promote B thành UI root: `root_id=B`, `display_parent_id=null`, `display_depth=0`; `parent_id` bị redacted vì parent vật lý A không còn published. Khi tải replies của B, C có `root_id=B`, `display_parent_id=B`, `display_depth=1`; `parent_id` là B vì B đang readable/published. `depth` vẫn phản ánh vị trí vật lý để không làm sai dữ liệu, trong khi UI dùng `display_depth`. Parent IDs trong database không đổi.

Nếu có nhiều ancestor ẩn/xóa liên tiếp, root hiển thị là ancestor published/readable cao nhất; các descendants được nối dưới parent hiển thị gần nhất khi nhánh được tải. Các node/nhánh khác nhau vẫn có ID riêng, không nhập chung thành một comment. UUID của ancestor không readable không được serialize qua `root_id`, `display_parent_id` hay `parent_id`.

## 5. JSON fields trước/sau

Projection comment trước và sau giữ nguyên các field và tên:

`id`, `post_id`, `author_id`, `author_name`, `author_avatar_asset_id`, `author_avatar_preset`, `parent_id`, `display_parent_id`, `root_id`, `depth`, `display_depth`, `body`, `state`, `version`, `created_at`, `updated_at`, `reactions_count`, `my_reaction`, `mentions`, `reply_count`, `preview_reply`.

Thay đổi là ngữ nghĩa/giới hạn: `parent_id` nullable khi parent vật lý bị redacted; `root_id` là UI root readable; `display_depth` là giá trị xác minh server; `preview_reply` không lồng thêm `preview_reply` cấp sâu. `Comment`/`Dto` đã có trường nullable phù hợp; `Comment.java` được ghi chú lại semantics. Adapter vẫn dedupe preview/page bằng ID và sắp xếp replies tăng dần.

## 6. RPC signatures, volatility và quyền

| RPC/function | Signature | Context/volatility và quyền |
| --- | --- | --- |
| `public.list_comment_tree` | `(uuid,uuid,integer,timestamptz,uuid,integer) returns jsonb` | `STABLE SECURITY INVOKER`, `search_path=''`; execute chỉ `authenticated` |
| `private.list_comment_tree` | Cùng 6 tham số | `STABLE SECURITY DEFINER`, `search_path=''`; execute `authenticated` để wrapper INVOKER gọi; tự kiểm tra active account, Post ACL, parent ACL và depth |
| `private.comment_tree_parent_position` | `(uuid,uuid) returns table(root_id uuid,parent_depth integer,display_depth integer)` | `STABLE SECURITY DEFINER`, `search_path=''`; helper nội bộ, revoke execute khỏi roles |
| `private.comment_tree_visible_children` | `(uuid,uuid) returns table(comment_id uuid,root_id uuid,depth integer,created_at timestamptz)` | `STABLE SECURITY DEFINER`, `search_path=''`; helper nội bộ, revoke execute |
| `private.comment_tree_hidden_children` | Cùng 2 tham số/return shape | `STABLE SECURITY DEFINER`, `search_path=''`; helper nội bộ, revoke execute |
| `private.comment_tree_page_candidates` | `(uuid,uuid,timestamptz,uuid,integer) returns table(comment_id uuid,root_id uuid,depth integer,display_depth integer,created_at timestamptz)` | `STABLE SECURITY DEFINER`, `search_path=''`; helper nội bộ, revoke execute |
| `private.comment_tree_node_json` | `(uuid,uuid,uuid,uuid,integer,integer,boolean) returns jsonb` | `STABLE SECURITY DEFINER`, `search_path=''`; helper nội bộ, revoke execute |
| `private.require_published_comment_parent` | `() returns trigger` | `VOLATILE SECURITY DEFINER`, `search_path=''`; chỉ được gọi bởi trigger, execute bị revoke khỏi các application roles |

Migration giữ nguyên signature/quyền của `create_comment`, `create_comment_with_mentions`, `list_comments`, reaction/edit/delete RPC cũ. Không đổi RLS, table grants hoặc `private` schema `USAGE`; migration authorization hiện hành cấp `USAGE` cho authenticated. Các function mới đều schema-qualify object và dùng `search_path=''`. `b_active()` là `STABLE` và chỉ đọc identity/account status, không lấy row lock; do đó lời gọi từ list RPC `STABLE` không thêm lock vào transaction READ ONLY.

Các signature cũ được giữ nguyên gồm `public.create_comment(uuid,uuid,text,uuid)`, `public.create_comment_with_mentions(uuid,uuid,text,uuid,jsonb)`, `public.list_comments(uuid,uuid,timestamptz,uuid,integer)`, `public.edit_comment(uuid,text,bigint)`, `public.delete_comment(uuid,bigint)` và `public.set_comment_reaction(uuid,text)`. Public wrappers là `SECURITY INVOKER` và migration trước chỉ cấp execute cho `authenticated`; private write implementations là `SECURITY DEFINER`. `list_comments` và quyền bảng hiện hành không bị sửa theo giới hạn yêu cầu; projection mới `list_comment_tree` redacts physical parent nếu không readable. Android hiện gọi tree RPC, không đọc trực tiếp bảng comments.

Comments vẫn bật RLS với policy SELECT gọi `private.can_read_comment`; quyền bảng rộng bị revoke, còn danh sách column-level SELECT đã cấp từ migration social vẫn giữ nguyên và chịu RLS. Projection tiếp tục filter profile/mentions như cũ, và thêm kiểm tra ACL cho actual parent/root trước khi trả JSON.

## 7. Rủi ro/giả thuyết chưa kiểm chứng

- Chưa chạy PostgreSQL parser hoặc migration trên DB cục bộ/Cloud; cú pháp, quyền thực tế của role sở hữu trigger và DDL ordering cần owner xác nhận trên môi trường kiểm thử trước deploy.
- Chưa dùng `EXPLAIN (ANALYZE, BUFFERS)` hoặc dữ liệu production để đo recursive traversal. Work của response được giới hạn 30 item và một preview level, nhưng mỗi node vẫn thực hiện projection/count work có thể tăng theo kích thước trang.
- `FOR SHARE` cùng lock order Post-then-comment được đối chiếu tĩnh với `create_comment`/`change_comment`; race test khi xóa parent đồng thời chưa chạy.
- Chưa chạy UI để xác nhận mọi chuỗi mở/thu gọn, nhiều trang, cập nhật preview sau retry và reparent local đồng bộ với observer.
- Cloud có thể lệch schema/grants so với migrations; các precondition trong file không thay thế việc inventory catalog thực tế.
- Existing column-level SELECT vẫn gồm `comments.parent_id`, còn `list_comments` cũ serialize cột đó cho các tombstone mà `can_read_comment` cho phép. Đây là hành vi có sẵn và được giữ nguyên theo yêu cầu không sửa RPC/table grants cũ; Android hiện dùng `list_comment_tree`, nơi physical parent bị redacted. Nếu yêu cầu redaction áp dụng cả cho client legacy/direct-table, migration này chưa thay đổi bề mặt đó và cần quyết định phạm vi riêng trước rollout rộng.
- Các RPC/table access legacy không đổi theo giới hạn yêu cầu; trước rollout với client cũ cần giữ nguyên inventory ACL hiện tại và xác nhận client chuyển sang tree RPC như dự kiến.

## 8. Files changed

- `supabase/migrations/20261107001000_comment_tree_paging.sql`
- `app/src/main/java/com/example/meetomxh/models/Comment.java`
- `app/src/main/java/com/example/meetomxh/fragments/CommentsBottomSheetFragment.java`
- `TASK53B_COMMENT_TREE_SQL_REVIEW.md`

Không sửa migration Task 52, migration comments cũ, `CommentClient`, `Dto`, `CommentAdapter` hoặc các RPC comments cũ.

## 9. Cloud prerequisites và kết luận

Trước khi owner triển khai, Cloud phải có migrations tạo `public.posts`, `public.comments`, `public.comment_reactions`, `public.content_mentions`, `public.profiles`, index `public.comments_thread_idx(post_id,parent_id,created_at,id)`, `private.b_active()`, `private.create_comment(uuid,uuid,text,uuid)`, `private.can_read_post(uuid)`, `private.can_read_comment(uuid)`, `private.can_read_profile(uuid)`, các cột được kiểm tra đầu file, và grants/schema usage từ authorization/social migrations. Cần có bản `create_comment` mới nhất từ messenger UX cùng mention wrapper/projection của migration tag mentions. Giữ bucket/storage và ACL hiện hành; migration này không thay đổi chúng.

**Kết luận source:** Các lỗi đã xác nhận được sửa trong migration `01000`; chữ ký public RPC giữ tương thích với Android hiện tại, và các điểm bắt buộc về parent tombstone, UI root/depth, redaction cùng giới hạn preview đã được xử lý ở source. Có thể chuyển cho owner review Cloud prerequisites trước khi triển khai, nhưng chưa có runtime/Cloud verification và không tuyên bố runtime PASS.
