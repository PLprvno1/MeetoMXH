-- Task 20 / migration 026: server-side people, post, group search and private history.
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.profiles') IS NULL OR to_regclass('public.user_settings') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.friendships') IS NULL
     OR to_regclass('public.friend_requests') IS NULL OR to_regclass('public.user_follows') IS NULL
     OR to_regclass('public.posts') IS NULL OR to_regclass('public.groups') IS NULL
     OR to_regclass('public.group_members') IS NULL OR to_regclass('public.post_media') IS NULL
     OR to_regclass('public.media_assets') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.are_friends(uuid)') IS NULL
     OR to_regprocedure('private.can_discover_group(uuid)') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('private.group_card(uuid)') IS NULL
     OR to_regprocedure('private.require_identity()') IS NULL
     OR to_regprocedure('private.update_privacy_settings(text,text,text)') IS NULL THEN
    RAISE EXCEPTION 'Advanced Search prerequisites missing; verify migrations 001-025 and deployed schema';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.profiles'::regclass
      AND attname='public_avatar_preset' AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Advanced Search requires the public avatar preset field from migration 020';
  END IF;
END $$;

-- Prefix indexes use existing profile and group privacy gates; posts use PostgreSQL's
-- built-in simple text parser (accent-sensitive; no external tokenizer is assumed).
CREATE INDEX IF NOT EXISTS posts_search_fts_idx
  ON public.posts USING gin (to_tsvector('simple',coalesce(body,'')))
  WHERE state='published' AND body<>'';
CREATE INDEX IF NOT EXISTS groups_search_name_idx
  ON public.groups (lower(name) text_pattern_ops) WHERE state='active';
CREATE INDEX IF NOT EXISTS friend_requests_search_out_idx
  ON public.friend_requests(requester_id,recipient_id) WHERE state='pending';
CREATE INDEX IF NOT EXISTS friend_requests_search_in_idx
  ON public.friend_requests(recipient_id,requester_id) WHERE state='pending';

CREATE TABLE public.search_history (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  query text NOT NULL CHECK (char_length(query) BETWEEN 2 AND 100),
  normalized_query text NOT NULL CHECK (char_length(normalized_query) BETWEEN 2 AND 100),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(user_id,normalized_query)
);
CREATE INDEX search_history_recent_idx ON public.search_history(user_id,created_at DESC,id DESC);
ALTER TABLE public.search_history ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.search_history FROM PUBLIC,anon,authenticated,service_role;
CREATE POLICY search_history_recipient_select ON public.search_history FOR SELECT TO authenticated
  USING (user_id=(select auth.uid()) AND private.is_active());

CREATE FUNCTION private.search_advanced_people(p_query text,p_after_name text,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; escaped text;
BEGIN
  PERFORM private.b_active();
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 2 AND 100
     OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
     OR (p_after_name IS NULL)<>(p_after_id IS NULL)
     OR (p_after_name IS NOT NULL AND char_length(p_after_name)>100) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search';
  END IF;
  q:=lower(btrim(p_query));
  escaped:=replace(replace(replace(q,'\','\\'),'%','\%'),'_','\_');
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object(
      'profile_id',x.id,'display_name',x.display_name,'avatar_asset_id',x.avatar_asset_id,
      'avatar_preset',x.public_avatar_preset,'cursor_name',lower(x.display_name),
      'friend_status',CASE WHEN private.are_friends(x.id) THEN 'friend'
        WHEN EXISTS(SELECT 1 FROM public.friend_requests fr WHERE fr.requester_id=auth.uid() AND fr.recipient_id=x.id AND fr.state='pending') THEN 'outgoing'
        WHEN EXISTS(SELECT 1 FROM public.friend_requests fr WHERE fr.requester_id=x.id AND fr.recipient_id=auth.uid() AND fr.state='pending') THEN 'incoming'
        ELSE 'none' END,
      'following',EXISTS(SELECT 1 FROM public.user_follows uf WHERE uf.follower_id=auth.uid() AND uf.followed_id=x.id))
      ORDER BY lower(x.display_name),x.id)
    FROM (SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset
      FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id AND s.state='active'
      WHERE p.id<>auth.uid() AND p.onboarding_completed AND private.can_read_profile(p.id)
        AND lower(p.display_name) LIKE escaped||'%' ESCAPE '\'
        AND (p_after_name IS NULL OR (lower(p.display_name),p.id)>(p_after_name,p_after_id))
      ORDER BY lower(p.display_name),p.id LIMIT p_limit) x),'[]'::jsonb);
END $$;

CREATE FUNCTION private.search_advanced_posts(p_query text,p_media_only boolean,p_since timestamptz,p_until timestamptz,
  p_sort text,p_after_rank real,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); q text; terms tsquery;
BEGIN
  PERFORM private.b_active();
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 2 AND 100 OR p_media_only IS NULL
     OR p_sort IS NULL OR p_sort NOT IN ('newest','relevance') OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
     OR (p_since IS NOT NULL AND p_until IS NOT NULL AND p_since>=p_until)
     OR (p_sort='newest' AND p_after_rank IS NOT NULL)
     OR (p_sort='relevance' AND ((p_after_rank IS NULL)<>(p_after_time IS NULL) OR (p_after_time IS NULL)<>(p_after_id IS NULL))) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post_search';
  END IF;
  PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  q:=btrim(p_query);
  terms:=websearch_to_tsquery('simple',q);
  IF numnode(terms)=0 THEN RETURN '[]'::jsonb; END IF;
  IF p_sort='relevance' AND p_after_rank IS NULL AND p_after_time IS NOT NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search_cursor';
  END IF;
  RETURN coalesce((SELECT jsonb_agg(private.post_json(z.id)||jsonb_build_object('search_rank',z.score)
      ORDER BY CASE WHEN p_sort='relevance' THEN z.score END DESC,z.created_at DESC,z.id DESC)
    FROM (SELECT p.id,p.created_at,ts_rank_cd(to_tsvector('simple',coalesce(p.body,'')),terms)::real AS score
      FROM public.posts p
      WHERE p.state='published' AND p.body<>''
        AND to_tsvector('simple',coalesce(p.body,'')) @@ terms
        AND (NOT p_media_only OR EXISTS(SELECT 1 FROM public.post_media pm
          JOIN public.media_assets a ON a.id=pm.asset_id
          WHERE pm.post_id=p.id AND private.can_read_media(a.id)))
        AND (p_since IS NULL OR p.created_at>=p_since) AND (p_until IS NULL OR p.created_at<p_until)
        -- Apply effective read permission before cursor and LIMIT.
        AND private.can_read_post(p.id)
        AND (CASE WHEN p_sort='relevance'
          THEN p_after_rank IS NULL OR (ts_rank_cd(to_tsvector('simple',coalesce(p.body,'')),terms)::real,p.created_at,p.id)<(p_after_rank,p_after_time,p_after_id)
          ELSE p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id) END)
      ORDER BY CASE WHEN p_sort='relevance' THEN ts_rank_cd(to_tsvector('simple',coalesce(p.body,'')),terms)::real END DESC,
        p.created_at DESC,p.id DESC LIMIT p_limit) z),'[]'::jsonb);
END $$;

CREATE FUNCTION private.search_advanced_groups(p_query text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; escaped text;
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 2 AND 100 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_search';
  END IF;
  q:=lower(btrim(p_query)); escaped:=replace(replace(replace(q,'\','\\'),'%','\%'),'_','\_');
  RETURN coalesce((SELECT jsonb_agg(private.group_card(g.id) ORDER BY g.created_at DESC,g.id DESC)
    FROM (SELECT x.id,x.created_at FROM public.groups x
      WHERE x.state='active' AND lower(x.name) LIKE escaped||'%' ESCAPE '\'
        AND private.can_discover_group(x.id)
        AND (p_after_time IS NULL OR (x.created_at,x.id)<(p_after_time,p_after_id))
      ORDER BY x.created_at DESC,x.id DESC LIMIT p_limit) g),'[]'::jsonb);
END $$;

CREATE FUNCTION private.get_search_history(p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active();
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 20 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_history_limit'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',h.id,'query',h.query,'created_at',h.created_at)
      ORDER BY h.created_at DESC,h.id DESC)
    FROM (SELECT id,query,created_at FROM public.search_history WHERE user_id=actor
      ORDER BY created_at DESC,id DESC LIMIT p_limit) h),'[]'::jsonb);
END $$;

CREATE FUNCTION private.save_search_query(p_query text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); q text; norm text; item public.search_history;
BEGIN
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 2 AND 100 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search_query'; END IF;
  q:=regexp_replace(btrim(p_query),'\s+',' ','g'); norm:=lower(q);
  PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':search-history',0));
  INSERT INTO public.search_history(user_id,query,normalized_query,created_at)
    VALUES(actor,q,norm,statement_timestamp())
    ON CONFLICT(user_id,normalized_query) DO UPDATE SET query=excluded.query,created_at=excluded.created_at
    RETURNING * INTO item;
  DELETE FROM public.search_history h WHERE h.user_id=actor AND h.id IN (
    SELECT id FROM public.search_history WHERE user_id=actor ORDER BY created_at DESC,id DESC OFFSET 20);
  RETURN jsonb_build_object('id',item.id,'query',item.query,'created_at',item.created_at);
END $$;

CREATE FUNCTION private.delete_search_history_item(p_item_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  IF p_item_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_history_item'; END IF;
  DELETE FROM public.search_history WHERE id=p_item_id AND user_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=0 THEN RAISE SQLSTATE 'PT404' USING MESSAGE='history_item_unavailable'; END IF;
  RETURN jsonb_build_object('deleted',true);
END $$;

CREATE FUNCTION private.clear_search_history() RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  DELETE FROM public.search_history WHERE user_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  RETURN jsonb_build_object('deleted',changed);
END $$;

CREATE FUNCTION public.search_advanced_people(p_query text,p_after_name text DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_advanced_people($1,$2,$3,$4) $$;
CREATE FUNCTION public.search_advanced_posts(p_query text,p_media_only boolean DEFAULT false,p_since timestamptz DEFAULT NULL,p_until timestamptz DEFAULT NULL,
  p_sort text DEFAULT 'relevance',p_after_rank real DEFAULT NULL,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_advanced_posts($1,$2,$3,$4,$5,$6,$7,$8,$9) $$;
CREATE FUNCTION public.search_advanced_groups(p_query text,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_advanced_groups($1,$2,$3,$4) $$;
CREATE FUNCTION public.get_search_history(p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_search_history($1) $$;
CREATE FUNCTION public.save_search_query(p_query text)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.save_search_query($1) $$;
CREATE FUNCTION public.delete_search_history_item(p_item_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.delete_search_history_item($1) $$;
CREATE FUNCTION public.clear_search_history()
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.clear_search_history() $$;

REVOKE ALL ON FUNCTION private.search_advanced_people(text,text,uuid,integer),
  private.search_advanced_posts(text,boolean,timestamptz,timestamptz,text,real,timestamptz,uuid,integer),
  private.search_advanced_groups(text,timestamptz,uuid,integer),private.get_search_history(integer),
  private.save_search_query(text),private.delete_search_history_item(uuid),private.clear_search_history()
  FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.search_advanced_people(text,text,uuid,integer),
  private.search_advanced_posts(text,boolean,timestamptz,timestamptz,text,real,timestamptz,uuid,integer),
  private.search_advanced_groups(text,timestamptz,uuid,integer),private.get_search_history(integer),
  private.save_search_query(text),private.delete_search_history_item(uuid),private.clear_search_history()
  TO authenticated;
REVOKE ALL ON FUNCTION public.search_advanced_people(text,text,uuid,integer),
  public.search_advanced_posts(text,boolean,timestamptz,timestamptz,text,real,timestamptz,uuid,integer),
  public.search_advanced_groups(text,timestamptz,uuid,integer),public.get_search_history(integer),
  public.save_search_query(text),public.delete_search_history_item(uuid),public.clear_search_history()
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.search_advanced_people(text,text,uuid,integer),
  public.search_advanced_posts(text,boolean,timestamptz,timestamptz,text,real,timestamptz,uuid,integer),
  public.search_advanced_groups(text,timestamptz,uuid,integer),public.get_search_history(integer),
  public.save_search_query(text),public.delete_search_history_item(uuid),public.clear_search_history()
  TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
