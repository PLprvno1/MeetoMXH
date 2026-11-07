-- TASK 54A / OWNER-RUN ONLY. Chua chay script nay trong task.
-- Chi SELECT catalog; khong goi RPC nghiep vu, khong thay doi role/JWT/session.
-- Ket qua la inventory de doi chieu, khong tu ket luan runtime PASS.

SELECT current_database() AS database_name, current_user AS inspection_role,
       current_setting('server_version') AS postgres_version,
       to_regclass('supabase_migrations.schema_migrations') AS migration_ledger;

-- Owner doi chieu ledger trong Dashboard voi danh sach source nay.
SELECT expected.version, expected.purpose
FROM (VALUES
 ('20261101000100','foundation tables, constraints, indexes'),
 ('20261101000200','authorization, RLS, table ACL'),
 ('20261101000300','identity and active writer guards'),
 ('20261101000400','writer lock / b_active'),
 ('20261101001500','chat last-admin / upload safety; not a platform role grant'),
 ('20261103000500','historical group and post states'),
 ('20261103000600','historical group reports and audit triggers'),
 ('20261103000700','platform report submit/list/claim/audit'),
 ('20261104000100','Admin Panel public wrappers and core helpers'),
 ('20261104000200','read-only admin user search fix'),
 ('20261106000100','follow-only privacy and historical group API revokes'),
 ('20261106000300','latest report resolution and direct content moderation'),
 ('20261106000400','latest report detail and case media authorization'),
 ('20261107000900','Music metadata, retain unchanged'),
 ('20261107001000','comment tree, retain unchanged')
) AS expected(version,purpose) ORDER BY expected.version;

-- Actual signatures, volatility (s=stable, v=volatile), owner, path and effective ACL.
WITH names(name) AS (VALUES
 ('admin_dashboard'),('admin_search_users'),('admin_list_content'),
 ('admin_moderate_content'),('admin_set_account_status'),('admin_report_detail'),
 ('admin_audit_page'),('submit_content_report'),('list_platform_reports'),
 ('claim_platform_report'),('resolve_platform_report'),('get_platform_audit'),
 ('has_role'),('is_active'),('account_ok'),('require_identity'),('lock_profile'),
 ('b_actor'),('b_active'),('b_page'),('can_review_report'),('can_review_report_media'),
 ('can_read_media'),('can_read_object'),('can_read_message'),('emit_notification'),
 ('schedule_media_cleanup'),('report_group_target'),('list_group_reports'),
 ('resolve_group_report'),('audit_group_role_change'),('audit_group_request_change'),
 ('audit_group_post_change'))
SELECT n.nspname AS schema_name, p.proname, pg_get_function_identity_arguments(p.oid) AS arguments,
       p.provolatile, p.prosecdef AS security_definer, p.proconfig,
       owner.rolname AS owner, owner.rolbypassrls AS owner_bypass_rls,
       p.proacl, md5(pg_get_functiondef(p.oid)) AS definition_hash,
       p.provolatile='s' AND pg_get_functiondef(p.oid) LIKE '%b_actor()%' AS review_stable_writer_call
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
JOIN pg_roles owner ON owner.oid=p.proowner JOIN names ON names.name=p.proname
WHERE n.nspname IN ('public','private') ORDER BY p.proname,n.nspname,arguments;

-- Missing RPC signatures return NULL, without executing them.
SELECT signature, to_regprocedure(signature) AS installed
FROM (VALUES
 ('public.admin_dashboard()'),
 ('public.admin_search_users(text,timestamp with time zone,uuid,integer)'),
 ('public.admin_list_content(text,text,text,timestamp with time zone,uuid,integer)'),
 ('public.admin_moderate_content(text,uuid,text,text,uuid)'),
 ('public.admin_set_account_status(uuid,text,text,uuid)'),
 ('public.admin_report_detail(uuid)'),
 ('public.admin_audit_page(timestamp with time zone,uuid,integer)'),
 ('public.submit_content_report(text,uuid,text,text,uuid)'),
 ('public.list_platform_reports(text,timestamp with time zone,uuid,integer)'),
 ('public.claim_platform_report(uuid)'),
 ('public.resolve_platform_report(uuid,text,text)'),
 ('public.get_platform_audit(integer)')) AS expected(signature);

-- Effective EXECUTE for real database roles; function bodies must still enforce app roles.
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,
       r.rolname,has_function_privilege(r.oid,p.oid,'EXECUTE') AS can_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
CROSS JOIN pg_roles r
WHERE n.nspname IN ('public','private') AND r.rolname IN ('anon','authenticated','service_role')
  AND (p.proname LIKE 'admin_%' OR p.proname IN
    ('submit_content_report','list_platform_reports','claim_platform_report','resolve_platform_report',
     'get_platform_audit','report_group_target','list_group_reports','resolve_group_report',
     'can_review_report_media'))
ORDER BY p.proname,n.nspname,r.rolname;

-- Owner may export definitions and compare with the final migrations named in the audit.
-- pg_get_functiondef returns text only; no returned definition is executed.
SELECT n.nspname,p.proname,pg_get_functiondef(p.oid) AS installed_definition
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='private' AND p.proname IN
 ('admin_dashboard','admin_search_users','admin_list_content','admin_moderate_content',
  'admin_set_account_status','admin_report_detail','admin_audit_page','submit_content_report',
  'list_platform_reports','claim_platform_report','resolve_platform_report','get_platform_audit',
  'can_review_report_media','can_read_media','has_role','emit_notification')
ORDER BY p.proname;

SELECT n.nspname,c.relname,c.relrowsecurity AS rls_enabled,c.relforcerowsecurity AS rls_forced,
       pg_get_userbyid(c.relowner) AS table_owner,c.relacl
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE (n.nspname='public' AND c.relname IN
 ('system_roles','reports','moderation_actions','moderation_audit','appeals','account_status',
  'profiles','posts','comments','stories','messages','groups','media_assets','notifications','event_outbox'))
   OR (n.nspname='storage' AND c.relname='objects')
ORDER BY n.nspname,c.relname;

SELECT schemaname,tablename,policyname,permissive,roles,cmd,qual,with_check
FROM pg_policies WHERE
 (schemaname='public' AND tablename IN
  ('system_roles','reports','moderation_actions','moderation_audit','appeals','account_status','media_assets'))
 OR (schemaname='storage' AND tablename='objects')
ORDER BY schemaname,tablename,policyname;

SELECT n.nspname,c.relname,r.rolname,
       has_table_privilege(r.oid,c.oid,'SELECT') AS can_select,
       has_table_privilege(r.oid,c.oid,'INSERT') AS can_insert,
       has_table_privilege(r.oid,c.oid,'UPDATE') AS can_update,
       has_table_privilege(r.oid,c.oid,'DELETE') AS can_delete,
       has_table_privilege(r.oid,c.oid,'TRUNCATE') AS can_truncate
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN pg_roles r
WHERE n.nspname='public' AND c.relname IN
 ('system_roles','reports','moderation_actions','moderation_audit','appeals','account_status')
 AND r.rolname IN ('anon','authenticated','service_role') ORDER BY c.relname,r.rolname;

SELECT table_schema,table_name,column_name,grantee,privilege_type
FROM information_schema.column_privileges
WHERE table_schema='public' AND table_name IN
 ('system_roles','reports','moderation_actions','moderation_audit','account_status')
 AND grantee IN ('PUBLIC','anon','authenticated') ORDER BY table_name,column_name,grantee;

SELECT c.relname AS table_name,k.conname,pg_get_constraintdef(k.oid) AS definition
FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN
 ('system_roles','reports','moderation_actions','moderation_audit','appeals','account_status',
  'posts','comments','stories','groups','messages','notifications')
ORDER BY c.relname,k.conname;

SELECT schemaname,tablename,indexname,indexdef FROM pg_indexes
WHERE schemaname='public' AND tablename IN
 ('reports','moderation_actions','moderation_audit','profiles','posts','comments','account_status')
ORDER BY tablename,indexname;

-- Includes disabled/internal triggers: distinguish FK enforcement from custom audit/notification hooks.
SELECT n.nspname,c.relname,t.tgname,t.tgenabled,t.tgisinternal,
       pg_get_triggerdef(t.oid) AS trigger_definition
FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN
 ('reports','moderation_actions','moderation_audit','system_roles','account_status','posts','comments','notifications')
ORDER BY c.relname,t.tgname;

SELECT table_schema,table_name,column_name,data_type,is_nullable,column_default
FROM information_schema.columns WHERE table_schema='public' AND table_name IN
 ('reports','moderation_actions','moderation_audit','system_roles','account_status')
ORDER BY table_name,ordinal_position;

SELECT n.nspname,n.nspacl FROM pg_namespace n WHERE n.nspname IN ('public','private','storage');
SELECT r.rolname,r.rolsuper,r.rolbypassrls FROM pg_roles r
WHERE r.rolname IN ('anon','authenticated','service_role','postgres','authenticator');

-- No auth.users, message bodies, reporter details, tokens or Storage object paths are selected.
-- Evidence lifetime / worker schedules / role assignment must be verified separately by owner.
