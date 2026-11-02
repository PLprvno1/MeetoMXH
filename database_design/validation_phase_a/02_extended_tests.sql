-- Run after 01, only the isolated synthetic local database.
DO $$ BEGIN IF current_database()<>'meeto_phase05'
 OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
 THEN RAISE EXCEPTION 'Wrong local test cluster'; END IF; END $$;
-- All checks use real phase-A RPC bodies, but identities remain SQL stubs.
INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,state,mime_type,size_bytes)
VALUES(test_a.id(101),test_a.id(1),test_a.id(201),'profile-media','synthetic/avatar-1','avatar','ready','image/jpeg',100),
(test_a.id(102),test_a.id(1),test_a.id(202),'profile-media','synthetic/avatar-2','avatar','ready','image/jpeg',100),
(test_a.id(103),test_a.id(2),test_a.id(203),'profile-media','synthetic/other-owner','avatar','ready','image/jpeg',100),
(test_a.id(104),test_a.id(1),test_a.id(204),'profile-media','synthetic/cover','cover','ready','image/jpeg',100),
(test_a.id(105),test_a.id(1),test_a.id(205),'profile-media','synthetic/pending','avatar','pending','image/jpeg',100);
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',test_a.id(1)::text,false),set_config('request.jwt.claims','{"is_anonymous":false}',false);
SELECT test_a.deny('wrong owner attachment','SELECT public.update_profile(''Alice'',''bio'',test_a.id(103),NULL,4)','PT422');
SELECT test_a.deny('wrong purpose attachment','SELECT public.update_profile(''Alice'',''bio'',test_a.id(104),NULL,4)','PT422');
SELECT test_a.deny('pending attachment','SELECT public.update_profile(''Alice'',''bio'',test_a.id(105),NULL,4)','PT422');
SELECT test_a.ok('owned ready avatar attach',(public.update_profile('Alice','bio',test_a.id(101),NULL,4)->>'version')='5');
SELECT test_a.ok('replace avatar and set cover',(public.update_profile('Alice','bio',test_a.id(102),test_a.id(104),5)->>'version')='6');
RESET ROLE;
SELECT test_a.ok('old avatar disabled',(SELECT state='pending_delete' FROM public.media_assets WHERE id=test_a.id(101)));
SELECT test_a.ok('retire outbox atomic',(SELECT count(*)=1 FROM public.event_outbox WHERE event_key='profile-media-retire:'||test_a.id(101)::text));
DELETE FROM public.profile_private WHERE user_id=test_a.id(1);
SET ROLE authenticated;
SELECT test_a.deny('RPC partial write rolls back',$q$SELECT public.update_profile('Should rollback','changed',NULL,NULL,6)$q$,'PT409');
RESET ROLE;
SELECT test_a.ok('profile mutation rolled back',(SELECT display_name='Alice' AND version=6 AND avatar_asset_id=test_a.id(102) AND cover_asset_id=test_a.id(104) FROM public.profiles WHERE id=test_a.id(1)));
SELECT test_a.ok('asset transition rolled back',(SELECT state='ready' FROM public.media_assets WHERE id=test_a.id(102)));
SELECT test_a.ok('outbox transition rolled back',NOT EXISTS(SELECT 1 FROM public.event_outbox WHERE event_key='profile-media-retire:'||test_a.id(102)::text));
SET ROLE authenticated;
SELECT test_a.ok('recovery does not reset public profile',(public.bootstrap_profile()->>'version')='6');
RESET ROLE;
-- Missing whole profile: identity authority remains auth.users; bootstrap recreates safely.
INSERT INTO auth.users(id,email_confirmed_at) VALUES(test_a.id(6),now());
DELETE FROM public.profiles WHERE id=test_a.id(6);
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',test_a.id(6)::text,false);
SELECT test_a.deny('whole missing profile detectable','SELECT public.get_my_profile()','PT409');
SELECT test_a.ok('whole profile recovered',(public.bootstrap_profile()->>'onboarding_completed')='false');
SELECT test_a.deny('recovered user cannot bypass onboarding',$q$SELECT public.update_profile('New','',NULL,NULL,1)$q$,'PT403');
RESET ROLE;
UPDATE public.account_status SET state='deleting',deletion_requested_at=now() WHERE user_id=test_a.id(5);
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',test_a.id(5)::text,false);
SELECT test_a.ok('deleting only status',(public.get_my_profile()->>'account_state')='deleting' AND NOT(public.get_my_profile() ? 'settings'));
SELECT test_a.deny('deleting cannot mutate',$q$SELECT public.update_profile_private('2000-01-01',NULL,2)$q$,'PT403');
SELECT test_a.ok('deleting bootstrap no resurrection',(public.bootstrap_profile()->>'account_state')='deleting');
RESET ROLE;
-- Default ACL probes for both namespaces, removed after checks.
CREATE FUNCTION public.phase_a_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1';
CREATE FUNCTION private.phase_a_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1';
CREATE TABLE public.phase_a_probe_table(id integer);
SELECT test_a.ok('new public function closed',NOT has_function_privilege('authenticated','public.phase_a_probe()','EXECUTE') AND NOT has_function_privilege('anon','public.phase_a_probe()','EXECUTE'));
SELECT test_a.ok('new private function closed',NOT has_function_privilege('authenticated','private.phase_a_probe()','EXECUTE'));
SELECT test_a.ok('new table closed',NOT has_table_privilege('authenticated','public.phase_a_probe_table','SELECT'));
DROP TABLE public.phase_a_probe_table;
DROP FUNCTION public.phase_a_probe(),private.phase_a_probe();
SELECT test_a.ok('one migration owner', (SELECT count(DISTINCT p.proowner)=1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private')));
SELECT test_a.ok('no app function anon execute',NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('public','private') AND has_function_privilege('anon',p.oid,'EXECUTE')));
SELECT test_a.ok('future column grants also closed',NOT has_any_column_privilege('authenticated','public.messages','SELECT') AND NOT has_any_column_privilege('authenticated','public.posts','SELECT') AND NOT has_any_column_privilege('authenticated','public.comments','SELECT') AND NOT has_any_column_privilege('authenticated','public.device_subscriptions','SELECT'));
SELECT jsonb_build_object('passed',count(*) FILTER(WHERE passed),'failed',count(*) FILTER(WHERE NOT passed),'scope','PostgreSQL phase A with stubs') FROM test_a.results;
