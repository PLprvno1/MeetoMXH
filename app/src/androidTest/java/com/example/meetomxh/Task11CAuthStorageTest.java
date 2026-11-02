package com.example.meetomxh;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.supabase.*;
import okhttp3.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.Espresso.setFailureHandler;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class Task11CAuthStorageTest {
    private final SupabaseCutoverTest ui = new SupabaseCutoverTest();
    private JSONObject credentials;
    private final JSONArray checks = new JSONArray();
    private final JSONArray storageResponses = new JSONArray();
    private boolean passed;

    @Test public void authFailureBackGuardsAndPendingStorageIsolation() throws Exception {
        setFailureHandler((error, matcher) -> { throw new AssertionError("UI action failed: " + error.getClass().getSimpleName()); });
        try {
            try (ServerSocket server = new ServerSocket()) {
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 38711));
                server.setSoTimeout(120000);
                android.os.Bundle ready = new android.os.Bundle();
                ready.putString("stream", "Task11C: Credential receiver ready\n");
                InstrumentationRegistry.getInstrumentation().sendStatus(0, ready);
                try (Socket client = server.accept()) {
                    client.setSoTimeout(10000);
                    credentials = new JSONObject(new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.UTF_8)).readLine());
                }
            }
            logout();
            // A protected Activity must route an anonymous identity back to Login.
            launch(MainActivity.class);
            ui.screen(LoginActivity.class);
            assertTrue(SessionManager.getInstance().uid().isEmpty());
            checks.put("anonymous protected activity routes to login");
            onView(withId(R.id.etEmail)).perform(replaceText(credentials.getJSONArray("emails").getString(0)), closeSoftKeyboard());
            onView(withId(R.id.etPassword)).perform(replaceText("invalid-test-" + UUID.randomUUID()), closeSoftKeyboard());
            onView(withId(R.id.btnLogin)).perform(click());
            ui.waitFor("invalid login releases loading state", () -> {
                try { onView(withId(R.id.btnLogin)).check(matches(isEnabled())); return true; }
                catch (AssertionError pending) { return false; }
            });
            assertTrue(SessionManager.getInstance().uid().isEmpty());
            assertTrue(ui.activity() instanceof LoginActivity);
            checks.put("wrong password remains unauthenticated; retry button recovers");
            pressBackUnconditionally();
            launch(LoginActivity.class);
            ui.screen(LoginActivity.class);
            assertTrue(SessionManager.getInstance().uid().isEmpty());
            login(0);
            assertEquals("95f6e952-c14b-48dd-9478-af2ac68841de", SessionManager.getInstance().uid());
            String owner = SessionManager.getInstance().uid();
            JSONObject intent = RpcClient.object("prepare_upload", Api.obj("p_purpose", "avatar",
                    "p_mime", "image/png", "p_client_request_id", UUID.randomUUID()));
            String assetId = intent.getString("id");
            String path = intent.getString("bucket_id") + "/" + intent.getString("object_path");
            assertEquals("profile-media", intent.getString("bucket_id"));
            assertTrue(intent.getString("object_path").startsWith(owner + "/"));
            byte[] bytes;
            try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                Bitmap image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
                image.eraseColor(0xff3355bb);
                image.compress(Bitmap.CompressFormat.PNG, 100, buffer);
                image.recycle();
                bytes = buffer.toByteArray();
            }
            requireDenied(storage("POST", path, bytes, null));
            login(1);
            assertEquals("32fde22e-5e5e-4e47-b2f6-ef09518ba1b9", SessionManager.getInstance().uid());
            requireDenied(storage("POST", path, bytes, SessionManager.getInstance().access(false)));
            try { RpcClient.call("finalize_upload", Api.obj("p_asset_id", assetId)); fail("wrong user finalize allowed"); }
            catch (ErrorMapper.HttpError error) { requireDenied(error.status); }
            new ClientSecurityTest().rejectsSpoofedActorAndUnauthorizedRealtime();
            checks.put("JWT B: own topic allow; wrong/public/publish deny; spoof/DML denial");
            login(0);
            int uploaded = storage("POST", path, bytes, SessionManager.getInstance().access(false));
            assertTrue("owner standard upload accepted into pending intent", uploaded == 200 || uploaded == 201);
            assertEquals("pending-owner preview is allowed for the original 15-minute intent contract", 200,
                    storage("GET", "authenticated/" + path, null, SessionManager.getInstance().access(false)));
            JSONArray registry = (JSONArray) SupabaseHttpClient.authorized("GET",
                    "/rest/v1/media_assets?id=eq." + assetId + "&select=id,state", null);
            assertEquals(1, registry.length());
            assertEquals("pending", registry.getJSONObject(0).getString("state"));
            requireDenied(storage("GET", "public/" + path, null, null));
            try { RpcClient.call("finalize_upload", Api.obj("p_asset_id", assetId)); fail("asset ready without verifier"); }
            catch (ErrorMapper.HttpError error) { assertEquals(409, error.status); }
            checks.put("one 2x2 PNG owner upload/preview follows pending intent contract; state pending; public deny; finalize409");
            login(1);
            requireDenied(storage("GET", "authenticated/" + path, null, SessionManager.getInstance().access(false)));
            checks.put("wrong-user upload/finalize/download denied for A pending fixture");
            login(0);
            assertEquals(owner, SessionManager.getInstance().uid());
            checks.put("UI returns to A after repeated logout/login; no extra accounts");
            passed = true;
        } finally {
            credentials = null;
            File report = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getExternalFilesDir(null), "task11c-auth-storage-results.json");
            try (FileOutputStream out = new FileOutputStream(report)) {
                out.write(Api.obj("passed", passed, "completedChecks", checks, "storageResponses", storageResponses,
                        "mediaReady", false).toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void launch(Class<?> type) {
        if (type.isInstance(ui.activity())) return;
        InstrumentationRegistry.getInstrumentation().getTargetContext().startActivity(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void logout() throws Exception {
        if (SessionManager.getInstance().uid().isEmpty()) { launch(LoginActivity.class); return; }
        launch(MainActivity.class);
        onView(withId(R.id.nav_profile)).perform(click());
        onView(withId(R.id.btnLogout)).perform(scrollTo());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.btnLogout)).perform(click());
        ui.screen(LoginActivity.class);
        assertTrue(SessionManager.getInstance().uid().isEmpty());
    }

    private void login(int account) throws Exception {
        logout();
        onView(withId(R.id.etEmail)).perform(replaceText(credentials.getJSONArray("emails").getString(account)), closeSoftKeyboard());
        onView(withId(R.id.etPassword)).perform(replaceText(credentials.getString("password")), closeSoftKeyboard());
        onView(withId(R.id.btnLogin)).perform(click());
        ui.screen(MainActivity.class);
    }

    private int storage(String method, String path, byte[] bytes, String token) throws Exception {
        Request.Builder request = new Request.Builder().url(SupabaseConfig.URL + "/storage/v1/object/" + path)
                .header("apikey", SupabaseConfig.KEY).header("cache-control", "0");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("POST".equals(method)) request.header("x-upsert", "false")
                .post(RequestBody.create(bytes, MediaType.get("image/png")));
        else request.get();
        try (Response response = SupabaseHttpClient.CLIENT.newCall(request.build()).execute()) {
            int logicalStatus = response.code();
            if (response.code() == 400 && response.body() != null) {
                JSONObject envelope = new JSONObject(response.body().string());
                logicalStatus = envelope.optInt("statusCode", 400);
            }
            storageResponses.put(Api.obj("http", response.code(), "storageStatusCode", logicalStatus));
            return logicalStatus;
        }
    }

    private void requireDenied(int status) {
        assertTrue("explicit authorization denial HTTP " + status,
                status == 401 || status == 403 || status == 404);
    }
}
