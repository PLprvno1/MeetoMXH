# MUSIC TASK 02 — Catalog, Storage và final review 02.1/02.2/02.3

## 1. Mục tiêu

Chuẩn bị backend cho 50 bài nhạc thư viện Meeto: catalog ổn định, bucket private, RPC đọc, manifest, upload có đối soát và metadata v2 hỗ trợ loop. Task 02.1 chỉ hoàn thiện source trước triển khai Cloud; chưa tích hợp Android.

## 2. File đã tạo và chỉnh sửa

Các artifact Task 02 đã có:

- `supabase/migrations/20261107001200_music_catalog_storage.sql`.
- `music_library_manifest.json`.
- `scripts/upload_meeto_music_library.ps1`.
- `TASK02_MUSIC_LIBRARY_PREFLIGHT_READONLY.sql` và `TASK02_MUSIC_LIBRARY_POSTDEPLOY_READONLY.sql`.
- `TASK02_MUSIC_CATALOG_STORAGE_REPORT.md` (quy trình triển khai và SQL activation do owner chạy).

Task 02.1 sửa điều kiện thời lượng v2 trong migration, cập nhật báo cáo cũ, bổ sung preflight kiểm tra schema USAGE, định nghĩa ba hàm cũ và constraints hiện hữu; tạo báo cáo riêng này. Task 02.3 bổ sung `-VerifyOnly` GET-only vào helper và cập nhật hướng dẫn này. Không sửa manifest, Android, Admin, Media Cleanup hoặc migration lịch sử.

Task 02.2 sửa riêng ownership guard trong migration `01200`, phần quyền policy trong preflight read-only và hai báo cáo triển khai (`TASK02_MUSIC_CATALOG_STORAGE_REPORT.md`, file này). Không đổi policy, grants, metadata, manifest hoặc helper upload.

## 3. Catalog và Storage architecture

`public.music_catalog_tracks` chứa 50 bản ghi từ catalog gốc: khóa `(track_id, catalog_version)`, thứ tự hiển thị ổn định, title/artist, duration, path, MIME/codec/container, size, SHA-256, license/source/attribution và `is_active`. Seed ban đầu inactive; API roles không được đọc/ghi bảng trực tiếp, bảng bật RLS.

Bucket `music-library` private, MIME `audio/ogg`, giới hạn mỗi object 5 MiB; path `v1/<track_id>.ogg`. Không dùng `media_assets` hoặc Media Cleanup. RPC `get_music_catalog(text,text,integer,integer)` trả tối đa 50 track active mỗi trang, cursor theo catalog version và sort order; `get_music_library_capabilities()` báo khả năng thư viện. Cả hai chỉ cấp EXECUTE cho authenticated và yêu cầu tài khoản active.

Public RPC dùng SECURITY DEFINER với search_path rỗng; implementation không cấp EXECUTE trực tiếp cho API roles. Helper Storage chỉ được cấp EXECUTE cho authenticated để RLS đánh giá; helper yêu cầu tài khoản và track active. Policy đọc cho authenticated kết hợp restrictive guard theo bucket/path/operation; anon bị chặn và restrictive policies chặn INSERT/UPDATE/DELETE của client trên bucket nhạc, kể cả khi tồn tại permissive policy rộng. Không thay policy các bucket cũ.

## 4. Metadata v2 và loop

Giữ đúng 16 trường: `contract_version`, `source`, `track_id`, `catalog_version`, `title`, `artist`, `duration_ms`, `start_offset_ms`, `used_duration_ms`, `music_volume`, `source_volume`, `license_id`, `license_url`, `source_url`, `attribution`, `sha256`.

Với `contract_version=2`, `source=meeto_library`, loop là quy tắc mặc định của contract; không thêm `loop_enabled` nên không phát sinh trường thứ 17 hoặc hai chế độ validation. Điều kiện mới:

- `0 <= start_offset_ms < duration_ms`, duration nguồn vẫn phải khớp catalog.
- `1000 <= used_duration_ms <= 60000`; được vượt phần thời lượng còn lại hoặc toàn bộ chiều dài track.
- Khi hết track, phát tiếp từ vị trí 0 tới đủ used duration. Tại thời điểm đầu ra `t`, vị trí nguồn là `(start_offset_ms + t) % duration_ms`.
- Catalog identity, title/artist, duration, SHA-256, license/source/attribution và volume 0–100 vẫn được kiểm tra như trước. Publish binder vẫn yêu cầu track active.

Ví dụ track 30 giây, start 29 giây, used 60 giây hợp lệ theo v2; start bằng 30 giây bị từ chối. Đây là quy tắc backend, chưa phải bằng chứng Android playback/export đã loop.

Đã đối chiếu với migration `00900`: `valid_music_metadata(jsonb)` giữ nguyên nhánh Openverse 13 trường, kể cả `start <= duration - used`; chỉ dispatch v2 bằng sự hiện diện của `contract_version`. Volatility đổi từ IMMUTABLE sang STABLE vì v2 đọc catalog. Hai binder giữ nguyên signature, SECURITY DEFINER/search_path, actor, kiểm tra owner/state, row lock, hash và idempotency; phần thêm chỉ kiểm tra v2 active. Constraints/hash và content projections cũ không bị thay. `get_music_publish_capabilities()` vẫn contract version 1; Android sau này phải giữ gate đó và kiểm tra thêm library capability.

## 5. Upload 50 bài

Owner chạy preflight read-only, đối chiếu catalog thực tế cùng ledger và định nghĩa Cloud trước khi chạy riêng migration `01200`. SQL Editor có thể không ghi ledger; không suy ra trạng thái triển khai chỉ từ ledger và không batch `supabase db push`.

Sau Task 02.2, ownership của `storage.objects` chỉ là thông tin tham khảo. Preflight không còn dùng ownership/role membership để suy ra quyền CREATE POLICY. Khi owner chạy migration, PostgreSQL kiểm tra quyền thực tế tại các câu CREATE POLICY trong transaction BEGIN/COMMIT. Nếu thiếu quyền, transaction bị abort, các thay đổi không được commit; owner cần ROLLBACK nếu phiên SQL Editor còn ở trạng thái aborted. Không đổi owner bảng hoặc GRANT role quản trị Supabase để vượt guard.

Helper mặc định chỉ kiểm tra local. Sau khi bucket đã tạo, owner chủ động chạy với `-ConfirmUpload`, credentials được inject qua process environment, không đưa key vào Android/source/log. Helper đối chiếu catalog/manifest và hash toàn bộ source trước network, GET object hiện hữu để kiểm tra bytes/MIME, chỉ POST object thiếu với `x-upsert:false`, GET xác minh lại và kiểm kê đủ 50 object dưới `v1`. Object khác hash/size/MIME bị giữ nguyên; chạy lại để tiếp tục phần chưa hoàn thành.

Sau khi upload, từ thư mục gốc repository owner chạy `.\scripts\upload_meeto_music_library.ps1 -VerifyOnly` (với `SUPABASE_URL` và `SUPABASE_SERVICE_ROLE_KEY` đã được inject an toàn vào process). Chế độ này không cần source OGG local: nó đọc manifest, GET từng path chuẩn, băm response stream và báo từng track có lỗi hash/size/MIME/HTTP. Cờ này loại trừ `-ConfirmUpload`; request builder từ chối mọi method khác GET trong nhánh này và key không được in ra.

`-VerifyOnly` không liệt kê bucket. Supabase Storage list endpoint dùng HTTP POST dù thao tác chỉ đọc, nên gọi nó sẽ vi phạm giới hạn GET-only. Xác nhận object thừa bằng truy vấn SQL read-only `unexpected_objects` trong postdeploy/preflight inventory; kết quả Cloud hiện owner cung cấp là 0.

Chỉ activate catalog sau khi helper báo thành công và postdeploy SQL xác nhận đủ 50 mapping, MIME/size đúng, không có object thừa. SQL activation có guard đã nằm trong báo cáo Task 02 gốc; Storage metadata không thay thế được kiểm tra SHA-256 qua GET. Không ghi SQL vào `storage.objects`.

## 6. Kiểm tra thực tế trong Task 02.1

- Chạy helper không có `-ConfirmUpload`: 50 file local qua đối soát catalog, manifest, size và SHA-256; không gửi request Supabase.
- Đối chiếu 50 SQL seed rows khớp dữ liệu catalog gốc; manifest có 50 track, tổng 43,627,767 byte (khoảng 41.61 MiB).
- PowerShell AST parse thành công. So sánh tự động thân ba hàm với `00900`, sau khi bỏ đúng phần mở rộng v2 để so sánh: đều giữ nguyên logic legacy (chuẩn hóa khoảng trắng).
- Rà tĩnh SQL/PLpgSQL, delimiters, IF/END, signatures, cursor, RLS, grants, owner guards và dependencies theo migration source. Không phát hiện thêm lỗi cú pháp xác định bằng source. Không có `psql`/`postgres` trên PATH; không compile hoặc thực thi SQL.

Task 02.2: đối chiếu source trước/sau để xác nhận chỉ Storage ownership predicate và thông báo/comment liên quan thay đổi trong migration; phần sau prerequisite block (bao gồm toàn bộ policies, grants, metadata và seed), BEGIN/COMMIT cùng các guard khác giữ nguyên. Preflight bỏ kết luận boolean từ ownership, vẫn là SQL read-only. Không thực thi SQL hoặc thử CREATE POLICY.

Task 02.3: PowerShell AST parse thành công. Rà source nhánh `-VerifyOnly`: chỉ gọi `Get-RemoteObject` qua request GET, kiểm tra 50 manifest entry, hash SHA-256 stream, byte count và MIME; khi một GET lỗi, kết quả nêu track ID và mã HTTP hoặc loại exception, không in credential. Static review xác nhận nhánh này không gọi upload/list helpers; request guard chặn method không phải GET. Không chạy helper với Cloud và không gửi HTTP request trong review này.

## 7. Lỗi, rủi ro và blocker còn lại

Đã sửa lỗi v2 giới hạn used duration bởi phần còn lại của track. Không phát hiện blocker source mới ngăn owner chạy preflight. Điều này không bảo đảm migration sẽ chạy thành công trên Cloud.

Task 02.2 sửa false blocker `14_storage_policy_ownership`: owner cung cấp kết quả Cloud preflight 14/15; điều kiện duy nhất fail là ownership. Trên managed Storage, quyền policy có thể được Supabase cấp qua `supautils.policy_grants` dù `postgres` không sở hữu hoặc thuộc role owner. Guard cũ suy ra thiếu quyền từ ownership là không đầy đủ nên đã bỏ riêng phép kiểm tra đó. Chưa có bằng chứng thực thi CREATE POLICY thành công với source sau sửa; đây vẫn là kiểm tra quyền tại thời điểm owner chạy migration.

Cloud còn cần xác minh prerequisites `00900`, schema USAGE (đặc biệt authenticated trên private cho helper RLS), function owner/ACL, constraints/hash, quyền tạo policy Storage, operation helper và toàn bộ policy thực tế. Bucket/table/function/policy Task 02 đã tồn tại hoặc owner/ledger drift sẽ khiến migration abort có chủ đích.

Upload network branch và Storage API responses chưa chạy; lỗi transport có thể dừng lượt upload, owner cần chạy lại để đối soát và tiếp tục. `-VerifyOnly` kiểm tra nội dung của 50 object dự kiến nhưng không tự phát hiện object thừa vì list API dùng POST; cần dùng SQL inventory read-only để chốt số object. Kết quả `unexpected_objects=0` hiện tại là do owner cung cấp, chưa được script kiểm tra lại. Catalog version/provenance và bytes đã dùng phải được giữ bất biến, giữ bản ghi cũ và deactivate thay vì xóa hoặc sửa metadata nguồn: validator dùng catalog lookup trong CHECK, thay catalog không tự revalidate nội dung cũ. Active check của binder vẫn có thể từ chối retry v2 sau khi track bị deactivate. Không thay đổi hành vi này trong hotfix loop.

Android hiện chưa hiểu metadata v2/loop; chưa có bằng chứng preview/export loop đúng. Backend Story music cũ vẫn yêu cầu video MP4; Story ảnh cần bước composition/export sau này. Metadata hợp lệ không chứng minh MP4 đã có âm thanh thật.

## 8. Trạng thái

**SOURCE REVIEWED. CLOUD OBJECT COUNTS OWNER-REPORTED:** catalog 50, Storage 50, matched paths/MIME/size 50, unexpected objects 0, inactive tracks 50. `-VerifyOnly` chưa chạy; SQL inventory chưa được agent chạy. **SQL RUNTIME NOT VERIFIED. STORAGE API RUNTIME NOT VERIFIED. ANDROID RUNTIME NOT VERIFIED.** Task 02.3 source review only; no migration/upload/activation, db push, Git push or APK build.

## 9. Điều kiện bắt đầu Task 03

Contract 16 trường với implicit loop đã chốt để triển khai Android Library/Cache bằng Java/XML. Trước kiểm thử tích hợp Cloud của Task 03, owner cần: preflight đạt; migration `01200` chạy thành công; đủ 50 object được xác minh bằng GET/hash và postdeploy SQL; catalog đã activate; tài khoản authenticated active đọc được capability/catalog có phân trang và tải object, còn anon/inactive và thao tác ghi client bị từ chối. Cần kiểm tra capability Openverse v1 và nội dung cũ vẫn đọc được. Task 03 không được coi loop playback/export là đã hoàn thành.
