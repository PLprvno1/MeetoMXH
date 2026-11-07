# Meeto — Reels upload, Back và quy tắc phân phối

Chỉ sửa source/rà soát tĩnh trong `F:\MeetoMXH`. Không build/test/emulator, upload thử, xuất APK/AAB hoặc gọi/deploy Cloud. Báo cáo này cập nhật quy tắc phân phối của Task 43: **Create Reel trực tiếp là Reel-only**.

## 1. Điều xác định được về upload thất bại

Thông báo cũ “Đã đạt giới hạn. Vui lòng thử lại sau.” đến từ nhánh **mọi HTTP 429** của `ErrorMapper`. Nó không chứng minh video vượt 50 MiB, cũng không chứng minh quota gói Supabase Free đã hết.

Một nguyên nhân cụ thể có trong source SQL là `PT429 / media_quota` khi cấp upload intent: pending >=10, tổng asset chưa deleted >=100, hoặc ngân sách media đã giữ chỗ + reservation mới vượt 500 MiB/caller. Pending/pending_delete/quarantined vẫn giữ worst-case reservation; một video mới có thể reserve 50 MiB dù file chỉ 2,8 MiB. Ảnh bìa purpose post cũng cấp intent riêng. Đây là quota ứng dụng, khác quota dự án Storage và khác rate limit.

**Chưa có HTTP body/status theo stage, Logcat hoặc Cloud logs của lần lỗi được báo**, nên chưa thể khẳng định trường hợp thực tế là `media_quota`, rate limit hay lỗi khác. Không tăng quota Android/SQL hoặc tự xóa pending để giả sửa lỗi này. Tài liệu Phase B cũ mô tả worker FFmpeg, nhưng source hiện tại đã dùng direct upload + Storage metadata finalizer; không quay về lifecycle cũ theo tài liệu lịch sử.

## 2. Error handling và upload/retry đã sửa

- REST/RPC, standard upload và TUS giữ HTTP status, machine code và server message trong `HttpError`; standard upload trước đây bỏ mất message. Log tag `MeetoPublish` chỉ ghi stage, status, mã/thông điệp kỹ thuật theo allowlist, exception type, MIME/size/duration. Không ghi body tự do, JWT, API key, signed URL, path hoặc caption.
- Tách thông báo tiếng Việt cho app `media_quota`, quota dự án/402/507, file/bucket quá lớn, rate limit/429, Auth/RLS, session hết hạn, object/request conflict, timeout, verifier pending/reject, và RPC/migration thiếu.
- UI Reel có stage kiểm tra file, cấp phiên, tải video, xử lý/xác nhận media, tải/xác nhận cover và đăng Reel. Có Thử lại; busy guard vẫn chặn nhấn gửi trùng.
- Chỉ upload `editedFile`. Giữ video asset khi cover lỗi; giữ cả hai asset khi create RPC lỗi. Retry bắt đầu từ stage chưa hoàn tất. Post reservation READY/object đã có được tái sử dụng qua migration Task 43 và finalizer; kiểm tra size trước khi chấp nhận object trùng. Không bỏ qua mọi HTTP 409 như trước.
- TUS chỉ một attempt mỗi thao tác người dùng, không ẩn retry quota/auth/rate limit; giữ resume state. Đã đọc bytecode JAR `tus-java-client:0.5.1` trong cache để đối chiếu `setDelays`/`makeAttempts`/`getCausingConnection`, không thực thi upload hay chạy test.
- Finalize vẫn retry hữu hạn cho lỗi mạng/5xx tạm thời, không retry quota/429. Voice verifier polling hữu hạn hiện có được giữ.
- Create Reel lưu receipt local trước upload/create và sau khi nhận video/cover. Khi khôi phục cùng draft qua Bundle, ưu tiên receipt mới hơn để tránh mất asset giữa save-state và callback. Giữ operation/fingerprint/request ID `create_reel` hiện có; chỉ complete sau response có ID + `is_reel` hợp lệ. Caption/privacy đã cố định cũng được khôi phục. Không xóa draft khi lỗi, không xóa Storage từ Android.

## 3. Back crash: điểm không an toàn và bản sửa

Trong source có đường recycle gọi `Glide.with(h.cover)` khi Activity có thể đã destroy; việc khởi tạo RequestManager theo View lúc đó có thể ném `IllegalArgumentException`. Teardown cũ cũng không tháo owner player listener trước khi clear/release và có callback còn dựa vào monitor/view đang bị dọn. **Chưa có stack trace để gắn chắc chắn crash người dùng gặp với một đường cụ thể.**

Bản sửa: giữ Fragment-scoped Glide RequestManager để clear holder, không tạo manager mới khi recycle; MainActivity gom toolbar/System Back của Reels về Feed và gọi `leaveReels()` trước navigation. Bỏ synchronous `popBackStackImmediate`, kiểm tra FragmentManager state trước transaction. Leave vô hiệu playback position/listener, cancel media/access/target jobs và timers, detach monitor, clear/release player theo thứ tự. Monitor release idempotent và bỏ qua event sau release. Destroy view tháo RecyclerView adapter/listener/snap helper; callback target/holder/ticker có view/lifecycle guards. Không thêm catch exception để nuốt crash. Reels mở lại tạo player mới và dùng vị trí đã lưu.

## 4. Phân phối mới

| Luồng | is_reel | publish_to_feed | Kết quả |
|---|---:|---:|---|
| Post text/ảnh/video, switch OFF | false | true | Feed-only |
| Post một video, switch ON | true | true | Feed + Reels, chung post/media/tương tác |
| Create Reel trực tiếp | true | false | Reels-only, vẫn có trong Profile/Reels |

Không thêm màn hình, switch Feed trong Create Reel hay upload video thứ hai. Video dài vẫn qua editor Task 38B. `publish_to_feed` chỉ lọc phân phối; `can_read_post`/`can_read_media`, privacy/block/owner không đổi. Xóa/quyền riêng tư trên cùng nội dung vẫn nhất quán. Menu Reel dùng lời giải thích có điều kiện “nếu đã đăng đồng thời lên Feed”.

Migration mới **`supabase/migrations/20261106001000_reel_publish_distribution_hotfix.sql`**, forward-only, chưa chạy. Thêm column/check, sửa wrapper create_reel, unified create và private.get_feed (bao gồm get_user_posts). List Reels/get_user_reels vẫn theo is_reel. Không xóa row, media, Like/Comment/Save hoặc sửa audience.

Backfill phân biệt dữ liệu có sẵn bằng contract source: Task 43 unified create có `mention_request_hash`, direct create_reel không có. Các Reel không có marker chuyển sang Reel-only; unified có marker giữ Feed. Nếu Cloud từng nhận dữ liệu từ client/SQL ngoài contract này, owner cần đối chiếu provenance trước khi áp backfill; source hiện không có dấu nguồn nào chắc chắn hơn cho dữ liệu lịch sử.

Policy RPC trả cờ `reel_only`/`unified_video_publishing` và effective bucket size. Client mới chặn đăng video Reels nếu backend chưa hỗ trợ quy tắc mới, không âm thầm đăng lên sai nơi. Create Post switch OFF vẫn dùng nghiệp vụ Post hiện có.

## 5. File trong hotfix

- Java: `CreateReelActivity.java`, `CreatePostActivity.java`, `MainActivity.java`.
- Java: `fragments/ReelsFragment.java`, `ui/ReelPlaybackMonitor.java`.
- Java: `storage/SupabaseStorage.java`, `supabase/SupabaseHttpClient.java`, `supabase/ErrorMapper.java`.
- SQL: `supabase/migrations/20261106001000_reel_publish_distribution_hotfix.sql`.
- Báo cáo: `MEETO_REELS_HOTFIX_HANDOFF.md`. Không đổi XML/Gradle/applicationId hoặc thuật toán camera/trim/export.

## 6. Owner cần làm trên Supabase và thu thập bằng chứng

1. Đối chiếu migration ledger thực tế; triển khai các dependency hiện có và Task 43 `20261106000900`, rồi hotfix `20261106001000`. Không chỉnh lại migration đã apply. Android vẫn qua Task 36 capability guard.
2. Nếu log là `stage=rpc.prepare_direct_upload http=429 code=PT429 reason=media_quota`: kiểm tra `media_assets` của tài khoản qua Table Editor, số pending/chưa deleted, reservation và cleanup/outbox/worker. Cho lifecycle cleanup hiện có hoàn tất; không xóa registry/object thủ công, không tự tăng cap. File nhỏ không tránh được reservation cap.
3. Nếu log chỉ quota Storage/402/507: kiểm tra Dashboard Usage/Storage/Billing, dung lượng dự án và trạng thái dịch vụ. Nếu file/bucket size: đối chiếu private `social-media`, MIME MP4 và bucket file-size limit. Nếu 429 từ Storage/rate limiter: chờ theo policy, không retry dồn.
4. Auth/RLS: đối chiếu active session, owner/path/bucket policies; không mở Public hoặc dùng service_role trong APK. Missing RPC: kiểm tra ledger/PostgREST schema cache; không “sửa” bằng fallback bỏ kiểm tra quyền.

Owner có thể lọc Logcat khi tự tái hiện bằng:

```powershell
adb logcat -v threadtime -s MeetoPublish ReelPlayback AndroidRuntime
adb logcat -b crash -d -v threadtime
```

Cần đoạn lỗi đầu tiên theo stage và stack `FATAL EXCEPTION`/`Caused by` của Back, kèm thao tác System/toolbar Back, phiên bản Android/device và đang tải hay phát. Đối chiếu cùng thời điểm ở Dashboard API/Storage/Postgres logs để phân biệt quota ứng dụng với quota dự án. Không cần cung cấp token/key/URL có chữ ký.

## 7. Chưa xác minh runtime

Không có runtime PASS. Chưa xác nhận quota/tồn kho/ledger Cloud hiện tại, nguyên nhân chính xác của lần 429, stack crash thực tế, Java compilation/SQL execution, lifecycle trên thiết bị, export/HDR/codec hoặc phân phối sau deploy. Owner tự kiểm tra upload nhỏ/TUS, video-success/cover-fail, create-fail/retry, Activity recreation, Back khi loading/playing/scrolling/sheet và mở lại, cùng ba chế độ phân phối/privacy. Draft không có Bundle tương ứng sau force-stop vẫn chưa có giao diện recovery riêng; lỗi persistence được báo và dừng trước create RPC.
