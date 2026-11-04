-- Task 18: Community Groups. Extends the original group tables; independent of Notifications.
DO $$ BEGIN
  IF to_regclass('public.groups') IS NULL OR to_regclass('public.group_members') IS NULL
     OR to_regclass('public.group_join_requests') IS NULL OR to_regclass('public.posts') IS NULL
     OR to_regclass('public.post_media') IS NULL OR to_regclass('public.profiles') IS NULL
     OR to_regclass('public.media_assets') IS NULL OR to_regprocedure('private.b_actor()') IS NULL
     OR to_regprocedure('private.b_active()') IS NULL OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL OR to_regprocedure('private.is_blocked(uuid)') IS NULL
     OR to_regprocedure('private.attach_post(uuid,uuid,uuid[])') IS NULL THEN
    RAISE EXCEPTION 'Community Groups prerequisites missing; verify migrations 001-017 and schema first';
  END IF;
END $$;

ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS listed boolean NOT NULL DEFAULT true;
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS post_approval boolean NOT NULL DEFAULT false;
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS rules text NOT NULL DEFAULT '';
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS member_count integer NOT NULL DEFAULT 1;
ALTER TABLE public.groups DROP CONSTRAINT IF EXISTS groups_privacy_check;
ALTER TABLE public.groups ADD CONSTRAINT groups_privacy_check CHECK (privacy IN ('public','private','hidden'));
ALTER TABLE public.group_members DROP CONSTRAINT IF EXISTS group_members_role_check;
ALTER TABLE public.group_members ADD CONSTRAINT group_members_role_check CHECK (role IN ('owner','admin','moderator','member'));
ALTER TABLE public.posts DROP CONSTRAINT IF EXISTS posts_state_check;
ALTER TABLE public.posts ADD CONSTRAINT posts_state_check CHECK (state IN ('published','pending_review','rejected','hidden','deleted'));
ALTER TABLE public.groups DROP CONSTRAINT IF EXISTS groups_rules_check;
ALTER TABLE public.groups ADD CONSTRAINT groups_rules_check CHECK (char_length(rules)<=10000 AND member_count>=0);

CREATE TABLE IF NOT EXISTS public.group_invites (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE,
 inviter_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
 invitee_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
 state text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','accepted','declined','cancelled')),
 created_at timestamptz NOT NULL DEFAULT now(), resolved_at timestamptz,
 CHECK (inviter_id<>invitee_id), CHECK ((state='pending')=(resolved_at IS NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS group_invites_pending_unique ON public.group_invites(group_id,invitee_id) WHERE state='pending';
CREATE INDEX IF NOT EXISTS groups_discovery_idx ON public.groups(privacy,listed,state,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS group_members_page_idx ON public.group_members(group_id,state,joined_at DESC,user_id);
CREATE INDEX IF NOT EXISTS posts_group_page_idx ON public.posts(group_id,state,created_at DESC,id DESC);
ALTER TABLE public.group_invites ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.group_invites FROM PUBLIC,anon,authenticated;
CREATE POLICY group_invites_select ON public.group_invites FOR SELECT TO authenticated
 USING (private.is_active() AND (invitee_id=(select auth.uid()) OR inviter_id=(select auth.uid()) OR private.group_manager(group_id)));

CREATE OR REPLACE FUNCTION private.group_member(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.group_members m JOIN public.groups g ON g.id=m.group_id
 WHERE m.group_id=gid AND m.user_id=auth.uid() AND m.state='active' AND g.state='active') $$;
CREATE OR REPLACE FUNCTION private.group_manager(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.group_member(gid) AND EXISTS(SELECT 1 FROM public.group_members m WHERE m.group_id=gid AND m.user_id=auth.uid() AND m.role IN ('owner','admin','moderator') AND m.state='active') $$;
CREATE OR REPLACE FUNCTION private.can_read_group(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.groups g WHERE g.id=gid AND g.state='active'
 AND ((g.privacy='public' AND g.listed) OR private.group_member(g.id))) $$;
CREATE FUNCTION private.can_discover_group(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.groups g WHERE g.id=gid AND g.state='active'
 AND ((g.privacy IN ('public','private') AND g.listed) OR private.group_member(g.id))) $$;
CREATE OR REPLACE FUNCTION private.can_read_post_base(pid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.posts p JOIN public.account_status s ON s.user_id=p.author_id
 WHERE p.id=pid AND (p.state='published' OR (p.group_id IS NOT NULL AND p.state='pending_review'
   AND (p.author_id=auth.uid() OR private.group_manager(p.group_id)))) AND s.state='active' AND NOT private.is_blocked(p.author_id)
 AND ((p.group_id IS NULL AND (p.author_id=auth.uid() OR p.audience='public' OR (p.audience='friends' AND private.are_friends(p.author_id))))
 OR (p.group_id IS NOT NULL AND private.can_read_group(p.group_id)))) $$;

CREATE FUNCTION private.group_card(gid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',g.id,'name',g.name,'description',g.description,'privacy',g.privacy,'state',g.state,'listed',g.listed,
 'member_count',g.member_count,'cover_asset_id',g.cover_asset_id,'created_at',g.created_at,'post_approval',g.post_approval,'rules',g.rules,
 'role',m.role,'membership_state',m.state,'request_state',r.state)
 FROM public.groups g LEFT JOIN public.group_members m ON m.group_id=g.id AND m.user_id=auth.uid()
 LEFT JOIN LATERAL (SELECT state FROM public.group_join_requests WHERE group_id=g.id AND user_id=auth.uid() ORDER BY created_at DESC LIMIT 1) r ON true
 WHERE g.id=gid AND private.can_discover_group(g.id)
$$;

CREATE FUNCTION private.create_community_group(p_name text,p_description text,p_privacy text,p_listed boolean,p_rules text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups;
BEGIN
 IF p_name IS NULL OR char_length(btrim(p_name)) NOT BETWEEN 1 AND 150 OR p_description IS NULL OR char_length(p_description)>5000
 OR p_privacy IS NULL OR p_privacy NOT IN ('public','private','hidden') OR p_listed IS NULL OR p_rules IS NULL OR char_length(p_rules)>10000 OR p_client_request_id IS NULL THEN
 RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group'; END IF;
 SELECT * INTO g FROM public.groups WHERE created_by=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN RETURN private.group_card(g.id); END IF;
 IF (SELECT count(*) FROM public.groups WHERE created_by=actor AND state<>'deleted')>=20 THEN RAISE SQLSTATE 'PT429' USING MESSAGE='group_creation_limit'; END IF;
 INSERT INTO public.groups(created_by,client_request_id,name,description,privacy,listed,rules,member_count)
 VALUES(actor,p_client_request_id,btrim(p_name),btrim(p_description),p_privacy,p_listed,p_rules,1) RETURNING * INTO g;
 INSERT INTO public.group_members(group_id,user_id,role,state) VALUES(g.id,actor,'owner','active');
 RETURN private.group_card(g.id);
END $$;

CREATE FUNCTION private.update_community_group(p_group_id uuid,p_name text,p_description text,p_privacy text,p_listed boolean,p_rules text,p_post_approval boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups;
BEGIN
 SELECT * INTO g FROM public.groups WHERE id=p_group_id FOR UPDATE;
 IF NOT FOUND OR g.state<>'active' THEN RAISE SQLSTATE 'PT404' USING MESSAGE='group_unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='active' AND role IN ('owner','admin')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_admin_required'; END IF;
 IF p_name IS NULL OR char_length(btrim(p_name)) NOT BETWEEN 1 AND 150 OR p_description IS NULL OR char_length(p_description)>5000 OR p_privacy IS NULL OR p_privacy NOT IN ('public','private','hidden') OR p_listed IS NULL OR p_rules IS NULL OR char_length(p_rules)>10000 OR p_post_approval IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group'; END IF;
 UPDATE public.groups SET name=btrim(p_name),description=btrim(p_description),privacy=p_privacy,listed=p_listed,rules=p_rules,post_approval=p_post_approval,updated_at=statement_timestamp(),version=version+1 WHERE id=g.id;
 RETURN private.group_card(g.id);
END $$;

CREATE FUNCTION private.search_community_groups(p_query text,p_mine boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_query IS NULL OR char_length(p_query)>100 OR p_mine IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_search'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.group_card(q.id) ORDER BY q.created_at DESC,q.id DESC) FROM (
   SELECT g.id,g.created_at FROM public.groups g WHERE g.state='active' AND
   ((p_mine AND EXISTS(SELECT 1 FROM public.group_members m WHERE m.group_id=g.id AND m.user_id=auth.uid() AND m.state='active'))
    OR (NOT p_mine AND g.privacy IN ('public','private') AND g.listed))
   AND g.name ILIKE '%'||replace(replace(replace(btrim(p_query),'\','\\'),'%','\%'),'_','\_')||'%'
   AND (p_after_time IS NULL OR (g.created_at,g.id)<(p_after_time,p_after_id)) ORDER BY g.created_at DESC,g.id DESC LIMIT p_limit
 ) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.get_community_group(p_group_id uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN PERFORM private.b_active(); RETURN private.group_card(p_group_id); END $$;

CREATE FUNCTION private.join_community_group(p_group_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups; m public.group_members; r public.group_join_requests;
BEGIN
 SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 IF NOT FOUND OR private.is_blocked(g.created_by) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='group_unavailable'; END IF;
 SELECT * INTO m FROM public.group_members WHERE group_id=g.id AND user_id=actor FOR UPDATE;
 IF FOUND AND m.state='active' THEN RETURN jsonb_build_object('status','joined'); END IF;
 IF FOUND AND m.state='banned' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_banned'; END IF;
 IF g.privacy='public' THEN
  INSERT INTO public.group_members(group_id,user_id,role,state,joined_at,left_at) VALUES(g.id,actor,'member','active',statement_timestamp(),NULL)
  ON CONFLICT(group_id,user_id) DO UPDATE SET state='active',role='member',joined_at=statement_timestamp(),left_at=NULL;
  UPDATE public.groups SET member_count=member_count+1 WHERE id=g.id;
  RETURN jsonb_build_object('status','joined');
 END IF;
 IF g.privacy='hidden' AND NOT EXISTS(SELECT 1 FROM public.group_invites WHERE group_id=g.id AND invitee_id=actor AND state='pending') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='group_unavailable'; END IF;
 SELECT * INTO r FROM public.group_join_requests WHERE group_id=g.id AND user_id=actor AND state='pending' FOR UPDATE;
 IF NOT FOUND THEN INSERT INTO public.group_join_requests(group_id,user_id) VALUES(g.id,actor) RETURNING * INTO r; END IF;
 RETURN jsonb_build_object('status',CASE WHEN g.privacy='hidden' THEN 'invited' ELSE 'requested' END,'request_id',r.id);
END $$;

CREATE FUNCTION private.leave_community_group(p_group_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); m public.group_members;
BEGIN
 SELECT * INTO m FROM public.group_members WHERE group_id=p_group_id AND user_id=actor FOR UPDATE;
 IF NOT FOUND OR m.state<>'active' THEN RAISE SQLSTATE 'PT404' USING MESSAGE='membership_unavailable'; END IF;
 IF m.role='owner' AND (SELECT count(*) FROM public.group_members WHERE group_id=p_group_id AND state='active' AND role='owner')=1 THEN RAISE SQLSTATE 'PT409' USING MESSAGE='transfer_ownership_first'; END IF;
 UPDATE public.group_members SET state='left',left_at=statement_timestamp() WHERE group_id=p_group_id AND user_id=actor;
 UPDATE public.groups SET member_count=greatest(0,member_count-1) WHERE id=p_group_id;
 RETURN jsonb_build_object('left',true);
END $$;

CREATE FUNCTION private.list_community_members(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.can_read_group(p_group_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='group_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',q.user_id,'role',q.role,'joined_at',q.joined_at,
  'display_name',CASE WHEN private.can_read_profile(q.user_id) THEN p.display_name ELSE 'Meeto user' END,
  'avatar_asset_id',CASE WHEN private.can_read_profile(q.user_id) THEN p.avatar_asset_id END) ORDER BY q.joined_at DESC,q.user_id)
 FROM (SELECT m.* FROM public.group_members m WHERE m.group_id=p_group_id AND m.state='active'
 AND (p_after_time IS NULL OR (m.joined_at,m.user_id)<(p_after_time,p_after_id)) ORDER BY m.joined_at DESC,m.user_id LIMIT p_limit) q
 JOIN public.profiles p ON p.id=q.user_id),'[]'::jsonb);
END $$;

CREATE FUNCTION private.resolve_group_request(p_request_id uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.group_join_requests; g public.groups; member_state text;
BEGIN
 SELECT * INTO r FROM public.group_join_requests WHERE id=p_request_id FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='request_unavailable'; END IF;
 SELECT * INTO g FROM public.groups WHERE id=r.group_id FOR UPDATE;
 IF NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='active' AND role IN ('owner','admin','moderator')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 IF p_action IS NULL OR p_action NOT IN ('approve','reject') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 IF r.state=CASE p_action WHEN 'approve' THEN 'approved' ELSE 'declined' END THEN RETURN to_jsonb(r); END IF;
 IF r.state<>'pending' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='request_resolved'; END IF;
 IF p_action='approve' THEN
   SELECT state INTO member_state FROM public.group_members WHERE group_id=g.id AND user_id=r.user_id FOR UPDATE;
   IF member_state='banned' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_banned'; END IF;
 END IF;
 UPDATE public.group_join_requests SET state=CASE p_action WHEN 'approve' THEN 'approved' ELSE 'declined' END,reviewed_by=actor,resolved_at=statement_timestamp() WHERE id=r.id RETURNING * INTO r;
 IF p_action='approve' AND member_state IS DISTINCT FROM 'active' THEN
  INSERT INTO public.group_members(group_id,user_id,role,state,joined_at,left_at) VALUES(g.id,r.user_id,'member','active',statement_timestamp(),NULL)
  ON CONFLICT(group_id,user_id) DO UPDATE SET state='active',role='member',joined_at=statement_timestamp(),left_at=NULL;
  UPDATE public.groups SET member_count=member_count+1 WHERE id=g.id;
 END IF;
 RETURN to_jsonb(r);
END $$;

CREATE FUNCTION private.invite_to_community_group(p_group_id uuid,p_invitee uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); i public.group_invites;
BEGIN
 IF p_invitee IS NULL OR p_invitee=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_invitee'; END IF;
 IF NOT private.group_member(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_member_required'; END IF;
 IF private.is_blocked(p_invitee) OR NOT EXISTS(SELECT 1 FROM public.friendships WHERE user_low=least(actor,p_invitee) AND user_high=greatest(actor,p_invitee)) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invitee_unavailable'; END IF;
 IF EXISTS(SELECT 1 FROM public.group_members WHERE group_id=p_group_id AND user_id=p_invitee AND state='active') THEN RETURN jsonb_build_object('status','member'); END IF;
 INSERT INTO public.group_invites(group_id,inviter_id,invitee_id) VALUES(p_group_id,actor,p_invitee) ON CONFLICT(group_id,invitee_id) WHERE state='pending' DO UPDATE SET inviter_id=excluded.inviter_id RETURNING * INTO i;
 RETURN to_jsonb(i);
END $$;

CREATE FUNCTION private.resolve_group_invite(p_invite_id uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); i public.group_invites; g public.groups;
BEGIN
 SELECT * INTO i FROM public.group_invites WHERE id=p_invite_id AND invitee_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invite_unavailable'; END IF;
 SELECT * INTO g FROM public.groups WHERE id=i.group_id FOR UPDATE;
 IF p_action IS NULL OR p_action NOT IN ('accept','decline') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 IF i.state=CASE p_action WHEN 'accept' THEN 'accepted' ELSE 'declined' END THEN RETURN to_jsonb(i); END IF;
 IF i.state<>'pending' OR private.is_blocked(i.inviter_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invite_unavailable'; END IF;
 UPDATE public.group_invites SET state=CASE p_action WHEN 'accept' THEN 'accepted' ELSE 'declined' END,resolved_at=statement_timestamp() WHERE id=i.id RETURNING * INTO i;
 IF p_action='accept' THEN
  INSERT INTO public.group_members(group_id,user_id,role,state,joined_at,left_at) VALUES(g.id,actor,'member','active',statement_timestamp(),NULL)
  ON CONFLICT(group_id,user_id) DO UPDATE SET state='active',role='member',joined_at=statement_timestamp(),left_at=NULL;
  UPDATE public.groups SET member_count=member_count+1 WHERE id=g.id;
 END IF;
 RETURN to_jsonb(i);
END $$;

CREATE FUNCTION private.set_community_member_role(p_group_id uuid,p_user_id uuid,p_role text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); caller_role text; target_role text;
BEGIN
 IF p_role IS NULL OR p_role NOT IN ('admin','moderator','member') OR p_user_id IS NULL OR p_user_id=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_role_change'; END IF;
 SELECT role INTO caller_role FROM public.group_members WHERE group_id=p_group_id AND user_id=actor AND state='active' FOR UPDATE;
 IF caller_role IS NULL THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_owner_required'; END IF;
 SELECT role INTO target_role FROM public.group_members WHERE group_id=p_group_id AND user_id=p_user_id AND state='active' FOR UPDATE;
 IF target_role IS NULL OR target_role='owner' THEN RAISE SQLSTATE 'PT404' USING MESSAGE='member_unavailable'; END IF;
 IF caller_role='owner' THEN NULL; ELSIF caller_role='admin' AND target_role IN ('member','moderator') AND p_role IN ('member','moderator') THEN NULL; ELSE RAISE SQLSTATE 'PT403' USING MESSAGE='group_owner_required'; END IF;
 UPDATE public.group_members SET role=p_role WHERE group_id=p_group_id AND user_id=p_user_id;
 RETURN jsonb_build_object('user_id',p_user_id,'role',p_role);
END $$;

CREATE FUNCTION private.create_community_post(p_group_id uuid,p_body text,p_assets uuid[],p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups; p public.posts; h text;
BEGIN
 SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 IF NOT FOUND OR NOT private.group_member(g.id) OR EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='banned') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_member_required'; END IF;
 IF p_body IS NULL OR char_length(p_body)>20000 OR (btrim(p_body)='' AND coalesce(cardinality(p_assets),0)=0)
 OR p_assets IS NULL OR cardinality(p_assets)>10 OR array_position(p_assets,NULL) IS NOT NULL
 OR EXISTS(SELECT 1 FROM unnest(p_assets) x GROUP BY x HAVING count(*)>1) OR p_client_request_id IS NULL THEN
   RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post'; END IF;
 h:=encode(sha256(convert_to(jsonb_build_array(p_body,p_assets,p_group_id)::text,'UTF8')),'hex');
 SELECT * INTO p FROM public.posts WHERE author_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN IF p.group_id<>g.id OR p.request_hash<>h THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF; RETURN jsonb_build_object('id',p.id,'state',p.state,'created_at',p.created_at); END IF;
 IF coalesce(cardinality(p_assets),0)>0 AND EXISTS(SELECT 1 FROM unnest(p_assets) a LEFT JOIN public.media_assets m ON m.id=a WHERE m.id IS NULL OR m.owner_id<>actor OR m.state<>'ready' OR m.purpose<>'post') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_attachments'; END IF;
 INSERT INTO public.posts(author_id,group_id,client_request_id,request_hash,body,audience,state)
 VALUES(actor,g.id,p_client_request_id,h,btrim(p_body),'group',CASE WHEN g.post_approval AND NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND role IN ('owner','admin','moderator')) THEN 'pending_review' ELSE 'published' END) RETURNING * INTO p;
 PERFORM private.attach_post(p.id,actor,p_assets);
 RETURN jsonb_build_object('id',p.id,'state',p.state,'created_at',p.created_at);
END $$;

CREATE FUNCTION private.review_community_post(p_post_id uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; g public.groups;
BEGIN
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND group_id IS NOT NULL FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=p.group_id AND user_id=actor AND state='active' AND role IN ('owner','admin','moderator')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 IF p_action IS NULL OR p_action NOT IN ('approve','reject','remove') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 UPDATE public.posts SET state=CASE p_action WHEN 'approve' THEN 'published' WHEN 'reject' THEN 'rejected' ELSE 'hidden' END,updated_at=statement_timestamp(),version=version+1 WHERE id=p.id RETURNING * INTO p;
 RETURN jsonb_build_object('id',p.id,'state',p.state);
END $$;

CREATE FUNCTION private.get_community_posts(p_group_id uuid,p_pending boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.can_read_group(p_group_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='group_unavailable'; END IF;
 IF p_pending AND NOT private.group_manager(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.id DESC) FROM (
 SELECT p.id,p.author_id,p.group_id,p.body,p.state,p.reactions_count,p.comments_count,p.created_at,p.updated_at,p.version,
 (SELECT coalesce(jsonb_agg(jsonb_build_object('asset_id',pm.asset_id,'position',pm.position) ORDER BY pm.position),'[]'::jsonb) FROM public.post_media pm WHERE pm.post_id=p.id) media
 FROM public.posts p WHERE p.group_id=p_group_id AND ((p_pending AND p.state='pending_review') OR (NOT p_pending AND (p.state='published' OR (p.state='pending_review' AND p.author_id=auth.uid()))))
 AND private.can_read_post(p.id) AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
 ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION public.create_community_group(p_name text,p_description text,p_privacy text,p_listed boolean,p_rules text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_community_group($1,$2,$3,$4,$5,$6) $$;
CREATE FUNCTION public.update_community_group(p_group_id uuid,p_name text,p_description text,p_privacy text,p_listed boolean,p_rules text,p_post_approval boolean) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_community_group($1,$2,$3,$4,$5,$6,$7) $$;
CREATE FUNCTION public.search_community_groups(p_query text,p_mine boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_community_groups($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.get_community_group(p_group_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_community_group($1) $$;
CREATE FUNCTION public.join_community_group(p_group_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.join_community_group($1) $$;
CREATE FUNCTION public.leave_community_group(p_group_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.leave_community_group($1) $$;
CREATE FUNCTION public.list_community_members(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_community_members($1,$2,$3,$4) $$;
CREATE FUNCTION public.resolve_group_request(p_request_id uuid,p_action text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_group_request($1,$2) $$;
CREATE FUNCTION public.invite_to_community_group(p_group_id uuid,p_invitee uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.invite_to_community_group($1,$2) $$;
CREATE FUNCTION public.resolve_group_invite(p_invite_id uuid,p_action text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_group_invite($1,$2) $$;
CREATE FUNCTION public.set_community_member_role(p_group_id uuid,p_user_id uuid,p_role text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_community_member_role($1,$2,$3) $$;
CREATE FUNCTION public.create_community_post(p_group_id uuid,p_body text,p_assets uuid[],p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_community_post($1,$2,$3,$4) $$;
CREATE FUNCTION public.review_community_post(p_post_id uuid,p_action text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.review_community_post($1,$2) $$;
CREATE FUNCTION public.get_community_posts(p_group_id uuid,p_pending boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_community_posts($1,$2,$3,$4,$5) $$;

DO $$ DECLARE f text; BEGIN
 FOREACH f IN ARRAY ARRAY['create_community_group(text,text,text,boolean,text,uuid)','update_community_group(uuid,text,text,text,boolean,text,boolean)','search_community_groups(text,boolean,timestamptz,uuid,integer)','get_community_group(uuid)','join_community_group(uuid)','leave_community_group(uuid)','list_community_members(uuid,timestamptz,uuid,integer)','resolve_group_request(uuid,text)','invite_to_community_group(uuid,uuid)','resolve_group_invite(uuid,text)','set_community_member_role(uuid,uuid,text)','create_community_post(uuid,text,uuid[],uuid)','review_community_post(uuid,text)','get_community_posts(uuid,boolean,timestamptz,uuid,integer)'] LOOP
  EXECUTE 'REVOKE ALL ON FUNCTION public.'||f||' FROM PUBLIC,anon,service_role'; EXECUTE 'GRANT EXECUTE ON FUNCTION public.'||f||' TO authenticated';
  EXECUTE 'REVOKE ALL ON FUNCTION private.'||f||' FROM PUBLIC,anon,authenticated,service_role';
  EXECUTE 'GRANT EXECUTE ON FUNCTION private.'||f||' TO authenticated';
 END LOOP;
END $$;
REVOKE ALL ON FUNCTION private.group_card(uuid),private.can_discover_group(uuid) FROM PUBLIC,anon,authenticated,service_role;
NOTIFY pgrst,'reload schema';
