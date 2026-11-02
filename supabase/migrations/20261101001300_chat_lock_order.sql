-- C uses shared actor guards, per-pair/per-conversation locks, never global B lock.
CREATE FUNCTION private.c_actor() RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
 PERFORM 1 FROM auth.users WHERE id=actor AND email_confirmed_at IS NOT NULL FOR SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='email_verification_required'; END IF;
 PERFORM 1 FROM public.account_status WHERE user_id=actor AND state='active' FOR SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 PERFORM 1 FROM public.profiles WHERE id=actor AND onboarding_completed FOR KEY SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='onboarding_required'; END IF;
 RETURN actor;
END $$;
CREATE OR REPLACE FUNCTION private.c_lock(cid uuid,allow_archived boolean DEFAULT false) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); c public.conversations;
BEGIN
 SELECT * INTO c FROM public.conversations WHERE id=cid;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 IF c.kind='private' THEN PERFORM private.c_pair(c.direct_low,c.direct_high); END IF;
 PERFORM private.c_actor();
 SELECT * INTO c FROM public.conversations WHERE id=cid FOR UPDATE;
 IF NOT private.can_read_conversation(cid) OR (c.state<>'active' AND NOT allow_archived) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN actor;
END $$;
CREATE OR REPLACE FUNCTION private.get_or_create_private(p_peer uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); c public.conversations;
BEGIN
 IF p_peer IS NULL OR p_peer=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_peer'; END IF;
 PERFORM private.c_pair(actor,p_peer); PERFORM private.c_actor();
 IF private.is_blocked(p_peer) OR NOT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id JOIN auth.users u ON u.id=p.id WHERE p.id=p_peer AND p.onboarding_completed AND s.state='active' AND u.email_confirmed_at IS NOT NULL) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='peer_unavailable'; END IF;
 SELECT * INTO c FROM public.conversations WHERE kind='private' AND direct_low=least(actor,p_peer) AND direct_high=greatest(actor,p_peer) FOR UPDATE;
 IF NOT FOUND THEN
  INSERT INTO public.conversations(kind,direct_low,direct_high,created_by) VALUES('private',least(actor,p_peer),greatest(actor,p_peer),actor) RETURNING * INTO c;
  INSERT INTO public.conversation_members(conversation_id,user_id) VALUES(c.id,actor),(c.id,p_peer);
  PERFORM private.c_event(c.id,'created');
 END IF;
 IF c.state<>'active' OR (SELECT count(*) FROM public.conversation_members WHERE conversation_id=c.id AND user_id IN (actor,p_peer) AND state='active')<>2 OR (SELECT count(*) FROM public.conversation_members WHERE conversation_id=c.id)<>2 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='private_conversation_invariant'; END IF;
 RETURN private.c_conversation(c.id);
END $$;
CREATE OR REPLACE FUNCTION private.create_group_conversation(p_title text,p_members uuid[],p_client_request_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); c public.conversations; target uuid; h text; old_hash text;
BEGIN
 IF p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 1 AND 150 OR p_title ~ '[[:cntrl:]]' OR p_members IS NULL OR cardinality(p_members) NOT BETWEEN 1 AND 49
 OR array_position(p_members,NULL) IS NOT NULL OR actor=ANY(p_members) OR p_client_request_id IS NULL OR EXISTS(SELECT 1 FROM unnest(p_members) x GROUP BY x HAVING count(*)>1) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_chat'; END IF;
 SELECT array_agg(x ORDER BY x) INTO p_members FROM unnest(p_members) x;
 h:=encode(sha256(convert_to(jsonb_build_array(btrim(p_title),p_members)::text,'UTF8')),'hex');
 SELECT * INTO c FROM public.conversations WHERE created_by=actor AND client_request_id=p_client_request_id;
 IF FOUND THEN
  SELECT payload->>'hash' INTO old_hash FROM public.event_outbox WHERE event_key='chat-create:'||c.id::text;
  IF old_hash IS DISTINCT FROM h THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF NOT private.can_read_conversation(c.id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
  RETURN private.c_conversation(c.id);
 END IF;
 FOREACH target IN ARRAY p_members LOOP PERFORM private.c_invitee(actor,target); END LOOP;
 INSERT INTO public.conversations(kind,created_by,client_request_id,title) VALUES('group',actor,p_client_request_id,btrim(p_title)) RETURNING * INTO c;
 INSERT INTO public.conversation_members(conversation_id,user_id,role) VALUES(c.id,actor,'admin');
 INSERT INTO public.conversation_members(conversation_id,user_id) SELECT c.id,x FROM unnest(p_members) x;
 INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id,payload,state,processed_at) VALUES('chat-create:'||c.id::text,'chat.create_receipt','conversation',c.id,jsonb_build_object('hash',h),'done',statement_timestamp());
 PERFORM private.c_event(c.id,'membership');
 RETURN private.c_conversation(c.id);
END $$;
CREATE OR REPLACE FUNCTION private.leave_conversation(p_conversation_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); c public.conversations; targets uuid[];
BEGIN
 -- A repeated leave still checks identity/status but does not rejoin or restore history.
 IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=p_conversation_id AND user_id=actor AND state='left') THEN PERFORM private.c_actor(); RETURN jsonb_build_object('left',true); END IF;
 actor:=private.c_lock(p_conversation_id); SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id;
 IF c.kind<>'group' THEN RAISE SQLSTATE 'PT422' USING MESSAGE='private_chat_leave_disabled'; END IF;
 PERFORM private.c_last_admin(c.id,actor);
 SELECT array_agg(user_id) INTO targets FROM public.conversation_members WHERE conversation_id=c.id AND state='active';
 UPDATE public.conversation_members SET state='left',role='member',left_at=statement_timestamp() WHERE conversation_id=c.id AND user_id=actor;
 UPDATE public.conversations SET version=version+1,updated_at=statement_timestamp() WHERE id=c.id;
 PERFORM private.c_event(c.id,'membership',targets);
 RETURN jsonb_build_object('left',true);
END $$;
-- Preserve B receipts/state machine; normalize B pair writers to global ->pair ->actor.
ALTER FUNCTION private.send_friend_request(uuid,uuid) RENAME TO phase_b_send_friend_request;
REVOKE ALL ON FUNCTION private.phase_b_send_friend_request(uuid,uuid) FROM authenticated;
CREATE FUNCTION private.send_friend_request(p_recipient uuid,p_client_request_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 IF auth.uid() IS NOT NULL AND p_recipient IS NOT NULL THEN PERFORM private.c_pair(auth.uid(),p_recipient); END IF;
 RETURN private.phase_b_send_friend_request(p_recipient,p_client_request_id);
END $$;
ALTER FUNCTION private.resolve_friend_request(uuid,text) RENAME TO phase_b_resolve_friend_request;
REVOKE ALL ON FUNCTION private.phase_b_resolve_friend_request(uuid,text) FROM authenticated;
CREATE FUNCTION private.resolve_friend_request(p_request_id uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE r public.friend_requests;
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 SELECT * INTO r FROM public.friend_requests WHERE id=p_request_id;
 IF FOUND THEN PERFORM private.c_pair(r.requester_id,r.recipient_id); END IF;
 RETURN private.phase_b_resolve_friend_request(p_request_id,p_action);
END $$;
GRANT EXECUTE ON FUNCTION private.send_friend_request(uuid,uuid),private.resolve_friend_request(uuid,text) TO authenticated;
CREATE OR REPLACE FUNCTION public.send_friend_request(p_recipient uuid,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.send_friend_request(p_recipient,p_client_request_id) $$;
CREATE OR REPLACE FUNCTION public.accept_friend_request(p_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_friend_request(p_request_id,'accept') $$;
CREATE OR REPLACE FUNCTION public.decline_friend_request(p_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_friend_request(p_request_id,'decline') $$;
CREATE OR REPLACE FUNCTION public.cancel_friend_request(p_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_friend_request(p_request_id,'cancel') $$;
NOTIFY pgrst,'reload schema';