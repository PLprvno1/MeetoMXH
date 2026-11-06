-- Task 36 / 038. Requires 037. Local preparation only, not deployed.
BEGIN;
ALTER TABLE public.conversations ADD COLUMN direct_consent text NOT NULL DEFAULT 'accepted' CHECK(direct_consent IN ('pending','accepted'));
-- Legacy auto-created chats did not record consent. Only two-way history proves it.
-- Preserve every message; require the current policy or recipient approval for other pairs.
UPDATE public.conversations c SET direct_consent='pending' WHERE c.kind='private' AND
 NOT (EXISTS(SELECT 1 FROM public.messages m WHERE m.conversation_id=c.id AND m.sender_id=c.direct_low)
 AND EXISTS(SELECT 1 FROM public.messages m WHERE m.conversation_id=c.id AND m.sender_id=c.direct_high));
ALTER TABLE public.conversations ADD COLUMN owner_id uuid REFERENCES public.profiles(id);
UPDATE public.conversations SET owner_id=created_by WHERE kind='group';
ALTER TABLE public.conversation_members ADD COLUMN archived_at timestamptz;
-- Preserve old archive intent per current member; restore normal server chat state.
UPDATE public.conversation_members m SET archived_at=statement_timestamp() FROM public.conversations c WHERE c.id=m.conversation_id AND c.state='archived';
UPDATE public.conversations SET state='active' WHERE state='archived';
CREATE TABLE public.message_requests (
 sender_id uuid NOT NULL REFERENCES public.profiles(id), recipient_id uuid NOT NULL REFERENCES public.profiles(id),
 body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 2000),
 story_id uuid REFERENCES public.stories(id),
 state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','accepted','rejected')),
 conversation_id uuid REFERENCES public.conversations(id),
 client_request_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(), resolved_at timestamptz,
 PRIMARY KEY(sender_id,recipient_id), CHECK(sender_id<>recipient_id)
);
CREATE TABLE public.conversation_invitations (
 conversation_id uuid NOT NULL REFERENCES public.conversations(id), user_id uuid NOT NULL REFERENCES public.profiles(id),
 invited_by uuid NOT NULL REFERENCES public.profiles(id),
 state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','accepted','rejected','canceled')),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(), resolved_at timestamptz,
 PRIMARY KEY(conversation_id,user_id)
);
ALTER TABLE public.message_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversation_invitations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.message_requests,public.conversation_invitations FROM PUBLIC,anon,authenticated,service_role;
CREATE INDEX message_requests_recipient ON public.message_requests(recipient_id,created_at DESC,sender_id) WHERE state='pending';
CREATE INDEX conversation_invitations_recipient ON public.conversation_invitations(user_id,created_at DESC,conversation_id) WHERE state='pending';

-- Only trusted policy-checked functions call open_direct. Never expose or grant it.
CREATE FUNCTION private.open_direct(p_peer uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); c public.conversations;
BEGIN
 PERFORM private.c_pair(actor,p_peer); PERFORM private.c_actor();
 IF actor=p_peer OR NOT private.social_target(actor) OR NOT private.social_target(p_peer) OR private.is_blocked(p_peer) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='peer_unavailable'; END IF;
 SELECT * INTO c FROM public.conversations WHERE kind='private' AND direct_low=least(actor,p_peer) AND direct_high=greatest(actor,p_peer) FOR UPDATE;
 IF NOT FOUND THEN
  INSERT INTO public.conversations(kind,direct_low,direct_high,created_by) VALUES('private',least(actor,p_peer),greatest(actor,p_peer),actor) RETURNING * INTO c;
  INSERT INTO public.conversation_members(conversation_id,user_id) VALUES(c.id,actor),(c.id,p_peer);
  PERFORM private.c_event(c.id,'created');
 END IF;
 IF (SELECT count(*) FROM public.conversation_members WHERE conversation_id=c.id AND user_id IN (actor,p_peer) AND state='active')<>2 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='private_conversation_invariant'; END IF;
 UPDATE public.conversations SET direct_consent='accepted' WHERE id=c.id;
 IF c.state<>'active' OR NOT private.can_read_conversation(c.id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN private.c_conversation(c.id);
END $$;
CREATE OR REPLACE FUNCTION private.get_or_create_private(p_peer uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); restricted boolean;
BEGIN
 IF p_peer IS NULL OR p_peer=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_peer'; END IF;
 PERFORM private.c_pair(actor,p_peer); PERFORM private.c_actor();
 IF NOT private.social_target(p_peer) OR private.is_blocked(p_peer) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='peer_unavailable'; END IF;
 IF EXISTS(SELECT 1 FROM public.conversations WHERE kind='private' AND direct_consent='accepted' AND direct_low=least(actor,p_peer) AND direct_high=greatest(actor,p_peer)) THEN RETURN private.open_direct(p_peer); END IF;
 SELECT account_private INTO restricted FROM public.user_settings WHERE user_id=p_peer FOR SHARE;
 IF restricted AND NOT private.follows(actor,p_peer) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='approved_follow_required'; END IF;
 IF NOT restricted AND NOT private.follows(p_peer,actor) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='message_request_required'; END IF;
 RETURN private.open_direct(p_peer);
END $$;
CREATE FUNCTION private.direct_contact_policy(p_peer uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE restricted boolean; cid uuid;
BEGIN
 PERFORM private.b_active();
 IF p_peer=auth.uid() OR NOT private.social_target(p_peer) OR private.is_blocked(p_peer) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='peer_unavailable'; END IF;
 SELECT id INTO cid FROM public.conversations c WHERE c.kind='private' AND c.direct_low=least(auth.uid(),p_peer) AND c.direct_high=greatest(auth.uid(),p_peer) AND private.can_read_conversation(c.id);
 IF cid IS NOT NULL THEN RETURN jsonb_build_object('policy','direct','conversation_id',cid); END IF;
 SELECT account_private INTO restricted FROM public.user_settings WHERE user_id=p_peer;
 RETURN jsonb_build_object('policy',CASE WHEN restricted AND NOT private.follows(auth.uid(),p_peer) THEN 'follow_required'
 WHEN restricted OR private.follows(p_peer,auth.uid()) THEN 'direct'
 WHEN EXISTS(SELECT 1 FROM public.message_requests WHERE sender_id=auth.uid() AND recipient_id=p_peer AND state='rejected') THEN 'request_rejected'
 WHEN EXISTS(SELECT 1 FROM public.message_requests WHERE sender_id=auth.uid() AND recipient_id=p_peer) THEN 'request_sent' ELSE 'request' END);
END $$;
CREATE FUNCTION private.send_message_request(p_peer uuid,p_body text,p_client_request_id uuid,p_story_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); r public.message_requests;
BEGIN
 PERFORM private.c_pair(actor,p_peer); PERFORM private.c_actor();
 IF p_peer=actor OR p_body IS NULL OR length(btrim(p_body)) NOT BETWEEN 1 AND 2000 OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message_request'; END IF;
 IF NOT private.social_target(p_peer) OR private.is_blocked(p_peer) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='peer_unavailable'; END IF;
 IF p_story_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.stories WHERE id=p_story_id AND author_id=p_peer AND private.can_read_story(id)) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 IF EXISTS(SELECT 1 FROM public.user_settings WHERE user_id=p_peer AND account_private) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='approved_follow_required'; END IF;
 SELECT * INTO r FROM public.message_requests WHERE sender_id=actor AND recipient_id=p_peer FOR UPDATE;
 IF FOUND THEN
  IF r.client_request_id=p_client_request_id AND r.body=btrim(p_body) AND r.story_id IS NOT DISTINCT FROM p_story_id THEN RETURN jsonb_build_object('state',r.state); END IF;
  RAISE SQLSTATE 'PT409' USING MESSAGE='message_request_already_sent';
 END IF;
 IF (private.direct_contact_policy(p_peer)->>'policy')<>'request' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='request_unavailable'; END IF;
 INSERT INTO public.message_requests(sender_id,recipient_id,body,client_request_id,story_id) VALUES(actor,p_peer,btrim(p_body),p_client_request_id,p_story_id);
 RETURN jsonb_build_object('state','pending');
END $$;
CREATE FUNCTION private.resolve_message_request(p_sender uuid,p_accept boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); r public.message_requests; c jsonb; mid uuid; seq bigint;
BEGIN
 PERFORM private.c_pair(actor,p_sender); PERFORM private.c_actor();
 IF p_sender IS NULL OR p_accept IS NULL OR private.is_blocked(p_sender) OR NOT private.social_target(p_sender) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='request_unavailable'; END IF;
 SELECT * INTO r FROM public.message_requests WHERE sender_id=p_sender AND recipient_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='request_unavailable'; END IF;
 IF r.state<>'pending' THEN RETURN jsonb_build_object('state',r.state,'conversation_id',r.conversation_id); END IF;
 IF p_accept THEN
  c:=private.open_direct(p_sender);
  UPDATE public.conversations SET next_seq=next_seq+1,updated_at=statement_timestamp(),version=version+1 WHERE id=(c->>'id')::uuid RETURNING next_seq INTO seq;
  INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,kind,body,reply_story_id)
   VALUES((c->>'id')::uuid,seq,p_sender,r.client_request_id,encode(sha256(convert_to(jsonb_build_array('request',p_sender,actor,r.body,r.story_id)::text,'UTF8')),'hex'),'text',r.body,r.story_id) RETURNING id INTO mid;
  UPDATE public.conversations SET last_message_id=mid WHERE id=(c->>'id')::uuid;
  PERFORM private.c_event((c->>'id')::uuid,'message',NULL,mid);
 END IF;
 UPDATE public.message_requests SET state=CASE WHEN p_accept THEN 'accepted' ELSE 'rejected' END,conversation_id=(c->>'id')::uuid,resolved_at=statement_timestamp()
 WHERE sender_id=p_sender AND recipient_id=actor;
 RETURN jsonb_build_object('state',CASE WHEN p_accept THEN 'accepted' ELSE 'rejected' END,'conversation_id',c->>'id');
END $$;
CREATE FUNCTION private.list_message_requests(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.profile_id DESC) FROM (
 SELECT p.id profile_id,p.display_name,p.avatar_asset_id,p.public_avatar_preset avatar_preset,r.body,r.story_id,r.created_at
 FROM public.message_requests r JOIN public.profiles p ON p.id=r.sender_id WHERE r.recipient_id=auth.uid() AND r.state='pending' AND private.can_read_profile(p.id)
 AND (p_after_time IS NULL OR (r.created_at,p.id)<(p_after_time,p_after_id)) ORDER BY r.created_at DESC,p.id DESC LIMIT p_limit)q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.c_invitee(actor uuid,target uuid) RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF actor<>auth.uid() OR target=actor OR NOT private.social_target(actor) OR NOT private.social_target(target) OR private.is_blocked(target) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invitee_unavailable'; END IF;
 IF EXISTS(SELECT 1 FROM public.user_settings WHERE user_id=target AND account_private) AND NOT private.follows(actor,target)
 AND NOT EXISTS(SELECT 1 FROM public.conversations c WHERE c.kind='private' AND c.direct_low=least(actor,target) AND c.direct_high=greatest(actor,target) AND private.can_read_conversation(c.id)) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='approved_follow_required'; END IF;
END $$;
CREATE OR REPLACE FUNCTION private.create_group_conversation(p_title text,p_members uuid[],p_client_request_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); c public.conversations; target uuid; h text;
BEGIN
 IF p_members IS NULL OR cardinality(p_members) NOT BETWEEN 2 AND 49 OR array_position(p_members,NULL) IS NOT NULL OR actor=ANY(p_members)
 OR p_client_request_id IS NULL OR p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 1 AND 150
 OR EXISTS(SELECT 1 FROM unnest(p_members)x GROUP BY x HAVING count(*)>1) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_chat'; END IF;
 SELECT array_agg(x ORDER BY x) INTO p_members FROM unnest(p_members)x;
 h:=encode(sha256(convert_to(jsonb_build_array(btrim(p_title),p_members)::text,'UTF8')),'hex');
 PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':group:'||p_client_request_id::text,0));
 SELECT * INTO c FROM public.conversations WHERE created_by=actor AND client_request_id=p_client_request_id;
 IF FOUND THEN
  IF NOT EXISTS(SELECT 1 FROM public.event_outbox WHERE event_key='chat-create:'||c.id::text AND payload->>'hash'=h) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF NOT private.can_read_conversation(c.id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
  RETURN private.c_conversation(c.id);
 END IF;
 FOREACH target IN ARRAY p_members LOOP PERFORM private.c_invitee(actor,target); END LOOP;
 INSERT INTO public.conversations(kind,created_by,owner_id,client_request_id,title) VALUES('group',actor,actor,p_client_request_id,btrim(p_title)) RETURNING * INTO c;
 INSERT INTO public.conversation_members(conversation_id,user_id,role) VALUES(c.id,actor,'admin');
 INSERT INTO public.conversation_invitations(conversation_id,user_id,invited_by) SELECT c.id,x,actor FROM unnest(p_members)x;
 INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id,payload,state,processed_at)
 VALUES('chat-create:'||c.id::text,'chat.create_receipt','conversation',c.id,jsonb_build_object('hash',h),'done',statement_timestamp());
 PERFORM private.c_event(c.id,'membership',array_append(p_members,actor));
 RETURN private.c_conversation(c.id);
END $$;
CREATE FUNCTION private.resolve_conversation_invitation(p_conversation_id uuid,p_accept boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); i public.conversation_invitations; c public.conversations;
BEGIN
 IF p_accept IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_invitation'; END IF;
 SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id AND kind='group' AND state='active' FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 SELECT * INTO i FROM public.conversation_invitations WHERE conversation_id=c.id AND user_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invitation_unavailable'; END IF;
 IF i.state<>'pending' THEN
  IF p_accept AND i.state='accepted' AND NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=actor AND state='active') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='membership_required'; END IF;
  RETURN jsonb_build_object('state',i.state);
 END IF;
 IF p_accept THEN
  IF NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=i.invited_by AND state='active' AND role='admin')
  OR EXISTS(SELECT 1 FROM public.conversation_members m WHERE m.conversation_id=c.id AND m.state='active' AND private.is_blocked(m.user_id)) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='invitation_unavailable'; END IF;
  IF EXISTS(SELECT 1 FROM public.user_settings WHERE user_id=actor AND account_private) AND NOT private.follows(i.invited_by,actor)
  AND NOT EXISTS(SELECT 1 FROM public.conversations d WHERE d.kind='private' AND d.direct_low=least(actor,i.invited_by) AND d.direct_high=greatest(actor,i.invited_by) AND private.can_read_conversation(d.id)) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='approved_follow_required'; END IF;
  IF (SELECT count(*) FROM public.conversation_members WHERE conversation_id=c.id AND state='active')>=50 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='member_limit'; END IF;
  INSERT INTO public.conversation_members(conversation_id,user_id,history_from_seq) VALUES(c.id,actor,1)
  ON CONFLICT(conversation_id,user_id) DO UPDATE SET state='active',role='member',history_from_seq=c.next_seq+1,last_read_seq=0,last_delivered_seq=0,joined_at=statement_timestamp(),left_at=NULL;
 END IF;
 UPDATE public.conversation_invitations SET state=CASE WHEN p_accept THEN 'accepted' ELSE 'rejected' END,resolved_at=statement_timestamp() WHERE conversation_id=c.id AND user_id=actor;
 PERFORM private.c_event(c.id,'membership',ARRAY[actor,i.invited_by]);
 RETURN jsonb_build_object('state',CASE WHEN p_accept THEN 'accepted' ELSE 'rejected' END,'conversation_id',c.id);
END $$;
CREATE FUNCTION private.list_conversation_invitations(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.conversation_id DESC) FROM (
 SELECT c.id conversation_id,c.title,p.display_name,i.created_at FROM public.conversation_invitations i JOIN public.conversations c ON c.id=i.conversation_id JOIN public.profiles p ON p.id=i.invited_by
 WHERE i.user_id=auth.uid() AND i.state='pending' AND c.state='active' AND NOT private.is_blocked(i.invited_by)
 AND (p_after_time IS NULL OR (i.created_at,c.id)<(p_after_time,p_after_id)) ORDER BY i.created_at DESC,c.id DESC LIMIT p_limit)q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.manage_conversation_member(p_conversation_id uuid,p_target uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); c public.conversations;
BEGIN
 SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id;
 IF c.kind<>'group' OR NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=actor AND state='active' AND role='admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='chat_admin_required'; END IF;
 IF p_target IS NULL OR p_target=actor OR p_action IS NULL OR p_action NOT IN ('add','remove','promote','demote') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_member_action'; END IF;
 IF p_target=c.owner_id THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_owner_protected'; END IF;
 IF p_action='add' THEN
  PERFORM private.c_invitee(actor,p_target);
  IF EXISTS(SELECT 1 FROM public.conversation_invitations WHERE conversation_id=c.id AND user_id=p_target AND state='rejected') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='invitation_rejected'; END IF;
  IF (SELECT count(*) FROM public.conversation_members WHERE conversation_id=c.id AND state='active')+(SELECT count(*) FROM public.conversation_invitations WHERE conversation_id=c.id AND state='pending')>=50 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='member_limit'; END IF;
  IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=p_target AND state='active') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='already_member'; END IF;
  INSERT INTO public.conversation_invitations(conversation_id,user_id,invited_by) VALUES(c.id,p_target,actor)
  ON CONFLICT(conversation_id,user_id) DO UPDATE SET state='pending',invited_by=actor,created_at=statement_timestamp(),resolved_at=NULL WHERE conversation_invitations.state IN ('accepted','canceled');
 ELSE
  IF NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=p_target AND state='active') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='member_unavailable'; END IF;
  IF p_action IN ('remove','demote') THEN PERFORM private.c_last_admin(c.id,p_target); END IF;
  UPDATE public.conversation_members SET state=CASE WHEN p_action='remove' THEN 'removed' ELSE state END,
   role=CASE WHEN p_action='promote' THEN 'admin' ELSE 'member' END,left_at=CASE WHEN p_action='remove' THEN statement_timestamp() ELSE left_at END WHERE conversation_id=c.id AND user_id=p_target;
 END IF;
 PERFORM private.c_event(c.id,'membership',ARRAY[actor,p_target]);
 RETURN private.c_conversation(c.id);
END $$;
CREATE OR REPLACE FUNCTION private.leave_conversation(p_conversation_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); c public.conversations; successor uuid; targets uuid[];
BEGIN
 SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id AND kind='group' FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=actor AND state='left') THEN RETURN jsonb_build_object('left',true); END IF;
 IF NOT private.can_read_conversation(c.id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='membership_required'; END IF;
 SELECT array_agg(user_id) INTO targets FROM public.conversation_members WHERE conversation_id=c.id AND state='active';
 IF c.owner_id=actor OR NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id<>actor AND state='active' AND role='admin') THEN
  SELECT user_id INTO successor FROM public.conversation_members WHERE conversation_id=c.id AND user_id<>actor AND state='active' AND private.social_target(user_id) ORDER BY (role='admin') DESC,joined_at,user_id LIMIT 1;
  IF successor IS NULL AND EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id<>actor AND state='active') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='ownership_transfer_required'; END IF;
  IF successor IS NOT NULL THEN UPDATE public.conversation_members SET role='admin' WHERE conversation_id=c.id AND user_id=successor; END IF;
  IF c.owner_id=actor THEN UPDATE public.conversations SET owner_id=successor WHERE id=c.id; END IF;
 END IF;
 UPDATE public.conversation_members SET state='left',role='member',left_at=statement_timestamp() WHERE conversation_id=c.id AND user_id=actor;
 UPDATE public.conversation_invitations SET state='canceled',resolved_at=statement_timestamp() WHERE conversation_id=c.id AND state='pending' AND invited_by=actor;
 UPDATE public.conversations SET version=version+1,updated_at=statement_timestamp() WHERE id=c.id;
 PERFORM private.c_event(c.id,'membership',targets);
 RETURN jsonb_build_object('left',true);
END $$;
CREATE FUNCTION private.set_conversation_archived(p_conversation_id uuid,p_archived boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id);
BEGIN
 IF p_archived IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_archive'; END IF;
 UPDATE public.conversation_members SET archived_at=CASE WHEN p_archived THEN statement_timestamp() END WHERE conversation_id=p_conversation_id AND user_id=actor;
 PERFORM private.c_event(p_conversation_id,'archive',ARRAY[actor]);
 RETURN private.c_conversation(p_conversation_id);
END $$;
-- New messages leave archives in place until the viewer explicitly unarchives (chosen policy).
-- This trigger also protects all send paths: text/media/share/story/forward and stale IDs.
CREATE FUNCTION private.enforce_message_contact() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF NEW.sender_id IS NOT NULL AND (NOT private.social_target(NEW.sender_id) OR EXISTS(SELECT 1 FROM public.conversation_members m JOIN public.system_roles r ON r.user_id=m.user_id AND r.role='system_admin' WHERE m.conversation_id=NEW.conversation_id AND m.state='active') OR EXISTS(SELECT 1 FROM public.conversations c WHERE c.id=NEW.conversation_id AND c.kind='private' AND c.direct_consent<>'accepted') OR NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=NEW.conversation_id AND user_id=NEW.sender_id AND state='active')
 OR EXISTS(SELECT 1 FROM public.conversation_members m JOIN public.user_blocks b ON (b.blocker_id=NEW.sender_id AND b.blocked_id=m.user_id) OR (b.blocked_id=NEW.sender_id AND b.blocker_id=m.user_id)
 WHERE m.conversation_id=NEW.conversation_id AND m.state='active')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='contact_blocked'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER task36_message_contact BEFORE INSERT ON public.messages FOR EACH ROW EXECUTE FUNCTION private.enforce_message_contact();
CREATE OR REPLACE FUNCTION private.reply_to_story(p_story_id uuid,p_client_request_id uuid,p_body text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); owner_id uuid; c jsonb; m jsonb; existing public.message_requests;
BEGIN
 IF p_body IS NULL OR length(btrim(p_body)) NOT BETWEEN 1 AND 2000 OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_reply'; END IF;
 SELECT author_id INTO owner_id FROM public.stories WHERE id=p_story_id AND private.can_read_story(id) FOR SHARE;
 IF NOT FOUND OR owner_id=actor THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 PERFORM private.c_pair(actor,owner_id);
 SELECT * INTO existing FROM public.message_requests WHERE sender_id=actor AND recipient_id=owner_id;
 IF FOUND AND existing.client_request_id=p_client_request_id THEN
  IF existing.body<>btrim(p_body) OR existing.story_id IS DISTINCT FROM p_story_id THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF existing.state='accepted' THEN RETURN (SELECT private.c_message(id) FROM public.messages WHERE conversation_id=existing.conversation_id AND sender_id=actor AND client_request_id=p_client_request_id); END IF;
  RETURN jsonb_build_object('request_state',existing.state);
 END IF;
 IF (private.direct_contact_policy(owner_id)->>'policy') IN ('request','request_sent') THEN
  m:=private.send_message_request(owner_id,p_body,p_client_request_id,p_story_id);
  RETURN jsonb_build_object('request_state',m->>'state');
 END IF;
 c:=private.get_or_create_private(owner_id);
 m:=private.send_message((c->>'id')::uuid,p_client_request_id,'text',btrim(p_body),'{}'::uuid[],NULL);
 IF EXISTS(SELECT 1 FROM public.messages WHERE id=(m->>'id')::uuid AND reply_story_id IS NOT NULL AND reply_story_id<>p_story_id) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
 UPDATE public.messages SET reply_story_id=p_story_id WHERE id=(m->>'id')::uuid;
 RETURN private.c_message((m->>'id')::uuid);
END $$;
-- RPC wrappers/grants and compatibility projections appended below.

-- Task 36 compatibility projections and request wrappers.
CREATE OR REPLACE FUNCTION private.c_actor() RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
 PERFORM 1 FROM auth.users WHERE id=actor AND email_confirmed_at IS NOT NULL FOR SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='email_verification_required'; END IF;
 PERFORM 1 FROM public.account_status WHERE user_id=actor AND state='active' FOR SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 PERFORM 1 FROM public.profiles WHERE id=actor AND onboarding_completed FOR KEY SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT403' USING MESSAGE='onboarding_required'; END IF;
 IF NOT private.social_target(actor) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='social_account_required'; END IF;
 RETURN actor;
END $$;
CREATE OR REPLACE FUNCTION private.can_read_conversation(cid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND private.social_target(auth.uid()) AND EXISTS(SELECT 1 FROM public.conversations c JOIN public.conversation_members m ON m.conversation_id=c.id
 WHERE c.id=cid AND c.state IN ('active','archived') AND m.user_id=auth.uid() AND m.state='active'
 AND (c.kind='group' OR (private.social_target(c.direct_low) AND private.social_target(c.direct_high) AND c.direct_consent='accepted' AND NOT private.is_blocked(c.direct_low) AND NOT private.is_blocked(c.direct_high)
  AND EXISTS(SELECT 1 FROM public.account_status s WHERE s.user_id=c.direct_low AND s.state='active')
  AND EXISTS(SELECT 1 FROM public.account_status s WHERE s.user_id=c.direct_high AND s.state='active'))));
$$;
CREATE OR REPLACE FUNCTION private.c_message(mid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',m.id,'conversation_id',m.conversation_id,'seq',m.seq,
   'sender_id',m.sender_id,'kind',m.kind,'body',CASE WHEN m.state='sent' THEN m.body END,
   'state',m.state,'version',m.version,'created_at',m.created_at,'edited_at',m.edited_at,'recalled_at',m.recalled_at,
   'reply_reference_present',m.reply_to_message_id IS NOT NULL,
   'reply_to_message_id',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN m.reply_to_message_id END,
   'reply',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN
     (SELECT jsonb_build_object('id',r.id,'seq',r.seq,'sender_id',r.sender_id,'state',r.state,
       'body',CASE WHEN r.state='sent' THEN r.body END,'kind',r.kind,
       'media',coalesce((SELECT jsonb_agg(jsonb_build_object('mime_type',ra.mime_type,
           'duration_ms',CASE WHEN ra.mime_type='audio/mp4' AND ra.metadata->>'validation'='audio_mp4_aac_lc_structural'
             AND coalesce(ra.metadata->>'duration_ms','') ~ '^[0-9]{1,6}$' THEN (ra.metadata->>'duration_ms')::integer END)
         ORDER BY rm.position) FROM public.message_media rm JOIN public.media_assets ra ON ra.id=rm.asset_id
         WHERE rm.message_id=r.id AND r.state='sent' AND private.can_read_media(ra.id)),'[]'::jsonb))
       FROM public.messages r WHERE r.id=m.reply_to_message_id) END,
   'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',mm.asset_id,'position',mm.position,
       'mime_type',a.mime_type,'duration_ms',CASE WHEN a.mime_type='audio/mp4'
         AND a.metadata->>'validation'='audio_mp4_aac_lc_structural'
         AND coalesce(a.metadata->>'duration_ms','') ~ '^[0-9]{1,6}$' THEN (a.metadata->>'duration_ms')::integer END)
       ORDER BY mm.position) FROM public.message_media mm JOIN public.media_assets a ON a.id=mm.asset_id
     WHERE mm.message_id=m.id AND m.state='sent' AND private.can_read_media(a.id)),'[]'::jsonb),
   'shared_post_present',m.state='sent' AND m.is_shared_post,
   'shared_post_available',m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL
     AND private.can_read_post(m.shared_post_id),
   'shared_post',CASE WHEN m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL
       AND private.can_read_post(m.shared_post_id) THEN private.post_json(m.shared_post_id) END,
   'forwarded',m.state='sent' AND m.forwarded,'reply_story_id',CASE WHEN m.state='sent' THEN m.reply_story_id END,'reply_story',CASE WHEN m.state='sent' AND private.can_read_story(m.reply_story_id) THEN (SELECT jsonb_build_object('id',s.id,'author_id',s.author_id,'caption',s.caption,'media_asset_id',s.media_asset_id) FROM public.stories s WHERE s.id=m.reply_story_id) END)
 FROM public.messages m WHERE m.id=mid AND private.can_read_message(m.id);
$$;
CREATE OR REPLACE FUNCTION private.c_conversation(cid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',c.id,'kind',c.kind,'title',c.title,'state',c.state,'created_at',c.created_at,'updated_at',c.updated_at,'version',c.version,
 'high_water',c.next_seq,'history_from_seq',cm.history_from_seq,'last_read_seq',cm.last_read_seq,'last_delivered_seq',cm.last_delivered_seq,'role',cm.role,'archived',cm.archived_at IS NOT NULL,'owner_id',c.owner_id,
 'last_message',(SELECT private.c_message(m.id) FROM public.messages m WHERE m.conversation_id=c.id AND private.can_read_message(m.id) ORDER BY m.seq DESC LIMIT 1),
 'unread_count',(SELECT count(*) FROM public.messages m WHERE m.conversation_id=c.id AND m.seq>cm.last_read_seq AND m.sender_id<>auth.uid() AND m.state='sent' AND private.can_read_message(m.id)))
 FROM public.conversations c JOIN public.conversation_members cm ON cm.conversation_id=c.id AND cm.user_id=auth.uid()
 WHERE c.id=cid AND private.can_read_conversation(c.id);
$$;
CREATE OR REPLACE FUNCTION private.get_inbox(p_after_time timestamptz,p_after_id uuid,p_limit integer,p_include_archived boolean) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_include_archived IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_archive_filter'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.c_conversation(q.id) ORDER BY q.updated_at DESC,q.id DESC) FROM (
 SELECT c.id,c.updated_at FROM public.conversations c WHERE private.can_read_conversation(c.id) AND (p_include_archived OR NOT EXISTS(SELECT 1 FROM public.conversation_members own WHERE own.conversation_id=c.id AND own.user_id=auth.uid() AND own.archived_at IS NOT NULL))
 AND (p_after_time IS NULL OR (c.updated_at,c.id)<(p_after_time,p_after_id)) ORDER BY c.updated_at DESC,c.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.search_conversations(p_query text,p_after_time timestamptz,
  p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; escaped text;
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 1 AND 80 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_conversation_search';
  END IF;
  q:=private.social_search_text(btrim(p_query));
  escaped:=replace(replace(replace(q,'!','!!'),'%','!%'),'_','!_');
  RETURN coalesce((SELECT jsonb_agg(private.c_conversation(x.id)
      ORDER BY x.updated_at DESC,x.id DESC)
    FROM (SELECT c.id,c.updated_at FROM public.conversations c
      WHERE private.can_read_conversation(c.id) AND (c.state='active' OR c.state='archived')
        AND (p_after_time IS NULL OR (c.updated_at,c.id)<(p_after_time,p_after_id))
        AND (private.social_search_text(coalesce(c.title,'')) LIKE '%'||escaped||'%' ESCAPE '!'
          OR (c.kind='private' AND EXISTS(SELECT 1 FROM public.conversation_members cm
            JOIN public.profiles p ON p.id=cm.user_id
            WHERE cm.conversation_id=c.id AND cm.state='active' AND cm.user_id<>auth.uid()
              AND private.can_read_profile(p.id)
              AND private.social_search_text(p.display_name) LIKE '%'||escaped||'%' ESCAPE '!')))
      ORDER BY c.updated_at DESC,c.id DESC LIMIT p_limit) x),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.search_conversations_v36(p_query text,p_after_time timestamptz,
  p_after_id uuid,p_limit integer,p_archived boolean) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; escaped text;
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 1 AND 80 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_conversation_search';
  END IF;
  q:=private.social_search_text(btrim(p_query));
  escaped:=replace(replace(replace(q,'!','!!'),'%','!%'),'_','!_');
  RETURN coalesce((SELECT jsonb_agg(private.c_conversation(x.id)
      ORDER BY x.updated_at DESC,x.id DESC)
    FROM (SELECT c.id,c.updated_at FROM public.conversations c
      WHERE private.can_read_conversation(c.id) AND (c.state='active' OR c.state='archived') AND EXISTS(SELECT 1 FROM public.conversation_members own WHERE own.conversation_id=c.id AND own.user_id=auth.uid() AND (own.archived_at IS NOT NULL)=p_archived)
        AND (p_after_time IS NULL OR (c.updated_at,c.id)<(p_after_time,p_after_id))
        AND (private.social_search_text(coalesce(c.title,'')) LIKE '%'||escaped||'%' ESCAPE '!'
          OR (c.kind='private' AND EXISTS(SELECT 1 FROM public.conversation_members cm
            JOIN public.profiles p ON p.id=cm.user_id
            WHERE cm.conversation_id=c.id AND cm.state='active' AND cm.user_id<>auth.uid()
              AND private.can_read_profile(p.id)
              AND private.social_search_text(p.display_name) LIKE '%'||escaped||'%' ESCAPE '!')))
      ORDER BY c.updated_at DESC,c.id DESC LIMIT p_limit) x),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.get_conversation_members(p_conversation_id uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',cm.user_id,'role',cm.role,'state',cm.state,'joined_at',cm.joined_at,
 'display_name',CASE WHEN private.can_read_profile(cm.user_id) THEN p.display_name END,
 'avatar_asset_id',CASE WHEN private.can_read_profile(cm.user_id) THEN p.avatar_asset_id END) ORDER BY cm.user_id)
 FROM public.conversation_members cm JOIN public.profiles p ON p.id=cm.user_id WHERE cm.conversation_id=p_conversation_id AND cm.state='active'),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.update_conversation(p_conversation_id uuid,p_value text,p_expected_version bigint,p_state boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id,p_state); c public.conversations;
BEGIN
 IF p_state THEN RAISE SQLSTATE 'PT410' USING MESSAGE='use_per_user_archive'; END IF;
 SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id;
 IF c.kind<>'group' OR NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=c.id AND user_id=actor AND role='admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='chat_admin_required'; END IF;
 IF p_value IS NULL OR (p_state AND p_value NOT IN ('active','archived')) OR (NOT p_state AND (length(btrim(p_value)) NOT BETWEEN 1 AND 150 OR p_value ~ '[[:cntrl:]]')) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_conversation_update'; END IF;
 IF (p_state AND c.state=p_value) OR (NOT p_state AND c.title=btrim(p_value)) THEN RETURN private.c_conversation(c.id); END IF;
 IF p_expected_version IS NULL OR c.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 UPDATE public.conversations SET title=CASE WHEN p_state THEN title ELSE btrim(p_value) END,state=CASE WHEN p_state THEN p_value ELSE state END,version=version+1,updated_at=statement_timestamp() WHERE id=c.id;
 PERFORM private.c_event(c.id,'state');
 RETURN private.c_conversation(c.id);
END $$;
CREATE FUNCTION public.direct_contact_policy(p_peer uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.direct_contact_policy($1) $$;
REVOKE ALL ON FUNCTION private.direct_contact_policy(uuid),public.direct_contact_policy(uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.direct_contact_policy(uuid),public.direct_contact_policy(uuid) TO authenticated;
CREATE FUNCTION public.send_message_request(p_peer uuid,p_body text,p_client_request_id uuid,p_story_id uuid DEFAULT NULL) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.send_message_request($1,$2,$3,$4) $$;
REVOKE ALL ON FUNCTION private.send_message_request(uuid,text,uuid,uuid),public.send_message_request(uuid,text,uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.send_message_request(uuid,text,uuid,uuid),public.send_message_request(uuid,text,uuid,uuid) TO authenticated;
CREATE FUNCTION public.resolve_message_request(p_sender uuid,p_accept boolean) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_message_request($1,$2) $$;
REVOKE ALL ON FUNCTION private.resolve_message_request(uuid,boolean),public.resolve_message_request(uuid,boolean) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.resolve_message_request(uuid,boolean),public.resolve_message_request(uuid,boolean) TO authenticated;
CREATE FUNCTION public.list_message_requests(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_message_requests($1,$2,$3) $$;
REVOKE ALL ON FUNCTION private.list_message_requests(timestamptz,uuid,integer),public.list_message_requests(timestamptz,uuid,integer) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.list_message_requests(timestamptz,uuid,integer),public.list_message_requests(timestamptz,uuid,integer) TO authenticated;
CREATE FUNCTION public.resolve_conversation_invitation(p_conversation_id uuid,p_accept boolean) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_conversation_invitation($1,$2) $$;
REVOKE ALL ON FUNCTION private.resolve_conversation_invitation(uuid,boolean),public.resolve_conversation_invitation(uuid,boolean) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.resolve_conversation_invitation(uuid,boolean),public.resolve_conversation_invitation(uuid,boolean) TO authenticated;
CREATE FUNCTION public.list_conversation_invitations(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_conversation_invitations($1,$2,$3) $$;
REVOKE ALL ON FUNCTION private.list_conversation_invitations(timestamptz,uuid,integer),public.list_conversation_invitations(timestamptz,uuid,integer) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.list_conversation_invitations(timestamptz,uuid,integer),public.list_conversation_invitations(timestamptz,uuid,integer) TO authenticated;
CREATE FUNCTION public.set_conversation_archived(p_conversation_id uuid,p_archived boolean) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_conversation_archived($1,$2) $$;
REVOKE ALL ON FUNCTION private.set_conversation_archived(uuid,boolean),public.set_conversation_archived(uuid,boolean) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.set_conversation_archived(uuid,boolean),public.set_conversation_archived(uuid,boolean) TO authenticated;
CREATE FUNCTION public.search_conversations_v36(p_query text,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30,p_archived boolean DEFAULT false) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_conversations_v36($1,$2,$3,$4,$5) $$;
REVOKE ALL ON FUNCTION private.search_conversations_v36(text,timestamptz,uuid,integer,boolean),public.search_conversations_v36(text,timestamptz,uuid,integer,boolean) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.search_conversations_v36(text,timestamptz,uuid,integer,boolean),public.search_conversations_v36(text,timestamptz,uuid,integer,boolean) TO authenticated;
REVOKE ALL ON FUNCTION private.open_direct(uuid),private.enforce_message_contact() FROM PUBLIC,anon,authenticated,service_role;
NOTIFY pgrst,'reload schema';
COMMIT;
