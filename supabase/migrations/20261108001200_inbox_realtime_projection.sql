-- Immediate private invalidations plus an inbox projection without per-row RPCs.
-- The existing outbox remains the retry path; broadcasts contain no message body.
BEGIN;

DO $$ BEGIN
 IF to_regprocedure('private.chat_dispatch_recipients(uuid)') IS NULL
 OR to_regprocedure('private.c_event(uuid,text,uuid[],uuid)') IS NULL
 OR to_regprocedure('realtime.send(jsonb,text,text,boolean)') IS NULL
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns
   WHERE table_schema='public' AND table_name='conversation_members' AND column_name='nickname') THEN
   RAISE EXCEPTION 'Requires messaging realtime, mute settings and chat customization migrations';
 END IF;
END $$;

CREATE OR REPLACE FUNCTION private.c_event(cid uuid,kind text,targets uuid[] DEFAULT NULL,mid uuid DEFAULT NULL) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE users uuid[]:=targets; event_id uuid; recipient text;
BEGIN
 IF users IS NULL THEN
   SELECT array_agg(user_id ORDER BY user_id) INTO users
   FROM public.conversation_members WHERE conversation_id=cid AND state='active';
 END IF;
 INSERT INTO public.event_outbox(event_key,event_type,aggregate_type,aggregate_id,payload)
 VALUES('chat:'||gen_random_uuid()::text,'chat.invalidate','conversation',cid,
   jsonb_build_object('kind',kind,'recipients',coalesce(users,'{}'::uuid[]),'message_id',mid))
 RETURNING id INTO event_id;
 -- Use the existing recipient checks, including block, hidden-message and history boundaries.
 -- These rows become visible to Realtime only when the enclosing transaction commits.
 FOR recipient IN SELECT jsonb_array_elements_text(private.chat_dispatch_recipients(event_id)) LOOP
   BEGIN
     PERFORM realtime.send(jsonb_build_object('id',gen_random_uuid(),'event_id',event_id,'scope','messenger'),
       'invalidate','user:'||recipient,true);
   EXCEPTION WHEN OTHERS THEN
     -- A transport failure must not roll back the message. The pending outbox retries it.
     NULL;
   END;
 END LOOP;
END $$;

CREATE OR REPLACE FUNCTION private.c_conversation(cid uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',c.id,'kind',c.kind,'title',c.title,'state',c.state,
 'created_at',c.created_at,'updated_at',c.updated_at,'version',c.version,'theme_id',c.theme_id,
 'peer_name',(SELECT coalesce(peer.nickname,p.display_name) FROM public.conversation_members peer
   JOIN public.profiles p ON p.id=peer.user_id WHERE c.kind='private' AND peer.conversation_id=c.id
   AND peer.user_id<>auth.uid() AND peer.state='active' AND private.can_read_profile(p.id) LIMIT 1),
 'peer_avatar_asset_id',(SELECT p.avatar_asset_id FROM public.conversation_members peer
   JOIN public.profiles p ON p.id=peer.user_id WHERE c.kind='private' AND peer.conversation_id=c.id
   AND peer.user_id<>auth.uid() AND peer.state='active' AND private.can_read_profile(p.id) LIMIT 1),
 'peer_avatar_preset',(SELECT p.public_avatar_preset FROM public.conversation_members peer
   JOIN public.profiles p ON p.id=peer.user_id WHERE c.kind='private' AND peer.conversation_id=c.id
   AND peer.user_id<>auth.uid() AND peer.state='active' AND private.can_read_profile(p.id) LIMIT 1),
 'participants',coalesce((SELECT jsonb_agg(member.user_id ORDER BY member.user_id)
   FROM public.conversation_members member WHERE member.conversation_id=c.id AND member.state='active'),'[]'::jsonb),
 'muted',EXISTS(SELECT 1 FROM public.conversation_mutes mute WHERE mute.conversation_id=c.id AND mute.user_id=auth.uid()),
 'high_water',c.next_seq,'history_from_seq',cm.history_from_seq,'last_read_seq',cm.last_read_seq,
 'last_delivered_seq',cm.last_delivered_seq,'role',cm.role,'archived',cm.archived_at IS NOT NULL,
 'owner_id',c.owner_id,'avatar_asset_id',c.avatar_asset_id,
 'last_message',(SELECT private.c_message(m.id) FROM public.messages m
   WHERE m.conversation_id=c.id AND private.can_read_message(m.id) ORDER BY m.seq DESC LIMIT 1),
 'unread_count',(SELECT count(*) FROM public.messages m WHERE m.conversation_id=c.id AND m.seq>cm.last_read_seq
   AND m.sender_id<>auth.uid() AND m.state='sent' AND private.can_read_message(m.id)))
 FROM public.conversations c JOIN public.conversation_members cm ON cm.conversation_id=c.id AND cm.user_id=auth.uid()
 WHERE c.id=cid AND private.can_read_conversation(c.id);
$$;

NOTIFY pgrst,'reload schema';
COMMIT;
