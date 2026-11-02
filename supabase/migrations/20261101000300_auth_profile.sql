-- Phase A: Auth owns credentials/sessions. Never trust raw_user_meta_data for authority.
-- Public RPCs are invoker wrappers; implementation/private projection is not exposed.
-- Do not expose pending signup placeholders to other active users via raw SELECT.
CREATE OR REPLACE FUNCTION private.can_read_profile(pid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT (pid=auth.uid() AND private.account_ok()) OR
 (private.is_active() AND NOT private.is_blocked(pid) AND EXISTS (
  SELECT 1 FROM public.profiles p JOIN public.user_settings u ON u.user_id=p.id
  JOIN public.account_status s ON s.user_id=p.id
  WHERE p.id=pid AND p.onboarding_completed AND s.state='active' AND
   (u.profile_audience='public' OR (u.profile_audience='friends' AND private.are_friends(pid)))));
$$;
CREATE FUNCTION private.require_identity() RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := auth.uid();
BEGIN
 IF actor IS NULL OR coalesce((auth.jwt()->>'is_anonymous')::boolean,false)
 OR NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor) THEN
  RAISE SQLSTATE 'PT401' USING MESSAGE='authentication_required';
 END IF;
 RETURN actor;
END $$;

CREATE FUNCTION private.bootstrap_identity(actor uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 -- Shared by signup trigger and actor-bound recovery. Serializes even missing profile.
 PERFORM 1 FROM auth.users WHERE id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT401' USING MESSAGE='authentication_required'; END IF;
 INSERT INTO public.profiles(id,display_name) VALUES(actor,'Meeto user') ON CONFLICT(id) DO NOTHING;
 INSERT INTO public.profile_private(user_id) VALUES(actor) ON CONFLICT(user_id) DO NOTHING;
 INSERT INTO public.user_settings(user_id) VALUES(actor) ON CONFLICT(user_id) DO NOTHING;
 INSERT INTO public.account_status(user_id) VALUES(actor) ON CONFLICT(user_id) DO NOTHING;
END $$;

CREATE FUNCTION private.on_auth_user_created() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.bootstrap_identity(NEW.id);
 RETURN NEW;
END $$;
CREATE TRIGGER meeto_bootstrap_user AFTER INSERT ON auth.users
FOR EACH ROW EXECUTE FUNCTION private.on_auth_user_created();

CREATE FUNCTION private.lock_profile(require_onboarding boolean DEFAULT true) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.require_identity();
BEGIN
 -- Serialize with bootstrap/recovery before status/profile locks (avoids inverse
 -- recovery-vs-update lock order). Operator suspension updates the same status row.
 PERFORM 1 FROM auth.users WHERE id=actor FOR UPDATE;
 PERFORM 1 FROM public.account_status WHERE user_id=actor AND state='active' FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 PERFORM 1 FROM public.profiles WHERE id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
 IF NOT EXISTS(SELECT 1 FROM auth.users WHERE id=actor AND email_confirmed_at IS NOT NULL) THEN
  RAISE SQLSTATE 'PT403' USING MESSAGE='email_verification_required';
 END IF;
 IF require_onboarding AND NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=actor AND onboarding_completed) THEN
  RAISE SQLSTATE 'PT403' USING MESSAGE='onboarding_required';
 END IF;
 RETURN actor;
END $$;

CREATE FUNCTION private.self_profile() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.require_identity(); result jsonb; status text;
BEGIN
 SELECT state INTO status FROM public.account_status WHERE user_id=actor;
 IF status IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
 -- Locked users get only a status envelope, never private/social data or self-unlock.
 IF status<>'active' THEN RETURN jsonb_build_object('profile_id',actor,'account_state',status); END IF;
 SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'bio',p.bio,
  'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,
  'onboarding_completed',p.onboarding_completed,'version',p.version,
  'account_state',status,'birth_date',d.birth_date,'gender',d.gender,
  'settings',jsonb_build_object('profile_audience',s.profile_audience,'last_seen_audience',s.last_seen_audience,
   'notifications_enabled',s.notifications_enabled,'notification_preferences',s.notification_preferences),
  'email_verified',EXISTS(SELECT 1 FROM auth.users u WHERE u.id=actor AND u.email_confirmed_at IS NOT NULL))
 INTO result FROM public.profiles p JOIN public.profile_private d ON d.user_id=p.id
 JOIN public.user_settings s ON s.user_id=p.id WHERE p.id=actor;
 IF result IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
 RETURN result;
END $$;

CREATE FUNCTION private.bootstrap_profile() RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.require_identity();
BEGIN
 PERFORM private.bootstrap_identity(actor);
 RETURN private.self_profile();
END $$;

CREATE FUNCTION private.validate_person(name text, dob date, gender_value text) RETURNS void
LANGUAGE plpgsql STABLE SET search_path='' AS $$
BEGIN
 IF name IS NULL OR char_length(btrim(name)) NOT BETWEEN 1 AND 100
 OR name ~ '[[:cntrl:]]' OR dob IS NULL OR NOT isfinite(dob)
 OR dob < DATE '1900-01-01' OR dob > (timezone('UTC',statement_timestamp())::date - interval '13 years')::date
 OR (gender_value IS NOT NULL AND (char_length(gender_value)>100 OR gender_value ~ '[[:cntrl:]]')) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_fields';
 END IF;
END $$;

CREATE FUNCTION private.complete_onboarding(p_display_name text,p_birth_date date,p_gender text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.lock_profile(false); p public.profiles; d public.profile_private;
BEGIN
 PERFORM private.validate_person(p_display_name,p_birth_date,p_gender);
 SELECT * INTO p FROM public.profiles WHERE id=actor;
 SELECT * INTO d FROM public.profile_private WHERE user_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
 IF p.onboarding_completed THEN
  IF p.display_name=btrim(p_display_name) AND d.birth_date=p_birth_date AND d.gender IS NOT DISTINCT FROM p_gender
   THEN RETURN private.self_profile(); END IF;
  RAISE SQLSTATE 'PT409' USING MESSAGE='onboarding_already_completed';
 END IF;
 IF p_expected_version IS NULL OR p.version<>p_expected_version THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict';
 END IF;
 UPDATE public.profile_private SET birth_date=p_birth_date,gender=p_gender,updated_at=statement_timestamp() WHERE user_id=actor;
 UPDATE public.profiles SET display_name=btrim(p_display_name),onboarding_completed=true,
  updated_at=statement_timestamp(),version=version+1 WHERE id=actor;
 RETURN private.self_profile();
END $$;

CREATE FUNCTION private.update_profile(p_display_name text,p_bio text,p_avatar_asset_id uuid,p_cover_asset_id uuid,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.lock_profile(); p public.profiles; a public.media_assets; old_asset uuid;
BEGIN
 IF p_display_name IS NULL OR char_length(btrim(p_display_name)) NOT BETWEEN 1 AND 100
 OR p_display_name ~ '[[:cntrl:]]' OR p_bio IS NULL OR char_length(p_bio)>2000 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_fields';
 END IF;
 SELECT * INTO p FROM public.profiles WHERE id=actor;
 -- Full desired state. Lock union of old/new assets in UUID order, never trust supplied ownership.
 FOR a IN SELECT * FROM public.media_assets WHERE id IN
 (p.avatar_asset_id,p.cover_asset_id,p_avatar_asset_id,p_cover_asset_id) ORDER BY id FOR UPDATE LOOP
  IF a.id IN (p_avatar_asset_id,p_cover_asset_id) AND
   (a.owner_id IS DISTINCT FROM actor OR a.state<>'ready' OR a.bucket_id<>'profile-media'
    OR a.purpose<>CASE WHEN a.id=p_avatar_asset_id THEN 'avatar' ELSE 'cover' END) THEN
   RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_asset';
  END IF;
 END LOOP;
 IF (p_avatar_asset_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=p_avatar_asset_id))
 OR (p_cover_asset_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=p_cover_asset_id))
 OR (p_avatar_asset_id IS NOT NULL AND p_avatar_asset_id=p_cover_asset_id) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_asset';
 END IF;
 IF p.display_name=btrim(p_display_name) AND p.bio=p_bio
 AND p.avatar_asset_id IS NOT DISTINCT FROM p_avatar_asset_id AND p.cover_asset_id IS NOT DISTINCT FROM p_cover_asset_id
 THEN RETURN private.self_profile(); END IF;
 IF p_expected_version IS NULL OR p.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 UPDATE public.profiles SET display_name=btrim(p_display_name),bio=p_bio,avatar_asset_id=p_avatar_asset_id,
 cover_asset_id=p_cover_asset_id,updated_at=statement_timestamp(),version=version+1 WHERE id=actor;
 FOREACH old_asset IN ARRAY ARRAY[p.avatar_asset_id,p.cover_asset_id] LOOP
  IF old_asset IS NOT NULL AND old_asset IS DISTINCT FROM p_avatar_asset_id AND old_asset IS DISTINCT FROM p_cover_asset_id THEN
   -- Purpose/owner exclusivity ensures old profile object has no other valid parent.
   UPDATE public.media_assets SET state='pending_delete',delete_after=statement_timestamp()+interval '24 hours'
    WHERE id=old_asset AND owner_id=actor AND purpose IN ('avatar','cover');
   INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id)
    VALUES('profile-media-retire:'||old_asset::text,'media.retire','media_asset',old_asset)
    ON CONFLICT(event_key) DO NOTHING;
  END IF;
 END LOOP;
 RETURN private.self_profile();
END $$;

CREATE FUNCTION private.update_profile_private(p_birth_date date,p_gender text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.lock_profile(); p public.profiles; d public.profile_private;
BEGIN
 SELECT * INTO p FROM public.profiles WHERE id=actor;
 PERFORM private.validate_person(p.display_name,p_birth_date,p_gender);
 SELECT * INTO d FROM public.profile_private WHERE user_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
 IF d.birth_date=p_birth_date AND d.gender IS NOT DISTINCT FROM p_gender THEN RETURN private.self_profile(); END IF;
 IF p_expected_version IS NULL OR p.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 UPDATE public.profile_private SET birth_date=p_birth_date,gender=p_gender,updated_at=statement_timestamp() WHERE user_id=actor;
 UPDATE public.profiles SET version=version+1,updated_at=statement_timestamp() WHERE id=actor;
 RETURN private.self_profile();
END $$;

CREATE FUNCTION private.get_profile(p_target uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.require_identity(); result jsonb;
BEGIN
 IF p_target=actor THEN RETURN private.self_profile(); END IF;
 IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'bio',p.bio,
 'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,'version',p.version)
 INTO result FROM public.profiles p WHERE p.id=p_target AND p.onboarding_completed AND private.can_read_profile(p.id);
 IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 RETURN result;
END $$;

CREATE FUNCTION private.search_profiles(p_prefix text,p_after_name text,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.require_identity();
 IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 IF p_prefix IS NULL OR char_length(btrim(p_prefix)) NOT BETWEEN 1 AND 100 OR p_limit IS NULL
 OR p_limit NOT BETWEEN 1 AND 50 OR (p_after_name IS NULL)<>(p_after_id IS NULL)
 OR (p_after_name IS NOT NULL AND char_length(p_after_name)>100) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('profile_id',q.id,'display_name',q.display_name,
 'avatar_asset_id',q.avatar_asset_id,'cursor_name',lower(q.display_name))) FROM (
 SELECT p.id,p.display_name,p.avatar_asset_id FROM public.profiles p
 WHERE p.id<>auth.uid() AND p.onboarding_completed AND private.can_read_profile(p.id)
 AND starts_with(lower(p.display_name),lower(btrim(p_prefix)))
 AND (p_after_name IS NULL OR (lower(p.display_name),p.id)>(lower(p_after_name),p_after_id))
 ORDER BY lower(p.display_name),p.id LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.set_block(p_target uuid,p_blocked boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid := private.require_identity(); changed integer;
BEGIN
 IF p_target IS NULL OR p_target=actor OR p_blocked IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_block'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(least(actor,p_target)::text||':'||greatest(actor,p_target)::text,0));
 PERFORM private.lock_profile();
 IF p_blocked THEN
  IF NOT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id
   WHERE p.id=p_target AND p.onboarding_completed AND s.state='active') THEN
   RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  INSERT INTO public.user_blocks(blocker_id,blocked_id) VALUES(actor,p_target) ON CONFLICT DO NOTHING;
  GET DIAGNOSTICS changed=ROW_COUNT;
  UPDATE public.friend_requests SET state='cancelled',resolved_at=statement_timestamp()
   WHERE pair_low=least(actor,p_target) AND pair_high=greatest(actor,p_target) AND state='pending';
  DELETE FROM public.friendships WHERE user_low=least(actor,p_target) AND user_high=greatest(actor,p_target);
  DELETE FROM public.user_follows WHERE (follower_id=actor AND followed_id=p_target) OR (follower_id=p_target AND followed_id=actor);
  IF changed>0 THEN
   INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id)
    VALUES('block:'||gen_random_uuid()::text,'relationship.blocked','profile',actor);
  END IF;
 ELSE DELETE FROM public.user_blocks WHERE blocker_id=actor AND blocked_id=p_target;
 END IF;
 RETURN jsonb_build_object('target_id',p_target,'blocked',p_blocked);
END $$;

-- Explicit wrappers have fixed parameter lists; no arbitrary actor/role/status patches.
CREATE FUNCTION public.bootstrap_profile() RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.bootstrap_profile() $$;
CREATE FUNCTION public.get_my_profile() RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.self_profile() $$;
CREATE FUNCTION public.complete_onboarding(p_display_name text,p_birth_date date,p_gender text,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.complete_onboarding(p_display_name,p_birth_date,p_gender,p_expected_version) $$;
CREATE FUNCTION public.update_profile(p_display_name text,p_bio text,p_avatar_asset_id uuid,p_cover_asset_id uuid,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_profile(p_display_name,p_bio,p_avatar_asset_id,p_cover_asset_id,p_expected_version) $$;
CREATE FUNCTION public.update_profile_private(p_birth_date date,p_gender text,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_profile_private(p_birth_date,p_gender,p_expected_version) $$;
CREATE FUNCTION public.get_profile(p_target uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_profile(p_target) $$;
CREATE FUNCTION public.search_profiles(p_prefix text,p_after_name text DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_profiles(p_prefix,p_after_name,p_after_id,p_limit) $$;
CREATE FUNCTION public.set_block(p_target uuid,p_blocked boolean) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_block(p_target,p_blocked) $$;

-- Helpers that accept actor UUID remain owner-only; no EXECUTE to authenticated.
REVOKE ALL ON FUNCTION private.require_identity(),private.bootstrap_identity(uuid),private.on_auth_user_created(),private.lock_profile(boolean),private.validate_person(text,date,text) FROM PUBLIC,anon,authenticated;
REVOKE ALL ON FUNCTION private.bootstrap_profile(),private.self_profile(),private.complete_onboarding(text,date,text,bigint),private.update_profile(text,text,uuid,uuid,bigint),private.update_profile_private(date,text,bigint),private.get_profile(uuid),private.search_profiles(text,text,uuid,integer),private.set_block(uuid,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION private.bootstrap_profile(),private.self_profile(),private.complete_onboarding(text,date,text,bigint),private.update_profile(text,text,uuid,uuid,bigint),private.update_profile_private(date,text,bigint),private.get_profile(uuid),private.search_profiles(text,text,uuid,integer),private.set_block(uuid,boolean) TO authenticated;
REVOKE ALL ON FUNCTION public.bootstrap_profile(),public.get_my_profile(),public.complete_onboarding(text,date,text,bigint),public.update_profile(text,text,uuid,uuid,bigint),public.update_profile_private(date,text,bigint),public.get_profile(uuid),public.search_profiles(text,text,uuid,integer),public.set_block(uuid,boolean) FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.bootstrap_profile(),public.get_my_profile(),public.complete_onboarding(text,date,text,bigint),public.update_profile(text,text,uuid,uuid,bigint),public.update_profile_private(date,text,bigint),public.get_profile(uuid),public.search_profiles(text,text,uuid,integer),public.set_block(uuid,boolean) TO authenticated;

-- No business APIs for future modules and no physical Auth deletion until retention approval.
NOTIFY pgrst,'reload schema';
