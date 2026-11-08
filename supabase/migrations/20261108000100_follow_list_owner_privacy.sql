-- Follow lists: public accounts are readable; private accounts are owner-only.
-- Source migration only. Owner deploys separately; no changes to content/media ACLs.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.list_follows(uuid,text,timestamptz,uuid,integer)') IS NULL
 OR to_regclass('public.user_settings') IS NULL OR to_regclass('public.follow_requests') IS NULL THEN
  RAISE EXCEPTION 'Follow-list privacy requires the canonical follow-only privacy migration';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc WHERE oid=to_regprocedure('private.list_follows(uuid,text,timestamptz,uuid,integer)')
  AND (pg_get_userbyid(proowner)<>current_user OR prorettype<>'jsonb'::regtype OR NOT prosecdef)) THEN
  RAISE EXCEPTION 'Review list_follows owner/signature before replacement';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.list_follows(p_target uuid,p_direction text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_direction IS NULL OR p_direction NOT IN ('followers','following') OR p_target IS NULL
  OR NOT private.can_read_profile(p_target) OR private.is_blocked(p_target) THEN
  RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable';
 END IF;
 -- An approved follow grants content access, never access to a private account's social graph.
 -- Missing settings fail closed for non-owners.
 IF p_target<>actor AND NOT EXISTS(SELECT 1 FROM public.user_settings s
  WHERE s.user_id=p_target AND NOT s.account_private) THEN
  RAISE SQLSTATE 'PT403' USING MESSAGE='follow_list_private';
 END IF;
 IF NOT private.account_content_allowed(p_target) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='follow_required'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('profile_id',q.id,'display_name',q.display_name,
  'avatar_asset_id',q.avatar_asset_id,'avatar_preset',q.public_avatar_preset,'created_at',q.created_at,
  'following',EXISTS(SELECT 1 FROM public.user_follows x WHERE x.follower_id=actor AND x.followed_id=q.id),
  'requested',EXISTS(SELECT 1 FROM public.follow_requests r WHERE r.requester_id=actor AND r.recipient_id=q.id AND r.state='pending'))
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
-- CREATE OR REPLACE preserves the existing owner and EXECUTE ACLs; no new client grants.
NOTIFY pgrst,'reload schema';
COMMIT;
