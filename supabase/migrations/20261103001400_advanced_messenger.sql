-- Task 25 / migration 032: message editing, safe text forwarding and inbox search.
-- Existing reply, recall, hide-for-me, media and private broadcast contracts remain intact.
BEGIN;

DO $$ BEGIN
  IF to_regclass('public.messages') IS NULL OR to_regclass('public.conversations') IS NULL
     OR to_regclass('public.conversation_members') IS NULL OR to_regclass('public.message_hidden') IS NULL
     OR to_regprocedure('private.c_lock(uuid,boolean)') IS NULL
     OR to_regprocedure('private.c_event(uuid,text,uuid[],uuid)') IS NULL
     OR to_regprocedure('private.c_message(uuid)') IS NULL
     OR to_regprocedure('private.c_conversation(uuid)') IS NULL
     OR to_regprocedure('private.can_read_post(uuid)') IS NULL
     OR to_regprocedure('private.can_read_message(uuid)') IS NULL
     OR to_regprocedure('private.can_read_conversation(uuid)') IS NULL
     OR to_regprocedure('private.can_read_media(uuid)') IS NULL
     OR to_regprocedure('private.post_json(uuid)') IS NULL
     OR to_regprocedure('private.can_read_profile(uuid)') IS NULL
     OR to_regprocedure('private.b_active()') IS NULL
     OR to_regprocedure('private.b_page(integer,timestamptz,uuid)') IS NULL THEN
    RAISE EXCEPTION 'Advanced Messenger prerequisites missing; verify migrations 001-031';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.messages'::regclass
      AND attname='is_shared_post' AND NOT attisdropped)
     OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.messages'::regclass
      AND attname='shared_post_id' AND NOT attisdropped) THEN
    RAISE EXCEPTION 'Advanced Messenger requires structured shared-post fields from migration 029';
  END IF;
END $$;

ALTER TABLE public.messages
  ADD COLUMN forwarded boolean NOT NULL DEFAULT false;

-- Preserve every existing Messenger and Task 22 projection, adding only the
-- forwarding marker. Media identifiers remain subject to can_read_media().
CREATE OR REPLACE FUNCTION private.c_message(mid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',m.id,'conversation_id',m.conversation_id,'seq',m.seq,
   'sender_id',m.sender_id,'kind',m.kind,'body',CASE WHEN m.state='sent' THEN m.body END,
   'state',m.state,'version',m.version,
   'created_at',m.created_at,'edited_at',m.edited_at,'recalled_at',m.recalled_at,
   'reply_reference_present',m.reply_to_message_id IS NOT NULL,
   'reply_to_message_id',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN m.reply_to_message_id END,
   'reply',CASE WHEN private.can_read_message(m.reply_to_message_id) THEN
     (SELECT jsonb_build_object('id',r.id,'seq',r.seq,'sender_id',r.sender_id,
        'state',r.state,'body',CASE WHEN r.state='sent' THEN r.body END,'kind',r.kind,
        'media',coalesce((SELECT jsonb_agg(jsonb_build_object('mime_type',ra.mime_type)
          ORDER BY rm.position) FROM public.message_media rm
          JOIN public.media_assets ra ON ra.id=rm.asset_id
          WHERE rm.message_id=r.id AND r.state='sent' AND private.can_read_media(ra.id)),'[]'::jsonb))
       FROM public.messages r WHERE r.id=m.reply_to_message_id) END,
   'media',coalesce((SELECT jsonb_agg(jsonb_build_object('asset_id',mm.asset_id,'position',mm.position,
       'mime_type',a.mime_type) ORDER BY mm.position)
     FROM public.message_media mm JOIN public.media_assets a ON a.id=mm.asset_id
     WHERE mm.message_id=m.id AND m.state='sent' AND private.can_read_media(a.id)),'[]'::jsonb),
   'shared_post_present',m.state='sent' AND m.is_shared_post,
   'shared_post_available',m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL
     AND private.can_read_post(m.shared_post_id),
   'shared_post',CASE WHEN m.state='sent' AND m.is_shared_post AND m.shared_post_id IS NOT NULL
       AND private.can_read_post(m.shared_post_id) THEN private.post_json(m.shared_post_id) END,
   'forwarded',m.state='sent' AND m.forwarded)
 FROM public.messages m WHERE m.id=mid AND private.can_read_message(m.id);
$$;

CREATE FUNCTION private.edit_message(p_message_id uuid,p_body text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE cid uuid; actor uuid; m public.messages;
BEGIN
  IF p_message_id IS NULL OR p_body IS NULL OR char_length(btrim(p_body)) NOT BETWEEN 1 AND 10000
     OR p_expected_version IS NULL OR p_expected_version<1 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_message_edit';
  END IF;
  SELECT conversation_id INTO cid FROM public.messages WHERE id=p_message_id;
  actor:=private.c_lock(cid);
  SELECT * INTO m FROM public.messages WHERE id=p_message_id AND sender_id=actor FOR UPDATE;
  IF NOT FOUND OR NOT private.can_read_message(m.id) THEN
    RAISE SQLSTATE 'PT404' USING MESSAGE='message_unavailable';
  END IF;
  IF m.kind<>'text' OR m.state<>'sent' OR m.body IS NULL OR m.is_shared_post THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='message_not_editable';
  END IF;
  IF m.created_at<statement_timestamp()-interval '15 minutes' THEN
    RAISE SQLSTATE 'PT409' USING MESSAGE='message_edit_window_closed';
  END IF;
  IF m.version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
  IF m.body=btrim(p_body) THEN RETURN private.c_message(m.id); END IF;
  UPDATE public.messages SET body=btrim(p_body),edited_at=statement_timestamp(),version=version+1
    WHERE id=m.id RETURNING * INTO m;
  UPDATE public.conversations SET updated_at=statement_timestamp(),version=version+1 WHERE id=cid;
  PERFORM private.c_event(cid,'edit',NULL,m.id);
  RETURN private.c_message(m.id);
END $$;

CREATE FUNCTION private.forward_text_message(p_source_message_id uuid,p_target_conversation_id uuid,
  p_client_request_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid; source public.messages; target public.messages; seq bigint; fingerprint text;
BEGIN
  IF p_source_message_id IS NULL OR p_target_conversation_id IS NULL OR p_client_request_id IS NULL THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_forward';
  END IF;
  actor:=private.c_lock(p_target_conversation_id);
  fingerprint:=encode(sha256(convert_to(jsonb_build_array('forward_text',p_source_message_id,
    p_target_conversation_id)::text,'UTF8')),'hex');
  SELECT * INTO target FROM public.messages WHERE conversation_id=p_target_conversation_id
    AND sender_id=actor AND client_request_id=p_client_request_id FOR UPDATE;
  IF FOUND THEN
    IF target.request_hash IS DISTINCT FROM fingerprint OR NOT target.forwarded THEN
      RAISE SQLSTATE 'PT409' USING MESSAGE='idempotency_conflict';
    END IF;
    IF NOT private.can_read_message(target.id) THEN RAISE SQLSTATE 'PT409' USING MESSAGE='message_no_longer_visible'; END IF;
    RETURN private.c_message(target.id);
  END IF;
  -- Resolve retries before rechecking the source: a successful forward remains
  -- idempotent if the original was later recalled or hidden, while c_lock and
  -- the sender-scoped lookup keep the destination authorization intact.
  SELECT * INTO source FROM public.messages WHERE id=p_source_message_id
    AND state='sent' AND kind='text' AND body IS NOT NULL AND NOT is_shared_post
    AND private.can_read_message(id) FOR SHARE;
  IF NOT FOUND THEN RAISE SQLSTATE 'PT404' USING MESSAGE='forward_source_unavailable'; END IF;
  UPDATE public.conversations SET next_seq=next_seq+1,updated_at=statement_timestamp(),version=version+1
    WHERE id=p_target_conversation_id RETURNING next_seq INTO seq;
  INSERT INTO public.messages(conversation_id,seq,sender_id,client_request_id,request_hash,
      kind,body,forwarded)
    VALUES(p_target_conversation_id,seq,actor,p_client_request_id,fingerprint,'text',source.body,true)
    RETURNING * INTO target;
  UPDATE public.conversations SET last_message_id=target.id WHERE id=p_target_conversation_id;
  PERFORM private.c_event(p_target_conversation_id,'message',NULL,target.id);
  RETURN private.c_message(target.id);
END $$;

-- Inbox search operates only over the caller's already-authorized conversations.
CREATE FUNCTION private.search_conversations(p_query text,p_after_time timestamptz,
  p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE q text; escaped text;
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_query IS NULL OR char_length(btrim(p_query)) NOT BETWEEN 1 AND 80 THEN
    RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_conversation_search';
  END IF;
  q:=lower(btrim(p_query));
  escaped:=replace(replace(replace(q,'!','!!'),'%','!%'),'_','!_');
  RETURN coalesce((SELECT jsonb_agg(private.c_conversation(x.id)
      ORDER BY x.updated_at DESC,x.id DESC)
    FROM (SELECT c.id,c.updated_at FROM public.conversations c
      WHERE private.can_read_conversation(c.id) AND (c.state='active' OR c.state='archived')
        AND (p_after_time IS NULL OR (c.updated_at,c.id)<(p_after_time,p_after_id))
        AND (lower(coalesce(c.title,'')) LIKE '%'||escaped||'%' ESCAPE '!'
          OR EXISTS(SELECT 1 FROM public.conversation_members cm
            JOIN public.profiles p ON p.id=cm.user_id
            WHERE cm.conversation_id=c.id AND cm.state='active' AND cm.user_id<>auth.uid()
              AND private.can_read_profile(p.id)
              AND lower(p.display_name) LIKE '%'||escaped||'%' ESCAPE '!'))
      ORDER BY c.updated_at DESC,c.id DESC LIMIT p_limit) x),'[]'::jsonb);
END $$;

CREATE FUNCTION public.edit_message(p_message_id uuid,p_body text,p_expected_version bigint)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.edit_message($1,$2,$3) $$;
CREATE FUNCTION public.forward_text_message(p_source_message_id uuid,p_target_conversation_id uuid,
  p_client_request_id uuid) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path='' AS $$
  SELECT private.forward_text_message($1,$2,$3) $$;
CREATE FUNCTION public.search_conversations(p_query text,p_after_time timestamptz DEFAULT NULL,
  p_after_id uuid DEFAULT NULL,p_limit integer DEFAULT 30) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path='' AS $$
  SELECT private.search_conversations($1,$2,$3,$4) $$;

REVOKE ALL ON FUNCTION private.edit_message(uuid,text,bigint),
  private.forward_text_message(uuid,uuid,uuid),private.search_conversations(text,timestamptz,uuid,integer)
  FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION private.edit_message(uuid,text,bigint),
  private.forward_text_message(uuid,uuid,uuid),private.search_conversations(text,timestamptz,uuid,integer)
  TO authenticated;
REVOKE ALL ON FUNCTION public.edit_message(uuid,text,bigint),
  public.forward_text_message(uuid,uuid,uuid),public.search_conversations(text,timestamptz,uuid,integer)
  FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.edit_message(uuid,text,bigint),
  public.forward_text_message(uuid,uuid,uuid),public.search_conversations(text,timestamptz,uuid,integer)
  TO authenticated;

NOTIFY pgrst,'reload schema';
COMMIT;
