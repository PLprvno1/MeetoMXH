-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
-- Synthetic interfaces only; not Supabase services.
DO $$ BEGIN
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='anon') THEN CREATE ROLE anon NOLOGIN NOBYPASSRLS; END IF;
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='authenticated') THEN CREATE ROLE authenticated NOLOGIN NOBYPASSRLS; END IF;
END $$;
CREATE SCHEMA auth;
CREATE TABLE auth.users(id uuid PRIMARY KEY,email_confirmed_at timestamptz,phone_confirmed_at timestamptz);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
 SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid;
$$;
CREATE FUNCTION auth.jwt() RETURNS jsonb LANGUAGE sql STABLE AS $$
 SELECT coalesce(nullif(current_setting('request.jwt.claims',true),''),'{}')::jsonb;
$$;
CREATE SCHEMA storage;
CREATE TABLE storage.objects(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),bucket_id text NOT NULL,name text NOT NULL,owner_id text,UNIQUE(bucket_id,name));
CREATE FUNCTION storage.allow_any_operation(operations text[]) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT coalesce(replace(current_setting('audit.storage_operation',true),'storage.','')=ANY(operations),false);
$$;
CREATE SCHEMA realtime;
CREATE TABLE realtime.messages(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),extension text NOT NULL);
CREATE FUNCTION realtime.topic() RETURNS text LANGUAGE sql STABLE AS $$ SELECT current_setting('audit.realtime_topic',true); $$;
GRANT USAGE ON SCHEMA public,auth,storage,realtime TO anon,authenticated;
GRANT SELECT,INSERT ON storage.objects,realtime.messages TO anon,authenticated;
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY;
SELECT 'LOCAL_STUBS_ONLY',version(),current_database();