-- Task 44. Forward only. Owner deploys; no Cloud deployment by the client agent.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.c_invitee(uuid,uuid)') IS NULL
 OR to_regprocedure('private.c_conversation(uuid)') IS NULL
 OR to_regprocedure('private.social_search_text(text)') IS NULL
 OR to_regprocedure('public.task36_capabilities()') IS NULL
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='conversations' AND column_name='avatar_asset_id') THEN
  RAISE EXCEPTION 'Deploy Task 36/37 messaging, Follow search and group avatar prerequisites first';
 END IF;
END $$;
ALTER TABLE public.conversation_members ADD COLUMN nickname text
 CHECK(nickname IS NULL OR (length(btrim(nickname)) BETWEEN 1 AND 40 AND nickname=btrim(nickname) AND nickname !~ '[[:cntrl:]]'));
ALTER TABLE public.conversations ADD COLUMN theme_id text NOT NULL DEFAULT 'meeto_pink'
 CHECK(theme_id IN ('meeto_pink','ocean_blue','lavender','midnight','forest','sunset','rose','graphite','coffee','aurora'));
ALTER TABLE public.conversations ADD COLUMN customization_version bigint NOT NULL DEFAULT 1 CHECK(customization_version>0);
-- No new raw column grants or client DML. Existing RLS and consent remain authoritative.
CREATE OR REPLACE FUNCTION private.get_conversation_members(p_conversation_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('user_id',cm.user_id,'role',cm.role,'state',cm.state,'joined_at',cm.joined_at,
 'display_name',CASE WHEN private.can_read_profile(cm.user_id) THEN p.display_name END,
 'nickname',CASE WHEN private.can_read_profile(cm.user_id) THEN cm.nickname END,
 'conversation_name',CASE WHEN private.can_read_profile(cm.user_id) THEN coalesce(cm.nickname,p.display_name) END,
 'username',CASE WHEN private.can_read_profile(cm.user_id) THEN p.username END,
 'avatar_preset',CASE WHEN private.can_read_profile(cm.user_id) THEN p.public_avatar_preset END,
 'avatar_asset_id',CASE WHEN private.can_read_profile(cm.user_id) THEN p.avatar_asset_id END) ORDER BY cm.user_id)
 FROM public.conversation_members cm JOIN public.profiles p ON p.id=cm.user_id
 WHERE cm.conversation_id=p_conversation_id AND cm.state='active'),'[]'::jsonb);
END $$;
CREATE OR REPLACE FUNCTION private.c_conversation(cid uuid) RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT jsonb_build_object('id',c.id,'kind',c.kind,'title',c.title,'state',c.state,'created_at',c.created_at,'updated_at',c.updated_at,'version',c.version,
 'theme_id',c.theme_id,'peer_name',(SELECT coalesce(peer.nickname,p.display_name) FROM public.conversation_members peer JOIN public.profiles p ON p.id=peer.user_id
 WHERE c.kind='private' AND peer.conversation_id=c.id AND peer.user_id<>auth.uid() AND peer.state='active' AND private.can_read_profile(p.id) LIMIT 1),
 'high_water',c.next_seq,'history_from_seq',cm.history_from_seq,'last_read_seq',cm.last_read_seq,'last_delivered_seq',cm.last_delivered_seq,'role',cm.role,'archived',cm.archived_at IS NOT NULL,'owner_id',c.owner_id,'avatar_asset_id',c.avatar_asset_id,
 'last_message',(SELECT private.c_message(m.id) FROM public.messages m WHERE m.conversation_id=c.id AND private.can_read_message(m.id) ORDER BY m.seq DESC LIMIT 1),
 'unread_count',(SELECT count(*) FROM public.messages m WHERE m.conversation_id=c.id AND m.seq>cm.last_read_seq AND m.sender_id<>auth.uid() AND m.state='sent' AND private.can_read_message(m.id)))
 FROM public.conversations c JOIN public.conversation_members cm ON cm.conversation_id=c.id AND cm.user_id=auth.uid()
 WHERE c.id=cid AND private.can_read_conversation(c.id);
$$;
CREATE FUNCTION public.task44_chat_capabilities() RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN PERFORM private.b_active(); RETURN jsonb_build_object('version',44,'chat_customization',true,'follow_picker',true); END $$;
CREATE FUNCTION public.get_chat_customization(p_conversation_id uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 PERFORM private.b_active();
 IF NOT private.can_read_conversation(p_conversation_id) THEN RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 RETURN (SELECT jsonb_build_object('id',id,'theme_id',theme_id,'version',customization_version,'members',private.get_conversation_members(id)) FROM public.conversations WHERE id=p_conversation_id);
END $$;
CREATE FUNCTION public.set_chat_nickname(p_conversation_id uuid,p_target uuid,p_nickname text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); value text:=nullif(btrim(p_nickname),''); v bigint;
BEGIN
 IF NOT private.social_target(actor) OR NOT private.social_target(p_target) OR private.is_blocked(p_target)
 OR NOT private.can_read_profile(p_target) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='nickname_target_unavailable'; END IF;
 -- Any active member may rename self or another active member, within this conversation only.
 IF NOT EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=p_conversation_id AND user_id=p_target AND state='active') THEN RAISE SQLSTATE 'PT404' USING MESSAGE='membership_required'; END IF;
 IF value IS NOT NULL AND (length(value)>40 OR value ~ '[[:cntrl:]]') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_nickname'; END IF;
 SELECT customization_version INTO v FROM public.conversations WHERE id=p_conversation_id;
 IF p_expected_version IS NULL OR v<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=p_conversation_id AND user_id=p_target AND nickname IS DISTINCT FROM value) THEN
  UPDATE public.conversation_members SET nickname=value WHERE conversation_id=p_conversation_id AND user_id=p_target;
  UPDATE public.conversations SET version=version+1,customization_version=customization_version+1 WHERE id=p_conversation_id;
  PERFORM private.c_event(p_conversation_id,'state');
 END IF;
 RETURN public.get_chat_customization(p_conversation_id);
END $$;
CREATE FUNCTION public.set_chat_theme(p_conversation_id uuid,p_theme_id text,p_expected_version bigint) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.c_lock(p_conversation_id); c public.conversations;
BEGIN
 IF NOT private.social_target(actor) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 IF p_theme_id IS NULL OR p_theme_id NOT IN ('meeto_pink','ocean_blue','lavender','midnight','forest','sunset','rose','graphite','coffee','aurora') THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_chat_theme'; END IF;
 SELECT * INTO c FROM public.conversations WHERE id=p_conversation_id;
 IF p_expected_version IS NULL OR c.customization_version<>p_expected_version THEN RAISE SQLSTATE 'PT409' USING MESSAGE='version_conflict'; END IF;
 IF c.theme_id<>p_theme_id THEN
  UPDATE public.conversations SET theme_id=p_theme_id,version=version+1,customization_version=customization_version+1 WHERE id=c.id;
  PERFORM private.c_event(c.id,'state');
 END IF;
 RETURN public.get_chat_customization(c.id);
END $$;
-- Eligibility is advisory. Creation/add still recheck the original c_invitee and invitations.
CREATE FUNCTION private.chat_invite_reason(target uuid,cid uuid) RETURNS text LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
BEGIN
 IF cid IS NOT NULL THEN
  IF EXISTS(SELECT 1 FROM public.conversation_members WHERE conversation_id=cid AND user_id=target AND state='active') THEN RETURN 'member'; END IF;
  IF EXISTS(SELECT 1 FROM public.conversation_invitations WHERE conversation_id=cid AND user_id=target AND state IN ('pending','rejected')) THEN RETURN 'invited_or_declined'; END IF;
 END IF;
 PERFORM private.c_invitee(auth.uid(),target); RETURN 'eligible';
EXCEPTION WHEN SQLSTATE 'PT403' THEN RETURN 'follow_required'; WHEN SQLSTATE 'PT404' THEN RETURN 'unavailable';
END $$;
CREATE FUNCTION public.chat_picker_people(p_query text DEFAULT '',p_after_name text DEFAULT NULL,p_after_id uuid DEFAULT NULL,
 p_limit integer DEFAULT 30,p_conversation_id uuid DEFAULT NULL,p_target uuid DEFAULT NULL) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE term text; escaped text;
BEGIN
 PERFORM private.b_active();
 IF NOT private.social_target(auth.uid()) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='account_unavailable'; END IF;
 IF p_query IS NULL OR length(p_query)>80 OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50
 OR (p_after_name IS NULL)<>(p_after_id IS NULL) THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_picker_page'; END IF;
 IF p_conversation_id IS NOT NULL AND (NOT private.can_read_conversation(p_conversation_id) OR NOT EXISTS(
 SELECT 1 FROM public.conversations c JOIN public.conversation_members m ON m.conversation_id=c.id
 WHERE c.id=p_conversation_id AND c.kind='group' AND c.state='active' AND m.user_id=auth.uid() AND m.state='active' AND m.role='admin')) THEN RAISE SQLSTATE 'PT403' USING MESSAGE='chat_admin_required'; END IF;
 term:=private.social_search_text(btrim(p_query)); escaped:=replace(replace(replace(term,'!','!!'),'%','!%'),'_','!_');
 RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('profile_id',q.id,'display_name',q.display_name,'username',q.username,
 'avatar_asset_id',q.avatar_asset_id,'avatar_preset',q.public_avatar_preset,'cursor_name',q.cursor_name,
 'following',private.follows(auth.uid(),q.id),'follower',private.follows(q.id,auth.uid()),'eligibility',private.chat_invite_reason(q.id,p_conversation_id)) ORDER BY q.cursor_name,q.id)
 FROM (SELECT p.*,private.social_search_text(p.display_name) cursor_name FROM public.profiles p
 WHERE p.id<>auth.uid() AND private.social_target(p.id) AND private.can_read_profile(p.id) AND NOT private.is_blocked(p.id)
 AND (p_target IS NULL OR p.id=p_target)
 AND (p_target IS NOT NULL OR (term='' AND (private.follows(auth.uid(),p.id) OR private.follows(p.id,auth.uid())))
  OR (term<>'' AND (private.social_search_text(p.display_name) LIKE '%'||escaped||'%' ESCAPE '!' OR private.social_search_text(p.username) LIKE '%'||escaped||'%' ESCAPE '!')))
 AND (p_after_name IS NULL OR (private.social_search_text(p.display_name),p.id)>(p_after_name,p_after_id))
 ORDER BY private.social_search_text(p.display_name),p.id LIMIT p_limit)q),'[]'::jsonb);
END $$;
REVOKE ALL ON FUNCTION private.chat_invite_reason(uuid,uuid),public.task44_chat_capabilities(),public.get_chat_customization(uuid),
 public.set_chat_nickname(uuid,uuid,text,bigint),public.set_chat_theme(uuid,text,bigint),public.chat_picker_people(text,text,uuid,integer,uuid,uuid) FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.task44_chat_capabilities(),public.get_chat_customization(uuid),public.set_chat_nickname(uuid,uuid,text,bigint),
 public.set_chat_theme(uuid,text,bigint),public.chat_picker_people(text,text,uuid,integer,uuid,uuid) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
