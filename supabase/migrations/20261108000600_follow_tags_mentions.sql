-- Follow-based tag candidates and @ suggestions; preserve recipient consent and media ACLs.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.search_mention_people(text,integer)') IS NULL
 OR to_regprocedure('private.sync_post_tags(uuid,uuid[])') IS NULL
 OR to_regprocedure('private.request_post_retag(uuid,uuid)') IS NULL
 OR to_regprocedure('private.social_search_text(text)') IS NULL THEN
  RAISE EXCEPTION 'Requires mentions, tags and follow-only privacy migrations';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc WHERE oid IN (
  to_regprocedure('private.search_mention_people(text,integer)'),
  to_regprocedure('private.sync_post_tags(uuid,uuid[])'),to_regprocedure('private.request_post_retag(uuid,uuid)'))
  AND (pg_get_userbyid(proowner)<>current_user OR NOT prosecdef)) THEN
  RAISE EXCEPTION 'Review tag/mention function owners before replacement';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.search_mention_people(p_query text,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE term text:=private.social_search_text(btrim(p_query,' @'));
BEGIN
 PERFORM private.b_active();
 IF p_query IS NULL OR length(btrim(p_query))>60 OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 12 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mention_search'; END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(x) ORDER BY lower(x.display_name),x.user_id) FROM (
  SELECT p.id user_id,p.display_name,p.username,p.avatar_asset_id,p.public_avatar_preset avatar_preset,
   private.follows(auth.uid(),p.id) following,private.follows(p.id,auth.uid()) follows_you,
   coalesce(s.tagging_policy IN ('everyone','followers'),false) can_tag
  FROM public.profiles p JOIN public.account_status a ON a.user_id=p.id AND a.state='active'
  LEFT JOIN public.user_settings s ON s.user_id=p.id
  WHERE p.id<>auth.uid() AND p.onboarding_completed AND private.can_read_profile(p.id)
   AND (private.follows(auth.uid(),p.id) OR private.follows(p.id,auth.uid()))
   AND NOT private.is_blocked(p.id)
   AND NOT EXISTS(SELECT 1 FROM regexp_split_to_table(term,'\s+') token
    WHERE strpos(private.social_search_text(concat_ws(' ',p.display_name,p.username)),token)=0)
  ORDER BY lower(p.display_name),p.id LIMIT p_limit) x),'[]'::jsonb);
END $$;

CREATE OR REPLACE FUNCTION private.sync_post_tags(p_post_id uuid,p_users uuid[]) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); uid uuid; policy text; review_required boolean; display_name text; post_group uuid;
BEGIN
  IF p_users IS NULL OR cardinality(p_users)>10 OR array_position(p_users,NULL) IS NOT NULL
     OR EXISTS(SELECT 1 FROM unnest(p_users) u GROUP BY u HAVING count(*)>1) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post_tags';
  END IF;
  SELECT p.group_id INTO post_group FROM public.posts p WHERE p.id=p_post_id AND p.author_id=actor
    AND p.state IN ('published','pending_review') AND private.can_read_post(p.id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  -- Removing a tag from the editor records a terminal decision; never delete
  -- recipient rejection/removal, since doing so would let an edit silently
  -- reset the consent state on the next save.
  UPDATE public.post_user_tags SET state='removed',resolved_at=statement_timestamp()
    WHERE post_id=p_post_id AND tagged_user_id<>ALL(p_users) AND state IN ('pending','accepted');
  FOREACH uid IN ARRAY p_users LOOP
    IF uid IS NULL OR uid=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post_tags'; END IF;
    SELECT s.tagging_policy,s.tag_review_required,p.display_name INTO policy,review_required,display_name
      FROM public.user_settings s JOIN public.profiles p ON p.id=s.user_id
      JOIN public.account_status a ON a.user_id=p.id AND a.state='active'
      WHERE s.user_id=uid AND p.onboarding_completed AND private.can_read_profile(uid)
        AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
          (b.blocker_id=actor AND b.blocked_id=uid) OR (b.blocker_id=uid AND b.blocked_id=actor))
        AND (s.tagging_policy='everyone' OR (s.tagging_policy='followers' AND (private.follows(actor,uid) OR private.follows(uid,actor))))
        AND (private.user_can_read_post(p_post_id,uid) OR EXISTS(
          SELECT 1 FROM public.posts p JOIN public.group_members gm ON gm.group_id=p.group_id
            AND gm.user_id=uid AND gm.state='active'
          WHERE p.id=p_post_id AND p.state='pending_review' AND gm.group_id IS NOT NULL));
    IF display_name IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='tag_target_unavailable'; END IF;
    INSERT INTO public.post_user_tags(post_id,tagged_user_id,tagged_by,display_name_snapshot,state,resolved_at)
      VALUES(p_post_id,uid,actor,display_name,CASE WHEN review_required THEN 'pending' ELSE 'accepted' END,
        CASE WHEN review_required THEN NULL ELSE statement_timestamp() END)
      -- Conflict updates only the display snapshot. Rejected/removed rows stay
      -- terminal here; request_post_retag is the sole explicit re-consent path.
      ON CONFLICT(post_id,tagged_user_id) DO UPDATE SET
        display_name_snapshot=excluded.display_name_snapshot;
  END LOOP;
END $$;
CREATE OR REPLACE FUNCTION private.request_post_retag(p_post_id uuid,p_user_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); target_policy text; changed integer; new_request uuid:=gen_random_uuid();
  prior_state text; prior_resolved_at timestamptz;
BEGIN
  IF p_post_id IS NULL OR p_user_id IS NULL OR p_user_id=actor THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_tag_request';
  END IF;
  PERFORM 1 FROM public.posts p WHERE p.id=p_post_id AND p.author_id=actor AND p.state='published'
    AND private.can_read_post(p.id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  SELECT s.tagging_policy INTO target_policy FROM public.user_settings s
    JOIN public.profiles p ON p.id=s.user_id JOIN public.account_status a ON a.user_id=p.id AND a.state='active'
    WHERE s.user_id=p_user_id AND p.onboarding_completed AND private.can_read_profile(p_user_id)
      AND (s.tagging_policy='everyone' OR (s.tagging_policy='followers' AND (private.follows(actor,p_user_id) OR private.follows(p_user_id,actor))))
      AND private.user_can_read_post(p_post_id,p_user_id)
      AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
        (b.blocker_id=actor AND b.blocked_id=p_user_id) OR (b.blocker_id=p_user_id AND b.blocked_id=actor));
  IF target_policy IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='tag_target_unavailable'; END IF;
  SELECT t.state,t.resolved_at INTO prior_state,prior_resolved_at FROM public.post_user_tags t
    WHERE t.post_id=p_post_id AND t.tagged_user_id=p_user_id FOR UPDATE;
  IF prior_state IS NULL OR prior_state NOT IN ('rejected','removed') OR prior_resolved_at IS NULL THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='tag_reconsent_unavailable';
  END IF;
  IF prior_resolved_at>statement_timestamp()-interval '7 days' THEN
    RAISE SQLSTATE 'PT429' USING MESSAGE='tag_reconsent_cooldown';
  END IF;
  UPDATE public.post_user_tags SET tagged_by=actor,state='pending',request_id=new_request,
    created_at=statement_timestamp(),resolved_at=NULL
    WHERE post_id=p_post_id AND tagged_user_id=p_user_id AND state=prior_state
      AND resolved_at=prior_resolved_at;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='tag_reconsent_unavailable'; END IF;
  RETURN jsonb_build_object('post_id',p_post_id,'user_id',p_user_id,'state','pending','request_id',new_request);
END $$;
-- Replacements retain existing owners, wrapper signatures and EXECUTE ACLs.
-- Do not reset settings that recipients explicitly set to nobody, or rejected/removed tags.
NOTIFY pgrst,'reload schema';
COMMIT;