-- EXECUTION GUARD: only synthetic local MeetoAudit04 databases, never Supabase.
DO $local_only$
BEGIN
 IF current_database() NOT IN ('meeto_audit04','meeto_audit04_final')
   OR replace(current_setting('data_directory'),chr(92),'/') NOT LIKE '%/Temp/MeetoAudit04-%/data'
   OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
 THEN RAISE EXCEPTION 'LOCAL AUDIT ONLY: wrong database/cluster/host'; END IF;
END $local_only$;
BEGIN;
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(1)::text,true);
SELECT set_config('request.jwt.claims','{"is_anonymous":false}',true);
UPDATE public.user_settings SET notification_preferences='{"unexpected":{"nested":"not boolean"}}' WHERE user_id=audit.id(1);
RESET ROLE;
CREATE FUNCTION private.audit_unhardened_probe() RETURNS integer LANGUAGE sql AS 'SELECT 1';
UPDATE public.profiles SET avatar_asset_id=audit.id(603) WHERE id=audit.id(1);
SET ROLE authenticated;
SELECT set_config('request.jwt.claim.sub',audit.id(2)::text,true);
SELECT json_build_object(
 'unvalidated_preference_accepted',true,
 'recalled_media_readable_via_wrong_avatar_link',EXISTS(SELECT 1 FROM public.media_assets WHERE id=audit.id(603)),
 'future_function_public_execute',has_function_privilege('authenticated','private.audit_unhardened_probe()','EXECUTE')
);
RESET ROLE;
INSERT INTO public.stories(id,author_id,media_asset_id,client_request_id,created_at,expires_at,audience)
VALUES(audit.id(703),audit.id(1),audit.id(606),audit.id(8703),now(),now()+interval '24 hours','friends');
SELECT json_build_object(
 'unvalidated_preference_accepted',(SELECT notification_preferences ? 'unexpected' FROM public.user_settings WHERE user_id=audit.id(1)),
 'recalled_media_readable_via_wrong_avatar_link',private.can_read_media(audit.id(603)),
 'future_function_public_execute',has_function_privilege('authenticated','private.audit_unhardened_probe()','EXECUTE'),
 'same_story_asset_reused',(SELECT count(*)>1 FROM public.stories WHERE media_asset_id=audit.id(606))
);
-- Kết quả SQL cuối cần trả probes, mọi thay đổi probe rollback bằng runner khi disconnect.

