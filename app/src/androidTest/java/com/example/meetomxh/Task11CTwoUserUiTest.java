package com.example.meetomxh;

import android.app.Activity;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.supabase.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.setFailureHandler;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** Credentials arrive once through an ADB-forwarded loopback socket and remain in memory. */
@RunWith(AndroidJUnit4.class)
public class Task11CTwoUserUiTest {
    private static final String A = "95f6e952-c14b-48dd-9478-af2ac68841de";
    private static final String B = "32fde22e-5e5e-4e47-b2f6-ef09518ba1b9";
    private final SupabaseCutoverTest ui = new SupabaseCutoverTest();
    private final JSONArray checks = new JSONArray();
    private JSONObject credentials;
    private boolean passed;

    @Test public void accountSwitchFriendsPrivacyAndMessengerThroughUi() throws Exception {
        // Espresso failures must never print login fields or a view hierarchy containing credentials.
        setFailureHandler((error, matcher) -> {
            throw new AssertionError("UI action failed (details suppressed for credential safety): "
                    + error.getClass().getSimpleName());
        });
        try {
            try (ServerSocket server = new ServerSocket()) {
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 38711));
                server.setSoTimeout(120000);
                status("Credential receiver ready");
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10000);
                    credentials = new JSONObject(new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8)).readLine());
                }
            }
            signIn(0, A);
            String suffix = UUID.randomUUID().toString();
            boolean messengerOnly = "true".equals(InstrumentationRegistry.getArguments().getString("messenger_only"));
            if (!messengerOnly) {
            JSONObject publicPost = createPost("Task11C A public " + suffix, 0);
            JSONObject friendsPost = createPost("Task11C A friends " + suffix, 1);
            JSONObject privatePost = createPost("Task11C A private " + suffix, 2);
            String peerName = RpcClient.object("get_profile", Api.obj("p_target", B)).getString("display_name");
            openProfile(B);
            if (hasPeer("get_friends", B)) {
                awaitButton(R.id.btnSecondaryAction, "H\u1ee7y k\u1ebft b\u1ea1n");
                onView(withId(R.id.btnSecondaryAction)).perform(scrollTo(), click());
                await("unfriend UI removes own relation", () -> !hasPeer("get_friends", B));
            }
            if (hasPeer("get_sent_requests", B)) {
                awaitButton(R.id.btnPrimaryAction, "H\u1ee7y l\u1eddi m\u1eddi");
                onView(withId(R.id.btnPrimaryAction)).perform(scrollTo(), click());
                await("cancel own pending fixture from earlier interrupted run", () -> !hasPeer("get_sent_requests", B));
            }
            awaitButton(R.id.btnPrimaryAction, "Th\u00eam b\u1ea1n b\u00e8");
            onView(withId(R.id.btnPrimaryAction)).perform(scrollTo(), click());
            await("send UI creates one pending request", () -> hasPeer("get_sent_requests", B));
            awaitButton(R.id.btnPrimaryAction, "H\u1ee7y l\u1eddi m\u1eddi");
            onView(withId(R.id.btnPrimaryAction)).perform(scrollTo(), click());
            await("cancel UI removes pending request", () -> !hasPeer("get_sent_requests", B));
            sendFromProfile(B);

            signIn(1, B);
            await("B sees incoming request after account switch", () -> hasPeer("get_received_requests", A));
            deniedPost(friendsPost.getString("id"));
            deniedPost(privatePost.getString("id"));
            assertEquals(publicPost.getString("body"), RpcClient.object("get_post", Api.obj(
                    "p_post_id", publicPost.getString("id"))).getString("body"));
            checks.put("B public allow; friends/private deny before acceptance");
            openProfile(A);
            awaitButton(R.id.btnSecondaryAction, "T\u1eeb ch\u1ed1i");
            onView(withId(R.id.btnSecondaryAction)).perform(scrollTo(), click());
            await("decline UI clears request without friendship", () ->
                    !hasPeer("get_received_requests", A) && !hasPeer("get_friends", A));

            signIn(0, A);
            sendFromProfile(B);
            signIn(1, B);
            openProfile(A);
            awaitButton(R.id.btnPrimaryAction, "Ch\u1ea5p nh\u1eadn");
            onView(withId(R.id.btnPrimaryAction)).perform(scrollTo(), click());
            await("accept UI creates canonical friendship", () ->
                    hasPeer("get_friends", A) && !hasPeer("get_received_requests", A));
            assertEquals(friendsPost.getString("body"), RpcClient.object("get_post", Api.obj(
                    "p_post_id", friendsPost.getString("id"))).getString("body"));
            deniedPost(privatePost.getString("id"));
            checks.put("friends visibility changes after acceptance; private remains denied");
            awaitButton(R.id.btnPrimaryAction, "B\u1ea1n b\u00e8");
            } else {
                signIn(1, B);
                openProfile(A);
                awaitButton(R.id.btnPrimaryAction, "B\u1ea1n b\u00e8");
            }

            onView(withId(R.id.btnMessage)).perform(scrollTo(), click());
            ui.screen(ChatActivity.class);
            String conversation = ui.activity().getIntent().getStringExtra(ChatActivity.EXTRA_CHAT_ID);
            assertNotNull(conversation);
            String bMessage = "Task11C B UI " + suffix;
            sendMessage(bMessage, conversation);
            signIn(0, A);
            assertTrue(hasPeer("get_friends", B));
            openProfile(B);
            awaitButton(R.id.btnPrimaryAction, "B\u1ea1n b\u00e8");
            onView(withId(R.id.btnMessage)).perform(scrollTo(), click());
            ui.screen(ChatActivity.class);
            awaitVisible(bMessage);
            messageAction(bMessage, "Trả lời");
            String aMessage = "Task11C A UI " + suffix;
            sendMessage(aMessage, conversation);
            JSONObject replyPage = RpcClient.object("get_messages", Api.obj("p_conversation_id", conversation));
            boolean validReply = false;
            for (int i = 0; i < replyPage.getJSONArray("items").length(); i++) {
                JSONObject row = replyPage.getJSONArray("items").getJSONObject(i);
                if (aMessage.equals(row.optString("body"))) validReply = !row.isNull("reply_to_message_id");
            }
            assertTrue("reply composer creates actual reply link", validReply);
            signIn(1, B);
            openProfile(A);
            onView(withId(R.id.btnMessage)).perform(scrollTo(), click());
            ui.screen(ChatActivity.class);
            awaitVisible(aMessage);
            signIn(0, A);
            JSONObject history = RpcClient.object("get_messages", Api.obj("p_conversation_id", conversation));
            String messageId = null;
            JSONArray messages = history.getJSONArray("items");
            for (int i = 0; i < messages.length(); i++) if (aMessage.equals(messages.getJSONObject(i).optString("body")))
                messageId = messages.getJSONObject(i).getString("id");
            assertNotNull(messageId);
            final String sentId = messageId;
            await("B rendered A message and device read ACK advanced", () -> {
                JSONArray receipts = (JSONArray) RpcClient.call("get_read_receipts", Api.obj(
                        "p_conversation_id", conversation, "p_message_id", sentId));
                for (int i = 0; i < receipts.length(); i++) if (receipts.getJSONObject(i).optBoolean("read")) return true;
                return false;
            });
            checks.put("A/B profile-to-chat navigation, UI send/receive and actual viewport ACK");
            signIn(1, B);
            openProfile(A);
            onView(withId(R.id.btnMessage)).perform(scrollTo(), click());
            ui.screen(ChatActivity.class);
            awaitVisible(aMessage);
            messageAction(aMessage, "Xóa khỏi phía tôi");
            onView(withText("Xóa")).perform(click());
            await("B hides A message through confirmation UI", () ->
                    RpcClient.object("search_messages", Api.obj("p_conversation_id", conversation,
                            "p_query", aMessage)).getJSONArray("items").length() == 0);
            signIn(0, A);
            assertEquals("B hide preserves sender's message", 1, RpcClient.object("search_messages",
                    Api.obj("p_conversation_id", conversation, "p_query", aMessage)).getJSONArray("items").length());
            openProfile(B);
            onView(withId(R.id.btnMessage)).perform(scrollTo(), click());
            ui.screen(ChatActivity.class);
            awaitVisible(aMessage);
            messageAction(aMessage, "Thu hồi tin nhắn");
            onView(withText("Thu hồi")).perform(click());
            await("A recalls own reply through confirmation UI", () -> {
                JSONArray rows = RpcClient.object("get_messages", Api.obj("p_conversation_id", conversation))
                        .getJSONArray("items");
                for (int i = 0; i < rows.length(); i++) if (sentId.equals(rows.getJSONObject(i).optString("id")))
                    return "recalled".equals(rows.getJSONObject(i).optString("state")) && rows.getJSONObject(i).isNull("body");
                return false;
            });
            checks.put("reply linkage; B hide does not delete for A; sender recall creates server tombstone");
            passed = true;
        } finally {
            credentials = null;
            File output = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getExternalFilesDir(null), "task11c-two-user-ui-results.json");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                stream.write(Api.obj("passed", passed, "completedChecks", checks).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void signIn(int account, String expectedUid) throws Exception {
        if (!SessionManager.getInstance().uid().isEmpty()) {
            InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            ui.screen(MainActivity.class);
            onView(withId(R.id.nav_profile)).perform(click());
            onView(withId(R.id.btnLogout)).perform(scrollTo());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(600);
            onView(withId(R.id.btnLogout)).perform(click());
            status("Logout clicked; identityCleared=" + SessionManager.getInstance().uid().isEmpty());
            ui.screen(LoginActivity.class);
            assertTrue("logout clears current identity", SessionManager.getInstance().uid().isEmpty());
        } else ui.launch(LoginActivity.class);
        onView(withId(R.id.etEmail)).perform(replaceText(credentials.getJSONArray("emails").getString(account)), closeSoftKeyboard());
        onView(withId(R.id.etPassword)).perform(replaceText(credentials.getString("password")), closeSoftKeyboard());
        onView(withId(R.id.btnLogin)).perform(click());
        ui.screen(MainActivity.class);
        assertEquals("login routes to expected pre-onboarded identity", expectedUid, SessionManager.getInstance().uid());
        assertTrue(ProfileClient.self().optBoolean("onboarding_completed"));
        checks.put("UI logout/login account " + (account == 0 ? "A" : "B"));
    }

    private void openProfile(String peer) throws Exception {
        InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), ProfileActivity.class)
                .putExtra("target_uid", peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui.screen(ProfileActivity.class);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void sendFromProfile(String peer) throws Exception {
        openProfile(peer);
        awaitButton(R.id.btnPrimaryAction, "Th\u00eam b\u1ea1n b\u00e8");
        onView(withId(R.id.btnPrimaryAction)).perform(scrollTo(), click());
        await("profile send request UI updates pending", () -> hasPeer("get_sent_requests", peer));
    }

    private JSONObject createPost(String body, int audience) throws Exception {
        ui.launch(CreatePostActivity.class);
        onView(withId(R.id.etPostContent)).perform(replaceText(body), closeSoftKeyboard());
        onView(withId(R.id.btnAudience)).perform(click());
        String[] labels = {"C\u00f4ng khai", "B\u1ea1n b\u00e8", "Ch\u1ec9 m\u00ecnh t\u00f4i"};
        onView(withText(labels[audience])).perform(click());
        onView(withId(R.id.btnPostSubmit)).perform(click());
        final JSONObject[] result = {null};
        await("UI creates post at selected privacy", () -> {
            JSONArray rows = (JSONArray) RpcClient.call("get_user_posts", Api.obj("p_author", A, "p_limit", 50));
            for (int i = 0; i < rows.length(); i++) if (body.equals(rows.getJSONObject(i).optString("body"))) {
                result[0] = rows.getJSONObject(i); return true;
            }
            return false;
        });
        return result[0];
    }

    private boolean hasPeer(String rpc, String peer) throws Exception {
        JSONArray rows = (JSONArray) RpcClient.call(rpc, Api.obj());
        for (int i = 0; i < rows.length(); i++) if (peer.equals(rows.getJSONObject(i).optString("id"))) return true;
        return false;
    }

    private void deniedPost(String id) throws Exception {
        try { RpcClient.call("get_post", Api.obj("p_post_id", id)); fail("inaccessible post was disclosed"); }
        catch (ErrorMapper.HttpError denial) { assertTrue(denial.status == 403 || denial.status == 404); }
    }

    private void sendMessage(String body, String conversation) throws Exception {
        onView(withId(R.id.etMessage)).perform(replaceText(body), closeSoftKeyboard());
        onView(withId(R.id.btnSendMessage)).perform(click());
        await("message composer commits text", () -> RpcClient.object("get_messages", Api.obj(
                "p_conversation_id", conversation)).getJSONArray("items").toString().contains(body));
        awaitVisible(body);
    }

    private void awaitButton(int id, String text) throws Exception {
        await("profile relationship action rendered", () -> {
            try { onView(withId(id)).check(matches(org.hamcrest.Matchers.allOf(withText(text), isEnabled()))); return true; }
            catch (AssertionError notReady) { return false; }
        });
    }

    private void messageAction(String body, String label) throws Exception {
        onView(org.hamcrest.Matchers.allOf(withId(R.id.messageBubble), hasDescendant(withText(body))))
                .perform(longClick());
        await("message action menu rendered", () -> {
            try { onView(withText(label)).check(matches(isDisplayed())); return true; }
            catch (AssertionError notReady) { return false; }
        });
        onView(withText(label)).perform(click());
    }

    private void awaitVisible(String text) throws Exception {
        await("committed message rendered", () -> {
            try { onView(withText(text)).check(matches(isDisplayed())); return true; }
            catch (AssertionError notReady) { return false; }
        });
    }

    private void await(String label, SupabaseCutoverTest.Condition condition) throws Exception {
        ui.waitFor(label, condition);
        checks.put(label);
        status(label);
    }

    private void status(String label) {
        android.os.Bundle update = new android.os.Bundle();
        update.putString("stream", "Task11C: " + label + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, update);
    }
}
