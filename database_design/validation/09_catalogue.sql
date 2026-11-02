-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
SELECT json_build_object(
 'version',version(),
 'tables',(SELECT json_agg(row_to_json(t) ORDER BY t.name) FROM (
   SELECT c.relname AS name,c.relrowsecurity AS rls,
     (SELECT count(*) FROM pg_constraint k WHERE k.conrelid=c.oid AND k.contype='f') AS fks,
     (SELECT count(*) FROM pg_constraint k WHERE k.conrelid=c.oid AND k.contype='c') AS checks,
     (SELECT count(*) FROM pg_index x WHERE x.indrelid=c.oid) AS indexes,
     (SELECT count(*) FROM pg_policies p WHERE p.schemaname='public' AND p.tablename=c.relname AND p.cmd='SELECT') AS select_policies
   FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname='public' AND c.relkind='r'
 )t),
 'fk_index_review',(SELECT json_agg(row_to_json(f) ORDER BY f.table_name,f.constraint_name) FROM (
  SELECT k.conrelid::regclass::text AS table_name,k.conname AS constraint_name,pg_get_constraintdef(k.oid) AS definition
  FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE k.contype='f' AND n.nspname='public' AND NOT EXISTS(
   SELECT 1 FROM pg_index i WHERE i.indrelid=k.conrelid AND i.indisvalid AND i.indpred IS NULL
    AND ARRAY(SELECT a FROM unnest(i.indkey::smallint[]) WITH ORDINALITY z(a,ord) WHERE ord<=cardinality(k.conkey)) @> k.conkey
    AND cardinality(k.conkey)=cardinality(ARRAY(SELECT a FROM unnest(i.indkey::smallint[]) WITH ORDINALITY z(a,ord) WHERE ord<=cardinality(k.conkey)))
  )
 )f)
);

