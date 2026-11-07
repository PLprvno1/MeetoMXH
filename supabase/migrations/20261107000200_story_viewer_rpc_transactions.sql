-- Story viewer hotfix. Forward only; owner deploys after Task 42 (20261106000800).
-- These RPCs call c_actor(), whose identity/active/onboarding checks use row locks.
-- PostgREST runs STABLE RPCs in READ ONLY transactions even for POST; FOR SHARE
-- then fails with SQLSTATE 25006 before any Story/media result is returned.
-- Keep the exact auth guard, body, signatures and grants; allow its required locks.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('public.get_story(uuid)') IS NULL
 OR to_regprocedure('public.get_story_author_stories(uuid)') IS NULL
 OR to_regprocedure('public.get_story_states(uuid[])') IS NULL
 OR to_regprocedure('public.get_story_viewers_page(uuid,timestamptz,uuid,integer)') IS NULL THEN
  RAISE EXCEPTION 'Deploy Task 42 Story RPCs (20261106000800) first';
 END IF;
END $$;
ALTER FUNCTION public.get_story(uuid) VOLATILE;
ALTER FUNCTION public.get_story_author_stories(uuid) VOLATILE;
ALTER FUNCTION public.get_story_states(uuid[]) VOLATILE;
ALTER FUNCTION public.get_story_viewers_page(uuid,timestamptz,uuid,integer) VOLATILE;
-- No content/media mutation, new grants, RLS changes or capability-guard bypass.
NOTIFY pgrst,'reload schema';
COMMIT;
