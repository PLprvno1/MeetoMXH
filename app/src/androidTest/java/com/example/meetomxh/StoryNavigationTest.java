package com.example.meetomxh;

import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.models.Story;
import com.example.meetomxh.ui.StoryPlaybackOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StoryNavigationTest {
    private static Object get(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private static void set(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception error){throw new AssertionError(error);}}
    private static Story story(String id,String author,boolean own){Story story=new Story();story.setId(id);story.setAuthorId(author);story.setOwn(own);story.setExpiresAt("2099-01-01T00:00:00Z");return story;}
    private static void tap(View target,boolean right){
        assertTrue(target.getWidth()>0);long time=SystemClock.uptimeMillis();float x=target.getWidth()*(right?.8f:.2f),y=target.getHeight()*.5f;
        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent up=MotionEvent.obtain(time,time+20,MotionEvent.ACTION_UP,x,y,0);
        try{target.dispatchTouchEvent(down);target.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
    }
    @Test @SuppressWarnings("unchecked") public void rightTapAfterOwnLastStoryOpensNextAuthorAndLeftTapReturns(){
        String self="00000000-0000-4000-8000-000000000001",peer="00000000-0000-4000-8000-000000000002";
        Story first=story("00000000-0000-4000-8000-000000000011",self,true);
        Story second=story("00000000-0000-4000-8000-000000000012",self,true);
        Story other=story("00000000-0000-4000-8000-000000000013",peer,false);
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),StoryViewActivity.class);
        // No entry extras: do not fetch or render real stories. Install local navigation fixtures below.
        try(ActivityScenario<StoryViewActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(activity->{
                List<Story> ordered=StoryPlaybackOrder.replaceAuthor(Arrays.asList(other,first),Arrays.asList(first,second),self,self);
                List<List<Story>> groups=(List<List<Story>>)get(activity,"groups");groups.clear();
                for(Story story:ordered){if(groups.isEmpty()||!groups.get(groups.size()-1).get(0).getAuthorId().equals(story.getAuthorId()))groups.add(new ArrayList<>());groups.get(groups.size()-1).add(story);}
                ((Set<String>)get(activity,"expandedAuthors")).addAll(Arrays.asList(self,peer));
                set(activity,"group",0);set(activity,"index",0);set(activity,"current",first);set(activity,"ready",false);
                View target=activity.findViewById(R.id.story_gestures);
                tap(target,true);assertFalse(activity.isFinishing());assertSame(second,get(activity,"current"));
                tap(target,true);assertFalse(activity.isFinishing());assertSame(other,get(activity,"current"));
                tap(target,false);assertFalse(activity.isFinishing());assertSame(second,get(activity,"current"));
                tap(target,true);assertFalse(activity.isFinishing());assertSame(other,get(activity,"current"));
                tap(target,true);assertTrue("Close only after the final available author",activity.isFinishing());
            });
        }
    }
}
