-- Task 21.5 / migration 028: comment reactions and private conversation controls/media.
BEGIN;
DO $$ BEGIN
  IF to_regclass('public.comment_reactions') IS NULL OR to_regclass('public.post_reactions') IS NULL
     OR to_regclass('public.comments') IS NULL
     OR to_regclass('public.posts') IS NULL OR to_regclass('public.conversations') IS NULL
     OR to_regclass('public.conversation_members') IS NULL OR to_regclass('public.messages') IS NULL
     OR to_regclass('public.message_media') IS NULL OR to_regclass('public.media_assets') IS NULL
     OR to_regclass('public.notifications') IS NULL OR to_regclass('public.user_settings') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.profiles') IS NULL
     OR to_regclass('public.user_blocks') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.can_read_comment(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL
     OR to_regprocedure('private.set_post_reaction(uuid,text)') IS NULL
     OR to_regprocedure('private.create_comment(uuid,uuid,text,uuid)') IS NULL
     OR to_regprocedure('private.change_comment(uuid,text,bigint,boolean)') IS NULL
     OR to_regprocedure('private.can_interact_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_conversation(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.is_blocked(uuid)') IS NULL
     OR to_regprocedure('private.require_identity()') IS NULL
     OR to_regprocedure('realtime.send(jsonb,text,text,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Messenger UX prerequisites missing; verify migrations 001-027';
  END IF;
END $$;

-- Existing social RPCs excluded group posts even though the group feed uses the same cards.
-- can_interact_post enforces current group membership and effective post visibility.
CREATE OR REPLACE FUNCTION private.set_post_reaction(p_post_id uuid,p_reaction text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); old_type text; delta integer:=0; total bigint;
BEGIN
  PERFORM 1 FROM public.posts WHERE id=p_post_id AND private.can_interact_post(id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  IF p_reaction IS NOT NULL AND p_reaction NOT IN ('like','love','care','haha','wow','sad','angry') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reaction'; END IF;
  SELECT reaction INTO old_type FROM public.post_reactions WHERE post_id=p_post_id AND user_id=actor;
  IF old_type IS DISTINCT FROM p_reaction THEN
    IF p_reaction IS NULL THEN DELETE FROM public.post_reactions WHERE post_id=p_post_id AND user_id=actor;delta:=-1;
    ELSE INSERT INTO public.post_reactions(post_id,user_id,reaction) VALUES(p_post_id,actor,p_reaction)
      ON CONFLICT(post_id,user_id) DO UPDATE SET reaction=excluded.reaction,updated_at=statement_timestamp();
      IF old_type IS NULL THEN delta:=1; END IF; END IF;
  END IF;
  UPDATE public.posts SET reactions_count=greatest(0,reactions_count+delta) WHERE id=p_post_id RETURNING reactions_count INTO total;
  RETURN jsonb_build_object('reaction',p_reaction,'reactions_count',total);
END $$;

CREATE OR REPLACE FUNCTION private.create_comment(p_post_id uuid,p_parent_id uuid,p_body text,p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); c public.comments; h text; parent uuid:=p_parent_id; depth integer:=0;
BEGIN
  IF p_body IS NULL OR length(btrim(p_body)) NOT BETWEEN 1 AND 5000 OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_comment'; END IF;
  PERFORM 1 FROM public.posts WHERE id=p_post_id AND private.can_interact_post(id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  h:=encode(sha256(convert_to(jsonb_build_array(p_post_id,p_parent_id,p_body)::text,'UTF8')),'hex');
  SELECT * INTO c FROM public.comments WHERE author_id=actor AND client_request_id=p_client_request_id;
  IF FOUND THEN
    IF c.request_hash<>h OR c.state<>'published' THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
    RETURN to_jsonb(c)-'request_hash'-'client_request_id'; END IF;
  WHILE parent IS NOT NULL LOOP
    SELECT * INTO c FROM public.comments WHERE id=parent AND post_id=p_post_id AND private.can_read_comment(id);
    IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='parent_unavailable'; END IF;
    depth:=depth+1;IF depth>=8 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='comment_depth_exceeded';END IF;parent:=c.parent_id;
  END LOOP;
  INSERT INTO public.comments(post_id,author_id,parent_id,client_request_id,request_hash,body) VALUES(p_post_id,actor,p_parent_id,p_client_request_id,h,p_body) RETURNING * INTO c;
  UPDATE public.posts SET comments_count=comments_count+1 WHERE id=p_post_id;
  RETURN to_jsonb(c)-'request_hash'-'client_request_id';
END $$;

CREATE OR REPLACE FUNCTION private.change_comment(p_comment_id uuid,p_body text,p_expected_version bigint,p_delete boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); c public.comments;
BEGIN
  IF p_comment_id IS NULL OR p_delete IS NULL OR p_expected_version IS NULL OR p_expected_version<1
     OR (p_delete AND p_body IS NOT NULL)
     OR (NOT p_delete AND (p_body IS NULL OR length(btrim(p_body)) NOT BETWEEN 1 AND 5000)) THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_comment_change';
  END IF;
  SELECT * INTO c FROM public.comments WHERE id=p_comment_id AND author_id=actor;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='comment_unavailable'; END IF;
  PERFORM 1 FROM public.posts WHERE id=c.post_id AND private.can_interact_post(id) FOR UPDATE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  IF (p_delete AND c.state='deleted') OR (NOT p_delete AND c.state='published' AND c.body=p_body) THEN RETURN to_jsonb(c)-'request_hash'-'client_request_id'; END IF;
  IF c.state<>'published' OR p_expected_version IS NULL OR c.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
  UPDATE public.comments SET body=CASE WHEN p_delete THEN NULL ELSE p_body END,state=CASE WHEN p_delete THEN 'deleted' ELSE 'published' END,version=version+1,updated_at=statement_timestamp() WHERE id=c.id RETURNING * INTO c;
  IF p_delete THEN UPDATE public.posts SET comments_count=greatest(0,comments_count-1) WHERE id=c.post_id; END IF;
  RETURN to_jsonb(c)-'request_hash'-'client_request_id';
END $$;

CREATE TABLE public.conversation_mutes(
  conversation_id uuid NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  muted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY(conversation_id,user_id)
);
ALTER TABLE public.conversation_mutes ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.conversation_mutes FROM PUBLIC,anon,authenticated,service_role;
CREATE POLICY conversation_mutes_select_own ON public.conversation_mutes FOR SELECT TO authenticated
  USING(user_id=(select auth.uid()) AND private.can_read_conversation(conversation_id));

CREATE OR REPLACE FUNCTION private.set_comment_reaction(p_comment_id uuid,p_reaction text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); c public.comments; old_type text;
BEGIN
  IF p_reaction IS NOT NULL AND p_reaction NOT IN ('like','love','care','haha','wow','sad','angry') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_reaction'; END IF;
  SELECT * INTO c FROM public.comments WHERE id=p_comment_id AND state='published' AND private.can_read_comment(id) FOR UPDATE;
  IF NOT FOUND OR NOT private.can_interact_post(c.post_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='comment_unavailable'; END IF;
  SELECT reaction INTO old_type FROM public.comment_reactions WHERE comment_id=c.id AND user_id=actor;
  IF old_type IS DISTINCT FROM p_reaction THEN
    IF p_reaction IS NULL THEN DELETE FROM public.comment_reactions WHERE comment_id=c.id AND user_id=actor;
    ELSE INSERT INTO public.comment_reactions(comment_id,user_id,reaction) VALUES(c.id,actor,p_reaction)
      ON CONFLICT(comment_id,user_id) DO UPDATE SET reaction=excluded.reaction; END IF;
  END IF;
  RETURN jsonb_build_object('reaction',p_reaction,'reactions_count',
    (SELECT count(*) FROM public.comment_reactions WHERE comment_id=c.id));
END $$;

CREATE OR REPLACE FUNCTION private.list_comments(p_post_id uuid,p_parent_id uuid,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF NOT private.can_read_post(p_post_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  IF p_parent_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.comments WHERE id=p_parent_id AND post_id=p_post_id AND private.can_read_comment(id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='parent_unavailable'; END IF;
  RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.created_at DESC,q.id DESC) FROM (
    SELECT c.id,c.post_id,c.author_id,c.parent_id,c.body,c.state,c.version,c.created_at,c.updated_at,
      (SELECT count(*) FROM public.comment_reactions cr WHERE cr.comment_id=c.id) AS reactions_count,
      (SELECT cr.reaction FROM public.comment_reactions cr WHERE cr.comment_id=c.id AND cr.user_id=auth.uid()) AS my_reaction
    FROM public.comments c WHERE c.post_id=p_post_id AND c.parent_id IS NOT DISTINCT FROM p_parent_id
      AND private.can_read_comment(c.id) AND (p_after_time IS NULL OR (c.created_at,c.id)<(p_after_time,p_after_id))
    ORDER BY c.created_at DESC,c.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.get_conversation_mute(p_conversation_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active();
  IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
  RETURN jsonb_build_object('muted',EXISTS(SELECT 1 FROM public.conversation_mutes WHERE conversation_id=p_conversation_id AND user_id=actor));
END $$;
CREATE FUNCTION private.set_conversation_muted(p_conversation_id uuid,p_muted boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
  IF p_muted IS NULL OR NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
  IF p_muted THEN INSERT INTO public.conversation_mutes(conversation_id,user_id) VALUES(p_conversation_id,actor) ON CONFLICT DO NOTHING;
  ELSE DELETE FROM public.conversation_mutes WHERE conversation_id=p_conversation_id AND user_id=actor; END IF;
  RETURN jsonb_build_object('muted',p_muted);
END $$;

CREATE FUNCTION private.get_shared_media(p_conversation_id uuid,p_kind text,p_before_seq bigint,p_before_position smallint,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
  PERFORM private.b_active();
  IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
  IF p_kind IS NULL OR p_kind NOT IN ('all','image','video') OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
    OR (p_before_seq IS NOT NULL AND p_before_seq<1)
    OR (p_before_seq IS NULL)<>(p_before_position IS NULL)
    OR (p_before_position IS NOT NULL AND p_before_position<0) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_media_cursor'; END IF;
  RETURN jsonb_build_object('items',coalesce((SELECT jsonb_agg(jsonb_build_object('message_id',q.message_id,'seq',q.seq,
      'created_at',q.created_at,'asset_id',q.asset_id,'mime_type',q.mime_type,'kind',q.kind,'position',q.position) ORDER BY q.seq DESC,q.position)
    FROM (SELECT m.id message_id,m.seq,m.created_at,a.id asset_id,a.mime_type,CASE WHEN a.mime_type LIKE 'video/%' THEN 'video' ELSE 'image' END kind,mm.position
      FROM public.messages m JOIN public.message_media mm ON mm.message_id=m.id JOIN public.media_assets a ON a.id=mm.asset_id
      WHERE m.conversation_id=p_conversation_id AND m.state='sent' AND private.can_read_message(m.id) AND private.can_read_media(a.id)
        AND (p_before_seq IS NULL OR m.seq<p_before_seq OR (m.seq=p_before_seq AND mm.position>p_before_position))
        AND (p_kind='all' OR (p_kind='video')=(a.mime_type LIKE 'video/%'))
      ORDER BY m.seq DESC,mm.position LIMIT p_limit) q),'[]'::jsonb));
END $$;

CREATE FUNCTION private.notify_conversation_message() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE recipient uuid; notification_id uuid; message_row public.messages;
BEGIN
  -- Story replies are created by send_message() and linked to their story later
  -- in the same transaction. Re-read the final row state from this deferred
  -- trigger so the existing notifications_story_reply trigger remains the
  -- sole notification path for those messages.
  SELECT * INTO message_row FROM public.messages WHERE id=NEW.id;
  IF NOT FOUND OR message_row.state<>'sent' OR message_row.sender_id IS NULL
     OR message_row.reply_story_id IS NOT NULL THEN RETURN NEW; END IF;
  FOR recipient IN SELECT cm.user_id FROM public.conversation_members cm JOIN public.user_settings s ON s.user_id=cm.user_id
    JOIN public.account_status account ON account.user_id=cm.user_id AND account.state='active'
    JOIN public.profiles profile ON profile.id=cm.user_id AND profile.onboarding_completed
    WHERE cm.conversation_id=message_row.conversation_id AND cm.state='active' AND cm.user_id<>message_row.sender_id
      AND s.notifications_enabled AND coalesce((s.notification_preferences->>'message')::boolean,true)
      AND NOT EXISTS(SELECT 1 FROM public.conversation_mutes m WHERE m.conversation_id=NEW.conversation_id AND m.user_id=cm.user_id)
      -- Use the explicit persisted pair, because authenticated backend writes may have no auth.uid().
      AND NOT EXISTS(SELECT 1 FROM public.user_blocks b
        WHERE (b.blocker_id=message_row.sender_id AND b.blocked_id=cm.user_id)
           OR (b.blocker_id=cm.user_id AND b.blocked_id=message_row.sender_id)) LOOP
    notification_id:=NULL;
    INSERT INTO public.notifications(recipient_id,actor_id,event_key,kind,target_type,target_id)
      VALUES(recipient,message_row.sender_id,'message:'||message_row.id::text,'message','conversation',message_row.conversation_id)
      ON CONFLICT(recipient_id,event_key) DO NOTHING RETURNING id INTO notification_id;
    IF notification_id IS NOT NULL THEN
      PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',notification_id,'scope','notifications'),
        'invalidate','user:'||recipient::text,true);
    END IF;
  END LOOP;
  RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER conversation_message_notification AFTER INSERT ON public.messages
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION private.notify_conversation_message();

CREATE FUNCTION public.set_comment_reaction(p_comment_id uuid,p_reaction text) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_comment_reaction($1,$2) $$;
CREATE OR REPLACE FUNCTION public.list_comments(p_post_id uuid,p_parent_id uuid DEFAULT NULL,p_after_time timestamptz DEFAULT NULL,p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_comments($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.get_conversation_mute(p_conversation_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_conversation_mute($1) $$;
CREATE FUNCTION public.set_conversation_muted(p_conversation_id uuid,p_muted boolean) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.set_conversation_muted($1,$2) $$;
CREATE FUNCTION public.get_shared_media(p_conversation_id uuid,p_kind text DEFAULT 'all',p_before_seq bigint DEFAULT NULL,p_before_position smallint DEFAULT NULL,p_limit integer DEFAULT 30)
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_shared_media($1,$2,$3,$4,$5) $$;

REVOKE ALL ON FUNCTION private.set_comment_reaction(uuid,text),private.list_comments(uuid,uuid,timestamptz,uuid,integer),
  private.get_conversation_mute(uuid),private.set_conversation_muted(uuid,boolean),private.get_shared_media(uuid,text,bigint,smallint,integer),private.notify_conversation_message()
  FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.set_comment_reaction(uuid,text),private.list_comments(uuid,uuid,timestamptz,uuid,integer),
  private.get_conversation_mute(uuid),private.set_conversation_muted(uuid,boolean),private.get_shared_media(uuid,text,bigint,smallint,integer)
  TO authenticated;
REVOKE ALL ON FUNCTION public.set_comment_reaction(uuid,text),public.list_comments(uuid,uuid,timestamptz,uuid,integer),
  public.get_conversation_mute(uuid),public.set_conversation_muted(uuid,boolean),public.get_shared_media(uuid,text,bigint,smallint,integer)
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.set_comment_reaction(uuid,text),public.list_comments(uuid,uuid,timestamptz,uuid,integer),
  public.get_conversation_mute(uuid),public.set_conversation_muted(uuid,boolean),public.get_shared_media(uuid,text,bigint,smallint,integer)
  TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
