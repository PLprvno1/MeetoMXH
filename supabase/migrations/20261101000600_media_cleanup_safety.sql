-- Fresh recheck after asset row lock closes attach-versus-orphan sweep race.
-- Future C/D/E media purposes remain inert and outside this worker's scope.
CREATE OR REPLACE FUNCTION private.schedule_media_cleanup() RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets; n integer:=0;
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 FOR a IN SELECT * FROM public.media_assets m WHERE purpose IN ('avatar','cover','post') AND
  ((state='pending_delete' AND coalesce(delete_after,now())<=now()) OR
   (state IN ('pending','ready','quarantined') AND created_at<now()-interval '24 hours'))
 ORDER BY id LIMIT 100 FOR UPDATE LOOP
  IF a.state='pending_delete' OR (
   NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=a.id)
   AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE a.id IN (p.avatar_asset_id,p.cover_asset_id))) THEN
   PERFORM private.retire_asset(a.id); n:=n+1;
  END IF;
 END LOOP;
 RETURN n;
END $$;
