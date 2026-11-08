package com.example.meetomxh;

import android.view.ContextThemeWrapper;
import android.widget.ImageView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.ui.ActivityRequests;
import com.example.meetomxh.ui.AvatarViews;
import com.example.meetomxh.ui.ViewSafety;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CrashSafetyTest {
    @Test public void malformedPostMediaSkipsInvalidEntries() throws Exception {
        JSONObject valid=new JSONObject().put("asset_id","image-id").put("mime_type","image/png");
        Post post=Dto.post(new JSONObject().put("media",new JSONArray().put(JSONObject.NULL).put("invalid")
                .put(new JSONObject()).put(valid)));
        assertEquals(1,post.getImageUrls().size());assertEquals("asset://image-id",post.getPostImage());
        assertEquals("image/png",post.getMediaTypes().get(0));
    }
    @Test public void malformedSharedAndReplyMediaDoNotCrashMessageParsing() throws Exception {
        for(Object invalid:new Object[]{JSONObject.NULL,"invalid"}){
            JSONObject data=new JSONObject().put("state","sent").put("kind","text")
                    .put("shared_post",new JSONObject().put("media",new JSONArray().put(invalid)))
                    .put("reply",new JSONObject().put("kind","media").put("media",new JSONArray().put(invalid)));
            assertNotNull(Dto.message(data));
        }
    }
    @Test public void destroyedThemedActivityDoesNotStartAvatarLoad(){
        ImageView[] avatar=new ImageView[1];
        try(ActivityScenario<ReportContentActivity> scenario=ActivityScenario.launch(ReportContentActivity.class)){
            scenario.onActivity(activity->{
                avatar[0]=new ImageView(new ContextThemeWrapper(activity,R.style.Theme_MeetoMXH));
                assertTrue(ViewSafety.isAlive(avatar[0]));
            });
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertFalse(ViewSafety.isAlive(avatar[0]));
            AvatarViews.load(avatar[0],"asset://unused","male");
            AvatarViews.load(avatar[0],null,"neutral");
        });
    }
    @Test public void closingSettingsCancelsWorkAndRejectsLateUiCallbacks() throws Exception {
        CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        AtomicInteger callbacks=new AtomicInteger();ActivityRequests[] requests=new ActivityRequests[1];
        Api.Callback<Object>[] guarded=new Api.Callback[1];
        try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(SettingsActivity.class)){
            scenario.onActivity(activity->{
                try{java.lang.reflect.Field field=SettingsActivity.class.getDeclaredField("requests");field.setAccessible(true);requests[0]=(ActivityRequests)field.get(activity);}
                catch(Exception error){throw new AssertionError(error);}
                guarded[0]=requests[0].guard(new Api.Callback<Object>(){
                    public void success(Object value){callbacks.incrementAndGet();}
                    public void error(String error){callbacks.incrementAndGet();}
                });
                requests[0].run(()->{
                    started.countDown();
                    try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);}
                    catch(InterruptedException expected){interrupted.countDown();throw expected;}
                    return new Object();
                },guarded[0]);
            });
            assertTrue(started.await(5,TimeUnit.SECONDS));
        }
        assertTrue(interrupted.await(5,TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            assertFalse(requests[0].alive());guarded[0].success(new Object());guarded[0].error("late failure");
            assertEquals(0,callbacks.get());
        });
    }
}
