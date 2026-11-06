-- Read-only inventory/smoke queries. Run as project owner in SQL Editor.
-- These queries do not deploy, create fixtures, change policies, or disclose credentials.
SELECT version FROM supabase_migrations.schema_migrations
WHERE version >= '20261104000000' ORDER BY version;

-- Pre-deploy catalog fingerprint. Review these exact objects before applying 037-040.
SELECT c.conrelid::regclass AS relation,c.conname,c.contype,c.convalidated,
       pg_get_constraintdef(c.oid,true) AS definition
FROM pg_constraint c
WHERE c.conrelid IN ('public.posts'::regclass,'public.stories'::regclass,
 'public.profile_albums'::regclass,'public.user_settings'::regclass,
 'public.profile_private'::regclass,'public.notifications'::regclass)
 AND c.conname IN ('posts_audience_check','stories_audience_check','profile_albums_audience_check',
  'user_settings_last_seen_audience_check','profile_private_location_audience_check',
  'user_settings_tagging_policy_check','notifications_kind_check','user_settings_prefs_keys')
ORDER BY c.conrelid::regclass::text,c.conname;

-- Counts only: these existing accounts will be blocked from Messenger until they
-- complete the existing Auth confirmation flow. No email addresses are returned.
SELECT coalesce(p.onboarding_completed,false) AS onboarding_completed,
       coalesce(s.state,'missing_status') AS account_state,count(*) AS accounts
FROM auth.users u LEFT JOIN public.profiles p ON p.id=u.id
LEFT JOIN public.account_status s ON s.user_id=u.id
WHERE u.email_confirmed_at IS NULL
GROUP BY 1,2 ORDER BY 1,2;

SELECT has_column_privilege('anon','public.profiles','bio','SELECT') AS anon_bio,
       has_column_privilege('anon','public.profiles','cover_asset_id','SELECT') AS anon_cover,
       has_column_privilege('authenticated','public.profiles','bio','SELECT') AS authenticated_bio,
       has_column_privilege('authenticated','public.profiles','cover_asset_id','SELECT') AS authenticated_cover,
       has_column_privilege('authenticated','public.profiles','display_name','SELECT') AS authenticated_display_name,
       c.relrowsecurity,c.relforcerowsecurity
FROM pg_class c WHERE c.oid='public.profiles'::regclass;

SELECT policyname,permissive,roles,cmd,qual,with_check
FROM pg_policies WHERE schemaname='public' AND tablename='profiles' ORDER BY policyname;

SELECT c.relacl::text AS table_acl FROM pg_class c WHERE c.oid='public.profiles'::regclass;

SELECT a.attname,a.attacl::text AS column_acl FROM pg_attribute a
WHERE a.attrelid='public.profiles'::regclass AND a.attname IN ('bio','cover_asset_id')
ORDER BY a.attname;

SELECT n.nspname,p.proname,p.oid::regprocedure AS signature,
       p.prosecdef,p.proconfig,p.proacl,
       has_function_privilege('authenticated',p.oid,'EXECUTE') AS authenticated_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname IN ('public','private') AND (p.proname ILIKE '%group%' OR p.proname IN
 ('c_actor','set_follow','get_or_create_private','schedule_media_cleanup','admin_moderate_content',
  'resolve_platform_report','emit_notification','notify_post_share','notify_conversation_message'))
ORDER BY n.nspname,p.proname,p.oid::regprocedure::text;

SELECT n.nspname,p.proname,p.oid::regprocedure AS signature,
       p.prosrc LIKE '%private.emit_notification%' AS calls_emit_notification,
       p.prosrc LIKE '%private.c_actor%' AS calls_c_actor
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname IN ('public','private')
 AND (p.prosrc LIKE '%private.emit_notification%' OR p.prosrc LIKE '%private.c_actor%')
ORDER BY n.nspname,p.proname,p.oid::regprocedure::text;

SELECT c.conrelid::regclass AS relation,c.conname,d.classid::regclass AS dependent_catalog,
       d.objid,d.objsubid,d.deptype
FROM pg_constraint c JOIN pg_depend d ON d.refclassid='pg_constraint'::regclass AND d.refobjid=c.oid
WHERE c.conrelid IN ('public.posts'::regclass,'public.stories'::regclass,
 'public.profile_albums'::regclass,'public.user_settings'::regclass,
 'public.profile_private'::regclass,'public.notifications'::regclass)
 AND c.conname IN ('posts_audience_check','stories_audience_check','profile_albums_audience_check',
  'user_settings_last_seen_audience_check','profile_private_location_audience_check',
  'user_settings_tagging_policy_check','notifications_kind_check','user_settings_prefs_keys')
ORDER BY c.conname,d.classid::regclass::text,d.objid,d.objsubid;

-- Profile table constraints and indexes are read-only context for the owner;
-- the migration changes SELECT ACLs and does not rewrite profile data.
SELECT c.conrelid::regclass AS relation,c.conname,pg_get_constraintdef(c.oid,true) AS definition
FROM pg_constraint c WHERE c.conrelid='public.profiles'::regclass ORDER BY c.conname;

SELECT signature, to_regprocedure(signature) IS NOT NULL AS present
FROM unnest(ARRAY[
 'private.update_profile_with_location(text,text,uuid,uuid,text,text,bigint)',
 'private.user_can_read_post(uuid,uuid)',
 'private.can_read_profile_album(uuid)',
 'public.task36_capabilities()',
 'public.update_profile_public_v36(text,text,text,uuid,uuid,text,text,bigint)',
 'public.list_follow_requests(timestamp with time zone,uuid,integer)',
 'public.resolve_follow_request(uuid,boolean)',
 'public.direct_contact_policy(uuid)',
 'public.send_message_request(uuid,text,uuid,uuid)',
 'public.resolve_message_request(uuid,boolean)',
 'public.resolve_conversation_invitation(uuid,boolean)',
 'public.search_conversations_v36(text,timestamp with time zone,uuid,integer,boolean)',
 'public.set_conversation_archived(uuid,boolean)',
 'public.set_conversation_avatar(uuid,uuid)',
 'private.can_review_report_media(uuid)'
]) AS signature;

SELECT c.relname,c.relrowsecurity
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname IN ('profiles','posts','stories','media_assets',
 'profile_albums','follow_requests','message_requests','conversation_invitations','conversations','messages');

SELECT id,public FROM storage.buckets
WHERE id IN ('profile-media','social-media','chat-media','group-media');

SELECT has_table_privilege('authenticated','public.follow_requests','INSERT') AS direct_follow_insert,
 has_table_privilege('authenticated','public.message_requests','UPDATE') AS direct_request_update,
 has_table_privilege('authenticated','public.conversation_invitations','INSERT') AS direct_invite_insert,
 has_column_privilege('authenticated','public.profiles','bio','SELECT') AS raw_bio_read,
 has_column_privilege('authenticated','public.profiles','cover_asset_id','SELECT') AS raw_cover_read,
 has_function_privilege('authenticated','private.open_direct(uuid)','EXECUTE') AS direct_helper_execute;
-- Expected after 037-040: all booleans above false; RLS true; buckets public=false.

SELECT c.conname,c.convalidated,pg_get_constraintdef(c.oid,true) AS definition
FROM pg_constraint c
WHERE c.conrelid IN ('public.posts'::regclass,'public.stories'::regclass,
 'public.profile_albums'::regclass,'public.user_settings'::regclass,
 'public.profile_private'::regclass,'public.notifications'::regclass)
 AND c.conname IN ('posts_audience_check','stories_audience_check','profile_albums_audience_check',
  'user_settings_last_seen_audience_check','profile_private_location_audience_check',
  'user_settings_tagging_policy_check','notifications_kind_check','user_settings_prefs_keys')
ORDER BY c.conrelid::regclass::text,c.conname;

SELECT n.nspname,p.proname,p.oid::regprocedure AS signature,
       has_function_privilege('authenticated',p.oid,'EXECUTE') AS authenticated_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname IN ('public','private') AND p.proname ILIKE '%group%'
ORDER BY n.nspname,p.proname,p.oid::regprocedure::text;

SELECT kind,direct_consent,count(*) AS conversations
FROM public.conversations GROUP BY kind,direct_consent ORDER BY kind,direct_consent;
-- Pending legacy private conversations retain messages but need current contact permission
-- or explicit recipient approval before access. Inspect counts, not message bodies.
