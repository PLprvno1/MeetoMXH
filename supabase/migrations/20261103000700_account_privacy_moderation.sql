-- Task 19 forward migration (025). Reuses the existing account, report and audit tables.
-- Does not depend on Community Groups migration 024.
DO $$
BEGIN
  IF to_regclass('public.user_settings') IS NULL OR to_regclass('public.profile_private') IS NULL
     OR to_regclass('public.user_blocks') IS NULL OR to_regclass('public.reports') IS NULL
     OR to_regclass('public.moderation_actions') IS NULL OR to_regclass('public.moderation_audit') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.posts') IS NULL
     OR to_regclass('public.comments') IS NULL OR to_regclass('public.stories') IS NULL
     OR to_regclass('public.messages') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_comment(uuid)') IS NULL
     OR to_regprocedure('private.can_read_story(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.can_review_report(uuid)') IS NULL
     OR to_regprocedure('private.has_role(text)') IS NULL
     OR to_regprocedure('private.require_identity()') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.set_block(uuid,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Task 19 prerequisites missing; verify migrations 001-021 and schema first';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.profile_private'::regclass
      AND attname='location_audience' AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Task 19 requires the profile location privacy field from migration 021';
  END IF;
END $$;

CREATE FUNCTION private.update_privacy_settings(p_profile_audience text,p_last_seen_audience text,
  p_location_audience text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
  changed integer;
BEGIN
  IF p_profile_audience IS NULL OR p_last_seen_audience IS NULL OR p_location_audience IS NULL
     OR p_profile_audience NOT IN ('public','friends','private')
     OR p_last_seen_audience NOT IN ('public','friends','private')
     OR p_location_audience NOT IN ('public','friends','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_privacy_settings';
  END IF;
  UPDATE public.user_settings SET profile_audience=p_profile_audience,
    last_seen_audience=p_last_seen_audience,updated_at=statement_timestamp() WHERE user_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='user_settings_unavailable'; END IF;
  UPDATE public.profile_private SET location_audience=p_location_audience,
    updated_at=statement_timestamp() WHERE user_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='profile_private_unavailable'; END IF;
  RETURN jsonb_build_object('profile_audience',p_profile_audience,
    'last_seen_audience',p_last_seen_audience,'location_audience',p_location_audience);
END $$;

CREATE FUNCTION private.list_blocked_users() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb;
BEGIN
  PERFORM private.b_active();
  SELECT coalesce(jsonb_agg(jsonb_build_object('user_id',b.blocked_id,'created_at',b.created_at)
    ORDER BY b.created_at DESC,b.blocked_id),'[]'::jsonb) INTO result
  FROM public.user_blocks b WHERE b.blocker_id=actor;
  RETURN result;
END $$;

CREATE FUNCTION private.request_account_deletion() RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); requested timestamptz; was_requested timestamptz;
BEGIN
  SELECT deletion_requested_at INTO was_requested FROM public.account_status WHERE user_id=actor FOR UPDATE;
  UPDATE public.account_status SET deletion_requested_at=coalesce(deletion_requested_at,statement_timestamp()),
    updated_at=statement_timestamp() WHERE user_id=actor RETURNING deletion_requested_at INTO requested;
  IF requested IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='account_status_unavailable'; END IF;
  IF was_requested IS NULL THEN
    INSERT INTO public.moderation_audit(actor_id,operation,subject_type,subject_id,safe_metadata)
      VALUES(actor,'account_deletion_requested','account',actor,'{}'::jsonb);
  END IF;
  RETURN jsonb_build_object('requested_at',requested,'state','pending_review');
END $$;

CREATE FUNCTION private.submit_content_report(p_target_type text,p_target_id uuid,p_reason text,
  p_details text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); report_id uuid; kind text; existing public.reports;
BEGIN
  IF p_target_type IS NULL OR p_target_type NOT IN ('profile','post','comment','story','message') OR p_target_id IS NULL
     OR p_reason IS NULL OR p_reason NOT IN ('spam','harassment','inappropriate','impersonation','misinformation','other')
     OR p_client_request_id IS NULL OR p_details IS NULL OR char_length(p_details)>1000 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_report';
  END IF;
  kind:=CASE p_target_type WHEN 'profile' THEN 'profile' WHEN 'post' THEN 'post'
    WHEN 'comment' THEN 'comment' WHEN 'story' THEN 'story' ELSE 'message' END;
  SELECT * INTO existing FROM public.reports WHERE reporter_id=actor AND client_request_id=p_client_request_id;
  IF FOUND THEN
    IF existing.target_kind IS DISTINCT FROM kind OR
       coalesce(existing.profile_id,existing.post_id,existing.comment_id,existing.story_id,existing.message_id) IS DISTINCT FROM p_target_id OR
       existing.reason_code IS DISTINCT FROM p_reason OR existing.details IS DISTINCT FROM btrim(p_details) THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
    END IF;
    RETURN jsonb_build_object('report_id',existing.id,'state','submitted');
  END IF;
  IF (kind='profile' AND (p_target_id=actor OR NOT EXISTS(SELECT 1 FROM public.profiles p
        WHERE p.id=p_target_id AND p.onboarding_completed AND private.can_read_profile(p.id))))
     OR (kind='post' AND NOT private.can_read_post(p_target_id))
     OR (kind='comment' AND NOT private.can_read_comment(p_target_id))
     OR (kind='story' AND NOT private.can_read_story(p_target_id))
     OR (kind='message' AND NOT private.can_read_message(p_target_id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='report_target_unavailable';
  END IF;
  SELECT id INTO report_id FROM public.reports WHERE reporter_id=actor AND target_kind=kind
    AND coalesce(profile_id,post_id,comment_id,story_id,message_id)=p_target_id
    AND reason_code=p_reason AND state IN ('open','assigned')
    AND created_at>statement_timestamp()-interval '24 hours' ORDER BY created_at DESC LIMIT 1;
  IF report_id IS NOT NULL THEN RETURN jsonb_build_object('report_id',report_id,'state','submitted'); END IF;
  INSERT INTO public.reports(reporter_id,client_request_id,target_kind,profile_id,post_id,comment_id,story_id,
    message_id,reason_code,details)
  VALUES(actor,p_client_request_id,kind,
    CASE WHEN kind='profile' THEN p_target_id END,CASE WHEN kind='post' THEN p_target_id END,
    CASE WHEN kind='comment' THEN p_target_id END,CASE WHEN kind='story' THEN p_target_id END,
    CASE WHEN kind='message' THEN p_target_id END,p_reason,left(btrim(p_details),1000))
  ON CONFLICT(reporter_id,client_request_id) DO NOTHING RETURNING id INTO report_id;
  IF report_id IS NULL THEN SELECT id INTO report_id FROM public.reports
    WHERE reporter_id=actor AND client_request_id=p_client_request_id; END IF;
  RETURN jsonb_build_object('report_id',report_id,'state','submitted');
END $$;

CREATE FUNCTION private.list_platform_reports(p_state text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_state IS NOT NULL AND p_state NOT IN ('open','assigned','resolved','dismissed') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_report_filter'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object('id',q.id,'target_kind',q.target_kind,
    'target_id',CASE WHEN private.has_role('system_admin') OR q.assigned_to=actor
      THEN coalesce(q.profile_id,q.post_id,q.comment_id,q.story_id,q.message_id,q.group_id) END,
    'reason_code',q.reason_code,'details',CASE WHEN private.has_role('system_admin') OR q.assigned_to=actor THEN q.details END,
    'state',q.state,'created_at',q.created_at,
    'assigned_to',q.assigned_to) ORDER BY q.created_at DESC,q.id DESC),'[]'::jsonb) INTO result
  FROM (SELECT r.* FROM public.reports r WHERE (p_state IS NULL OR r.state=p_state OR
      (p_state='open' AND r.state='assigned'))
    AND (private.has_role('system_admin') OR r.assigned_to=actor OR
      (r.state IN ('open','assigned') AND r.assigned_to IS NULL))
    AND (p_after_time IS NULL OR (r.created_at,r.id)<(p_after_time,p_after_id))
    ORDER BY r.created_at DESC,r.id DESC LIMIT least(p_limit,50)) q;
  RETURN result;
END $$;

CREATE FUNCTION private.claim_platform_report(p_report_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); report public.reports;
BEGIN
  IF p_report_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_report'; END IF;
  IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
  UPDATE public.reports SET assigned_to=actor,state='assigned'
    WHERE id=p_report_id AND state IN ('open','assigned') AND assigned_to IS NULL
    RETURNING * INTO report;
  IF FOUND THEN RETURN jsonb_build_object('report_id',report.id,'target_kind',report.target_kind,
    'target_id',coalesce(report.profile_id,report.post_id,report.comment_id,report.story_id,report.message_id,report.group_id),
    'reason_code',report.reason_code,'details',report.details,'state',report.state,'assigned_to',actor,'claimed',true); END IF;
  SELECT * INTO report FROM public.reports WHERE id=p_report_id AND state='assigned' AND assigned_to=actor;
  IF FOUND THEN RETURN jsonb_build_object('report_id',report.id,'target_kind',report.target_kind,
    'target_id',coalesce(report.profile_id,report.post_id,report.comment_id,report.story_id,report.message_id,report.group_id),
    'reason_code',report.reason_code,'details',report.details,'state',report.state,'assigned_to',actor,'claimed',true); END IF;
  RAISE SQLSTATE 'PT409' USING MESSAGE='report_already_claimed_or_resolved';
END $$;

CREATE FUNCTION private.resolve_platform_report(p_report_id uuid,p_action text,p_reason text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); report public.reports; action_id uuid;
BEGIN
  IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
  IF p_report_id IS NULL OR p_action IS NULL OR p_action NOT IN ('dismiss','warn') OR p_reason IS NULL
     OR char_length(btrim(p_reason)) NOT BETWEEN 1 AND 1000 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_moderation_action'; END IF;
  SELECT * INTO report FROM public.reports WHERE id=p_report_id FOR UPDATE;
  IF NOT FOUND OR (NOT private.has_role('system_admin') AND report.assigned_to<>actor) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
  IF report.state IN ('resolved','dismissed') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='report_resolved'; END IF;
  INSERT INTO public.moderation_actions(report_id,actor_id,client_request_id,action,reason)
    VALUES(report.id,actor,gen_random_uuid(),p_action,p_reason) RETURNING id INTO action_id;
  UPDATE public.reports SET state=CASE WHEN p_action='dismiss' THEN 'dismissed' ELSE 'resolved' END,
    resolved_at=statement_timestamp() WHERE id=report.id;
  INSERT INTO public.moderation_audit(actor_id,action_id,report_id,operation,subject_type,
    subject_id,safe_metadata) VALUES(actor,action_id,report.id,'report_'||p_action,report.target_kind,
    coalesce(report.profile_id,report.post_id,report.comment_id,report.story_id,report.message_id),
    jsonb_build_object('reason',left(btrim(p_reason),1000)));
  RETURN jsonb_build_object('report_id',report.id,'state',CASE WHEN p_action='dismiss' THEN 'dismissed' ELSE 'resolved' END);
END $$;

CREATE FUNCTION private.get_platform_audit(p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_limit'; END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object('id',q.id,'operation',q.operation,
      'subject_type',q.subject_type,'subject_id',q.subject_id,'created_at',q.created_at,
      'safe_metadata',q.safe_metadata) ORDER BY q.created_at DESC,q.id DESC),'[]'::jsonb) INTO result
  FROM (SELECT a.* FROM public.moderation_audit a WHERE private.has_role('system_admin')
      OR (a.report_id IS NOT NULL AND private.can_review_report(a.report_id))
    ORDER BY a.created_at DESC,a.id DESC LIMIT p_limit) q;
  RETURN result;
END $$;

CREATE FUNCTION public.update_privacy_settings(p_profile_audience text,p_last_seen_audience text,
  p_location_audience text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.update_privacy_settings($1,$2,$3) $$;
CREATE FUNCTION public.list_blocked_users() RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.list_blocked_users() $$;
CREATE FUNCTION public.request_account_deletion() RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.request_account_deletion() $$;
CREATE FUNCTION public.submit_content_report(p_target_type text,p_target_id uuid,p_reason text,
  p_details text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.submit_content_report($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.list_platform_reports(p_state text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.list_platform_reports($1,$2,$3,$4) $$;
CREATE FUNCTION public.claim_platform_report(p_report_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.claim_platform_report($1) $$;
CREATE FUNCTION public.resolve_platform_report(p_report_id uuid,p_action text,p_reason text) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_platform_report($1,$2,$3) $$;
CREATE FUNCTION public.get_platform_audit(p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.get_platform_audit($1) $$;

REVOKE ALL ON FUNCTION private.update_privacy_settings(text,text,text),private.list_blocked_users(),
  private.request_account_deletion(),private.submit_content_report(text,uuid,text,text,uuid),
  private.list_platform_reports(text,timestamptz,uuid,integer),private.claim_platform_report(uuid),private.resolve_platform_report(uuid,text,text),private.get_platform_audit(integer)
  FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.update_privacy_settings(text,text,text),public.list_blocked_users(),
  public.request_account_deletion(),public.submit_content_report(text,uuid,text,text,uuid),
  public.list_platform_reports(text,timestamptz,uuid,integer),public.claim_platform_report(uuid),public.resolve_platform_report(uuid,text,text),public.get_platform_audit(integer)
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.update_privacy_settings(text,text,text),public.list_blocked_users(),
  public.request_account_deletion(),public.submit_content_report(text,uuid,text,text,uuid),
  public.list_platform_reports(text,timestamptz,uuid,integer),public.claim_platform_report(uuid),public.resolve_platform_report(uuid,text,text),public.get_platform_audit(integer)
  TO authenticated;
GRANT EXECUTE ON FUNCTION private.update_privacy_settings(text,text,text),private.list_blocked_users(),
  private.request_account_deletion(),private.submit_content_report(text,uuid,text,text,uuid),
  private.list_platform_reports(text,timestamptz,uuid,integer),private.claim_platform_report(uuid),private.resolve_platform_report(uuid,text,text),private.get_platform_audit(integer)
  TO authenticated;
NOTIFY pgrst,'reload schema';
