-- Task 16 forward migration. Requires Task 15 avatar projection (migration 020).
DO $$
BEGIN
  IF to_regclass('public.saved_posts') IS NULL OR to_regclass('public.user_follows') IS NULL
     OR to_regclass('public.profile_private') IS NULL OR to_regclass('public.profiles') IS NULL
     OR to_regclass('public.posts') IS NULL OR to_regclass('public.account_status') IS NULL
     OR to_regclass('public.user_settings') IS NULL
     OR to_regprocedure('private.self_profile()') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.are_friends(uuid)') IS NULL
     OR to_regprocedure('private.is_blocked(uuid)') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL
     OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.lock_profile(boolean)') IS NULL
     OR to_regprocedure('private.complete_onboarding(text,date,text,bigint)') IS NULL
     OR to_regprocedure('private.update_profile(text,text,uuid,uuid,bigint)') IS NULL
     OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.profiles'::regclass
                    AND attname='public_avatar_preset' AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Task 16 prerequisites missing; apply and verify migration 020 first';
  END IF;
END $$;

ALTER TABLE public.profile_private
  ADD COLUMN current_location text,
  ADD COLUMN location_audience text NOT NULL DEFAULT 'private'
    CHECK (location_audience IN ('public','friends','private'));

CREATE FUNCTION private.valid_location(p_location text) RETURNS boolean
LANGUAGE sql IMMUTABLE SET search_path='' AS $$
 SELECT p_location IS NULL OR p_location=ANY(ARRAY[
 'An Giang','Bắc Ninh','Cao Bằng','Cà Mau','Cần Thơ','Đà Nẵng','Đắk Lắk','Điện Biên',
 'Đồng Nai','Đồng Tháp','Gia Lai','Hà Nội','Hà Tĩnh','Hải Phòng','Huế','Hưng Yên',
 'Khánh Hòa','Lai Châu','Lâm Đồng','Lạng Sơn','Lào Cai','Nghệ An','Ninh Bình','Phú Thọ',
 'Quảng Ngãi','Quảng Ninh','Quảng Trị','Sơn La','Tây Ninh','Thái Nguyên','Thanh Hóa',
 'TP. Hồ Chí Minh','Tuyên Quang','Vĩnh Long']::text[])
$$;

CREATE OR REPLACE FUNCTION private.self_profile() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb; status text;
BEGIN
  SELECT state INTO status FROM public.account_status WHERE user_id=actor;
  IF status IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  IF status<>'active' THEN RETURN jsonb_build_object('profile_id',actor,'account_state',status); END IF;
  SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'bio',p.bio,
    'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,
    'avatar_preset',p.public_avatar_preset,'onboarding_completed',p.onboarding_completed,'version',p.version,
    'account_state',status,'birth_date',d.birth_date,'gender',d.gender,'location',d.current_location,
    'location_audience',d.location_audience,
    'settings',jsonb_build_object('profile_audience',s.profile_audience,'last_seen_audience',s.last_seen_audience,
      'notifications_enabled',s.notifications_enabled,'notification_preferences',s.notification_preferences),
    'email_verified',EXISTS(SELECT 1 FROM auth.users u WHERE u.id=actor AND u.email_confirmed_at IS NOT NULL))
  INTO result FROM public.profiles p JOIN public.profile_private d ON d.user_id=p.id
    JOIN public.user_settings s ON s.user_id=p.id WHERE p.id=actor;
  IF result IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  RETURN result;
END $$;

CREATE OR REPLACE FUNCTION private.get_profile(p_target uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb;
BEGIN
  IF p_target=actor THEN RETURN private.self_profile(); END IF;
  IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
  SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'bio',p.bio,
    'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,
    'avatar_preset',p.public_avatar_preset,'version',p.version,
    'location',CASE WHEN d.location_audience='public' OR
       (d.location_audience='friends' AND private.are_friends(p.id)) THEN d.current_location END)
  INTO result FROM public.profiles p JOIN public.profile_private d ON d.user_id=p.id
  WHERE p.id=p_target AND p.onboarding_completed AND private.can_read_profile(p.id);
  IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN result;
END $$;

CREATE FUNCTION private.update_profile_with_location(p_display_name text,p_bio text,p_avatar_asset_id uuid,
  p_cover_asset_id uuid,p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); before_location text; before_audience text; current_version bigint;
BEGIN
  IF NOT private.valid_location(p_location) OR p_location_audience IS NULL
     OR p_location_audience NOT IN ('public','friends','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_location';
  END IF;
  PERFORM private.lock_profile();
  SELECT version INTO current_version FROM public.profiles WHERE id=actor;
  IF p_expected_version IS NULL OR current_version<>p_expected_version THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict';
  END IF;
  SELECT current_location,location_audience INTO before_location,before_audience
    FROM public.profile_private WHERE user_id=actor FOR UPDATE;
  PERFORM private.update_profile(p_display_name,p_bio,p_avatar_asset_id,p_cover_asset_id,p_expected_version);
  SELECT version INTO current_version FROM public.profiles WHERE id=actor;
  UPDATE public.profile_private SET current_location=p_location,location_audience=p_location_audience,
    updated_at=statement_timestamp() WHERE user_id=actor;
  IF (before_location IS DISTINCT FROM p_location OR before_audience IS DISTINCT FROM p_location_audience)
     AND current_version=p_expected_version THEN
    UPDATE public.profiles SET version=version+1,updated_at=statement_timestamp() WHERE id=actor;
  END IF;
  RETURN private.self_profile();
END $$;

CREATE FUNCTION private.complete_onboarding_with_location(p_display_name text,p_birth_date date,p_gender text,
  p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); old_version bigint; new_version bigint;
  old_location text; old_audience text;
BEGIN
  IF NOT private.valid_location(p_location) OR p_location_audience IS NULL
     OR p_location_audience NOT IN ('public','friends','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_location';
  END IF;
  PERFORM private.lock_profile(false);
  SELECT version INTO old_version FROM public.profiles WHERE id=actor;
  IF p_expected_version IS NULL OR old_version<>p_expected_version THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict';
  END IF;
  SELECT current_location,location_audience INTO old_location,old_audience
    FROM public.profile_private WHERE user_id=actor FOR UPDATE;
  PERFORM private.complete_onboarding(p_display_name,p_birth_date,p_gender,p_expected_version);
  UPDATE public.profile_private SET current_location=p_location,location_audience=p_location_audience,
    updated_at=statement_timestamp() WHERE user_id=actor;
  SELECT version INTO new_version FROM public.profiles WHERE id=actor;
  IF (old_location IS DISTINCT FROM p_location OR old_audience IS DISTINCT FROM p_location_audience)
     AND new_version=old_version THEN
    UPDATE public.profiles SET version=version+1,updated_at=statement_timestamp() WHERE id=actor;
  END IF;
  RETURN private.self_profile();
END $$;

CREATE OR REPLACE FUNCTION private.post_json(pid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT jsonb_build_object('id',p.id,'author_id',p.author_id,'body',p.body,'audience',p.audience,'kind',p.kind,
    'original_post_id',CASE WHEN private.can_read_post_base(p.original_post_id) THEN p.original_post_id END,
    'original_available',p.kind='share' AND private.can_read_post_base(p.original_post_id),
    'author',CASE WHEN private.can_read_profile(p.author_id) THEN
      (SELECT jsonb_build_object('id',u.id,'display_name',u.display_name,'avatar_asset_id',u.avatar_asset_id,
        'avatar_preset',u.public_avatar_preset) FROM public.profiles u WHERE u.id=p.author_id) END,
    'reactions_count',p.reactions_count,'comments_count',p.comments_count,'shares_count',p.shares_count,
    'my_reaction',(SELECT r.reaction FROM public.post_reactions r WHERE r.post_id=p.id AND r.user_id=auth.uid()),
    'is_saved',EXISTS(SELECT 1 FROM public.saved_posts s WHERE s.user_id=auth.uid() AND s.post_id=p.id),
    'version',p.version,'created_at',p.created_at,'updated_at',p.updated_at,
    'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',m.asset_id,'position',m.position,
      'mime_type',a.mime_type) ORDER BY m.position) FROM public.post_media m
      JOIN public.media_assets a ON a.id=m.asset_id
      WHERE m.post_id=p.id AND private.can_read_media(m.asset_id)),'[]'::jsonb))
  FROM public.posts p WHERE p.id=pid AND private.can_read_post(p.id);
$$;

CREATE FUNCTION private.set_saved_post(p_post_id uuid,p_saved boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
  IF p_post_id IS NULL OR p_saved IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_saved_post'; END IF;
  IF p_saved THEN
    IF NOT private.can_read_post(p_post_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
    INSERT INTO public.saved_posts(user_id,post_id) VALUES(actor,p_post_id) ON CONFLICT DO NOTHING;
  ELSE
    DELETE FROM public.saved_posts WHERE user_id=actor AND post_id=p_post_id;
  END IF;
  RETURN jsonb_build_object('post_id',p_post_id,'saved',p_saved);
END $$;

CREATE FUNCTION private.get_saved_posts(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('saved_at',q.created_at,'post',private.post_json(q.post_id))
      ORDER BY q.created_at DESC,q.post_id DESC)
    FROM (SELECT s.post_id,s.created_at FROM public.saved_posts s JOIN public.posts p ON p.id=s.post_id
      WHERE s.user_id=actor AND private.can_read_post(s.post_id)
        AND (p_after_time IS NULL OR (s.created_at,s.post_id)<(p_after_time,p_after_id))
      ORDER BY s.created_at DESC,s.post_id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.set_follow(p_target uuid,p_follow boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); target_exists boolean;
BEGIN
  IF p_target IS NULL OR p_follow IS NULL OR p_target=actor THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_follow';
  END IF;
  IF p_follow THEN
    IF private.is_blocked(p_target) OR NOT private.can_read_profile(p_target) THEN
      RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable';
    END IF;
    SELECT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id
      WHERE p.id=p_target AND p.onboarding_completed AND s.state='active') INTO target_exists;
    IF NOT target_exists THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
    INSERT INTO public.user_follows(follower_id,followed_id) VALUES(actor,p_target) ON CONFLICT DO NOTHING;
  ELSE
    DELETE FROM public.user_follows WHERE follower_id=actor AND followed_id=p_target;
  END IF;
  -- Counts are aggregate totals for a readable, unblocked profile; list RPCs
  -- return only identities the caller is allowed to read.
  IF private.is_blocked(p_target) OR NOT private.can_read_profile(p_target) THEN
    RETURN jsonb_build_object('target_id',p_target,'following',false);
  END IF;
  RETURN jsonb_build_object('target_id',p_target,'following',p_follow,
    'followers_count',(SELECT count(*) FROM public.user_follows WHERE followed_id=p_target),
    'following_count',(SELECT count(*) FROM public.user_follows WHERE follower_id=p_target));
END $$;

CREATE FUNCTION private.get_follow_counts(p_target uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  IF p_target IS NULL OR NOT private.can_read_profile(p_target) OR private.is_blocked(p_target) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN jsonb_build_object('target_id',p_target,
    'followers_count',(SELECT count(*) FROM public.user_follows WHERE followed_id=p_target),
    'following_count',(SELECT count(*) FROM public.user_follows WHERE follower_id=p_target),
    'following',EXISTS(SELECT 1 FROM public.user_follows WHERE follower_id=actor AND followed_id=p_target));
END $$;

CREATE FUNCTION private.list_follows(p_target uuid,p_direction text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_direction IS NULL OR p_direction NOT IN ('followers','following') OR p_target IS NULL
     OR NOT private.can_read_profile(p_target) OR private.is_blocked(p_target) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('profile_id',q.id,'display_name',q.display_name,
      'avatar_asset_id',q.avatar_asset_id,'avatar_preset',q.public_avatar_preset,'created_at',q.created_at,
      'following',EXISTS(SELECT 1 FROM public.user_follows x WHERE x.follower_id=actor AND x.followed_id=q.id))
      ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset,f.created_at
      FROM public.user_follows f
      JOIN public.profiles p ON p.id=CASE WHEN p_direction='followers' THEN f.follower_id ELSE f.followed_id END
      JOIN public.account_status s ON s.user_id=p.id AND s.state='active'
      WHERE CASE WHEN p_direction='followers' THEN f.followed_id ELSE f.follower_id END=p_target
        AND p.onboarding_completed AND private.can_read_profile(p.id) AND NOT private.is_blocked(p.id)
        AND (p_after_time IS NULL OR (f.created_at,p.id)<(p_after_time,p_after_id))
      ORDER BY f.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION public.update_profile_with_location(p_display_name text,p_bio text,p_avatar_asset_id uuid,
  p_cover_asset_id uuid,p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_profile_with_location(
  p_display_name,p_bio,p_avatar_asset_id,p_cover_asset_id,p_location,p_location_audience,p_expected_version) $$;
CREATE FUNCTION public.complete_onboarding_with_location(p_display_name text,p_birth_date date,p_gender text,
  p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.complete_onboarding_with_location(
  p_display_name,p_birth_date,p_gender,p_location,p_location_audience,p_expected_version) $$;
CREATE FUNCTION public.set_saved_post(p_post_id uuid,p_saved boolean) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_saved_post(p_post_id,p_saved) $$;
CREATE FUNCTION public.get_saved_posts(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_saved_posts(p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.set_follow(p_target uuid,p_follow boolean) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_follow(p_target,p_follow) $$;
CREATE FUNCTION public.get_follow_counts(p_target uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_follow_counts(p_target) $$;
CREATE FUNCTION public.list_follows(p_target uuid,p_direction text,p_after_time timestamptz DEFAULT NULL,
  p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_follows(p_target,p_direction,p_after_time,p_after_id,p_limit) $$;

REVOKE ALL ON FUNCTION private.valid_location(text),private.update_profile_with_location(text,text,uuid,uuid,text,text,bigint),
  private.complete_onboarding_with_location(text,date,text,text,text,bigint),private.set_saved_post(uuid,boolean),
  private.get_saved_posts(timestamptz,uuid,integer),private.set_follow(uuid,boolean),
  private.get_follow_counts(uuid),private.list_follows(uuid,text,timestamptz,uuid,integer)
  FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.update_profile_with_location(text,text,uuid,uuid,text,text,bigint),
  public.complete_onboarding_with_location(text,date,text,text,text,bigint),public.set_saved_post(uuid,boolean),
  public.get_saved_posts(timestamptz,uuid,integer),public.set_follow(uuid,boolean),
  public.get_follow_counts(uuid),public.list_follows(uuid,text,timestamptz,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION private.update_profile_with_location(text,text,uuid,uuid,text,text,bigint),
  private.complete_onboarding_with_location(text,date,text,text,text,bigint),private.set_saved_post(uuid,boolean),
  private.get_saved_posts(timestamptz,uuid,integer),private.set_follow(uuid,boolean),
  private.get_follow_counts(uuid),private.list_follows(uuid,text,timestamptz,uuid,integer) TO authenticated;
GRANT EXECUTE ON FUNCTION public.update_profile_with_location(text,text,uuid,uuid,text,text,bigint),
  public.complete_onboarding_with_location(text,date,text,text,text,bigint),public.set_saved_post(uuid,boolean),
  public.get_saved_posts(timestamptz,uuid,integer),public.set_follow(uuid,boolean),
  public.get_follow_counts(uuid),public.list_follows(uuid,text,timestamptz,uuid,integer) TO authenticated;
NOTIFY pgrst,'reload schema';
