-- Task 49. Forward only; owner deploys. No content, ACL or bucket changes.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.search_advanced_posts(text,boolean,timestamptz,timestamptz,text,real,timestamptz,uuid,integer)') IS NULL
 OR to_regprocedure('public.search_advanced_posts(text,boolean,timestamptz,timestamptz,text,real,timestamptz,uuid,integer)') IS NULL
 OR to_regprocedure('private.get_explore_posts(timestamptz,uuid,integer)') IS NULL
 OR to_regprocedure('private.reel_card(uuid)') IS NULL
 OR to_regprocedure('private.b_active()') IS NULL THEN
  RAISE EXCEPTION 'Deploy advanced search, Task 36 and Reels prerequisites first'; END IF;
END $$;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='pg_trgm' AND n.nspname='extensions') THEN
  RAISE EXCEPTION 'Inventory pg_trgm schema; expected extensions, no extension relocation is performed'; END IF;
END $$;
CREATE FUNCTION private.search_fold(p_text text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path='' AS $$
 SELECT translate(lower(coalesce(p_text,'')),
 'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ',
 'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd')
$$;
CREATE INDEX posts_search_substring_idx ON public.posts USING gin(private.search_fold(body) extensions.gin_trgm_ops) WHERE state='published';
CREATE INDEX profiles_search_display_substring_idx ON public.profiles USING gin(private.search_fold(display_name) extensions.gin_trgm_ops);
CREATE INDEX profiles_search_username_substring_idx ON public.profiles USING gin(private.search_fold(username) extensions.gin_trgm_ops);

-- Shared projection: expose cover only under the same media ACL as playback.
CREATE FUNCTION private.search_post_card(p_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.post_json(p.id)||jsonb_build_object('is_reel',p.is_reel,
  'reel_cover_asset_id',CASE WHEN p.reel_cover_asset_id IS NOT NULL AND private.can_read_media(p.reel_cover_asset_id)
   AND EXISTS(SELECT 1 FROM public.media_assets a WHERE a.id=p.reel_cover_asset_id AND a.state='ready'
    AND a.mime_type IN ('image/png','image/jpeg','image/webp')) THEN p.reel_cover_asset_id END,
  'reel_duration_ms',p.reel_duration_ms)
 FROM public.posts p WHERE p.id=p_id AND p.state='published' AND private.can_read_post(p.id)
$$;
CREATE OR REPLACE FUNCTION private.get_explore_posts(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 RETURN coalesce((SELECT jsonb_agg(private.search_post_card(q.id) ORDER BY q.created_at DESC,q.id DESC) FROM (
  SELECT p.id,p.created_at FROM public.posts p WHERE p.state='published' AND private.can_read_post(p.id)
   AND EXISTS(SELECT 1 FROM public.post_media m WHERE m.post_id=p.id AND private.can_read_media(m.asset_id))
   AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
  ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit)q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.search_advanced_posts(p_query text,p_media_only boolean,p_since timestamptz,p_until timestamptz,
 p_sort text,p_after_rank real,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; escaped text;
BEGIN
 -- Pure read guard, never b_actor/FOR UPDATE inside a STABLE PostgREST RPC.
 PERFORM private.b_active();PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 2 AND 100 OR p_media_only IS NULL
 OR p_sort IS NULL OR p_sort NOT IN ('newest','relevance')
 OR (p_since IS NOT NULL AND p_until IS NOT NULL AND p_since>=p_until)
 OR (p_sort='newest' AND p_after_rank IS NOT NULL)
 OR (p_sort='relevance' AND ((p_after_rank IS NULL)<>(p_after_time IS NULL)
  OR (p_after_rank IS NOT NULL AND p_after_rank NOT BETWEEN 0 AND 2))) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post_search'; END IF;
 q:=private.search_fold(btrim(p_query));
 escaped:=replace(replace(replace(q,'\','\\'),'%','\%'),'_','\_');
 RETURN coalesce((SELECT jsonb_agg(private.search_post_card(page.id)||jsonb_build_object('search_rank',page.score)
  ORDER BY CASE WHEN p_sort='relevance' THEN page.score END DESC,page.created_at DESC,page.id DESC)
 FROM (SELECT z.* FROM (SELECT p.id,p.created_at,CASE WHEN private.search_fold(p.body)=q THEN 2::real
  WHEN private.search_fold(p.body) LIKE escaped||'%' ESCAPE '\' THEN 1::real ELSE 0::real END score
  FROM public.posts p WHERE p.state='published' AND private.can_read_post(p.id)
   AND private.search_fold(p.body) LIKE '%'||escaped||'%' ESCAPE '\'
   AND (p_since IS NULL OR p.created_at>=p_since) AND (p_until IS NULL OR p.created_at<p_until)
   AND (NOT p_media_only OR EXISTS(SELECT 1 FROM public.post_media m WHERE m.post_id=p.id AND private.can_read_media(m.asset_id)))) z
 WHERE p_after_time IS NULL OR (p_sort='newest' AND (z.created_at,z.id)<(p_after_time,p_after_id))
  OR (p_sort='relevance' AND (z.score,z.created_at,z.id)<(p_after_rank,p_after_time,p_after_id))
 ORDER BY CASE WHEN p_sort='relevance' THEN z.score END DESC,z.created_at DESC,z.id DESC LIMIT p_limit)page),'[]'::jsonb);
END $$;

CREATE FUNCTION public.search_reels(p_query text,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE escaped text; person text;
BEGIN
 PERFORM private.b_active();PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 2 AND 100 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_search'; END IF;
 escaped:=replace(replace(replace(private.search_fold(btrim(p_query)),'\','\\'),'%','\%'),'_','\_');
 person:=replace(replace(replace(private.search_fold(btrim(p_query,' @')),'\','\\'),'%','\%'),'_','\_');
 RETURN coalesce((SELECT jsonb_agg(private.reel_card(q.id) ORDER BY q.created_at DESC,q.id DESC) FROM (
  SELECT p.id,p.created_at FROM public.posts p JOIN public.profiles author ON author.id=p.author_id
  WHERE p.is_reel AND p.state='published' AND p.group_id IS NULL AND private.can_read_post(p.id)
   AND (private.search_fold(p.body) LIKE '%'||escaped||'%' ESCAPE '\'
    OR (char_length(person)>=2 AND private.can_read_profile(author.id) AND (private.search_fold(author.display_name) LIKE '%'||person||'%' ESCAPE '\'
     OR private.search_fold(author.username) LIKE '%'||person||'%' ESCAPE '\')))
   AND private.reel_card(p.id) IS NOT NULL
   AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
  ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit)q),'[]'::jsonb);
END $$;
REVOKE ALL ON FUNCTION private.search_fold(text),private.search_post_card(uuid) FROM PUBLIC,anon,authenticated,service_role;
-- Index expressions may be evaluated in invoker queries; fold is a pure text transform.
GRANT EXECUTE ON FUNCTION private.search_fold(text) TO authenticated;
REVOKE ALL ON FUNCTION public.search_reels(text,timestamptz,uuid,integer) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.search_reels(text,timestamptz,uuid,integer) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
