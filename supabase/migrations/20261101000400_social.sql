-- Phase B. Existing 36-table architecture retained. Polling is authoritative.
-- One transaction advisory lock intentionally bounds local MVP write concurrency.
-- All B writers and block share it; profile/media locks retain A ordering.
CREATE FUNCTION private.b_actor() RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 RETURN private.lock_profile();
END $$;
ALTER FUNCTION private.set_block(uuid,boolean) RENAME TO phase_a_set_block;
CREATE FUNCTION private.set_block(p_target uuid,p_blocked boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(6061101);
 RETURN private.phase_a_set_block(p_target,p_blocked);
END $$;
REVOKE ALL ON FUNCTION private.phase_a_set_block(uuid,boolean) FROM authenticated;
GRANT EXECUTE ON FUNCTION private.set_block(uuid,boolean) TO authenticated;

CREATE FUNCTION private.b_page(n integer,t timestamptz,i uuid) RETURNS void LANGUAGE plpgsql SET search_path='' AS $$
BEGIN
 IF n IS NULL OR n NOT BETWEEN 1 AND 50 OR (t IS NULL)<>(i IS NULL) THEN
  RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_cursor_or_limit'; END IF;
END $$;
CREATE FUNCTION private.b_active() RETURNS void LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.require_identity();
 IF NOT private.is_active() THEN RAISE SQLSTATE 'PT403' USING MESSAGE='active_onboarded_account_required'; END IF;
END $$;

CREATE FUNCTION private.send_friend_request(p_recipient uuid,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.friend_requests;
BEGIN
 IF p_recipient IS NULL OR p_recipient=actor OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_request'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(least(actor,p_recipient)::text||':'||greatest(actor,p_recipient)::text,0));
 IF private.is_blocked(p_recipient) OR NOT EXISTS(SELECT 1 FROM public.profiles p JOIN public.account_status s ON s.user_id=p.id WHERE p.id=p_recipient AND p.onboarding_completed AND s.state='active') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
 SELECT * INTO r FROM public.friend_requests WHERE requester_id=actor AND client_request_id=p_client_request_id;
 IF FOUND THEN
  IF r.recipient_id<>p_recipient THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN to_jsonb(r); END IF;
 IF private.are_friends(p_recipient) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='already_friends'; END IF;
 SELECT * INTO r FROM public.friend_requests WHERE pair_low=least(actor,p_recipient) AND pair_high=greatest(actor,p_recipient) AND state='pending';
 IF FOUND THEN RETURN to_jsonb(r); END IF; -- Crossed request returns incoming, never auto-accepts.
 INSERT INTO public.friend_requests(requester_id,recipient_id,client_request_id) VALUES(actor,p_recipient,p_client_request_id) RETURNING * INTO r;
 RETURN to_jsonb(r);
END $$;
CREATE FUNCTION private.resolve_friend_request(p_request_id uuid,p_action text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); r public.friend_requests; peer uuid; desired text;
BEGIN
 IF p_action IS NULL OR p_action NOT IN ('accept','decline','cancel') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_action'; END IF;
 SELECT * INTO r FROM public.friend_requests WHERE id=p_request_id AND actor IN (requester_id,recipient_id) FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='request_unavailable'; END IF;
 IF (p_action IN ('accept','decline') AND actor<>r.recipient_id) OR (p_action='cancel' AND actor<>r.requester_id) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='wrong_direction'; END IF;
 peer:=CASE WHEN actor=r.requester_id THEN r.recipient_id ELSE r.requester_id END;
 PERFORM pg_advisory_xact_lock(hashtextextended(least(actor,peer)::text||':'||greatest(actor,peer)::text,0));
 desired:=CASE p_action WHEN 'accept' THEN 'accepted' WHEN 'decline' THEN 'declined' ELSE 'cancelled' END;
 IF private.is_blocked(peer) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='request_unavailable'; END IF;
 IF r.state=desired THEN
  IF desired='accepted' AND NOT private.are_friends(peer) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='friendship_ended'; END IF;
  RETURN to_jsonb(r); END IF;
 IF r.state<>'pending' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='request_resolved'; END IF;
 IF p_action='accept' THEN
  IF NOT EXISTS(SELECT 1 FROM public.account_status s JOIN public.profiles p ON p.id=s.user_id WHERE s.user_id=peer AND s.state='active' AND p.onboarding_completed) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='profile_unavailable'; END IF;
  INSERT INTO public.friendships(user_low,user_high) VALUES(least(actor,peer),greatest(actor,peer));
 END IF;
 UPDATE public.friend_requests SET state=desired,resolved_at=statement_timestamp() WHERE id=r.id RETURNING * INTO r;
 RETURN to_jsonb(r);
END $$;
CREATE FUNCTION private.unfriend(p_other uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
 IF p_other IS NULL OR p_other=actor THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_peer'; END IF;
 DELETE FROM public.friendships WHERE user_low=least(actor,p_other) AND user_high=greatest(actor,p_other);
 RETURN jsonb_build_object('friend',false);
END $$;
CREATE FUNCTION private.list_relations(p_mode text,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_mode IS NULL OR p_mode NOT IN ('friends','received','sent','suggestions') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mode'; END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.id DESC) FROM (
  SELECT p.id,CASE WHEN private.can_read_profile(p.id) THEN p.display_name END AS display_name,
   CASE WHEN private.can_read_profile(p.id) THEN p.avatar_asset_id END AS avatar_asset_id,
   coalesce(r.created_at,f.created_at,p.created_at) AS created_at,r.id AS request_id,r.state
  FROM public.profiles p
  LEFT JOIN public.friendships f ON f.user_low=least(auth.uid(),p.id) AND f.user_high=greatest(auth.uid(),p.id)
  LEFT JOIN public.friend_requests r ON r.pair_low=least(auth.uid(),p.id) AND r.pair_high=greatest(auth.uid(),p.id) AND r.state='pending'
  JOIN public.account_status s ON s.user_id=p.id AND s.state='active'
  WHERE p.id<>auth.uid() AND p.onboarding_completed AND NOT private.is_blocked(p.id)
  AND CASE p_mode WHEN 'friends' THEN f.user_low IS NOT NULL WHEN 'sent' THEN r.requester_id=auth.uid() WHEN 'received' THEN r.recipient_id=auth.uid()
   ELSE f.user_low IS NULL AND r.id IS NULL AND private.can_read_profile(p.id) END
  AND (p_after_time IS NULL OR (coalesce(r.created_at,f.created_at,p.created_at),p.id)<(p_after_time,p_after_id))
  ORDER BY coalesce(r.created_at,f.created_at,p.created_at) DESC,p.id DESC LIMIT p_limit
 ) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.post_json(pid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',p.id,'author_id',p.author_id,'body',p.body,'audience',p.audience,'kind',p.kind,
 'original_post_id',CASE WHEN private.can_read_post_base(p.original_post_id) THEN p.original_post_id END,
 'original_available',p.kind='share' AND private.can_read_post_base(p.original_post_id),
 'author',CASE WHEN private.can_read_profile(p.author_id) THEN (SELECT jsonb_build_object('id',u.id,'display_name',u.display_name,'avatar_asset_id',u.avatar_asset_id) FROM public.profiles u WHERE u.id=p.author_id) END,
 'reactions_count',p.reactions_count,'comments_count',p.comments_count,'shares_count',p.shares_count,
 'my_reaction',(SELECT r.reaction FROM public.post_reactions r WHERE r.post_id=p.id AND r.user_id=auth.uid()),
 'version',p.version,'created_at',p.created_at,'updated_at',p.updated_at,
 'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',m.asset_id,'position',m.position) ORDER BY m.position) FROM public.post_media m WHERE m.post_id=p.id AND private.can_read_media(m.asset_id)),'[]'::jsonb))
 FROM public.posts p WHERE p.id=pid AND private.can_read_post(p.id);
$$;
CREATE FUNCTION private.attach_post(pid uuid,actor uuid,assets uuid[]) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets; x uuid; pos integer:=0;
BEGIN
 IF assets IS NULL OR cardinality(assets)>10 OR EXISTS(SELECT 1 FROM unnest(assets) u GROUP BY u HAVING count(*)>1) OR array_position(assets,NULL) IS NOT NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_attachments'; END IF;
 FOR a IN SELECT * FROM public.media_assets WHERE id=ANY(assets) ORDER BY id FOR UPDATE LOOP
  IF a.owner_id IS DISTINCT FROM actor OR a.purpose<>'post' OR a.state<>'ready' OR EXISTS(SELECT 1 FROM public.post_media WHERE asset_id=a.id AND post_id<>pid) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_attachment'; END IF;
 END LOOP;
 IF (SELECT count(*) FROM public.media_assets WHERE id=ANY(assets))<>cardinality(assets) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='missing_attachment'; END IF;
 FOREACH x IN ARRAY assets LOOP
  INSERT INTO public.post_media(post_id,asset_id,position) VALUES(pid,x,pos); pos:=pos+1;
 END LOOP;
END $$;
CREATE FUNCTION private.create_post(p_body text,p_audience text,p_assets uuid[],p_client_request_id uuid,p_original uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; h text;
BEGIN
 IF p_body IS NULL OR length(p_body)>20000 OR p_audience IS NULL OR p_audience NOT IN ('public','friends','private') OR p_client_request_id IS NULL OR p_assets IS NULL
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
CREATE FUNCTION private.edit_post(p_post_id uuid,p_body text,p_audience text,p_assets uuid[],p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; previous uuid[]; a uuid;
BEGIN
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND state='published' AND group_id IS NULL FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p_body IS NULL OR length(p_body)>20000 OR p_audience IS NULL OR p_audience NOT IN ('public','friends','private') OR p_assets IS NULL
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
CREATE FUNCTION private.retire_asset(aid uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
 UPDATE public.media_assets SET state='pending_delete',delete_after=statement_timestamp() WHERE id=aid AND state<>'deleted';
 INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id) VALUES('media-retire:'||aid::text,'media.retire','media_asset',aid) ON CONFLICT(event_key) DO NOTHING;
END $$;
CREATE FUNCTION private.delete_post(p_post_id uuid,p_expected_version bigint) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); p public.posts; a uuid;
BEGIN
 SELECT * INTO p FROM public.posts WHERE id=p_post_id AND author_id=actor AND group_id IS NULL FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p.state='deleted' THEN RETURN jsonb_build_object('id',p.id,'state','deleted','version',p.version); END IF;
 IF p.state<>'published' OR p_expected_version IS NULL OR p.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 FOR a IN SELECT asset_id FROM public.post_media WHERE post_id=p.id ORDER BY asset_id LOOP PERFORM private.retire_asset(a); END LOOP;
 IF p.kind='share' THEN UPDATE public.posts SET shares_count=shares_count-1 WHERE id=p.original_post_id; END IF;
 UPDATE public.posts SET state='deleted',body='',deleted_at=statement_timestamp(),updated_at=statement_timestamp(),version=version+1 WHERE id=p.id RETURNING * INTO p;
 RETURN jsonb_build_object('id',p.id,'state',p.state,'version',p.version);
END $$;
CREATE FUNCTION private.get_post(p_post_id uuid) RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 PERFORM private.b_active(); result:=private.post_json(p_post_id);
 IF result IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 RETURN result;
END $$;
CREATE FUNCTION private.get_feed(p_author uuid,p_mode text,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_mode IS NULL OR p_mode NOT IN ('all','friends') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mode'; END IF;
 RETURN coalesce((SELECT jsonb_agg(private.post_json(q.id) ORDER BY q.created_at DESC,q.id DESC) FROM (
 SELECT p.id,p.created_at FROM public.posts p WHERE p.group_id IS NULL AND private.can_read_post(p.id)
 AND (p_author IS NULL OR p.author_id=p_author) AND (p_mode='all' OR p.author_id=auth.uid() OR private.are_friends(p.author_id))
 AND (p_after_time IS NULL OR (p.created_at,p.id)<(p_after_time,p_after_id)) ORDER BY p.created_at DESC,p.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.set_post_reaction(p_post_id uuid,p_reaction text) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); old_type text; delta integer:=0; total bigint;
BEGIN
 PERFORM 1 FROM public.posts WHERE id=p_post_id AND group_id IS NULL AND private.can_interact_post(id) FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p_reaction IS NOT NULL AND p_reaction NOT IN ('like','love','care','haha','wow','sad','angry') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reaction'; END IF;
 SELECT reaction INTO old_type FROM public.post_reactions WHERE post_id=p_post_id AND user_id=actor;
 IF old_type IS DISTINCT FROM p_reaction THEN
  IF p_reaction IS NULL THEN DELETE FROM public.post_reactions WHERE post_id=p_post_id AND user_id=actor; delta:=-1;
  ELSE INSERT INTO public.post_reactions(post_id,user_id,reaction) VALUES(p_post_id,actor,p_reaction)
   ON CONFLICT(post_id,user_id) DO UPDATE SET reaction=excluded.reaction,updated_at=statement_timestamp();
   IF old_type IS NULL THEN delta:=1; END IF;
  END IF;
 END IF;
 UPDATE public.posts SET reactions_count=reactions_count+delta WHERE id=p_post_id RETURNING reactions_count INTO total;
 RETURN jsonb_build_object('reaction',p_reaction,'reactions_count',total);
END $$;
CREATE FUNCTION private.create_comment(p_post_id uuid,p_parent_id uuid,p_body text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); c public.comments; h text; parent uuid:=p_parent_id; depth integer:=0;
BEGIN
 IF p_body IS NULL OR length(btrim(p_body)) NOT BETWEEN 1 AND 5000 OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_comment'; END IF;
 PERFORM 1 FROM public.posts WHERE id=p_post_id AND group_id IS NULL AND private.can_interact_post(id) FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 h:=encode(sha256(convert_to(jsonb_build_array(p_post_id,p_parent_id,p_body)::text,'UTF8')),'hex');
 SELECT * INTO c FROM public.comments WHERE author_id=actor AND client_request_id=p_client_request_id;
 IF FOUND THEN
  IF c.request_hash<>h OR c.state<>'published' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
  RETURN to_jsonb(c)-'request_hash'-'client_request_id'; END IF;
 WHILE parent IS NOT NULL LOOP
  SELECT * INTO c FROM public.comments WHERE id=parent AND post_id=p_post_id AND private.can_read_comment(id);
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='parent_unavailable'; END IF;
  depth:=depth+1; IF depth>=8 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='comment_depth_exceeded'; END IF;
  parent:=c.parent_id;
 END LOOP;
 INSERT INTO public.comments(post_id,author_id,parent_id,client_request_id,request_hash,body) VALUES(p_post_id,actor,p_parent_id,p_client_request_id,h,p_body) RETURNING * INTO c;
 UPDATE public.posts SET comments_count=comments_count+1 WHERE id=p_post_id;
 RETURN to_jsonb(c)-'request_hash'-'client_request_id';
END $$;
CREATE FUNCTION private.change_comment(p_comment_id uuid,p_body text,p_expected_version bigint,p_delete boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); c public.comments;
BEGIN
 SELECT * INTO c FROM public.comments WHERE id=p_comment_id AND author_id=actor;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='comment_unavailable'; END IF;
 PERFORM 1 FROM public.posts WHERE id=c.post_id AND group_id IS NULL AND private.can_interact_post(id) FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF (p_delete AND c.state='deleted') OR (NOT p_delete AND c.state='published' AND c.body=p_body) THEN RETURN to_jsonb(c)-'request_hash'-'client_request_id'; END IF;
 IF c.state<>'published' OR p_expected_version IS NULL OR c.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 IF NOT p_delete AND (p_body IS NULL OR length(btrim(p_body)) NOT BETWEEN 1 AND 5000) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_comment'; END IF;
 UPDATE public.comments SET body=CASE WHEN p_delete THEN NULL ELSE p_body END,state=CASE WHEN p_delete THEN 'deleted' ELSE 'published' END,version=version+1,updated_at=statement_timestamp() WHERE id=c.id RETURNING * INTO c;
 IF p_delete THEN UPDATE public.posts SET comments_count=comments_count-1 WHERE id=c.post_id; END IF;
 RETURN to_jsonb(c)-'request_hash'-'client_request_id';
END $$;
CREATE FUNCTION private.list_comments(p_post_id uuid,p_parent_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF NOT private.can_read_post(p_post_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
 IF p_parent_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.comments WHERE id=p_parent_id AND post_id=p_post_id AND private.can_read_comment(id)) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='parent_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.id DESC) FROM (
  SELECT c.id,c.post_id,c.author_id,c.parent_id,c.body,c.state,c.version,c.created_at,c.updated_at FROM public.comments c
  WHERE c.post_id=p_post_id AND c.parent_id IS NOT DISTINCT FROM p_parent_id AND private.can_read_comment(c.id)
  AND (p_after_time IS NULL OR (c.created_at,c.id)<(p_after_time,p_after_id)) ORDER BY c.created_at DESC,c.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

-- Only Phase B safe column reads, no raw writes/hash/client request identifiers.
GRANT SELECT(id,author_id,group_id,kind,original_post_id,body,audience,state,reactions_count,comments_count,shares_count,created_at,updated_at,deleted_at,version) ON public.posts TO authenticated;
GRANT SELECT(id,post_id,author_id,parent_id,body,state,created_at,updated_at,version) ON public.comments TO authenticated;
GRANT SELECT ON public.post_media,public.post_reactions,public.friendships,public.friend_requests TO authenticated;
GRANT SELECT(id,owner_id,bucket_id,object_path,purpose,state,mime_type,size_bytes,created_at,ready_at) ON public.media_assets TO authenticated;

-- Fixed invoker interfaces. Internal actor-taking helpers remain owner-only.
CREATE FUNCTION public.send_friend_request(p_recipient uuid,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.send_friend_request(p_recipient,p_client_request_id) $$;
CREATE FUNCTION public.accept_friend_request(p_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_friend_request(p_request_id,'accept') $$;
CREATE FUNCTION public.decline_friend_request(p_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_friend_request(p_request_id,'decline') $$;
CREATE FUNCTION public.cancel_friend_request(p_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.resolve_friend_request(p_request_id,'cancel') $$;
CREATE FUNCTION public.unfriend(p_other uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.unfriend(p_other) $$;
CREATE FUNCTION public.get_friends(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_relations('friends',p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.get_received_requests(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_relations('received',p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.get_sent_requests(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_relations('sent',p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.get_suggestions(p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_relations('suggestions',p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.create_post(p_body text,p_audience text,p_assets uuid[],p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_post(p_body,p_audience,p_assets,p_client_request_id,NULL) $$;
CREATE FUNCTION public.share_post(p_original uuid,p_body text,p_audience text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_post(p_body,p_audience,'{}',p_client_request_id,p_original) $$;
CREATE FUNCTION public.edit_post(p_post_id uuid,p_body text,p_audience text,p_assets uuid[],p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.edit_post(p_post_id,p_body,p_audience,p_assets,p_expected_version) $$;
CREATE FUNCTION public.delete_post(p_post_id uuid,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.delete_post(p_post_id,p_expected_version) $$;
CREATE FUNCTION public.get_post(p_post_id uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_post(p_post_id) $$;
CREATE FUNCTION public.get_feed(p_mode text DEFAULT 'all',p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_feed(NULL,p_mode,p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.get_user_posts(p_author uuid,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_feed(p_author,'all',p_after_time,p_after_id,p_limit) $$;
CREATE FUNCTION public.set_post_reaction(p_post_id uuid,p_reaction text) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_post_reaction(p_post_id,p_reaction) $$;
CREATE FUNCTION public.create_comment(p_post_id uuid,p_parent_id uuid,p_body text,p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_comment(p_post_id,p_parent_id,p_body,p_client_request_id) $$;
CREATE FUNCTION public.edit_comment(p_comment_id uuid,p_body text,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.change_comment(p_comment_id,p_body,p_expected_version,false) $$;
CREATE FUNCTION public.delete_comment(p_comment_id uuid,p_expected_version bigint) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.change_comment(p_comment_id,NULL,p_expected_version,true) $$;
CREATE FUNCTION public.list_comments(p_post_id uuid,p_parent_id uuid DEFAULT NULL,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_comments(p_post_id,p_parent_id,p_after_time,p_after_id,p_limit) $$;
DO $$ DECLARE r record; BEGIN
 FOR r IN SELECT p.oid::regprocedure AS sig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
 WHERE n.nspname IN ('public','private') AND p.proname IN ('send_friend_request','resolve_friend_request','accept_friend_request','decline_friend_request','cancel_friend_request','unfriend','list_relations','get_friends','get_received_requests','get_sent_requests','get_suggestions','create_post','share_post','edit_post','delete_post','get_post','get_feed','get_user_posts','set_post_reaction','create_comment','change_comment','edit_comment','delete_comment','list_comments') LOOP
  EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC,anon,authenticated',r.sig);
  EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO authenticated',r.sig);
 END LOOP;
END $$;
NOTIFY pgrst,'reload schema';
