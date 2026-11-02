# Meeto — Data Dictionary
DRAFT, ngày01/11/2026; **36 bảng thiết kế, chưa tạo trên Supabase**. Nhiệm vụ04 đã thực thi DDL/RLS trên PostgreSQL16.11 local với managed-schema stubs; xem MEETO_PRE_IMPLEMENTATION_AUDIT.md, không suy thành Supabase integration pass. Đọc cùng SCHEMA_DRAFT.sql; trạng thái CURRENT chỉ logic/source hiện có.

## Quy ước áp dụng cho mọi bảng
- PostgreSQL>=15, UTF-8; UUID PK/FK, TIMESTAMPTZ thời điểm server UTC, DATE ngày sinh; JSONB chỉ preference/event/metadata linh hoạt. Không lưu plaintext password/refresh token/signed URLs.
- Trong bảng cột: N = NOT NULL/PK; “nullable” là có thể null, không phải default chuỗi rỗng; khai báo nguyên văn thể hiện default/check/generated/FK. PK composite thể hiện phần constraints riêng. FK ON DELETE ghi rõ ở declaration.
- PK/UNIQUE tạo B-tree index tự động; indexes bổ sung liệt kê từng bảng. Không tạo FK cho target polymorphic notifications/outbox/mapping mà không có bảng đích thống nhất; writers/reconciliation kiểm tra.
- created_at default now() chỉ thời điểm INSERT. Mọi RPC mutation tự set updated_at và tăng version; user_settings có trigger timestamp riêng. Các trường do server quản lý không có direct UPDATE grant.
- auth.users, storage.objects, storage.buckets, realtime.messages là managed infrastructure, không tính vào36, không tạo lại chúng. profiles.id là FK Auth chính thức; Storage registry dùng API consistency thay FK managed object.
- SELECT chịu RLS, mọi social mutations dùng RPC transaction đã mô tả; SQL nháp chưa có body các RPC write nên deny-by-default là trạng thái dự kiến của draft. Không gọi đó là backend hoạt động.
- Với bảng FUTURE/OPTIONAL, phần CRUD là hợp đồng tương lai; không mở grants hoặc triển khai feature trước duyệt. Các liên kết chứa UUID không tự chứng minh người gọi có quyền xem đối tượng.

## 1. profiles

1. **Mục đích/lý do tồn tại:** Hồ sơ hiển thị, không chứa email/SĐT/ngày sinh.
2. **Nhóm nghiệp vụ:** Tài khoản.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE` |
| `display_name` | `text` | `NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100)` |
| `bio` | `text` | `NOT NULL DEFAULT '' CHECK (char_length(bio) <= 2000)` |
| `avatar_asset_id` | `uuid` | `nullable; default NULL` |
| `cover_asset_id` | `uuid` | `nullable; default NULL` |
| `onboarding_completed` | `boolean` | `NOT NULL DEFAULT false` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `version` | `bigint` | `NOT NULL DEFAULT 1 CHECK (version > 0)` |

5. **Primary key / Unique / Check cấp bảng:** PK/UNIQUE/CHECK trên cột xem declaration bên trên; các CHECK cấp bảng nếu có đã liệt kê ngay trước mục này.

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE`
- `ALTER TABLE public.profiles ADD CONSTRAINT profiles_avatar_fk FOREIGN KEY (avatar_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;`
- `ALTER TABLE public.profiles ADD CONSTRAINT profiles_cover_fk FOREIGN KEY (cover_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;`

7. **Indexes:** 
- `CREATE INDEX profiles_search_idx ON public.profiles (lower(display_name) text_pattern_ops, id);`

8. **Quyền đọc:** 
```sql
private.can_read_profile(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** bootstrap_profile / update_profile / request_account_deletion. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; bootstrap_profile / update_profile / request_account_deletion; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Users.uid/fullName/bio -> id/display_name/bio; ảnh URL chuyển media_assets. create qua bootstrap; update_profile RPC; không cho client đổi onboarding/version/asset ngoài workflow.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Hard delete chỉ sau phased deletion workflow; Auth FK cascade. Content author references SET NULL, personal body scrub trước.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Users.uid/fullName/bio -> id/display_name/bio; ảnh URL chuyển media_assets. create qua bootstrap; update_profile RPC; không cho client đổi onboarding/version/asset ngoài workflow.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.profiles` trong SCHEMA_DRAFT, `profiles_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 2. profile_private

1. **Mục đích/lý do tồn tại:** Thông tin nhạy cảm chỉ chủ hồ sơ.
2. **Nhóm nghiệp vụ:** Tài khoản.
3. **Trạng thái:** REQUIRED; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `user_id` | `uuid` | `PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `birth_date` | `date` | `CHECK (birth_date >= DATE '1900-01-01')` |
| `gender` | `text` | `CHECK (char_length(gender) <= 100)` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** PK/UNIQUE/CHECK trên cột xem declaration bên trên; các CHECK cấp bảng nếu có đã liệt kê ngay trước mục này.

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
private.is_active() AND user_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** update_profile_private (validate tuổi/calendars phía server). Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; update_profile_private (validate tuổi/calendars phía server); transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** dateOfBirth dd/MM/yyyy parse DATE, gender tách private. Email/phone/password vẫn ở Supabase Auth, không sao chép. Server validate tuổi/không tương lai, không CHECK phụ thuộc now().
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Hard cascade khi profile bị purge; không giữ DOB/gender trong audit.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** dateOfBirth dd/MM/yyyy parse DATE, gender tách private. Email/phone/password vẫn ở Supabase Auth, không sao chép. Server validate tuổi/không tương lai, không CHECK phụ thuộc now().
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.profile_private` trong SCHEMA_DRAFT, `profile_private_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 3. user_settings

1. **Mục đích/lý do tồn tại:** Quyền riêng tư, hiển thị last seen và notification preferences.
2. **Nhóm nghiệp vụ:** Tài khoản.
3. **Trạng thái:** REQUIRED; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `user_id` | `uuid` | `PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `profile_audience` | `text` | `NOT NULL DEFAULT 'public' CHECK (profile_audience IN ('public','friends','private'))` |
| `last_seen_audience` | `text` | `NOT NULL DEFAULT 'friends' CHECK (last_seen_audience IN ('public','friends','private'))` |
| `notifications_enabled` | `boolean` | `NOT NULL DEFAULT true` |
| `notification_preferences` | `jsonb` | `NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(notification_preferences) = 'object')` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:**
- `CHECK (octet_length(notification_preferences::text) <= 4096)`.
- `CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation']) = '{}'::jsonb)`.
- `CHECK (NOT jsonb_path_exists(notification_preferences, '$.* ? (@.type() != "boolean")'))`.
 PK/UNIQUE/CHECK trên cột xem declaration bên trên; các CHECK cấp bảng nếu có đã liệt kê ngay trước mục này.

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
private.is_active() AND user_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** bootstrap; GET/PATCH preference columns owner; timestamp trigger. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; bootstrap; GET/PATCH preference columns owner; timestamp trigger; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Không có collection hiện hành. CRUD owner; whitelist keys, boolean values và giới hạn4096 byte enforce tại DB vì owner có direct UPDATE; không chứa roles. Nếu thêm loại notification không cần thêm bảng.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Không có collection hiện hành. CRUD owner với whitelist keys preference ở API; không chứa roles. Nếu thêm loại notification không cần thêm bảng.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.user_settings` trong SCHEMA_DRAFT, `user_settings_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 4. account_status

1. **Mục đích/lý do tồn tại:** Cổng chặn tài khoản ở database, không tin JWT còn hiệu lực.
2. **Nhóm nghiệp vụ:** Tài khoản.
3. **Trạng thái:** REQUIRED; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `user_id` | `uuid` | `PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `state` | `text` | `NOT NULL DEFAULT 'active' CHECK (state IN ('active','suspended','deleting'))` |
| `suspended_until` | `timestamptz` | `nullable; default NULL` |
| `deletion_requested_at` | `timestamptz` | `nullable; default NULL` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** PK/UNIQUE/CHECK trên cột xem declaration bên trên; các CHECK cấp bảng nếu có đã liệt kê ngay trước mục này.

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
user_id=(select auth.uid()) AND auth.uid() IS NOT NULL
```

9. **Quyền INSERT/UPDATE/DELETE:** bootstrap / suspend_account / unsuspend_account / request_account_deletion. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; bootstrap / suspend_account / unsuspend_account / request_account_deletion; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Bootstrap/server/role-authorized RPC ghi; client chỉ đọc trạng thái của mình. Suspend hết hạn phải qua job/RPC unlock, không tự coi expired active. Chưa có state tương ứng trong source.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Hard cascade cuối workflow; suspended/deleting là state, không xóa để mở lại quyền.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Bootstrap/server/role-authorized RPC ghi; client chỉ đọc trạng thái của mình. Suspend hết hạn phải qua job/RPC unlock, không tự coi expired active. Chưa có state tương ứng trong source.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.account_status` trong SCHEMA_DRAFT, `account_status_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 5. user_blocks

1. **Mục đích/lý do tồn tại:** Block một chiều, hiệu lực truy cập được xét hai chiều.
2. **Nhóm nghiệp vụ:** Quan hệ.
3. **Trạng thái:** REQUIRED; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `blocker_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `blocked_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (blocker_id, blocked_id)`
- `CHECK (blocker_id <> blocked_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `blocker_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `blocked_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX user_blocks_reverse_idx ON public.user_blocks (blocked_id, blocker_id);`

8. **Quyền đọc:** 
```sql
private.is_active() AND blocker_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** set_block(target,true|false) trong pair lock. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; set_block(target,true|false) trong pair lock; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Block RPC pair-lock và xóa friendship/pending requests/follows hai chiều trong một transaction; unblock không tự phục hồi. Chỉ blocker đọc row để không lộ ai block mình.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Hard delete khi unblock; không tự restore friendship.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Block RPC pair-lock và xóa friendship/pending requests/follows hai chiều trong một transaction; unblock không tự phục hồi. Chỉ blocker đọc row để không lộ ai block mình.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.user_blocks` trong SCHEMA_DRAFT, `user_blocks_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 6. user_follows

1. **Mục đích/lý do tồn tại:** Follow độc lập friendship.
2. **Nhóm nghiệp vụ:** Quan hệ.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `follower_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `followed_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (follower_id, followed_id)`
- `CHECK (follower_id <> followed_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `follower_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `followed_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX user_follows_reverse_idx ON public.user_follows (followed_id, follower_id);`

8. **Quyền đọc:** 
```sql
private.is_active() AND (follower_id=(select auth.uid()) OR followed_id=(select auth.uid())) AND NOT private.is_blocked(CASE WHEN follower_id=auth.uid() THEN followed_id ELSE follower_id END)
```

9. **Quyền INSERT/UPDATE/DELETE:** set_follow(target,true|false) trong pair lock. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; set_follow(target,true|false) trong pair lock; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Follow RPC idempotent; không tự follow khi accept friend. Không cấp quyền Friends Only từ follow. Lists qua RPC theo visibility; raw rows chỉ owner hai đầu.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Follow RPC idempotent; không tự follow khi accept friend. Không cấp quyền Friends Only từ follow. Lists qua RPC theo visibility; raw rows chỉ owner hai đầu.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.user_follows` trong SCHEMA_DRAFT, `user_follows_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 7. friend_requests

1. **Mục đích/lý do tồn tại:** Lời mời có hướng; một pending request cho cặp.
2. **Nhóm nghiệp vụ:** Quan hệ.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `requester_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `recipient_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `pair_low` | `uuid` | `GENERATED ALWAYS AS (least(requester_id, recipient_id)) STORED` |
| `pair_high` | `uuid` | `GENERATED ALWAYS AS (greatest(requester_id, recipient_id)) STORED` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `state` | `text` | `NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','declined','cancelled'))` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `resolved_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (requester_id, client_request_id)`
- `CHECK (requester_id <> recipient_id)`
- `CHECK ((state = 'pending') = (resolved_at IS NULL))`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `requester_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `recipient_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE UNIQUE INDEX friend_requests_pending_pair_uq ON public.friend_requests (pair_low,pair_high) WHERE state='pending';`
- `CREATE INDEX friend_requests_inbox_idx ON public.friend_requests (recipient_id, state, created_at DESC, id DESC);`
- `CREATE INDEX friend_requests_outbox_idx ON public.friend_requests (requester_id, state, created_at DESC, id DESC);`

8. **Quyền đọc:** 
```sql
private.is_active() AND (requester_id=(select auth.uid()) OR recipient_id=(select auth.uid()))
```

9. **Quyền INSERT/UPDATE/DELETE:** send_friend_request / accept_friend_request / resolve_friend_request. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; send_friend_request / accept_friend_request / resolve_friend_request; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** PENDING_SENT/PENDING_RECEIVED hai document thành một row. Partial unique pending pair + pair advisory lock; gửi chéo trả existing incoming, không auto-accept. terminal row giữ tối đa90 ngày; idempotency nằm trong retention window.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Terminal status giữ90 ngày đề xuất rồi hard delete; pending không xóa để accept giả.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** PENDING_SENT/PENDING_RECEIVED hai document thành một row. Partial unique pending pair + pair advisory lock; gửi chéo trả existing incoming, không auto-accept. terminal row giữ tối đa90 ngày; idempotency nằm trong retention window.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.friend_requests` trong SCHEMA_DRAFT, `friend_requests_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 8. friendships

1. **Mục đích/lý do tồn tại:** Quan hệ vô hướng, một row/cặp UUID chuẩn hóa.
2. **Nhóm nghiệp vụ:** Quan hệ.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `user_low` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `user_high` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (user_low, user_high)`
- `CHECK (user_low < user_high)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_low uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `user_high uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX friendships_reverse_idx ON public.friendships (user_high,user_low);`

8. **Quyền đọc:** 
```sql
private.is_active() AND (user_low=(select auth.uid()) OR user_high=(select auth.uid()))
```

9. **Quyền INSERT/UPDATE/DELETE:** accept_friend_request / unfriend / set_block. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; accept_friend_request / unfriend / set_block; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** ACCEPTED hai phía hợp nhất một row; discrepancy quarantine khi migration. Accept transaction từ request pending; unfriend delete pair không ghi hai row.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Hard delete unfriend/block trong pair transaction.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** ACCEPTED hai phía hợp nhất một row; discrepancy quarantine khi migration. Accept transaction từ request pending; unfriend delete pair không ghi hai row.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.friendships` trong SCHEMA_DRAFT, `friendships_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 9. groups

1. **Mục đích/lý do tồn tại:** Metadata và trạng thái nhóm cộng đồng.
2. **Nhóm nghiệp vụ:** Nhóm.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `created_by` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `name` | `text` | `NOT NULL CHECK (char_length(name) BETWEEN 1 AND 150)` |
| `description` | `text` | `NOT NULL DEFAULT '' CHECK (char_length(description) <= 5000)` |
| `privacy` | `text` | `NOT NULL DEFAULT 'public' CHECK (privacy IN ('public','private'))` |
| `state` | `text` | `NOT NULL DEFAULT 'active' CHECK (state IN ('active','archived','deleted'))` |
| `cover_asset_id` | `uuid` | `nullable; default NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `version` | `bigint` | `NOT NULL DEFAULT 1 CHECK (version > 0)` |

5. **Primary key / Unique / Check cấp bảng:** PK/UNIQUE/CHECK trên cột xem declaration bên trên; các CHECK cấp bảng nếu có đã liệt kê ngay trước mục này.

- `UNIQUE (created_by, client_request_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `created_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `ALTER TABLE public.groups ADD CONSTRAINT groups_cover_fk FOREIGN KEY (cover_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
private.can_read_group(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** create_group / update_group / archive_group / delete_group. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; create_group / update_group / archive_group / delete_group; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Create group RPC tạo group+admin atomically. Private group chỉ metadata tối thiểu public-to-authenticated qua listing RPC; direct table private chỉ member. Group Admin riêng system_roles.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Create group RPC tạo group+admin atomically. Private group chỉ metadata tối thiểu public-to-authenticated qua listing RPC; direct table private chỉ member. Group Admin riêng system_roles.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.groups` trong SCHEMA_DRAFT, `groups_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 10. group_members

1. **Mục đích/lý do tồn tại:** Membership và vai trò scoped theo nhóm, bao gồm ban.
2. **Nhóm nghiệp vụ:** Nhóm.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `group_id` | `uuid` | `NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE` |
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `role` | `text` | `NOT NULL DEFAULT 'member' CHECK (role IN ('admin','moderator','member'))` |
| `state` | `text` | `NOT NULL DEFAULT 'active' CHECK (state IN ('active','left','removed','banned'))` |
| `joined_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `left_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (group_id, user_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE`
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX group_members_user_idx ON public.group_members (user_id,state,group_id);`
- `CREATE INDEX group_members_admin_idx ON public.group_members (group_id,role) WHERE state='active';`

8. **Quyền đọc:** 
```sql
private.group_member(group_id)
```

9. **Quyền INSERT/UPDATE/DELETE:** join_group / approve_group_request / manage_group_member / leave_group / transfer_group_admin. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; join_group / approve_group_request / manage_group_member / leave_group / transfer_group_admin; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Row state giữ ban/removed; RPC lock group ngăn admin cuối cùng leave/demote/ban; transfer admin trong transaction; last-admin invariant không phải CHECK đếm cross-row.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Row state giữ ban/removed; RPC lock group ngăn admin cuối cùng leave/demote/ban; transfer admin trong transaction; last-admin invariant không phải CHECK đếm cross-row.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.group_members` trong SCHEMA_DRAFT, `group_members_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 11. group_join_requests

1. **Mục đích/lý do tồn tại:** Yêu cầu join nhóm private và kết quả.
2. **Nhóm nghiệp vụ:** Nhóm.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `group_id` | `uuid` | `NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE` |
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `state` | `text` | `NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','approved','declined','cancelled'))` |
| `reviewed_by` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `resolved_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `CHECK ((state = 'pending') = (resolved_at IS NULL))`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE`
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `reviewed_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

7. **Indexes:** 
- `CREATE UNIQUE INDEX group_join_pending_uq ON public.group_join_requests (group_id,user_id) WHERE state='pending';`
- `CREATE INDEX group_join_review_idx ON public.group_join_requests (group_id,state,created_at,id);`

8. **Quyền đọc:** 
```sql
private.is_active() AND (user_id=(select auth.uid()) OR private.group_manager(group_id))
```

9. **Quyền INSERT/UPDATE/DELETE:** request_group_join / resolve_group_join_request. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; request_group_join / resolve_group_join_request; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Partial unique pending(group,user). Approve -> group_members upsert active dưới group lock; không approve banned. Public join cùng membership RPC, không cần request.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Partial unique pending(group,user). Approve -> group_members upsert active dưới group lock; không approve banned. Public join cùng membership RPC, không cần request.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.group_join_requests` trong SCHEMA_DRAFT, `group_join_requests_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 12. posts

1. **Mục đích/lý do tồn tại:** Original/share dùng chung bảng; audience personal khác group context.
2. **Nhóm nghiệp vụ:** Bài viết.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `author_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `group_id` | `uuid` | `REFERENCES public.groups(id) ON DELETE RESTRICT` |
| `kind` | `text` | `NOT NULL DEFAULT 'original' CHECK (kind IN ('original','share'))` |
| `original_post_id` | `uuid` | `REFERENCES public.posts(id) ON DELETE RESTRICT` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `request_hash` | `text` | `NOT NULL CHECK (char_length(request_hash) = 64)` |
| `body` | `text` | `NOT NULL DEFAULT '' CHECK (char_length(body) <= 20000)` |
| `audience` | `text` | `NOT NULL DEFAULT 'public' CHECK (audience IN ('public','friends','private','group'))` |
| `state` | `text` | `NOT NULL DEFAULT 'published' CHECK (state IN ('published','hidden','deleted'))` |
| `reactions_count` | `bigint` | `NOT NULL DEFAULT 0 CHECK (reactions_count >= 0)` |
| `comments_count` | `bigint` | `NOT NULL DEFAULT 0 CHECK (comments_count >= 0)` |
| `shares_count` | `bigint` | `NOT NULL DEFAULT 0 CHECK (shares_count >= 0)` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `deleted_at` | `timestamptz` | `nullable; default NULL` |
| `version` | `bigint` | `NOT NULL DEFAULT 1 CHECK (version > 0)` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (author_id, client_request_id)`
- `CHECK ((group_id IS NULL AND audience <> 'group') OR (group_id IS NOT NULL AND audience = 'group'))`
- `CHECK ((kind = 'share' AND original_post_id IS NOT NULL AND group_id IS NULL) OR (kind = 'original' AND original_post_id IS NULL))`
- `CHECK (original_post_id IS NULL OR original_post_id <> id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `group_id uuid REFERENCES public.groups(id) ON DELETE RESTRICT`
- `original_post_id uuid REFERENCES public.posts(id) ON DELETE RESTRICT`

7. **Indexes:** 
- `CREATE INDEX posts_feed_idx ON public.posts (created_at DESC,id DESC) WHERE state='published' AND group_id IS NULL;`
- `CREATE INDEX posts_author_idx ON public.posts (author_id,created_at DESC,id DESC);`
- `CREATE INDEX posts_group_idx ON public.posts (group_id,created_at DESC,id DESC) WHERE group_id IS NOT NULL;`
- `CREATE INDEX posts_original_idx ON public.posts (original_post_id) WHERE original_post_id IS NOT NULL;`

8. **Quyền đọc:** 
```sql
private.can_read_post(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** create_post / edit_post / share_post / delete_post. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; create_post / edit_post / share_post / delete_post; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Posts -> normalized author/media; server timestamps. Share trỏ original root (không chain/group share); RPC kiểm tra source readable; source xóa/đổi quyền thì share không copy nội dung. FK RESTRICT giữ tombstone tới cleanup tham chiếu.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Soft delete/tombstone và revoke media ngay; hard purge sau xử lý shares FK RESTRICT/cases/retention.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Posts -> normalized author/media; server timestamps. Share trỏ original root (không chain/group share); RPC kiểm tra source readable; source xóa/đổi quyền thì share không copy nội dung. FK RESTRICT giữ tombstone tới cleanup tham chiếu.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.posts` trong SCHEMA_DRAFT, `posts_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 13. post_reactions

1. **Mục đích/lý do tồn tại:** Một reaction enum-text/user/post, thay type không tăng tổng.
2. **Nhóm nghiệp vụ:** Bài viết.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `post_id` | `uuid` | `NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `reaction` | `text` | `NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry'))` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (post_id, user_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE`
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX post_reactions_user_idx ON public.post_reactions (user_id,post_id);`

8. **Quyền đọc:** 
```sql
private.can_read_post(post_id) AND NOT private.is_blocked(user_id)
```

9. **Quyền INSERT/UPDATE/DELETE:** set_post_reaction(post,type|null). Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; set_post_reaction(post,type|null); transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Reactions.uid/type LIKE -> PK composite + like. set_reaction(post,type|null) idempotent, không toggle mơ hồ; atomic counter delta từ INSERT/DELETE thực tế.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Reactions.uid/type LIKE -> PK composite + like. set_reaction(post,type|null) idempotent, không toggle mơ hồ; atomic counter delta từ INSERT/DELETE thực tế.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.post_reactions` trong SCHEMA_DRAFT, `post_reactions_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 14. comments

1. **Mục đích/lý do tồn tại:** Thread nhiều cấp, parent phải cùng post.
2. **Nhóm nghiệp vụ:** Bài viết.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `post_id` | `uuid` | `NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `author_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `parent_id` | `uuid` | `nullable; default NULL` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `request_hash` | `text` | `NOT NULL CHECK (char_length(request_hash) = 64)` |
| `body` | `text` | `CHECK (char_length(body) <= 5000)` |
| `state` | `text` | `NOT NULL DEFAULT 'published' CHECK (state IN ('published','hidden','deleted'))` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `version` | `bigint` | `NOT NULL DEFAULT 1 CHECK (version > 0)` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (id, post_id)`
- `UNIQUE (author_id, client_request_id)`
- `FOREIGN KEY (parent_id, post_id) REFERENCES public.comments(id, post_id) ON DELETE RESTRICT`
- `CHECK (parent_id IS NULL OR parent_id <> id)`
- `CHECK (state <> 'published' OR (body IS NOT NULL AND char_length(btrim(body)) > 0))`
- `CHECK (state <> 'deleted' OR body IS NULL)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE`
- `author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `FOREIGN KEY (parent_id, post_id) REFERENCES public.comments(id, post_id) ON DELETE RESTRICT`

7. **Indexes:** 
- `CREATE INDEX comments_thread_idx ON public.comments (post_id,parent_id,created_at,id);`
- `CREATE INDEX comments_author_idx ON public.comments (author_id);`

8. **Quyền đọc:** 
```sql
private.can_read_comment(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** create_comment / edit_comment / delete_comment. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; create_comment / edit_comment / delete_comment; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Comments.createdAt millis -> timestamptz. parent immutable RPC; server depth tối đa8 ở MVP; insert ancestor existent; không reparent nên không cycle. Tombstone parent giữ replies; delete state/body trong transaction.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Soft delete body=NULL; giữ tombstone parent; hard purge leaf hoặc cả post subtree bằng worker.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Comments.createdAt millis -> timestamptz. parent immutable RPC; server depth tối đa8 ở MVP; insert ancestor existent; không reparent nên không cycle. Tombstone parent giữ replies; delete state/body trong transaction.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.comments` trong SCHEMA_DRAFT, `comments_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 15. comment_reactions

1. **Mục đích/lý do tồn tại:** Reaction cho comment nếu được duyệt, không cần cho parity.
2. **Nhóm nghiệp vụ:** Bài viết.
3. **Trạng thái:** OPTIONAL; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `comment_id` | `uuid` | `NOT NULL REFERENCES public.comments(id) ON DELETE CASCADE` |
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `reaction` | `text` | `NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry'))` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (comment_id, user_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `comment_id uuid NOT NULL REFERENCES public.comments(id) ON DELETE CASCADE`
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX comment_reactions_user_idx ON public.comment_reactions (user_id,comment_id);`

8. **Quyền đọc:** 
```sql
private.can_read_comment(comment_id) AND NOT private.is_blocked(user_id)
```

9. **Quyền INSERT/UPDATE/DELETE:** set_comment_reaction(comment,type|null). Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; set_comment_reaction(comment,type|null); transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Chưa có feature trong source/không bắt buộc hiện tại. Dùng RPC tương tự post; không tạo counter cache comment trước khi đo nhu cầu.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Chỉ bật khi comment reactions được phê duyệt; không ảnh hưởng schema post reactions.
16. **Migration từ Firebase:** Chưa có feature trong source/không bắt buộc hiện tại. Dùng RPC tương tự post; không tạo counter cache comment trước khi đo nhu cầu.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.comment_reactions` trong SCHEMA_DRAFT, `comment_reactions_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 16. saved_posts

1. **Mục đích/lý do tồn tại:** Bookmark cá nhân, không thêm quyền đọc post.
2. **Nhóm nghiệp vụ:** Bài viết.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `post_id` | `uuid` | `NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (user_id, post_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX saved_posts_cursor_idx ON public.saved_posts (user_id,created_at DESC,post_id DESC);`

8. **Quyền đọc:** 
```sql
private.is_active() AND user_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** owner INSERT/DELETE; duplicate PK -> idempotent conflict handling. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; owner INSERT/DELETE; duplicate PK -> idempotent conflict handling; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Saved UI hiện Toast; set_saved RPC/check can_read_post; khi mất quyền row owner vẫn có thể xóa nhưng không load được nội dung post.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Saved UI hiện Toast; set_saved RPC/check can_read_post; khi mất quyền row owner vẫn có thể xóa nhưng không load được nội dung post.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.saved_posts` trong SCHEMA_DRAFT, `saved_posts_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 17. hidden_posts

1. **Mục đích/lý do tồn tại:** Preference loại bài khỏi feed, không đổi audience.
2. **Nhóm nghiệp vụ:** Bài viết.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `post_id` | `uuid` | `NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (user_id, post_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
private.is_active() AND user_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** owner INSERT/DELETE; feed anti-join. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; owner INSERT/DELETE; feed anti-join; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** set_hidden idempotent. Feed RPC anti-join; post detail còn được xem nếu có quyền; hide khác moderation hidden.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** set_hidden idempotent. Feed RPC anti-join; post detail còn được xem nếu có quyền; hide khác moderation hidden.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.hidden_posts` trong SCHEMA_DRAFT, `hidden_posts_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 18. stories

1. **Mục đích/lý do tồn tại:** Một story một asset, asset không reuse story khác ảnh/video, hạn24h độc lập cleanup.
2. **Nhóm nghiệp vụ:** Stories.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `author_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `media_asset_id` | `uuid` | `NOT NULL UNIQUE` |
| `caption` | `text` | `NOT NULL DEFAULT '' CHECK (char_length(caption) <= 2000)` |
| `audience` | `text` | `NOT NULL DEFAULT 'friends' CHECK (audience IN ('public','friends','private'))` |
| `state` | `text` | `NOT NULL DEFAULT 'active' CHECK (state IN ('active','deleted','archived'))` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `expires_at` | `timestamptz` | `NOT NULL DEFAULT (now() + interval '24 hours')` |
| `deleted_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (author_id, client_request_id)`
- `CHECK (expires_at = created_at + interval '24 hours')`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `ALTER TABLE public.stories ADD CONSTRAINT stories_media_fk FOREIGN KEY (media_asset_id) REFERENCES public.media_assets(id) ON DELETE RESTRICT;`

7. **Indexes:** 
- `CREATE INDEX stories_live_idx ON public.stories (expires_at,created_at DESC,id DESC) WHERE state='active';`
- `CREATE INDEX stories_author_idx ON public.stories (author_id,created_at DESC,id DESC);`
- `CREATE INDEX stories_asset_idx ON public.stories (media_asset_id);`

8. **Quyền đọc:** 
```sql
private.can_read_story(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** create_story / delete_story / archive_story (archive chưa bật). Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; create_story / delete_story / archive_story (archive chưa bật); transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Story model hiện mock chỉ card; không migrate dummy IDs. Expired suy từ expires_at<=now, không thêm state expired dễ drift; archived owner-only sau expiry, tùy sản phẩm bật sau.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Expiry query không xóa row; deleted/archive khác nhau; cleanup bytes/row theo retention.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Story model hiện mock chỉ card; không migrate dummy IDs. Expired suy từ expires_at<=now, không thêm state expired dễ drift; archived owner-only sau expiry, tùy sản phẩm bật sau.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.stories` trong SCHEMA_DRAFT, `stories_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 19. story_views

1. **Mục đích/lý do tồn tại:** Một viewer/story; source cho owner xem viewers.
2. **Nhóm nghiệp vụ:** Stories.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `story_id` | `uuid` | `NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE` |
| `viewer_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `first_viewed_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (story_id, viewer_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `story_id uuid NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE`
- `viewer_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX story_views_viewer_idx ON public.story_views (viewer_id,story_id);`

8. **Quyền đọc:** 
```sql
private.can_read_story(story_id) AND (viewer_id=(select auth.uid()) OR EXISTS (SELECT 1 FROM public.stories s WHERE s.id=story_id AND s.author_id=(select auth.uid())))
```

9. **Quyền INSERT/UPDATE/DELETE:** record_story_view (UPSERT no-op). Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; record_story_view (UPSERT no-op); transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** record_view RPC checks not expired/readable, ON CONFLICT DO NOTHING; chỉ owner story hoặc viewer đọc row thích hợp, không public viewer list.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** record_view RPC checks not expired/readable, ON CONFLICT DO NOTHING; chỉ owner story hoặc viewer đọc row thích hợp, không public viewer list.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.story_views` trong SCHEMA_DRAFT, `story_views_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 20. story_reactions

1. **Mục đích/lý do tồn tại:** Reaction cho story, một row/user.
2. **Nhóm nghiệp vụ:** Stories.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `story_id` | `uuid` | `NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE` |
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `reaction` | `text` | `NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry'))` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (story_id, user_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `story_id uuid NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE`
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX story_reactions_user_idx ON public.story_reactions (user_id,story_id);`

8. **Quyền đọc:** 
```sql
private.can_read_story(story_id) AND (user_id=(select auth.uid()) OR EXISTS (SELECT 1 FROM public.stories s WHERE s.id=story_id AND s.author_id=(select auth.uid())))
```

9. **Quyền INSERT/UPDATE/DELETE:** set_story_reaction(story,type|null). Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; set_story_reaction(story,type|null); transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** set_story_reaction RPC checks expiry; người dùng/owner story đọc theo privacy; Story reply đi qua messages.reply_story_id, không tạo chat song song.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** set_story_reaction RPC checks expiry; người dùng/owner story đọc theo privacy; Story reply đi qua messages.reply_story_id, không tạo chat song song.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.story_reactions` trong SCHEMA_DRAFT, `story_reactions_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 21. conversations

1. **Mục đích/lý do tồn tại:** Cùng schema cho private và group chat, sequence serialized.
2. **Nhóm nghiệp vụ:** Tin nhắn.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `kind` | `text` | `NOT NULL DEFAULT 'private' CHECK (kind IN ('private','group'))` |
| `direct_low` | `uuid` | `REFERENCES public.profiles(id) ON DELETE RESTRICT` |
| `direct_high` | `uuid` | `REFERENCES public.profiles(id) ON DELETE RESTRICT` |
| `client_request_id` | `uuid` | `nullable; group requires value` |
| `created_by` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `title` | `text` | `CHECK (char_length(title) <= 150)` |
| `state` | `text` | `NOT NULL DEFAULT 'active' CHECK (state IN ('active','archived','deleted'))` |
| `next_seq` | `bigint` | `NOT NULL DEFAULT 0 CHECK (next_seq >= 0)` |
| `last_message_id` | `uuid` | `nullable; default NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `version` | `bigint` | `NOT NULL DEFAULT 1 CHECK (version > 0)` |

5. **Primary key / Unique / Check cấp bảng:** 
- `CHECK ((kind = 'private' AND direct_low IS NOT NULL AND direct_high IS NOT NULL AND direct_low < direct_high) OR (kind = 'group' AND direct_low IS NULL AND direct_high IS NULL))`

- `UNIQUE (created_by, client_request_id); CHECK (kind <> 'group' OR client_request_id IS NOT NULL)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `direct_low uuid REFERENCES public.profiles(id) ON DELETE RESTRICT`
- `direct_high uuid REFERENCES public.profiles(id) ON DELETE RESTRICT`
- `created_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

- `FOREIGN KEY (last_message_id,id) REFERENCES public.messages(id,conversation_id) DEFERRABLE INITIALLY DEFERRED` (ALTER sau tables).

7. **Indexes:** 
- `CREATE UNIQUE INDEX conversations_direct_pair_uq ON public.conversations (direct_low,direct_high) WHERE kind='private';`
- `CREATE INDEX conversations_inbox_idx ON public.conversations (updated_at DESC,id DESC);`

8. **Quyền đọc:** 
```sql
private.can_read_conversation(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** get_or_create_private / create_group_conversation / archive_conversation. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; get_or_create_private / create_group_conversation / archive_conversation; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Chats SHA256 ID -> UUID map, partial unique direct pair. Không lưu lastMessage text dễ lộ sau recall/delete; last_message_id chỉ gợi ý, inbox RPC resolve newest visible row từng user. Khi xóa account, job hard-delete private conversations trước profile purge vì pair FK RESTRICT.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Archived/deleted deny mặc định; hard purge messages sau retention, clear last pointer/reply deps.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Chats SHA256 ID -> UUID map, partial unique direct pair. Không lưu lastMessage text dễ lộ sau recall/delete; last_message_id chỉ gợi ý, inbox RPC resolve newest visible row từng user. Khi xóa account, job hard-delete private conversations trước profile purge vì pair FK RESTRICT.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.conversations` trong SCHEMA_DRAFT, `conversations_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 22. conversation_members

1. **Mục đích/lý do tồn tại:** Membership, role và per-member read/delivery boundary.
2. **Nhóm nghiệp vụ:** Tin nhắn.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `conversation_id` | `uuid` | `NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE` |
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `role` | `text` | `NOT NULL DEFAULT 'member' CHECK (role IN ('admin','member'))` |
| `state` | `text` | `NOT NULL DEFAULT 'active' CHECK (state IN ('active','left','removed'))` |
| `history_from_seq` | `bigint` | `NOT NULL DEFAULT 1 CHECK (history_from_seq >= 1)` |
| `last_read_seq` | `bigint` | `NOT NULL DEFAULT 0 CHECK (last_read_seq >= 0)` |
| `last_delivered_seq` | `bigint` | `NOT NULL DEFAULT 0 CHECK (last_delivered_seq >= 0)` |
| `joined_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `left_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (conversation_id, user_id)`
- `CHECK (last_read_seq <= last_delivered_seq)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE`
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX conversation_members_user_idx ON public.conversation_members (user_id,state,conversation_id);`

8. **Quyền đọc:** 
```sql
private.can_read_conversation(conversation_id)
```

9. **Quyền INSERT/UPDATE/DELETE:** manage_conversation_member / leave_conversation / mark_read / acknowledge_delivery. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; manage_conversation_member / leave_conversation / mark_read / acknowledge_delivery; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** participants[] + read maps -> normalized rows. New group member history_from_seq=next_seq+1; không đọc cũ. Rejoin resets boundary (đề xuất); private members immutable exactly2 via RPC. Delivered chỉ cập nhật bởi ACK thiết bị (future), read RPC advances both monotonic.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Soft state left/removed để deny/giữ lịch sử membership-boundary; no Android DELETE.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** participants[] + read maps -> normalized rows. New group member history_from_seq=next_seq+1; không đọc cũ. Rejoin resets boundary (đề xuất); private members immutable exactly2 via RPC. Delivered chỉ cập nhật bởi ACK thiết bị (future), read RPC advances both monotonic.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.conversation_members` trong SCHEMA_DRAFT, `conversation_members_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 23. messages

1. **Mục đích/lý do tồn tại:** Text/media/system, retry idempotent, seq theo conversation.
2. **Nhóm nghiệp vụ:** Tin nhắn.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `conversation_id` | `uuid` | `NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE` |
| `seq` | `bigint` | `NOT NULL CHECK (seq > 0)` |
| `sender_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `request_hash` | `text` | `NOT NULL CHECK (char_length(request_hash) = 64)` |
| `kind` | `text` | `NOT NULL DEFAULT 'text' CHECK (kind IN ('text','media','system'))` |
| `body` | `text` | `CHECK (char_length(body) <= 10000)` |
| `reply_to_message_id` | `uuid` | `nullable; default NULL` |
| `reply_story_id` | `uuid` | `REFERENCES public.stories(id) ON DELETE SET NULL` |
| `state` | `text` | `NOT NULL DEFAULT 'sent' CHECK (state IN ('sent','recalled','removed'))` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `edited_at` | `timestamptz` | `nullable; default NULL` |
| `recalled_at` | `timestamptz` | `nullable; default NULL` |
| `version` | `bigint` | `NOT NULL DEFAULT 1 CHECK (version > 0)` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (conversation_id, seq)`
- `UNIQUE (id, conversation_id)`
- `UNIQUE (conversation_id, sender_id, client_request_id)`
- `FOREIGN KEY (reply_to_message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE RESTRICT`
- `CHECK (reply_to_message_id IS NULL OR reply_to_message_id <> id)`
- `CHECK (state = 'sent' OR body IS NULL)`
- `CHECK (state <> 'sent' OR kind <> 'text' OR (body IS NOT NULL AND char_length(btrim(body)) > 0))`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE`
- `sender_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `reply_story_id uuid REFERENCES public.stories(id) ON DELETE SET NULL`
- `FOREIGN KEY (reply_to_message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE RESTRICT`

7. **Indexes:** 
- `CREATE INDEX messages_sender_idx ON public.messages (sender_id,created_at DESC);`
- `CREATE INDEX messages_reply_idx ON public.messages (reply_to_message_id) WHERE reply_to_message_id IS NOT NULL;`
- `CREATE INDEX messages_story_idx ON public.messages (reply_story_id) WHERE reply_story_id IS NOT NULL;`
- `CREATE INDEX messages_search_idx ON public.messages USING gin (to_tsvector('simple',coalesce(body,''))) WHERE state='sent' AND kind='text';`

8. **Quyền đọc:** 
```sql
private.can_read_message(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** send_message / recall_message / remove_message_by_case; edit FUTURE. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; send_message / recall_message / remove_message_by_case; edit FUTURE; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Messages sender/receiver -> membership; receiver không còn cột. request_hash SHA256 server canonical body+type+refs+assetIDs, không grant SELECT hash vì có thể suy nội dung recalled. Reply preview không sao chép body. Editing text future chỉ với version/permission; recall wipes body và disables assets atomically.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Recall/removed body=NULL lập tức; hard purge theo conversation/retention, giải quyết reply FK trước.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Messages sender/receiver -> membership; receiver không còn cột. request_hash SHA256 server canonical body+type+refs+assetIDs, không grant SELECT hash vì có thể suy nội dung recalled. Reply preview không sao chép body. Editing text future chỉ với version/permission; recall wipes body và disables assets atomically.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.messages` trong SCHEMA_DRAFT, `messages_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 24. message_hidden

1. **Mục đích/lý do tồn tại:** Xóa phía mình: preference một row/member/message.
2. **Nhóm nghiệp vụ:** Tin nhắn.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `message_id` | `uuid` | `NOT NULL` |
| `conversation_id` | `uuid` | `NOT NULL` |
| `user_id` | `uuid` | `NOT NULL` |
| `hidden_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (message_id, user_id)`
- `FOREIGN KEY (message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE CASCADE`
- `FOREIGN KEY (conversation_id, user_id) REFERENCES public.conversation_members(conversation_id, user_id) ON DELETE CASCADE`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `FOREIGN KEY (message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE CASCADE`
- `FOREIGN KEY (conversation_id, user_id) REFERENCES public.conversation_members(conversation_id, user_id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX message_hidden_user_idx ON public.message_hidden (user_id,conversation_id,message_id);`

8. **Quyền đọc:** 
```sql
private.is_active() AND user_id=(select auth.uid()) AND private.can_read_conversation(conversation_id)
```

9. **Quyền INSERT/UPDATE/DELETE:** hide_message_for_me (không unhide MVP). Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; hide_message_for_me (không unhide MVP); transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** deletedFor[] -> rows; RLS messages excludes row for current viewer. Hidden user không get raw message/media; author khác vẫn đọc theo quyền. No unsend; no direct list rows của người khác.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** deletedFor[] -> rows; RLS messages excludes row for current viewer. Hidden user không get raw message/media; author khác vẫn đọc theo quyền. No unsend; no direct list rows của người khác.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.message_hidden` trong SCHEMA_DRAFT, `message_hidden_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 25. media_assets

1. **Mục đích/lý do tồn tại:** Registry object ownership, purpose, lifecycle; không lưu signed URL.
2. **Nhóm nghiệp vụ:** Media.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `owner_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `bucket_id` | `text` | `NOT NULL CHECK (bucket_id IN ('profile-media','social-media','chat-media','group-media'))` |
| `object_path` | `text` | `NOT NULL` |
| `purpose` | `text` | `NOT NULL CHECK (purpose IN ('avatar','cover','post','story','message','group'))` |
| `state` | `text` | `NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','ready','quarantined','pending_delete','deleted'))` |
| `mime_type` | `text` | `NOT NULL` |
| `size_bytes` | `bigint` | `CHECK (size_bytes > 0 AND size_bytes <= 104857600)` |
| `metadata` | `jsonb` | `NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object')` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `ready_at` | `timestamptz` | `nullable; default NULL` |
| `delete_after` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (bucket_id, object_path)`
- `CHECK ((purpose IN ('avatar','cover') AND bucket_id = 'profile-media') OR (purpose IN ('post','story') AND bucket_id = 'social-media') OR (purpose = 'message' AND bucket_id = 'chat-media') OR (purpose = 'group' AND bucket_id = 'group-media'))`

- `UNIQUE (owner_id, client_request_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `owner_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

7. **Indexes:** 
- `CREATE INDEX media_owner_idx ON public.media_assets (owner_id,state,created_at);`
- `CREATE INDEX media_cleanup_idx ON public.media_assets (state,delete_after);`

8. **Quyền đọc:** 
```sql
private.can_read_media(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** prepare_upload / finalize_upload / attach via parent RPC / cleanup worker. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; prepare_upload / finalize_upload / attach via parent RPC / cleanup worker; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** URL -> bucket/path + mapping; profile/post/chat calls tương ứng CURRENT logic, chưa có bảng. Upload intent do server tạo path ownerUUID/assetUUID/original.ext; finalize verifies MIME bytes actual. Không FK storage.objects vì lifecycle API riêng; consistency qua job.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** State pending_delete deny reads trước, delete Storage API rồi state=deleted; metadata purge khi không còn FK.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** URL -> bucket/path + mapping; profile/post/chat calls tương ứng CURRENT logic, chưa có bảng. Upload intent do server tạo path ownerUUID/assetUUID/original.ext; finalize verifies MIME bytes actual. Không FK storage.objects vì lifecycle API riêng; consistency qua job.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.media_assets` trong SCHEMA_DRAFT, `media_assets_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 26. post_media

1. **Mục đích/lý do tồn tại:** Media nhiều ảnh/video có thứ tự của bài.
2. **Nhóm nghiệp vụ:** Media.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `post_id` | `uuid` | `NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `asset_id` | `uuid` | `NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT` |
| `position` | `smallint` | `NOT NULL CHECK (position BETWEEN 0 AND 9)` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (post_id, asset_id)`
- `UNIQUE (post_id, position)`
- `UNIQUE (asset_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE`
- `asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
private.can_read_post(post_id)
```

9. **Quyền INSERT/UPDATE/DELETE:** create_post/edit_post gắn ready owned assets trong cùng transaction. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; create_post/edit_post gắn ready owned assets trong cùng transaction; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** postImage/imageUrls -> ordered assets dedup URL. Asset không reuse ở bài khác, shares chỉ reference post gốc. RPC validates purpose post, owner,ready; one transaction attach+post content invariant.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** postImage/imageUrls -> ordered assets dedup URL. Asset không reuse ở bài khác, shares chỉ reference post gốc. RPC validates purpose post, owner,ready; one transaction attach+post content invariant.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.post_media` trong SCHEMA_DRAFT, `post_media_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 27. message_media

1. **Mục đích/lý do tồn tại:** Attachment ảnh/video/file với thứ tự.
2. **Nhóm nghiệp vụ:** Media.
3. **Trạng thái:** CURRENT; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `message_id` | `uuid` | `NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE` |
| `asset_id` | `uuid` | `NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT` |
| `position` | `smallint` | `NOT NULL CHECK (position BETWEEN 0 AND 9)` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (message_id, asset_id)`
- `UNIQUE (message_id, position)`
- `UNIQUE (asset_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `message_id uuid NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE`
- `asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
private.can_read_message(message_id) AND EXISTS (SELECT 1 FROM public.messages m WHERE m.id=message_id AND m.state='sent')
```

9. **Quyền INSERT/UPDATE/DELETE:** send_message gắn assets; recall disables registry, cleanup worker delete link sau retention. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; send_message gắn assets; recall disables registry, cleanup worker delete link sau retention; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** imageUrl -> asset row; video/file FUTURE cùng schema, whitelist policy theo phase. RPC validates purpose message, ownership,ready; recall -> asset pending_delete and bytes cleanup.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** imageUrl -> asset row; video/file FUTURE cùng schema, whitelist policy theo phase. RPC validates purpose message, ownership,ready; recall -> asset pending_delete and bytes cleanup.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.message_media` trong SCHEMA_DRAFT, `message_media_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 28. notifications

1. **Mục đích/lý do tồn tại:** Thông báo bền vững theo recipient; không payload nội dung riêng.
2. **Nhóm nghiệp vụ:** Thông báo.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `recipient_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `actor_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `event_key` | `text` | `NOT NULL` |
| `kind` | `text` | `NOT NULL CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation'))` |
| `target_type` | `text` | `NOT NULL CHECK (target_type IN ('profile','post','comment','story','group','conversation','report'))` |
| `target_id` | `uuid` | `NOT NULL` |
| `read_at` | `timestamptz` | `nullable; default NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (recipient_id, event_key)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `recipient_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

7. **Indexes:** 
- `CREATE INDEX notifications_cursor_idx ON public.notifications (recipient_id,created_at DESC,id DESC);`
- `CREATE INDEX notifications_unread_idx ON public.notifications (recipient_id,created_at DESC) WHERE read_at IS NULL;`
- `CREATE INDEX notifications_actor_idx ON public.notifications (actor_id);`

8. **Quyền đọc:** 
```sql
private.is_active() AND recipient_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** outbox worker create; mark_notifications_read owner; retention worker delete. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; outbox worker create; mark_notifications_read owner; retention worker delete; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Không source collection. Target polymorphic cố ý không FK 7 bảng; event writer verifies target/type; deleted targets stale -> navigation404. Không body/URL/snapshot; event_key stable(action UUID,eventType,recipient).
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Không source collection. Target polymorphic cố ý không FK 7 bảng; event writer verifies target/type; deleted targets stale -> navigation404. Không body/URL/snapshot; event_key stable(action UUID,eventType,recipient).
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.notifications` trong SCHEMA_DRAFT, `notifications_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 29. device_subscriptions

1. **Mục đích/lý do tồn tại:** Endpoint thiết bị cho push không Firebase.
2. **Nhóm nghiệp vụ:** Thông báo.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `installation_id` | `uuid` | `NOT NULL` |
| `provider` | `text` | `NOT NULL CHECK (provider IN ('unifiedpush','oem'))` |
| `endpoint` | `text` | `NOT NULL CHECK (endpoint LIKE 'https://%')` |
| `state` | `text` | `NOT NULL DEFAULT 'active' CHECK (state IN ('active','revoked'))` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `last_seen_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (user_id, installation_id)`
- `UNIQUE (endpoint)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`

7. **Indexes:** 
- `CREATE INDEX device_subscriptions_user_idx ON public.device_subscriptions (user_id,state);`

8. **Quyền đọc:** 
```sql
private.is_active() AND user_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** Edge register_device / revoke_device; endpoint không trả raw. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; Edge register_device / revoke_device; endpoint không trả raw; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Endpoint capability là bí mật, không grant raw SELECT Android. Edge registration verifies endpoint/provider HTTPS/allowlist/public IP + DNS recheck/redirect deny chống SSRF. Thu hồi khi logout, owner-auth bind. Không FCM token.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Endpoint capability là bí mật, không grant raw SELECT Android. Edge registration verifies endpoint/provider HTTPS/allowlist/public IP + DNS recheck/redirect deny chống SSRF. Thu hồi khi logout, owner-auth bind. Không FCM token.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.device_subscriptions` trong SCHEMA_DRAFT, `device_subscriptions_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 30. event_outbox

1. **Mục đích/lý do tồn tại:** Outbox transaction cho event/cleanup/push retry, không pipeline phân tán.
2. **Nhóm nghiệp vụ:** Hạ tầng.
3. **Trạng thái:** REQUIRED; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `event_key` | `text` | `NOT NULL UNIQUE` |
| `event_type` | `text` | `NOT NULL` |
| `aggregate_type` | `text` | `NOT NULL` |
| `aggregate_id` | `uuid` | `nullable; default NULL` |
| `payload` | `jsonb` | `NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object')` |
| `state` | `text` | `NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','processing','done','dead'))` |
| `attempts` | `integer` | `NOT NULL DEFAULT 0 CHECK (attempts >= 0)` |
| `available_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `lease_until` | `timestamptz` | `nullable; default NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `processed_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** PK/UNIQUE/CHECK trên cột xem declaration bên trên; các CHECK cấp bảng nếu có đã liệt kê ngay trước mục này.

6. **Foreign keys / hành vi xóa / quan hệ:** Không FK do target polymorphic/server job; xem ghi chú migration và integrity.

7. **Indexes:** 
- `CREATE INDEX event_outbox_due_idx ON public.event_outbox (state,available_at,created_at);`

8. **Quyền đọc:** Không SELECT Android, server/operator-only.

9. **Quyền INSERT/UPDATE/DELETE:** domain transaction INSERT; worker claim/lease/process/retry/purge. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; domain transaction INSERT; worker claim/lease/process/retry/purge; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Server only, ghi cùng tx nghiệp vụ; worker SKIP LOCKED lease/backoff/idempotent recipient event keys. Payload IDs tối thiểu, không message plaintext/token; push external at-least-once, notification IDs dedup phía device.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** Server only, ghi cùng tx nghiệp vụ; worker SKIP LOCKED lease/backoff/idempotent recipient event keys. Payload IDs tối thiểu, không message plaintext/token; push external at-least-once, notification IDs dedup phía device.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.event_outbox` trong SCHEMA_DRAFT, `server-only revoke` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 31. system_roles

1. **Mục đích/lý do tồn tại:** Vai trò hệ thống do server gán, khác group roles.
2. **Nhóm nghiệp vụ:** Kiểm duyệt.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `user_id` | `uuid` | `NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `role` | `text` | `NOT NULL CHECK (role IN ('system_admin','moderator'))` |
| `granted_by` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (user_id, role)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE`
- `granted_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

7. **Indexes:** PK/UNIQUE indexes là đủ cho access path MVP của bảng này; chưa thêm index không có query cụ thể.

8. **Quyền đọc:** 
```sql
private.is_active() AND user_id=(select auth.uid())
```

9. **Quyền INSERT/UPDATE/DELETE:** server-only grant_system_role/revoke_system_role có approval/audit. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; server-only grant_system_role/revoke_system_role có approval/audit; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Không dựa user_metadata/role Android. Bootstrapping admin vận hành thủ công được phê duyệt; chưa tạo account admin. system_admin không auto đọc private conversations/profile_private.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Bảng quan hệ/preference: hard delete khi bỏ quan hệ hoặc profile/parent cascade; worker theo retention đề xuất. Không dùng soft delete vô nghĩa cho một preference.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Không dựa user_metadata/role Android. Bootstrapping admin vận hành thủ công được phê duyệt; chưa tạo account admin. system_admin không auto đọc private conversations/profile_private.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.system_roles` trong SCHEMA_DRAFT, `system_roles_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 32. reports

1. **Mục đích/lý do tồn tại:** Báo cáo có typed target FK, assignment và state.
2. **Nhóm nghiệp vụ:** Kiểm duyệt.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `reporter_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `target_kind` | `text` | `NOT NULL CHECK (target_kind IN ('post','comment','story','message','profile','group'))` |
| `post_id` | `uuid` | `REFERENCES public.posts(id) ON DELETE SET NULL` |
| `comment_id` | `uuid` | `REFERENCES public.comments(id) ON DELETE SET NULL` |
| `story_id` | `uuid` | `REFERENCES public.stories(id) ON DELETE SET NULL` |
| `message_id` | `uuid` | `REFERENCES public.messages(id) ON DELETE SET NULL` |
| `profile_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `group_id` | `uuid` | `REFERENCES public.groups(id) ON DELETE SET NULL` |
| `reason_code` | `text` | `NOT NULL CHECK (char_length(reason_code) BETWEEN 1 AND 50)` |
| `details` | `text` | `NOT NULL DEFAULT '' CHECK (char_length(details) <= 3000)` |
| `state` | `text` | `NOT NULL DEFAULT 'open' CHECK (state IN ('open','assigned','resolved','dismissed'))` |
| `assigned_to` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `resolved_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (reporter_id, client_request_id)`
- `CHECK (num_nonnulls(post_id,comment_id,story_id,message_id,profile_id,group_id) <= 1)`
- `CHECK ((post_id IS NULL OR target_kind='post') AND (comment_id IS NULL OR target_kind='comment') AND (story_id IS NULL OR target_kind='story') AND (message_id IS NULL OR target_kind='message') AND (profile_id IS NULL OR target_kind='profile') AND (group_id IS NULL OR target_kind='group'))`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `reporter_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `post_id uuid REFERENCES public.posts(id) ON DELETE SET NULL`
- `comment_id uuid REFERENCES public.comments(id) ON DELETE SET NULL`
- `story_id uuid REFERENCES public.stories(id) ON DELETE SET NULL`
- `message_id uuid REFERENCES public.messages(id) ON DELETE SET NULL`
- `profile_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `group_id uuid REFERENCES public.groups(id) ON DELETE SET NULL`
- `assigned_to uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

7. **Indexes:** 
- `CREATE INDEX reports_assignment_idx ON public.reports (assigned_to,state,created_at,id);`
- `CREATE INDEX reports_reporter_idx ON public.reports (reporter_id,created_at DESC,id DESC);`
- `CREATE INDEX reports_post_idx ON public.reports (post_id) WHERE post_id IS NOT NULL;`
- `CREATE INDEX reports_message_idx ON public.reports (message_id) WHERE message_id IS NOT NULL;`

8. **Quyền đọc:** 
```sql
private.can_review_report(id)
```

9. **Quyền INSERT/UPDATE/DELETE:** submit_report / assign_report / resolve_report; reporter receipt RPC redacted. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; submit_report / assign_report / resolve_report; reporter receipt RPC redacted; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** RPC yêu cầu đúng1 target phù hợp target_kind lúc create; FK set null cho phép giữ lịch sử tối thiểu sau purge. Reporter chỉ nhận receipt qua RPC, không SELECT assignment/details toàn bộ case; moderator chỉ assigned.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Giữ metadata case tối thiểu; typed FK có thể null sau purge target; hard retention job không giữ raw evidence tùy tiện.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** RPC yêu cầu đúng1 target phù hợp target_kind lúc create; FK set null cho phép giữ lịch sử tối thiểu sau purge. Reporter chỉ nhận receipt qua RPC, không SELECT assignment/details toàn bộ case; moderator chỉ assigned.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.reports` trong SCHEMA_DRAFT, `reports_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 33. moderation_actions

1. **Mục đích/lý do tồn tại:** Hành động xử lý gắn case, actor và lý do.
2. **Nhóm nghiệp vụ:** Kiểm duyệt.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `report_id` | `uuid` | `NOT NULL REFERENCES public.reports(id) ON DELETE RESTRICT` |
| `actor_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `uuid` | `NOT NULL` |
| `action` | `text` | `NOT NULL CHECK (action IN ('hide','remove','restore','warn','suspend','unsuspend','dismiss'))` |
| `reason` | `text` | `NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 3000)` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (actor_id, client_request_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `report_id uuid NOT NULL REFERENCES public.reports(id) ON DELETE RESTRICT`
- `actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

7. **Indexes:** 
- `CREATE INDEX moderation_actions_case_idx ON public.moderation_actions (report_id,created_at,id);`

8. **Quyền đọc:** 
```sql
private.can_review_report(report_id)
```

9. **Quyền INSERT/UPDATE/DELETE:** apply_moderation cùng report/target/audit/outbox transaction. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; apply_moderation cùng report/target/audit/outbox transaction; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** RPC apply_moderation checks role,assignment,scope,target current state and audit same tx. Warn là row/action + notification, không thêm bảng warnings khi chưa cần escalation.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Append-only, case FK RESTRICT; sanitize/purge case bundle theo retention được duyệt.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** RPC apply_moderation checks role,assignment,scope,target current state and audit same tx. Warn là row/action + notification, không thêm bảng warnings khi chưa cần escalation.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.moderation_actions` trong SCHEMA_DRAFT, `moderation_actions_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 34. moderation_audit

1. **Mục đích/lý do tồn tại:** Append-only audit tối thiểu cho role/case/admin.
2. **Nhóm nghiệp vụ:** Kiểm duyệt.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `actor_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `action_id` | `uuid` | `REFERENCES public.moderation_actions(id) ON DELETE SET NULL` |
| `report_id` | `uuid` | `REFERENCES public.reports(id) ON DELETE SET NULL` |
| `operation` | `text` | `NOT NULL` |
| `subject_type` | `text` | `NOT NULL` |
| `subject_id` | `uuid` | `nullable; default NULL` |
| `safe_metadata` | `jsonb` | `NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(safe_metadata) = 'object')` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** PK/UNIQUE/CHECK trên cột xem declaration bên trên; các CHECK cấp bảng nếu có đã liệt kê ngay trước mục này.

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `action_id uuid REFERENCES public.moderation_actions(id) ON DELETE SET NULL`
- `report_id uuid REFERENCES public.reports(id) ON DELETE SET NULL`

7. **Indexes:** 
- `CREATE INDEX moderation_audit_case_idx ON public.moderation_audit (report_id,created_at,id);`
- `CREATE INDEX moderation_audit_actor_idx ON public.moderation_audit (actor_id,created_at,id);`

8. **Quyền đọc:** 
```sql
private.has_role('system_admin') OR (report_id IS NOT NULL AND private.can_review_report(report_id))
```

9. **Quyền INSERT/UPDATE/DELETE:** append server event; purge retention authorized, không client CRUD. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; append server event; purge retention authorized, không client CRUD; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Không PII/body/token trong metadata; ghi bằng transaction/server, không client update/delete. Retention mặc định180 ngày proposal, có purge job được duyệt.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Append-only đối với app/admin API; privileged retention job180 ngày proposal.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Không PII/body/token trong metadata; ghi bằng transaction/server, không client update/delete. Retention mặc định180 ngày proposal, có purge job được duyệt.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.moderation_audit` trong SCHEMA_DRAFT, `moderation_audit_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 35. appeals

1. **Mục đích/lý do tồn tại:** Kháng nghị một action và trạng thái review.
2. **Nhóm nghiệp vụ:** Kiểm duyệt.
3. **Trạng thái:** FUTURE; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `id` | `uuid` | `PRIMARY KEY DEFAULT gen_random_uuid()` |
| `action_id` | `uuid` | `NOT NULL REFERENCES public.moderation_actions(id) ON DELETE RESTRICT` |
| `appellant_id` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `body` | `text` | `NOT NULL CHECK (char_length(body) BETWEEN 1 AND 3000)` |
| `state` | `text` | `NOT NULL DEFAULT 'open' CHECK (state IN ('open','reviewing','accepted','rejected'))` |
| `assigned_to` | `uuid` | `REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |
| `resolved_at` | `timestamptz` | `nullable; default NULL` |

5. **Primary key / Unique / Check cấp bảng:** 
- `UNIQUE (action_id, appellant_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** 
- `action_id uuid NOT NULL REFERENCES public.moderation_actions(id) ON DELETE RESTRICT`
- `appellant_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL`
- `assigned_to uuid REFERENCES public.profiles(id) ON DELETE SET NULL`

7. **Indexes:** 
- `CREATE INDEX appeals_assignment_idx ON public.appeals (assigned_to,state,created_at,id);`

8. **Quyền đọc:** 
```sql
private.is_active() AND (appellant_id=(select auth.uid()) OR private.has_role('system_admin') OR (private.has_role('moderator') AND assigned_to=(select auth.uid())))
```

9. **Quyền INSERT/UPDATE/DELETE:** submit_appeal / assign_appeal / resolve_appeal. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; submit_appeal / assign_appeal / resolve_appeal; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** Chưa source; mở khi core moderation ổn định; RPC verifies appellant affected by action; reviewer khác actor ban đầu nếu vận hành cho phép. Locked user dùng Edge support path giới hạn, không mở toàn DB.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Giữ qua review/retention rồi purge; action FK RESTRICT nên purge theo thứ tự.
15. **Khả năng mở rộng:** Bật feature/grants/RPC riêng theo roadmap; giữ quan hệ chuẩn hóa để không phá các entity cốt lõi.
16. **Migration từ Firebase:** Chưa source; mở khi core moderation ổn định; RPC verifies appellant affected by action; reviewer khác actor ban đầu nếu vận hành cho phép. Locked user dùng Edge support path giới hạn, không mở toàn DB.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.appeals` trong SCHEMA_DRAFT, `appeals_select` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.

## 36. migration_id_map

1. **Mục đích/lý do tồn tại:** Mapping UID/document ID cũ, chỉ staging migration/server.
2. **Nhóm nghiệp vụ:** Migration.
3. **Trạng thái:** REQUIRED; chưa có bảng được xác minh triển khai.
4. **Cột và kiểu/default/ràng buộc:**

| Cột | Kiểu PostgreSQL | Nullability/default/check/FK khai báo |
|---|---|---|
| `source_system` | `text` | `NOT NULL DEFAULT 'firebase'` |
| `entity_type` | `text` | `NOT NULL CHECK (entity_type IN ('user','post','comment','conversation','message','media'))` |
| `legacy_id` | `text` | `NOT NULL` |
| `new_id` | `uuid` | `NOT NULL` |
| `batch_id` | `uuid` | `NOT NULL` |
| `source_checksum` | `text` | `nullable; default NULL` |
| `migrated_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

5. **Primary key / Unique / Check cấp bảng:** 
- `PRIMARY KEY (source_system, entity_type, legacy_id)`

6. **Foreign keys / hành vi xóa / quan hệ:** Không FK do target polymorphic/server job; xem ghi chú migration và integrity.

7. **Indexes:** 
- `CREATE INDEX migration_id_map_new_idx ON public.migration_id_map (entity_type,new_id);`
- `CREATE INDEX migration_id_map_batch_idx ON public.migration_id_map (batch_id,entity_type);`

8. **Quyền đọc:** Không SELECT Android, server/operator-only.

9. **Quyền INSERT/UPDATE/DELETE:** migration operator INSERT/UPSERT trong staging, verify, purge sau rollback window. Không có quyền tự ghi trường nhạy cảm/cross-user; xem ma trận RLS theo bảng.
10. **RLS bắt buộc:** ENABLE RLS, anon deny, role authenticated có active/owner/parent ACL ở predicate trên; không bypass theo UI. posts/comments/messages.request_hash và device_subscriptions.endpoint bị loại khỏi column SELECT grant. role admin không là blanket private-data read.
11. **Ví dụ CRUD/luồng:** client lấy row được SELECT cho phép; migration operator INSERT/UPSERT trong staging, verify, purge sau rollback window; transaction callback trả ID/version; Android refetch projection có RLS. Không được tự thay RPC bằng nhiều request REST để giả lập transaction.
12. **Constraints cross-row còn phải enforce:** new_id polymorphic không FK; reconciliation verifies table per entity type. Chỉ bảng vận hành tạm, không runtime Android; giữ qua rollback window rồi purge. Legacy nested IDs dùng full path để không collision. Many source conversation IDs có thể merge vào1 destination pair.
13. **Default/timestamp:** các default là declaration cột, không timezone thiết bị; input timestamps không tin cậy. generated pair columns do PostgreSQL tính; client không ghi.
14. **Soft/hard delete và retention:** Hard purge sau cutover/rollback window; không nằm public export.
15. **Khả năng mở rộng:** Giữ UUID/parent FK và API projection; thêm feature không đổi identity hiện hành; chỉ partition/counter async sau khi đo tải.
16. **Migration từ Firebase:** new_id polymorphic không FK; reconciliation verifies table per entity type. Chỉ bảng vận hành tạm, không runtime Android; giữ qua rollback window rồi purge. Legacy nested IDs dùng full path để không collision. Many source conversation IDs có thể merge vào1 destination pair.
17. **Kiểm thử bắt buộc:** insert vi phạm PK/FK/UNIQUE/CHECK phải fail; allow/deny theo predicate; retry cùng action không nhân bản; race/parent lifecycle tương ứng18 tình huống DESIGN_REVIEW. Chưa thực thi các test này.
18. **Đối chiếu SQL:** `CREATE TABLE public.migration_id_map` trong SCHEMA_DRAFT, `server-only revoke` trong RLS_DRAFT. Không cho rằng draft SELECT policy đủ thực thi các RPC chưa được viết.



## Bổ sung kiểm chứng nhiệm vụ04
- Giữ nguyên36 bảng/267 cột. Stories.media_asset_id có UNIQUE để tránh reuse asset rồi cleanup một Story làm hỏng Story khác.
- media_assets read helper kiểm purpose/owner đúng từng liên kết profile/post/story/message; group-cover phải purpose=group, uploader không buộc trùng creator nhóm. Liên kết sai dù do importer/operator tạo cũng không trở thành đường đọc media private.
- Default EXECUTE: `ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC` cho migration owner hiện tại. REVOKE per-schema không gỡ global default; mọi owner khác phải được cấu hình/check riêng.21 helpers hiện hữu vẫn explicit EXECUTE authenticated và anon denied.
- Catalog PostgreSQL local hiện có79 FK,104 CHECK và110 indexes tổng cộng (50 explicit +60 implicit PK/UNIQUE).24 FK cần review index-prefix/partial theo query thực, không coi24 là24 index chắc chắn thiếu.

## Ghi chú implementation Phase A — nhiệm vụ 05
36 bảng/cột/FK/CHECK giữ nguyên. Migrations thực tế nằm supabase/migrations, khác SQL draft lịch sử có guard.
- profiles read: migration 003 siết target của người khác phải onboarding_completed, tránh lộ placeholder signup; self active còn incomplete vẫn đọc để resume. Draft nguyên bản giữ lịch sử, implementation predicate mới là authority của Phase A.
- profiles/private mutation: 8 public RPC đã viết, self-only/versioned, validate tên/ngày sinh đủ13 tuổi và gender; bootstrap/recovery4 row, verified email gate riêng trước complete, không trust metadata role/status.
- profile_private: raw SELECT vẫn active self; get_my_profile RPC hỗ trợ incomplete self, locked/deleting chỉ status envelope. Contact/credentials ở Auth.
- user_settings: Phase A cho owner PATCH các cột preference theo grants/RLS/CHECK; timestamp server.
- user_blocks: set_block transaction thực, cancel pending/remove friendship/follows; unblock không restore.
- account_status: chỉ owner đọc trạng thái; suspended/deleting deny, không self-unlock API; deletion request/worker chờ retention/reauth approved.
- media_assets: ready owned avatar/cover attach validation và retire outbox đã viết; upload/finalize/cleanup bytes/Storage policies chưa bật.
- FUTURE và B/C scaffold chưa có app table/column grants hoặc mutation APIs. Full helper graph có đủ anchors nhưng không kích hoạt feature.
Trạng thái: CODED + PostgreSQL16.11 LOCAL TESTED (stubs); chưa Supabase JWT/service/staging. Xem MEETO_PHASE_A_IMPLEMENTATION.md và validation_phase_a/.