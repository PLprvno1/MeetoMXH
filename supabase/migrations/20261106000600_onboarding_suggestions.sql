BEGIN;

DO $$ BEGIN
  IF to_regprocedure('private.b_actor()') IS NULL
     OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.social_target(uuid)') IS NULL
     OR to_regprocedure('public.set_follow(uuid,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Onboarding suggestions prerequisites missing; verify the deployed follow-only authorization catalog';
  END IF;
END $$;

CREATE FUNCTION private.onboarding_suggestions(p_limit integer DEFAULT 40) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
  PERFORM private.b_active();
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_suggestion_limit';
  END IF;
  RETURN coalesce((
    SELECT jsonb_agg(jsonb_build_object(
      'profile_id',q.id,'display_name',q.display_name,'avatar_asset_id',q.avatar_asset_id,
      'avatar_preset',q.public_avatar_preset,'following',q.following,'requested',q.requested)
      ORDER BY q.shared_following DESC,lower(q.display_name),q.id)
    FROM (
      SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset,
        EXISTS(SELECT 1 FROM public.user_follows f WHERE f.follower_id=actor AND f.followed_id=p.id) following,
        EXISTS(SELECT 1 FROM public.follow_requests f WHERE f.requester_id=actor AND f.recipient_id=p.id AND f.state='pending') requested,
        (SELECT count(*) FROM public.user_follows mine JOIN public.user_follows theirs
          ON theirs.followed_id=mine.followed_id
          WHERE mine.follower_id=actor AND theirs.follower_id=p.id) shared_following
      FROM public.profiles p
      JOIN public.account_status st ON st.user_id=p.id AND st.state='active'
      JOIN public.user_settings settings ON settings.user_id=p.id AND NOT settings.account_private
      JOIN auth.users auth_user ON auth_user.id=p.id AND auth_user.email_confirmed_at IS NOT NULL
      WHERE p.id<>actor AND p.onboarding_completed AND private.social_target(p.id)
        AND NOT private.is_blocked(p.id)
      ORDER BY (SELECT count(*) FROM public.user_follows mine JOIN public.user_follows theirs
        ON theirs.followed_id=mine.followed_id
        WHERE mine.follower_id=actor AND theirs.follower_id=p.id) DESC,lower(p.display_name),p.id
      LIMIT p_limit
    ) q
  ),'[]'::jsonb);
END $$;

CREATE FUNCTION public.onboarding_suggestions(p_limit integer DEFAULT 40) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.onboarding_suggestions(p_limit)
$$;

REVOKE ALL ON FUNCTION private.onboarding_suggestions(integer),public.onboarding_suggestions(integer)
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION private.onboarding_suggestions(integer),public.onboarding_suggestions(integer)
  TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
