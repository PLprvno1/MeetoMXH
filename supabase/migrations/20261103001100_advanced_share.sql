-- Task 22 / migration 029: safe group reposts and structured Messenger post references.
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.posts') IS NULL OR to_regclass('public.messages') IS NULL
     OR to_regclass('public.groups') IS NULL OR to_regclass('public.group_members') IS NULL
     OR to_regclass('public.user_settings') IS NULL OR to_regclass('public.notifications') IS NULL
     OR to_regclass('public.user_blocks') IS NULL OR to_regclass('public.profiles') IS NULL
     OR to_regclass('public.account_status') IS NULL OR to_regclass('public.conversations') IS NULL
     OR to_regclass('public.conversation_members') IS NULL OR to_regclass('public.message_media') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post_base(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('private.group_member(uuid)') IS NULL
     OR to_regprocedure('private.c_lock(uuid,boolean)') IS NULL
     OR to_regprocedure('private.c_message(uuid)') IS NULL
     OR to_regprocedure('private.c_event(uuid,text,uuid[],uuid)') IS NULL
     OR to_regprocedure('realtime.send(jsonb,text,text,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Advanced Share prerequisites missing; verify migrations 001-028';
  END IF;
END $$;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.notifications'::regclass
      AND conname='notifications_kind_check' AND pg_get_constraintdef(oid) ILIKE '%share%')
     OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.user_settings'::regclass
      AND conname='user_settings_prefs_keys' AND pg_get_constraintdef(oid) ILIKE '%share%') THEN
    RAISE EXCEPTION 'Advanced Share requires notification share kind/preferences from migrations 022+';
  END IF;
END $$;

-- Allow the existing post reference model to represent a group repost. Keep the
-- audience/group invariant and require every share to reference an original.
DO $$
BEGIN
  -- Migration 001 creates unnamed table checks in this order: posts_check is
  -- (group_id,audience), posts_check1 is (kind,original_post_id,group_id), and
  -- posts_check2 is (original_post_id,id). Replace only the middle invariant.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.posts'::regclass
      AND contype='c' AND conname='posts_check1'
      AND pg_get_constraintdef(oid) ILIKE '%kind = ''share''%'
      AND pg_get_constraintdef(oid) ILIKE '%original_post_id IS NOT NULL%'
      AND pg_get_constraintdef(oid) ILIKE '%group_id IS NULL%'
      AND pg_get_constraintdef(oid) ILIKE '%kind = ''original''%'
      AND pg_get_constraintdef(oid) ILIKE '%original_post_id IS NULL%')
     OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.posts'::regclass
      AND contype='c' AND conname='posts_check'
      AND pg_get_constraintdef(oid) ILIKE '%group_id IS NULL%'
      AND pg_get_constraintdef(oid) ILIKE '%audience <>%'
      AND pg_get_constraintdef(oid) ILIKE '%group_id IS NOT NULL%'
      AND pg_get_constraintdef(oid) ILIKE '%audience =%')
     OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.posts'::regclass
      AND contype='c' AND conname='posts_check2'
      AND pg_get_constraintdef(oid) ILIKE '%original_post_id IS NULL%'
      AND pg_get_constraintdef(oid) ILIKE '%original_post_id <> id%') THEN
    RAISE EXCEPTION 'Unexpected public.posts checks; expected the migration 001 constraints before replacing posts_check1';
  END IF;
END $$;
ALTER TABLE public.posts DROP CONSTRAINT posts_check1;
ALTER TABLE public.posts ADD CONSTRAINT posts_share_reference_check
  CHECK ((kind='share' AND original_post_id IS NOT NULL)
      OR (kind='original' AND original_post_id IS NULL));
-- Reset table-level SELECT, then restore the same safe column contract from
-- migration 004 without original_post_id. RLS remains enabled and posts_select
-- still gates every row through can_read_post(id).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid='public.posts'::regclass AND c.relrowsecurity)
     OR NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='posts'
       AND policyname='posts_select' AND cmd='SELECT' AND qual ILIKE '%can_read_post%') THEN
    RAISE EXCEPTION 'Advanced Share requires posts RLS and the posts_select can_read_post policy';
  END IF;
END $$;
REVOKE SELECT ON TABLE public.posts FROM PUBLIC,anon,authenticated;
REVOKE SELECT (original_post_id) ON TABLE public.posts FROM PUBLIC,anon,authenticated;
GRANT SELECT(id,author_id,group_id,kind,body,audience,state,reactions_count,comments_count,
  shares_count,created_at,updated_at,deleted_at,version) ON public.posts TO authenticated;
DO $$
BEGIN
  IF has_table_privilege('authenticated','public.posts','SELECT')
     OR has_column_privilege('authenticated','public.posts','original_post_id','SELECT') THEN
    RAISE EXCEPTION 'public.posts direct SELECT still exposes table or original_post_id privileges';
  END IF;
END $$;

ALTER TABLE public.messages ADD COLUMN shared_post_id uuid REFERENCES public.posts(id) ON DELETE SET NULL;
ALTER TABLE public.messages ADD COLUMN is_shared_post boolean NOT NULL DEFAULT false;
CREATE INDEX messages_shared_post_idx ON public.messages(shared_post_id) WHERE shared_post_id IS NOT NULL;

-- Preserve the existing message projection, adding only a viewer-authorized preview.
CREATE OR REPLACE FUNCTION private.c_message(mid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',m.id,'conversation_id',m.conversation_id,'seq',m.seq,'sender_id',m.sender_id,'kind',m.kind,'body',m.body,'state',m.state,
 'version',m.version,'created_at',m.created_at,'edited_at',m.edited_at,'recalled_at',m.recalled_at,
 'reply_to_message_id',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN m.reply_to_message_id END,
 'reply',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN (SELECT jsonb_build_object('id',r.id,'sender_id',r.sender_id,'state',r.state,'body',r.body,'kind',r.kind) FROM public.messages r WHERE r.id=m.reply_to_message_id) END,
 'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',mm.asset_id,'position',mm.position) ORDER BY mm.position) FROM public.message_media mm WHERE mm.message_id=m.id AND m.state='sent' AND private.can_read_media(mm.asset_id)),'[]'::jsonb),
 'shared_post_present',m.state='sent' AND m.is_shared_post,
 'shared_post_available',m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL AND private.can_read_post(m.shared_post_id),
 'shared_post',CASE WHEN m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL AND private.can_read_post(m.shared_post_id)
   THEN private.post_json(m.shared_post_id) ELSE NULL END)
 FROM public.messages m WHERE m.id=mid AND private.can_read_message(m.id);
$$;

CREATE FUNCTION private.send_shared_post(p_conversation_id uuid,p_original uuid,p_client_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); m public.messages; seq bigint; fingerprint text;
BEGIN
  IF p_original IS NULL OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_shared_post';
  END IF;
  fingerprint:=encode(sha256(convert_to(jsonb_build_array('shared_post',p_original)::text,'UTF8')),'hex');
  SELECT * INTO m FROM public.messages WHERE conversation_id=p_conversation_id AND sender_id=actor
    AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF m.request_hash<>fingerprint OR NOT m.is_shared_post
       OR (m.shared_post_id IS NOT NULL AND m.shared_post_id IS DISTINCT FROM p_original) THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
    END IF;
    RETURN private.c_message(m.id);
  END IF;
  PERFORM 1 FROM public.posts WHERE id=p_original AND kind='original'
    AND state='published' AND private.can_read_post(id) FOR SHARE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='source_unavailable'; END IF;
  UPDATE public.conversations SET next_seq=next_seq+1,updated_at=statement_timestamp(),version=version+1
    WHERE id=p_conversation_id RETURNING next_seq INTO seq;
  INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,kind,body,shared_post_id,is_shared_post)
    VALUES(p_conversation_id,seq,actor,p_client_request_id,fingerprint,'text','Shared a post',p_original,true)
    RETURNING * INTO m;
  UPDATE public.conversations SET last_message_id=m.id WHERE id=p_conversation_id;
  PERFORM private.c_event(p_conversation_id,'message',NULL,m.id);
  RETURN private.c_message(m.id);
END $$;

CREATE FUNCTION private.share_community_post(p_group_id uuid,p_original uuid,p_body text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); g public.groups; p public.posts; fingerprint text;
BEGIN
  PERFORM private.b_active();
  IF p_group_id IS NULL OR p_original IS NULL OR p_body IS NULL OR char_length(p_body)>20000
     OR p_client_request_id IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_group_share'; END IF;
  SELECT * INTO g FROM public.groups WHERE id=p_group_id AND state='active' FOR UPDATE;
  IF NOT FOUND OR NOT private.group_member(g.id)
     OR EXISTS(SELECT 1 FROM public.group_members WHERE group_id=g.id AND user_id=actor AND state='banned') THEN
    RAISE SQLSTATE 'PT403' USING MESSAGE='group_member_required';
  END IF;
  fingerprint:=encode(sha256(convert_to(jsonb_build_array('group_share',p_group_id,p_original,p_body)::text,'UTF8')),'hex');
  SELECT * INTO p FROM public.posts WHERE author_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF p.group_id IS DISTINCT FROM p_group_id OR p.original_post_id IS DISTINCT FROM p_original
       OR p.request_hash<>fingerprint THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
    RETURN jsonb_build_object('id',p.id,'state',p.state,'created_at',p.created_at);
  END IF;
  PERFORM 1 FROM public.posts WHERE id=p_original AND kind='original' AND state='published'
    AND private.can_read_post(id) FOR SHARE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='source_unavailable'; END IF;
  INSERT INTO public.posts(author_id,group_id,client_request_id,request_hash,body,audience,kind,original_post_id,state)
    VALUES(actor,g.id,p_client_request_id,fingerprint,btrim(p_body),'group','share',p_original,
      CASE WHEN g.post_approval AND NOT EXISTS(SELECT 1 FROM public.group_members
        WHERE group_id=g.id AND user_id=actor AND role IN ('owner','admin','moderator') AND state='active')
        THEN 'pending_review' ELSE 'published' END) RETURNING * INTO p;
  RETURN jsonb_build_object('id',p.id,'state',p.state,'created_at',p.created_at);
END $$;

-- shares_count is the number of currently published Feed/Group repost rows.
-- Messenger references are message shares and intentionally do not affect it.
-- Group pending/rejected posts do not count; publish/removal transitions count
-- once in this trigger. Personal reposts retain migration 004's insert/delete logic.
CREATE FUNCTION private.adjust_group_repost_count() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.kind='share' AND NEW.group_id IS NOT NULL AND NEW.state='published' THEN
      UPDATE public.posts SET shares_count=shares_count+1 WHERE id=NEW.original_post_id;
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.kind='share' AND OLD.group_id IS NOT NULL
     AND NEW.kind='share' AND NEW.group_id=OLD.group_id
     AND NEW.original_post_id=OLD.original_post_id THEN
    IF OLD.state<>'published' AND NEW.state='published' THEN
      UPDATE public.posts SET shares_count=shares_count+1 WHERE id=NEW.original_post_id;
    ELSIF OLD.state='published' AND NEW.state<>'published' THEN
      UPDATE public.posts SET shares_count=greatest(0,shares_count-1) WHERE id=OLD.original_post_id;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER group_repost_count_insert AFTER INSERT ON public.posts
  FOR EACH ROW WHEN (NEW.kind='share' AND NEW.group_id IS NOT NULL)
  EXECUTE FUNCTION private.adjust_group_repost_count();
CREATE TRIGGER group_repost_count_state AFTER UPDATE OF state ON public.posts
  FOR EACH ROW WHEN (OLD.kind='share' AND OLD.group_id IS NOT NULL AND NEW.original_post_id=OLD.original_post_id)
  EXECUTE FUNCTION private.adjust_group_repost_count();

-- A single insert trigger notifies the original author; event_key makes retries idempotent.
CREATE FUNCTION private.notify_post_share() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE recipient uuid; notification_id uuid;
BEGIN
  IF NEW.kind<>'share' OR NEW.state<>'published' OR NEW.author_id IS NULL THEN RETURN NEW; END IF;
  SELECT p.author_id INTO recipient FROM public.posts p WHERE p.id=NEW.original_post_id AND p.state='published';
  IF recipient IS NULL OR recipient=NEW.author_id OR NOT EXISTS(
    SELECT 1 FROM public.account_status s JOIN public.profiles p ON p.id=s.user_id
    WHERE s.user_id=recipient AND s.state='active' AND p.onboarding_completed)
    OR EXISTS(SELECT 1 FROM public.user_blocks b WHERE
      (b.blocker_id=NEW.author_id AND b.blocked_id=recipient) OR (b.blocker_id=recipient AND b.blocked_id=NEW.author_id))
    OR NOT EXISTS(SELECT 1 FROM public.user_settings s WHERE s.user_id=recipient AND s.notifications_enabled
      AND coalesce((s.notification_preferences->>'share')::boolean,true)) THEN
    RETURN NEW;
  END IF;
  INSERT INTO public.notifications(recipient_id,actor_id,event_key,kind,target_type,target_id)
    VALUES(recipient,NEW.author_id,'share:'||NEW.id::text,'share','post',NEW.original_post_id)
    ON CONFLICT(recipient_id,event_key) DO NOTHING RETURNING id INTO notification_id;
  IF notification_id IS NOT NULL THEN
    PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',notification_id,'scope','notifications'),
      'invalidate','user:'||recipient::text,true);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER notifications_post_share AFTER INSERT ON public.posts
  FOR EACH ROW WHEN (NEW.kind='share') EXECUTE FUNCTION private.notify_post_share();
CREATE TRIGGER notifications_approved_group_share AFTER UPDATE OF state ON public.posts
  FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM 'published' AND NEW.state='published' AND NEW.kind='share')
  EXECUTE FUNCTION private.notify_post_share();

CREATE FUNCTION public.send_shared_post(p_conversation_id uuid,p_original uuid,p_client_request_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.send_shared_post($1,$2,$3) $$;
CREATE FUNCTION public.share_community_post(p_group_id uuid,p_original uuid,p_body text,p_client_request_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.share_community_post($1,$2,$3,$4) $$;

REVOKE ALL ON FUNCTION private.send_shared_post(uuid,uuid,uuid),private.share_community_post(uuid,uuid,text,uuid),
  private.notify_post_share(),private.adjust_group_repost_count() FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.send_shared_post(uuid,uuid,uuid),private.share_community_post(uuid,uuid,text,uuid)
  TO authenticated;
REVOKE ALL ON FUNCTION public.send_shared_post(uuid,uuid,uuid),public.share_community_post(uuid,uuid,text,uuid)
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.send_shared_post(uuid,uuid,uuid),public.share_community_post(uuid,uuid,text,uuid)
  TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
