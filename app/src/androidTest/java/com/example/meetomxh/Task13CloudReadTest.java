package com.example.meetomxh;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

/** Uses UI login and profile/social flows, then removes its uniquely named temporary post. */
@RunWith(AndroidJUnit4.class)
public class Task13CloudReadTest {
    @Test public void loginAndRenderCloudProfilePostsAndSuggestions() throws Exception {
        if(SessionManager.getInstance().uid().isEmpty()) {
            JSONObject credentials=receiveCredentials();
            JSONArray emails=credentials.getJSONArray("emails");
            String email=emails.getString(0), password=credentials.getString("password");
            InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
            onView(withId(R.id.etEmail)).perform(replaceText(email),closeSoftKeyboard());
            onView(withId(R.id.etPassword)).perform(replaceText(password),closeSoftKeyboard());
            onView(withId(R.id.btnLogin)).perform(click());
            waitForActivity(MainActivity.class);
            credentials=null;
        }
        Assert.assertFalse(SessionManager.getInstance().uid().isEmpty());
        String uid=SessionManager.getInstance().uid();
        JSONArray postPage=(JSONArray)RpcClient.call("get_user_posts",Api.obj("p_author",uid,"p_after_time",null,"p_after_id",null,"p_limit",20));
        Intent profileIntent=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),ProfileActivity.class)
                .putExtra("target_uid",uid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        InstrumentationRegistry.getInstrumentation().startActivitySync(profileIntent);
        waitForCount(postPage.length());

        String firstBody="Task13 profile test "+java.util.UUID.randomUUID();
        String editedBody=firstBody+" edited";
        String createdPostId=null;
        try {
            launchComposer(null);
            onView(withId(R.id.etPostContent)).perform(replaceText(firstBody),closeSoftKeyboard());
            onView(withId(R.id.btnAudience)).perform(click());
            int listId=android.content.res.Resources.getSystem().getIdentifier("select_dialog_listview","id","android");
            onData(org.hamcrest.Matchers.anything()).inAdapterView(withId(listId)).atPosition(2).perform(click());
            onView(withId(R.id.btnPostSubmit)).perform(click());
            waitForActivity(MainActivity.class);
            JSONObject created=awaitPost(firstBody,true);
            createdPostId=created.getString("id");
            Assert.assertEquals("test post is Only Me", "private", created.optString("audience"));

            openOwnProfile(uid);
            JSONArray afterCreate=(JSONArray)RpcClient.call("get_user_posts",Api.obj("p_author",uid,"p_after_time",null,"p_after_id",null,"p_limit",20));
            waitForCount(afterCreate.length());
            clickMoreForBody(firstBody);
            clickPopupItem("Ch\u1ec9nh s\u1eeda b\u00e0i vi\u1ebft");
            waitForActivity(CreatePostActivity.class);
            onView(withId(R.id.etPostContent)).perform(replaceText(editedBody),closeSoftKeyboard());
            onView(withId(R.id.btnPostSubmit)).perform(click());
            waitForActivity(ProfileActivity.class);
            JSONObject edited=awaitPost(editedBody,true);
            Assert.assertEquals("edit preserves private audience", "private", edited.optString("audience"));

            JSONObject current=RpcClient.object("get_post",Api.obj("p_post_id",createdPostId));
            RpcClient.call("delete_post",Api.obj("p_post_id",createdPostId,"p_expected_version",current.optLong("version")));
            awaitNoPost(createdPostId);
            createdPostId=null;
        } finally {
            if(createdPostId==null) {
                try { JSONObject leftover=findPost(firstBody); if(leftover!=null)createdPostId=leftover.getString("id"); }
                catch(Exception notCreatedOrEdited) {
                    try { JSONObject leftover=findPost(editedBody); if(leftover!=null)createdPostId=leftover.getString("id"); }
                    catch(Exception notCreated) { }
                }
            }
            if(createdPostId!=null) {
                try { JSONObject current=RpcClient.object("get_post",Api.obj("p_post_id",createdPostId)); RpcClient.call("delete_post",Api.obj("p_post_id",createdPostId,"p_expected_version",current.optLong("version"))); }
                catch(Exception alreadyDeleted) { }
            }
        }
        android.os.Bundle status=new android.os.Bundle();status.putString("stream","Task13 Cloud timeline, private text post create/edit, authorized cleanup, and suggestion UI checks passed; temporary post removed and no relationship changed.\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }

    private JSONObject receiveCredentials() throws Exception {
        try(ServerSocket server=new ServerSocket()){
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),38713));server.setSoTimeout(120000);
            android.os.Bundle status=new android.os.Bundle();status.putString("stream","Task13 in-memory UI login receiver ready\n");InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
            try(Socket socket=server.accept()){socket.setSoTimeout(10000);return new JSONObject(new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine());}
        }
    }
    private void logout() throws Exception {
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        onView(withId(R.id.nav_profile)).perform(click());onView(withId(R.id.btnLogout)).perform(androidx.test.espresso.action.ViewActions.scrollTo(),click());waitForActivity(LoginActivity.class);
    }
    private JSONArray allPages(String rpc)throws Exception{
        JSONArray all=new JSONArray();String time=null,id=null;
        for(int page=0;page<100;page++){
            JSONArray rows=(JSONArray)RpcClient.call(rpc,Api.obj("p_limit",50,"p_after_time",time,"p_after_id",id));
            for(int i=0;i<rows.length();i++)all.put(rows.get(i));
            if(rows.length()<50)break;
            JSONObject last=rows.getJSONObject(rows.length()-1);time=last.optString("created_at");id=last.optString("id");
        }
        return all;
    }
    private void launchComposer(String postId){Intent i=new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),CreatePostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(postId!=null)i.putExtra("post_id",postId);InstrumentationRegistry.getInstrumentation().startActivitySync(i);}
    private void openOwnProfile(String uid)throws Exception{InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),ProfileActivity.class).putExtra("target_uid",uid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));waitForActivity(ProfileActivity.class);}
    private JSONObject awaitPost(String body,boolean expected)throws Exception{
        final JSONObject[] found={null};waitUntil(()->{JSONArray rows=(JSONArray)RpcClient.call("get_user_posts",Api.obj("p_author",SessionManager.getInstance().uid(),"p_after_time",null,"p_after_id",null,"p_limit",20));for(int i=0;i<rows.length();i++)if(body.equals(rows.getJSONObject(i).optString("body"))){found[0]=rows.getJSONObject(i);return true;}return false;});return found[0];
    }
    private JSONObject findPost(String body)throws Exception{JSONArray rows=(JSONArray)RpcClient.call("get_user_posts",Api.obj("p_author",SessionManager.getInstance().uid(),"p_after_time",null,"p_after_id",null,"p_limit",20));for(int i=0;i<rows.length();i++)if(body.equals(rows.getJSONObject(i).optString("body")))return rows.getJSONObject(i);return null;}
    private void awaitNoPost(String id)throws Exception{waitUntil(()->{try{RpcClient.call("get_post",Api.obj("p_post_id",id));return false;}catch(com.example.meetomxh.supabase.ErrorMapper.HttpError error){return error.status==404;} });}
    private void clickMoreForBody(String body)throws Exception{
        waitUntil(()->{Activity activity=currentActivity();if(activity==null)return false;final boolean[] clicked={false};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{RecyclerView rv=findRecycler(activity.getWindow().getDecorView());if(rv!=null)for(int i=0;i<rv.getChildCount();i++)if(clickMoreIn(rv.getChildAt(i),body)){clicked[0]=true;break;}});return clicked[0];});
    }
    private void clickPopupItem(String label){
        onView(withText(label)).perform(new androidx.test.espresso.ViewAction(){
            @Override public org.hamcrest.Matcher<View> getConstraints(){return org.hamcrest.Matchers.<View>instanceOf(TextView.class);}
            @Override public String getDescription(){return "click popup menu item through its clickable parent";}
            @Override public void perform(androidx.test.espresso.UiController ui,View view){
                View row=view;
                while(row.getParent() instanceof View && !(row.getParent() instanceof android.widget.AdapterView))row=(View)row.getParent();
                Assert.assertTrue("popup item belongs to adapter",row.getParent() instanceof android.widget.AdapterView);
                android.widget.AdapterView<?> menu=(android.widget.AdapterView<?>)row.getParent();
                int position=menu.getPositionForView(row);
                Assert.assertTrue("popup item position found",position>=0);
                menu.performItemClick(row,position,menu.getItemIdAtPosition(position));
            }
        });
    }
    private boolean clickMoreIn(View view,String body){
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;TextView content=group.findViewById(R.id.tvContent);if(content!=null&&body.contentEquals(content.getText())){View more=group.findViewById(R.id.ibMore);if(more!=null){more.performClick();return true;}}for(int i=0;i<group.getChildCount();i++)if(clickMoreIn(group.getChildAt(i),body))return true;}return false;
    }
    private Activity currentActivity(){
        final Activity[] current={null};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{Collection<Activity> resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);if(!resumed.isEmpty())current[0]=resumed.iterator().next();});return current[0];
    }
    private void waitForActivity(Class<?> type)throws Exception{waitUntil(()->type.isInstance(currentActivity()));}
    private void waitForCount(int expected)throws Exception{waitUntil(()->{Activity activity=currentActivity();if(activity==null)return false;final int[] count={-1};InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{RecyclerView rv=findRecycler(activity.getWindow().getDecorView());if(rv!=null&&rv.getAdapter()!=null)count[0]=rv.getAdapter().getItemCount();});return count[0]==expected;});}
    private RecyclerView findRecycler(View view){if(view instanceof RecyclerView)return (RecyclerView)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup parent=(android.view.ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){RecyclerView found=findRecycler(parent.getChildAt(i));if(found!=null)return found;}}return null;}
    private void waitUntil(java.util.concurrent.Callable<Boolean> check)throws Exception{long deadline=android.os.SystemClock.uptimeMillis()+20000;while(android.os.SystemClock.uptimeMillis()<deadline){if(check.call())return;Thread.sleep(100);}Assert.fail("Cloud-backed screen timed out");}
}
