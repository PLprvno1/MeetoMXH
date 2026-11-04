-- Task 15 forward migration. Reuses the existing Stories/media/message schema.
DO $$
BEGIN
  IF to_regclass('public.stories') IS NULL
     OR to_regclass('public.story_views') IS NULL
     OR to_regclass('public.media_assets') IS NULL
     OR to_regclass('public.profiles') IS NULL
     OR to_regclass('public.profile_private') IS NULL
     OR to_regclass('public.user_settings') IS NULL
     OR to_regclass('public.account_status') IS NULL
     OR to_regclass('public.posts') IS NULL
     OR to_regclass('public.post_media') IS NULL
     OR to_regclass('public.post_reactions') IS NULL
     OR to_regclass('public.friendships') IS NULL
     OR to_regclass('public.friend_requests') IS NULL
     OR to_regclass('public.messages') IS NULL
     OR to_regclass('public.message_media') IS NULL
     OR to_regclass('public.conversations') IS NULL
     OR to_regclass('public.conversation_members') IS NULL
     OR to_regclass('storage.objects') IS NULL
     OR to_regclass('storage.buckets') IS NULL
     OR to_regprocedure('private.prepare_direct_upload(text,text,uuid)') IS NULL
     OR to_regprocedure('private.finalize_direct_upload(uuid)') IS NULL
     OR to_regprocedure('private.finalize_upload(uuid)') IS NULL
     OR to_regprocedure('private.phase_b_finalize_upload(uuid)') IS NULL
     OR to_regprocedure('private.c_message(uuid)') IS NULL
     OR to_regprocedure('private.c_lock(uuid,boolean)') IS NULL
     OR to_regprocedure('private.can_read_story(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post_base(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.is_active()') IS NULL
     OR to_regprocedure('private.is_blocked(uuid)') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.retire_asset(uuid)') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL
     OR to_regprocedure('private.require_identity()') IS NULL
     OR to_regprocedure('private.lock_profile(boolean)') IS NULL
     OR to_regprocedure('private.get_or_create_private(uuid)') IS NULL
     OR to_regprocedure('private.send_message(uuid,uuid,text,text,uuid[],uuid)') IS NULL THEN
    RAISE EXCEPTION 'Task 15 prerequisites are missing; verify live Cloud ledger/schema first';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM storage.buckets b WHERE b.id='social-media' AND b.public IS FALSE
      AND b.file_size_limit>=52428800
      AND b.allowed_mime_types @> ARRAY['image/png','image/jpeg','image/webp','video/mp4']::text[]) THEN
    RAISE EXCEPTION 'Task 15 requires a private social-media bucket with the expected MIME types and 50 MiB limit';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relname IN ('stories','story_views','media_assets')
      GROUP BY c.relrowsecurity HAVING count(*)=3 AND bool_and(c.relrowsecurity))
     OR NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='storage' AND c.relname='objects' AND c.relrowsecurity)
     OR NOT EXISTS(SELECT 1 FROM pg_policies p WHERE p.schemaname='public' AND p.tablename='stories'
      AND p.cmd='SELECT' AND p.qual ILIKE '%can_read_story%')
     OR NOT EXISTS(SELECT 1 FROM pg_policies p WHERE p.schemaname='public' AND p.tablename='story_views'
      AND p.cmd='SELECT' AND p.qual ILIKE '%can_read_story%') THEN
    RAISE EXCEPTION 'Task 15 requires existing RLS and Stories read policies to remain enabled';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_policies p WHERE p.schemaname='storage' AND p.tablename='objects'
      AND p.cmd='INSERT' AND p.permissive='PERMISSIVE' AND p.roles @> ARRAY['authenticated']::name[]
      AND p.with_check ILIKE '%social-media%' AND p.with_check ILIKE '%owner_id%'
      AND p.with_check ILIKE '%can_upload_object%')
     OR NOT EXISTS(SELECT 1 FROM pg_policies p WHERE p.schemaname='storage' AND p.tablename='objects'
      AND p.cmd='INSERT' AND p.permissive='RESTRICTIVE' AND p.roles @> ARRAY['authenticated']::name[]
      AND p.with_check ILIKE '%object.upload%')
     OR NOT EXISTS(SELECT 1 FROM pg_policies p WHERE p.schemaname='storage' AND p.tablename='objects'
      AND p.cmd='SELECT' AND p.roles @> ARRAY['authenticated']::name[]
      AND p.qual ILIKE '%social-media%' AND p.qual ILIKE '%can_read_object%')
     OR EXISTS(SELECT 1 FROM pg_policies p WHERE p.schemaname='storage' AND p.tablename='objects'
      AND p.cmd='INSERT' AND p.permissive='PERMISSIVE' AND p.roles @> ARRAY['authenticated']::name[]
      AND p.policyname NOT IN ('meeto_b_insert','meeto_c_insert'))
     OR EXISTS(SELECT 1 FROM pg_policies p WHERE p.schemaname='storage' AND p.tablename='objects'
      AND p.cmd='SELECT' AND p.permissive='PERMISSIVE' AND p.roles @> ARRAY['authenticated']::name[]
      AND p.policyname NOT IN ('meeto_b_get','meeto_c_get')) THEN
    RAISE EXCEPTION 'Task 15 Storage policies must enforce owned intents, standard uploads and authorized object reads';
  END IF;
END $$;

ALTER TABLE public.profiles
  ADD COLUMN public_avatar_preset text NOT NULL DEFAULT 'neutral'
  CHECK (public_avatar_preset IN ('neutral','male','female'));

CREATE OR REPLACE FUNCTION private.self_profile() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb; status text;
BEGIN
  SELECT state INTO status FROM public.account_status WHERE user_id=actor;
  IF status IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  IF status<>'active' THEN RETURN jsonb_build_object('profile_id',actor,'account_state',status); END IF;
  SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'bio',p.bio,
    'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,
    'avatar_preset',p.public_avatar_preset,'onboarding_completed',p.onboarding_completed,'version',p.version,
    'account_state',status,'birth_date',d.birth_date,'gender',d.gender,
    'settings',jsonb_build_object('profile_audience',s.profile_audience,'last_seen_audience',s.last_seen_audience,
      'notifications_enabled',s.notifications_enabled,'notification_preferences',s.notification_preferences),
    'email_verified',EXISTS(SELECT 1 FROM auth.users u WHERE u.id=actor AND u.email_confirmed_at IS NOT NULL))
  INTO result FROM public.profiles p JOIN public.profile_private d ON d.user_id=p.id
    JOIN public.user_settings s ON s.user_id=p.id WHERE p.id=actor;
  IF result IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  RETURN result;
END $$;

CREATE OR REPLACE FUNCTION private.get_profile(p_target uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb;
BEGIN
  IF p_target=actor THEN RETURN private.self_profile(); END IF;
  IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
  SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'bio',p.bio,
    'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,
    'avatar_preset',p.public_avatar_preset,'version',p.version)
  INTO result FROM public.profiles p
  WHERE p.id=p_target AND p.onboarding_completed AND private.can_read_profile(p.id);
  IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN result;
END $$;

CREATE OR REPLACE FUNCTION private.search_profiles(p_prefix text,p_after_name text,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.require_identity();
  IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
  IF p_prefix IS NULL OR char_length(btrim(p_prefix)) NOT BETWEEN 1 AND 100 OR p_limit IS NULL
     OR p_limit NOT BETWEEN 1 AND 50 OR (p_after_name IS NULL)<>(p_after_id IS NULL)
     OR (p_after_name IS NOT NULL AND char_length(p_after_name)>100) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search';
  END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('profile_id',q.id,'display_name',q.display_name,
    'avatar_asset_id',q.avatar_asset_id,'avatar_preset',q.public_avatar_preset,'cursor_name',lower(q.display_name)))
    FROM (SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset FROM public.profiles p
      WHERE p.id<>auth.uid() AND p.onboarding_completed AND private.can_read_profile(p.id)
        AND starts_with(lower(p.display_name),lower(btrim(p_prefix)))
        AND (p_after_name IS NULL OR (lower(p.display_name),p.id)>(lower(p_after_name),p_after_id))
      ORDER BY lower(p.display_name),p.id LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE OR REPLACE FUNCTION private.list_relations(p_mode text,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_mode IS NULL OR p_mode NOT IN ('friends','received','sent','suggestions') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mode'; END IF;
  RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.id DESC) FROM (
    SELECT p.id,
      CASE WHEN private.can_read_profile(p.id) THEN p.display_name END AS display_name,
      CASE WHEN private.can_read_profile(p.id) THEN p.avatar_asset_id END AS avatar_asset_id,
      CASE WHEN private.can_read_profile(p.id) THEN p.public_avatar_preset END AS avatar_preset,
      coalesce(r.created_at,f.created_at,p.created_at) AS created_at,r.id AS request_id,r.state
    FROM public.profiles p
      LEFT JOIN public.friendships f ON f.user_low=least(auth.uid(),p.id) AND f.user_high=greatest(auth.uid(),p.id)
      LEFT JOIN public.friend_requests r ON r.pair_low=least(auth.uid(),p.id) AND r.pair_high=greatest(auth.uid(),p.id) AND r.state='pending'
      JOIN public.account_status s ON s.user_id=p.id AND s.state='active'
    WHERE p.id<>auth.uid() AND p.onboarding_completed AND NOT private.is_blocked(p.id)
      AND CASE p_mode WHEN 'friends' THEN f.user_low IS NOT NULL WHEN 'sent' THEN r.requester_id=auth.uid()
        WHEN 'received' THEN r.recipient_id=auth.uid() ELSE f.user_low IS NULL AND r.id IS NULL AND private.can_read_profile(p.id) END
      AND (p_after_time IS NULL OR (coalesce(r.created_at,f.created_at,p.created_at),p.id)<(p_after_time,p_after_id))
    ORDER BY coalesce(r.created_at,f.created_at,p.created_at) DESC,p.id DESC LIMIT p_limit
  ) q),'[]'::jsonb);
END $$;

CREATE OR REPLACE FUNCTION private.post_json(pid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT jsonb_build_object('id',p.id,'author_id',p.author_id,'body',p.body,'audience',p.audience,'kind',p.kind,
    'original_post_id',CASE WHEN private.can_read_post_base(p.original_post_id) THEN p.original_post_id END,
    'original_available',p.kind='share' AND private.can_read_post_base(p.original_post_id),
    'author',CASE WHEN private.can_read_profile(p.author_id) THEN
      (SELECT jsonb_build_object('id',u.id,'display_name',u.display_name,'avatar_asset_id',u.avatar_asset_id,
        'avatar_preset',u.public_avatar_preset) FROM public.profiles u WHERE u.id=p.author_id) END,
    'reactions_count',p.reactions_count,'comments_count',p.comments_count,'shares_count',p.shares_count,
    'my_reaction',(SELECT r.reaction FROM public.post_reactions r WHERE r.post_id=p.id AND r.user_id=auth.uid()),
    'version',p.version,'created_at',p.created_at,'updated_at',p.updated_at,
    'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',m.asset_id,'position',m.position,
      'mime_type',a.mime_type) ORDER BY m.position) FROM public.post_media m
      JOIN public.media_assets a ON a.id=m.asset_id
      WHERE m.post_id=p.id AND private.can_read_media(m.asset_id)),'[]'::jsonb))
  FROM public.posts p WHERE p.id=pid AND private.can_read_post(p.id);
$$;

CREATE OR REPLACE FUNCTION private.prepare_direct_story_upload(p_mime text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.media_assets; aid uuid:=gen_random_uuid(); ext text;
BEGIN
  IF p_mime IS NULL OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4') OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_media'; END IF;
  SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF a.purpose<>'story' OR a.mime_type<>p_mime OR a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
    IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN
      RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
    RETURN to_jsonb(a)-'metadata'-'client_request_id';
  END IF;
  IF (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state='pending')>=10
     OR (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted')>=100
     OR coalesce((SELECT sum(CASE WHEN state IN ('pending','pending_delete','quarantined')
       THEN CASE WHEN bucket_id='profile-media' THEN 10485760 ELSE 52428800 END ELSE coalesce(size_bytes,0) END)
       FROM public.media_assets WHERE owner_id=actor AND state<>'deleted'),0)+52428800>524288000 THEN
    RAISE SQLSTATE 'PT429' USING MESSAGE='media_quota'; END IF;
  ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' ELSE 'mp4' END;
  INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata)
    VALUES(aid,actor,p_client_request_id,'social-media',actor::text||'/'||aid::text||'/original.'||ext,
      'story',p_mime,jsonb_build_object('upload_flow','direct_storage_v1')) RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE FUNCTION public.prepare_direct_story_upload(p_mime text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.prepare_direct_story_upload(p_mime,p_client_request_id)
$$;

CREATE OR REPLACE FUNCTION private.finalize_story_upload(p_asset_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); a public.media_assets; oid uuid; obj_owner text;
  obj_size bigint; obj_mime text; limit_bytes bigint; mimes text[]; bucket_public boolean; ext text;
BEGIN
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
  IF a.purpose<>'story' OR a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid'; END IF;
  IF a.state NOT IN ('pending','ready') OR (a.state='pending' AND a.created_at<=statement_timestamp()-interval '15 minutes') THEN
    RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  ext:=CASE a.mime_type WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' WHEN 'video/mp4' THEN 'mp4' END;
  IF ext IS NULL OR a.bucket_id<>'social-media'
     OR a.object_path<>actor::text||'/'||a.id::text||'/original.'||ext THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid'; END IF;
  SELECT o.id,o.owner_id,
    CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$' THEN (o.metadata->>'size')::bigint ELSE NULL END,
    o.metadata->>'mimetype',b.file_size_limit,b.allowed_mime_types,b.public
  INTO oid,obj_owner,obj_size,obj_mime,limit_bytes,mimes,bucket_public
  FROM storage.objects o JOIN storage.buckets b ON b.id=o.bucket_id
  WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path;
  IF NOT FOUND OR oid IS NULL OR obj_owner IS DISTINCT FROM actor::text OR obj_size IS NULL OR obj_size<1
     OR obj_size>52428800 OR (limit_bytes IS NOT NULL AND obj_size>limit_bytes)
     OR obj_mime IS DISTINCT FROM a.mime_type OR mimes IS NULL OR NOT (a.mime_type=ANY(mimes))
     OR bucket_public IS DISTINCT FROM false THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid'; END IF;
  IF a.state='ready' THEN RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
  UPDATE public.media_assets SET size_bytes=obj_size,
    metadata=(metadata-'verified'-'sha256'-'width'-'height'-'duration')||jsonb_build_object(
      'object_id',oid,'validation','storage_object_metadata','storage_confirmed_at',statement_timestamp()),
    state='ready',ready_at=statement_timestamp() WHERE id=a.id RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE OR REPLACE FUNCTION private.finalize_upload(p_asset_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); a public.media_assets;
BEGIN
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor;
  IF NOT FOUND THEN PERFORM private.lock_profile(); RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
  IF a.metadata->>'upload_flow'='direct_storage_v1' AND a.purpose='story' THEN
    RETURN private.finalize_story_upload(p_asset_id);
  END IF;
  IF a.metadata->>'upload_flow'='direct_storage_v1' THEN RETURN private.finalize_direct_upload(p_asset_id); END IF;
  IF a.purpose<>'message' THEN RETURN private.phase_b_finalize_upload(p_asset_id); END IF;
  PERFORM private.c_lock((a.metadata->>'conversation_id')::uuid);
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
  IF a.state='ready' THEN RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
  IF a.state<>'pending' OR a.created_at<=now()-interval '15 minutes' THEN
    RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  IF a.metadata->>'verified' IS DISTINCT FROM 'true' OR NOT EXISTS(
    SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path
      AND o.id::text=a.metadata->>'object_id' AND o.owner_id=actor::text
      AND (o.metadata->>'size')::bigint=a.size_bytes) THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='verification_pending'; END IF;
  UPDATE public.media_assets SET state='ready',ready_at=statement_timestamp()
    WHERE id=a.id RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE OR REPLACE FUNCTION private.c_message(mid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT jsonb_build_object('id',m.id,'conversation_id',m.conversation_id,'seq',m.seq,'sender_id',m.sender_id,
    'kind',m.kind,'body',m.body,'state',m.state,'version',m.version,'created_at',m.created_at,
    'edited_at',m.edited_at,'recalled_at',m.recalled_at,
    'reply_to_message_id',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN m.reply_to_message_id END,
    'reply',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN
      (SELECT jsonb_build_object('id',r.id,'sender_id',r.sender_id,'state',r.state,'body',r.body,'kind',r.kind)
       FROM public.messages r WHERE r.id=m.reply_to_message_id) END,
    'reply_story_id',m.reply_story_id,
    'reply_story',CASE WHEN m.reply_story_id IS NOT NULL THEN
      (SELECT jsonb_build_object('story_id',s.id,'author_id',s.author_id,'caption',s.caption,
        'expires_at',s.expires_at,'expired',s.expires_at<=now() OR s.state<>'active')
       FROM public.stories s WHERE s.id=m.reply_story_id) END,
    'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',mm.asset_id,'position',mm.position)
      ORDER BY mm.position) FROM public.message_media mm WHERE mm.message_id=m.id AND m.state='sent'
      AND private.can_read_media(mm.asset_id)),'[]'::jsonb))
  FROM public.messages m WHERE m.id=mid AND private.can_read_message(m.id);
$$;

CREATE FUNCTION private.story_json(sid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT jsonb_build_object('id',s.id,'author_id',s.author_id,
    'author_name',CASE WHEN private.can_read_profile(p.id) THEN p.display_name ELSE 'Meeto' END,
    'avatar_asset_id',CASE WHEN private.can_read_profile(p.id) THEN p.avatar_asset_id END,
    'avatar_preset',CASE WHEN private.can_read_profile(p.id) THEN p.public_avatar_preset ELSE 'neutral' END,
    'media_asset_id',s.media_asset_id,'mime_type',a.mime_type,'caption',s.caption,'audience',s.audience,
    'created_at',s.created_at,'expires_at',s.expires_at,
    'viewed',EXISTS(SELECT 1 FROM public.story_views v WHERE v.story_id=s.id AND v.viewer_id=auth.uid()),
    'own',s.author_id=auth.uid())
  FROM public.stories s JOIN public.profiles p ON p.id=s.author_id
    JOIN public.media_assets a ON a.id=s.media_asset_id
  WHERE s.id=sid AND s.state='active' AND s.expires_at>statement_timestamp()
    AND private.can_read_story(s.id) AND private.can_read_media(s.media_asset_id);
$$;

CREATE FUNCTION private.get_stories(p_author uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_limit'; END IF;
  RETURN coalesce((SELECT jsonb_agg(private.story_json(q.id) ORDER BY q.created_at,q.id)
    FROM (SELECT s.id,s.created_at FROM public.stories s
      WHERE s.state='active' AND s.expires_at>statement_timestamp()
        AND (p_author IS NULL OR s.author_id=p_author) AND private.can_read_story(s.id)
      ORDER BY s.created_at DESC,s.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.create_story(p_media_asset_id uuid,p_caption text,p_audience text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.media_assets; s public.stories;
BEGIN
  IF p_media_asset_id IS NULL OR p_client_request_id IS NULL OR p_caption IS NULL
     OR char_length(p_caption)>2000 OR p_caption ~ '[[:cntrl:]]'
     OR p_audience IS NULL OR p_audience NOT IN ('public','friends','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story'; END IF;
  SELECT * INTO s FROM public.stories WHERE author_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF s.media_asset_id<>p_media_asset_id OR s.caption<>p_caption OR s.audience<>p_audience THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
    RETURN private.story_json(s.id);
  END IF;
  SELECT * INTO a FROM public.media_assets WHERE id=p_media_asset_id FOR UPDATE;
  IF NOT FOUND OR a.owner_id IS DISTINCT FROM actor OR a.purpose<>'story' OR a.state<>'ready'
     OR a.mime_type NOT IN ('image/png','image/jpeg','image/webp','video/mp4') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_asset'; END IF;
  INSERT INTO public.stories(author_id,media_asset_id,caption,audience,client_request_id)
    VALUES(actor,a.id,btrim(p_caption),p_audience,p_client_request_id) RETURNING * INTO s;
  RETURN private.story_json(s.id);
END $$;

CREATE FUNCTION private.mark_story_viewed(p_story_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); owner uuid; inserted integer;
BEGIN
  SELECT author_id INTO owner FROM public.stories WHERE id=p_story_id AND state='active'
    AND expires_at>statement_timestamp();
  IF NOT FOUND OR NOT private.can_read_story(p_story_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
  IF owner=actor THEN RETURN jsonb_build_object('story_id',p_story_id,'viewed',false,'own',true); END IF;
  INSERT INTO public.story_views(story_id,viewer_id) VALUES(p_story_id,actor) ON CONFLICT DO NOTHING;
  GET DIAGNOSTICS inserted=ROW_COUNT;
  RETURN jsonb_build_object('story_id',p_story_id,'viewed',true,'first_view',inserted=1);
END $$;

CREATE FUNCTION private.get_story_viewers(p_story_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR (p_after_time IS NULL)<>(p_after_id IS NULL) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_cursor'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.stories WHERE id=p_story_id AND author_id=actor AND state='active') THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object(
    'viewer_id',CASE WHEN private.can_read_profile(q.id) THEN q.id END,
    'display_name',CASE WHEN private.can_read_profile(q.id) THEN q.display_name ELSE 'Meeto user' END,
    'avatar_asset_id',CASE WHEN private.can_read_profile(q.id) THEN q.avatar_asset_id END,
    'avatar_preset',CASE WHEN private.can_read_profile(q.id) THEN q.public_avatar_preset ELSE 'neutral' END,
    'viewed_at',q.first_viewed_at)
    ORDER BY q.first_viewed_at,q.id) FROM (
      SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset,v.first_viewed_at
      FROM public.story_views v JOIN public.profiles p ON p.id=v.viewer_id
      WHERE v.story_id=p_story_id AND (p_after_time IS NULL OR (v.first_viewed_at,p.id)>(p_after_time,p_after_id))
      ORDER BY v.first_viewed_at,p.id LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.delete_story(p_story_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); asset uuid;
BEGIN
  UPDATE public.stories SET state='deleted',deleted_at=statement_timestamp()
    WHERE id=p_story_id AND author_id=actor AND state='active' RETURNING media_asset_id INTO asset;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
  PERFORM private.retire_asset(asset);
  RETURN jsonb_build_object('story_id',p_story_id,'deleted',true);
END $$;

CREATE FUNCTION private.reply_to_story(p_story_id uuid,p_client_request_id uuid,p_body text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); owner uuid; conversation jsonb; message jsonb; mid uuid; linked uuid;
BEGIN
  IF p_client_request_id IS NULL OR p_body IS NULL OR char_length(btrim(p_body)) NOT BETWEEN 1 AND 2000
     OR p_body ~ '[[:cntrl:]]' THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_reply'; END IF;
  SELECT author_id INTO owner FROM public.stories WHERE id=p_story_id AND state='active'
    AND expires_at>statement_timestamp() FOR SHARE;
  IF NOT FOUND OR owner=actor OR NOT private.can_read_story(p_story_id) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
  conversation:=private.get_or_create_private(owner);
  message:=private.send_message((conversation->>'id')::uuid,p_client_request_id,'text',btrim(p_body),'{}'::uuid[],NULL);
  mid:=(message->>'id')::uuid;
  SELECT reply_story_id INTO linked FROM public.messages WHERE id=mid AND sender_id=actor FOR UPDATE;
  IF linked IS NOT NULL AND linked<>p_story_id THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  UPDATE public.messages SET reply_story_id=p_story_id WHERE id=mid AND reply_story_id IS NULL;
  RETURN private.c_message(mid);
END $$;

CREATE FUNCTION private.set_avatar_preset(p_preset text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.lock_profile();
BEGIN
  IF p_preset IS NULL OR p_preset NOT IN ('neutral','male','female') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_avatar_preset'; END IF;
  UPDATE public.profiles SET public_avatar_preset=p_preset,version=version+1,
    updated_at=statement_timestamp() WHERE id=actor AND public_avatar_preset IS DISTINCT FROM p_preset;
  RETURN private.self_profile();
END $$;

CREATE OR REPLACE FUNCTION private.schedule_media_cleanup() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets; n integer:=0;
BEGIN
  PERFORM pg_advisory_xact_lock(6061101);
  FOR a IN SELECT * FROM public.media_assets m WHERE purpose IN ('avatar','cover','post','story') AND
    ((state='pending_delete' AND coalesce(delete_after,now())<=now()) OR
     (state IN ('pending','ready','quarantined') AND created_at<now()-interval '24 hours'))
    AND NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
      WHERE pm.asset_id=m.id AND p.state='published')
    AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE m.id IN (p.avatar_asset_id,p.cover_asset_id))
    AND NOT EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=m.id AND s.state='active' AND s.expires_at>now())
    ORDER BY id LIMIT 100 FOR UPDATE LOOP
    IF NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
        WHERE pm.asset_id=a.id AND p.state='published')
      AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE a.id IN (p.avatar_asset_id,p.cover_asset_id))
      AND NOT EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND s.state='active' AND s.expires_at>now())
    THEN PERFORM private.retire_asset(a.id); n:=n+1; END IF;
  END LOOP;
  RETURN n;
END $$;

CREATE FUNCTION public.get_stories(p_author uuid DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_stories(p_author,p_limit) $$;
CREATE FUNCTION public.create_story(p_media_asset_id uuid,p_caption text,p_audience text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_story(p_media_asset_id,p_caption,p_audience,p_client_request_id) $$;
CREATE FUNCTION public.mark_story_viewed(p_story_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.mark_story_viewed(p_story_id) $$;
CREATE FUNCTION public.get_story_viewers(p_story_id uuid,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_story_viewers(p_story_id,p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.delete_story(p_story_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.delete_story(p_story_id) $$;
CREATE FUNCTION public.reply_to_story(p_story_id uuid,p_client_request_id uuid,p_body text) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.reply_to_story(p_story_id,p_client_request_id,p_body) $$;
CREATE FUNCTION public.set_avatar_preset(p_preset text) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_avatar_preset(p_preset) $$;

REVOKE ALL ON FUNCTION private.prepare_direct_story_upload(text,uuid),private.finalize_story_upload(uuid),
  private.story_json(uuid),private.get_stories(uuid,integer),private.create_story(uuid,text,text,uuid),
  private.mark_story_viewed(uuid),private.get_story_viewers(uuid,timestamptz,uuid,integer),
  private.delete_story(uuid),private.reply_to_story(uuid,uuid,text),private.set_avatar_preset(text)
  FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.prepare_direct_story_upload(text,uuid),public.get_stories(uuid,integer),
  public.create_story(uuid,text,text,uuid),public.mark_story_viewed(uuid),
  public.get_story_viewers(uuid,timestamptz,uuid,integer),public.delete_story(uuid),
  public.reply_to_story(uuid,uuid,text),public.set_avatar_preset(text) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.prepare_direct_story_upload(text,uuid),private.prepare_direct_story_upload(text,uuid),
  public.get_stories(uuid,integer),private.get_stories(uuid,integer),public.create_story(uuid,text,text,uuid),
  private.create_story(uuid,text,text,uuid),public.mark_story_viewed(uuid),private.mark_story_viewed(uuid),
  public.get_story_viewers(uuid,timestamptz,uuid,integer),private.get_story_viewers(uuid,timestamptz,uuid,integer),
  public.delete_story(uuid),private.delete_story(uuid),public.reply_to_story(uuid,uuid,text),
  private.reply_to_story(uuid,uuid,text),public.set_avatar_preset(text),private.set_avatar_preset(text)
  TO authenticated;
REVOKE ALL ON FUNCTION private.finalize_story_upload(uuid) FROM PUBLIC,anon,authenticated,service_role;
NOTIFY pgrst,'reload schema';
