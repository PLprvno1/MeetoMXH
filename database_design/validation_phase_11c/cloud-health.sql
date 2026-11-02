-- Read-only aggregate evidence. No event payloads, user content, tokens or secrets.
SELECT jsonb_build_object(
  'captured_at', statement_timestamp(),
  'server_version', current_setting('server_version'),
  'ledger_versions', (SELECT count(*) FROM supabase_migrations.schema_migrations),
  'chat_events_last_30_minutes', (
    SELECT coalesce(jsonb_agg(s), '[]'::jsonb) FROM (
      SELECT state, count(*) AS events, max(attempts) AS max_attempts
      FROM public.event_outbox
      WHERE event_type='chat.invalidate' AND created_at > statement_timestamp()-interval '30 minutes'
      GROUP BY state ORDER BY state
    ) s
  ),
  'recent_chat_cron_runs', (
    SELECT coalesce(jsonb_agg(r), '[]'::jsonb) FROM (
      SELECT d.status,d.start_time,d.end_time
      FROM cron.job_run_details d JOIN cron.job j USING (jobid)
      WHERE j.jobname='meeto-chat-dispatch-every-minute'
      ORDER BY d.start_time DESC LIMIT 5
    ) r
  )
) AS summary;
