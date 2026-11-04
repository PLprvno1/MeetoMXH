-- Task 17: transactional in-app notifications over the existing recipient-only table.
DO $$
BEGIN
  IF to_regclass('public.notifications') IS NULL OR to_regclass('public.user_settings') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.profiles') IS NULL
     OR to_regclass('public.posts') IS NULL OR to_regclass('public.post_reactions') IS NULL
     OR to_regclass('public.comments') IS NULL OR to_regclass('public.friend_requests') IS NULL
     OR to_regclass('public.messages') IS NULL OR to_regclass('public.conversation_members') IS NULL
     OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_comment(uuid)') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.can_read_conversation(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.can_read_story(uuid)') IS NULL
     OR to_regprocedure('private.is_blocked(uuid)') IS NULL
     OR to_regprocedure('private.require_identity()') IS NULL
     OR to_regprocedure('private.finalize_direct_upload(uuid)') IS NULL
     OR to_regprocedure('private.finalize_story_upload(uuid)') IS NULL
     OR to_regprocedure('private.reply_to_story(uuid,uuid,text)') IS NULL
     OR to_regprocedure('public.create_story(uuid,text,text,uuid)') IS NULL
     OR to_regclass('public.stories') IS NULL
     OR to_regprocedure('realtime.send(jsonb,text,text,boolean)') IS NULL
     OR to_regclass('public.saved_posts') IS NULL OR to_regclass('public.user_follows') IS NULL
     OR to_regprocedure('private.set_follow(uuid,boolean)') IS NULL
     OR to_regprocedure('public.set_follow(uuid,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Notifications prerequisites missing; verify Cloud migrations 019-021 and schema first';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relname='notifications' AND c.relrowsecurity)
     OR NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='notifications'
       AND policyname='notifications_select' AND cmd='SELECT'
       AND qual ILIKE '%recipient_id%' AND qual ILIKE '%auth.uid%' AND qual ILIKE '%is_active%')
     OR has_table_privilege('authenticated','public.notifications','SELECT')
     OR has_table_privilege('authenticated','public.notifications','INSERT')
     OR has_table_privilege('authenticated','public.notifications','UPDATE')
     OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.profiles'::regclass
       AND attname='public_avatar_preset' AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Recipient-only notification RLS must remain enabled';
  END IF;
END $$;

ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_kind_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check
  CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow'));
ALTER TABLE public.user_settings DROP CONSTRAINT IF EXISTS user_settings_prefs_keys;
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys
  CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow']) = '{}'::jsonb);

CREATE OR REPLACE FUNCTION private.emit_notification(p_recipient uuid,p_actor uuid,p_kind text,
  p_target_type text,p_target_id uuid,p_event_key text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE notification_id uuid;
BEGIN
  IF p_recipient IS NULL OR p_actor IS NULL OR p_recipient=p_actor OR p_target_id IS NULL
     OR p_kind NOT IN ('friend_request','friend_accepted','reaction','comment','reply','story','follow')
     OR p_target_type NOT IN ('profile','post','comment','story','conversation')
     OR p_event_key IS NULL OR char_length(p_event_key)>240 THEN RETURN; END IF;
  IF private.is_blocked(p_recipient) OR NOT EXISTS (
    SELECT 1 FROM public.account_status s JOIN public.profiles p ON p.id=s.user_id
      WHERE s.user_id=p_recipient AND s.state='active' AND p.onboarding_completed) THEN RETURN; END IF;
  IF EXISTS (SELECT 1 FROM public.user_settings s WHERE s.user_id=p_recipient
      AND (NOT s.notifications_enabled OR coalesce((s.notification_preferences->>p_kind)::boolean,true)=false)) THEN RETURN; END IF;
  INSERT INTO public.notifications(recipient_id,actor_id,event_key,kind,target_type,target_id)
    VALUES(p_recipient,p_actor,p_event_key,p_kind,p_target_type,p_target_id)
    ON CONFLICT(recipient_id,event_key) DO NOTHING RETURNING id INTO notification_id;
  IF notification_id IS NOT NULL THEN
    PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',notification_id,'scope','notifications'),
      'invalidate','user:'||p_recipient::text,true);
  END IF;
END $$;

CREATE OR REPLACE FUNCTION private.notify_post_reaction() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE owner_id uuid;
BEGIN
  SELECT author_id INTO owner_id FROM public.posts WHERE id=NEW.post_id;
  PERFORM private.emit_notification(owner_id,NEW.user_id,'reaction','post',NEW.post_id,
    'reaction:'||NEW.post_id::text||':'||NEW.user_id::text||':'||md5(NEW.created_at::text));
  RETURN NEW;
END $$;
CREATE TRIGGER notifications_post_reaction AFTER INSERT ON public.post_reactions
  FOR EACH ROW EXECUTE FUNCTION private.notify_post_reaction();

CREATE OR REPLACE FUNCTION private.notify_comment() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE post_owner uuid; parent_owner uuid;
BEGIN
  SELECT author_id INTO post_owner FROM public.posts WHERE id=NEW.post_id;
  IF NEW.parent_id IS NOT NULL THEN SELECT author_id INTO parent_owner FROM public.comments WHERE id=NEW.parent_id; END IF;
  IF parent_owner IS NOT NULL THEN
    PERFORM private.emit_notification(parent_owner,NEW.author_id,'reply','comment',NEW.id,'comment:'||NEW.id::text);
  END IF;
  IF post_owner IS DISTINCT FROM parent_owner THEN
    PERFORM private.emit_notification(post_owner,NEW.author_id,'comment','comment',NEW.id,'comment:'||NEW.id::text);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER notifications_comment AFTER INSERT ON public.comments
  FOR EACH ROW WHEN (NEW.state='published' AND NEW.author_id IS NOT NULL)
  EXECUTE FUNCTION private.notify_comment();

CREATE OR REPLACE FUNCTION private.notify_friend_request() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.emit_notification(NEW.recipient_id,NEW.requester_id,'friend_request','profile',NEW.requester_id,
    'friend-request:'||NEW.id::text);
  RETURN NEW;
END $$;
CREATE TRIGGER notifications_friend_request AFTER INSERT ON public.friend_requests
  FOR EACH ROW WHEN (NEW.state='pending') EXECUTE FUNCTION private.notify_friend_request();

CREATE OR REPLACE FUNCTION private.notify_friend_accepted() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF OLD.state='pending' AND NEW.state='accepted' THEN
    PERFORM private.emit_notification(NEW.requester_id,NEW.recipient_id,'friend_accepted','profile',NEW.recipient_id,
      'friend-accepted:'||NEW.id::text);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER notifications_friend_accepted AFTER UPDATE OF state ON public.friend_requests
  FOR EACH ROW EXECUTE FUNCTION private.notify_friend_accepted();

CREATE OR REPLACE FUNCTION private.notify_follow() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.emit_notification(NEW.followed_id,NEW.follower_id,'follow','profile',NEW.follower_id,
    'follow:'||NEW.follower_id::text||':'||NEW.followed_id::text||':'||md5(NEW.created_at::text));
  RETURN NEW;
END $$;
CREATE TRIGGER notifications_follow AFTER INSERT ON public.user_follows
  FOR EACH ROW EXECUTE FUNCTION private.notify_follow();

CREATE OR REPLACE FUNCTION private.notify_story_reply() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE owner_id uuid;
BEGIN
  IF OLD.reply_story_id IS NULL AND NEW.reply_story_id IS NOT NULL AND NEW.state='sent' THEN
    SELECT author_id INTO owner_id FROM public.stories WHERE id=NEW.reply_story_id
      AND state='active' AND expires_at>statement_timestamp();
    PERFORM private.emit_notification(owner_id,NEW.sender_id,'story','conversation',NEW.conversation_id,
      'story-reply:'||NEW.id::text);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER notifications_story_reply AFTER UPDATE OF reply_story_id ON public.messages
  FOR EACH ROW EXECUTE FUNCTION private.notify_story_reply();

CREATE FUNCTION private.notification_target_readable(p_type text,p_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT CASE p_type
    WHEN 'profile' THEN EXISTS(SELECT 1 FROM public.profiles p WHERE p.id=p_id AND private.can_read_profile(p.id))
    WHEN 'post' THEN private.can_read_post(p_id)
    WHEN 'comment' THEN private.can_read_comment(p_id)
    WHEN 'story' THEN private.can_read_story(p_id)
    WHEN 'conversation' THEN private.can_read_conversation(p_id)
    ELSE false END
$$;

CREATE FUNCTION private.get_notifications(p_unread_only boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_unread_only IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_notification_filter'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',q.id,'kind',q.kind,'target_type',q.target_type,
      'target_id',CASE WHEN private.notification_target_readable(q.target_type,q.target_id) THEN q.target_id END,
      'created_at',q.created_at,'read_at',q.read_at,
      'target_context_id',CASE WHEN q.target_type='comment' AND private.notification_target_readable(q.target_type,q.target_id)
        THEN (SELECT c.post_id FROM public.comments c WHERE c.id=q.target_id) END,
      'actor_id',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN q.actor_id END,
      'actor_name',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.display_name ELSE 'Meeto user' END,
      'actor_avatar_asset_id',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.avatar_asset_id END,
      'actor_avatar_preset',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.public_avatar_preset ELSE 'neutral' END,
      'target_available',private.notification_target_readable(q.target_type,q.target_id)) ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT n.* FROM public.notifications n WHERE n.recipient_id=actor
      AND (NOT p_unread_only OR n.read_at IS NULL)
      AND (p_after_time IS NULL OR (n.created_at,n.id)<(p_after_time,p_after_id))
      ORDER BY n.created_at DESC,n.id DESC LIMIT p_limit) q
      LEFT JOIN public.profiles p ON p.id=q.actor_id),'[]'::jsonb);
END $$;

CREATE FUNCTION private.get_notification_unread_count() RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  RETURN (SELECT count(*) FROM public.notifications WHERE recipient_id=auth.uid() AND read_at IS NULL);
END $$;
CREATE FUNCTION private.mark_notification_read(p_notification_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  IF p_notification_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_notification'; END IF;
  UPDATE public.notifications SET read_at=coalesce(read_at,statement_timestamp())
    WHERE id=p_notification_id AND recipient_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=0 THEN RAISE SQLSTATE 'PT404' USING MESSAGE='notification_unavailable'; END IF;
  PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',gen_random_uuid(),'scope','notifications'),
    'invalidate','user:'||actor::text,true);
  RETURN jsonb_build_object('notification_id',p_notification_id,'read',true);
END $$;
CREATE FUNCTION private.mark_all_notifications_read() RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  UPDATE public.notifications SET read_at=statement_timestamp() WHERE recipient_id=actor AND read_at IS NULL;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed>0 THEN
    PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',gen_random_uuid(),'scope','notifications'),
      'invalidate','user:'||actor::text,true);
  END IF;
  RETURN jsonb_build_object('updated',changed);
END $$;
CREATE FUNCTION private.get_notification_settings() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  RETURN (SELECT jsonb_build_object('enabled',notifications_enabled,'preferences',notification_preferences)
    FROM public.user_settings WHERE user_id=auth.uid());
END $$;
CREATE FUNCTION private.update_notification_settings(p_enabled boolean,p_preferences jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); merged jsonb;
BEGIN
  IF p_enabled IS NULL OR p_preferences IS NULL OR jsonb_typeof(p_preferences)<>'object'
     OR (p_preferences-ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow'])<>'{}'::jsonb
     OR jsonb_path_exists(p_preferences,'$.* ? (@.type() != "boolean")') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_notification_settings'; END IF;
  SELECT notification_preferences||p_preferences INTO merged FROM public.user_settings WHERE user_id=actor FOR UPDATE;
  UPDATE public.user_settings SET notifications_enabled=p_enabled,notification_preferences=merged,updated_at=statement_timestamp()
    WHERE user_id=actor;
  RETURN jsonb_build_object('enabled',p_enabled,'preferences',merged);
END $$;

CREATE FUNCTION private.get_message_unread_count() RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); total bigint;
BEGIN
  PERFORM private.b_active();
  SELECT count(*) INTO total FROM public.messages m JOIN public.conversation_members cm
    ON cm.conversation_id=m.conversation_id AND cm.user_id=actor AND cm.state='active'
    JOIN public.conversations c ON c.id=m.conversation_id
  WHERE m.seq>cm.last_read_seq AND m.sender_id<>actor AND m.state='sent'
    AND private.can_read_message(m.id);
  RETURN coalesce(total,0);
END $$;

CREATE FUNCTION public.get_notifications(p_unread_only boolean DEFAULT false,p_after_time timestamptz DEFAULT NULL,
  p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_notifications(p_unread_only,p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.get_notification_unread_count() RETURNS bigint LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_notification_unread_count() $$;
CREATE FUNCTION public.mark_notification_read(p_notification_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.mark_notification_read(p_notification_id) $$;
CREATE FUNCTION public.mark_all_notifications_read() RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.mark_all_notifications_read() $$;
CREATE FUNCTION public.get_notification_settings() RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_notification_settings() $$;
CREATE FUNCTION public.update_notification_settings(p_enabled boolean,p_preferences jsonb) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_notification_settings(p_enabled,p_preferences) $$;
CREATE FUNCTION public.get_message_unread_count() RETURNS bigint LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_message_unread_count() $$;

REVOKE ALL ON FUNCTION private.emit_notification(uuid,uuid,text,text,uuid,text),
  private.notify_post_reaction(),private.notify_comment(),private.notify_friend_request(),
  private.notify_friend_accepted(),private.notify_follow(),private.notify_story_reply(),
  private.notification_target_readable(text,uuid),
  private.get_notifications(boolean,timestamptz,uuid,integer),private.get_notification_unread_count(),
  private.mark_notification_read(uuid),private.mark_all_notifications_read(),private.get_notification_settings(),
  private.update_notification_settings(boolean,jsonb),private.get_message_unread_count()
  FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.get_notifications(boolean,timestamptz,uuid,integer),
  public.get_notification_unread_count(),public.mark_notification_read(uuid),public.mark_all_notifications_read(),
  public.get_notification_settings(),public.update_notification_settings(boolean,jsonb),public.get_message_unread_count()
  FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.get_notifications(boolean,timestamptz,uuid,integer),
  public.get_notification_unread_count(),public.mark_notification_read(uuid),public.mark_all_notifications_read(),
  public.get_notification_settings(),public.update_notification_settings(boolean,jsonb),public.get_message_unread_count()
  TO authenticated;
GRANT EXECUTE ON FUNCTION private.get_notifications(boolean,timestamptz,uuid,integer),
  private.get_notification_unread_count(),private.mark_notification_read(uuid),private.mark_all_notifications_read(),
  private.get_notification_settings(),private.update_notification_settings(boolean,jsonb),private.get_message_unread_count()
  TO authenticated;
NOTIFY pgrst,'reload schema';
