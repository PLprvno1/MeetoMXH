-- Task 52: bind verified Openverse CC0 provenance to the canonical Post/Story.
-- Forward-only; owner deploys after Tasks 42, 43 and 47. No Cloud operation here.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('public.get_story(uuid)') IS NULL
 OR to_regprocedure('public.create_story(uuid,text,text,uuid)') IS NULL
 OR to_regprocedure('public.create_reel(uuid,uuid,text,text,integer,integer,uuid)') IS NULL
 OR to_regprocedure('public.get_reel_upload_policy()') IS NULL
 OR to_regprocedure('public.create_post_reel_with_mentions(uuid,text,text,integer,jsonb,uuid[],uuid)') IS NULL
 OR to_regprocedure('private.create_reel(uuid,uuid,text,text,integer,integer,uuid)') IS NULL
 OR to_regprocedure('private.bind_mention_request(text,uuid,uuid,text)') IS NULL
 OR to_regprocedure('private.sync_content_mentions(text,uuid,text,jsonb)') IS NULL
 OR to_regprocedure('private.sync_post_tags(uuid,uuid[])') IS NULL
 OR to_regprocedure('private.b_actor()') IS NULL
 OR to_regprocedure('private.c_actor()') IS NULL
 OR to_regprocedure('private.reel_card(uuid)') IS NULL
 OR to_regprocedure('private.post_json(uuid)') IS NULL
 OR to_regprocedure('private.story_json(uuid)') IS NULL
 OR to_regprocedure('private.can_read_post(uuid)') IS NULL
 OR to_regprocedure('private.can_read_post_base(uuid)') IS NULL
 OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
 OR to_regprocedure('private.can_read_story(uuid)') IS NULL
 OR to_regprocedure('private.can_read_media(uuid)') IS NULL
 OR to_regprocedure('private.b_active()') IS NULL THEN
  RAISE EXCEPTION 'Deploy Story, unified Reel and Task 47 prerequisites before Task 52';
 END IF;
 IF to_regclass('public.posts') IS NULL OR to_regclass('public.stories') IS NULL
 OR to_regclass('public.profiles') IS NULL OR to_regclass('public.post_reactions') IS NULL
 OR to_regclass('public.saved_posts') IS NULL OR to_regclass('public.user_follows') IS NULL
 OR to_regclass('public.media_assets') IS NULL OR to_regclass('public.story_views') IS NULL
 OR to_regclass('public.story_reactions') IS NULL OR to_regclass('public.post_media') IS NULL
 OR to_regclass('public.content_mentions') IS NULL OR to_regclass('public.post_user_tags') IS NULL THEN
  RAISE EXCEPTION 'Required Post/Story projection tables are missing';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public'
   AND table_name IN ('posts','stories') AND column_name IN ('music_metadata','music_metadata_hash')) THEN
  RAISE EXCEPTION 'Music metadata columns already exist; inventory the prior Task 52 attempt before retrying';
 END IF;
END $$;

CREATE FUNCTION private.valid_music_metadata(p jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE SECURITY INVOKER SET search_path='' AS $$
DECLARE v_duration bigint; v_start bigint; v_used bigint;
BEGIN
 IF p IS NULL OR jsonb_typeof(p) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
 IF octet_length(p::text)>16384
  OR (SELECT count(*) FROM jsonb_object_keys(p))<>13
  OR EXISTS(SELECT 1 FROM jsonb_object_keys(p) AS keys(name) WHERE name NOT IN
    ('id','title','creator','provider','source','source_url','audio_url','license','license_url','attribution','duration_ms','start_offset_ms','used_duration_ms')) THEN
  RETURN false;
 END IF;
 IF jsonb_typeof(p->'id') IS DISTINCT FROM 'string'
  OR NOT coalesce(p->>'id' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',false)
  OR jsonb_typeof(p->'title') IS DISTINCT FROM 'string' OR coalesce(length(btrim(p->>'title')),0) NOT BETWEEN 1 AND 300
  OR jsonb_typeof(p->'creator') IS DISTINCT FROM 'string' OR coalesce(length(p->>'creator'),0)>300
  OR jsonb_typeof(p->'provider') IS DISTINCT FROM 'string' OR coalesce(length(btrim(p->>'provider')),0) NOT BETWEEN 1 AND 200
  OR jsonb_typeof(p->'source') IS DISTINCT FROM 'string' OR coalesce(length(btrim(p->>'source')),0) NOT BETWEEN 1 AND 200
  OR jsonb_typeof(p->'attribution') IS DISTINCT FROM 'string' OR coalesce(length(p->>'attribution'),0)>800
  OR jsonb_typeof(p->'license') IS DISTINCT FROM 'string' OR p->>'license' IS DISTINCT FROM 'cc0'
  OR jsonb_typeof(p->'license_url') IS DISTINCT FROM 'string' OR coalesce(length(p->>'license_url'),0) NOT BETWEEN 1 AND 2048
  OR NOT coalesce(p->>'license_url' ~* '^https://creativecommons[.]org/publicdomain/zero/1[.]0/?([?#].*)?$',false)
  OR jsonb_typeof(p->'source_url') IS DISTINCT FROM 'string' OR coalesce(length(p->>'source_url'),0)>2048
  OR NOT coalesce(p->>'source_url' ~* '^https://[^/@[:space:]]+(/|[?#]|$)',false)
  OR jsonb_typeof(p->'audio_url') IS DISTINCT FROM 'string' OR coalesce(length(p->>'audio_url'),0)>2048
  OR NOT coalesce(p->>'audio_url' ~* '^https://[^/@[:space:]]+(/|[?#]|$)',false) THEN
  RETURN false;
 END IF;
 IF jsonb_typeof(p->'duration_ms') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'start_offset_ms') IS DISTINCT FROM 'number'
  OR jsonb_typeof(p->'used_duration_ms') IS DISTINCT FROM 'number'
  OR coalesce(p->>'duration_ms','') !~ '^[0-9]{1,8}$'
  OR coalesce(p->>'start_offset_ms','') !~ '^[0-9]{1,8}$'
  OR coalesce(p->>'used_duration_ms','') !~ '^[0-9]{1,5}$' THEN RETURN false; END IF;
 v_duration:=(p->>'duration_ms')::bigint;v_start:=(p->>'start_offset_ms')::bigint;v_used:=(p->>'used_duration_ms')::bigint;
 RETURN v_duration BETWEEN 1000 AND 3600000 AND v_start>=0 AND v_used BETWEEN 1000 AND 60000
   AND v_start<=v_duration-v_used;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;

ALTER TABLE public.posts ADD COLUMN music_metadata jsonb;
ALTER TABLE public.posts ADD COLUMN music_metadata_hash text;
ALTER TABLE public.stories ADD COLUMN music_metadata jsonb;
ALTER TABLE public.stories ADD COLUMN music_metadata_hash text;
ALTER TABLE public.posts ADD CONSTRAINT posts_music_metadata_check CHECK(
 (music_metadata IS NULL AND music_metadata_hash IS NULL) OR
 (music_metadata IS NOT NULL AND music_metadata_hash IS NOT NULL AND private.valid_music_metadata(music_metadata)
  AND music_metadata_hash=encode(sha256(convert_to(music_metadata::text,'UTF8')),'hex')));
ALTER TABLE public.stories ADD CONSTRAINT stories_music_metadata_check CHECK(
 (music_metadata IS NULL AND music_metadata_hash IS NULL) OR
 (music_metadata IS NOT NULL AND music_metadata_hash IS NOT NULL AND private.valid_music_metadata(music_metadata)
  AND music_metadata_hash=encode(sha256(convert_to(music_metadata::text,'UTF8')),'hex')));

CREATE FUNCTION private.bind_post_music(p_post_id uuid,p_music_metadata jsonb) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; fingerprint text;
BEGIN
 IF p_post_id IS NULL OR NOT private.valid_music_metadata(p_music_metadata) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_metadata'; END IF;
 fingerprint:=encode(sha256(convert_to(p_music_metadata::text,'UTF8')),'hex');
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND is_reel
   AND group_id IS NULL AND state='published' FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p.music_metadata_hash IS NOT NULL THEN
  IF p.music_metadata_hash<>fingerprint THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN;
 END IF;
 UPDATE public.posts SET music_metadata=p_music_metadata,music_metadata_hash=fingerprint
  WHERE id=p.id AND author_id=actor AND music_metadata_hash IS NULL;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
END $$;

CREATE FUNCTION private.bind_story_music(p_story_id uuid,p_music_metadata jsonb) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); s public.stories; fingerprint text;
BEGIN
 IF p_story_id IS NULL OR NOT private.valid_music_metadata(p_music_metadata) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_metadata'; END IF;
 fingerprint:=encode(sha256(convert_to(p_music_metadata::text,'UTF8')),'hex');
 SELECT * INTO s FROM public.stories WHERE id=p_story_id AND author_id=actor AND state='active'
   AND expires_at>statement_timestamp() FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 IF s.music_metadata_hash IS NOT NULL THEN
  IF s.music_metadata_hash<>fingerprint THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN;
 END IF;
 UPDATE public.stories SET music_metadata=p_music_metadata,music_metadata_hash=fingerprint
  WHERE id=s.id AND author_id=actor AND music_metadata_hash IS NULL;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
END $$;

CREATE FUNCTION private.create_reel_with_music(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid,p_music_metadata jsonb) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; pid uuid;
BEGIN
 PERFORM private.b_active();
 IF NOT private.valid_music_metadata(p_music_metadata) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_metadata'; END IF;
 IF p_duration_ms IS NULL OR p_duration_ms NOT BETWEEN 1000 AND 60000
  OR abs((p_music_metadata->>'used_duration_ms')::bigint-p_duration_ms)>300 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_duration'; END IF;
 result:=public.create_reel(p_video_asset_id,p_cover_asset_id,p_caption,p_audience,p_duration_ms,p_cover_position_ms,p_client_request_id);
 pid:=(result->>'id')::uuid;PERFORM private.bind_post_music(pid,p_music_metadata);
 RETURN private.reel_card(pid);
END $$;

CREATE FUNCTION private.create_post_reel_with_music(p_video_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_mentions jsonb,p_tagged_users uuid[],p_client_request_id uuid,p_music_metadata jsonb) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; pid uuid;
BEGIN
 PERFORM private.b_active();
 IF NOT private.valid_music_metadata(p_music_metadata) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_metadata'; END IF;
 IF p_duration_ms IS NULL OR p_duration_ms NOT BETWEEN 1000 AND 60000
  OR abs((p_music_metadata->>'used_duration_ms')::bigint-p_duration_ms)>300 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_duration'; END IF;
 result:=public.create_post_reel_with_mentions(p_video_asset_id,p_caption,p_audience,p_duration_ms,p_mentions,p_tagged_users,p_client_request_id);
 pid:=(result->>'id')::uuid;PERFORM private.bind_post_music(pid,p_music_metadata);
 RETURN private.reel_card(pid);
END $$;

CREATE FUNCTION private.create_story_with_music(p_media_asset_id uuid,p_caption text,p_audience text,
 p_client_request_id uuid,p_duration_ms integer,p_music_metadata jsonb) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; sid uuid;
BEGIN
 PERFORM private.b_active();
 IF NOT private.valid_music_metadata(p_music_metadata) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_metadata'; END IF;
 IF p_duration_ms IS NULL OR p_duration_ms NOT BETWEEN 1000 AND 60000
  OR abs((p_music_metadata->>'used_duration_ms')::bigint-p_duration_ms)>300 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_music_duration'; END IF;
 result:=public.create_story(p_media_asset_id,p_caption,p_audience,p_client_request_id);
 IF result->>'mime_type' IS DISTINCT FROM 'video/mp4' THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='music_story_requires_video'; END IF;
 sid:=(result->>'id')::uuid;PERFORM private.bind_story_music(sid,p_music_metadata);
 RETURN private.story_json(sid);
END $$;

CREATE FUNCTION private.get_music_publish_capabilities() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 RETURN jsonb_build_object('enabled',true,'contract_version',1,'max_duration_ms',60000,'license','cc0');
END $$;
CREATE FUNCTION public.get_music_publish_capabilities() RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT private.get_music_publish_capabilities() $$;
CREATE FUNCTION public.create_reel_with_music(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid,p_music_metadata jsonb) RETURNS jsonb
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.create_reel_with_music($1,$2,$3,$4,$5,$6,$7,$8)
$$;
CREATE FUNCTION public.create_post_reel_with_music(p_video_asset_id uuid,p_caption text,p_audience text,
 p_duration_ms integer,p_mentions jsonb,p_tagged_users uuid[],p_client_request_id uuid,p_music_metadata jsonb) RETURNS jsonb
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.create_post_reel_with_music($1,$2,$3,$4,$5,$6,$7,$8)
$$;
CREATE FUNCTION public.create_story_with_music(p_media_asset_id uuid,p_caption text,p_audience text,
 p_client_request_id uuid,p_duration_ms integer,p_music_metadata jsonb) RETURNS jsonb
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.create_story_with_music($1,$2,$3,$4,$5,$6)
$$;

-- Keep media provenance inside the same ACL-filtered content projections.
CREATE OR REPLACE FUNCTION private.story_json(sid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',s.id,'author_id',s.author_id,
 'author_name',CASE WHEN private.can_read_profile(p.id) THEN p.display_name ELSE 'Meeto' END,
 'avatar_asset_id',CASE WHEN private.can_read_profile(p.id) THEN p.avatar_asset_id END,
 'avatar_preset',CASE WHEN private.can_read_profile(p.id) THEN p.public_avatar_preset ELSE 'neutral' END,
 'media_asset_id',s.media_asset_id,'mime_type',a.mime_type,'caption',s.caption,'audience',s.audience,
 'music_metadata',s.music_metadata,'created_at',s.created_at,'expires_at',s.expires_at,
 'viewed',EXISTS(SELECT 1 FROM public.story_views v WHERE v.story_id=s.id AND v.viewer_id=auth.uid()),
 'liked',EXISTS(SELECT 1 FROM public.story_reactions r WHERE r.story_id=s.id AND r.user_id=auth.uid() AND r.reaction='like'),
 'views_count',CASE WHEN s.author_id=auth.uid() THEN (SELECT count(*) FROM public.story_views v WHERE v.story_id=s.id AND v.viewer_id<>s.author_id) END,
 'own',s.author_id=auth.uid())
 FROM public.stories s JOIN public.profiles p ON p.id=s.author_id JOIN public.media_assets a ON a.id=s.media_asset_id
 WHERE s.id=sid AND s.state='active' AND s.expires_at>statement_timestamp()
 AND private.can_read_story(s.id) AND private.can_read_media(s.media_asset_id)
$$;

CREATE OR REPLACE FUNCTION private.post_json(pid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',p.id,'author_id',p.author_id,'group_id',p.group_id,'state',p.state,
 'body',p.body,'audience',p.audience,'kind',p.kind,'is_reel',p.is_reel,'music_metadata',p.music_metadata,
 'original_post_id',CASE WHEN private.can_read_post_base(p.original_post_id) THEN p.original_post_id END,
 'original_available',p.kind='share' AND private.can_read_post_base(p.original_post_id),
 'author',CASE WHEN private.can_read_profile(p.author_id) THEN (SELECT jsonb_build_object('id',u.id,'display_name',u.display_name,'avatar_asset_id',u.avatar_asset_id,
   'avatar_preset',u.public_avatar_preset) FROM public.profiles u WHERE u.id=p.author_id) END,
 'reactions_count',p.reactions_count,'comments_count',p.comments_count,'shares_count',p.shares_count,
 'my_reaction',(SELECT r.reaction FROM public.post_reactions r WHERE r.post_id=p.id AND r.user_id=auth.uid()),
 'is_saved',EXISTS(SELECT 1 FROM public.saved_posts s WHERE s.user_id=auth.uid() AND s.post_id=p.id),
 'version',p.version,'created_at',p.created_at,'updated_at',p.updated_at,
 'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',m.asset_id,'position',m.position,'mime_type',a.mime_type) ORDER BY m.position)
   FROM public.post_media m JOIN public.media_assets a ON a.id=m.asset_id
   WHERE m.post_id=p.id AND private.can_read_media(m.asset_id)),'[]'::jsonb),
 'mentions',coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',CASE WHEN private.can_read_profile(m.mentioned_user_id) OR p.author_id=auth.uid() THEN m.mentioned_user_id END,
   'label',CASE WHEN private.can_read_profile(m.mentioned_user_id) THEN m.display_label ELSE '@Meeto user' END,
   'start_utf16',m.span_start_utf16,'end_utf16',m.span_end_utf16) ORDER BY m.span_start_utf16)
   FROM public.content_mentions m WHERE m.content_type='post' AND m.content_id=p.id),'[]'::jsonb),
 'tags',coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',CASE WHEN private.can_read_profile(t.tagged_user_id) OR t.tagged_by=auth.uid() THEN t.tagged_user_id END,
   'display_name',CASE WHEN private.can_read_profile(t.tagged_user_id) THEN t.display_name_snapshot ELSE 'Meeto user' END,
   'state',t.state)
   ORDER BY t.created_at,t.tagged_user_id) FROM public.post_user_tags t WHERE t.post_id=p.id
     AND (t.state='accepted' OR t.tagged_by=auth.uid())),'[]'::jsonb))
 FROM public.posts p WHERE p.id=pid AND private.can_read_post(p.id)
$$;

REVOKE ALL ON FUNCTION private.valid_music_metadata(jsonb),private.bind_post_music(uuid,jsonb),private.bind_story_music(uuid,jsonb),
 private.create_reel_with_music(uuid,uuid,text,text,integer,integer,uuid,jsonb),
 private.create_post_reel_with_music(uuid,text,text,integer,jsonb,uuid[],uuid,jsonb),
 private.create_story_with_music(uuid,text,text,uuid,integer,jsonb),private.get_music_publish_capabilities()
 FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.get_music_publish_capabilities(),
 public.create_reel_with_music(uuid,uuid,text,text,integer,integer,uuid,jsonb),
 public.create_post_reel_with_music(uuid,text,text,integer,jsonb,uuid[],uuid,jsonb),
 public.create_story_with_music(uuid,text,text,uuid,integer,jsonb) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.get_music_publish_capabilities(),
 public.create_reel_with_music(uuid,uuid,text,text,integer,integer,uuid,jsonb),
 public.create_post_reel_with_music(uuid,text,text,integer,jsonb,uuid[],uuid,jsonb),
 public.create_story_with_music(uuid,text,text,uuid,integer,jsonb) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
