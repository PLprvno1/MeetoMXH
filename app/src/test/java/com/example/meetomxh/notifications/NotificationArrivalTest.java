package com.example.meetomxh.notifications;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationArrivalTest {
    @Test public void initialUnreadItemsAreBaselinedAndNewItemsAreDeduplicated(){
        NotificationArrival state=new NotificationArrival();assertFalse(state.isInitialized());
        assertTrue(state.accept("old",100));state.initialized();assertTrue(state.isInitialized());
        assertFalse(state.accept("old",100));assertTrue(state.accept("new",200));assertFalse(state.accept("new",200));
    }
    @Test public void clearingSessionRemovesPriorAccountState(){
        NotificationArrival state=new NotificationArrival();state.accept("id",100);state.initialized();state.clear();
        assertFalse(state.isInitialized());assertTrue(state.accept("id",100));
    }
    @Test public void emptyIdentifiersAreIgnored(){NotificationArrival state=new NotificationArrival();assertFalse(state.accept(null,0));assertFalse(state.accept("",0));}
    @Test public void olderUnreadItemsEnteringThePageDoNotReplayAlerts(){
        NotificationArrival state=new NotificationArrival();state.accept("latest",200);state.initialized();
        assertFalse(state.accept("old-backlog",100));assertTrue(state.accept("new",300));
    }
}
