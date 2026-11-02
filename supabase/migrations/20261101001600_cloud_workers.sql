-- Cloud background processing: database-native private Broadcast dispatcher
-- plus a narrowly authenticated Storage cleanup Edge invocation.
-- The public key below is a publishable API key, never a secret/admin key.
CREATE EXTENSION IF NOT EXISTS pg_cron;
CREATE EXTENSION IF NOT EXISTS pg_net WITH SCHEMA extensions;

CREATE TABLE private.worker_invocations (
  singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
  run_id uuid NOT NULL,
  token_hash bytea NOT NULL,
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  completed_at timestamptz,
  net_request_id bigint,
  summary jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(summary)='object')
);
ALTER TABLE private.worker_invocations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE private.worker_invocations FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.meeto_dispatch_chat_outbox(p_batch integer DEFAULT 20) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE e record; recipients jsonb; recipient text; broadcast_id uuid; attempt_no integer;
        processed integer:=0; published integer:=0; retried integer:=0; dead integer:=0;
BEGIN
 IF p_batch IS NULL OR p_batch<1 OR p_batch>20 THEN RAISE EXCEPTION 'invalid_dispatch_batch'; END IF;
 FOR e IN
  SELECT id FROM public.event_outbox
  WHERE event_type='chat.invalidate'
    AND (state='pending' OR (state='processing' AND coalesce(lease_until,'-infinity'::timestamptz)<statement_timestamp()))
    AND attempts<5 AND available_at<=statement_timestamp()
  ORDER BY available_at,created_at,id LIMIT p_batch FOR UPDATE SKIP LOCKED
 LOOP
  UPDATE public.event_outbox SET state='processing',attempts=attempts+1,
    lease_until=statement_timestamp()+interval '2 minutes'
  WHERE id=e.id RETURNING attempts INTO attempt_no;
  BEGIN
   recipients:=private.chat_dispatch_recipients(e.id);
   IF jsonb_typeof(recipients)<>'array' THEN RAISE EXCEPTION 'invalid_recipient_set'; END IF;
   FOR recipient IN SELECT jsonb_array_elements_text(recipients) LOOP
    IF recipient !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
      RAISE EXCEPTION 'invalid_recipient';
    END IF;
    broadcast_id:=gen_random_uuid();
    PERFORM realtime.send(
      jsonb_build_object('id',broadcast_id,'event_id',e.id,'scope','messenger'),
      'invalidate','user:'||recipient,true);
    -- realtime.send() catches INSERT errors and returns void. Verify its row
    -- before ACK; absent partitions therefore follow the retry path.
    IF NOT EXISTS(SELECT 1 FROM realtime.messages WHERE id=broadcast_id) THEN
      RAISE EXCEPTION 'broadcast_not_persisted';
    END IF;
    published:=published+1;
   END LOOP;
   UPDATE public.event_outbox SET state='done',lease_until=NULL,processed_at=statement_timestamp()
   WHERE id=e.id AND state='processing' AND attempts=attempt_no;
   IF NOT FOUND THEN RAISE EXCEPTION 'dispatch_fence_lost'; END IF;
   processed:=processed+1;
  EXCEPTION WHEN OTHERS THEN
   UPDATE public.event_outbox
   SET state=CASE WHEN attempts>=5 THEN 'dead' ELSE 'pending' END,
       lease_until=NULL,
       available_at=statement_timestamp()+make_interval(secs=>least(3600,30*power(2,attempt_no)::integer)),
       payload=payload||jsonb_build_object('last_error','broadcast_failed')
   WHERE id=e.id AND state='processing' AND attempts=attempt_no;
   IF attempt_no>=5 THEN dead:=dead+1; ELSE retried:=retried+1; END IF;
  END;
 END LOOP;
 RETURN jsonb_build_object('processed',processed,'published',published,'retried',retried,'dead',dead);
END $$;
REVOKE ALL ON FUNCTION private.meeto_dispatch_chat_outbox(integer) FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.meeto_claim_media_cleanup(p_batch integer DEFAULT 5) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 IF p_batch IS NULL OR p_batch<1 OR p_batch>5 THEN RAISE EXCEPTION 'invalid_cleanup_batch'; END IF;
 PERFORM private.schedule_media_cleanup();
 WITH candidates AS (
  SELECT e.id AS event_id,e.attempts AS prior_attempts,a.id AS asset_id,a.bucket_id,a.object_path,a.mime_type
  FROM public.event_outbox e
  JOIN public.media_assets a ON a.id=e.aggregate_id
  WHERE e.event_type='media.retire' AND e.aggregate_type='media_asset'
    AND (e.state='pending' OR (e.state='processing' AND coalesce(e.lease_until,'-infinity'::timestamptz)<statement_timestamp()))
    AND e.attempts<5 AND e.available_at<=statement_timestamp()
    AND a.state='pending_delete' AND coalesce(a.delete_after,statement_timestamp())<=statement_timestamp()
    AND ((a.bucket_id='profile-media' AND a.purpose IN ('avatar','cover') AND a.mime_type IN ('image/png','image/jpeg','image/webp'))
      OR (a.bucket_id='social-media' AND a.purpose='post' AND a.mime_type IN ('image/png','image/jpeg','image/webp','video/mp4'))
      OR (a.bucket_id='chat-media' AND a.purpose='message' AND a.mime_type IN ('image/png','image/jpeg','image/webp','video/mp4')))
    AND a.object_path=a.owner_id::text||'/'||a.id::text||'/original.'||
      CASE a.mime_type WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
        WHEN 'image/webp' THEN 'webp' WHEN 'video/mp4' THEN 'mp4' END
    AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE a.id IN (p.avatar_asset_id,p.cover_asset_id))
    AND NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
                   WHERE pm.asset_id=a.id AND p.state='published')
    AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id
                   WHERE mm.asset_id=a.id AND m.state='sent')
  ORDER BY e.available_at,e.created_at,e.id LIMIT p_batch
  FOR UPDATE OF e SKIP LOCKED
 ), claimed AS (
  UPDATE public.event_outbox e SET state='processing',attempts=e.attempts+1,
    lease_until=statement_timestamp()+interval '2 minutes'
  FROM candidates c WHERE e.id=c.event_id
  RETURNING e.id AS event_id,e.attempts,e.aggregate_id
 )
 SELECT coalesce(jsonb_agg(jsonb_build_object('event_id',c.event_id,'attempt',c.attempts,
    'asset_id',a.id,'bucket_id',a.bucket_id,'object_path',a.object_path) ORDER BY c.event_id),'[]'::jsonb)
 INTO result
 FROM claimed c JOIN public.media_assets a ON a.id=c.aggregate_id
 WHERE a.state='pending_delete' AND coalesce(a.delete_after,statement_timestamp())<=statement_timestamp();
 RETURN coalesce(result,'[]'::jsonb);
END $$;
REVOKE ALL ON FUNCTION private.meeto_claim_media_cleanup(integer) FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.meeto_finish_media_cleanup(p_event_id uuid,p_attempt integer,p_success boolean,p_error_code text DEFAULT NULL) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE asset_id uuid; safe_error text;
BEGIN
 SELECT aggregate_id INTO asset_id FROM public.event_outbox
 WHERE id=p_event_id AND event_type='media.retire' AND state='processing' AND attempts=p_attempt;
 IF NOT FOUND THEN RETURN false; END IF;
 PERFORM 1 FROM public.media_assets WHERE id=asset_id FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 PERFORM 1 FROM public.event_outbox WHERE id=p_event_id AND state='processing' AND attempts=p_attempt FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 IF p_success THEN
  UPDATE public.media_assets SET state='deleted',metadata='{}'::jsonb
  WHERE id=asset_id AND state='pending_delete'
    AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE asset_id IN (p.avatar_asset_id,p.cover_asset_id))
    AND NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
                   WHERE pm.asset_id=asset_id AND p.state='published')
    AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id
                   WHERE mm.asset_id=asset_id AND m.state='sent');
  IF NOT FOUND THEN RETURN false; END IF;
  UPDATE public.event_outbox SET state='done',lease_until=NULL,processed_at=statement_timestamp()
  WHERE id=p_event_id AND state='processing' AND attempts=p_attempt;
  RETURN FOUND;
 END IF;
 safe_error:=CASE WHEN p_error_code IN ('storage_unavailable','storage_denied','storage_error') THEN p_error_code ELSE 'storage_error' END;
 UPDATE public.event_outbox
 SET state=CASE WHEN attempts>=5 THEN 'dead' ELSE 'pending' END,
     lease_until=NULL,
     available_at=statement_timestamp()+make_interval(secs=>least(3600,30*power(2,p_attempt)::integer)),
     payload=payload||jsonb_build_object('last_error',safe_error)
 WHERE id=p_event_id AND state='processing' AND attempts=p_attempt;
 RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION private.meeto_finish_media_cleanup(uuid,integer,boolean,text) FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.meeto_consume_worker_token(p_token text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE run uuid;
BEGIN
 IF p_token IS NULL OR p_token !~ '^[0-9a-f]{64}$' THEN RETURN NULL; END IF;
 UPDATE private.worker_invocations
 SET consumed_at=statement_timestamp()
 WHERE singleton AND consumed_at IS NULL AND expires_at>statement_timestamp()
   AND token_hash=extensions.digest(p_token,'sha256')
 RETURNING run_id INTO run;
 RETURN run;
END $$;
REVOKE ALL ON FUNCTION private.meeto_consume_worker_token(text) FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.meeto_finish_worker_run(p_run_id uuid,p_summary jsonb) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF p_summary IS NULL OR jsonb_typeof(p_summary)<>'object' OR pg_column_size(p_summary)>1024 THEN RETURN false; END IF;
 UPDATE private.worker_invocations SET completed_at=statement_timestamp(),summary=p_summary
 WHERE singleton AND run_id=p_run_id AND consumed_at IS NOT NULL;
 RETURN FOUND;
END $$;
REVOKE ALL ON FUNCTION private.meeto_finish_worker_run(uuid,jsonb) FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.meeto_worker_enqueue_cleanup() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE token text:=encode(extensions.gen_random_bytes(32),'hex'); run uuid:=gen_random_uuid(); request_id bigint;
BEGIN
 INSERT INTO private.worker_invocations(singleton,run_id,token_hash,issued_at,expires_at,consumed_at,completed_at,net_request_id,summary)
 VALUES(true,run,extensions.digest(token,'sha256'),statement_timestamp(),statement_timestamp()+interval '2 minutes',NULL,NULL,NULL,'{}'::jsonb)
 ON CONFLICT(singleton) DO UPDATE SET run_id=EXCLUDED.run_id,token_hash=EXCLUDED.token_hash,
  issued_at=EXCLUDED.issued_at,expires_at=EXCLUDED.expires_at,consumed_at=NULL,completed_at=NULL,net_request_id=NULL,summary='{}'::jsonb;
 SELECT net.http_post(
   url:='https://qjpohzpapiwdrxsionyr.supabase.co/functions/v1/meeto-background-cleanup',
   headers:=jsonb_build_object('Content-Type','application/json','apikey','sb_publishable_HDdsJa_VXk1Fj9mSCFe3RA_LIHLcVOq'),
   body:=jsonb_build_object('token',token)
 ) INTO request_id;
 UPDATE private.worker_invocations SET net_request_id=request_id WHERE singleton AND run_id=run;
 RETURN request_id;
END $$;
REVOKE ALL ON FUNCTION private.meeto_worker_enqueue_cleanup() FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION public.meeto_worker_consume_token(p_token text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 RETURN private.meeto_consume_worker_token(p_token);
END $$;
CREATE FUNCTION public.meeto_worker_claim_cleanup(p_batch integer DEFAULT 5) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 RETURN private.meeto_claim_media_cleanup(p_batch);
END $$;
CREATE FUNCTION public.meeto_worker_finish_cleanup(p_event_id uuid,p_attempt integer,p_success boolean,p_error_code text DEFAULT NULL) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 RETURN private.meeto_finish_media_cleanup(p_event_id,p_attempt,p_success,p_error_code);
END $$;
CREATE FUNCTION public.meeto_worker_finish_run(p_run_id uuid,p_summary jsonb) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 RETURN private.meeto_finish_worker_run(p_run_id,p_summary);
END $$;
REVOKE ALL ON FUNCTION public.meeto_worker_consume_token(text),public.meeto_worker_claim_cleanup(integer),
 public.meeto_worker_finish_cleanup(uuid,integer,boolean,text),public.meeto_worker_finish_run(uuid,jsonb)
FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.meeto_worker_consume_token(text),public.meeto_worker_claim_cleanup(integer),
 public.meeto_worker_finish_cleanup(uuid,integer,boolean,text),public.meeto_worker_finish_run(uuid,jsonb)
TO service_role;

DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM cron.job WHERE jobname IN ('meeto-chat-dispatch-every-minute','meeto-media-cleanup-every-five-minutes')) THEN
   RAISE EXCEPTION 'Meeto worker cron job name already exists; inventory required';
 END IF;
 PERFORM cron.schedule('meeto-chat-dispatch-every-minute','* * * * *',
   'SELECT private.meeto_dispatch_chat_outbox(20);');
 PERFORM cron.schedule('meeto-media-cleanup-every-five-minutes','*/5 * * * *',
   'SELECT private.meeto_worker_enqueue_cleanup();');
END $$;

NOTIFY pgrst,'reload schema';
