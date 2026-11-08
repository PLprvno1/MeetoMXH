package com.example.meetomxh;

import android.content.Intent;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.fragments.InboxFragment;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.supabase.ServerTime;
import com.example.meetomxh.ui.InboxOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class InboxScrollTest {
    private void set(Object target,String name,Object value)throws Exception{
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private void call(Object target,String name){
        try{Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}
        catch(Exception error){throw new AssertionError(error);}
    }
    @Test public void movedConversationIsVisibleWithoutManualScrolling(){
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class)
            .putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED,true);
        List<Chat> chats=new ArrayList<>();
        for(int i=0;i<30;i++){
            Chat chat=new Chat();chat.setChatId("scroll-test-"+i);chat.setType("group");chat.title="Group "+i;
            chat.setLastMessageId("message-"+i);chat.setLastMessage("Original");chat.highWater=1;
            chat.setCreatedAt(new ServerTime(new Date(0)));chat.setLastMessageAt(new ServerTime(new Date(1000000-i*1000)));
            chats.add(chat);
        }
        InboxFragment[] fragment=new InboxFragment[1];RecyclerView[] list=new RecyclerView[1];
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(activity->{
                fragment[0]=new InboxFragment();activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container,fragment[0]).commitNow();
                call(fragment[0],"removeChatsListener");
                try{set(fragment[0],"chatsLoaded",true);set(fragment[0],"chats",chats);}
                catch(Exception e){throw new AssertionError(e);}
                call(fragment[0],"renderChats");list[0]=activity.findViewById(R.id.rvInbox);
                ((LinearLayoutManager)list[0].getLayoutManager()).scrollToPositionWithOffset(12,0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertTrue(((LinearLayoutManager)list[0].getLayoutManager()).findFirstVisibleItemPosition()>=10);
                Chat updated=chats.get(20);updated.setLastMessageId("new-message");updated.setLastMessage("New message");
                updated.highWater=2;updated.setLastMessageAt(ServerTime.now());chats.sort(InboxOrder.NEWEST_FIRST);
                call(fragment[0],"renderChats");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                LinearLayoutManager manager=(LinearLayoutManager)list[0].getLayoutManager();
                assertEquals(0,manager.findFirstVisibleItemPosition());
                RecyclerView.ViewHolder first=list[0].findViewHolderForAdapterPosition(0);assertNotNull(first);
                assertEquals("Group 20",((TextView)first.itemView.findViewById(R.id.tvChatName)).getText().toString());
                manager.scrollToPositionWithOffset(12,0);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->call(fragment[0],"renderChats"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->assertTrue("Unchanged refresh must preserve user scrolling",
                ((LinearLayoutManager)list[0].getLayoutManager()).findFirstVisibleItemPosition()>=10));
        }
    }
}
