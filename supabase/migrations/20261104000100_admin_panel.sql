-- Task 28 / migration 035: system-admin dashboard and moderation RPCs.
-- Reuses system_roles, reports, moderation_actions and moderation_audit.
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.system_roles') IS NULL OR to_regclass('public.profiles') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.posts') IS NULL
     OR to_regclass('public.comments') IS NULL OR to_regclass('public.groups') IS NULL
     OR to_regclass('public.reports') IS NULL OR to_regclass('public.moderation_actions') IS NULL
     OR to_regclass('public.moderation_audit') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.has_role(text)') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.retire_asset(uuid)') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL THEN
    RAISE EXCEPTION 'Admin panel prerequisites missing; apply migrations 001-034 in order';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.posts'::regclass AND attname='is_reel' AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Admin panel requires Reels migration 034; apply migrations in order';
  END IF;
END $$;

-- Direct admin actions have no report case. The append-only moderation audit
-- remains the record of what happened; report-linked actions keep their FK.
ALTER TABLE public.moderation_actions ALTER COLUMN report_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS profiles_admin_page_idx ON public.profiles(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS posts_admin_page_idx ON public.posts(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS comments_admin_page_idx ON public.comments(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS groups_admin_page_idx ON public.groups(created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS moderation_audit_admin_page_idx ON public.moderation_audit(created_at DESC,id DESC);

CREATE FUNCTION private.admin_dashboard() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  SELECT jsonb_build_object(
    'users_total',(SELECT count(*) FROM public.profiles),
    'users_active',(SELECT count(*) FROM public.account_status WHERE state='active'),
    'posts_total',(SELECT count(*) FROM public.posts WHERE state<>'deleted' AND NOT coalesce(is_reel,false)),
    'reels_total',(SELECT count(*) FROM public.posts WHERE state<>'deleted' AND coalesce(is_reel,false)),
    'groups_total',(SELECT count(*) FROM public.groups WHERE state<>'deleted'),
    'reports_pending',(SELECT count(*) FROM public.reports WHERE state IN ('open','assigned')),
    'reports_resolved',(SELECT count(*) FROM public.reports WHERE state IN ('resolved','dismissed')),
    'recent_reports',(SELECT coalesce(jsonb_agg(x.payload ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb)
      FROM (SELECT jsonb_build_object('id',r.id,'target_kind',r.target_kind,'reason_code',r.reason_code,
        'state',r.state,'created_at',r.created_at,'assigned_to',r.assigned_to) payload,r.created_at,r.id
        FROM public.reports r ORDER BY r.created_at DESC,r.id DESC LIMIT 5) x),
    'recent_actions',(SELECT coalesce(jsonb_agg(x.payload ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb)
      FROM (SELECT jsonb_build_object('id',a.id,'operation',a.operation,'subject_type',a.subject_type,
        'subject_id',a.subject_id,'created_at',a.created_at,'safe_metadata',a.safe_metadata) payload,a.created_at,a.id
        FROM public.moderation_audit a ORDER BY a.created_at DESC,a.id DESC LIMIT 5) x)) INTO result;
  RETURN result;
END $$;

CREATE FUNCTION private.admin_search_users(p_query text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); q text; result jsonb;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_query IS NULL OR char_length(btrim(p_query))>100 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_admin_search'; END IF;
  q:=lower(btrim(p_query));
  SELECT coalesce(jsonb_agg(jsonb_build_object('user_id',x.id,'display_name',x.display_name,
      'avatar_asset_id',x.avatar_asset_id,'created_at',x.created_at,'account_state',coalesce(x.account_state,'active'),
      'suspended_until',x.suspended_until,'is_system_admin',x.is_system_admin)
      ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb) INTO result
  FROM (SELECT p.id,p.display_name,p.avatar_asset_id,p.created_at,s.state account_state,s.suspended_until,
      EXISTS(SELECT 1 FROM public.system_roles r WHERE r.user_id=p.id AND r.role='system_admin') is_system_admin
    FROM public.profiles p LEFT JOIN public.account_status s ON s.user_id=p.id
    WHERE (q='' OR lower(p.display_name) LIKE '%'||q||'%' OR p.id::text=q)
      AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
    ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) x;
  RETURN result;
END $$;

CREATE FUNCTION private.admin_list_content(p_kind text,p_query text,p_state text,
  p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; result jsonb;
BEGIN
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

CREATE FUNCTION private.admin_moderate_content(p_kind text,p_target_id uuid,p_action text,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); old_state text; new_state text; author_id uuid; action_id uuid;
  original_id uuid; post_kind text; comment_post_id uuid;
  existing public.moderation_actions; audit_row public.moderation_audit; asset_id uuid;
BEGIN
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
  IF author_id=actor AND p_action IN ('hide','remove') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='cannot_moderate_own_account_content'; END IF;
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

CREATE FUNCTION private.admin_set_account_status(p_user_id uuid,p_action text,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); old_state text; new_state text; action_name text; action_id uuid;
  existing public.moderation_actions; audit_row public.moderation_audit;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  IF p_user_id IS NULL OR p_user_id=actor OR p_action IS NULL OR p_action NOT IN ('suspend','restore')
     OR p_reason IS NULL OR char_length(btrim(p_reason)) NOT BETWEEN 1 AND 1000 OR p_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_account_action';
  END IF;
  IF EXISTS(SELECT 1 FROM public.system_roles WHERE user_id=p_user_id AND role='system_admin') THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='cannot_change_system_admin_status';
  END IF;
  -- The public API calls this operation "restore"; the existing moderation
  -- action constraint and audit vocabulary call it "unsuspend".
  action_name:=CASE p_action WHEN 'suspend' THEN 'suspend' ELSE 'unsuspend' END;
  PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':admin-action:'||p_request_id::text,0));
  SELECT * INTO existing FROM public.moderation_actions WHERE actor_id=actor AND client_request_id=p_request_id;
  IF FOUND THEN
    SELECT * INTO audit_row FROM public.moderation_audit WHERE action_id=existing.id LIMIT 1;
    IF existing.action IS DISTINCT FROM action_name OR existing.reason IS DISTINCT FROM btrim(p_reason)
       OR audit_row.subject_type IS DISTINCT FROM 'account' OR audit_row.subject_id IS DISTINCT FROM p_user_id THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
    END IF;
    RETURN jsonb_build_object('user_id',p_user_id,'state',audit_row.safe_metadata->>'new_state','replayed',true);
  END IF;
  SELECT state INTO old_state FROM public.account_status WHERE user_id=p_user_id FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='account_unavailable'; END IF;
  IF p_action='suspend' THEN
    IF old_state<>'active' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='account_state_conflict'; END IF;
    new_state:='suspended';
  ELSE
    IF old_state<>'suspended' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='account_state_conflict'; END IF;
    new_state:='active';
  END IF;
  UPDATE public.account_status SET state=new_state,suspended_until=NULL,updated_at=statement_timestamp() WHERE user_id=p_user_id;
  INSERT INTO public.moderation_actions(report_id,actor_id,client_request_id,action,reason)
    VALUES(NULL,actor,p_request_id,action_name,btrim(p_reason)) RETURNING id INTO action_id;
  INSERT INTO public.moderation_audit(actor_id,action_id,operation,subject_type,subject_id,safe_metadata)
    VALUES(actor,action_id,'admin_account_'||action_name,'account',p_user_id,
      jsonb_build_object('old_state',old_state,'new_state',new_state,'reason',btrim(p_reason)));
  RETURN jsonb_build_object('user_id',p_user_id,'state',new_state,'action_id',action_id,'replayed',false);
END $$;

CREATE FUNCTION private.admin_report_detail(p_report_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); r public.reports; result jsonb; target uuid;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
  SELECT * INTO r FROM public.reports WHERE id=p_report_id;
  IF NOT FOUND OR (NOT private.has_role('system_admin') AND r.assigned_to IS DISTINCT FROM actor) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
  target:=coalesce(r.profile_id,r.post_id,r.comment_id,r.story_id,r.message_id,r.group_id);
  result:=jsonb_build_object('id',r.id,'target_kind',r.target_kind,'target_id',target,'reason_code',r.reason_code,
    'details',r.details,'state',r.state,'created_at',r.created_at,'assigned_to',r.assigned_to,
    'target',CASE r.target_kind
      WHEN 'post' THEN (SELECT jsonb_build_object('id',p.id,'kind',CASE WHEN coalesce(p.is_reel,false) THEN 'reel' ELSE 'post' END,
        'state',p.state,'body',p.body,'author_name',pr.display_name,'created_at',p.created_at)
        FROM public.posts p LEFT JOIN public.profiles pr ON pr.id=p.author_id WHERE p.id=target)
      WHEN 'comment' THEN (SELECT jsonb_build_object('id',c.id,'kind','comment','state',c.state,'body',c.body,
        'author_name',pr.display_name,'created_at',c.created_at,'post_id',c.post_id)
        FROM public.comments c LEFT JOIN public.profiles pr ON pr.id=c.author_id WHERE c.id=target)
      WHEN 'group' THEN (SELECT jsonb_build_object('id',g.id,'kind','group','state',g.state,'name',g.name,
        'description',g.description,'created_at',g.created_at) FROM public.groups g WHERE g.id=target)
      WHEN 'profile' THEN (SELECT jsonb_build_object('id',p.id,'kind','profile','display_name',p.display_name,
        'account_state',s.state) FROM public.profiles p LEFT JOIN public.account_status s ON s.user_id=p.id WHERE p.id=target)
      WHEN 'story' THEN (SELECT jsonb_build_object('id',s.id,'kind','story','state',s.state,'caption',s.caption,
        'created_at',s.created_at) FROM public.stories s WHERE s.id=target)
      ELSE jsonb_build_object('id',target,'kind','message','preview','Nội dung tin nhắn được bảo vệ; dùng moderation report workflow.') END);
  RETURN result;
END $$;

CREATE FUNCTION private.admin_audit_page(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  SELECT coalesce(jsonb_agg(jsonb_build_object('id',x.id,'operation',x.operation,'subject_type',x.subject_type,
    'subject_id',x.subject_id,'created_at',x.created_at,'safe_metadata',x.safe_metadata)
    ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb) INTO result
  FROM (SELECT a.id,a.operation,a.subject_type,a.subject_id,a.created_at,a.safe_metadata
    FROM public.moderation_audit a WHERE p_after_time IS NULL OR (a.created_at,a.id)<(p_after_time,p_after_id)
    ORDER BY a.created_at DESC,a.id DESC LIMIT p_limit) x;
  RETURN result;
END $$;

CREATE FUNCTION public.admin_dashboard() RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.admin_dashboard() $$;
CREATE FUNCTION public.admin_search_users(p_query text,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.admin_search_users($1,$2,$3,$4) $$;
CREATE FUNCTION public.admin_list_content(p_kind text,p_query text DEFAULT '',p_state text DEFAULT NULL,
  p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.admin_list_content($1,$2,$3,$4,$5,$6) $$;
CREATE FUNCTION public.admin_moderate_content(p_kind text,p_target_id uuid,p_action text,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.admin_moderate_content($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.admin_set_account_status(p_user_id uuid,p_action text,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.admin_set_account_status($1,$2,$3,$4) $$;
CREATE FUNCTION public.admin_report_detail(p_report_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.admin_report_detail($1) $$;
CREATE FUNCTION public.admin_audit_page(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.admin_audit_page($1,$2,$3) $$;

REVOKE ALL ON FUNCTION private.admin_dashboard(),private.admin_search_users(text,timestamptz,uuid,integer),
  private.admin_list_content(text,text,text,timestamptz,uuid,integer),
  private.admin_moderate_content(text,uuid,text,text,uuid),private.admin_set_account_status(uuid,text,text,uuid),
  private.admin_report_detail(uuid),private.admin_audit_page(timestamptz,uuid,integer)
  FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.admin_dashboard(),private.admin_search_users(text,timestamptz,uuid,integer),
  private.admin_list_content(text,text,text,timestamptz,uuid,integer),
  private.admin_moderate_content(text,uuid,text,text,uuid),private.admin_set_account_status(uuid,text,text,uuid),
  private.admin_report_detail(uuid),private.admin_audit_page(timestamptz,uuid,integer) TO authenticated;
REVOKE ALL ON FUNCTION public.admin_dashboard(),public.admin_search_users(text,timestamptz,uuid,integer),
  public.admin_list_content(text,text,text,timestamptz,uuid,integer),public.admin_moderate_content(text,uuid,text,text,uuid),
  public.admin_set_account_status(uuid,text,text,uuid),public.admin_report_detail(uuid),public.admin_audit_page(timestamptz,uuid,integer)
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.admin_dashboard(),public.admin_search_users(text,timestamptz,uuid,integer),
  public.admin_list_content(text,text,text,timestamptz,uuid,integer),public.admin_moderate_content(text,uuid,text,text,uuid),
  public.admin_set_account_status(uuid,text,text,uuid),public.admin_report_detail(uuid),public.admin_audit_page(timestamptz,uuid,integer)
  TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
