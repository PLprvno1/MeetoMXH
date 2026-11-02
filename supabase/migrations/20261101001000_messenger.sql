-- Phase C: same 36 tables. High-frequency writes never acquire B global lock.
CREATE FUNCTION private.c_pair(a uuid,b uuid) RETURNS void LANGUAGE sql SET search_path='' AS $$
 SELECT pg_advisory_xact_lock(hashtextextended(least(a,b)::text||':'||greatest(a,b)::text,0));
$$;
CREATE OR REPLACE FUNCTION private.can_read_conversation(cid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.conversations c JOIN public.conversation_members m ON m.conversation_id=c.id
 WHERE c.id=cid AND c.state IN ('active','archived') AND m.user_id=auth.uid() AND m.state='active'
 AND (c.kind='group' OR (NOT private.is_blocked(c.direct_low) AND NOT private.is_blocked(c.direct_high)
  AND EXISTS(SELECT 1 FROM public.account_status s WHERE s.user_id=c.direct_low AND s.state='active')
  AND EXISTS(SELECT 1 FROM public.account_status s WHERE s.user_id=c.direct_high AND s.state='active'))));
$$;
CREATE FUNCTION private.c_lock(cid uuid,allow_archived boolean DEFAULT false) RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); c public.conversations;
BEGIN
 SELECT * INTO c FROM public.conversations WHERE id=cid;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 IF c.kind='private' THEN PERFORM private.c_pair(c.direct_low,c.direct_high); END IF;
 PERFORM private.lock_profile();
 SELECT * INTO c FROM public.conversations WHERE id=cid FOR UPDATE;
 IF NOT private.can_read_conversation(cid) OR (c.state<>'active' AND NOT allow_archived) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN actor;
END $$;
CREATE FUNCTION private.c_event(cid uuid,kind text,targets uuid[] DEFAULT NULL,mid uuid DEFAULT NULL) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE users uuid[]:=targets;
BEGIN
 IF users IS NULL THEN SELECT array_agg(user_id ORDER BY user_id) INTO users FROM public.conversation_members WHERE conversation_id=cid AND state='active'; END IF;
 INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id,payload)
 VALUES('chat:'||gen_random_uuid()::text,'chat.invalidate','conversation',cid,jsonb_build_object('kind',kind,'recipients',coalesce(users,'{}'::uuid[]),'message_id',mid));
END $$;
CREATE FUNCTION private.c_message(mid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',m.id,'conversation_id',m.conversation_id,'seq',m.seq,'sender_id',m.sender_id,'kind',m.kind,'body',m.body,'state',m.state,
 'version',m.version,'created_at',m.created_at,'edited_at',m.edited_at,'recalled_at',m.recalled_at,
 'reply_to_message_id',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN m.reply_to_message_id END,
 'reply',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN (SELECT jsonb_build_object('id',r.id,'sender_id',r.sender_id,'state',r.state,'body',r.body,'kind',r.kind) FROM public.messages r WHERE r.id=m.reply_to_message_id) END,
 'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',mm.asset_id,'position',mm.position) ORDER BY mm.position) FROM public.message_media mm WHERE mm.message_id=m.id AND m.state='sent' AND private.can_read_media(mm.asset_id)),'[]'::jsonb))
 FROM public.messages m WHERE m.id=mid AND private.can_read_message(m.id);
$$;
CREATE FUNCTION private.c_conversation(cid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',c.id,'kind',c.kind,'title',c.title,'state',c.state,'created_at',c.created_at,'updated_at',c.updated_at,'version',c.version,
 'high_water',c.next_seq,'history_from_seq',cm.history_from_seq,'last_read_seq',cm.last_read_seq,'last_delivered_seq',cm.last_delivered_seq,'role',cm.role,
 'last_message',(SELECT private.c_message(m.id) FROM public.messages m WHERE m.conversation_id=c.id AND private.can_read_message(m.id) ORDER BY m.seq DESC LIMIT 1),
 'unread_count',(SELECT count(*) FROM public.messages m WHERE m.conversation_id=c.id AND m.seq>cm.last_read_seq AND m.sender_id<>auth.uid() AND m.state='sent' AND private.can_read_message(m.id)))
 FROM public.conversations c JOIN public.conversation_members cm ON cm.conversation_id=c.id AND cm.user_id=auth.uid()
 WHERE c.id=cid AND private.can_read_conversation(c.id);
$$;
CREATE FUNCTION private.get_or_create_private(p_peer uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); c public.conversations;
BEGIN
 IF p_peer IS NULL OR p_peer=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_peer'; END IF;
 PERFORM private.c_pair(actor,p_peer); PERFORM private.lock_profile();
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
CREATE FUNCTION private.c_invitee(actor uuid,target uuid) RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF target=actor OR target IS NULL OR private.is_blocked(target) OR NOT private.are_friends(target)
 OR NOT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id JOIN auth.users u ON u.id=p.id WHERE p.id=target AND p.onboarding_completed AND s.state='active' AND u.email_confirmed_at IS NOT NULL) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invitee_unavailable'; END IF;
END $$;
CREATE FUNCTION private.create_group_conversation(p_title text,p_members uuid[],p_client_request_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.lock_profile(); c public.conversations; target uuid; h text; old_hash text;
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
CREATE FUNCTION private.get_conversation(p_conversation_id uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN PERFORM private.b_active(); result:=private.c_conversation(p_conversation_id); IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF; RETURN result; END $$;
CREATE FUNCTION private.get_inbox(p_after_time timestamptz,p_after_id uuid,p_limit integer,p_include_archived boolean) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_include_archived IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_archive_filter'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.c_conversation(q.id) ORDER BY q.updated_at DESC,q.id DESC) FROM (
 SELECT c.id,c.updated_at FROM public.conversations c WHERE private.can_read_conversation(c.id) AND (c.state='active' OR p_include_archived)
 AND (p_after_time IS NULL OR (c.updated_at,c.id)<(p_after_time,p_after_id)) ORDER BY c.updated_at DESC,c.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
CREATE FUNCTION private.send_message(p_conversation_id uuid,p_client_request_id uuid,p_kind text,p_body text,p_assets uuid[],p_reply_to uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); m public.messages; a public.media_assets; aid uuid; h text; seq bigint; pos integer:=0;
BEGIN
 IF p_client_request_id IS NULL OR p_kind IS NULL OR p_kind NOT IN ('text','media') OR p_assets IS NULL OR p_body IS NULL OR length(p_body)>10000
 OR (p_kind='text' AND (length(btrim(p_body))=0 OR cardinality(p_assets)<>0)) OR (p_kind='media' AND cardinality(p_assets) NOT BETWEEN 1 AND 10)
 OR array_position(p_assets,NULL) IS NOT NULL OR EXISTS(SELECT 1 FROM unnest(p_assets) x GROUP BY x HAVING count(*)>1) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message'; END IF;
 h:=encode(sha256(convert_to(jsonb_build_array(p_kind,p_body,p_assets,p_reply_to)::text,'UTF8')),'hex');
 SELECT * INTO m FROM public.messages WHERE conversation_id=p_conversation_id AND sender_id=actor AND client_request_id=p_client_request_id;
 IF FOUND THEN
  IF m.request_hash<>h THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF NOT private.can_read_message(m.id) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='message_no_longer_visible'; END IF;
  RETURN private.c_message(m.id);
 END IF;
 IF p_reply_to IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.messages WHERE id=p_reply_to AND conversation_id=p_conversation_id AND state='sent' AND private.can_read_message(id)) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='reply_unavailable'; END IF;
 FOR a IN SELECT * FROM public.media_assets WHERE id=ANY(p_assets) ORDER BY id FOR UPDATE LOOP
  IF a.owner_id IS DISTINCT FROM actor OR a.purpose<>'message' OR a.state<>'ready' OR a.metadata->>'conversation_id' IS DISTINCT FROM p_conversation_id::text
  OR EXISTS(SELECT 1 FROM public.message_media WHERE asset_id=a.id) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message_asset'; END IF;
 END LOOP;
 IF (SELECT count(*) FROM public.media_assets WHERE id=ANY(p_assets))<>cardinality(p_assets) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='missing_message_asset'; END IF;
 UPDATE public.conversations SET next_seq=next_seq+1,updated_at=statement_timestamp(),version=version+1 WHERE id=p_conversation_id RETURNING next_seq INTO seq;
 INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,kind,body,reply_to_message_id) VALUES(p_conversation_id,seq,actor,p_client_request_id,h,p_kind,p_body,p_reply_to) RETURNING * INTO m;
 FOREACH aid IN ARRAY p_assets LOOP INSERT INTO public.message_media(message_id,asset_id,position) VALUES(m.id,aid,pos); pos:=pos+1; END LOOP;
 UPDATE public.conversations SET last_message_id=m.id WHERE id=p_conversation_id;
 PERFORM private.c_event(p_conversation_id,'message',NULL,m.id);
 RETURN private.c_message(m.id);
END $$;
CREATE FUNCTION private.get_messages(p_conversation_id uuid,p_before_seq bigint,p_after_seq bigint,p_limit integer,p_query text DEFAULT NULL) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR (p_before_seq IS NOT NULL AND p_after_seq IS NOT NULL) OR p_before_seq<=0 OR p_after_seq<0
 OR (p_query IS NOT NULL AND length(btrim(p_query)) NOT BETWEEN 2 AND 100) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message_cursor'; END IF;
 RETURN jsonb_build_object('high_water',(SELECT next_seq FROM public.conversations WHERE id=p_conversation_id),
 'conversation_version',(SELECT version FROM public.conversations WHERE id=p_conversation_id),
 'items',coalesce((SELECT jsonb_agg(private.c_message(q.id) ORDER BY CASE WHEN p_after_seq IS NOT NULL THEN q.seq END ASC,q.seq DESC) FROM (
  SELECT m.id,m.seq FROM public.messages m WHERE m.conversation_id=p_conversation_id AND private.can_read_message(m.id)
  AND (p_before_seq IS NULL OR m.seq<p_before_seq) AND (p_after_seq IS NULL OR m.seq>p_after_seq)
  AND (p_query IS NULL OR (m.state='sent' AND m.kind='text' AND to_tsvector('simple',coalesce(m.body,'')) @@ plainto_tsquery('simple',btrim(p_query))))
  ORDER BY CASE WHEN p_after_seq IS NOT NULL THEN m.seq END ASC,m.seq DESC LIMIT p_limit) q),'[]'::jsonb));
END $$;
CREATE FUNCTION private.c_mark(p_conversation_id uuid,p_message_id uuid,p_read boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); n bigint; old public.conversation_members; result public.conversation_members;
BEGIN
 SELECT seq INTO n FROM public.messages WHERE id=p_message_id AND conversation_id=p_conversation_id AND private.can_read_message(id);
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='message_unavailable'; END IF;
 SELECT * INTO old FROM public.conversation_members WHERE conversation_id=p_conversation_id AND user_id=actor;
 UPDATE public.conversation_members SET last_read_seq=CASE WHEN p_read THEN greatest(last_read_seq,n) ELSE last_read_seq END,
 last_delivered_seq=greatest(last_delivered_seq,n) WHERE conversation_id=p_conversation_id AND user_id=actor RETURNING * INTO result;
 IF (old.last_read_seq,old.last_delivered_seq) IS DISTINCT FROM (result.last_read_seq,result.last_delivered_seq) THEN
  UPDATE public.conversations SET version=version+1 WHERE id=p_conversation_id;
  PERFORM private.c_event(p_conversation_id,'receipt');
 END IF;
 RETURN jsonb_build_object('last_read_seq',result.last_read_seq,'last_delivered_seq',result.last_delivered_seq);
END $$;
CREATE FUNCTION private.recall_message(p_message_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE cid uuid; actor uuid; m public.messages; a uuid;
BEGIN
 SELECT conversation_id INTO cid FROM public.messages WHERE id=p_message_id;
 actor:=private.c_lock(cid);
 SELECT * INTO m FROM public.messages WHERE id=p_message_id AND sender_id=actor AND private.can_read_message(id);
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='message_unavailable'; END IF;
 IF m.state='recalled' THEN RETURN private.c_message(m.id); END IF;
 IF m.state<>'sent' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='message_unavailable'; END IF;
 UPDATE public.messages SET body=NULL,state='recalled',recalled_at=statement_timestamp(),version=version+1 WHERE id=m.id;
 FOR a IN SELECT asset_id FROM public.message_media WHERE message_id=m.id ORDER BY asset_id LOOP PERFORM private.retire_asset(a); END LOOP;
 UPDATE public.conversations SET updated_at=statement_timestamp(),version=version+1 WHERE id=cid;
 PERFORM private.c_event(cid,'recall',NULL,m.id);
 RETURN private.c_message(m.id);
END $$;
CREATE FUNCTION private.hide_message_for_me(p_message_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid; cid uuid; changed integer;
BEGIN
 SELECT conversation_id INTO cid FROM public.messages WHERE id=p_message_id;
 actor:=private.c_lock(cid);
 IF EXISTS(SELECT 1 FROM public.message_hidden WHERE message_id=p_message_id AND user_id=actor) THEN RETURN jsonb_build_object('hidden',true); END IF;
 IF NOT private.can_read_message(p_message_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='message_unavailable'; END IF;
 INSERT INTO public.message_hidden(message_id,conversation_id,user_id) VALUES(p_message_id,cid,actor);
 UPDATE public.conversations SET version=version+1 WHERE id=cid;
 PERFORM private.c_event(cid,'hidden',ARRAY[actor]);
 RETURN jsonb_build_object('hidden',true);
END $$;
CREATE FUNCTION private.get_read_receipts(p_conversation_id uuid,p_message_id uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE n bigint;
BEGIN
 PERFORM private.b_active();
 SELECT seq INTO n FROM public.messages WHERE id=p_message_id AND conversation_id=p_conversation_id AND sender_id=auth.uid() AND private.can_read_message(id);
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='message_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',cm.user_id,'delivered',cm.last_delivered_seq>=n,'read',cm.last_read_seq>=n) ORDER BY cm.user_id)
 FROM public.conversation_members cm JOIN public.account_status s ON s.user_id=cm.user_id AND s.state='active'
 WHERE cm.conversation_id=p_conversation_id AND cm.state='active' AND cm.user_id<>auth.uid() AND cm.history_from_seq<=n AND NOT private.is_blocked(cm.user_id)),'[]'::jsonb);
END $$;
CREATE FUNCTION private.get_conversation_members(p_conversation_id uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',cm.user_id,'role',cm.role,'joined_at',cm.joined_at,
 'display_name',CASE WHEN private.can_read_profile(cm.user_id) THEN p.display_name END,
 'avatar_asset_id',CASE WHEN private.can_read_profile(cm.user_id) THEN p.avatar_asset_id END) ORDER BY cm.user_id)
 FROM public.conversation_members cm JOIN public.profiles p ON p.id=cm.user_id WHERE cm.conversation_id=p_conversation_id AND cm.state='active'),'[]'::jsonb);
END $$;
CREATE FUNCTION private.c_last_admin(cid uuid,target uuid) RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=cid AND user_id=target AND role='admin' AND state='active')
 AND NOT EXISTS(SELECT 1 FROM public.conversation_members cm JOIN public.account_status s ON s.user_id=cm.user_id AND s.state='active' JOIN auth.users u ON u.id=cm.user_id AND u.email_confirmed_at IS NOT NULL
 WHERE cm.conversation_id=cid AND cm.user_id<>target AND cm.role='admin' AND cm.state='active') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='last_active_admin'; END IF;
END $$;
CREATE FUNCTION private.manage_conversation_member(p_conversation_id uuid,p_target uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); cm public.conversation_members; c public.conversations; targets uuid[];
BEGIN
 SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id;
 IF c.kind<>'group' OR NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=actor AND role='admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='chat_admin_required'; END IF;
 IF p_target IS NULL OR p_target=actor OR p_action IS NULL OR p_action NOT IN ('add','remove','promote','demote') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_member_action'; END IF;
 SELECT array_agg(user_id) INTO targets FROM public.conversation_members WHERE conversation_id=c.id AND state='active';
 SELECT * INTO cm FROM public.conversation_members WHERE conversation_id=c.id AND user_id=p_target;
 IF p_action='add' THEN
  PERFORM private.c_invitee(actor,p_target);
  IF cm.state='active' THEN RETURN private.c_conversation(c.id); END IF;
  IF (SELECT count(*) FROM public.conversation_members WHERE conversation_id=c.id AND state='active')>=50 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='member_limit'; END IF;
  INSERT INTO public.conversation_members(conversation_id,user_id,history_from_seq) VALUES(c.id,p_target,c.next_seq+1)
  ON CONFLICT(conversation_id,user_id) DO UPDATE SET state='active',role='member',history_from_seq=excluded.history_from_seq,last_read_seq=0,last_delivered_seq=0,joined_at=statement_timestamp(),left_at=NULL;
  targets:=array_append(targets,p_target);
 ELSE
  IF cm.state IS DISTINCT FROM 'active' THEN
   IF p_action='remove' AND cm.state='removed' THEN RETURN private.c_conversation(c.id); END IF;
   RAISE SQLSTATE 'PT404' USING MESSAGE='member_unavailable'; END IF;
  IF (p_action='promote' AND cm.role='admin') OR (p_action='demote' AND cm.role='member') THEN RETURN private.c_conversation(c.id); END IF;
  IF p_action IN ('remove','demote') THEN PERFORM private.c_last_admin(c.id,p_target); END IF;
  IF p_action='promote' AND NOT EXISTS(SELECT 1 FROM public.account_status WHERE user_id=p_target AND state='active') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='member_unavailable'; END IF;
  UPDATE public.conversation_members SET state=CASE WHEN p_action='remove' THEN 'removed' ELSE state END,
   role=CASE WHEN p_action='promote' THEN 'admin' ELSE 'member' END,left_at=CASE WHEN p_action='remove' THEN statement_timestamp() ELSE left_at END WHERE conversation_id=c.id AND user_id=p_target;
 END IF;
 UPDATE public.conversations SET version=version+1,updated_at=statement_timestamp() WHERE id=c.id;
 PERFORM private.c_event(c.id,'membership',targets);
 RETURN private.c_conversation(c.id);
END $$;
CREATE FUNCTION private.leave_conversation(p_conversation_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); c public.conversations; targets uuid[];
BEGIN
 -- A repeated leave still checks identity/status but does not rejoin or restore history.
 IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=p_conversation_id AND user_id=actor AND state='left') THEN PERFORM private.lock_profile(); RETURN jsonb_build_object('left',true); END IF;
 actor:=private.c_lock(p_conversation_id); SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id;
 IF c.kind<>'group' THEN RAISE SQLSTATE 'PT422' USING MESSAGE='private_chat_leave_disabled'; END IF;
 PERFORM private.c_last_admin(c.id,actor);
 SELECT array_agg(user_id) INTO targets FROM public.conversation_members WHERE conversation_id=c.id AND state='active';
 UPDATE public.conversation_members SET state='left',role='member',left_at=statement_timestamp() WHERE conversation_id=c.id AND user_id=actor;
 UPDATE public.conversations SET version=version+1,updated_at=statement_timestamp() WHERE id=c.id;
 PERFORM private.c_event(c.id,'membership',targets);
 RETURN jsonb_build_object('left',true);
END $$;
CREATE FUNCTION private.update_conversation(p_conversation_id uuid,p_value text,p_expected_version bigint,p_state boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id,p_state); c public.conversations;
BEGIN
 SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id;
 IF c.kind<>'group' OR NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=actor AND role='admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='chat_admin_required'; END IF;
 IF p_value IS NULL OR (p_state AND p_value NOT IN ('active','archived')) OR (NOT p_state AND (length(btrim(p_value)) NOT BETWEEN 1 AND 150 OR p_value ~ '[[:cntrl:]]')) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_conversation_update'; END IF;
 IF (p_state AND c.state=p_value) OR (NOT p_state AND c.title=btrim(p_value)) THEN RETURN private.c_conversation(c.id); END IF;
 IF p_expected_version IS NULL OR c.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 UPDATE public.conversations SET title=CASE WHEN p_state THEN title ELSE btrim(p_value) END,state=CASE WHEN p_state THEN p_value ELSE state END,version=version+1,updated_at=statement_timestamp() WHERE id=c.id;
 PERFORM private.c_event(c.id,'state');
 RETURN private.c_conversation(c.id);
END $$;

-- Safe raw columns only. last_message_id is deliberately absent (per-viewer summary).
GRANT SELECT(id,kind,title,state,created_at,updated_at,version,next_seq) ON public.conversations TO authenticated;
GRANT SELECT(conversation_id,user_id,role,state,joined_at,last_read_seq,last_delivered_seq) ON public.conversation_members TO authenticated;
GRANT SELECT(id,conversation_id,seq,sender_id,kind,body,state,created_at,edited_at,recalled_at,version) ON public.messages TO authenticated;
GRANT SELECT ON public.message_hidden,public.message_media TO authenticated;
DROP POLICY conversation_members_select ON public.conversation_members;
CREATE POLICY conversation_members_select ON public.conversation_members FOR SELECT TO authenticated USING(private.can_read_conversation(conversation_id) AND state='active');

CREATE FUNCTION public.get_or_create_private(p_peer uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_or_create_private(p_peer) $$;
CREATE FUNCTION public.create_group_conversation(p_title text,p_members uuid[],p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_group_conversation(p_title,p_members,p_client_request_id) $$;
CREATE FUNCTION public.get_conversation(p_conversation_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_conversation(p_conversation_id) $$;
CREATE FUNCTION public.get_inbox(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20,p_include_archived boolean DEFAULT false) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_inbox(p_after_time,p_after_id,p_limit,p_include_archived) $$;
CREATE FUNCTION public.send_message(p_conversation_id uuid,p_client_request_id uuid,p_kind text,p_body text,p_assets uuid[],p_reply_to uuid DEFAULT NULL) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.send_message(p_conversation_id,p_client_request_id,p_kind,p_body,p_assets,p_reply_to) $$;
CREATE FUNCTION public.get_messages(p_conversation_id uuid,p_before_seq bigint DEFAULT NULL,p_after_seq bigint DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_messages(p_conversation_id,p_before_seq,p_after_seq,p_limit) $$;
CREATE FUNCTION public.search_messages(p_conversation_id uuid,p_query text,p_before_seq bigint DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_messages(p_conversation_id,p_before_seq,NULL,p_limit,p_query) $$;
CREATE FUNCTION public.mark_read(p_conversation_id uuid,p_message_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.c_mark(p_conversation_id,p_message_id,true) $$;
CREATE FUNCTION public.acknowledge_delivery(p_conversation_id uuid,p_message_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.c_mark(p_conversation_id,p_message_id,false) $$;
CREATE FUNCTION public.recall_message(p_message_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.recall_message(p_message_id) $$;
CREATE FUNCTION public.hide_message_for_me(p_message_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.hide_message_for_me(p_message_id) $$;
CREATE FUNCTION public.get_read_receipts(p_conversation_id uuid,p_message_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_read_receipts(p_conversation_id,p_message_id) $$;
CREATE FUNCTION public.get_conversation_members(p_conversation_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_conversation_members(p_conversation_id) $$;
CREATE FUNCTION public.manage_conversation_member(p_conversation_id uuid,p_target uuid,p_action text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.manage_conversation_member(p_conversation_id,p_target,p_action) $$;
CREATE FUNCTION public.leave_conversation(p_conversation_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.leave_conversation(p_conversation_id) $$;
CREATE FUNCTION public.rename_conversation(p_conversation_id uuid,p_title text,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_conversation(p_conversation_id,p_title,p_expected_version,false) $$;
CREATE FUNCTION public.set_conversation_state(p_conversation_id uuid,p_state text,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_conversation(p_conversation_id,p_state,p_expected_version,true) $$;
DO $$ DECLARE r record; BEGIN
 FOR r IN SELECT p.oid::regprocedure sig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private') AND p.proname IN
 ('get_or_create_private','create_group_conversation','get_conversation','get_inbox','send_message','get_messages','search_messages','c_mark','mark_read','acknowledge_delivery','recall_message','hide_message_for_me','get_read_receipts','get_conversation_members','manage_conversation_member','leave_conversation','update_conversation','rename_conversation','set_conversation_state') LOOP
  EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated',r.sig);
 END LOOP;
END $$;
NOTIFY pgrst,'reload schema';
