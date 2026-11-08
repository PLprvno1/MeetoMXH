package com.example.meetomxh.supabase;

import com.example.meetomxh.models.Chat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class InboxProjectionTest {
    @Test public void projectionSuppliesMembershipMuteAndAvatarWithoutFollowupRequests()throws Exception{
        Chat c=new Chat();JSONObject row=new JSONObject().put("participants",new JSONArray().put("self").put("peer"))
            .put("muted",true).put("peer_avatar_asset_id","avatar-id").put("peer_avatar_preset","female");
        assertTrue(InboxProjection.apply(c,row));assertTrue(c.inboxProfileIncluded);assertTrue(c.muted);
        assertTrue(c.hasExactlyParticipants("self","peer"));assertEquals("asset://avatar-id",c.peerAvatar);
        assertEquals("female",c.peerAvatarPreset);
    }
    @Test public void olderServersUseCompatibilityPath()throws Exception{
        Chat c=new Chat();assertFalse(InboxProjection.apply(c,new JSONObject()));assertFalse(c.inboxProfileIncluded);
        assertFalse(InboxProjection.apply(c,new JSONObject().put("participants",new JSONArray())));
    }
    @Test public void missingAvatarKeepsPublicFallback()throws Exception{
        Chat c=new Chat();assertTrue(InboxProjection.apply(c,new JSONObject().put("participants",new JSONArray()).put("muted",false)));
        assertEquals("",c.peerAvatar);assertEquals("neutral",c.peerAvatarPreset);assertFalse(c.muted);
    }
}
