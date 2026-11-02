-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
BEGIN;
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(1)::text,true);
SELECT set_config('request.jwt.claims','{"is_anonymous":false}',true);
SELECT audit.expect_state('regression.pref_unknown_key',
 $$UPDATE public.user_settings SET notification_preferences='{"unexpected":true}' WHERE user_id=audit.id(1)$$,'23514');
SELECT audit.expect_state('regression.pref_nested_value',
 $$UPDATE public.user_settings SET notification_preferences='{"message":{"enabled":true}}' WHERE user_id=audit.id(1)$$,'23514');
SELECT audit.expect_state('regression.pref_string_value',
 $$UPDATE public.user_settings SET notification_preferences='{"message":"yes"}' WHERE user_id=audit.id(1)$$,'23514');
SELECT audit.expect_state('regression.pref_oversize',
 $$UPDATE public.user_settings SET notification_preferences=jsonb_build_object('message',repeat('x',5000)) WHERE user_id=audit.id(1)$$,'23514');
UPDATE public.user_settings SET notification_preferences='{"message":false,"comment":true}' WHERE user_id=audit.id(1);
SELECT audit.assert_true('regression.pref_boolean_allowed',(SELECT notification_preferences->'message'='false'::jsonb FROM public.user_settings WHERE user_id=audit.id(1)));
RESET ROLE;
SELECT audit.expect_state('regression.story_asset_unique',
 $$INSERT INTO public.stories(author_id,media_asset_id,client_request_id) VALUES(audit.id(1),audit.id(606),audit.id(9703))$$,'23505');
CREATE FUNCTION private.audit_unhardened_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1';
CREATE FUNCTION public.audit_unhardened_public_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1';
SELECT audit.assert_true('regression.private_future_execute_denied',NOT has_function_privilege('authenticated','private.audit_unhardened_probe()','EXECUTE'));
SELECT audit.assert_true('regression.public_future_execute_denied',NOT has_function_privilege('authenticated','public.audit_unhardened_public_probe()','EXECUTE'));
SELECT audit.assert_true('regression.existing_helper_explicit_grant',has_function_privilege('authenticated','private.is_active()','EXECUTE'));
DROP FUNCTION private.audit_unhardened_probe(),public.audit_unhardened_public_probe();
UPDATE public.profiles SET avatar_asset_id=audit.id(603) WHERE id=audit.id(1);
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(2)::text,true);
SELECT audit.assert_true('regression.recalled_avatar_confusion_denied',NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(603)));
RESET ROLE;
UPDATE public.profiles SET avatar_asset_id=NULL WHERE id=audit.id(1);
-- Parent link can be corrupted by migration/operator; RLS should still deny wrong purpose/owner.
INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,state,mime_type,size_bytes)
VALUES(audit.id(607),audit.id(1),audit.id(9607),'social-media','test/another-story.jpg','story','ready','image/jpeg',100),
(audit.id(608),audit.id(3),audit.id(9608),'social-media','test/another-owner.jpg','post','ready','image/jpeg',100),
(audit.id(609),audit.id(1),audit.id(9609),'social-media','test/valid-upload.jpg','post','pending','image/jpeg',100);
INSERT INTO public.post_media VALUES(audit.id(101),audit.id(607),0),(audit.id(101),audit.id(608),1);
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(2)::text,true);
SELECT audit.assert_true('regression.wrong_post_asset_purpose_denied',NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(607)));
SELECT audit.assert_true('regression.wrong_post_asset_owner_denied',NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(608)));
SELECT set_config('request.jwt.claim.sub',audit.id(1)::text,true);
INSERT INTO storage.objects(bucket_id,name,owner_id) VALUES('social-media','test/valid-upload.jpg',audit.id(1)::text);
SELECT audit.assert_true('regression.pending_upload_insert_allowed',private.can_upload_object('social-media','test/valid-upload.jpg'));
SELECT audit.expect_state('regression.pending_upload_wrong_owner_denied',
 $$INSERT INTO storage.objects(bucket_id,name,owner_id) VALUES('social-media','test/valid-upload-other.jpg',audit.id(2)::text)$$,'42501');
RESET ROLE;
-- Validate circular deletion in disposable fixtures, then rollback subtransaction changes.
DO $$
BEGIN
 BEGIN
  DELETE FROM public.posts WHERE id=audit.id(101);
  PERFORM audit.assert_true('temporary.cascade',NOT EXISTS(SELECT 1 FROM public.comments WHERE post_id=audit.id(101)));
  RAISE EXCEPTION USING ERRCODE='ZX001',MESSAGE='restore cascade fixture';
 EXCEPTION WHEN SQLSTATE 'ZX001' THEN NULL;
 END;
 INSERT INTO audit.results VALUES('regression.post_comment_cascade',true);
 BEGIN
  DELETE FROM public.conversations WHERE id=audit.id(401);
  PERFORM audit.assert_true('temporary.conversation_cascade',NOT EXISTS(SELECT 1 FROM public.messages WHERE conversation_id=audit.id(401)));
  RAISE EXCEPTION USING ERRCODE='ZX001',MESSAGE='restore conversation fixture';
 EXCEPTION WHEN SQLSTATE 'ZX001' THEN NULL;
 END;
 INSERT INTO audit.results VALUES('regression.conversation_message_cycle_cascade',true);
END $$;
COMMIT;
SELECT json_build_object('passed',count(*),'failed',count(*) FILTER (WHERE NOT passed),'regressions',count(*) FILTER(WHERE test LIKE 'regression.%')) FROM audit.results;

