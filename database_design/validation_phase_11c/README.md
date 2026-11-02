# Nhiệm vụ 11C — Android UI trên Supabase Cloud

Project được phép: `qjpohzpapiwdrxsionyr`. Emulator Pixel 4/API 37, hai tài khoản synthetic A/B hiện có. Báo cáo chính: [baocao_nhiemvu11C.txt](../../baocao_nhiemvu11C.txt).

Các suite thao tác widget bằng Espresso, rồi đối soát RPC bằng session GoTrue thật. Những đoạn chỉ đọc RPC hoặc bind model fixture được ghi riêng trong báo cáo. Không dùng kết quả fixture presentation làm bằng chứng verifier/Storage bytes thành công.

## Runners và nguồn

- `Task11CUiAuditTest`: form hồ sơ, profile refresh, post/reaction/edit/delete/privacy/cancel, friend list, private/group composer.
- `Task11CTwoUserUiTest`: logout/login A/B, friend lifecycle, privacy reads, private chat/reply/hide/recall/viewport ACK. `messenger_only=true` chạy targeted regression Messenger với quan hệ A/B đã accepted.
- `Task11CAuthStorageTest`: wrong password/protected activity/Back, JWT B matrix, pending PNG Storage isolation; không finalize ready.
- `Task11CNavigationTest`: tabs, search/empty state, Back, profile card, rotation và theme.
- `Task11CGroupUiTest`: create/rename/archive/restore/promote/leave qua menu nhóm synthetic.
- `CommentsUiTest`: root, hai reply, đúng hàng delete, giữ sibling, sửa reply, input rỗng.
- `adapters/MessagePresentationTest`: fixture UI cho sequence receipt và asset reference; không Cloud media success.
- `Task11CRecallUiTest`: targeted own-message recall.
- `CloudNetworkRecoveryTest`, `SessionRestoreTest`: targeted regressions có sẵn, không chạy lại toàn bộ tám test Task 11.

Chỉ build debug phục vụ emulator. Phải build cả target app khi đổi Java/XML:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# Cài -r app debug và test debug từ app/build/outputs/apk; giữ dữ liệu app.
```

`Invoke-TwoUserUi.ps1` nhận đường dẫn tài liệu owner đã cung cấp ở ngoài repository. Script chỉ đọc hai email/password vào memory và gửi một lần qua socket loopback tạm được ADB forward trên port 38711. Không truyền credentials bằng arguments instrumentation, không lưu credentials vào file mới; finally gỡ forward. Đây là runner test, không phải backend worker hoặc dependency để demo Cloud. Không dùng ADB reverse cho HTTP Cloud. Espresso failures trong suite có credentials được rút gọn để tránh dump form login.

Rerun phải dùng đúng A/B đã được chủ dự án cho phép và hộp thoại/profile synthetic hiện tại; không tạo Auth user. Không chạy đồng thời nhiều instrumentation suites trên cùng emulator.

## Evidence

JSON trong thư mục này chỉ chứa nhãn kiểm tra, HTTP/statusCode, counts, versions và hashes. `cloud-health.sql` là SELECT aggregate; không đọc event payload, email, token hoặc bytes. Cloud health snapshot không thay thế xác nhận delivery/read của thiết bị.

Owner preview của asset pending trong 15 phút là contract của migration 002. Ready chưa attach và quyền đọc qua parent là trạng thái khác. Hai trạng thái này phải có kỳ vọng riêng. Verifier Cloud vẫn BLOCKED; fixture pending không chứng minh ảnh/video hoàn chỉnh. Giữ pending fixtures theo lifecycle hiện có, không dùng SQL DELETE storage.objects, không mark verified/ready bằng operator.

Các lượt lỗi của harness có thể đã để lại post/comment/message/intent synthetic. Chỉ fixture mới của đúng test được sửa/xóa bằng user RPC khi scenario yêu cầu; không reset hoặc dọn dữ liệu cũ để đạt PASS. Báo cáo ghi giới hạn và các lỗi trước retest cuối.
