-- Task 53: ACL-filtered comment tree pages and one-reply previews.
-- Forward-only. Existing list_comments and write RPC contracts remain available.
BEGIN;

DO $$ BEGIN
 IF to_regclass('public.posts') IS NULL OR to_regclass('public.comments') IS NULL
  OR to_regclass('public.comment_reactions') IS NULL
  OR to_regclass('public.content_mentions') IS NULL OR to_regclass('public.profiles') IS NULL
  OR to_regclass('public.comments_thread_idx') IS NULL
  OR to_regprocedure('private.b_active()') IS NULL
  OR to_regprocedure('private.create_comment(uuid,uuid,text,uuid)') IS NULL
  OR to_regprocedure('private.can_read_post(uuid)') IS NULL
  OR to_regprocedure('private.can_read_comment(uuid)') IS NULL
  OR to_regprocedure('private.can_read_profile(uuid)') IS NULL THEN
  RAISE EXCEPTION 'Deploy comment, mention, profile and authorization prerequisites before Task 53';
 END IF;
 IF EXISTS (
   SELECT 1 FROM (VALUES
     ('comments','id'),('comments','post_id'),('comments','author_id'),('comments','parent_id'),
     ('comments','body'),('comments','state'),('comments','version'),('comments','created_at'),
     ('comments','updated_at'),('comment_reactions','comment_id'),('comment_reactions','user_id'),
     ('content_mentions','content_type'),('content_mentions','content_id'),('content_mentions','mentioned_user_id'),
     ('content_mentions','display_label'),('content_mentions','span_start_utf16'),('content_mentions','span_end_utf16'),
     ('profiles','id'),('profiles','display_name'),('profiles','avatar_asset_id'),('profiles','public_avatar_preset')
   ) AS needed(table_name,column_name)
   WHERE NOT EXISTS(SELECT 1 FROM information_schema.columns c
     WHERE c.table_schema='public' AND c.table_name=needed.table_name AND c.column_name=needed.column_name)
 ) THEN RAISE EXCEPTION 'Comment tree columns do not match the Task 53 contract'; END IF;
END $$;

-- A tombstone remains readable for thread traversal, but cannot be a new
-- direct reply target. Lock the direct parent so a concurrent delete cannot
-- race the insert; create_comment continues to validate actor/Post/depth and
-- preserve its idempotency check before reaching this trigger.
CREATE FUNCTION private.require_published_comment_parent() RETURNS trigger
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path='' AS $$
DECLARE locked_parent uuid;
BEGIN
 IF NEW.parent_id IS NULL THEN RETURN NEW; END IF;
 SELECT p.id INTO locked_parent
 FROM public.comments p
 WHERE p.id=NEW.parent_id AND p.post_id=NEW.post_id AND p.state='published'
   AND private.can_read_comment(p.id)
 FOR SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='parent_unavailable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER comments_require_published_parent
 BEFORE INSERT ON public.comments FOR EACH ROW
 EXECUTE FUNCTION private.require_published_comment_parent();

-- Resolve the UI root and displayed depth from the verified parent chain.
-- parent_depth remains the physical depth; hidden/deleted ancestors do not
-- become UI roots and do not contribute to the visible indentation.
CREATE FUNCTION private.comment_tree_parent_position(p_post_id uuid,p_parent_id uuid)
RETURNS TABLE(root_id uuid,parent_depth integer,display_depth integer)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 WITH RECURSIVE parent_chain(id,parent_id,state,depth,path) AS (
   SELECT c.id,c.parent_id,c.state,0,ARRAY[c.id]::uuid[]
   FROM public.comments c WHERE p_parent_id IS NOT NULL AND c.id=p_parent_id AND c.post_id=p_post_id
   UNION ALL
   SELECT a.id,a.parent_id,a.state,pc.depth+1,pc.path||a.id
   FROM parent_chain pc JOIN public.comments a ON a.id=pc.parent_id AND a.post_id=p_post_id
   WHERE pc.depth<8 AND NOT a.id=ANY(pc.path)
 )
 SELECT
   (SELECT v.id FROM parent_chain v
    WHERE v.state='published' AND private.can_read_comment(v.id)
    ORDER BY v.depth DESC LIMIT 1),
   (max(depth) FILTER (WHERE parent_id IS NULL))::integer,
   ((count(*) FILTER (WHERE state='published' AND private.can_read_comment(id)))-1)::integer
 FROM parent_chain
 HAVING (max(depth) FILTER (WHERE parent_id IS NULL)) IS NOT NULL
   AND (count(*) FILTER (WHERE state='published' AND private.can_read_comment(id)))>0
$$;

-- Return published comments which should be presented as direct children of
-- p_parent_id. Hidden/deleted ancestors are traversed without returning their
-- content, so their readable descendants remain in the visible tree.
CREATE FUNCTION private.comment_tree_visible_children(p_post_id uuid,p_parent_id uuid)
RETURNS TABLE(comment_id uuid,root_id uuid,depth integer,created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 WITH RECURSIVE parent_position AS (
   SELECT * FROM private.comment_tree_parent_position(p_post_id,p_parent_id)
 ), walk(id,parent_id,state,created_at,root_id,depth,path) AS (
   SELECT c.id,c.parent_id,c.state,c.created_at,
     CASE WHEN p_parent_id IS NULL THEN c.id ELSE pp.root_id END,
     CASE WHEN p_parent_id IS NULL THEN 0 ELSE pp.parent_depth+1 END,
     ARRAY[c.id]::uuid[]
   FROM public.comments c LEFT JOIN parent_position pp ON p_parent_id IS NOT NULL
   WHERE c.post_id=p_post_id AND c.parent_id IS NOT DISTINCT FROM p_parent_id
     AND (p_parent_id IS NULL OR pp.root_id IS NOT NULL)
   UNION ALL
   SELECT child.id,child.parent_id,child.state,child.created_at,w.root_id,w.depth+1,w.path||child.id
   FROM walk w JOIN public.comments child ON child.post_id=p_post_id AND child.parent_id=w.id
   WHERE (w.state<>'published' OR NOT private.can_read_comment(w.id))
     AND w.depth<8 AND NOT child.id=ANY(w.path)
 )
 SELECT w.id,CASE WHEN p_parent_id IS NULL THEN w.id ELSE w.root_id END,w.depth,w.created_at
 FROM walk w
 WHERE w.state='published' AND private.can_read_comment(w.id)
$$;

-- Traverse only inaccessible/tombstoned branches when paging. Ordinary visible
-- roots/replies can use the comments_thread_idx cursor directly.
CREATE FUNCTION private.comment_tree_hidden_children(p_post_id uuid,p_parent_id uuid)
RETURNS TABLE(comment_id uuid,root_id uuid,depth integer,created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 WITH RECURSIVE parent_position AS (
   SELECT * FROM private.comment_tree_parent_position(p_post_id,p_parent_id)
 ), walk(id,parent_id,state,created_at,root_id,depth,path) AS (
   SELECT c.id,c.parent_id,c.state,c.created_at,
     CASE WHEN p_parent_id IS NULL THEN c.id ELSE pp.root_id END,
     CASE WHEN p_parent_id IS NULL THEN 0 ELSE pp.parent_depth+1 END,
     ARRAY[c.id]::uuid[]
   FROM public.comments c LEFT JOIN parent_position pp ON p_parent_id IS NOT NULL
   WHERE c.post_id=p_post_id AND c.parent_id IS NOT DISTINCT FROM p_parent_id
     AND (p_parent_id IS NULL OR pp.root_id IS NOT NULL)
     AND (c.state<>'published' OR NOT private.can_read_comment(c.id))
   UNION ALL
   SELECT child.id,child.parent_id,child.state,child.created_at,w.root_id,w.depth+1,w.path||child.id
   FROM walk w JOIN public.comments child ON child.post_id=p_post_id AND child.parent_id=w.id
   WHERE (w.state<>'published' OR NOT private.can_read_comment(w.id))
     AND w.depth<8 AND NOT child.id=ANY(w.path)
 )
 SELECT w.id,CASE WHEN p_parent_id IS NULL THEN w.id ELSE w.root_id END,w.depth,w.created_at
 FROM walk w WHERE w.state='published' AND private.can_read_comment(w.id)
$$;

CREATE FUNCTION private.comment_tree_page_candidates(
 p_post_id uuid,p_parent_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer
) RETURNS TABLE(comment_id uuid,root_id uuid,depth integer,display_depth integer,created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 WITH RECURSIVE parent_position AS MATERIALIZED (
   SELECT * FROM private.comment_tree_parent_position(p_post_id,p_parent_id)
 ), direct AS MATERIALIZED (
   SELECT c.id AS comment_id,CASE WHEN p_parent_id IS NULL THEN c.id ELSE pp.root_id END AS root_id,
     CASE WHEN p_parent_id IS NULL THEN 0 ELSE pp.parent_depth+1 END AS depth,
     CASE WHEN p_parent_id IS NULL THEN 0 ELSE pp.display_depth+1 END AS display_depth,c.created_at
   FROM public.comments c LEFT JOIN parent_position pp ON p_parent_id IS NOT NULL
   WHERE c.post_id=p_post_id AND c.parent_id IS NOT DISTINCT FROM p_parent_id
     AND c.state='published' AND private.can_read_comment(c.id)
     AND (p_parent_id IS NULL OR pp.root_id IS NOT NULL)
     AND (p_parent_id IS NULL OR pp.display_depth<7)
     AND (p_after_time IS NULL OR
       (p_parent_id IS NULL AND (c.created_at,c.id)<(p_after_time,p_after_id)) OR
       (p_parent_id IS NOT NULL AND (c.created_at,c.id)>(p_after_time,p_after_id)))
   ORDER BY CASE WHEN p_parent_id IS NULL THEN c.created_at END DESC,
     CASE WHEN p_parent_id IS NULL THEN c.id END DESC,
     CASE WHEN p_parent_id IS NOT NULL THEN c.created_at END ASC,
     CASE WHEN p_parent_id IS NOT NULL THEN c.id END ASC
   LIMIT p_limit+1
 ), promoted AS MATERIALIZED (
   SELECT h.comment_id,CASE WHEN p_parent_id IS NULL THEN h.comment_id ELSE pp.root_id END AS root_id,
     h.depth,CASE WHEN p_parent_id IS NULL THEN 0 ELSE pp.display_depth+1 END AS display_depth,h.created_at
   FROM private.comment_tree_hidden_children(p_post_id,p_parent_id) h
   LEFT JOIN parent_position pp ON true
   WHERE (p_parent_id IS NULL OR pp.root_id IS NOT NULL)
     AND (p_parent_id IS NULL OR pp.display_depth<7)
     AND (p_after_time IS NULL OR
     (p_parent_id IS NULL AND (h.created_at,h.comment_id)<(p_after_time,p_after_id)) OR
     (p_parent_id IS NOT NULL AND (h.created_at,h.comment_id)>(p_after_time,p_after_id)))
   ORDER BY CASE WHEN p_parent_id IS NULL THEN h.created_at END DESC,
     CASE WHEN p_parent_id IS NULL THEN h.comment_id END DESC,
     CASE WHEN p_parent_id IS NOT NULL THEN h.created_at END ASC,
     CASE WHEN p_parent_id IS NOT NULL THEN h.comment_id END ASC
   LIMIT p_limit+1
 ), combined AS (
   SELECT * FROM direct UNION ALL SELECT * FROM promoted
 )
 SELECT * FROM combined
 ORDER BY CASE WHEN p_parent_id IS NULL THEN created_at END DESC,
   CASE WHEN p_parent_id IS NULL THEN comment_id END DESC,
   CASE WHEN p_parent_id IS NOT NULL THEN created_at END ASC,
   CASE WHEN p_parent_id IS NOT NULL THEN comment_id END ASC
 LIMIT p_limit+1
$$;

CREATE FUNCTION private.comment_tree_node_json(
 p_post_id uuid,p_comment_id uuid,p_display_parent_id uuid,p_root_id uuid,
 p_depth integer,p_display_depth integer,p_include_preview boolean
) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE c public.comments; profile_name text; profile_avatar uuid; profile_preset text;
  child_count bigint; preview_id uuid; preview_depth integer;
  preview_json jsonb; mention_rows jsonb;
BEGIN
 SELECT * INTO c FROM public.comments x WHERE x.id=p_comment_id AND x.post_id=p_post_id
   AND x.state='published' AND private.can_read_comment(x.id);
 IF NOT FOUND THEN RETURN NULL; END IF;

 SELECT pr.display_name,pr.avatar_asset_id,pr.public_avatar_preset
 INTO profile_name,profile_avatar,profile_preset
 FROM public.profiles pr WHERE pr.id=c.author_id AND private.can_read_profile(pr.id);

 WITH child_rows AS MATERIALIZED (
   SELECT v.comment_id,v.depth,v.created_at
   FROM private.comment_tree_visible_children(p_post_id,c.id) v
 ), child_count_row AS (
   SELECT count(*) AS reply_count FROM child_rows
 ), latest_child AS (
   SELECT comment_id,depth FROM child_rows
   ORDER BY created_at DESC,comment_id DESC LIMIT 1
 )
 SELECT child_count_row.reply_count,latest_child.comment_id,latest_child.depth
 INTO child_count,preview_id,preview_depth
 FROM child_count_row LEFT JOIN latest_child ON true;
 IF p_include_preview AND preview_id IS NOT NULL AND p_display_depth<7 THEN
   -- One preview level only. The preview's count is still included, and the
   -- client lazy-loads its branch instead of receiving a recursive JSON tree.
   preview_json:=private.comment_tree_node_json(p_post_id,preview_id,c.id,p_root_id,
     preview_depth,p_display_depth+1,false);
 END IF;

 SELECT coalesce(jsonb_agg(jsonb_build_object(
   'user_id',CASE WHEN private.can_read_profile(m.mentioned_user_id) OR c.author_id=auth.uid() THEN m.mentioned_user_id END,
   'label',CASE WHEN private.can_read_profile(m.mentioned_user_id) THEN m.display_label ELSE '@Meeto user' END,
   'start_utf16',m.span_start_utf16,'end_utf16',m.span_end_utf16
 ) ORDER BY m.span_start_utf16),'[]'::jsonb)
 INTO mention_rows
 FROM public.content_mentions m WHERE m.content_type='comment' AND m.content_id=c.id;

 RETURN jsonb_build_object(
   'id',c.id,'post_id',c.post_id,
   'author_id',CASE WHEN private.can_read_profile(c.author_id) OR c.author_id=auth.uid() THEN c.author_id END,
   'author_name',coalesce(profile_name,'Meeto user'),
   'author_avatar_asset_id',profile_avatar,'author_avatar_preset',coalesce(profile_preset,'neutral'),
   'parent_id',CASE WHEN c.parent_id IS NOT NULL AND EXISTS(
     SELECT 1 FROM public.comments actual_parent WHERE actual_parent.id=c.parent_id
       AND actual_parent.post_id=c.post_id AND actual_parent.state='published'
       AND private.can_read_comment(actual_parent.id)
   ) THEN c.parent_id END,
   'display_parent_id',p_display_parent_id,'root_id',p_root_id,
   'depth',p_depth,'display_depth',p_display_depth,'body',c.body,'state',c.state,
   'version',c.version,'created_at',c.created_at,'updated_at',c.updated_at,
   'reactions_count',(SELECT count(*) FROM public.comment_reactions r WHERE r.comment_id=c.id),
   'my_reaction',(SELECT r.reaction FROM public.comment_reactions r WHERE r.comment_id=c.id AND r.user_id=auth.uid()),
   'mentions',mention_rows,'reply_count',child_count,'preview_reply',preview_json
 );
END $$;

CREATE FUNCTION private.list_comment_tree(
 p_post_id uuid,p_parent_id uuid,p_display_depth integer,
 p_after_time timestamptz,p_after_id uuid,p_limit integer
) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE rows_json jsonb; more boolean; verified_display_depth integer;
BEGIN
 PERFORM private.b_active();
 IF p_post_id IS NULL OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 30
  OR (p_after_time IS NULL)<>(p_after_id IS NULL)
  OR p_display_depth IS NULL OR p_display_depth NOT BETWEEN 0 AND 7
  OR (p_parent_id IS NULL AND p_display_depth<>0) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_comment_tree_page';
 END IF;
 IF NOT private.can_read_post(p_post_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p_parent_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM public.comments c WHERE c.id=p_parent_id AND c.post_id=p_post_id
    AND c.state='published' AND private.can_read_comment(c.id)
 ) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='parent_unavailable'; END IF;
 IF p_parent_id IS NOT NULL THEN
   SELECT pp.display_depth INTO verified_display_depth
   FROM private.comment_tree_parent_position(p_post_id,p_parent_id) pp;
   IF NOT FOUND OR p_display_depth IS DISTINCT FROM verified_display_depth THEN
     RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_comment_tree_page';
   END IF;
 END IF;

 WITH candidates AS MATERIALIZED (
   SELECT v.comment_id,v.root_id,v.depth,v.display_depth,v.created_at
   FROM private.comment_tree_page_candidates(p_post_id,p_parent_id,p_after_time,p_after_id,p_limit) v
 ), selected AS MATERIALIZED (
   SELECT * FROM candidates
   ORDER BY CASE WHEN p_parent_id IS NULL THEN created_at END DESC,
     CASE WHEN p_parent_id IS NULL THEN comment_id END DESC,
     CASE WHEN p_parent_id IS NOT NULL THEN created_at END ASC,
     CASE WHEN p_parent_id IS NOT NULL THEN comment_id END ASC
   LIMIT p_limit
 )
 SELECT coalesce((SELECT jsonb_agg(private.comment_tree_node_json(
     p_post_id,s.comment_id,p_parent_id,s.root_id,s.depth,s.display_depth,true
   ) ORDER BY CASE WHEN p_parent_id IS NULL THEN s.created_at END DESC,
     CASE WHEN p_parent_id IS NULL THEN s.comment_id END DESC,
     CASE WHEN p_parent_id IS NOT NULL THEN s.created_at END ASC,
     CASE WHEN p_parent_id IS NOT NULL THEN s.comment_id END ASC) FROM selected s),'[]'::jsonb),
   (SELECT count(*)>p_limit FROM candidates)
 INTO rows_json,more;
 RETURN jsonb_build_object('items',rows_json,'has_more',coalesce(more,false));
END $$;

CREATE FUNCTION public.list_comment_tree(
 p_post_id uuid,p_parent_id uuid,p_display_depth integer,
 p_after_time timestamptz,p_after_id uuid,p_limit integer
) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
 SELECT private.list_comment_tree($1,$2,$3,$4,$5,$6)
$$;

REVOKE ALL ON FUNCTION private.comment_tree_visible_children(uuid,uuid),
 private.require_published_comment_parent(),
 private.comment_tree_parent_position(uuid,uuid),
 private.comment_tree_hidden_children(uuid,uuid),
 private.comment_tree_page_candidates(uuid,uuid,timestamptz,uuid,integer),
 private.comment_tree_node_json(uuid,uuid,uuid,uuid,integer,integer,boolean),
 private.list_comment_tree(uuid,uuid,integer,timestamptz,uuid,integer)
 FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.list_comment_tree(uuid,uuid,integer,timestamptz,uuid,integer)
 TO authenticated;
REVOKE ALL ON FUNCTION public.list_comment_tree(uuid,uuid,integer,timestamptz,uuid,integer)
 FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.list_comment_tree(uuid,uuid,integer,timestamptz,uuid,integer)
 TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
