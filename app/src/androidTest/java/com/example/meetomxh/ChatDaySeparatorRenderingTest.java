package com.example.meetomxh;

import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.adapters.ChatMessageAdapter;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.supabase.ServerTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChatDaySeparatorRenderingTest {
    private ChatMessage message(String id,String date){
        ChatMessage message=new ChatMessage();message.setMessageId(id);message.setText("Tin nhắn "+id);
        message.setSenderId("peer");message.setType("text");message.setCreatedAt(ServerTime.parse(date));return message;
    }
    private TextView label(RecyclerView list,int position){
        RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(position);assertNotNull(holder);
        return holder.itemView.findViewById(R.id.tvMessageDay);
    }
    @Test public void labelsFollowVisibleDayBoundariesAfterRemovalAndPrepending(){
        ChatMessage a=message("a","2026-11-02T01:00:00Z"),b=message("b","2026-11-02T02:00:00Z");
        ChatMessage c=message("c","2026-11-04T01:00:00Z"),d=message("d","2026-11-04T02:00:00Z");
        ChatMessage hidden=message("hidden","2026-11-03T01:00:00Z");hidden.setDeletedFor(Collections.singletonList("self"));
        ChatMessageAdapter adapter=new ChatMessageAdapter("self");RecyclerView[] list=new RecyclerView[1];
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class)
                .putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED,true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(activity->{
                list[0]=new RecyclerView(activity);list[0].setLayoutManager(new LinearLayoutManager(activity));
                list[0].setItemAnimator(null);activity.setContentView(list[0]);list[0].setAdapter(adapter);
                adapter.submitList(Arrays.asList(a,b,hidden,c,d));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertEquals(4,adapter.getItemCount());
                assertEquals(View.GONE,label(list[0],0).getVisibility());assertEquals(View.GONE,label(list[0],1).getVisibility());
                assertEquals(View.VISIBLE,label(list[0],2).getVisibility());assertFalse(label(list[0],2).getText().toString().isEmpty());
                assertEquals(View.GONE,label(list[0],3).getVisibility());
                adapter.submitList(Arrays.asList(c,d));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertEquals(View.GONE,label(list[0],0).getVisibility());assertEquals(View.GONE,label(list[0],1).getVisibility());
                adapter.submitList(Arrays.asList(a,c,d));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertEquals(View.GONE,label(list[0],0).getVisibility());assertEquals(View.VISIBLE,label(list[0],1).getVisibility());
                assertEquals(View.GONE,label(list[0],2).getVisibility());assertEquals("c",adapter.messageIdAt(1));
            });
        }
    }
}
