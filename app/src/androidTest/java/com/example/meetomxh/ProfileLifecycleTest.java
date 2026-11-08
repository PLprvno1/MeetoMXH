package com.example.meetomxh;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ui.ActivityRequests;
import com.example.meetomxh.ui.MediaThumbnailLoader;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProfileLifecycleTest {
    private ActivityScenario<ProfileActivity> profile(){
        assertNotNull("A signed-in emulator is required",SessionManager.getInstance().getCurrentUser());
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),ProfileActivity.class)
                .putExtra("target_uid",SessionManager.getInstance().uid());
        return ActivityScenario.launch(intent);
    }

    @Test public void populatedReelGridCanBeDestroyedAndThumbnailClearedAgain(){
        ImageView[] cover=new ImageView[1];
        try(ActivityScenario<ProfileActivity> scenario=profile()){
            scenario.onActivity(activity->{
                RecyclerView grid=activity.findViewById(R.id.rvProfileMedia);
                activity.findViewById(R.id.layoutTimelineSection).setVisibility(View.GONE);
                activity.findViewById(R.id.layoutProfileMedia).setVisibility(View.VISIBLE);
                Post reel=new Post();reel.setPostId("00000000-0000-4000-8000-000000000001");reel.setReel(true);
                try{
                    Method set=grid.getAdapter().getClass().getDeclaredMethod("set",List.class);set.setAccessible(true);
                    set.invoke(grid.getAdapter(),Arrays.asList(reel,reel,reel));
                }catch(Exception error){throw new AssertionError(error);}
                grid.measure(View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
                grid.layout(0,0,900,600);
                assertNotNull("Must recycle a real bound Reel cell",grid.findViewHolderForAdapterPosition(0));
                cover[0]=grid.findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.profile_reel_cover);
            });
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            MediaThumbnailLoader.clear(cover[0]);
            assertNull(cover[0].getDrawable());
        });
    }

    @Test public void closingProfileSuppressesBothLateCallbackPaths(){
        AtomicInteger delivered=new AtomicInteger();
        @SuppressWarnings("unchecked") Api.Callback<Object>[] callback=new Api.Callback[1];
        try(ActivityScenario<ProfileActivity> scenario=profile()){
            scenario.onActivity(activity->{
                ActivityRequests requests=new ActivityRequests(activity);
                callback[0]=requests.guard(new Api.Callback<Object>(){
                    public void success(Object ignored){delivered.incrementAndGet();}
                    public void error(String ignored){delivered.incrementAndGet();}
                });
            });
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            callback[0].success(new Object());callback[0].error("late network failure");
        });
        assertEquals(0,delivered.get());
    }

    @Test @SuppressWarnings({"rawtypes","unchecked"})
    public void followerAvatarCanBeRecycledAfterItsActivityIsDestroyed(){
        RecyclerView.Adapter[] adapter=new RecyclerView.Adapter[1];
        RecyclerView.ViewHolder[] holder=new RecyclerView.ViewHolder[1];
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),FollowListActivity.class)
                .putExtra("target_uid",SessionManager.getInstance().uid()).putExtra("direction","following");
        try(ActivityScenario<FollowListActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(activity->{
                RecyclerView list=activity.findViewById(R.id.followList);adapter[0]=list.getAdapter();
                holder[0]=adapter[0].createViewHolder(list,0);
            });
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->adapter[0].onViewRecycled(holder[0]));
    }

    @Test public void closingProfileCancelsPendingRequest() throws Exception {
        CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger delivered=new AtomicInteger();
        try(ActivityScenario<ProfileActivity> scenario=profile()){
            scenario.onActivity(activity->new ActivityRequests(activity).run(()->{
                started.countDown();
                try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException expected){interrupted.countDown();}
                return true;
            },new Api.Callback<Boolean>(){
                public void success(Boolean ignored){delivered.incrementAndGet();}
                public void error(String ignored){delivered.incrementAndGet();}
            }));
            assertTrue(started.await(5,TimeUnit.SECONDS));
        }finally{release.countDown();}
        assertTrue("Activity destruction cancels its work",interrupted.await(5,TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertEquals(0,delivered.get());
    }
}
