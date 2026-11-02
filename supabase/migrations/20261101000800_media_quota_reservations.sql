-- Retain worst-case byte reservations until Storage deletion has succeeded.
CREATE OR REPLACE FUNCTION private.prepare_upload(p_purpose text,p_mime text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.media_assets; aid uuid:=gen_random_uuid(); bucket text; ext text; cap bigint;
BEGIN
 IF p_purpose IS NULL OR p_purpose NOT IN ('avatar','cover','post') OR p_mime IS NULL OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4')
 OR (p_purpose<>'post' AND p_mime='video/mp4') OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_upload'; END IF;
 SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN
  IF a.purpose<>p_purpose OR a.mime_type<>p_mime THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
 -- Reserve bucket worst case, including oversized image uploads awaiting rejection.
 cap:=CASE WHEN p_purpose='post' THEN 52428800 ELSE 10485760 END;
 IF (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state='pending')>=10
 OR (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted')>=100
 OR coalesce((SELECT sum(CASE WHEN state IN ('pending','pending_delete','quarantined') THEN CASE WHEN bucket_id='social-media' THEN 52428800 ELSE 10485760 END ELSE coalesce(size_bytes,0) END) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted'),0)+cap>524288000 THEN RAISE SQLSTATE 'PT429' USING MESSAGE='media_quota'; END IF;
 bucket:=CASE WHEN p_purpose='post' THEN 'social-media' ELSE 'profile-media' END;
 ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg' WHEN 'image/webp' THEN 'webp' ELSE 'mp4' END;
 INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type)
 VALUES(aid,actor,p_client_request_id,bucket,actor::text||'/'||aid::text||'/original.'||ext,p_purpose,p_mime) RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;
