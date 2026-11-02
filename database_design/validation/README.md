# Validation local — nhiệm vụ04
Đây là bộ kiểm tra PostgreSQL local, không phải thư mục migrations/deploy. Không sao chép Auth/Storage/Realtime stubs vào Supabase.

## Môi trường đã chạy
- Windows11 x64, PostgreSQL16.11 portable từ `io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64:16.11.0`, SHA-512 đối chiếu MavenCentral trước giải nén.
- Không có psql trong package; dùng libpq.dll qua PowerShell/.NET, không cài dependency Android.
- Cluster nằm trong Temp/MeetoAudit04-<32hex>/data, bind127.0.0.1:55439. Synthetic databases: meeto_audit04 và meeto_audit04_final.
- Tham khảo nguồn package: [Zonky binaries](https://github.com/zonkyio/embedded-postgres-binaries), [artifact MavenCentral](https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/16.11.0/).
- Không dùng local.properties, Supabase key, remote DSN, dữ liệu hoặc tài khoản Meeto thật. Portable server được dừng sau audit; thư mục tạm có thể bị OS cleanup.

## Runner bảo vệ môi trường
Invoke-MeetoLocalSql.ps1 chỉ nhận hai tên database tổng hợp trong ValidateSet, kết nối127.0.0.1 và kiểm SHOW data_directory khớp PortableRoot có pattern Temp/MeetoAudit04-UUID. CREATE/RECREATE chỉ dành cluster đó; recreate chỉ meeto_audit04_final.
Mỗi SQL validation thêm guard database/loopback/temp data_directory, giữ nguyên guard DRAFT ở hai file root. Không tự deploy hay mở network interface khác.

## Thứ tự tái lập final
Sau khi chuẩn bị một cluster portable cô lập cùng layout:
```powershell
# $auditRoot là đường dẫn Temp/MeetoAudit04-32hex; chưa tồn tại data cluster:
& "$auditRoot\pgsql\bin\initdb.exe" -D "$auditRoot\data" -U meeto_audit_owner -A trust --encoding=UTF8 --no-locale
& "$auditRoot\pgsql\bin\pg_ctl.exe" -D "$auditRoot\data" -l "$auditRoot\postgres.log" -o "-h 127.0.0.1 -p 55439 -c timezone=UTC" -w start
.\database_design\validation\Invoke-MeetoLocalSql.ps1 -PortableRoot $auditRoot -Database meeto_audit04_final -CreateAuditDatabase

# Chỉ tái lập synthetic final DB đã có mới dùng -RecreateAuditDatabase.
$files = '00_local_supabase_stubs.sql','01_schema_local.sql','02_rls_local.sql','03_synthetic_fixtures.sql','04_constraints_rls_tests.sql','07_regression_tests.sql'
foreach ($name in $files) {
  .\database_design\validation\Invoke-MeetoLocalSql.ps1 -PortableRoot $auditRoot -Database meeto_audit04_final -SqlFile ".\database_design\validation\$name"
  if ($LASTEXITCODE -ne 0) { throw "Validation failed: $name" }
}
.\database_design\validation\Invoke-MeetoConcurrency.ps1 -PortableRoot $auditRoot
.\database_design\validation\Invoke-MeetoLocalSql.ps1 -PortableRoot $auditRoot -Database meeto_audit04_final -SqlFile '.\database_design\validation\09_catalogue.sql'
.\database_design\validation\Invoke-MeetoLocalSql.ps1 -PortableRoot $auditRoot -Database meeto_audit04_final -SqlFile '.\database_design\validation\10_explain_small_fixture.sql'
& "$auditRoot\pgsql\bin\pg_ctl.exe" -D "$auditRoot\data" -m fast -w stop
```

01/02 là bản copy local của final drafts, chỉ bỏ design guard và COMMIT để engine kiểm tra; local guard vẫn còn. Không chạy các file này trên project thật. Khi sửa root drafts phải regenerate/review local copies, không chỉnh root guard để test.

03 chứa fixtures/identities giả và audit assertion functions invoker. Test helpers được explicit GRANT cho hai role mock; không tạo chúng trong ứng dụng.
04 trả83 assertions,07 bổ sung16 thành99. Concurrency script tạo hai connection và xác nhận wait_event_type=Lock,3 patterns; scripts không chứng minh các RPC chưa có body.
09 gồm FK index-prefix review thận trọng, không coi mọi dòng là thiếu index.10 chỉ EXPLAIN trên fixture nhỏ, không load/SLA benchmark.

## Evidence lịch sử, không thuộc chuỗi final
-05_baseline_adversarial_probes.sql: chỉ dùng trên bản schema/RLS trước sửa audit04; trả4 cờ điểm yếu. Sau finalfix, chạy nó sẽ bị constraint chặn, không phải regression mới.
-06_local_design_fixes.sql: bản vá trung gian trên baseline local, per-schema default EXECUTE revoke chưa đủ. Regression đã bắt lỗi,08 là sửa global default. **Không dùng06 làm migration final**.
-08_local_global_execute_fix.sql: historical correction tại local; freshfinal đã tích hợp global revoke trong01.
-Baseline original root guards và rerun42P07 đã thử riêng để xác nhận fail-before-DDL/rollback.
-Raw summary/catalog/EXPLAIN: MEETO_LOCAL_AUDIT_RESULTS.json.

## Giới hạn kiểm chứng
auth.uid/auth.jwt,storage.objects/allow_any_operation,realtime.topic/messages là interfaces SQL mô phỏng. Không có GoTrue,PostgREST,StorageHTTP,CDN,RealtimeWebSocket,pushSDK thật. JWT cryptography/service-role governance, HTTP route metadata và Android runtime cần staging tests riêng.
Không dùng dữ liệu giả để tuyên bố production backend đã hoạt động.99 assertions là kết quả thực trên PostgreSQL với fixtures tổng hợp có phạm vi xác định.

