-- Dashboard cards use the reporter's details, just like the report list/detail.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.admin_dashboard_v2()') IS NULL
 OR to_regprocedure('private.admin_reports_page_v2(text,text,text,boolean,timestamptz,uuid,integer)') IS NULL THEN
  RAISE EXCEPTION 'Requires admin logic hardening migration 20261107001100';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc WHERE oid=to_regprocedure('private.admin_dashboard_v2()')
  AND (pg_get_userbyid(proowner)<>current_user OR prorettype<>'jsonb'::regtype OR NOT prosecdef)) THEN
  RAISE EXCEPTION 'Review admin_dashboard_v2 owner/signature before replacement';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.admin_dashboard_v2() RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.admin_dashboard() || jsonb_build_object(
  'contract_version',2,
  'recent_reports',private.admin_reports_page_v2(NULL,NULL,'',false,NULL,NULL,5)->'items',
  'recent_actions',private.admin_audit_page_v2(NULL,NULL,5)->'items')
$$;

-- Retain the system-admin gate in admin_dashboard, scoped report visibility,
-- the existing public wrapper, owner and EXECUTE ACLs. No row data is changed.
NOTIFY pgrst, 'reload schema';
COMMIT;
