-- Task 02 post-deploy checks. Read-only; no catalog activation or Storage write.
SELECT id,name,public,file_size_limit,allowed_mime_types
FROM storage.buckets WHERE id='music-library';

SELECT catalog_version,count(*) AS tracks,count(*) FILTER (WHERE is_active) AS active_tracks,
       count(DISTINCT track_id) AS distinct_track_ids,sum(file_size_bytes) AS declared_bytes,
       min(sort_order) AS first_order,max(sort_order) AS last_order,
       bool_and(mime_type='audio/ogg' AND codec='vorbis' AND container='ogg') AS format_contract_ok
FROM public.music_catalog_tracks
GROUP BY catalog_version ORDER BY catalog_version;

-- Should report 0 missing/mismatched rows after upload, before owner activation.
SELECT count(*) AS catalog_tracks,
       count(*) FILTER (WHERE o.id IS NULL) AS missing_objects,
       count(*) FILTER (WHERE o.id IS NOT NULL AND
         (o.metadata->>'mimetype' IS DISTINCT FROM t.mime_type OR
          CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]+$'
               THEN (o.metadata->>'size')::bigint ELSE -1 END <> t.file_size_bytes)) AS object_metadata_mismatches
FROM public.music_catalog_tracks t
LEFT JOIN storage.objects o ON o.bucket_id=t.bucket_id AND o.name=t.object_path
GROUP BY t.catalog_version ORDER BY t.catalog_version;

-- Any rows here are unexpected objects under the versioned upload prefix.
SELECT o.name,o.metadata->>'mimetype' AS mime_type,o.metadata->>'size' AS size_bytes
FROM storage.objects o
LEFT JOIN public.music_catalog_tracks t ON t.bucket_id=o.bucket_id AND t.object_path=o.name
WHERE o.bucket_id='music-library' AND o.name LIKE 'v1/%' AND t.track_id IS NULL
ORDER BY o.name;

SELECT policyname,permissive,roles,cmd,qual,with_check
FROM pg_policies WHERE schemaname='storage' AND tablename='objects'
 AND policyname LIKE 'meeto_music_library_%' ORDER BY policyname;

SELECT has_table_privilege('anon','public.music_catalog_tracks','SELECT') AS anon_select,
       has_table_privilege('anon','public.music_catalog_tracks','INSERT') AS anon_insert,
       has_table_privilege('anon','public.music_catalog_tracks','UPDATE') AS anon_update,
       has_table_privilege('anon','public.music_catalog_tracks','DELETE') AS anon_delete,
       has_table_privilege('authenticated','public.music_catalog_tracks','SELECT') AS authenticated_select,
       has_table_privilege('authenticated','public.music_catalog_tracks','INSERT') AS authenticated_insert,
       has_table_privilege('authenticated','public.music_catalog_tracks','UPDATE') AS authenticated_update,
       has_table_privilege('authenticated','public.music_catalog_tracks','DELETE') AS authenticated_delete,
       has_table_privilege('service_role','public.music_catalog_tracks','SELECT') AS service_role_select,
       has_table_privilege('service_role','public.music_catalog_tracks','INSERT') AS service_role_insert,
       has_table_privilege('service_role','public.music_catalog_tracks','UPDATE') AS service_role_update,
       has_table_privilege('service_role','public.music_catalog_tracks','DELETE') AS service_role_delete,
       has_function_privilege('anon','public.get_music_catalog(text,text,integer,integer)','EXECUTE') AS anon_catalog_rpc,
       has_function_privilege('authenticated','public.get_music_catalog(text,text,integer,integer)','EXECUTE') AS authenticated_catalog_rpc,
       has_function_privilege('service_role','public.get_music_catalog(text,text,integer,integer)','EXECUTE') AS service_role_catalog_rpc,
       has_function_privilege('anon','public.get_music_library_capabilities()','EXECUTE') AS anon_library_capability,
       has_function_privilege('authenticated','public.get_music_library_capabilities()','EXECUTE') AS authenticated_library_capability;

SELECT n.nspname AS schema_name,p.proname,pg_get_function_identity_arguments(p.oid) AS arguments,
       pg_get_userbyid(p.proowner) AS owner,p.prosecdef,p.provolatile,p.proconfig
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE (n.nspname='private' AND p.proname IN
       ('valid_music_metadata','valid_music_library_metadata','get_music_catalog_v2',
        'get_music_library_capabilities','meeto_music_library_object_readable'))
   OR (n.nspname='public' AND p.proname IN ('get_music_catalog','get_music_library_capabilities',
                                            'get_music_publish_capabilities'))
ORDER BY n.nspname,p.proname;

-- Existing Openverse publish-capability contract must remain version 1.
SELECT pg_get_functiondef('private.get_music_publish_capabilities()'::regprocedure) AS legacy_capability_definition;
