-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
BEGIN;
SELECT audit.assert_true('catalog.36_tables',(SELECT count(*)=36 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r'));
SELECT audit.assert_true('catalog.rls_all',(SELECT bool_and(c.relrowsecurity) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='r'));
SELECT audit.assert_true('catalog.34_select_policies',(SELECT count(*)=34 FROM pg_policies WHERE schemaname='public' AND cmd='SELECT'));
SELECT audit.assert_true('catalog.roles_nobypass',(SELECT bool_and(NOT rolsuper AND NOT rolbypassrls) FROM pg_roles WHERE rolname IN ('anon','authenticated')));
SELECT audit.assert_true('catalog.helper_paths',(SELECT bool_and(proconfig @> ARRAY['search_path=""']) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='private' AND p.prosecdef));

-- Constraints engine tests execute as the LOCAL owner, never a production service key.
SELECT audit.expect_state('constraint.no_self_friend',
 $$INSERT INTO public.friendships(user_low,user_high) VALUES(audit.id(3),audit.id(3))$$,'23514');
SELECT audit.expect_state('constraint.friend_pair_unique',
 $$INSERT INTO public.friendships(user_low,user_high) VALUES(audit.id(1),audit.id(2))$$,'23505');
INSERT INTO public.friend_requests(id,requester_id,recipient_id,client_request_id)
VALUES(audit.id(1001),audit.id(1),audit.id(3),audit.id(9101));
SELECT audit.expect_state('constraint.cross_request_unique',
 $$INSERT INTO public.friend_requests(requester_id,recipient_id,client_request_id) VALUES(audit.id(3),audit.id(1),audit.id(9102))$$,'23505');
SELECT audit.expect_state('constraint.request_state_timestamp',
 $$UPDATE public.friend_requests SET state='accepted' WHERE id=audit.id(1001)$$,'23514');
SELECT audit.expect_state('constraint.direct_chat_pair_unique',
 $$INSERT INTO public.conversations(kind,direct_low,direct_high) VALUES('private',audit.id(1),audit.id(2))$$,'23505');
SELECT audit.expect_state('constraint.comment_same_post',
 $$INSERT INTO public.comments(post_id,author_id,parent_id,client_request_id,request_hash,body) VALUES(audit.id(102),audit.id(1),audit.id(301),audit.id(9301),repeat('a',64),'cross post')$$,'23503');
SELECT audit.expect_state('constraint.deleted_comment_redacted',
 $$UPDATE public.comments SET state='deleted' WHERE id=audit.id(301)$$,'23514');
SELECT audit.expect_state('constraint.message_same_chat_reply',
 $$UPDATE public.messages SET reply_to_message_id=audit.id(504) WHERE id=audit.id(501)$$,'23503');
SELECT audit.expect_state('constraint.send_retry_unique',
 $$INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,body) VALUES(audit.id(401),4,audit.id(2),audit.id(8501),repeat('a',64),'retry')$$,'23505');
SELECT audit.expect_state('constraint.message_seq_unique',
 $$INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,body) VALUES(audit.id(401),1,audit.id(2),audit.id(9501),repeat('a',64),'duplicate seq')$$,'23505');
SELECT audit.expect_state('constraint.recall_redacted',
 $$UPDATE public.messages SET state='recalled' WHERE id=audit.id(501)$$,'23514');
SELECT audit.expect_state('constraint.read_le_delivery',
 $$UPDATE public.conversation_members SET last_read_seq=1 WHERE conversation_id=audit.id(401) AND user_id=audit.id(1)$$,'23514');
SELECT audit.expect_state('constraint.story_24hours',
 $$UPDATE public.stories SET expires_at=created_at+interval '25 hours' WHERE id=audit.id(702)$$,'23514');
SELECT audit.expect_state('constraint.media_purpose_bucket',
 $$UPDATE public.media_assets SET bucket_id='chat-media' WHERE id=audit.id(601)$$,'23514');
SELECT audit.expect_state('constraint.report_typed_target',
 $$UPDATE public.reports SET target_kind='message' WHERE id=audit.id(901)$$,'23514');
SELECT audit.expect_state('constraint.last_pointer_same_chat',
 $$SET CONSTRAINTS ALL IMMEDIATE; UPDATE public.conversations SET last_message_id=audit.id(504) WHERE id=audit.id(401)$$,'23503');
-- Untrusted roles
SET ROLE anon;
SELECT audit.expect_state('anon.posts_denied','SELECT id FROM public.posts','42501');
SELECT audit.expect_state('anon.helpers_denied','SELECT private.is_active()','42501');
RESET ROLE;
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(3)::text,true);
SELECT set_config('request.jwt.claims','{"is_anonymous":false}',true);
SELECT audit.assert_true('nonfriend.public_read',EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(101)));
SELECT audit.assert_true('nonfriend.friends_deny',NOT EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(102)));
SELECT audit.assert_true('nonfriend.only_me_deny',NOT EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(103)));
SELECT audit.assert_true('nonfriend.private_profile_deny',NOT EXISTS(SELECT 1 FROM public.profile_private WHERE user_id=audit.id(1)));
SELECT audit.assert_true('share.author_shell',EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(104)));
SELECT audit.assert_true('moderated_post.hidden',NOT EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(105)));
SELECT audit.assert_true('comment.hidden',NOT EXISTS(SELECT 1 FROM public.comments WHERE id=audit.id(304)));
SELECT audit.assert_true('comment.deleted_tombstone',EXISTS(SELECT 1 FROM public.comments WHERE id=audit.id(303) AND body IS NULL));
SELECT audit.assert_true('group.private_nonmember_deny',NOT EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(106)));
SELECT audit.assert_true('chat.guess_id_denied',NOT EXISTS(SELECT 1 FROM public.messages WHERE id=audit.id(501)));
SELECT audit.assert_true('chat.group_history_boundary',(SELECT array_agg(seq ORDER BY seq)=ARRAY[3]::bigint[] FROM public.messages WHERE conversation_id=audit.id(402)));
SELECT audit.assert_true('story.expired_denied',NOT EXISTS(SELECT 1 FROM public.stories WHERE id=audit.id(701)));
SELECT audit.assert_true('media.nonfriend_deny',NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(601)));
SELECT audit.expect_state('columns.post_hash_denied','SELECT request_hash FROM public.posts','42501');
SELECT audit.expect_state('columns.comment_hash_denied','SELECT request_hash FROM public.comments','42501');
SELECT audit.expect_state('columns.message_hash_denied','SELECT request_hash FROM public.messages','42501');
SELECT audit.expect_state('columns.endpoint_denied','SELECT endpoint FROM public.device_subscriptions','42501');
SELECT audit.expect_state('roles.self_escalation_denied',
 $$INSERT INTO public.system_roles(user_id,role) VALUES(audit.id(3),'system_admin')$$,'42501');
SELECT audit.expect_state('posts.direct_write_denied',
 $$UPDATE public.posts SET body='hack' WHERE id=audit.id(101)$$,'42501');
SELECT audit.expect_state('onboard.direct_flag_denied',
 $$UPDATE public.profiles SET onboarding_completed=true WHERE id=audit.id(3)$$,'42501');
SELECT audit.expect_state('counters.direct_update_denied',
 $$UPDATE public.posts SET reactions_count=99 WHERE id=audit.id(101)$$,'42501');
SELECT audit.expect_state('private.birthdate_direct_update_denied',
 $$UPDATE public.profile_private SET birth_date=DATE '2025-01-01' WHERE user_id=audit.id(3)$$,'42501');
SELECT set_config('request.jwt.claim.sub',audit.id(2)::text,true);
SELECT audit.assert_true('friend.friends_only_read',EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(102)));
SELECT audit.assert_true('friend.private_group_read',EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(106)));
SELECT audit.assert_true('friend.media_read',EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(601)));
SELECT audit.assert_true('chat.member_read',EXISTS(SELECT 1 FROM public.messages WHERE id=audit.id(501)));
SELECT audit.assert_true('chat.other_conversation_deny',NOT EXISTS(SELECT 1 FROM public.messages WHERE id=audit.id(504)));
SELECT audit.assert_true('story.live_friend_read',EXISTS(SELECT 1 FROM public.stories WHERE id=audit.id(702)));
SELECT audit.assert_true('realtime.own_topic_allowed',private.can_subscribe_topic('user:'||audit.id(2)::text));
SELECT audit.assert_true('realtime.other_topic_denied',NOT private.can_subscribe_topic('user:'||audit.id(1)::text));
SELECT audit.assert_true('realtime.post_topic_friend',private.can_subscribe_topic('post-comments:'||audit.id(102)::text));
SELECT audit.assert_true('realtime.invalid_topic_safe',NOT private.can_subscribe_topic('post-comments:not-uuid'));
SELECT set_config('request.jwt.claim.sub',audit.id(1)::text,true);
SELECT audit.assert_true('owner.only_me_read',EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(103)));
SELECT audit.assert_true('owner.expired_story_deny',NOT EXISTS(SELECT 1 FROM public.stories WHERE id=audit.id(701)));
SELECT audit.assert_true('owner.recalled_media_deny',NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(603)));
SELECT audit.assert_true('owner.pending_preview',EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(604)));
SELECT set_config('audit.storage_operation','object.get_authenticated',true);
SELECT audit.assert_true('storage.auth_get_policy',EXISTS(SELECT 1 FROM storage.objects WHERE name='test/chat.jpg'));
SELECT audit.assert_true('storage.recall_get_deny',NOT EXISTS(SELECT 1 FROM storage.objects WHERE name='test/recalled.jpg'));
SELECT set_config('audit.storage_operation','object.sign',true);
SELECT audit.assert_true('storage.sign_deny',NOT EXISTS(SELECT 1 FROM storage.objects WHERE name='test/chat.jpg'));
SELECT set_config('audit.storage_operation','object.list',true);
SELECT audit.assert_true('storage.list_deny',NOT EXISTS(SELECT 1 FROM storage.objects));
SELECT audit.expect_state('storage.unreserved_insert_deny',
 $$INSERT INTO storage.objects(bucket_id,name,owner_id) VALUES('chat-media','unreserved.jpg',audit.id(1)::text)$$,'42501');
SELECT audit.expect_state('storage.direct_delete_denied',
 $$DELETE FROM storage.objects WHERE name='test/chat.jpg'$$,'42501');
UPDATE public.user_settings SET notifications_enabled=false WHERE user_id=audit.id(1);
SELECT audit.assert_true('settings.owner_patch',EXISTS(SELECT 1 FROM public.user_settings WHERE user_id=audit.id(1) AND NOT notifications_enabled));
SELECT set_config('request.jwt.claim.sub',audit.id(4)::text,true);
SELECT audit.assert_true('moderator.assigned_case_read',EXISTS(SELECT 1 FROM public.reports WHERE id=audit.id(901)));
SELECT audit.assert_true('moderator.unassigned_case_deny',NOT EXISTS(SELECT 1 FROM public.reports WHERE id=audit.id(902)));
SELECT set_config('request.jwt.claim.sub',audit.id(5)::text,true);
SELECT audit.assert_true('admin.case_triage',EXISTS(SELECT 1 FROM public.reports WHERE id=audit.id(902)));
SELECT audit.assert_true('admin.not_private_chat',NOT EXISTS(SELECT 1 FROM public.messages WHERE id=audit.id(501)));
SELECT audit.assert_true('admin.not_private_profile',NOT EXISTS(SELECT 1 FROM public.profile_private WHERE user_id=audit.id(1)));
SELECT set_config('request.jwt.claim.sub',audit.id(6)::text,true);
SELECT audit.assert_true('suspended.no_posts',NOT EXISTS(SELECT 1 FROM public.posts));
SELECT audit.assert_true('suspended.own_status',EXISTS(SELECT 1 FROM public.account_status WHERE user_id=audit.id(6)));
SELECT set_config('request.jwt.claim.sub',audit.id(7)::text,true);
SELECT audit.assert_true('incomplete.no_social',NOT EXISTS(SELECT 1 FROM public.posts));
SELECT set_config('request.jwt.claim.sub',audit.id(9)::text,true);
SELECT audit.assert_true('unverified.no_social',NOT EXISTS(SELECT 1 FROM public.posts));
SELECT set_config('request.jwt.claim.sub',audit.id(8)::text,true);
SELECT set_config('request.jwt.claims','{"is_anonymous":true}',true);
SELECT audit.assert_true('anonymous_auth.no_social',NOT EXISTS(SELECT 1 FROM public.posts));
RESET ROLE;
-- Model committed revoke/block/recall transitions as owner to test their READ effects.
INSERT INTO public.message_hidden VALUES(audit.id(501),audit.id(401),audit.id(1),now());
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(1)::text,true);
SELECT set_config('request.jwt.claims','{"is_anonymous":false}',true);
SELECT audit.assert_true('hide.raw_message_denied',NOT EXISTS(SELECT 1 FROM public.messages WHERE id=audit.id(501)));
RESET ROLE;
UPDATE public.group_members SET state='removed' WHERE group_id=audit.id(201) AND user_id=audit.id(2);
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(2)::text,true);
SELECT audit.assert_true('removed.private_group_denied',NOT EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(106)));
RESET ROLE;
INSERT INTO public.user_blocks VALUES(audit.id(1),audit.id(2),now());
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(2)::text,true);
SELECT audit.assert_true('blocked.profile_denied',NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=audit.id(1)));
SELECT audit.assert_true('blocked.public_post_denied',NOT EXISTS(SELECT 1 FROM public.posts WHERE id=audit.id(101)));
SELECT audit.assert_true('blocked.direct_chat_denied',NOT EXISTS(SELECT 1 FROM public.conversations WHERE id=audit.id(401)));
SELECT audit.assert_true('blocked.asset_denied',NOT EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(601)));
SELECT audit.assert_true('blocked.post_topic_denied',NOT private.can_subscribe_topic('post-comments:'||audit.id(102)::text));
RESET ROLE;
DELETE FROM public.user_blocks;
UPDATE public.group_members SET state='active' WHERE group_id=audit.id(201) AND user_id=audit.id(2);
COMMIT;
SELECT json_build_object('passed',count(*),'failed',count(*) FILTER(WHERE NOT passed),'tests',json_agg(test ORDER BY test)) FROM audit.results;

