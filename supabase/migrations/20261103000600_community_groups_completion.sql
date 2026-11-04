-- Task 18B / migration 024. Forward-only completion; safe if 023 is already deployed.
DO $$ BEGIN
 IF to_regclass('public.groups') IS NULL OR to_regclass('public.group_invites') IS NULL
 OR to_regclass('public.posts') IS NULL OR to_regclass('public.post_media') IS NULL
 OR to_regclass('public.media_assets') IS NULL OR to_regclass('public.stories') IS NULL
 OR to_regclass('public.message_media') IS NULL
 OR to_regclass('public.moderation_audit') IS NULL OR to_regclass('public.reports') IS NULL
 OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
 OR to_regprocedure('private.group_manager(uuid)') IS NULL OR to_regprocedure('private.group_member(uuid)') IS NULL
 OR to_regprocedure('private.can_discover_group(uuid)') IS NULL OR to_regprocedure('private.can_read_group(uuid)') IS NULL
 OR to_regprocedure('private.can_read_post(uuid)') IS NULL
 OR to_regprocedure('private.can_read_story(uuid)') IS NULL OR to_regprocedure('private.can_read_message(uuid)') IS NULL
 OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
 OR to_regprocedure('private.post_json(uuid)') IS NULL OR to_regprocedure('private.can_read_media(uuid)') IS NULL
 OR to_regprocedure('private.can_upload_object(text,text)') IS NULL OR to_regprocedure('private.can_read_object(text,text)') IS NULL
 OR to_regprocedure('private.attach_post(uuid,uuid,uuid[])') IS NULL
 OR to_regprocedure('storage.allow_any_operation(text[])') IS NULL
 OR to_regclass('storage.buckets') IS NULL OR to_regclass('storage.objects') IS NULL
 THEN RAISE EXCEPTION 'Community Groups completion prerequisites missing; apply migrations 001-023 first'; END IF;
END $$;

-- Existing rows may predate the cached count; reconcile once before serving cards.
UPDATE public.group_members gm SET role='owner'
FROM public.groups g
WHERE g.id=gm.group_id AND gm.user_id=g.created_by AND gm.state='active'
 AND NOT EXISTS(SELECT 1 FROM public.group_members x WHERE x.group_id=g.id AND x.role='owner' AND x.state='active');
WITH candidates AS (
 SELECT m.group_id,m.user_id,row_number() OVER(PARTITION BY m.group_id ORDER BY (m.user_id=g.created_by) DESC,m.joined_at,m.user_id) AS rank
 FROM public.group_members m JOIN public.groups g ON g.id=m.group_id
 WHERE m.state='active' AND NOT EXISTS(SELECT 1 FROM public.group_members x WHERE x.group_id=m.group_id AND x.role='owner' AND x.state='active')
)
UPDATE public.group_members m SET role='owner' FROM candidates c WHERE c.rank=1 AND c.group_id=m.group_id AND c.user_id=m.user_id;
UPDATE public.groups g SET state='archived',updated_at=statement_timestamp(),version=version+1
WHERE g.state='active' AND NOT EXISTS(SELECT 1 FROM public.group_members m WHERE m.group_id=g.id AND m.state='active' AND m.role='owner')
;
UPDATE public.groups g SET member_count=(SELECT count(*)::integer FROM public.group_members m WHERE m.group_id=g.id AND m.state='active');
CREATE INDEX IF NOT EXISTS group_requests_page_idx ON public.group_join_requests(group_id,state,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS group_invites_inbox_idx ON public.group_invites(invitee_id,state,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS reports_group_lookup_idx ON public.reports(group_id,state,created_at DESC,id DESC);

-- A member report has a profile target and cannot also use reports.group_id:
-- the base reports CHECK permits only one target FK. Keep group context separately.
CREATE TABLE IF NOT EXISTS public.group_report_contexts (
 report_id uuid PRIMARY KEY REFERENCES public.reports(id) ON DELETE CASCADE,
 group_id uuid NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE,
 target_member_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS group_report_contexts_group_page_idx
 ON public.group_report_contexts(group_id,created_at DESC,report_id DESC);
ALTER TABLE public.group_report_contexts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.group_report_contexts FROM PUBLIC,anon,authenticated,service_role;

-- Dedicated private group-media bucket and object rules. Existing private buckets remain unchanged.
INSERT INTO storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
VALUES('group-media','group-media',false,10485760,ARRAY['image/png','image/jpeg','image/webp'])
ON CONFLICT(id) DO UPDATE SET public=false,file_size_limit=10485760,allowed_mime_types=ARRAY['image/png','image/jpeg','image/webp'];
DROP POLICY IF EXISTS meeto_b_insert ON storage.objects;
CREATE POLICY meeto_b_insert ON storage.objects FOR INSERT TO authenticated
 WITH CHECK(bucket_id IN ('profile-media','social-media','group-media') AND owner_id=auth.uid()::text AND private.can_upload_object(bucket_id,name));
DROP POLICY IF EXISTS meeto_b_get ON storage.objects;
CREATE POLICY meeto_b_get ON storage.objects FOR SELECT TO authenticated
 USING(bucket_id IN ('profile-media','social-media','group-media') AND storage.allow_any_operation(ARRAY['object.get_authenticated','object.get_authenticated_info']) AND private.can_read_object(bucket_id,name));
-- The inherited restrictive policy protects standard uploads from signed/copy APIs;
-- include group-media while preserving its existing checks for earlier buckets.
DROP POLICY IF EXISTS meeto_standard_upload_only ON storage.objects;
CREATE POLICY meeto_standard_upload_only ON storage.objects AS RESTRICTIVE FOR INSERT TO authenticated
 WITH CHECK(bucket_id NOT IN ('profile-media','social-media','chat-media','group-media') OR storage.allow_any_operation(ARRAY['object.upload']));

-- Discovery metadata visibility is separate from content access: private-listed names may be found,
-- but only active members can read private group posts or post media.
CREATE OR REPLACE FUNCTION private.can_discover_group(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.groups g WHERE g.id=gid AND g.state='active'
 AND ((g.privacy IN ('public','private') AND g.listed) OR private.group_member(g.id))) $$;
CREATE OR REPLACE FUNCTION private.can_read_group(gid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.groups g WHERE g.id=gid AND g.state='active'
 AND ((g.privacy='public' AND g.listed) OR private.group_member(g.id))) $$;
CREATE OR REPLACE FUNCTION private.can_read_media(aid uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.media_assets a WHERE a.id=aid AND
 ((a.state='pending' AND a.owner_id=auth.uid() AND a.created_at>now()-interval '15 minutes') OR (a.state='ready' AND (
   EXISTS(SELECT 1 FROM public.profiles p WHERE ((p.avatar_asset_id=a.id AND a.purpose='avatar') OR (p.cover_asset_id=a.id AND a.purpose='cover')) AND a.owner_id=p.id AND private.can_read_profile(p.id))
   OR EXISTS(SELECT 1 FROM public.groups g WHERE g.cover_asset_id=a.id AND a.bucket_id='group-media' AND a.purpose='group' AND g.state='active' AND ((g.privacy IN ('public','private') AND g.listed) OR private.group_member(g.id)))
   OR EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id WHERE pm.asset_id=a.id AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(pm.post_id))
   OR EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND a.purpose='story' AND a.owner_id=s.author_id AND private.can_read_story(s.id))
   OR EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id WHERE mm.asset_id=a.id AND a.purpose='message' AND a.owner_id=m.sender_id AND m.state='sent' AND private.can_read_message(m.id))))));
$$;
-- Retain the existing profile/social/chat upload checks and validate group cover
-- intent metadata plus current manager role for the new private bucket.
CREATE OR REPLACE FUNCTION private.can_upload_object(bucket text,path text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS (
   SELECT 1 FROM public.media_assets a
   WHERE a.bucket_id=bucket AND a.object_path=path AND a.owner_id=auth.uid()
     AND a.state='pending' AND a.created_at>now()-interval '15 minutes'
     AND CASE WHEN a.purpose='group' THEN
       bucket='group-media' AND a.metadata->>'upload_flow'='group_cover_v1'
       AND EXISTS(SELECT 1 FROM public.groups g JOIN public.group_members gm ON gm.group_id=g.id
         WHERE g.id::text=a.metadata->>'group_id' AND g.state='active'
           AND gm.user_id=auth.uid() AND gm.state='active' AND gm.role IN ('owner','admin'))
       ELSE (a.purpose<>'message' OR
         (private.can_read_conversation((a.metadata->>'conversation_id')::uuid)
          AND EXISTS(SELECT 1 FROM public.conversations c
            WHERE c.id=(a.metadata->>'conversation_id')::uuid AND c.state='active')))
     END
 );
$$;
CREATE OR REPLACE FUNCTION private.group_card(gid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',g.id,'name',g.name,'description',g.description,'privacy',g.privacy,'state',g.state,'listed',g.listed,
 'member_count',(SELECT count(*) FROM public.group_members x WHERE x.group_id=g.id AND x.state='active'),
 'cover_asset_id',CASE WHEN g.cover_asset_id IS NOT NULL AND private.can_read_media(g.cover_asset_id) THEN g.cover_asset_id END,
 'created_at',g.created_at,'post_approval',g.post_approval,'rules',g.rules,'role',m.role,'membership_state',m.state,'request_state',r.state,'request_id',r.id)
 FROM public.groups g LEFT JOIN public.group_members m ON m.group_id=g.id AND m.user_id=auth.uid()
 LEFT JOIN LATERAL (SELECT id,state FROM public.group_join_requests WHERE group_id=g.id AND user_id=auth.uid() ORDER BY created_at DESC LIMIT 1) r ON true
 WHERE g.id=gid AND private.can_discover_group(g.id)
$$;

CREATE OR REPLACE FUNCTION private.get_community_posts(p_group_id uuid,p_pending boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.can_read_group(p_group_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='group_unavailable'; END IF;
 IF p_pending AND NOT private.group_manager(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.post_json(q.id)||jsonb_build_object('group_id',q.group_id,'state',q.state) ORDER BY q.created_at DESC,q.id DESC)
 FROM (SELECT p.id,p.group_id,p.state,p.created_at FROM public.posts p WHERE p.group_id=p_group_id
 AND ((p_pending AND p.state='pending_review') OR (NOT p_pending AND (p.state='published' OR (p.state='pending_review' AND p.author_id=auth.uid()))))
 AND private.can_read_post(p.id) AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id))
 ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE OR REPLACE FUNCTION private.update_community_group(p_group_id uuid,p_name text,p_description text,p_privacy text,p_listed boolean,p_rules text,p_post_approval boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups;
BEGIN
 SELECT * INTO g FROM public.groups WHERE id=p_group_id FOR UPDATE;
 IF NOT FOUND OR g.state<>'active' THEN RAISE SQLSTATE 'PT404' USING MESSAGE='group_unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='active' AND role IN ('owner','admin')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_admin_required'; END IF;
 IF p_name IS NULL OR char_length(btrim(p_name)) NOT BETWEEN 1 AND 150 OR p_description IS NULL OR char_length(p_description)>5000 OR p_privacy IS NULL OR p_privacy NOT IN ('public','private','hidden') OR p_listed IS NULL OR p_rules IS NULL OR char_length(p_rules)>10000 OR p_post_approval IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group'; END IF;
 IF p_privacy='public' AND g.privacy<>'public' AND EXISTS(SELECT 1 FROM public.posts WHERE group_id=g.id AND state<>'deleted') THEN RAISE SQLSTATE 'PT409' USING MESSAGE='group_has_posts_privacy_transition'; END IF;
 UPDATE public.groups SET name=btrim(p_name),description=btrim(p_description),privacy=p_privacy,listed=p_listed,rules=p_rules,post_approval=p_post_approval,updated_at=statement_timestamp(),version=version+1 WHERE id=g.id;
 INSERT INTO public.moderation_audit(actor_id,operation,subject_type,subject_id,safe_metadata)
 VALUES(actor,'group_settings_changed','group',g.id,jsonb_build_object('privacy',p_privacy,'listed',p_listed,'post_approval',p_post_approval));
 RETURN private.group_card(g.id);
END $$;

CREATE FUNCTION private.audit_group_role_change() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF OLD.role IS DISTINCT FROM NEW.role THEN
  INSERT INTO public.moderation_audit(actor_id,operation,subject_type,subject_id,safe_metadata)
  VALUES(auth.uid(),'group_role_changed','group_member',NEW.user_id,jsonb_build_object('group_id',NEW.group_id,'old_role',OLD.role,'new_role',NEW.role));
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER group_role_audit AFTER UPDATE OF role ON public.group_members FOR EACH ROW EXECUTE FUNCTION private.audit_group_role_change();
CREATE FUNCTION private.audit_group_request_change() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF OLD.state IS DISTINCT FROM NEW.state THEN
  INSERT INTO public.moderation_audit(actor_id,operation,subject_type,subject_id,safe_metadata)
  VALUES(coalesce(NEW.reviewed_by,auth.uid()),'group_request_'||NEW.state,'group_request',NEW.id,jsonb_build_object('group_id',NEW.group_id,'user_id',NEW.user_id));
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER group_request_audit AFTER UPDATE OF state ON public.group_join_requests FOR EACH ROW EXECUTE FUNCTION private.audit_group_request_change();
CREATE FUNCTION private.audit_group_post_change() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF OLD.state IS DISTINCT FROM NEW.state AND NEW.group_id IS NOT NULL THEN
  INSERT INTO public.moderation_audit(actor_id,operation,subject_type,subject_id,safe_metadata)
  VALUES(auth.uid(),'group_post_'||NEW.state,'group_post',NEW.id,jsonb_build_object('group_id',NEW.group_id,'author_id',NEW.author_id));
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER group_post_audit AFTER UPDATE OF state ON public.posts FOR EACH ROW EXECUTE FUNCTION private.audit_group_post_change();
REVOKE ALL ON FUNCTION private.audit_group_role_change(),private.audit_group_request_change(),private.audit_group_post_change() FROM PUBLIC,anon,authenticated,service_role;

CREATE OR REPLACE FUNCTION private.resolve_group_invite(p_invite_id uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); i public.group_invites; g public.groups; old_state text;
BEGIN
 SELECT * INTO i FROM public.group_invites WHERE id=p_invite_id AND invitee_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invite_unavailable'; END IF;
 SELECT * INTO g FROM public.groups WHERE id=i.group_id FOR UPDATE;
 IF NOT FOUND OR g.state<>'active' THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invite_unavailable'; END IF;
 IF p_action IS NULL OR p_action NOT IN ('accept','decline') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 IF i.state=CASE p_action WHEN 'accept' THEN 'accepted' ELSE 'declined' END THEN RETURN to_jsonb(i); END IF;
 IF i.state<>'pending' OR private.is_blocked(i.inviter_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invite_unavailable'; END IF;
 SELECT state INTO old_state FROM public.group_members WHERE group_id=g.id AND user_id=actor FOR UPDATE;
 IF p_action='accept' AND old_state='banned' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_banned'; END IF;
 UPDATE public.group_invites SET state=CASE p_action WHEN 'accept' THEN 'accepted' ELSE 'declined' END,resolved_at=statement_timestamp() WHERE id=i.id RETURNING * INTO i;
 IF p_action='accept' AND old_state IS DISTINCT FROM 'active' THEN
  INSERT INTO public.group_members(group_id,user_id,role,state,joined_at,left_at) VALUES(g.id,actor,'member','active',statement_timestamp(),NULL)
  ON CONFLICT(group_id,user_id) DO UPDATE SET state='active',role='member',joined_at=statement_timestamp(),left_at=NULL;
  UPDATE public.groups SET member_count=member_count+1 WHERE id=g.id;
 END IF;
 RETURN to_jsonb(i);
END $$;

CREATE OR REPLACE FUNCTION private.invite_to_community_group(p_group_id uuid,p_invitee uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); i public.group_invites;
BEGIN
 IF p_invitee IS NULL OR p_invitee=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_invitee'; END IF;
 IF NOT private.group_member(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_member_required'; END IF;
 IF private.is_blocked(p_invitee) OR NOT EXISTS(SELECT 1 FROM public.friendships WHERE user_low=least(actor,p_invitee) AND user_high=greatest(actor,p_invitee))
 OR NOT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id WHERE p.id=p_invitee AND s.state='active' AND p.onboarding_completed)
 OR EXISTS(SELECT 1 FROM public.group_members WHERE group_id=p_group_id AND user_id=p_invitee AND state='banned') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invitee_unavailable'; END IF;
 IF EXISTS(SELECT 1 FROM public.group_members WHERE group_id=p_group_id AND user_id=p_invitee AND state='active') THEN RETURN jsonb_build_object('status','member'); END IF;
 INSERT INTO public.group_invites(group_id,inviter_id,invitee_id) VALUES(p_group_id,actor,p_invitee) ON CONFLICT(group_id,invitee_id) WHERE state='pending' DO UPDATE SET inviter_id=excluded.inviter_id RETURNING * INTO i;
 RETURN to_jsonb(i);
END $$;

CREATE FUNCTION private.delete_community_post(p_post_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts;
BEGIN
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND group_id IS NOT NULL FOR UPDATE;
 IF NOT FOUND OR (p.author_id<>actor AND NOT private.group_manager(p.group_id)) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p.state='deleted' THEN RETURN jsonb_build_object('id',p.id,'state',p.state); END IF;
 UPDATE public.posts SET state='deleted',deleted_at=statement_timestamp(),updated_at=statement_timestamp(),version=version+1 WHERE id=p.id RETURNING * INTO p;
 RETURN jsonb_build_object('id',p.id,'state',p.state);
END $$;
CREATE FUNCTION public.delete_community_post(p_post_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.delete_community_post($1) $$;

CREATE OR REPLACE FUNCTION private.review_community_post(p_post_id uuid,p_action text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; desired text;
BEGIN
 IF p_action NOT IN ('approve','reject','remove') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND group_id IS NOT NULL FOR UPDATE;
 IF NOT FOUND OR NOT private.group_manager(p.group_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 desired:=CASE p_action WHEN 'approve' THEN 'published' WHEN 'reject' THEN 'rejected' ELSE 'hidden' END;
 IF p.state=desired THEN RETURN jsonb_build_object('id',p.id,'state',p.state); END IF;
 IF p_action IN ('approve','reject') AND p.state<>'pending_review' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='post_review_resolved'; END IF;
 IF p_action='remove' AND p.state IN ('deleted','hidden') THEN RETURN jsonb_build_object('id',p.id,'state',p.state); END IF;
 UPDATE public.posts SET state=desired,deleted_at=CASE WHEN p_action='remove' THEN coalesce(deleted_at,statement_timestamp()) ELSE deleted_at END,updated_at=statement_timestamp(),version=version+1 WHERE id=p.id RETURNING * INTO p;
 RETURN jsonb_build_object('id',p.id,'state',p.state);
END $$;

CREATE OR REPLACE FUNCTION private.create_community_post(p_group_id uuid,p_body text,p_assets uuid[],p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups; p public.posts; h text;
BEGIN
 SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 IF NOT FOUND OR NOT private.group_member(g.id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_member_required'; END IF;
 IF p_body IS NULL OR char_length(p_body)>20000 OR (btrim(p_body)='' AND coalesce(cardinality(p_assets),0)=0)
 OR p_assets IS NULL OR cardinality(p_assets)>10 OR array_position(p_assets,NULL) IS NOT NULL OR EXISTS(SELECT 1 FROM unnest(p_assets) x GROUP BY x HAVING count(*)>1)
 OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_post'; END IF;
 h:=encode(sha256(convert_to(jsonb_build_array(p_body,p_assets,p_group_id)::text,'UTF8')),'hex');
 SELECT * INTO p FROM public.posts WHERE author_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN
  IF p.group_id IS DISTINCT FROM g.id OR p.request_hash<>h THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN jsonb_build_object('id',p.id,'state',p.state,'created_at',p.created_at);
 END IF;
 INSERT INTO public.posts(author_id,group_id,client_request_id,request_hash,body,audience,state)
 VALUES(actor,g.id,p_client_request_id,h,btrim(p_body),'group',CASE WHEN g.post_approval AND NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND role IN ('owner','admin','moderator')) THEN 'pending_review' ELSE 'published' END) RETURNING * INTO p;
 PERFORM private.attach_post(p.id,actor,p_assets);
 RETURN jsonb_build_object('id',p.id,'state',p.state,'created_at',p.created_at);
END $$;

CREATE FUNCTION private.prepare_group_cover_upload(p_group_id uuid,p_mime text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups; a public.media_assets; aid uuid:=gen_random_uuid(); ext text;
BEGIN
 IF p_mime IS NULL OR p_mime NOT IN ('image/png','image/jpeg','image/webp') OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_cover'; END IF;
 SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='active' AND role IN ('owner','admin')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_admin_required'; END IF;
 SELECT * INTO a FROM public.media_assets WHERE owner_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
 IF FOUND THEN
  IF a.purpose<>'group' OR a.metadata->>'group_id'<>p_group_id::text OR a.mime_type<>p_mime THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
 END IF;
 IF (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state='pending')>=10 OR
    (SELECT count(*) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted')>=100 OR
    coalesce((SELECT sum(CASE WHEN state='pending' THEN 10485760 ELSE coalesce(size_bytes,0) END) FROM public.media_assets WHERE owner_id=actor AND state<>'deleted'),0)+10485760>524288000
 THEN RAISE SQLSTATE 'PT429' USING MESSAGE='media_quota'; END IF;
 ext:=CASE p_mime WHEN 'image/png' THEN 'png' WHEN 'image/jpeg' THEN 'jpg' ELSE 'webp' END;
 INSERT INTO public.media_assets(id,owner_id,client_request_id,bucket_id,object_path,purpose,mime_type,metadata)
 VALUES(aid,actor,p_client_request_id,'group-media',actor::text||'/'||aid::text||'/original.'||ext,'group',p_mime,
 jsonb_build_object('upload_flow','group_cover_v1','group_id',p_group_id)) RETURNING * INTO a;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE FUNCTION private.finalize_group_cover_upload(p_asset_id uuid,p_group_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); a public.media_assets; g public.groups; obj_owner text; bytes bigint; obj_mime text; bucket_private boolean;
BEGIN
 SELECT * INTO a FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor FOR UPDATE;
 IF NOT FOUND OR a.purpose<>'group' OR a.bucket_id<>'group-media' OR a.metadata->>'upload_flow'<>'group_cover_v1' OR a.metadata->>'group_id'<>p_group_id::text THEN RAISE SQLSTATE 'PT404' USING MESSAGE='asset_unavailable'; END IF;
 SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='active' AND role IN ('owner','admin')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_admin_required'; END IF;
 IF a.state='ready' THEN
  UPDATE public.groups SET cover_asset_id=a.id,updated_at=statement_timestamp(),version=version+1 WHERE id=g.id;
  RETURN to_jsonb(a)-'metadata'-'client_request_id';
 END IF;
 IF a.state<>'pending' OR a.created_at<=statement_timestamp()-interval '15 minutes' THEN RAISE SQLSTATE 'PT410' USING MESSAGE='intent_closed'; END IF;
 SELECT o.owner_id,CASE WHEN coalesce(o.metadata->>'size','') ~ '^[0-9]{1,12}$' THEN (o.metadata->>'size')::bigint END,
        o.metadata->>'mimetype',NOT b.public INTO obj_owner,bytes,obj_mime,bucket_private
 FROM storage.objects o JOIN storage.buckets b ON b.id=o.bucket_id WHERE o.bucket_id=a.bucket_id AND o.name=a.object_path;
 IF NOT FOUND OR obj_owner IS DISTINCT FROM actor::text OR bytes IS NULL OR bytes<1 OR bytes>10485760 OR obj_mime IS DISTINCT FROM a.mime_type OR bucket_private IS DISTINCT FROM true THEN RAISE SQLSTATE 'PT409' USING MESSAGE='upload_metadata_invalid'; END IF;
 UPDATE public.media_assets SET size_bytes=bytes,state='ready',ready_at=statement_timestamp(),metadata=metadata||jsonb_build_object('validation','storage_object_metadata') WHERE id=a.id RETURNING * INTO a;
 UPDATE public.groups SET cover_asset_id=a.id,updated_at=statement_timestamp(),version=version+1 WHERE id=g.id;
 RETURN to_jsonb(a)-'metadata'-'client_request_id';
END $$;

CREATE FUNCTION private.list_group_requests(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.group_manager(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',q.id,'user_id',q.user_id,'created_at',q.created_at,
 'display_name',CASE WHEN private.can_read_profile(q.user_id) THEN p.display_name ELSE 'Meeto user' END,
 'avatar_asset_id',CASE WHEN private.can_read_profile(q.user_id) THEN p.avatar_asset_id END,
 'avatar_preset',CASE WHEN private.can_read_profile(q.user_id) THEN p.public_avatar_preset ELSE 'neutral' END) ORDER BY q.created_at DESC,q.id DESC)
 FROM (SELECT r.* FROM public.group_join_requests r WHERE r.group_id=p_group_id AND r.state='pending'
 AND (p_after_time IS NULL OR (r.created_at,r.id)<(p_after_time,p_after_id)) ORDER BY r.created_at DESC,r.id DESC LIMIT p_limit) q
 JOIN public.profiles p ON p.id=q.user_id),'[]'::jsonb);
END $$;
CREATE FUNCTION private.cancel_group_request(p_request_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.group_join_requests;
BEGIN
 SELECT * INTO r FROM public.group_join_requests WHERE id=p_request_id AND user_id=actor FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='request_unavailable'; END IF;
 IF r.state='cancelled' THEN RETURN to_jsonb(r); END IF;
 IF r.state<>'pending' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='request_resolved'; END IF;
 UPDATE public.group_join_requests SET state='cancelled',resolved_at=statement_timestamp() WHERE id=r.id RETURNING * INTO r;
 RETURN to_jsonb(r);
END $$;
CREATE FUNCTION private.list_group_invites(p_group_id uuid,p_inbox boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_inbox IS NULL OR (NOT p_inbox AND (p_group_id IS NULL OR NOT private.group_member(p_group_id))) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_member_required'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',q.id,'group_id',q.group_id,'group_name',g.name,'inviter_id',q.inviter_id,'invitee_id',q.invitee_id,'state',q.state,'created_at',q.created_at) ORDER BY q.created_at DESC,q.id DESC)
 FROM (SELECT i.* FROM public.group_invites i WHERE i.state='pending' AND ((p_inbox AND i.invitee_id=auth.uid()) OR (NOT p_inbox AND i.group_id=p_group_id AND i.inviter_id=auth.uid()))
 AND (p_after_time IS NULL OR (i.created_at,i.id)<(p_after_time,p_after_id)) ORDER BY i.created_at DESC,i.id DESC LIMIT p_limit) q JOIN public.groups g ON g.id=q.group_id),'[]'::jsonb);
END $$;
CREATE FUNCTION private.cancel_group_invite(p_invite_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); i public.group_invites;
BEGIN
 SELECT * INTO i FROM public.group_invites WHERE id=p_invite_id FOR UPDATE;
 IF NOT FOUND OR (i.inviter_id<>actor AND NOT private.group_manager(i.group_id)) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='invite_unavailable'; END IF;
 IF i.state='cancelled' THEN RETURN to_jsonb(i); END IF;
 IF i.state<>'pending' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='invite_resolved'; END IF;
 UPDATE public.group_invites SET state='cancelled',resolved_at=statement_timestamp() WHERE id=i.id RETURNING * INTO i;
 RETURN to_jsonb(i);
END $$;

CREATE FUNCTION private.manage_group_member(p_group_id uuid,p_user_id uuid,p_action text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups; caller_role text; target_role text; target_state text;
BEGIN
 SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 SELECT role INTO caller_role FROM public.group_members WHERE group_id=p_group_id AND user_id=actor AND state='active' FOR UPDATE;
 SELECT role,state INTO target_role,target_state FROM public.group_members WHERE group_id=p_group_id AND user_id=p_user_id FOR UPDATE;
 IF NOT FOUND OR caller_role IS NULL OR p_user_id=actor OR target_role='owner' THEN RAISE SQLSTATE 'PT404' USING MESSAGE='member_unavailable'; END IF;
 IF p_action IS NULL OR p_action NOT IN ('remove','ban','unban') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 IF caller_role='moderator' AND target_role<>'member' THEN RAISE SQLSTATE 'PT403' USING MESSAGE='member_role_hierarchy'; END IF;
 IF caller_role='admin' AND target_role IN ('owner','admin') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='member_role_hierarchy'; END IF;
 IF caller_role NOT IN ('owner','admin','moderator') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 IF p_action='unban' THEN
   IF target_state<>'banned' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='member_not_banned'; END IF;
   UPDATE public.group_members SET state='left',role='member',left_at=statement_timestamp() WHERE group_id=p_group_id AND user_id=p_user_id;
 ELSE
   IF target_state='banned' AND p_action='ban' THEN RETURN jsonb_build_object('user_id',p_user_id,'state','banned'); END IF;
   IF target_state<>'active' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='member_not_active'; END IF;
   UPDATE public.group_members SET state=CASE p_action WHEN 'ban' THEN 'banned' ELSE 'removed' END,left_at=statement_timestamp(),role='member' WHERE group_id=p_group_id AND user_id=p_user_id;
   UPDATE public.groups SET member_count=greatest(0,member_count-1) WHERE id=p_group_id;
 END IF;
 INSERT INTO public.moderation_audit(actor_id,operation,subject_type,subject_id,safe_metadata)
 VALUES(actor,'group_member_'||p_action,'group_member',p_user_id,jsonb_build_object('group_id',p_group_id));
 RETURN jsonb_build_object('user_id',p_user_id,'state',CASE p_action WHEN 'unban' THEN 'left' WHEN 'ban' THEN 'banned' ELSE 'removed' END);
END $$;
CREATE FUNCTION private.transfer_group_owner(p_group_id uuid,p_target uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); target_role text;
BEGIN
 IF p_target IS NULL OR p_target=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_owner_transfer'; END IF;
 PERFORM 1 FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
 IF NOT EXISTS(SELECT 1 FROM public.group_members WHERE group_id=p_group_id AND user_id=actor AND role='owner' AND state='active') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_owner_required'; END IF;
 SELECT role INTO target_role FROM public.group_members WHERE group_id=p_group_id AND user_id=p_target AND state='active' FOR UPDATE;
 IF target_role IS NULL OR target_role='owner' THEN RAISE SQLSTATE 'PT404' USING MESSAGE='member_unavailable'; END IF;
 UPDATE public.group_members SET role='admin' WHERE group_id=p_group_id AND user_id=actor;
 UPDATE public.group_members SET role='owner' WHERE group_id=p_group_id AND user_id=p_target;
 INSERT INTO public.moderation_audit(actor_id,operation,subject_type,subject_id,safe_metadata) VALUES(actor,'group_owner_transferred','group',p_group_id,jsonb_build_object('new_owner_id',p_target));
 RETURN jsonb_build_object('group_id',p_group_id,'owner_id',p_target);
END $$;
CREATE FUNCTION private.list_group_bans(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.group_manager(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',q.user_id,'role',q.role,'joined_at',q.joined_at,'display_name',CASE WHEN private.can_read_profile(q.user_id) THEN p.display_name ELSE 'Meeto user' END,'avatar_asset_id',CASE WHEN private.can_read_profile(q.user_id) THEN p.avatar_asset_id END) ORDER BY q.joined_at DESC,q.user_id)
 FROM (SELECT * FROM public.group_members WHERE group_id=p_group_id AND state='banned' AND (p_after_time IS NULL OR (joined_at,user_id)<(p_after_time,p_after_id)) ORDER BY joined_at DESC,user_id LIMIT p_limit) q JOIN public.profiles p ON p.id=q.user_id),'[]'::jsonb);
END $$;

CREATE FUNCTION private.report_group_target(p_group_id uuid,p_target_type text,p_target_id uuid,p_reason text,p_details text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.reports; target_group uuid; is_member_report boolean;
BEGIN
 IF NOT private.group_member(p_group_id) OR p_group_id IS NULL OR p_target_id IS NULL OR p_target_type IS NULL OR p_reason IS NULL OR p_reason NOT IN ('spam','harassment','nudity','violence','misinformation','other') OR char_length(coalesce(p_details,''))>3000 OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_report'; END IF;
 IF p_target_type='post' THEN SELECT group_id INTO target_group FROM public.posts WHERE id=p_target_id AND state='published';
 ELSIF p_target_type='comment' THEN SELECT p.group_id INTO target_group FROM public.comments c JOIN public.posts p ON p.id=c.post_id WHERE c.id=p_target_id AND c.state='published';
 ELSIF p_target_type='member' THEN
   is_member_report:=true;
   SELECT p_group_id INTO target_group FROM public.group_members WHERE group_id=p_group_id AND user_id=p_target_id AND state='active';
 ELSIF p_target_type='group' THEN target_group:=p_target_id;
 ELSE RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_report_target'; END IF;
 IF target_group IS DISTINCT FROM p_group_id THEN RAISE SQLSTATE 'PT404' USING MESSAGE='target_unavailable'; END IF;
 SELECT * INTO r FROM public.reports WHERE reporter_id=actor AND client_request_id=p_client_request_id;
 IF FOUND THEN
   IF r.reason_code IS DISTINCT FROM p_reason OR r.details IS DISTINCT FROM btrim(coalesce(p_details,''))
     OR (is_member_report AND (r.target_kind<>'profile' OR r.profile_id<>p_target_id OR NOT EXISTS(
       SELECT 1 FROM public.group_report_contexts x WHERE x.report_id=r.id AND x.group_id=p_group_id AND x.target_member_id=p_target_id)))
     OR (NOT coalesce(is_member_report,false) AND (r.target_kind IS DISTINCT FROM CASE p_target_type WHEN 'post' THEN 'post' WHEN 'comment' THEN 'comment' WHEN 'group' THEN 'group' END
       OR r.post_id IS DISTINCT FROM CASE WHEN p_target_type='post' THEN p_target_id END
       OR r.comment_id IS DISTINCT FROM CASE WHEN p_target_type='comment' THEN p_target_id END
       OR r.group_id IS DISTINCT FROM CASE WHEN p_target_type='group' THEN p_target_id END)) THEN
     RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
   END IF;
   RETURN jsonb_build_object('id',r.id,'state',r.state);
 END IF;
 INSERT INTO public.reports(reporter_id,client_request_id,target_kind,post_id,comment_id,profile_id,group_id,reason_code,details)
 VALUES(actor,p_client_request_id,CASE p_target_type WHEN 'member' THEN 'profile' ELSE p_target_type END,
 CASE WHEN p_target_type='post' THEN p_target_id END,CASE WHEN p_target_type='comment' THEN p_target_id END,
 CASE WHEN p_target_type='member' THEN p_target_id END,CASE WHEN p_target_type='group' THEN p_target_id END,p_reason,btrim(coalesce(p_details,''))) RETURNING * INTO r;
 IF is_member_report THEN
   INSERT INTO public.group_report_contexts(report_id,group_id,target_member_id)
   VALUES(r.id,p_group_id,p_target_id);
 END IF;
 RETURN jsonb_build_object('id',r.id,'state',r.state);
END $$;
CREATE FUNCTION private.list_group_reports(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.group_manager(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.id DESC) FROM (
 SELECT r.id,r.target_kind,r.post_id,r.comment_id,r.profile_id,r.reason_code,r.details,r.state,r.created_at,
 coalesce(p.group_id,cp.group_id,r.group_id,grc.group_id) AS group_id
 FROM public.reports r LEFT JOIN public.posts p ON p.id=r.post_id LEFT JOIN public.comments c ON c.id=r.comment_id LEFT JOIN public.posts cp ON cp.id=c.post_id
 LEFT JOIN public.group_report_contexts grc ON grc.report_id=r.id
 WHERE coalesce(p.group_id,cp.group_id,r.group_id,grc.group_id)=p_group_id AND r.state IN ('open','assigned')
 AND (p_after_time IS NULL OR (r.created_at,r.id)<(p_after_time,p_after_id)) ORDER BY r.created_at DESC,r.id DESC LIMIT p_limit
 ) q),'[]'::jsonb);
END $$;
CREATE FUNCTION private.resolve_group_report(p_group_id uuid,p_report_id uuid,p_action text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.reports;
BEGIN
 IF NOT private.group_manager(p_group_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='group_moderator_required'; END IF;
 IF p_action IS NULL OR p_action NOT IN ('resolve','dismiss') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 SELECT * INTO r FROM public.reports x WHERE x.id=p_report_id AND (
   x.group_id=p_group_id
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p.id=x.post_id AND p.group_id=p_group_id)
   OR EXISTS(SELECT 1 FROM public.comments c JOIN public.posts p ON p.id=c.post_id WHERE c.id=x.comment_id AND p.group_id=p_group_id)
   OR EXISTS(SELECT 1 FROM public.group_report_contexts grc WHERE grc.report_id=x.id AND grc.group_id=p_group_id)
 ) FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
 UPDATE public.reports SET state=CASE p_action WHEN 'resolve' THEN 'resolved' ELSE 'dismissed' END,resolved_at=statement_timestamp() WHERE id=r.id RETURNING * INTO r;
 INSERT INTO public.moderation_audit(actor_id,report_id,operation,subject_type,subject_id,safe_metadata) VALUES(actor,r.id,'group_report_'||p_action,'group',p_group_id,'{}'::jsonb);
 RETURN jsonb_build_object('id',r.id,'state',r.state);
END $$;

CREATE FUNCTION public.prepare_group_cover_upload(p_group_id uuid,p_mime text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.prepare_group_cover_upload($1,$2,$3) $$;
CREATE FUNCTION public.finalize_group_cover_upload(p_asset_id uuid,p_group_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.finalize_group_cover_upload($1,$2) $$;
CREATE FUNCTION public.list_group_requests(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_group_requests($1,$2,$3,$4) $$;
CREATE FUNCTION public.cancel_group_request(p_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.cancel_group_request($1) $$;
CREATE FUNCTION public.list_group_invites(p_group_id uuid,p_inbox boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_group_invites($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.cancel_group_invite(p_invite_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.cancel_group_invite($1) $$;
CREATE FUNCTION public.manage_group_member(p_group_id uuid,p_user_id uuid,p_action text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.manage_group_member($1,$2,$3) $$;
CREATE FUNCTION public.transfer_group_owner(p_group_id uuid,p_target uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.transfer_group_owner($1,$2) $$;
CREATE FUNCTION public.list_group_bans(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_group_bans($1,$2,$3,$4) $$;
CREATE FUNCTION public.report_group_target(p_group_id uuid,p_target_type text,p_target_id uuid,p_reason text,p_details text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.report_group_target($1,$2,$3,$4,$5,$6) $$;
CREATE FUNCTION public.list_group_reports(p_group_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_group_reports($1,$2,$3,$4) $$;
CREATE FUNCTION public.resolve_group_report(p_group_id uuid,p_report_id uuid,p_action text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_group_report($1,$2,$3) $$;

DO $$ DECLARE x text; BEGIN
 FOREACH x IN ARRAY ARRAY['prepare_group_cover_upload(uuid,text,uuid)','finalize_group_cover_upload(uuid,uuid)','list_group_requests(uuid,timestamptz,uuid,integer)','cancel_group_request(uuid)','list_group_invites(uuid,boolean,timestamptz,uuid,integer)','cancel_group_invite(uuid)','manage_group_member(uuid,uuid,text)','transfer_group_owner(uuid,uuid)','list_group_bans(uuid,timestamptz,uuid,integer)','report_group_target(uuid,text,uuid,text,text,uuid)','list_group_reports(uuid,timestamptz,uuid,integer)','resolve_group_report(uuid,uuid,text)','delete_community_post(uuid)'] LOOP
  EXECUTE 'REVOKE ALL ON FUNCTION public.'||x||' FROM PUBLIC,anon,service_role'; EXECUTE 'GRANT EXECUTE ON FUNCTION public.'||x||' TO authenticated';
  EXECUTE 'REVOKE ALL ON FUNCTION private.'||x||' FROM PUBLIC,anon,authenticated,service_role';
  EXECUTE 'GRANT EXECUTE ON FUNCTION private.'||x||' TO authenticated';
 END LOOP;
END $$;
REVOKE ALL ON FUNCTION private.can_discover_group(uuid) FROM PUBLIC,anon,authenticated,service_role;
NOTIFY pgrst,'reload schema';
