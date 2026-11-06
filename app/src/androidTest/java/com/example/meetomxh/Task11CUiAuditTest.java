package com.example.meetomxh;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ErrorMapper;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ChatActivity;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;

@RunWith(AndroidJUnit4.class)
public class Task11CUiAuditTest {
    private final SupabaseCutoverTest helper = new SupabaseCutoverTest();

    @Before public void startWithCleanNavigationAndRetainedSession() throws Exception {
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        helper.screen(MainActivity.class);
    }

    @Test public void profileEditorUiSavesAndRestoresOnlyOwnedSyntheticChange() throws Exception {
        Assert.assertFalse("signed-in synthetic account", SessionManager.getInstance().uid().isEmpty());
        JSONObject original = ProfileClient.self();
        String originalBio = original.optString("bio", "");
        String marker = "Task11C profile UI " + UUID.randomUUID();

        helper.launch(MainActivity.class);
        helper.launch(ProfileEditActivity.class);
        helper.waitFor("profile editor rendered current bio", () -> {
            try {
                onView(withHint("Họ và tên")).check(matches(withText(original.getString("display_name"))));
                onView(withHint("Tiểu sử")).check(matches(withText(originalBio)));
                return true;
            } catch (Throwable viewNotReady) { return false; }
        });
        onView(withHint("Tiểu sử")).perform(replaceText(marker), closeSoftKeyboard());
        onView(withText("Lưu hồ sơ")).perform(click());
        long saveDeadline = System.currentTimeMillis() + 60000;
        boolean saved = false;
        while (System.currentTimeMillis() < saveDeadline) {
            if (marker.equals(ProfileClient.self().optString("bio"))) { saved = true; break; }
            Thread.sleep(500);
        }
        Assert.assertTrue("profile editor UI save; status=" + formStatus(), saved);

        onView(withHint("Tiểu sử")).perform(replaceText(originalBio), closeSoftKeyboard());
        onView(withText("Lưu hồ sơ")).perform(click());
        helper.waitFor("profile editor UI restored original bio", () ->
                originalBio.equals(ProfileClient.self().optString("bio")));

        String cancelled = "Task11C cancelled edit " + UUID.randomUUID();
        onView(withHint("Tiểu sử")).perform(replaceText(cancelled), closeSoftKeyboard());
        pressBack();
        helper.waitFor("back closes profile editor without saving", () ->
                !ProfileEditActivity.class.isInstance(helper.activity()));
        Assert.assertEquals("cancel leaves server bio untouched", originalBio,
                ProfileClient.self().optString("bio", ""));
    }

    @Test public void profileNameAndBioRefreshAfterReturningFromEditor() throws Exception {
        JSONObject before = ProfileClient.self();
        String oldName = before.getString("display_name");
        String oldBio = before.optString("bio", "");
        String marker = "Task11C profile " + UUID.randomUUID().toString().substring(0, 8);
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), ProfileActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        helper.screen(ProfileActivity.class);
        helper.waitFor("own profile loaded", () -> {
            try { onView(withId(R.id.tvFullName)).check(matches(withText(oldName))); return true; }
            catch (AssertionError notReady) { return false; }
        });
        onView(withId(R.id.btnEditProfile)).perform(click());
        helper.screen(ProfileEditActivity.class);
        helper.waitFor("editor fields populated", () -> {
            try { onView(withHint("Họ và tên")).check(matches(withText(oldName))); return true; }
            catch (AssertionError notReady) { return false; }
        });
        try {
            onView(withHint("Họ và tên")).perform(replaceText(marker), closeSoftKeyboard());
            onView(withHint("Tiểu sử")).perform(replaceText(marker), closeSoftKeyboard());
            onView(withText("Lưu hồ sơ")).perform(click());
            helper.waitFor("name and bio saved through editor", () -> marker.equals(ProfileClient.self().optString("display_name")));
            pressBack();
            helper.screen(ProfileActivity.class);
            helper.waitFor("profile UI refreshes name and bio on return", () -> {
                try {
                    onView(withId(R.id.tvFullName)).check(matches(withText(marker)));
                    onView(withId(R.id.tvBio)).check(matches(withText(marker)));
                    return true;
                } catch (AssertionError notReady) { return false; }
            });
        } finally {
            JSONObject current = ProfileClient.self();
            if (marker.equals(current.optString("display_name"))) RpcClient.call("update_profile", Api.obj(
                    "p_display_name", oldName, "p_bio", oldBio,
                    "p_avatar_asset_id", current.opt("avatar_asset_id"),
                    "p_cover_asset_id", current.opt("cover_asset_id"),
                    "p_expected_version", current.getLong("version")));
        }
    }

    private String formStatus() {
        final String[] text = {"<unavailable>"};
        Activity activity = helper.activity();
        if (activity == null) return text[0];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            TextView status = activity.findViewById(R.id.formStatus);
            if (status != null) text[0] = String.valueOf(status.getText());
        });
        return text[0];
    }

    @Test public void postCreateReactionEditAndDeleteUseTheVisibleOwnedRow() throws Exception {
        String owner = SessionManager.getInstance().uid();
        Assert.assertFalse("signed-in synthetic account", owner.isEmpty());
        String marker = "Task11C post " + UUID.randomUUID();
        String edited = marker + " edited";

        helper.launch(MainActivity.class);
        helper.launch(CreatePostActivity.class);
        onView(withId(R.id.etPostContent)).perform(replaceText(marker), closeSoftKeyboard());
        onView(withId(R.id.btnPostSubmit)).perform(click());

        final JSONObject[] created = {null};
        helper.waitFor("post create UI committed once", () -> {
            JSONArray rows = (JSONArray) RpcClient.call("get_user_posts", Api.obj(
                    "p_author", owner, "p_limit", 50));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (marker.equals(row.optString("body"))) {
                    created[0] = row;
                    return true;
                }
            }
            return false;
        });
        String postId = created[0].getString("id");
        helper.waitFor("new post rendered in feed", () -> {
            Activity activity = helper.activity();
            if (activity == null || !activity.findViewById(R.id.rvFeed).isShown()) return false;
            try {
                onView(withText(marker)).check(matches(isDisplayed()));
                return true;
            } catch (Throwable ignored) { return false; }
        });

        onView(inPostRow(R.id.btnLike, marker)).perform(click());
        helper.waitFor("feed like UI reaches desired server state", () ->
                "like".equals(((JSONObject) RpcClient.call("get_post", Api.obj("p_post_id", postId)))
                        .optString("my_reaction")));
        helper.waitFor("feed row reflects server like before re-tap", () -> postLikedInFeed(postId));
        onView(inPostRow(R.id.btnLike, marker)).perform(click());
        long unlikeDeadline = System.currentTimeMillis() + 15000;
        JSONObject afterUnlike = null;
        while (System.currentTimeMillis() < unlikeDeadline) {
            afterUnlike = (JSONObject) RpcClient.call("get_post", Api.obj("p_post_id", postId));
            if (afterUnlike.isNull("my_reaction")) break;
            Thread.sleep(400);
        }
        Assert.assertNotNull(afterUnlike);
        Assert.assertTrue("feed unlike UI clears server state", afterUnlike.isNull("my_reaction"));

        onView(inPostRow(R.id.ibMore, marker)).perform(click());
        onView(withText("Chỉnh sửa bài viết")).perform(click());
        helper.screen(CreatePostActivity.class);
        helper.waitFor("edit form loaded server post before typing", () -> {
            try { onView(withId(R.id.etPostContent)).check(matches(withText(marker))); return true; }
            catch (Throwable viewNotReady) { return false; }
        });
        onView(withId(R.id.etPostContent)).perform(replaceText(edited), closeSoftKeyboard());
        onView(withId(R.id.btnPostSubmit)).perform(click());
        helper.waitFor("post edit UI updated same row", () ->
                edited.equals(((JSONObject) RpcClient.call("get_post", Api.obj("p_post_id", postId)))
                        .optString("body")));
        helper.waitFor("edited post row refreshed before delete menu", () -> {
            try { onView(withText(edited)).check(matches(isDisplayed())); return true; }
            catch (Throwable viewNotReady) { return false; }
        });

        onView(inPostRow(R.id.ibMore, edited)).perform(click());
        onView(withText("Xóa bài viết")).perform(click());
        onView(withText("Xóa")).perform(click());
        helper.waitFor("post delete UI tombstoned owned row", () -> {
            try {
                RpcClient.call("get_post", Api.obj("p_post_id", postId));
                return false;
            } catch (ErrorMapper.HttpError expectedAfterTombstone) {
                return expectedAfterTombstone.status == 404 || expectedAfterTombstone.status == 410;
            }
        });
    }



    @Test public void editPrivacyLabelAndCancelPreserveTheServerDesiredState() throws Exception {
        String body = "Task11C private editor " + UUID.randomUUID();
        JSONObject created = RpcClient.object("create_post", Api.obj("p_body", body, "p_audience", "private",
                "p_assets", new JSONArray(), "p_client_request_id", UUID.randomUUID()));
        String id = created.getString("id");
        helper.launch(MainActivity.class);
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), CreatePostActivity.class)
                .putExtra("post_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        helper.waitFor("private editor loads existing content", () -> {
            try { onView(withId(R.id.etPostContent)).check(matches(withText(body))); return true; }
            catch (AssertionError notReady) { return false; }
        });
        onView(withId(R.id.btnAudience)).check(matches(withText("Chỉ mình tôi")));
        onView(withId(R.id.etPostContent)).perform(replaceText(body + " cancelled"), closeSoftKeyboard());
        onView(withId(R.id.btnBack)).perform(click());
        JSONObject unchanged = RpcClient.object("get_post", Api.obj("p_post_id", id));
        Assert.assertEquals(body, unchanged.getString("body"));
        Assert.assertEquals("private", unchanged.getString("audience"));
        Assert.assertEquals(created.getLong("version"), unchanged.getLong("version"));
    }

    @Test public void privateAndGroupMessengerSendFromComposerToCloud() throws Exception {
        JSONArray inbox = (JSONArray) RpcClient.call("get_inbox", Api.obj(
                "p_after_time", null, "p_after_id", null, "p_limit", 50,
                "p_include_archived", false));
        String privateId = null;
        String groupId = null;
        for (int i = 0; i < inbox.length(); i++) {
            JSONObject row = inbox.getJSONObject(i);
            if (!"active".equals(row.optString("state"))) continue;
            if (privateId == null && "private".equals(row.optString("kind"))) privateId = row.optString("id");
            if (groupId == null && "group".equals(row.optString("kind"))) groupId = row.optString("id");
        }
        Assert.assertNotNull("active private conversation fixture", privateId);
        Assert.assertNotNull("active group conversation fixture", groupId);
        helper.launch(MainActivity.class);
        sendFromChatUi(privateId, "Task11C private UI " + UUID.randomUUID());
        sendFromChatUi(groupId, "Task11C group UI " + UUID.randomUUID());
    }

    private void sendFromChatUi(String conversationId, String body) throws Exception {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(intent);
        helper.screen(ChatActivity.class);
        onView(withId(R.id.etMessage)).perform(replaceText(body), closeSoftKeyboard());
        onView(withId(R.id.btnSendMessage)).perform(click());
        helper.waitFor("chat UI message committed once", () -> {
            JSONObject page = RpcClient.object("get_messages", Api.obj(
                    "p_conversation_id", conversationId, "p_before_seq", null,
                    "p_after_seq", null, "p_limit", 100));
            return page.getJSONArray("items").toString().contains(body);
        });
        helper.waitFor("chat UI renders committed message", () -> {
            try { onView(withText(body)).check(matches(isDisplayed())); return true; }
            catch (Throwable viewNotReady) { return false; }
        });
    }

    private static Matcher<View> inPostRow(int childId, String body) {
        return allOf(withId(childId), new TypeSafeMatcher<View>() {
            @Override protected boolean matchesSafely(View view) {
                View current = view;
                while (current.getParent() instanceof View) {
                    current = (View) current.getParent();
                    if (current.getId() == R.id.rvFeed) break;
                    if (!(current instanceof ViewGroup)) continue;
                    View content = current.findViewById(R.id.tvContent);
                    if (content instanceof TextView && body.contentEquals(((TextView) content).getText()))
                        return true;
                }
                return false;
            }
            @Override public void describeTo(Description description) {
                description.appendText("view in post row with body ").appendValue(body);
            }
        });
    }

    private boolean postLikedInFeed(String postId) {
        java.util.List<com.example.meetomxh.models.Post> posts =
                com.example.meetomxh.repositories.FeedRepository.getInstance().getPosts().getValue();
        if (posts != null) for (com.example.meetomxh.models.Post post : posts)
            if (postId.equals(post.getPostId())) return post.isLiked();
        return false;
    }
}
