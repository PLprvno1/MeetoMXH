package com.example.meetomxh.ui;

import com.example.meetomxh.models.Chat;
import com.example.meetomxh.supabase.ServerTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class InboxOrderTest {
    private Chat chat(String id,String created,String last){
        Chat c=new Chat();c.setChatId(id);c.setCreatedAt(ServerTime.parse(created));c.setLastMessageAt(ServerTime.parse(last));return c;
    }
    @Test public void newEmptyGroupComesBeforeOlderConversations(){
        Chat old=chat("old","2026-11-01T10:00:00Z","2026-11-08T10:00:00Z");
        Chat group=chat("group","2026-11-08T11:00:00Z",null);
        List<Chat> chats=new ArrayList<>(Arrays.asList(old,group));chats.sort(InboxOrder.NEWEST_FIRST);
        assertEquals("group",chats.get(0).getChatId());
        assertEquals(group.getCreatedAt(),InboxOrder.activityTime(group));
    }
    @Test public void newlyReceivedMessageMovesItsConversationToTheTop(){
        Chat old=chat("old","2026-11-01T10:00:00Z","2026-11-08T12:00:00Z");
        Chat group=chat("group","2026-11-08T11:00:00Z",null);
        assertTrue(InboxOrder.NEWEST_FIRST.compare(old,group)<0);
    }
    @Test public void settingsUpdatesDoNotBumpOldConversations(){
        Chat old=chat("old","2026-11-01T10:00:00Z","2026-11-02T10:00:00Z");
        old.setUpdatedAt(ServerTime.parse("2026-11-08T12:00:00Z"));
        assertEquals(old.getLastMessageAt(),InboxOrder.activityTime(old));
    }
    @Test public void tiesAndMissingDatesHaveStableOrdering(){
        Chat a=chat("a",null,null),b=chat("b",null,null);
        assertTrue(InboxOrder.NEWEST_FIRST.compare(a,b)>0);
        b.setUpdatedAt(ServerTime.parse("2026-11-08T12:00:00Z"));
        assertTrue(InboxOrder.NEWEST_FIRST.compare(b,a)<0);
    }
}
