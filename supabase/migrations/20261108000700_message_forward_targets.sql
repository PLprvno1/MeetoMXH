-- Read-only destination picker. Forwarding still uses forward_text_message and its guards.
BEGIN;
DO $$ BEGIN
 IF to_regprocedure('private.forward_text_message(uuid,uuid,uuid)') IS NULL
 OR to_regprocedure('private.social_search_text(text)') IS NULL
 OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public'
  AND table_name='conversation_members' AND column_name='nickname') THEN
  RAISE EXCEPTION 'Requires advanced messenger, messaging consent and chat customization';
 END IF;
END $$;

CREATE FUNCTION public.list_message_forward_targets(p_source_conversation_id uuid,p_query text,
 p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity(); term text:=private.social_search_text(btrim(coalesce(p_query,'')));
BEGIN
 PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
 IF p_source_conversation_id IS NULL OR NOT private.can_read_conversation(p_source_conversation_id) THEN
  RAISE SQLSTATE 'PT404' USING MESSAGE='conversation_unavailable'; END IF;
 IF p_query IS NULL OR length(p_query)>80 THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_conversation_search'; END IF;
 RETURN coalesce((SELECT jsonb_agg(to_jsonb(q) ORDER BY q.updated_at DESC,q.id DESC) FROM (
  SELECT c.id,c.kind,c.updated_at,
   CASE WHEN c.kind='group' THEN coalesce(nullif(btrim(c.title),''),'Nhóm chat')
    WHEN private.can_read_profile(p.id) THEN coalesce(nullif(btrim(peer.nickname),''),p.display_name)
    ELSE 'Người dùng Meeto' END display_name,
   CASE WHEN c.kind='group' THEN c.avatar_asset_id
    WHEN private.can_read_profile(p.id) THEN p.avatar_asset_id END avatar_asset_id,
   CASE WHEN c.kind='private' AND private.can_read_profile(p.id) THEN p.public_avatar_preset ELSE 'neutral' END avatar_preset,
   own.archived_at IS NOT NULL archived
  FROM public.conversations c
  JOIN public.conversation_members own ON own.conversation_id=c.id AND own.user_id=actor AND own.state='active'
  LEFT JOIN public.conversation_members peer ON c.kind='private' AND peer.conversation_id=c.id
   AND peer.user_id=CASE WHEN c.direct_low=actor THEN c.direct_high ELSE c.direct_low END AND peer.state='active'
  LEFT JOIN public.profiles p ON p.id=peer.user_id
  WHERE c.id<>p_source_conversation_id AND c.state='active' AND private.can_read_conversation(c.id)
   AND (c.kind='group' OR (c.kind='private' AND c.direct_consent='accepted' AND peer.user_id IS NOT NULL))
   -- Mirror exclusions enforced by the message-insert contact trigger.
   AND NOT EXISTS(SELECT 1 FROM public.conversation_members m JOIN public.system_roles r
    ON r.user_id=m.user_id AND r.role='system_admin' WHERE m.conversation_id=c.id AND m.state='active')
   AND NOT EXISTS(SELECT 1 FROM public.conversation_members m JOIN public.user_blocks b
    ON (b.blocker_id=actor AND b.blocked_id=m.user_id) OR (b.blocked_id=actor AND b.blocker_id=m.user_id)
    WHERE m.conversation_id=c.id AND m.state='active')
   AND (p_after_time IS NULL OR (c.updated_at,c.id)<(p_after_time,p_after_id))
   AND (term='' OR strpos(private.social_search_text(CASE WHEN c.kind='group' THEN c.title
    WHEN private.can_read_profile(p.id) THEN concat_ws(' ',peer.nickname,p.display_name,p.username) END),term)>0)
  ORDER BY c.updated_at DESC,c.id DESC LIMIT p_limit) q),'[]'::jsonb);
END $$;

REVOKE ALL ON FUNCTION public.list_message_forward_targets(uuid,text,timestamptz,uuid,integer)
 FROM PUBLIC,anon,authenticated,service_role;
GRANT EXECUTE ON FUNCTION public.list_message_forward_targets(uuid,text,timestamptz,uuid,integer) TO authenticated;
NOTIFY pgrst,'reload schema';
COMMIT;
