package com.example.meetomxh.supabase;

import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SessionManager {
    private static SessionManager instance;
    private final EncryptedVault vault;
    private JSONObject data;
    private volatile long generation;
    public final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<Runnable> identityListeners = new CopyOnWriteArrayList<>();

    private SessionManager(Context context) {
        vault = new EncryptedVault(context);
        data = vault.read();
    }

    public static synchronized void init(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext());
    }

    public static SessionManager getInstance() {
        if (instance == null) throw new IllegalStateException("App chưa khởi tạo");
        return instance;
    }

    public long generation() { return generation; }

    /** Runs a delivery while holding the same lock used to commit identity changes. */
    public synchronized boolean runIfCurrent(long expectedGeneration, Runnable action) {
        if (generation != expectedGeneration) return false;
        action.run();
        return true;
    }

    public synchronized SessionUser getCurrentUser() {
        String id = data.optString("uid");
        return id.isEmpty() ? null : new SessionUser(id, data.optString("email"));
    }

    public synchronized String uid() {
        SessionUser user = getCurrentUser();
        return user == null ? "" : user.getUid();
    }

    public synchronized void accept(JSONObject session) throws Exception {
        Api.checkCancelled();
        acceptLocked(session, false);
    }

    /** Validates the app's pending email-auth flow and accepts its server-issued session atomically. */
    public synchronized void acceptAuthCallback(JSONObject session, long expectedGeneration,
                                                String expectedFlow) throws Exception {
        if (generation != expectedGeneration) throw new IOException("Authentication callback is stale");
        String flow = data.optString("auth_flow_type");
        String flowUid = data.optString("auth_flow_uid");
        String currentUid = uid();
        if (!flow.equals(expectedFlow) || !flowUid.equals(currentUid))
            throw new IOException("Authentication callback no longer matches the active sign-in flow");

        JSONObject user = session == null ? null : session.optJSONObject("user");
        String returnedEmail = user == null ? "" : user.optString("email").trim();
        String expectedEmail = data.optString("auth_flow_email").trim();
        if (expectedEmail.isEmpty() || returnedEmail.isEmpty()
                || !expectedEmail.equalsIgnoreCase(returnedEmail))
            throw new IOException("Authentication callback account does not match the requested email");

        Api.checkCancelled();
        acceptLocked(session, true);
    }

    /** Stores PKCE material and the identity that initiated a signup or recovery link. */
    public synchronized void beginAuthFlow(String type, String email, String verifier) throws Exception {
        if (!("signup".equals(type) || "recovery".equals(type)) || email == null
                || email.trim().isEmpty() || verifier == null || verifier.isEmpty())
            throw new IOException("Invalid authentication flow");
        JSONObject next = new JSONObject(data.toString());
        next.put("auth_flow_type", type);
        next.put("auth_flow_email", email.trim().toLowerCase(Locale.ROOT));
        next.put("auth_flow_uid", uid());
        next.put("pkce", verifier);
        next.put("recovery", "recovery".equals(type) ? "yes" : "no");
        vault.write(next);
        data = next;
    }

    public synchronized String authCallbackFlow(long expectedGeneration) throws Exception {
        if (generation != expectedGeneration) throw new IOException("Authentication callback is stale");
        String flow = data.optString("auth_flow_type");
        if (!("signup".equals(flow) || "recovery".equals(flow))
                || !data.optString("auth_flow_uid").equals(uid())
                || data.optString("auth_flow_email").trim().isEmpty())
            throw new IOException("No matching authentication flow is pending");
        return flow;
    }

    private void acceptLocked(JSONObject session, boolean clearAuthFlow) throws Exception {
        if (session == null) throw new IOException("Authentication session is missing");
        JSONObject user = session.optJSONObject("user");
        String id = user == null ? "" : user.optString("id").trim();
        String email = user == null ? "" : user.optString("email");
        String access = session.optString("access_token");
        String refresh = session.optString("refresh_token");
        if (id.isEmpty() || access.isEmpty() || refresh.isEmpty())
            throw new IOException("Authentication session is incomplete");

        String previousUid = data.optString("uid");
        boolean identityChanged = !previousUid.equals(id);
        long previousGeneration = generation;
        JSONObject next = new JSONObject(data.toString());
        next.put("access", access).put("refresh", refresh).put("uid", id).put("email", email)
                .put("exp", session.optLong("expires_at",
                        System.currentTimeMillis() / 1000 + session.optLong("expires_in", 3600)));

        // A successful signup may return a session before the email link is opened.
        // Bind that pending flow only to the same address returned by Supabase.
        if ("signup".equals(next.optString("auth_flow_type"))
                && next.optString("auth_flow_email").equalsIgnoreCase(email.trim()))
            next.put("auth_flow_uid", id);
        if (clearAuthFlow) {
            next.remove("auth_flow_type");
            next.remove("auth_flow_email");
            next.remove("auth_flow_uid");
            next.remove("pkce");
            next.remove("recovery");
        }

        // Persist the complete next state before publishing it to other threads.
        vault.write(next);
        data = next;
        if (identityChanged) {
            generation++;
            Api.sessionAccepted(previousGeneration, generation);
            invalidateIdentityState();
        } else if (clearAuthFlow) {
            for (Runnable listener : listeners) Api.MAIN.post(listener);
        }
    }

    private void invalidateIdentityState() {
        SupabaseHttpClient.CLIENT.dispatcher().cancelAll();
        Api.MAIN.post(() -> {
            RealtimeClient.get().stop();
            com.bumptech.glide.Glide.get(com.example.meetomxh.MeetoApplication.get()).clearMemory();
            for (Runnable listener : identityListeners) listener.run();
            for (Runnable listener : listeners) listener.run();
        });
        Api.IO.execute(() -> com.bumptech.glide.Glide.get(com.example.meetomxh.MeetoApplication.get()).clearDiskCache());
    }

    public synchronized String access(boolean force) throws Exception {
        if (getCurrentUser() == null) throw new ErrorMapper.HttpError(401, "no_session");
        if (force || System.currentTimeMillis() / 1000 + 45 >= data.optLong("exp")) {
            try {
                JSONObject fresh = (JSONObject) SupabaseHttpClient.raw("POST",
                        "/auth/v1/token?grant_type=refresh_token",
                        Api.obj("refresh_token", data.getString("refresh")), null);
                accept(fresh);
                for (Runnable listener : listeners) Api.MAIN.post(listener);
            } catch (ErrorMapper.HttpError error) {
                if (error.status == 400 || error.status == 401) clear();
                throw error;
            }
        }
        return data.getString("access");
    }

    public synchronized String afterRejected(String rejected) throws Exception {
        return data.optString("access").equals(rejected) ? access(true) : access(false);
    }

    public synchronized void clear() {
        data = new JSONObject();
        generation++;
        try { vault.write(data); } catch (Exception ignored) { }
        SupabaseHttpClient.CLIENT.dispatcher().cancelAll();
        Api.MAIN.post(() -> {
            RealtimeClient.get().stop();
            com.bumptech.glide.Glide.get(com.example.meetomxh.MeetoApplication.get()).clearMemory();
            for (Runnable listener : identityListeners) listener.run();
            for (Runnable listener : listeners) listener.run();
        });
        Api.IO.execute(() -> com.bumptech.glide.Glide.get(com.example.meetomxh.MeetoApplication.get()).clearDiskCache());
    }

    public void signOut() {
        String token;
        synchronized (this) { token = data.optString("access", null); }
        clear();
        if (token != null) Api.IO.execute(() -> {
            try { SupabaseHttpClient.raw("POST", "/auth/v1/logout", Api.obj(), token); }
            catch (Exception ignored) { }
        });
    }

    public synchronized void pending(String key, String value) throws Exception {
        data.put(key, value);
        vault.write(data);
    }

    public synchronized String pending(String key) { return data.optString(key); }
}
