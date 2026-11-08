# Inbox realtime and ordering — 2026-11-08

The inbox already watched the private Realtime channel, but each refresh made
membership and mute RPCs for every conversation before rendering. Chat outbox
broadcasts were scheduled once a minute. Empty conversations also sorted below
all conversations with messages, regardless of their creation time.

Changes:

- Migration `20261108001200_inbox_realtime_projection.sql` sends a private
  invalidation from `c_event`, using the existing recipient authorization helper.
  Events contain no message content. Transaction rollback also rolls back the
  broadcast row. Transport failures leave the existing outbox available for retry.
- Immediate and queued broadcasts share their event ID for client deduplication.
- The conversation projection now includes active member IDs, mute state and
  authorized peer avatar data, avoiding per-conversation client requests.
- Inbox watches continue to refetch on broadcasts/reconnection and poll every
  five seconds while started as a fallback. Stopping the screen removes its watch.
- Search results refresh when inbox events arrive. Temporary refresh failures
  retain the currently displayed conversations.
- Ordering uses last-message time, or creation time for an empty conversation,
  with stable ID ordering for ties. The displayed time uses the same rule.

Validation: debug build and seven unit tests passed (`InboxOrderTest`,
`InboxProjectionTest`). Debug APK installed on the emulator. A transient missing
generated R.jar was resolved by regenerating resources and rerunning the build.

Deployment: apply migration 012 to Supabase before expecting the immediate event
and single-page metadata path. Older servers retain the legacy per-row fallback.
Cloud migration and live two-account latency have not been tested in this session.
After deployment, leave the recipient on Inbox and send from another account;
check preview/unread/order without manual refresh, then check reconnection,
search results, archive/mute, a new empty group, and stopped-screen behavior.
