-- Task 02 preflight. Read-only; run before reviewing/applying migration 01200.
SELECT current_database() AS database_name, current_user AS migration_role;

SELECT to_regclass('supabase_migrations.schema_migrations') AS migration_ledger,
       to_regclass('public.music_catalog_tracks') AS catalog_table,
       to_regclass('storage.buckets') AS storage_buckets,
       to_regclass('storage.objects') AS storage_objects,
       to_regprocedure('private.valid_music_metadata(jsonb)') AS music_validator,
       to_regprocedure('private.bind_post_music(uuid,jsonb)') AS post_music_binder,
       to_regprocedure('private.bind_story_music(uuid,jsonb)') AS story_music_binder,
       to_regprocedure('public.get_music_publish_capabilities()') AS legacy_capability_rpc,
       to_regprocedure('storage.allow_any_operation(text[])') AS storage_operation_guard;

-- The migration assumes Task 52's original Openverse constraints and columns.
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema='public' AND table_name IN ('posts','stories')
  AND column_name IN ('music_metadata','music_metadata_hash')
ORDER BY table_name,column_name;

-- Inspect the actual Cloud policy set; the migration adds targeted restrictive
-- guards and never drops/replaces these existing policies.
SELECT policyname, permissive, roles, cmd, qual, with_check
FROM pg_policies
WHERE schemaname='storage' AND tablename='objects'
ORDER BY cmd,policyname;

SELECT id,name,public,file_size_limit,allowed_mime_types
FROM storage.buckets
WHERE id='music-library';

-- Storage ownership is informational, not a policy-DDL prerequisite: managed
-- Supabase grants may authorize CREATE POLICY without owning storage.objects.
-- Read-only preflight does not prove DDL authorization. The migration's actual
-- CREATE POLICY statements check it inside BEGIN/COMMIT and abort on failure.
SELECT c.relrowsecurity, c.relforcerowsecurity,
       has_schema_privilege(current_user,'public','CREATE') AS can_create_public,
       has_schema_privilege('authenticated','public','USAGE') AS authenticated_public_usage,
       has_schema_privilege('authenticated','private','USAGE') AS authenticated_private_usage,
       has_table_privilege(current_user,'storage.buckets','INSERT') AS can_insert_bucket,
       pg_get_userbyid(c.relowner) AS storage_objects_owner_informational,
       'checked_by_create_policy_in_transaction'::text AS storage_policy_permission_check
FROM pg_class c
WHERE c.oid='storage.objects'::regclass;

-- Check the ledger only if it exists. Absence of a row is not proof that a
-- manually executed SQL Editor migration is absent; catalog objects are decisive.
SELECT to_regclass('supabase_migrations.schema_migrations') IS NOT NULL AS ledger_exists;
-- If ledger_exists is true, run this separate read-only query:
-- SELECT version FROM supabase_migrations.schema_migrations
-- WHERE version IN ('20261107000900','20261107001200') ORDER BY version;

SELECT n.nspname AS schema_name,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,
       pg_get_userbyid(p.proowner) AS owner,p.prosecdef,p.provolatile,p.proconfig
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE (n.nspname='private' AND p.proname IN
       ('valid_music_metadata','bind_post_music','bind_story_music','get_music_publish_capabilities'))
   OR (n.nspname='public' AND p.proname='get_music_publish_capabilities')
ORDER BY n.nspname,p.proname;

-- Compare the actual replacement bodies with migration 00900, including actor,
-- row locks, ownership/state filters, metadata hash and idempotent replay.
SELECT n.nspname AS schema_name,p.proname,pg_get_functiondef(p.oid) AS function_definition
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='private' AND p.proname IN
 ('valid_music_metadata','bind_post_music','bind_story_music')
ORDER BY p.proname,p.oid;

SELECT conrelid::regclass AS table_name,conname,convalidated,pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN (to_regclass('public.posts'),to_regclass('public.stories'))
 AND conname IN ('posts_music_metadata_check','stories_music_metadata_check')
ORDER BY conname;

-- Migration 01200 must not be applied if any of these objects already exist.
SELECT to_regprocedure('private.valid_music_library_metadata(jsonb,boolean)') AS v2_validator,
       to_regprocedure('private.get_music_catalog_v2(text,text,integer,integer)') AS catalog_impl,
       to_regprocedure('public.get_music_catalog(text,text,integer,integer)') AS catalog_rpc,
       to_regprocedure('public.get_music_library_capabilities()') AS library_capability_rpc,
       EXISTS(SELECT 1 FROM pg_policies WHERE schemaname='storage' AND tablename='objects'
         AND policyname LIKE 'meeto_music_library_%') AS task02_policies_exist;
