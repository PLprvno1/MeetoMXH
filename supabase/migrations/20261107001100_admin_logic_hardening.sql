-- Task 54B. Forward-only; owner deploys after catalog/ledger review.
-- Amended by 54B.1 for FIRST deployment only. An installed 54B requires a new migration.
-- Evidence is held indefinitely pending an approved retention/release policy.
BEGIN;
DO $$ DECLARE installed boolean; BEGIN
 IF current_setting('server_version_num')::integer<120000 THEN
  RAISE EXCEPTION '54B requires PostgreSQL 12+ for MATERIALIZED CTEs'; END IF;
 IF to_regprocedure('pg_catalog.gen_random_uuid()') IS NULL THEN
  RAISE EXCEPTION '54B requires pg_catalog.gen_random_uuid() for empty-search-path mutation calls'; END IF;
 IF to_regclass('supabase_migrations.schema_migrations') IS NOT NULL THEN
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM supabase_migrations.schema_migrations WHERE version=$1)'
  INTO installed USING '20261107001100';
  IF installed THEN RAISE EXCEPTION '54B is already installed; use a new forward-only fix migration'; END IF;
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM supabase_migrations.schema_migrations WHERE version=$1)'
   INTO installed USING '20261107001050';
  IF NOT installed THEN RAISE EXCEPTION 'Apply 20261107001050 media cleanup recovery before Admin 54B'; END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname IN ('public','private') AND p.proname IN
 ('admin_get_capabilities','admin_target','admin_permitted_actions','admin_transition','admin_commit_action',
  'admin_decide_report_v2','admin_moderate_subject_v2','admin_action_receipt','admin_reassign_report','admin_claim_report_v2',
  'admin_reports_page_v2','admin_audit_page_v2','admin_report_detail_v2','admin_subject_detail_v2',
  'admin_users_page_v2','admin_content_page_v2','admin_dashboard_v2','protect_moderation_media_hold'))
 OR to_regclass('private.admin_action_receipts') IS NOT NULL
 OR to_regclass('private.moderation_media_holds') IS NOT NULL
 OR to_regclass('private.moderation_text_evidence') IS NOT NULL THEN
  RAISE EXCEPTION '54B objects already exist; inspect ledger/drift and use a new migration if deployed';
 END IF;
 IF to_regprocedure('private.admin_moderate_content(text,uuid,text,text,uuid)') IS NULL
 OR to_regprocedure('private.admin_set_account_status(uuid,text,text,uuid)') IS NULL
 OR to_regprocedure('private.admin_report_detail(uuid)') IS NULL
 OR to_regprocedure('private.schedule_media_cleanup()') IS NULL
 OR to_regprocedure('private.retire_asset(uuid)') IS NULL
 OR to_regprocedure('private.can_review_report_media(uuid)') IS NULL
 OR to_regprocedure('private.b_active()') IS NULL
 OR to_regprocedure('private.b_actor()') IS NULL
 OR to_regprocedure('private.require_identity()') IS NULL
 OR to_regprocedure('private.is_active()') IS NULL
 OR to_regprocedure('private.has_role(text)') IS NULL
 OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
 OR to_regprocedure('private.admin_dashboard()') IS NULL
 OR to_regprocedure('private.admin_list_content(text,text,text,timestamptz,uuid,integer)') IS NULL
 OR to_regprocedure('private.can_review_report(uuid)') IS NULL
 OR to_regprocedure('private.task43_asset_live(uuid)') IS NULL
 OR to_regprocedure('private.meeto_cleanup_asset_blocked(uuid)') IS NULL
 OR to_regprocedure('private.meeto_reap_exhausted_cleanup(uuid[],uuid,integer,text)') IS NULL
 OR to_regclass('private.media_cleanup_attempt_audit') IS NULL
 OR to_regclass('private.media_cleanup_recovery_log') IS NULL
 OR to_regclass('public.moderation_audit') IS NULL
 OR to_regclass('public.moderation_actions') IS NULL
 OR to_regclass('public.event_outbox') IS NULL OR to_regclass('cron.job') IS NULL THEN
 RAISE EXCEPTION 'Task54B requires Admin 040001/040002, moderation 060003/060004 and Reel reference graph 060009; review live definitions first';
 END IF;
 IF EXISTS(SELECT 1 FROM (VALUES
 ('public.admin_moderate_content(text,uuid,text,text,uuid)'),
 ('public.admin_set_account_status(uuid,text,text,uuid)'),('public.admin_report_detail(uuid)'),
 ('public.claim_platform_report(uuid)'),('private.claim_platform_report(uuid)'),
 ('public.resolve_platform_report(uuid,text,text)'),('private.resolve_platform_report(uuid,text,text)'),
 ('private.lock_profile(boolean)')) required(signature) WHERE to_regprocedure(signature) IS NULL) THEN
 RAISE EXCEPTION '54B requires exact legacy wrapper/writer signatures'; END IF;
 IF EXISTS(SELECT 1 FROM (VALUES
 ('public','profiles',ARRAY['id','display_name','onboarding_completed','avatar_asset_id','cover_asset_id','created_at']),
 ('public','conversations',ARRAY['id','avatar_asset_id','state']),
 ('public','profile_album_items',ARRAY['asset_id','source_post_id']),
 ('public','account_status',ARRAY['user_id','state','updated_at','suspended_until','deletion_requested_at']),
 ('public','system_roles',ARRAY['user_id','role']),
 ('public','posts',ARRAY['id','kind','is_reel','author_id','body','state','created_at','updated_at','deleted_at','version',
 'original_post_id','shares_count','comments_count','reel_cover_asset_id']),
 ('public','comments',ARRAY['id','post_id','author_id','parent_id','body','state','version','created_at','updated_at']),
 ('public','stories',ARRAY['id','author_id','media_asset_id','caption','state','created_at','expires_at','deleted_at']),
 ('public','messages',ARRAY['id','sender_id','body','state','version','created_at']),
 ('public','reports',ARRAY['id','post_id','comment_id','story_id','message_id','profile_id','group_id','target_kind',
 'assigned_to','reason_code','details','state','created_at','resolved_at']),
 ('public','moderation_actions',ARRAY['id','report_id','actor_id','client_request_id','action','reason','created_at']),
 ('public','moderation_audit',ARRAY['id','actor_id','action_id','report_id','operation','subject_type','subject_id','safe_metadata','created_at']),
 ('public','media_assets',ARRAY['id','owner_id','bucket_id','object_path','mime_type','purpose','state','created_at','delete_after']),
 ('public','post_media',ARRAY['post_id','asset_id']),('public','message_media',ARRAY['message_id','asset_id']),
 ('public','event_outbox',ARRAY['event_key','event_type','aggregate_type','aggregate_id','state']),
 ('auth','users',ARRAY['id','email_confirmed_at'])
 ) needed(table_schema,table_name,column_names) CROSS JOIN LATERAL unnest(needed.column_names) AS needed_column(column_name)
 WHERE NOT EXISTS(SELECT 1 FROM information_schema.columns c
 WHERE c.table_schema=needed.table_schema AND c.table_name=needed.table_name AND c.column_name=needed_column.column_name)) THEN
 RAISE EXCEPTION 'Task54B requires canonical Post/Reel, comment, message, story and account columns'; END IF;
 -- Existing SECURITY DEFINER bodies keep their owner on CREATE OR REPLACE.
 -- Mixed owners could lose EXECUTE on the newly owner-only helpers. Do not silently re-own functions.
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='private' AND p.proname IN ('admin_moderate_content','admin_set_account_status','admin_report_detail',
 'claim_platform_report','resolve_platform_report','retire_asset','schedule_media_cleanup')
 AND pg_get_userbyid(p.proowner)<>current_user) THEN
  RAISE EXCEPTION '54B replacement function owner differs from migration role; owner review required'; END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname IN ('public','private') AND p.proname IN ('admin_moderate_content','admin_set_account_status','admin_report_detail',
 'claim_platform_report','resolve_platform_report','retire_asset','schedule_media_cleanup')
 GROUP BY n.nspname,p.proname HAVING count(*)<>1) THEN
  RAISE EXCEPTION 'Unexpected legacy Admin overloads; inspect signatures before deployment'; END IF;
END $$;

-- Serialize rollout with existing writers/cleanup. Operator must pause and drain
-- workers first: a SQL lock cannot recall an already-issued Storage DELETE.
SELECT pg_advisory_xact_lock(6061101);
LOCK TABLE public.media_assets,public.event_outbox IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
 IF (SELECT count(*) FROM cron.job WHERE jobname='meeto-media-cleanup-every-five-minutes' AND NOT active)<>1
 OR EXISTS(SELECT 1 FROM private.worker_invocations w WHERE w.singleton AND w.completed_at IS NULL AND
   ((w.consumed_at IS NULL AND w.expires_at>statement_timestamp()) OR
    (w.consumed_at IS NOT NULL AND coalesce(w.execution_until,w.expires_at)>statement_timestamp()))) THEN
  RAISE EXCEPTION 'Keep the shared cleanup schedule paused and drain worker tokens before Task54B'; END IF;
 IF EXISTS(SELECT 1 FROM public.event_outbox WHERE event_type='media.retire' AND state='processing') THEN
  RAISE EXCEPTION 'Drain media cleanup processing events before 54B; do not reset leases to bypass this guard'; END IF;
END $$;
-- Required for authenticated invoker wrappers to resolve their private entry point.
-- USAGE grants no CREATE, table access, or EXECUTE on internal functions.
GRANT USAGE ON SCHEMA public,private TO authenticated;

CREATE TABLE private.admin_action_receipts (
 actor_id uuid NOT NULL, request_id uuid NOT NULL,
 payload jsonb NOT NULL, receipt jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(actor_id,request_id)
);
CREATE TABLE private.moderation_media_holds (
 asset_id uuid PRIMARY KEY REFERENCES public.media_assets(id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(), policy text NOT NULL DEFAULT 'owner_retention_review_required'
);
CREATE TABLE private.moderation_text_evidence (
 target_kind text NOT NULL, target_id uuid NOT NULL, body text,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(target_kind,target_id)
);
ALTER TABLE private.admin_action_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE private.moderation_media_holds ENABLE ROW LEVEL SECURITY;
ALTER TABLE private.moderation_text_evidence ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON private.admin_action_receipts,private.moderation_media_holds,private.moderation_text_evidence FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.admin_get_capabilities() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE a boolean; m boolean;
BEGIN
 PERFORM private.b_active(); a:=private.has_role('system_admin'); m:=private.has_role('moderator');
 RETURN jsonb_build_object('contract_version',2,'actor_id',auth.uid(),'system_admin',a,'moderator',m,
 'can_review_reports',a OR m,'can_manage_accounts',a,'can_manage_content',a,
 'can_view_dashboard',a,'can_suspend',a,'can_reassign',a,'notification_delivery','not_implemented');
END $$;

-- Internal projection only: no social ACL bypass is granted to callers.
CREATE FUNCTION private.admin_target(p_kind text,p_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT CASE WHEN p_kind IN ('post','reel') THEN
 (SELECT jsonb_build_object('id',p.id,'kind',CASE WHEN p.is_reel THEN 'reel' ELSE 'post' END,
 'state',p.state,'version',p.version,'owner_id',p.author_id,'body',CASE WHEN p.state='deleted' THEN
 coalesce(nullif(p.body,''),(SELECT e.body FROM private.moderation_text_evidence e WHERE e.target_kind='post' AND e.target_id=p.id)) ELSE p.body END,
 'created_at',p.created_at,
 'music_metadata',to_jsonb(p)->'music_metadata') FROM public.posts p WHERE p.id=p_id)
 WHEN p_kind='comment' THEN (SELECT jsonb_build_object('id',c.id,'kind','comment','state',c.state,
 'version',c.version,'owner_id',c.author_id,'body',coalesce(c.body,(SELECT e.body FROM private.moderation_text_evidence e
 WHERE e.target_kind='comment' AND e.target_id=c.id)),'post_id',c.post_id,'parent_id',c.parent_id,
 'thread_notice','Only this comment changes; readable descendants remain visible','created_at',c.created_at)
 FROM public.comments c WHERE c.id=p_id)
 WHEN p_kind='story' THEN (SELECT jsonb_build_object('id',s.id,'kind','story','state',s.state,
 'owner_id',s.author_id,'caption',s.caption,'expires_at',s.expires_at,'created_at',s.created_at,
 'music_metadata',to_jsonb(s)->'music_metadata')
 FROM public.stories s WHERE s.id=p_id)
 WHEN p_kind='message' THEN (SELECT jsonb_build_object('id',m.id,'kind','message','state',m.state,
 'version',m.version,'owner_id',m.sender_id,'body',CASE WHEN m.state='sent' THEN m.body ELSE
 (SELECT e.body FROM private.moderation_text_evidence e WHERE e.target_kind='message' AND e.target_id=m.id) END,
 'created_at',m.created_at) FROM public.messages m WHERE m.id=p_id)
 WHEN p_kind IN ('profile','account') THEN (SELECT jsonb_build_object('id',p.id,'kind','profile',
 'display_name',p.display_name,'owner_id',p.id,'state',s.state,'account_state',s.state,
 'onboarding_completed',p.onboarding_completed,'suspended_until',s.suspended_until,
 'deletion_requested_at',s.deletion_requested_at) FROM public.profiles p
 JOIN public.account_status s ON s.user_id=p.id WHERE p.id=p_id) END
$$;

CREATE FUNCTION private.admin_permitted_actions(p_kind text,p_target jsonb,p_report boolean) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb:=CASE WHEN p_report THEN '["dismiss"]'::jsonb ELSE '[]'::jsonb END;
 s text:=p_target->>'state'; owner_id uuid:=(p_target->>'owner_id')::uuid;
BEGIN
 IF p_target IS NULL OR p_kind='group' OR owner_id=auth.uid() OR EXISTS(
 SELECT 1 FROM public.system_roles r WHERE r.user_id=owner_id AND r.role='system_admin') THEN RETURN result; END IF;
 IF p_report AND owner_id IS NOT NULL THEN result:=result||'"warn"'::jsonb; END IF;
 IF p_kind IN ('post','reel','comment') THEN
  IF s='published' THEN result:=result||'"hide"'::jsonb; END IF;
  IF s NOT IN ('deleted','rejected') THEN result:=result||'"remove"'::jsonb; END IF;
  IF NOT p_report AND s='hidden' THEN result:=result||'"restore"'::jsonb; END IF;
 ELSIF p_report AND ((p_kind='story' AND s IN ('active','archived')) OR (p_kind='message' AND s='sent')) THEN
  result:=result||'"remove"'::jsonb;
 END IF;
 IF private.has_role('system_admin') THEN
  IF p_report AND EXISTS(SELECT 1 FROM public.account_status a WHERE a.user_id=owner_id AND a.state='active') THEN
   result:=result||'"suspend"'::jsonb;
  ELSIF NOT p_report AND p_kind='account' THEN
   IF s='active' THEN result:=result||'"suspend"'::jsonb;
   ELSIF s='suspended' THEN result:=result||'"restore"'::jsonb; END IF;
  END IF;
 END IF;
 RETURN result;
END $$;

-- Shared locked transition, used by both legacy and v2 direct/report entry points.
CREATE FUNCTION private.admin_transition(p_kind text,p_id uuid,p_action text,p_expected_state text)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE t jsonb; old_state text; new_state text; owner_id uuid; post_id uuid; original_id uuid; post_kind text;
BEGIN
 IF p_kind='group' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='feature_retired'; END IF;
 IF p_kind IN ('post','reel') THEN
  PERFORM 1 FROM public.posts p WHERE p.id=p_id AND (p_kind<>'reel' OR p.is_reel) FOR UPDATE;
 ELSIF p_kind='comment' THEN PERFORM 1 FROM public.comments c WHERE c.id=p_id FOR UPDATE;
 ELSIF p_kind='story' THEN PERFORM 1 FROM public.stories s WHERE s.id=p_id FOR UPDATE;
 ELSIF p_kind='message' THEN PERFORM 1 FROM public.messages m WHERE m.id=p_id FOR UPDATE;
 ELSIF p_kind IN ('profile','account') THEN PERFORM 1 FROM public.account_status a WHERE a.user_id=p_id FOR UPDATE;
 ELSE RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_target_kind'; END IF;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
 t:=private.admin_target(p_kind,p_id); old_state:=t->>'state'; owner_id:=(t->>'owner_id')::uuid;
 IF owner_id=auth.uid() OR EXISTS(SELECT 1 FROM public.system_roles WHERE user_id=owner_id AND role='system_admin') THEN
  RAISE SQLSTATE 'PT403' USING MESSAGE='protected_account'; END IF;
 IF p_expected_state IS NOT NULL AND old_state IS DISTINCT FROM p_expected_state THEN
  RAISE SQLSTATE 'PT409' USING MESSAGE='content_state_conflict'; END IF;
 new_state:=old_state;
 IF p_action='warn' THEN
  IF owner_id IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='account_unavailable'; END IF;
  -- Recorded warning; no delivery claim.
 ELSIF p_action IN ('suspend','unsuspend') THEN
  IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
  SELECT a.state INTO old_state FROM public.account_status a WHERE a.user_id=owner_id FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='account_unavailable'; END IF;
  IF (p_action='suspend' AND old_state<>'active') OR (p_action='unsuspend' AND old_state<>'suspended') THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='account_state_conflict'; END IF;
  new_state:=CASE WHEN p_action='suspend' THEN 'suspended' ELSE 'active' END;
  UPDATE public.account_status SET state=new_state,suspended_until=NULL,updated_at=statement_timestamp() WHERE user_id=owner_id;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='account_unavailable'; END IF;
  p_kind:='account'; p_id:=owner_id;
 ELSE
  IF p_action='hide' AND p_kind IN ('post','reel','comment') AND old_state='published' THEN new_state:='hidden';
  ELSIF p_action='restore' AND p_kind IN ('post','reel','comment') AND old_state='hidden' THEN new_state:='published';
  ELSIF p_action='remove' AND ((p_kind IN ('post','reel','comment') AND old_state NOT IN ('deleted','rejected'))
    OR (p_kind='story' AND old_state IN ('active','archived')) OR (p_kind='message' AND old_state='sent')) THEN
   new_state:=CASE WHEN p_kind='message' THEN 'removed' ELSE 'deleted' END;
  ELSE RAISE SQLSTATE 'PT409' USING MESSAGE='content_state_conflict'; END IF;
  -- Tombstones are readable through legacy social/table paths. Keep their body
  -- redacted and retain text in an owner-only store, never in a social projection.
  IF p_action='remove' THEN
   INSERT INTO private.moderation_text_evidence(target_kind,target_id,body)
   VALUES(CASE WHEN p_kind='reel' THEN 'post' ELSE p_kind END,p_id,coalesce(t->>'body',t->>'caption')) ON CONFLICT DO NOTHING;
  END IF;
  -- Preserve linked media, including cover assets. No cleanup is scheduled here.
  -- Hold creation and retirement lock the same asset rows before checking state.
  PERFORM 1 FROM public.media_assets a WHERE
   EXISTS(SELECT 1 FROM public.post_media pm WHERE p_kind IN ('post','reel') AND pm.post_id=p_id AND pm.asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p_kind IN ('post','reel') AND p.id=p_id AND p.reel_cover_asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.stories s WHERE p_kind='story' AND s.id=p_id AND s.media_asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.message_media mm WHERE p_kind='message' AND mm.message_id=p_id AND mm.asset_id=a.id)
   ORDER BY a.id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM public.media_assets a WHERE a.state='pending_delete' AND (
   EXISTS(SELECT 1 FROM public.post_media pm WHERE p_kind IN ('post','reel') AND pm.post_id=p_id AND pm.asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p_kind IN ('post','reel') AND p.id=p_id AND p.reel_cover_asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.stories s WHERE p_kind='story' AND s.id=p_id AND s.media_asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.message_media mm WHERE p_kind='message' AND mm.message_id=p_id AND mm.asset_id=a.id))) THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='evidence_cleanup_in_progress'; END IF;
  INSERT INTO private.moderation_media_holds(asset_id)
   SELECT a.id FROM public.media_assets a WHERE a.state='ready' AND (
    EXISTS(SELECT 1 FROM public.post_media pm WHERE p_kind IN ('post','reel') AND pm.post_id=p_id AND pm.asset_id=a.id)
    OR EXISTS(SELECT 1 FROM public.posts p WHERE p_kind IN ('post','reel') AND p.id=p_id AND p.reel_cover_asset_id=a.id)
    OR EXISTS(SELECT 1 FROM public.stories s WHERE p_kind='story' AND s.id=p_id AND s.media_asset_id=a.id)
    OR EXISTS(SELECT 1 FROM public.message_media mm WHERE p_kind='message' AND mm.message_id=p_id AND mm.asset_id=a.id))
   ON CONFLICT DO NOTHING;
  IF p_kind IN ('post','reel') THEN
   SELECT p.original_post_id,p.kind INTO original_id,post_kind FROM public.posts p WHERE p.id=p_id;
   UPDATE public.posts SET state=new_state,version=version+1,updated_at=statement_timestamp(),
    deleted_at=CASE WHEN new_state='deleted' THEN statement_timestamp() END WHERE id=p_id;
   IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
   IF post_kind='share' AND original_id IS NOT NULL AND new_state='deleted' THEN
    UPDATE public.posts SET shares_count=greatest(0,shares_count-1) WHERE id=original_id;
   END IF;
  ELSIF p_kind='comment' THEN
   UPDATE public.comments SET state=new_state,body=CASE WHEN p_action='remove' THEN NULL ELSE body END,
   version=version+1,updated_at=statement_timestamp() WHERE id=p_id RETURNING comments.post_id INTO post_id;
   IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
   IF (old_state='published')<>(new_state='published') THEN
    UPDATE public.posts SET comments_count=greatest(0,comments_count+CASE WHEN new_state='published' THEN 1 ELSE -1 END) WHERE id=post_id;
   END IF;
  ELSIF p_kind='story' THEN
   UPDATE public.stories SET state=new_state,deleted_at=statement_timestamp() WHERE id=p_id;
   IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
  ELSE
   UPDATE public.messages SET state=new_state,body=NULL,version=version+1 WHERE id=p_id;
   IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
  END IF;
 END IF;
 RETURN jsonb_build_object('subject_type',p_kind,'subject_id',p_id,'old_state',old_state,'new_state',new_state,
 'evidence_policy','retained_pending_owner_review','notification_status','not_implemented');
END $$;

CREATE FUNCTION private.admin_commit_action(p_report_id uuid,p_kind text,p_target_id uuid,p_action text,
 p_reason text,p_request_id uuid,p_expected_state text) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.reports; target uuid:=p_target_id; kind text:=p_kind;
 payload jsonb; saved private.admin_action_receipts; delta jsonb; result jsonb; action_id uuid; mapped text:=p_action;
 legacy public.moderation_actions; legacy_audit public.moderation_audit;
BEGIN
 PERFORM private.b_active();
 IF NOT private.has_role('system_admin') AND (p_report_id IS NULL OR NOT private.has_role('moderator')) THEN
  RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 IF p_request_id IS NULL OR p_action IS NULL OR p_reason IS NULL OR char_length(btrim(p_reason)) NOT BETWEEN 1 AND 1000 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_admin_action'; END IF;
 payload:=jsonb_build_object('report_id',p_report_id,'kind',p_kind,'target_id',p_target_id,'action',p_action,
 'reason',btrim(p_reason),'expected_state',p_expected_state);
 PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':admin-action:'||p_request_id::text,0));
 SELECT * INTO saved FROM private.admin_action_receipts WHERE actor_id=actor AND request_id=p_request_id;
 IF FOUND THEN
  IF saved.payload<>payload THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN saved.receipt||jsonb_build_object('replayed',true);
 END IF;
 -- Preserve exact payload-bound replay of pre-54B direct actions.
 SELECT * INTO legacy FROM public.moderation_actions WHERE actor_id=actor AND client_request_id=p_request_id;
 IF FOUND THEN
  SELECT * INTO legacy_audit FROM public.moderation_audit WHERE moderation_audit.action_id=legacy.id ORDER BY created_at,id LIMIT 1;
  IF p_report_id IS NOT NULL OR legacy.report_id IS NOT NULL OR legacy.reason IS DISTINCT FROM btrim(p_reason)
   OR legacy.action IS DISTINCT FROM (CASE WHEN p_kind='account' AND p_action='restore' THEN 'unsuspend' ELSE p_action END)
   OR legacy_audit.subject_type IS DISTINCT FROM p_kind OR legacy_audit.subject_id IS DISTINCT FROM p_target_id THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  result:=jsonb_build_object('contract_version',2,'request_id',p_request_id,'action_id',legacy.id,'replayed',true,
   'id',p_target_id,'user_id',p_target_id,'state',legacy_audit.safe_metadata->>'new_state','notification_status','unknown_legacy');
  INSERT INTO private.admin_action_receipts VALUES(actor,p_request_id,payload,result,now()); RETURN result;
 END IF;
 IF p_report_id IS NOT NULL THEN
  SELECT * INTO r FROM public.reports WHERE id=p_report_id FOR UPDATE;
  IF NOT FOUND OR (NOT private.has_role('system_admin') AND r.assigned_to IS DISTINCT FROM actor) THEN
   RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
  IF r.state NOT IN ('open','assigned') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='report_already_closed'; END IF;
  kind:=r.target_kind; target:=coalesce(r.post_id,r.comment_id,r.story_id,r.message_id,r.profile_id,r.group_id);
  IF p_action NOT IN ('dismiss','warn','hide','remove','suspend') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_report_action'; END IF;
 ELSE
  IF p_kind='post' AND EXISTS(SELECT 1 FROM public.posts WHERE id=p_target_id AND is_reel) THEN
   RAISE SQLSTATE 'PT422' USING MESSAGE='target_kind_mismatch'; END IF;
  IF p_kind='account' AND p_action='restore' THEN mapped:='unsuspend'; END IF;
  IF p_kind IS NULL OR p_kind NOT IN ('post','reel','comment','account','group') OR p_target_id IS NULL
   OR (p_kind='account' AND p_action NOT IN ('suspend','restore'))
   OR (p_kind<>'account' AND p_action NOT IN ('hide','remove','restore')) THEN
   RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_admin_action'; END IF;
 END IF;
 IF p_action='dismiss' THEN
  delta:=jsonb_build_object('subject_type',kind,'subject_id',target,'old_state',NULL,'new_state',NULL,
   'target_available',target IS NOT NULL,'notification_status','not_implemented');
 ELSE delta:=private.admin_transition(kind,target,mapped,p_expected_state); END IF;
 INSERT INTO public.moderation_actions(report_id,actor_id,client_request_id,action,reason)
 VALUES(p_report_id,actor,p_request_id,mapped,btrim(p_reason)) RETURNING id INTO action_id;
 IF p_report_id IS NOT NULL THEN
  UPDATE public.reports SET state=CASE WHEN p_action='dismiss' THEN 'dismissed' ELSE 'resolved' END,
   resolved_at=statement_timestamp() WHERE id=p_report_id;
 END IF;
 INSERT INTO public.moderation_audit(actor_id,action_id,report_id,operation,subject_type,subject_id,safe_metadata)
 VALUES(actor,action_id,p_report_id,CASE WHEN p_report_id IS NULL THEN 'admin_' ELSE 'report_' END||mapped,
 delta->>'subject_type',(delta->>'subject_id')::uuid,delta||jsonb_build_object('reason',btrim(p_reason),
 'report_old_state',r.state,'report_new_state',CASE WHEN p_report_id IS NOT NULL THEN CASE WHEN p_action='dismiss' THEN 'dismissed' ELSE 'resolved' END END));
 result:=delta||jsonb_build_object('contract_version',2,'action_id',action_id,'request_id',p_request_id,
 'report_id',p_report_id,'id',target,'user_id',CASE WHEN delta->>'subject_type'='account' THEN delta->>'subject_id' END,
 'decision',p_action,'state',CASE WHEN p_report_id IS NULL
 THEN delta->>'new_state' ELSE CASE WHEN p_action='dismiss' THEN 'dismissed' ELSE 'resolved' END END,'replayed',false);
 INSERT INTO private.admin_action_receipts VALUES(actor,p_request_id,payload,result,now());
 RETURN result;
END $$;

CREATE OR REPLACE FUNCTION private.admin_moderate_content(p_kind text,p_target_id uuid,p_action text,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.admin_commit_action(NULL,$1,$2,$3,$4,$5,NULL)
$$;
CREATE OR REPLACE FUNCTION private.admin_set_account_status(p_user_id uuid,p_action text,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.admin_commit_action(NULL,'account',$1,$2,$3,$4,NULL)
$$;

CREATE FUNCTION private.admin_decide_report_v2(p_report_id uuid,p_action text,p_reason text,p_request_id uuid,p_expected_state text)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.admin_commit_action($1,NULL,NULL,$2,$3,$4,$5)
$$;
CREATE FUNCTION private.admin_moderate_subject_v2(p_kind text,p_target_id uuid,p_action text,p_reason text,p_request_id uuid,p_expected_state text)
RETURNS jsonb LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.admin_commit_action(NULL,$1,$2,$3,$4,$5,$6)
$$;
CREATE FUNCTION private.admin_action_receipt(p_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
 RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 RETURN (SELECT receipt||jsonb_build_object('replayed',true) FROM private.admin_action_receipts
 WHERE actor_id=auth.uid() AND request_id=p_request_id);
END $$;

-- Old clients retain terminal tuple replay, while new clients bind an explicit request ID.
CREATE OR REPLACE FUNCTION private.resolve_platform_report(p_report_id uuid,p_action text,p_reason text) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.reports; a public.moderation_actions;
BEGIN
 PERFORM private.b_active();
 IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
 RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 SELECT * INTO r FROM public.reports WHERE id=p_report_id FOR UPDATE;
 IF NOT FOUND OR (NOT private.has_role('system_admin') AND r.assigned_to IS DISTINCT FROM actor) THEN
 RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
 IF r.state IN ('resolved','dismissed') THEN
  SELECT * INTO a FROM public.moderation_actions WHERE report_id=r.id ORDER BY created_at DESC,id DESC LIMIT 1;
  IF a.actor_id=actor AND a.action=p_action AND a.reason=btrim(p_reason) THEN
   RETURN jsonb_build_object('report_id',r.id,'state',r.state,'decision',p_action,'action_id',a.id,'replayed',true);
  END IF;
  RAISE SQLSTATE 'PT409' USING MESSAGE='report_already_closed';
 END IF;
 RETURN private.admin_decide_report_v2(p_report_id,p_action,p_reason,gen_random_uuid(),NULL);
END $$;

CREATE OR REPLACE FUNCTION private.claim_platform_report(p_report_id uuid) RETURNS jsonb
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.reports;
BEGIN
 PERFORM private.b_active();
 IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
 RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 SELECT * INTO r FROM public.reports WHERE id=p_report_id FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
 IF r.state NOT IN ('open','assigned') OR (r.assigned_to IS NOT NULL AND r.assigned_to<>actor) THEN
 RAISE SQLSTATE 'PT409' USING MESSAGE='report_assignment_conflict'; END IF;
 IF r.assigned_to IS NULL THEN
  UPDATE public.reports SET assigned_to=actor,state='assigned' WHERE id=r.id;
  INSERT INTO public.moderation_audit(actor_id,report_id,operation,subject_type,subject_id,safe_metadata)
  VALUES(actor,r.id,'report_claim','report',r.id,jsonb_build_object('old_state',r.state,'new_state','assigned','assigned_to',actor));
 END IF;
 RETURN jsonb_build_object('report_id',r.id,'claimed',true,'state','assigned','assigned_to',actor,
 'target_kind',r.target_kind,'target_id',coalesce(r.post_id,r.comment_id,r.story_id,r.message_id,r.profile_id,r.group_id),
 'reason_code',r.reason_code,'details',r.details);
END $$;

CREATE FUNCTION private.admin_reassign_report(p_report_id uuid,p_expected_assignee uuid,p_assignee uuid,p_reason text,p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.reports; payload jsonb; saved private.admin_action_receipts; result jsonb;
BEGIN
 PERFORM private.b_active();
 IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
 IF p_request_id IS NULL OR p_reason IS NULL OR char_length(btrim(p_reason)) NOT BETWEEN 1 AND 1000 THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_assignment'; END IF;
 payload:=jsonb_build_object('operation','reassign','report_id',p_report_id,'expected',p_expected_assignee,'assignee',p_assignee,'reason',btrim(p_reason));
 SELECT * INTO saved FROM private.admin_action_receipts WHERE actor_id=actor AND request_id=p_request_id;
 IF FOUND THEN
  IF saved.payload<>payload THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN saved.receipt||jsonb_build_object('replayed',true);
 END IF;
 IF EXISTS(SELECT 1 FROM public.moderation_actions WHERE actor_id=actor AND client_request_id=p_request_id) THEN
 RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
 SELECT * INTO r FROM public.reports WHERE id=p_report_id FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
 IF r.state NOT IN ('open','assigned') OR r.assigned_to IS DISTINCT FROM p_expected_assignee THEN
 RAISE SQLSTATE 'PT409' USING MESSAGE='report_assignment_conflict'; END IF;
 IF p_assignee IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.system_roles s JOIN public.account_status a ON a.user_id=s.user_id
  JOIN public.profiles p ON p.id=s.user_id JOIN auth.users u ON u.id=p.id
  WHERE s.user_id=p_assignee AND s.role IN ('system_admin','moderator')
  AND a.state='active' AND p.onboarding_completed AND u.email_confirmed_at IS NOT NULL) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_assignee'; END IF;
 UPDATE public.reports SET assigned_to=p_assignee,state=CASE WHEN p_assignee IS NULL THEN 'open' ELSE 'assigned' END WHERE id=r.id;
 result:=jsonb_build_object('report_id',r.id,'assigned_to',p_assignee,'state',CASE WHEN p_assignee IS NULL THEN 'open' ELSE 'assigned' END,
 'request_id',p_request_id,'replayed',false);
 INSERT INTO public.moderation_audit(actor_id,report_id,operation,subject_type,subject_id,safe_metadata)
 VALUES(actor,r.id,'report_reassign','report',r.id,jsonb_build_object('old_assignee',r.assigned_to,'new_assignee',p_assignee,
 'old_state',r.state,'new_state',result->>'state','reason',btrim(p_reason),'request_id',p_request_id));
 INSERT INTO private.admin_action_receipts VALUES(actor,p_request_id,payload,result,now()); RETURN result;
END $$;
CREATE FUNCTION private.admin_claim_report_v2(p_report_id uuid) RETURNS jsonb
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path='' AS $$ SELECT private.claim_platform_report($1) $$;

CREATE FUNCTION private.admin_reports_page_v2(p_state text,p_kind text,p_query text,p_mine boolean,
 p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE rows jsonb; more boolean; a boolean;
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id); a:=private.has_role('system_admin');
 IF NOT a AND NOT private.has_role('moderator') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 IF (p_state IS NOT NULL AND p_state NOT IN ('open','assigned','resolved','dismissed'))
 OR (p_kind IS NOT NULL AND p_kind NOT IN ('post','comment','story','message','profile','group'))
 OR char_length(coalesce(p_query,''))>100 OR p_mine IS NULL THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_report_filter'; END IF;
 WITH candidates AS MATERIALIZED (
 SELECT r.* FROM public.reports r WHERE (a OR r.assigned_to=auth.uid() OR (r.assigned_to IS NULL AND r.state IN ('open','assigned')))
 AND (p_state IS NULL OR r.state=p_state) AND (p_kind IS NULL OR r.target_kind=p_kind)
 AND (NOT p_mine OR r.assigned_to=auth.uid())
 AND (nullif(btrim(p_query),'') IS NULL OR position(lower(btrim(p_query)) IN lower(r.reason_code))>0
  OR r.id::text=btrim(p_query) OR ((a OR r.assigned_to=auth.uid()) AND position(lower(btrim(p_query)) IN lower(r.details))>0))
 AND (p_after_time IS NULL OR (r.created_at,r.id)<(p_after_time,p_after_id))
 ORDER BY r.created_at DESC,r.id DESC LIMIT p_limit+1
 ), selected AS (SELECT * FROM candidates ORDER BY created_at DESC,id DESC LIMIT p_limit)
 SELECT coalesce((SELECT jsonb_agg(jsonb_build_object('id',r.id,'target_kind',r.target_kind,
 'target_id',CASE WHEN a OR r.assigned_to=auth.uid() THEN coalesce(r.post_id,r.comment_id,r.story_id,r.message_id,r.profile_id,r.group_id) END,
 'reason_code',r.reason_code,'details',CASE WHEN a OR r.assigned_to=auth.uid() THEN r.details END,
 'state',r.state,'assigned_to',r.assigned_to,'created_at',r.created_at,'resolved_at',r.resolved_at,
 'can_claim',r.assigned_to IS NULL AND r.state IN ('open','assigned')) ORDER BY r.created_at DESC,r.id DESC) FROM selected r),'[]'::jsonb),
 (SELECT count(*)>p_limit FROM candidates) INTO rows,more;
 RETURN jsonb_build_object('contract_version',2,'items',rows,'has_more',more,'next_cursor',CASE WHEN more THEN
 jsonb_build_object('created_at',rows->-1->>'created_at','id',rows->-1->>'id') END);
END $$;

CREATE FUNCTION private.admin_audit_page_v2(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE rows jsonb; more boolean;
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
 RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 WITH candidates AS MATERIALIZED (
 SELECT a.*,m.client_request_id FROM public.moderation_audit a LEFT JOIN public.moderation_actions m ON m.id=a.action_id
 WHERE (private.has_role('system_admin') OR (a.report_id IS NOT NULL AND private.can_review_report(a.report_id)))
 AND (p_after_time IS NULL OR (a.created_at,a.id)<(p_after_time,p_after_id)) ORDER BY a.created_at DESC,a.id DESC LIMIT p_limit+1
 ), selected AS (SELECT * FROM candidates ORDER BY created_at DESC,id DESC LIMIT p_limit)
 SELECT coalesce((SELECT jsonb_agg(jsonb_build_object('id',id,'actor_id',actor_id,'action_id',action_id,'report_id',report_id,
 'request_id',coalesce(client_request_id::text,safe_metadata->>'request_id'),'operation',operation,
 'subject_type',subject_type,'subject_id',subject_id,'created_at',created_at,
 'reason',safe_metadata->>'reason','old_state',safe_metadata->>'old_state','new_state',safe_metadata->>'new_state',
 'notification_status',safe_metadata->>'notification_status') ORDER BY created_at DESC,id DESC) FROM selected),'[]'::jsonb),
 (SELECT count(*)>p_limit FROM candidates) INTO rows,more;
 RETURN jsonb_build_object('contract_version',2,'items',rows,'has_more',more,'next_cursor',CASE WHEN more THEN
 jsonb_build_object('created_at',rows->-1->>'created_at','id',rows->-1->>'id') END);
END $$;

CREATE FUNCTION private.admin_report_detail_v2(p_report_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE r public.reports; a boolean; scoped boolean; target uuid; t jsonb; media jsonb; actions jsonb; expected_media bigint;
BEGIN
 PERFORM private.b_active(); a:=private.has_role('system_admin');
 IF NOT a AND NOT private.has_role('moderator') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
 SELECT * INTO r FROM public.reports WHERE id=p_report_id;
 IF NOT FOUND OR (NOT a AND r.assigned_to IS DISTINCT FROM auth.uid() AND NOT (r.assigned_to IS NULL AND r.state IN ('open','assigned'))) THEN
 RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
 scoped:=a OR r.assigned_to IS NOT DISTINCT FROM auth.uid();
 IF scoped THEN
  target:=coalesce(r.post_id,r.comment_id,r.story_id,r.message_id,r.profile_id,r.group_id);
  t:=private.admin_target(r.target_kind,target);
  -- Exactly the reported message, never a conversation projection or arbitrary message ID.
  SELECT count(*),coalesce(jsonb_agg(jsonb_build_object('asset_id',m.id,'mime_type',m.mime_type))
   FILTER (WHERE m.state='ready' AND private.can_review_report_media(m.id)),'[]'::jsonb) INTO expected_media,media
  FROM public.media_assets m WHERE (
   EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.post_id=r.post_id AND pm.asset_id=m.id)
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p.id=r.post_id AND p.reel_cover_asset_id=m.id)
   OR EXISTS(SELECT 1 FROM public.stories s WHERE s.id=r.story_id AND s.media_asset_id=m.id)
   OR EXISTS(SELECT 1 FROM public.message_media mm WHERE mm.message_id=r.message_id AND mm.asset_id=m.id));
 END IF;
 actions:=CASE WHEN scoped AND r.state IN ('open','assigned') THEN private.admin_permitted_actions(r.target_kind,t,true) ELSE '[]'::jsonb END;
 RETURN jsonb_build_object('contract_version',2,'id',r.id,'target_kind',r.target_kind,'target_id',target,'state',r.state,
 'reason_code',r.reason_code,'details',CASE WHEN scoped THEN r.details END,'assigned_to',r.assigned_to,
 'created_at',r.created_at,'resolved_at',r.resolved_at,'can_claim',r.assigned_to IS NULL AND r.state IN ('open','assigned'),
 'evidence_status',CASE WHEN NOT scoped THEN 'claim_required' WHEN t IS NULL THEN 'target_unavailable'
 WHEN r.target_kind='message' AND t->>'body' IS NULL AND jsonb_array_length(media)=0 THEN 'content_unavailable' ELSE 'available' END,
 'media_status',CASE WHEN NOT scoped THEN 'claim_required' WHEN t IS NULL THEN 'unavailable' WHEN expected_media=0 THEN 'none'
 WHEN jsonb_array_length(media)=0 THEN 'unavailable' WHEN jsonb_array_length(media)<expected_media THEN 'partial' ELSE 'available' END,
 'target',t,'media',coalesce(media,'[]'::jsonb),'permitted_actions',actions,
 'disabled_reason',CASE WHEN NOT scoped THEN 'claim_required' WHEN r.state NOT IN ('open','assigned') THEN 'report_closed'
 WHEN t IS NULL THEN 'target_unavailable_dismiss_only' WHEN jsonb_array_length(actions)=1 THEN 'protected_or_retired_target' END,
 'notification_status','not_implemented',
 'history',coalesce((SELECT jsonb_agg(jsonb_build_object('action_id',m.id,'actor_id',m.actor_id,'action',m.action,
 'reason',m.reason,'created_at',m.created_at) ORDER BY m.created_at DESC,m.id DESC) FROM public.moderation_actions m
 WHERE m.report_id=r.id AND scoped),'[]'::jsonb));
END $$;
CREATE OR REPLACE FUNCTION private.admin_report_detail(p_report_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT private.admin_report_detail_v2($1) $$;

CREATE FUNCTION private.admin_subject_detail_v2(p_kind text,p_target_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE t jsonb;
BEGIN
 PERFORM private.b_active();
 IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
 IF p_kind IS NULL OR p_kind NOT IN ('account','post','reel','comment','story') THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_target_kind'; END IF;
 IF p_kind='post' AND EXISTS(SELECT 1 FROM public.posts WHERE id=p_target_id AND is_reel) OR
 p_kind='reel' AND EXISTS(SELECT 1 FROM public.posts WHERE id=p_target_id AND NOT is_reel) THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='target_kind_mismatch'; END IF;
 t:=private.admin_target(p_kind,p_target_id);
 IF t IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
 RETURN jsonb_build_object('contract_version',2,'target',t,'permitted_actions',CASE WHEN p_kind='story' THEN '[]'::jsonb
 ELSE private.admin_permitted_actions(p_kind,t,false) END,
 'roles',CASE WHEN p_kind='account' THEN (SELECT coalesce(jsonb_agg(role),'[]'::jsonb) FROM public.system_roles WHERE user_id=p_target_id) END,
 'media_access','Use a scoped report for protected media; ordinary media ACL remains enforced',
 'history',coalesce((SELECT jsonb_agg(x.row ORDER BY x.created_at DESC,x.id DESC) FROM
 (SELECT a.id,a.created_at,jsonb_build_object('id',a.id,'actor_id',a.actor_id,'action_id',a.action_id,'report_id',a.report_id,
 'operation',a.operation,'reason',a.safe_metadata->>'reason','old_state',a.safe_metadata->>'old_state',
 'new_state',a.safe_metadata->>'new_state','created_at',a.created_at) AS row
 FROM public.moderation_audit a WHERE a.subject_id=p_target_id AND a.subject_type=p_kind
 ORDER BY a.created_at DESC,a.id DESC LIMIT 30) x),'[]'::jsonb));
END $$;

CREATE FUNCTION private.admin_users_page_v2(p_query text,p_state text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE rows jsonb; more boolean;
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
 IF char_length(coalesce(p_query,''))>100 OR (p_state IS NOT NULL AND p_state NOT IN ('active','suspended','deleting')) THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_user_filter'; END IF;
 WITH candidates AS MATERIALIZED (
 SELECT p.id,p.display_name,p.created_at,p.avatar_asset_id,a.state,a.suspended_until FROM public.profiles p
 JOIN public.account_status a ON a.user_id=p.id WHERE (p_state IS NULL OR a.state=p_state)
 AND (nullif(btrim(p_query),'') IS NULL OR position(lower(btrim(p_query)) IN lower(p.display_name))>0 OR p.id::text=btrim(p_query))
 AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id)) ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit+1
 ), selected AS (SELECT * FROM candidates ORDER BY created_at DESC,id DESC LIMIT p_limit)
 SELECT coalesce((SELECT jsonb_agg(jsonb_build_object('id',u.id,'user_id',u.id,'display_name',u.display_name,
 'created_at',u.created_at,'avatar_asset_id',u.avatar_asset_id,'account_state',u.state,'suspended_until',u.suspended_until,
 'roles',(SELECT coalesce(jsonb_agg(role),'[]'::jsonb) FROM public.system_roles WHERE user_id=u.id)) ORDER BY u.created_at DESC,u.id DESC)
 FROM selected u),'[]'::jsonb),(SELECT count(*)>p_limit FROM candidates) INTO rows,more;
 RETURN jsonb_build_object('contract_version',2,'items',rows,'has_more',more,'next_cursor',CASE WHEN more THEN
 jsonb_build_object('created_at',rows->-1->>'created_at','id',rows->-1->>'id') END);
END $$;

CREATE FUNCTION private.admin_content_page_v2(p_kind text,p_query text,p_state text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE candidates jsonb; rows jsonb; more boolean;
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
 IF p_limit>49 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_page_size'; END IF;
 IF p_state IS NOT NULL AND ((p_kind IN ('posts','reels') AND p_state NOT IN ('published','pending_review','rejected','hidden','deleted'))
 OR (p_kind='comments' AND p_state NOT IN ('published','hidden','deleted'))) THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_content_state'; END IF;
 candidates:=private.admin_list_content(p_kind,p_query,p_state,p_after_time,p_after_id,p_limit+1);
 more:=jsonb_array_length(candidates)>p_limit;
 SELECT coalesce(jsonb_agg(value ORDER BY ordinal),'[]'::jsonb) INTO rows FROM jsonb_array_elements(candidates) WITH ORDINALITY AS x(value,ordinal)
 WHERE ordinal<=p_limit;
 RETURN jsonb_build_object('contract_version',2,'items',rows,'has_more',more,'next_cursor',CASE WHEN more THEN
 jsonb_build_object('created_at',rows->-1->>'created_at','id',rows->-1->>'id') END);
END $$;
CREATE FUNCTION private.admin_dashboard_v2() RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.admin_dashboard()||jsonb_build_object('contract_version',2,'recent_actions',private.admin_audit_page_v2(NULL,NULL,5)->'items')
$$;

-- Protect ready media already tied to reports before enabling the new cleanup guard.
-- Does not revive retired/deleted assets, backfill text, or undo historical cleanup.
INSERT INTO private.moderation_media_holds(asset_id)
 SELECT a.id FROM public.media_assets a WHERE a.state='ready' AND EXISTS(SELECT 1 FROM public.reports r WHERE
 EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.post_id=r.post_id AND pm.asset_id=a.id)
 OR EXISTS(SELECT 1 FROM public.posts p WHERE p.id=r.post_id AND p.reel_cover_asset_id=a.id)
 OR EXISTS(SELECT 1 FROM public.stories s WHERE s.id=r.story_id AND s.media_asset_id=a.id)
 OR EXISTS(SELECT 1 FROM public.message_media mm WHERE mm.message_id=r.message_id AND mm.asset_id=a.id)) ON CONFLICT DO NOTHING;

-- Cleanup uses the final Task43 live-reference graph, including Reel covers and
-- album references. Its writer lock is shared with moderation and rollout.
-- See the guarded cleanup definition appended below.
-- Existing author deletion flows may still remove social references. Deferring
-- retirement here preserves held bytes without failing those unrelated flows.
CREATE OR REPLACE FUNCTION private.retire_asset(aid uuid) RETURNS void
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 -- No new global lock here: existing verification/trigger callers can already
 -- hold an asset row. Taking global-after-row would invert normal writer order.
 PERFORM 1 FROM public.media_assets WHERE id=aid FOR UPDATE;
 IF NOT FOUND THEN RETURN; END IF;
 IF EXISTS(SELECT 1 FROM private.moderation_media_holds h WHERE h.asset_id=aid) THEN RETURN; END IF;
 UPDATE public.media_assets SET state='pending_delete',delete_after=statement_timestamp() WHERE id=aid AND state<>'deleted';
 IF NOT FOUND THEN RETURN; END IF;
 INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id)
 VALUES('media-retire:'||aid::text,'media.retire','media_asset',aid) ON CONFLICT(event_key) DO NOTHING;
END $$;
CREATE FUNCTION private.protect_moderation_media_hold() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF NEW.state IN ('pending_delete','deleted') AND NEW.state IS DISTINCT FROM OLD.state
 AND EXISTS(SELECT 1 FROM private.moderation_media_holds h WHERE h.asset_id=OLD.id) THEN
 RAISE SQLSTATE 'PT409' USING MESSAGE='moderation_evidence_held'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER media_assets_moderation_hold BEFORE UPDATE OF state ON public.media_assets
FOR EACH ROW EXECUTE FUNCTION private.protect_moderation_media_hold();

CREATE OR REPLACE FUNCTION private.schedule_media_cleanup() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets; n integer:=0;
BEGIN
  PERFORM pg_advisory_xact_lock(6061101);
  FOR a IN SELECT * FROM public.media_assets m WHERE m.purpose IN ('avatar','cover','post','message','story')
    AND ((m.state='pending_delete' AND coalesce(m.delete_after,now())<=now())
      OR (m.state IN ('pending','ready','quarantined') AND m.created_at<now()-interval '24 hours'))
    AND NOT EXISTS(SELECT 1 FROM private.moderation_media_holds h WHERE h.asset_id=m.id)
    AND NOT private.task43_asset_live(m.id)
    ORDER BY m.id LIMIT 100 FOR UPDATE LOOP
    -- Serialize with hide/restore transitions. Hidden posts remain restorable,
    -- so their media must stay attached until the post is permanently removed.
    PERFORM 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
      WHERE pm.asset_id=a.id AND p.state IN ('published','hidden') FOR UPDATE OF p;
    IF FOUND THEN CONTINUE; END IF;
    IF NOT EXISTS(SELECT 1 FROM private.moderation_media_holds h WHERE h.asset_id=a.id)
      AND NOT private.task43_asset_live(a.id) THEN
      PERFORM private.retire_asset(a.id); n:=n+1;
    END IF;
  END LOOP;
  RETURN n;
END $$;
-- Only the enumerated RPC entry points are exposed; internal helpers remain owner-only.
DO $$
DECLARE f record; forwarding text; signature text; wrapper_count integer:=0;
BEGIN
 FOR f IN SELECT p.*,pg_get_function_identity_arguments(p.oid) AS args FROM (VALUES
 ('private.admin_get_capabilities()'),('private.admin_decide_report_v2(uuid,text,text,uuid,text)'),
 ('private.admin_moderate_subject_v2(text,uuid,text,text,uuid,text)'),('private.admin_action_receipt(uuid)'),
 ('private.admin_reassign_report(uuid,uuid,uuid,text,uuid)'),('private.admin_claim_report_v2(uuid)'),
 ('private.admin_reports_page_v2(text,text,text,boolean,timestamptz,uuid,integer)'),
 ('private.admin_audit_page_v2(timestamptz,uuid,integer)'),('private.admin_report_detail_v2(uuid)'),
 ('private.admin_subject_detail_v2(text,uuid)'),('private.admin_users_page_v2(text,text,timestamptz,uuid,integer)'),
 ('private.admin_content_page_v2(text,text,text,timestamptz,uuid,integer)'),('private.admin_dashboard_v2()')
 ) allowed(signature) JOIN pg_proc p ON p.oid=to_regprocedure(allowed.signature) LOOP
  IF f.prorettype<>'jsonb'::regtype OR f.prokind<>'f' OR NOT f.prosecdef THEN
   RAISE EXCEPTION 'Unexpected RPC implementation contract: %',f.proname; END IF;
  wrapper_count:=wrapper_count+1;
  SELECT string_agg('$'||i,',' ORDER BY i) INTO forwarding FROM generate_series(1,f.pronargs) AS argument_position(i);
  EXECUTE format('CREATE FUNCTION public.%I(%s) RETURNS jsonb LANGUAGE sql %s SECURITY INVOKER SET search_path='''' AS %L',
    f.proname,f.args,CASE WHEN f.provolatile='s' THEN 'STABLE' ELSE 'VOLATILE' END,
    format('SELECT private.%I(%s)',f.proname,coalesce(forwarding,'')));
  signature:=format('%I(%s)',f.proname,oidvectortypes(f.proargtypes));
  EXECUTE 'REVOKE ALL ON FUNCTION public.'||signature||',private.'||signature||' FROM PUBLIC,anon,authenticated,service_role';
  EXECUTE 'GRANT EXECUTE ON FUNCTION public.'||signature||',private.'||signature||' TO authenticated';
 END LOOP;
 IF wrapper_count<>13 THEN RAISE EXCEPTION '54B expected exactly 13 invoker wrappers, found %',wrapper_count; END IF;
 FOR f IN SELECT p.oid,p.proname,oidvectortypes(p.proargtypes) AS args FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname='private' AND p.proname IN ('admin_target','admin_permitted_actions','admin_transition','admin_commit_action',
 'protect_moderation_media_hold','retire_asset','schedule_media_cleanup') LOOP
  EXECUTE format('REVOKE ALL ON FUNCTION private.%I(%s) FROM PUBLIC,anon,authenticated,service_role',f.proname,f.args);
 END LOOP;
 FOR f IN SELECT p.oid,p.proname,n.nspname,oidvectortypes(p.proargtypes) AS args FROM pg_proc p
 JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private') AND p.proname IN
 ('admin_moderate_content','admin_set_account_status','admin_report_detail','claim_platform_report','resolve_platform_report') LOOP
  EXECUTE format('REVOKE ALL ON FUNCTION %I.%I(%s) FROM PUBLIC,anon,authenticated,service_role',f.nspname,f.proname,f.args);
  EXECUTE format('GRANT EXECUTE ON FUNCTION %I.%I(%s) TO authenticated',f.nspname,f.proname,f.args);
 END LOOP;
END $$;
-- Effective privileges include inheritance/default ACL drift. Abort rather than
-- silently granting broader roles, changing memberships, or exposing private data.
DO $$ BEGIN
 IF NOT has_schema_privilege('authenticated','public','USAGE')
 OR NOT has_schema_privilege('authenticated','private','USAGE')
 OR has_schema_privilege('authenticated','private','CREATE') THEN
  RAISE EXCEPTION '54B requires authenticated schema USAGE without private CREATE'; END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace CROSS JOIN pg_roles r
 WHERE n.nspname='private' AND p.proname IN ('admin_target','admin_permitted_actions','admin_transition','admin_commit_action',
 'protect_moderation_media_hold','retire_asset','schedule_media_cleanup')
 AND r.rolname IN ('anon','authenticated','service_role') AND has_function_privilege(r.oid,p.oid,'EXECUTE')) THEN
  RAISE EXCEPTION '54B internal helper EXECUTE remains exposed through inherited/default privileges'; END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace CROSS JOIN pg_roles r
 WHERE n.nspname IN ('public','private') AND p.proname IN
 ('admin_get_capabilities','admin_decide_report_v2','admin_moderate_subject_v2','admin_action_receipt','admin_reassign_report','admin_claim_report_v2',
 'admin_reports_page_v2','admin_audit_page_v2','admin_report_detail_v2','admin_subject_detail_v2','admin_users_page_v2',
 'admin_content_page_v2','admin_dashboard_v2','admin_moderate_content','admin_set_account_status','admin_report_detail',
 'claim_platform_report','resolve_platform_report') AND r.rolname IN ('anon','authenticated','service_role')
 AND has_function_privilege(r.oid,p.oid,'EXECUTE') IS DISTINCT FROM (r.rolname='authenticated')) THEN
  RAISE EXCEPTION '54B RPC effective EXECUTE does not match authenticated-only contract'; END IF;
 IF EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN pg_roles r
 WHERE n.nspname='private' AND c.relname IN ('admin_action_receipts','moderation_media_holds','moderation_text_evidence')
 AND r.rolname IN ('anon','authenticated','service_role') AND
 (has_table_privilege(r.oid,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
 OR has_any_column_privilege(r.oid,c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
  RAISE EXCEPTION '54B private table/column access remains exposed through inherited/default privileges'; END IF;
 IF EXISTS(SELECT 1 FROM (VALUES
 ('private.meeto_claim_media_cleanup(integer)','private.schedule_media_cleanup()'),
 ('private.meeto_finish_voice_verification(uuid,smallint,text,uuid,bigint,text,integer)','private.retire_asset(uuid)'),
 ('private.retire_reel_cover_on_delete()','private.retire_asset(uuid)'),
 ('private.schedule_media_cleanup()','private.task43_asset_live(uuid)')
 ) dependency(caller,callee) JOIN pg_proc caller ON caller.oid=to_regprocedure(dependency.caller)
 JOIN pg_proc callee ON callee.oid=to_regprocedure(dependency.callee)
 WHERE NOT has_function_privilege(caller.proowner,callee.oid,'EXECUTE')
 OR NOT has_schema_privilege(caller.proowner,callee.pronamespace,'USAGE')) THEN
  RAISE EXCEPTION '54B cleanup/trigger caller owner lacks helper privileges; reconcile owners without client grants'; END IF;
END $$;
NOTIFY pgrst,'reload schema';
COMMIT;
