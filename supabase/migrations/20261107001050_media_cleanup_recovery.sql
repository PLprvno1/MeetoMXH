-- Task 54B.2: bounded media-cleanup recovery and exact Storage contract.
-- Timestamp is intentionally before the still-un-deployed 20261107001100 Admin
-- migration. Deploy this first, recover exhausted leases, then deploy 54B.
-- No Storage DELETE or historical row rewrite is performed by this migration.
BEGIN;

DO $$
DECLARE migration_present boolean;
BEGIN
 IF current_setting('server_version_num')::integer < 120000 THEN
  RAISE EXCEPTION 'Media cleanup recovery requires PostgreSQL 12+';
 END IF;
 IF to_regprocedure('pg_catalog.gen_random_uuid()') IS NULL THEN
  RAISE EXCEPTION 'Media cleanup recovery requires pg_catalog.gen_random_uuid()';
 END IF;
 IF to_regclass('supabase_migrations.schema_migrations') IS NOT NULL THEN
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM supabase_migrations.schema_migrations WHERE version=$1)'
    INTO migration_present USING '20261107001100';
  IF migration_present THEN
   RAISE EXCEPTION 'Task 54B is installed; use a later forward-only cleanup migration';
  END IF;
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM supabase_migrations.schema_migrations WHERE version=$1)'
    INTO migration_present USING '20261107001050';
  IF migration_present THEN RAISE EXCEPTION 'Task 54B.2 migration already installed'; END IF;
 END IF;
 IF to_regprocedure('private.meeto_claim_media_cleanup(integer)') IS NULL
 OR to_regprocedure('private.meeto_finish_media_cleanup(uuid,integer,boolean,text)') IS NULL
 OR to_regprocedure('public.meeto_worker_claim_cleanup(integer)') IS NULL
 OR to_regprocedure('public.meeto_worker_finish_cleanup(uuid,integer,boolean,text)') IS NULL
 OR to_regprocedure('private.meeto_worker_finish_run(uuid,jsonb)') IS NULL
 OR to_regprocedure('private.schedule_media_cleanup()') IS NULL
 OR to_regprocedure('private.task43_asset_live(uuid)') IS NULL
 OR to_regclass('private.worker_invocations') IS NULL
 OR to_regclass('public.event_outbox') IS NULL
 OR to_regclass('public.media_assets') IS NULL
 OR to_regclass('public.reports') IS NULL
  OR to_regclass('cron.job') IS NULL
 OR to_regclass('storage.objects') IS NULL
 OR to_regclass('storage.buckets') IS NULL THEN
  RAISE EXCEPTION 'Apply Cloud workers, Story/voice uploads and Task43 reference-graph prerequisites first';
 END IF;
 IF EXISTS(SELECT 1 FROM (VALUES
  ('private.worker_invocations','singleton'),('private.worker_invocations','run_id'),
  ('private.worker_invocations','consumed_at'),('private.worker_invocations','completed_at'),
  ('private.worker_invocations','execution_until'),('public.event_outbox','id'),
  ('public.event_outbox','event_key'),('public.event_outbox','event_type'),
  ('public.event_outbox','aggregate_type'),('public.event_outbox','aggregate_id'),
  ('public.event_outbox','payload'),('public.event_outbox','state'),('public.event_outbox','attempts'),
  ('public.event_outbox','available_at'),('public.event_outbox','lease_until'),('public.event_outbox','processed_at'),
  ('public.media_assets','id'),('public.media_assets','owner_id'),('public.media_assets','bucket_id'),
  ('public.media_assets','object_path'),('public.media_assets','purpose'),('public.media_assets','mime_type'),
  ('public.media_assets','state'),('public.media_assets','delete_after'),('public.media_assets','metadata'),
  ('public.post_media','post_id'),('public.post_media','asset_id'),
  ('public.message_media','message_id'),('public.message_media','asset_id'),
  ('public.posts','id'),('public.posts','reel_cover_asset_id'),('public.posts','state'),
  ('public.stories','id'),('public.stories','media_asset_id'),
  ('public.messages','id'),('public.messages','state'),
  ('public.profiles','id'),('public.profiles','avatar_asset_id'),('public.profiles','cover_asset_id'),
  ('public.conversations','avatar_asset_id'),('public.conversations','state'),
  ('public.profile_album_items','asset_id'),('public.profile_album_items','source_post_id'),
  ('public.reports','post_id'),('public.reports','story_id'),('public.reports','message_id')
 ) needed(table_name,column_name)
 WHERE NOT EXISTS(SELECT 1 FROM information_schema.columns c
  WHERE c.table_schema=split_part(needed.table_name,'.',1)
    AND c.table_name=split_part(needed.table_name,'.',2)
    AND c.column_name=needed.column_name)) THEN
  RAISE EXCEPTION 'Media cleanup recovery prerequisite columns are missing';
 END IF;
 IF EXISTS(SELECT 1 FROM (VALUES
  ('profile-media',ARRAY['image/png','image/jpeg','image/webp']::text[]),
  ('social-media',ARRAY['image/png','image/jpeg','image/webp','video/mp4']::text[]),
  ('chat-media',ARRAY['image/png','image/jpeg','image/webp','video/mp4','audio/mp4']::text[])
 ) required(bucket_id,mimes)
 WHERE NOT EXISTS(SELECT 1 FROM storage.buckets b WHERE b.id=required.bucket_id AND b.public IS FALSE
   AND required.mimes <@ coalesce(b.allowed_mime_types,ARRAY[]::text[]))) THEN
  RAISE EXCEPTION 'Private Storage buckets do not allow the source-supported media MIME contract';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='private' AND p.proname IN
   ('meeto_claim_media_cleanup','meeto_finish_media_cleanup','schedule_media_cleanup','task43_asset_live')
   AND pg_get_userbyid(p.proowner)<>current_user) THEN
  RAISE EXCEPTION 'Cleanup function owner differs from migration role; review EXECUTE dependencies before deployment';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE (n.nspname='private' AND p.proname IN ('meeto_claim_media_cleanup','meeto_finish_media_cleanup')
     AND pg_get_userbyid(p.proowner)<>current_user)
     OR (n.nspname='public' AND p.proname IN ('meeto_worker_claim_cleanup','meeto_worker_finish_cleanup')
     AND pg_get_userbyid(p.proowner)<>current_user)) THEN
  RAISE EXCEPTION 'Worker broker/private cleanup owner mismatch; inspect EXECUTE closure before deployment';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE (n.nspname='private' AND p.proname IN ('meeto_claim_media_cleanup','meeto_finish_media_cleanup')
     OR n.nspname='public' AND p.proname IN ('meeto_worker_claim_cleanup','meeto_worker_finish_cleanup'))
  GROUP BY n.nspname,p.proname HAVING count(*)<>1) THEN
  RAISE EXCEPTION 'Unexpected cleanup RPC overload; inspect signatures before deployment';
 END IF;
  IF (SELECT count(*) FROM cron.job WHERE jobname='meeto-media-cleanup-every-five-minutes' AND NOT active)<>1 THEN
   RAISE EXCEPTION 'Pause the shared media cleanup/voice verification schedule before applying 54B.2';
  END IF;
  IF NOT has_table_privilege(current_user,'public.event_outbox','SELECT,INSERT,UPDATE')
   OR NOT has_table_privilege(current_user,'public.media_assets','SELECT,UPDATE')
   OR NOT has_table_privilege(current_user,'storage.objects','SELECT')
   OR NOT has_table_privilege(current_user,'storage.buckets','SELECT')
   OR NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     JOIN pg_roles r ON r.rolname=current_user::text WHERE n.nspname='storage' AND c.relname='objects'
      AND (r.rolsuper OR r.rolbypassrls OR (c.relowner=r.oid AND NOT c.relforcerowsecurity))) THEN
   RAISE EXCEPTION 'Cleanup definer requires worker table rights and Storage catalog read that bypasses RLS';
  END IF;
  IF EXISTS(SELECT 1 FROM private.worker_invocations w WHERE w.singleton AND w.completed_at IS NULL AND
    ((w.consumed_at IS NULL AND w.expires_at>statement_timestamp()) OR
     (w.consumed_at IS NOT NULL AND coalesce(w.execution_until,w.expires_at)>statement_timestamp()))) THEN
   RAISE EXCEPTION 'Drain the current or still-valid cleanup worker token before applying 54B.2';
  END IF;
 IF to_regclass('private.media_cleanup_attempt_audit') IS NOT NULL
 OR to_regclass('private.media_cleanup_recovery_log') IS NOT NULL
 OR to_regprocedure('private.meeto_cleanup_asset_blocked(uuid)') IS NOT NULL
 OR to_regprocedure('private.meeto_reap_exhausted_cleanup(uuid[],uuid,integer,text)') IS NOT NULL
 OR to_regprocedure('private.meeto_reconcile_cleanup_event(uuid,integer,uuid,text,text)') IS NOT NULL THEN
  RAISE EXCEPTION 'Task 54B.2 objects already exist; inspect ledger and drift';
 END IF;
END $$;

CREATE TABLE private.media_cleanup_attempt_audit (
 event_id uuid NOT NULL,
 attempt integer NOT NULL CHECK (attempt > 0),
 asset_id uuid NOT NULL,
 storage_outcome text NOT NULL DEFAULT 'not_observed'
   CHECK (storage_outcome IN ('not_observed','absent','present','unknown')),
 observation_source text CHECK (observation_source IN ('worker_head','owner_head','legacy_lease_reaper')),
 delete_http_status integer CHECK (delete_http_status IS NULL OR delete_http_status BETWEEN 100 AND 599),
 error_code text CHECK (error_code IS NULL OR error_code IN
   ('storage_error','storage_denied','storage_unavailable','storage_present','storage_unknown',
    'invalid_cleanup_contract','cleanup_finalize_blocked')),
 claimed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 observed_at timestamptz,
 finish_result text CHECK (finish_result IS NULL OR finish_result IN
   ('done','retry_scheduled','dead_lettered','blocked_after_storage_absent','fence_mismatch')),
 finished_at timestamptz,
 PRIMARY KEY(event_id,attempt)
);
CREATE TABLE private.media_cleanup_recovery_log (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 source_event_id uuid NOT NULL,
 attempt integer NOT NULL CHECK (attempt > 0),
 request_id uuid NOT NULL,
 action text NOT NULL CHECK (action IN
   ('dead_letter_unknown','storage_absent_confirmed','retry_scheduled','reconciliation_blocked','unknown_retained')),
 storage_outcome text NOT NULL CHECK (storage_outcome IN ('absent','present','unknown')),
 replacement_event_id uuid,
 actor_name text NOT NULL DEFAULT session_user,
 reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 receipt jsonb NOT NULL CHECK (jsonb_typeof(receipt)='object'),
 UNIQUE(source_event_id,request_id)
);
ALTER TABLE private.media_cleanup_attempt_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE private.media_cleanup_recovery_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON private.media_cleanup_attempt_audit,private.media_cleanup_recovery_log
 FROM PUBLIC,anon,authenticated,service_role;

-- Dynamic optional lookup avoids a dependency cycle: 54B creates this table
-- later. Reports and Task43 live references are always checked here.
CREATE FUNCTION private.meeto_cleanup_asset_blocked(p_asset_id uuid) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE held boolean:=false;
BEGIN
 IF p_asset_id IS NULL OR private.task43_asset_live(p_asset_id) THEN RETURN true; END IF;
 IF EXISTS(
  SELECT 1 FROM public.reports r
  WHERE EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.post_id=r.post_id AND pm.asset_id=p_asset_id)
     OR EXISTS(SELECT 1 FROM public.posts p WHERE p.id=r.post_id AND p.reel_cover_asset_id=p_asset_id)
     OR EXISTS(SELECT 1 FROM public.stories s WHERE s.id=r.story_id AND s.media_asset_id=p_asset_id)
     OR EXISTS(SELECT 1 FROM public.message_media mm WHERE mm.message_id=r.message_id AND mm.asset_id=p_asset_id)
 ) THEN RETURN true; END IF;
 -- Removed/recalled messages remain reportable tombstones; keep their
 -- attachments available as possible evidence even when no report exists yet.
 IF EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id
           WHERE mm.asset_id=p_asset_id AND m.state<>'sent') THEN RETURN true; END IF;
 IF to_regclass('private.moderation_media_holds') IS NOT NULL THEN
  EXECUTE 'SELECT EXISTS(SELECT 1 FROM private.moderation_media_holds WHERE asset_id=$1)'
    INTO held USING p_asset_id;
 END IF;
 RETURN held;
END $$;

CREATE OR REPLACE FUNCTION private.meeto_claim_media_cleanup(p_batch integer DEFAULT 5) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 IF p_batch IS NULL OR p_batch<1 OR p_batch>5 THEN RAISE EXCEPTION 'invalid_cleanup_batch'; END IF;
 -- Takes the existing shared media-writer lock for the duration of the claim.
 PERFORM private.schedule_media_cleanup();
 WITH candidates AS (
  SELECT e.id AS event_id,a.id AS asset_id,a.owner_id,a.bucket_id,a.object_path,a.purpose,a.mime_type
  FROM public.event_outbox e
  JOIN public.media_assets a ON a.id=e.aggregate_id
  JOIN storage.buckets b ON b.id=a.bucket_id AND b.public IS FALSE
  WHERE e.event_type='media.retire' AND e.aggregate_type='media_asset'
    AND (e.state='pending' OR (e.state='processing' AND coalesce(e.lease_until,'-infinity'::timestamptz)<statement_timestamp()))
    AND e.attempts<5 AND e.available_at<=statement_timestamp()
    AND a.state='pending_delete' AND coalesce(a.delete_after,statement_timestamp())<=statement_timestamp()
    AND a.owner_id IS NOT NULL
    AND ((a.bucket_id='profile-media' AND a.purpose IN ('avatar','cover')
          AND a.mime_type IN ('image/png','image/jpeg','image/webp'))
      OR (a.bucket_id='social-media' AND a.purpose IN ('post','story')
          AND a.mime_type IN ('image/png','image/jpeg','image/webp','video/mp4'))
      OR (a.bucket_id='chat-media' AND a.purpose='message'
          AND a.mime_type IN ('image/png','image/jpeg','image/webp','video/mp4','audio/mp4')))
    AND a.mime_type=ANY(coalesce(b.allowed_mime_types,ARRAY[]::text[]))
    AND a.object_path=a.owner_id::text||'/'||a.id::text||'/original.'||
      CASE a.mime_type WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
        WHEN 'image/webp' THEN 'webp' WHEN 'video/mp4' THEN 'mp4' WHEN 'audio/mp4' THEN 'm4a' END
    AND NOT private.meeto_cleanup_asset_blocked(a.id)
    -- Never issue DELETE for an unrecognized object. Missing catalog rows are
    -- handled by exact owner HEAD/reconciliation, not by a blind Storage call.
    AND EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path
      AND o.owner_id=a.owner_id::text AND o.metadata->>'mimetype'=a.mime_type)
  ORDER BY e.available_at,e.created_at,e.id LIMIT p_batch FOR UPDATE OF e SKIP LOCKED
 ), claimed AS (
  UPDATE public.event_outbox e SET state='processing',attempts=e.attempts+1,
    lease_until=statement_timestamp()+interval '2 minutes'
  FROM candidates c WHERE e.id=c.event_id
  RETURNING e.id AS event_id,e.attempts,e.aggregate_id
 ), audited AS (
  INSERT INTO private.media_cleanup_attempt_audit(event_id,attempt,asset_id)
  SELECT c.event_id,c.attempts,c.aggregate_id FROM claimed c
  RETURNING event_id,attempt
 )
 SELECT coalesce(jsonb_agg(jsonb_build_object('event_id',c.event_id,'attempt',c.attempts,
    'asset_id',a.id,'owner_id',a.owner_id,'bucket_id',a.bucket_id,'object_path',a.object_path,
    'purpose',a.purpose,'mime_type',a.mime_type) ORDER BY c.event_id),'[]'::jsonb)
 INTO result
 FROM claimed c JOIN public.media_assets a ON a.id=c.aggregate_id
 JOIN audited au ON au.event_id=c.event_id AND au.attempt=c.attempts;
 RETURN coalesce(result,'[]'::jsonb);
END $$;

CREATE FUNCTION private.meeto_record_media_cleanup_observation(
 p_event_id uuid,p_attempt integer,p_outcome text,p_delete_http_status integer
) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE asset_id uuid; old_outcome text; old_status integer;
BEGIN
 IF p_event_id IS NULL OR p_attempt IS NULL OR p_outcome IS NULL OR p_outcome NOT IN ('absent','present','unknown')
 OR (p_delete_http_status IS NOT NULL AND p_delete_http_status NOT BETWEEN 100 AND 599) THEN RETURN false; END IF;
 SELECT e.aggregate_id INTO asset_id FROM public.event_outbox e
 WHERE e.id=p_event_id AND e.event_type='media.retire' AND e.aggregate_type='media_asset'
  AND e.state='processing' AND e.attempts=p_attempt;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT storage_outcome,delete_http_status INTO old_outcome,old_status
 FROM private.media_cleanup_attempt_audit WHERE event_id=p_event_id AND attempt=p_attempt FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 IF old_outcome<>'not_observed' THEN
  RETURN old_outcome=p_outcome AND old_status IS NOT DISTINCT FROM p_delete_http_status;
 END IF;
 UPDATE private.media_cleanup_attempt_audit SET storage_outcome=p_outcome,
   observation_source='worker_head',delete_http_status=p_delete_http_status,observed_at=statement_timestamp(),
   error_code=CASE p_outcome WHEN 'present' THEN 'storage_present' WHEN 'unknown' THEN 'storage_unknown' END
 WHERE event_id=p_event_id AND attempt=p_attempt;
 RETURN FOUND;
END $$;

CREATE OR REPLACE FUNCTION private.meeto_finish_media_cleanup(
 p_event_id uuid,p_attempt integer,p_success boolean,p_error_code text DEFAULT NULL
) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE asset_id uuid; safe_error text; observed text; a public.media_assets; finished boolean;
BEGIN
 IF p_event_id IS NULL OR p_attempt IS NULL OR p_success IS NULL THEN RETURN false; END IF;
 PERFORM pg_advisory_xact_lock(6061101);
 SELECT e.aggregate_id INTO asset_id FROM public.event_outbox e
 WHERE e.id=p_event_id AND e.event_type='media.retire' AND e.aggregate_type='media_asset'
  AND e.state='processing' AND e.attempts=p_attempt;
 IF NOT FOUND THEN
  UPDATE private.media_cleanup_attempt_audit SET finish_result='fence_mismatch',finished_at=statement_timestamp()
   WHERE event_id=p_event_id AND attempt=p_attempt AND finish_result IS NULL;
  RETURN false;
 END IF;
 SELECT * INTO a FROM public.media_assets WHERE id=asset_id FOR UPDATE;
 IF NOT FOUND THEN
  UPDATE public.event_outbox SET state='dead',lease_until=NULL,
   payload=payload||jsonb_build_object('last_error','cleanup_finalize_blocked')
   WHERE id=p_event_id AND state='processing' AND attempts=p_attempt;
  UPDATE private.media_cleanup_attempt_audit SET error_code='cleanup_finalize_blocked',
   finish_result='blocked_after_storage_absent',finished_at=statement_timestamp()
   WHERE event_id=p_event_id AND attempt=p_attempt;
  RETURN false;
 END IF;
 PERFORM 1 FROM public.event_outbox WHERE id=p_event_id AND state='processing' AND attempts=p_attempt FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 SELECT storage_outcome INTO observed FROM private.media_cleanup_attempt_audit
 WHERE event_id=p_event_id AND attempt=p_attempt FOR UPDATE;
 IF NOT FOUND THEN
  UPDATE public.event_outbox SET state='dead',lease_until=NULL,
   payload=payload||jsonb_build_object('last_error','cleanup_finalize_blocked')
   WHERE id=p_event_id AND state='processing' AND attempts=p_attempt;
  RETURN false;
 END IF;
 IF p_success AND observed='absent'
 AND a.state='pending_delete'
 AND NOT EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path)
 AND NOT private.meeto_cleanup_asset_blocked(a.id) THEN
  UPDATE public.media_assets SET state='deleted',metadata='{}'::jsonb
   WHERE id=asset_id AND state='pending_delete';
  IF FOUND THEN
   UPDATE public.event_outbox SET state='done',lease_until=NULL,processed_at=statement_timestamp()
    WHERE id=p_event_id AND state='processing' AND attempts=p_attempt;
   finished:=FOUND;
   UPDATE private.media_cleanup_attempt_audit SET finish_result=CASE WHEN finished THEN 'done' ELSE 'fence_mismatch' END,
     finished_at=statement_timestamp() WHERE event_id=p_event_id AND attempt=p_attempt;
   RETURN finished;
  END IF;
 END IF;
 IF p_success THEN
  safe_error:='cleanup_finalize_blocked';
 ELSE
  safe_error:=CASE WHEN p_error_code IN
    ('storage_unavailable','storage_denied','storage_error','storage_present','storage_unknown','invalid_cleanup_contract')
    THEN p_error_code ELSE 'storage_error' END;
 END IF;
 UPDATE public.event_outbox SET state=CASE WHEN p_success OR attempts>=5 THEN 'dead' ELSE 'pending' END,
   lease_until=NULL,
   available_at=CASE WHEN p_success OR attempts>=5 THEN available_at
     ELSE statement_timestamp()+make_interval(secs=>least(3600,30*power(2,p_attempt)::integer)) END,
   payload=payload||jsonb_build_object('last_error',safe_error)
 WHERE id=p_event_id AND state='processing' AND attempts=p_attempt;
 finished:=FOUND;
 UPDATE private.media_cleanup_attempt_audit SET error_code=safe_error,
   finish_result=CASE WHEN p_success THEN 'blocked_after_storage_absent'
     WHEN p_attempt>=5 THEN 'dead_lettered' ELSE 'retry_scheduled' END,
   finished_at=statement_timestamp() WHERE event_id=p_event_id AND attempt=p_attempt;
 RETURN false;
END $$;

-- Owner-only bounded transition. It records uncertainty and changes no media
-- state; it never asserts that a Storage DELETE succeeded.
CREATE FUNCTION private.meeto_reap_exhausted_cleanup(
 p_event_ids uuid[],p_request_id uuid,p_expected_count integer,p_reason text
) RETURNS jsonb
LANGUAGE plpgsql SET search_path='' AS $$
DECLARE eid uuid; e public.event_outbox; saved integer; changed integer:=0; rows jsonb:='[]'::jsonb;
BEGIN
 IF p_event_ids IS NULL OR p_request_id IS NULL OR p_expected_count IS NULL
 OR p_expected_count<1 OR p_expected_count>25 OR cardinality(p_event_ids)<>p_expected_count
 OR (SELECT count(DISTINCT event_id) FROM unnest(p_event_ids) AS requested_ids(event_id))<>p_expected_count
 OR p_reason IS NULL OR char_length(btrim(p_reason)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'invalid_bounded_cleanup_recovery';
 END IF;
 SELECT count(*) INTO saved FROM private.media_cleanup_recovery_log
 WHERE source_event_id=ANY(p_event_ids) AND request_id=p_request_id AND action='dead_letter_unknown';
 IF saved=p_expected_count THEN
  SELECT coalesce(jsonb_agg(receipt ORDER BY source_event_id),'[]'::jsonb) INTO rows
   FROM private.media_cleanup_recovery_log WHERE source_event_id=ANY(p_event_ids) AND request_id=p_request_id;
  RETURN jsonb_build_object('replayed',true,'items',rows);
 ELSIF saved>0 THEN RAISE EXCEPTION 'partial_recovery_request_conflict'; END IF;
 IF EXISTS(SELECT 1 FROM cron.job WHERE jobname='meeto-media-cleanup-every-five-minutes' AND active) THEN
  RAISE EXCEPTION 'Keep the shared cleanup/voice verification schedule paused during legacy recovery';
 END IF;
 IF EXISTS(SELECT 1 FROM private.worker_invocations w WHERE w.singleton AND w.completed_at IS NULL AND
   ((w.consumed_at IS NULL AND w.expires_at>statement_timestamp()) OR
    (w.consumed_at IS NOT NULL AND coalesce(w.execution_until,w.expires_at)>statement_timestamp()))) THEN
  RAISE EXCEPTION 'cleanup_worker_invocation_still_active';
 END IF;
 PERFORM pg_advisory_xact_lock(6061101);
 FOR eid IN SELECT event_id FROM unnest(p_event_ids) AS requested(event_id) ORDER BY event_id LOOP
  SELECT * INTO e FROM public.event_outbox WHERE id=eid FOR UPDATE;
  IF NOT FOUND OR e.event_type<>'media.retire' OR e.aggregate_type<>'media_asset'
    OR e.state<>'processing' OR e.attempts<>5 OR coalesce(e.lease_until,'-infinity'::timestamptz)>statement_timestamp()
    OR NOT EXISTS(SELECT 1 FROM public.media_assets a WHERE a.id=e.aggregate_id AND a.state='pending_delete') THEN
   RAISE EXCEPTION 'cleanup_event_does_not_match_expired_exhausted_expectation: %',eid;
  END IF;
  IF EXISTS(SELECT 1 FROM private.media_cleanup_attempt_audit x
    WHERE x.event_id=e.id AND x.attempt=e.attempts AND x.storage_outcome NOT IN ('not_observed','unknown')) THEN
   RAISE EXCEPTION 'cleanup_event_has_a_recorded_storage_outcome: %',eid;
  END IF;
  INSERT INTO private.media_cleanup_attempt_audit(event_id,attempt,asset_id,storage_outcome,
    observation_source,error_code,observed_at,finish_result,finished_at)
  VALUES(e.id,e.attempts,e.aggregate_id,'unknown','legacy_lease_reaper','storage_unknown',
    statement_timestamp(),'dead_lettered',statement_timestamp())
  ON CONFLICT(event_id,attempt) DO UPDATE SET
    storage_outcome='unknown',observation_source='legacy_lease_reaper',error_code='storage_unknown',
    observed_at=statement_timestamp(),finish_result='dead_lettered',finished_at=statement_timestamp()
   WHERE private.media_cleanup_attempt_audit.storage_outcome='not_observed';
  UPDATE public.event_outbox SET state='dead',lease_until=NULL,
    payload=payload||jsonb_build_object('last_error','lease_expired_outcome_unknown',
      'recovery','owner_storage_reconciliation_required')
   WHERE id=e.id AND state='processing' AND attempts=e.attempts;
  IF NOT FOUND THEN RAISE EXCEPTION 'cleanup_event_changed_during_recovery: %',eid; END IF;
  INSERT INTO private.media_cleanup_recovery_log(source_event_id,attempt,request_id,action,storage_outcome,reason,receipt)
  VALUES(e.id,e.attempts,p_request_id,'dead_letter_unknown','unknown',btrim(p_reason),
    jsonb_build_object('event_id',e.id,'asset_id',e.aggregate_id,'attempt',e.attempts,
      'state','dead','storage_outcome','unknown','storage_delete_asserted',false))
  changed:=changed+1;
 END LOOP;
 SELECT coalesce(jsonb_agg(l.receipt ORDER BY l.source_event_id),'[]'::jsonb) INTO rows
  FROM private.media_cleanup_recovery_log l
  WHERE l.source_event_id=ANY(p_event_ids) AND l.request_id=p_request_id AND l.action='dead_letter_unknown';
 RETURN jsonb_build_object('replayed',false,'changed',changed,'storage_outcome','unknown',
   'storage_delete_asserted',false,'items',rows);
END $$;

-- Owner calls this only after a direct HEAD of the exact private object,
-- a successful bucket check, and comparison with storage.objects. "present"
-- creates a new event; original attempts and dead-letter history stay intact.
CREATE FUNCTION private.meeto_reconcile_cleanup_event(
 p_event_id uuid,p_attempt integer,p_request_id uuid,p_storage_state text,p_reason text
) RETURNS jsonb
LANGUAGE plpgsql SET search_path='' AS $$
DECLARE e public.event_outbox; a public.media_assets; object_owner text; object_mime text;
 object_id uuid; replacement uuid; saved private.media_cleanup_recovery_log; receipt jsonb;
BEGIN
 IF p_event_id IS NULL OR p_attempt IS NULL OR p_request_id IS NULL
 OR p_storage_state IS NULL OR p_storage_state NOT IN ('absent','present','unknown') OR p_reason IS NULL
 OR char_length(btrim(p_reason)) NOT BETWEEN 1 AND 500 THEN
  RAISE EXCEPTION 'invalid_cleanup_reconciliation';
 END IF;
 SELECT * INTO saved FROM private.media_cleanup_recovery_log
 WHERE source_event_id=p_event_id AND request_id=p_request_id;
 IF FOUND THEN RETURN saved.receipt||jsonb_build_object('replayed',true); END IF;
 PERFORM pg_advisory_xact_lock(6061101);
 SELECT * INTO e FROM public.event_outbox WHERE id=p_event_id FOR UPDATE;
 IF NOT FOUND OR e.event_type<>'media.retire' OR e.aggregate_type<>'media_asset'
  OR e.state<>'dead' OR e.attempts<>p_attempt THEN RAISE EXCEPTION 'cleanup_event_not_dead_lettered'; END IF;
 SELECT * INTO a FROM public.media_assets WHERE id=e.aggregate_id FOR UPDATE;
 IF NOT FOUND OR a.state<>'pending_delete' OR a.owner_id IS NULL
  OR a.object_path<>a.owner_id::text||'/'||a.id::text||'/original.'||
   CASE a.mime_type WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg'
    WHEN 'image/webp' THEN 'webp' WHEN 'video/mp4' THEN 'mp4' WHEN 'audio/mp4' THEN 'm4a' END
  OR NOT EXISTS(SELECT 1 FROM storage.buckets b WHERE b.id=a.bucket_id AND b.public IS FALSE
    AND a.mime_type=ANY(coalesce(b.allowed_mime_types,ARRAY[]::text[])))
  OR NOT ((a.bucket_id='profile-media' AND a.purpose IN ('avatar','cover')
      AND a.mime_type IN ('image/png','image/jpeg','image/webp'))
    OR (a.bucket_id='social-media' AND a.purpose IN ('post','story')
      AND a.mime_type IN ('image/png','image/jpeg','image/webp','video/mp4'))
    OR (a.bucket_id='chat-media' AND a.purpose='message'
      AND a.mime_type IN ('image/png','image/jpeg','image/webp','video/mp4','audio/mp4'))) THEN
  RAISE EXCEPTION 'cleanup_asset_contract_requires_manual_review';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM private.media_cleanup_attempt_audit x
   WHERE x.event_id=e.id AND x.attempt=p_attempt AND x.storage_outcome<>'not_observed') THEN
  RAISE EXCEPTION 'cleanup_storage_observation_required';
 END IF;
 IF p_storage_state='absent' THEN
  IF EXISTS(SELECT 1 FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path) THEN
   RAISE EXCEPTION 'storage_catalog_still_contains_object';
  END IF;
  UPDATE private.media_cleanup_attempt_audit SET storage_outcome='absent',observation_source='owner_head',
   observed_at=statement_timestamp(),error_code=NULL WHERE event_id=e.id AND attempt=p_attempt;
  IF private.meeto_cleanup_asset_blocked(a.id) THEN
   receipt:=jsonb_build_object('event_id',e.id,'asset_id',a.id,'action','reconciliation_blocked',
    'storage_outcome','absent','media_state','pending_delete');
   INSERT INTO private.media_cleanup_recovery_log(source_event_id,attempt,request_id,action,storage_outcome,reason,receipt)
   VALUES(e.id,p_attempt,p_request_id,'reconciliation_blocked','absent',btrim(p_reason),receipt);
   RETURN receipt;
  END IF;
  UPDATE public.media_assets SET state='deleted',metadata='{}'::jsonb
   WHERE id=a.id AND state='pending_delete';
  IF NOT FOUND THEN RAISE EXCEPTION 'media_state_changed_during_reconciliation'; END IF;
  UPDATE public.event_outbox SET state='done',lease_until=NULL,processed_at=statement_timestamp(),
   payload=payload||jsonb_build_object('recovery','owner_confirmed_storage_absent')
   WHERE id=e.id AND state='dead' AND attempts=p_attempt;
  IF NOT FOUND THEN RAISE EXCEPTION 'cleanup_event_changed_during_reconciliation'; END IF;
  receipt:=jsonb_build_object('event_id',e.id,'asset_id',a.id,'action','storage_absent_confirmed',
    'storage_outcome','absent','media_state','deleted','storage_delete_asserted',false);
  INSERT INTO private.media_cleanup_recovery_log(source_event_id,attempt,request_id,action,storage_outcome,reason,receipt)
  VALUES(e.id,p_attempt,p_request_id,'storage_absent_confirmed','absent',btrim(p_reason),receipt);
  RETURN receipt;
 ELSIF p_storage_state='present' THEN
  SELECT o.id,o.owner_id,o.metadata->>'mimetype' INTO object_id,object_owner,object_mime
   FROM storage.objects o WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path;
  IF object_id IS NULL OR object_owner IS DISTINCT FROM a.owner_id::text
   OR object_mime IS DISTINCT FROM a.mime_type THEN RAISE EXCEPTION 'storage_object_owner_or_mime_mismatch'; END IF;
  UPDATE private.media_cleanup_attempt_audit SET storage_outcome='present',observation_source='owner_head',
   observed_at=statement_timestamp(),error_code='storage_present' WHERE event_id=e.id AND attempt=p_attempt;
  IF private.meeto_cleanup_asset_blocked(a.id) THEN
   receipt:=jsonb_build_object('event_id',e.id,'asset_id',a.id,'action','reconciliation_blocked',
    'storage_outcome','present','media_state','pending_delete');
   INSERT INTO private.media_cleanup_recovery_log(source_event_id,attempt,request_id,action,storage_outcome,reason,receipt)
   VALUES(e.id,p_attempt,p_request_id,'reconciliation_blocked','present',btrim(p_reason),receipt);
   RETURN receipt;
  END IF;
  INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id,payload,state,attempts,available_at)
  VALUES('media-retire-recovery:'||e.id::text||':'||p_request_id::text,'media.retire','media_asset',a.id,
    jsonb_build_object('recovery_of',e.id,'operator_verified_object_id',object_id),'pending',0,statement_timestamp())
  RETURNING id INTO replacement;
  receipt:=jsonb_build_object('event_id',e.id,'replacement_event_id',replacement,'asset_id',a.id,
    'action','retry_scheduled','storage_outcome','present','original_attempts_preserved',e.attempts);
  INSERT INTO private.media_cleanup_recovery_log(source_event_id,attempt,request_id,action,storage_outcome,
    replacement_event_id,reason,receipt)
  VALUES(e.id,p_attempt,p_request_id,'retry_scheduled','present',replacement,btrim(p_reason),receipt);
  RETURN receipt;
 ELSE
  UPDATE private.media_cleanup_attempt_audit SET storage_outcome='unknown',observation_source='owner_head',
   observed_at=statement_timestamp(),error_code='storage_unknown' WHERE event_id=e.id AND attempt=p_attempt;
  receipt:=jsonb_build_object('event_id',e.id,'asset_id',a.id,'action','unknown_retained',
    'storage_outcome','unknown','media_state','pending_delete');
  INSERT INTO private.media_cleanup_recovery_log(source_event_id,attempt,request_id,action,storage_outcome,reason,receipt)
  VALUES(e.id,p_attempt,p_request_id,'unknown_retained','unknown',btrim(p_reason),receipt);
  RETURN receipt;
 END IF;
END $$;

CREATE OR REPLACE FUNCTION public.meeto_worker_claim_cleanup(p_batch integer DEFAULT 5) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 RETURN private.meeto_claim_media_cleanup(p_batch);
END $$;
CREATE OR REPLACE FUNCTION public.meeto_worker_finish_cleanup(
 p_event_id uuid,p_attempt integer,p_success boolean,p_error_code text DEFAULT NULL
) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 RETURN private.meeto_finish_media_cleanup(p_event_id,p_attempt,p_success,p_error_code);
END $$;
CREATE FUNCTION private.meeto_worker_record_cleanup_observation(
 p_event_id uuid,p_attempt integer,p_outcome text,p_delete_http_status integer
) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF auth.role()<>'service_role' THEN RAISE EXCEPTION 'worker_only' USING ERRCODE='42501'; END IF;
 RETURN private.meeto_record_media_cleanup_observation($1,$2,$3,$4);
END $$;
CREATE FUNCTION public.meeto_worker_record_cleanup_observation(
 p_event_id uuid,p_attempt integer,p_outcome text,p_delete_http_status integer
) RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path='' AS $$
 SELECT private.meeto_worker_record_cleanup_observation($1,$2,$3,$4)
$$;

REVOKE ALL ON FUNCTION private.meeto_cleanup_asset_blocked(uuid),
 private.meeto_record_media_cleanup_observation(uuid,integer,text,integer),
 private.meeto_worker_record_cleanup_observation(uuid,integer,text,integer),
 private.meeto_reap_exhausted_cleanup(uuid[],uuid,integer,text),
 private.meeto_reconcile_cleanup_event(uuid,integer,uuid,text,text)
 FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.meeto_worker_record_cleanup_observation(uuid,integer,text,integer)
 FROM PUBLIC,anon,authenticated;
GRANT EXECUTE ON FUNCTION public.meeto_worker_record_cleanup_observation(uuid,integer,text,integer)
 TO service_role;

DO $$ BEGIN
 IF has_function_privilege('anon','public.meeto_worker_claim_cleanup(integer)','EXECUTE')
 OR has_function_privilege('authenticated','public.meeto_worker_claim_cleanup(integer)','EXECUTE')
 OR NOT has_function_privilege('service_role','public.meeto_worker_claim_cleanup(integer)','EXECUTE')
 OR has_function_privilege('anon','public.meeto_worker_finish_cleanup(uuid,integer,boolean,text)','EXECUTE')
 OR has_function_privilege('authenticated','public.meeto_worker_finish_cleanup(uuid,integer,boolean,text)','EXECUTE')
 OR NOT has_function_privilege('service_role','public.meeto_worker_finish_cleanup(uuid,integer,boolean,text)','EXECUTE')
 OR has_function_privilege('anon','public.meeto_worker_record_cleanup_observation(uuid,integer,text,integer)','EXECUTE')
 OR has_function_privilege('authenticated','public.meeto_worker_record_cleanup_observation(uuid,integer,text,integer)','EXECUTE')
 OR NOT has_function_privilege('service_role','public.meeto_worker_record_cleanup_observation(uuid,integer,text,integer)','EXECUTE') THEN
  RAISE EXCEPTION 'Worker cleanup RPC effective EXECUTE must remain service_role-only';
 END IF;
 IF has_function_privilege('anon','private.meeto_reap_exhausted_cleanup(uuid[],uuid,integer,text)','EXECUTE')
 OR has_function_privilege('authenticated','private.meeto_reap_exhausted_cleanup(uuid[],uuid,integer,text)','EXECUTE')
 OR has_function_privilege('service_role','private.meeto_reap_exhausted_cleanup(uuid[],uuid,integer,text)','EXECUTE')
 OR has_function_privilege('anon','private.meeto_reconcile_cleanup_event(uuid,integer,uuid,text,text)','EXECUTE')
 OR has_function_privilege('authenticated','private.meeto_reconcile_cleanup_event(uuid,integer,uuid,text,text)','EXECUTE')
 OR has_function_privilege('service_role','private.meeto_reconcile_cleanup_event(uuid,integer,uuid,text,text)','EXECUTE') THEN
  RAISE EXCEPTION 'Owner recovery functions must not be callable by API roles';
 END IF;
 IF has_table_privilege('anon','private.media_cleanup_attempt_audit','SELECT,INSERT,UPDATE,DELETE')
 OR has_table_privilege('authenticated','private.media_cleanup_attempt_audit','SELECT,INSERT,UPDATE,DELETE')
 OR has_table_privilege('service_role','private.media_cleanup_attempt_audit','SELECT,INSERT,UPDATE,DELETE')
 OR has_table_privilege('anon','private.media_cleanup_recovery_log','SELECT,INSERT,UPDATE,DELETE')
 OR has_table_privilege('authenticated','private.media_cleanup_recovery_log','SELECT,INSERT,UPDATE,DELETE')
 OR has_table_privilege('service_role','private.media_cleanup_recovery_log','SELECT,INSERT,UPDATE,DELETE')
 OR has_any_column_privilege('anon','private.media_cleanup_attempt_audit','SELECT,INSERT,UPDATE,REFERENCES')
 OR has_any_column_privilege('authenticated','private.media_cleanup_attempt_audit','SELECT,INSERT,UPDATE,REFERENCES')
 OR has_any_column_privilege('service_role','private.media_cleanup_attempt_audit','SELECT,INSERT,UPDATE,REFERENCES')
 OR has_any_column_privilege('anon','private.media_cleanup_recovery_log','SELECT,INSERT,UPDATE,REFERENCES')
 OR has_any_column_privilege('authenticated','private.media_cleanup_recovery_log','SELECT,INSERT,UPDATE,REFERENCES')
 OR has_any_column_privilege('service_role','private.media_cleanup_recovery_log','SELECT,INSERT,UPDATE,REFERENCES') THEN
  RAISE EXCEPTION 'Cleanup audit tables must remain private';
 END IF;
END $$;

NOTIFY pgrst,'reload schema';
COMMIT;
