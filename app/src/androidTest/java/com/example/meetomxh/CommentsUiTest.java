package com.example.meetomxh;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.CommentClient;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RpcClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class CommentsUiTest {
    @Test public void actualCommentAndReplyUiResolveAuthorsAndDeleteOnlySelectedSibling() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject post = RpcClient.object("create_post", Api.obj(
                "p_body", "Android comment UI fixture",
                "p_audience", "public",
                "p_assets", new JSONArray(),
                "p_client_request_id", UUID.randomUUID()));
        String postId = post.getString("id");
        String rootBody = "Android UI root " + UUID.randomUUID();
        String firstReply = "Android UI reply " + UUID.randomUUID();
        String secondReply = "Android UI second reply " + UUID.randomUUID();

        SupabaseCutoverTest helper = new SupabaseCutoverTest();
        helper.launch(MainActivity.class);
        MainActivity activity = (MainActivity) helper.activity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                CommentsBottomSheetFragment.newInstance(postId)
                        .showNow(activity.getSupportFragmentManager(), "task11c-comments"));

        onView(withId(R.id.etCommentInput)).perform(replaceText(rootBody), closeSoftKeyboard());
        onView(withId(R.id.btnSendComment)).perform(click());
        helper.waitFor("root comment created through UI", () ->
                ((JSONArray) RpcClient.call("list_comments", Api.obj("p_post_id", postId))).length() == 1);
        String resolvedAuthor = ProfileClient.self().getString("display_name");
        assertEquals(resolvedAuthor, CommentClient.thread(postId, null).get(0).getAuthorName());

        onView(withText(rootBody)).perform(click());
        chooseFirstCommentAction();
        onView(withId(R.id.etCommentInput)).perform(replaceText(firstReply), closeSoftKeyboard());
        onView(withId(R.id.btnSendComment)).perform(click());

        JSONArray roots = (JSONArray) RpcClient.call("list_comments", Api.obj("p_post_id", postId));
        String rootId = roots.getJSONObject(0).getString("id");
        helper.waitFor("first reply created through UI", () ->
                ((JSONArray) RpcClient.call("list_comments", Api.obj(
                        "p_post_id", postId, "p_parent_id", rootId))).toString().contains(firstReply));

        onView(withId(R.id.btnCloseComments)).perform(click());
        helper.waitFor("root list restored after leaving reply view", () -> {
            try { onView(withText(rootBody)).check(matches(isDisplayed())); return true; }
            catch (Throwable viewNotReady) { return false; }
        });
        onView(withText(rootBody)).perform(click());
        chooseFirstCommentAction();
        onView(withId(R.id.etCommentInput)).perform(replaceText(secondReply), closeSoftKeyboard());
        onView(withId(R.id.btnSendComment)).perform(click());
        final String[] secondReplyId = {null};
        helper.waitFor("second sibling reply created through UI", () -> {
            JSONArray children = (JSONArray) RpcClient.call("list_comments", Api.obj(
                    "p_post_id", postId, "p_parent_id", rootId));
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                if (secondReply.equals(child.optString("body"))) {
                    secondReplyId[0] = child.getString("id");
                    return true;
                }
            }
            return false;
        });

        helper.waitFor("second reply rendered in open thread", () -> {
            try { onView(withText(secondReply)).check(matches(isDisplayed())); return true; }
            catch (Throwable viewNotReady) { return false; }
        });
        onView(allOf(withId(R.id.btnDeleteComment),
                withParent(hasDescendant(withText(secondReply))))).perform(click());
        helper.waitFor("delete targets selected reply and preserves sibling", () -> {
            JSONArray children = (JSONArray) RpcClient.call("list_comments", Api.obj(
                    "p_post_id", postId, "p_parent_id", rootId));
            boolean siblingVisible = false;
            boolean selectedTombstone = false;
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                if (firstReply.equals(child.optString("body"))) siblingVisible = true;
                if (child.isNull("body") && secondReplyId[0].equals(child.optString("id")))
                    selectedTombstone = true;
            }
            return children.length() == 2 && siblingVisible && selectedTombstone;
        });

        onView(withText(firstReply)).perform(click());
        onView(withText("Sửa")).perform(click());
        String editedReply = firstReply + " edited";
        onView(isAssignableFrom(android.widget.EditText.class)).perform(replaceText(editedReply), closeSoftKeyboard());
        onView(withText("Lưu")).perform(click());
        helper.waitFor("edit changes only surviving reply", () -> {
            JSONArray children = (JSONArray) RpcClient.call("list_comments", Api.obj(
                    "p_post_id", postId, "p_parent_id", rootId));
            return children.length() == 2 && children.toString().contains(editedReply);
        });
        onView(withId(R.id.etCommentInput)).perform(replaceText("   "), closeSoftKeyboard());
        onView(withId(R.id.btnSendComment)).perform(click());
        assertEquals("empty composer does not create another comment", 2,
                ((JSONArray) RpcClient.call("list_comments", Api.obj("p_post_id", postId, "p_parent_id", rootId))).length());

        File resultFile = new File(context.getExternalFilesDir(null), "comments-ui-results.json");
        try (FileOutputStream out = new FileOutputStream(resultFile)) {
            out.write(Api.obj("actualRootUi", true, "actualReplyUi", true,
                    "simultaneousSiblingReplies", true,
                    "selectedReplyDeletePreservedSibling", true,
                    "survivingReplyEditedThroughUi", true, "emptyCommentDidNotCommit", true,
                    "authorResolvedByProfileAcl", true).toString(2)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void chooseFirstCommentAction() {
        onView(withText("Tr\u1ea3 l\u1eddi")).perform(click());
    }
}
