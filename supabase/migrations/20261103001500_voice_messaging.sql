-- Task 26 / migration 033: private, verified AAC-LC voice messages.
-- Requires migrations 001-032 to be present; does not deploy or configure the Edge Function.
BEGIN;

DO $$ BEGIN
  IF to_regclass('public.messages') IS NULL OR to_regclass('public.message_media') IS NULL
     OR to_regclass('public.media_assets') IS NULL OR to_regclass('public.conversations') IS NULL
     OR to_regclass('public.conversation_members') IS NULL OR to_regclass('public.event_outbox') IS NULL
     OR to_regclass('storage.objects') IS NULL OR to_regclass('storage.buckets') IS NULL
     OR to_regclass('private.worker_invocations') IS NULL
     OR to_regprocedure('private.meeto_worker_enqueue_cleanup()') IS NULL
     OR to_regprocedure('private.meeto_consume_worker_token(text)') IS NULL
     OR to_regprocedure('private.meeto_finish_worker_run(uuid,jsonb)') IS NULL
     OR to_regprocedure('private.c_lock(uuid,boolean)') IS NULL
     OR to_regprocedure('private.c_event(uuid,text,uuid[],uuid)') IS NULL
     OR to_regprocedure('private.c_message(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.prepare_message_upload(uuid,text,uuid)') IS NULL
     OR to_regprocedure('private.prepare_direct_message_upload(uuid,text,uuid)') IS NULL
     OR to_regprocedure('private.finalize_direct_upload(uuid)') IS NULL
     OR to_regprocedure('private.send_message(uuid,uuid,text,text,uuid[],uuid)') IS NULL
     OR to_regprocedure('private.retire_asset(uuid)') IS NULL
     OR to_regprocedure('public.meeto_worker_consume_token(text)') IS NULL
     OR to_regprocedure('public.meeto_worker_finish_run(uuid,jsonb)') IS NULL THEN
    RAISE EXCEPTION 'Voice Messaging prerequisites missing; apply and verify migrations 001-032 first';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.messages'::regclass AND attname='forwarded' AND NOT attisdropped)
     OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.messages'::regclass AND attname='shared_post_id' AND NOT attisdropped)
     OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.messages'::regclass AND attname='is_shared_post' AND NOT attisdropped)
     OR NOT EXISTS (SELECT 1 FROM pg_proc WHERE oid='private.c_message(uuid)'::regprocedure AND prorettype='jsonb'::regtype)
     OR NOT EXISTS (SELECT 1 FROM storage.buckets WHERE id='chat-media' AND NOT public) THEN
    RAISE EXCEPTION 'Voice Messaging requires migration 032 projection and the existing private chat-media bucket';
  END IF;
END $$;

ALTER TABLE public.media_assets
  ADD COLUMN audio_verify_attempts smallint NOT NULL DEFAULT 0 CHECK (audio_verify_attempts BETWEEN 0 AND 5),
  ADD COLUMN audio_verify_claimed_at timestamptz;
-- A prior table-wide grant would expose newly added verifier bookkeeping columns.
REVOKE SELECT ON TABLE public.media_assets FROM PUBLIC,anon,authenticated;
GRANT SELECT(id,owner_id,bucket_id,object_path,purpose,state,mime_type,size_bytes,created_at,ready_at)
  ON public.media_assets TO authenticated;
CREATE INDEX media_assets_audio_verify_idx ON public.media_assets(created_at,id)
  WHERE purpose='message' AND mime_type='audio/mp4' AND state='pending';

UPDATE storage.buckets SET allowed_mime_types=CASE
  WHEN 'audio/mp4'=ANY(allowed_mime_types) THEN allowed_mime_types
  ELSE array_append(allowed_mime_types,'audio/mp4') END
WHERE id='chat-media' AND public=false;

CREATE OR REPLACE FUNCTION private.prepare_message_upload(p_conversation_id uuid,p_mime text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); a public.media_assets; aid uuid:=gen_random_uuid(); ext text;
BEGIN
  IF p_mime IS NULL OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4','audio/mp4')
     OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_chat_media'; END IF;
  SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF a.purpose<>'message' OR a.mime_type<>p_mime
       OR a.metadata->>'conversation_id' IS DISTINCT FROM p_conversation_id::text THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
    IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN
      RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
    RETURN to_jsonb(a)-'metadata'-'client_request_id';
  END IF;
  IF (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state='pending')>=10
     OR (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted')>=100
     OR coalesce((SELECT sum(CASE WHEN state IN ('pending','pending_delete','quarantined')
       THEN CASE WHEN mime_type='audio/mp4' THEN 2097152 WHEN bucket_id='profile-media' THEN 10485760 ELSE 52428800 END
       ELSE coalesce(size_bytes,0) END) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted'),0)
       +CASE WHEN p_mime='audio/mp4' THEN 2097152 ELSE 52428800 END>524288000 THEN
    RAISE SQLSTATE 'PT429' USING MESSAGE='media_quota'; END IF;
  ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' WHEN 'audio/mp4' THEN 'm4a' ELSE 'mp4' END;
  INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata)
    VALUES(aid,actor,p_client_request_id,'chat-media',actor::text||'/'||aid::text||'/original.'||ext,
      'message',p_mime,jsonb_build_object('conversation_id',p_conversation_id)) RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE OR REPLACE FUNCTION private.prepare_direct_message_upload(p_conversation_id uuid,p_mime text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid; a public.media_assets; aid uuid:=gen_random_uuid(); ext text;
BEGIN
  IF p_conversation_id IS NULL OR p_mime IS NULL
     OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4','audio/mp4')
     OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_chat_media'; END IF;
  actor:=private.c_lock(p_conversation_id);
  -- Serialize retries by owner and request id. New direct intents are marked
  -- only by the INSERT below; a legacy intent is never promoted retroactively.
  PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':direct-message-upload:'||p_client_request_id::text,0));
  SELECT * INTO a FROM public.media_assets
    WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' OR a.purpose<>'message'
       OR a.mime_type<>p_mime OR a.metadata->>'conversation_id' IS DISTINCT FROM p_conversation_id::text THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent'; END IF;
    IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN
      RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
    RETURN to_jsonb(a)-'metadata'-'client_request_id';
  END IF;

  IF (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state='pending')>=10
     OR (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted')>=100
     OR coalesce((SELECT sum(CASE WHEN state IN ('pending','pending_delete','quarantined')
       THEN CASE WHEN mime_type='audio/mp4' THEN 2097152 WHEN bucket_id='profile-media' THEN 10485760 ELSE 52428800 END
       ELSE coalesce(size_bytes,0) END) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted'),0)
       +CASE WHEN p_mime='audio/mp4' THEN 2097152 ELSE 52428800 END>524288000 THEN
    RAISE SQLSTATE 'PT429' USING MESSAGE='media_quota'; END IF;
  ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' WHEN 'audio/mp4' THEN 'm4a' ELSE 'mp4' END;
  INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata)
    VALUES(aid,actor,p_client_request_id,'chat-media',actor::text||'/'||aid::text||'/original.'||ext,
      'message',p_mime,jsonb_build_object('conversation_id',p_conversation_id,'upload_flow','direct_storage_v1'))
    RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

-- Preserve the direct-upload contract. Audio is not made ready from Storage
-- metadata: it must carry the verifier's AAC-LC result and remain within 2 MiB.
CREATE OR REPLACE FUNCTION private.finalize_direct_upload(p_asset_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); a public.media_assets; obj_id uuid; object_owner text;
  object_size bigint; object_mime text; bucket_limit bigint; bucket_mimes text[]; bucket_is_public boolean;
  expected_bucket text; max_bytes bigint; expected_ext text;
BEGIN
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
  IF a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent'; END IF;
  IF a.purpose='message' THEN
    PERFORM private.c_lock((a.metadata->>'conversation_id')::uuid);
    SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
  ELSE
    SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
  END IF;
  IF a.state NOT IN ('pending','ready') OR (a.state='pending' AND a.created_at<=statement_timestamp()-interval '15 minutes') THEN
    RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  expected_bucket:=CASE WHEN a.purpose IN ('avatar','cover') THEN 'profile-media'
    WHEN a.purpose='post' THEN 'social-media' WHEN a.purpose='message' THEN 'chat-media' END;
  expected_ext:=CASE a.mime_type WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' WHEN 'video/mp4' THEN 'mp4' WHEN 'audio/mp4' THEN 'm4a' END;
  max_bytes:=CASE WHEN expected_bucket='profile-media' THEN 10485760
    WHEN a.mime_type='audio/mp4' THEN 2097152 ELSE 52428800 END;
  IF expected_bucket IS NULL OR a.bucket_id<>expected_bucket OR expected_ext IS NULL
     OR a.object_path<>actor::text||'/'||a.id::text||'/original.'||expected_ext
     OR (a.purpose IN ('avatar','cover') AND a.mime_type='video/mp4')
     OR (a.mime_type='audio/mp4' AND a.purpose<>'message') THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid'; END IF;
  SELECT o.id,o.owner_id,
    CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$' THEN (o.metadata->>'size')::bigint ELSE NULL END,
    o.metadata->>'mimetype',b.file_size_limit,b.allowed_mime_types,b.public
  INTO obj_id,object_owner,object_size,object_mime,bucket_limit,bucket_mimes,bucket_is_public
  FROM storage.objects o JOIN storage.buckets b ON b.id=o.bucket_id
  WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path;
  IF NOT FOUND OR obj_id IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='upload_object_missing'; END IF;
  IF object_owner IS DISTINCT FROM actor::text OR object_size IS NULL OR object_size<1
     OR object_size>max_bytes OR (bucket_limit IS NOT NULL AND object_size>bucket_limit)
     OR object_mime IS DISTINCT FROM a.mime_type OR bucket_mimes IS NULL OR NOT (a.mime_type=ANY(bucket_mimes))
     OR bucket_is_public IS DISTINCT FROM false THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid'; END IF;
  IF a.mime_type='audio/mp4' AND (a.metadata->>'verified' IS DISTINCT FROM 'true'
      OR a.metadata->>'validation' IS DISTINCT FROM 'audio_mp4_aac_lc_structural'
      OR CASE WHEN coalesce(a.metadata->>'duration_ms','') ~ '^[0-9]{1,6}$'
        THEN (a.metadata->>'duration_ms')::integer NOT BETWEEN 1 AND 120000 ELSE true END
      OR a.metadata->>'object_id' IS DISTINCT FROM obj_id::text
      OR coalesce(a.metadata->>'sha256','') !~ '^[0-9a-f]{64}$') THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='verification_pending'; END IF;
  IF a.state='ready' THEN RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
  IF a.mime_type='audio/mp4' THEN
    UPDATE public.media_assets SET size_bytes=object_size,
      metadata=metadata||jsonb_build_object('storage_confirmed_at',statement_timestamp()),
      state='ready',ready_at=statement_timestamp() WHERE id=a.id RETURNING * INTO a;
  ELSE
    UPDATE public.media_assets SET size_bytes=object_size,
      metadata=(metadata-'verified'-'sha256'-'width'-'height'-'duration')
        ||jsonb_build_object('object_id',obj_id,'validation','storage_object_metadata','storage_confirmed_at',statement_timestamp()),
      state='ready',ready_at=statement_timestamp() WHERE id=a.id RETURNING * INTO a;
  END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

ALTER TABLE public.messages DROP CONSTRAINT messages_kind_check;
ALTER TABLE public.messages ADD CONSTRAINT messages_kind_check
  CHECK (kind IN ('text','media','system','voice'));

CREATE OR REPLACE FUNCTION private.send_message(p_conversation_id uuid,p_client_request_id uuid,p_kind text,
  p_body text,p_assets uuid[],p_reply_to uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); m public.messages; a public.media_assets;
  aid uuid; h text; seq bigint; pos integer:=0;
BEGIN
  IF p_client_request_id IS NULL OR p_kind IS NULL OR p_kind NOT IN ('text','media','voice')
     OR p_assets IS NULL OR p_body IS NULL OR length(p_body)>10000
     OR (p_kind='text' AND (length(btrim(p_body))=0 OR cardinality(p_assets)<>0))
     OR (p_kind='media' AND cardinality(p_assets) NOT BETWEEN 1 AND 10)
     OR (p_kind='voice' AND (p_body<>'' OR cardinality(p_assets)<>1))
     OR array_position(p_assets,NULL) IS NOT NULL
     OR EXISTS(SELECT 1 FROM unnest(p_assets) x GROUP BY x HAVING count(*)>1) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message'; END IF;
  h:=encode(sha256(convert_to(jsonb_build_array(p_kind,p_body,p_assets,p_reply_to)::text,'UTF8')),'hex');
  SELECT * INTO m FROM public.messages WHERE conversation_id=p_conversation_id
    AND sender_id=actor AND client_request_id=p_client_request_id;
  IF FOUND THEN
    IF m.request_hash<>h THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
    IF NOT private.can_read_message(m.id) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='message_no_longer_visible'; END IF;
    RETURN private.c_message(m.id);
  END IF;
  IF p_reply_to IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.messages WHERE id=p_reply_to
      AND conversation_id=p_conversation_id AND state='sent' AND private.can_read_message(id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='reply_unavailable'; END IF;
  FOR a IN SELECT * FROM public.media_assets WHERE id=ANY(p_assets) ORDER BY id FOR UPDATE LOOP
    IF a.owner_id IS DISTINCT FROM actor OR a.purpose<>'message' OR a.state<>'ready'
       OR a.metadata->>'conversation_id' IS DISTINCT FROM p_conversation_id::text
       OR EXISTS(SELECT 1 FROM public.message_media WHERE asset_id=a.id) THEN
      RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message_asset'; END IF;
    IF p_kind='voice' AND (a.mime_type<>'audio/mp4' OR a.size_bytes NOT BETWEEN 1 AND 2097152
       OR a.metadata->>'verified' IS DISTINCT FROM 'true'
       OR a.metadata->>'validation' IS DISTINCT FROM 'audio_mp4_aac_lc_structural'
       OR CASE WHEN coalesce(a.metadata->>'duration_ms','') ~ '^[0-9]{1,6}$'
         THEN (a.metadata->>'duration_ms')::integer NOT BETWEEN 1 AND 120000 ELSE true END) THEN
      RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_voice_asset'; END IF;
    IF p_kind='media' AND a.mime_type='audio/mp4' THEN
      RAISE SQLSTATE 'PT422' USING MESSAGE='voice_message_required'; END IF;
    pos:=pos+1;
  END LOOP;
  IF (SELECT count(*) FROM public.media_assets WHERE id=ANY(p_assets))<>cardinality(p_assets) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='missing_message_asset'; END IF;
  UPDATE public.conversations SET next_seq=next_seq+1,updated_at=statement_timestamp(),version=version+1
    WHERE id=p_conversation_id RETURNING next_seq INTO seq;
  INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,kind,body,reply_to_message_id)
    VALUES(p_conversation_id,seq,actor,p_client_request_id,h,p_kind,p_body,p_reply_to) RETURNING * INTO m;
  pos:=0;
  FOREACH aid IN ARRAY p_assets LOOP
    INSERT INTO public.message_media(message_id,asset_id,position) VALUES(m.id,aid,pos); pos:=pos+1;
  END LOOP;
  UPDATE public.conversations SET last_message_id=m.id WHERE id=p_conversation_id;
  PERFORM private.c_event(p_conversation_id,'message',NULL,m.id);
  RETURN private.c_message(m.id);
END $$;

-- The 032 JSON contract is preserved. The only new projection data is a duration
-- for verified audio assets; raw paths, URLs, and verifier internals stay private.
CREATE OR REPLACE FUNCTION private.c_message(mid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',m.id,'conversation_id',m.conversation_id,'seq',m.seq,
   'sender_id',m.sender_id,'kind',m.kind,'body',CASE WHEN m.state='sent' THEN m.body END,
   'state',m.state,'version',m.version,'created_at',m.created_at,'edited_at',m.edited_at,'recalled_at',m.recalled_at,
   'reply_reference_present',m.reply_to_message_id IS NOT NULL,
   'reply_to_message_id',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN m.reply_to_message_id END,
   'reply',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN
     (SELECT jsonb_build_object('id',r.id,'seq',r.seq,'sender_id',r.sender_id,'state',r.state,
       'body',CASE WHEN r.state='sent' THEN r.body END,'kind',r.kind,
       'media',coalesce((SELECT jsonb_agg(jsonb_build_object('mime_type',ra.mime_type,
           'duration_ms',CASE WHEN ra.mime_type='audio/mp4' AND ra.metadata->>'validation'='audio_mp4_aac_lc_structural'
             AND coalesce(ra.metadata->>'duration_ms','') ~ '^[0-9]{1,6}$' THEN (ra.metadata->>'duration_ms')::integer END)
         ORDER BY rm.position) FROM public.message_media rm JOIN public.media_assets ra ON ra.id=rm.asset_id
         WHERE rm.message_id=r.id AND r.state='sent' AND private.can_read_media(ra.id)),'[]'::jsonb))
       FROM public.messages r WHERE r.id=m.reply_to_message_id) END,
   'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',mm.asset_id,'position',mm.position,
       'mime_type',a.mime_type,'duration_ms',CASE WHEN a.mime_type='audio/mp4'
         AND a.metadata->>'validation'='audio_mp4_aac_lc_structural'
         AND coalesce(a.metadata->>'duration_ms','') ~ '^[0-9]{1,6}$' THEN (a.metadata->>'duration_ms')::integer END)
       ORDER BY mm.position) FROM public.message_media mm JOIN public.media_assets a ON a.id=mm.asset_id
     WHERE mm.message_id=m.id AND m.state='sent' AND private.can_read_media(a.id)),'[]'::jsonb),
   'shared_post_present',m.state='sent' AND m.is_shared_post,
   'shared_post_available',m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL
     AND private.can_read_post(m.shared_post_id),
   'shared_post',CASE WHEN m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL
       AND private.can_read_post(m.shared_post_id) THEN private.post_json(m.shared_post_id) END,
   'forwarded',m.state='sent' AND m.forwarded)
 FROM public.messages m WHERE m.id=mid AND private.can_read_message(m.id);
$$;

CREATE OR REPLACE FUNCTION private.schedule_media_cleanup() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets; n integer:=0;
BEGIN
  PERFORM pg_advisory_xact_lock(6061101);
  FOR a IN SELECT * FROM public.media_assets m WHERE m.purpose IN ('avatar','cover','post','message','story')
    AND ((m.state='pending_delete' AND coalesce(m.delete_after,now())<=now())
      OR (m.state IN ('pending','ready','quarantined') AND m.created_at<now()-interval '24 hours'))
    AND NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
      WHERE pm.asset_id=m.id AND p.state='published')
    AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE m.id IN (p.avatar_asset_id,p.cover_asset_id))
    AND NOT EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=m.id AND s.state='active' AND s.expires_at>now())
    AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages msg ON msg.id=mm.message_id
      WHERE mm.asset_id=m.id AND msg.state='sent')
    ORDER BY m.id LIMIT 100 FOR UPDATE LOOP
    IF NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
        WHERE pm.asset_id=a.id AND p.state='published')
      AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE a.id IN (p.avatar_asset_id,p.cover_asset_id))
      AND NOT EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND s.state='active' AND s.expires_at>now())
      AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages msg ON msg.id=mm.message_id
        WHERE mm.asset_id=a.id AND msg.state='sent') THEN
      PERFORM private.retire_asset(a.id); n:=n+1;
    END IF;
  END LOOP;
  RETURN n;
END $$;

-- Worker-only verifier queue; run inside the existing one-shot cleanup Edge Function.
CREATE FUNCTION private.meeto_claim_voice_verification(p_batch integer DEFAULT 1) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
  IF p_batch IS NULL OR p_batch<>1 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_audio_batch'; END IF;
  RETURN coalesce((WITH candidates AS (
    SELECT a.id FROM public.media_assets a JOIN storage.objects o
      ON o.bucket_id=a.bucket_id AND o.name=a.object_path AND o.owner_id=a.owner_id::text
    WHERE a.purpose='message' AND a.mime_type='audio/mp4' AND a.bucket_id='chat-media'
      AND a.state='pending' AND a.created_at>statement_timestamp()-interval '15 minutes'
      AND a.audio_verify_attempts<5 AND a.metadata->>'verified' IS DISTINCT FROM 'true'
      AND (a.audio_verify_claimed_at IS NULL OR a.audio_verify_claimed_at<statement_timestamp()-interval '2 minutes')
    ORDER BY a.created_at,a.id LIMIT p_batch FOR UPDATE OF a SKIP LOCKED
  ), claimed AS (
    UPDATE public.media_assets a SET audio_verify_attempts=a.audio_verify_attempts+1,
      audio_verify_claimed_at=statement_timestamp() FROM candidates c WHERE a.id=c.id
    RETURNING a.id,a.owner_id,a.bucket_id,a.object_path,a.mime_type,a.audio_verify_attempts,a.size_bytes
  ) SELECT jsonb_agg(jsonb_build_object('asset_id',c.id,'owner_id',c.owner_id,'bucket_id',c.bucket_id,
      'object_path',c.object_path,'mime_type',c.mime_type,'attempt',c.audio_verify_attempts,
      'expected_size',CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$' THEN (o.metadata->>'size')::bigint END,
      'object_id',o.id) ORDER BY c.id)
    FROM claimed c JOIN storage.objects o ON o.bucket_id=c.bucket_id AND o.name=c.object_path
      AND o.owner_id=c.owner_id::text),'[]'::jsonb);
END $$;

CREATE FUNCTION private.meeto_finish_voice_verification(p_asset_id uuid,p_attempt smallint,p_outcome text,
  p_object_id uuid DEFAULT NULL,p_bytes bigint DEFAULT NULL,p_sha256 text DEFAULT NULL,p_duration_ms integer DEFAULT NULL)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets;
BEGIN
  IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
  IF p_asset_id IS NULL OR p_attempt IS NULL OR p_outcome IS NULL
     OR p_outcome NOT IN ('verified','invalid','retry') THEN RETURN false; END IF;
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id FOR UPDATE;
  IF NOT FOUND OR a.purpose<>'message' OR a.mime_type<>'audio/mp4' OR a.state<>'pending'
     OR a.audio_verify_attempts<>p_attempt OR a.audio_verify_claimed_at IS NULL THEN RETURN false; END IF;
  IF p_outcome='verified' THEN
    IF p_object_id IS NULL OR p_bytes IS NULL OR p_bytes NOT BETWEEN 1 AND 2097152
       OR p_sha256 IS NULL OR p_sha256 !~ '^[0-9a-f]{64}$'
       OR p_duration_ms IS NULL OR p_duration_ms NOT BETWEEN 1 AND 120000
       OR NOT EXISTS(SELECT 1 FROM storage.objects o JOIN storage.buckets b ON b.id=o.bucket_id
         WHERE o.id=p_object_id AND o.bucket_id='chat-media' AND o.name=a.object_path
           AND o.owner_id=a.owner_id::text AND o.metadata->>'mimetype'='audio/mp4'
           AND coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$'
           AND (o.metadata->>'size')::bigint=p_bytes AND b.public=false
           AND 'audio/mp4'=ANY(b.allowed_mime_types)) THEN
      RETURN false;
    END IF;
    UPDATE public.media_assets SET size_bytes=p_bytes,
      metadata=metadata||jsonb_build_object('verified',true,'validation','audio_mp4_aac_lc_structural',
        'object_id',p_object_id,'sha256',p_sha256,'duration_ms',p_duration_ms,
        'duration',p_duration_ms::numeric/1000,'audio_codec','aac-lc'),
      audio_verify_claimed_at=NULL WHERE id=a.id;
    RETURN true;
  ELSIF p_outcome='invalid' OR a.audio_verify_attempts>=5
     OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN
    UPDATE public.media_assets SET audio_verify_claimed_at=NULL WHERE id=a.id;
    PERFORM private.retire_asset(a.id);
    RETURN true;
  ELSE
    UPDATE public.media_assets SET audio_verify_claimed_at=NULL WHERE id=a.id;
    RETURN true;
  END IF;
END $$;

CREATE FUNCTION public.meeto_worker_claim_voice_verification(p_batch integer DEFAULT 1) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
  RETURN private.meeto_claim_voice_verification(p_batch);
END $$;
CREATE FUNCTION public.meeto_worker_finish_voice_verification(p_asset_id uuid,p_attempt smallint,p_outcome text,
  p_object_id uuid DEFAULT NULL,p_bytes bigint DEFAULT NULL,p_sha256 text DEFAULT NULL,p_duration_ms integer DEFAULT NULL)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
  RETURN private.meeto_finish_voice_verification(p_asset_id,p_attempt,p_outcome,p_object_id,p_bytes,p_sha256,p_duration_ms);
END $$;

REVOKE ALL ON FUNCTION private.meeto_claim_voice_verification(integer),
  private.meeto_finish_voice_verification(uuid,smallint,text,uuid,bigint,text,integer)
  FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.meeto_worker_claim_voice_verification(integer),
  public.meeto_worker_finish_voice_verification(uuid,smallint,text,uuid,bigint,text,integer)
  FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.meeto_worker_claim_voice_verification(integer),
  public.meeto_worker_finish_voice_verification(uuid,smallint,text,uuid,bigint,text,integer) TO service_role;
GRANT EXECUTE ON FUNCTION private.prepare_message_upload(uuid,text,uuid),
  private.prepare_direct_message_upload(uuid,text,uuid),private.finalize_direct_upload(uuid),
  private.send_message(uuid,uuid,text,text,uuid[],uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.prepare_direct_message_upload(uuid,text,uuid),
  public.send_message(uuid,uuid,text,text,uuid[],uuid) TO authenticated;

-- The singleton invocation row must not have its nonce replaced while an Edge
-- request is still running. Seven minutes covers Free (150s) and paid (400s)
-- hosted Edge wall-clock limits, with a buffer before another invocation starts.
ALTER TABLE private.worker_invocations ADD COLUMN execution_until timestamptz;
UPDATE private.worker_invocations SET execution_until=statement_timestamp()+interval '7 minutes'
  WHERE singleton AND consumed_at IS NOT NULL AND completed_at IS NULL;

CREATE OR REPLACE FUNCTION private.meeto_consume_worker_token(p_token text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE run uuid;
BEGIN
  IF p_token IS NULL OR p_token !~ '^[0-9a-f]{64}$' THEN RETURN NULL; END IF;
  UPDATE private.worker_invocations SET consumed_at=statement_timestamp(),
    execution_until=statement_timestamp()+interval '7 minutes'
  WHERE singleton AND consumed_at IS NULL AND expires_at>statement_timestamp()
    AND token_hash=extensions.digest(p_token,'sha256')
  RETURNING run_id INTO run;
  RETURN run;
END $$;

CREATE OR REPLACE FUNCTION private.meeto_finish_worker_run(p_run_id uuid,p_summary jsonb) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF p_summary IS NULL OR jsonb_typeof(p_summary)<>'object' OR pg_column_size(p_summary)>1024 THEN RETURN false; END IF;
  UPDATE private.worker_invocations SET completed_at=statement_timestamp(),execution_until=NULL,summary=p_summary
  WHERE singleton AND run_id=p_run_id AND consumed_at IS NOT NULL;
  RETURN FOUND;
END $$;

CREATE OR REPLACE FUNCTION private.meeto_worker_enqueue_cleanup() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE token text; run uuid; request_id bigint; current_invocation private.worker_invocations%ROWTYPE;
BEGIN
  PERFORM pg_advisory_xact_lock(6061102);
  SELECT * INTO current_invocation FROM private.worker_invocations WHERE singleton FOR UPDATE;
  IF FOUND AND ((current_invocation.consumed_at IS NULL
        AND current_invocation.expires_at>statement_timestamp())
      OR (current_invocation.consumed_at IS NOT NULL AND current_invocation.completed_at IS NULL
        AND current_invocation.execution_until>statement_timestamp())) THEN
    RETURN current_invocation.net_request_id;
  END IF;

  token:=encode(extensions.gen_random_bytes(32),'hex');
  run:=gen_random_uuid();
  INSERT INTO private.worker_invocations(singleton,run_id,token_hash,issued_at,expires_at,consumed_at,
      completed_at,net_request_id,summary,execution_until)
  VALUES(true,run,extensions.digest(token,'sha256'),statement_timestamp(),
      statement_timestamp()+interval '2 minutes',NULL,NULL,NULL,'{}'::jsonb,NULL)
  ON CONFLICT(singleton) DO UPDATE SET run_id=EXCLUDED.run_id,token_hash=EXCLUDED.token_hash,
    issued_at=EXCLUDED.issued_at,expires_at=EXCLUDED.expires_at,consumed_at=NULL,
    completed_at=NULL,net_request_id=NULL,summary='{}'::jsonb,execution_until=NULL;

  SELECT net.http_post(
    url:='https://qjpohzpapiwdrxsionyr.supabase.co/functions/v1/meeto-background-cleanup',
    headers:=jsonb_build_object('Content-Type','application/json','apikey','sb_publishable_HDdsJa_VXk1Fj9mSCFe3RA_LIHLcVOq'),
    body:=jsonb_build_object('token',token),
    timeout_milliseconds:=120000
  ) INTO request_id;
  UPDATE private.worker_invocations SET net_request_id=request_id WHERE singleton AND run_id=run;
  RETURN request_id;
END $$;

-- The existing one-shot worker already verifies requests and cleans media. Run it
-- every minute so short-lived voice upload intents can be verified promptly.
DO $$ BEGIN
  IF (SELECT count(*) FROM cron.job WHERE jobname='meeto-media-cleanup-every-five-minutes'
      AND active AND schedule='*/5 * * * *'
      AND command='SELECT private.meeto_worker_enqueue_cleanup();')<>1 THEN
    RAISE EXCEPTION 'Existing media cleanup cron job is required for voice verification';
  END IF;
  PERFORM cron.unschedule('meeto-media-cleanup-every-five-minutes');
  PERFORM cron.schedule('meeto-media-cleanup-every-five-minutes','* * * * *',
    'SELECT private.meeto_worker_enqueue_cleanup();');
END $$;

NOTIFY pgrst,'reload schema';
COMMIT;
