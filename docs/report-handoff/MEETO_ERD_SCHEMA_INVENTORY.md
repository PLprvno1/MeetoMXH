# MEETO — Danh mục schema/PK/FK và dữ liệu dựng ERD

Khảo sát tĩnh ngày08/11/2026, Asia/Bangkok. Chỉ là bằng chứng DDL trong repository; chưa có schema export/migration ledger Cloud. Không chạy SQL, không tạo database hay sơ đồ. Các số dưới đây không phải số bảng/RPC đã deploy.

## Cách sử dụng

- Có58 tên bảng CREATE TABLE tường minh:52 public và6 private. Không tìm thấy DROP TABLE tường minh trong tập migration đã rà. Bảng legacy vẫn được kiểm kê để không mất quan hệ vật lý.
- Mỗi mục cung cấp purpose/group, định nghĩa cột (kèm kiểu/null/default/PK/FK/constraints), constraints của CREATE, unique indexes và ledger ALTER theo filename. **CREATE ban đầu không được coi là schema cuối**: xem các ALTER/override, đặc biệt audience, voice, music, Reels, album, account privacy. Lệnh điều kiện/DO/dynamic grants cần đối chiếu nguyên file; không giả chúng đã chạy trên Cloud.
- Bảng quan hệ ở cuối chỉ gồm REFERENCES/FK tường minh. Không nối FK từ tên `*_id` khi DDL không khai báo. Cặp composite FK giữ nguyên tuple, không tách thành hai quan hệ sai.
- Parent có thể có0..N child; nếu toàn bộ tuple FK là PK/UNIQUE của child thì tối đa1 child. Child→parent tối đa1; tối thiểu0 nếu nullable,1 nếu NOT NULL/PK. Không suy mỗi auth.users bắt buộc có profile từ app bootstrap thành total participation ở DB.
- SECURITY DEFINER RPC+ACL quản writes trên nhiều bảng; RLS ENABLE không tự tạo quyền đọc. Policies/ALTER ở từng mục là lịch sử khai báo, có thể bị thay/drop về sau. Private operational tables không public API.

## Các thay đổi cuối quan trọng phải áp dụng khi vẽ schema source

| Bảng | Bổ sung/quy tắc hiện tại có source |
|---|---|
| profiles/profile_private/settings | public_avatar_preset; username unique (3–30); current_location/location_audience; birth_date_audience/gender_audience; account_private. Cột username hiển thị là Biệt danh. |
| user_follows/follow_requests | Follow confirmed riêng với request private. Friendships vẫn tồn tại nhưng retired. |
| posts | is_reel/reel_duration_ms/reel_cover_asset_id/views projection; music_metadata JSONB+hash; mention_request_hash; states pending_review/rejected được thêm từ Community Groups lịch sử. |
| comments | mention_request_hash, parent tuple cùng post; root/display-depth của tree projection không mặc nhiên là cột vật lý. |
| stories | music_metadata JSONB+hash; expiry24h, audience followers/private/public theo late migration. |
| conversations/members/messages | direct_consent/owner/avatar/theme; archived_at/nickname per member; forwarded/shared_post_id/is_shared_post; voice schema có nhưng entry UI disabled. |
| media_assets | File type/size/purpose/metadata được ALTER;070001 bỏ per-account quotas, không xóa limits file/bucket/validation. |
| profile_albums | is_featured boolean từ080005, nhiều ảnh trong items;080009 chỉ thêm size_bytes vào JSON projections, **không thêm cột size_bytes vào album_items**. |
| user_settings | tagging_policy DEFAULT everyone từ080008 cho row mới; không UPDATE quyền đã lưu. tag_review_required giữ nguyên. |
| music_catalog_tracks | PK(track_id,catalog_version), catalog/version/hash, active. Không có FK từ JSON music_metadata sang bảng này; binder/validator kiểm logic. |

## Danh mục đầy đủ theo bảng

### public.profiles

- Nhóm: Auth/Profile; chức năng: Hồ sơ công khai và biệt danh.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:28`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE` |
| `display_name` | `display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100)` |
| `bio` | `bio text NOT NULL DEFAULT '' CHECK (char_length(bio) <= 2000)` |
| `avatar_asset_id` | `avatar_asset_id uuid` |
| `cover_asset_id` | `cover_asset_id uuid` |
| `onboarding_completed` | `onboarding_completed boolean NOT NULL DEFAULT false` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |
| `version` | `version bigint NOT NULL DEFAULT 1 CHECK (version > 0)` |

Unique indexes ngoài CREATE (kể cả partial):
- `CREATE UNIQUE INDEX profiles_username_unique ON public.profiles(username) WHERE username IS NOT NULL;` — `supabase/migrations/20261106000100_follow_only_privacy.sql:14`

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:559`
```sql
ALTER TABLE public.profiles ADD CONSTRAINT profiles_avatar_fk FOREIGN KEY (avatar_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;
```

`supabase/migrations/20261101000100_foundation.sql:560`
```sql
ALTER TABLE public.profiles ADD CONSTRAINT profiles_cover_fk FOREIGN KEY (cover_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;
```

`supabase/migrations/20261101000100_foundation.sql:568`
```sql
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:182`
```sql
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103000200_stories_avatar_preset.sql:81`
```sql
ALTER TABLE public.profiles
  ADD COLUMN public_avatar_preset text NOT NULL DEFAULT 'neutral'
  CHECK (public_avatar_preset IN ('neutral','male','female'));
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:13`
```sql
ALTER TABLE public.profiles ADD COLUMN username text CHECK(username IS NULL OR username ~ '^[a-z0-9][a-z0-9._]{2,29}$');
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY profiles_select ON public.profiles FOR SELECT TO authenticated USING (private.can_read_profile(id));` — `supabase/migrations/20261101000200_authorization.sql:185`

### public.profile_private

- Nhóm: Auth/Profile; chức năng: Ngày sinh/giới tính/nơi ở theo quyền.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:41`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_id` | `user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `birth_date` | `birth_date date CHECK (birth_date >= DATE '1900-01-01')` |
| `gender` | `gender text CHECK (char_length(gender) <= 100)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:570`
```sql
ALTER TABLE public.profile_private ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:188`
```sql
ALTER TABLE public.profile_private ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103000300_saved_follows_profile_location.sql:25`
```sql
ALTER TABLE public.profile_private
  ADD COLUMN current_location text,
  ADD COLUMN location_audience text NOT NULL DEFAULT 'private'
    CHECK (location_audience IN ('public','friends','private'));
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:95`
```sql
ALTER TABLE public.profile_private DROP CONSTRAINT profile_private_location_audience_check;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:96`
```sql
ALTER TABLE public.profile_private ADD CONSTRAINT profile_private_location_audience_check CHECK(location_audience IN ('public','followers','private','friends'));
```

`supabase/migrations/20261106000500_profile_field_visibility.sql:12`
```sql
ALTER TABLE public.profile_private
  ADD COLUMN birth_date_audience text NOT NULL DEFAULT 'private'
    CHECK (birth_date_audience IN ('public','followers','private','friends')),
  ADD COLUMN gender_audience text NOT NULL DEFAULT 'private'
    CHECK (gender_audience IN ('public','followers','private','friends'));
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY profile_private_select ON public.profile_private FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:191`

### public.user_settings

- Nhóm: Auth/Profile; chức năng: Quyền riêng tư, tags và notifications.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:50`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_id` | `user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `profile_audience` | `profile_audience text NOT NULL DEFAULT 'public' CHECK (profile_audience IN ('public','friends','private'))` |
| `last_seen_audience` | `last_seen_audience text NOT NULL DEFAULT 'friends' CHECK (last_seen_audience IN ('public','friends','private'))` |
| `notifications_enabled` | `notifications_enabled boolean NOT NULL DEFAULT true` |
| `notification_preferences` | `notification_preferences jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(notification_preferences) = 'object')` |
| `CONSTRAINT` | `CONSTRAINT user_settings_prefs_size CHECK (octet_length(notification_preferences::text) <= 4096)` |
| `CONSTRAINT` | `CONSTRAINT user_settings_prefs_keys CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation']) = '{}'::jsonb)` |
| `CONSTRAINT` | `CONSTRAINT user_settings_prefs_boolean CHECK (NOT jsonb_path_exists(notification_preferences, '$.* ? (@.type() != "boolean")'))` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:572`
```sql
ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:194`
```sql
ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103000400_notifications_center.sql:48`
```sql
ALTER TABLE public.user_settings DROP CONSTRAINT IF EXISTS user_settings_prefs_keys;
```

`supabase/migrations/20261103000400_notifications_center.sql:49`
```sql
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys
  CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow']) = '{}'::jsonb);
```

`supabase/migrations/20261103000900_memories.sql:35`
```sql
ALTER TABLE public.user_settings
  ADD COLUMN IF NOT EXISTS memories_enabled boolean NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS memory_reminders_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS memories_timezone text NOT NULL DEFAULT 'UTC';
```

`supabase/migrations/20261103000900_memories.sql:43`
```sql
ALTER TABLE public.user_settings DROP CONSTRAINT IF EXISTS user_settings_prefs_keys;
```

`supabase/migrations/20261103000900_memories.sql:44`
```sql
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys
  CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','memories']) = '{}'::jsonb);
```

`supabase/migrations/20261103001200_tag_mentions.sql:37`
```sql
ALTER TABLE public.user_settings
  ADD COLUMN tagging_policy text NOT NULL DEFAULT 'friends'
    CHECK (tagging_policy IN ('everyone','friends','nobody')),
  ADD COLUMN tag_review_required boolean NOT NULL DEFAULT true;
```

`supabase/migrations/20261103001200_tag_mentions.sql:49`
```sql
ALTER TABLE public.user_settings DROP CONSTRAINT IF EXISTS user_settings_prefs_keys;
```

`supabase/migrations/20261103001200_tag_mentions.sql:50`
```sql
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys
  CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply',
    'share','story','group','message','moderation','follow','memories','mention','tag']) = '{}'::jsonb);
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:15`
```sql
ALTER TABLE public.user_settings ADD COLUMN account_private boolean NOT NULL DEFAULT false;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:93`
```sql
ALTER TABLE public.user_settings DROP CONSTRAINT user_settings_last_seen_audience_check;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:94`
```sql
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_last_seen_audience_check CHECK(last_seen_audience IN ('public','followers','private','friends'));
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:578`
```sql
ALTER TABLE public.user_settings DROP CONSTRAINT user_settings_tagging_policy_check;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:579`
```sql
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_tagging_policy_check CHECK(tagging_policy IN ('everyone','followers','nobody','friends'));
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:581`
```sql
ALTER TABLE public.user_settings ALTER COLUMN tagging_policy SET DEFAULT 'followers';
```

`supabase/migrations/20261106000300_notifications_moderation.sql:21`
```sql
ALTER TABLE public.user_settings DROP CONSTRAINT user_settings_prefs_keys;
```

`supabase/migrations/20261106000300_notifications_moderation.sql:22`
```sql
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys CHECK((notification_preferences-ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','mention','tag','follow_request','follow_accepted','message_request','group_invite'])='{}'::jsonb);
```

`supabase/migrations/20261108000800_default_tagging_everyone.sql:4`
```sql
ALTER TABLE public.user_settings ALTER COLUMN tagging_policy SET DEFAULT 'everyone';
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY user_settings_select ON public.user_settings FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:197`
- `CREATE POLICY user_settings_update ON public.user_settings FOR UPDATE TO authenticated USING (private.is_active() AND user_id=auth.uid()) WITH CHECK (private.is_active() AND user_id=auth.uid());` — `supabase/migrations/20261101000200_authorization.sql:397`

### public.account_status

- Nhóm: Auth/Profile; chức năng: Active/suspended/deleting và request deletion.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:63`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_id` | `user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `state` | `state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','suspended','deleting'))` |
| `suspended_until` | `suspended_until timestamptz` |
| `deletion_requested_at` | `deletion_requested_at timestamptz` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:574`
```sql
ALTER TABLE public.account_status ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:200`
```sql
ALTER TABLE public.account_status ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY account_status_select ON public.account_status FOR SELECT TO authenticated USING (user_id=(select auth.uid()) AND auth.uid() IS NOT NULL);` — `supabase/migrations/20261101000200_authorization.sql:203`

### public.user_blocks

- Nhóm: Follow; chức năng: Chặn có hướng, hiệu lực xét hai chiều.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:72`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `blocker_id` | `blocker_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `blocked_id` | `blocked_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (blocker_id, blocked_id)` |
| `CHECK` | `CHECK (blocker_id <> blocked_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:576`
```sql
ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:206`
```sql
ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY user_blocks_select ON public.user_blocks FOR SELECT TO authenticated USING (private.is_active() AND blocker_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:209`

### public.user_follows

- Nhóm: Follow; chức năng: Quan hệ follow đã có hiệu lực.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:81`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `follower_id` | `follower_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `followed_id` | `followed_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (follower_id, followed_id)` |
| `CHECK` | `CHECK (follower_id <> followed_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:578`
```sql
ALTER TABLE public.user_follows ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:212`
```sql
ALTER TABLE public.user_follows ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY user_follows_select ON public.user_follows FOR SELECT TO authenticated USING (private.is_active() AND (follower_id=(select auth.uid()) OR followed_id=(select auth.uid())) AND NOT private.is_blocked(CASE WHEN follower_id=auth.uid() THEN followed_id ELSE follower_id END));` — `supabase/migrations/20261101000200_authorization.sql:215`

### public.friend_requests

- Nhóm: Legacy; chức năng: Lời mời friendship lịch sử, retired.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:90`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `requester_id` | `requester_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `recipient_id` | `recipient_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `pair_low` | `pair_low uuid GENERATED ALWAYS AS (least(requester_id, recipient_id)) STORED` |
| `pair_high` | `pair_high uuid GENERATED ALWAYS AS (greatest(requester_id, recipient_id)) STORED` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','declined','cancelled'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `resolved_at` | `resolved_at timestamptz` |
| `UNIQUE` | `UNIQUE (requester_id, client_request_id)` |
| `CHECK` | `CHECK (requester_id <> recipient_id)` |
| `CHECK` | `CHECK ((state = 'pending') = (resolved_at IS NULL))` |

Unique indexes ngoài CREATE (kể cả partial):
- `CREATE UNIQUE INDEX friend_requests_pending_pair_uq ON public.friend_requests (pair_low,pair_high) WHERE state='pending';` — `supabase/migrations/20261101000100_foundation.sql:510`

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:580`
```sql
ALTER TABLE public.friend_requests ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:218`
```sql
ALTER TABLE public.friend_requests ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY friend_requests_select ON public.friend_requests FOR SELECT TO authenticated USING (private.is_active() AND (requester_id=(select auth.uid()) OR recipient_id=(select auth.uid())));` — `supabase/migrations/20261101000200_authorization.sql:221`

### public.friendships

- Nhóm: Legacy; chức năng: Cặp bạn bè vô hướng lịch sử, retired.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:106`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_low` | `user_low uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `user_high` | `user_high uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (user_low, user_high)` |
| `CHECK` | `CHECK (user_low < user_high)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:582`
```sql
ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:224`
```sql
ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY friendships_select ON public.friendships FOR SELECT TO authenticated USING (private.is_active() AND (user_low=(select auth.uid()) OR user_high=(select auth.uid())));` — `supabase/migrations/20261101000200_authorization.sql:227`

### public.groups

- Nhóm: Legacy; chức năng: Community Groups đã loại khỏi sản phẩm.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:115`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `created_by` | `created_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `name` | `name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 150)` |
| `description` | `description text NOT NULL DEFAULT '' CHECK (char_length(description) <= 5000)` |
| `privacy` | `privacy text NOT NULL DEFAULT 'public' CHECK (privacy IN ('public','private'))` |
| `state` | `state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','archived','deleted'))` |
| `cover_asset_id` | `cover_asset_id uuid` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |
| `version` | `version bigint NOT NULL DEFAULT 1 CHECK (version > 0)` |
| `UNIQUE` | `UNIQUE (created_by, client_request_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:561`
```sql
ALTER TABLE public.groups ADD CONSTRAINT groups_cover_fk FOREIGN KEY (cover_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;
```

`supabase/migrations/20261101000100_foundation.sql:584`
```sql
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:230`
```sql
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103000500_community_groups.sql:13`
```sql
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS listed boolean NOT NULL DEFAULT true;
```

`supabase/migrations/20261103000500_community_groups.sql:15`
```sql
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS post_approval boolean NOT NULL DEFAULT false;
```

`supabase/migrations/20261103000500_community_groups.sql:16`
```sql
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS rules text NOT NULL DEFAULT '';
```

`supabase/migrations/20261103000500_community_groups.sql:17`
```sql
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS member_count integer NOT NULL DEFAULT 1;
```

`supabase/migrations/20261103000500_community_groups.sql:18`
```sql
ALTER TABLE public.groups DROP CONSTRAINT IF EXISTS groups_privacy_check;
```

`supabase/migrations/20261103000500_community_groups.sql:19`
```sql
ALTER TABLE public.groups ADD CONSTRAINT groups_privacy_check CHECK (privacy IN ('public','private','hidden'));
```

`supabase/migrations/20261103000500_community_groups.sql:24`
```sql
ALTER TABLE public.groups DROP CONSTRAINT IF EXISTS groups_rules_check;
```

`supabase/migrations/20261103000500_community_groups.sql:25`
```sql
ALTER TABLE public.groups ADD CONSTRAINT groups_rules_check CHECK (char_length(rules)<=10000 AND member_count>=0);
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY groups_select ON public.groups FOR SELECT TO authenticated USING (private.can_read_group(id));` — `supabase/migrations/20261101000200_authorization.sql:233`

### public.group_members

- Nhóm: Legacy; chức năng: Membership Community Groups, không phải chat.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:131`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `group_id` | `group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `role` | `role text NOT NULL DEFAULT 'member' CHECK (role IN ('admin','moderator','member'))` |
| `state` | `state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','left','removed','banned'))` |
| `joined_at` | `joined_at timestamptz NOT NULL DEFAULT now()` |
| `left_at` | `left_at timestamptz` |
| `PRIMARY` | `PRIMARY KEY (group_id, user_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:586`
```sql
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:236`
```sql
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103000500_community_groups.sql:20`
```sql
ALTER TABLE public.group_members DROP CONSTRAINT IF EXISTS group_members_role_check;
```

`supabase/migrations/20261103000500_community_groups.sql:21`
```sql
ALTER TABLE public.group_members ADD CONSTRAINT group_members_role_check CHECK (role IN ('owner','admin','moderator','member'));
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY group_members_select ON public.group_members FOR SELECT TO authenticated USING (private.group_member(group_id));` — `supabase/migrations/20261101000200_authorization.sql:239`

### public.group_join_requests

- Nhóm: Legacy; chức năng: Yêu cầu vào Community Groups.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:142`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `group_id` | `group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','approved','declined','cancelled'))` |
| `reviewed_by` | `reviewed_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `resolved_at` | `resolved_at timestamptz` |
| `CHECK` | `CHECK ((state = 'pending') = (resolved_at IS NULL))` |

Unique indexes ngoài CREATE (kể cả partial):
- `CREATE UNIQUE INDEX group_join_pending_uq ON public.group_join_requests (group_id,user_id) WHERE state='pending';` — `supabase/migrations/20261101000100_foundation.sql:516`

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:588`
```sql
ALTER TABLE public.group_join_requests ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:242`
```sql
ALTER TABLE public.group_join_requests ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY group_join_requests_select ON public.group_join_requests FOR SELECT TO authenticated USING (private.is_active() AND (user_id=(select auth.uid()) OR private.group_manager(group_id)));` — `supabase/migrations/20261101000200_authorization.sql:245`

### public.posts

- Nhóm: Post/Reel; chức năng: Original/share/Reels dùng chung thực thể.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:154`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `author_id` | `author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `group_id` | `group_id uuid REFERENCES public.groups(id) ON DELETE RESTRICT` |
| `kind` | `kind text NOT NULL DEFAULT 'original' CHECK (kind IN ('original','share'))` |
| `original_post_id` | `original_post_id uuid REFERENCES public.posts(id) ON DELETE RESTRICT` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `request_hash` | `request_hash text NOT NULL CHECK (char_length(request_hash) = 64)` |
| `body` | `body text NOT NULL DEFAULT '' CHECK (char_length(body) <= 20000)` |
| `audience` | `audience text NOT NULL DEFAULT 'public' CHECK (audience IN ('public','friends','private','group'))` |
| `state` | `state text NOT NULL DEFAULT 'published' CHECK (state IN ('published','hidden','deleted'))` |
| `reactions_count` | `reactions_count bigint NOT NULL DEFAULT 0 CHECK (reactions_count >= 0)` |
| `comments_count` | `comments_count bigint NOT NULL DEFAULT 0 CHECK (comments_count >= 0)` |
| `shares_count` | `shares_count bigint NOT NULL DEFAULT 0 CHECK (shares_count >= 0)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |
| `deleted_at` | `deleted_at timestamptz` |
| `version` | `version bigint NOT NULL DEFAULT 1 CHECK (version > 0)` |
| `UNIQUE` | `UNIQUE (author_id, client_request_id)` |
| `CHECK` | `CHECK ((group_id IS NULL AND audience <> 'group') OR (group_id IS NOT NULL AND audience = 'group'))` |
| `CHECK` | `CHECK ((kind = 'share' AND original_post_id IS NOT NULL AND group_id IS NULL) OR (kind = 'original' AND original_post_id IS NULL))` |
| `CHECK` | `CHECK (original_post_id IS NULL OR original_post_id <> id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:590`
```sql
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:248`
```sql
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103000500_community_groups.sql:22`
```sql
ALTER TABLE public.posts DROP CONSTRAINT IF EXISTS posts_state_check;
```

`supabase/migrations/20261103000500_community_groups.sql:23`
```sql
ALTER TABLE public.posts ADD CONSTRAINT posts_state_check CHECK (state IN ('published','pending_review','rejected','hidden','deleted'));
```

`supabase/migrations/20261103001100_advanced_share.sql:63`
```sql
ALTER TABLE public.posts DROP CONSTRAINT posts_check1;
```

`supabase/migrations/20261103001100_advanced_share.sql:64`
```sql
ALTER TABLE public.posts ADD CONSTRAINT posts_share_reference_check
  CHECK ((kind='share' AND original_post_id IS NOT NULL)
      OR (kind='original' AND original_post_id IS NULL));
```

`supabase/migrations/20261103001200_tag_mentions.sql:42`
```sql
ALTER TABLE public.posts ADD COLUMN mention_request_hash text CHECK (mention_request_hash IS NULL OR char_length(mention_request_hash)=64);
```

`supabase/migrations/20261103001600_reels_short_videos.sql:30`
```sql
ALTER TABLE public.posts
  ADD COLUMN is_reel boolean NOT NULL DEFAULT false,
  ADD COLUMN reel_duration_ms integer,
  ADD COLUMN reel_cover_position_ms integer,
  ADD COLUMN reel_cover_asset_id uuid REFERENCES public.media_assets(id) ON DELETE SET NULL,
  ADD COLUMN reel_request_hash text,
  ADD CONSTRAINT posts_reel_metadata_check CHECK (
    (NOT is_reel AND reel_duration_ms IS NULL AND reel_cover_position_ms IS NULL
      AND reel_cover_asset_id IS NULL AND reel_request_hash IS NULL)
    OR (is_reel AND kind='original' AND group_id IS NULL
      AND reel_duration_ms IS NOT NULL AND reel_cover_position_ms IS NOT NULL AND reel_request_hash IS NOT NULL
      AND reel_duration_ms BETWEEN 1 AND 60000
      AND reel_cover_position_ms BETWEEN 0 AND reel_duration_ms
      AND reel_request_hash ~ '^[0-9a-f]{64}$')
  );
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:84`
```sql
ALTER TABLE public.posts DROP CONSTRAINT posts_audience_check;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:86`
```sql
ALTER TABLE public.posts ADD CONSTRAINT posts_audience_check CHECK(audience IN ('public','followers','private','friends','group'));
```

`supabase/migrations/20261106001000_reel_publish_distribution_hotfix.sql:12`
```sql
ALTER TABLE public.posts ADD COLUMN publish_to_feed boolean NOT NULL DEFAULT true;
```

`supabase/migrations/20261106001000_reel_publish_distribution_hotfix.sql:13`
```sql
ALTER TABLE public.posts ADD CONSTRAINT posts_distribution_check CHECK(is_reel OR publish_to_feed);
```

`supabase/migrations/20261107000900_openverse_music_metadata.sql:79`
```sql
ALTER TABLE public.posts ADD COLUMN music_metadata jsonb;
```

`supabase/migrations/20261107000900_openverse_music_metadata.sql:81`
```sql
ALTER TABLE public.posts ADD COLUMN music_metadata_hash text;
```

`supabase/migrations/20261107000900_openverse_music_metadata.sql:84`
```sql
ALTER TABLE public.posts ADD CONSTRAINT posts_music_metadata_check CHECK(
 (music_metadata IS NULL AND music_metadata_hash IS NULL) OR
 (music_metadata IS NOT NULL AND music_metadata_hash IS NOT NULL AND private.valid_music_metadata(music_metadata)
  AND music_metadata_hash=encode(sha256(convert_to(music_metadata::text,'UTF8')),'hex')));
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY posts_select ON public.posts FOR SELECT TO authenticated USING (private.can_read_post(id));` — `supabase/migrations/20261101000200_authorization.sql:251`

### public.post_reactions

- Nhóm: Post; chức năng: Reaction người dùng trên bài.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:179`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `reaction` | `reaction text NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (post_id, user_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:592`
```sql
ALTER TABLE public.post_reactions ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:254`
```sql
ALTER TABLE public.post_reactions ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY post_reactions_select ON public.post_reactions FOR SELECT TO authenticated USING (private.can_read_post(post_id) AND NOT private.is_blocked(user_id));` — `supabase/migrations/20261101000200_authorization.sql:257`

### public.comments

- Nhóm: Comment; chức năng: Bình luận cây cùng post, parent vật lý.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:189`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `author_id` | `author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `parent_id` | `parent_id uuid` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `request_hash` | `request_hash text NOT NULL CHECK (char_length(request_hash) = 64)` |
| `body` | `body text CHECK (char_length(body) <= 5000)` |
| `state` | `state text NOT NULL DEFAULT 'published' CHECK (state IN ('published','hidden','deleted'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |
| `version` | `version bigint NOT NULL DEFAULT 1 CHECK (version > 0)` |
| `UNIQUE` | `UNIQUE (id, post_id)` |
| `UNIQUE` | `UNIQUE (author_id, client_request_id)` |
| `FOREIGN` | `FOREIGN KEY (parent_id, post_id) REFERENCES public.comments(id, post_id) ON DELETE RESTRICT` |
| `CHECK` | `CHECK (parent_id IS NULL OR parent_id <> id)` |
| `CHECK` | `CHECK (state <> 'published' OR (body IS NOT NULL AND char_length(btrim(body)) > 0))` |
| `CHECK` | `CHECK (state <> 'deleted' OR body IS NULL)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:594`
```sql
ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:260`
```sql
ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103001200_tag_mentions.sql:43`
```sql
ALTER TABLE public.comments ADD COLUMN mention_request_hash text CHECK (mention_request_hash IS NULL OR char_length(mention_request_hash)=64);
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY comments_select ON public.comments FOR SELECT TO authenticated USING (private.can_read_comment(id));` — `supabase/migrations/20261101000200_authorization.sql:263`

### public.comment_reactions

- Nhóm: Comment; chức năng: Reaction bình luận.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:210`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `comment_id` | `comment_id uuid NOT NULL REFERENCES public.comments(id) ON DELETE CASCADE` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `reaction` | `reaction text NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (comment_id, user_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:596`
```sql
ALTER TABLE public.comment_reactions ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:266`
```sql
ALTER TABLE public.comment_reactions ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY comment_reactions_select ON public.comment_reactions FOR SELECT TO authenticated USING (private.can_read_comment(comment_id) AND NOT private.is_blocked(user_id));` — `supabase/migrations/20261101000200_authorization.sql:269`

### public.saved_posts

- Nhóm: Post; chức năng: Bookmark riêng không tăng quyền đọc.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:219`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (user_id, post_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:598`
```sql
ALTER TABLE public.saved_posts ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:272`
```sql
ALTER TABLE public.saved_posts ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY saved_posts_select ON public.saved_posts FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:275`

### public.hidden_posts

- Nhóm: Post; chức năng: Preference ẩn bài trên feed.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:227`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (user_id, post_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:600`
```sql
ALTER TABLE public.hidden_posts ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:278`
```sql
ALTER TABLE public.hidden_posts ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY hidden_posts_select ON public.hidden_posts FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:281`

### public.stories

- Nhóm: Story; chức năng: Story một media và expiry24h.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:235`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `author_id` | `author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `media_asset_id` | `media_asset_id uuid NOT NULL UNIQUE` |
| `caption` | `caption text NOT NULL DEFAULT '' CHECK (char_length(caption) <= 2000)` |
| `audience` | `audience text NOT NULL DEFAULT 'friends' CHECK (audience IN ('public','friends','private'))` |
| `state` | `state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','deleted','archived'))` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `expires_at` | `expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours')` |
| `deleted_at` | `deleted_at timestamptz` |
| `UNIQUE` | `UNIQUE (author_id, client_request_id)` |
| `CHECK` | `CHECK (expires_at = created_at + interval '24 hours')` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:562`
```sql
ALTER TABLE public.stories ADD CONSTRAINT stories_media_fk FOREIGN KEY (media_asset_id) REFERENCES public.media_assets(id) ON DELETE RESTRICT;
```

`supabase/migrations/20261101000100_foundation.sql:602`
```sql
ALTER TABLE public.stories ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:284`
```sql
ALTER TABLE public.stories ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:87`
```sql
ALTER TABLE public.stories DROP CONSTRAINT stories_audience_check;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:88`
```sql
ALTER TABLE public.stories ADD CONSTRAINT stories_audience_check CHECK(audience IN ('public','followers','private','friends'));
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:89`
```sql
ALTER TABLE public.stories ALTER COLUMN audience SET DEFAULT 'private';
```

`supabase/migrations/20261107000900_openverse_music_metadata.sql:82`
```sql
ALTER TABLE public.stories ADD COLUMN music_metadata jsonb;
```

`supabase/migrations/20261107000900_openverse_music_metadata.sql:83`
```sql
ALTER TABLE public.stories ADD COLUMN music_metadata_hash text;
```

`supabase/migrations/20261107000900_openverse_music_metadata.sql:88`
```sql
ALTER TABLE public.stories ADD CONSTRAINT stories_music_metadata_check CHECK(
 (music_metadata IS NULL AND music_metadata_hash IS NULL) OR
 (music_metadata IS NOT NULL AND music_metadata_hash IS NOT NULL AND private.valid_music_metadata(music_metadata)
  AND music_metadata_hash=encode(sha256(convert_to(music_metadata::text,'UTF8')),'hex')));
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY stories_select ON public.stories FOR SELECT TO authenticated USING (private.can_read_story(id));` — `supabase/migrations/20261101000200_authorization.sql:287`

### public.story_views

- Nhóm: Story; chức năng: Viewer đầu tiên theo story.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:251`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `story_id` | `story_id uuid NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE` |
| `viewer_id` | `viewer_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `first_viewed_at` | `first_viewed_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (story_id, viewer_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:604`
```sql
ALTER TABLE public.story_views ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:290`
```sql
ALTER TABLE public.story_views ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY story_views_select ON public.story_views FOR SELECT TO authenticated USING (private.can_read_story(story_id) AND (viewer_id=(select auth.uid()) OR EXISTS (SELECT 1 FROM public.stories s WHERE s.id=story_id AND s.author_id=(select auth.uid()))));` — `supabase/migrations/20261101000200_authorization.sql:293`

### public.story_reactions

- Nhóm: Story; chức năng: Reaction Story, UI hiện dùng like.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:259`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `story_id` | `story_id uuid NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `reaction` | `reaction text NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (story_id, user_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:606`
```sql
ALTER TABLE public.story_reactions ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:296`
```sql
ALTER TABLE public.story_reactions ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY story_reactions_select ON public.story_reactions FOR SELECT TO authenticated USING (private.can_read_story(story_id) AND (user_id=(select auth.uid()) OR EXISTS (SELECT 1 FROM public.stories s WHERE s.id=story_id AND s.author_id=(select auth.uid()))));` — `supabase/migrations/20261101000200_authorization.sql:299`

### public.conversations

- Nhóm: Messenger; chức năng: Direct hoặc Messenger group chat.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:268`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `kind` | `kind text NOT NULL DEFAULT 'private' CHECK (kind IN ('private','group'))` |
| `direct_low` | `direct_low uuid REFERENCES public.profiles(id) ON DELETE RESTRICT` |
| `direct_high` | `direct_high uuid REFERENCES public.profiles(id) ON DELETE RESTRICT` |
| `created_by` | `created_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `client_request_id uuid` |
| `title` | `title text CHECK (char_length(title) <= 150)` |
| `state` | `state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','archived','deleted'))` |
| `next_seq` | `next_seq bigint NOT NULL DEFAULT 0 CHECK (next_seq >= 0)` |
| `last_message_id` | `last_message_id uuid` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT now()` |
| `version` | `version bigint NOT NULL DEFAULT 1 CHECK (version > 0)` |
| `CHECK` | `CHECK ((kind = 'private' AND direct_low IS NOT NULL AND direct_high IS NOT NULL AND direct_low < direct_high) OR (kind = 'group' AND direct_low IS NULL AND direct_high IS NULL))` |
| `UNIQUE` | `UNIQUE (created_by, client_request_id)` |
| `CHECK` | `CHECK (kind <> 'group' OR client_request_id IS NOT NULL)` |

Unique indexes ngoài CREATE (kể cả partial):
- `CREATE UNIQUE INDEX conversations_direct_pair_uq ON public.conversations (direct_low,direct_high) WHERE kind='private';` — `supabase/migrations/20261101000100_foundation.sql:532`

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:563`
```sql
ALTER TABLE public.conversations ADD CONSTRAINT conversations_last_message_fk
  FOREIGN KEY (last_message_id,id) REFERENCES public.messages(id,conversation_id) DEFERRABLE INITIALLY DEFERRED;
```

`supabase/migrations/20261101000100_foundation.sql:608`
```sql
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:302`
```sql
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261106000200_messaging_consent.sql:3`
```sql
ALTER TABLE public.conversations ADD COLUMN direct_consent text NOT NULL DEFAULT 'accepted' CHECK(direct_consent IN ('pending','accepted'));
```

`supabase/migrations/20261106000200_messaging_consent.sql:9`
```sql
ALTER TABLE public.conversations ADD COLUMN owner_id uuid REFERENCES public.profiles(id);
```

`supabase/migrations/20261106000400_group_chat_avatar.sql:3`
```sql
ALTER TABLE public.conversations ADD COLUMN avatar_asset_id uuid REFERENCES public.media_assets(id);
```

`supabase/migrations/20261107000300_chat_customization_picker.sql:14`
```sql
ALTER TABLE public.conversations ADD COLUMN theme_id text NOT NULL DEFAULT 'meeto_pink'
 CHECK(theme_id IN ('meeto_pink','ocean_blue','lavender','midnight','forest','sunset','rose','graphite','coffee','aurora'));
```

`supabase/migrations/20261107000300_chat_customization_picker.sql:16`
```sql
ALTER TABLE public.conversations ADD COLUMN customization_version bigint NOT NULL DEFAULT 1 CHECK(customization_version>0);
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY conversations_select ON public.conversations FOR SELECT TO authenticated USING (private.can_read_conversation(id));` — `supabase/migrations/20261101000200_authorization.sql:305`

### public.conversation_members

- Nhóm: Messenger; chức năng: Membership, role, archive, read/delivery seq, nickname.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:288`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `conversation_id` | `conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `role` | `role text NOT NULL DEFAULT 'member' CHECK (role IN ('admin','member'))` |
| `state` | `state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','left','removed'))` |
| `history_from_seq` | `history_from_seq bigint NOT NULL DEFAULT 1 CHECK (history_from_seq >= 1)` |
| `last_read_seq` | `last_read_seq bigint NOT NULL DEFAULT 0 CHECK (last_read_seq >= 0)` |
| `last_delivered_seq` | `last_delivered_seq bigint NOT NULL DEFAULT 0 CHECK (last_delivered_seq >= 0)` |
| `joined_at` | `joined_at timestamptz NOT NULL DEFAULT now()` |
| `left_at` | `left_at timestamptz` |
| `PRIMARY` | `PRIMARY KEY (conversation_id, user_id)` |
| `CHECK` | `CHECK (last_read_seq <= last_delivered_seq)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:610`
```sql
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:308`
```sql
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261106000200_messaging_consent.sql:11`
```sql
ALTER TABLE public.conversation_members ADD COLUMN archived_at timestamptz;
```

`supabase/migrations/20261107000300_chat_customization_picker.sql:12`
```sql
ALTER TABLE public.conversation_members ADD COLUMN nickname text
 CHECK(nickname IS NULL OR (length(btrim(nickname)) BETWEEN 1 AND 40 AND nickname=btrim(nickname) AND nickname !~ '[[:cntrl:]]'));
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY conversation_members_select ON public.conversation_members FOR SELECT TO authenticated USING (private.can_read_conversation(conversation_id));` — `supabase/migrations/20261101000200_authorization.sql:311`
- `CREATE POLICY conversation_members_select ON public.conversation_members FOR SELECT TO authenticated USING(private.can_read_conversation(conversation_id) AND state='active');` — `supabase/migrations/20261101001000_messenger.sql:266`

### public.messages

- Nhóm: Messenger; chức năng: Tin text/media/system/voice-schema/share và reply.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:303`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `conversation_id` | `conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE` |
| `seq` | `seq bigint NOT NULL CHECK (seq > 0)` |
| `sender_id` | `sender_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `request_hash` | `request_hash text NOT NULL CHECK (char_length(request_hash) = 64)` |
| `kind` | `kind text NOT NULL DEFAULT 'text' CHECK (kind IN ('text','media','system'))` |
| `body` | `body text CHECK (char_length(body) <= 10000)` |
| `reply_to_message_id` | `reply_to_message_id uuid` |
| `reply_story_id` | `reply_story_id uuid REFERENCES public.stories(id) ON DELETE SET NULL` |
| `state` | `state text NOT NULL DEFAULT 'sent' CHECK (state IN ('sent','recalled','removed'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `edited_at` | `edited_at timestamptz` |
| `recalled_at` | `recalled_at timestamptz` |
| `version` | `version bigint NOT NULL DEFAULT 1 CHECK (version > 0)` |
| `UNIQUE` | `UNIQUE (conversation_id, seq)` |
| `UNIQUE` | `UNIQUE (id, conversation_id)` |
| `UNIQUE` | `UNIQUE (conversation_id, sender_id, client_request_id)` |
| `FOREIGN` | `FOREIGN KEY (reply_to_message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE RESTRICT` |
| `CHECK` | `CHECK (reply_to_message_id IS NULL OR reply_to_message_id <> id)` |
| `CHECK` | `CHECK (state = 'sent' OR body IS NULL)` |
| `CHECK` | `CHECK (state <> 'sent' OR kind <> 'text' OR (body IS NOT NULL AND char_length(btrim(body)) > 0))` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:612`
```sql
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:314`
```sql
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103001100_advanced_share.sql:89`
```sql
ALTER TABLE public.messages ADD COLUMN shared_post_id uuid REFERENCES public.posts(id) ON DELETE SET NULL;
```

`supabase/migrations/20261103001100_advanced_share.sql:91`
```sql
ALTER TABLE public.messages ADD COLUMN is_shared_post boolean NOT NULL DEFAULT false;
```

`supabase/migrations/20261103001400_advanced_messenger.sql:29`
```sql
ALTER TABLE public.messages
  ADD COLUMN forwarded boolean NOT NULL DEFAULT false;
```

`supabase/migrations/20261103001500_voice_messaging.sql:184`
```sql
ALTER TABLE public.messages DROP CONSTRAINT messages_kind_check;
```

`supabase/migrations/20261103001500_voice_messaging.sql:186`
```sql
ALTER TABLE public.messages ADD CONSTRAINT messages_kind_check
  CHECK (kind IN ('text','media','system','voice'));
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY messages_select ON public.messages FOR SELECT TO authenticated USING (private.can_read_message(id));` — `supabase/migrations/20261101000200_authorization.sql:317`

### public.message_hidden

- Nhóm: Messenger; chức năng: Xóa phía mình theo membership.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:329`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `message_id` | `message_id uuid NOT NULL` |
| `conversation_id` | `conversation_id uuid NOT NULL` |
| `user_id` | `user_id uuid NOT NULL` |
| `hidden_at` | `hidden_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (message_id, user_id)` |
| `FOREIGN` | `FOREIGN KEY (message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE CASCADE` |
| `FOREIGN` | `FOREIGN KEY (conversation_id, user_id) REFERENCES public.conversation_members(conversation_id, user_id) ON DELETE CASCADE` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:614`
```sql
ALTER TABLE public.message_hidden ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:320`
```sql
ALTER TABLE public.message_hidden ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY message_hidden_select ON public.message_hidden FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()) AND private.can_read_conversation(conversation_id));` — `supabase/migrations/20261101000200_authorization.sql:323`

### public.media_assets

- Nhóm: Media; chức năng: Registry owner/purpose/state/object/metadata.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:340`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `owner_id` | `owner_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `bucket_id` | `bucket_id text NOT NULL CHECK (bucket_id IN ('profile-media','social-media','chat-media','group-media'))` |
| `object_path` | `object_path text NOT NULL` |
| `purpose` | `purpose text NOT NULL CHECK (purpose IN ('avatar','cover','post','story','message','group'))` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','ready','quarantined','pending_delete','deleted'))` |
| `mime_type` | `mime_type text NOT NULL` |
| `size_bytes` | `size_bytes bigint CHECK (size_bytes > 0 AND size_bytes <= 104857600)` |
| `metadata` | `metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object')` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `ready_at` | `ready_at timestamptz` |
| `delete_after` | `delete_after timestamptz` |
| `UNIQUE` | `UNIQUE (bucket_id, object_path)` |
| `CHECK` | `CHECK ((purpose IN ('avatar','cover') AND bucket_id = 'profile-media') OR (purpose IN ('post','story') AND bucket_id = 'social-media') OR (purpose = 'message' AND bucket_id = 'chat-media') OR (purpose = 'group' AND bucket_id = 'group-media'))` |
| `UNIQUE` | `UNIQUE (owner_id, client_request_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:616`
```sql
ALTER TABLE public.media_assets ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:326`
```sql
ALTER TABLE public.media_assets ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103001500_voice_messaging.sql:36`
```sql
ALTER TABLE public.media_assets
  ADD COLUMN audio_verify_attempts smallint NOT NULL DEFAULT 0 CHECK (audio_verify_attempts BETWEEN 0 AND 5),
  ADD COLUMN audio_verify_claimed_at timestamptz;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY media_assets_select ON public.media_assets FOR SELECT TO authenticated USING (private.can_read_media(id));` — `supabase/migrations/20261101000200_authorization.sql:329`

### public.post_media

- Nhóm: Media; chức năng: Media nhiều ảnh/video theo post.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:360`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `asset_id` | `asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT` |
| `position` | `position smallint NOT NULL CHECK (position BETWEEN 0 AND 9)` |
| `PRIMARY` | `PRIMARY KEY (post_id, asset_id)` |
| `UNIQUE` | `UNIQUE (post_id, position)` |
| `UNIQUE` | `UNIQUE (asset_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:618`
```sql
ALTER TABLE public.post_media ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:332`
```sql
ALTER TABLE public.post_media ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY post_media_select ON public.post_media FOR SELECT TO authenticated USING (private.can_read_post(post_id));` — `supabase/migrations/20261101000200_authorization.sql:335`

### public.message_media

- Nhóm: Messenger; chức năng: Media đính kèm message, vị trí.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:370`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `message_id` | `message_id uuid NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE` |
| `asset_id` | `asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT` |
| `position` | `position smallint NOT NULL CHECK (position BETWEEN 0 AND 9)` |
| `PRIMARY` | `PRIMARY KEY (message_id, asset_id)` |
| `UNIQUE` | `UNIQUE (message_id, position)` |
| `UNIQUE` | `UNIQUE (asset_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:620`
```sql
ALTER TABLE public.message_media ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:338`
```sql
ALTER TABLE public.message_media ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY message_media_select ON public.message_media FOR SELECT TO authenticated USING (private.can_read_message(message_id) AND EXISTS (SELECT 1 FROM public.messages m WHERE m.id=message_id AND m.state='sent'));` — `supabase/migrations/20261101000200_authorization.sql:341`

### public.notifications

- Nhóm: Notification; chức năng: Thông báo recipient/target/read.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:380`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `recipient_id` | `recipient_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `actor_id` | `actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `event_key` | `event_key text NOT NULL` |
| `kind` | `kind text NOT NULL CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation'))` |
| `target_type` | `target_type text NOT NULL CHECK (target_type IN ('profile','post','comment','story','group','conversation','report'))` |
| `target_id` | `target_id uuid NOT NULL` |
| `read_at` | `read_at timestamptz` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `UNIQUE` | `UNIQUE (recipient_id, event_key)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:622`
```sql
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:344`
```sql
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103000400_notifications_center.sql:44`
```sql
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_kind_check;
```

`supabase/migrations/20261103000400_notifications_center.sql:46`
```sql
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check
  CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow'));
```

`supabase/migrations/20261103000900_memories.sql:40`
```sql
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_kind_check;
```

`supabase/migrations/20261103000900_memories.sql:41`
```sql
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check
  CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','memories'));
```

`supabase/migrations/20261103001200_tag_mentions.sql:44`
```sql
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_kind_check;
```

`supabase/migrations/20261103001200_tag_mentions.sql:46`
```sql
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check
  CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group',
    'message','moderation','follow','memories','mention','tag'));
```

`supabase/migrations/20261106000300_notifications_moderation.sql:18`
```sql
ALTER TABLE public.notifications DROP CONSTRAINT notifications_kind_check;
```

`supabase/migrations/20261106000300_notifications_moderation.sql:20`
```sql
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check CHECK(kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','mention','tag','follow_request','follow_accepted','message_request','group_invite'));
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY notifications_select ON public.notifications FOR SELECT TO authenticated USING (private.is_active() AND recipient_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:347`

### public.device_subscriptions

- Nhóm: Notification; chức năng: Schema thiết bị; không xác nhận push-client hiện hành.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:394`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `installation_id` | `installation_id uuid NOT NULL` |
| `provider` | `provider text NOT NULL CHECK (provider IN ('unifiedpush','oem'))` |
| `endpoint` | `endpoint text NOT NULL CHECK (endpoint LIKE 'https://%')` |
| `state` | `state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','revoked'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `last_seen_at` | `last_seen_at timestamptz NOT NULL DEFAULT now()` |
| `UNIQUE` | `UNIQUE (user_id, installation_id)` |
| `UNIQUE` | `UNIQUE (endpoint)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:624`
```sql
ALTER TABLE public.device_subscriptions ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:350`
```sql
ALTER TABLE public.device_subscriptions ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY device_subscriptions_select ON public.device_subscriptions FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:353`

### public.event_outbox

- Nhóm: Background; chức năng: Sự kiện dispatcher/cleanup và trạng thái worker.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:408`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `event_key` | `event_key text NOT NULL UNIQUE` |
| `event_type` | `event_type text NOT NULL` |
| `aggregate_type` | `aggregate_type text NOT NULL` |
| `aggregate_id` | `aggregate_id uuid` |
| `payload` | `payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object')` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','processing','done','dead'))` |
| `attempts` | `attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0)` |
| `available_at` | `available_at timestamptz NOT NULL DEFAULT now()` |
| `lease_until` | `lease_until timestamptz` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `processed_at` | `processed_at timestamptz` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:626`
```sql
ALTER TABLE public.event_outbox ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:356`
```sql
ALTER TABLE public.event_outbox ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.system_roles

- Nhóm: Admin; chức năng: Role hệ thống, khác role chat.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:424`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `role` | `role text NOT NULL CHECK (role IN ('system_admin','moderator'))` |
| `granted_by` | `granted_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (user_id, role)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:628`
```sql
ALTER TABLE public.system_roles ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:361`
```sql
ALTER TABLE public.system_roles ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY system_roles_select ON public.system_roles FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));` — `supabase/migrations/20261101000200_authorization.sql:364`

### public.reports

- Nhóm: Moderation; chức năng: Case báo cáo một target có scope.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:433`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `reporter_id` | `reporter_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `target_kind` | `target_kind text NOT NULL CHECK (target_kind IN ('post','comment','story','message','profile','group'))` |
| `post_id` | `post_id uuid REFERENCES public.posts(id) ON DELETE SET NULL` |
| `comment_id` | `comment_id uuid REFERENCES public.comments(id) ON DELETE SET NULL` |
| `story_id` | `story_id uuid REFERENCES public.stories(id) ON DELETE SET NULL` |
| `message_id` | `message_id uuid REFERENCES public.messages(id) ON DELETE SET NULL` |
| `profile_id` | `profile_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `group_id` | `group_id uuid REFERENCES public.groups(id) ON DELETE SET NULL` |
| `reason_code` | `reason_code text NOT NULL CHECK (char_length(reason_code) BETWEEN 1 AND 50)` |
| `details` | `details text NOT NULL DEFAULT '' CHECK (char_length(details) <= 3000)` |
| `state` | `state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','assigned','resolved','dismissed'))` |
| `assigned_to` | `assigned_to uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `resolved_at` | `resolved_at timestamptz` |
| `UNIQUE` | `UNIQUE (reporter_id, client_request_id)` |
| `CHECK` | `CHECK (num_nonnulls(post_id,comment_id,story_id,message_id,profile_id,group_id) <= 1)` |
| `CHECK` | `CHECK ((post_id IS NULL OR target_kind='post') AND (comment_id IS NULL OR target_kind='comment') AND (story_id IS NULL OR target_kind='story') AND (message_id IS NULL OR target_kind='message') AND (profile_id IS NULL OR target_kind='profile') AND (group_id IS NULL OR target_kind='group'))` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:630`
```sql
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:367`
```sql
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY reports_select ON public.reports FOR SELECT TO authenticated USING (private.can_review_report(id));` — `supabase/migrations/20261101000200_authorization.sql:370`

### public.moderation_actions

- Nhóm: Moderation; chức năng: Quyết định quản trị gắn report.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:456`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `report_id` | `report_id uuid NOT NULL REFERENCES public.reports(id) ON DELETE RESTRICT` |
| `actor_id` | `actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `action` | `action text NOT NULL CHECK (action IN ('hide','remove','restore','warn','suspend','unsuspend','dismiss'))` |
| `reason` | `reason text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 3000)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `UNIQUE` | `UNIQUE (actor_id, client_request_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:632`
```sql
ALTER TABLE public.moderation_actions ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:373`
```sql
ALTER TABLE public.moderation_actions ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261104000100_admin_panel.sql:26`
```sql
ALTER TABLE public.moderation_actions ALTER COLUMN report_id DROP NOT NULL;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY moderation_actions_select ON public.moderation_actions FOR SELECT TO authenticated USING (private.can_review_report(report_id));` — `supabase/migrations/20261101000200_authorization.sql:376`

### public.moderation_audit

- Nhóm: Moderation; chức năng: Committed audit, không failure-attempt log.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:468`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `actor_id` | `actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `action_id` | `action_id uuid REFERENCES public.moderation_actions(id) ON DELETE SET NULL` |
| `report_id` | `report_id uuid REFERENCES public.reports(id) ON DELETE SET NULL` |
| `operation` | `operation text NOT NULL` |
| `subject_type` | `subject_type text NOT NULL` |
| `subject_id` | `subject_id uuid` |
| `safe_metadata` | `safe_metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(safe_metadata) = 'object')` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:634`
```sql
ALTER TABLE public.moderation_audit ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:379`
```sql
ALTER TABLE public.moderation_audit ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY moderation_audit_select ON public.moderation_audit FOR SELECT TO authenticated USING (private.has_role('system_admin') OR (report_id IS NOT NULL AND private.can_review_report(report_id)));` — `supabase/migrations/20261101000200_authorization.sql:382`

### public.appeals

- Nhóm: Moderation; chức năng: Schema kháng nghị; UI hiện hành chưa xác nhận.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:481`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `action_id` | `action_id uuid NOT NULL REFERENCES public.moderation_actions(id) ON DELETE RESTRICT` |
| `appellant_id` | `appellant_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `body` | `body text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 3000)` |
| `state` | `state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','reviewing','accepted','rejected'))` |
| `assigned_to` | `assigned_to uuid REFERENCES public.profiles(id) ON DELETE SET NULL` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `resolved_at` | `resolved_at timestamptz` |
| `UNIQUE` | `UNIQUE (action_id, appellant_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:636`
```sql
ALTER TABLE public.appeals ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:385`
```sql
ALTER TABLE public.appeals ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY appeals_select ON public.appeals FOR SELECT TO authenticated USING (private.is_active() AND (appellant_id=(select auth.uid()) OR private.has_role('system_admin') OR (private.has_role('moderator') AND assigned_to=(select auth.uid()))));` — `supabase/migrations/20261101000200_authorization.sql:388`

### public.migration_id_map

- Nhóm: Migration; chức năng: Mapping Firebase lịch sử, server/migration only.
- CREATE source: `supabase/migrations/20261101000100_foundation.sql:494`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `source_system` | `source_system text NOT NULL DEFAULT 'firebase'` |
| `entity_type` | `entity_type text NOT NULL CHECK (entity_type IN ('user','post','comment','conversation','message','media'))` |
| `legacy_id` | `legacy_id text NOT NULL` |
| `new_id` | `new_id uuid NOT NULL` |
| `batch_id` | `batch_id uuid NOT NULL` |
| `source_checksum` | `source_checksum text` |
| `migrated_at` | `migrated_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (source_system, entity_type, legacy_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101000100_foundation.sql:638`
```sql
ALTER TABLE public.migration_id_map ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261101000200_authorization.sql:391`
```sql
ALTER TABLE public.migration_id_map ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### private.worker_invocations

- Nhóm: Private Background; chức năng: Lease/token hash invocation; không tiết lộ giá trị.
- CREATE source: `supabase/migrations/20261101001600_cloud_workers.sql:7`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `singleton` | `singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton)` |
| `run_id` | `run_id uuid NOT NULL` |
| `token_hash` | `token_hash bytea NOT NULL` |
| `issued_at` | `issued_at timestamptz NOT NULL` |
| `expires_at` | `expires_at timestamptz NOT NULL` |
| `consumed_at` | `consumed_at timestamptz` |
| `completed_at` | `completed_at timestamptz` |
| `net_request_id` | `net_request_id bigint` |
| `summary` | `summary jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(summary)='object')` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261101001600_cloud_workers.sql:18`
```sql
ALTER TABLE private.worker_invocations ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261103001500_voice_messaging.sql:401`
```sql
ALTER TABLE private.worker_invocations ADD COLUMN execution_until timestamptz;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.group_invites

- Nhóm: Legacy; chức năng: Lời mời Community Groups.
- CREATE source: `supabase/migrations/20261103000500_community_groups.sql:27`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `group_id` | `group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE` |
| `inviter_id` | `inviter_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `invitee_id` | `invitee_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','declined','cancelled'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `resolved_at` | `resolved_at timestamptz` |
| `CHECK` | `CHECK (inviter_id<>invitee_id)` |
| `CHECK` | `CHECK ((state='pending')=(resolved_at IS NULL))` |

Unique indexes ngoài CREATE (kể cả partial):
- `CREATE UNIQUE INDEX IF NOT EXISTS group_invites_pending_unique ON public.group_invites(group_id,invitee_id) WHERE state='pending';` — `supabase/migrations/20261103000500_community_groups.sql:35`

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103000500_community_groups.sql:39`
```sql
ALTER TABLE public.group_invites ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY group_invites_select ON public.group_invites FOR SELECT TO authenticated USING (private.is_active() AND (invitee_id=(select auth.uid()) OR inviter_id=(select auth.uid()) OR private.group_manager(group_id)));` — `supabase/migrations/20261103000500_community_groups.sql:41`

### public.group_report_contexts

- Nhóm: Legacy; chức năng: Context báo cáo Community Groups.
- CREATE source: `supabase/migrations/20261103000600_community_groups_completion.sql:43`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `report_id` | `report_id uuid PRIMARY KEY REFERENCES public.reports(id) ON DELETE CASCADE` |
| `group_id` | `group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE` |
| `target_member_id` | `target_member_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103000600_community_groups_completion.sql:51`
```sql
ALTER TABLE public.group_report_contexts ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.search_history

- Nhóm: Search; chức năng: Lịch sử tìm kiếm theo người dùng.
- CREATE source: `supabase/migrations/20261103000800_advanced_search.sql:43`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `query` | `query text NOT NULL CHECK (char_length(query) BETWEEN 2 AND 100)` |
| `normalized_query` | `normalized_query text NOT NULL CHECK (char_length(normalized_query) BETWEEN 2 AND 100)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `UNIQUE(user_id,normalized_query)` | `UNIQUE(user_id,normalized_query)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103000800_advanced_search.sql:52`
```sql
ALTER TABLE public.search_history ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY search_history_recipient_select ON public.search_history FOR SELECT TO authenticated USING (user_id=(select auth.uid()) AND private.is_active());` — `supabase/migrations/20261103000800_advanced_search.sql:54`

### public.hidden_memories

- Nhóm: Legacy; chức năng: Preference Memories retired.
- CREATE source: `supabase/migrations/20261103000900_memories.sql:47`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `hidden_at` | `hidden_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `PRIMARY` | `PRIMARY KEY(user_id,post_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103000900_memories.sql:54`
```sql
ALTER TABLE public.hidden_memories ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY hidden_memories_select_own ON public.hidden_memories FOR SELECT TO authenticated USING (user_id=(select auth.uid()) AND private.is_active());` — `supabase/migrations/20261103000900_memories.sql:56`

### public.conversation_mutes

- Nhóm: Messenger; chức năng: Mute per member.
- CREATE source: `supabase/migrations/20261103001000_messenger_ux.sql:92`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `conversation_id` | `conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `muted_at` | `muted_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `PRIMARY` | `PRIMARY KEY(conversation_id,user_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103001000_messenger_ux.sql:98`
```sql
ALTER TABLE public.conversation_mutes ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY conversation_mutes_select_own ON public.conversation_mutes FOR SELECT TO authenticated USING(user_id=(select auth.uid()) AND private.can_read_conversation(conversation_id));` — `supabase/migrations/20261103001000_messenger_ux.sql:100`

### public.content_mentions

- Nhóm: Tag/Mention; chức năng: Span UTF-16 trên post/comment, target content polymorphic.
- CREATE source: `supabase/migrations/20261103001200_tag_mentions.sql:54`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `content_type` | `content_type text NOT NULL CHECK (content_type IN ('post','comment'))` |
| `content_id` | `content_id uuid NOT NULL` |
| `mentioned_user_id` | `mentioned_user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `span_start_utf16` | `span_start_utf16 integer NOT NULL CHECK (span_start_utf16>=0)` |
| `span_end_utf16` | `span_end_utf16 integer NOT NULL CHECK (span_end_utf16>span_start_utf16)` |
| `display_label` | `display_label text NOT NULL CHECK (char_length(display_label) BETWEEN 2 AND 201)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `CHECK` | `CHECK (display_label LIKE '@%')` |
| `UNIQUE(content_type,content_id,span_start_utf16)` | `UNIQUE(content_type,content_id,span_start_utf16)` |
| `UNIQUE(content_type,content_id,mentioned_user_id,span_start_utf16)` | `UNIQUE(content_type,content_id,mentioned_user_id,span_start_utf16)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103001200_tag_mentions.sql:69`
```sql
ALTER TABLE public.content_mentions ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY content_mentions_select_visible ON public.content_mentions FOR SELECT TO authenticated USING (private.is_active() AND mentioned_user_id=(select auth.uid()));` — `supabase/migrations/20261103001200_tag_mentions.sql:71`

### public.post_user_tags

- Nhóm: Tag/Mention; chức năng: Consent tag pending/accepted/rejected/removed.
- CREATE source: `supabase/migrations/20261103001200_tag_mentions.sql:74`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `tagged_user_id` | `tagged_user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `tagged_by` | `tagged_by uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `display_name_snapshot` | `display_name_snapshot text NOT NULL CHECK (char_length(display_name_snapshot) BETWEEN 1 AND 100)` |
| `state` | `state text NOT NULL CHECK (state IN ('pending','accepted','rejected','removed'))` |
| `request_id` | `request_id uuid NOT NULL DEFAULT gen_random_uuid()` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `resolved_at` | `resolved_at timestamptz` |
| `PRIMARY` | `PRIMARY KEY(post_id,tagged_user_id)` |
| `CHECK` | `CHECK ((state='pending')=(resolved_at IS NULL))` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103001200_tag_mentions.sql:88`
```sql
ALTER TABLE public.post_user_tags ENABLE ROW LEVEL SECURITY;
```

Policies khai báo tường minh (historical; đối chiếu DROP/override trong source):
- `CREATE POLICY post_user_tags_recipient_select ON public.post_user_tags FOR SELECT TO authenticated USING (private.is_active() AND tagged_user_id=(select auth.uid()));` — `supabase/migrations/20261103001200_tag_mentions.sql:90`

### public.profile_albums

- Nhóm: Profile Albums; chức năng: Album hồ sơ, cờ is_featured và cover.
- CREATE source: `supabase/migrations/20261103001300_advanced_profile_albums.sql:19`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `owner_id` | `owner_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `title` | `title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 100)` |
| `description` | `description text NOT NULL DEFAULT '' CHECK (char_length(description)<=1000)` |
| `audience` | `audience text NOT NULL DEFAULT 'friends' CHECK (audience IN ('public','friends','private'))` |
| `cover_item_id` | `cover_item_id uuid` |
| `version` | `version bigint NOT NULL DEFAULT 1 CHECK (version>0)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT statement_timestamp()` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103001300_advanced_profile_albums.sql:45`
```sql
ALTER TABLE public.profile_albums ADD CONSTRAINT profile_albums_cover_item_fk
  FOREIGN KEY(cover_item_id) REFERENCES public.profile_album_items(id) ON DELETE SET NULL;
```

`supabase/migrations/20261103001300_advanced_profile_albums.sql:64`
```sql
ALTER TABLE public.profile_albums ENABLE ROW LEVEL SECURITY;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:90`
```sql
ALTER TABLE public.profile_albums DROP CONSTRAINT profile_albums_audience_check;
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:91`
```sql
ALTER TABLE public.profile_albums ADD CONSTRAINT profile_albums_audience_check CHECK(audience IN ('public','followers','private','friends'));
```

`supabase/migrations/20261106000100_follow_only_privacy.sql:92`
```sql
ALTER TABLE public.profile_albums ALTER COLUMN audience SET DEFAULT 'private';
```

`supabase/migrations/20261108000500_featured_photo_albums.sql:10`
```sql
ALTER TABLE public.profile_albums ADD COLUMN is_featured boolean NOT NULL DEFAULT false;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.profile_album_items

- Nhóm: Profile Albums; chức năng: Ảnh nguồn bài viết trong album.
- CREATE source: `supabase/migrations/20261103001300_advanced_profile_albums.sql:32`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `album_id` | `album_id uuid NOT NULL REFERENCES public.profile_albums(id) ON DELETE CASCADE` |
| `source_post_id` | `source_post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `asset_id` | `asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE CASCADE` |
| `position` | `position integer NOT NULL DEFAULT 0 CHECK (position>=0)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `UNIQUE(album_id,asset_id)` | `UNIQUE(album_id,asset_id)` |
| `UNIQUE(id,album_id)` | `UNIQUE(id,album_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103001300_advanced_profile_albums.sql:66`
```sql
ALTER TABLE public.profile_album_items ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.profile_featured_items

- Nhóm: Profile Albums; chức năng: Danh sách featured đơn cũ, giữ tương thích.
- CREATE source: `supabase/migrations/20261103001300_advanced_profile_albums.sql:48`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `owner_id` | `owner_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `source_post_id` | `source_post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `asset_id` | `asset_id uuid REFERENCES public.media_assets(id) ON DELETE CASCADE` |
| `position` | `position smallint NOT NULL CHECK (position BETWEEN 0 AND 5)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |

Unique indexes ngoài CREATE (kể cả partial):
- `CREATE UNIQUE INDEX profile_featured_post_unique_idx ON public.profile_featured_items(owner_id,source_post_id) WHERE asset_id IS NULL;` — `supabase/migrations/20261103001300_advanced_profile_albums.sql:57`
- `CREATE UNIQUE INDEX profile_featured_photo_unique_idx ON public.profile_featured_items(owner_id,source_post_id,asset_id) WHERE asset_id IS NOT NULL;` — `supabase/migrations/20261103001300_advanced_profile_albums.sql:58`

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103001300_advanced_profile_albums.sql:67`
```sql
ALTER TABLE public.profile_featured_items ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.profile_pinned_posts

- Nhóm: Profile; chức năng: Bài ghim của chủ hồ sơ.
- CREATE source: `supabase/migrations/20261103001300_advanced_profile_albums.sql:59`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `owner_id` | `owner_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `post_id` | `post_id uuid NOT NULL UNIQUE REFERENCES public.posts(id) ON DELETE CASCADE` |
| `pinned_at` | `pinned_at timestamptz NOT NULL DEFAULT statement_timestamp()` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261103001300_advanced_profile_albums.sql:68`
```sql
ALTER TABLE public.profile_pinned_posts ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.follow_requests

- Nhóm: Follow; chức năng: Yêu cầu follow private và quyết định.
- CREATE source: `supabase/migrations/20261106000100_follow_only_privacy.sql:18`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `requester_id` | `requester_id uuid NOT NULL REFERENCES public.profiles(id)` |
| `recipient_id` | `recipient_id uuid NOT NULL REFERENCES public.profiles(id)` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','accepted','rejected','canceled'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `updated_at` | `updated_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `PRIMARY` | `PRIMARY KEY(requester_id,recipient_id)` |
| `CHECK(requester_id<>recipient_id)` | `CHECK(requester_id<>recipient_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261106000100_follow_only_privacy.sql:27`
```sql
ALTER TABLE public.follow_requests ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.message_requests

- Nhóm: Messenger; chức năng: Consent request cho direct message.
- CREATE source: `supabase/migrations/20261106000200_messaging_consent.sql:15`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `sender_id` | `sender_id uuid NOT NULL REFERENCES public.profiles(id)` |
| `recipient_id` | `recipient_id uuid NOT NULL REFERENCES public.profiles(id)` |
| `body` | `body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 2000)` |
| `story_id` | `story_id uuid REFERENCES public.stories(id)` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','accepted','rejected'))` |
| `conversation_id` | `conversation_id uuid REFERENCES public.conversations(id)` |
| `client_request_id` | `client_request_id uuid NOT NULL` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `resolved_at` | `resolved_at timestamptz` |
| `PRIMARY` | `PRIMARY KEY(sender_id,recipient_id)` |
| `CHECK(sender_id<>recipient_id)` | `CHECK(sender_id<>recipient_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261106000200_messaging_consent.sql:32`
```sql
ALTER TABLE public.message_requests ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.conversation_invitations

- Nhóm: Messenger; chức năng: Invite/accept group chat.
- CREATE source: `supabase/migrations/20261106000200_messaging_consent.sql:25`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `conversation_id` | `conversation_id uuid NOT NULL REFERENCES public.conversations(id)` |
| `user_id` | `user_id uuid NOT NULL REFERENCES public.profiles(id)` |
| `invited_by` | `invited_by uuid NOT NULL REFERENCES public.profiles(id)` |
| `state` | `state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','accepted','rejected','canceled'))` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `resolved_at` | `resolved_at timestamptz` |
| `PRIMARY` | `PRIMARY KEY(conversation_id,user_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261106000200_messaging_consent.sql:33`
```sql
ALTER TABLE public.conversation_invitations ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.reel_views

- Nhóm: Reel; chức năng: Unique viewer trên post is_reel.
- CREATE source: `supabase/migrations/20261106000700_reel_view_counts.sql:3`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `post_id` | `post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE` |
| `viewer_id` | `viewer_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE` |
| `first_viewed_at` | `first_viewed_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY (post_id, viewer_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261106000700_reel_view_counts.sql:9`
```sql
ALTER TABLE public.reel_views ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### private.media_cleanup_attempt_audit

- Nhóm: Private Background; chức năng: Observation/finish theo event+attempt, ID không FK.
- CREATE source: `supabase/migrations/20261107001050_media_cleanup_recovery.sql:133`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `event_id` | `event_id uuid NOT NULL` |
| `attempt` | `attempt integer NOT NULL CHECK (attempt > 0)` |
| `asset_id` | `asset_id uuid NOT NULL` |
| `storage_outcome` | `storage_outcome text NOT NULL DEFAULT 'not_observed' CHECK (storage_outcome IN ('not_observed','absent','present','unknown'))` |
| `observation_source` | `observation_source text CHECK (observation_source IN ('worker_head','owner_head','legacy_lease_reaper'))` |
| `delete_http_status` | `delete_http_status integer CHECK (delete_http_status IS NULL OR delete_http_status BETWEEN 100 AND 599)` |
| `error_code` | `error_code text CHECK (error_code IS NULL OR error_code IN ('storage_error','storage_denied','storage_unavailable','storage_present','storage_unknown', 'invalid_cleanup_contract','cleanup_finalize_blocked'))` |
| `claimed_at` | `claimed_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `observed_at` | `observed_at timestamptz` |
| `finish_result` | `finish_result text CHECK (finish_result IS NULL OR finish_result IN ('done','retry_scheduled','dead_lettered','blocked_after_storage_absent','fence_mismatch'))` |
| `finished_at` | `finished_at timestamptz` |
| `PRIMARY` | `PRIMARY KEY(event_id,attempt)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261107001050_media_cleanup_recovery.sql:166`
```sql
ALTER TABLE private.media_cleanup_attempt_audit ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### private.media_cleanup_recovery_log

- Nhóm: Private Background; chức năng: Receipt recovery và replacement event, ID không FK.
- CREATE source: `supabase/migrations/20261107001050_media_cleanup_recovery.sql:151`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `id` | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` |
| `source_event_id` | `source_event_id uuid NOT NULL` |
| `attempt` | `attempt integer NOT NULL CHECK (attempt > 0)` |
| `request_id` | `request_id uuid NOT NULL` |
| `action` | `action text NOT NULL CHECK (action IN ('dead_letter_unknown','storage_absent_confirmed','retry_scheduled','reconciliation_blocked','unknown_retained'))` |
| `storage_outcome` | `storage_outcome text NOT NULL CHECK (storage_outcome IN ('absent','present','unknown'))` |
| `replacement_event_id` | `replacement_event_id uuid` |
| `actor_name` | `actor_name text NOT NULL DEFAULT session_user` |
| `reason` | `reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500)` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT statement_timestamp()` |
| `receipt` | `receipt jsonb NOT NULL CHECK (jsonb_typeof(receipt)='object')` |
| `UNIQUE(source_event_id,request_id)` | `UNIQUE(source_event_id,request_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261107001050_media_cleanup_recovery.sql:167`
```sql
ALTER TABLE private.media_cleanup_recovery_log ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### private.admin_action_receipts

- Nhóm: Private Admin; chức năng: Idempotency actor/request, IDs không FK.
- CREATE source: `supabase/migrations/20261107001100_admin_logic_hardening.sql:115`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `actor_id` | `actor_id uuid NOT NULL` |
| `request_id` | `request_id uuid NOT NULL` |
| `payload` | `payload jsonb NOT NULL` |
| `receipt` | `receipt jsonb NOT NULL` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY(actor_id,request_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261107001100_admin_logic_hardening.sql:128`
```sql
ALTER TABLE private.admin_action_receipts ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### private.moderation_media_holds

- Nhóm: Private Admin; chức năng: Giữ registry media chứng cứ tránh retire.
- CREATE source: `supabase/migrations/20261107001100_admin_logic_hardening.sql:120`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `asset_id` | `asset_id uuid PRIMARY KEY REFERENCES public.media_assets(id) ON DELETE RESTRICT` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `policy` | `policy text NOT NULL DEFAULT 'owner_retention_review_required'` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261107001100_admin_logic_hardening.sql:129`
```sql
ALTER TABLE private.moderation_media_holds ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### private.moderation_text_evidence

- Nhóm: Private Admin; chức năng: Snapshot văn bản polymorphic, không FK target.
- CREATE source: `supabase/migrations/20261107001100_admin_logic_hardening.sql:124`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `target_kind` | `target_kind text NOT NULL` |
| `target_id` | `target_id uuid NOT NULL` |
| `body` | `body text` |
| `created_at` | `created_at timestamptz NOT NULL DEFAULT now()` |
| `PRIMARY` | `PRIMARY KEY(target_kind,target_id)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261107001100_admin_logic_hardening.sql:130`
```sql
ALTER TABLE private.moderation_text_evidence ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

### public.music_catalog_tracks

- Nhóm: Music; chức năng: Catalog CC0 versioned, không user media_assets.
- CREATE source: `supabase/migrations/20261107001200_music_catalog_storage.sql:71`. Declaration evidence; live NOT VERIFIED.

| Cột / constraint | Định nghĩa nguồn: kiểu, null, mặc định, PK/FK/unique |
|---|---|
| `track_id` | `track_id text NOT NULL CHECK(track_id ~ '^meeto_music_[0-9]{3}$')` |
| `catalog_version` | `catalog_version text NOT NULL CHECK(char_length(catalog_version) BETWEEN 1 AND 80)` |
| `sort_order` | `sort_order integer NOT NULL CHECK(sort_order BETWEEN 1 AND 10000)` |
| `title` | `title text NOT NULL CHECK(char_length(btrim(title)) BETWEEN 1 AND 300)` |
| `artist` | `artist text NOT NULL CHECK(char_length(btrim(artist)) BETWEEN 1 AND 300)` |
| `duration_ms` | `duration_ms integer NOT NULL CHECK(duration_ms BETWEEN 1000 AND 3600000)` |
| `bucket_id` | `bucket_id text NOT NULL DEFAULT 'music-library' CHECK(bucket_id='music-library')` |
| `object_path` | `object_path text NOT NULL CHECK(object_path ~ '^v[0-9]+/meeto_music_[0-9]{3}[.]ogg$')` |
| `mime_type` | `mime_type text NOT NULL DEFAULT 'audio/ogg' CHECK(mime_type='audio/ogg')` |
| `codec` | `codec text NOT NULL DEFAULT 'vorbis' CHECK(codec='vorbis')` |
| `container` | `container text NOT NULL DEFAULT 'ogg' CHECK(container='ogg')` |
| `file_size_bytes` | `file_size_bytes bigint NOT NULL CHECK(file_size_bytes BETWEEN 1 AND 5242880)` |
| `sha256` | `sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$')` |
| `license_id` | `license_id text NOT NULL CHECK(license_id='CC0-1.0')` |
| `license_url` | `license_url text NOT NULL CHECK(license_url='https://creativecommons.org/publicdomain/zero/1.0/')` |
| `source_url` | `source_url text NOT NULL CHECK(source_url ~ '^https://[^/@[:space:]]+(/&#124;[?#]&#124;$)')` |
| `attribution` | `attribution text NOT NULL CHECK(char_length(btrim(attribution)) BETWEEN 1 AND 800)` |
| `is_active` | `is_active boolean NOT NULL DEFAULT false` |
| `PRIMARY` | `PRIMARY KEY(track_id,catalog_version)` |
| `UNIQUE(catalog_version,sort_order)` | `UNIQUE(catalog_version,sort_order)` |
| `UNIQUE(bucket_id,object_path)` | `UNIQUE(bucket_id,object_path)` |

Ledger ALTER tĩnh theo thứ tự migration (đọc cả DROP/ADD/DEFAULT/constraints; không phải mọi dòng là thêm cột):

`supabase/migrations/20261107001200_music_catalog_storage.sql:94`
```sql
ALTER TABLE public.music_catalog_tracks ENABLE ROW LEVEL SECURITY;
```

Không thu được CREATE POLICY tĩnh riêng cho bảng trong phép trích xuất. Không kết luận public/unprotected: đọc REVOKE/RLS và SECURITY DEFINER RPC ở source; private/admin/reference tables thường chỉ qua trusted functions.

## Quan hệ REFERENCES/FK được khai báo tường minh

Có 120 ánh xạ tuple FK DISTINCT trong DDL đã trích; không phải số constraint/live FK. Bảng dưới xác định cardinality bằng PK/UNIQUE/NOT NULL của declaration+ADD, không áp đặt total participation nghiệp vụ. Ledger DROP/re-add phía trên quyết định ON DELETE và constraint cuối. Nullable là tối thiểu tham chiếu của một child; parent luôn có thể chưa có child.

| source_table.source_column -> target_table.target_column | Parent→child | Child→parent | Evidence |
|---|---|---|---|
| `public.profiles.(id) -> auth.users.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261101000100_foundation.sql:28` |
| `public.profile_private.(user_id) -> public.profiles.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261101000100_foundation.sql:41` |
| `public.user_settings.(user_id) -> public.profiles.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261101000100_foundation.sql:50` |
| `public.account_status.(user_id) -> public.profiles.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261101000100_foundation.sql:63` |
| `public.user_blocks.(blocker_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:72` |
| `public.user_blocks.(blocked_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:72` |
| `public.user_follows.(follower_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:81` |
| `public.user_follows.(followed_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:81` |
| `public.friend_requests.(requester_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:90` |
| `public.friend_requests.(recipient_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:90` |
| `public.friendships.(user_low) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:106` |
| `public.friendships.(user_high) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:106` |
| `public.groups.(created_by) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:115` |
| `public.group_members.(group_id) -> public.groups.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:131` |
| `public.group_members.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:131` |
| `public.group_join_requests.(group_id) -> public.groups.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:142` |
| `public.group_join_requests.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:142` |
| `public.group_join_requests.(reviewed_by) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:142` |
| `public.posts.(author_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:154` |
| `public.posts.(group_id) -> public.groups.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:154` |
| `public.posts.(original_post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:154` |
| `public.post_reactions.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:179` |
| `public.post_reactions.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:179` |
| `public.comments.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:189` |
| `public.comments.(author_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:189` |
| `public.comments.(parent_id,post_id) -> public.comments.(id,post_id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:189` |
| `public.comment_reactions.(comment_id) -> public.comments.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:210` |
| `public.comment_reactions.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:210` |
| `public.saved_posts.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:219` |
| `public.saved_posts.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:219` |
| `public.hidden_posts.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:227` |
| `public.hidden_posts.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:227` |
| `public.stories.(author_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:235` |
| `public.story_views.(story_id) -> public.stories.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:251` |
| `public.story_views.(viewer_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:251` |
| `public.story_reactions.(story_id) -> public.stories.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:259` |
| `public.story_reactions.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:259` |
| `public.conversations.(direct_low) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:268` |
| `public.conversations.(direct_high) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:268` |
| `public.conversations.(created_by) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:268` |
| `public.conversation_members.(conversation_id) -> public.conversations.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:288` |
| `public.conversation_members.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:288` |
| `public.messages.(conversation_id) -> public.conversations.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:303` |
| `public.messages.(sender_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:303` |
| `public.messages.(reply_story_id) -> public.stories.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:303` |
| `public.messages.(reply_to_message_id,conversation_id) -> public.messages.(id,conversation_id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:303` |
| `public.message_hidden.(message_id,conversation_id) -> public.messages.(id,conversation_id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:329` |
| `public.message_hidden.(conversation_id,user_id) -> public.conversation_members.(conversation_id,user_id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:329` |
| `public.media_assets.(owner_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:340` |
| `public.post_media.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:360` |
| `public.post_media.(asset_id) -> public.media_assets.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261101000100_foundation.sql:360` |
| `public.message_media.(message_id) -> public.messages.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:370` |
| `public.message_media.(asset_id) -> public.media_assets.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261101000100_foundation.sql:370` |
| `public.notifications.(recipient_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:380` |
| `public.notifications.(actor_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:380` |
| `public.device_subscriptions.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:394` |
| `public.system_roles.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:424` |
| `public.system_roles.(granted_by) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:424` |
| `public.reports.(reporter_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.reports.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.reports.(comment_id) -> public.comments.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.reports.(story_id) -> public.stories.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.reports.(message_id) -> public.messages.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.reports.(profile_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.reports.(group_id) -> public.groups.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.reports.(assigned_to) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:433` |
| `public.moderation_actions.(report_id) -> public.reports.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:456` |
| `public.moderation_actions.(actor_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:456` |
| `public.moderation_audit.(actor_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:468` |
| `public.moderation_audit.(action_id) -> public.moderation_actions.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:468` |
| `public.moderation_audit.(report_id) -> public.reports.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:468` |
| `public.appeals.(action_id) -> public.moderation_actions.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261101000100_foundation.sql:481` |
| `public.appeals.(appellant_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:481` |
| `public.appeals.(assigned_to) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:481` |
| `public.group_invites.(group_id) -> public.groups.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000500_community_groups.sql:27` |
| `public.group_invites.(inviter_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000500_community_groups.sql:27` |
| `public.group_invites.(invitee_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000500_community_groups.sql:27` |
| `public.group_report_contexts.(report_id) -> public.reports.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261103000600_community_groups_completion.sql:43` |
| `public.group_report_contexts.(group_id) -> public.groups.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000600_community_groups_completion.sql:43` |
| `public.group_report_contexts.(target_member_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000600_community_groups_completion.sql:43` |
| `public.search_history.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000800_advanced_search.sql:43` |
| `public.hidden_memories.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000900_memories.sql:47` |
| `public.hidden_memories.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103000900_memories.sql:47` |
| `public.conversation_mutes.(conversation_id) -> public.conversations.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001000_messenger_ux.sql:92` |
| `public.conversation_mutes.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001000_messenger_ux.sql:92` |
| `public.content_mentions.(mentioned_user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001200_tag_mentions.sql:54` |
| `public.post_user_tags.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001200_tag_mentions.sql:74` |
| `public.post_user_tags.(tagged_user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001200_tag_mentions.sql:74` |
| `public.post_user_tags.(tagged_by) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001200_tag_mentions.sql:74` |
| `public.profile_albums.(owner_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:19` |
| `public.profile_album_items.(album_id) -> public.profile_albums.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:32` |
| `public.profile_album_items.(source_post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:32` |
| `public.profile_album_items.(asset_id) -> public.media_assets.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:32` |
| `public.profile_featured_items.(owner_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:48` |
| `public.profile_featured_items.(source_post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:48` |
| `public.profile_featured_items.(asset_id) -> public.media_assets.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261103001300_advanced_profile_albums.sql:48` |
| `public.profile_pinned_posts.(owner_id) -> public.profiles.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:59` |
| `public.profile_pinned_posts.(post_id) -> public.posts.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261103001300_advanced_profile_albums.sql:59` |
| `public.follow_requests.(requester_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000100_follow_only_privacy.sql:18` |
| `public.follow_requests.(recipient_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000100_follow_only_privacy.sql:18` |
| `public.message_requests.(sender_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000200_messaging_consent.sql:15` |
| `public.message_requests.(recipient_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000200_messaging_consent.sql:15` |
| `public.message_requests.(story_id) -> public.stories.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261106000200_messaging_consent.sql:15` |
| `public.message_requests.(conversation_id) -> public.conversations.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261106000200_messaging_consent.sql:15` |
| `public.conversation_invitations.(conversation_id) -> public.conversations.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000200_messaging_consent.sql:25` |
| `public.conversation_invitations.(user_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000200_messaging_consent.sql:25` |
| `public.conversation_invitations.(invited_by) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000200_messaging_consent.sql:25` |
| `public.reel_views.(post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000700_reel_view_counts.sql:3` |
| `public.reel_views.(viewer_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 1 | `supabase/migrations/20261106000700_reel_view_counts.sql:3` |
| `private.moderation_media_holds.(asset_id) -> public.media_assets.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261107001100_admin_logic_hardening.sql:120` |
| `public.profiles.(avatar_asset_id) -> public.media_assets.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:559` |
| `public.profiles.(cover_asset_id) -> public.media_assets.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:560` |
| `public.groups.(cover_asset_id) -> public.media_assets.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:561` |
| `public.stories.(media_asset_id) -> public.media_assets.(id)` | 1 — 0..1 (1–1) | 1 | `supabase/migrations/20261101000100_foundation.sql:562` |
| `public.conversations.(last_message_id,id) -> public.messages.(id,conversation_id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261101000100_foundation.sql:563` |
| `public.messages.(shared_post_id) -> public.posts.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261103001100_advanced_share.sql:89` |
| `public.profile_albums.(cover_item_id) -> public.profile_album_items.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261103001300_advanced_profile_albums.sql:45` |
| `public.posts.(reel_cover_asset_id) -> public.media_assets.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261103001600_reels_short_videos.sql:30` |
| `public.conversations.(owner_id) -> public.profiles.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261106000200_messaging_consent.sql:9` |
| `public.conversations.(avatar_asset_id) -> public.media_assets.(id)` | 1 — 0..N (1–N) | 0..1 (nullable) | `supabase/migrations/20261106000400_group_chat_avatar.sql:3` |

## Các quan hệ N–N và 1–1 nghiệp vụ đã có bảng nối

| Cặp thực thể | Bảng nối / FK đã xác nhận | Cardinality và lưu ý |
|---|---|---|
| Profile ↔ Profile follow | user_follows(follower_id,followed_id) → profiles.id | N–N có hướng; PK cặp; follower≠followed. follow_requests riêng, pending chưa là follows. |
| Profile ↔ Profile block | user_blocks(blocker_id,blocked_id) | N–N có hướng lưu dữ liệu, quyền bị xét hai chiều. |
| Profile ↔ Post reactions | post_reactions(user_id,post_id) | N–N; mỗi cặp một reaction enum. |
| Profile ↔ Comment reactions | comment_reactions(user_id,comment_id) | N–N, PK cặp. |
| Profile ↔ Post save/hide | saved_posts/hidden_posts(user_id,post_id) | Hai quan hệ N–N riêng, không cấp thêm quyền đọc. |
| Profile ↔ Story | story_views/story_reactions | N–N theo viewer/user và story; schema reactions rộng hơn UI like. |
| Profile ↔ Reel | reel_views(viewer_id,post_id) | N–N, post phải is_reel theo RPC; FK chỉ trỏ posts. |
| Profile ↔ Conversation | conversation_members | N–N; role/state/read/delivery/history/nickname/archived_at là thuộc tính membership, không Community Groups. |
| Message ↔ Media | message_media | N–N ở mức FK; source giới hạn scope/position/ready/owner khi bind, không suy mỗi asset chỉ có một message nếu không UNIQUE. |
| Post ↔ Media | post_media | N–N vật lý; PK(post,asset), UNIQUE(post,position). |
| Album ↔ Media/Post | profile_album_items | Album1–N items; mỗi item trỏ một source post và asset; UNIQUE(album,asset). Không copy object. |
| Post ↔ Profile tag | post_user_tags(post_id,tagged_user_id) | N–N với state/request/consent; tagged_by là FK khác có vai trò người gắn thẻ. |
| Auth user ↔ Profile | profiles.id PK+FK auth.users.id | Auth user0..1 profile ở mức DB; mỗi profile đúng1 auth user. Bootstrap thường tạo row nhưng không thay optionality vật lý. |
| Profile ↔ private/settings/status | Mỗi bảng user_id PK+FK | Profile0..1 row mỗi loại, child đúng1 profile. |
| Profile ↔ pinned post | profile_pinned_posts.owner_id PK, post_id UNIQUE | Owner tối đa1 pinned row; Post tối đa1 pinned row theo unique. Không suy mọi profile đều ghim. |
| Group cộng đồng ↔ Profile | group_members | N–N legacy; giữ trong ERD vật lý, tách khỏi Use Case hiện hành. |
| Profile ↔ friendship | friendships(user_low,user_high) | N–N cặp vô hướng legacy; user_low<user_high. |

Cover album: cover_item_id FK nullable → profile_album_items.id, **không UNIQUE cover_item_id** trong DDL. Vật lý một item có thể được tham chiếu bởi nhiều album cover; RPC giới hạn item thuộc đúng album. Không vẽ 1–1 chỉ vì gọi là cover. stories.media_asset_id có UNIQUE nên cần phân biệt với post_media/message_media.

## Tham chiếu logic, tuyệt đối không vẽ như FK vật lý

| Trường | Đối tượng logic / giới hạn |
|---|---|
| content_mentions.content_type + content_id | Post hoặc Comment; không có FK từ content_id. Mentioned_user_id mới có FK profiles. |
| notifications.target_type/target_id/payload context | Polymorphic navigation; không nối target_id tới mọi bảng bằng FK. recipient/actor nếu DDL có REFERENCES là quan hệ riêng. |
| event_outbox.aggregate_type/aggregate_id/payload | Event routing/reference, không FK target tự động. |
| moderation_audit.subject_type/subject_id | Target polymorphic; actor/action/report có FK thật, subject_id không. |
| private.moderation_text_evidence(target_kind,target_id) | Snapshot/key polymorphic, không FK target. |
| private.admin_action_receipts.actor_id/request_id | PK ghép nhưng không REFERENCES profiles trong CREATE, không tự vẽ FK actor. |
| private.media_cleanup_attempt_audit.event_id/asset_id | Cố ý không FK để giữ audit sau lifecycle; không nối physical. |
| private.media_cleanup_recovery_log.source_event_id/replacement_event_id | Logic receipt, không FK. |
| posts/stories.music_metadata | JSONB v1/v2; binder/validator đối chiếu catalog, không FK tới music_catalog_tracks. |
| music_catalog_tracks.bucket_id/object_path | Object address, UNIQUE tuple nhưng không REFERENCES storage.objects. Không nối track_id sang media_assets. |
| media_assets.bucket_id/object_path | Registry → Storage theo address/validation; không FK tới storage.objects trong app DDL. |
| migration_id_map.new_id/entity_type | Mapping polymorphic của migration, không FK tất cả các bảng. |
| comments.root_id/display_parent_id/display_depth/preview_reply | Có trong projection cây; đối chiếu cột CREATE/ALTER, không dựng thành cột vật lý chỉ vì DTO có field. |

## Schema Supabase quản lý và tablespace ngoài ứng dụng

- `auth.users`: managed; app FK profiles.id tới auth.users.id đã xác nhận. Không chép full schema auth hoặc password/token internals vào ERD app.
- `storage.buckets`, `storage.objects`: managed, buckets/policies được migrations cấu hình. Không tính là app CREATE TABLE; permissions/Storage address không tự thành FK media_assets.
- `realtime.messages`: managed cho private-broadcast RLS; không đồng nhất messages nghiệp vụ của app. `RealtimeClient` subscribe user topic và refetch.
- `cron.job`, `net.*`, extension/catalog tables: hạ tầng nếu extension tồn tại; không vẽ thành entity Social hoặc xác nhận Cloud có chúng từ source.
- `private.worker_invocations` chứa token_hash; chỉ mô tả kiểu và trách nhiệm, không lấy giá trị credential.

## Những điểm schema cuối còn phải xác minh

1. File `20261107000800_tag_management_rpc_transactions.sql` chỉ1 byte, không có ALTER FUNCTION như Task51 mô tả. Tên file không là migration fix hoàn chỉnh.
2. Các DDL redefinitions/grants sinh trong DO/EXECUTE không được xem như đã thực thi. Dữ liệu current Cloud có thể ở mốc migration cũ.
3. Những retired tables không bị xóa khỏi inventory: đây là ERD vật lý source, không Use Case sản phẩm. Các RPC retired hoặc helpers false không làm FK biến mất.
4. Foreign-key deletion behavior có DROP/re-add muộn (ví dụ preserve social references/moderation). Đọc ledger và source file cuối, không áp dụng ON DELETE ban đầu một cách máy móc. Không khẳng định CASCADE đã xóa dữ liệu thật.
5. 120 là số ánh xạ source/target tuple DISTINCT tìm được trong DDL, không số constraint active hoặc số cạnh cuối của live ERD. Các quan hệ cardinality trong bảng suy từ declaration/null/unique, không từ số row thực tế.
6. Không có schema dump Cloud để xác nhận final constraint names, triggers/RLS/grants/columns đang triển khai. Tác giả cần ghi source-model nếu chưa bổ sung bằng chứng owner.