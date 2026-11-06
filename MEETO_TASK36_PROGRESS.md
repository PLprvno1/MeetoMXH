# MEETO Task 36 — implementation checkpoint

Updated **2026-11-06**, workspace `D:\Android\MeetoMXH`, project `qjpohzpapiwdrxsionyr`, AVD **Pixel_4**.

**Overall: PARTIAL. Local implementation and targeted checks completed; Cloud integration BLOCKED by the explicitly undeployed backend migrations. Do not report system-wide completion or tester issues as fully FIXED.**

## Product decisions and preserved data

- Exactly five Social tabs; the dedicated Admin surface remains separate.
- Follow-only model, public/private accounts, approved follow requests, text-only message introductions, recipient-approved Messenger group invitations.
- Friendship, Community Groups, Memories and Marketplace are **REMOVED FROM PRODUCT**. Their Cloud tables/history remain intact. Retired Android classes/layouts/callers and obsolete test scenarios were removed or replaced; obsolete notifications are filtered and excluded from new server pagination/counts.
- Voice is **DEFERRED BY OWNER**. Entry point hidden/disabled; recording/playback source preserved. No voice verifier, Edge Function, worker or cron investigation/deployment.
- No Cloud SQL deployment, Dashboard change, Storage mutation, account creation, moderation mutation, or Cloud fixture creation was performed. The owner-provided Report1.docx and Report2.docx are unchanged.
- Existing data is preserved. Legacy `friends` content remains owner-only; legacy community memberships grant no access. Legacy `group` audience remains valid in schema so historical group posts do not prevent migration, but is inaccessible from Social.
- Legacy private chats without two-way sender history retain messages with pending consent. Current contact permission or explicit recipient acceptance is required. Existing accepted chats survive privacy changes/unfollow. Old conversation IDs do not bypass approval or block checks.
- Archive is per member; new messages remain archived until explicit unarchive. Mute is independent.

## Phase status

| Phase | Result |
|---|---|
| 0 Baseline/dependency map | Task 35, clean Git baseline, active source paths, both report texts and tester video-control screenshot inspected. Only pre-existing untracked inputs were Report1/2. |
| 1 Feature removal | REMOVED FROM PRODUCT; source registrations, drawer/profile/search/share routes, friendship repository/models/fragment, community activities/repository, Memories/Marketplace source/layouts removed. Messenger groups retained. Build and targeted navigation PASS. |
| 2 Follow/public-private | PASS for local SQL scope; Android wired. Cloud BLOCKED. Includes follower removal, cancel/accept/reject, safe audience handling, optional unique username editing and accent/token/username search. |
| 3 Direct/message requests | PASS for local SQL scope; Android request screen/contact policy/Story routing wired. Cloud transport/two-account E2E NOT VERIFIED. |
| 4 Group chat | PASS for local SQL authorization/state scope. Android multi-select/search/invitations/unified details/avatar/role controls/archive/search/leave wired. Actual image/video upload and simultaneous delivery NOT VERIFIED. |
| 5 Tester/Feed/Reels/Stories/Profile/Albums | PARTIAL. Context cards, count reconciliation, email callbacks, playback controls, private audiences, profile footer pagination, album grids/paging/forms and block rows implemented. Physical media playback and complete live flows NOT VERIFIED. |
| 6 Admin moderation | PASS for local SQL decisions/atomicity/protected-target/report-media scope; Android decisions/previews wired. Cloud E2E NOT VERIFIED. |
| 7 Validation | Debug APK + instrumentation APK + 30 JVM tests PASS. Local SQL **109 assertions PASS**. Final APKs installed over existing Pixel_4 data; targeted UI smoke **4/4 PASS**. Migration manifest **40/40 PASS**, with all **36 historical checksums unchanged**. `git diff --check` PASS. Full acceptance remains blocked. |

## Root causes and concrete changes

- Profile still called friendship repository and contained two identical “Đóng” actions; removed friendship paths and added explicit block/unblock/report actions with confirmation. The following-list direction accidentally used `Đóng`; corrected to `following`.
- SQL authorized friends and community groups; new helpers enforce account privacy plus audience and current approved follows across posts, shares, Stories, Reels, albums, raw media/Storage and attention notifications. Raw profile column grants no longer expose private biography/cover. Legacy community media's inline authorization branch was disabled too.
- Old Direct RPC immediately created memberships for strangers; new contact policy requires approved follow for private accounts or one text request for public strangers. Requests have pair uniqueness, idempotency and recipient-owned resolution. Rejection/block cannot silently reopen them.
- Group creation used a single-choice friends picker and auto-added members. It now searches server pages, keeps multiple selections across searches, excludes self/duplicates, requires two invitees, and creates invitations. Only active accepted members read/send. SQL prevents duplicate active members, protects owner/admin roles and transfers ownership on leave.
- Avatar and title opened different group actions; both now enter ConversationDetailsActivity. Member rows wrap with adequate height. Group avatars use the existing private avatar-upload pipeline, group-scoped media access, and retained cleanup references.
- Archive changed global conversation state and appended a label; it now stores `archived_at` per membership and filters Inbox/archive/search independently. Conversation search normalizes Vietnamese text, filters on server before LIMIT, pages all matching results, ignores left chats, and has query clearing/stale-response suppression.
- Task 35 shared videos bypassed source context. Shared cards retain author/avatar/caption/preview/type and always open reauthorized PostDetails. Its media buttons choose image/video viewers by MIME. Source permissions are rechecked after privacy changes; SQL tests cover shared/saved references and Reels.
- Comment UI did not reconcile owning cards. PostUpdates fetches authoritative counts after successful comment/reply/delete and updates Feed/Profile/Reels/Saved adapters without optimistic double increments or a whole-feed reload.
- Register lacked confirmation input and always promised an email; it now validates matching passwords, distinguishes actual session responses, has resend, and shows callback results for PKCE/token-hash/validated implicit flows. Callback error links have a visible recovery route.
- Read-only Cloud Auth settings: email enabled, signup enabled, **mailer_autoconfirm=false**. Confirmation is already enabled. Redirect allowlist/template and full delivery flow remain unverified; no settings changed.
- Video controls had white icons on light circles in the tester screenshot; controls now have explicit contrast, seek/play controls and a dark overlay. Video errors log safe Media3 code/cause type. Found and corrected a source defect where Reels called `failed()` on `STATE_READY`, displaying Retry during successful playback. **The tester's original black-video/HTTP/codec root cause is not yet established.**
- Search Explore sent an empty string to an RPC requiring a nonempty search term; added a paged authorized media Explore RPC. Name search formerly matched only the display-name prefix; now matches normalized tokens/full name/middle name/surname/username, excluding System Admin.
- Profile's misplaced Load More was removed and pagination follows its outer NestedScrollView. Album cards show human titles, counts and audience labels; photo cells have explicit size, labels contain no technical timestamp/ID, and album/photo pages use server cursors. Form failures preserve the dialog for retry.
- Block list displayed IDs; it now uses names/avatar presets and explicit unblock controls. Requests and follows do not revive on unblock.
- Admin report “Xử lý” only warned/closed the report. New decisions dismiss/warn/hide/remove/suspend, change the actual target state, resolve the report and write audit in one transaction; failure leaves both report/audit unchanged. Case-scoped media previews do not expose unrelated private assets. System Admin/self protections are checked on server, including old Direct IDs.
- Pixel_4 smoke exposed Material 3's 80dp minimum inside the compact 54dp tab area. Explicit tab/item minimums/padding fixed clipped hit areas; navigation retest PASS.

## Acceptance issue matrix

| Issue | Evidence/status |
|---|---|
| R1-01 / R2-01 Register/email | PARTIAL: source implemented; expired-link emulator PASS; Cloud settings read-only verified; actual email→verification→login NOT VERIFIED. |
| R1-02 User search | PASS local SQL names/accents/username/admin exclusion; Cloud Android search BLOCKED. |
| R1-03 Video | PARTIAL: MIME routing, safe diagnostics and Reels ready/error bug corrected; original fixture playback/root cause NOT VERIFIED. |
| R1-04 Comment sync | PASS local SQL retry/reply/delete counts; Android card reconciliation implemented, live UI mutation NOT VERIFIED. |
| R1-05 Shared context | PASS local SQL attribution/Reel type/privacy redaction; Android preview/context destination implemented, live recipient UI NOT VERIFIED. |
| R1-06 Story reply | PASS local SQL request→accept→quoted message, private restrictions; two-account Android E2E NOT VERIFIED. |
| R1-07 Profile menu | PARTIAL: explicit actions/confirmations implemented; no real block fixture used on Cloud. |
| R1-08 Block list | PARTIAL: identity rows/unblock UI; local SQL block enforcement PASS; live two-sided UI NOT VERIFIED. |
| R1-09 Admin security | PASS local SQL search/follow/DM exclusion, old-ID denial, self/other-admin protection and failed moderation atomicity; Cloud NOT VERIFIED. |
| R1-10 Albums | PARTIAL: grid/forms/paging/labels/source-photo flow implemented; local SQL album/media privacy PASS; physical photo mutations NOT VERIFIED. |
| R2-03 Friendship | REMOVED FROM PRODUCT. Replaced by followers/following/request routes. |
| R2-04 Conversation details | PARTIAL: name fallback, profile/media/mute/archive/block/report/group actions implemented; live full action set NOT VERIFIED. |
| R2-05 Share UI | PARTIAL: scrollable Bottom Sheet, attribution, vertical privacy choices, loading/retry, separate Feed/Messenger/copy routes; live keyboard/share submission NOT VERIFIED. |
| R2-06 Profile footer | PARTIAL: legacy section removed; outer-scroll cursor pagination implemented; long real timeline E2E NOT VERIFIED. |
| R2-07 Group creation | PASS local SQL minimum/duplicates/idempotency/invite acceptance/text/media references; Android disabled-until-enough picker smoke PASS. Cloud create/send NOT VERIFIED. |
| R2-08 Group details | PARTIAL: unified entry point/details/avatar/role UI; SQL authorization PASS; live screen actions NOT VERIFIED. |
| R2-09 Duplicate members | PASS local SQL uniqueness, active/pending/rejected exclusions; Android candidate filtering implemented. |
| R2-10 Leave | PASS local SQL membership/access/Inbox/media denial and ownership transfer; Android confirmation/refresh wired; Cloud NOT VERIFIED. |
| R2-11 Member rows | PARTIAL: wrapped text/minimum height; long-name runtime NOT VERIFIED. |
| R2-12 Archive/search | PASS local SQL isolation/search filters before pagination/left exclusion; client routes/paging/clear wired; Cloud NOT VERIFIED. |
| R2-13 Reports | PASS local SQL actual hide, audit, idempotency, protected failure rollback and reported-media authorization; live fixture moderation NOT VERIFIED. |

## Migrations — prepared, NOT deployed

1. `supabase/migrations/20261106000100_follow_only_privacy.sql`
2. `supabase/migrations/20261106000200_messaging_consent.sql`
3. `supabase/migrations/20261106000300_notifications_moderation.sql`
4. `supabase/migrations/20261106000400_group_chat_avatar.sql`

See [deployment handoff](supabase/TASK36_DEPLOYMENT.md) and [read-only Cloud smoke/inventory](supabase/tests/task36-cloud-smoke.sql). Historical migration checksums must remain unchanged. New checksums are appended to the manifest at final packaging.

## Source / test artifacts

- New RequestCenterActivity; SocialActions, PeoplePicker, AudienceChoices, SocialContract, PostUpdates; custom player controls; callback/security smoke tests.
- Modified Main/Profile/Settings/Search/CreatePost/Reel/Story/Chat/ConversationDetails/Albums, shared cards/DTO/models, Feed/Reels/Saved/comment adapters and relevant XML/Manifest/tests. Removed 22 exact retired module files, without recursive deletion or Cloud schema cleanup.
- Complete changed-path inventory: `MEETO_TASK36_CHANGED_FILES.txt` (generated at final packaging).
- Local SQL harness: `supabase/tests/task36-local.cjs`, ephemeral PGlite 0.5.15, **109 assertions**. Managed Auth/Storage/Realtime are mocks; worker transport/cron and voice execution are excluded. Historical retired-community function-body validation is disabled while loading its pre-existing CASE syntax; all Task 36 migrations validate strictly. Includes a pre-migration legacy community fixture to verify data-preserving compatibility.
- Debug build and instrumentation APK compile; **30 JVM tests** pass. Logs: `build/task36-gradle.log`, `build/task36-sql.log`.
- Targeted Pixel_4 UI: `build/task36-instrumentation.log` (4 tests); screenshot `build/task36-before-nav.png`; report extracts/screenshots in `build/task36-Report*.docx*` are local ignored artifacts.
- No fixture credentials, tokens, Storage paths or personal message content are printed in reports. Local synthetic users/posts/messages/media registries disappear when the ephemeral harness closes; no actual media file or Cloud fixture was uploaded.
- Deliverable APK: `app/build/outputs/apk/debug/app-debug.apk`. It intentionally requires the new backend for Social features; do not interpret successful installation as full business-flow PASS.

## Blockers / limitations / exact next step

1. **Owner reviews deployed catalog and applies 037–040 through the normal migration process. Do not auto-deploy.** Task 35 recorded drift; do not replay foundation migrations over Cloud. Run the read-only inventory before and after.
2. The debug client **fails closed for Social RPCs/media until the new capability endpoint confirms all migrations**. Auth restoration/onboarding and the independent Admin role resolver remain available. Missing backend features show an upgrade/error state, never fake success. This is a deployment dependency, not Cloud PASS.
3. After deployment, use authorized A/B/C sessions and disposable fixtures for actual register/mail redirects, private media/Storage HTTP, DM requests/Story replies, two invitees joining a group, physical image/video upload/playback, sharing/receipts/realtime, archive/search/leave/block, albums, and safe report hide/remove/restore. Parallel race tests and full notification routing remain NOT VERIFIED.
4. Capture the original failing video fixture's Media3/HTTP evidence after valid authorization is available. Do not infer a codec/network/backend root cause from the old generic error.
5. Do not resume Voice, investigate workers/cron, wipe app data, clean legacy Cloud tables, create real-user moderation fixtures, or repeat the completed baseline survey. Continue from these exact remaining integration checks.
