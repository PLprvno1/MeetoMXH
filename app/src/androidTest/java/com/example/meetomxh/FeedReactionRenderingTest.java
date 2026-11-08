package com.example.meetomxh;

import android.content.Intent;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.target.DrawableImageViewTarget;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.PostViewerUpdates;
import com.example.meetomxh.supabase.SessionManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FeedReactionRenderingTest {
    @Test public void likeAndUnlikeKeepTheSameImageRequestAndOnlyUpdateReaction(){
        AtomicInteger fullBinds=new AtomicInteger();RecyclerView[] list=new RecyclerView[1];
        Request[] original=new Request[1];ImageView[] photo=new ImageView[1];
        Post post=new Post();post.setPostId(UUID.randomUUID().toString());post.setAuthorUid(SessionManager.getInstance().uid());
        post.setUserName("Rendering test");post.setContent("Caption");post.setVisibility("public");post.setType("normal");
        String resource="android.resource://com.example.meetomxh/"+R.drawable.avatar_male;
        post.setPostImage(resource);post.setImageUrls(Collections.singletonList(resource));post.setMediaTypes(Collections.singletonList("image/png"));
        FeedAdapter.OnPostClickListener listener=new FeedAdapter.OnPostClickListener(){
            public void onLikeClick(Post p,Runnable done){done.run();}
            public void onReactionSelected(Post p,String reaction,FeedAdapter.ReactionCallback callback){
                PostViewerUpdates.publishEngagement(p);callback.onResult(true,null);
            }
            public void onCommentClick(Post p){} public void onShareClick(Post p){} public void onCreatePostClick(){}
            public void onAuthorClick(Post p){} public void onDeletePostClick(Post p){}
        };
        Intent intent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class)
            .putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED,true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            scenario.onActivity(activity->{
                list[0]=new RecyclerView(activity);list[0].setLayoutManager(new LinearLayoutManager(activity));activity.setContentView(list[0]);
                FeedAdapter adapter=new FeedAdapter(listener,false){
                    @Override public void onBindViewHolder(RecyclerView.ViewHolder holder,int position){fullBinds.incrementAndGet();super.onBindViewHolder(holder,position);}
                };
                adapter.setPosts(Collections.singletonList(post));list[0].setAdapter(adapter);
                list[0].measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1800,android.view.View.MeasureSpec.EXACTLY));
                list[0].layout(0,0,1080,1800);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                RecyclerView.ViewHolder holder=list[0].findViewHolderForAdapterPosition(0);assertNotNull(holder);
                photo[0]=holder.itemView.findViewById(R.id.ivPostImage);original[0]=new DrawableImageViewTarget(photo[0]).getRequest();
                assertNotNull(original[0]);holder.itemView.findViewById(R.id.btnLike).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertEquals(1,fullBinds.get());assertSame(original[0],new DrawableImageViewTarget(photo[0]).getRequest());
                RecyclerView.ViewHolder holder=list[0].findViewHolderForAdapterPosition(0);
                assertEquals("1 lượt thích",((TextView)holder.itemView.findViewById(R.id.tvLikesCount)).getText().toString());
                holder.itemView.findViewById(R.id.btnLike).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                assertEquals(1,fullBinds.get());assertSame(original[0],new DrawableImageViewTarget(photo[0]).getRequest());
                RecyclerView.ViewHolder holder=list[0].findViewHolderForAdapterPosition(0);
                assertEquals("0 lượt thích",((TextView)holder.itemView.findViewById(R.id.tvLikesCount)).getText().toString());
                post.setContent("Edited caption");PostViewerUpdates.publish(post,false);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->assertTrue("Content edits still bind the full card",fullBinds.get()>1));
        }
    }
}
