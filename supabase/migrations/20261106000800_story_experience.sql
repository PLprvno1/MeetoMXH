-- Task 42. Forward-only; owner applies after Task 36/37. No new media buckets or chat tables.
BEGIN;
CREATE OR REPLACE FUNCTION private.story_json(sid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',s.id,'author_id',s.author_id,
 'author_name',CASE WHEN private.can_read_profile(p.id) THEN p.display_name ELSE 'Meeto' END,
 'avatar_asset_id',CASE WHEN private.can_read_profile(p.id) THEN p.avatar_asset_id END,
 'avatar_preset',CASE WHEN private.can_read_profile(p.id) THEN p.public_avatar_preset ELSE 'neutral' END,
 'media_asset_id',s.media_asset_id,'mime_type',a.mime_type,'caption',s.caption,'audience',s.audience,
 'created_at',s.created_at,'expires_at',s.expires_at,
 'viewed',EXISTS(SELECT 1 FROM public.story_views v WHERE v.story_id=s.id AND v.viewer_id=auth.uid()),
 'liked',EXISTS(SELECT 1 FROM public.story_reactions r WHERE r.story_id=s.id AND r.user_id=auth.uid() AND r.reaction='like'),
 'views_count',CASE WHEN s.author_id=auth.uid() THEN (SELECT count(*) FROM public.story_views v WHERE v.story_id=s.id AND v.viewer_id<>s.author_id) END,
 'own',s.author_id=auth.uid())
 FROM public.stories s JOIN public.profiles p ON p.id=s.author_id JOIN public.media_assets a ON a.id=s.media_asset_id
 WHERE s.id=sid AND s.state='active' AND s.expires_at>statement_timestamp()
 AND private.can_read_story(s.id) AND private.can_read_media(s.media_asset_id);
$$;
CREATE FUNCTION public.get_story(p_story_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 PERFORM private.c_actor(); result:=private.story_json(p_story_id);
 IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 RETURN result;
END $$;
CREATE FUNCTION public.get_story_states(p_authors uuid[]) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.c_actor();
 IF p_authors IS NULL OR cardinality(p_authors)>200 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_batch'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('author_id',q.author_id,'story_id',q.id,'expires_at',q.expires_at))
 FROM (SELECT DISTINCT ON (s.author_id) s.author_id,s.id,s.expires_at FROM public.stories s
 WHERE s.author_id=ANY(p_authors) AND s.state='active' AND s.expires_at>statement_timestamp()
 AND private.can_read_story(s.id) AND private.can_read_media(s.media_asset_id)
 ORDER BY s.author_id,s.created_at,s.id) q),'[]'::jsonb);
END $$;
CREATE FUNCTION public.get_story_author_stories(p_author uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.c_actor();
 IF p_author IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_author'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.story_json(s.id) ORDER BY s.created_at,s.id)
 FROM public.stories s WHERE s.author_id=p_author AND s.state='active' AND s.expires_at>statement_timestamp()
 AND private.can_read_story(s.id) AND private.can_read_media(s.media_asset_id)),'[]'::jsonb);
END $$;
CREATE FUNCTION public.set_story_like(p_story_id uuid,p_liked boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); owner_id uuid;
BEGIN
 IF p_liked IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_like'; END IF;
 PERFORM 1 FROM public.stories s WHERE s.id=p_story_id AND s.state='active' AND s.expires_at>statement_timestamp()
 AND private.can_read_story(s.id) AND private.can_read_media(s.media_asset_id) FOR SHARE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 SELECT author_id INTO owner_id FROM public.stories WHERE id=p_story_id;
 IF p_liked THEN INSERT INTO public.story_reactions(story_id,user_id,reaction) VALUES(p_story_id,actor,'like')
 ON CONFLICT(story_id,user_id) DO UPDATE SET reaction='like';
 PERFORM private.emit_notification(owner_id,actor,'story','story',p_story_id,'story-like:'||p_story_id::text||':'||actor::text);
 ELSE DELETE FROM public.story_reactions WHERE story_id=p_story_id AND user_id=actor; END IF;
 RETURN jsonb_build_object('story_id',p_story_id,'liked',p_liked);
END $$;
CREATE FUNCTION public.get_story_viewers_page(p_story_id uuid,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_actor(); items jsonb; total bigint;
BEGIN
 IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 OR (p_after_time IS NULL)<>(p_after_id IS NULL) THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_cursor'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.stories s WHERE s.id=p_story_id AND s.author_id=actor AND private.can_read_story(s.id)) THEN
 RAISE SQLSTATE 'PT404' USING MESSAGE='story_unavailable'; END IF;
 SELECT count(*) INTO total FROM public.story_views v WHERE v.story_id=p_story_id AND v.viewer_id<>actor;
 SELECT coalesce(jsonb_agg(jsonb_build_object('cursor_id',q.id,'viewed_at',q.first_viewed_at,
 'viewer_id',CASE WHEN private.can_read_profile(q.id) THEN q.id END,
 'display_name',CASE WHEN private.can_read_profile(q.id) THEN q.display_name ELSE 'Người dùng Meeto' END,
 'avatar_asset_id',CASE WHEN private.can_read_profile(q.id) THEN q.avatar_asset_id END,
 'avatar_preset',CASE WHEN private.can_read_profile(q.id) THEN q.public_avatar_preset ELSE 'neutral' END,
 'liked',EXISTS(SELECT 1 FROM public.story_reactions r WHERE r.story_id=p_story_id AND r.user_id=q.id AND r.reaction='like'))
 ORDER BY q.first_viewed_at DESC,q.id DESC),'[]'::jsonb) INTO items
 FROM (SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset,v.first_viewed_at
 FROM public.story_views v JOIN public.profiles p ON p.id=v.viewer_id WHERE v.story_id=p_story_id AND v.viewer_id<>actor
 AND (p_after_time IS NULL OR (v.first_viewed_at,v.viewer_id)<(p_after_time,p_after_id))
 ORDER BY v.first_viewed_at DESC,v.viewer_id DESC LIMIT p_limit) q;
 RETURN jsonb_build_object('total',total,'items',items,'has_more',jsonb_array_length(items)=p_limit);
END $$;
REVOKE ALL ON FUNCTION public.get_story(uuid),public.get_story_author_stories(uuid),public.get_story_states(uuid[]),public.set_story_like(uuid,boolean),public.get_story_viewers_page(uuid,timestamptz,uuid,integer) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.get_story(uuid),public.get_story_author_stories(uuid),public.get_story_states(uuid[]),public.set_story_like(uuid,boolean),public.get_story_viewers_page(uuid,timestamptz,uuid,integer) TO authenticated;
-- Existing RLS/revoked table writes remain intact. Identity is never supplied by the caller.
NOTIFY pgrst,'reload schema';
COMMIT;
