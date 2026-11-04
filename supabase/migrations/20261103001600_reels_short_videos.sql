-- Task 27 / migration 034: Reels backed by existing Posts, media and interactions.
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.posts') IS NULL OR to_regclass('public.post_media') IS NULL
     OR to_regclass('public.media_assets') IS NULL OR to_regclass('public.user_follows') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.user_blocks') IS NULL
     OR to_regclass('public.profiles') IS NULL OR to_regclass('public.groups') IS NULL
     OR to_regclass('public.stories') IS NULL OR to_regclass('public.messages') IS NULL
     OR to_regclass('public.message_media') IS NULL
     OR to_regclass('storage.objects') IS NULL OR to_regclass('storage.buckets') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.is_active()') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.group_member(uuid)') IS NULL
     OR to_regprocedure('private.can_read_story(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('private.create_post(text,text,uuid[],uuid,uuid)') IS NULL
     OR to_regprocedure('private.set_post_reaction(uuid,text)') IS NULL
     OR to_regprocedure('private.set_saved_post(uuid,boolean)') IS NULL
     OR to_regprocedure('private.retire_asset(uuid)') IS NULL THEN
    RAISE EXCEPTION 'Reels prerequisites missing; verify migrations 001-033 and the deployed schema';
  END IF;
END $$;

ALTER TABLE public.posts
  ADD COLUMN is_reel boolean NOT NULL DEFAULT false,
  ADD COLUMN reel_duration_ms integer,
  ADD COLUMN reel_cover_position_ms integer,
  ADD COLUMN reel_cover_asset_id uuid REFERENCES public.media_assets(id) ON DELETE SET NULL,
  ADD COLUMN reel_request_hash text,
  ADD CONSTRAINT posts_reel_metadata_check CHECK (
    (NOT is_reel AND reel_duration_ms IS NULL AND reel_cover_position_ms IS NULL
      AND reel_cover_asset_id IS NULL AND reel_request_hash IS NULL)
    OR (is_reel AND kind='original' AND group_id IS NULL
      AND reel_duration_ms IS NOT NULL AND reel_cover_position_ms IS NOT NULL AND reel_request_hash IS NOT NULL
      AND reel_duration_ms BETWEEN 1 AND 60000
      AND reel_cover_position_ms BETWEEN 0 AND reel_duration_ms
      AND reel_request_hash ~ '^[0-9a-f]{64}$')
  );
CREATE INDEX posts_reels_recent_idx ON public.posts(created_at DESC,id DESC)
  WHERE is_reel AND state='published' AND group_id IS NULL;
CREATE INDEX posts_reel_cover_asset_idx ON public.posts(reel_cover_asset_id)
  WHERE is_reel AND reel_cover_asset_id IS NOT NULL;

-- Extend only the existing ready-media authorization graph: a Reel cover is
-- readable exactly when its owning Reel post is readable to this viewer.
CREATE OR REPLACE FUNCTION private.can_read_media(aid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.media_assets a WHERE a.id=aid AND
 ((a.state='pending' AND a.owner_id=auth.uid() AND a.created_at>now()-interval '15 minutes') OR (a.state='ready' AND (
   EXISTS(SELECT 1 FROM public.profiles p WHERE ((p.avatar_asset_id=a.id AND a.purpose='avatar') OR (p.cover_asset_id=a.id AND a.purpose='cover')) AND a.owner_id=p.id AND private.can_read_profile(p.id))
   OR EXISTS(SELECT 1 FROM public.groups g WHERE g.cover_asset_id=a.id AND a.bucket_id='group-media' AND a.purpose='group' AND g.state='active' AND ((g.privacy IN ('public','private') AND g.listed) OR private.group_member(g.id)))
   OR EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id WHERE pm.asset_id=a.id AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(pm.post_id))
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id AND p.is_reel
     AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(p.id))
   OR EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND a.purpose='story' AND a.owner_id=s.author_id AND private.can_read_story(s.id))
   OR EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id WHERE mm.asset_id=a.id AND a.purpose='message' AND a.owner_id=m.sender_id AND m.state='sent' AND private.can_read_message(m.id))))));
$$;

CREATE FUNCTION private.reel_card(p_post_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.post_json(p.id)||jsonb_build_object(
    'is_reel',true,'reel_duration_ms',p.reel_duration_ms,
    'reel_cover_position_ms',p.reel_cover_position_ms,
    'reel_cover_asset_id',CASE WHEN p.reel_cover_asset_id IS NOT NULL AND private.can_read_media(p.reel_cover_asset_id)
      THEN p.reel_cover_asset_id END,
    'is_following',EXISTS(SELECT 1 FROM public.user_follows f
      WHERE f.follower_id=auth.uid() AND f.followed_id=p.author_id))
  FROM public.posts p
  WHERE p.id=p_post_id AND p.is_reel AND p.state='published' AND p.group_id IS NULL
    AND private.can_read_post(p.id)
    AND EXISTS(SELECT 1 FROM public.post_media pm JOIN public.media_assets a ON a.id=pm.asset_id
      WHERE pm.post_id=p.id AND a.state='ready' AND a.mime_type='video/mp4'
        AND private.can_read_media(a.id))
$$;

CREATE FUNCTION private.list_reels(p_mode text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_mode IS NULL OR p_mode NOT IN ('discover','following') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reels_mode';
  END IF;
  RETURN coalesce((SELECT jsonb_agg(private.reel_card(q.id) ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT p.id,p.created_at FROM public.posts p
      WHERE p.is_reel AND p.state='published' AND p.group_id IS NULL
        AND private.can_read_post(p.id)
        AND (p_mode<>'discover' OR p.audience='public' OR p.author_id=auth.uid())
        AND EXISTS(SELECT 1 FROM public.post_media pm JOIN public.media_assets a ON a.id=pm.asset_id
          WHERE pm.post_id=p.id AND a.state='ready' AND a.mime_type='video/mp4'
            AND private.can_read_media(a.id))
        AND (p_mode='discover' OR p.author_id=auth.uid() OR EXISTS(
          SELECT 1 FROM public.user_follows f WHERE f.follower_id=auth.uid() AND f.followed_id=p.author_id))
        AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
      ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.get_user_reels(p_author uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_author IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reels_author'; END IF;
  RETURN coalesce((SELECT jsonb_agg(private.reel_card(q.id) ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT p.id,p.created_at FROM public.posts p
      WHERE p.author_id=p_author AND p.is_reel AND p.state='published' AND p.group_id IS NULL
        AND private.can_read_post(p.id)
        AND EXISTS(SELECT 1 FROM public.post_media pm JOIN public.media_assets a ON a.id=pm.asset_id
          WHERE pm.post_id=p.id AND a.state='ready' AND a.mime_type='video/mp4'
            AND private.can_read_media(a.id))
        AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
      ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.create_reel(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
  p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); existing_id uuid; existing_reel boolean; existing_hash text;
  state_value text; fingerprint text; result jsonb; reel_id uuid; locked_assets integer;
BEGIN
  PERFORM private.b_active();
  IF p_video_asset_id IS NULL OR p_caption IS NULL OR char_length(p_caption)>2000
     OR p_audience IS NULL OR p_audience NOT IN ('public','friends','private')
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
      AND a.size_bytes BETWEEN 1 AND 52428800
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
  result:=private.create_post(p_caption,p_audience,ARRAY[p_video_asset_id],p_client_request_id,NULL);
  reel_id:=(result->>'id')::uuid;
  UPDATE public.posts SET is_reel=true,reel_duration_ms=p_duration_ms,
      reel_cover_position_ms=p_cover_position_ms,reel_cover_asset_id=p_cover_asset_id,reel_request_hash=fingerprint
    WHERE id=reel_id AND author_id=actor AND kind='original' AND group_id IS NULL AND state='published';
  IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='reel_creation_conflict'; END IF;
  RETURN private.reel_card(reel_id);
END $$;

CREATE FUNCTION private.retire_reel_cover_on_delete() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF OLD.state IS DISTINCT FROM 'deleted' AND NEW.state='deleted' AND OLD.reel_cover_asset_id IS NOT NULL THEN
    PERFORM private.retire_asset(OLD.reel_cover_asset_id);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER reels_cover_retire_after_post_delete AFTER UPDATE OF state ON public.posts
  FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM 'deleted' AND NEW.state='deleted' AND OLD.is_reel)
  EXECUTE FUNCTION private.retire_reel_cover_on_delete();

CREATE FUNCTION public.list_reels(p_mode text DEFAULT 'discover',p_after_time timestamptz DEFAULT NULL,
  p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 10) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.list_reels($1,$2,$3,$4)
$$;
CREATE FUNCTION public.get_user_reels(p_author uuid,p_after_time timestamptz DEFAULT NULL,
  p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.get_user_reels($1,$2,$3,$4)
$$;
CREATE FUNCTION public.create_reel(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
  p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.create_reel($1,$2,$3,$4,$5,$6,$7)
$$;

REVOKE ALL ON FUNCTION private.reel_card(uuid),private.list_reels(text,timestamptz,uuid,integer),
  private.get_user_reels(uuid,timestamptz,uuid,integer),
  private.create_reel(uuid,uuid,text,text,integer,integer,uuid),private.retire_reel_cover_on_delete() FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.list_reels(text,timestamptz,uuid,integer),
  private.get_user_reels(uuid,timestamptz,uuid,integer),
  private.create_reel(uuid,uuid,text,text,integer,integer,uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.list_reels(text,timestamptz,uuid,integer),
  public.get_user_reels(uuid,timestamptz,uuid,integer),
  public.create_reel(uuid,uuid,text,text,integer,integer,uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.list_reels(text,timestamptz,uuid,integer),
  public.get_user_reels(uuid,timestamptz,uuid,integer),
  public.create_reel(uuid,uuid,text,text,integer,integer,uuid) TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
