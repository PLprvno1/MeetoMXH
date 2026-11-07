# MEETO — Task 54B.2: Media cleanup recovery

Date: **2026-11-07, Asia/Bangkok**. Scope: repair the media-retirement worker and prepare a bounded owner-run recovery for the Cloud backlog. No Cloud migration, Storage operation, cron edit, Edge Function deployment, Android build/test/emulator, or Git push was run.

**SOURCE VERIFIED: YES, by migration and worker source inspection. CLOUD VERIFIED BY CODEX: NO. SQL RUNTIME VERIFIED: NO. ANDROID RUNTIME VERIFIED: NO.** The 14 processing and 10 pending rows below are owner-provided Cloud query results; this report does not claim to have queried the project.

## Findings

The old claim function only claims a due pending event or an expired processing event when attempts are below 5. The old finish function moves the fifth failed attempt to dead, but only when finish reaches and commits its failure branch. It also has a concrete false-return path: when `p_success` is true but the registry update affects no row (for example the asset is no longer `pending_delete` or a reference guard fails), it returns false without changing the event or writing `last_error`. Therefore a row still in `processing` at attempt 5 means the final finish transition did not commit a terminal/retry state. The retry predicate then makes that row permanently ineligible.

The worker source has several paths that can leave the event processing without a durable per-event outcome: malformed claim data is skipped without calling finish; finish can return false when the asset state or reference checks fail; an RPC error or lost response is counted in the run summary only; and the old worker checked the finish RPC HTTP status without validating its returned boolean. A finish response of HTTP 200 with JSON `false` could therefore be followed by a completed worker invocation while the event stayed `processing`. The old worker also did not read the boolean body from finish_run. `payload.last_error` is written only by the SQL finish failure branch. Its absence confirms that branch did not durably record the failed outcome, but it does not identify which path occurred for any particular event.

A Storage DELETE can finish before the finish RPC fails or its response is lost. Conversely, an expired lease does not show that DELETE ran. The old worker treats any 2xx DELETE as success without checking the exact object afterward. worker_invocations.completed_at closes the invocation record; it does not attest that every object delete and every finish RPC succeeded.

The four listed categories among the 14 exhausted events (Post JPEG, chat PNG, profile avatar PNG/JPEG, and Post MP4) were already represented in the old cleanup whitelist. The old cleanup whitelist omitted Story media and voice `audio/mp4`; this explains why the 10 pending Story/audio events were not eligible under that function. It does **not** identify the individual failure point for any of the 14 exhausted events.

## Supported media contract

The source upload functions establish the registry path and the private bucket. The new cleanup claim additionally checks the exact bucket/purpose/MIME combination, canonical object path, private bucket configuration, allowed bucket MIME, current references, and a matching `storage.objects` owner/MIME row. It will not issue DELETE if that catalog row is missing or mismatched; those cases go to owner HEAD/reconciliation. It does not infer a file type from an extension alone.

| Bucket | Purpose | Allowed MIME and path suffix | Source contract |
|---|---|---|---|
| profile-media | avatar, cover | PNG .png, JPEG .jpg, WebP .webp | private bucket; max upload 10 MiB |
| social-media | post | PNG, JPEG, WebP, MP4 .mp4 | private bucket; max upload 50 MiB |
| social-media | story | PNG, JPEG, WebP, MP4 .mp4 | Story direct upload/finalize; max upload 50 MiB |
| chat-media | message | PNG, JPEG, WebP, MP4 .mp4, audio/mp4 .m4a | private bucket; voice audio max 2 MiB |
| group-media | group | excluded | Community Groups are retired; historical schema is not a reason to restore cleanup support |

Every supported registry path must be exactly <owner UUID>/<asset UUID>/original.<extension>. A non-null registry owner must match the path and the Storage catalog row's owner and MIME metadata before the worker can send DELETE. Rows whose media_assets.owner_id is null are held for manual review; cleanup will not guess the original owner from an orphaned path.

The claim and finish checks use the final Task43 graph: published/hidden Post media, Reel covers, profile avatar/cover, non-deleted conversation avatar, active unexpired Story, sent Message media, and album items tied to published/hidden Posts. They also protect assets referenced by any report and media attached to removed/recalled Message tombstones, which remain reportable. If 54B's moderation_media_holds table exists, the same helper checks it dynamically. This keeps 01050 deployable before 01100 without a circular dependency.

The worker never reads or logs message text, captions, Storage response bodies, keys, or signed URLs. It sends DELETE only for the exact registered path from an eligible claim.

## Changed files

- Added supabase/migrations/20261107001050_media_cleanup_recovery.sql.
- Updated supabase/functions/meeto-background-cleanup/index.ts.
- Added a prerequisite check to the still-un-deployed supabase/migrations/20261107001100_admin_logic_hardening.sql.
- Added TASK54B2_MEDIA_CLEANUP_PREFLIGHT_READONLY.sql.
- Added TASK54B2_MEDIA_CLEANUP_POSTDEPLOY_READONLY.sql.
- Added this report.

No deployed migration was edited. The 54B migration is changed only to require 01050 in the intended first-deployment sequence. If Cloud already contains 01100, do not run either local migration as written; prepare a new later forward-only migration after live catalog review.

## Claim, Storage and finish

The updated worker validates the claimed UUIDs, attempt range, MIME, purpose, bucket, owner, and exact path before issuing DELETE. After DELETE, including a timeout or non-2xx response, it checks that the bucket exists and HEADs the exact object. The outcome is persisted per event and attempt as absent, present, or unknown before finish.

Finish marks the registry asset deleted only when the recorded outcome is absent, storage.objects has no matching row, the asset is still pending deletion, and no live/report/tombstone/evidence reference blocks it. A failed check dead-letters the event with a diagnostic code and leaves the asset pending_delete; it does not report false success. Non-absent outcomes retry with backoff until attempt 5, then become dead. The worker checks both the finish RPC boolean and the invocation finish boolean.

The observation absent means the exact object was absent when checked after this attempt. It does not prove which actor deleted it. The worker records only HTTP status and outcome, never raw Storage bodies. If finish commits but the HTTP response is lost, the attempt audit still shows the committed result.

## Recovery for the 14 exhausted events

The migration adds a bounded owner-only reaper. It accepts an explicit, unique event-ID list and expected count (maximum 25), verifies every row is media.retire / media_asset, processing, attempts 5, lease expired, and asset pending_delete, and refuses while the cleanup schedule is active or a worker invocation/token is still within its execution window. The owner must first pause the shared cleanup/voice-verification schedule and wait for all in-flight invocations and unconsumed tokens to expire. The migration also requires its SECURITY DEFINER owner to have the worker table rights and Storage catalog SELECT/RLS bypass needed for exact object matching. The reaper then moves only those exact exhausted events to dead, stores lease_expired_outcome_unknown, and writes an audit row with storage_outcome=unknown. It does not touch media_assets, issue a Storage request, or claim success.

After dead-lettering, the owner must inspect each exact bucket/object with a successful bucket check and direct Storage HEAD, then compare `storage.objects` owner/MIME/object ID. The owner-only reconciliation function requires a unique request ID and reason. A successful HEAD alone is not enough to delete or retry an object when its current media/report/reference contract is blocked:

- **HEAD confirms absent**, bucket exists, and SQL catalog row is absent: mark the media registry deleted and event done only if no live/report/tombstone/hold reference blocks it. Otherwise preserve pending_delete and log a blocked reconciliation.
- **HEAD confirms present** and the catalog row matches owner and MIME: create a new media.retire event with a new ID and attempt counter. The old event remains dead at attempt 5, preserving its history. A second eligibility/reference check occurs before a worker can delete it.
- **HEAD cannot determine state**: record unknown and leave the event dead and asset pending deletion. No automatic retry is scheduled.

The SQL function cannot independently prove that an operator actually made the HTTP HEAD request. owner_head is an explicit operator attestation, corroborated by bucket/catalog state. The operator must retain the per-object Storage response or console evidence in the project’s incident record. A lease, run summary, 404 without confirming the bucket, or missing catalog row alone is insufficient.

Use the exact IDs returned by the read-only preflight; do not paste a guessed list. After pausing the cleanup cron, waiting past the worker execution window, and reviewing all 14 exact rows, the owner invocation has this form:

    SELECT private.meeto_reap_exhausted_cleanup(
      ARRAY[/* exact reviewed event UUIDs */]::uuid[],
      /* a new owner-generated request UUID */ '...'::uuid,
      14,
      'Cleanup worker paused and drained; exact expired attempts reviewed; Storage result remains unknown'
    );

For each reaped event, only after the owner directly checked the exact object:

    SELECT private.meeto_reconcile_cleanup_event(
      /* event UUID */ '...'::uuid,
      5,
      /* a new request UUID for this event */ '...'::uuid,
      /* exactly: absent, present, or unknown */ 'unknown',
      'Owner checked the exact private bucket and object; record the actual result'
    );

The examples are templates and intentionally contain no usable event IDs. Recovery functions are private, have no public PostgREST wrapper, and are revoked from PUBLIC, anon, authenticated, and service_role. Do not replace them with hand-written updates to event_outbox or media_assets.

## Handling the 10 pending events

The 6 chat audio/mp4 objects use chat-media, purpose message, and original.m4a; voice upload source permits 2 MiB. The 4 Story images use social-media, purpose story, and the exact PNG/JPEG suffix. The migration adds both to the cleanup whitelist only with their private-bucket MIME checks.

They remain pending during the Admin migration. After 01050 and 01100 are installed and the updated Edge Function is deployed, the normal worker can claim due supported events. A sent Message, live Story, report link, removed/recalled Message reference, unknown MIME/path/owner, or evidence hold keeps its asset out of deletion. A blocked event remains visible for owner review.

## Deploy and pause order

1. Run TASK54B2_MEDIA_CLEANUP_PREFLIGHT_READONLY.sql. Confirm 01050 and 01100 are absent, source prerequisites and all three private bucket MIME lists match, and the 14 exact rows still match the expected state. If 01100 is present, stop and use a new later migration.
2. Pause only `meeto-media-cleanup-every-five-minutes` using the owner’s scheduled-job controls. This job invokes a shared one-shot worker that also checks voice-audio verification, so voice verification will be delayed while paused; its records are not deleted. The separate chat outbox dispatcher is not part of this pause.
3. Wait for the current invocation to complete or pass `execution_until`, and for any unconsumed worker token to expire. Allow a drain interval longer than the configured worker window. Verify the job is inactive, no valid token/invocation remains, and no newly claimed processing rows appeared. `completed_at` alone is not proof of per-file results.
4. Apply `20261107001050_media_cleanup_recovery.sql` first. It creates audit/recovery functions and tightens worker RPC behavior but does not rewrite historical events or delete files. The migration refuses to run unless the shared schedule is inactive and every issued worker token/invocation has expired or completed. Do not use one bulk push that would immediately proceed to 01100 before the explicit recovery gate. Supabase compares migration files with the remote ledger and applies pending files in timestamp order; inspect the proposed set with `supabase migration list --remote` and `supabase db push --dry-run --remote` first. Use an owner-approved staged migration procedure to apply 01050 alone. If SQL Editor is used, only after verifying the entire migration committed and the catalog checks pass, record its ledger version with `supabase migration repair 20261107001050 --status applied --remote`; this command changes migration history only and must never be used to skip SQL execution or bypass the gate. See [Supabase migration deployment](https://supabase.com/docs/guides/deployment/database-migrations) and [CLI reference](https://supabase.com/docs/reference/cli/introduction).
5. Run the bounded reaper for the exact 14 IDs. Confirm all 14 are now dead with unknown outcome and no media row was changed. Keep the cleanup cron paused.
6. Apply 20261107001100_admin_logic_hardening.sql. Its guards require the 01050 objects, the shared schedule still paused, no active/unexpired worker token, and zero `media.retire` rows in `processing`; pending/dead rows are not silently reset.
7. Deploy the updated meeto-background-cleanup Edge Function. This task prepared source only; it did not deploy it.
8. Run TASK54B2_MEDIA_CLEANUP_POSTDEPLOY_READONLY.sql, review holds/references and reconcile each old event after direct Storage checks.
9. Resume the same cleanup schedule only after migration 01100 and the new Edge Function are both active. Then monitor the ten pending events and any new replacement events. Re-enable the paused schedule explicitly.

Timestamp 01050 is intentionally between the last listed deployed migration 01000 and the un-deployed Admin migration 01100. Supabase migration ordering must be confirmed against the live ledger and dry-run output before applying. This avoids making cleanup migration 01050 depend on 54B objects while still ensuring 54B cannot run first. If 01100 is already in the Cloud ledger, stop: 01050's guard requires a later forward-only migration after live review.

## Verification status and remaining blockers

**Source verified:** old claim/finish/worker paths; upload MIME/path contracts; bucket names and privacy; final Task43 reference graph; request/deletion behavior; new source changes and migration order. The account deletion request source only sets deletion_requested_at; it does not delete the profile. A later hard deletion can null media_assets.owner_id, and this cleanup intentionally leaves such rows for manual review. The migration itself now refuses to apply unless the shared scheduled job is paused and all recorded worker invocations/tokens have drained.

**Cloud verified:** no new live query performed by Codex. The 14 processing and 10 pending counts are supplied by the owner. Their exact event IDs, current Storage HEAD outcomes, effective Cloud ACL/owners, current cron state, schema ledger and actual Edge version remain unverified here.

**SQL runtime verified:** no PostgreSQL client/server was available in the workspace, so the migration was not compiled or executed. Static review cannot establish deployability or live function ownership/grants. The migration has guards for PostgreSQL 12+, catalog signatures/columns, private bucket MIME contract, owner consistency, and API-role ACLs, but the owner must review the live output.

**Android runtime verified:** not applicable; no Android source or runtime flow was changed.

Blocking owner actions are: confirm Cloud has not recorded 01100; pause and drain the shared worker; run preflight and confirm exact IDs/contract; apply 01050; run the explicit bounded unknown-outcome reaper; apply 01100; deploy the Edge Function; directly inspect all 14 Storage objects; reconcile only from that evidence; run postflight; and resume the cleanup schedule. Notification delivery remains not_implemented.
