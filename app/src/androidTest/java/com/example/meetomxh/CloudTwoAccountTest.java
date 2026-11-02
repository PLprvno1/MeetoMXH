package com.example.meetomxh;

import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SupabaseConfig;
import com.example.meetomxh.supabase.SupabaseHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@RunWith(AndroidJUnit4.class)
public class CloudTwoAccountTest {
    private static final String USER_A = "95f6e952-c14b-48dd-9478-af2ac68841de";
    private static final String USER_B = "32fde22e-5e5e-4e47-b2f6-ef09518ba1b9";

    private Object rpc(String token, String name, JSONObject args) throws Exception {
        return SupabaseHttpClient.raw("POST", "/rest/v1/rpc/" + name, args, token);
    }

    private JSONObject rpcObject(String token, String name, JSONObject args) throws Exception {
        return (JSONObject) rpc(token, name, args);
    }

    @Test public void realTwoAccountCloudSocialMessengerAndCron() throws Exception {
        Assert.assertEquals("qjpohzpapiwdrxsionyr.supabase.co", android.net.Uri.parse(SupabaseConfig.URL).getHost());
        SessionManager sessions = SessionManager.getInstance();
        String tokenA = null, tokenB = null;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        if (USER_B.equals(sessions.uid())) {
            tokenB = sessions.access(false); // memory only; never logged or persisted by this test
            JSONObject profileB = rpcObject(tokenB, "get_my_profile", Api.obj());
            Assert.assertTrue("B onboarding complete", profileB.optBoolean("onboarding_completed"));
            openLoginAfterLocalClear(sessions);
            while (!USER_A.equals(sessions.uid()) && System.nanoTime() < deadline) Thread.sleep(500);
            Assert.assertEquals("Sign in as Test User A in the app", USER_A, sessions.uid());
            tokenA = sessions.access(false);
        } else {
            Assert.assertEquals("Sign in as Test User A before starting", USER_A, sessions.uid());
            tokenA = sessions.access(false);
            JSONObject profileA = rpcObject(tokenA, "get_my_profile", Api.obj());
            Assert.assertTrue("A onboarding complete", profileA.optBoolean("onboarding_completed"));
        }

        String marker = UUID.randomUUID().toString();
        JSONObject post = rpcObject(tokenA, "create_post", Api.obj("p_body", "Task11 Cloud public fixture " + marker,
                "p_audience", "public", "p_assets", new JSONArray(), "p_client_request_id", UUID.randomUUID()));
        String postId = post.getString("id");
        rpcObject(tokenA, "set_post_reaction", Api.obj("p_post_id", postId, "p_reaction", "love"));
        JSONObject root = rpcObject(tokenA, "create_comment", Api.obj("p_post_id", postId, "p_parent_id", null,
                "p_body", "Task11 root " + marker, "p_client_request_id", UUID.randomUUID()));
        JSONObject reply = rpcObject(tokenA, "create_comment", Api.obj("p_post_id", postId,
                "p_parent_id", root.getString("id"), "p_body", "Task11 reply " + marker,
                "p_client_request_id", UUID.randomUUID()));
        Assert.assertEquals("reply parent preserved", root.getString("id"), reply.getString("parent_id"));
        JSONObject privatePost = rpcObject(tokenA, "create_post", Api.obj("p_body", "Task11 private fixture " + marker,
                "p_audience", "private", "p_assets", new JSONArray(), "p_client_request_id", UUID.randomUUID()));
        // If B was not the starting session, preserve A's JWT in this process and ask for B login.
        if (tokenB == null) {
            openLoginAfterLocalClear(sessions);
            while (!USER_B.equals(sessions.uid()) && System.nanoTime() < deadline) Thread.sleep(500);
            Assert.assertEquals("Sign in as Test User B in the app", USER_B, sessions.uid());
            tokenB = sessions.access(false);
            JSONObject profileB = rpcObject(tokenB, "get_my_profile", Api.obj());
            while (!profileB.optBoolean("onboarding_completed") && System.nanoTime() < deadline) {
                Thread.sleep(1000);
                profileB = rpcObject(tokenB, "get_my_profile", Api.obj());
            }
            Assert.assertTrue("B onboarding completed through Register screen", profileB.optBoolean("onboarding_completed"));
        }

        JSONArray friendsA = (JSONArray) rpc(tokenA, "get_friends", Api.obj());
        if (!friendsA.toString().contains(USER_B)) {
            JSONObject request = rpcObject(tokenA, "send_friend_request", Api.obj("p_recipient", USER_B,
                    "p_client_request_id", UUID.randomUUID()));
            rpcObject(tokenB, "accept_friend_request", Api.obj("p_request_id", request.getString("id")));
        }
        Assert.assertTrue("A sees B in own friends list", ((JSONArray) rpc(tokenA, "get_friends", Api.obj())).toString().contains(USER_B));
        JSONArray friendsB = (JSONArray) rpc(tokenB, "get_friends", Api.obj());
        Assert.assertTrue("B sees A in own friends list", friendsB.toString().contains(USER_A));
        JSONObject visiblePost = rpcObject(tokenB, "get_post", Api.obj("p_post_id", postId));
        Assert.assertEquals("public post visible to B", postId, visiblePost.getString("id"));
        rpcObject(tokenB, "set_post_reaction", Api.obj("p_post_id", postId, "p_reaction", "like"));
        JSONObject bComment = rpcObject(tokenB, "create_comment", Api.obj("p_post_id", postId, "p_parent_id", null,
                "p_body", "Task11 B comment " + marker, "p_client_request_id", UUID.randomUUID()));
        JSONArray aComments = (JSONArray) rpc(tokenA, "list_comments", Api.obj("p_post_id", postId, "p_limit", 50));
        Assert.assertTrue("A sees B's comment through post ACL", aComments.toString().contains(bComment.getString("id")));
        boolean privateDenied = false;
        try { rpc(tokenB, "get_post", Api.obj("p_post_id", privatePost.getString("id"))); }
        catch (Exception denied) { privateDenied = true; }
        Assert.assertTrue("B cannot read A private post", privateDenied);

        JSONObject convo = rpcObject(tokenA, "get_or_create_private", Api.obj("p_peer", USER_B));
        String conversationId = convo.getString("id");
        JSONObject group = rpcObject(tokenA, "create_group_conversation", Api.obj("p_title", "Task11 group " + marker,
                "p_members", new JSONArray().put(USER_B), "p_client_request_id", UUID.randomUUID()));
        String groupId = group.getString("id");
        Assert.assertTrue("B can read invited group after friendship", rpcObject(tokenB, "get_conversation",
                Api.obj("p_conversation_id", groupId)).has("id"));

        BroadcastProbe probe = new BroadcastProbe(tokenB, USER_B);
        try {
            probe.open();
            Assert.assertTrue("B joins own private Broadcast topic", probe.joined.await(25, TimeUnit.SECONDS));
            Assert.assertTrue("private channel authorization succeeds", probe.allowed.get());
            JSONObject message = rpcObject(tokenA, "send_message", Api.obj("p_conversation_id", conversationId,
                    "p_client_request_id", UUID.randomUUID(), "p_kind", "text", "p_body", "Task11 Cron message " + marker,
                    "p_assets", new JSONArray(), "p_reply_to", null));
            Assert.assertTrue("Cloud Cron emits the private invalidation hint", probe.event.await(150, TimeUnit.SECONDS));
            Assert.assertTrue("broadcast contains only the invalidation contract", probe.validPayload.get());
            JSONArray history = (JSONArray) ((JSONObject) rpc(tokenB, "get_messages", Api.obj(
                    "p_conversation_id", conversationId, "p_after_seq", 0, "p_limit", 50))).getJSONArray("items");
            Assert.assertTrue("B refetch sees durable message", history.toString().contains(message.getString("id")));
            rpcObject(tokenB, "acknowledge_delivery", Api.obj("p_conversation_id", conversationId,
                    "p_message_id", message.getString("id")));
            rpcObject(tokenB, "mark_read", Api.obj("p_conversation_id", conversationId, "p_message_id", message.getString("id")));
            JSONArray receipts = (JSONArray) rpc(tokenA, "get_read_receipts", Api.obj("p_conversation_id", conversationId,
                    "p_message_id", message.getString("id")));
            Assert.assertTrue("sender sees B delivery/read receipt", receipts.toString().contains("\"read\":true"));

            JSONObject groupMessage = rpcObject(tokenA, "send_message", Api.obj("p_conversation_id", groupId,
                    "p_client_request_id", UUID.randomUUID(), "p_kind", "text", "p_body", "Task11 group message " + marker,
                    "p_assets", new JSONArray()));
            JSONArray groupHistory = (JSONArray) ((JSONObject) rpc(tokenB, "get_messages", Api.obj(
                    "p_conversation_id", groupId, "p_after_seq", 0, "p_limit", 50))).getJSONArray("items");
            Assert.assertTrue("B reads group message", groupHistory.toString().contains(groupMessage.getString("id")));
            String activeIdentity = sessions.uid();
            Assert.assertTrue("current app session belongs to one test account",
                    USER_A.equals(activeIdentity) || USER_B.equals(activeIdentity));
            String refreshedAccess = sessions.access(true);
            Assert.assertTrue("real GoTrue refresh returns an access token", refreshedAccess != null && !refreshedAccess.isEmpty());
            Assert.assertEquals("real GoTrue refresh preserves the current app identity", activeIdentity, sessions.uid());
        } finally { probe.close(); }
    }

    private void openLoginAfterLocalClear(SessionManager sessions) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            sessions.clear(); // local clear only; do not revoke the cloud session
            Intent login = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(login);
        });
    }

    private static final class BroadcastProbe extends WebSocketListener {
        final CountDownLatch joined = new CountDownLatch(1), event = new CountDownLatch(1);
        final AtomicBoolean allowed = new AtomicBoolean(), validPayload = new AtomicBoolean();
        private final String token;
        private final String user;
        private WebSocket socket;
        BroadcastProbe(String token, String user) { this.token = token; this.user = user; }
        void open() {
            String url = SupabaseConfig.URL.replaceFirst("^http", "ws") + "/realtime/v1/websocket?apikey=" + SupabaseConfig.KEY + "&vsn=1.0.0";
            socket = SupabaseHttpClient.CLIENT.newWebSocket(new Request.Builder().url(url).build(), this);
        }
        @Override public void onOpen(WebSocket ws, Response response) {
            socket = ws;
            ws.send(Api.obj("topic", "realtime:user:" + user, "event", "phx_join", "ref", "1", "join_ref", "1",
                    "payload", Api.obj("config", Api.obj("private", true, "broadcast", Api.obj("ack", true, "self", false),
                            "presence", Api.obj("enabled", false), "postgres_changes", new JSONArray()), "access_token", token)).toString());
        }
        @Override public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject frame = new JSONObject(text);
                if ("phx_reply".equals(frame.optString("event")) && "1".equals(frame.optString("ref"))) {
                    allowed.set("ok".equals(frame.getJSONObject("payload").optString("status"))); joined.countDown();
                }
                if ("broadcast".equals(frame.optString("event"))) {
                    JSONObject payload = frame.getJSONObject("payload").getJSONObject("payload");
                    // Realtime adds its transport UUID `id`; the application fields remain event_id/scope only.
                    validPayload.set("messenger".equals(payload.optString("scope")) &&
                            payload.optString("event_id").matches("[0-9a-fA-F-]{36}") &&
                            payload.optString("id").matches("[0-9a-fA-F-]{36}") && payload.length() == 3);
                    event.countDown();
                }
            } catch (Exception ignored) { }
        }
        @Override public void onFailure(WebSocket ws, Throwable t, Response response) { joined.countDown(); event.countDown(); }
        void close() { if (socket != null) socket.cancel(); }
    }
}
