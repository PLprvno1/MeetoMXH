-- Task 23 / migration 030: structured mentions, post tags, privacy and tag review.
BEGIN;

DO $$
BEGIN
  IF to_regclass('public.profiles') IS NULL OR to_regclass('public.posts') IS NULL
     OR to_regclass('public.comments') IS NULL OR to_regclass('public.user_settings') IS NULL
     OR to_regclass('public.notifications') IS NULL OR to_regclass('public.account_status') IS NULL
     OR to_regclass('public.user_blocks') IS NULL OR to_regclass('public.friendships') IS NULL
     OR to_regclass('public.groups') IS NULL OR to_regclass('public.group_members') IS NULL
     OR to_regclass('public.saved_posts') IS NULL OR to_regclass('public.post_media') IS NULL
     OR to_regclass('public.media_assets') IS NULL
     OR to_regprocedure('private.b_actor()') IS NULL OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post_base(uuid)') IS NULL
     OR to_regprocedure('private.can_read_comment(uuid)') IS NULL
     OR to_regprocedure('private.are_friends(uuid)') IS NULL
     OR to_regprocedure('private.create_post(text,text,uuid[],uuid,uuid)') IS NULL
     OR to_regprocedure('private.edit_post(uuid,text,text,uuid[],bigint)') IS NULL
     OR to_regprocedure('private.create_comment(uuid,uuid,text,uuid)') IS NULL
     OR to_regprocedure('private.change_comment(uuid,text,bigint,boolean)') IS NULL
     OR to_regprocedure('private.create_community_post(uuid,text,uuid[],uuid)') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('realtime.send(jsonb,text,text,boolean)') IS NULL THEN
    RAISE EXCEPTION 'Tag/Mention prerequisites missing; verify migrations 001-029 and deployed schema';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_class c WHERE c.oid='public.notifications'::regclass AND c.relrowsecurity)
     OR NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='notifications'
       AND policyname='notifications_select' AND cmd='SELECT' AND qual ILIKE '%recipient_id%')
     OR has_table_privilege('authenticated','public.notifications','SELECT')
     OR has_table_privilege('authenticated','public.notifications','INSERT')
     OR has_table_privilege('authenticated','public.notifications','UPDATE') THEN
    RAISE EXCEPTION 'Migration 030 requires recipient-only notifications RLS';
  END IF;
END $$;

ALTER TABLE public.user_settings
  ADD COLUMN tagging_policy text NOT NULL DEFAULT 'friends'
    CHECK (tagging_policy IN ('everyone','friends','nobody')),
  ADD COLUMN tag_review_required boolean NOT NULL DEFAULT true;
ALTER TABLE public.posts ADD COLUMN mention_request_hash text CHECK (mention_request_hash IS NULL OR char_length(mention_request_hash)=64);
ALTER TABLE public.comments ADD COLUMN mention_request_hash text CHECK (mention_request_hash IS NULL OR char_length(mention_request_hash)=64);

ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_kind_check;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_kind_check
  CHECK (kind IN ('friend_request','friend_accepted','reaction','comment','reply','share','story','group',
    'message','moderation','follow','memories','mention','tag'));
ALTER TABLE public.user_settings DROP CONSTRAINT IF EXISTS user_settings_prefs_keys;
ALTER TABLE public.user_settings ADD CONSTRAINT user_settings_prefs_keys
  CHECK ((notification_preferences - ARRAY['friend_request','friend_accepted','reaction','comment','reply',
    'share','story','group','message','moderation','follow','memories','mention','tag']) = '{}'::jsonb);

CREATE TABLE public.content_mentions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  content_type text NOT NULL CHECK (content_type IN ('post','comment')),
  content_id uuid NOT NULL,
  mentioned_user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  span_start_utf16 integer NOT NULL CHECK (span_start_utf16>=0),
  span_end_utf16 integer NOT NULL CHECK (span_end_utf16>span_start_utf16),
  display_label text NOT NULL CHECK (char_length(display_label) BETWEEN 2 AND 201),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  CHECK (display_label LIKE '@%'),
  UNIQUE(content_type,content_id,span_start_utf16),
  UNIQUE(content_type,content_id,mentioned_user_id,span_start_utf16)
);
CREATE INDEX content_mentions_user_idx ON public.content_mentions(mentioned_user_id,created_at DESC,id DESC);
CREATE INDEX content_mentions_content_idx ON public.content_mentions(content_type,content_id,span_start_utf16);
ALTER TABLE public.content_mentions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.content_mentions FROM PUBLIC,anon,authenticated,service_role;
CREATE POLICY content_mentions_select_visible ON public.content_mentions FOR SELECT TO authenticated
  USING (private.is_active() AND mentioned_user_id=(select auth.uid()));

CREATE TABLE public.post_user_tags (
  post_id uuid NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
  tagged_user_id uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  tagged_by uuid NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
  display_name_snapshot text NOT NULL CHECK (char_length(display_name_snapshot) BETWEEN 1 AND 100),
  state text NOT NULL CHECK (state IN ('pending','accepted','rejected','removed')),
  request_id uuid NOT NULL DEFAULT gen_random_uuid(),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  resolved_at timestamptz,
  PRIMARY KEY(post_id,tagged_user_id),
  CHECK ((state='pending')=(resolved_at IS NULL))
);
CREATE INDEX post_user_tags_inbox_idx ON public.post_user_tags(tagged_user_id,state,created_at DESC,post_id);
CREATE INDEX post_user_tags_profile_idx ON public.post_user_tags(tagged_user_id,created_at DESC,post_id) WHERE state='accepted';
ALTER TABLE public.post_user_tags ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.post_user_tags FROM PUBLIC,anon,authenticated,service_role;
CREATE POLICY post_user_tags_recipient_select ON public.post_user_tags FOR SELECT TO authenticated
  USING (private.is_active() AND tagged_user_id=(select auth.uid()));

-- PostgreSQL character offsets count Unicode code points; Android spans count UTF-16 units.
-- Convert by walking code points, so emoji/surrogate pairs cannot shift mention identity.
CREATE FUNCTION private.utf16_slice(p_text text,p_start integer,p_end integer) RETURNS text
LANGUAGE plpgsql IMMUTABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE i integer; units integer:=0; width integer; start_found boolean:=false; end_found boolean:=false; result text:=''; ch text;
BEGIN
  IF p_text IS NULL OR p_start IS NULL OR p_end IS NULL OR p_start<0 OR p_end<=p_start THEN RETURN NULL; END IF;
  FOR i IN 1..char_length(p_text) LOOP
    IF units=p_start THEN start_found:=true; END IF;
    -- The end boundary is valid as soon as the running UTF-16 offset reaches it;
    -- stop before scanning the rest of the string.
    IF units=p_end THEN end_found:=true; EXIT; END IF;
    ch:=substr(p_text,i,1); width:=CASE WHEN ascii(ch)>65535 THEN 2 ELSE 1 END;
    -- Reject either endpoint if it falls inside a supplementary code point.
    IF (units<p_start AND units+width>p_start) OR (units<p_end AND units+width>p_end) THEN RETURN NULL; END IF;
    IF units>=p_start AND units+width<=p_end THEN result:=result||ch;
    END IF;
    units:=units+width;
    IF units=p_end THEN end_found:=true; EXIT; END IF;
  END LOOP;
  IF units=p_start THEN start_found:=true; END IF;
  IF units=p_end THEN end_found:=true; END IF;
  IF NOT start_found OR NOT end_found THEN RETURN NULL; END IF;
  RETURN result;
END $$;

-- Group post creation trims surrounding ASCII spaces in migration 024. Translate
-- Android UTF-16 offsets by that removed prefix, then validate against stored text.
CREATE FUNCTION private.mentions_for_trimmed_body(p_body text,p_mentions jsonb) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE prefix text; prefix_units integer:=0; i integer; item jsonb; result jsonb:='[]'::jsonb; st integer; en integer; ch text;
BEGIN
  IF p_body IS NULL OR p_mentions IS NULL OR jsonb_typeof(p_mentions) IS DISTINCT FROM 'array' THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mentions';
  END IF;
  prefix:=left(p_body,char_length(p_body)-char_length(ltrim(p_body)));
  FOR i IN 1..char_length(prefix) LOOP
    ch:=substr(prefix,i,1); prefix_units:=prefix_units+CASE WHEN ascii(ch)>65535 THEN 2 ELSE 1 END;
  END LOOP;
  FOR item IN SELECT value FROM jsonb_array_elements(p_mentions) LOOP
    BEGIN st:=(item->>'start_utf16')::integer; en:=(item->>'end_utf16')::integer;
    EXCEPTION WHEN others THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mentions'; END;
    result:=result||jsonb_build_array(jsonb_set(jsonb_set(item,'{start_utf16}',to_jsonb(st-prefix_units),true),
      '{end_utf16}',to_jsonb(en-prefix_units),true));
  END LOOP;
  RETURN result;
END $$;

-- Evaluate an audience for a recipient UUID without depending on auth.uid().
CREATE FUNCTION private.user_can_read_post(p_post_id uuid,p_user_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT p_user_id IS NOT NULL AND EXISTS(
    SELECT 1 FROM public.posts p
      JOIN public.account_status author_status ON author_status.user_id=p.author_id AND author_status.state='active'
      JOIN public.account_status viewer_status ON viewer_status.user_id=p_user_id AND viewer_status.state='active'
    WHERE p.id=p_post_id AND p.state='published'
      AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
        (b.blocker_id=p.author_id AND b.blocked_id=p_user_id) OR (b.blocker_id=p_user_id AND b.blocked_id=p.author_id))
      AND ((p.group_id IS NOT NULL AND EXISTS(SELECT 1 FROM public.group_members gm
            JOIN public.groups g ON g.id=gm.group_id AND g.state='active'
            WHERE gm.group_id=p.group_id AND gm.user_id=p_user_id AND gm.state='active'))
        OR (p.group_id IS NULL AND (p.author_id=p_user_id OR p.audience='public'
          OR (p.audience='friends' AND EXISTS(SELECT 1 FROM public.friendships f
            WHERE f.user_low=least(p.author_id,p_user_id) AND f.user_high=greatest(p.author_id,p_user_id))))))
  )
$$;

CREATE FUNCTION private.user_can_read_comment(p_comment_id uuid,p_user_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT EXISTS(SELECT 1 FROM public.comments c WHERE c.id=p_comment_id AND c.state='published'
    AND private.user_can_read_post(c.post_id,p_user_id)
    AND (c.author_id IS NULL OR NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
      (b.blocker_id=c.author_id AND b.blocked_id=p_user_id) OR (b.blocker_id=p_user_id AND b.blocked_id=c.author_id))))
$$;

CREATE FUNCTION private.sync_content_mentions(p_type text,p_id uuid,p_body text,p_mentions jsonb) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); item jsonb; uid uuid; st integer; en integer; label text; profile_name text;
  existing_id uuid; used_ids uuid[]:='{}'::uuid[]; starts integer[]:='{}'::integer[]; ends integer[]:='{}'::integer[]; i integer;
BEGIN
  IF p_type IS NULL OR p_type NOT IN ('post','comment') OR p_id IS NULL OR p_body IS NULL OR p_mentions IS NULL
     OR jsonb_typeof(p_mentions) IS DISTINCT FROM 'array' THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mentions';
  END IF;
  IF jsonb_array_length(p_mentions)>30 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mentions'; END IF;
  IF p_type='post' AND NOT EXISTS(SELECT 1 FROM public.posts p WHERE p.id=p_id AND p.author_id=actor
      AND p.state IN ('published','pending_review') AND private.can_read_post(p.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable';
  ELSIF p_type='comment' AND NOT EXISTS(SELECT 1 FROM public.comments c WHERE c.id=p_id AND c.author_id=actor AND c.state='published' AND private.can_read_comment(c.id)) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='comment_unavailable';
  END IF;
  -- Move existing spans outside the legal body range before applying new positions;
  -- this makes swaps/reordering collision-safe with the unique span index.
  UPDATE public.content_mentions SET span_start_utf16=span_start_utf16+1000000,
    span_end_utf16=span_end_utf16+1000000
    WHERE content_type=p_type AND content_id=p_id;
  FOR item IN SELECT value FROM jsonb_array_elements(p_mentions) LOOP
    BEGIN uid:=(item->>'user_id')::uuid; st:=(item->>'start_utf16')::integer; en:=(item->>'end_utf16')::integer;
    EXCEPTION WHEN others THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mentions'; END;
    IF uid IS NULL OR st IS NULL OR en IS NULL OR st<0 OR en<=st OR st=ANY(starts)
       OR private.utf16_slice(p_body,st,en) IS DISTINCT FROM item->>'label'
       OR char_length(item->>'label') NOT BETWEEN 2 AND 201 OR left(item->>'label',1)<>'@' THEN
      RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mentions';
    END IF;
    FOR i IN 1..coalesce(array_length(starts,1),0) LOOP
      IF st<ends[i] AND en>starts[i] THEN RAISE SQLSTATE 'PT422' USING MESSAGE='overlapping_mentions'; END IF;
    END LOOP;
    starts:=array_append(starts,st); ends:=array_append(ends,en);
    SELECT p.display_name INTO profile_name FROM public.profiles p JOIN public.account_status a ON a.user_id=p.id
      WHERE p.id=uid AND p.onboarding_completed AND a.state='active' AND private.can_read_profile(p.id)
        AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
          (b.blocker_id=actor AND b.blocked_id=uid) OR (b.blocker_id=uid AND b.blocked_id=actor))
        AND CASE p_type WHEN 'post' THEN (private.user_can_read_post(p_id,uid) OR EXISTS(
            SELECT 1 FROM public.posts p JOIN public.group_members gm ON gm.group_id=p.group_id
              AND gm.user_id=uid AND gm.state='active'
            WHERE p.id=p_id AND p.state='pending_review' AND gm.group_id IS NOT NULL))
          ELSE private.user_can_read_comment(p_id,uid) END;
    IF profile_name IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='mention_target_unavailable'; END IF;
    IF item->>'label' IS DISTINCT FROM '@'||profile_name AND NOT EXISTS(
      SELECT 1 FROM public.content_mentions prior WHERE prior.content_type=p_type AND prior.content_id=p_id
        AND prior.mentioned_user_id=uid AND prior.display_label=item->>'label') THEN
      RAISE SQLSTATE 'PT422' USING MESSAGE='mention_label_mismatch';
    END IF;
    existing_id:=NULL;
    SELECT m.id INTO existing_id FROM public.content_mentions m WHERE m.content_type=p_type AND m.content_id=p_id
      AND m.mentioned_user_id=uid AND NOT (m.id=ANY(used_ids)) ORDER BY m.span_start_utf16 LIMIT 1 FOR UPDATE;
    IF existing_id IS NULL THEN
      INSERT INTO public.content_mentions(content_type,content_id,mentioned_user_id,span_start_utf16,span_end_utf16,display_label)
        VALUES(p_type,p_id,uid,st,en,item->>'label') RETURNING id INTO existing_id;
    ELSE
      UPDATE public.content_mentions SET span_start_utf16=st,span_end_utf16=en,display_label=item->>'label'
        WHERE id=existing_id;
    END IF;
    used_ids:=array_append(used_ids,existing_id);
  END LOOP;
  DELETE FROM public.content_mentions m WHERE m.content_type=p_type AND m.content_id=p_id AND NOT (m.id=ANY(used_ids));
END $$;

CREATE FUNCTION private.sync_post_tags(p_post_id uuid,p_users uuid[]) RETURNS void
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
        AND (s.tagging_policy='everyone' OR (s.tagging_policy='friends' AND EXISTS(
          SELECT 1 FROM public.friendships f WHERE f.user_low=least(actor,uid) AND f.user_high=greatest(actor,uid))))
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

CREATE FUNCTION private.bind_mention_request(p_type text,p_content_id uuid,p_client_request_id uuid,p_hash text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); existing_hash text; changed integer;
BEGIN
  IF p_type IS NULL OR p_type NOT IN ('post','group_post','comment') OR p_content_id IS NULL OR p_client_request_id IS NULL
     OR p_hash IS NULL OR char_length(p_hash)<>64 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_content_request'; END IF;
  IF p_type='comment' THEN
    UPDATE public.comments SET mention_request_hash=p_hash WHERE id=p_content_id AND author_id=actor
      AND client_request_id=p_client_request_id AND mention_request_hash IS NULL;
  ELSE
    UPDATE public.posts SET mention_request_hash=p_hash WHERE id=p_content_id AND author_id=actor
      AND client_request_id=p_client_request_id AND mention_request_hash IS NULL;
  END IF;
  GET DIAGNOSTICS changed=ROW_COUNT;
  IF changed=1 THEN RETURN; END IF;
  IF p_type='comment' THEN SELECT mention_request_hash INTO existing_hash FROM public.comments WHERE id=p_content_id
    AND author_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  ELSE SELECT mention_request_hash INTO existing_hash FROM public.posts WHERE id=p_content_id
    AND author_id=actor AND client_request_id=p_client_request_id FOR UPDATE; END IF;
  IF existing_hash IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='content_unavailable'; END IF;
  IF existing_hash IS DISTINCT FROM p_hash THEN RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict'; END IF;
END $$;

CREATE FUNCTION private.emit_content_attention(p_recipient uuid,p_actor uuid,p_kind text,
  p_target_type text,p_target_id uuid,p_event_key text,p_visible boolean) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE notification_id uuid;
BEGIN
  IF p_recipient IS NULL OR p_actor IS NULL OR p_actor=p_recipient OR p_target_id IS NULL
     OR p_event_key IS NULL OR char_length(p_event_key)>240 OR NOT coalesce(p_visible,false)
     OR EXISTS(SELECT 1 FROM public.user_blocks b WHERE
       (b.blocker_id=p_actor AND b.blocked_id=p_recipient) OR (b.blocker_id=p_recipient AND b.blocked_id=p_actor))
     OR NOT EXISTS(SELECT 1 FROM public.account_status a JOIN public.profiles p ON p.id=a.user_id
       WHERE a.user_id=p_recipient AND a.state='active' AND p.onboarding_completed)
     OR NOT EXISTS(SELECT 1 FROM public.user_settings s WHERE s.user_id=p_recipient AND s.notifications_enabled
       AND coalesce((s.notification_preferences->>p_kind)::boolean,true)) THEN RETURN; END IF;
  INSERT INTO public.notifications(recipient_id,actor_id,event_key,kind,target_type,target_id)
    VALUES(p_recipient,p_actor,p_event_key,p_kind,p_target_type,p_target_id)
    ON CONFLICT(recipient_id,event_key) DO NOTHING RETURNING id INTO notification_id;
  IF notification_id IS NOT NULL THEN
    PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',notification_id,'scope','notifications'),
      'invalidate','user:'||p_recipient::text,true);
  END IF;
END $$;

CREATE FUNCTION private.notify_content_mention() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE author uuid; target_type text; target_id uuid; visible boolean;
BEGIN
  IF NEW.content_type='post' THEN
    SELECT p.author_id,private.user_can_read_post(p.id,NEW.mentioned_user_id)
      INTO author,visible FROM public.posts p WHERE p.id=NEW.content_id AND p.state='published';
    target_type:='post';target_id:=NEW.content_id;
  ELSE
    SELECT c.author_id,private.user_can_read_comment(c.id,NEW.mentioned_user_id)
      INTO author,visible FROM public.comments c WHERE c.id=NEW.content_id AND c.state='published';
    target_type:='comment';target_id:=NEW.content_id;
  END IF;
  PERFORM private.emit_content_attention(NEW.mentioned_user_id,author,'mention',target_type,target_id,
    'mention:'||NEW.content_type||':'||NEW.content_id::text||':'||NEW.mentioned_user_id::text,visible);
  RETURN NEW;
END $$;
CREATE FUNCTION private.notify_post_user_tag() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
BEGIN
  IF NEW.state IN ('pending','accepted') THEN
    PERFORM private.emit_content_attention(NEW.tagged_user_id,NEW.tagged_by,'tag','post',NEW.post_id,
      'tag:'||NEW.post_id::text||':'||NEW.tagged_user_id::text||':'||NEW.request_id::text,
      private.user_can_read_post(NEW.post_id,NEW.tagged_user_id));
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER content_mentions_notify AFTER INSERT ON public.content_mentions
  FOR EACH ROW EXECUTE FUNCTION private.notify_content_mention();
CREATE TRIGGER post_user_tags_notify AFTER INSERT ON public.post_user_tags
  FOR EACH ROW EXECUTE FUNCTION private.notify_post_user_tag();
CREATE TRIGGER post_user_tags_state_notify AFTER UPDATE OF state ON public.post_user_tags
  FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM NEW.state) EXECUTE FUNCTION private.notify_post_user_tag();

CREATE FUNCTION private.notify_approved_group_attention() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE mention_row record; tag_row record;
BEGIN
  IF OLD.state IS DISTINCT FROM 'published' AND NEW.state='published' THEN
    FOR mention_row IN SELECT m.mentioned_user_id FROM public.content_mentions m
      WHERE m.content_type='post' AND m.content_id=NEW.id LOOP
      PERFORM private.emit_content_attention(mention_row.mentioned_user_id,NEW.author_id,'mention','post',NEW.id,
        'mention:post:'||NEW.id::text||':'||mention_row.mentioned_user_id::text,
        private.user_can_read_post(NEW.id,mention_row.mentioned_user_id));
    END LOOP;
    FOR tag_row IN SELECT t.tagged_user_id FROM public.post_user_tags t
      WHERE t.post_id=NEW.id AND t.state IN ('pending','accepted') LOOP
      PERFORM private.emit_content_attention(tag_row.tagged_user_id,NEW.author_id,'tag','post',NEW.id,
        'tag:'||NEW.id::text||':'||tag_row.tagged_user_id::text||':'||tag_row.request_id::text,
        private.user_can_read_post(NEW.id,tag_row.tagged_user_id));
    END LOOP;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER post_attention_on_publish AFTER UPDATE OF state ON public.posts
  FOR EACH ROW WHEN (OLD.state IS DISTINCT FROM 'published' AND NEW.state='published')
  EXECUTE FUNCTION private.notify_approved_group_attention();

CREATE OR REPLACE FUNCTION private.post_json(pid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',p.id,'author_id',p.author_id,'group_id',p.group_id,'state',p.state,
 'body',p.body,'audience',p.audience,'kind',p.kind,
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
      (SELECT cr.reaction FROM public.comment_reactions cr WHERE cr.comment_id=c.id AND cr.user_id=auth.uid()) AS my_reaction,
      coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',CASE WHEN private.can_read_profile(m.mentioned_user_id) OR c.author_id=auth.uid() THEN m.mentioned_user_id END,
        'label',CASE WHEN private.can_read_profile(m.mentioned_user_id) THEN m.display_label ELSE '@Meeto user' END,
        'start_utf16',m.span_start_utf16,'end_utf16',m.span_end_utf16) ORDER BY m.span_start_utf16)
        FROM public.content_mentions m WHERE m.content_type='comment' AND m.content_id=c.id),'[]'::jsonb) AS mentions
    FROM public.comments c WHERE c.post_id=p_post_id AND c.parent_id IS NOT DISTINCT FROM p_parent_id
      AND private.can_read_comment(c.id) AND (p_after_time IS NULL OR (c.created_at,c.id)<(p_after_time,p_after_id))
    ORDER BY c.created_at DESC,c.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

CREATE FUNCTION private.search_mention_people(p_query text,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; escaped text;
BEGIN
  PERFORM private.b_active();
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 1 AND 60 OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 12 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_mention_search'; END IF;
  q:=lower(btrim(p_query)); escaped:=replace(replace(replace(q,'\','\\'),'%','\%'),'_','\_');
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',x.id,'display_name',x.display_name,
    'avatar_asset_id',x.avatar_asset_id,'avatar_preset',x.public_avatar_preset,'is_friend',x.is_friend)
    ORDER BY x.is_friend DESC,lower(x.display_name),x.id) FROM (
      SELECT p.id,p.display_name,p.avatar_asset_id,p.public_avatar_preset,private.are_friends(p.id) is_friend
      FROM public.profiles p JOIN public.account_status a ON a.user_id=p.id AND a.state='active'
      WHERE p.id<>auth.uid() AND p.onboarding_completed AND private.can_read_profile(p.id)
        AND lower(p.display_name) LIKE escaped||'%' ESCAPE '\'
        AND NOT EXISTS(SELECT 1 FROM public.user_blocks b WHERE
          (b.blocker_id=auth.uid() AND b.blocked_id=p.id) OR (b.blocker_id=p.id AND b.blocked_id=auth.uid()))
      ORDER BY private.are_friends(p.id) DESC,lower(p.display_name),p.id LIMIT p_limit) x),'[]'::jsonb);
END $$;

CREATE FUNCTION private.create_post_with_mentions(p_body text,p_audience text,p_assets uuid[],p_client_request_id uuid,
  p_mentions jsonb,p_tagged_users uuid[]) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; pid uuid; saved_body text;
BEGIN
  result:=private.create_post(p_body,p_audience,p_assets,p_client_request_id,NULL); pid:=(result->>'id')::uuid;
  SELECT p.body INTO saved_body FROM public.posts p WHERE p.id=pid AND p.author_id=private.b_actor();
  IF saved_body IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  PERFORM private.bind_mention_request('post',pid,p_client_request_id,
    encode(sha256(convert_to(jsonb_build_array('post',p_body,p_audience,p_assets,p_mentions,p_tagged_users)::text,'UTF8')),'hex'));
  PERFORM private.sync_content_mentions('post',pid,saved_body,p_mentions);
  PERFORM private.sync_post_tags(pid,p_tagged_users);
  RETURN private.post_json(pid);
END $$;
CREATE FUNCTION private.edit_post_with_mentions(p_post_id uuid,p_body text,p_audience text,p_assets uuid[],
  p_expected_version bigint,p_mentions jsonb,p_tagged_users uuid[]) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; saved_body text;
BEGIN
  result:=private.edit_post(p_post_id,p_body,p_audience,p_assets,p_expected_version);
  SELECT p.body INTO saved_body FROM public.posts p WHERE p.id=p_post_id AND p.author_id=private.b_actor();
  IF saved_body IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='post_unavailable'; END IF;
  PERFORM private.sync_content_mentions('post',p_post_id,saved_body,p_mentions);
  PERFORM private.sync_post_tags(p_post_id,p_tagged_users);
  RETURN private.post_json(p_post_id);
END $$;
CREATE FUNCTION private.create_community_post_with_mentions(p_group_id uuid,p_body text,p_assets uuid[],
  p_client_request_id uuid,p_mentions jsonb,p_tagged_users uuid[]) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; pid uuid; saved_body text; normalized_mentions jsonb;
BEGIN
  result:=private.create_community_post(p_group_id,p_body,p_assets,p_client_request_id);
  pid:=(result->>'id')::uuid;
  PERFORM private.bind_mention_request('group_post',pid,p_client_request_id,
    encode(sha256(convert_to(jsonb_build_array('group_post',p_group_id,p_body,p_assets,p_mentions,p_tagged_users)::text,'UTF8')),'hex'));
  SELECT p.body INTO saved_body FROM public.posts p WHERE p.id=pid AND p.author_id=private.b_actor();
  IF saved_body IS NULL OR saved_body IS DISTINCT FROM btrim(p_body) THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='stored_post_body_mismatch';
  END IF;
  normalized_mentions:=private.mentions_for_trimmed_body(p_body,p_mentions);
  PERFORM private.sync_content_mentions('post',pid,saved_body,normalized_mentions);
  PERFORM private.sync_post_tags(pid,p_tagged_users);
  RETURN result;
END $$;
CREATE FUNCTION private.create_comment_with_mentions(p_post_id uuid,p_parent_id uuid,p_body text,
  p_client_request_id uuid,p_mentions jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; cid uuid; saved_body text;
BEGIN
  result:=private.create_comment(p_post_id,p_parent_id,p_body,p_client_request_id); cid:=(result->>'id')::uuid;
  SELECT c.body INTO saved_body FROM public.comments c WHERE c.id=cid AND c.author_id=private.b_actor();
  IF saved_body IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='comment_unavailable'; END IF;
  PERFORM private.bind_mention_request('comment',cid,p_client_request_id,
    encode(sha256(convert_to(jsonb_build_array('comment',p_post_id,p_parent_id,p_body,p_mentions)::text,'UTF8')),'hex'));
  PERFORM private.sync_content_mentions('comment',cid,saved_body,p_mentions);
  RETURN result;
END $$;
CREATE FUNCTION private.change_comment_with_mentions(p_comment_id uuid,p_body text,p_expected_version bigint,
  p_mentions jsonb) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb; saved_body text;
BEGIN
  result:=private.change_comment(p_comment_id,p_body,p_expected_version,false);
  SELECT c.body INTO saved_body FROM public.comments c WHERE c.id=p_comment_id AND c.author_id=private.b_actor();
  IF saved_body IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='comment_unavailable'; END IF;
  PERFORM private.sync_content_mentions('comment',p_comment_id,saved_body,p_mentions);
  RETURN result;
END $$;

-- Re-requesting a tag is a separate, explicit operation. It always creates a
-- fresh pending consent request, regardless of the recipient's auto-approve setting.
CREATE FUNCTION private.request_post_retag(p_post_id uuid,p_user_id uuid) RETURNS jsonb
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
      AND (s.tagging_policy='everyone' OR (s.tagging_policy='friends' AND EXISTS(
        SELECT 1 FROM public.friendships f WHERE f.user_low=least(actor,p_user_id)
          AND f.user_high=greatest(actor,p_user_id))))
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

CREATE FUNCTION private.update_tagging_settings(p_policy text,p_review_required boolean) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
  IF p_policy IS NULL OR p_policy NOT IN ('everyone','friends','nobody') OR p_review_required IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_tagging_settings'; END IF;
  UPDATE public.user_settings SET tagging_policy=p_policy,tag_review_required=p_review_required,updated_at=statement_timestamp()
    WHERE user_id=actor;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT409' USING MESSAGE='user_settings_unavailable'; END IF;
  RETURN jsonb_build_object('tagging_policy',p_policy,'tag_review_required',p_review_required);
END $$;
CREATE FUNCTION private.get_tagging_settings() RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
  SELECT jsonb_build_object('tagging_policy',s.tagging_policy,'tag_review_required',s.tag_review_required)
  FROM public.user_settings s WHERE s.user_id=auth.uid() AND private.is_active()
$$;
CREATE FUNCTION private.list_my_post_tags(p_state text,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_state IS NOT NULL AND p_state NOT IN ('pending','accepted') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_tag_filter'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('post',private.post_json(q.post_id),'state',q.state,
      'tagged_by',CASE WHEN private.can_read_profile(q.tagged_by) THEN q.tagged_by END,
      'tagged_by_name',CASE WHEN private.can_read_profile(q.tagged_by) THEN p.display_name ELSE 'Meeto user' END,
      'created_at',q.created_at) ORDER BY q.created_at DESC,q.post_id DESC)
    FROM (SELECT t.post_id,t.state,t.tagged_by,t.created_at FROM public.post_user_tags t
      JOIN public.posts po ON po.id=t.post_id WHERE t.tagged_user_id=actor AND t.state IN ('pending','accepted')
        AND (p_state IS NULL OR t.state=p_state) AND po.state='published' AND private.can_read_post(po.id)
        AND (p_after_time IS NULL OR (t.created_at,t.post_id)<(p_after_time,p_after_id))
      ORDER BY t.created_at DESC,t.post_id DESC LIMIT p_limit) q
    LEFT JOIN public.profiles p ON p.id=q.tagged_by),'[]'::jsonb);
END $$;
CREATE FUNCTION private.review_post_tag(p_post_id uuid,p_action text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.b_actor(); current_state text; next_state text;
BEGIN
  IF p_post_id IS NULL OR p_action IS NULL OR p_action NOT IN ('approve','reject','remove') THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_tag_action'; END IF;
  next_state:=CASE p_action WHEN 'approve' THEN 'accepted' WHEN 'reject' THEN 'rejected' ELSE 'removed' END;
  SELECT t.state INTO current_state FROM public.post_user_tags t JOIN public.posts p ON p.id=t.post_id
    WHERE t.post_id=p_post_id AND t.tagged_user_id=actor AND p.state='published' AND private.can_read_post(p.id) FOR UPDATE OF t;
  IF current_state IS NULL THEN RAISE SQLSTATE 'PT404' USING MESSAGE='tag_unavailable'; END IF;
  IF current_state='pending' AND p_action IN ('approve','reject') OR current_state='accepted' AND p_action='remove' THEN
    UPDATE public.post_user_tags SET state=next_state,resolved_at=statement_timestamp()
      WHERE post_id=p_post_id AND tagged_user_id=actor;
  ELSIF current_state<>next_state THEN RAISE SQLSTATE 'PT409' USING MESSAGE='tag_already_resolved'; END IF;
  RETURN jsonb_build_object('post_id',p_post_id,'state',next_state);
END $$;

CREATE FUNCTION public.search_mention_people(p_query text,p_limit integer DEFAULT 8) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.search_mention_people($1,$2) $$;
CREATE FUNCTION public.create_post_with_mentions(p_body text,p_audience text,p_assets uuid[],p_client_request_id uuid,
  p_mentions jsonb DEFAULT '[]'::jsonb,p_tagged_users uuid[] DEFAULT '{}') RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_post_with_mentions($1,$2,$3,$4,$5,$6) $$;
CREATE FUNCTION public.edit_post_with_mentions(p_post_id uuid,p_body text,p_audience text,p_assets uuid[],
  p_expected_version bigint,p_mentions jsonb DEFAULT '[]'::jsonb,p_tagged_users uuid[] DEFAULT '{}') RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.edit_post_with_mentions($1,$2,$3,$4,$5,$6,$7) $$;
CREATE FUNCTION public.create_community_post_with_mentions(p_group_id uuid,p_body text,p_assets uuid[],
  p_client_request_id uuid,p_mentions jsonb DEFAULT '[]'::jsonb,p_tagged_users uuid[] DEFAULT '{}') RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_community_post_with_mentions($1,$2,$3,$4,$5,$6) $$;
CREATE FUNCTION public.create_comment_with_mentions(p_post_id uuid,p_parent_id uuid,p_body text,
  p_client_request_id uuid,p_mentions jsonb DEFAULT '[]'::jsonb) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.create_comment_with_mentions($1,$2,$3,$4,$5) $$;
CREATE FUNCTION public.change_comment_with_mentions(p_comment_id uuid,p_body text,p_expected_version bigint,
  p_mentions jsonb DEFAULT '[]'::jsonb) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.change_comment_with_mentions($1,$2,$3,$4) $$;
CREATE FUNCTION public.update_tagging_settings(p_policy text,p_review_required boolean) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.update_tagging_settings($1,$2) $$;
CREATE FUNCTION public.get_tagging_settings() RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.get_tagging_settings() $$;
CREATE FUNCTION public.list_my_post_tags(p_state text DEFAULT NULL,p_after_time timestamptz DEFAULT NULL,
  p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 20) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$ SELECT private.list_my_post_tags($1,$2,$3,$4) $$;
CREATE FUNCTION public.review_post_tag(p_post_id uuid,p_action text) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.review_post_tag($1,$2) $$;
CREATE FUNCTION public.request_post_retag(p_post_id uuid,p_user_id uuid) RETURNS jsonb
LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$ SELECT private.request_post_retag($1,$2) $$;

-- Legacy edit endpoints cannot carry structured spans. Clear them atomically so
-- old clients never leave identity records pointing at changed body positions.
CREATE OR REPLACE FUNCTION public.edit_post(p_post_id uuid,p_body text,p_audience text,p_assets uuid[],p_expected_version bigint)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.edit_post_with_mentions($1,$2,$3,$4,$5,'[]'::jsonb,'{}'::uuid[])
$$;
CREATE OR REPLACE FUNCTION public.edit_comment(p_comment_id uuid,p_body text,p_expected_version bigint)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.change_comment_with_mentions($1,$2,$3,'[]'::jsonb)
$$;

REVOKE ALL ON FUNCTION private.utf16_slice(text,integer,integer),private.mentions_for_trimmed_body(text,jsonb),
  private.user_can_read_post(uuid,uuid),private.user_can_read_comment(uuid,uuid),private.sync_content_mentions(text,uuid,text,jsonb),private.bind_mention_request(text,uuid,uuid,text),
  private.sync_post_tags(uuid,uuid[]),private.emit_content_attention(uuid,uuid,text,text,uuid,text,boolean),
  private.notify_content_mention(),private.notify_post_user_tag(),private.notify_approved_group_attention(),private.search_mention_people(text,integer),
  private.create_post_with_mentions(text,text,uuid[],uuid,jsonb,uuid[]),
  private.edit_post_with_mentions(uuid,text,text,uuid[],bigint,jsonb,uuid[]),
  private.create_community_post_with_mentions(uuid,text,uuid[],uuid,jsonb,uuid[]),
  private.create_comment_with_mentions(uuid,uuid,text,uuid,jsonb),private.change_comment_with_mentions(uuid,text,bigint,jsonb),
  private.update_tagging_settings(text,boolean),private.get_tagging_settings(),private.list_my_post_tags(text,timestamptz,uuid,integer),
  private.review_post_tag(uuid,text),private.request_post_retag(uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.search_mention_people(text,integer),
  private.create_post_with_mentions(text,text,uuid[],uuid,jsonb,uuid[]),
  private.edit_post_with_mentions(uuid,text,text,uuid[],bigint,jsonb,uuid[]),
  private.create_community_post_with_mentions(uuid,text,uuid[],uuid,jsonb,uuid[]),
  private.create_comment_with_mentions(uuid,uuid,text,uuid,jsonb),private.change_comment_with_mentions(uuid,text,bigint,jsonb),
  private.update_tagging_settings(text,boolean),private.get_tagging_settings(),private.list_my_post_tags(text,timestamptz,uuid,integer),
  private.review_post_tag(uuid,text),private.request_post_retag(uuid,uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.search_mention_people(text,integer),
  public.create_post_with_mentions(text,text,uuid[],uuid,jsonb,uuid[]),
  public.edit_post_with_mentions(uuid,text,text,uuid[],bigint,jsonb,uuid[]),
  public.create_community_post_with_mentions(uuid,text,uuid[],uuid,jsonb,uuid[]),
  public.create_comment_with_mentions(uuid,uuid,text,uuid,jsonb),public.change_comment_with_mentions(uuid,text,bigint,jsonb),
  public.update_tagging_settings(text,boolean),public.get_tagging_settings(),public.list_my_post_tags(text,timestamptz,uuid,integer),
  public.review_post_tag(uuid,text),public.request_post_retag(uuid,uuid) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.search_mention_people(text,integer),
  public.create_post_with_mentions(text,text,uuid[],uuid,jsonb,uuid[]),
  public.edit_post_with_mentions(uuid,text,text,uuid[],bigint,jsonb,uuid[]),
  public.create_community_post_with_mentions(uuid,text,uuid[],uuid,jsonb,uuid[]),
  public.create_comment_with_mentions(uuid,uuid,text,uuid,jsonb),public.change_comment_with_mentions(uuid,text,bigint,jsonb),
  public.update_tagging_settings(text,boolean),public.get_tagging_settings(),public.list_my_post_tags(text,timestamptz,uuid,integer),
  public.review_post_tag(uuid,text),public.request_post_retag(uuid,uuid) TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
