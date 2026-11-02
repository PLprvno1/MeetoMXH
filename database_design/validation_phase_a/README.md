# Phase A local tests

Đây là tests PostgreSQL, không là Supabase managed services. `00_stubs.sql` chỉ được chạy trên cluster Temp/MeetoAudit04-32hex, localhost:55439, DB `meeto_phase05`. Không đưa stubs vào migrations hoặc project thật.

Portable package/setup từ [audit04 validation](../validation/README.md). Runner mới không đọc local.properties; chỉ cho database Phase A và kiểm data_directory. Dùng process `powershell -NoProfile -File` để exit codes độc lập.

Thứ tự trên database sạch:

1. `Invoke-PhaseALocal.ps1 -PortableRoot <root> -CreateAuditDatabase` (nếu cần tái lập, `-RecreateAuditDatabase` chỉ xóa synthetic DB này).
2. Cùng runner `-SqlFile 00_stubs.sql`.
3. Cùng runner `-ApplyMigrations`: verify manifest, transaction từng version, local checksum ledger.
4. `-SqlFile 01_auth_profile_tests.sql` → 59 assertions.
5. `-SqlFile 02_extended_tests.sql` → 83 assertions tổng.
6. `-SqlFile 03_block_cleanup_tests.sql` → 87 assertions tổng.
7. `Invoke-PhaseAConcurrency.ps1 -PortableRoot <root>` → 3 real RPC races; reset riêng Bob synthetic fixture để tái lập; không sửa DB khác.
8. `Test-MigrationControls.ps1 -PortableRoot <root>` → 4 checks; cố ý đổi checksum local ledger rồi restore trong finally; không sửa SQL migration.
9. Dừng portable server bằng pg_ctl. Không để service nền.

83 và 87 là số tích lũy, không cộng 59+83+87. Mỗi SQL file có guard local. Native runner nhận `-SqlText` phục vụ truy vấn catalogue local; không có DSN remote option. Local checksum ledger không phải Supabase CLI migration ledger; CLI service integration còn thiếu.

[HTTP suite thật](../../supabase/tests/Invoke-AuthProfileHttp.ps1) cần Docker/local Supabase+Mailpit. Suite dùng GoTrue-issued JWT và invalid signature; không dùng auth GUC. Chưa chạy ở nhiệm vụ05 vì không có Docker/Podman. Mailpit HTTP ABI/provider behavior phải được xác nhận khi stack chạy. Test users không được xóa bằng Auth API tự động; chỉ reset local synthetic stack theo thao tác của chủ dự án sau khi không cần fixtures.

Không chạy lại Android/Gradle vì source/dependencies không đổi. Chi tiết kết quả, giới hạn, staging và recovery: [implementation report](../MEETO_PHASE_A_IMPLEMENTATION.md).
