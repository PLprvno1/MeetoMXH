-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
-- LOCAL regression patch only, source draft guards retained in original files.
BEGIN;
ALTER DEFAULT PRIVILEGES IN SCHEMA public, private REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER TABLE public.user_settings
 ADD CONSTRAINT user_settings_prefs_size CHECK (octet_length(notification_preferences::text) <= 4096),
 ADD CONSTRAINT user_settings_prefs_keys CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation']) = '{}'::jsonb),
 ADD CONSTRAINT user_settings_prefs_boolean CHECK (NOT jsonb_path_exists(notification_preferences, '$.* ? (@.type() != "boolean")'));
ALTER TABLE public.stories ADD CONSTRAINT stories_media_asset_id_key UNIQUE (media_asset_id);
CREATE OR REPLACE FUNCTION private.can_read_media(aid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
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
COMMIT;

