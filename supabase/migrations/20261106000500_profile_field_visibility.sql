BEGIN;

DO $$
BEGIN
  IF to_regprocedure('private.update_profile_public_v36(text,text,text,uuid,uuid,text,text,bigint)') IS NULL
     OR to_regprocedure('private.get_profile(uuid)') IS NULL
     OR to_regprocedure('private.get_follow_counts(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL THEN
    RAISE EXCEPTION 'Profile field visibility prerequisites are missing; verify the installed Cloud catalog first';
  END IF;
END $$;

ALTER TABLE public.profile_private
  ADD COLUMN birth_date_audience text NOT NULL DEFAULT 'private'
    CHECK (birth_date_audience IN ('public','followers','private','friends')),
  ADD COLUMN gender_audience text NOT NULL DEFAULT 'private'
    CHECK (gender_audience IN ('public','followers','private','friends'));

-- Existing details remain owner-only until the owner explicitly changes each audience.
UPDATE public.profiles p
SET public_avatar_preset = CASE
  WHEN lower(btrim(d.gender)) IN ('nam','male') THEN 'male'
  WHEN lower(btrim(d.gender)) IN ('nữ','nu','female') THEN 'female'
  ELSE p.public_avatar_preset
END,
version=version+1,updated_at=statement_timestamp()
FROM public.profile_private d
WHERE d.user_id = p.id
  AND lower(btrim(coalesce(d.gender,''))) IN ('nam','male','nữ','nu','female')
  AND p.public_avatar_preset IS DISTINCT FROM CASE
    WHEN lower(btrim(d.gender)) IN ('nam','male') THEN 'male'
    ELSE 'female'
  END;

CREATE FUNCTION private.sync_public_avatar_preset_from_gender() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF NEW.gender IS DISTINCT FROM OLD.gender THEN
    UPDATE public.profiles SET public_avatar_preset=CASE
      WHEN lower(btrim(coalesce(NEW.gender,''))) IN ('nam','male') THEN 'male'
      WHEN lower(btrim(coalesce(NEW.gender,''))) IN ('nữ','nu','female') THEN 'female'
      ELSE 'neutral'
    END
    WHERE id=NEW.user_id AND public_avatar_preset IS DISTINCT FROM CASE
      WHEN lower(btrim(coalesce(NEW.gender,''))) IN ('nam','male') THEN 'male'
      WHEN lower(btrim(coalesce(NEW.gender,''))) IN ('nữ','nu','female') THEN 'female'
      ELSE 'neutral'
    END;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER profile_private_sync_avatar_preset
  AFTER UPDATE OF gender ON public.profile_private
  FOR EACH ROW EXECUTE FUNCTION private.sync_public_avatar_preset_from_gender();

CREATE OR REPLACE FUNCTION private.self_profile() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb; status text;
BEGIN
  SELECT state INTO status FROM public.account_status WHERE user_id=actor;
  IF status IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  IF status<>'active' THEN RETURN jsonb_build_object('profile_id',actor,'account_state',status); END IF;
  SELECT jsonb_build_object('profile_id',p.id,'username',p.username,'display_name',p.display_name,'bio',p.bio,
    'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,
    'avatar_preset',p.public_avatar_preset,'onboarding_completed',p.onboarding_completed,'version',p.version,
    'account_state',status,'birth_date',d.birth_date,'birth_date_audience',d.birth_date_audience,
    'gender',d.gender,'gender_audience',d.gender_audience,'location',d.current_location,
    'location_audience',d.location_audience,
    'settings',jsonb_build_object('account_private',s.account_private,
      'profile_audience',CASE WHEN s.account_private THEN 'private' ELSE 'public' END,
      'last_seen_audience',s.last_seen_audience,'notifications_enabled',s.notifications_enabled,
      'notification_preferences',s.notification_preferences),
    'email_verified',EXISTS(SELECT 1 FROM auth.users u WHERE u.id=actor AND u.email_confirmed_at IS NOT NULL))
  INTO result FROM public.profiles p JOIN public.profile_private d ON d.user_id=p.id
    JOIN public.user_settings s ON s.user_id=p.id WHERE p.id=actor;
  IF result IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  RETURN result;
END $$;

CREATE OR REPLACE FUNCTION private.get_profile(p_target uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  IF p_target=auth.uid() THEN RETURN private.self_profile(); END IF;
  IF NOT private.can_read_profile(p_target) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN (SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'username',p.username,
    'avatar_asset_id',p.avatar_asset_id,'avatar_preset',p.public_avatar_preset,
    'bio',CASE WHEN private.account_content_allowed(p.id) THEN p.bio END,
    'cover_asset_id',CASE WHEN private.account_content_allowed(p.id) THEN p.cover_asset_id END,
    'account_private',s.account_private,'content_allowed',private.account_content_allowed(p.id),
    'location',CASE WHEN private.account_content_allowed(p.id) AND
      (d.location_audience='public' OR (d.location_audience='followers' AND private.follows(auth.uid(),p.id)))
      THEN d.current_location END,
    'birth_date',CASE WHEN private.account_content_allowed(p.id) AND
      (d.birth_date_audience='public' OR (d.birth_date_audience='followers' AND private.follows(auth.uid(),p.id)))
      THEN d.birth_date END,
    'gender',CASE WHEN private.account_content_allowed(p.id) AND
      (d.gender_audience='public' OR (d.gender_audience='followers' AND private.follows(auth.uid(),p.id)))
      THEN d.gender END,
    'version',p.version)
  FROM public.profiles p JOIN public.user_settings s ON s.user_id=p.id
    JOIN public.profile_private d ON d.user_id=p.id WHERE p.id=p_target);
END $$;

CREATE OR REPLACE FUNCTION private.get_follow_counts(p_target uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  IF NOT private.can_read_profile(p_target) OR NOT private.social_target(p_target) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable';
  END IF;
  RETURN jsonb_build_object('target_id',p_target,
    'posts_count',(SELECT count(*) FROM public.posts p WHERE p.author_id=p_target
      AND p.group_id IS NULL AND private.can_read_post(p.id)),
    'followers_count',(SELECT count(*) FROM public.user_follows f
      WHERE f.followed_id=p_target AND private.social_target(f.follower_id)),
    'following_count',(SELECT count(*) FROM public.user_follows f
      WHERE f.follower_id=p_target AND private.social_target(f.followed_id)),
    'following',private.follows(auth.uid(),p_target),
    'requested',EXISTS(SELECT 1 FROM public.follow_requests
      WHERE requester_id=auth.uid() AND recipient_id=p_target AND state='pending'));
END $$;

CREATE FUNCTION private.update_profile_v37(
  p_display_name text,p_bio text,p_username text,p_avatar_asset_id uuid,p_cover_asset_id uuid,
  p_location text,p_location_audience text,p_birth_date date,p_birth_date_audience text,
  p_gender text,p_gender_audience text,p_expected_version bigint
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); old_birth date; old_gender text;
  old_birth_audience text; old_gender_audience text; private_fields_changed boolean;
BEGIN
  IF p_birth_date_audience IS NULL OR p_birth_date_audience NOT IN ('public','followers','private')
     OR p_gender_audience IS NULL OR p_gender_audience NOT IN ('public','followers','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_audience';
  END IF;
  IF p_birth_date IS NOT NULL AND (NOT isfinite(p_birth_date) OR p_birth_date<DATE '1900-01-01'
     OR p_birth_date>(timezone('UTC',statement_timestamp())::date-interval '13 years')::date) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_fields';
  END IF;
  IF p_gender IS NOT NULL AND (char_length(p_gender)>100 OR p_gender ~ '[[:cntrl:]]') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_fields';
  END IF;

  PERFORM private.lock_profile();
  SELECT birth_date,gender,birth_date_audience,gender_audience
    INTO old_birth,old_gender,old_birth_audience,old_gender_audience
    FROM public.profile_private WHERE user_id=actor FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  private_fields_changed := old_birth IS DISTINCT FROM p_birth_date
    OR old_gender IS DISTINCT FROM p_gender
    OR old_birth_audience IS DISTINCT FROM p_birth_date_audience
    OR old_gender_audience IS DISTINCT FROM p_gender_audience;

  PERFORM private.update_profile_public_v36(p_display_name,p_bio,p_username,p_avatar_asset_id,
    p_cover_asset_id,p_location,p_location_audience,p_expected_version);

  UPDATE public.profile_private SET birth_date=p_birth_date,gender=p_gender,
    birth_date_audience=p_birth_date_audience,gender_audience=p_gender_audience,
    updated_at=statement_timestamp() WHERE user_id=actor;

  IF private_fields_changed THEN
    UPDATE public.profiles SET version=version+1,updated_at=statement_timestamp()
      WHERE id=actor AND version=p_expected_version;
  END IF;
  RETURN private.self_profile();
END $$;

CREATE FUNCTION public.update_profile_v37(
  p_display_name text,p_bio text,p_username text,p_avatar_asset_id uuid,p_cover_asset_id uuid,
  p_location text,p_location_audience text,p_birth_date date,p_birth_date_audience text,
  p_gender text,p_gender_audience text,p_expected_version bigint
) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.update_profile_v37($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
$$;

CREATE OR REPLACE FUNCTION public.task36_capabilities() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  RETURN jsonb_build_object(
    'version',CASE WHEN to_regprocedure('private.update_profile_v37(text,text,text,uuid,uuid,text,text,date,text,text,text,bigint)') IS NOT NULL THEN 37 ELSE 36 END,
    'follow_only',true,'consent_messaging',true,'profile_field_visibility',true);
END $$;

REVOKE ALL ON FUNCTION private.update_profile_v37(text,text,text,uuid,uuid,text,text,date,text,text,text,bigint),
  public.update_profile_v37(text,text,text,uuid,uuid,text,text,date,text,text,text,bigint),
  private.sync_public_avatar_preset_from_gender(),
  public.task36_capabilities()
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION private.update_profile_v37(text,text,text,uuid,uuid,text,text,date,text,text,text,bigint),
  public.update_profile_v37(text,text,text,uuid,uuid,text,text,date,text,text,text,bigint),
  public.task36_capabilities() TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
