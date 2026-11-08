-- Expose existing owner requests to system administrators; no new destructive action.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.admin_users_page_v2(text,text,timestamptz,uuid,integer)') IS NULL THEN
  RAISE EXCEPTION 'Requires admin logic hardening migration 20261107001100';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc
  WHERE oid=to_regprocedure('private.admin_users_page_v2(text,text,timestamptz,uuid,integer)')
  AND (pg_get_userbyid(proowner)<>current_user OR prorettype<>'jsonb'::regtype OR NOT prosecdef)) THEN
  RAISE EXCEPTION 'Review admin_users_page_v2 owner/signature before replacement';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.admin_users_page_v2(p_query text,p_state text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE rows jsonb; more boolean;
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.has_role('system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required'; END IF;
 IF char_length(coalesce(p_query,''))>100 OR (p_state IS NOT NULL AND p_state NOT IN ('active','suspended','deleting','deletion_requested')) THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_user_filter'; END IF;
 WITH candidates AS MATERIALIZED (
 SELECT p.id,p.display_name,p.created_at,p.avatar_asset_id,a.state,a.suspended_until,a.deletion_requested_at FROM public.profiles p
 JOIN public.account_status a ON a.user_id=p.id WHERE (p_state IS NULL OR a.state=p_state OR (p_state='deletion_requested' AND a.state='active' AND a.deletion_requested_at IS NOT NULL))
 AND (nullif(btrim(p_query),'') IS NULL OR position(lower(btrim(p_query)) IN lower(p.display_name))>0 OR p.id::text=btrim(p_query))
 AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id)) ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit+1
 ), selected AS (SELECT * FROM candidates ORDER BY created_at DESC,id DESC LIMIT p_limit)
 SELECT coalesce((SELECT jsonb_agg(jsonb_build_object('id',u.id,'user_id',u.id,'display_name',u.display_name,
 'created_at',u.created_at,'avatar_asset_id',u.avatar_asset_id,'account_state',u.state,'suspended_until',u.suspended_until,
 'deletion_requested_at',u.deletion_requested_at,'roles',(SELECT coalesce(jsonb_agg(role),'[]'::jsonb) FROM public.system_roles WHERE user_id=u.id)) ORDER BY u.created_at DESC,u.id DESC)
 FROM selected u),'[]'::jsonb),(SELECT count(*)>p_limit FROM candidates) INTO rows,more;
 RETURN jsonb_build_object('contract_version',2,'items',rows,'has_more',more,'next_cursor',CASE WHEN more THEN
 jsonb_build_object('created_at',rows->-1->>'created_at','id',rows->-1->>'id') END);
END $$;

-- CREATE OR REPLACE retains existing owner and EXECUTE grants.
NOTIFY pgrst, 'reload schema';
COMMIT;