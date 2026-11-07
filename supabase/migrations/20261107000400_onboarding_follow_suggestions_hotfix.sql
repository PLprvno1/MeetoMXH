-- Follow suggestions after signup. Forward only; owner deploys, never reset data.
-- Task 36 Follow-only/privacy is required; no dependency on Task 44 chat changes.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.b_active()') IS NULL
 OR to_regprocedure('private.social_target(uuid)') IS NULL
 OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
 OR to_regprocedure('public.set_follow(uuid,boolean)') IS NULL
 OR to_regprocedure('public.task36_capabilities()') IS NULL THEN
  RAISE EXCEPTION 'Deploy Task 36 Follow-only/privacy and capability prerequisites first';
 END IF;
 IF (SELECT count(*) FROM information_schema.columns WHERE table_schema='auth' AND table_name='users'
  AND column_name IN ('banned_until','deleted_at','is_anonymous'))<>3 THEN
  RAISE EXCEPTION 'Inventory Supabase Auth user eligibility columns before applying';
 END IF;
END $$;

CREATE FUNCTION private.get_onboarding_suggestions(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
 -- Pure read guard: b_actor -> lock_profile uses FOR UPDATE and cannot run in a
 -- PostgREST STABLE/READ ONLY RPC. Do not relax active/verified/onboarded checks.
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.social_target(actor) OR NOT EXISTS(SELECT 1 FROM auth.users u WHERE u.id=actor
  AND u.deleted_at IS NULL AND NOT coalesce(u.is_anonymous,false)
  AND (u.banned_until IS NULL OR u.banned_until<=statement_timestamp())) THEN
  RAISE SQLSTATE 'PT403' USING MESSAGE='suggestions_account_unavailable';
 END IF;
 -- Public and private basic identity are readable under Task 36 can_read_profile.
 -- No email, birth date, location, private bio, activity timestamps or Auth data is projected.
 -- Newest eligible profiles first; stable creation tuple supports bounded paging
 -- without requiring the caller to have any existing Follow relationships.
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object(
  'profile_id',q.id,'display_name',q.display_name,'username',q.username,
  'avatar_asset_id',q.avatar_asset_id,'avatar_preset',q.public_avatar_preset,'created_at',q.created_at,
  'following',EXISTS(SELECT 1 FROM public.user_follows f WHERE f.follower_id=actor AND f.followed_id=q.id),
  'requested',EXISTS(SELECT 1 FROM public.follow_requests r WHERE r.requester_id=actor AND r.recipient_id=q.id AND r.state='pending'))
  ORDER BY q.created_at DESC,q.id DESC)
 FROM (SELECT p.id,p.display_name,p.username,p.avatar_asset_id,p.public_avatar_preset,p.created_at
  FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id AND s.state='active'
  JOIN public.user_settings settings ON settings.user_id=p.id
  JOIN auth.users u ON u.id=p.id
  WHERE p.id<>actor AND p.onboarding_completed AND private.social_target(p.id)
   AND private.can_read_profile(p.id) AND NOT private.is_blocked(p.id)
   AND (u.email_confirmed_at IS NOT NULL OR u.phone_confirmed_at IS NOT NULL)
   AND u.deleted_at IS NULL AND NOT coalesce(u.is_anonymous,false)
   AND (u.banned_until IS NULL OR u.banned_until<=statement_timestamp())
   AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
  ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit)q),'[]'::jsonb);
END $$;
CREATE FUNCTION public.get_onboarding_suggestions(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
 SELECT private.get_onboarding_suggestions(p_after_time,p_after_id,p_limit)
$$;

-- Fix old Android builds as well: preserve their exact one-argument ABI/default.
-- CREATE OR REPLACE also installs it if optional migration 20261106000600 was absent.
CREATE OR REPLACE FUNCTION private.onboarding_suggestions(p_limit integer DEFAULT 40) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.get_onboarding_suggestions(NULL,NULL,p_limit)
$$;
CREATE OR REPLACE FUNCTION public.onboarding_suggestions(p_limit integer DEFAULT 40) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
 SELECT private.onboarding_suggestions(p_limit)
$$;
REVOKE ALL ON FUNCTION private.get_onboarding_suggestions(timestamptz,uuid,integer),public.get_onboarding_suggestions(timestamptz,uuid,integer),
 private.onboarding_suggestions(integer),public.onboarding_suggestions(integer) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.get_onboarding_suggestions(timestamptz,uuid,integer),public.get_onboarding_suggestions(timestamptz,uuid,integer),
 private.onboarding_suggestions(integer),public.onboarding_suggestions(integer) TO authenticated;
-- No RLS/table grants, follow mutation, account state or profile data changes.
-- Auth eligibility columns: https://github.com/supabase/auth/blob/master/internal/models/user.go
NOTIFY pgrst,'reload schema';
COMMIT;
