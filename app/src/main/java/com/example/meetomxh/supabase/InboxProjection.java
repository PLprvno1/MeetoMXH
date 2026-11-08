package com.example.meetomxh.supabase;

import com.example.meetomxh.models.Chat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Inbox metadata returned with the conversation, without per-row requests. */
public final class InboxProjection {
    private InboxProjection(){}
    public static boolean apply(Chat chat,JSONObject row){
        JSONArray members=row.optJSONArray("participants");
        if(members==null||!row.has("muted"))return false;
        List<String> ids=new ArrayList<>();
        for(int i=0;i<members.length();i++){String id=members.optString(i,"");if(!id.isEmpty()&&!"null".equals(id))ids.add(id);}
        chat.setParticipants(ids);chat.muted=row.optBoolean("muted");
        chat.inboxProfileIncluded=true;
        chat.peerAvatar=Dto.asset(Dto.nullable(row,"peer_avatar_asset_id"));
        chat.peerAvatarPreset=row.optString("peer_avatar_preset","neutral");
        return true;
    }
}
