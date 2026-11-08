-- Supply sizes for exact-content deduplication in the featured-photo picker.
-- This migration neither hashes in SQL nor changes media/post rows.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.list_profile_photo_candidates(timestamptz,uuid,integer)') IS NULL
 OR to_regprocedure('public.list_profile_featured_albums(uuid)') IS NULL THEN
  RAISE EXCEPTION 'Requires profile albums and featured-photo albums migrations';
 END IF;
 IF EXISTS(SELECT 1 FROM pg_proc WHERE oid IN (
  to_regprocedure('private.list_profile_photo_candidates(timestamptz,uuid,integer)'),
  to_regprocedure('public.list_profile_featured_albums(uuid)'))
  AND (pg_get_userbyid(proowner)<>current_user OR NOT prosecdef)) THEN
  RAISE EXCEPTION 'Review photo-list function owners before replacement';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.list_profile_photo_candidates(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.asset_id DESC)
    FROM (SELECT p.id post_id,p.created_at,pm.asset_id,pm.position,m.mime_type,m.size_bytes
      FROM public.posts p JOIN public.post_media pm ON pm.post_id=p.id
      JOIN public.media_assets m ON m.id=pm.asset_id
      WHERE p.author_id=actor AND p.group_id IS NULL AND p.kind='original' AND p.state='published'
        AND m.owner_id=actor AND m.state='ready' AND m.purpose='post' AND m.mime_type LIKE 'image/%'
        AND private.can_read_post(p.id) AND private.can_read_media(m.id)
        AND (p_after_time IS NULL OR (p.created_at,pm.asset_id)<(p_after_time,p_after_id))
      ORDER BY p.created_at DESC,pm.asset_id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION public.list_profile_featured_albums(p_owner_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF p_owner_id IS NULL OR NOT private.can_read_profile(p_owner_id)
 OR NOT private.account_content_allowed(p_owner_id) THEN
  RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',a.id,'title',a.title,'version',a.version,
  'items',coalesce((SELECT jsonb_agg(jsonb_build_object('id',i.id,'asset_id',i.asset_id,
   'post_id',i.source_post_id,'size_bytes',(SELECT m.size_bytes FROM public.media_assets m WHERE m.id=i.asset_id)) ORDER BY i.position,i.id)
   FROM public.profile_album_items i WHERE i.album_id=a.id
   AND private.can_read_post(i.source_post_id) AND private.can_read_media(i.asset_id)),'[]'::jsonb))
  ORDER BY a.created_at,a.id) FROM public.profile_albums a
  WHERE a.owner_id=p_owner_id AND a.is_featured AND private.can_read_profile_album(a.id)),'[]'::jsonb);
END $$;

-- Preserve existing owners, EXECUTE ACLs and media visibility checks.
NOTIFY pgrst,'reload schema';
COMMIT;