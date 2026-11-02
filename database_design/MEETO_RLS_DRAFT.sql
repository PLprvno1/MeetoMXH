-- MEETO RLS DESIGN DRAFT, KHÔNG PHẢI MIGRATION.
-- Nhiệm vụ04: đã kiểm PostgreSQL16.11 local với managed-schema stubs; chưa test Supabase HTTP/Realtime thật.
-- Chạy staging chỉ sau review và chuyển thành migration riêng.
BEGIN;
DO $design_guard$ BEGIN
 RAISE EXCEPTION 'DESIGN ONLY: RLS draft is not approved for deployment';
END $design_guard$;
REVOKE ALL ON SCHEMA private FROM PUBLIC,anon,authenticated;
GRANT USAGE ON SCHEMA private TO authenticated;

-- Helpers chạy với owner có BYPASSRLS tại môi trường Supabase test sau này.
-- private không là exposed schema; auth.uid luôn caller, không có actor_id do client truyền.
CREATE FUNCTION private.account_ok() RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT auth.uid() IS NOT NULL
    AND coalesce((auth.jwt()->>'is_anonymous')::boolean,false)=false
    AND EXISTS (SELECT 1 FROM public.account_status s WHERE s.user_id=auth.uid() AND s.state='active');
$$;
CREATE FUNCTION private.is_active() RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.account_ok() AND EXISTS (
    SELECT 1 FROM auth.users u JOIN public.profiles p ON p.id=u.id
    WHERE u.id=auth.uid() AND p.onboarding_completed
    AND (u.email_confirmed_at IS NOT NULL OR u.phone_confirmed_at IS NOT NULL));
$$;
CREATE FUNCTION private.is_blocked(other_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS (SELECT 1 FROM public.user_blocks b
    WHERE (b.blocker_id=auth.uid() AND b.blocked_id=other_id)
       OR (b.blocked_id=auth.uid() AND b.blocker_id=other_id));
$$;
CREATE FUNCTION private.are_friends(other_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS (SELECT 1 FROM public.friendships f
    WHERE f.user_low=least(auth.uid(),other_id) AND f.user_high=greatest(auth.uid(),other_id));
$$;
CREATE FUNCTION private.has_role(role_name text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (SELECT 1 FROM public.system_roles r WHERE r.user_id=auth.uid() AND r.role=role_name);
$$;
CREATE FUNCTION private.group_member(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (SELECT 1 FROM public.group_members m
    JOIN public.groups g ON g.id=m.group_id
    WHERE m.group_id=gid AND m.user_id=auth.uid() AND m.state='active' AND g.state='active');
$$;
CREATE FUNCTION private.group_manager(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.group_member(gid) AND EXISTS (SELECT 1 FROM public.group_members m
    WHERE m.group_id=gid AND m.user_id=auth.uid() AND m.role IN ('admin','moderator') AND m.state='active');
$$;
CREATE FUNCTION private.can_read_group(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (SELECT 1 FROM public.groups g
    WHERE g.id=gid AND g.state='active' AND (g.privacy='public' OR private.group_member(g.id)));
$$;
CREATE FUNCTION private.can_read_profile(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT (pid=auth.uid() AND private.account_ok()) OR
    (private.is_active() AND NOT private.is_blocked(pid) AND EXISTS (
      SELECT 1 FROM public.profiles p JOIN public.user_settings u ON u.user_id=p.id
      JOIN public.account_status s ON s.user_id=p.id
      WHERE p.id=pid AND s.state='active' AND
      (u.profile_audience='public' OR (u.profile_audience='friends' AND private.are_friends(pid)))));
$$;
CREATE FUNCTION private.can_read_post_base(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (
    SELECT 1 FROM public.posts p JOIN public.account_status s ON s.user_id=p.author_id
    WHERE p.id=pid AND p.state='published' AND s.state='active'
      AND NOT private.is_blocked(p.author_id)
      AND ((p.group_id IS NULL AND (p.author_id=auth.uid() OR p.audience='public'
           OR (p.audience='friends' AND private.are_friends(p.author_id))))
        OR (p.group_id IS NOT NULL AND private.can_read_group(p.group_id))));
$$;
CREATE FUNCTION private.can_read_post(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.can_read_post_base(pid) AND EXISTS (SELECT 1 FROM public.posts p WHERE p.id=pid AND
    (p.kind='original' OR p.author_id=auth.uid() OR EXISTS (
      SELECT 1 FROM public.posts original WHERE original.id=p.original_post_id
        AND original.kind='original' AND private.can_read_post_base(original.id))));
$$;
CREATE FUNCTION private.can_interact_post(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.can_read_post(pid) AND EXISTS (SELECT 1 FROM public.posts p WHERE p.id=pid
    AND (p.group_id IS NULL OR private.group_member(p.group_id)));
$$;
CREATE FUNCTION private.can_read_comment(cid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS (SELECT 1 FROM public.comments c WHERE c.id=cid
    AND private.can_read_post(c.post_id) AND c.state IN ('published','deleted')
    AND (c.author_id IS NULL OR NOT private.is_blocked(c.author_id)));
-- Tombstone comment phải được redacted body=NULL; parent_id bất biến để giữ thread.
$$;
CREATE FUNCTION private.can_read_story(sid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (
    SELECT 1 FROM public.stories s JOIN public.account_status a ON a.user_id=s.author_id
    WHERE s.id=sid AND a.state='active' AND NOT private.is_blocked(s.author_id)
      AND ((s.state='archived' AND s.author_id=auth.uid())
        OR (s.state='active' AND s.expires_at>now()
          AND (s.author_id=auth.uid() OR s.audience='public'
            OR (s.audience='friends' AND private.are_friends(s.author_id))))));
$$;
CREATE FUNCTION private.can_read_conversation(cid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (
    SELECT 1 FROM public.conversations c JOIN public.conversation_members m ON m.conversation_id=c.id
    WHERE c.id=cid AND c.state='active' AND m.user_id=auth.uid() AND m.state='active'
      AND (c.kind='group' OR (NOT private.is_blocked(c.direct_low) AND NOT private.is_blocked(c.direct_high))));
$$;
CREATE FUNCTION private.can_read_message(mid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.messages msg JOIN public.conversation_members m ON m.conversation_id=msg.conversation_id
    WHERE msg.id=mid AND m.user_id=auth.uid() AND m.state='active'
      AND private.can_read_conversation(msg.conversation_id) AND msg.seq>=m.history_from_seq
      AND (msg.sender_id IS NULL OR NOT private.is_blocked(msg.sender_id))
      AND NOT EXISTS (SELECT 1 FROM public.message_hidden h WHERE h.message_id=msg.id AND h.user_id=auth.uid()));
-- Recalled/removed row còn được đọc dưới dạng tombstone; body phải NULL và media disabled.
$$;
CREATE FUNCTION private.can_read_media(aid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (SELECT 1 FROM public.media_assets a WHERE a.id=aid AND
    ((a.state='pending' AND a.owner_id=auth.uid() AND a.created_at>now()-interval '15 minutes')
     OR (a.state='ready' AND (
       EXISTS (SELECT 1 FROM public.profiles p WHERE ((p.avatar_asset_id=a.id AND a.purpose='avatar') OR (p.cover_asset_id=a.id AND a.purpose='cover')) AND a.owner_id=p.id AND private.can_read_profile(p.id))
       OR EXISTS (SELECT 1 FROM public.groups g WHERE g.cover_asset_id=a.id AND a.purpose='group' AND private.can_read_group(g.id))
       OR EXISTS (SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id WHERE pm.asset_id=a.id AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(pm.post_id))
       OR EXISTS (SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND a.purpose='story' AND a.owner_id=s.author_id AND private.can_read_story(s.id))
       OR EXISTS (SELECT 1 FROM public.message_media mm JOIN public.messages msg ON msg.id=mm.message_id
         WHERE mm.asset_id=a.id AND a.purpose='message' AND a.owner_id=msg.sender_id AND msg.state='sent' AND private.can_read_message(msg.id))))));
-- Không có owner bypass cho ready media: ảnh đã recall không còn readable kể cả người gửi.
$$;
CREATE FUNCTION private.can_upload_object(bucket text, path text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.is_active() AND EXISTS (SELECT 1 FROM public.media_assets a
    WHERE a.bucket_id=bucket AND a.object_path=path AND a.owner_id=auth.uid()
      AND a.state='pending' AND a.created_at>now()-interval '15 minutes');
$$;
CREATE FUNCTION private.can_read_object(bucket text, path text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS (SELECT 1 FROM public.media_assets a WHERE a.bucket_id=bucket AND a.object_path=path
    AND private.can_read_media(a.id));
$$;
CREATE FUNCTION private.can_review_report(rid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.has_role('system_admin') OR (private.has_role('moderator') AND EXISTS (
    SELECT 1 FROM public.reports r WHERE r.id=rid AND r.assigned_to=auth.uid()));
$$;

CREATE FUNCTION private.can_subscribe_topic(topic text) RETURNS boolean
 LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
 BEGIN
  IF topic='user:' || auth.uid()::text THEN RETURN private.is_active(); END IF;
  IF topic ~ '^post-comments:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
   RETURN private.can_read_post(split_part(topic,':',2)::uuid);
  END IF;
  RETURN false;
 END;
$$;

REVOKE ALL ON FUNCTION private.account_ok() FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.account_ok() TO authenticated;
REVOKE ALL ON FUNCTION private.is_active() FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.is_active() TO authenticated;
REVOKE ALL ON FUNCTION private.is_blocked(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.is_blocked(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.are_friends(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.are_friends(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.has_role(text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.has_role(text) TO authenticated;
REVOKE ALL ON FUNCTION private.group_member(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.group_member(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.group_manager(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.group_manager(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_group(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_group(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_profile(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_profile(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_post_base(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_post_base(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_post(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_post(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_interact_post(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_interact_post(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_comment(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_comment(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_story(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_story(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_conversation(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_conversation(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_message(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_message(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_media(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_media(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_upload_object(text, text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_upload_object(text, text) TO authenticated;
REVOKE ALL ON FUNCTION private.can_read_object(text, text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_read_object(text, text) TO authenticated;
REVOKE ALL ON FUNCTION private.can_review_report(uuid) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_review_report(uuid) TO authenticated;
REVOKE ALL ON FUNCTION private.can_subscribe_topic(text) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.can_subscribe_topic(text) TO authenticated;
-- Helpers chỉ trả boolean với actor auth.uid(); private không exposed Data API.
-- service_role không Android. Worker grants/roles cần migration vận hành riêng.
-- Tài khoản / profiles
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.profiles FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.profiles TO authenticated;
CREATE POLICY profiles_select ON public.profiles FOR SELECT TO authenticated USING (private.can_read_profile(id));

-- Tài khoản / profile_private
ALTER TABLE public.profile_private ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.profile_private FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.profile_private TO authenticated;
CREATE POLICY profile_private_select ON public.profile_private FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));

-- Tài khoản / user_settings
ALTER TABLE public.user_settings ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_settings FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.user_settings TO authenticated;
CREATE POLICY user_settings_select ON public.user_settings FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));

-- Tài khoản / account_status
ALTER TABLE public.account_status ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.account_status FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.account_status TO authenticated;
CREATE POLICY account_status_select ON public.account_status FOR SELECT TO authenticated USING (user_id=(select auth.uid()) AND auth.uid() IS NOT NULL);

-- Quan hệ / user_blocks
ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_blocks FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.user_blocks TO authenticated;
CREATE POLICY user_blocks_select ON public.user_blocks FOR SELECT TO authenticated USING (private.is_active() AND blocker_id=(select auth.uid()));

-- Quan hệ / user_follows
ALTER TABLE public.user_follows ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.user_follows FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.user_follows TO authenticated;
CREATE POLICY user_follows_select ON public.user_follows FOR SELECT TO authenticated USING (private.is_active() AND (follower_id=(select auth.uid()) OR followed_id=(select auth.uid())) AND NOT private.is_blocked(CASE WHEN follower_id=auth.uid() THEN followed_id ELSE follower_id END));

-- Quan hệ / friend_requests
ALTER TABLE public.friend_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.friend_requests FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.friend_requests TO authenticated;
CREATE POLICY friend_requests_select ON public.friend_requests FOR SELECT TO authenticated USING (private.is_active() AND (requester_id=(select auth.uid()) OR recipient_id=(select auth.uid())));

-- Quan hệ / friendships
ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.friendships FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.friendships TO authenticated;
CREATE POLICY friendships_select ON public.friendships FOR SELECT TO authenticated USING (private.is_active() AND (user_low=(select auth.uid()) OR user_high=(select auth.uid())));

-- Nhóm / groups
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.groups FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.groups TO authenticated;
CREATE POLICY groups_select ON public.groups FOR SELECT TO authenticated USING (private.can_read_group(id));

-- Nhóm / group_members
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.group_members FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.group_members TO authenticated;
CREATE POLICY group_members_select ON public.group_members FOR SELECT TO authenticated USING (private.group_member(group_id));

-- Nhóm / group_join_requests
ALTER TABLE public.group_join_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.group_join_requests FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.group_join_requests TO authenticated;
CREATE POLICY group_join_requests_select ON public.group_join_requests FOR SELECT TO authenticated USING (private.is_active() AND (user_id=(select auth.uid()) OR private.group_manager(group_id)));

-- Bài viết / posts
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.posts FROM PUBLIC,anon,authenticated;
GRANT SELECT (id,author_id,group_id,kind,original_post_id,client_request_id,body,audience,state,reactions_count,comments_count,shares_count,created_at,updated_at,deleted_at,version) ON public.posts TO authenticated;
CREATE POLICY posts_select ON public.posts FOR SELECT TO authenticated USING (private.can_read_post(id));

-- Bài viết / post_reactions
ALTER TABLE public.post_reactions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.post_reactions FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.post_reactions TO authenticated;
CREATE POLICY post_reactions_select ON public.post_reactions FOR SELECT TO authenticated USING (private.can_read_post(post_id) AND NOT private.is_blocked(user_id));

-- Bài viết / comments
ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.comments FROM PUBLIC,anon,authenticated;
GRANT SELECT (id,post_id,author_id,parent_id,client_request_id,body,state,created_at,updated_at,version) ON public.comments TO authenticated;
CREATE POLICY comments_select ON public.comments FOR SELECT TO authenticated USING (private.can_read_comment(id));

-- Bài viết / comment_reactions
ALTER TABLE public.comment_reactions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.comment_reactions FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.comment_reactions TO authenticated;
CREATE POLICY comment_reactions_select ON public.comment_reactions FOR SELECT TO authenticated USING (private.can_read_comment(comment_id) AND NOT private.is_blocked(user_id));

-- Bài viết / saved_posts
ALTER TABLE public.saved_posts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.saved_posts FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.saved_posts TO authenticated;
CREATE POLICY saved_posts_select ON public.saved_posts FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));

-- Bài viết / hidden_posts
ALTER TABLE public.hidden_posts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.hidden_posts FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.hidden_posts TO authenticated;
CREATE POLICY hidden_posts_select ON public.hidden_posts FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));

-- Stories / stories
ALTER TABLE public.stories ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.stories FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.stories TO authenticated;
CREATE POLICY stories_select ON public.stories FOR SELECT TO authenticated USING (private.can_read_story(id));

-- Stories / story_views
ALTER TABLE public.story_views ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.story_views FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.story_views TO authenticated;
CREATE POLICY story_views_select ON public.story_views FOR SELECT TO authenticated USING (private.can_read_story(story_id) AND (viewer_id=(select auth.uid()) OR EXISTS (SELECT 1 FROM public.stories s WHERE s.id=story_id AND s.author_id=(select auth.uid()))));

-- Stories / story_reactions
ALTER TABLE public.story_reactions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.story_reactions FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.story_reactions TO authenticated;
CREATE POLICY story_reactions_select ON public.story_reactions FOR SELECT TO authenticated USING (private.can_read_story(story_id) AND (user_id=(select auth.uid()) OR EXISTS (SELECT 1 FROM public.stories s WHERE s.id=story_id AND s.author_id=(select auth.uid()))));

-- Tin nhắn / conversations
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.conversations FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.conversations TO authenticated;
CREATE POLICY conversations_select ON public.conversations FOR SELECT TO authenticated USING (private.can_read_conversation(id));

-- Tin nhắn / conversation_members
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.conversation_members FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.conversation_members TO authenticated;
CREATE POLICY conversation_members_select ON public.conversation_members FOR SELECT TO authenticated USING (private.can_read_conversation(conversation_id));

-- Tin nhắn / messages
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.messages FROM PUBLIC,anon,authenticated;
GRANT SELECT (id,conversation_id,seq,sender_id,client_request_id,kind,body,reply_to_message_id,reply_story_id,state,created_at,edited_at,recalled_at,version) ON public.messages TO authenticated;
CREATE POLICY messages_select ON public.messages FOR SELECT TO authenticated USING (private.can_read_message(id));

-- Tin nhắn / message_hidden
ALTER TABLE public.message_hidden ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.message_hidden FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.message_hidden TO authenticated;
CREATE POLICY message_hidden_select ON public.message_hidden FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()) AND private.can_read_conversation(conversation_id));

-- Media / media_assets
ALTER TABLE public.media_assets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.media_assets FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.media_assets TO authenticated;
CREATE POLICY media_assets_select ON public.media_assets FOR SELECT TO authenticated USING (private.can_read_media(id));

-- Media / post_media
ALTER TABLE public.post_media ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.post_media FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.post_media TO authenticated;
CREATE POLICY post_media_select ON public.post_media FOR SELECT TO authenticated USING (private.can_read_post(post_id));

-- Media / message_media
ALTER TABLE public.message_media ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.message_media FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.message_media TO authenticated;
CREATE POLICY message_media_select ON public.message_media FOR SELECT TO authenticated USING (private.can_read_message(message_id) AND EXISTS (SELECT 1 FROM public.messages m WHERE m.id=message_id AND m.state='sent'));

-- Thông báo / notifications
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.notifications FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.notifications TO authenticated;
CREATE POLICY notifications_select ON public.notifications FOR SELECT TO authenticated USING (private.is_active() AND recipient_id=(select auth.uid()));

-- Thông báo / device_subscriptions
ALTER TABLE public.device_subscriptions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.device_subscriptions FROM PUBLIC,anon,authenticated;
GRANT SELECT (id,user_id,installation_id,provider,state,created_at,last_seen_at) ON public.device_subscriptions TO authenticated;
CREATE POLICY device_subscriptions_select ON public.device_subscriptions FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));

-- Hạ tầng / event_outbox
ALTER TABLE public.event_outbox ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.event_outbox FROM PUBLIC,anon,authenticated;
-- Server only: không SELECT policy/grant Android.

-- Kiểm duyệt / system_roles
ALTER TABLE public.system_roles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.system_roles FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.system_roles TO authenticated;
CREATE POLICY system_roles_select ON public.system_roles FOR SELECT TO authenticated USING (private.is_active() AND user_id=(select auth.uid()));

-- Kiểm duyệt / reports
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.reports FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.reports TO authenticated;
CREATE POLICY reports_select ON public.reports FOR SELECT TO authenticated USING (private.can_review_report(id));

-- Kiểm duyệt / moderation_actions
ALTER TABLE public.moderation_actions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.moderation_actions FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.moderation_actions TO authenticated;
CREATE POLICY moderation_actions_select ON public.moderation_actions FOR SELECT TO authenticated USING (private.can_review_report(report_id));

-- Kiểm duyệt / moderation_audit
ALTER TABLE public.moderation_audit ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.moderation_audit FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.moderation_audit TO authenticated;
CREATE POLICY moderation_audit_select ON public.moderation_audit FOR SELECT TO authenticated USING (private.has_role('system_admin') OR (report_id IS NOT NULL AND private.can_review_report(report_id)));

-- Kiểm duyệt / appeals
ALTER TABLE public.appeals ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.appeals FROM PUBLIC,anon,authenticated;
GRANT SELECT ON public.appeals TO authenticated;
CREATE POLICY appeals_select ON public.appeals FOR SELECT TO authenticated USING (private.is_active() AND (appellant_id=(select auth.uid()) OR private.has_role('system_admin') OR (private.has_role('moderator') AND assigned_to=(select auth.uid()))));

-- Migration / migration_id_map
ALTER TABLE public.migration_id_map ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.migration_id_map FROM PUBLIC,anon,authenticated;
-- Server only: không SELECT policy/grant Android.

-- profile_private mutation chỉ RPC, validate tuổi/calendar/ownership; no direct UPDATE.
GRANT UPDATE (profile_audience,last_seen_audience,notifications_enabled,notification_preferences) ON public.user_settings TO authenticated;
CREATE POLICY user_settings_update ON public.user_settings FOR UPDATE TO authenticated
 USING (private.is_active() AND user_id=auth.uid())
 WITH CHECK (private.is_active() AND user_id=auth.uid());
-- Timestamp user_settings do trigger trong schema draft quản lý.

GRANT INSERT (user_id,post_id) ON public.saved_posts,public.hidden_posts TO authenticated;
GRANT DELETE ON public.saved_posts,public.hidden_posts TO authenticated;
CREATE POLICY saved_posts_insert ON public.saved_posts FOR INSERT TO authenticated
 WITH CHECK (private.is_active() AND user_id=auth.uid() AND private.can_read_post(post_id));
CREATE POLICY saved_posts_delete ON public.saved_posts FOR DELETE TO authenticated
 USING (private.is_active() AND user_id=auth.uid());
CREATE POLICY hidden_posts_insert ON public.hidden_posts FOR INSERT TO authenticated
 WITH CHECK (private.is_active() AND user_id=auth.uid() AND private.can_read_post(post_id));
CREATE POLICY hidden_posts_delete ON public.hidden_posts FOR DELETE TO authenticated
 USING (private.is_active() AND user_id=auth.uid());

-- Không INSERT/UPDATE/DELETE client cho role/status/counter/membership/message/report/media/outbox.
-- mark_notifications_read và mọi mutation nghiệp vụ: RPC contract, chưa viết function body ở design.
-- Không tạo stub bypass hoặc grant rộng để bù cho RPC chưa có.

-- STORAGE: không tạo bucket, không ALTER managed table; 4 bucket phải PRIVATE khi triển khai.
CREATE POLICY meeto_private_object_insert ON storage.objects FOR INSERT TO authenticated
 WITH CHECK (bucket_id IN ('profile-media','social-media','chat-media','group-media')
   AND owner_id=auth.uid()::text AND private.can_upload_object(bucket_id,name));
CREATE POLICY meeto_private_object_read ON storage.objects FOR SELECT TO authenticated
 USING (bucket_id IN ('profile-media','social-media','chat-media','group-media')
   AND storage.allow_any_operation(ARRAY['object.get_authenticated_info','object.get_authenticated'])
   AND private.can_read_object(bucket_id,name));
-- Storage version không có operation helpers -> deny direct reads, dùng authenticated Edge proxy.
-- No client sign/list/update/upsert/delete; policies permissive cũ phải review/retire sau duyệt.

-- REALTIME: không ALTER publication, không tạo trigger/channel/job trong draft.
-- Disable Allow public access ở Dashboard là deployment gate, publisher/client private:true.
-- user:<uid> hoặc post-comments:<authorized post UUID>, payload generic invalidation only.
-- Broadcast auth cache còn activity timing sau revoke tới JWT refresh: xem STORAGE_REALTIME.
CREATE POLICY meeto_receive_scoped_invalidations ON realtime.messages FOR SELECT TO authenticated
 USING (private.can_subscribe_topic(realtime.topic()) AND extension='broadcast');
-- No client INSERT Broadcast; publisher rechecks recipients, không body/URL/request_hash/author.
-- No Presence policy trước khi duyệt last_seen privacy.
ROLLBACK;

