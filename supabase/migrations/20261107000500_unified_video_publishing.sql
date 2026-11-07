-- Task 47: new videos have one post/media graph and two distribution surfaces.
-- Forward only. Owner deploys. No backfill, old IDs/assets/distribution are untouched.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('public.get_reel_upload_policy()') IS NULL
 OR to_regprocedure('public.create_post_reel_with_mentions(uuid,text,text,integer,jsonb,uuid[],uuid)') IS NULL
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='posts' AND column_name='publish_to_feed')
 OR to_regprocedure('private.task47_create_post_base(text,text,uuid[],uuid,uuid)') IS NOT NULL THEN
  RAISE EXCEPTION 'Inventory Task 36/43 and distribution hotfix 20261106001000 before Task 47';
 END IF;
END $$;
-- Preserve the validated creation implementation internally. New generic post
-- creates cannot silently bypass the explicit duration/video publishing contract.
ALTER FUNCTION private.create_post(text,text,uuid[],uuid,uuid) RENAME TO task47_create_post_base;
REVOKE ALL ON FUNCTION private.task47_create_post_base(text,text,uuid[],uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
CREATE FUNCTION private.create_post(p_body text,p_audience text,p_assets uuid[],p_client_request_id uuid,p_original uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
 PERFORM private.b_active();
 IF p_original IS NULL AND NOT EXISTS(SELECT 1 FROM public.posts WHERE author_id=actor AND client_request_id=p_client_request_id)
 AND EXISTS(SELECT 1 FROM public.media_assets WHERE id=ANY(p_assets) AND mime_type LIKE 'video/%') THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='use_unified_video_publishing';
 END IF;
 RETURN private.task47_create_post_base(p_body,p_audience,p_assets,p_client_request_id,p_original);
END $$;
REVOKE ALL ON FUNCTION private.create_post(text,text,uuid[],uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.create_post(text,text,uuid[],uuid,uuid) TO authenticated;

-- Caption/privacy edits may retain old video albums. Introducing/replacing a
-- video through generic edit must not bypass the new publishing contract.
ALTER FUNCTION private.edit_post(uuid,text,text,uuid[],bigint) RENAME TO task47_edit_post_base;
REVOKE ALL ON FUNCTION private.task47_edit_post_base(uuid,text,text,uuid[],bigint) FROM PUBLIC,anon,authenticated,service_role;
CREATE FUNCTION private.edit_post(p_post_id uuid,p_body text,p_audience text,p_assets uuid[],p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; previous uuid[];
BEGIN
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 SELECT coalesce(array_agg(asset_id ORDER BY position),'{}'::uuid[]) INTO previous FROM public.post_media WHERE post_id=p.id;
 IF (p.is_reel AND p_assets IS DISTINCT FROM previous) OR EXISTS(
  SELECT 1 FROM public.media_assets a WHERE a.id=ANY(p_assets) AND a.mime_type LIKE 'video/%' AND NOT a.id=ANY(previous)) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='use_unified_video_publishing'; END IF;
 RETURN private.task47_edit_post_base(p_post_id,p_body,p_audience,p_assets,p_expected_version);
END $$;
REVOKE ALL ON FUNCTION private.edit_post(uuid,text,text,uuid[],bigint) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.edit_post(uuid,text,text,uuid[],bigint) TO authenticated;
CREATE OR REPLACE FUNCTION private.create_reel(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
  p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); existing_id uuid; existing_reel boolean; existing_hash text;
  state_value text; fingerprint text; result jsonb; reel_id uuid; locked_assets integer;
BEGIN
  PERFORM private.b_active();
  IF p_video_asset_id IS NULL OR p_caption IS NULL OR char_length(p_caption)>2000
     OR p_audience IS NULL OR p_audience NOT IN ('public','followers','private')
     OR p_duration_ms IS NULL OR p_duration_ms NOT BETWEEN 1 AND 60000
     OR p_cover_position_ms IS NULL OR p_cover_position_ms<0 OR p_cover_position_ms>p_duration_ms
     OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel';
  END IF;
  fingerprint:=encode(sha256(convert_to(jsonb_build_array('reel',p_video_asset_id,p_cover_asset_id,p_caption,p_audience,
    p_duration_ms,p_cover_position_ms)::text,'UTF8')),'hex');
  PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':create-reel:'||p_client_request_id::text,0));
  SELECT p.id,p.is_reel,p.reel_request_hash,p.state INTO existing_id,existing_reel,existing_hash,state_value
    FROM public.posts p WHERE p.author_id=actor AND p.client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF existing_reel AND existing_hash=fingerprint AND state_value='published' THEN
      RETURN private.reel_card(existing_id);
    END IF;
    RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
  END IF;
  -- Lock video and cover in a stable UUID order before either can be attached
  -- or reused by another request in the same transaction.
  PERFORM 1 FROM public.media_assets a
    WHERE a.id=p_video_asset_id OR a.id=p_cover_asset_id
    ORDER BY a.id FOR UPDATE;
  GET DIAGNOSTICS locked_assets=ROW_COUNT;
  IF locked_assets<>(CASE WHEN p_cover_asset_id IS NULL THEN 1 ELSE 2 END) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_media';
  END IF;
  -- An uploaded READY asset is not yet readable through can_read_media until
  -- it is attached. Validate ownership and the existing direct-upload metadata
  -- contract here; do not grant temporary read access to unattached media.
  PERFORM 1 FROM public.media_assets a
    JOIN storage.objects o ON o.bucket_id=a.bucket_id AND o.name=a.object_path
    JOIN storage.buckets b ON b.id=a.bucket_id
    WHERE a.id=p_video_asset_id AND a.owner_id=actor AND a.purpose='post'
      AND a.bucket_id='social-media' AND a.mime_type='video/mp4' AND a.state='ready'
      AND a.size_bytes BETWEEN 1 AND 50000000
      AND a.metadata->>'upload_flow'='direct_storage_v1'
      AND a.metadata->>'validation'='storage_object_metadata'
      AND a.metadata->>'storage_confirmed_at' IS NOT NULL
      AND a.metadata->>'object_id'=o.id::text
      AND a.object_path=actor::text||'/'||a.id::text||'/original.mp4'
      AND o.owner_id=actor::text AND o.metadata->>'mimetype'=a.mime_type
      AND CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$'
        THEN (o.metadata->>'size')::bigint ELSE NULL END=a.size_bytes
      AND (b.file_size_limit IS NULL OR a.size_bytes<=b.file_size_limit)
      AND b.allowed_mime_types IS NOT NULL AND a.mime_type=ANY(b.allowed_mime_types)
      AND b.public IS FALSE
      AND NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=a.id)
      AND NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id);
  IF NOT FOUND THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_video'; END IF;
  IF p_cover_asset_id IS NOT NULL THEN
    PERFORM 1 FROM public.media_assets a
      JOIN storage.objects o ON o.bucket_id=a.bucket_id AND o.name=a.object_path
      JOIN storage.buckets b ON b.id=a.bucket_id
      WHERE a.id=p_cover_asset_id AND a.owner_id=actor
      AND a.purpose='post' AND a.bucket_id='social-media' AND a.mime_type IN ('image/jpeg','image/png','image/webp')
      AND a.state='ready' AND a.size_bytes BETWEEN 1 AND 10485760
      AND a.metadata->>'upload_flow'='direct_storage_v1'
      AND a.metadata->>'validation'='storage_object_metadata'
      AND a.metadata->>'storage_confirmed_at' IS NOT NULL
      AND a.metadata->>'object_id'=o.id::text
      AND a.object_path=actor::text||'/'||a.id::text||'/original.'||
        CASE a.mime_type WHEN 'image/jpeg' THEN 'jpg' WHEN 'image/png' THEN 'png' ELSE 'webp' END
      AND o.owner_id=actor::text AND o.metadata->>'mimetype'=a.mime_type
      AND CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$'
        THEN (o.metadata->>'size')::bigint ELSE NULL END=a.size_bytes
      AND (b.file_size_limit IS NULL OR a.size_bytes<=b.file_size_limit)
      AND b.allowed_mime_types IS NOT NULL AND a.mime_type=ANY(b.allowed_mime_types)
      AND b.public IS FALSE
      AND NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=a.id)
      AND NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id);
    IF NOT FOUND THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_cover'; END IF;
  END IF;
  result:=private.task47_create_post_base(p_caption,p_audience,ARRAY[p_video_asset_id],p_client_request_id,NULL);
  reel_id:=(result->>'id')::uuid;
  UPDATE public.posts SET is_reel=true,publish_to_feed=true,reel_duration_ms=p_duration_ms,
      reel_cover_position_ms=p_cover_position_ms,reel_cover_asset_id=p_cover_asset_id,reel_request_hash=fingerprint
    WHERE id=reel_id AND author_id=actor AND kind='original' AND group_id IS NULL AND state='published';
  IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='reel_creation_conflict'; END IF;
  RETURN private.reel_card(reel_id);
END $$;
-- Only the NEW-record branch above sets distribution=true. Existing request
-- hashes still return the original record; retries never backfill old Reels.
CREATE OR REPLACE FUNCTION public.create_reel(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; pid uuid;
BEGIN
 result:=private.create_reel(p_video_asset_id,p_cover_asset_id,p_caption,p_audience,p_duration_ms,p_cover_position_ms,p_client_request_id);
 pid:=(result->>'id')::uuid;
 IF EXISTS(SELECT 1 FROM public.posts WHERE id=pid AND mention_request_hash IS NOT NULL) THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
 RETURN result;
END $$;
CREATE OR REPLACE FUNCTION public.create_post_reel_with_mentions(p_video_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_mentions jsonb,p_tagged_users uuid[],p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); result jsonb; pid uuid; previous public.posts; payload_hash text; retry boolean;
BEGIN
 PERFORM private.b_active();
 payload_hash:=encode(sha256(convert_to(jsonb_build_array('post-reel',p_video_asset_id,p_caption,p_audience,
  p_duration_ms,p_mentions,p_tagged_users)::text,'UTF8')),'hex');
 SELECT * INTO previous FROM public.posts WHERE author_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 retry:=FOUND;
 IF retry AND previous.mention_request_hash IS DISTINCT FROM payload_hash THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
 result:=private.create_reel(p_video_asset_id,NULL,p_caption,p_audience,p_duration_ms,0,p_client_request_id);
 IF retry THEN RETURN result; END IF;
 pid:=(result->>'id')::uuid;
 PERFORM private.bind_mention_request('post',pid,p_client_request_id,payload_hash);
 PERFORM private.sync_content_mentions('post',pid,p_caption,p_mentions);
 PERFORM private.sync_post_tags(pid,p_tagged_users);
 RETURN private.reel_card(pid);
END $$;
CREATE OR REPLACE FUNCTION public.get_reel_upload_policy() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE max_bytes bigint; types text[]; is_public boolean;
BEGIN
 PERFORM private.b_active();
 SELECT least(50000000,coalesce(b.file_size_limit,50000000)),b.allowed_mime_types,b.public
 INTO max_bytes,types,is_public FROM storage.buckets b WHERE b.id='social-media';
 IF NOT FOUND OR is_public IS DISTINCT FROM false OR types IS NULL OR NOT ('video/mp4'=ANY(types)) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='reel_storage_unavailable'; END IF;
 RETURN jsonb_build_object('max_bytes',max_bytes,'max_duration_ms',60000,
  'reel_only',false,'unified_video_publishing',true,'automatic_video_distribution',true);
END $$;
-- Targeted legacy viewing is a read projection, never a rewrite/backfill. Keep
-- discovery limited to is_reel. Legacy albums retain all attachments and IDs.
-- A legacy target plays its first video; the original album remains accessible.
CREATE OR REPLACE FUNCTION public.get_reel_access(p_post_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 PERFORM private.b_active(); result:=private.reel_card(p_post_id);
 IF result IS NULL THEN
  SELECT private.post_json(p.id)||jsonb_build_object('legacy_video',true,
   'is_following',EXISTS(SELECT 1 FROM public.user_follows f WHERE f.follower_id=auth.uid() AND f.followed_id=p.author_id))
  INTO result FROM public.posts p WHERE p.id=p_post_id AND NOT p.is_reel AND p.kind='original'
   AND p.group_id IS NULL AND p.state='published' AND private.can_read_post(p.id)
   AND EXISTS(SELECT 1 FROM public.post_media pm JOIN public.media_assets a ON a.id=pm.asset_id
    WHERE pm.post_id=p.id AND a.state='ready' AND a.mime_type='video/mp4' AND private.can_read_media(a.id));
 END IF;
 RETURN jsonb_build_object('available',result IS NOT NULL,'reel',result);
END $$;
-- CREATE OR REPLACE retains existing privileges; explicitly close new helper.
-- No Storage bucket, RLS, media lifecycle, Task 36 or existing post data changes.
NOTIFY pgrst,'reload schema';
COMMIT;
