-- Task 24 / migration 031: privacy-preserving profile albums, galleries and featured content.
-- Albums reference existing post media; this migration never duplicates storage objects.
BEGIN;

DO $$ BEGIN
  IF to_regclass('public.profiles') IS NULL OR to_regclass('public.posts') IS NULL
     OR to_regclass('public.post_media') IS NULL OR to_regclass('public.media_assets') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.user_settings') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL OR to_regprocedure('private.require_identity()') IS NULL OR to_regprocedure('private.is_active()') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.are_friends(uuid)') IS NULL OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL THEN
    RAISE EXCEPTION 'Profile Albums prerequisites missing; verify migrations 001-030';
  END IF;
END $$;

CREATE TABLE public.profile_albums (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 100),
  description text NOT NULL DEFAULT '' CHECK (char_length(description)<=1000),
  audience text NOT NULL DEFAULT 'friends' CHECK (audience IN ('public','friends','private')),
  cover_item_id uuid,
  version bigint NOT NULL DEFAULT 1 CHECK (version>0),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE INDEX profile_albums_owner_idx ON public.profile_albums(owner_id,created_at DESC,id DESC);

CREATE TABLE public.profile_album_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  album_id uuid NOT NULL REFERENCES public.profile_albums(id) ON DELETE CASCADE,
  source_post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  asset_id uuid NOT NULL REFERENCES public.media_assets(id) ON DELETE CASCADE,
  position integer NOT NULL DEFAULT 0 CHECK (position>=0),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(album_id,asset_id),
  UNIQUE(id,album_id)
);
CREATE INDEX profile_album_items_page_idx ON public.profile_album_items(album_id,created_at DESC,id DESC);
-- Media cleanup uses pending_delete -> deleted soft-state transitions and preserves registry rows.
-- CASCADE only applies if a registry row is hard-purged; the cover FK then nulls the album cover.
ALTER TABLE public.profile_albums ADD CONSTRAINT profile_albums_cover_item_fk
  FOREIGN KEY(cover_item_id) REFERENCES public.profile_album_items(id) ON DELETE SET NULL;

CREATE TABLE public.profile_featured_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  source_post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  asset_id uuid REFERENCES public.media_assets(id) ON DELETE CASCADE,
  position smallint NOT NULL CHECK (position BETWEEN 0 AND 5),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE INDEX profile_featured_owner_idx ON public.profile_featured_items(owner_id,position,id);
CREATE UNIQUE INDEX profile_featured_post_unique_idx ON public.profile_featured_items(owner_id,source_post_id) WHERE asset_id IS NULL;
CREATE UNIQUE INDEX profile_featured_photo_unique_idx ON public.profile_featured_items(owner_id,source_post_id,asset_id) WHERE asset_id IS NOT NULL;
CREATE TABLE public.profile_pinned_posts (
  owner_id uuid PRIMARY KEY REFERENCES public.profiles(id) ON DELETE CASCADE,
  post_id uuid NOT NULL UNIQUE REFERENCES public.posts(id) ON DELETE CASCADE,
  pinned_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

ALTER TABLE public.profile_albums ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_album_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_featured_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profile_pinned_posts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.profile_albums,public.profile_album_items,public.profile_featured_items,
  public.profile_pinned_posts FROM PUBLIC,anon,authenticated,service_role;

-- Retirement is a soft-delete in the existing worker. Remove virtual references
-- at retirement time; the FK CASCADE also covers any later hard purge of the registry row.
CREATE FUNCTION private.remove_retired_profile_media_refs() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  DELETE FROM public.profile_album_items WHERE asset_id=NEW.id;
  DELETE FROM public.profile_featured_items WHERE asset_id=NEW.id;
  RETURN NEW;
END $$;
CREATE TRIGGER profile_media_retirement_cleanup
  AFTER UPDATE OF state ON public.media_assets
  FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state AND NEW.state IN ('pending_delete','deleted'))
  EXECUTE FUNCTION private.remove_retired_profile_media_refs();

CREATE FUNCTION private.can_read_profile_album(p_album_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT private.require_identity() IS NOT NULL AND private.is_active() AND EXISTS(
    SELECT 1 FROM public.profile_albums a
    WHERE a.id=p_album_id AND private.can_read_profile(a.owner_id)
      AND (a.owner_id=auth.uid() OR a.audience='public'
        OR (a.audience='friends' AND private.are_friends(a.owner_id))))
$$;
CREATE FUNCTION private.can_read_profile_album_item(p_item_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS(SELECT 1 FROM public.profile_album_items i
    WHERE i.id=p_item_id AND private.can_read_profile_album(i.album_id)
      AND private.can_read_post(i.source_post_id) AND private.can_read_media(i.asset_id))
$$;

CREATE FUNCTION private.create_profile_album(p_title text,p_description text,p_audience text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); item public.profile_albums;
BEGIN
  PERFORM private.b_active();
  IF p_title IS NULL OR char_length(btrim(p_title)) NOT BETWEEN 1 AND 100
     OR p_description IS NULL OR char_length(p_description)>1000
     OR p_audience IS NULL OR p_audience NOT IN ('public','friends','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album'; END IF;
  INSERT INTO public.profile_albums(owner_id,title,description,audience)
    VALUES(actor,btrim(p_title),btrim(p_description),p_audience) RETURNING * INTO item;
  RETURN jsonb_build_object('id',item.id,'title',item.title,'description',item.description,
    'audience',item.audience,'version',item.version,'created_at',item.created_at);
END $$;
CREATE FUNCTION private.update_profile_album(p_album_id uuid,p_title text,p_description text,p_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); item public.profile_albums;
BEGIN
  IF p_album_id IS NULL OR p_title IS NULL OR char_length(btrim(p_title)) NOT BETWEEN 1 AND 100
     OR p_description IS NULL OR char_length(p_description)>1000 OR p_audience IS NULL
     OR p_audience NOT IN ('public','friends','private') OR p_expected_version IS NULL OR p_expected_version<1 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album'; END IF;
  UPDATE public.profile_albums SET title=btrim(p_title),description=btrim(p_description),audience=p_audience,
    version=version+1,updated_at=statement_timestamp()
    WHERE id=p_album_id AND owner_id=actor AND version=p_expected_version RETURNING * INTO item;
  IF NOT FOUND THEN
    IF NOT EXISTS(SELECT 1 FROM public.profile_albums WHERE id=p_album_id AND owner_id=actor) THEN
      RAISE SQLSTATE 'PT404' USING MESSAGE='album_unavailable';
    END IF;
    RAISE SQLSTATE 'PT409' USING MESSAGE='album_version_conflict';
  END IF;
  RETURN jsonb_build_object('id',item.id,'title',item.title,'description',item.description,
    'audience',item.audience,'version',item.version,'updated_at',item.updated_at);
END $$;
CREATE FUNCTION private.delete_profile_album(p_album_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  IF p_album_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album'; END IF;
  DELETE FROM public.profile_albums WHERE id=p_album_id AND owner_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=0 THEN RAISE SQLSTATE 'PT404' USING MESSAGE='album_unavailable'; END IF;
  RETURN jsonb_build_object('deleted',true);
END $$;

CREATE FUNCTION private.list_profile_albums(p_owner_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_owner_id IS NULL OR NOT private.can_read_profile(p_owner_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',q.id,'title',q.title,'description',q.description,
      'audience',q.audience,'version',q.version,'created_at',q.created_at,'updated_at',q.updated_at,
      'photo_count',q.photo_count,'cover_asset_id',q.cover_asset_id) ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT a.id,a.title,a.description,a.audience,a.version,a.created_at,a.updated_at,
      (SELECT count(*) FROM public.profile_album_items i WHERE i.album_id=a.id
        AND private.can_read_post(i.source_post_id) AND private.can_read_media(i.asset_id)) photo_count,
      CASE WHEN EXISTS(SELECT 1 FROM public.profile_album_items i WHERE i.id=a.cover_item_id
          AND private.can_read_post(i.source_post_id) AND private.can_read_media(i.asset_id))
        THEN (SELECT i.asset_id FROM public.profile_album_items i WHERE i.id=a.cover_item_id) END cover_asset_id
      FROM public.profile_albums a WHERE a.owner_id=p_owner_id AND private.can_read_profile_album(a.id)
        AND (p_after_time IS NULL OR (a.created_at,a.id)<(p_after_time,p_after_id))
      ORDER BY a.created_at DESC,a.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
CREATE FUNCTION private.get_profile_album(p_album_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE meta jsonb; items jsonb;
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF NOT private.can_read_profile_album(p_album_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='album_unavailable'; END IF;
  SELECT jsonb_build_object('id',a.id,'owner_id',a.owner_id,'title',a.title,'description',a.description,
      'audience',a.audience,'version',a.version,'cover_asset_id',CASE WHEN ci.id IS NOT NULL
        AND private.can_read_post(ci.source_post_id) AND private.can_read_media(ci.asset_id) THEN ci.asset_id END)
    INTO meta FROM public.profile_albums a LEFT JOIN public.profile_album_items ci ON ci.id=a.cover_item_id WHERE a.id=p_album_id;
  SELECT coalesce(jsonb_agg(jsonb_build_object('id',q.id,'asset_id',q.asset_id,'source_post_id',q.source_post_id,
      'mime_type',q.mime_type,'position',q.position,'created_at',q.created_at) ORDER BY q.created_at DESC,q.id DESC),'[]'::jsonb)
    INTO items FROM (SELECT i.id,i.asset_id,i.source_post_id,m.mime_type,i.position,i.created_at
      FROM public.profile_album_items i JOIN public.media_assets m ON m.id=i.asset_id
      WHERE i.album_id=p_album_id AND private.can_read_post(i.source_post_id) AND private.can_read_media(i.asset_id)
        AND (p_after_time IS NULL OR (i.created_at,i.id)<(p_after_time,p_after_id))
      ORDER BY i.created_at DESC,i.id DESC LIMIT p_limit) q;
  RETURN jsonb_build_object('album',meta,'items',items);
END $$;
CREATE FUNCTION private.list_profile_photo_candidates(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.asset_id DESC)
    FROM (SELECT p.id post_id,p.created_at,pm.asset_id,pm.position,m.mime_type
      FROM public.posts p JOIN public.post_media pm ON pm.post_id=p.id
      JOIN public.media_assets m ON m.id=pm.asset_id
      WHERE p.author_id=actor AND p.group_id IS NULL AND p.kind='original' AND p.state='published'
        AND m.owner_id=actor AND m.state='ready' AND m.purpose='post' AND m.mime_type LIKE 'image/%'
        AND private.can_read_post(p.id) AND private.can_read_media(m.id)
        AND (p_after_time IS NULL OR (p.created_at,pm.asset_id)<(p_after_time,p_after_id))
      ORDER BY p.created_at DESC,pm.asset_id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
CREATE FUNCTION private.add_profile_album_photos(p_album_id uuid,p_source_post_id uuid,p_asset_ids uuid[]) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); inserted integer;
BEGIN
  PERFORM private.b_active();
  IF p_album_id IS NULL OR p_source_post_id IS NULL OR p_asset_ids IS NULL OR cardinality(p_asset_ids)<1
     OR cardinality(p_asset_ids)>10 OR array_position(p_asset_ids,NULL) IS NOT NULL
     OR EXISTS(SELECT 1 FROM unnest(p_asset_ids) a GROUP BY a HAVING count(*)>1) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album_photos'; END IF;
  PERFORM 1 FROM public.profile_albums WHERE id=p_album_id AND owner_id=actor FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='album_unavailable'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.id=p_source_post_id AND p.author_id=actor
      AND p.group_id IS NULL AND p.kind='original' AND p.state='published' AND private.can_read_post(p.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='source_post_unavailable'; END IF;
  IF EXISTS(SELECT 1 FROM unnest(p_asset_ids) wanted(asset_id) LEFT JOIN public.post_media pm
      ON pm.post_id=p_source_post_id AND pm.asset_id=wanted.asset_id
      LEFT JOIN public.media_assets m ON m.id=wanted.asset_id
      WHERE pm.asset_id IS NULL OR m.owner_id IS DISTINCT FROM actor OR m.state<>'ready'
        OR m.purpose<>'post' OR m.mime_type NOT LIKE 'image/%' OR NOT private.can_read_media(m.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='album_photo_unavailable'; END IF;
  INSERT INTO public.profile_album_items(album_id,source_post_id,asset_id,position)
    SELECT p_album_id,p_source_post_id,u.asset_id,coalesce((SELECT max(i.position)+1 FROM public.profile_album_items i WHERE i.album_id=p_album_id),0)
      + row_number() OVER(ORDER BY u.ordinality)-1
    FROM unnest(p_asset_ids) WITH ORDINALITY u(asset_id,ordinality)
    ON CONFLICT(album_id,asset_id) DO NOTHING;
  GET DIAGNOSTICS inserted=ROW_COUNT;
  RETURN jsonb_build_object('added',inserted);
END $$;
CREATE FUNCTION private.remove_profile_album_photo(p_album_id uuid,p_item_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  DELETE FROM public.profile_album_items i USING public.profile_albums a
    WHERE i.album_id=a.id AND a.id=p_album_id AND a.owner_id=actor AND i.id=p_item_id;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=0 THEN RAISE SQLSTATE 'PT404' USING MESSAGE='album_photo_unavailable'; END IF;
  RETURN jsonb_build_object('removed',true);
END $$;
CREATE FUNCTION private.set_profile_album_cover(p_album_id uuid,p_item_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); asset uuid;
BEGIN
  IF p_album_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album'; END IF;
  IF p_item_id IS NOT NULL THEN
    SELECT i.asset_id INTO asset FROM public.profile_album_items i JOIN public.profile_albums a ON a.id=i.album_id
      WHERE i.id=p_item_id AND a.id=p_album_id AND a.owner_id=actor AND private.can_read_post(i.source_post_id)
        AND private.can_read_media(i.asset_id);
    IF asset IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='album_photo_unavailable'; END IF;
  END IF;
  UPDATE public.profile_albums SET cover_item_id=p_item_id,version=version+1,updated_at=statement_timestamp()
    WHERE id=p_album_id AND owner_id=actor;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='album_unavailable'; END IF;
  RETURN jsonb_build_object('cover_asset_id',asset);
END $$;
CREATE FUNCTION private.list_profile_media(p_owner_id uuid,p_kind text,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_kind IS NULL OR p_kind NOT IN ('image','video') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_profile_media_request'; END IF;
  IF p_owner_id IS NULL OR NOT private.can_read_profile(p_owner_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.asset_id DESC)
    FROM (SELECT p.id post_id,p.created_at,pm.asset_id,pm.position,m.mime_type,
        CASE WHEN m.metadata->>'duration_ms' ~ '^[0-9]{1,12}$' THEN (m.metadata->>'duration_ms')::bigint END duration_ms
      FROM public.posts p JOIN public.post_media pm ON pm.post_id=p.id
      JOIN public.media_assets m ON m.id=pm.asset_id
      WHERE p.author_id=p_owner_id AND p.group_id IS NULL AND p.kind='original' AND p.state='published'
        AND m.state='ready' AND m.purpose='post'
        AND CASE p_kind WHEN 'image' THEN m.mime_type LIKE 'image/%' ELSE m.mime_type LIKE 'video/%' END
        AND private.can_read_post(p.id) AND private.can_read_media(m.id)
        AND (p_after_time IS NULL OR (p.created_at,pm.asset_id)<(p_after_time,p_after_id))
      ORDER BY p.created_at DESC,pm.asset_id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
CREATE FUNCTION private.set_profile_featured(p_source_post_id uuid,p_asset_id uuid,p_position smallint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); featured public.profile_featured_items;
BEGIN
  IF p_source_post_id IS NULL OR p_position IS NULL OR p_position NOT BETWEEN 0 AND 5 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_featured_item'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':profile-featured',0));
  IF NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.id=p_source_post_id AND p.author_id=actor
      AND p.group_id IS NULL AND p.kind='original' AND p.state='published' AND private.can_read_post(p.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='source_post_unavailable'; END IF;
  IF p_asset_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.media_assets m ON m.id=pm.asset_id
      WHERE pm.post_id=p_source_post_id AND pm.asset_id=p_asset_id AND m.owner_id=actor AND m.state='ready'
        AND m.purpose='post' AND m.mime_type LIKE 'image/%' AND private.can_read_media(m.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='source_photo_unavailable'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.profile_featured_items f WHERE f.owner_id=actor
      AND f.source_post_id=p_source_post_id AND f.asset_id IS NOT DISTINCT FROM p_asset_id)
     AND (SELECT count(*) FROM public.profile_featured_items f WHERE f.owner_id=actor)>=6 THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='featured_limit_reached'; END IF;
  IF p_asset_id IS NULL THEN
    INSERT INTO public.profile_featured_items(owner_id,source_post_id,asset_id,position)
      VALUES(actor,p_source_post_id,NULL,p_position)
      ON CONFLICT(owner_id,source_post_id) WHERE asset_id IS NULL
      DO UPDATE SET position=excluded.position RETURNING * INTO featured;
  ELSE
    INSERT INTO public.profile_featured_items(owner_id,source_post_id,asset_id,position)
      VALUES(actor,p_source_post_id,p_asset_id,p_position)
      ON CONFLICT(owner_id,source_post_id,asset_id) WHERE asset_id IS NOT NULL
      DO UPDATE SET position=excluded.position RETURNING * INTO featured;
  END IF;
  RETURN jsonb_build_object('id',featured.id,'position',featured.position);
END $$;
CREATE FUNCTION private.remove_profile_featured(p_item_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  DELETE FROM public.profile_featured_items WHERE id=p_item_id AND owner_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=0 THEN RAISE SQLSTATE 'PT404' USING MESSAGE='featured_item_unavailable'; END IF;
  RETURN jsonb_build_object('removed',true);
END $$;
CREATE FUNCTION private.list_profile_featured(p_owner_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  IF p_owner_id IS NULL OR NOT private.can_read_profile(p_owner_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',f.id,'post_id',p.id,'asset_id',f.asset_id,
      'position',f.position,'mime_type',m.mime_type,'created_at',p.created_at) ORDER BY f.position,f.id)
    FROM public.profile_featured_items f JOIN public.posts p ON p.id=f.source_post_id
      LEFT JOIN public.media_assets m ON m.id=f.asset_id
    WHERE f.owner_id=p_owner_id AND p.group_id IS NULL AND p.kind='original'
      AND private.can_read_post(p.id) AND (f.asset_id IS NULL OR private.can_read_media(f.asset_id))),'[]'::jsonb);
END $$;
CREATE FUNCTION private.set_profile_pinned_post(p_post_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); pinned public.profile_pinned_posts;
BEGIN
  IF p_post_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_pinned_post'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.id=p_post_id AND p.author_id=actor AND p.group_id IS NULL
      AND p.kind='original' AND p.state='published' AND private.can_read_post(p.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='pinnable_post_unavailable'; END IF;
  INSERT INTO public.profile_pinned_posts(owner_id,post_id) VALUES(actor,p_post_id)
    ON CONFLICT(owner_id) DO UPDATE SET post_id=excluded.post_id,pinned_at=statement_timestamp()
    RETURNING * INTO pinned;
  RETURN jsonb_build_object('post_id',pinned.post_id,'pinned_at',pinned.pinned_at);
END $$;
CREATE FUNCTION private.clear_profile_pinned_post() RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); changed integer;
BEGIN
  DELETE FROM public.profile_pinned_posts WHERE owner_id=actor;
  GET DIAGNOSTICS changed=ROW_COUNT;
  RETURN jsonb_build_object('unpinned',changed=1);
END $$;
CREATE FUNCTION private.get_profile_pinned_post(p_owner_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
  PERFORM private.b_active();
  IF p_owner_id IS NULL OR NOT private.can_read_profile(p_owner_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  SELECT private.post_json(p.id) INTO result FROM public.profile_pinned_posts pin JOIN public.posts p ON p.id=pin.post_id
    WHERE pin.owner_id=p_owner_id AND p.author_id=p_owner_id AND p.group_id IS NULL AND p.kind='original'
      AND p.state='published' AND private.can_read_post(p.id);
  RETURN result;
END $$;

-- Public wrappers preserve the established SECURITY INVOKER/PostgREST contract.
CREATE FUNCTION public.create_profile_album(p_title text,p_description text DEFAULT '',p_audience text DEFAULT 'friends') RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_profile_album($1,$2,$3) $$;
CREATE FUNCTION public.update_profile_album(p_album_id uuid,p_title text,p_description text,p_audience text,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_profile_album($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.delete_profile_album(p_album_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.delete_profile_album($1) $$;
CREATE FUNCTION public.list_profile_albums(p_owner_id uuid,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_profile_albums($1,$2,$3,$4) $$;
CREATE FUNCTION public.get_profile_album(p_album_id uuid,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_profile_album($1,$2,$3,$4) $$;
CREATE FUNCTION public.list_profile_photo_candidates(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_profile_photo_candidates($1,$2,$3) $$;
CREATE FUNCTION public.add_profile_album_photos(p_album_id uuid,p_source_post_id uuid,p_asset_ids uuid[]) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.add_profile_album_photos($1,$2,$3) $$;
CREATE FUNCTION public.remove_profile_album_photo(p_album_id uuid,p_item_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.remove_profile_album_photo($1,$2) $$;
CREATE FUNCTION public.set_profile_album_cover(p_album_id uuid,p_item_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_profile_album_cover($1,$2) $$;
CREATE FUNCTION public.list_profile_media(p_owner_id uuid,p_kind text,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 50) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_profile_media($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.set_profile_featured(p_source_post_id uuid,p_asset_id uuid DEFAULT NULL,p_position smallint DEFAULT 0) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_profile_featured($1,$2,$3) $$;
CREATE FUNCTION public.remove_profile_featured(p_item_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.remove_profile_featured($1) $$;
CREATE FUNCTION public.list_profile_featured(p_owner_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_profile_featured($1) $$;
CREATE FUNCTION public.set_profile_pinned_post(p_post_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_profile_pinned_post($1) $$;
CREATE FUNCTION public.clear_profile_pinned_post() RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.clear_profile_pinned_post() $$;
CREATE FUNCTION public.get_profile_pinned_post(p_owner_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_profile_pinned_post($1) $$;

REVOKE ALL ON FUNCTION private.can_read_profile_album(uuid),private.can_read_profile_album_item(uuid),private.remove_retired_profile_media_refs(),
 private.create_profile_album(text,text,text),private.update_profile_album(uuid,text,text,text,bigint),private.delete_profile_album(uuid),
 private.list_profile_albums(uuid,timestamptz,uuid,integer),private.get_profile_album(uuid,timestamptz,uuid,integer),
 private.list_profile_photo_candidates(timestamptz,uuid,integer),private.add_profile_album_photos(uuid,uuid,uuid[]),
 private.remove_profile_album_photo(uuid,uuid),private.set_profile_album_cover(uuid,uuid),
 private.list_profile_media(uuid,text,timestamptz,uuid,integer),private.set_profile_featured(uuid,uuid,smallint),
 private.remove_profile_featured(uuid),private.list_profile_featured(uuid),private.set_profile_pinned_post(uuid),
 private.clear_profile_pinned_post(),private.get_profile_pinned_post(uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.create_profile_album(text,text,text),private.update_profile_album(uuid,text,text,text,bigint),
 private.delete_profile_album(uuid),private.list_profile_albums(uuid,timestamptz,uuid,integer),
 private.get_profile_album(uuid,timestamptz,uuid,integer),private.list_profile_photo_candidates(timestamptz,uuid,integer),
 private.add_profile_album_photos(uuid,uuid,uuid[]),private.remove_profile_album_photo(uuid,uuid),
 private.set_profile_album_cover(uuid,uuid),private.list_profile_media(uuid,text,timestamptz,uuid,integer),
 private.set_profile_featured(uuid,uuid,smallint),private.remove_profile_featured(uuid),private.list_profile_featured(uuid),
 private.set_profile_pinned_post(uuid),private.clear_profile_pinned_post(),private.get_profile_pinned_post(uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.create_profile_album(text,text,text),public.update_profile_album(uuid,text,text,text,bigint),
 public.delete_profile_album(uuid),public.list_profile_albums(uuid,timestamptz,uuid,integer),
 public.get_profile_album(uuid,timestamptz,uuid,integer),public.list_profile_photo_candidates(timestamptz,uuid,integer),
 public.add_profile_album_photos(uuid,uuid,uuid[]),public.remove_profile_album_photo(uuid,uuid),
 public.set_profile_album_cover(uuid,uuid),public.list_profile_media(uuid,text,timestamptz,uuid,integer),
 public.set_profile_featured(uuid,uuid,smallint),public.remove_profile_featured(uuid),public.list_profile_featured(uuid),
 public.set_profile_pinned_post(uuid),public.clear_profile_pinned_post(),public.get_profile_pinned_post(uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.create_profile_album(text,text,text),public.update_profile_album(uuid,text,text,text,bigint),
 public.delete_profile_album(uuid),public.list_profile_albums(uuid,timestamptz,uuid,integer),
 public.get_profile_album(uuid,timestamptz,uuid,integer),public.list_profile_photo_candidates(timestamptz,uuid,integer),
 public.add_profile_album_photos(uuid,uuid,uuid[]),public.remove_profile_album_photo(uuid,uuid),
 public.set_profile_album_cover(uuid,uuid),public.list_profile_media(uuid,text,timestamptz,uuid,integer),
 public.set_profile_featured(uuid,uuid,smallint),public.remove_profile_featured(uuid),public.list_profile_featured(uuid),
 public.set_profile_pinned_post(uuid),public.clear_profile_pinned_post(),public.get_profile_pinned_post(uuid) TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;





