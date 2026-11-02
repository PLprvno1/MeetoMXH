-- Operator-only SQL invariant probe, NOT JWT/HTTP evidence. Entirely rolled back.
BEGIN;
DO $$
DECLARE actor uuid; pid uuid; aid uuid; orphan uuid:='ffffffff-0000-0000-0000-000000000006'; g integer;
BEGIN
 IF current_database()<>'postgres' OR to_regclass('supabase_migrations.schema_migrations') IS NULL
 OR EXISTS(SELECT 1 FROM public.media_assets WHERE id::text LIKE '06000000-%' OR id=orphan)
 OR EXISTS(SELECT 1 FROM public.posts WHERE id::text LIKE '07000000-%') THEN RAISE EXCEPTION 'Wrong backlog fixture scope'; END IF;
 SELECT p.id INTO STRICT actor FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id WHERE s.state='active' AND p.onboarding_completed ORDER BY p.id LIMIT 1;
 FOR g IN 1..101 LOOP
  aid:=('06000000-0000-0000-0000-'||lpad(g::text,12,'0'))::uuid;
  pid:=('07000000-0000-0000-0000-'||lpad(g::text,12,'0'))::uuid;
  INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,state,mime_type,size_bytes,created_at)
   VALUES(aid,actor,gen_random_uuid(),'social-media',actor::text||'/'||aid::text||'/original.png','post','ready','image/png',100,now()-interval '48 hours');
  INSERT INTO public.posts(id,author_id,client_request_id,request_hash,body) VALUES(pid,actor,gen_random_uuid(),repeat('0',64),'synthetic backlog linked media');
  INSERT INTO public.post_media(post_id,asset_id,position) VALUES(pid,aid,0);
 END LOOP;
 INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,state,mime_type,size_bytes,created_at)
  VALUES(orphan,actor,gen_random_uuid(),'social-media',actor::text||'/'||orphan::text||'/original.png','post','ready','image/png',100,now()-interval '48 hours');
 PERFORM private.schedule_media_cleanup();
 IF (SELECT state FROM public.media_assets WHERE id=orphan)<>'pending_delete'
 OR EXISTS(SELECT 1 FROM public.media_assets WHERE id::text LIKE '06000000-%' AND state<>'ready') THEN
  RAISE EXCEPTION 'Linked rows starved orphan cleanup or attached media retired'; END IF;
END $$;
SELECT json_build_object('environment','LOCAL PG17 SQL rollback; not HTTP','passed',1,'failed',0,'test','101 older linked assets do not starve the next orphan; linked media remains ready');
ROLLBACK;
