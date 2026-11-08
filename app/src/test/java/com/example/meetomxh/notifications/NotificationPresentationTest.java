package com.example.meetomxh.notifications;

import com.example.meetomxh.models.NotificationItem;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationPresentationTest {
    private JSONObject message()throws Exception{return new JSONObject().put("kind","message").put("target_type","conversation").put("actor_name","Minh");}
    @Test public void groupNameIsTitleAndSenderAndMessageAreBody()throws Exception{
        NotificationPresentation p=NotificationPresentation.of(new NotificationItem(message().put("conversation_kind","group")
            .put("conversation_title","Nhóm bạn").put("message_preview","Xin chào")));
        assertEquals("Nhóm bạn",p.title);assertEquals("Minh: Xin chào",p.body);
    }
    @Test public void directMessageIsClearlyNamed()throws Exception{
        NotificationPresentation p=NotificationPresentation.of(new NotificationItem(message()));assertEquals("Minh",p.title);assertTrue(p.body.contains("Minh"));
    }
    @Test public void socialActivityUsesAppTitle()throws Exception{
        NotificationPresentation p=NotificationPresentation.of(new NotificationItem(message().put("kind","follow")));
        assertEquals("Meeto",p.title);assertEquals("Minh đã bắt đầu theo dõi bạn.",p.body);
    }
}
