# Task 35 � Progress

## Phase 1 complete: inventory / known state
- Read the prior complete-flow review, current manifest/navigation, role routing, Feed/Reels/Messenger/Stories/Search/Profile/Groups/Notifications/Admin paths, Storage client, voice migration and cleanup Edge Function implementation.
- Workspace has many pre-existing uncommitted Task 29�34 files; preserved them. Task 35 edits are limited to sanitized voice/group RPC diagnostics and a PGRST202-only plain-group-post fallback in `CreatePostActivity.java`, `CommunityGroupRepository.java`, and `SupabaseStorage.java`.
- Verified the only connected device is `emulator-5554` and AVD name is `Pixel_4`.

## Phase 2 complete: Voice P1 investigation
- Reproduced on Pixel_4 in an existing test conversation: record ~1 second -> local preview works -> send reaches 99% -> fails; no voice message appears.
- After installing the diagnostic build over the existing app (no data reset), reproduced again and captured sanitized Logcat: `Finalize failed: HTTP 409 code=verification_pending`.
- Android flow verified in source: `uploadVoice` -> `prepare_direct_message_upload` -> Storage upload -> `finalize_upload` retries 32 times only for 409 `verification_pending` -> on ready, caller invokes `send_message`. SQL requires verifier-set audio metadata before ready and `send_message` also checks verification. No bypass and no fake send.
- Confirmed failure boundary: backend never returned `ready`; `send_message` was not reached. Likely Cloud worker/verifier not completing, but exact cause is not observable from the client. Cloud migration/cron/Edge Function deployment state is unknown; no Cloud access/deploy used.
- Diagnostic code changed no behavior/auth; app surfaced same failure after retry.

## Validation
- `:app:assembleDebug` PASS.
- `:app:testDebugUnitTest` PASS, 24 tests, 0 failures/errors.
- Pixel_4 install-over-existing-data PASS; session restored into Social MainActivity.
- Voice E2E FAIL/BLOCKED at finalize: HTTP 409 `verification_pending`.

## Remaining / next
1. Owner may run the read-only worker/cron diagnostic below to establish why voice verification remains pending; no Cloud query or deployment was performed by this agent.
2. Voice recipient playback remains blocked until Cloud verification completes.
3. Group join/member-two and approval paths, full Albums mutation, social graph, all notification kinds, and simultaneous Realtime remain unverified; continue only if needed for demo scope.

## Phase 3 complete: selected runtime/module checks
- Pixel_4 remained the sole connected AVD. AndroidRuntime crash-filter returned no entries during this pass.
- Group flow initially returned Retry for the new wrapper; later sanitized retry confirmed PGRST202 and the narrow legacy-RPC fallback successfully published a plain post. The test post was deleted. The private one-member group remains because current owner UI/source has no group-delete path.
- Memories opened and returned live �no memories today� empty state.
- Marketplace opened, but is UI ONLY: on-screen message says commerce connection is future work and repository/migration search found no Marketplace RPC/data path. No implementation added because this would create a new feature/backend.
- Profile tab and ProfileActivity opened; profile post sections and counts rendered. Albums opened to its empty state; a create attempt did not produce a visible album, so mutation remains NOT VERIFIED.
- The app returned to Feed after launcher resume with the existing session. No data reset or Git reset occurred.
- Existing source fact: MainActivity switches tabs via fragment replacement; scroll/query retention risk remains source-identified, not retested as a user-visible regression in this pass. Reels, Stories, Admin, and the core Feed/message flows retain prior Task 32�34 evidence; this diagnostic-only source edit does not alter their behavior.

## Task 35 code change
- `SupabaseStorage.java` logs terminal finalize HTTP status and allowlisted code only. `CreatePostActivity.java` logs group-post status/code and uses the older authenticated RPC only for PGRST202 with no mentions/tags. `CommunityGroupRepository.java` logs delete status/code only. No response body, URL, path, user/account data, token, or credential is logged.
- No migration, Edge Function, role, or backend permission was changed.

## Targeted regression results
- Build/tests were run after the diagnostic change: `:app:assembleDebug` PASS; `:app:testDebugUnitTest` PASS (24 tests, 0 failed/errors).
- Voice: FAIL/BLOCKED on emulator at finalize (`HTTP 409 verification_pending`); playback by recipient cannot be tested because no message was sent. Cloud verifier/cron deployment or processing is unverified.
- Groups: private create/detail and plain post create/delete via fallback PASS; group post with mentions/tags requires `create_community_post_with_mentions` to be exposed in the Cloud PostgREST schema; member-two/join/approval NOT VERIFIED.
- Memories: Activity and live empty state PASS for this account.
- Marketplace: UI ONLY; not a functioning commerce flow.
- No crash observed in AndroidRuntime filter. Other historical PASS claims remain limited to their Task 32�34 scope and are not upgraded by this pass.

## Safe owner-run Cloud diagnostic (read-only, no tokens/paths/IDs)
Run in Supabase SQL Editor only if you need to distinguish undeployed/inactive worker from verifier backlog. This query returns job cadence, invocation lifecycle and aggregate verifier state only:
```sql
SELECT jobname, schedule, active
FROM cron.job
WHERE jobname = 'meeto-media-cleanup-every-five-minutes';

SELECT issued_at, expires_at, consumed_at, completed_at, net_request_id,
       execution_until, summary->'audio' AS audio_summary
FROM private.worker_invocations
WHERE singleton;

SELECT state, audio_verify_attempts, audio_verify_claimed_at IS NOT NULL AS claimed,
       count(*) AS assets
FROM public.media_assets
WHERE purpose = 'message' AND mime_type = 'audio/mp4'
  AND created_at >= statement_timestamp() - interval '2 hours'
GROUP BY state, audio_verify_attempts, (audio_verify_claimed_at IS NOT NULL)
ORDER BY state, audio_verify_attempts;
```
This has not been run here. It must not be interpreted as Cloud PASS until the owner checks the deployed project.

## Current acceptance snapshot
- Authentication/role routing, Dedicated Admin: PASS only for prior Task 33 emulator scope; no new Admin actions run here.
- Feed and basic Post interactions: PASS/PARTIAL per prior Task 34 scope; this turn did not regress them.
- Reels, Stories: PASS only for prior fixture create/view/delete flows; interaction matrix not complete.
- Messenger text: PASS for prior sequential two-account flow; simultaneous realtime NOT VERIFIED.
- Voice: BLOCKED (observed finalize 409; send/playback unavailable).
- Groups: PASS for private group create/detail and plain group post create/delete via compatibility fallback; member-two/join/approval remain NOT VERIFIED.
- Memories: PASS for Activity + empty state only.
- Albums: create/list/detail/delete PASS on Pixel_4; adding photos is BLOCKED by zero eligible photo candidates.
- Marketplace: UI ONLY.
- Cross-module, all notification types, full social graph, Cloud deployment parity: PARTIAL/NOT VERIFIED.
- Android Build: PASS. Unit tests: PASS (24/24). Emulator E2E: PARTIAL, not system-wide PASS. Cloud E2E: BLOCKED/NOT VERIFIED.

## Next action for the next agent
1. Ask owner to run the read-only diagnostic above and confirm migration 033 + `meeto-background-cleanup` deployment/version and cron cadence; do not deploy.
2. If voice verifier is active, reproduce with one small fixture only after Cloud processing is confirmed; then verify recipient playback with a second authorized session if available.
3. Group-post RPC absence is confirmed and plain-post fallback was retested. Keep the one-member private test group as-is; no owner delete route is available.
4. Continue only the highest-value unchecked flow; do not reread the whole project or repeat unrelated historical PASS tests.

## Additional Phase 3 checks
- ProfileActivity opened for the active user; Posts/Photos/Videos/About sections are wired in source. `Xem album ?nh` opened ProfileAlbumsActivity and returned its live �No albums yet� state. A reversible `Task35QA` empty-album create attempt did not appear in the refreshed list; the exact backend response was not captured. Album mutation is therefore BLOCKED/NOT VERIFIED, not PASS. No album fixture is currently visible.
- Cross-module navigation observed: Group Detail ? group composer (returns Retry state on publish attempt); Profile tab ? full ProfileActivity ? Albums screen; drawer ? Memories and Marketplace.
- Marketplace status confirmed UI ONLY, because the user-facing page states the service is future work and source search found no RPC/repository integration.
- App session remained valid after returning through launcher; no crash was observed in filtered AndroidRuntime output.

## Group integration fix + emulator retest
- Root cause confirmed from sanitized Logcat: Cloud responds `HTTP 404 PGRST202` for `create_community_post_with_mentions`; PostgREST does not currently resolve that RPC. This does not distinguish an unapplied migration from a stale schema cache. Earlier migration `create_community_post` remains compatible and has server-side membership, approval-state and request-id checks.
- Implemented narrow fallback in `CreatePostActivity.java`: only on PGRST202 and only when mention/tag arrays are empty, call authenticated `create_community_post` with group/body/assets/client request ID. Tagged or mentioned posts do not silently lose metadata and still require the new RPC. No SQL/RLS or backend change.
- Added sanitized logging for the affected group create/delete RPCs only. Logs contain HTTP status and allowlisted PostgREST code, never request body, IDs, auth, or server message.
- Pixel_4 after install-over-existing-data: group fixture reopened; plain group post published through fallback and appeared in Group Detail; deleting that test post succeeded and a fresh list showed �Chua c� b�i vi?t trong nh�m.� The private one-member group fixture remains because the owner UI has no delete action; it is test-only and contains no post.
- Re-ran `:app:assembleDebug :app:testDebugUnitTest` after source changes: PASS; 24 unit tests, 0 failures/errors. Reinstalled over app data and exercised fallback + delete on Pixel_4.

## Updated current acceptance snapshot
- Groups: PASS for private group create ? detail ? plain post publish via backward-compatible RPC ? delete post ? refreshed empty feed. Join/member-two/approval flows remain NOT VERIFIED.
- Voice: BLOCKED at server finalization (`409 verification_pending`); no send/playback.
- Albums: initial create attempt was inconclusive; see the continuation below, where create/list/detail/delete were retested successfully.
- Marketplace: UI ONLY, no backend path in source.
- Build and unit tests PASS. Overall system E2E remains PARTIAL; Cloud deployment parity remains NOT VERIFIED.

## Albums and Groups continuation (2026-11-04)
- Album root-cause check: the earlier �create did not appear� attempt had not activated Save while the IME was visible; it did not establish an RPC/backend failure. Added sanitized `ProfileAlbums` RPC telemetry (status + allowlisted code on error; response keys/count only on success; no values, IDs, body, or credentials).
- Pixel_4 observed create flow: `create_profile_album` succeeded with object keys `[audience, created_at, description, id, title, version]`; subsequent `list_profile_albums` returned count 1 and rendered `Task35Album35` (friends, 0 photos). `get_profile_album` succeeded with `[album, items]`. This verifies create/list/detail response handling on the current Cloud project.
- `list_profile_photo_candidates` returned an empty array; therefore add/remove/cover could not be exercised without first creating/uploading a personal post image. No media was added. Deleted only the test album; `delete_profile_album` returned `{deleted}` and refreshed list count 0.
- Group: reopened existing private one-member fixture. Member screen rendered the owner as �Ch? nh�m�; group admin screen opened; pending-approval screen returned �Kh�ng c� b�i ch? duy?t.� A second member session/approval-enabled fixture was not established in this pass, so join/leave, requests, approval transitions, privacy changes and member actions remain NOT VERIFIED.
- Static targeted check of migrations 005�006 and Group Android source found no owner-facing `delete_community_group` or `archive_community_group` RPC/UI route; only a migration state update to `archived` was found. No new delete/archive route was added.
- Validation after telemetry-only source change: `:app:assembleDebug :app:testDebugUnitTest` PASS; installed over existing Pixel_4 data and verified the album flows above. Existing test data was not reset.
- Remaining: Voice stays BLOCKED on the previously observed Cloud `verification_pending` until owner diagnostics; Marketplace remains UI ONLY; Groups approval/multi-user tests need an eligible second member and an approval-enabled fixture. No Cloud changes/deploy.






## Voice verifier root-cause investigation (2026-11-04)

Scope was limited to migration 033 and its worker dependencies, the existing cleanup Edge Function/verifier, and the Android upload/finalize/send boundary. No Android, SQL, Edge Function, Cloud configuration, credentials, or fixtures were changed. No build/tests or Cloud queries were run in this investigation.

### Runtime path confirmed in source

1. `ChatActivity.sendVoicePreview()` calls `SupabaseStorage.uploadVoice()`; upload obtains a direct message upload intent and stores the audio object in the private `chat-media` bucket. It retries `finalize_direct_upload` while the server returns `verification_pending` (`SupabaseStorage.java`, `finalizeWithRetry`; migration 033 `private.finalize_direct_upload`).
2. `finalize_direct_upload` deliberately keeps audio pending until verifier metadata says `verified=true`, has validation `audio_mp4_aac_lc_structural`, and has the expected object ID, SHA-256 and duration. It then changes the asset to `ready`. Storage object metadata alone is insufficient.
3. The existing cron calls `private.meeto_worker_enqueue_cleanup()`. That single-flight function mints a short-lived token and posts to the existing `meeto-background-cleanup` Edge Function. Migration 033 changes this existing job to a one-minute cadence; it does not create a separate verifier or deploy/configure the Edge Function.
4. The Edge Function consumes the token, calls `meeto_worker_claim_voice_verification(p_batch: 1)`, downloads the private object, checks bounded size and `audio/mp4`, parses the M4A/AAC-LC structure (`audio_verifier.ts`), computes SHA-256, and calls `meeto_worker_finish_voice_verification`. Only then can a later `finalize_direct_upload` mark it ready. Android calls `send_message` only after upload finalization succeeds.

### Claim and finish conditions

The SQL claim function in migration 033 only selects assets with all of these conditions: `purpose='message'`, `mime_type='audio/mp4'`, `bucket_id='chat-media'`, `state='pending'`, created within the last 15 minutes, attempts below 5, not already marked verified, and no active claim (claim absent or older than two minutes). It also requires a matching `storage.objects` row by bucket + exact object path + owner. Claiming increments `audio_verify_attempts` and sets `audio_verify_claimed_at` atomically. Thus the reported three rows with attempts 0 and no claim timestamp show that none of those rows has passed through a successful SQL claim so far. This does not identify which filter prevented selection.

The finish RPC requires service-role authorization, a current matching attempt and claim, and a still-pending message audio asset. A verified result additionally checks the same private Storage object, owner, MIME, byte count, size metadata, bucket privacy/allowlist, SHA-256 format, object ID and duration bounds. A valid result writes verification metadata and clears the claim; it does not mark the media ready. Invalid input or exhausted/expired work retires the asset; a retry clears the claim for a later attempt.

### Findings: confirmed vs. unresolved

- **Confirmed source path exists:** migration 033 defines claim/finish SQL RPCs and authenticated Edge verifier code; `audio_verifier.ts` contains a structural AAC-LC M4A validator. No source evidence supports bypassing verification or changing Android behavior.
- **Confirmed Android behavior is gated:** `ChatActivity` sends a voice message only from the upload success callback; `finalizeWithRetry` surfaces the observed 409 after its bounded wait. Therefore the absent `send_message` call is expected while finalization is pending.
- **Confirmed implication of `summary.audio=NULL`:** in the current Edge Function source, a healthy claim RPC returning an empty array still produces `audio: {claimed:0, verified:0, rejected:0, retried:0}`. The outer error path instead completes the invocation with `{failed:true}` and omits `audio`. Consequently a completed/consumed invocation plus absent `audio` is consistent with a failure before the normal summary is committed (including a claim-RPC error), or with an older/different deployed Edge Function. It is not proof of either explanation. A failure later in cleanup also takes that catch path and omits audio, even if an audio claim had already occurred.
- **Unresolved Cloud causes for zero claims:** asset age over 15 minutes; wrong purpose/bucket/MIME/state; attempts at limit; verified metadata; a still-live claim; missing/mismatched Storage object/owner; missing migration 033 RPCs or grants; Edge Function RPC failure/permission/schema error; or a Cloud Edge deployment different from this source. The supplied cron-active fact does not establish its cadence/command or prove migration 033's cron replacement is deployed. A consumed/completed invocation proves dispatch reached token consumption and some completion path, not that audio verification ran successfully.
- **No confirmed source logic defect:** no patch is justified without matching Cloud invocation details to the deployed function and RPC state. Current catch handling is low-observability because it discards stage/RPC response details; this is a diagnostic limitation, not yet a root cause.

### Minimum read-only Cloud checks for the project owner

Run in the project SQL Editor; these queries do not claim, retry, finalize, or mutate assets. Do not paste credentials, full Storage paths, tokens, or signed headers into chat.

```sql
-- Confirm the actual cadence without printing the command (which contains endpoint configuration).
SELECT schedule, active,
       command = 'SELECT private.meeto_worker_enqueue_cleanup();' AS expected_command
FROM cron.job
WHERE jobname = 'meeto-media-cleanup-every-five-minutes';

-- Distinguish an absent audio key from an explicit JSON null and inspect the last lifecycle summary.
SELECT consumed_at, completed_at, net_request_id,
       summary ? 'audio' AS has_audio_key,
       summary->'audio' AS audio_summary,
       summary->>'failed' AS failed
FROM private.worker_invocations
WHERE singleton;

-- Confirm that migration 033's RPCs exist and the Edge service role can execute wrappers.
WITH funcs AS (
  SELECT to_regprocedure('public.meeto_worker_claim_voice_verification(integer)') AS claim_fn,
         to_regprocedure('public.meeto_worker_finish_voice_verification(uuid,smallint,text,uuid,bigint,text,integer)') AS finish_fn
)
SELECT claim_fn IS NOT NULL AS claim_rpc_exists,
       finish_fn IS NOT NULL AS finish_rpc_exists,
       CASE WHEN claim_fn IS NOT NULL THEN has_function_privilege('service_role', claim_fn, 'EXECUTE') ELSE false END AS claim_service_role_execute,
       CASE WHEN finish_fn IS NOT NULL THEN has_function_privilege('service_role', finish_fn, 'EXECUTE') ELSE false END AS finish_service_role_execute
FROM funcs;

-- Aggregate which claim predicates currently exclude pending audio; returns counts only, no asset IDs/paths.
SELECT count(*) AS pending_audio,
       count(*) FILTER (WHERE a.purpose='message') AS correct_purpose,
       count(*) FILTER (WHERE a.purpose='message' AND a.bucket_id='chat-media') AS correct_bucket,
       count(*) FILTER (WHERE a.purpose='message' AND a.bucket_id='chat-media'
         AND a.created_at > statement_timestamp()-interval '15 minutes') AS within_intent_window,
       count(*) FILTER (WHERE a.purpose='message' AND a.bucket_id='chat-media'
         AND a.created_at > statement_timestamp()-interval '15 minutes' AND a.audio_verify_attempts<5
         AND a.metadata->>'verified' IS DISTINCT FROM 'true'
         AND (a.audio_verify_claimed_at IS NULL OR a.audio_verify_claimed_at < statement_timestamp()-interval '2 minutes')) AS eligible_before_storage_join,
       count(*) FILTER (WHERE a.purpose='message' AND a.bucket_id='chat-media'
         AND a.created_at > statement_timestamp()-interval '15 minutes' AND a.audio_verify_attempts<5
         AND a.metadata->>'verified' IS DISTINCT FROM 'true'
         AND (a.audio_verify_claimed_at IS NULL OR a.audio_verify_claimed_at < statement_timestamp()-interval '2 minutes')
         AND EXISTS (SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id
           AND o.name=a.object_path AND o.owner_id=a.owner_id::text)) AS claimable_now
FROM public.media_assets a
WHERE a.state='pending' AND a.mime_type='audio/mp4';
```

Then correlate the row's `net_request_id` and completion time with the Edge Function invocation logs in Supabase Dashboard. Confirm the deployed `meeto-background-cleanup` version and look for the actual HTTP/RPC failure stage/status for `meeto_worker_claim_voice_verification` (without exposing request headers or secrets). Also confirm the deployed migration contains both public verifier wrappers and service-role grants. If `summary.failed=true` and the current Edge source is deployed, the function's generic catch explains the missing audio summary, but logs/SQL error are still needed to identify the failing call. If the current Edge source is deployed and no claim RPC error is present, compare the aggregate predicate counts; `claimable_now=0` points to eligibility/Storage-row state instead.

### Safe remediation direction

Do not create more voice fixtures or retry the current assets until these read-only checks identify the failing predicate/stage. If the RPCs/grants are absent, review/apply migration 033 through the owner's normal Cloud migration process. If RPCs exist but invocation logs show an Edge deployment/configuration issue, review the existing `supabase/functions/meeto-background-cleanup` deployment and its server-side environment (`SUPABASE_URL`, `SUPABASE_SECRET_KEYS` with a `default` key) with the owner; never move those keys to Android or reports. If an asset is outside the 15-minute window or has mismatched/missing Storage metadata, preserve it and diagnose that exact state before any cleanup. Do not relax verification, bucket privacy, attempt limits, or storage authorization.

**Status:** source investigation complete; Cloud root cause remains unconfirmed. No Cloud deployment or mutation performed. Voice verification/playback remains BLOCKED pending owner read-only diagnostics.

## Continuation: social graph + Messenger video share (2026-11-04)

- **Voice:** project owner paused the entire area. Status is now `DEFERRED BY OWNER`; no verifier/audio/worker/cron work or test was done after that decision. This supersedes the older “voice blocked” priority language above; preserve its prior diagnostics as history only.
- **Pixel_4 identity/session:** confirmed the connected AVD is `Pixel_4`. Installed the debug build over existing app data (no wipe/reset). The app restored to Social `MainActivity` and displayed Feed with all five bottom tabs.
- **Follow/Unfollow runtime:** from the signed-in test account, opened the visible test Admin profile, followed it, observed the button change to “Đang theo dõi” and follower count increase 0→1; unfollowed, then observed “Theo dõi” and count return to 0. The relationship was restored to its initial state. Friend-request accept/decline and block authorization remain NOT VERIFIED; no request/block was created.
- **Saved sync runtime:** saved one visible public video post from Feed; its control changed to “Bỏ lưu bài viết”. Opened Saved Posts and observed the same post. Un-saved it there; Saved Posts showed the empty state again. This verifies this Feed↔Saved path on Pixel_4 and leaves the fixture unchanged.
- **Messenger share runtime limit:** opened Feed share sheet and Messenger recipient chooser. Search for the test Admin returned no conversation, so canceled without sending a message. Current inbox chooser exposed only existing group conversations. Post/Reel-to-chat send, recipient click-through, attachment upload and simultaneous realtime remain NOT VERIFIED because no suitable recipient conversation/session was available. Reels tab currently showed its live empty state; no new Reel fixture was created.
- **Confirmed client gap + minimal fix:** before the patch, `Dto.message()` retained only shared-post ID/availability and `ChatMessageAdapter` always routed an available reference to generic `PostDetails`. For a shared video/Reel, `private.c_message()` projects the post JSON including authorized media, but the adapter had discarded that media type. Updated `Dto` to capture the first authorized video asset from the nested shared post and the chat row now opens the existing `VideoPlayerActivity` directly for that asset; image/text shared posts continue to use `PostDetails`, and unavailable references remain non-clickable. This is Android-only; no migration/backend/RLS changes.
- **Files changed for this continuation:** `ChatMessage.java`, `supabase/Dto.java`, `adapters/ChatMessageAdapter.java`, and `ChatMessageProjectionTest.java`.
- **Validation:** `:app:assembleDebug` PASS; `:app:testDebugUnitTest` PASS (25 tests, 0 failures/errors). Build installed over existing Pixel_4 data and app returned to the Social Feed. The shared-video playback branch itself is not emulator-verified yet because no message could be sent/received.
- **Next:** when a suitable test conversation exists, send one authorized shared video/Reel, verify recipient opens the existing video viewer, and remove only that test message if the chat supports it. Continue remaining friend/block, media, story reply, notification, group membership and album media checks only with valid test fixtures; do not repeat already verified Group plain-post or Album create/list/delete flows.
- **Saved round-trip detail:** the saved card was verified in `SavedPostsActivity`, then removed there; the list returned to “Chưa có bài viết nào được lưu.” The operation is fully reverted.
- **Navigation observation:** the source still replaces the selected fragment in `MainActivity.loadFragment()` and constructs a new fragment on each tab selection. This predicts view-state loss, but the attempted scroll round-trip was inconclusive on the short current Feed; record it as a source-identified risk, not an emulator-reproduced regression. No navigation code changed.
- **Search navigation runtime:** user search for `Admin` returned a profile result; tapping it opened `ProfileActivity` on Pixel_4. Search→Profile PASS. Profile→Chat was not exercised to avoid creating an empty conversation without a verified recipient test session.
- The reversible follow test may have emitted the normal follow notification before unfollow; notification delivery/cleanup on the recipient account was not inspected. Relationship and follower count are restored; recipient-side notification state is a small test side effect to account for.

## Continuation: friend request, notifications, story tray and tab state (2026-11-04)

- **Friend request runtime:** on Pixel_4, sent a request to the existing test Admin profile. The action changed to `Hủy lời mời`; canceling returned it to `Thêm bạn bè`. The outgoing relationship is restored. The recipient's notification state was not inspected, so a test notification may remain on that account.
- **Existing friend-request notification:** opened the existing notification from Nguyen Dang Khoa. It navigated to that user's ProfileActivity, where the current server-backed state showed `Hủy kết bạn` (already friends). This verifies notification-to-profile navigation and current relationship rendering; it does not verify accepting/declining a pending request. The notification may have been marked read by opening it.
- **Search tab state:** switched Search (`Admin` query) → Home → Search on the five-tab shell. The query remained `Admin` and its result remained available. This verifies query retention for this path; Feed scroll retention remains unverified.
- **Stories availability:** Home displayed `Tin của bạn` / create-story entry and no received story card in the visible tray. Story reply/privacy could not be retested without an eligible incoming story fixture; no story was created.
- **No source changes or build/tests** were needed for these checks. No Cloud/schema change or data reset occurred.
- **Still open:** friend accept/decline and block restrictions; Messenger attachment/share recipient flow; simultaneous Realtime; Reel interactions; incoming Story reply/privacy; notification destinations for non-message kinds; group membership/approval; album media mutations; Feed scroll restoration. Existing work on these should be resumed only when an eligible account/content fixture is available.

## Continuation: Share chooser and Messenger image send fixes (2026-11-04)

- **Confirmed Share chooser bug:** after creating/opening the test Admin private chat, Inbox displayed it as `Admin`, but Feed → Share → Messenger rendered its recipient as `null`; searching `Admin` returned no choice. Migration 010's `private.c_conversation()` projects SQL `title` as NULL for a private conversation. `ShareActions.messenger()` treated `JSONObject.optString("title")` as a non-empty label and skipped its existing member-name fallback.
- **Fix:** `ShareActions` now normalizes null/blank/`"null"` titles, resolves the other active member's authorized `display_name`, and uses a stable generic label as a final fallback. Added 3 unit cases for null, blank, and explicit titles.
- **Share runtime after fix:** Pixel_4's recipient picker found Admin; sending the existing public video post created a `Shared post · tap to view` inbox preview. Opening Chat and tapping the shared message opened `VideoPlayerActivity` and displayed its 7-second media controls/frame. Sender then recalled the shared message. The share counter may remain incremented because the share RPC counts the operation; this was not queried. Recipient-side rendering was not observed in an Admin session.
- **Confirmed image-send bug:** Pixel_4's image picker accepted one existing AVD screenshot and upload progress reached 100%, but ChatActivity remained without an image message. Source showed `onSuccess()` calling `sendUploadedImageMessage()` while `uploadingImage` was still true, so the callee's guard returned before `send_message`.
- **Fix:** clear `uploadingImage` in the upload-success UI callback before calling `sendUploadedImageMessage()`.
- **Image runtime retest:** after installing the fixed debug build over the existing app data, one image selection produced Inbox preview `Ảnh`; reopening the chat displayed the media tile. This verifies sender upload/send and reload on Pixel_4, not recipient playback on a second session. The test image message was recalled. The underlying uploaded media asset and test conversation may remain until normal backend cleanup; no SQL/storage cleanup was run.
- **Files changed for this phase:** `ChatActivity.java`, `ShareActions.java`; added `ShareActionsLabelTest.java`. No migration, Cloud deployment, or data reset.
- **Validation:** `:app:assembleDebug :app:testDebugUnitTest` PASS; 28/28 tests, zero failures/errors. `git diff --check` PASS for the changed tracked source. Pixel_4 app update/install-over-existing-data and both targeted flows verified.
- **Remaining:** Admin-side receipt/notification for the test messages; simultaneous Realtime; Friend accept/decline and Block; video attachment (image attachment only tested); incoming Story reply/privacy; Reel interactions; group join/approval; album media mutations; non-message notification destinations; Feed scroll retention. Voice remains `DEFERRED BY PROJECT OWNER`; Marketplace remains UI only/out of scope.
- **Groups discovery check:** reopened Groups Home on Pixel_4. `Nhóm của bạn` showed only the existing private one-member test group; the Discover tab showed no separate eligible public group in this account's current results. Join/leave and approval state transitions remain NOT VERIFIED; no membership was changed and no group fixture was created.
