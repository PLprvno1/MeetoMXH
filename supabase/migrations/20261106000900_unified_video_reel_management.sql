-- Task 43. FORWARD ONLY; prepare locally, owner deploys. Requires Task 36/37.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.create_reel(uuid,uuid,text,text,integer,integer,uuid)') IS NULL
 OR to_regprocedure('private.bind_mention_request(text,uuid,uuid,text)') IS NULL
 OR to_regprocedure('private.follows(uuid,uuid)') IS NULL THEN
  RAISE EXCEPTION 'Task 43 prerequisites missing: deploy Reels, mentions and Task 36 first';
 END IF;
END $$;

-- Authenticated clients learn only the effective upload limit, never Storage keys/paths.
CREATE FUNCTION public.get_reel_upload_policy() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE max_bytes bigint; types text[]; is_public boolean;
BEGIN
 PERFORM private.b_active();
 SELECT least(52428800,coalesce(b.file_size_limit,52428800)),b.allowed_mime_types,b.public
  INTO max_bytes,types,is_public FROM storage.buckets b WHERE b.id='social-media';
 IF NOT FOUND OR is_public IS DISTINCT FROM false OR types IS NULL OR NOT ('video/mp4'=ANY(types)) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='reel_storage_unavailable'; END IF;
 RETURN jsonb_build_object('max_bytes',max_bytes,'max_duration_ms',60000);
END $$;

-- Post uploads can recover a READY reservation after process/UI interruption.
-- Revalidate the existing object instead of allocating/uploading another video.
CREATE OR REPLACE FUNCTION private.prepare_direct_upload(p_purpose text,p_mime text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); result jsonb; a public.media_assets; started_at timestamptz:=transaction_timestamp();
BEGIN
 IF p_purpose IS NULL OR p_purpose NOT IN ('avatar','cover','post') OR p_mime IS NULL
  OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4')
  OR (p_purpose<>'post' AND p_mime='video/mp4') OR p_client_request_id IS NULL THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_upload'; END IF;
 SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN
  IF a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent'; END IF;
  IF a.purpose<>p_purpose OR a.mime_type<>p_mime THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF p_purpose='post' AND (a.state='ready' OR (a.state='pending' AND EXISTS(
   SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path))) THEN
   RETURN private.finalize_direct_upload(a.id);
  END IF;
 END IF;
 result:=private.prepare_upload(p_purpose,p_mime,p_client_request_id);
 SELECT * INTO a FROM public.media_assets WHERE id=(result->>'id')::uuid AND owner_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
 IF a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
  IF a.created_at<>started_at OR a.state<>'pending' THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent'; END IF;
  UPDATE public.media_assets SET metadata=metadata||jsonb_build_object('upload_flow','direct_storage_v1')
   WHERE id=a.id RETURNING * INTO a;
 END IF;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

-- Feed already includes published posts with is_reel=true. One atomic transaction,
-- one post ID, one video attachment, one interaction graph. Reuse strict Storage
-- object/bucket/ownership validation and actor/request advisory lock in create_reel.
CREATE FUNCTION public.create_post_reel_with_mentions(p_video_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_mentions jsonb,p_tagged_users uuid[],p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; pid uuid;
BEGIN
 PERFORM private.b_active();
 result:=private.create_reel(p_video_asset_id,NULL,p_caption,p_audience,p_duration_ms,0,p_client_request_id);
 pid:=(result->>'id')::uuid;
 PERFORM private.bind_mention_request('post',pid,p_client_request_id,
  encode(sha256(convert_to(jsonb_build_array('post-reel',p_video_asset_id,p_caption,p_audience,
    p_duration_ms,p_mentions,p_tagged_users)::text,'UTF8')),'hex'));
 PERFORM private.sync_content_mentions('post',pid,p_caption,p_mentions);
 PERFORM private.sync_post_tags(pid,p_tagged_users);
 RETURN private.reel_card(pid);
END $$;

-- Privacy-only mutation never detaches/re-attaches video, cover, tags or mentions.
CREATE FUNCTION public.set_reel_audience(p_post_id uuid,p_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts;
BEGIN
 PERFORM private.b_active();
 IF p_audience IS NULL OR p_audience NOT IN ('public','followers','private') THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_audience'; END IF;
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND is_reel
  AND group_id IS NULL AND state='published' FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p.audience=p_audience THEN RETURN private.reel_card(p.id); END IF;
 IF p_expected_version IS NULL OR p.version<>p_expected_version THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 IF p_audience='public' AND EXISTS(SELECT 1 FROM public.user_settings WHERE user_id=actor AND account_private) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='private_account_public_audience'; END IF;
 UPDATE public.posts SET audience=p_audience,version=version+1,updated_at=statement_timestamp() WHERE id=p.id;
 RETURN private.reel_card(p.id);
END $$;

-- Server-authoritative probe: unavailable is data, network failures are errors.
-- No IDs, metadata or privacy labels are disclosed for inaccessible posts.
CREATE FUNCTION public.get_reel_access(p_post_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 PERFORM private.b_active();result:=private.reel_card(p_post_id);
 RETURN jsonb_build_object('available',result IS NOT NULL,'reel',result);
END $$;

-- Reference graph used before retiring media. Album entries derive permission
-- from source_post_id; they do not independently own/copy the media file.
CREATE FUNCTION private.task43_asset_live(aid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
   WHERE pm.asset_id=aid AND p.state IN ('published','hidden'))
 OR EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=aid AND p.state IN ('published','hidden'))
 OR EXISTS(SELECT 1 FROM public.profiles p WHERE aid IN (p.avatar_asset_id,p.cover_asset_id))
 OR EXISTS(SELECT 1 FROM public.conversations c WHERE c.avatar_asset_id=aid AND c.state<>'deleted')
 OR EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=aid AND s.state='active' AND s.expires_at>statement_timestamp())
 OR EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id
   WHERE mm.asset_id=aid AND m.state='sent')
 OR EXISTS(SELECT 1 FROM public.profile_album_items i JOIN public.posts p ON p.id=i.source_post_id
   WHERE i.asset_id=aid AND p.state IN ('published','hidden'))
$$;

CREATE OR REPLACE FUNCTION private.retire_reel_cover_on_delete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF OLD.state IS DISTINCT FROM 'deleted' AND NEW.state='deleted' AND OLD.reel_cover_asset_id IS NOT NULL THEN
  PERFORM 1 FROM public.media_assets WHERE id=OLD.reel_cover_asset_id FOR UPDATE;
  IF NOT private.task43_asset_live(OLD.reel_cover_asset_id) THEN
   PERFORM private.retire_asset(OLD.reel_cover_asset_id);
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION public.delete_reel(p_post_id uuid,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; aid uuid;
BEGIN
 PERFORM private.b_active();
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND is_reel AND group_id IS NULL FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p.state='deleted' THEN RETURN jsonb_build_object('id',p.id,'state','deleted','version',p.version); END IF;
 IF p.state<>'published' OR p_expected_version IS NULL OR p.version<>p_expected_version THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 -- Soft-delete the shared object first. Existing authorization removes Feed,
 -- Reels, Saved/Search results and access through shared-message/old links.
 UPDATE public.posts SET state='deleted',body='',deleted_at=statement_timestamp(),
  updated_at=statement_timestamp(),version=version+1 WHERE id=p.id RETURNING * INTO p;
 FOR aid IN SELECT asset_id FROM public.post_media WHERE post_id=p.id ORDER BY asset_id LOOP
  PERFORM 1 FROM public.media_assets WHERE id=aid FOR UPDATE;
  IF NOT private.task43_asset_live(aid) THEN PERFORM private.retire_asset(aid); END IF;
 END LOOP;
 RETURN jsonb_build_object('id',p.id,'state',p.state,'version',p.version);
END $$;

-- Legacy Feed/Profile delete callers receive the same safe Reel lifecycle.
CREATE OR REPLACE FUNCTION private.delete_post(p_post_id uuid,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; a uuid;
BEGIN
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND group_id IS NULL FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p.is_reel THEN RETURN public.delete_reel(p_post_id,p_expected_version); END IF;
 IF p.state='deleted' THEN RETURN jsonb_build_object('id',p.id,'state','deleted','version',p.version); END IF;
 IF p.state<>'published' OR p_expected_version IS NULL OR p.version<>p_expected_version THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 FOR a IN SELECT asset_id FROM public.post_media WHERE post_id=p.id ORDER BY asset_id LOOP PERFORM private.retire_asset(a); END LOOP;
 IF p.kind='share' THEN UPDATE public.posts SET shares_count=shares_count-1 WHERE id=p.original_post_id; END IF;
 UPDATE public.posts SET state='deleted',body='',deleted_at=statement_timestamp(),updated_at=statement_timestamp(),version=version+1
  WHERE id=p.id RETURNING * INTO p;
 RETURN jsonb_build_object('id',p.id,'state',p.state,'version',p.version);
END $$;

REVOKE ALL ON FUNCTION private.task43_asset_live(uuid) FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.create_post_reel_with_mentions(uuid,text,text,integer,jsonb,uuid[],uuid),
 public.set_reel_audience(uuid,text,bigint),public.get_reel_access(uuid),public.delete_reel(uuid,bigint),public.get_reel_upload_policy()
 FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.create_post_reel_with_mentions(uuid,text,text,integer,jsonb,uuid[],uuid),
 public.set_reel_audience(uuid,text,bigint),public.get_reel_access(uuid),public.delete_reel(uuid,bigint),public.get_reel_upload_policy()
 TO authenticated;
-- Existing admin moderation entry points and Task 36 capability guard stay in force.
NOTIFY pgrst,'reload schema';
COMMIT;
