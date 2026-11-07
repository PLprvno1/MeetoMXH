-- Remove application-imposed per-account upload quotas. Forward only; owner deploys.
-- No account asset-count, pending-count or aggregate/reserved-byte budget remains.
-- Existing per-file validators (at most 50 MiB), bucket limits, Auth/RLS,
-- idempotency, intent expiry and media cleanup remain in force. No data deletion.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.prepare_upload(text,text,uuid)') IS NULL
 OR to_regprocedure('private.prepare_message_upload(uuid,text,uuid)') IS NULL
 OR to_regprocedure('private.prepare_direct_message_upload(uuid,text,uuid)') IS NULL
 OR to_regprocedure('private.prepare_direct_story_upload(text,uuid)') IS NULL
 OR to_regprocedure('private.prepare_group_cover_upload(uuid,text,uuid)') IS NULL THEN
  RAISE EXCEPTION 'Upload lifecycle prerequisites missing; inventory migrations before applying';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.prepare_upload(p_purpose text,p_mime text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.media_assets; aid uuid:=gen_random_uuid(); bucket text; ext text;
BEGIN
 IF p_purpose IS NULL OR p_purpose NOT IN ('avatar','cover','post') OR p_mime IS NULL OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4')
 OR (p_purpose<>'post' AND p_mime='video/mp4') OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_upload'; END IF;
 SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN
  IF a.purpose<>p_purpose OR a.mime_type<>p_mime THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;

 bucket:=CASE WHEN p_purpose='post' THEN 'social-media' ELSE 'profile-media' END;
 ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg' WHEN 'image/webp' THEN 'webp' ELSE 'mp4' END;
 INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type)
 VALUES(aid,actor,p_client_request_id,bucket,actor::text||'/'||aid::text||'/original.'||ext,p_purpose,p_mime) RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

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


  ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' WHEN 'audio/mp4' THEN 'm4a' ELSE 'mp4' END;
  INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata)
    VALUES(aid,actor,p_client_request_id,'chat-media',actor::text||'/'||aid::text||'/original.'||ext,
      'message',p_mime,jsonb_build_object('conversation_id',p_conversation_id,'upload_flow','direct_storage_v1'))
    RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

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

  ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' ELSE 'mp4' END;
  INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata)
    VALUES(aid,actor,p_client_request_id,'social-media',actor::text||'/'||aid::text||'/original.'||ext,
      'story',p_mime,jsonb_build_object('upload_flow','direct_storage_v1')) RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE OR REPLACE FUNCTION private.prepare_group_cover_upload(p_group_id uuid,p_mime text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups; a public.media_assets; aid uuid:=gen_random_uuid(); ext text;
BEGIN
 IF p_mime IS NULL OR p_mime NOT IN ('image/png','image/jpeg','image/webp') OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_cover'; END IF;
 SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='active' AND role IN ('owner','admin')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_admin_required'; END IF;
 SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN
  IF a.purpose<>'group' OR a.metadata->>'group_id'<>p_group_id::text OR a.mime_type<>p_mime THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
 END IF;

 ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg' ELSE 'webp' END;
 INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata)
 VALUES(aid,actor,p_client_request_id,'group-media',actor::text||'/'||aid::text||'/original.'||ext,'group',p_mime,
 jsonb_build_object('upload_flow','group_cover_v1','group_id',p_group_id)) RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

-- CREATE OR REPLACE preserves existing function privileges; no new DML grants.
NOTIFY pgrst,'reload schema';
COMMIT;
