-- Group notifications identify the conversation and preview their exact message.
-- Read current message state rather than persisting body text in notifications,
-- so recall, hide, block and membership changes still protect the preview.
BEGIN;

CREATE OR REPLACE FUNCTION private.get_notifications(p_unread_only boolean,p_after_time timestamptz,p_after_id uuid,p_limit integer) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE actor uuid:=private.require_identity();
BEGIN
  PERFORM private.b_active(); PERFORM private.b_page(p_limit,p_after_time,p_after_id);
  IF p_unread_only IS NULL THEN RAISE SQLSTATE 'PT422' USING MESSAGE='invalid_notification_filter'; END IF;
  RETURN coalesce((SELECT jsonb_agg(jsonb_build_object('id',q.id,'kind',q.kind,'target_type',q.target_type,
      'target_id',CASE WHEN private.notification_target_readable(q.target_type,q.target_id) THEN q.target_id END,
      'created_at',q.created_at,'read_at',q.read_at,
      'target_context_id',CASE WHEN q.target_type='comment' AND private.notification_target_readable(q.target_type,q.target_id)
        THEN (SELECT c.post_id FROM public.comments c WHERE c.id=q.target_id) END,
      'actor_id',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN q.actor_id END,
      'actor_name',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.display_name ELSE 'Meeto user' END,
      'actor_avatar_asset_id',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.avatar_asset_id END,
      'actor_avatar_preset',CASE WHEN q.actor_id IS NOT NULL AND private.can_read_profile(q.actor_id) THEN p.public_avatar_preset ELSE 'neutral' END,
      'conversation_kind',conversation.kind,
      'conversation_title',CASE WHEN conversation.kind='group' THEN coalesce(nullif(btrim(conversation.title),''),'Nhóm chat') END,
      'message_preview',CASE WHEN conversation.kind='group' THEN (
        SELECT CASE
          WHEN m.state<>'sent' THEN 'Tin nhắn đã được thu hồi hoặc gỡ bỏ.'
          WHEN nullif(btrim(m.body),'') IS NOT NULL THEN left(m.body,240)
          WHEN m.is_shared_post THEN 'Đã chia sẻ bài viết.'
          WHEN m.kind='media' THEN 'Đã gửi ảnh/video hoặc tệp đính kèm.'
          ELSE 'Đã gửi tin nhắn.' END
        FROM public.messages m
        WHERE m.id=CASE WHEN q.event_key ~ '^message:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
          THEN substring(q.event_key FROM 9)::uuid END
          AND m.conversation_id=conversation.id AND m.sender_id=q.actor_id
          AND private.can_read_message(m.id)
      ) END,
      'target_available',private.notification_target_readable(q.target_type,q.target_id)) ORDER BY q.created_at DESC,q.id DESC)
    FROM (SELECT n.* FROM public.notifications n WHERE n.recipient_id=actor AND n.kind NOT IN ('friend_request','friend_accepted','group','memories','marketplace')
      AND (NOT p_unread_only OR n.read_at IS NULL)
      AND (p_after_time IS NULL OR (n.created_at,n.id)<(p_after_time,p_after_id))
      ORDER BY n.created_at DESC,n.id DESC LIMIT p_limit) q
      LEFT JOIN public.profiles p ON p.id=q.actor_id
      LEFT JOIN public.conversations conversation ON q.kind='message' AND q.target_type='conversation'
        AND conversation.id=q.target_id AND private.can_read_conversation(conversation.id)), '[]'::jsonb);
END $$;

COMMIT;
