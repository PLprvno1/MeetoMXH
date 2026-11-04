-- Task 32 hotfix: admin user search is read-only; do not take the B-writer lock.
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.profiles') IS NULL
     OR to_regclass('public.account_status') IS NULL
     OR to_regclass('public.system_roles') IS NULL
     OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.has_role(text)') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.admin_search_users(text,timestamptz,uuid,integer)') IS NULL
     OR to_regprocedure('public.admin_search_users(text,timestamptz,uuid,integer)') IS NULL THEN
    RAISE EXCEPTION 'Admin search hotfix prerequisites missing; verify migration 035 and authorization helpers';
  END IF;
END $$;

-- The previous STABLE function initialized an unused actor via b_actor(),
-- which takes pg_advisory_xact_lock() and fails in a read-only function context.
-- Keep the same signature, result shape, pagination, and server-side role gate.
CREATE OR REPLACE FUNCTION private.admin_search_users(
  p_query text,p_after_time timestamptz,p_after_id uuid,p_limit integer
) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; result jsonb;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='system_admin_required';
  END IF;
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_query IS NULL OR char_length(btrim(p_query))>100 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_admin_search';
  END IF;
  q:=lower(btrim(p_query));
  SELECT coalesce(jsonb_agg(jsonb_build_object(
      'user_id',x.id,'display_name',x.display_name,'avatar_asset_id',x.avatar_asset_id,
      'created_at',x.created_at,'account_state',coalesce(x.account_state,'active'),
      'suspended_until',x.suspended_until,'is_system_admin',x.is_system_admin
    ) ORDER BY x.created_at DESC,x.id DESC),'[]'::jsonb)
    INTO result
  FROM (
    SELECT p.id,p.display_name,p.avatar_asset_id,p.created_at,s.state AS account_state,s.suspended_until,
      EXISTS(SELECT 1 FROM public.system_roles r
        WHERE r.user_id=p.id AND r.role='system_admin') AS is_system_admin
    FROM public.profiles p
    LEFT JOIN public.account_status s ON s.user_id=p.id
    WHERE (q='' OR lower(p.display_name) LIKE '%'||q||'%' OR p.id::text=q)
      AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
    ORDER BY p.created_at DESC,p.id DESC
    LIMIT p_limit
  ) x;
  RETURN result;
END $$;

NOTIFY pgrst,'reload schema';
COMMIT;
