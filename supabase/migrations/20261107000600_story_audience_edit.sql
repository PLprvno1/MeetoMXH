-- Story audience editing. Forward only; owner deploys after Task 36/42.
-- No media replacement, view/reaction/message deletion, RLS or bucket changes.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.story_json(uuid)') IS NULL
 OR to_regprocedure('private.b_actor()') IS NULL
 OR to_regprocedure('private.c_actor()') IS NULL
 OR to_regprocedure('private.follows(uuid,uuid)') IS NULL
 OR to_regprocedure('public.get_story(uuid)') IS NULL THEN
  RAISE EXCEPTION 'Deploy Task 36/42 Story prerequisites first';
 END IF;
END $$;

CREATE FUNCTION public.get_story_privacy(p_story_id uuid) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); s public.stories; result jsonb; restricted boolean;
BEGIN
 SELECT * INTO s FROM public.stories WHERE id=p_story_id AND author_id=actor
  AND state='active' AND expires_at>statement_timestamp();
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 result:=private.story_json(s.id);
 IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 SELECT account_private INTO restricted FROM public.user_settings WHERE user_id=actor;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 RETURN jsonb_build_object('story_id',s.id,'audience',s.audience,'allowed_audiences',
  CASE WHEN restricted THEN jsonb_build_array('followers','private')
   ELSE jsonb_build_array('public','followers','private') END);
END $$;

CREATE FUNCTION public.set_story_audience(p_story_id uuid,p_audience text,p_expected_audience text) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); s public.stories; result jsonb; restricted boolean;
BEGIN
 PERFORM private.b_active();
 IF p_story_id IS NULL OR p_audience IS NULL OR p_audience NOT IN ('public','followers','private')
  OR p_expected_audience IS NULL OR p_expected_audience NOT IN ('public','followers','private','friends') THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_audience'; END IF;
 SELECT * INTO s FROM public.stories WHERE id=p_story_id AND author_id=actor
  AND state='active' AND expires_at>statement_timestamp() FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 -- A repeated desired state is a no-op. Never resurrect or change expiry/media.
 IF s.audience<>p_audience THEN
  IF s.audience<>p_expected_audience THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='story_audience_conflict'; END IF;
  SELECT account_private INTO restricted FROM public.user_settings WHERE user_id=actor FOR SHARE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
  IF restricted AND p_audience='public' THEN
   RAISE SQLSTATE 'PT422' USING MESSAGE='private_account_public_audience'; END IF;
  UPDATE public.stories SET audience=p_audience WHERE id=s.id;
 END IF;
 result:=private.story_json(s.id);
 IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 RETURN result;
END $$;
REVOKE ALL ON FUNCTION public.get_story_privacy(uuid),public.set_story_audience(uuid,text,text)
 FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.get_story_privacy(uuid),public.set_story_audience(uuid,text,text) TO authenticated;
-- Existing can_read_story -> can_read_media -> authenticated Storage GET checks
-- observe the new audience on subsequent requests, including old object paths.
-- Existing signed URLs/downloaded bytes cannot be retroactively revoked here.
NOTIFY pgrst,'reload schema';
COMMIT;
