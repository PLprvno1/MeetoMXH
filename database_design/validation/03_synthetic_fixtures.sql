-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
-- Tổng hợp hoàn toàn cho PostgreSQL local; không phải data Meeto thật.
CREATE SCHEMA audit;
CREATE TABLE audit.results (test text PRIMARY KEY, passed boolean NOT NULL);
GRANT USAGE ON SCHEMA audit TO anon,authenticated;
GRANT INSERT ON audit.results TO anon,authenticated;
CREATE FUNCTION audit.id(n integer) RETURNS uuid LANGUAGE sql IMMUTABLE AS $$
 SELECT ('00000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid;
$$;
CREATE FUNCTION audit.assert_true(label text, condition boolean) RETURNS void LANGUAGE plpgsql AS $$
 BEGIN
  IF condition IS DISTINCT FROM true THEN RAISE EXCEPTION 'ASSERTION FAILED: %',label; END IF;
  INSERT INTO audit.results VALUES(label,true);
 END;
$$;
CREATE FUNCTION audit.expect_state(label text, statement text, wanted text) RETURNS void LANGUAGE plpgsql AS $$
 DECLARE got text;
 BEGIN
  BEGIN
   EXECUTE statement;
  EXCEPTION WHEN OTHERS THEN
   GET STACKED DIAGNOSTICS got=RETURNED_SQLSTATE;
   IF got<>wanted THEN RAISE EXCEPTION 'ASSERTION % expected %, got %',label,wanted,got; END IF;
   INSERT INTO audit.results VALUES(label,true);
   RETURN;
  END;
  RAISE EXCEPTION 'ASSERTION % expected error %, command succeeded',label,wanted;
 END;
$$;
GRANT EXECUTE ON FUNCTION audit.id(integer),audit.assert_true(text,boolean),audit.expect_state(text,text,text) TO anon,authenticated;
INSERT INTO auth.users
SELECT audit.id(i),CASE WHEN i<>9 THEN now() ELSE NULL END,NULL FROM generate_series(1,9)i;
INSERT INTO public.profiles(id,display_name,onboarding_completed)
SELECT audit.id(i),'Synthetic user '||i,(i<>7) FROM generate_series(1,9)i;
INSERT INTO public.user_settings(user_id) SELECT audit.id(i) FROM generate_series(1,9)i;
INSERT INTO public.profile_private(user_id,birth_date,gender) SELECT audit.id(i),DATE '2000-01-01','test' FROM generate_series(1,9)i;
INSERT INTO public.account_status(user_id,state) SELECT audit.id(i),CASE WHEN i=6 THEN 'suspended' ELSE 'active' END FROM generate_series(1,9)i;
INSERT INTO public.system_roles(user_id,role) VALUES(audit.id(4),'moderator'),(audit.id(5),'system_admin');
INSERT INTO public.friendships(user_low,user_high) VALUES(audit.id(1),audit.id(2));
INSERT INTO public.groups(id,created_by,client_request_id,name,privacy)
VALUES(audit.id(201),audit.id(1),audit.id(8201),'Synthetic private group','private'),
(audit.id(202),audit.id(1),audit.id(8202),'Synthetic public group','public');
INSERT INTO public.group_members(group_id,user_id,role)
VALUES(audit.id(201),audit.id(1),'admin'),(audit.id(201),audit.id(2),'member'),
(audit.id(202),audit.id(1),'admin');
INSERT INTO public.posts(id,author_id,client_request_id,request_hash,body,audience,group_id,kind,original_post_id,state)
VALUES
(audit.id(101),audit.id(1),audit.id(8101),repeat('a',64),'Public text','public',NULL,'original',NULL,'published'),
(audit.id(102),audit.id(1),audit.id(8102),repeat('a',64),'Friends text','friends',NULL,'original',NULL,'published'),
(audit.id(103),audit.id(1),audit.id(8103),repeat('a',64),'Only me text','private',NULL,'original',NULL,'published'),
(audit.id(104),audit.id(3),audit.id(8104),repeat('a',64),'Share commentary','public',NULL,'share',audit.id(102),'published'),
(audit.id(105),audit.id(1),audit.id(8105),repeat('a',64),'Hidden moderation text','public',NULL,'original',NULL,'hidden'),
(audit.id(106),audit.id(1),audit.id(8106),repeat('a',64),'Private group text','group',audit.id(201),'original',NULL,'published');
INSERT INTO public.comments(id,post_id,author_id,parent_id,client_request_id,request_hash,body,state)
VALUES(audit.id(301),audit.id(101),audit.id(1),NULL,audit.id(8301),repeat('b',64),'Root','published'),
(audit.id(302),audit.id(101),audit.id(2),audit.id(301),audit.id(8302),repeat('b',64),'Reply','published'),
(audit.id(303),audit.id(101),audit.id(1),NULL,audit.id(8303),repeat('b',64),NULL,'deleted'),
(audit.id(304),audit.id(101),audit.id(1),NULL,audit.id(8304),repeat('b',64),'Hidden comment','hidden');
INSERT INTO public.conversations(id,kind,direct_low,direct_high,created_by,client_request_id,next_seq)
VALUES(audit.id(401),'private',audit.id(1),audit.id(2),audit.id(1),NULL,3),
(audit.id(402),'group',NULL,NULL,audit.id(1),audit.id(8402),3),
(audit.id(403),'private',audit.id(1),audit.id(4),audit.id(1),NULL,1);
INSERT INTO public.conversation_members(conversation_id,user_id,role,history_from_seq)
VALUES(audit.id(401),audit.id(1),'member',1),(audit.id(401),audit.id(2),'member',1),
(audit.id(402),audit.id(1),'admin',1),(audit.id(402),audit.id(2),'member',1),(audit.id(402),audit.id(3),'member',3),
(audit.id(403),audit.id(1),'member',1),(audit.id(403),audit.id(4),'member',1);
INSERT INTO public.messages(id,conversation_id,seq,sender_id,client_request_id,request_hash,kind,body,state)
VALUES(audit.id(501),audit.id(401),1,audit.id(2),audit.id(8501),repeat('c',64),'text','Incoming text','sent'),
(audit.id(502),audit.id(401),2,audit.id(1),audit.id(8502),repeat('c',64),'media',NULL,'sent'),
(audit.id(503),audit.id(401),3,audit.id(1),audit.id(8503),repeat('c',64),'media',NULL,'recalled'),
(audit.id(504),audit.id(403),1,audit.id(1),audit.id(8504),repeat('c',64),'text','Other conversation','sent'),
(audit.id(511),audit.id(402),1,audit.id(1),audit.id(8511),repeat('c',64),'text','Before join1','sent'),
(audit.id(512),audit.id(402),2,audit.id(1),audit.id(8512),repeat('c',64),'text','Before join2','sent'),
(audit.id(513),audit.id(402),3,audit.id(1),audit.id(8513),repeat('c',64),'text','After join','sent');
UPDATE public.messages SET reply_to_message_id=audit.id(501) WHERE id=audit.id(502);
UPDATE public.conversations SET last_message_id=audit.id(503) WHERE id=audit.id(401);
INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,state,mime_type,size_bytes)
VALUES(audit.id(601),audit.id(1),audit.id(8601),'social-media','test/post.jpg','post','ready','image/jpeg',1024),
(audit.id(602),audit.id(1),audit.id(8602),'chat-media','test/chat.jpg','message','ready','image/jpeg',1024),
(audit.id(603),audit.id(1),audit.id(8603),'chat-media','test/recalled.jpg','message','ready','image/jpeg',1024),
(audit.id(604),audit.id(1),audit.id(8604),'social-media','test/pending.jpg','post','pending','image/jpeg',1024),
(audit.id(605),audit.id(1),audit.id(8605),'social-media','test/expired.jpg','story','ready','image/jpeg',1024),
(audit.id(606),audit.id(1),audit.id(8606),'social-media','test/live.jpg','story','ready','image/jpeg',1024);
INSERT INTO public.post_media VALUES(audit.id(102),audit.id(601),0);
INSERT INTO public.message_media VALUES(audit.id(502),audit.id(602),0),(audit.id(503),audit.id(603),0);
INSERT INTO storage.objects(bucket_id,name,owner_id) SELECT bucket_id,object_path,owner_id::text FROM public.media_assets;
INSERT INTO public.stories(id,author_id,media_asset_id,client_request_id,created_at,expires_at,audience)
VALUES(audit.id(701),audit.id(1),audit.id(605),audit.id(8701),now()-interval '25 hours',now()-interval '1 hour','public'),
(audit.id(702),audit.id(1),audit.id(606),audit.id(8702),now(),now()+interval '24 hours','friends');
INSERT INTO public.reports(id,reporter_id,client_request_id,target_kind,post_id,reason_code,state,assigned_to)
VALUES(audit.id(901),audit.id(2),audit.id(8901),'post',audit.id(101),'test','assigned',audit.id(4)),
(audit.id(902),audit.id(2),audit.id(8902),'post',audit.id(101),'test','open',NULL);
SELECT 'SYNTHETIC_FIXTURES_ONLY';

