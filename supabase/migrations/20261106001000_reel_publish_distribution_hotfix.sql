-- HOTFIX after Task 43. Forward only. Owner deploys; no media/post is deleted.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('public.create_post_reel_with_mentions(uuid,text,text,integer,jsonb,uuid[],uuid)') IS NULL
 OR to_regprocedure('private.follows(uuid,uuid)') IS NULL THEN
  RAISE EXCEPTION 'Deploy Task 36 and Task 43 migration 20261106000900 first';
 END IF;
END $$;

-- false + is_reel = Reel-only; true + is_reel = Feed+Reel;
-- true + not is_reel = ordinary Feed post. This is distribution, never an ACL.
ALTER TABLE public.posts ADD COLUMN publish_to_feed boolean NOT NULL DEFAULT true;
ALTER TABLE public.posts ADD CONSTRAINT posts_distribution_check CHECK(is_reel OR publish_to_feed);
-- Under the source contract, Task 43 unified creates have a bound mention request
-- hash; direct create_reel never binds one. Preserve those unified posts in Feed.
UPDATE public.posts SET publish_to_feed=false WHERE is_reel AND mention_request_hash IS NULL;

-- Client fails closed if Cloud still has the old direct-Reel distribution rule.
CREATE OR REPLACE FUNCTION public.get_reel_upload_policy() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE max_bytes bigint; types text[]; is_public boolean;
BEGIN
 PERFORM private.b_active();
 SELECT least(52428800,coalesce(b.file_size_limit,52428800)),b.allowed_mime_types,b.public
  INTO max_bytes,types,is_public FROM storage.buckets b WHERE b.id='social-media';
 IF NOT FOUND OR is_public IS DISTINCT FROM false OR types IS NULL OR NOT ('video/mp4'=ANY(types)) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='reel_storage_unavailable'; END IF;
 RETURN jsonb_build_object('max_bytes',max_bytes,'max_duration_ms',60000,
  'reel_only',true,'unified_video_publishing',true);
END $$;

CREATE OR REPLACE FUNCTION public.create_reel(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; pid uuid;
BEGIN
 result:=private.create_reel(p_video_asset_id,p_cover_asset_id,p_caption,p_audience,p_duration_ms,p_cover_position_ms,p_client_request_id);
 pid:=(result->>'id')::uuid;
 -- A request already bound to unified publishing cannot silently change route.
 IF EXISTS(SELECT 1 FROM public.posts WHERE id=pid AND mention_request_hash IS NOT NULL) THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
 UPDATE public.posts SET publish_to_feed=false WHERE id=pid AND author_id=auth.uid();
 RETURN private.reel_card(pid);
END $$;

CREATE OR REPLACE FUNCTION public.create_post_reel_with_mentions(p_video_asset_id uuid,p_caption text,p_audience text,
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
 UPDATE public.posts SET publish_to_feed=true WHERE id=pid AND author_id=auth.uid();
 RETURN private.reel_card(pid);
END $$;

-- Also governs get_user_posts, whose wrapper calls this same function.
CREATE OR REPLACE FUNCTION private.get_feed(p_author uuid,p_mode text,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_mode IS NULL OR p_mode NOT IN ('all','following') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mode'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.post_json(q.id) ORDER BY q.created_at DESC,q.id DESC) FROM (
  SELECT p.id,p.created_at FROM public.posts p WHERE p.group_id IS NULL AND p.publish_to_feed AND private.can_read_post(p.id)
   AND (p_author IS NULL OR p.author_id=p_author)
   AND (p_mode='all' OR p.author_id=auth.uid() OR private.follows(auth.uid(),p.author_id))
   AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
   ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
-- Reels/get_user_reels continue using is_reel + the existing post/media ACL.
-- No DML/read grant on the new internal column, no public bucket or RLS change.
REVOKE ALL ON FUNCTION public.create_reel(uuid,uuid,text,text,integer,integer,uuid),
 public.create_post_reel_with_mentions(uuid,text,text,integer,jsonb,uuid[],uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.create_reel(uuid,uuid,text,text,integer,integer,uuid),
 public.create_post_reel_with_mentions(uuid,text,text,integer,jsonb,uuid[],uuid) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
