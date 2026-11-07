# Story Viewer & avatar click — bàn giao source

## 1. Nguyên nhân Viewer lỗi

Đã xác định lỗi transaction trong source Task 42: `get_story`, `get_story_author_stories`, `get_story_states`, `get_story_viewers_page` là `STABLE`, nhưng đều gọi `private.c_actor()` có `SELECT ... FOR SHARE / FOR KEY SHARE`. PostgREST chạy RPC STABLE trong transaction READ ONLY ngay cả khi gọi POST; row locks bị từ chối với SQLSTATE `25006`, trước khi có kết quả Story hay media. Nhánh lỗi chung trước đây biến lỗi này thành “Yêu cầu không hợp lệ”. Đây là xung đột xác định từ SQL và [contract transaction của PostgREST](https://docs.postgrest.org/en/stable/references/transactions.html#access-mode-on-functions).

Chưa gọi Cloud hoặc có log lần lỗi của owner, nên chưa xác nhận ledger/code đang deploy hay loại trừ lỗi khác ở môi trường thật. `StoryClient` nay nhận diện/log an toàn lỗi `25006`, thiếu RPC, UUID sai và quyền/hết hạn; không che bằng ảnh giả hoặc bỏ guard.

## 2. Nguyên nhân avatar không nhận click

`StoryAdapter` chỉ đặt navigation trên root; avatar được `StoryRings.apply()` đặt listener riêng. Khi cache chưa có Story hoặc `get_story_states` lỗi, listener thành null nhưng View vẫn có thể giữ clickable và chặn click item cha. Khi cache thành công, avatar còn có thể mở story ID khác ID root đang giữ. Item “Tin của bạn” trước đây chưa có owner ID, root tạo tin thay vì xem, còn dấu + chưa có callback riêng.

Sửa bằng `StoryNavigation` và cùng một callback cho root/tên/ring/avatar. Binding tray giữ callback đó qua mọi lần ring refresh; binding không có hành động đặt clickable=false. Dấu + tạo Story riêng. Header gán owner hiện tại và Story ID còn hiệu lực nếu có, không truyền sentinel `create_story` vào RPC; không thêm item riêng trùng Story của chính mình. Avatar khi chưa có tin mở danh sách chính mình và báo chưa có tin; + vẫn tạo tin. Viền tím lấy từ metadata còn hiệu lực/cache và batch query hiện có.

## 3. File đã sửa/thêm

- `app/src/main/java/com/example/meetomxh/StoryViewActivity.java`
- `app/src/main/java/com/example/meetomxh/supabase/StoryClient.java`
- `app/src/main/java/com/example/meetomxh/adapters/StoryAdapter.java`
- `app/src/main/java/com/example/meetomxh/ui/StoryRings.java`
- `app/src/main/java/com/example/meetomxh/ui/StoryNavigation.java` (mới)
- `app/src/main/java/com/example/meetomxh/adapters/FeedAdapter.java`: chỉ phần dữ liệu Story Tray, không sửa luồng post của Feed.
- `app/src/main/res/layout/item_story.xml`: dấu + có click/focus riêng.
- `supabase/migrations/20261107000200_story_viewer_rpc_transactions.sql` (mới)
- Báo cáo này. Không đổi Reels, Chat, shared media loader, capability guard, Gradle hoặc applicationId.

## 4. Luồng tải sau sửa

Navigation kiểm UUID, gửi owner/story đúng loại; Viewer đối chiếu Story với owner, giữ ID khi restore. RPC/DTO kiểm ID/media ID, không đưa chuỗi rỗng/sentinel vào UUID RPC. Signatures/tham số đã đối chiếu SQL: `p_story_id`, `p_author`, `p_authors`, cursor viewer và `p_limit` giữ nguyên.

Sau `get_story`, media đi qua `SocialContract` và registry SELECT/RLS hiện có. Source `can_read_media` chỉ cấp asset READY có parent Story được phép xem. Registry/MIME sai hoặc thiếu quyền dừng với lỗi, không chuyển sang public bucket hay fallback bỏ RLS.

Source hiện dùng `/storage/v1/object/authenticated/...`, **không dùng signed URL** cho Viewer. Ảnh: GlideUrl + JWT/apikey hiện tại, không lưu ảnh Story vào memory/disk cache. Video: Media3 dùng cùng authenticated URL/header và monitor hiện có. Không log token, header, URL hay caption. Ảnh chỉ bắt đầu progress sau khi Glide trả resource; video sau first frame. Segment/tap/swipe, Like, Reply, record view và owner viewers giữ các RPC/điều khiển hiện có; migration cũng sửa transaction của viewer list.

Lỗi hiển thị thông điệp thật và hành động retry/skip/close. Callback có generation/lifecycle guards; lỗi Glide trả handled và cập nhật error UI ở lượt main tiếp theo, tránh clear/load lồng trong callback hoặc ảnh cũ ghi đè Story mới. Khi hết hạn/thu hồi quyền/lỗi sẽ dừng media; không để audio/video cũ tiếp tục. Giữ RequestManager của Activity để clear khi teardown.

## 5. Cloud/RPC cần cập nhật

Owner cần áp dụng **`20261107000200_story_viewer_rpc_transactions.sql` sau Task 42 `20261106000800`**. Migration chỉ ALTER bốn hàm đọc nêu trên thành `VOLATILE`, cho phép guard row-lock hiện có trong POST transaction, rồi notify schema cache. Không thay signature/body/grants, không xóa/sửa content và không bỏ RLS/capability guard. Android vẫn gọi POST qua RpcClient.

Chưa chạy migration hay bất kỳ request nào lên Cloud. Nếu còn lỗi sau deploy, owner có thể lọc `StoryViewer`, `MeetoPublish`, `AndroidRuntime` trong Logcat để đối chiếu stage `rpc.get_story`/`rpc.get_story_author_stories`, status/code và reason `readonly_actor_lock`; không cần gửi token/URL. Đối chiếu API/Postgres logs cùng thời điểm để xác nhận code Cloud thực tế.

## 6. Chưa xác minh

Không build, test, emulator hoặc runtime PASS. Chỉ rà soát source/diff/XML và contract SQL/PostgREST. Owner tự kiểm ảnh/video với bucket private, token hết hạn, media chưa READY, Story xóa/hết hạn, Like/Reply/viewers, chuyển nhanh người đăng, background/restore, avatar/tên/khoảng trống/+ của own item. Discovery toàn cục vẫn giới hạn 100 metadata như trước; từng owner dùng RPC riêng, không tăng tải media hàng loạt. Chưa có bằng chứng thiết bị/Cloud để cam kết toàn bộ trường hợp runtime đã hết lỗi.
