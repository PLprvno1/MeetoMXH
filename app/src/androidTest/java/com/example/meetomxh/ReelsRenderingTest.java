package com.example.meetomxh;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.example.meetomxh.fragments.ReelsFragment;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.PostViewerUpdates;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ui.ReelPlaybackMonitor;
import androidx.media3.ui.PlayerView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Local rendering fixture: no reaction, message or view-count RPC is issued. */
@RunWith(AndroidJUnit4.class)
public class ReelsRenderingTest {
    private static Object get(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new AssertionError(e);}}
    private static void invoke(Object target,String name,Class<?>[] types,Object... args){try{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(target,args);}catch(Exception e){throw new AssertionError(e);}}

    @Test public void engagementAndAccessChecksKeepVideoSurfaceAndCaptionBinding(){
        Post post=new Post();post.setPostId(UUID.randomUUID().toString());post.setAuthorUid(SessionManager.getInstance().uid());
        post.setUserName("Local reel");post.setContent("Original caption");post.setVisibility("public");post.setReel(true);
        ReelsFragment fragment=new ReelsFragment();set(fragment,"feedLoading",true);
        RecyclerView[] list=new RecyclerView[1];RecyclerView.ViewHolder[] holder=new RecyclerView.ViewHolder[1];
        Request[] coverRequest=new Request[1];Object[] gesture=new Object[1];ReelPlaybackMonitor[] monitor=new ReelPlaybackMonitor[1];
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class)
                .putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED,true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(activity->{
                activity.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,fragment)
                        .setMaxLifecycle(fragment,Lifecycle.State.STARTED).commitNow();
                list[0]=fragment.requireView().findViewById(R.id.reels_list);
                RecyclerView.Adapter adapter=list[0].getAdapter();
                List<Post> rows=(List<Post>)get(adapter,"rows");rows.clear();rows.add(post);adapter.notifyDataSetChanged();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                holder[0]=list[0].findViewHolderForAdapterPosition(0);assertNotNull(holder[0]);
                assertNull(list[0].getItemAnimator());
                gesture[0]=get(holder[0],"gestureDetector");
                coverRequest[0]=new DrawableImageViewTarget((ImageView)get(holder[0],"cover")).getRequest();assertNotNull(coverRequest[0]);
                invoke(fragment,"createPlayer",new Class<?>[0]);set(fragment,"activePosition",0);
                monitor[0]=(ReelPlaybackMonitor)get(fragment,"playbackMonitor");
                monitor[0].attach((PlayerView)get(holder[0],"playerView"));
                set(monitor[0],"rendered",true);((View)get(holder[0],"cover")).setVisibility(View.GONE);
                post.setReaction("like");post.setLikesCount(12);post.setCommentsCount(3);post.setSaved(true);
                PostViewerUpdates.publishEngagement(post);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertSame(holder[0],list[0].findViewHolderForAdapterPosition(0));
                assertSame(gesture[0],get(holder[0],"gestureDetector"));
                assertSame(coverRequest[0],new DrawableImageViewTarget((ImageView)get(holder[0],"cover")).getRequest());
                assertTrue(monitor[0].hasFrame());assertTrue(monitor[0].owns((PlayerView)get(holder[0],"playerView")));
                assertEquals(View.GONE,((View)get(holder[0],"cover")).getVisibility());
                assertEquals("12",((TextView)get(holder[0],"likes")).getText().toString());
                assertEquals("3",((TextView)get(holder[0],"comments")).getText().toString());
                JSONObject authorized=new JSONObject();
                try{authorized.put("body",post.getContent()).put("audience","public").put("version",post.version)
                        .put("reactions_count",13).put("comments_count",4).put("my_reaction","like").put("is_saved",true);}catch(Exception e){throw new AssertionError(e);}
                invoke(fragment,"reconcileReel",new Class<?>[]{Post.class,JSONObject.class},post,authorized);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertSame(gesture[0],get(holder[0],"gestureDetector"));assertTrue(monitor[0].hasFrame());
                assertEquals("13",((TextView)get(holder[0],"likes")).getText().toString());
                post.setContent("Edited caption");PostViewerUpdates.publish(post,false);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertNotSame(gesture[0],get(holder[0],"gestureDetector"));
                assertTrue(((TextView)get(holder[0],"caption")).getText().toString().contains("Edited caption"));
            });
        }
    }
}
