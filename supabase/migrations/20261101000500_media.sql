-- Managed Storage APIs keep ownership of physical object writes/deletes.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_policies WHERE schemaname='storage' AND tablename='objects') THEN
  RAISE EXCEPTION 'Storage policies already present: review permissive OR policies before deployment'; END IF;
 IF to_regprocedure('storage.allow_any_operation(text[])') IS NULL THEN RAISE EXCEPTION 'Operation-aware Storage required'; END IF;
END $$;
INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types) VALUES
 ('profile-media','profile-media',false,10485760,ARRAY['image/png','image/jpeg','image/webp']),
 ('social-media','social-media',false,52428800,ARRAY['image/png','image/jpeg','image/webp','video/mp4']);
CREATE POLICY meeto_b_insert ON storage.objects FOR INSERT TO authenticated
 WITH CHECK(bucket_id IN ('profile-media','social-media') AND owner_id=auth.uid()::text AND private.can_upload_object(bucket_id,name));
CREATE POLICY meeto_b_get ON storage.objects FOR SELECT TO authenticated
 USING(bucket_id IN ('profile-media','social-media') AND storage.allow_any_operation(ARRAY['object.get_authenticated','object.get_authenticated_info']) AND private.can_read_object(bucket_id,name));
-- No sign/list/copy/move/update/delete grant via policy. Buckets stay private.
CREATE FUNCTION private.prepare_upload(p_purpose text,p_mime text,p_client_request_id uuid) RETURNS jsonb
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
 OR coalesce((SELECT sum(CASE WHEN state='pending' THEN CASE WHEN bucket_id='social-media' THEN 52428800 ELSE 10485760 END ELSE coalesce(size_bytes,0) END) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted'),0)+cap>524288000 THEN RAISE SQLSTATE 'PT429' USING MESSAGE='media_quota'; END IF;
 bucket:=CASE WHEN p_purpose='post' THEN 'social-media' ELSE 'profile-media' END;
 ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg' WHEN 'image/webp' THEN 'webp' ELSE 'mp4' END;
 INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type)
 VALUES(aid,actor,p_client_request_id,bucket,actor::text||'/'||aid::text||'/original.'||ext,p_purpose,p_mime) RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;
CREATE FUNCTION private.finalize_upload(p_asset_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.media_assets;
BEGIN
 SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
 IF a.state='ready' THEN RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
 IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
 IF a.metadata->>'verified' IS DISTINCT FROM 'true' OR NOT EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path AND o.id::text=a.metadata->>'object_id' AND o.owner_id=actor::text AND (o.metadata->>'size')::bigint=a.size_bytes) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='verification_pending'; END IF;
 UPDATE public.media_assets SET state='ready',ready_at=statement_timestamp() WHERE id=a.id RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;
CREATE FUNCTION public.prepare_upload(p_purpose text,p_mime text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.prepare_upload(p_purpose,p_mime,p_client_request_id) $$;
CREATE FUNCTION public.finalize_upload(p_asset_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.finalize_upload(p_asset_id) $$;
GRANT EXECUTE ON FUNCTION public.prepare_upload(text,text,uuid),private.prepare_upload(text,text,uuid),public.finalize_upload(uuid),private.finalize_upload(uuid) TO authenticated;

-- Worker-only ABI. Never granted to API roles; private is not exposed.
CREATE FUNCTION private.record_media_verification(aid uuid,oid uuid,bytes bigint,digest text,width integer,height integer,duration numeric) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets;
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 SELECT * INTO a FROM public.media_assets WHERE id=aid AND state='pending' FOR UPDATE;
 IF NOT FOUND THEN RETURN; END IF;
 IF bytes<=0 OR bytes>(CASE WHEN a.mime_type='video/mp4' THEN 52428800 ELSE 10485760 END) OR digest !~ '^[0-9a-f]{64}$'
 OR NOT EXISTS(SELECT 1 FROM storage.objects o WHERE o.id=oid AND o.bucket_id=a.bucket_id AND o.name=a.object_path AND o.owner_id=a.owner_id::text AND (o.metadata->>'size')::bigint=bytes AND o.metadata->>'mimetype'=a.mime_type) THEN RAISE EXCEPTION 'Invalid worker verification'; END IF;
 UPDATE public.media_assets SET size_bytes=bytes,metadata=jsonb_build_object('verified',true,'object_id',oid,'sha256',digest,'width',width,'height',height,'duration',duration) WHERE id=aid;
END $$;
CREATE FUNCTION private.schedule_media_cleanup() RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a uuid; n integer:=0;
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 FOR a IN SELECT id FROM public.media_assets m WHERE
  (state='pending_delete' AND coalesce(delete_after,now())<=now()) OR
  (state IN ('pending','ready','quarantined') AND created_at<now()-interval '24 hours'
   AND NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=m.id)
   AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE m.id IN (p.avatar_asset_id,p.cover_asset_id)))
 ORDER BY id LIMIT 100 FOR UPDATE LOOP PERFORM private.retire_asset(a); n:=n+1; END LOOP;
 RETURN n;
END $$;
NOTIFY pgrst,'reload schema';
