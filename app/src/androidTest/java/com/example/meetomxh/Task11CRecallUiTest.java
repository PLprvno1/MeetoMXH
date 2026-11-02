package com.example.meetomxh;

import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.supabase.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Task11CRecallUiTest {
    @Test public void senderCanRecallVisibleMessageThroughOwnActionMenu() throws Exception {
        SupabaseCutoverTest ui = new SupabaseCutoverTest();
        String peer = "32fde22e-5e5e-4e47-b2f6-ef09518ba1b9";
        String conversation = RpcClient.object("get_or_create_private", Api.obj("p_peer", peer)).getString("id");
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), ChatActivity.class)
                .putExtra("chat_id", conversation).putExtra("other_uid", peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui.screen(ChatActivity.class);
        android.app.Activity current = ui.activity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertEquals(
                "send label fits on one line", 1,
                ((android.widget.TextView) current.findViewById(R.id.btnSendMessage)).getLineCount()));
        String body = "Task11C own recall " + UUID.randomUUID();
        onView(withId(R.id.etMessage)).perform(replaceText(body), closeSoftKeyboard());
        onView(withId(R.id.btnSendMessage)).perform(click());
        final String[] messageId = {null};
        ui.waitFor("own message committed", () -> {
            JSONArray rows = RpcClient.object("get_messages", Api.obj("p_conversation_id", conversation)).getJSONArray("items");
            for (int i = 0; i < rows.length(); i++) if (body.equals(rows.getJSONObject(i).optString("body"))) {
                assertEquals(SessionManager.getInstance().uid(), rows.getJSONObject(i).getString("sender_id"));
                messageId[0] = rows.getJSONObject(i).getString("id"); return true;
            }
            return false;
        });
        ui.waitFor("own message rendered", () -> {
            try { onView(withText(body)).check(matches(isDisplayed())); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withText(body)).perform(longClick());
        ui.waitFor("own recall menu visible", () -> {
            try { onView(withText("Thu hồi tin nhắn")).check(matches(isDisplayed())); return true; }
            catch (RuntimeException | AssertionError pending) { return false; }
        });
        onView(withText("Thu hồi tin nhắn")).perform(click());
        onView(withText("Thu hồi")).perform(click());
        ui.waitFor("server recalls exact message", () -> {
            JSONArray rows = RpcClient.object("get_messages", Api.obj("p_conversation_id", conversation)).getJSONArray("items");
            for (int i = 0; i < rows.length(); i++) if (messageId[0].equals(rows.getJSONObject(i).optString("id")))
                return rows.getJSONObject(i).isNull("body") && "recalled".equals(rows.getJSONObject(i).optString("state"));
            return false;
        });
    }
}
