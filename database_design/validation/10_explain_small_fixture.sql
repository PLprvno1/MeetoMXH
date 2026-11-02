-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(2)::text,false);
SELECT set_config('request.jwt.claims','{"is_anonymous":false}',false);
EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON)
SELECT id,seq,body FROM public.messages
WHERE conversation_id=audit.id(401) AND seq<100
ORDER BY seq DESC LIMIT 20;
