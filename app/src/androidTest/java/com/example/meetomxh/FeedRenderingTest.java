package com.example.meetomxh;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.repositories.FeedRepository;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class FeedRenderingTest {
 @Test public void actualFeedRowsRenderAndRemainAuthoritative()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();String body="Android08 feed rendering "+UUID.randomUUID();JSONObject created=RpcClient.object("create_post",Api.obj("p_body",body,"p_audience","public","p_assets",new JSONArray(),"p_client_request_id",UUID.randomUUID()));String id=created.getString("id");
  JSONArray rows=(JSONArray)RpcClient.call("get_feed",Api.obj("p_limit",20));assertTrue("created public row in feed",rows.toString().contains(id));for(int i=0;i<rows.length();i++)Dto.post(rows.getJSONObject(i));
  c.startActivity(new Intent(c,LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));SupabaseCutoverTest helper=new SupabaseCutoverTest();helper.screen(MainActivity.class);
  helper.waitFor("feed repository publishes actual row",()->{final boolean[] found={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{java.util.List<com.example.meetomxh.models.Post> posts=FeedRepository.getInstance().getPosts().getValue();if(posts!=null)for(com.example.meetomxh.models.Post post:posts)if(id.equals(post.getPostId()))found[0]=true;});return found[0];});
  final android.app.Activity main=helper.activity();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->((androidx.recyclerview.widget.RecyclerView)main.findViewById(R.id.rvFeed)).scrollToPosition(2));
  androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(body)).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
  try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"feed-rendering-results.json"))){out.write(Api.obj("actualFeedRpc",true,"dtoDecodedAllRows",true,"mainFeedRepositoryRendered",true).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
}
