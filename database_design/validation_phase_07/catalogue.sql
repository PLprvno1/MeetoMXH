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
SELECT pg_temp.ok('49 public RPC wrappers',(SELECT count(*)=49 FROM pg_proc WHERE pronamespace='public'::regnamespace));
SELECT pg_temp.ok('no PUBLIC EXECUTE on app functions',NOT EXISTS(SELECT 1 FROM pg_proc p CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.pronamespace IN ('public'::regnamespace,'private'::regnamespace) AND a.grantee=0 AND a.privilege_type='EXECUTE'));
SELECT pg_temp.ok('all definer functions have empty search_path',NOT EXISTS(SELECT 1 FROM pg_proc p WHERE p.pronamespace IN ('public'::regnamespace,'private'::regnamespace) AND p.prosecdef AND NOT coalesce(p.proconfig @> ARRAY['search_path=""'],false)));
SELECT pg_temp.ok('definer owner has BYPASSRLS',NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_roles r ON r.oid=p.proowner WHERE p.pronamespace='private'::regnamespace AND p.prosecdef AND NOT r.rolbypassrls));
SELECT pg_temp.ok('anon authenticated roles cannot bypass RLS',(SELECT bool_and(NOT rolbypassrls AND NOT rolsuper) FROM pg_roles WHERE rolname IN ('anon','authenticated')));
SELECT pg_temp.ok('RPC grants authenticated only',(SELECT bool_and(has_function_privilege('authenticated',oid,'EXECUTE') AND NOT has_function_privilege('anon',oid,'EXECUTE')) FROM pg_proc WHERE pronamespace='public'::regnamespace));
SELECT pg_temp.ok('internal actor and worker helpers denied',NOT has_function_privilege('authenticated','private.bootstrap_identity(uuid)','EXECUTE') AND NOT has_function_privilege('authenticated','private.lock_profile(boolean)','EXECUTE') AND NOT has_function_privilege('authenticated','private.record_media_verification(uuid,uuid,bigint,text,integer,integer,numeric)','EXECUTE') AND NOT has_function_privilege('authenticated','private.schedule_media_cleanup()','EXECUTE') AND NOT has_function_privilege('authenticated','private.send_friend_request_unreceipted(uuid,uuid)','EXECUTE') AND NOT has_function_privilege('authenticated','private.c_actor()','EXECUTE') AND NOT has_function_privilege('authenticated','private.chat_dispatch_recipients(uuid)','EXECUTE'));
SELECT pg_temp.ok('future table and column privileges closed',NOT EXISTS(
 SELECT 1 FROM pg_class c WHERE c.relnamespace='public'::regnamespace AND c.relkind='r'
 AND c.relname NOT IN ('profiles','profile_private','user_settings','account_status','user_blocks','media_assets','posts','comments','post_media','post_reactions','friend_requests','friendships','conversations','conversation_members','messages','message_hidden','message_media')
 AND (has_table_privilege('authenticated',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege('authenticated',c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))));
SELECT pg_temp.ok('GoTrue-owned real auth.users',(SELECT pg_get_userbyid(relowner)='supabase_auth_admin' FROM pg_class WHERE oid='auth.users'::regclass));
SELECT pg_temp.ok('real bootstrap trigger owner postgres',(SELECT pg_get_userbyid(p.proowner)='postgres' FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid WHERE t.tgname='meeto_bootstrap_user' AND t.tgrelid='auth.users'::regclass));
SELECT pg_temp.ok('Storage operation ABI exists',to_regprocedure('storage.allow_any_operation(text[])') IS NOT NULL AND to_regprocedure('storage.operation()') IS NOT NULL);
SELECT pg_temp.ok('Storage objects owner_id text',(SELECT format_type(atttypid,atttypmod)='text' FROM pg_attribute WHERE attrelid='storage.objects'::regclass AND attname='owner_id'));
SELECT pg_temp.ok('three private A B C buckets',(SELECT count(*)=3 AND bool_and(NOT public) FROM storage.buckets));
SELECT pg_temp.ok('five scoped Storage policies',(SELECT count(*)=5 FROM pg_policies WHERE schemaname='storage' AND tablename='objects'));
SELECT pg_temp.ok('Realtime topic ABI exists',to_regprocedure('realtime.topic()') IS NOT NULL);
SELECT pg_temp.ok('no app table replication enabled',NOT EXISTS(SELECT 1 FROM pg_publication_tables WHERE schemaname='public'));
SELECT pg_temp.ok('CLI ledger has exact 15 versions',(SELECT array_agg(version ORDER BY version)=ARRAY['20261101000100','20261101000200','20261101000300','20261101000400','20261101000500','20261101000600','20261101000700','20261101000800','20261101000900','20261101001000','20261101001100','20261101001200','20261101001300','20261101001400','20261101001500'] FROM supabase_migrations.schema_migrations));
CREATE FUNCTION private.integration_default_probe() RETURNS boolean LANGUAGE sql AS $$ SELECT true $$;
CREATE TABLE private.integration_default_table(id integer);
SELECT pg_temp.ok('new function default EXECUTE deny',NOT has_function_privilege('authenticated','private.integration_default_probe()','EXECUTE') AND NOT has_function_privilege('anon','private.integration_default_probe()','EXECUTE'));
SELECT pg_temp.ok('new table default access deny',NOT has_table_privilege('authenticated','private.integration_default_table','SELECT,INSERT,UPDATE,DELETE') AND NOT has_table_privilege('anon','private.integration_default_table','SELECT,INSERT,UPDATE,DELETE'));
SELECT pg_temp.ok('private pair has exactly two matching active members',NOT EXISTS(SELECT 1 FROM public.conversations c WHERE c.kind='private' AND ((SELECT count(*) FROM public.conversation_members cm WHERE cm.conversation_id=c.id)<>2 OR (SELECT count(*) FROM public.conversation_members cm WHERE cm.conversation_id=c.id AND cm.user_id IN (c.direct_low,c.direct_high) AND cm.state='active')<>2)));
SELECT pg_temp.ok('sequence contiguous and high water reconciles',NOT EXISTS(SELECT 1 FROM public.conversations c WHERE c.next_seq<>coalesce((SELECT max(m.seq) FROM public.messages m WHERE m.conversation_id=c.id),0) OR c.next_seq<>(SELECT count(*) FROM public.messages m WHERE m.conversation_id=c.id)));
SELECT pg_temp.ok('markers bounded by stream and read implies delivered',NOT EXISTS(SELECT 1 FROM public.conversation_members cm JOIN public.conversations c ON c.id=cm.conversation_id WHERE cm.last_read_seq>cm.last_delivered_seq OR cm.last_delivered_seq>c.next_seq OR cm.history_from_seq>c.next_seq+1));
SELECT pg_temp.ok('exactly one transactional send event per message',NOT EXISTS(SELECT 1 FROM public.messages m WHERE (SELECT count(*) FROM public.event_outbox e WHERE e.event_type='chat.invalidate' AND e.payload->>'kind'='message' AND e.payload->>'message_id'=m.id::text)<>1));
SELECT pg_temp.ok('each live group has an operative admin',NOT EXISTS(SELECT 1 FROM public.conversations c WHERE c.kind='group' AND c.state IN ('active','archived') AND NOT EXISTS(SELECT 1 FROM public.conversation_members cm JOIN public.profiles p ON p.id=cm.user_id AND p.onboarding_completed JOIN public.account_status s ON s.user_id=p.id AND s.state='active' JOIN auth.users u ON u.id=p.id AND u.email_confirmed_at IS NOT NULL WHERE cm.conversation_id=c.id AND cm.role='admin' AND cm.state='active')));
SELECT pg_temp.ok('Realtime own user receive-only policy',(SELECT count(*)=1 AND bool_and(cmd='SELECT' AND qual LIKE '%realtime.topic()%') FROM pg_policies WHERE schemaname='realtime' AND tablename='messages'));
SELECT pg_temp.ok('Realtime private-only tenant',(SELECT count(*)=1 AND bool_and(private_only) FROM _realtime.tenants WHERE external_id='realtime-dev'));
SELECT pg_temp.ok('Storage standard upload restrictive gate',(SELECT count(*)=1 FROM pg_policies WHERE schemaname='storage' AND tablename='objects' AND policyname='meeto_standard_upload_only' AND permissive='RESTRICTIVE' AND cmd='INSERT' AND with_check LIKE '%object.upload%'));
SELECT json_build_object('environment','REAL SUPABASE POSTGRES 17 CATALOG','passed',count(*),'failed',0,'tests',json_agg(row_to_json(r) ORDER BY label)) FROM integration_results r;
ROLLBACK;
