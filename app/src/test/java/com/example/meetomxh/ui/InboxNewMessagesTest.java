package com.example.meetomxh.ui;

import com.example.meetomxh.models.Chat;
import com.example.meetomxh.supabase.ServerTime;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class InboxNewMessagesTest {
    private Chat chat(String id,String message,long sequence){
        Chat c=new Chat();c.setChatId(id);c.setLastMessageId(message);c.highWater=sequence;
        c.setLastMessageAt(ServerTime.parse("2026-11-08T12:00:00Z"));return c;
    }
    @Test public void movingConversationToFirstRowRevealsItEvenWithinTheSameMinute(){
        InboxNewMessages state=new InboxNewMessages();Chat a=chat("a","a1",1),b=chat("b","b1",1);
        assertFalse(state.update(Arrays.asList(a,b)));
        b.setLastMessageId("b2");b.highWater=2;
        assertTrue(state.update(Arrays.asList(b,a)));
        assertFalse(state.update(Arrays.asList(b,a)));
    }
    @Test public void newMessageInAlreadyFirstConversationStillScrollsToTop(){
        InboxNewMessages state=new InboxNewMessages();Chat a=chat("a","1",1);
        state.update(Collections.singletonList(a));a.setLastMessageId("2");a.highWater=2;
        assertTrue(state.update(Collections.singletonList(a)));
    }
    @Test public void readMuteAndProfileRefreshesDoNotInterruptScrolling(){
        InboxNewMessages state=new InboxNewMessages();Chat a=chat("a","1",1);
        state.update(Collections.singletonList(a));a.unreadCount=0;a.muted=true;a.peerName="New name";
        assertFalse(state.update(Collections.singletonList(a)));
    }
    @Test public void hidingLatestMessageDoesNotLookLikeANewMessage(){
        InboxNewMessages state=new InboxNewMessages();state.update(Collections.singletonList(chat("a","2",2)));
        assertFalse(state.update(Collections.singletonList(chat("a","1",2))));
    }
    @Test public void newlyCreatedConversationIsRevealedAndClearResetsBaseline(){
        InboxNewMessages state=new InboxNewMessages();state.update(Collections.emptyList());
        assertTrue(state.update(Collections.singletonList(chat("group",null,0))));
        state.clear();assertFalse(state.update(Collections.singletonList(chat("group",null,0))));
    }
}
