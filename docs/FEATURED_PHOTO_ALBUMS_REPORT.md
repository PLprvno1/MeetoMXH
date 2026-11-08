# Album ảnh nổi bật trên trang cá nhân

SOURCE IMPLEMENTED / BUILD NOT VERIFIED / ANDROID RUNTIME NOT VERIFIED / CLOUD RUNTIME NOT VERIFIED.

## Giao diện và sử dụng

- Đổi “Tin nổi bật” thành “Ảnh nổi bật”. Album hiển thị theo lưới 3 cột, ảnh bìa bo góc, tên bên dưới. Album có 3 ảnh hiển thị `+2`; một ảnh không hiện số cộng.
- Nút cộng mở trình tạo album: tên album và lưới chọn ảnh nhiều lựa chọn, có dấu tích, số ảnh đã chọn, nút tải thêm với cursor. Dùng ảnh đã đăng trong bài viết cá nhân theo danh sách ứng viên hiện có. Không tải ảnh mới lên Storage trong luồng này.
- Chạm album mở trình xem toàn màn hình kiểu Story, bắt đầu từ album được chọn. Mỗi ảnh tự chuyển sau 5 giây đã tải thành công, có thanh tiến trình; chạm trái/phải để lùi/tiến, giữ tay để tạm dừng, có nút tạm dừng/tiếp tục và đóng. Hết album sang album kế tiếp theo thứ tự trên hồ sơ; lùi ở ảnh đầu sang ảnh cuối album trước, ở đầu danh sách giữ ảnh đầu; hết album cuối đóng trình xem. Bỏ qua album rỗng.
- Chủ tài khoản nhấn giữ album để đổi tên, thêm/bỏ ảnh hoặc gỡ album trong cửa sổ chỉnh sửa. Có hướng dẫn nhấn giữ trên trang cá nhân. Ảnh được chọn đầu tiên làm bìa. Gỡ album có xác nhận, không xóa ảnh/bài viết nguồn.
- Tối đa 12 album nổi bật, mỗi album 1–50 ảnh. Không giới hạn chỉ ở 50 ảnh ứng viên đầu tiên: chủ tài khoản có thể tải trang tiếp theo. Tên album tối đa 100 ký tự.

## File và dữ liệu

- `activity_profile.xml`: nhãn mới và vùng chứa lưới album.
- `ProfileActivity.java`: chuyển phần ảnh nổi bật sang `ui/FeaturedAlbumsUi.java`, giải phóng request/dialog khi destroy.
- `ui/FeaturedAlbumsUi.java`: hiển thị, xem, chọn nhiều ảnh có phân trang, chỉnh tên và lưu album. Request cũ bị hủy khi tải lại; callback có kiểm tra lifecycle và session generation. UUID tạo album ổn định trong cùng cửa sổ giúp retry không tạo album thứ hai.
- `FeaturedAlbumViewerActivity.java` và khai báo không exported trong Manifest: trình chiếu ảnh dùng lại `StoryProgressView`. Intent chỉ mang owner/album ID; viewer tải lại album qua RPC có ACL, không truyền toàn bộ ảnh qua Intent. Kiểm tra lại quyền khi resume và mỗi 15 giây; lỗi truy vấn xóa danh sách đang giữ, dừng ảnh và cho thử lại. Lỗi tải ảnh dừng timer, có thử lại hoặc chuyển ảnh khác. Callback ảnh có generation fence; pause/destroy hủy timer/request. Lưu album/ảnh/thời gian/vị trí tạm dừng khi Activity tạo lại. Đây là trình xem ảnh nổi bật, không tạo lượt xem/like/reply hoặc thời hạn Story.
- `supabase/ErrorMapper.java`: thông báo giới hạn album, ảnh không khả dụng, xung đột phiên bản.
- Migration `20261108000500_featured_photo_albums.sql`: cờ `is_featured` trên hệ thống album hiện có; RPC đọc album và lưu tên/lựa chọn trong một transaction. Tái sử dụng validation của `private.add_profile_album_photos`, giữ quyền sở hữu, ACL bài viết/media, trạng thái ready, ảnh thuộc bài gốc không thuộc nhóm. Kiểm tra version khi sửa; lỗi một ảnh rollback toàn bộ. Không thay đổi bucket/catalog/nhạc/Cleanup.
- Migration chuyển mỗi mục ảnh nổi bật cũ có asset thành một album một ảnh, có tên mặc định và có thể đổi tên. Giữ bảng tham chiếu cũ để tương thích, không nhân bản file Storage. Các mục nổi bật cũ chỉ trỏ bài viết và không có asset ảnh không được chuyển thành album ảnh.
- Tài khoản riêng tư dùng audience followers khi tạo album, đồng thời vẫn phải qua `account_content_allowed`, ACL album, bài viết và media khi đọc. Không cấp quyền xem ảnh chỉ nhờ đưa ảnh vào album. Count/+N lấy từ ảnh thực sự được phép trả về cho người xem. Khi ảnh bị ẩn/gỡ, bìa hiển thị chuyển sang ảnh đầu tiên còn đọc được; album không còn ảnh hiển thị placeholder, chủ tài khoản có thể chỉnh sửa hoặc gỡ.

## Owner triển khai và kiểm tra

1. Áp dụng migration mới sau migration profile albums và follow-only privacy, rồi dùng Android source mới. Chưa chạy migration trên Cloud. RPC thiếu sẽ báo lỗi kèm khả năng tải lại ở trang chủ tài khoản.
2. Kiểm tra ảnh nổi bật cũ được giữ; tạo hai album tên khác nhau với 1 và 3 ảnh, xác nhận bo góc/lưới/tên/`+2`.
3. Mở album với tài khoản khác: kiểm tra tự chạy 5 giây sau tải ảnh, chạm trái/phải, giữ tay, tạm dừng/tiếp tục, tiến/lùi qua ranh giới album, bỏ qua album rỗng và đóng ở cuối. Chuyển nền/xoay màn hình kiểm tra giữ vị trí, không chạy timer trong nền, không nhận ảnh callback cũ. Thử lỗi ảnh, lỗi truy vấn và thay đổi quyền giữa lúc xem. Nhấn giữ album bằng chủ tài khoản để đổi tên, thêm/bỏ ảnh và tải lại trang. Kiểm tra tải thêm khi có hơn 50 ảnh ứng viên, lựa chọn cũ vẫn giữ khi tải trang tiếp.
4. Hủy tạo/chỉnh sửa không ghi dữ liệu. Thử lỗi mạng/nhấn lưu nhiều lần; cùng cửa sổ dùng cùng UUID. Nếu server đã lưu nhưng response mất, retry có thể báo xung đột; đóng và tải lại để lấy version hiện tại trước khi sửa tiếp.
5. Kiểm tra chủ tài khoản, người xem tài khoản công khai, follower được duyệt và người ngoài tài khoản riêng tư; ảnh/bài gốc riêng tư hoặc bị gỡ không được lộ qua bìa/count/viewer. Thử sửa album người khác qua RPC phải bị từ chối.
6. Kiểm tra giới hạn, xung đột hai phiên chỉnh sửa, ảnh mất quyền giữa chọn và lưu, xoay màn hình/đóng màn hình/đổi tài khoản. Bản chọn chưa lưu hiện chỉ nằm trong cửa sổ và không được khôi phục sau khi Activity bị tạo lại.

Chỉ đọc/rà và sửa source. Không chạy Gradle/compile/test/emulator/ADB, Cloud hoặc Git push. Chưa chứng minh build, migration hay giao diện thực tế trên thiết bị.

Phần trình xem kiểu Story dùng nguyên RPC của migration `20261108000500`; không cần migration bổ sung nếu file này đã được owner áp dụng.

## Sửa ảnh trùng trong bảng chọn

Source cũ chỉ loại trùng `asset_id`: cùng một file tải lại ở bài khác có asset ID khác nên vẫn hiện nhiều ô. Chưa có dữ liệu file từ thiết bị để khẳng định các cặp trong ảnh chụp có byte giống nhau.

`FeaturedAlbumsUi.Editor` nay đối chiếu SHA-256 của file gốc cho các ứng viên cùng kích thước byte, dùng `ExactPhotoFingerprint` đọc streaming qua quyền truy cập Storage hiện có. Chỉ tải nhóm có kích thước trùng nhau để đối chiếu; kích thước/thumbnail/tên không được coi là chứng cứ hai ảnh bằng nhau. Không tải file vào RAM toàn bộ hoặc ghi file tạm. Hash giữ trong phiên editor, tái sử dụng qua các trang. Giữ asset đã chọn làm đại diện nếu có; các bản trùng khác không hiện lại khi tải thêm. Chọn trùng trong bản chỉnh sửa được gộp trong draft, chỉ thay album nếu owner bấm Lưu. Hủy không đổi album hoặc ảnh nguồn.

Migration bổ sung `20261108000900_featured_photo_duplicate_sizes.sql` thêm `size_bytes` vào RPC ứng viên và ảnh album hiện có để so sánh cả ảnh đã chọn khi sửa. Giữ nguyên cursor và các kiểm tra quyền; không sửa dữ liệu media/post. Owner cần áp dụng file mới sau `20261108000500`.

Owner kiểm tra cùng một file được đăng nhiều lần chỉ còn một ô, kể cả qua hai trang; hai ảnh khác nhau có cùng số byte vẫn phải giữ riêng; chỉnh album giữ đại diện đã chọn và bấm Hủy không thay dữ liệu. Thử lỗi tải ảnh, hủy khi đang lọc và thay tài khoản. Hash không lấy được hoặc size thiếu/sai thì giữ ứng viên, không tự gộp. Ảnh nhìn giống nhau nhưng đã nén/crop/chỉnh sửa khác byte vẫn được giữ riêng để tránh gộp nhầm. Việc đối chiếu có thể tải thêm dữ liệu cho nhóm cùng size, nên lần mở đầu có thể chậm hơn. Chưa chạy build, kiểm thử hoặc Cloud cho sửa đổi này.
