package com.example.meetomxh;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.supabase.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Task11CGroupUiTest {
    @Test public void createRenameArchiveRestoreAndPromoteGroupThroughMenus() throws Exception {
        SupabaseCutoverTest ui = new SupabaseCutoverTest();
        String peer = "32fde22e-5e5e-4e47-b2f6-ef09518ba1b9";
        String peerName = RpcClient.object("get_profile", Api.obj("p_target", peer)).getString("display_name");
        String title = "Task11C group menu " + UUID.randomUUID().toString().substring(0, 8);
        ui.launch(MainActivity.class);
        onView(withId(R.id.btnMessenger)).perform(click());
        onView(withId(R.id.btnCreateChatGroup)).perform(click());
        ui.waitFor("friend chooser loaded", () -> {
            try { onView(withText(peerName)).check(matches(isDisplayed())); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withText(peerName)).perform(click());
        onView(withHint("Tên nhóm chat")).perform(replaceText(title), closeSoftKeyboard());
        onView(withText("Tạo")).perform(click());
        ui.screen(ChatActivity.class);
        String id = ui.activity().getIntent().getStringExtra(ChatActivity.EXTRA_CHAT_ID);
        assertNotNull(id);
        ui.waitFor("created group header loaded", () -> {
            try { onView(withId(R.id.tvChatTitle)).check(matches(withText(title))); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withId(R.id.tvChatTitle)).perform(click());
        menu(ui, "Đổi tên nhóm");
        onView(isAssignableFrom(android.widget.EditText.class)).perform(replaceText(title + " renamed"), closeSoftKeyboard());
        onView(withText("Lưu")).perform(click());
        ui.waitFor("rename menu committed", () -> (title + " renamed").equals(
                RpcClient.object("get_conversation", Api.obj("p_conversation_id", id)).optString("title")));
        ui.waitFor("renamed group header updates in current screen", () -> {
            try { onView(withId(R.id.tvChatTitle)).check(matches(withText(title + " renamed"))); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withId(R.id.tvChatTitle)).perform(click());
        menu(ui, "Lưu trữ cuộc trò chuyện");
        ui.waitFor("archive state updates", () -> "archived".equals(
                RpcClient.object("get_conversation", Api.obj("p_conversation_id", id)).optString("state")));
        ui.waitFor("archived composer disabled", () -> {
            try { onView(withId(R.id.btnSendMessage)).check(matches(org.hamcrest.Matchers.not(isEnabled()))); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withId(R.id.tvChatTitle)).perform(click());
        menu(ui, "Mở lại cuộc trò chuyện");
        ui.waitFor("restored composer enabled", () -> {
            try { onView(withId(R.id.btnSendMessage)).check(matches(isEnabled())); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withId(R.id.tvChatTitle)).perform(click());
        menu(ui, "Thành viên");
        ui.waitFor("member roster visible", () -> {
            try { onView(withText(peerName + " · member")).check(matches(isDisplayed())); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withText(peerName + " · member")).perform(click());
        menu(ui, "Đặt quản trị viên");
        ui.waitFor("promote from roster updates role", () -> {
            JSONArray members = (JSONArray) RpcClient.call("get_conversation_members", Api.obj("p_conversation_id", id));
            for (int i = 0; i < members.length(); i++) {
                JSONObject member = members.getJSONObject(i);
                if (peer.equals(member.optString("user_id"))) return "admin".equals(member.optString("role"));
            }
            return false;
        });
        // AlertDialog item selection has already dismissed both menus.
        onView(withId(R.id.tvChatTitle)).perform(click());
        menu(ui, "Rời nhóm");
        ui.waitFor("leave returns from chat", () -> !(ui.activity() instanceof ChatActivity));
        try { RpcClient.call("get_conversation", Api.obj("p_conversation_id", id)); fail("left member still reads group"); }
        catch (ErrorMapper.HttpError denied) { assertTrue(denied.status == 403 || denied.status == 404); }
    }

    private void menu(SupabaseCutoverTest ui, String label) throws Exception {
        ui.waitFor("group menu action visible", () -> {
            try { onView(withText(label)).check(matches(isDisplayed())); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withText(label)).perform(click());
    }
}
