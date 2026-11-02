package com.example.meetomxh;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.meetomxh.supabase.ErrorMapper;
import com.example.meetomxh.supabase.SupabaseConfig;
import com.example.meetomxh.supabase.SupabaseHttpClient;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class CloudConnectivityTest {
    @Test
    public void cloudHealthReachableAndAnonymousProfileRpcDenied() throws Exception {
        SupabaseConfig.check();
        assertEquals("qjpohzpapiwdrxsionyr.supabase.co", android.net.Uri.parse(SupabaseConfig.URL).getHost());

        // Exercise the production Android HTTP client with the client key only.
        SupabaseHttpClient.raw("GET", "/auth/v1/health", null, null);

        try {
            SupabaseHttpClient.raw("POST", "/rest/v1/rpc/get_my_profile", new org.json.JSONObject(), null);
            fail("Anonymous RPC unexpectedly succeeded");
        } catch (ErrorMapper.HttpError error) {
            assertTrue("Expected Cloud Auth denial, got HTTP " + error.status,
                    error.status == 401 || error.status == 403);
        }
    }

    private boolean anonymousJoinAllowed(String topic, boolean privateChannel) throws Exception {
        CountDownLatch result = new CountDownLatch(1);
        boolean[] allowed = {false};
        boolean[] transportFailure = {false};
        String wsUrl = SupabaseConfig.URL.replaceFirst("^http", "ws")
                + "/realtime/v1/websocket?apikey=" + SupabaseConfig.KEY + "&vsn=1.0.0";
        WebSocket socket = SupabaseHttpClient.CLIENT.newWebSocket(
                new Request.Builder().url(wsUrl).build(), new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket webSocket, Response response) {
                        try {
                            webSocket.send(new org.json.JSONObject()
                                    .put("topic", "realtime:" + topic)
                                    .put("event", "phx_join")
                                    .put("ref", "1")
                                    .put("join_ref", "1")
                                    .put("payload", new org.json.JSONObject()
                                            .put("config", new org.json.JSONObject()
                                                    .put("private", privateChannel)
                                                    .put("broadcast", new org.json.JSONObject()
                                                            .put("ack", true).put("self", false))
                                                    .put("presence", new org.json.JSONObject().put("enabled", false))
                                                    .put("postgres_changes", new org.json.JSONArray())))
                                    .toString());
                        } catch (org.json.JSONException error) {
                            transportFailure[0] = true;
                            result.countDown();
                        }
                    }

                    @Override
                    public void onMessage(WebSocket webSocket, String text) {
                        try {
                            org.json.JSONObject frame = new org.json.JSONObject(text);
                            String event = frame.optString("event");
                            if ("phx_reply".equals(event) && "1".equals(frame.optString("ref"))) {
                                allowed[0] = "ok".equals(frame.getJSONObject("payload").optString("status"));
                                result.countDown();
                            } else if ("phx_error".equals(event) || "phx_close".equals(event)) {
                                result.countDown();
                            }
                        } catch (org.json.JSONException ignored) {
                        }
                    }

                    @Override
                    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                        transportFailure[0] = true;
                        result.countDown();
                    }
                });
        try {
            assertTrue("Realtime join returned a response", result.await(20, TimeUnit.SECONDS));
            assertTrue("Realtime authorization response, not network failure", !transportFailure[0]);
            return allowed[0];
        } finally {
            socket.cancel();
        }
    }

    @Test
    public void anonymousCannotJoinPrivateOrPublicRealtimeTopics() throws Exception {
        assertTrue("Anonymous private topic must be denied",
                !anonymousJoinAllowed("user:" + UUID.randomUUID(), true));
        assertTrue("Public channel must be disabled",
                !anonymousJoinAllowed("task11-anonymous-probe", false));
    }
}
