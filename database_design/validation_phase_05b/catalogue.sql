-- Real Supabase catalog/ACL checks. All probes are rolled back.
BEGIN;
DO $$ BEGIN
 IF current_database()<>'postgres' OR to_regclass('supabase_migrations.schema_migrations') IS NULL
 OR current_setting('server_version_num')::int < 170000 THEN RAISE EXCEPTION 'Wrong integration database'; END IF;
END $$;
CREATE TEMP TABLE integration_results(label text PRIMARY KEY,passed boolean NOT NULL);
CREATE FUNCTION pg_temp.ok(label text,value boolean) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 IF value IS DISTINCT FROM true THEN RAISE EXCEPTION 'Assertion failed: %',label; END IF;
 INSERT INTO integration_results VALUES(label,true);
END $$;
SELECT pg_temp.ok('36 application tables',(SELECT count(*)=36 FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='r'));
SELECT pg_temp.ok('79 foreign keys',(SELECT count(*)=79 FROM pg_constraint WHERE connamespace='public'::regnamespace AND contype='f'));
SELECT pg_temp.ok('104 CHECK constraints',(SELECT count(*)=104 FROM pg_constraint WHERE connamespace='public'::regnamespace AND contype='c'));
SELECT pg_temp.ok('110 indexes',(SELECT count(*)=110 FROM pg_indexes WHERE schemaname='public'));
SELECT pg_temp.ok('all 36 tables enable RLS',(SELECT bool_and(relrowsecurity) FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind='r'));
SELECT pg_temp.ok('21 boolean authorization helpers',(SELECT count(*)=21 FROM pg_proc WHERE pronamespace='private'::regnamespace AND prorettype='boolean'::regtype AND prosecdef AND provolatile='s'));
SELECT pg_temp.ok('8 public RPC wrappers',(SELECT count(*)=8 FROM pg_proc WHERE pronamespace='public'::regnamespace));
SELECT pg_temp.ok('no PUBLIC EXECUTE on app functions',NOT EXISTS(SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.pronamespace IN ('public'::regnamespace,'private'::regnamespace) AND a.grantee=0 AND a.privilege_type='EXECUTE'));
SELECT pg_temp.ok('all definer functions have empty search_path',NOT EXISTS(SELECT 1 FROM pg_proc p WHERE p.pronamespace IN ('public'::regnamespace,'private'::regnamespace) AND p.prosecdef AND NOT coalesce(p.proconfig @> ARRAY['search_path=""'],false)));
SELECT pg_temp.ok('definer owner has BYPASSRLS',NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_roles r ON r.oid=p.proowner WHERE p.pronamespace='private'::regnamespace AND p.prosecdef AND NOT r.rolbypassrls));
SELECT pg_temp.ok('anon authenticated roles cannot bypass RLS',(SELECT bool_and(NOT rolbypassrls AND NOT rolsuper) FROM pg_roles WHERE rolname IN ('anon','authenticated')));
SELECT pg_temp.ok('RPC grants authenticated only',(SELECT bool_and(has_function_privilege('authenticated',oid,'EXECUTE') AND NOT has_function_privilege('anon',oid,'EXECUTE')) FROM pg_proc WHERE pronamespace='public'::regnamespace));
SELECT pg_temp.ok('internal actor helpers denied',NOT has_function_privilege('authenticated','private.bootstrap_identity(uuid)','EXECUTE') AND NOT has_function_privilege('authenticated','private.lock_profile(boolean)','EXECUTE'));
SELECT pg_temp.ok('future table and column privileges closed',NOT EXISTS(
 SELECT 1 FROM pg_class c WHERE c.relnamespace='public'::regnamespace AND c.relkind='r'
 AND c.relname NOT IN ('profiles','profile_private','user_settings','account_status','user_blocks','media_assets')
 AND (has_table_privilege('authenticated',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege('authenticated',c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))));
SELECT pg_temp.ok('GoTrue-owned real auth.users',(SELECT pg_get_userbyid(relowner)='supabase_auth_admin' FROM pg_class WHERE oid='auth.users'::regclass));
SELECT pg_temp.ok('real bootstrap trigger owner postgres',(SELECT pg_get_userbyid(p.proowner)='postgres' FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid WHERE t.tgname='meeto_bootstrap_user' AND t.tgrelid='auth.users'::regclass));
SELECT pg_temp.ok('Storage operation ABI exists',to_regprocedure('storage.allow_any_operation(text[])') IS NOT NULL AND to_regprocedure('storage.operation()') IS NOT NULL);
SELECT pg_temp.ok('Storage objects owner_id text',(SELECT format_type(atttypid,atttypmod)='text' FROM pg_attribute WHERE attrelid='storage.objects'::regclass AND attname='owner_id'));
SELECT pg_temp.ok('no buckets prematurely enabled',(SELECT count(*)=0 FROM storage.buckets));
SELECT pg_temp.ok('no Meeto managed policies prematurely enabled',NOT EXISTS(SELECT 1 FROM pg_policies WHERE schemaname IN ('storage','realtime') AND (qual LIKE '%private.%' OR with_check LIKE '%private.%')));
SELECT pg_temp.ok('Realtime topic ABI exists',to_regprocedure('realtime.topic()') IS NOT NULL);
SELECT pg_temp.ok('no app table replication enabled',NOT EXISTS(SELECT 1 FROM pg_publication_tables WHERE schemaname='public'));
SELECT pg_temp.ok('CLI ledger has exact 3 versions',(SELECT array_agg(version ORDER BY version)=ARRAY['20261101000100','20261101000200','20261101000300'] FROM supabase_migrations.schema_migrations));
CREATE FUNCTION private.integration_default_probe() RETURNS boolean LANGUAGE sql AS $$ SELECT true $$;
CREATE TABLE private.integration_default_table(id integer);
SELECT pg_temp.ok('new function default EXECUTE deny',NOT has_function_privilege('authenticated','private.integration_default_probe()','EXECUTE') AND NOT has_function_privilege('anon','private.integration_default_probe()','EXECUTE'));
SELECT pg_temp.ok('new table default access deny',NOT has_table_privilege('authenticated','private.integration_default_table','SELECT,INSERT,UPDATE,DELETE') AND NOT has_table_privilege('anon','private.integration_default_table','SELECT,INSERT,UPDATE,DELETE'));
SELECT json_build_object('environment','REAL SUPABASE POSTGRES 17 CATALOG','passed',count(*),'failed',0,'tests',json_agg(row_to_json(r) ORDER BY label)) FROM integration_results r;
ROLLBACK;
