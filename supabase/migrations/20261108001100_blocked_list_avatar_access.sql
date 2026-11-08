-- Allow recognizing accounts in the caller's own block list by their current
-- avatar. Other profile media and reverse blocks retain their existing rules.
BEGIN;

CREATE OR REPLACE FUNCTION private.can_read_media(aid uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path='' AS $$
 SELECT private.is_active() AND EXISTS(SELECT 1 FROM public.media_assets a WHERE a.id=aid AND
 ((a.state='pending' AND a.owner_id=auth.uid() AND a.created_at>now()-interval '15 minutes') OR (a.state='ready' AND (
   EXISTS(SELECT 1 FROM public.profiles p WHERE ((p.avatar_asset_id=a.id AND a.purpose='avatar') OR (p.cover_asset_id=a.id AND a.purpose='cover')) AND a.owner_id=p.id AND private.can_read_profile(p.id) AND (a.purpose='avatar' OR private.account_content_allowed(p.id)))
   OR EXISTS(SELECT 1 FROM public.profiles p
     JOIN public.user_blocks b ON b.blocked_id=p.id AND b.blocker_id=auth.uid()
     WHERE p.avatar_asset_id=a.id AND a.purpose='avatar' AND a.owner_id=p.id
       AND private.social_target(p.id)
       AND NOT EXISTS(SELECT 1 FROM public.user_blocks reverse_block
         WHERE reverse_block.blocker_id=p.id AND reverse_block.blocked_id=auth.uid()))
   OR EXISTS(SELECT 1 FROM public.groups g WHERE false AND g.cover_asset_id=a.id AND a.bucket_id='group-media' AND a.purpose='group' AND g.state='active' AND ((g.privacy IN ('public','private') AND g.listed) OR private.group_member(g.id)))
   OR private.can_review_report_media(a.id)
   OR EXISTS(SELECT 1 FROM public.conversations c WHERE c.avatar_asset_id=a.id AND c.kind='group' AND a.purpose='avatar' AND private.can_read_conversation(c.id))
   OR EXISTS(SELECT 1 FROM public.post_media pm JOIN public.posts p ON p.id=pm.post_id WHERE pm.asset_id=a.id AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(pm.post_id))
   OR EXISTS(SELECT 1 FROM public.posts p WHERE p.reel_cover_asset_id=a.id AND p.is_reel
     AND a.purpose='post' AND a.owner_id=p.author_id AND private.can_read_post(p.id))
   OR EXISTS(SELECT 1 FROM public.stories s WHERE s.media_asset_id=a.id AND a.purpose='story' AND a.owner_id=s.author_id AND private.can_read_story(s.id))
   OR EXISTS(SELECT 1 FROM public.message_media mm JOIN public.messages m ON m.id=mm.message_id WHERE mm.asset_id=a.id AND a.purpose='message' AND a.owner_id=m.sender_id AND m.state='sent' AND private.can_read_message(m.id))))));
$$;

COMMIT;
