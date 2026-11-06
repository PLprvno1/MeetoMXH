-- Task 36 / 039. Requires 037-038; no Cloud deployment performed.
BEGIN;
CREATE FUNCTION public.task36_capabilities() RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN PERFORM private.b_active(); RETURN jsonb_build_object('version',CASE WHEN to_regprocedure('private.set_conversation_avatar(uuid,uuid)') IS NOT NULL THEN 36 ELSE 35 END,'follow_only',true,'consent_messaging',true); END $$;
REVOKE ALL ON FUNCTION public.task36_capabilities() FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.task36_capabilities() TO authenticated;
CREATE FUNCTION private.get_conversation_invite_candidates_excluded(p_conversation_id uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(user_id) FROM (
 SELECT user_id FROM public.conversation_members WHERE conversation_id=p_conversation_id AND state='active'
 UNION SELECT user_id FROM public.conversation_invitations WHERE conversation_id=p_conversation_id AND state IN ('pending','rejected'))q),'[]'::jsonb);
END $$;
CREATE FUNCTION public.get_conversation_invite_candidates_excluded(p_conversation_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_conversation_invite_candidates_excluded($1) $$;
REVOKE ALL ON FUNCTION private.get_conversation_invite_candidates_excluded(uuid),public.get_conversation_invite_candidates_excluded(uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION private.get_conversation_invite_candidates_excluded(uuid),public.get_conversation_invite_candidates_excluded(uuid) TO authenticated;

ALTER TABLE public.notifications DROP CONSTRAINT notifications_kind_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check CHECK(kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','mention','tag','follow_request','follow_accepted','message_request','group_invite'));
ALTER TABLE public.user_settings DROP CONSTRAINT user_settings_prefs_keys;
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys CHECK((notification_preferences-ARRAY['friend_request','friend_accepted','reaction','comment','reply','share','story','group','message','moderation','follow','mention','tag','follow_request','follow_accepted','message_request','group_invite'])='{}'::jsonb);

CREATE FUNCTION private.cancel_blocked_contact() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 UPDATE public.message_requests SET state='rejected',resolved_at=statement_timestamp() WHERE state='pending' AND
 ((sender_id=NEW.blocker_id AND recipient_id=NEW.blocked_id) OR (sender_id=NEW.blocked_id AND recipient_id=NEW.blocker_id));
 UPDATE public.conversation_invitations SET state='canceled',resolved_at=statement_timestamp() WHERE state='pending' AND
 ((invited_by=NEW.blocker_id AND user_id=NEW.blocked_id) OR (invited_by=NEW.blocked_id AND user_id=NEW.blocker_id));
 RETURN NEW;
END $$;
CREATE TRIGGER task36_cancel_blocked_contact AFTER INSERT ON public.user_blocks FOR EACH ROW EXECUTE FUNCTION private.cancel_blocked_contact();
CREATE FUNCTION private.notify_social_consent() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF TG_TABLE_NAME='follow_requests' THEN
  IF NEW.state='pending' THEN PERFORM private.emit_notification(NEW.recipient_id,NEW.requester_id,'follow_request','profile',NEW.requester_id,'follow-request:'||NEW.requester_id::text||':'||md5(NEW.created_at::text));
  ELSIF NEW.state='accepted' THEN PERFORM private.emit_notification(NEW.requester_id,NEW.recipient_id,'follow_accepted','profile',NEW.recipient_id,'follow-accepted:'||NEW.requester_id::text||':'||md5(NEW.created_at::text)); END IF;
 ELSIF TG_TABLE_NAME='message_requests' THEN
  IF NEW.state='pending' THEN PERFORM private.emit_notification(NEW.recipient_id,NEW.sender_id,'message_request','profile',NEW.sender_id,'message-request:'||NEW.sender_id::text||':'||NEW.client_request_id::text); END IF;
 ELSIF NEW.state='pending' THEN
  PERFORM private.emit_notification(NEW.user_id,NEW.invited_by,'group_invite','conversation',NEW.conversation_id,'chat-invite:'||NEW.conversation_id::text||':'||md5(NEW.created_at::text));
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER task36_notify_follow AFTER INSERT OR UPDATE OF state ON public.follow_requests FOR EACH ROW EXECUTE FUNCTION private.notify_social_consent();
CREATE TRIGGER task36_notify_message_request AFTER INSERT ON public.message_requests FOR EACH ROW EXECUTE FUNCTION private.notify_social_consent();
CREATE TRIGGER task36_notify_chat_invitation AFTER INSERT OR UPDATE OF state ON public.conversation_invitations FOR EACH ROW EXECUTE FUNCTION private.notify_social_consent();

CREATE OR REPLACE FUNCTION private.resolve_platform_report(p_report_id uuid,p_action text,p_reason text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.reports; action_id uuid; target uuid; owner_id uuid; old_state text; new_state text; previous public.moderation_actions; post_id uuid;
BEGIN
 IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 IF p_action IS NULL OR p_action NOT IN ('dismiss','warn','hide','remove','suspend') OR p_reason IS NULL OR length(btrim(p_reason)) NOT BETWEEN 1 AND 1000 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_moderation_action'; END IF;
 SELECT * INTO r FROM public.reports WHERE id=p_report_id FOR UPDATE;
 IF NOT FOUND OR (NOT private.has_role('system_admin') AND r.assigned_to IS DISTINCT FROM actor) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
 IF r.state IN ('resolved','dismissed') THEN
  SELECT * INTO previous FROM public.moderation_actions WHERE report_id=r.id ORDER BY created_at DESC LIMIT 1;
  IF previous.actor_id=actor AND previous.action=p_action AND previous.reason=btrim(p_reason) THEN RETURN jsonb_build_object('report_id',r.id,'state',r.state,'decision',p_action); END IF;
  RAISE SQLSTATE 'PT409' USING MESSAGE='report_resolved';
 END IF;
 target:=coalesce(r.profile_id,r.post_id,r.comment_id,r.story_id,r.message_id,r.group_id);
 IF r.target_kind='profile' THEN owner_id:=r.profile_id;
 ELSIF r.target_kind='post' THEN SELECT p.author_id,p.state INTO owner_id,old_state FROM public.posts p WHERE p.id=r.post_id FOR UPDATE;
 ELSIF r.target_kind='comment' THEN SELECT c.author_id,c.state,c.post_id INTO owner_id,old_state,post_id FROM public.comments c WHERE c.id=r.comment_id FOR UPDATE;
 ELSIF r.target_kind='story' THEN SELECT s.author_id,s.state INTO owner_id,old_state FROM public.stories s WHERE s.id=r.story_id FOR UPDATE;
 ELSIF r.target_kind='message' THEN SELECT m.sender_id,m.state INTO owner_id,old_state FROM public.messages m WHERE m.id=r.message_id FOR UPDATE;
 ELSE IF p_action NOT IN ('dismiss') THEN RAISE SQLSTATE 'PT410' USING MESSAGE='feature_retired'; END IF;
 END IF;
 IF p_action<>'dismiss' AND (owner_id=actor OR EXISTS(SELECT 1 FROM public.system_roles WHERE user_id=owner_id AND role='system_admin')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='protected_account'; END IF;
 IF p_action='suspend' THEN
  IF NOT private.has_role('system_admin') OR owner_id IS NULL THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  UPDATE public.account_status SET state='suspended',updated_at=statement_timestamp() WHERE user_id=owner_id AND state='active';
  IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='account_state_conflict'; END IF;
 ELSIF p_action IN ('hide','remove') THEN
  IF r.target_kind='post' THEN
   IF old_state IS NULL OR old_state='deleted' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='content_unavailable'; END IF;
   new_state:=CASE WHEN p_action='hide' THEN 'hidden' ELSE 'deleted' END;
   UPDATE public.posts SET state=new_state,version=version+1,updated_at=statement_timestamp(),deleted_at=CASE WHEN p_action='remove' THEN statement_timestamp() END WHERE id=r.post_id;
  ELSIF r.target_kind='comment' THEN
   IF old_state IS NULL OR old_state='deleted' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='content_unavailable'; END IF;
   new_state:=CASE WHEN p_action='hide' THEN 'hidden' ELSE 'deleted' END;
   UPDATE public.comments SET state=new_state,body=CASE WHEN p_action='remove' THEN NULL ELSE body END,version=version+1,updated_at=statement_timestamp() WHERE id=r.comment_id;
   IF old_state='published' THEN UPDATE public.posts SET comments_count=greatest(0,comments_count-1) WHERE id=post_id; END IF;
  ELSIF r.target_kind='story' AND p_action='remove' THEN UPDATE public.stories SET state='deleted',deleted_at=statement_timestamp() WHERE id=r.story_id;
  ELSIF r.target_kind='message' AND p_action='remove' THEN UPDATE public.messages SET state='removed',body=NULL,version=version+1 WHERE id=r.message_id;
  ELSE RAISE SQLSTATE 'PT422' USING MESSAGE='unsupported_target_action'; END IF;
 END IF;
 INSERT INTO public.moderation_actions(report_id,actor_id,client_request_id,action,reason) VALUES(r.id,actor,gen_random_uuid(),p_action,btrim(p_reason)) RETURNING id INTO action_id;
 UPDATE public.reports SET state=CASE WHEN p_action='dismiss' THEN 'dismissed' ELSE 'resolved' END,resolved_at=statement_timestamp() WHERE id=r.id;
 INSERT INTO public.moderation_audit(actor_id,action_id,report_id,operation,subject_type,subject_id,safe_metadata)
 VALUES(actor,action_id,r.id,'report_'||p_action,r.target_kind,target,jsonb_build_object('reason',btrim(p_reason),'decision',p_action,'old_state',old_state,'new_state',new_state));
 RETURN jsonb_build_object('report_id',r.id,'state',CASE WHEN p_action='dismiss' THEN 'dismissed' ELSE 'resolved' END,'decision',p_action);
END $$;
REVOKE ALL ON FUNCTION private.cancel_blocked_contact(),private.notify_social_consent() FROM PUBLIC,anon,authenticated,service_role;
-- Notification compatibility overrides appended below.

-- Task 36 notification projections and validators.
CREATE OR REPLACE FUNCTION private.admin_list_content(p_kind text,p_query text,p_state text,
  p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; result jsonb;
BEGIN
 IF p_kind='groups' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='feature_retired'; END IF;
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_kind IS NULL OR p_kind NOT IN ('posts','reels','comments','groups') OR p_query IS NULL OR char_length(p_query)>100
     OR p_state IS NOT NULL AND char_length(p_state)>30 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_admin_content_filter'; END IF;
  q:=lower(btrim(p_query));
  IF p_kind IN ('posts','reels') THEN
    SELECT coalesce(jsonb_agg(jsonb_build_object('id',x.id,'kind',p_kind,'author_id',x.author_id,
      'author_name',x.author_name,'group_id',x.group_id,'state',x.state,'body',x.body,'created_at',x.created_at,
      'is_reel',x.is_reel,'reactions_count',x.reactions_count,'comments_count',x.comments_count)
      ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb) INTO result
    FROM (SELECT p.id,p.author_id,pr.display_name author_name,p.group_id,p.state,p.body,p.created_at,
        coalesce(p.is_reel,false) is_reel,p.reactions_count,p.comments_count
      FROM public.posts p LEFT JOIN public.profiles pr ON pr.id=p.author_id
      WHERE (p_kind='reels')=coalesce(p.is_reel,false) AND (p_state IS NULL OR p.state=p_state)
        AND (q='' OR p.body ILIKE '%'||q||'%' OR coalesce(pr.display_name,'') ILIKE '%'||q||'%'
          OR p.id::text=q)
        AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
      ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) x;
  ELSIF p_kind='comments' THEN
    SELECT coalesce(jsonb_agg(jsonb_build_object('id',x.id,'kind','comments','post_id',x.post_id,
      'author_id',x.author_id,'author_name',x.author_name,'state',x.state,'body',x.body,'created_at',x.created_at)
      ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb) INTO result
    FROM (SELECT c.id,c.post_id,c.author_id,pr.display_name author_name,c.state,c.body,c.created_at
      FROM public.comments c LEFT JOIN public.profiles pr ON pr.id=c.author_id
      WHERE (p_state IS NULL OR c.state=p_state)
        AND (q='' OR coalesce(c.body,'') ILIKE '%'||q||'%' OR coalesce(pr.display_name,'') ILIKE '%'||q||'%' OR c.id::text=q)
        AND (p_after_time IS NULL OR (c.created_at,c.id)<(p_after_time,p_after_id))
      ORDER BY c.created_at DESC,c.id DESC LIMIT p_limit) x;
  ELSE
    SELECT coalesce(jsonb_agg(jsonb_build_object('id',x.id,'kind','groups','name',x.name,
      'created_by',x.created_by,'state',x.state,'privacy',x.privacy,'member_count',x.member_count,
      'created_at',x.created_at) ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb) INTO result
    FROM (SELECT g.id,g.name,g.created_by,g.state,g.privacy,g.member_count,g.created_at
      FROM public.groups g WHERE (p_state IS NULL OR g.state=p_state)
        AND (q='' OR g.name ILIKE '%'||q||'%' OR g.id::text=q)
        AND (p_after_time IS NULL OR (g.created_at,g.id)<(p_after_time,p_after_id))
      ORDER BY g.created_at DESC,g.id DESC LIMIT p_limit) x;
  END IF;
  RETURN coalesce(result,'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.admin_moderate_content(p_kind text,p_target_id uuid,p_action text,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); old_state text; new_state text; author_id uuid; action_id uuid;
  original_id uuid; post_kind text; comment_post_id uuid;
  existing public.moderation_actions; audit_row public.moderation_audit; asset_id uuid;
BEGIN
 IF p_kind='group' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='feature_retired'; END IF;
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  IF p_kind IS NULL OR p_kind NOT IN ('post','reel','comment','group') OR p_target_id IS NULL
     OR p_action IS NULL OR p_action NOT IN ('hide','remove','restore')
     OR p_reason IS NULL OR char_length(btrim(p_reason)) NOT BETWEEN 1 AND 1000 OR p_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_admin_moderation';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':admin-action:'||p_request_id::text,0));
  SELECT * INTO existing FROM public.moderation_actions WHERE actor_id=actor AND client_request_id=p_request_id;
  IF FOUND THEN
    SELECT * INTO audit_row FROM public.moderation_audit WHERE action_id=existing.id LIMIT 1;
    IF existing.action IS DISTINCT FROM p_action OR existing.reason IS DISTINCT FROM btrim(p_reason)
       OR audit_row.subject_type IS DISTINCT FROM p_kind OR audit_row.subject_id IS DISTINCT FROM p_target_id THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
    END IF;
    RETURN jsonb_build_object('id',p_target_id,'state',audit_row.safe_metadata->>'new_state','action_id',existing.id,'replayed',true);
  END IF;
  IF p_kind IN ('post','reel') THEN
    SELECT p.state,p.author_id,p.original_post_id,p.kind INTO old_state,author_id,original_id,post_kind FROM public.posts p WHERE p.id=p_target_id
      AND ((p_kind='reel' AND p.is_reel) OR (p_kind='post' AND NOT p.is_reel)) FOR UPDATE;
  ELSIF p_kind='comment' THEN
    SELECT c.state,c.author_id,c.post_id INTO old_state,author_id,comment_post_id FROM public.comments c WHERE c.id=p_target_id FOR UPDATE;
  ELSE
    SELECT g.state,g.created_by INTO old_state,author_id FROM public.groups g WHERE g.id=p_target_id FOR UPDATE;
  END IF;
  IF old_state IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
  IF author_id=actor OR EXISTS(SELECT 1 FROM public.system_roles WHERE user_id=author_id AND role='system_admin') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='protected_account';
  END IF;
  IF p_action='hide' THEN
    IF old_state NOT IN ('published','active') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='content_state_conflict'; END IF;
    new_state:=CASE WHEN p_kind='group' THEN 'archived' ELSE 'hidden' END;
  ELSIF p_action='remove' THEN
    IF old_state IN ('deleted','rejected') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='content_state_conflict'; END IF;
    new_state:='deleted';
  ELSE
    IF old_state NOT IN ('hidden','archived') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='content_state_conflict'; END IF;
    new_state:=CASE WHEN p_kind='group' THEN 'active' ELSE 'published' END;
  END IF;
  IF p_kind IN ('post','reel') THEN
    IF p_action='remove' THEN
      FOR asset_id IN SELECT pm.asset_id FROM public.post_media pm WHERE pm.post_id=p_target_id ORDER BY pm.asset_id LOOP
        PERFORM private.retire_asset(asset_id);
      END LOOP;
      UPDATE public.posts SET state=new_state,body='',deleted_at=statement_timestamp(),updated_at=statement_timestamp(),version=version+1 WHERE id=p_target_id;
      IF post_kind='share' AND original_id IS NOT NULL THEN
        UPDATE public.posts SET shares_count=greatest(0,shares_count-1) WHERE id=original_id;
      END IF;
    ELSE UPDATE public.posts SET state=new_state,deleted_at=NULL,updated_at=statement_timestamp(),version=version+1 WHERE id=p_target_id; END IF;
  ELSIF p_kind='comment' THEN
    UPDATE public.comments SET state=new_state,body=CASE WHEN p_action='remove' THEN NULL ELSE body END,
      updated_at=statement_timestamp(),version=version+1 WHERE id=p_target_id;
    IF old_state='published' AND new_state<>'published' THEN
      UPDATE public.posts SET comments_count=greatest(0,comments_count-1) WHERE id=comment_post_id;
    ELSIF old_state<>'published' AND new_state='published' THEN
      UPDATE public.posts SET comments_count=comments_count+1 WHERE id=comment_post_id;
    END IF;
  ELSE
    UPDATE public.groups SET state=new_state,updated_at=statement_timestamp(),version=version+1 WHERE id=p_target_id;
  END IF;
  INSERT INTO public.moderation_actions(report_id,actor_id,client_request_id,action,reason)
    VALUES(NULL,actor,p_request_id,p_action,btrim(p_reason)) RETURNING id INTO action_id;
  INSERT INTO public.moderation_audit(actor_id,action_id,operation,subject_type,subject_id,safe_metadata)
    VALUES(actor,action_id,'admin_content_'||p_action,p_kind,p_target_id,
      jsonb_build_object('old_state',old_state,'new_state',new_state,'reason',btrim(p_reason)));
  RETURN jsonb_build_object('id',p_target_id,'state',new_state,'action_id',action_id,'replayed',false);
END $$;
CREATE OR REPLACE FUNCTION private.emit_notification(p_recipient uuid,p_actor uuid,p_kind text,
  p_target_type text,p_target_id uuid,p_event_key text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE notification_id uuid;
BEGIN
  IF p_recipient IS NULL OR p_actor IS NULL OR p_recipient=p_actor OR p_target_id IS NULL
     OR p_kind NOT IN ('reaction','comment','reply','story','follow','follow_request','follow_accepted','message_request','group_invite','mention','tag','share','message','moderation')
     OR p_target_type NOT IN ('profile','post','comment','story','conversation')
     OR p_event_key IS NULL OR char_length(p_event_key)>240 THEN RETURN; END IF;
  IF private.is_blocked(p_recipient) OR NOT EXISTS (
    SELECT 1 FROM public.account_status s JOIN public.profiles p ON p.id=s.user_id
      WHERE s.user_id=p_recipient AND s.state='active' AND p.onboarding_completed) THEN RETURN; END IF;
  IF EXISTS (SELECT 1 FROM public.user_settings s WHERE s.user_id=p_recipient
      AND (NOT s.notifications_enabled OR coalesce((s.notification_preferences->>p_kind)::boolean,true)=false)) THEN RETURN; END IF;
  INSERT INTO public.notifications(recipient_id,actor_id,event_key,kind,target_type,target_id)
    VALUES(p_recipient,p_actor,p_event_key,p_kind,p_target_type,p_target_id)
    ON CONFLICT(recipient_id,event_key) DO NOTHING RETURNING id INTO notification_id;
  IF notification_id IS NOT NULL THEN
    PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',notification_id,'scope','notifications'),
      'invalidate','user:'||p_recipient::text,true);
  END IF;
END $$;
CREATE OR REPLACE FUNCTION private.get_notifications(p_unread_only boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_unread_only IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_notification_filter'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',q.id,'kind',q.kind,'target_type',q.target_type,
      'target_id',CASE WHEN private.notification_target_readable(q.target_type,q.target_id) THEN q.target_id END,
      'created_at',q.created_at,'read_at',q.read_at,
      'target_context_id',CASE WHEN q.target_type='comment' AND private.notification_target_readable(q.target_type,q.target_id)
        THEN (SELECT c.post_id FROM public.comments c WHERE c.id=q.target_id) END,
      'actor_id',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN q.actor_id END,
      'actor_name',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.display_name ELSE 'Meeto user' END,
      'actor_avatar_asset_id',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.avatar_asset_id END,
      'actor_avatar_preset',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.public_avatar_preset ELSE 'neutral' END,
      'target_available',private.notification_target_readable(q.target_type,q.target_id)) ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT n.* FROM public.notifications n WHERE n.recipient_id=actor AND n.kind NOT IN ('friend_request','friend_accepted','group','memories','marketplace')
      AND (NOT p_unread_only OR n.read_at IS NULL)
      AND (p_after_time IS NULL OR (n.created_at,n.id)<(p_after_time,p_after_id))
      ORDER BY n.created_at DESC,n.id DESC LIMIT p_limit) q
      LEFT JOIN public.profiles p ON p.id=q.actor_id),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.get_notification_unread_count() RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  RETURN (SELECT count(*) FROM public.notifications WHERE recipient_id=auth.uid() AND kind NOT IN ('friend_request','friend_accepted','group','memories','marketplace') AND read_at IS NULL);
END $$;
CREATE OR REPLACE FUNCTION private.notification_target_readable(p_type text,p_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT CASE p_type
    WHEN 'profile' THEN EXISTS(SELECT 1 FROM public.profiles p WHERE p.id=p_id AND private.can_read_profile(p.id))
    WHEN 'post' THEN private.can_read_post(p_id)
    WHEN 'comment' THEN private.can_read_comment(p_id)
    WHEN 'story' THEN private.can_read_story(p_id)
    WHEN 'conversation' THEN private.can_read_conversation(p_id) OR EXISTS(SELECT 1 FROM public.conversation_invitations i WHERE i.conversation_id=p_id AND i.user_id=auth.uid() AND i.state='pending' AND NOT private.is_blocked(i.invited_by))
    ELSE false END
$$;
CREATE OR REPLACE FUNCTION private.update_notification_settings(p_enabled boolean,p_preferences jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); merged jsonb;
BEGIN
  IF p_enabled IS NULL OR p_preferences IS NULL OR jsonb_typeof(p_preferences)<>'object'
     OR (p_preferences-ARRAY['follow_request','follow_accepted','message_request','group_invite','reaction','comment','reply','share','story','message','moderation','follow','mention','tag'])<>'{}'::jsonb
     OR jsonb_path_exists(p_preferences,'$.* ? (@.type() != "boolean")') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_notification_settings'; END IF;
  SELECT notification_preferences||p_preferences INTO merged FROM public.user_settings WHERE user_id=actor FOR UPDATE;
  UPDATE public.user_settings SET notifications_enabled=p_enabled,notification_preferences=merged,updated_at=statement_timestamp()
    WHERE user_id=actor;
  RETURN jsonb_build_object('enabled',p_enabled,'preferences',merged);
END $$;
NOTIFY pgrst,'reload schema';
COMMIT;
