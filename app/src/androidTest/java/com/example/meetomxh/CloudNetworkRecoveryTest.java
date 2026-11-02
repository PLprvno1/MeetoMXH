package com.example.meetomxh;

import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ErrorMapper;
import com.example.meetomxh.supabase.MessageOutbox;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RealtimeClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.SupabaseHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CloudNetworkRecoveryTest {
    private static final String USER_A = "95f6e952-c14b-48dd-9478-af2ac68841de";
    private static final String USER_B = "32fde22e-5e5e-4e47-b2f6-ef09518ba1b9";

    private void await(String label, long timeoutSeconds, Condition condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < until) {
            if (condition.ok()) return;
            Thread.sleep(500);
        }
        Assert.fail("Timed out: " + label);
    }

    private interface Condition { boolean ok() throws Exception; }

    private String shell(String command) throws Exception {
        ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test public void cloudRpcOutboxAndPrivateRealtimeRecoverAfterActualNetworkLoss() throws Exception {
        SessionManager session = SessionManager.getInstance();
        String actor = session.uid();
        Assert.assertTrue("signed-in synthetic test user", USER_A.equals(actor) || USER_B.equals(actor));
        Assert.assertTrue("Cloud profile RPC initially succeeds", ProfileClient.self().optBoolean("onboarding_completed"));
        String peer = USER_A.equals(actor) ? USER_B : USER_A;
        String conversation = com.example.meetomxh.supabase.RpcClient.object("get_or_create_private",
                Api.obj("p_peer", peer)).getString("id");

        RealtimeClient realtime = RealtimeClient.get();
        Subscription watch = realtime.watch(() -> { });
        try {
        JSONObject input = Api.obj("p_conversation_id", conversation,
                "p_client_request_id", UUID.randomUUID(), "p_kind", "text",
                "p_body", "Task11 network recovery " + UUID.randomUUID(),
                "p_assets", new JSONArray(), "p_reply_to", null);
        int pendingBefore = MessageOutbox.forConversation(conversation).length();
        MessageOutbox.put(input);
        int expectedPending = pendingBefore + 1;
        await("private Realtime channel joined before outage", 30, () -> realtime.reconnections > 0);
        long joinedBefore = realtime.reconnections;

        boolean networkWasDisabled = false;
        try {
            networkWasDisabled = true;
            shell("cmd connectivity set-chain3-enabled true");
            String disabled = shell("cmd connectivity set-package-networking-enabled false com.example.meetomxh");
            disabled += shell("cmd connectivity set-package-networking-enabled false com.example.meetomxh.test");
            Assert.assertTrue("emulator firewall blocks app and instrumentation UIDs: " + disabled.trim(), disabled.contains("Disabled networking for"));
            Assert.assertTrue("firewall deny bit is active", shell("cmd connectivity get-package-networking-enabled com.example.meetomxh").contains(":deny"));
            SupabaseHttpClient.CLIENT.dispatcher().cancelAll();
            SupabaseHttpClient.CLIENT.connectionPool().evictAll();
            Thread.sleep(1000);
            Thread.sleep(1500);
            boolean rpcFailed = false;
            try { ProfileClient.self(); }
            catch (java.io.IOException expected) { rpcFailed = true; }
            Assert.assertTrue("Cloud RPC fails during emulator network outage", rpcFailed);
            boolean retryFailed = false;
            try { MessageOutbox.retry(conversation); }
            catch (java.io.IOException expected) { retryFailed = true; }
            Assert.assertTrue("message retry reports offline failure", retryFailed);
            Assert.assertEquals("encrypted outbox retains uncommitted retries", expectedPending,
                    MessageOutbox.forConversation(conversation).length());
        } finally {
            if (networkWasDisabled) restoreNetwork();
        }

        await("Cloud RPC recovers after network restore", 90,
                () -> { try { return ProfileClient.self().optBoolean("onboarding_completed"); }
                        catch (java.io.IOException transientNetworkError) { return false; } });
        await("private WebSocket rejoins after network restore", 120,
                () -> realtime.reconnections > joinedBefore);
        Assert.assertEquals("pending messages commit after reconnect", expectedPending,
                MessageOutbox.retry(conversation));
        Assert.assertEquals("outbox clears only after server acknowledgement", 0,
                MessageOutbox.forConversation(conversation).length());
        JSONArray search = ((JSONObject) SupabaseHttpClient.authorized("POST", "/rest/v1/rpc/search_messages",
                Api.obj("p_conversation_id", conversation, "p_query", input.getString("p_body")))).getJSONArray("items");
        Assert.assertEquals("one durable message after retry", 1, search.length());
        } finally {
            watch.remove();
            restoreNetwork();
        }
    }

    private void restoreNetwork() throws Exception {
        shell("cmd connectivity set-package-networking-enabled true com.example.meetomxh");
        shell("cmd connectivity set-package-networking-enabled true com.example.meetomxh.test");
        shell("cmd connectivity set-chain3-enabled false");
    }
}
