BEGIN;

CREATE TABLE public.reel_views (
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  viewer_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  first_viewed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, viewer_id)
);
ALTER TABLE public.reel_views ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.reel_views FROM PUBLIC,anon,authenticated,service_role;

CREATE OR REPLACE FUNCTION private.reel_card(p_post_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.post_json(p.id)||jsonb_build_object(
    'is_reel',true,'reel_duration_ms',p.reel_duration_ms,
    'reel_cover_position_ms',p.reel_cover_position_ms,
    'reel_cover_asset_id',CASE WHEN p.reel_cover_asset_id IS NOT NULL AND private.can_read_media(p.reel_cover_asset_id)
      THEN p.reel_cover_asset_id END,
    'views_count',(SELECT count(*) FROM public.reel_views rv WHERE rv.post_id=p.id),
    'is_following',EXISTS(SELECT 1 FROM public.user_follows f
      WHERE f.follower_id=auth.uid() AND f.followed_id=p.author_id))
  FROM public.posts p
  WHERE p.id=p_post_id AND p.is_reel AND p.state='published' AND p.group_id IS NULL
    AND private.can_read_post(p.id)
    AND EXISTS(SELECT 1 FROM public.post_media pm JOIN public.media_assets a ON a.id=pm.asset_id
      WHERE pm.post_id=p.id AND a.state='ready' AND a.mime_type='video/mp4'
        AND private.can_read_media(a.id))
$$;

CREATE FUNCTION private.record_reel_view(p_post_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); total bigint;
BEGIN
  PERFORM private.b_active();
  IF p_post_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.posts p
      WHERE p.id=p_post_id AND p.is_reel AND p.state='published' AND p.group_id IS NULL
        AND private.can_read_post(p.id)
        AND EXISTS(SELECT 1 FROM public.post_media pm JOIN public.media_assets a ON a.id=pm.asset_id
          WHERE pm.post_id=p.id AND a.state='ready' AND a.mime_type='video/mp4'
            AND private.can_read_media(a.id))) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='reel_unavailable';
  END IF;
  INSERT INTO public.reel_views(post_id,viewer_id) VALUES(p_post_id,actor)
    ON CONFLICT(post_id,viewer_id) DO NOTHING;
  SELECT count(*) INTO total FROM public.reel_views WHERE post_id=p_post_id;
  RETURN jsonb_build_object('views_count',total);
END $$;
CREATE FUNCTION public.record_reel_view(p_post_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.record_reel_view($1) $$;

REVOKE ALL ON FUNCTION private.reel_card(uuid),private.record_reel_view(uuid) FROM PUBLIC,anon,authenticated,service_role;
REVOKE ALL ON FUNCTION public.record_reel_view(uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION private.record_reel_view(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.record_reel_view(uuid) TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
