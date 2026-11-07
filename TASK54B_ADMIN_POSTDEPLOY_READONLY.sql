-- OWNER-RUN ONLY. Not executed by Codex. Catalog/aggregate verification is not functional/runtime acceptance.
SELECT version FROM supabase_migrations.schema_migrations WHERE version='20261107001100';
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,p.provolatile,p.prosecdef,
 p.proconfig,pg_get_userbyid(p.proowner) AS owner,p.proacl,md5(pg_get_functiondef(p.oid)) AS definition_hash
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private') AND p.proname IN
 ('admin_get_capabilities','admin_reports_page_v2','admin_report_detail_v2','admin_decide_report_v2',
 'admin_moderate_subject_v2','admin_claim_report_v2','admin_action_receipt','admin_reassign_report','admin_audit_page_v2',
 'admin_subject_detail_v2','admin_users_page_v2','admin_content_page_v2','admin_dashboard_v2',
 'admin_transition','admin_commit_action','admin_target','admin_permitted_actions','protect_moderation_media_hold',
 'admin_moderate_content','admin_set_account_status','admin_report_detail','claim_platform_report','resolve_platform_report',
 'schedule_media_cleanup','retire_asset') ORDER BY p.proname,n.nspname,arguments;
-- Expected: new RPC entries authenticated=true, anon/service_role=false.
-- Internal helpers: all three false. Inherited privileges and function owner must also be reviewed.
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,r.rolname,
 has_function_privilege(r.oid,p.oid,'EXECUTE') AS can_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace CROSS JOIN pg_roles r
WHERE n.nspname IN ('public','private') AND r.rolname IN ('anon','authenticated','service_role') AND p.proname IN
 ('admin_get_capabilities','admin_reports_page_v2','admin_report_detail_v2','admin_decide_report_v2',
 'admin_moderate_subject_v2','admin_claim_report_v2','admin_action_receipt','admin_reassign_report','admin_audit_page_v2',
 'admin_subject_detail_v2','admin_users_page_v2','admin_content_page_v2','admin_dashboard_v2',
 'admin_transition','admin_commit_action','admin_target','admin_permitted_actions','protect_moderation_media_hold','retire_asset','schedule_media_cleanup',
 'admin_moderate_content','admin_set_account_status','admin_report_detail','claim_platform_report','resolve_platform_report')
ORDER BY p.proname,n.nspname,r.rolname;
SELECT n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity,c.relacl,r.rolname,
 has_table_privilege(r.oid,c.oid,'SELECT') AS can_select,has_table_privilege(r.oid,c.oid,'INSERT') AS can_insert,
 has_table_privilege(r.oid,c.oid,'UPDATE') AS can_update,has_table_privilege(r.oid,c.oid,'DELETE') AS can_delete
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN pg_roles r
WHERE n.nspname='private' AND c.relname IN ('admin_action_receipts','moderation_media_holds','moderation_text_evidence')
AND r.rolname IN ('anon','authenticated','service_role') ORDER BY c.relname,r.rolname;
SELECT n.nspname,c.relname,k.conname,pg_get_constraintdef(k.oid) AS definition
FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='private' AND c.relname IN ('admin_action_receipts','moderation_media_holds','moderation_text_evidence') ORDER BY c.relname,k.conname;
SELECT t.tgname,t.tgenabled,pg_get_triggerdef(t.oid) AS definition FROM pg_trigger t
WHERE t.tgrelid='public.media_assets'::regclass AND t.tgname='media_assets_moderation_hold';
SELECT p.proname,pg_get_functiondef(p.oid) AS installed_definition FROM pg_proc p
JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='private' AND p.proname IN
('admin_transition','admin_commit_action','admin_report_detail_v2','admin_reassign_report','schedule_media_cleanup','retire_asset','protect_moderation_media_hold');
-- Catalog evidence only. Do not execute moderation RPCs against production targets as a smoke test.

-- 54B.1: each expected wrapper must exist, be INVOKER, and match the private
-- DEFINER volatility/argument names. Both schemas need USAGE for authenticated.
WITH expected(signature) AS (VALUES
 ('admin_get_capabilities()'),('admin_decide_report_v2(uuid,text,text,uuid,text)'),
 ('admin_moderate_subject_v2(text,uuid,text,text,uuid,text)'),('admin_action_receipt(uuid)'),
 ('admin_reassign_report(uuid,uuid,uuid,text,uuid)'),('admin_claim_report_v2(uuid)'),
 ('admin_reports_page_v2(text,text,text,boolean,timestamptz,uuid,integer)'),
 ('admin_audit_page_v2(timestamptz,uuid,integer)'),('admin_report_detail_v2(uuid)'),
 ('admin_subject_detail_v2(text,uuid)'),('admin_users_page_v2(text,text,timestamptz,uuid,integer)'),
 ('admin_content_page_v2(text,text,text,timestamptz,uuid,integer)'),('admin_dashboard_v2()'))
SELECT e.signature,p.oid AS wrapper,d.oid AS implementation,NOT p.prosecdef AS wrapper_invoker,
 d.prosecdef AS implementation_definer,p.provolatile=d.provolatile AS volatility_matches,
 p.proargnames IS NOT DISTINCT FROM d.proargnames AS argument_names_match,
 has_schema_privilege('authenticated','public','USAGE') AS public_usage,
 has_schema_privilege('authenticated','private','USAGE') AS private_usage,
 has_function_privilege('authenticated',p.oid,'EXECUTE') AS wrapper_execute,
 has_function_privilege('authenticated',d.oid,'EXECUTE') AS implementation_execute
FROM expected e LEFT JOIN pg_proc p ON p.oid=to_regprocedure('public.'||e.signature)
LEFT JOIN pg_proc d ON d.oid=to_regprocedure('private.'||e.signature) ORDER BY e.signature;
SELECT n.nspname,r.rolname,has_schema_privilege(r.oid,n.oid,'USAGE') AS has_usage,
 has_schema_privilege(r.oid,n.oid,'CREATE') AS has_create
FROM pg_namespace n CROSS JOIN pg_roles r WHERE n.nspname IN ('public','private')
AND r.rolname IN ('anon','authenticated','service_role') ORDER BY n.nspname,r.rolname;
SELECT c.relname,r.rolname,has_any_column_privilege(r.oid,c.oid,'SELECT') AS any_column_select,
 has_any_column_privilege(r.oid,c.oid,'INSERT') AS any_column_insert,
 has_any_column_privilege(r.oid,c.oid,'UPDATE') AS any_column_update,
 has_any_column_privilege(r.oid,c.oid,'REFERENCES') AS any_column_references,
 has_table_privilege(r.oid,c.oid,'TRUNCATE') AS can_truncate,
 has_table_privilege(r.oid,c.oid,'TRIGGER') AS can_trigger
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN pg_roles r
WHERE n.nspname='private' AND c.relname IN ('admin_action_receipts','moderation_media_holds','moderation_text_evidence')
AND r.rolname IN ('anon','authenticated','service_role') ORDER BY c.relname,r.rolname;
-- Owner permission dependency: client=false on helpers is intentional;
-- the DEFINER owner must still be able to call each internal helper.
SELECT entry.proname AS entry_name,pg_get_userbyid(entry.proowner) AS entry_owner,helper.proname AS helper_name,
 has_schema_privilege(entry.proowner,n.oid,'USAGE') AS owner_schema_usage,
 has_function_privilege(entry.proowner,helper.oid,'EXECUTE') AS owner_helper_execute
FROM pg_proc entry JOIN pg_namespace n ON n.oid=entry.pronamespace JOIN pg_proc helper ON helper.pronamespace=n.oid
WHERE n.nspname='private' AND entry.proname IN ('admin_moderate_content','admin_set_account_status','admin_report_detail',
 'claim_platform_report','resolve_platform_report','admin_commit_action','admin_transition','admin_reassign_report','schedule_media_cleanup')
AND helper.proname IN ('admin_commit_action','admin_transition','admin_target','admin_permitted_actions','b_actor','b_active',
 'lock_profile','retire_asset','task43_asset_live') ORDER BY entry.proname,helper.proname;
SELECT p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,pg_get_functiondef(p.oid) AS installed_definition
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('private','public')
AND p.proname IN ('claim_platform_report','admin_claim_report_v2','admin_reassign_report','b_actor','lock_profile','b_active',
 'require_identity','has_role','is_active','task43_asset_live','request_account_deletion','meeto_claim_media_cleanup',
 'meeto_finish_media_cleanup','meeto_finish_voice_verification');

-- Aggregate-only invariants. Held pending_delete/deleted, or processing events for
-- a held asset, require owner investigation; do not auto-clear holds or queue rows.
SELECT a.state,count(*) AS held_assets,count(*) FILTER(WHERE a.owner_id IS NULL) AS held_owner_reference_missing
FROM private.moderation_media_holds h JOIN public.media_assets a ON a.id=h.asset_id GROUP BY a.state ORDER BY a.state;
SELECT e.state,a.state AS asset_state,count(*) AS held_media_retire_events
FROM private.moderation_media_holds h JOIN public.media_assets a ON a.id=h.asset_id
JOIN public.event_outbox e ON e.aggregate_id=a.id AND e.aggregate_type='media_asset' AND e.event_type='media.retire'
GROUP BY e.state,a.state ORDER BY e.state,a.state;
SELECT s.state,count(*) AS deletion_requested_accounts_with_holds
FROM public.account_status s WHERE s.deletion_requested_at IS NOT NULL AND EXISTS
(SELECT 1 FROM public.media_assets a JOIN private.moderation_media_holds h ON h.asset_id=a.id WHERE a.owner_id=s.user_id)
GROUP BY s.state ORDER BY s.state;
SELECT c.relname,k.conname,k.convalidated,pg_get_constraintdef(k.oid) AS definition
FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN ('media_assets','account_status','reports') ORDER BY c.relname,k.conname;
-- These SELECTs do not execute guard-negative tests, Storage DELETEs or account purge.

WITH dependency(caller,callee) AS (VALUES
 ('private.meeto_claim_media_cleanup(integer)','private.schedule_media_cleanup()'),
 ('private.meeto_finish_voice_verification(uuid,smallint,text,uuid,bigint,text,integer)','private.retire_asset(uuid)'),
 ('private.retire_reel_cover_on_delete()','private.retire_asset(uuid)'),
 ('private.schedule_media_cleanup()','private.task43_asset_live(uuid)'))
SELECT d.caller,d.callee,p.oid AS installed_caller,h.oid AS installed_helper,
 pg_get_userbyid(p.proowner) AS caller_owner,
 has_schema_privilege(p.proowner,h.pronamespace,'USAGE') AS caller_owner_schema_usage,
 has_function_privilege(p.proowner,h.oid,'EXECUTE') AS caller_owner_helper_execute
FROM dependency d LEFT JOIN pg_proc p ON p.oid=to_regprocedure(d.caller)
LEFT JOIN pg_proc h ON h.oid=to_regprocedure(d.callee) ORDER BY d.caller;
