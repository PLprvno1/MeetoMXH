-- Explicitly deploy name + public username (nickname) search for existing accounts.
-- No bio search, profile rewrites or changes to private-account/content access.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.search_advanced_people(text,text,uuid,integer)') IS NULL
 OR to_regprocedure('private.social_search_text(text)') IS NULL
 OR to_regprocedure('private.social_target(uuid)') IS NULL THEN
  RAISE EXCEPTION 'Requires advanced search and follow-only privacy migrations';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc WHERE oid=to_regprocedure('private.search_advanced_people(text,text,uuid,integer)')
  AND (pg_get_userbyid(proowner)<>current_user OR prorettype<>'jsonb'::regtype OR NOT prosecdef)) THEN
  RAISE EXCEPTION 'Review search_advanced_people owner/signature before replacement';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.search_advanced_people(p_query text,p_after_name text,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE term text:=private.social_search_text(btrim(p_query,' @'));
BEGIN
 PERFORM private.b_active();
 IF p_query IS NULL OR length(btrim(p_query)) NOT BETWEEN 2 AND 100
 OR length(term)<2 OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
 OR (p_after_name IS NULL)<>(p_after_id IS NULL)
 OR (p_after_name IS NOT NULL AND length(p_after_name)>100) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search';
 END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.cursor_name,q.profile_id) FROM (
 SELECT p.id profile_id,p.display_name,p.username,p.avatar_asset_id,p.public_avatar_preset avatar_preset,
 lower(p.display_name) cursor_name,private.follows(auth.uid(),p.id) following
 FROM public.profiles p
 WHERE p.id<>auth.uid() AND private.can_read_profile(p.id) AND private.social_target(p.id)
 AND NOT EXISTS(SELECT 1 FROM regexp_split_to_table(term,'\s+') token
  WHERE strpos(private.social_search_text(concat_ws(' ',p.display_name,p.username)),token)=0)
 AND (p_after_name IS NULL OR (lower(p.display_name),p.id)>(p_after_name,p_after_id))
 ORDER BY lower(p.display_name),p.id LIMIT p_limit) q),'[]'::jsonb);
END $$;

-- Existing wrappers, ownership and EXECUTE ACLs remain in place.
NOTIFY pgrst,'reload schema';
COMMIT;
