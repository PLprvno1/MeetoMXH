INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types) VALUES('chat-media','chat-media',false,52428800,ARRAY['image/png','image/jpeg','image/webp','video/mp4']);
CREATE FUNCTION private.prepare_message_upload(p_conversation_id uuid,p_mime text,p_client_request_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); a public.media_assets; aid uuid:=gen_random_uuid(); ext text;
BEGIN
 IF p_mime IS NULL OR p_mime NOT IN ('image/png','image/jpeg','image/webp','video/mp4') OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_chat_media'; END IF;
 SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN
  IF a.purpose<>'message' OR a.mime_type<>p_mime OR a.metadata->>'conversation_id' IS DISTINCT FROM p_conversation_id::text THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF a.state<>'pending' OR a.created_at<=now()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
 END IF;
 IF (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state='pending')>=10 OR (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted')>=100
 OR coalesce((SELECT sum(CASE WHEN state IN ('pending','pending_delete','quarantined') THEN CASE WHEN bucket_id='profile-media' THEN 10485760 ELSE 52428800 END ELSE coalesce(size_bytes,0) END) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted'),0)+52428800>524288000 THEN RAISE SQLSTATE 'PT429' USING MESSAGE='media_quota'; END IF;
 ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg' WHEN 'image/webp' THEN 'webp' ELSE 'mp4' END;
 INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata) VALUES(aid,actor,p_client_request_id,'chat-media',actor::text||'/'||aid::text||'/original.'||ext,'message',p_mime,jsonb_build_object('conversation_id',p_conversation_id)) RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;
CREATE OR REPLACE FUNCTION private.can_upload_object(bucket text,path text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.media_assets a WHERE a.bucket_id=bucket AND a.object_path=path AND a.owner_id=auth.uid() AND a.state='pending' AND a.created_at>now()-interval '15 minutes'
 AND (a.purpose<>'message' OR (private.can_read_conversation((a.metadata->>'conversation_id')::uuid) AND EXISTS(SELECT 1 FROM public.conversations c WHERE c.id=(a.metadata->>'conversation_id')::uuid AND c.state='active'))));
$$;
CREATE POLICY meeto_c_insert ON storage.objects FOR INSERT TO authenticated WITH CHECK(bucket_id='chat-media' AND owner_id=auth.uid()::text AND private.can_upload_object(bucket_id,name));
CREATE POLICY meeto_c_get ON storage.objects FOR SELECT TO authenticated USING(bucket_id='chat-media' AND storage.allow_any_operation(ARRAY['object.get_authenticated','object.get_authenticated_info']) AND private.can_read_object(bucket_id,name));
ALTER FUNCTION private.finalize_upload(uuid) RENAME TO phase_b_finalize_upload;
REVOKE ALL ON FUNCTION private.phase_b_finalize_upload(uuid) FROM authenticated;
CREATE FUNCTION private.finalize_upload(p_asset_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); a public.media_assets;
BEGIN
 SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor;
 IF NOT FOUND THEN PERFORM private.lock_profile(); RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
 IF a.purpose<>'message' THEN RETURN private.phase_b_finalize_upload(p_asset_id); END IF;
 PERFORM private.c_lock((a.metadata->>'conversation_id')::uuid);
 SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id FOR UPDATE;
 IF a.state='ready' THEN RETURN to_jsonb(a)-'metadata'-'client_request_id'; END IF;
 IF a.state<>'pending' OR a.created_at<=now()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
 IF a.metadata->>'verified' IS DISTINCT FROM 'true' OR NOT EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path AND o.id::text=a.metadata->>'object_id' AND o.owner_id=actor::text AND (o.metadata->>'size')::bigint=a.size_bytes) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='verification_pending'; END IF;
 UPDATE public.media_assets SET state='ready',ready_at=statement_timestamp() WHERE id=a.id RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;
GRANT EXECUTE ON FUNCTION private.finalize_upload(uuid) TO authenticated;
CREATE OR REPLACE FUNCTION public.finalize_upload(p_asset_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.finalize_upload(p_asset_id) $$;
CREATE OR REPLACE FUNCTION private.record_media_verification(aid uuid,oid uuid,bytes bigint,digest text,width integer,height integer,duration numeric) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets;
BEGIN
 -- Worker-only; no actor or global B lock needed, immutable object paths.
 SELECT * INTO a FROM public.media_assets WHERE id=aid AND state='pending' FOR UPDATE;
 IF NOT FOUND THEN RETURN; END IF;
 IF bytes<=0 OR bytes>(CASE WHEN a.mime_type='video/mp4' THEN 52428800 ELSE 10485760 END) OR digest !~ '^[0-9a-f]{64}$'
 OR NOT EXISTS(SELECT 1 FROM storage.objects o WHERE o.id=oid AND o.bucket_id=a.bucket_id AND o.name=a.object_path AND o.owner_id=a.owner_id::text AND (o.metadata->>'size')::bigint=bytes AND o.metadata->>'mimetype'=a.mime_type) THEN RAISE EXCEPTION 'Invalid worker verification'; END IF;
 UPDATE public.media_assets SET size_bytes=bytes,metadata=jsonb_build_object('verified',true,'object_id',oid,'sha256',digest,'width',width,'height',height,'duration',duration)
 || CASE WHEN a.purpose='message' THEN jsonb_build_object('conversation_id',a.metadata->>'conversation_id') ELSE '{}'::jsonb END WHERE id=aid;
END $$;
CREATE OR REPLACE FUNCTION private.schedule_media_cleanup() RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets; n integer:=0;
BEGIN
 FOR a IN SELECT * FROM public.media_assets m WHERE purpose IN ('avatar','cover','post','message') AND
 ((state='pending_delete' AND coalesce(delete_after,now())<=now()) OR (state IN ('pending','ready','quarantined') AND created_at<now()-interval '24 hours'
 AND NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=m.id)
 AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE m.id IN (p.avatar_asset_id,p.cover_asset_id))
 AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages msg ON msg.id=mm.message_id WHERE mm.asset_id=m.id AND msg.state='sent')))
 ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED LOOP
  IF a.state='pending_delete' OR (NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=a.id)
  AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE a.id IN (p.avatar_asset_id,p.cover_asset_id))
  AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages msg ON msg.id=mm.message_id WHERE mm.asset_id=a.id AND msg.state='sent')) THEN
   PERFORM private.retire_asset(a.id); n:=n+1;
  END IF;
 END LOOP;
 RETURN n;
END $$;
CREATE FUNCTION public.prepare_message_upload(p_conversation_id uuid,p_mime text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.prepare_message_upload(p_conversation_id,p_mime,p_client_request_id) $$;
GRANT EXECUTE ON FUNCTION public.prepare_message_upload(uuid,text,uuid),private.prepare_message_upload(uuid,text,uuid) TO authenticated;

-- A/B writer retains its pair lock. Chat sends use the same pair lock, not global.
ALTER FUNCTION private.set_block(uuid,boolean) RENAME TO phase_b_set_block;
REVOKE ALL ON FUNCTION private.phase_b_set_block(uuid,boolean) FROM authenticated;
CREATE FUNCTION private.set_block(p_target uuid,p_blocked boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE before_state boolean; result jsonb;
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 -- Acquire pair before checking desired state, consistently with existing A/B writer.
 IF auth.uid() IS NOT NULL AND p_target IS NOT NULL THEN PERFORM private.c_pair(auth.uid(),p_target); END IF;
 SELECT EXISTS(SELECT 1 FROM public.user_blocks WHERE blocker_id=auth.uid() AND blocked_id=p_target) INTO before_state;
 result:=private.phase_b_set_block(p_target,p_blocked);
 IF before_state IS DISTINCT FROM p_blocked THEN PERFORM private.c_event(NULL,'block',ARRAY[auth.uid(),p_target]); END IF;
 RETURN result;
END $$;
GRANT EXECUTE ON FUNCTION private.set_block(uuid,boolean) TO authenticated;
CREATE OR REPLACE FUNCTION public.set_block(p_target uuid,p_blocked boolean) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_block(p_target,p_blocked) $$;

-- Only own per-user private Broadcast receive, never resource-wide raw messages.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_policies WHERE schemaname='realtime' AND tablename='messages') THEN RAISE EXCEPTION 'Realtime policies already exist; inventory required'; END IF;
 IF NOT (SELECT relrowsecurity FROM pg_class WHERE oid='realtime.messages'::regclass) THEN RAISE EXCEPTION 'Realtime managed RLS missing'; END IF;
END $$;
CREATE POLICY meeto_c_receive ON realtime.messages FOR SELECT TO authenticated USING(extension='broadcast' AND realtime.topic()='user:'||auth.uid()::text AND private.is_active());
-- No client INSERT policy: clients cannot forge invalidations.
CREATE FUNCTION private.chat_dispatch_recipients(eid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT coalesce(jsonb_agg(q.uid ORDER BY q.uid),'[]'::jsonb) FROM (
 SELECT DISTINCT x.uid FROM public.event_outbox e CROSS JOIN LATERAL jsonb_array_elements_text(e.payload->'recipients') x(uid)
 JOIN public.account_status s ON s.user_id=x.uid::uuid AND s.state='active'
 JOIN public.profiles p ON p.id=s.user_id AND p.onboarding_completed
 JOIN auth.users u ON u.id=p.id AND u.email_confirmed_at IS NOT NULL
 WHERE e.id=eid AND e.event_type='chat.invalidate' AND (
 e.payload->>'kind' IN ('membership','state','block','hidden') OR EXISTS(
  SELECT 1 FROM public.conversations c JOIN public.conversation_members cm ON cm.conversation_id=c.id AND cm.user_id=p.id AND cm.state='active'
  WHERE c.id=e.aggregate_id AND c.state IN ('active','archived') AND
   (c.kind='group' OR NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE (b.blocker_id=c.direct_low AND b.blocked_id=c.direct_high) OR (b.blocker_id=c.direct_high AND b.blocked_id=c.direct_low)))
  AND (e.payload->>'message_id' IS NULL OR EXISTS(SELECT 1 FROM public.messages m WHERE m.id=(e.payload->>'message_id')::uuid AND m.conversation_id=c.id AND m.seq>=cm.history_from_seq
   AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE (b.blocker_id=p.id AND b.blocked_id=m.sender_id) OR (b.blocked_id=p.id AND b.blocker_id=m.sender_id))
   AND NOT EXISTS(SELECT 1 FROM public.message_hidden h WHERE h.message_id=m.id AND h.user_id=p.id)))))) q;
$$;
NOTIFY pgrst,'reload schema';
