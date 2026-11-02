-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
-- MEETO DESIGN DRAFT / CHƯA ĐƯỢC DUYỆT / KHÔNG PHẢI MIGRATION.
-- Nhiệm vụ04: đã kiểm DDL trên PostgreSQL16.11 local; chưa chạy trên Supabase thật.
-- Target: PostgreSQL >=15; auth.users và roles Supabase có sẵn tại môi trường test sau này.
-- Guard cố ý chặn thực thi nguyên file. Bản migration phải được tạo riêng sau review.
BEGIN;
-- Guard removed only in local validation copy.

CREATE SCHEMA IF NOT EXISTS private;
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon, authenticated;
REVOKE CREATE ON SCHEMA public FROM PUBLIC, anon, authenticated;
-- Nhiệm vụ04: role migration owner phải revoke global default PUBLIC EXECUTE.
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
-- Không CREATE EXTENSION và không chỉnh auth/storage/realtime schemas tại đây.

-- CURRENT | Tài khoản: Hồ sơ hiển thị, không chứa email/SĐT/ngày sinh.
CREATE TABLE public.profiles (
  id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 100),
  bio text NOT NULL DEFAULT '' CHECK (char_length(bio) <= 2000),
  avatar_asset_id uuid,
  cover_asset_id uuid,
  onboarding_completed boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

-- REQUIRED | Tài khoản: Thông tin nhạy cảm chỉ chủ hồ sơ.
CREATE TABLE public.profile_private (
  user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
  birth_date date CHECK (birth_date >= DATE '1900-01-01'),
  gender text CHECK (char_length(gender) <= 100),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- REQUIRED | Tài khoản: Quyền riêng tư, hiển thị last seen và notification preferences.
CREATE TABLE public.user_settings (
  user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
  profile_audience text NOT NULL DEFAULT 'public' CHECK (profile_audience IN ('public','friends','private')),
  last_seen_audience text NOT NULL DEFAULT 'friends' CHECK (last_seen_audience IN ('public','friends','private')),
  notifications_enabled boolean NOT NULL DEFAULT true,
  notification_preferences jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(notification_preferences) = 'object'),
  CONSTRAINT user_settings_prefs_size CHECK (octet_length(notification_preferences::text) <= 4096),
  CONSTRAINT user_settings_prefs_keys CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation']) = '{}'::jsonb),
  CONSTRAINT user_settings_prefs_boolean CHECK (NOT jsonb_path_exists(notification_preferences, '$.* ? (@.type() != "boolean")')),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- REQUIRED | Tài khoản: Cổng chặn tài khoản ở database, không tin JWT còn hiệu lực.
CREATE TABLE public.account_status (
  user_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','suspended','deleting')),
  suspended_until timestamptz,
  deletion_requested_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- REQUIRED | Quan hệ: Block một chiều, hiệu lực truy cập được xét hai chiều.
CREATE TABLE public.user_blocks (
  blocker_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  blocked_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_id, blocked_id),
  CHECK (blocker_id <> blocked_id)
);

-- FUTURE | Quan hệ: Follow độc lập friendship.
CREATE TABLE public.user_follows (
  follower_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  followed_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (follower_id, followed_id),
  CHECK (follower_id <> followed_id)
);

-- CURRENT | Quan hệ: Lời mời có hướng; một pending request cho cặp.
CREATE TABLE public.friend_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  requester_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  recipient_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  pair_low uuid GENERATED ALWAYS AS (least(requester_id, recipient_id)) STORED,
  pair_high uuid GENERATED ALWAYS AS (greatest(requester_id, recipient_id)) STORED,
  client_request_id uuid NOT NULL,
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','declined','cancelled')),
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz,
  UNIQUE (requester_id, client_request_id),
  CHECK (requester_id <> recipient_id),
  CHECK ((state = 'pending') = (resolved_at IS NULL))
);

-- CURRENT | Quan hệ: Quan hệ vô hướng, một row/cặp UUID chuẩn hóa.
CREATE TABLE public.friendships (
  user_low uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  user_high uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_low, user_high),
  CHECK (user_low < user_high)
);

-- FUTURE | Nhóm: Metadata và trạng thái nhóm cộng đồng.
CREATE TABLE public.groups (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  created_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  client_request_id uuid NOT NULL,
  name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 150),
  description text NOT NULL DEFAULT '' CHECK (char_length(description) <= 5000),
  privacy text NOT NULL DEFAULT 'public' CHECK (privacy IN ('public','private')),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','archived','deleted')),
  cover_asset_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  UNIQUE (created_by, client_request_id)
);

-- FUTURE | Nhóm: Membership và vai trò scoped theo nhóm, bao gồm ban.
CREATE TABLE public.group_members (
  group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  role text NOT NULL DEFAULT 'member' CHECK (role IN ('admin','moderator','member')),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','left','removed','banned')),
  joined_at timestamptz NOT NULL DEFAULT now(),
  left_at timestamptz,
  PRIMARY KEY (group_id, user_id)
);

-- FUTURE | Nhóm: Yêu cầu join nhóm private và kết quả.
CREATE TABLE public.group_join_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','approved','declined','cancelled')),
  reviewed_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz,
  CHECK ((state = 'pending') = (resolved_at IS NULL))
);

-- CURRENT | Bài viết: Original/share dùng chung bảng; audience personal khác group context.
CREATE TABLE public.posts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  group_id uuid REFERENCES public.groups(id) ON DELETE RESTRICT,
  kind text NOT NULL DEFAULT 'original' CHECK (kind IN ('original','share')),
  original_post_id uuid REFERENCES public.posts(id) ON DELETE RESTRICT,
  client_request_id uuid NOT NULL,
  request_hash text NOT NULL CHECK (char_length(request_hash) = 64),
  body text NOT NULL DEFAULT '' CHECK (char_length(body) <= 20000),
  audience text NOT NULL DEFAULT 'public' CHECK (audience IN ('public','friends','private','group')),
  state text NOT NULL DEFAULT 'published' CHECK (state IN ('published','hidden','deleted')),
  reactions_count bigint NOT NULL DEFAULT 0 CHECK (reactions_count >= 0),
  comments_count bigint NOT NULL DEFAULT 0 CHECK (comments_count >= 0),
  shares_count bigint NOT NULL DEFAULT 0 CHECK (shares_count >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  UNIQUE (author_id, client_request_id),
  CHECK ((group_id IS NULL AND audience <> 'group') OR (group_id IS NOT NULL AND audience = 'group')),
  CHECK ((kind = 'share' AND original_post_id IS NOT NULL AND group_id IS NULL) OR (kind = 'original' AND original_post_id IS NULL)),
  CHECK (original_post_id IS NULL OR original_post_id <> id)
);

-- CURRENT | Bài viết: Một reaction enum-text/user/post, thay type không tăng tổng.
CREATE TABLE public.post_reactions (
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  reaction text NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, user_id)
);

-- CURRENT | Bài viết: Thread nhiều cấp, parent phải cùng post.
CREATE TABLE public.comments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  parent_id uuid,
  client_request_id uuid NOT NULL,
  request_hash text NOT NULL CHECK (char_length(request_hash) = 64),
  body text CHECK (char_length(body) <= 5000),
  state text NOT NULL DEFAULT 'published' CHECK (state IN ('published','hidden','deleted')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  UNIQUE (id, post_id),
  UNIQUE (author_id, client_request_id),
  FOREIGN KEY (parent_id, post_id) REFERENCES public.comments(id, post_id) ON DELETE RESTRICT,
  CHECK (parent_id IS NULL OR parent_id <> id),
  CHECK (state <> 'published' OR (body IS NOT NULL AND char_length(btrim(body)) > 0)),
  CHECK (state <> 'deleted' OR body IS NULL)
);

-- OPTIONAL | Bài viết: Reaction cho comment nếu được duyệt, không cần cho parity.
CREATE TABLE public.comment_reactions (
  comment_id uuid NOT NULL REFERENCES public.comments(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  reaction text NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (comment_id, user_id)
);

-- FUTURE | Bài viết: Bookmark cá nhân, không thêm quyền đọc post.
CREATE TABLE public.saved_posts (
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, post_id)
);

-- FUTURE | Bài viết: Preference loại bài khỏi feed, không đổi audience.
CREATE TABLE public.hidden_posts (
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, post_id)
);

-- FUTURE | Stories: Một story một asset ảnh/video, hạn24h độc lập cleanup.
CREATE TABLE public.stories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  author_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  media_asset_id uuid NOT NULL UNIQUE,
  caption text NOT NULL DEFAULT '' CHECK (char_length(caption) <= 2000),
  audience text NOT NULL DEFAULT 'friends' CHECK (audience IN ('public','friends','private')),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','deleted','archived')),
  client_request_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
  deleted_at timestamptz,
  UNIQUE (author_id, client_request_id),
  CHECK (expires_at = created_at + interval '24 hours')
);

-- FUTURE | Stories: Một viewer/story; source cho owner xem viewers.
CREATE TABLE public.story_views (
  story_id uuid NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE,
  viewer_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  first_viewed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (story_id, viewer_id)
);

-- FUTURE | Stories: Reaction cho story, một row/user.
CREATE TABLE public.story_reactions (
  story_id uuid NOT NULL REFERENCES public.stories(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  reaction text NOT NULL CHECK (reaction IN ('like','love','care','haha','wow','sad','angry')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (story_id, user_id)
);

-- CURRENT | Tin nhắn: Cùng schema cho private và group chat, sequence serialized.
CREATE TABLE public.conversations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind text NOT NULL DEFAULT 'private' CHECK (kind IN ('private','group')),
  direct_low uuid REFERENCES public.profiles(id) ON DELETE RESTRICT,
  direct_high uuid REFERENCES public.profiles(id) ON DELETE RESTRICT,
  created_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  client_request_id uuid,
  title text CHECK (char_length(title) <= 150),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','archived','deleted')),
  next_seq bigint NOT NULL DEFAULT 0 CHECK (next_seq >= 0),
  last_message_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  CHECK ((kind = 'private' AND direct_low IS NOT NULL AND direct_high IS NOT NULL AND direct_low < direct_high) OR (kind = 'group' AND direct_low IS NULL AND direct_high IS NULL)),
  UNIQUE (created_by, client_request_id),
  CHECK (kind <> 'group' OR client_request_id IS NOT NULL)
);

-- CURRENT | Tin nhắn: Membership, role và per-member read/delivery boundary.
CREATE TABLE public.conversation_members (
  conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  role text NOT NULL DEFAULT 'member' CHECK (role IN ('admin','member')),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','left','removed')),
  history_from_seq bigint NOT NULL DEFAULT 1 CHECK (history_from_seq >= 1),
  last_read_seq bigint NOT NULL DEFAULT 0 CHECK (last_read_seq >= 0),
  last_delivered_seq bigint NOT NULL DEFAULT 0 CHECK (last_delivered_seq >= 0),
  joined_at timestamptz NOT NULL DEFAULT now(),
  left_at timestamptz,
  PRIMARY KEY (conversation_id, user_id),
  CHECK (last_read_seq <= last_delivered_seq)
);

-- CURRENT | Tin nhắn: Text/media/system, retry idempotent, seq theo conversation.
CREATE TABLE public.messages (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
  seq bigint NOT NULL CHECK (seq > 0),
  sender_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  client_request_id uuid NOT NULL,
  request_hash text NOT NULL CHECK (char_length(request_hash) = 64),
  kind text NOT NULL DEFAULT 'text' CHECK (kind IN ('text','media','system')),
  body text CHECK (char_length(body) <= 10000),
  reply_to_message_id uuid,
  reply_story_id uuid REFERENCES public.stories(id) ON DELETE SET NULL,
  state text NOT NULL DEFAULT 'sent' CHECK (state IN ('sent','recalled','removed')),
  created_at timestamptz NOT NULL DEFAULT now(),
  edited_at timestamptz,
  recalled_at timestamptz,
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  UNIQUE (conversation_id, seq),
  UNIQUE (id, conversation_id),
  UNIQUE (conversation_id, sender_id, client_request_id),
  FOREIGN KEY (reply_to_message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE RESTRICT,
  CHECK (reply_to_message_id IS NULL OR reply_to_message_id <> id),
  CHECK (state = 'sent' OR body IS NULL),
  CHECK (state <> 'sent' OR kind <> 'text' OR (body IS NOT NULL AND char_length(btrim(body)) > 0))
);

-- CURRENT | Tin nhắn: Xóa phía mình: preference một row/member/message.
CREATE TABLE public.message_hidden (
  message_id uuid NOT NULL,
  conversation_id uuid NOT NULL,
  user_id uuid NOT NULL,
  hidden_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, user_id),
  FOREIGN KEY (message_id, conversation_id) REFERENCES public.messages(id, conversation_id) ON DELETE CASCADE,
  FOREIGN KEY (conversation_id, user_id) REFERENCES public.conversation_members(conversation_id, user_id) ON DELETE CASCADE
);

-- CURRENT | Media: Registry object ownership, purpose, lifecycle; không lưu signed URL.
CREATE TABLE public.media_assets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  client_request_id uuid NOT NULL,
  bucket_id text NOT NULL CHECK (bucket_id IN ('profile-media','social-media','chat-media','group-media')),
  object_path text NOT NULL,
  purpose text NOT NULL CHECK (purpose IN ('avatar','cover','post','story','message','group')),
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','ready','quarantined','pending_delete','deleted')),
  mime_type text NOT NULL,
  size_bytes bigint CHECK (size_bytes > 0 AND size_bytes <= 104857600),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  ready_at timestamptz,
  delete_after timestamptz,
  UNIQUE (bucket_id, object_path),
  CHECK ((purpose IN ('avatar','cover') AND bucket_id = 'profile-media') OR (purpose IN ('post','story') AND bucket_id = 'social-media') OR (purpose = 'message' AND bucket_id = 'chat-media') OR (purpose = 'group' AND bucket_id = 'group-media')),
  UNIQUE (owner_id, client_request_id)
);

-- CURRENT | Media: Media nhiều ảnh/video có thứ tự của bài.
CREATE TABLE public.post_media (
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT,
  position smallint NOT NULL CHECK (position BETWEEN 0 AND 9),
  PRIMARY KEY (post_id, asset_id),
  UNIQUE (post_id, position),
  UNIQUE (asset_id)
);

-- CURRENT | Media: Attachment ảnh/video/file với thứ tự.
CREATE TABLE public.message_media (
  message_id uuid NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
  asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE RESTRICT,
  position smallint NOT NULL CHECK (position BETWEEN 0 AND 9),
  PRIMARY KEY (message_id, asset_id),
  UNIQUE (message_id, position),
  UNIQUE (asset_id)
);

-- FUTURE | Thông báo: Thông báo bền vững theo recipient; không payload nội dung riêng.
CREATE TABLE public.notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  event_key text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation')),
  target_type text NOT NULL CHECK (target_type IN ('profile','post','comment','story','group','conversation','report')),
  target_id uuid NOT NULL,
  read_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (recipient_id, event_key)
);

-- FUTURE | Thông báo: Endpoint thiết bị cho push không Firebase.
CREATE TABLE public.device_subscriptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  installation_id uuid NOT NULL,
  provider text NOT NULL CHECK (provider IN ('unifiedpush','oem')),
  endpoint text NOT NULL CHECK (endpoint LIKE 'https://%'),
  state text NOT NULL DEFAULT 'active' CHECK (state IN ('active','revoked')),
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, installation_id),
  UNIQUE (endpoint)
);

-- REQUIRED | Hạ tầng: Outbox transaction cho event/cleanup/push retry, không pipeline phân tán.
CREATE TABLE public.event_outbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_key text NOT NULL UNIQUE,
  event_type text NOT NULL,
  aggregate_type text NOT NULL,
  aggregate_id uuid,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
  state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','processing','done','dead')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);

-- FUTURE | Kiểm duyệt: Vai trò hệ thống do server gán, khác group roles.
CREATE TABLE public.system_roles (
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('system_admin','moderator')),
  granted_by uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role)
);

-- FUTURE | Kiểm duyệt: Báo cáo có typed target FK, assignment và state.
CREATE TABLE public.reports (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  client_request_id uuid NOT NULL,
  target_kind text NOT NULL CHECK (target_kind IN ('post','comment','story','message','profile','group')),
  post_id uuid REFERENCES public.posts(id) ON DELETE SET NULL,
  comment_id uuid REFERENCES public.comments(id) ON DELETE SET NULL,
  story_id uuid REFERENCES public.stories(id) ON DELETE SET NULL,
  message_id uuid REFERENCES public.messages(id) ON DELETE SET NULL,
  profile_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  group_id uuid REFERENCES public.groups(id) ON DELETE SET NULL,
  reason_code text NOT NULL CHECK (char_length(reason_code) BETWEEN 1 AND 50),
  details text NOT NULL DEFAULT '' CHECK (char_length(details) <= 3000),
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','assigned','resolved','dismissed')),
  assigned_to uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz,
  UNIQUE (reporter_id, client_request_id),
  CHECK (num_nonnulls(post_id,comment_id,story_id,message_id,profile_id,group_id) <= 1),
  CHECK ((post_id IS NULL OR target_kind='post') AND (comment_id IS NULL OR target_kind='comment') AND (story_id IS NULL OR target_kind='story') AND (message_id IS NULL OR target_kind='message') AND (profile_id IS NULL OR target_kind='profile') AND (group_id IS NULL OR target_kind='group'))
);

-- FUTURE | Kiểm duyệt: Hành động xử lý gắn case, actor và lý do.
CREATE TABLE public.moderation_actions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  report_id uuid NOT NULL REFERENCES public.reports(id) ON DELETE RESTRICT,
  actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  client_request_id uuid NOT NULL,
  action text NOT NULL CHECK (action IN ('hide','remove','restore','warn','suspend','unsuspend','dismiss')),
  reason text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 3000),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (actor_id, client_request_id)
);

-- FUTURE | Kiểm duyệt: Append-only audit tối thiểu cho role/case/admin.
CREATE TABLE public.moderation_audit (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  action_id uuid REFERENCES public.moderation_actions(id) ON DELETE SET NULL,
  report_id uuid REFERENCES public.reports(id) ON DELETE SET NULL,
  operation text NOT NULL,
  subject_type text NOT NULL,
  subject_id uuid,
  safe_metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(safe_metadata) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- FUTURE | Kiểm duyệt: Kháng nghị một action và trạng thái review.
CREATE TABLE public.appeals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  action_id uuid NOT NULL REFERENCES public.moderation_actions(id) ON DELETE RESTRICT,
  appellant_id uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  body text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 3000),
  state text NOT NULL DEFAULT 'open' CHECK (state IN ('open','reviewing','accepted','rejected')),
  assigned_to uuid REFERENCES public.profiles(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz,
  UNIQUE (action_id, appellant_id)
);

-- REQUIRED | Migration: Mapping UID/document ID cũ, chỉ staging migration/server.
CREATE TABLE public.migration_id_map (
  source_system text NOT NULL DEFAULT 'firebase',
  entity_type text NOT NULL CHECK (entity_type IN ('user','post','comment','conversation','message','media')),
  legacy_id text NOT NULL,
  new_id uuid NOT NULL,
  batch_id uuid NOT NULL,
  source_checksum text,
  migrated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source_system, entity_type, legacy_id)
);


-- Indexes phục vụ query đã xác định; PK/UNIQUE tự có index.
CREATE INDEX profiles_search_idx ON public.profiles (lower(display_name) text_pattern_ops, id);
CREATE INDEX user_blocks_reverse_idx ON public.user_blocks (blocked_id, blocker_id);
CREATE INDEX user_follows_reverse_idx ON public.user_follows (followed_id, follower_id);
CREATE UNIQUE INDEX friend_requests_pending_pair_uq ON public.friend_requests (pair_low,pair_high) WHERE state='pending';
CREATE INDEX friend_requests_inbox_idx ON public.friend_requests (recipient_id, state, created_at DESC, id DESC);
CREATE INDEX friend_requests_outbox_idx ON public.friend_requests (requester_id, state, created_at DESC, id DESC);
CREATE INDEX friendships_reverse_idx ON public.friendships (user_high,user_low);
CREATE INDEX group_members_user_idx ON public.group_members (user_id,state,group_id);
CREATE INDEX group_members_admin_idx ON public.group_members (group_id,role) WHERE state='active';
CREATE UNIQUE INDEX group_join_pending_uq ON public.group_join_requests (group_id,user_id) WHERE state='pending';
CREATE INDEX group_join_review_idx ON public.group_join_requests (group_id,state,created_at,id);
CREATE INDEX posts_feed_idx ON public.posts (created_at DESC,id DESC) WHERE state='published' AND group_id IS NULL;
CREATE INDEX posts_author_idx ON public.posts (author_id,created_at DESC,id DESC);
CREATE INDEX posts_group_idx ON public.posts (group_id,created_at DESC,id DESC) WHERE group_id IS NOT NULL;
CREATE INDEX posts_original_idx ON public.posts (original_post_id) WHERE original_post_id IS NOT NULL;
CREATE INDEX post_reactions_user_idx ON public.post_reactions (user_id,post_id);
CREATE INDEX comments_thread_idx ON public.comments (post_id,parent_id,created_at,id);
CREATE INDEX comments_author_idx ON public.comments (author_id);
CREATE INDEX comment_reactions_user_idx ON public.comment_reactions (user_id,comment_id);
CREATE INDEX saved_posts_cursor_idx ON public.saved_posts (user_id,created_at DESC,post_id DESC);
CREATE INDEX stories_live_idx ON public.stories (expires_at,created_at DESC,id DESC) WHERE state='active';
CREATE INDEX stories_author_idx ON public.stories (author_id,created_at DESC,id DESC);
CREATE INDEX stories_asset_idx ON public.stories (media_asset_id);
CREATE INDEX story_views_viewer_idx ON public.story_views (viewer_id,story_id);
CREATE INDEX story_reactions_user_idx ON public.story_reactions (user_id,story_id);
CREATE UNIQUE INDEX conversations_direct_pair_uq ON public.conversations (direct_low,direct_high) WHERE kind='private';
CREATE INDEX conversations_inbox_idx ON public.conversations (updated_at DESC,id DESC);
CREATE INDEX conversation_members_user_idx ON public.conversation_members (user_id,state,conversation_id);
CREATE INDEX messages_sender_idx ON public.messages (sender_id,created_at DESC);
CREATE INDEX messages_reply_idx ON public.messages (reply_to_message_id) WHERE reply_to_message_id IS NOT NULL;
CREATE INDEX messages_story_idx ON public.messages (reply_story_id) WHERE reply_story_id IS NOT NULL;
CREATE INDEX messages_search_idx ON public.messages USING gin (to_tsvector('simple',coalesce(body,''))) WHERE state='sent' AND kind='text';
CREATE INDEX message_hidden_user_idx ON public.message_hidden (user_id,conversation_id,message_id);
CREATE INDEX media_owner_idx ON public.media_assets (owner_id,state,created_at);
CREATE INDEX media_cleanup_idx ON public.media_assets (state,delete_after);
CREATE INDEX notifications_cursor_idx ON public.notifications (recipient_id,created_at DESC,id DESC);
CREATE INDEX notifications_unread_idx ON public.notifications (recipient_id,created_at DESC) WHERE read_at IS NULL;
CREATE INDEX notifications_actor_idx ON public.notifications (actor_id);
CREATE INDEX device_subscriptions_user_idx ON public.device_subscriptions (user_id,state);
CREATE INDEX event_outbox_due_idx ON public.event_outbox (state,available_at,created_at);
CREATE INDEX reports_assignment_idx ON public.reports (assigned_to,state,created_at,id);
CREATE INDEX reports_reporter_idx ON public.reports (reporter_id,created_at DESC,id DESC);
CREATE INDEX reports_post_idx ON public.reports (post_id) WHERE post_id IS NOT NULL;
CREATE INDEX reports_message_idx ON public.reports (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX moderation_actions_case_idx ON public.moderation_actions (report_id,created_at,id);
CREATE INDEX moderation_audit_case_idx ON public.moderation_audit (report_id,created_at,id);
CREATE INDEX moderation_audit_actor_idx ON public.moderation_audit (actor_id,created_at,id);
CREATE INDEX appeals_assignment_idx ON public.appeals (assigned_to,state,created_at,id);
CREATE INDEX migration_id_map_new_idx ON public.migration_id_map (entity_type,new_id);
CREATE INDEX migration_id_map_batch_idx ON public.migration_id_map (batch_id,entity_type);

-- FK tạo sau để giải quyết dependency media/profile/group/story/conversation/message.
ALTER TABLE public.profiles ADD CONSTRAINT profiles_avatar_fk FOREIGN KEY (avatar_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;
ALTER TABLE public.profiles ADD CONSTRAINT profiles_cover_fk FOREIGN KEY (cover_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;
ALTER TABLE public.groups ADD CONSTRAINT groups_cover_fk FOREIGN KEY (cover_asset_id) REFERENCES public.media_assets(id) ON DELETE SET NULL;
ALTER TABLE public.stories ADD CONSTRAINT stories_media_fk FOREIGN KEY (media_asset_id) REFERENCES public.media_assets(id) ON DELETE RESTRICT;
ALTER TABLE public.conversations ADD CONSTRAINT conversations_last_message_fk
  FOREIGN KEY (last_message_id,id) REFERENCES public.messages(id,conversation_id) DEFERRABLE INITIALLY DEFERRED;
-- last_message_id không bao giờ do Android tự ghi; purge job clear pointer trước hard delete.

-- Enable deny-by-default ngay khi có bảng; grants/policies xem MEETO_RLS_DRAFT.sql.
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.profiles FROM PUBLIC, anon, authenticated;
ALTER TABLE public.profile_private ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.profile_private FROM PUBLIC, anon, authenticated;
ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_settings FROM PUBLIC, anon, authenticated;
ALTER TABLE public.account_status ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.account_status FROM PUBLIC, anon, authenticated;
ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_blocks FROM PUBLIC, anon, authenticated;
ALTER TABLE public.user_follows ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_follows FROM PUBLIC, anon, authenticated;
ALTER TABLE public.friend_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.friend_requests FROM PUBLIC, anon, authenticated;
ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.friendships FROM PUBLIC, anon, authenticated;
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.groups FROM PUBLIC, anon, authenticated;
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.group_members FROM PUBLIC, anon, authenticated;
ALTER TABLE public.group_join_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.group_join_requests FROM PUBLIC, anon, authenticated;
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.posts FROM PUBLIC, anon, authenticated;
ALTER TABLE public.post_reactions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.post_reactions FROM PUBLIC, anon, authenticated;
ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.comments FROM PUBLIC, anon, authenticated;
ALTER TABLE public.comment_reactions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.comment_reactions FROM PUBLIC, anon, authenticated;
ALTER TABLE public.saved_posts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.saved_posts FROM PUBLIC, anon, authenticated;
ALTER TABLE public.hidden_posts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.hidden_posts FROM PUBLIC, anon, authenticated;
ALTER TABLE public.stories ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.stories FROM PUBLIC, anon, authenticated;
ALTER TABLE public.story_views ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.story_views FROM PUBLIC, anon, authenticated;
ALTER TABLE public.story_reactions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.story_reactions FROM PUBLIC, anon, authenticated;
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.conversations FROM PUBLIC, anon, authenticated;
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.conversation_members FROM PUBLIC, anon, authenticated;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.messages FROM PUBLIC, anon, authenticated;
ALTER TABLE public.message_hidden ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.message_hidden FROM PUBLIC, anon, authenticated;
ALTER TABLE public.media_assets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.media_assets FROM PUBLIC, anon, authenticated;
ALTER TABLE public.post_media ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.post_media FROM PUBLIC, anon, authenticated;
ALTER TABLE public.message_media ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.message_media FROM PUBLIC, anon, authenticated;
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notifications FROM PUBLIC, anon, authenticated;
ALTER TABLE public.device_subscriptions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.device_subscriptions FROM PUBLIC, anon, authenticated;
ALTER TABLE public.event_outbox ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.event_outbox FROM PUBLIC, anon, authenticated;
ALTER TABLE public.system_roles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.system_roles FROM PUBLIC, anon, authenticated;
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.reports FROM PUBLIC, anon, authenticated;
ALTER TABLE public.moderation_actions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.moderation_actions FROM PUBLIC, anon, authenticated;
ALTER TABLE public.moderation_audit ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.moderation_audit FROM PUBLIC, anon, authenticated;
ALTER TABLE public.appeals ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.appeals FROM PUBLIC, anon, authenticated;
ALTER TABLE public.migration_id_map ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.migration_id_map FROM PUBLIC, anon, authenticated;

-- CROSS-ROW INVARIANTS KHÔNG THỂ DÙNG CHECK:
-- last group admin; private chat exactly 2 matching members; parent depth/immutability;
-- post non-empty text OR ready media; share original must be original/readable;
-- media purpose/ownership/single attachment across purposes; group/member role transitions;
-- read/delivered <= next_seq; reports exactly 1 typed target when created.
-- Các điều kiện trên do transaction/RPC enforce, không tuyên bố DDL tự enforce tất cả.
-- Đừng mở DML trước khi RPC và test tương ứng đã hoàn tất.

-- Một trigger mục tiêu cho CRUD settings; các nghiệp vụ khác RPC tự quản updated_at/version.
CREATE FUNCTION private.touch_settings_updated_at() RETURNS trigger
 LANGUAGE plpgsql SET search_path='' AS $$
 BEGIN NEW.updated_at := statement_timestamp(); RETURN NEW; END;
$$;
REVOKE ALL ON FUNCTION private.touch_settings_updated_at() FROM PUBLIC,anon,authenticated;
CREATE TRIGGER user_settings_touch BEFORE UPDATE ON public.user_settings
 FOR EACH ROW EXECUTE FUNCTION private.touch_settings_updated_at();

-- Không commit bản thiết kế, không ALTER PUBLICATION, không tạo bucket/job trên remote.
COMMIT;
