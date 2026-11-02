package com.example.meetomxh;
import com.example.meetomxh.supabase.EventDeduplicator;
import com.example.meetomxh.supabase.ServerTime;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.repositories.UserSearchQuery;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExampleUnitTest {
 @Test public void eventReplayDoesNotApplyTwice(){EventDeduplicator d=new EventDeduplicator(4);assertTrue(d.accept("event-2"));assertTrue(d.accept("event-1"));assertFalse(d.accept("event-2"));}
 @Test public void eventsAreScopedToSessionLifecycle(){EventDeduplicator d=new EventDeduplicator(4);assertTrue(d.accept("event-1"));d.clear();assertTrue(d.accept("event-1"));}
 @Test public void boundedEventHistoryDoesNotGrowForever(){EventDeduplicator d=new EventDeduplicator(2);d.accept("one");d.accept("two");d.accept("three");assertTrue(d.accept("one"));assertFalse(d.accept("three"));}
 @Test public void nullEventIsRejected(){EventDeduplicator d=new EventDeduplicator(2);assertFalse(d.accept(null));assertFalse(d.accept(""));}
 @Test public void serverTimeHonorsOffsets(){assertEquals(ServerTime.parse("2026-11-02T00:00:00Z"),ServerTime.parse("2026-11-02T07:00:00+07:00"));}
 @Test public void malformedTimeIsNotDeviceNow(){assertNull(ServerTime.parse("not-a-server-time"));}
 @Test public void hiddenMessageIsScopedToOneViewer(){ChatMessage m=new ChatMessage();m.setDeletedFor(java.util.Collections.singletonList("actor-a"));assertTrue(m.isDeletedFor("actor-a"));assertFalse(m.isDeletedFor("actor-b"));}
 @Test public void searchDoesNotResurrectRecalledContent(){ChatMessage m=new ChatMessage();m.setType("text");m.setText("private needle");assertTrue(m.matchesVisibleTextQuery("a","NEED"));m.setRecalled(true);assertFalse(m.matchesVisibleTextQuery("a","needle"));}
 @Test public void searchDoesNotResurrectHiddenContent(){ChatMessage m=new ChatMessage();m.setType("text");m.setText("needle");m.setDeletedFor(java.util.Collections.singletonList("a"));assertFalse(m.matchesVisibleTextQuery("a","needle"));assertTrue(m.matchesVisibleTextQuery("b","needle"));}
 @Test public void searchInputsNormalizeSafely(){assertEquals("",UserSearchQuery.normalize(null));assertEquals("An",UserSearchQuery.normalize("  An "));assertFalse(UserSearchQuery.isCurrent("a",1,"ab",2));}
}