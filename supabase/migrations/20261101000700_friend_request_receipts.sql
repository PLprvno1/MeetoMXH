-- Preserve every successful send UUID, including duplicate-pair/crossed sends.
-- Completed outbox receipts are local transaction checkpoints, not queued broadcasts.
ALTER FUNCTION private.send_friend_request(uuid,uuid) RENAME TO send_friend_request_unreceipted;
REVOKE ALL ON FUNCTION private.send_friend_request_unreceipted(uuid,uuid) FROM authenticated;
CREATE FUNCTION private.send_friend_request(p_recipient uuid,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); e public.event_outbox; result jsonb; key text;
BEGIN
 IF p_recipient IS NULL OR p_recipient=actor OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_request'; END IF;
 IF private.is_blocked(p_recipient) OR NOT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id WHERE p.id=p_recipient AND p.onboarding_completed AND s.state='active') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 key:='friend-send-receipt:'||actor::text||':'||p_client_request_id::text;
 SELECT * INTO e FROM public.event_outbox WHERE event_key=key;
 IF FOUND THEN
  IF e.aggregate_id<>p_recipient THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  SELECT to_jsonb(r) INTO result FROM public.friend_requests r WHERE r.id=(e.payload->>'request_id')::uuid AND actor IN (r.requester_id,r.recipient_id);
  IF result IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='request_receipt_expired'; END IF;
  RETURN result;
 END IF;
 result:=private.send_friend_request_unreceipted(p_recipient,p_client_request_id);
 INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id,payload,state,processed_at)
 VALUES(key,'relationship.request_receipt','profile',p_recipient,jsonb_build_object('request_id',result->>'id'),'done',statement_timestamp());
 RETURN result;
END $$;
GRANT EXECUTE ON FUNCTION private.send_friend_request(uuid,uuid) TO authenticated;
CREATE OR REPLACE FUNCTION public.send_friend_request(p_recipient uuid,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.send_friend_request(p_recipient,p_client_request_id) $$;
NOTIFY pgrst,'reload schema';
