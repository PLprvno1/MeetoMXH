-- Featured photos become named albums referencing existing post images.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.add_profile_album_photos(uuid,uuid,uuid[])') IS NULL
 OR to_regprocedure('private.account_content_allowed(uuid)') IS NULL
 OR to_regclass('public.profile_featured_items') IS NULL THEN
  RAISE EXCEPTION 'Requires profile albums and follow-only privacy migrations';
 END IF;
END $$;

ALTER TABLE public.profile_albums ADD COLUMN is_featured boolean NOT NULL DEFAULT false;
CREATE INDEX profile_albums_featured_owner_idx ON public.profile_albums(owner_id) WHERE is_featured;

-- Keep old featured image references. Deterministic IDs avoid duplicating their assets.
INSERT INTO public.profile_albums(id,owner_id,title,audience,is_featured,created_at)
 SELECT f.id,f.owner_id,'Ảnh nổi bật ' || (f.position+1)::text,
 CASE WHEN EXISTS(SELECT 1 FROM public.user_settings s WHERE s.user_id=f.owner_id AND s.account_private)
 THEN 'followers' ELSE 'public' END,true,f.created_at
 FROM public.profile_featured_items f WHERE f.asset_id IS NOT NULL;
INSERT INTO public.profile_album_items(id,album_id,source_post_id,asset_id,position,created_at)
 SELECT f.id,f.id,f.source_post_id,f.asset_id,0,f.created_at
 FROM public.profile_featured_items f WHERE f.asset_id IS NOT NULL;
UPDATE public.profile_albums a SET cover_item_id=a.id WHERE a.is_featured;

CREATE FUNCTION public.list_profile_featured_albums(p_owner_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF p_owner_id IS NULL OR NOT private.can_read_profile(p_owner_id)
 OR NOT private.account_content_allowed(p_owner_id) THEN
  RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',a.id,'title',a.title,'version',a.version,
  'items',coalesce((SELECT jsonb_agg(jsonb_build_object('id',i.id,'asset_id',i.asset_id,
   'post_id',i.source_post_id) ORDER BY i.position,i.id)
   FROM public.profile_album_items i WHERE i.album_id=a.id
   AND private.can_read_post(i.source_post_id) AND private.can_read_media(i.asset_id)),'[]'::jsonb))
  ORDER BY a.created_at,a.id) FROM public.profile_albums a
  WHERE a.owner_id=p_owner_id AND a.is_featured AND private.can_read_profile_album(a.id)),'[]'::jsonb);
END $$;

-- One transaction: title and selection either save together or roll back together.
-- Client supplies a stable UUID so retrying a new album never creates a second album.
CREATE FUNCTION public.save_profile_featured_album(p_album_id uuid,p_title text,p_items jsonb,p_expected_version bigint)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.profile_albums; photo jsonb; first_item uuid; ordinal integer:=0;
BEGIN
 PERFORM private.b_active();
 IF p_album_id IS NULL OR p_title IS NULL OR length(btrim(p_title)) NOT BETWEEN 1 AND 100
 OR p_items IS NULL OR jsonb_typeof(p_items)<>'array'
 OR p_expected_version IS NULL OR p_expected_version<0 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album'; END IF;
 IF jsonb_array_length(p_items) NOT BETWEEN 1 AND 50 THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album_photos'; END IF;
 IF EXISTS(SELECT 1 FROM jsonb_array_elements(p_items) x
  WHERE jsonb_typeof(x)<>'object' OR nullif(x->>'asset_id','') IS NULL OR nullif(x->>'post_id','') IS NULL)
 OR (SELECT count(DISTINCT x->>'asset_id') FROM jsonb_array_elements(p_items) x)<>jsonb_array_length(p_items) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album_photos'; END IF;
 -- Serialize creation per owner, including the album limit.
 PERFORM 1 FROM public.profiles WHERE id=actor FOR UPDATE;
 IF p_expected_version=0 THEN
  IF EXISTS(SELECT 1 FROM public.profile_albums WHERE id=p_album_id) THEN
   RAISE SQLSTATE 'PT409' USING MESSAGE='album_version_conflict'; END IF;
  IF (SELECT count(*) FROM public.profile_albums WHERE owner_id=actor AND is_featured)>=12 THEN
   RAISE SQLSTATE 'PT422' USING MESSAGE='featured_album_limit'; END IF;
  INSERT INTO public.profile_albums(id,owner_id,title,audience,is_featured)
   VALUES(p_album_id,actor,btrim(p_title),
    CASE WHEN EXISTS(SELECT 1 FROM public.user_settings s WHERE s.user_id=actor AND s.account_private)
    THEN 'followers' ELSE 'public' END,true) RETURNING * INTO a;
 ELSE
  SELECT * INTO a FROM public.profile_albums WHERE id=p_album_id AND owner_id=actor AND is_featured FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='album_unavailable'; END IF;
  IF a.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='album_version_conflict'; END IF;
 END IF;
 FOR photo IN SELECT value FROM jsonb_array_elements(p_items) LOOP
  -- Reuse canonical validation: owner, original non-group published post, ready image,
  -- source-post membership and current media/post ACLs. A failed item rolls back all changes.
  PERFORM private.add_profile_album_photos(p_album_id,(photo->>'post_id')::uuid,ARRAY[(photo->>'asset_id')::uuid]);
  UPDATE public.profile_album_items SET position=ordinal
   WHERE album_id=p_album_id AND asset_id=(photo->>'asset_id')::uuid RETURNING id INTO first_item;
  IF ordinal=0 THEN
   UPDATE public.profile_albums SET cover_item_id=first_item WHERE id=p_album_id;
  END IF;
  ordinal:=ordinal+1;
 END LOOP;
 DELETE FROM public.profile_album_items i WHERE i.album_id=p_album_id
  AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(p_items) x WHERE (x->>'asset_id')::uuid=i.asset_id);
 UPDATE public.profile_albums SET title=btrim(p_title),version=version+1,updated_at=statement_timestamp()
  WHERE id=p_album_id RETURNING * INTO a;
 RETURN jsonb_build_object('id',a.id,'version',a.version,'title',a.title);
END $$;

REVOKE ALL ON FUNCTION public.list_profile_featured_albums(uuid),
 public.save_profile_featured_album(uuid,text,jsonb,bigint) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.list_profile_featured_albums(uuid),
 public.save_profile_featured_album(uuid,text,jsonb,bigint) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
