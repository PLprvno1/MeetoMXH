DO $$ BEGIN IF current_database()<>'meeto_phase05'
 OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
 THEN RAISE EXCEPTION 'Wrong local test cluster'; END IF; END $$;
-- Privileged fixtures emulate a legacy pair with relations needing cleanup.
INSERT INTO public.friendships(user_low,user_high) VALUES(test_a.id(1),test_a.id(2));
INSERT INTO public.friend_requests(id,requester_id,recipient_id,client_request_id)
VALUES(test_a.id(300),test_a.id(1),test_a.id(2),test_a.id(301));
INSERT INTO public.user_follows(follower_id,followed_id)
VALUES(test_a.id(1),test_a.id(2)),(test_a.id(2),test_a.id(1));
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',test_a.id(2)::text,false),set_config('request.jwt.claims','{}',false);
SELECT public.set_block(test_a.id(1),true);
RESET ROLE;
SELECT test_a.ok('block removes friendship',NOT EXISTS(SELECT 1 FROM public.friendships WHERE user_low=test_a.id(1) AND user_high=test_a.id(2)));
SELECT test_a.ok('block cancels pending request',(SELECT state='cancelled' AND resolved_at IS NOT NULL FROM public.friend_requests WHERE id=test_a.id(300)));
SELECT test_a.ok('block removes both follows',NOT EXISTS(SELECT 1 FROM public.user_follows WHERE follower_id IN(test_a.id(1),test_a.id(2)) AND followed_id IN(test_a.id(1),test_a.id(2))));
SET ROLE authenticated;
SELECT public.set_block(test_a.id(1),false);
RESET ROLE;
SELECT test_a.ok('unblock never restores relations',NOT EXISTS(SELECT 1 FROM public.friendships WHERE user_low=test_a.id(1) AND user_high=test_a.id(2)) AND NOT EXISTS(SELECT 1 FROM public.friend_requests WHERE id=test_a.id(300) AND state='pending'));
SELECT jsonb_build_object('passed',count(*) FILTER(WHERE passed),'failed',count(*) FILTER(WHERE NOT passed),'scope','PostgreSQL phase A with stubs') FROM test_a.results;
