-- Task 21 / migration 027: private Memories over existing posts and media.
-- Independent of Advanced Search migration 026.
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.profiles') IS NULL OR to_regclass('public.posts') IS NULL
     OR to_regclass('public.user_settings') IS NULL OR to_regclass('public.notifications') IS NULL
     OR to_regclass('public.saved_posts') IS NULL OR to_regclass('public.post_media') IS NULL
     OR to_regclass('public.media_assets') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('private.set_saved_post(uuid,boolean)') IS NULL
     OR to_regprocedure('public.share_post(uuid,text,text,uuid)') IS NULL
     OR to_regprocedure('realtime.send(jsonb,text,text,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Memories prerequisites missing; verify migrations 001-025 and the deployed schema';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.posts'::regclass
      AND attname='group_id' AND NOT attisdropped)
     OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.posts'::regclass
      AND attname='original_post_id' AND NOT attisdropped)
     OR NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
       WHERE n.nspname='public' AND c.relname='notifications' AND c.relrowsecurity)
     OR NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='notifications'
       AND policyname='notifications_select' AND cmd='SELECT'
       AND qual ILIKE '%recipient_id%' AND qual ILIKE '%auth.uid%' AND qual ILIKE '%is_active%')
     OR has_table_privilege('authenticated','public.notifications','SELECT')
     OR has_table_privilege('authenticated','public.notifications','INSERT')
     OR has_table_privilege('authenticated','public.notifications','UPDATE') THEN
    RAISE EXCEPTION 'Memories requires recipient-only notification RLS and existing share/posts schema';
  END IF;
END $$;

ALTER TABLE public.user_settings
  ADD COLUMN IF NOT EXISTS memories_enabled boolean NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS memory_reminders_enabled boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS memories_timezone text NOT NULL DEFAULT 'UTC';
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_kind_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check
  CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','memories'));
ALTER TABLE public.user_settings DROP CONSTRAINT IF EXISTS user_settings_prefs_keys;
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys
  CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','memories']) = '{}'::jsonb);

CREATE TABLE public.hidden_memories (
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  hidden_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY(user_id,post_id)
);
CREATE INDEX hidden_memories_recent_idx ON public.hidden_memories(user_id,hidden_at DESC,post_id);
ALTER TABLE public.hidden_memories ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.hidden_memories FROM PUBLIC,anon,authenticated,service_role;
CREATE POLICY hidden_memories_select_own ON public.hidden_memories FOR SELECT TO authenticated
  USING (user_id=(select auth.uid()) AND private.is_active());

CREATE INDEX IF NOT EXISTS posts_memories_owner_idx
  ON public.posts(author_id,created_at DESC,id DESC) WHERE state='published';

CREATE FUNCTION private.get_memories_settings() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb;
BEGIN
  PERFORM private.b_active();
  SELECT jsonb_build_object('enabled',memories_enabled,'reminders_enabled',memory_reminders_enabled,
    'timezone',memories_timezone) INTO result
    FROM public.user_settings WHERE user_id=actor;
  IF result IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='user_settings_unavailable'; END IF;
  RETURN result;
END $$;

CREATE FUNCTION private.update_memories_settings(p_enabled boolean,p_reminders_enabled boolean,p_timezone text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer; zone_name text;
BEGIN
  PERFORM private.b_active();
  IF p_enabled IS NULL OR p_reminders_enabled IS NULL OR p_timezone IS NULL
     OR char_length(p_timezone)>80 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_memories_settings';
  END IF;
  SELECT name INTO zone_name FROM pg_catalog.pg_timezone_names WHERE name=p_timezone LIMIT 1;
  IF zone_name IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_timezone'; END IF;
  UPDATE public.user_settings SET memories_enabled=p_enabled,
    memory_reminders_enabled=p_reminders_enabled,memories_timezone=zone_name,
    updated_at=statement_timestamp() WHERE user_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='user_settings_unavailable'; END IF;
  RETURN jsonb_build_object('enabled',p_enabled,'reminders_enabled',p_reminders_enabled,'timezone',zone_name);
END $$;

CREATE FUNCTION private.memory_page(p_years_ago integer,p_after_time timestamptz,p_after_id uuid,p_limit integer,
  p_hidden boolean DEFAULT false) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); zone_name text; local_today date; target_year integer;
BEGIN
  PERFORM private.b_active();
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
     OR (p_after_time IS NULL)<>(p_after_id IS NULL)
     OR (p_years_ago IS NOT NULL AND p_years_ago NOT BETWEEN 1 AND 120)
     OR p_hidden IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_memories_page';
  END IF;
  SELECT memories_timezone INTO zone_name FROM public.user_settings WHERE user_id=actor;
  IF zone_name IS NULL OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_timezone_names WHERE name=zone_name) THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='memories_timezone_unavailable';
  END IF;
  IF NOT p_hidden AND NOT coalesce((SELECT memories_enabled FROM public.user_settings WHERE user_id=actor),false) THEN
    RETURN '[]'::jsonb;
  END IF;
  local_today:=(statement_timestamp() AT TIME ZONE zone_name)::date;
  IF p_years_ago IS NOT NULL THEN target_year:=extract(year FROM local_today)::integer-p_years_ago;
    IF target_year<1900 THEN RETURN '[]'::jsonb; END IF;
  END IF;
  RETURN coalesce((
    SELECT jsonb_agg(private.post_json(x.id)||jsonb_build_object(
      'years_ago',extract(year FROM local_today)::integer-extract(year FROM (x.created_at AT TIME ZONE zone_name))::integer,
      'local_date',(x.created_at AT TIME ZONE zone_name)::date,
      'is_hidden',p_hidden,
      'can_share',x.group_id IS NULL AND x.kind='original')
      ORDER BY x.created_at DESC,x.id DESC)
    FROM (
      SELECT p.id,p.created_at,p.group_id,p.kind FROM public.posts p
      WHERE p.author_id=actor AND p.state='published' AND private.can_read_post(p.id)
        AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
        AND CASE WHEN p_hidden THEN EXISTS(SELECT 1 FROM public.hidden_memories h
              WHERE h.user_id=actor AND h.post_id=p.id)
          ELSE NOT EXISTS(SELECT 1 FROM public.hidden_memories h
              WHERE h.user_id=actor AND h.post_id=p.id)
            AND CASE WHEN p_years_ago IS NULL THEN
              (p.created_at AT TIME ZONE zone_name)::date<local_today
              AND extract(month FROM p.created_at AT TIME ZONE zone_name)=extract(month FROM local_today)
              AND extract(day FROM p.created_at AT TIME ZONE zone_name)=extract(day FROM local_today)
            ELSE (p.created_at AT TIME ZONE zone_name) >= make_date(target_year,1,1)::timestamp
  AND (p.created_at AT TIME ZONE zone_name) < make_date(target_year+1,1,1)::timestamp
END
          END
      ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit
    ) x
  ),'[]'::jsonb);
END $$;

CREATE FUNCTION private.get_today_memories(p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); rows jsonb; first_post uuid; zone_name text; local_today date;
  enabled boolean; reminders boolean; notifications_on boolean; memory_pref boolean; notification_id uuid;
BEGIN
  PERFORM private.b_active();
  rows:=private.memory_page(NULL,p_after_time,p_after_id,p_limit,false);
  SELECT memories_timezone,memories_enabled,memory_reminders_enabled,notifications_enabled,
    coalesce((notification_preferences->>'memories')::boolean,true)
    INTO zone_name,enabled,reminders,notifications_on,memory_pref
    FROM public.user_settings WHERE user_id=actor;
  IF enabled AND reminders AND notifications_on AND memory_pref THEN
    local_today:=(statement_timestamp() AT TIME ZONE zone_name)::date;
    SELECT p.id INTO first_post FROM public.posts p
    WHERE p.author_id=actor AND p.state='published' AND private.can_read_post(p.id)
      AND NOT EXISTS(SELECT 1 FROM public.hidden_memories h WHERE h.user_id=actor AND h.post_id=p.id)
      AND (p.created_at AT TIME ZONE zone_name)::date<local_today
      AND extract(month FROM p.created_at AT TIME ZONE zone_name)=extract(month FROM local_today)
      AND extract(day FROM p.created_at AT TIME ZONE zone_name)=extract(day FROM local_today)
    ORDER BY p.created_at DESC,p.id DESC LIMIT 1;
    IF first_post IS NOT NULL THEN
      INSERT INTO public.notifications(recipient_id,actor_id,event_key,kind,target_type,target_id)
      VALUES(actor,NULL,'memories:'||local_today::text,'memories','post',first_post)
      ON CONFLICT(recipient_id,event_key) DO NOTHING RETURNING id INTO notification_id;
      IF notification_id IS NOT NULL THEN
        PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',notification_id,'scope','notifications'),
          'invalidate','user:'||actor::text,true);
      END IF;
    END IF;
  END IF;
  RETURN rows;
END $$;

CREATE FUNCTION private.get_memories_by_year(p_years_ago integer,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.memory_page(p_years_ago,p_after_time,p_after_id,p_limit,false)
$$;
CREATE FUNCTION private.get_hidden_memories(p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.memory_page(NULL,p_after_time,p_after_id,p_limit,true)
$$;

CREATE FUNCTION private.hide_memory(p_post_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
  PERFORM private.b_active();
  IF p_post_id IS NULL OR NOT EXISTS (SELECT 1 FROM public.posts p WHERE p.id=p_post_id
      AND p.author_id=actor AND p.state='published' AND private.can_read_post(p.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='memory_unavailable';
  END IF;
  INSERT INTO public.hidden_memories(user_id,post_id) VALUES(actor,p_post_id)
    ON CONFLICT(user_id,post_id) DO NOTHING;
  RETURN jsonb_build_object('hidden',true,'post_id',p_post_id);
END $$;

CREATE FUNCTION private.unhide_memory(p_post_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  PERFORM private.b_active();
  IF p_post_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_memory'; END IF;
  DELETE FROM public.hidden_memories WHERE user_id=actor AND post_id=p_post_id;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=0 THEN RAISE SQLSTATE 'PT404' USING MESSAGE='hidden_memory_unavailable'; END IF;
  RETURN jsonb_build_object('hidden',false,'post_id',p_post_id);
END $$;

CREATE FUNCTION public.get_memories_settings() RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_memories_settings() $$;
CREATE FUNCTION public.update_memories_settings(p_enabled boolean,p_reminders_enabled boolean,p_timezone text)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.update_memories_settings($1,$2,$3) $$;
CREATE FUNCTION public.get_today_memories(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.get_today_memories($1,$2,$3) $$;
CREATE FUNCTION public.get_memories_by_year(p_years_ago integer,p_after_time timestamptz DEFAULT NULL,
  p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.get_memories_by_year($1,$2,$3,$4) $$;
CREATE FUNCTION public.get_hidden_memories(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.get_hidden_memories($1,$2,$3) $$;
CREATE FUNCTION public.hide_memory(p_post_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.hide_memory($1) $$;
CREATE FUNCTION public.unhide_memory(p_post_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.unhide_memory($1) $$;

REVOKE ALL ON FUNCTION private.get_memories_settings(),private.update_memories_settings(boolean,boolean,text),
  private.get_today_memories(timestamptz,uuid,integer),private.get_memories_by_year(integer,timestamptz,uuid,integer),
  private.get_hidden_memories(timestamptz,uuid,integer),private.hide_memory(uuid),private.unhide_memory(uuid),
  private.memory_page(integer,timestamptz,uuid,integer,boolean)
  FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.get_memories_settings(),private.update_memories_settings(boolean,boolean,text),
  private.get_today_memories(timestamptz,uuid,integer),private.get_memories_by_year(integer,timestamptz,uuid,integer),
  private.get_hidden_memories(timestamptz,uuid,integer),private.hide_memory(uuid),private.unhide_memory(uuid)
  TO authenticated;
REVOKE ALL ON FUNCTION public.get_memories_settings(),public.update_memories_settings(boolean,boolean,text),
  public.get_today_memories(timestamptz,uuid,integer),public.get_memories_by_year(integer,timestamptz,uuid,integer),
  public.get_hidden_memories(timestamptz,uuid,integer),public.hide_memory(uuid),public.unhide_memory(uuid)
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.get_memories_settings(),public.update_memories_settings(boolean,boolean,text),
  public.get_today_memories(timestamptz,uuid,integer),public.get_memories_by_year(integer,timestamptz,uuid,integer),
  public.get_hidden_memories(timestamptz,uuid,integer),public.hide_memory(uuid),public.unhide_memory(uuid)
  TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
