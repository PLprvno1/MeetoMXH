-- Application uploads are authenticated standard POST only. Do not mint bearer upload capabilities.
CREATE POLICY meeto_standard_upload_only ON storage.objects AS RESTRICTIVE FOR INSERT TO authenticated
 WITH CHECK(bucket_id NOT IN ('profile-media','social-media','chat-media') OR storage.allow_any_operation(ARRAY['object.upload']));
-- An operative chat administrator must satisfy the same onboarding/confirmation gates as writers.
CREATE OR REPLACE FUNCTION private.c_last_admin(cid uuid,target uuid) RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=cid AND user_id=target AND role='admin' AND state='active')
 AND NOT EXISTS(SELECT 1 FROM public.conversation_members cm JOIN public.account_status s ON s.user_id=cm.user_id AND s.state='active' JOIN auth.users u ON u.id=cm.user_id AND u.email_confirmed_at IS NOT NULL JOIN public.profiles p ON p.id=cm.user_id AND p.onboarding_completed
 WHERE cm.conversation_id=cid AND cm.user_id<>target AND cm.role='admin' AND cm.state='active') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='last_active_admin'; END IF;
END $$;
CREATE OR REPLACE FUNCTION private.manage_conversation_member(p_conversation_id uuid,p_target uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
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
  IF p_action='promote' AND NOT EXISTS(SELECT 1 FROM public.account_status s JOIN public.profiles p ON p.id=s.user_id AND p.onboarding_completed JOIN auth.users u ON u.id=p.id AND u.email_confirmed_at IS NOT NULL WHERE s.user_id=p_target AND s.state='active') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='member_unavailable'; END IF;
  UPDATE public.conversation_members SET state=CASE WHEN p_action='remove' THEN 'removed' ELSE state END,
   role=CASE WHEN p_action='promote' THEN 'admin' ELSE 'member' END,left_at=CASE WHEN p_action='remove' THEN statement_timestamp() ELSE left_at END WHERE conversation_id=c.id AND user_id=p_target;
 END IF;
 UPDATE public.conversations SET version=version+1,updated_at=statement_timestamp() WHERE id=c.id;
 PERFORM private.c_event(c.id,'membership',targets);
 RETURN private.c_conversation(c.id);
END $$;