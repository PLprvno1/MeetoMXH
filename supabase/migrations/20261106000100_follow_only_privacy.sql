-- Task 36 / 037. FORWARD ONLY. Requires all migrations through 20261104000200.
-- Prepared locally; owner deployment required. No legacy content or tables are deleted.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.update_profile_with_location(text,text,uuid,uuid,text,text,bigint)') IS NULL
 OR to_regprocedure('private.c_actor()') IS NULL OR to_regprocedure('private.c_message(uuid)') IS NULL
 OR to_regprocedure('private.user_can_read_post(uuid,uuid)') IS NULL
 OR to_regprocedure('private.can_read_profile_album(uuid)') IS NULL
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='posts' AND column_name='is_reel') THEN
 RAISE EXCEPTION 'Task 36 prerequisites missing: inventory deployed migrations 001-036 before proceeding';
 END IF;
END $$;
ALTER TABLE public.profiles ADD COLUMN username text CHECK(username IS NULL OR username ~ '^[a-z0-9][a-z0-9._]{2,29}$');
CREATE UNIQUE INDEX profiles_username_unique ON public.profiles(username) WHERE username IS NOT NULL;
ALTER TABLE public.user_settings ADD COLUMN account_private boolean NOT NULL DEFAULT false;
-- Previously restricted profiles stay restricted. Pending requests are never auto-accepted.
UPDATE public.user_settings SET account_private=(profile_audience<>'public');
CREATE TABLE public.follow_requests (
  requester_id uuid NOT NULL REFERENCES public.profiles(id),
  recipient_id uuid NOT NULL REFERENCES public.profiles(id),
  state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','accepted','rejected','canceled')),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY(requester_id,recipient_id), CHECK(requester_id<>recipient_id)
);
CREATE INDEX follow_requests_received ON public.follow_requests(recipient_id,created_at DESC,requester_id) WHERE state='pending';
ALTER TABLE public.follow_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.follow_requests FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION private.social_target(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id
 WHERE p.id=pid AND p.onboarding_completed AND s.state='active'
 AND NOT EXISTS(SELECT 1 FROM public.system_roles r WHERE r.user_id=p.id AND r.role='system_admin'))
$$;
CREATE FUNCTION private.follows(viewer uuid,owner_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM public.user_follows f WHERE f.follower_id=viewer AND f.followed_id=owner_id)
$$;
CREATE FUNCTION private.account_content_allowed(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND NOT private.is_blocked(pid) AND private.social_target(pid)
 AND (pid=auth.uid() OR EXISTS(SELECT 1 FROM public.user_settings s WHERE s.user_id=pid
 AND (NOT s.account_private OR private.follows(auth.uid(),pid))))
$$;
-- Retire friendship authorization without destroying history. Legacy friends-only rows are owner-only.
CREATE OR REPLACE FUNCTION private.are_friends(other_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT false $$;
CREATE OR REPLACE FUNCTION private.can_read_group(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT false $$;
CREATE OR REPLACE FUNCTION private.group_member(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT false $$;
CREATE OR REPLACE FUNCTION private.group_manager(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT false $$;
CREATE OR REPLACE FUNCTION private.can_discover_group(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$ SELECT false $$;
-- Notification/mention fanout evaluates a different recipient; it must use the same privacy rules.
CREATE FUNCTION private.user_can_read_post_base(p_post_id uuid,p_user_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM public.posts p JOIN public.user_settings s ON s.user_id=p.author_id
 JOIN public.account_status viewer ON viewer.user_id=p_user_id AND viewer.state='active'
 JOIN public.profiles v ON v.id=viewer.user_id AND v.onboarding_completed
 WHERE p.id=p_post_id AND p.state='published' AND p.group_id IS NULL AND private.social_target(p.author_id)
 AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE (b.blocker_id=p.author_id AND b.blocked_id=p_user_id) OR (b.blocker_id=p_user_id AND b.blocked_id=p.author_id))
 AND (p.author_id=p_user_id OR ((NOT s.account_private OR private.follows(p_user_id,p.author_id))
 AND (p.audience='public' OR (p.audience='followers' AND private.follows(p_user_id,p.author_id))))))
$$;
CREATE OR REPLACE FUNCTION private.user_can_read_post(p_post_id uuid,p_user_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.user_can_read_post_base(p_post_id,p_user_id) AND EXISTS(SELECT 1 FROM public.posts p WHERE p.id=p_post_id
 AND (p.kind='original' OR p.author_id=p_user_id OR EXISTS(SELECT 1 FROM public.posts original WHERE original.id=p.original_post_id AND original.kind='original' AND private.user_can_read_post_base(original.id,p_user_id))))
$$;
REVOKE ALL ON FUNCTION private.user_can_read_post_base(uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
CREATE OR REPLACE FUNCTION private.can_read_profile(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT (pid=auth.uid() AND private.account_ok()) OR
 (private.is_active() AND private.social_target(pid) AND NOT private.is_blocked(pid))
$$;
CREATE OR REPLACE FUNCTION private.can_read_post_base(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.posts p
 WHERE p.id=pid AND p.state='published' AND p.group_id IS NULL
 AND private.account_content_allowed(p.author_id)
 AND (p.author_id=auth.uid() OR p.audience='public' OR (p.audience='followers' AND private.follows(auth.uid(),p.author_id))))
$$;
CREATE OR REPLACE FUNCTION private.can_read_story(sid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.stories s
 WHERE s.id=sid AND private.account_content_allowed(s.author_id)
 AND ((s.state='archived' AND s.author_id=auth.uid()) OR (s.state='active' AND s.expires_at>statement_timestamp()
 AND (s.author_id=auth.uid() OR s.audience='public' OR (s.audience='followers' AND private.follows(auth.uid(),s.author_id))))))
$$;
CREATE OR REPLACE FUNCTION private.can_read_profile_album(p_album_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT EXISTS(SELECT 1 FROM public.profile_albums a WHERE a.id=p_album_id AND private.account_content_allowed(a.owner_id)
 AND (a.owner_id=auth.uid() OR a.audience='public' OR (a.audience='followers' AND private.follows(auth.uid(),a.owner_id))))
$$;

ALTER TABLE public.posts DROP CONSTRAINT posts_audience_check;
ALTER TABLE public.posts ADD CONSTRAINT posts_audience_check CHECK(audience IN ('public','followers','private','friends','group'));
ALTER TABLE public.stories DROP CONSTRAINT stories_audience_check;
ALTER TABLE public.stories ADD CONSTRAINT stories_audience_check CHECK(audience IN ('public','followers','private','friends'));
ALTER TABLE public.stories ALTER COLUMN audience SET DEFAULT 'private';
ALTER TABLE public.profile_albums DROP CONSTRAINT profile_albums_audience_check;
ALTER TABLE public.profile_albums ADD CONSTRAINT profile_albums_audience_check CHECK(audience IN ('public','followers','private','friends'));
ALTER TABLE public.profile_albums ALTER COLUMN audience SET DEFAULT 'private';
ALTER TABLE public.user_settings DROP CONSTRAINT user_settings_last_seen_audience_check;
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_last_seen_audience_check CHECK(last_seen_audience IN ('public','followers','private','friends'));
ALTER TABLE public.profile_private DROP CONSTRAINT profile_private_location_audience_check;
ALTER TABLE public.profile_private ADD CONSTRAINT profile_private_location_audience_check CHECK(location_audience IN ('public','followers','private','friends'));

CREATE OR REPLACE FUNCTION private.get_profile(p_target uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF p_target=auth.uid() THEN RETURN private.self_profile(); END IF;
 IF NOT private.can_read_profile(p_target) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 RETURN (SELECT jsonb_build_object('profile_id',p.id,'display_name',p.display_name,'username',p.username,'avatar_asset_id',p.avatar_asset_id,
 'avatar_preset',p.public_avatar_preset,'bio',CASE WHEN private.account_content_allowed(p.id) THEN p.bio END,
 'cover_asset_id',CASE WHEN private.account_content_allowed(p.id) THEN p.cover_asset_id END,
 'account_private',s.account_private,'content_allowed',private.account_content_allowed(p.id),
 'location',CASE WHEN private.account_content_allowed(p.id) AND (d.location_audience='public' OR
 (d.location_audience='followers' AND private.follows(auth.uid(),p.id))) THEN d.current_location END,'version',p.version)
 FROM public.profiles p JOIN public.user_settings s ON s.user_id=p.id JOIN public.profile_private d ON d.user_id=p.id WHERE p.id=p_target);
END $$;

CREATE OR REPLACE FUNCTION private.get_follow_counts(p_target uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF NOT private.can_read_profile(p_target) OR NOT private.social_target(p_target) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 RETURN jsonb_build_object('target_id',p_target,'followers_count',(SELECT count(*) FROM public.user_follows f WHERE followed_id=p_target AND private.social_target(f.follower_id)),
 'following_count',(SELECT count(*) FROM public.user_follows f WHERE follower_id=p_target AND private.social_target(f.followed_id)),
 'following',private.follows(auth.uid(),p_target),'requested',EXISTS(SELECT 1 FROM public.follow_requests
 WHERE requester_id=auth.uid() AND recipient_id=p_target AND state='pending'));
END $$;
CREATE OR REPLACE FUNCTION private.set_follow(p_target uuid,p_follow boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); restricted boolean;
BEGIN
 IF p_target IS NULL OR p_target=actor OR p_follow IS NULL OR NOT private.social_target(actor) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_follow'; END IF;
 PERFORM private.c_pair(actor,p_target);
 IF NOT private.social_target(p_target) OR private.is_blocked(p_target) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 SELECT account_private INTO restricted FROM public.user_settings WHERE user_id=p_target FOR UPDATE;
 IF NOT p_follow THEN
   DELETE FROM public.user_follows WHERE follower_id=actor AND followed_id=p_target;
   UPDATE public.follow_requests SET state='canceled',updated_at=statement_timestamp() WHERE requester_id=actor AND recipient_id=p_target AND state='pending';
 ELSIF NOT private.follows(actor,p_target) THEN
   IF restricted THEN
     INSERT INTO public.follow_requests(requester_id,recipient_id) VALUES(actor,p_target)
     ON CONFLICT(requester_id,recipient_id) DO UPDATE SET state='pending',created_at=statement_timestamp(),updated_at=statement_timestamp()
       WHERE follow_requests.state<>'pending';
   ELSE
     INSERT INTO public.user_follows(follower_id,followed_id) VALUES(actor,p_target) ON CONFLICT DO NOTHING;
     -- A fresh explicit follow to a public account takes effect immediately.
     -- Only this pair's stale request is canceled; other requests are untouched.
     UPDATE public.follow_requests SET state='canceled',updated_at=statement_timestamp()
       WHERE requester_id=actor AND recipient_id=p_target AND state='pending';
   END IF;
 END IF;
 RETURN private.get_follow_counts(p_target);
END $$;
CREATE FUNCTION private.resolve_follow_request(p_requester uuid,p_accept boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.follow_requests;
BEGIN
 IF p_requester IS NULL OR p_accept IS NULL OR NOT private.social_target(actor) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_follow_request'; END IF;
 PERFORM private.c_pair(actor,p_requester);
 IF private.is_blocked(p_requester) OR NOT private.social_target(p_requester) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 SELECT * INTO r FROM public.follow_requests WHERE requester_id=p_requester AND recipient_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='request_unavailable'; END IF;
 IF r.state<>'pending' THEN RETURN jsonb_build_object('state',r.state); END IF;
 IF p_accept THEN INSERT INTO public.user_follows(follower_id,followed_id) VALUES(p_requester,actor) ON CONFLICT DO NOTHING; END IF;
 UPDATE public.follow_requests SET state=CASE WHEN p_accept THEN 'accepted' ELSE 'rejected' END,updated_at=statement_timestamp()
 WHERE requester_id=p_requester AND recipient_id=actor;
 RETURN jsonb_build_object('state',CASE WHEN p_accept THEN 'accepted' ELSE 'rejected' END);
END $$;
CREATE FUNCTION private.remove_follower(p_follower uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
 PERFORM private.c_pair(actor,p_follower);
 DELETE FROM public.user_follows WHERE follower_id=p_follower AND followed_id=actor;
 RETURN private.get_follow_counts(actor);
END $$;
CREATE FUNCTION private.list_follow_requests(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.profile_id DESC) FROM (
 SELECT p.id profile_id,p.display_name,p.avatar_asset_id,p.public_avatar_preset avatar_preset,r.created_at
 FROM public.follow_requests r JOIN public.profiles p ON p.id=r.requester_id WHERE r.recipient_id=auth.uid() AND r.state='pending'
 AND private.can_read_profile(p.id) AND (p_after_time IS NULL OR (r.created_at,p.id)<(p_after_time,p_after_id))
 ORDER BY r.created_at DESC,p.id DESC LIMIT p_limit)q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.update_privacy_settings(p_profile_audience text,p_last_seen_audience text,p_location_audience text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
 IF p_profile_audience IS NULL OR p_profile_audience NOT IN ('public','private') OR p_last_seen_audience IS NULL OR p_last_seen_audience NOT IN ('public','followers','private')
 OR p_location_audience IS NULL OR p_location_audience NOT IN ('public','followers','private') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_privacy_settings'; END IF;
 UPDATE public.user_settings SET account_private=(p_profile_audience='private'),profile_audience=p_profile_audience,
 last_seen_audience=p_last_seen_audience,updated_at=statement_timestamp() WHERE user_id=actor;
 UPDATE public.profile_private SET location_audience=p_location_audience,updated_at=statement_timestamp() WHERE user_id=actor;
 RETURN jsonb_build_object('account_private',p_profile_audience='private','profile_audience',p_profile_audience,
 'last_seen_audience',p_last_seen_audience,'location_audience',p_location_audience);
END $$;
CREATE OR REPLACE FUNCTION private.list_blocked_users() RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',b.blocked_id,'display_name',p.display_name,'username',p.username,
 'avatar_asset_id',p.avatar_asset_id,'avatar_preset',p.public_avatar_preset,'created_at',b.created_at) ORDER BY b.created_at DESC)
 FROM public.user_blocks b JOIN public.profiles p ON p.id=b.blocked_id WHERE b.blocker_id=auth.uid()),'[]'::jsonb);
END $$;

-- Normalization is deterministic and does not require installing an extension in Cloud.
CREATE FUNCTION private.social_search_text(value text) RETURNS text LANGUAGE sql IMMUTABLE SET search_path='' AS $$
 SELECT translate(lower(value),'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ',
 'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd')
$$;
CREATE OR REPLACE FUNCTION private.search_advanced_people(p_query text,p_after_name text,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF p_query IS NULL OR length(btrim(p_query)) NOT BETWEEN 2 AND 100 OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50 OR (p_after_name IS NULL)<>(p_after_id IS NULL) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_search'; END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.cursor_name,q.profile_id) FROM (
 SELECT p.id profile_id,p.display_name,p.username,p.avatar_asset_id,p.public_avatar_preset avatar_preset,lower(p.display_name) cursor_name,
 private.follows(auth.uid(),p.id) following FROM public.profiles p
 WHERE p.id<>auth.uid() AND private.can_read_profile(p.id) AND private.social_target(p.id)
 AND NOT EXISTS(SELECT 1 FROM regexp_split_to_table(private.social_search_text(btrim(p_query,' @')),'\s+') token
 WHERE strpos(private.social_search_text(concat_ws(' ',p.display_name,p.username)),token)=0)
 AND (p_after_name IS NULL OR (lower(p.display_name),p.id)>(p_after_name,p_after_id)) ORDER BY lower(p.display_name),p.id LIMIT p_limit)q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.search_profiles(p_prefix text,p_after_name text,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.search_advanced_people(p_prefix,p_after_name,p_after_id,p_limit)
$$;
CREATE FUNCTION private.get_explore_posts(p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 RETURN coalesce((SELECT jsonb_agg(private.post_json(q.id) ORDER BY q.created_at DESC,q.id DESC) FROM (
 SELECT p.id,p.created_at FROM public.posts p WHERE private.can_read_post(p.id) AND EXISTS(SELECT 1 FROM public.post_media m WHERE m.post_id=p.id AND private.can_read_media(m.asset_id))
 AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id)) ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit)q),'[]'::jsonb);
END $$;
CREATE FUNCTION public.get_explore_posts(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_explore_posts($1,$2,$3) $$;
REVOKE ALL ON FUNCTION private.get_explore_posts(timestamptz,uuid,integer),public.get_explore_posts(timestamptz,uuid,integer) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION private.get_explore_posts(timestamptz,uuid,integer),public.get_explore_posts(timestamptz,uuid,integer) TO authenticated;

CREATE FUNCTION public.resolve_follow_request(p_requester uuid,p_accept boolean) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_follow_request($1,$2) $$;
CREATE FUNCTION public.remove_follower(p_follower uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.remove_follower($1) $$;
CREATE FUNCTION public.list_follow_requests(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_follow_requests($1,$2,$3) $$;
CREATE FUNCTION private.update_profile_public_v36(p_display_name text,p_bio text,p_username text,p_avatar_asset_id uuid,p_cover_asset_id uuid,p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); handle text:=nullif(lower(btrim(p_username)),'');
BEGIN
 IF handle IS NOT NULL AND handle !~ '^[a-z0-9][a-z0-9._]{2,29}$' THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_username'; END IF;
 PERFORM private.update_profile_with_location(p_display_name,p_bio,p_avatar_asset_id,p_cover_asset_id,p_location,p_location_audience,p_expected_version);
 UPDATE public.profiles SET username=handle,version=version+1,updated_at=statement_timestamp() WHERE id=actor AND username IS DISTINCT FROM handle;
 RETURN private.self_profile();
 EXCEPTION WHEN unique_violation THEN RAISE SQLSTATE 'PT409' USING MESSAGE='username_taken';
END $$;
CREATE FUNCTION public.update_profile_public_v36(p_display_name text,p_bio text,p_username text,p_avatar_asset_id uuid,p_cover_asset_id uuid,p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_profile_public_v36($1,$2,$3,$4,$5,$6,$7,$8) $$;
REVOKE ALL ON FUNCTION private.update_profile_public_v36(text,text,text,uuid,uuid,text,text,bigint),public.update_profile_public_v36(text,text,text,uuid,uuid,text,text,bigint) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION private.update_profile_public_v36(text,text,text,uuid,uuid,text,text,bigint),public.update_profile_public_v36(text,text,text,uuid,uuid,text,text,bigint) TO authenticated;
REVOKE ALL ON FUNCTION private.social_target(uuid),private.follows(uuid,uuid),private.account_content_allowed(uuid),private.social_search_text(text),
 private.resolve_follow_request(uuid,boolean),private.remove_follower(uuid),private.list_follow_requests(timestamptz,uuid,integer),
 public.resolve_follow_request(uuid,boolean),public.remove_follower(uuid),public.list_follow_requests(timestamptz,uuid,integer) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.resolve_follow_request(uuid,boolean),private.remove_follower(uuid),private.list_follow_requests(timestamptz,uuid,integer),
 public.resolve_follow_request(uuid,boolean),public.remove_follower(uuid),public.list_follow_requests(timestamptz,uuid,integer) TO authenticated;
-- Additional audited overrides and final COMMIT appended below.

-- Preserve existing RPC signatures, idempotency, validation, and media pipelines.
CREATE OR REPLACE FUNCTION private.create_post(p_body text,p_audience text,p_assets uuid[],p_client_request_id uuid,p_original uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; h text;
BEGIN
 IF p_body IS NULL OR length(p_body)>20000 OR p_audience IS NULL OR p_audience NOT IN ('public','followers','private') OR p_client_request_id IS NULL OR p_assets IS NULL
 OR (p_original IS NULL AND btrim(p_body)='' AND cardinality(p_assets)=0) OR (p_original IS NOT NULL AND cardinality(p_assets)>0) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post'; END IF;
 h:=encode(sha256(convert_to(jsonb_build_array(p_body,p_audience,p_assets,p_original)::text,'UTF8')),'hex');
 SELECT * INTO p FROM public.posts WHERE author_id=actor AND client_request_id=p_client_request_id;
 IF FOUND THEN
  IF p.request_hash<>h THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF p.state<>'published' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='post_unavailable'; END IF;
  RETURN private.post_json(p.id); END IF;
 IF p_original IS NOT NULL THEN
  PERFORM 1 FROM public.posts WHERE id=p_original AND kind='original' AND group_id IS NULL AND private.can_read_post(id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='source_unavailable'; END IF;
 END IF;
 INSERT INTO public.posts(author_id,client_request_id,request_hash,body,audience,kind,original_post_id)
 VALUES(actor,p_client_request_id,h,p_body,p_audience,CASE WHEN p_original IS NULL THEN 'original' ELSE 'share' END,p_original) RETURNING * INTO p;
 PERFORM private.attach_post(p.id,actor,p_assets);
 IF p_original IS NOT NULL THEN UPDATE public.posts SET shares_count=shares_count+1 WHERE id=p_original; END IF;
 RETURN private.post_json(p.id);
END $$;
CREATE OR REPLACE FUNCTION private.edit_post(p_post_id uuid,p_body text,p_audience text,p_assets uuid[],p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; previous uuid[]; a uuid;
BEGIN
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND state='published' AND group_id IS NULL FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p_body IS NULL OR length(p_body)>20000 OR p_audience IS NULL OR p_audience NOT IN ('public','followers','private') OR p_assets IS NULL
 OR (p.kind='original' AND btrim(p_body)='' AND cardinality(p_assets)=0) OR (p.kind='share' AND cardinality(p_assets)>0) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post'; END IF;
 SELECT coalesce(array_agg(asset_id ORDER BY position),'{}'::uuid[]) INTO previous FROM public.post_media WHERE post_id=p.id;
 IF (p.body,p.audience,previous) IS NOT DISTINCT FROM (p_body,p_audience,p_assets) THEN RETURN private.post_json(p.id); END IF;
 IF p_expected_version IS NULL OR p.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 DELETE FROM public.post_media WHERE post_id=p.id;
 PERFORM private.attach_post(p.id,actor,p_assets);
 FOREACH a IN ARRAY previous LOOP
  IF NOT a=ANY(p_assets) THEN PERFORM private.retire_asset(a); END IF;
 END LOOP;
 UPDATE public.posts SET body=p_body,audience=p_audience,version=version+1,updated_at=statement_timestamp() WHERE id=p.id;
 RETURN private.post_json(p.id);
END $$;
CREATE OR REPLACE FUNCTION private.create_story(p_media_asset_id uuid,p_caption text,p_audience text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.media_assets; s public.stories;
BEGIN
  IF p_media_asset_id IS NULL OR p_client_request_id IS NULL OR p_caption IS NULL
     OR char_length(p_caption)>2000 OR p_caption ~ '[[:cntrl:]]'
     OR p_audience IS NULL OR p_audience NOT IN ('public','followers','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story'; END IF;
  SELECT * INTO s FROM public.stories WHERE author_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF s.media_asset_id<>p_media_asset_id OR s.caption<>p_caption OR s.audience<>p_audience THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
    RETURN private.story_json(s.id);
  END IF;
  SELECT * INTO a FROM public.media_assets WHERE id=p_media_asset_id FOR UPDATE;
  IF NOT FOUND OR a.owner_id IS DISTINCT FROM actor OR a.purpose<>'story' OR a.state<>'ready'
     OR a.mime_type NOT IN ('image/png','image/jpeg','image/webp','video/mp4') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_story_asset'; END IF;
  INSERT INTO public.stories(author_id,media_asset_id,caption,audience,client_request_id)
    VALUES(actor,a.id,btrim(p_caption),p_audience,p_client_request_id) RETURNING * INTO s;
  RETURN private.story_json(s.id);
END $$;
CREATE OR REPLACE FUNCTION private.create_reel(p_video_asset_id uuid,p_cover_asset_id uuid,p_caption text,p_audience text,
  p_duration_ms integer,p_cover_position_ms integer,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); existing_id uuid; existing_reel boolean; existing_hash text;
  state_value text; fingerprint text; result jsonb; reel_id uuid; locked_assets integer;
BEGIN
  PERFORM private.b_active();
  IF p_video_asset_id IS NULL OR p_caption IS NULL OR char_length(p_caption)>2000
     OR p_audience IS NULL OR p_audience NOT IN ('public','followers','private')
     OR p_duration_ms IS NULL OR p_duration_ms NOT BETWEEN 1 AND 60000
     OR p_cover_position_ms IS NULL OR p_cover_position_ms<0 OR p_cover_position_ms>p_duration_ms
     OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel';
  END IF;
  fingerprint:=encode(sha256(convert_to(jsonb_build_array('reel',p_video_asset_id,p_cover_asset_id,p_caption,p_audience,
    p_duration_ms,p_cover_position_ms)::text,'UTF8')),'hex');
  PERFORM pg_advisory_xact_lock(hashtextextended(actor::text||':create-reel:'||p_client_request_id::text,0));
  SELECT p.id,p.is_reel,p.reel_request_hash,p.state INTO existing_id,existing_reel,existing_hash,state_value
    FROM public.posts p WHERE p.author_id=actor AND p.client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF existing_reel AND existing_hash=fingerprint AND state_value='published' THEN
      RETURN private.reel_card(existing_id);
    END IF;
    RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
  END IF;
  -- Lock video and cover in a stable UUID order before either can be attached
  -- or reused by another request in the same transaction.
  PERFORM 1 FROM public.media_assets a
    WHERE a.id=p_video_asset_id OR a.id=p_cover_asset_id
    ORDER BY a.id FOR UPDATE;
  GET DIAGNOSTICS locked_assets=ROW_COUNT;
  IF locked_assets<>(CASE WHEN p_cover_asset_id IS NULL THEN 1 ELSE 2 END) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_media';
  END IF;
  -- An uploaded READY asset is not yet readable through can_read_media until
  -- it is attached. Validate ownership and the existing direct-upload metadata
  -- contract here; do not grant temporary read access to unattached media.
  PERFORM 1 FROM public.media_assets a
    JOIN storage.objects o ON o.bucket_id=a.bucket_id AND o.name=a.object_path
    JOIN storage.buckets b ON b.id=a.bucket_id
    WHERE a.id=p_video_asset_id AND a.owner_id=actor AND a.purpose='post'
      AND a.bucket_id='social-media' AND a.mime_type='video/mp4' AND a.state='ready'
      AND a.size_bytes BETWEEN 1 AND 52428800
      AND a.metadata->>'upload_flow'='direct_storage_v1'
      AND a.metadata->>'validation'='storage_object_metadata'
      AND a.metadata->>'storage_confirmed_at' IS NOT NULL
      AND a.metadata->>'object_id'=o.id::text
      AND a.object_path=actor::text||'/'||a.id::text||'/original.mp4'
      AND o.owner_id=actor::text AND o.metadata->>'mimetype'=a.mime_type
      AND CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$'
        THEN (o.metadata->>'size')::bigint ELSE NULL END=a.size_bytes
      AND (b.file_size_limit IS NULL OR a.size_bytes<=b.file_size_limit)
      AND b.allowed_mime_types IS NOT NULL AND a.mime_type=ANY(b.allowed_mime_types)
      AND b.public IS FALSE
      AND NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=a.id)
      AND NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id);
  IF NOT FOUND THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_video'; END IF;
  IF p_cover_asset_id IS NOT NULL THEN
    PERFORM 1 FROM public.media_assets a
      JOIN storage.objects o ON o.bucket_id=a.bucket_id AND o.name=a.object_path
      JOIN storage.buckets b ON b.id=a.bucket_id
      WHERE a.id=p_cover_asset_id AND a.owner_id=actor
      AND a.purpose='post' AND a.bucket_id='social-media' AND a.mime_type IN ('image/jpeg','image/png','image/webp')
      AND a.state='ready' AND a.size_bytes BETWEEN 1 AND 10485760
      AND a.metadata->>'upload_flow'='direct_storage_v1'
      AND a.metadata->>'validation'='storage_object_metadata'
      AND a.metadata->>'storage_confirmed_at' IS NOT NULL
      AND a.metadata->>'object_id'=o.id::text
      AND a.object_path=actor::text||'/'||a.id::text||'/original.'||
        CASE a.mime_type WHEN 'image/jpeg' THEN 'jpg' WHEN 'image/png' THEN 'png' ELSE 'webp' END
      AND o.owner_id=actor::text AND o.metadata->>'mimetype'=a.mime_type
      AND CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$'
        THEN (o.metadata->>'size')::bigint ELSE NULL END=a.size_bytes
      AND (b.file_size_limit IS NULL OR a.size_bytes<=b.file_size_limit)
      AND b.allowed_mime_types IS NOT NULL AND a.mime_type=ANY(b.allowed_mime_types)
      AND b.public IS FALSE
      AND NOT EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.asset_id=a.id)
      AND NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id);
    IF NOT FOUND THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reel_cover'; END IF;
  END IF;
  result:=private.create_post(p_caption,p_audience,ARRAY[p_video_asset_id],p_client_request_id,NULL);
  reel_id:=(result->>'id')::uuid;
  UPDATE public.posts SET is_reel=true,reel_duration_ms=p_duration_ms,
      reel_cover_position_ms=p_cover_position_ms,reel_cover_asset_id=p_cover_asset_id,reel_request_hash=fingerprint
    WHERE id=reel_id AND author_id=actor AND kind='original' AND group_id IS NULL AND state='published';
  IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='reel_creation_conflict'; END IF;
  RETURN private.reel_card(reel_id);
END $$;
CREATE OR REPLACE FUNCTION private.create_profile_album(p_title text,p_description text,p_audience text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); item public.profile_albums;
BEGIN
  PERFORM private.b_active();
  IF p_title IS NULL OR char_length(btrim(p_title)) NOT BETWEEN 1 AND 100
     OR p_description IS NULL OR char_length(p_description)>1000
     OR p_audience IS NULL OR p_audience NOT IN ('public','followers','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_album'; END IF;
  INSERT INTO public.profile_albums(owner_id,title,description,audience)
    VALUES(actor,btrim(p_title),btrim(p_description),p_audience) RETURNING * INTO item;
  RETURN jsonb_build_object('id',item.id,'title',item.title,'description',item.description,
    'audience',item.audience,'version',item.version,'created_at',item.created_at);
END $$;
CREATE OR REPLACE FUNCTION private.update_profile_album(p_album_id uuid,p_title text,p_description text,p_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); item public.profile_albums;
BEGIN
  IF p_album_id IS NULL OR p_title IS NULL OR char_length(btrim(p_title)) NOT BETWEEN 1 AND 100
     OR p_description IS NULL OR char_length(p_description)>1000 OR p_audience IS NULL
     OR p_audience NOT IN ('public','followers','private') OR p_expected_version IS NULL OR p_expected_version<1 THEN
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
CREATE OR REPLACE FUNCTION private.update_profile_with_location(p_display_name text,p_bio text,p_avatar_asset_id uuid,
  p_cover_asset_id uuid,p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); before_location text; before_audience text; current_version bigint;
BEGIN
  IF NOT private.valid_location(p_location) OR p_location_audience IS NULL
     OR p_location_audience NOT IN ('public','followers','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_location';
  END IF;
  PERFORM private.lock_profile();
  SELECT version INTO current_version FROM public.profiles WHERE id=actor;
  IF p_expected_version IS NULL OR current_version<>p_expected_version THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict';
  END IF;
  SELECT current_location,location_audience INTO before_location,before_audience
    FROM public.profile_private WHERE user_id=actor FOR UPDATE;
  PERFORM private.update_profile(p_display_name,p_bio,p_avatar_asset_id,p_cover_asset_id,p_expected_version);
  SELECT version INTO current_version FROM public.profiles WHERE id=actor;
  UPDATE public.profile_private SET current_location=p_location,location_audience=p_location_audience,
    updated_at=statement_timestamp() WHERE user_id=actor;
  IF (before_location IS DISTINCT FROM p_location OR before_audience IS DISTINCT FROM p_location_audience)
     AND current_version=p_expected_version THEN
    UPDATE public.profiles SET version=version+1,updated_at=statement_timestamp() WHERE id=actor;
  END IF;
  RETURN private.self_profile();
END $$;
CREATE OR REPLACE FUNCTION private.complete_onboarding_with_location(p_display_name text,p_birth_date date,p_gender text,
  p_location text,p_location_audience text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); old_version bigint; new_version bigint;
  old_location text; old_audience text;
BEGIN
  IF NOT private.valid_location(p_location) OR p_location_audience IS NULL
     OR p_location_audience NOT IN ('public','followers','private') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_location';
  END IF;
  PERFORM private.lock_profile(false);
  SELECT version INTO old_version FROM public.profiles WHERE id=actor;
  IF p_expected_version IS NULL OR old_version<>p_expected_version THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict';
  END IF;
  SELECT current_location,location_audience INTO old_location,old_audience
    FROM public.profile_private WHERE user_id=actor FOR UPDATE;
  PERFORM private.complete_onboarding(p_display_name,p_birth_date,p_gender,p_expected_version);
  UPDATE public.profile_private SET current_location=p_location,location_audience=p_location_audience,
    updated_at=statement_timestamp() WHERE user_id=actor;
  SELECT version INTO new_version FROM public.profiles WHERE id=actor;
  IF (old_location IS DISTINCT FROM p_location OR old_audience IS DISTINCT FROM p_location_audience)
     AND new_version=old_version THEN
    UPDATE public.profiles SET version=version+1,updated_at=statement_timestamp() WHERE id=actor;
  END IF;
  RETURN private.self_profile();
END $$;
CREATE OR REPLACE FUNCTION private.self_profile() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); result jsonb; status text;
BEGIN
  SELECT state INTO status FROM public.account_status WHERE user_id=actor;
  IF status IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  IF status<>'active' THEN RETURN jsonb_build_object('profile_id',actor,'account_state',status); END IF;
  SELECT jsonb_build_object('profile_id',p.id,'username',p.username,'display_name',p.display_name,'bio',p.bio,
    'avatar_asset_id',p.avatar_asset_id,'cover_asset_id',p.cover_asset_id,
    'avatar_preset',p.public_avatar_preset,'onboarding_completed',p.onboarding_completed,'version',p.version,
    'account_state',status,'birth_date',d.birth_date,'gender',d.gender,'location',d.current_location,
    'location_audience',d.location_audience,
    'settings',jsonb_build_object('account_private',s.account_private,'profile_audience',CASE WHEN s.account_private THEN 'private' ELSE 'public' END,'last_seen_audience',s.last_seen_audience,
      'notifications_enabled',s.notifications_enabled,'notification_preferences',s.notification_preferences),
    'email_verified',EXISTS(SELECT 1 FROM auth.users u WHERE u.id=actor AND u.email_confirmed_at IS NOT NULL))
  INTO result FROM public.profiles p JOIN public.profile_private d ON d.user_id=p.id
    JOIN public.user_settings s ON s.user_id=p.id WHERE p.id=actor;
  IF result IS NULL THEN RAISE SQLSTATE 'PT409' USING MESSAGE='bootstrap_required'; END IF;
  RETURN result;
END $$;
CREATE OR REPLACE FUNCTION private.post_json(pid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',p.id,'author_id',p.author_id,'group_id',p.group_id,'state',p.state,
 'body',p.body,'audience',p.audience,'kind',p.kind,'is_reel',p.is_reel,
 'original_post_id',CASE WHEN private.can_read_post_base(p.original_post_id) THEN p.original_post_id END,
 'original_available',p.kind='share' AND private.can_read_post_base(p.original_post_id),
 'author',CASE WHEN private.can_read_profile(p.author_id) THEN (SELECT jsonb_build_object('id',u.id,'display_name',u.display_name,'avatar_asset_id',u.avatar_asset_id,
   'avatar_preset',u.public_avatar_preset) FROM public.profiles u WHERE u.id=p.author_id) END,
 'reactions_count',p.reactions_count,'comments_count',p.comments_count,'shares_count',p.shares_count,
 'my_reaction',(SELECT r.reaction FROM public.post_reactions r WHERE r.post_id=p.id AND r.user_id=auth.uid()),
 'is_saved',EXISTS(SELECT 1 FROM public.saved_posts s WHERE s.user_id=auth.uid() AND s.post_id=p.id),
 'version',p.version,'created_at',p.created_at,'updated_at',p.updated_at,
 'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',m.asset_id,'position',m.position,'mime_type',a.mime_type) ORDER BY m.position)
   FROM public.post_media m JOIN public.media_assets a ON a.id=m.asset_id
   WHERE m.post_id=p.id AND private.can_read_media(m.asset_id)),'[]'::jsonb),
 'mentions',coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',CASE WHEN private.can_read_profile(m.mentioned_user_id) OR p.author_id=auth.uid() THEN m.mentioned_user_id END,
   'label',CASE WHEN private.can_read_profile(m.mentioned_user_id) THEN m.display_label ELSE '@Meeto user' END,
   'start_utf16',m.span_start_utf16,'end_utf16',m.span_end_utf16) ORDER BY m.span_start_utf16)
   FROM public.content_mentions m WHERE m.content_type='post' AND m.content_id=p.id),'[]'::jsonb),
 'tags',coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',CASE WHEN private.can_read_profile(t.tagged_user_id) OR t.tagged_by=auth.uid() THEN t.tagged_user_id END,
   'display_name',CASE WHEN private.can_read_profile(t.tagged_user_id) THEN t.display_name_snapshot ELSE 'Meeto user' END,
   'state',t.state)
   ORDER BY t.created_at,t.tagged_user_id) FROM public.post_user_tags t WHERE t.post_id=p.id
     AND (t.state='accepted' OR t.tagged_by=auth.uid())),'[]'::jsonb))
 FROM public.posts p WHERE p.id=pid AND private.can_read_post(p.id)
$$;
CREATE OR REPLACE FUNCTION private.get_feed(p_author uuid,p_mode text,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_mode IS NULL OR p_mode NOT IN ('all','following') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mode'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.post_json(q.id) ORDER BY q.created_at DESC,q.id DESC) FROM (
 SELECT p.id,p.created_at FROM public.posts p WHERE p.group_id IS NULL AND private.can_read_post(p.id)
 AND (p_author IS NULL OR p.author_id=p_author) AND (p_mode='all' OR p.author_id=auth.uid() OR private.follows(auth.uid(),p.author_id))
 AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id)) ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION public.create_profile_album(p_title text,p_description text DEFAULT '',p_audience text DEFAULT 'private') RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_profile_album($1,$2,$3) $$;
CREATE OR REPLACE FUNCTION private.can_read_media(aid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.media_assets a WHERE a.id=aid AND
 ((a.state='pending' AND a.owner_id=auth.uid() AND a.created_at>now()-interval '15 minutes') OR (a.state='ready' AND (
   EXISTS(SELECT 1 FROM public.profiles p WHERE ((p.avatar_asset_id=a.id AND a.purpose='avatar') OR (p.cover_asset_id=a.id AND a.purpose='cover')) AND a.owner_id=p.id AND private.can_read_profile(p.id) AND (a.purpose='avatar' OR private.account_content_allowed(p.id)))
   OR EXISTS(SELECT 1 FROM public.groups g WHERE false AND g.cover_asset_id=a.id AND a.bucket_id='group-media' AND a.purpose='group' AND g.state='active' AND ((g.privacy IN ('public','private') AND g.listed) OR private.group_member(g.id)))
   OR EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id WHERE pm.asset_id=a.id AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(pm.post_id))
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id AND p.is_reel
     AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(p.id))
   OR EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND a.purpose='story' AND a.owner_id=s.author_id AND private.can_read_story(s.id))
   OR EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id WHERE mm.asset_id=a.id AND a.purpose='message' AND a.owner_id=m.sender_id AND m.state='sent' AND private.can_read_message(m.id))))));
$$;
CREATE OR REPLACE FUNCTION private.list_follows(p_target uuid,p_direction text,p_after_time timestamptz,p_after_id uuid,p_limit integer)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_direction IS NULL OR p_direction NOT IN ('followers','following') OR p_target IS NULL
     OR NOT private.can_read_profile(p_target) OR private.is_blocked(p_target) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  IF NOT private.account_content_allowed(p_target) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='follow_required'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('profile_id',q.id,'display_name',q.display_name,
      'avatar_asset_id',q.avatar_asset_id,'avatar_preset',q.public_avatar_preset,'created_at',q.created_at,
      'following',EXISTS(SELECT 1 FROM public.user_follows x WHERE x.follower_id=actor AND x.followed_id=q.id))
      ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset,f.created_at
      FROM public.user_follows f
      JOIN public.profiles p ON p.id=CASE WHEN p_direction='followers' THEN f.follower_id ELSE f.followed_id END
      JOIN public.account_status s ON s.user_id=p.id AND s.state='active'
      WHERE CASE WHEN p_direction='followers' THEN f.followed_id ELSE f.follower_id END=p_target
        AND p.onboarding_completed AND private.can_read_profile(p.id) AND NOT private.is_blocked(p.id)
        AND (p_after_time IS NULL OR (f.created_at,p.id)<(p_after_time,p_after_id))
      ORDER BY f.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;
ALTER TABLE public.user_settings DROP CONSTRAINT user_settings_tagging_policy_check;
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_tagging_policy_check CHECK(tagging_policy IN ('everyone','followers','nobody','friends'));
UPDATE public.user_settings SET tagging_policy='nobody' WHERE tagging_policy='friends';
ALTER TABLE public.user_settings ALTER COLUMN tagging_policy SET DEFAULT 'followers';
CREATE OR REPLACE FUNCTION private.sync_post_tags(p_post_id uuid,p_users uuid[]) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); uid uuid; policy text; review_required boolean; display_name text; post_group uuid;
BEGIN
  IF p_users IS NULL OR cardinality(p_users)>10 OR array_position(p_users,NULL) IS NOT NULL
     OR EXISTS(SELECT 1 FROM unnest(p_users) u GROUP BY u HAVING count(*)>1) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post_tags';
  END IF;
  SELECT p.group_id INTO post_group FROM public.posts p WHERE p.id=p_post_id AND p.author_id=actor
    AND p.state IN ('published','pending_review') AND private.can_read_post(p.id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  -- Removing a tag from the editor records a terminal decision; never delete
  -- recipient rejection/removal, since doing so would let an edit silently
  -- reset the consent state on the next save.
  UPDATE public.post_user_tags SET state='removed',resolved_at=statement_timestamp()
    WHERE post_id=p_post_id AND tagged_user_id<>ALL(p_users) AND state IN ('pending','accepted');
  FOREACH uid IN ARRAY p_users LOOP
    IF uid IS NULL OR uid=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post_tags'; END IF;
    SELECT s.tagging_policy,s.tag_review_required,p.display_name INTO policy,review_required,display_name
      FROM public.user_settings s JOIN public.profiles p ON p.id=s.user_id
      JOIN public.account_status a ON a.user_id=p.id AND a.state='active'
      WHERE s.user_id=uid AND p.onboarding_completed AND private.can_read_profile(uid)
        AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
          (b.blocker_id=actor AND b.blocked_id=uid) OR (b.blocker_id=uid AND b.blocked_id=actor))
        AND (s.tagging_policy='everyone' OR (s.tagging_policy='followers' AND private.follows(actor,uid)))
        AND (private.user_can_read_post(p_post_id,uid) OR EXISTS(
          SELECT 1 FROM public.posts p JOIN public.group_members gm ON gm.group_id=p.group_id
            AND gm.user_id=uid AND gm.state='active'
          WHERE p.id=p_post_id AND p.state='pending_review' AND gm.group_id IS NOT NULL));
    IF display_name IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='tag_target_unavailable'; END IF;
    INSERT INTO public.post_user_tags(post_id,tagged_user_id,tagged_by,display_name_snapshot,state,resolved_at)
      VALUES(p_post_id,uid,actor,display_name,CASE WHEN review_required THEN 'pending' ELSE 'accepted' END,
        CASE WHEN review_required THEN NULL ELSE statement_timestamp() END)
      -- Conflict updates only the display snapshot. Rejected/removed rows stay
      -- terminal here; request_post_retag is the sole explicit re-consent path.
      ON CONFLICT(post_id,tagged_user_id) DO UPDATE SET
        display_name_snapshot=excluded.display_name_snapshot;
  END LOOP;
END $$;
CREATE OR REPLACE FUNCTION private.request_post_retag(p_post_id uuid,p_user_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); target_policy text; changed integer; new_request uuid:=gen_random_uuid();
  prior_state text; prior_resolved_at timestamptz;
BEGIN
  IF p_post_id IS NULL OR p_user_id IS NULL OR p_user_id=actor THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_tag_request';
  END IF;
  PERFORM 1 FROM public.posts p WHERE p.id=p_post_id AND p.author_id=actor AND p.state='published'
    AND private.can_read_post(p.id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  SELECT s.tagging_policy INTO target_policy FROM public.user_settings s
    JOIN public.profiles p ON p.id=s.user_id JOIN public.account_status a ON a.user_id=p.id AND a.state='active'
    WHERE s.user_id=p_user_id AND p.onboarding_completed AND private.can_read_profile(p_user_id)
      AND (s.tagging_policy='everyone' OR (s.tagging_policy='followers' AND private.follows(actor,p_user_id)))
      AND private.user_can_read_post(p_post_id,p_user_id)
      AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
        (b.blocker_id=actor AND b.blocked_id=p_user_id) OR (b.blocker_id=p_user_id AND b.blocked_id=actor));
  IF target_policy IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='tag_target_unavailable'; END IF;
  SELECT t.state,t.resolved_at INTO prior_state,prior_resolved_at FROM public.post_user_tags t
    WHERE t.post_id=p_post_id AND t.tagged_user_id=p_user_id FOR UPDATE;
  IF prior_state IS NULL OR prior_state NOT IN ('rejected','removed') OR prior_resolved_at IS NULL THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='tag_reconsent_unavailable';
  END IF;
  IF prior_resolved_at>statement_timestamp()-interval '7 days' THEN
    RAISE SQLSTATE 'PT429' USING MESSAGE='tag_reconsent_cooldown';
  END IF;
  UPDATE public.post_user_tags SET tagged_by=actor,state='pending',request_id=new_request,
    created_at=statement_timestamp(),resolved_at=NULL
    WHERE post_id=p_post_id AND tagged_user_id=p_user_id AND state=prior_state
      AND resolved_at=prior_resolved_at;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed<>1 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='tag_reconsent_unavailable'; END IF;
  RETURN jsonb_build_object('post_id',p_post_id,'user_id',p_user_id,'state','pending','request_id',new_request);
END $$;
CREATE OR REPLACE FUNCTION private.update_tagging_settings(p_policy text,p_review_required boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
  IF p_policy IS NULL OR p_policy NOT IN ('everyone','followers','nobody') OR p_review_required IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_tagging_settings'; END IF;
  UPDATE public.user_settings SET tagging_policy=p_policy,tag_review_required=p_review_required,updated_at=statement_timestamp()
    WHERE user_id=actor;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='user_settings_unavailable'; END IF;
  RETURN jsonb_build_object('tagging_policy',p_policy,'tag_review_required',p_review_required);
END $$;
CREATE OR REPLACE FUNCTION private.set_block(p_target uuid,p_blocked boolean) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE before_state boolean; result jsonb;
BEGIN
 IF p_target=auth.uid() OR EXISTS(SELECT 1 FROM public.system_roles WHERE user_id=p_target AND role='system_admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='protected_account'; END IF;
 PERFORM pg_advisory_xact_lock(6061101);
 -- Acquire pair before checking desired state, consistently with existing A/B writer.
 IF auth.uid() IS NOT NULL AND p_target IS NOT NULL THEN PERFORM private.c_pair(auth.uid(),p_target); END IF;
 SELECT EXISTS(SELECT 1 FROM public.user_blocks WHERE blocker_id=auth.uid() AND blocked_id=p_target) INTO before_state;
 result:=private.phase_b_set_block(p_target,p_blocked);
 IF before_state IS DISTINCT FROM p_blocked THEN PERFORM private.c_event(NULL,'block',ARRAY[auth.uid(),p_target]); END IF;
 IF p_blocked THEN UPDATE public.follow_requests SET state='canceled',updated_at=statement_timestamp() WHERE (requester_id=auth.uid() AND recipient_id=p_target OR requester_id=p_target AND recipient_id=auth.uid()) AND state='pending'; END IF;
 RETURN result;
END $$;

-- Column access must not expose private bio/cover via raw REST profiles reads.
REVOKE SELECT ON public.profiles FROM PUBLIC,anon,authenticated,service_role;
REVOKE SELECT (bio,cover_asset_id) ON public.profiles FROM PUBLIC,anon,authenticated,service_role;
GRANT SELECT(id,display_name,username,avatar_asset_id,public_avatar_preset,onboarding_completed,version,created_at,updated_at) ON public.profiles TO authenticated;
DO $$ BEGIN
 IF has_column_privilege('anon','public.profiles','bio','SELECT')
   OR has_column_privilege('anon','public.profiles','cover_asset_id','SELECT')
   OR has_column_privilege('authenticated','public.profiles','bio','SELECT')
   OR has_column_privilege('authenticated','public.profiles','cover_asset_id','SELECT')
   OR has_column_privilege('service_role','public.profiles','bio','SELECT')
   OR has_column_privilege('service_role','public.profiles','cover_asset_id','SELECT') THEN
   RAISE EXCEPTION 'profile privacy ACL mismatch: bio/cover still selectable; inspect inherited Cloud grants';
 END IF;
 IF NOT has_column_privilege('authenticated','public.profiles','display_name','SELECT')
   OR NOT (SELECT relrowsecurity FROM pg_class WHERE oid='public.profiles'::regclass)
   OR NOT EXISTS(SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='profiles' AND policyname='profiles_select') THEN
   RAISE EXCEPTION 'profile privacy prerequisite mismatch: safe identity grant or profiles RLS policy missing';
 END IF;
END $$;
-- Retired API entrypoints are revoked, data and internal historical functions remain intact.
DO $retire$ DECLARE f record; BEGIN
 FOR f IN SELECT p.oid::regprocedure signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname IN ('public','private') AND (p.proname IN ('send_friend_request','resolve_friend_request','accept_friend_request','decline_friend_request','cancel_friend_request','unfriend','get_friends','get_friend_requests','get_received_requests','get_sent_requests','get_suggestions','get_friend_suggestions','list_relations','get_memories')
 OR p.proname LIKE '%group%' AND p.proname NOT LIKE '%conversation%') LOOP
 EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon,authenticated',f.signature);
 END LOOP;
END $retire$;
NOTIFY pgrst,'reload schema';
COMMIT;
