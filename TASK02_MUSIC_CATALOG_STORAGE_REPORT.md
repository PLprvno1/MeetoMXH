# Task 02 — Music Catalog & Supabase Storage

Implemented the catalog and upload preparation at source level. No Cloud migration, Storage upload, activation, Android edit, or runtime test was performed.

Created files:

- `supabase/migrations/20261107001200_music_catalog_storage.sql` creates `public.music_catalog_tracks`, seeds the 50 source entries as inactive, adds authenticated catalog/capability RPCs, and creates the private `music-library` bucket and scoped RLS policies. It preserves the existing Openverse validator and publish-capability contract while extending the validator to accept catalog-backed metadata v2. Publishing rejects unknown or inactive library tracks. Catalog audio stays outside `media_assets` and Media Cleanup.
- `music_library_manifest.json` maps every source track ID and filename to `music-library/v1/<track_id>.ogg`, MIME, version, SHA-256, size and duration.
- `scripts/upload_meeto_music_library.ps1` validates the local catalog and all 50 source hashes before connecting. Without `-ConfirmUpload`, it exits after local checks and makes no network request. With explicit owner invocation, it reads `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` from process environment, uses `x-upsert:false`, never replaces a differing existing object, resumes by verifying matching existing objects, verifies each uploaded object with Storage GET, and checks the `v1` listing contains exactly the expected 50 objects.
- `TASK02_MUSIC_LIBRARY_PREFLIGHT_READONLY.sql` and `TASK02_MUSIC_LIBRARY_POSTDEPLOY_READONLY.sql` provide owner-run read-only checks.

The source package has 50 unique IDs and filenames; all 50 files match catalog byte sizes and SHA-256 values. Combined size is 43,627,767 bytes (about 41.61 MiB). The catalog identifies OGG/Vorbis, CC0-1.0, artist `Abstraction`, and the source page `https://tallbeard.itch.io/music-loop-bundle`. Storage uses `audio/ogg`, a 5 MiB per-object limit, and stable paths independent of signed URLs. No audio files were changed.

Metadata v2 retains the existing `music_metadata` projection and hash, so old Story/Reel records and the strict 13-field Openverse format remain readable. V2 stores `contract_version=2`, `source=meeto_library`, text `track_id`, `catalog_version`, title/artist, source duration, start/used duration, music/source volume, license/source attribution and SHA-256. It stores no object URL. The validator matches these claims against the catalog; binder functions require the matched track to be active. The existing `get_music_publish_capabilities()` remains unchanged at contract version 1. A future Android picker must keep checking it and also check `get_music_library_capabilities()` before publishing library music.

The existing backend music Story publish RPC accepts video Stories only; adding music to photo Stories still needs the later client composition/export work. This migration does not change the Android picker, cache, playback, Story/Reel UI or Transformer pipeline.

Task 02.1 defines implicit looping for the existing 16-field library metadata v2: `0 <= start_offset_ms < duration_ms`, `1000 <= used_duration_ms <= 60000`, and wrap to track position zero at EOF until the requested duration is reached. Openverse v1 keeps its original no-wrap duration validation. This is a backend contract only; Android loop playback/export has not been implemented or verified. The final review is recorded in `docs/music-reports/MUSIC_TASK_02_REPORT.md`.

Owner deployment order:

1. Run the preflight SQL and inspect actual Cloud catalog state, bucket configuration, Storage policies, function ownership and the migration ledger. A missing ledger row is not proof that a SQL Editor migration is absent; use the actual catalog and function definitions as well. Do not batch-apply migrations with `supabase db push`.
2. After confirming Task 52 / migration `00900` is present and no Task 02 objects already exist, apply only migration `20261107001200_music_catalog_storage.sql`. The migration aborts on an existing bucket/table/policy or ledger entry rather than trying to repair drift.

   Task 02.2 removes the Storage table-ownership prerequisite: owner reports Cloud preflight 14/15, with only `14_storage_policy_ownership` failing because managed Storage can authorize policy DDL through `supautils.policy_grants`. Storage owner is now informational in read-only preflight. Schema privileges, bucket INSERT, Storage RLS and all other guards remain; PostgreSQL checks actual permission at each `CREATE POLICY` inside the transaction. An authorization failure aborts the transaction; roll it back if the SQL Editor leaves it open. Do not change managed ownership or grant administrative role membership. This source correction has not been executed on Cloud.
3. Inject the project URL and service-role key into a secure process environment, then run `scripts/upload_meeto_music_library.ps1 -ConfirmUpload`. Never put the service key in Android, source control, command-line arguments, or logs. If any file conflicts or verification fails, leave it untouched and resolve that object before proceeding.
4. Run the post-deploy read-only SQL and require exactly 50 catalog rows, 50 matching Storage objects, matching MIME/size, no unexpected object under `v1/`, and successful per-object SHA-256 verification from the helper. Rows remain inactive until all checks pass.
5. Owner may then activate the one version in a separate transaction. The helper’s GET hash checks and exact 50-object listing are required because Storage metadata does not prove SHA-256. The following guarded update checks all catalog object rows again; it changes only `is_active` and is not part of the migration or helper:

   ```sql
   BEGIN;
   DO $$
   DECLARE total integer; active integer; changed integer;
   BEGIN
     SELECT count(*),count(*) FILTER (WHERE is_active)
       INTO total,active FROM public.music_catalog_tracks
       WHERE catalog_version='meeto_bundled_cc0_v1';
     IF total<>50 OR active NOT IN (0,50) THEN
       RAISE EXCEPTION 'Expected 50 catalog rows and either 0 or 50 active rows';
     END IF;
     IF EXISTS(
       SELECT 1 FROM public.music_catalog_tracks t
       LEFT JOIN storage.objects o ON o.bucket_id=t.bucket_id AND o.name=t.object_path
       WHERE t.catalog_version='meeto_bundled_cc0_v1'
         AND (o.id IS NULL OR o.metadata->>'mimetype' IS DISTINCT FROM t.mime_type
           OR CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]+$'
                   THEN (o.metadata->>'size')::bigint ELSE -1 END <> t.file_size_bytes)
     ) THEN RAISE EXCEPTION 'Storage object mapping, MIME or size check failed'; END IF;
     IF active=0 THEN
       UPDATE public.music_catalog_tracks SET is_active=true
       WHERE catalog_version='meeto_bundled_cc0_v1' AND NOT is_active;
       GET DIAGNOSTICS changed=ROW_COUNT;
       IF changed<>50 THEN RAISE EXCEPTION 'Expected to activate exactly 50 tracks'; END IF;
     END IF;
   END $$;
   COMMIT;
   ```

   Do not run it after a partial upload.

**Status:** SOURCE REVIEWED; source package hashes/sizes verified. CLOUD NOT VERIFIED. SQL RUNTIME NOT VERIFIED (no local PostgreSQL/`psql` available). Android RUNTIME NOT VERIFIED; no Android files were changed or built.
