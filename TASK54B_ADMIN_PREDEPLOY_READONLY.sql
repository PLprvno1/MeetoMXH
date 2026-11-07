-- OWNER-RUN ONLY. Not executed by Codex. SELECT-only catalog/aggregate checks; no SET role/JWT or business RPC calls.
SELECT current_database(),current_user,current_setting('server_version') AS postgres_version,
 to_regclass('supabase_migrations.schema_migrations') AS migration_ledger;
-- Run only if the ledger above exists. No migration-ledger edits are authorized.
SELECT version FROM supabase_migrations.schema_migrations
WHERE version IN ('20261101000100','20261101000200','20261101000300','20261101000400',
 '20261101001600','20261103000700','20261103001600','20261104000100','20261104000200','20261106000100',
 '20261106000200','20261106000300','20261106000400','20261106000900','20261107000900','20261107001000',
 '20261107001050','20261107001100') ORDER BY version;
SELECT signature,to_regprocedure(signature) AS installed FROM (VALUES
 ('private.b_actor()'),('private.b_active()'),('private.require_identity()'),('private.b_page(integer,timestamp with time zone,uuid)'),
 ('private.has_role(text)'),('private.can_review_report(uuid)'),('private.can_review_report_media(uuid)'),
 ('private.admin_dashboard()'),('private.admin_search_users(text,timestamp with time zone,uuid,integer)'),
 ('private.admin_list_content(text,text,text,timestamp with time zone,uuid,integer)'),
 ('private.admin_moderate_content(text,uuid,text,text,uuid)'),
 ('private.admin_set_account_status(uuid,text,text,uuid)'),('private.admin_report_detail(uuid)'),
 ('private.list_platform_reports(text,timestamp with time zone,uuid,integer)'),
 ('private.claim_platform_report(uuid)'),('private.resolve_platform_report(uuid,text,text)'),
 ('private.schedule_media_cleanup()'),('private.retire_asset(uuid)'),
 ('private.meeto_claim_media_cleanup(integer)'),('private.meeto_finish_media_cleanup(uuid,integer,boolean,text)')
) x(signature);
-- Presence alone does not establish version correctness. Owner compares definitions with local final migrations.
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,p.provolatile,p.prosecdef,
 p.proconfig,pg_get_userbyid(p.proowner) AS owner,p.proacl,md5(pg_get_functiondef(p.oid)) AS definition_hash,
 pg_get_functiondef(p.oid) AS installed_definition
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private')
AND p.proname IN ('b_actor','b_active','lock_profile','require_identity','has_role','is_active','can_read_comment','c_message',
 'admin_dashboard','admin_search_users','admin_list_content','admin_moderate_content','admin_set_account_status',
 'admin_report_detail','list_platform_reports','claim_platform_report','resolve_platform_report',
 'can_review_report_media','schedule_media_cleanup','retire_asset','meeto_claim_media_cleanup','meeto_finish_media_cleanup',
 'task43_asset_live','retire_reel_cover_on_delete','request_account_deletion','meeto_finish_voice_verification')
ORDER BY p.proname,n.nspname,arguments;
SELECT table_schema,table_name,column_name,data_type,is_nullable FROM information_schema.columns
WHERE table_schema='public' AND table_name IN ('profiles','account_status','posts','comments','stories','messages',
 'reports','system_roles','moderation_actions','moderation_audit','media_assets','post_media','message_media','conversations','profile_album_items')
ORDER BY table_name,ordinal_position;
SELECT n.nspname,c.relname,t.tgname,t.tgenabled,t.tgisinternal,pg_get_triggerdef(t.oid) AS definition
FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN ('posts','comments','stories','messages','media_assets','reports','moderation_actions','moderation_audit')
ORDER BY c.relname,t.tgname;
SELECT n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity,c.relacl FROM pg_class c
JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname IN
('reports','system_roles','moderation_actions','moderation_audit','media_assets','account_status');
SELECT schemaname,tablename,policyname,roles,cmd,qual,with_check FROM pg_policies
WHERE (schemaname='public' AND tablename IN ('reports','system_roles','moderation_actions','moderation_audit','media_assets'))
OR (schemaname='storage' AND tablename='objects') ORDER BY schemaname,tablename,policyname;
SELECT nspname,nspacl FROM pg_namespace WHERE nspname IN ('public','private');
SELECT signature,to_regprocedure(signature) AS must_be_absent_before_first_deploy FROM (VALUES
 ('private.admin_get_capabilities()'),('public.admin_get_capabilities()'),
 ('private.admin_transition(text,uuid,text,text)'),('public.admin_decide_report_v2(uuid,text,text,uuid,text)')) x(signature);
SELECT name,to_regclass(name) AS must_be_absent_before_first_deploy FROM (VALUES
 ('private.admin_action_receipts'),('private.moderation_media_holds'),('private.moderation_text_evidence')) x(name);
-- Owner must separately review: private schema not exposed in PostgREST, worker in-flight jobs,
-- retention/storage capacity, revoked assignees, and deployment order. No business rows or secrets selected.

-- 54B.1: FIRST DEPLOY ONLY. A ledger row or ANY 54B object means STOP and inspect;
-- an installed 54B must receive a new migration, never this amended file.
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private')
AND p.proname IN ('admin_get_capabilities','admin_target','admin_permitted_actions','admin_transition','admin_commit_action',
 'admin_decide_report_v2','admin_moderate_subject_v2','admin_action_receipt','admin_reassign_report','admin_claim_report_v2',
 'admin_reports_page_v2','admin_audit_page_v2','admin_report_detail_v2','admin_subject_detail_v2',
 'admin_users_page_v2','admin_content_page_v2','admin_dashboard_v2','protect_moderation_media_hold')
ORDER BY n.nspname,p.proname,arguments;
SELECT signature,to_regprocedure(signature) AS required FROM (VALUES
 ('pg_catalog.gen_random_uuid()'),
 ('private.lock_profile(boolean)'),('private.task43_asset_live(uuid)'),
 ('public.admin_moderate_content(text,uuid,text,text,uuid)'),('public.admin_set_account_status(uuid,text,text,uuid)'),
 ('public.admin_report_detail(uuid)'),('public.claim_platform_report(uuid)'),('public.resolve_platform_report(uuid,text,text)')
) expected(signature);

-- USAGE is required on both schemas for an authenticated INVOKER wrapper.
-- CREATE is not required. USAGE does not grant SELECT on a private table.
SELECT n.nspname,r.rolname,has_schema_privilege(r.oid,n.oid,'USAGE') AS has_usage,
 has_schema_privilege(r.oid,n.oid,'CREATE') AS has_create
FROM pg_namespace n CROSS JOIN pg_roles r WHERE n.nspname IN ('public','private')
AND r.rolname IN ('anon','authenticated','service_role',current_user) ORDER BY n.nspname,r.rolname;
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,
 pg_get_userbyid(p.proowner) AS owner,p.prosecdef,p.provolatile,
 CASE WHEN n.nspname='private' THEN pg_get_userbyid(p.proowner)=current_user END AS replacement_owner_matches_deployer,
 r.rolname,has_function_privilege(r.oid,p.oid,'EXECUTE') AS can_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace CROSS JOIN pg_roles r
WHERE n.nspname IN ('public','private') AND p.proname IN
 ('admin_moderate_content','admin_set_account_status','admin_report_detail','claim_platform_report','resolve_platform_report',
 'retire_asset','schedule_media_cleanup') AND r.rolname IN ('anon','authenticated','service_role')
ORDER BY n.nspname,p.proname,arguments,r.rolname;
-- Count overloads without the role cross join above.
SELECT n.nspname,p.proname,count(*) AS overload_count FROM pg_proc p
JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private') AND p.proname IN
('admin_moderate_content','admin_set_account_status','admin_report_detail','claim_platform_report','resolve_platform_report',
 'retire_asset','schedule_media_cleanup') GROUP BY n.nspname,p.proname ORDER BY n.nspname,p.proname;

-- Aggregate-only operational checks; no IDs, paths, reasons or message bodies.
-- Pause/drain cleanup outside this script. Any processing event blocks the amended migration,
-- including expired leases: changing a lease cannot cancel an in-flight Storage request.
SELECT state,count(*) AS media_retire_events,
 count(*) FILTER(WHERE lease_until>statement_timestamp()) AS active_leases
FROM public.event_outbox WHERE event_type='media.retire' GROUP BY state ORDER BY state;
SELECT count(*) AS ready_reel_covers_without_post_media_link FROM public.media_assets a
WHERE a.state='ready' AND EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id AND p.state IN ('published','hidden'))
AND NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=a.id);
SELECT a.state,count(*) AS deletion_requested_accounts FROM public.account_status a
WHERE a.deletion_requested_at IS NOT NULL GROUP BY a.state ORDER BY a.state;
SELECT m.state,count(*) AS media_owned_by_deletion_requested_accounts FROM public.media_assets m
JOIN public.account_status a ON a.user_id=m.owner_id WHERE a.deletion_requested_at IS NOT NULL GROUP BY m.state ORDER BY m.state;
SELECT n.nspname,c.relname,k.conname,k.convalidated,pg_get_constraintdef(k.oid) AS definition
FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN ('media_assets','account_status','reports','moderation_actions','moderation_audit')
ORDER BY c.relname,k.conname;
SELECT current_setting('server_version_num')::integer>=120000 AS supports_materialized_cte,
 current_setting('pgrst.db_schemas',true) AS session_pgrst_schema_setting;
-- NULL here does not prove private is unexposed. Owner must inspect real PostgREST configuration.
