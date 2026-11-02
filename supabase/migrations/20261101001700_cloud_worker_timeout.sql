-- Allow the bounded, sequential Storage cleanup batch to finish within the
-- Edge Function wall-clock budget instead of pg_net's 2 second default.
CREATE OR REPLACE FUNCTION private.meeto_worker_enqueue_cleanup() RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE token text:=encode(extensions.gen_random_bytes(32),'hex'); run uuid:=gen_random_uuid(); request_id bigint;
BEGIN
 INSERT INTO private.worker_invocations(singleton,run_id,token_hash,issued_at,expires_at,consumed_at,completed_at,net_request_id,summary)
 VALUES(true,run,extensions.digest(token,'sha256'),statement_timestamp(),statement_timestamp()+interval '2 minutes',NULL,NULL,NULL,'{}'::jsonb)
 ON CONFLICT(singleton) DO UPDATE SET run_id=EXCLUDED.run_id,token_hash=EXCLUDED.token_hash,
  issued_at=EXCLUDED.issued_at,expires_at=EXCLUDED.expires_at,consumed_at=NULL,completed_at=NULL,net_request_id=NULL,summary='{}'::jsonb;
 SELECT net.http_post(
   url:='https://qjpohzpapiwdrxsionyr.supabase.co/functions/v1/meeto-background-cleanup',
   headers:=jsonb_build_object('Content-Type','application/json','apikey','sb_publishable_HDdsJa_VXk1Fj9mSCFe3RA_LIHLcVOq'),
   body:=jsonb_build_object('token',token),
   timeout_milliseconds:=120000
 ) INTO request_id;
 UPDATE private.worker_invocations SET net_request_id=request_id WHERE singleton AND run_id=run;
 RETURN request_id;
END $$;
