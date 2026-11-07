-- TASK 54B.2 OWNER-RUN READ ONLY POSTDEPLOY CHECK.
-- SELECT/catalog checks only. No RPC calls, event/asset edits, Storage operations or cron writes.

SELECT version
FROM supabase_migrations.schema_migrations
WHERE version IN ('20261107001050','20261107001100')
ORDER BY version;

-- Owner resumes this job manually only after both migrations and the new Edge
-- Function are confirmed active; this query itself does not change cron state.
SELECT jobid,jobname,schedule,active
FROM cron.job WHERE jobname='meeto-media-cleanup-every-five-minutes';
SELECT singleton,run_id,expires_at,consumed_at,execution_until,completed_at,summary
FROM private.worker_invocations WHERE singleton;

SELECT state,attempts,count(*) AS event_count,
 count(*) FILTER(WHERE lease_until<=statement_timestamp()) AS expired_lease_count,
 count(*) FILTER(WHERE payload ? 'last_error') AS with_last_error
FROM public.event_outbox WHERE event_type='media.retire'
GROUP BY state,attempts ORDER BY state,attempts;

-- These are the legacy events the bounded owner reaper should account for.
-- "unknown" is an honest result until each Storage object is checked directly.
SELECT a.observation_source,a.storage_outcome,a.finish_result,count(*) AS attempt_count
FROM private.media_cleanup_attempt_audit a
JOIN public.event_outbox e ON e.id=a.event_id
WHERE e.event_type='media.retire'
GROUP BY a.observation_source,a.storage_outcome,a.finish_result
ORDER BY a.observation_source,a.storage_outcome,a.finish_result;

SELECT l.action,l.storage_outcome,count(*) AS event_count
FROM private.media_cleanup_recovery_log l
GROUP BY l.action,l.storage_outcome
ORDER BY l.action,l.storage_outcome;

-- Review each unresolved event and compare its exact object using Storage HEAD.
SELECT e.id AS event_id,e.state AS event_state,e.attempts,e.lease_until,e.payload->>'last_error' AS last_error,
 a.id AS asset_id,a.state AS asset_state,a.owner_id,a.bucket_id,a.object_path,a.purpose,a.mime_type,
 (o.id IS NOT NULL) AS storage_catalog_row_present,
 (o.id IS NOT NULL AND o.owner_id=a.owner_id::text) AS storage_owner_matches,
 (b.id IS NOT NULL AND b.public IS FALSE) AS bucket_exists_and_private,
 (b.allowed_mime_types @> ARRAY[a.mime_type]::text[]) AS bucket_allows_asset_mime,
 x.storage_outcome,x.observation_source,x.observed_at,x.finish_result
FROM public.event_outbox e
JOIN public.media_assets a ON a.id=e.aggregate_id
LEFT JOIN storage.objects o ON o.bucket_id=a.bucket_id AND o.name=a.object_path
LEFT JOIN storage.buckets b ON b.id=a.bucket_id
LEFT JOIN private.media_cleanup_attempt_audit x ON x.event_id=e.id AND x.attempt=e.attempts
WHERE e.event_type='media.retire' AND e.aggregate_type='media_asset'
 AND e.state IN ('processing','dead')
ORDER BY e.state,e.attempts,e.id;

-- A Storage catalog row is supporting evidence, not a substitute for the
-- direct exact-object HEAD required before owner reconciliation.
SELECT b.id,b.public,b.allowed_mime_types
FROM storage.buckets b WHERE b.id IN ('profile-media','social-media','chat-media')
ORDER BY b.id;

SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,
 p.prosecdef,p.proconfig,pg_get_userbyid(p.proowner) AS function_owner,p.proacl,
 r.rolname,has_function_privilege(r.oid,p.oid,'EXECUTE') AS can_execute
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace CROSS JOIN pg_roles r
WHERE ((n.nspname='public' AND p.proname IN
 ('meeto_worker_claim_cleanup','meeto_worker_finish_cleanup','meeto_worker_record_cleanup_observation'))
 OR (n.nspname='private' AND p.proname IN
 ('meeto_reap_exhausted_cleanup','meeto_reconcile_cleanup_event','meeto_cleanup_asset_blocked')))
 AND r.rolname IN ('anon','authenticated','service_role')
ORDER BY n.nspname,p.proname,r.rolname;

SELECT t.tgname,t.tgenabled,t.tgisinternal,pg_get_triggerdef(t.oid) AS trigger_definition
FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relname='media_assets'
ORDER BY t.tgname;

SELECT c.relrowsecurity,c.relforcerowsecurity,pg_get_userbyid(c.relowner) AS table_owner,
 r.rolsuper,r.rolbypassrls,
 has_table_privilege(current_user,c.oid,'SELECT') AS inspection_role_can_select
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_roles r ON r.rolname=current_user::text
WHERE n.nspname='storage' AND c.relname='objects';

SELECT n.nspname,c.relname,r.rolname,
 has_table_privilege(r.oid,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AS any_table_privilege,
 has_any_column_privilege(r.oid,c.oid,'SELECT,INSERT,UPDATE,REFERENCES') AS any_column_privilege
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace CROSS JOIN pg_roles r
WHERE n.nspname='private' AND c.relname IN ('media_cleanup_attempt_audit','media_cleanup_recovery_log')
 AND r.rolname IN ('anon','authenticated','service_role')
ORDER BY c.relname,r.rolname;
