-- Task 36 / 040. Requires 037-039. Reuses private avatar upload pipeline/bucket.
BEGIN;
ALTER TABLE public.conversations ADD COLUMN avatar_asset_id uuid REFERENCES public.media_assets(id);
CREATE FUNCTION private.set_conversation_avatar(p_conversation_id uuid,p_asset_id uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id);
BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.conversations c JOIN public.conversation_members m ON m.conversation_id=c.id WHERE c.id=p_conversation_id AND c.kind='group' AND m.user_id=actor AND m.role='admin' AND m.state='active') THEN RAISE SQLSTATE 'PT403' USING MESSAGE='chat_admin_required'; END IF;
 PERFORM 1 FROM public.media_assets WHERE id=p_asset_id AND owner_id=actor AND purpose='avatar' AND state='ready' AND mime_type IN ('image/png','image/jpeg','image/webp') FOR UPDATE;
 IF NOT FOUND THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_avatar'; END IF;
 UPDATE public.conversations SET avatar_asset_id=p_asset_id,version=version+1,updated_at=statement_timestamp() WHERE id=p_conversation_id;
 PERFORM private.c_event(p_conversation_id,'avatar');
 RETURN private.c_conversation(p_conversation_id);
END $$;
CREATE FUNCTION public.set_conversation_avatar(p_conversation_id uuid,p_asset_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_conversation_avatar($1,$2) $$;
REVOKE ALL ON FUNCTION private.set_conversation_avatar(uuid,uuid),public.set_conversation_avatar(uuid,uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION private.set_conversation_avatar(uuid,uuid),public.set_conversation_avatar(uuid,uuid) TO authenticated;
-- Case-scoped moderation preview. Does not grant admins access to unrelated private media.
CREATE FUNCTION private.can_review_report_media(p_asset_id uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.reports r
 WHERE (private.has_role('system_admin') OR (private.has_role('moderator') AND r.assigned_to=auth.uid())) AND (
 EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.post_id=r.post_id AND pm.asset_id=p_asset_id)
 OR EXISTS(SELECT 1 FROM public.stories s WHERE s.id=r.story_id AND s.media_asset_id=p_asset_id)
 OR EXISTS(SELECT 1 FROM public.message_media mm WHERE mm.message_id=r.message_id AND mm.asset_id=p_asset_id)))
$$;
REVOKE ALL ON FUNCTION private.can_review_report_media(uuid) FROM PUBLIC,anon,authenticated,service_role;
-- Media authorization and cleanup references appended below.

-- Task 36 group avatar media authorization and existing cleanup references.
CREATE OR REPLACE FUNCTION private.can_read_media(aid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.media_assets a WHERE a.id=aid AND
 ((a.state='pending' AND a.owner_id=auth.uid() AND a.created_at>now()-interval '15 minutes') OR (a.state='ready' AND (
   EXISTS(SELECT 1 FROM public.profiles p WHERE ((p.avatar_asset_id=a.id AND a.purpose='avatar') OR (p.cover_asset_id=a.id AND a.purpose='cover')) AND a.owner_id=p.id AND private.can_read_profile(p.id) AND (a.purpose='avatar' OR private.account_content_allowed(p.id)))
   OR EXISTS(SELECT 1 FROM public.groups g WHERE false AND g.cover_asset_id=a.id AND a.bucket_id='group-media' AND a.purpose='group' AND g.state='active' AND ((g.privacy IN ('public','private') AND g.listed) OR private.group_member(g.id)))
   OR private.can_review_report_media(a.id)
   OR EXISTS(SELECT 1 FROM public.conversations c WHERE c.avatar_asset_id=a.id AND c.kind='group' AND a.purpose='avatar' AND private.can_read_conversation(c.id))
   OR EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id WHERE pm.asset_id=a.id AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(pm.post_id))
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id AND p.is_reel
     AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(p.id))
   OR EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND a.purpose='story' AND a.owner_id=s.author_id AND private.can_read_story(s.id))
   OR EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id WHERE mm.asset_id=a.id AND a.purpose='message' AND a.owner_id=m.sender_id AND m.state='sent' AND private.can_read_message(m.id))))));
$$;
CREATE OR REPLACE FUNCTION private.admin_report_detail(p_report_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); r public.reports; result jsonb; target uuid;
BEGIN
  PERFORM private.b_active();
  IF NOT private.has_role('system_admin') AND NOT private.has_role('moderator') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='platform_moderator_required'; END IF;
  SELECT * INTO r FROM public.reports WHERE id=p_report_id;
  IF NOT FOUND OR (NOT private.has_role('system_admin') AND r.assigned_to IS DISTINCT FROM actor) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='report_unavailable'; END IF;
  target:=coalesce(r.profile_id,r.post_id,r.comment_id,r.story_id,r.message_id,r.group_id);
  result:=jsonb_build_object('id',r.id,'target_kind',r.target_kind,'target_id',target,'reason_code',r.reason_code,
    'details',r.details,'state',r.state,'created_at',r.created_at,'assigned_to',r.assigned_to,
    'target',CASE r.target_kind
      WHEN 'post' THEN (SELECT jsonb_build_object('id',p.id,'kind',CASE WHEN coalesce(p.is_reel,false) THEN 'reel' ELSE 'post' END,
        'state',p.state,'body',p.body,'author_name',pr.display_name,'created_at',p.created_at)
        FROM public.posts p LEFT JOIN public.profiles pr ON pr.id=p.author_id WHERE p.id=target)
      WHEN 'comment' THEN (SELECT jsonb_build_object('id',c.id,'kind','comment','state',c.state,'body',c.body,
        'author_name',pr.display_name,'created_at',c.created_at,'post_id',c.post_id)
        FROM public.comments c LEFT JOIN public.profiles pr ON pr.id=c.author_id WHERE c.id=target)
      WHEN 'group' THEN (SELECT jsonb_build_object('id',g.id,'kind','group','state',g.state,'name',g.name,
        'description',g.description,'created_at',g.created_at) FROM public.groups g WHERE g.id=target)
      WHEN 'profile' THEN (SELECT jsonb_build_object('id',p.id,'kind','profile','display_name',p.display_name,
        'account_state',s.state) FROM public.profiles p LEFT JOIN public.account_status s ON s.user_id=p.id WHERE p.id=target)
      WHEN 'story' THEN (SELECT jsonb_build_object('id',s.id,'kind','story','state',s.state,'caption',s.caption,
        'created_at',s.created_at) FROM public.stories s WHERE s.id=target)
      ELSE jsonb_build_object('id',target,'kind','message','preview','Nội dung tin nhắn được bảo vệ; dùng moderation report workflow.') END);
  result:=result||jsonb_build_object('media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',a.id,'mime_type',a.mime_type)) FROM public.media_assets a WHERE a.state='ready' AND private.can_review_report_media(a.id) AND (EXISTS(SELECT 1 FROM public.post_media pm WHERE pm.post_id=r.post_id AND pm.asset_id=a.id) OR EXISTS(SELECT 1 FROM public.stories s WHERE s.id=r.story_id AND s.media_asset_id=a.id) OR EXISTS(SELECT 1 FROM public.message_media mm WHERE mm.message_id=r.message_id AND mm.asset_id=a.id))),'[]'::jsonb));
  RETURN result;
END $$;
CREATE OR REPLACE FUNCTION private.schedule_media_cleanup() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE a public.media_assets; n integer:=0;
BEGIN
  PERFORM pg_advisory_xact_lock(6061101);
  FOR a IN SELECT * FROM public.media_assets m WHERE m.purpose IN ('avatar','cover','post','message','story')
    AND ((m.state='pending_delete' AND coalesce(m.delete_after,now())<=now())
      OR (m.state IN ('pending','ready','quarantined') AND m.created_at<now()-interval '24 hours'))
    AND NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
      WHERE pm.asset_id=m.id AND p.state IN ('published','hidden'))
    AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE m.id IN (p.avatar_asset_id,p.cover_asset_id))
    AND NOT EXISTS(SELECT 1 FROM public.conversations c WHERE c.avatar_asset_id=m.id AND c.state<>'deleted')
    AND NOT EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=m.id AND s.state='active' AND s.expires_at>now())
    AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages msg ON msg.id=mm.message_id
      WHERE mm.asset_id=m.id AND msg.state='sent')
    ORDER BY m.id LIMIT 100 FOR UPDATE LOOP
    -- Serialize with hide/restore transitions. Hidden posts remain restorable,
    -- so their media must stay attached until the post is permanently removed.
    PERFORM 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
      WHERE pm.asset_id=a.id AND p.state IN ('published','hidden') FOR UPDATE OF p;
    IF FOUND THEN CONTINUE; END IF;
    IF NOT EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id
        WHERE pm.asset_id=a.id AND p.state IN ('published','hidden'))
      AND NOT EXISTS(SELECT 1 FROM public.profiles p WHERE a.id IN (p.avatar_asset_id,p.cover_asset_id))
      AND NOT EXISTS(SELECT 1 FROM public.conversations c WHERE c.avatar_asset_id=a.id AND c.state<>'deleted')
      AND NOT EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND s.state='active' AND s.expires_at>now())
      AND NOT EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages msg ON msg.id=mm.message_id
        WHERE mm.asset_id=a.id AND msg.state='sent') THEN
      PERFORM private.retire_asset(a.id); n:=n+1;
    END IF;
  END LOOP;
  RETURN n;
END $$;
CREATE OR REPLACE FUNCTION private.c_conversation(cid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',c.id,'kind',c.kind,'title',c.title,'state',c.state,'created_at',c.created_at,'updated_at',c.updated_at,'version',c.version,
 'high_water',c.next_seq,'history_from_seq',cm.history_from_seq,'last_read_seq',cm.last_read_seq,'last_delivered_seq',cm.last_delivered_seq,'role',cm.role,'archived',cm.archived_at IS NOT NULL,'owner_id',c.owner_id,'avatar_asset_id',c.avatar_asset_id,
 'last_message',(SELECT private.c_message(m.id) FROM public.messages m WHERE m.conversation_id=c.id AND private.can_read_message(m.id) ORDER BY m.seq DESC LIMIT 1),
 'unread_count',(SELECT count(*) FROM public.messages m WHERE m.conversation_id=c.id AND m.seq>cm.last_read_seq AND m.sender_id<>auth.uid() AND m.state='sent' AND private.can_read_message(m.id)))
 FROM public.conversations c JOIN public.conversation_members cm ON cm.conversation_id=c.id AND cm.user_id=auth.uid()
 WHERE c.id=cid AND private.can_read_conversation(c.id);
$$;
NOTIFY pgrst,'reload schema';
COMMIT;
