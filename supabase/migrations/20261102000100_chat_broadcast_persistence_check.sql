-- The managed realtime.send() function keeps a caller-supplied payload.id
-- but generates realtime.messages.id independently. Verify the stable
-- payload ID so accepted broadcasts are not retried as failed.
CREATE OR REPLACE FUNCTION private.meeto_dispatch_chat_outbox(p_batch integer DEFAULT 20) RETURNS jsonb
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
    IF NOT EXISTS(
      SELECT 1 FROM realtime.messages
      WHERE payload->>'id'=broadcast_id::text
        AND payload->>'event_id'=e.id::text
        AND topic='user:'||recipient AND event='invalidate'
        AND private AND extension='broadcast'
    ) THEN
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
