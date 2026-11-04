-- Direct Storage upload contract. New intents are explicitly marked so old
-- pending verifier-based intents keep their original finalization behavior.
DO $$
BEGIN
  IF to_regclass('public.media_assets') IS NULL
     OR to_regclass('storage.objects') IS NULL
     OR to_regclass('storage.buckets') IS NULL
     OR to_regprocedure('private.prepare_upload(text,text,uuid)') IS NULL
     OR to_regprocedure('private.prepare_message_upload(uuid,text,uuid)') IS NULL
     OR to_regprocedure('private.phase_b_finalize_upload(uuid)') IS NULL
     OR to_regprocedure('private.c_lock(uuid,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Direct Storage migration prerequisites are missing; verify remote ledger/schema first';
  END IF;
END $$;

CREATE FUNCTION private.prepare_direct_upload(p_purpose text,p_mime text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); result jsonb; a public.media_assets; started_at timestamptz:=transaction_timestamp();
BEGIN
  IF p_purpose NOT IN ('avatar','cover','post') OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4')
     OR (p_purpose<>'post' AND p_mime='video/mp4') OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_upload';
  END IF;
  SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND AND a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent';
  END IF;
  result:=private.prepare_upload(p_purpose,p_mime,p_client_request_id);
  SELECT * INTO a FROM public.media_assets WHERE id=(result->>'id')::uuid AND owner_id=actor FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
  IF a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
    IF a.created_at<>started_at OR a.state<>'pending' THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent';
    END IF;
    UPDATE public.media_assets SET metadata=metadata||jsonb_build_object('upload_flow','direct_storage_v1')
      WHERE id=a.id RETURNING * INTO a;
  END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE FUNCTION private.prepare_direct_message_upload(p_conversation_id uuid,p_mime text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid; result jsonb; a public.media_assets; started_at timestamptz:=transaction_timestamp();
BEGIN
  IF p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4') OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_chat_media';
  END IF;
  SELECT * INTO a FROM public.media_assets
    WHERE owner_id=auth.uid() AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND AND (a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1'
      OR a.purpose<>'message' OR a.mime_type<>p_mime
      OR a.metadata->>'conversation_id' IS DISTINCT FROM p_conversation_id::text) THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent';
  END IF;
  result:=private.prepare_message_upload(p_conversation_id,p_mime,p_client_request_id);
  actor:=private.b_actor();
  SELECT * INTO a FROM public.media_assets WHERE id=(result->>'id')::uuid AND owner_id=actor FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
  IF a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
    IF a.created_at<>started_at OR a.state<>'pending'
       OR a.metadata->>'conversation_id' IS DISTINCT FROM p_conversation_id::text THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent';
    END IF;
    UPDATE public.media_assets SET metadata=metadata||jsonb_build_object('upload_flow','direct_storage_v1')
      WHERE id=a.id RETURNING * INTO a;
  END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE FUNCTION private.finalize_direct_upload(p_asset_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); a public.media_assets; obj_id uuid; object_owner text;
        object_size bigint; object_mime text; bucket_limit bigint; bucket_mimes text[]; bucket_is_public boolean;
        expected_bucket text; max_bytes bigint; expected_ext text;
BEGIN
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
  IF a.metadata->>'upload_flow' IS DISTINCT FROM 'direct_storage_v1' THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='legacy_upload_intent';
  END IF;
  IF a.purpose='message' THEN
    PERFORM private.c_lock((a.metadata->>'conversation_id')::uuid);
    SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
  ELSE
    SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
  END IF;
  IF a.state NOT IN ('pending','ready') OR (a.state='pending' AND a.created_at<=statement_timestamp()-interval '15 minutes') THEN
    RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed';
  END IF;
  expected_bucket:=CASE WHEN a.purpose IN ('avatar','cover') THEN 'profile-media'
                        WHEN a.purpose='post' THEN 'social-media'
                        WHEN a.purpose='message' THEN 'chat-media' END;
  expected_ext:=CASE a.mime_type WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
                                 WHEN 'image/webp' THEN 'webp' WHEN 'video/mp4' THEN 'mp4' END;
  max_bytes:=CASE WHEN expected_bucket='profile-media' THEN 10485760 ELSE 52428800 END;
  IF expected_bucket IS NULL OR a.bucket_id<>expected_bucket OR expected_ext IS NULL
     OR a.object_path<>actor::text||'/'||a.id::text||'/original.'||expected_ext
     OR (a.purpose IN ('avatar','cover') AND a.mime_type='video/mp4') THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid';
  END IF;
  SELECT o.id,o.owner_id,
         CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$'
              THEN (o.metadata->>'size')::bigint ELSE NULL END,
         o.metadata->>'mimetype',b.file_size_limit,b.allowed_mime_types,b.public
    INTO obj_id,object_owner,object_size,object_mime,bucket_limit,bucket_mimes,bucket_is_public
    FROM storage.objects o JOIN storage.buckets b ON b.id=o.bucket_id
    WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path;
  IF NOT FOUND OR obj_id IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='upload_object_missing'; END IF;
  IF object_owner IS DISTINCT FROM actor::text OR object_size IS NULL OR object_size<1
     OR object_size>max_bytes OR (bucket_limit IS NOT NULL AND object_size>bucket_limit)
     OR object_mime IS DISTINCT FROM a.mime_type OR bucket_mimes IS NULL OR NOT (a.mime_type=ANY(bucket_mimes))
     OR bucket_is_public IS DISTINCT FROM false THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid';
  END IF;
  IF a.state='ready' THEN RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
  UPDATE public.media_assets
     SET size_bytes=object_size,
         metadata=(metadata-'verified'-'sha256'-'width'-'height'-'duration')
                  ||jsonb_build_object('object_id',obj_id,'validation','storage_object_metadata',
                                       'storage_confirmed_at',statement_timestamp()),
         state='ready',ready_at=statement_timestamp()
   WHERE id=a.id RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE OR REPLACE FUNCTION private.finalize_upload(p_asset_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); a public.media_assets;
BEGIN
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor;
  IF NOT FOUND THEN PERFORM private.lock_profile(); RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
  IF a.metadata->>'upload_flow'='direct_storage_v1' THEN
    RETURN private.finalize_direct_upload(p_asset_id);
  END IF;
  IF a.purpose<>'message' THEN RETURN private.phase_b_finalize_upload(p_asset_id); END IF;
  PERFORM private.c_lock((a.metadata->>'conversation_id')::uuid);
  SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
  IF a.state='ready' THEN RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
  IF a.state<>'pending' OR a.created_at<=now()-interval '15 minutes' THEN
    RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed';
  END IF;
  IF a.metadata->>'verified' IS DISTINCT FROM 'true' OR NOT EXISTS(
    SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path
      AND o.id::text=a.metadata->>'object_id' AND o.owner_id=actor::text
      AND (o.metadata->>'size')::bigint=a.size_bytes) THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='verification_pending';
  END IF;
  UPDATE public.media_assets SET state='ready',ready_at=statement_timestamp()
    WHERE id=a.id RETURNING * INTO a;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE FUNCTION public.prepare_direct_upload(p_purpose text,p_mime text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.prepare_direct_upload(p_purpose,p_mime,p_client_request_id)
$$;
CREATE FUNCTION public.prepare_direct_message_upload(p_conversation_id uuid,p_mime text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.prepare_direct_message_upload(p_conversation_id,p_mime,p_client_request_id)
$$;
CREATE OR REPLACE FUNCTION public.finalize_upload(p_asset_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.finalize_upload(p_asset_id) $$;

REVOKE ALL ON FUNCTION private.prepare_direct_upload(text,text,uuid),
  private.prepare_direct_message_upload(uuid,text,uuid),private.finalize_direct_upload(uuid)
  FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.prepare_direct_upload(text,text,uuid),
  public.prepare_direct_message_upload(uuid,text,uuid) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.prepare_direct_upload(text,text,uuid),
  public.prepare_direct_message_upload(uuid,text,uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION private.prepare_direct_upload(text,text,uuid),
  private.prepare_direct_message_upload(uuid,text,uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION private.finalize_upload(uuid) TO authenticated;
NOTIFY pgrst,'reload schema';
