-- TASK 54B.2 OWNER-RUN READ ONLY PREFLIGHT.
-- SELECT/catalog inventory only. Does not call RPCs, Storage, cron changes or writes.
-- Storage HEAD/DELETE outcomes must be checked separately against exact objects.

SELECT current_database() AS database_name,current_user AS inspection_role,
 current_setting('server_version') AS postgres_version,
 to_regclass('supabase_migrations.schema_migrations') AS migration_ledger;

SELECT version
FROM supabase_migrations.schema_migrations
WHERE version IN ('20261107001000','20261107001050','20261107001100')
ORDER BY version;

-- Baseline worker signatures/owners/ACLs before replacing them. Hashes allow
-- comparison without returning large function bodies or any token values.
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,
 p.prosecdef,p.proconfig,pg_get_userbyid(p.proowner) AS function_owner,
 p.proacl,md5(pg_get_functiondef(p.oid)) AS definition_hash
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE (n.nspname='private' AND p.proname IN
 ('meeto_claim_media_cleanup','meeto_finish_media_cleanup','meeto_finish_worker_run',
  'schedule_media_cleanup','task43_asset_live'))
 OR (n.nspname='public' AND p.proname IN
 ('meeto_worker_claim_cleanup','meeto_worker_finish_cleanup','meeto_worker_finish_run'))
ORDER BY n.nspname,p.proname,arguments;

-- Effective worker ACLs (including inherited grants); private implementation
-- functions must remain internal and public worker wrappers service_role-only.
SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,
 r.rolname,has_function_privilege(r.oid,p.oid,'EXECUTE') AS can_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace CROSS JOIN pg_roles r
WHERE ((n.nspname='private' AND p.proname IN
 ('meeto_claim_media_cleanup','meeto_finish_media_cleanup','meeto_finish_worker_run'))
 OR (n.nspname='public' AND p.proname IN
 ('meeto_worker_claim_cleanup','meeto_worker_finish_cleanup','meeto_worker_finish_run')))
 AND r.rolname IN ('anon','authenticated','service_role')
ORDER BY n.nspname,p.proname,arguments,r.rolname;

SELECT t.tgname,t.tgenabled,t.tgisinternal,pg_get_triggerdef(t.oid) AS trigger_definition
FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname='media_assets'
ORDER BY t.tgname;
SELECT c.relrowsecurity,c.relforcerowsecurity,pg_get_userbyid(c.relowner) AS table_owner,
 r.rolsuper,r.rolbypassrls,
 has_table_privilege(current_user,c.oid,'SELECT') AS migration_role_can_select
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.rolname=current_user::text
WHERE n.nspname='storage' AND c.relname='objects';

-- Worker schedule and last invocation. completed_at says the function returned;
-- it does not prove each individual Storage DELETE or finish RPC succeeded.
SELECT jobid,jobname,schedule,active
FROM cron.job WHERE jobname='meeto-media-cleanup-every-five-minutes';
SELECT singleton,run_id,issued_at,expires_at,consumed_at,execution_until,completed_at,
 net_request_id,summary
FROM private.worker_invocations WHERE singleton;

-- Counts first; inspect event IDs individually before any owner recovery action.
SELECT state,attempts,
 count(*) AS event_count,
 count(*) FILTER(WHERE lease_until<=statement_timestamp()) AS expired_lease_count,
 count(*) FILTER(WHERE payload ? 'last_error') AS with_last_error
FROM public.event_outbox WHERE event_type='media.retire'
GROUP BY state,attempts ORDER BY state,attempts;

-- Bounded inventory for due cleanup events; no body, caption, path, signed URL or token.
SELECT e.id AS event_id,e.state AS event_state,e.attempts,e.available_at,e.lease_until,
 a.id AS asset_id,a.state AS asset_state,a.owner_id,a.bucket_id,a.object_path,a.purpose,a.mime_type,
 a.delete_after,
 (o.id IS NOT NULL) AS storage_catalog_row_present,
 (o.id IS NOT NULL AND o.owner_id=a.owner_id::text) AS storage_owner_matches,
 (o.id IS NOT NULL AND o.metadata->>'mimetype'=a.mime_type) AS storage_mime_matches,
 (b.id IS NOT NULL AND b.public IS FALSE) AS bucket_exists_and_private,
 (b.allowed_mime_types @> ARRAY[a.mime_type]::text[]) AS bucket_allows_asset_mime
FROM public.event_outbox e
JOIN public.media_assets a ON a.id=e.aggregate_id
LEFT JOIN storage.objects o ON o.bucket_id=a.bucket_id AND o.name=a.object_path
LEFT JOIN storage.buckets b ON b.id=a.bucket_id
WHERE e.event_type='media.retire' AND e.aggregate_type='media_asset'
 AND (e.state IN ('pending','processing','dead'))
ORDER BY e.state,e.attempts,e.available_at,e.id;

-- Category inventory verifies actual contract rows without listing user content.
SELECT bucket_id,purpose,mime_type,state,count(*) AS asset_count,
 count(*) FILTER(WHERE owner_id IS NULL) AS ownerless_count
FROM public.media_assets
WHERE bucket_id IN ('profile-media','social-media','chat-media','group-media')
GROUP BY bucket_id,purpose,mime_type,state
ORDER BY bucket_id,purpose,mime_type,state;

-- Exact report/reference counts for due events; any positive count blocks deletion.
SELECT e.id AS event_id,a.id AS asset_id,
 (SELECT count(*) FROM public.reports r WHERE
   EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.post_id=r.post_id AND pm.asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p.id=r.post_id AND p.reel_cover_asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.stories s WHERE s.id=r.story_id AND s.media_asset_id=a.id)
   OR EXISTS(SELECT 1 FROM public.message_media mm WHERE mm.message_id=r.message_id AND mm.asset_id=a.id)
 ) AS linked_report_count,
 (SELECT count(*) FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id
   WHERE mm.asset_id=a.id AND m.state<>'sent') AS tombstone_message_reference_count,
 private.task43_asset_live(a.id) AS task43_live_reference
FROM public.event_outbox e JOIN public.media_assets a ON a.id=e.aggregate_id
WHERE e.event_type='media.retire' AND e.aggregate_type='media_asset'
 AND e.state IN ('pending','processing','dead')
ORDER BY e.id;

-- Storage bucket privacy/MIME contract and policy inventory.
SELECT id,public,file_size_limit,allowed_mime_types
FROM storage.buckets WHERE id IN ('profile-media','social-media','chat-media','group-media')
ORDER BY id;
SELECT schemaname,tablename,policyname,permissive,roles,cmd,qual,with_check
FROM pg_policies WHERE schemaname='storage' AND tablename='objects'
ORDER BY policyname;

-- No DELETE outcome can be established from SQL alone. For each exhausted lease,
-- owner must GET-confirm the exact bucket, then HEAD-confirm that exact object.
