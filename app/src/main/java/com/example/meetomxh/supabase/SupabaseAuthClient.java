package com.example.meetomxh.supabase;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class SupabaseAuthClient {
    private SupabaseAuthClient() { }

    public static void resend(String email, Api.Callback<JSONObject> callback) {
        Api.run(() -> (JSONObject) SupabaseHttpClient.raw("POST",
                "/auth/v1/resend?redirect_to=meeto%3A%2F%2Fauth%2Fcallback",
                Api.obj("type", "signup", "email", email.trim()), null), callback);
    }

    public static Subscription login(String email, String password, Api.Callback<JSONObject> callback) {
        if (SessionManager.getInstance().getCurrentUser() != null) SessionManager.getInstance().clear();
        return Api.run(() -> {
            JSONObject session = (JSONObject) SupabaseHttpClient.raw("POST",
                    "/auth/v1/token?grant_type=password",
                    Api.obj("email", email.trim(), "password", password), null);
            SessionManager.getInstance().accept(session);
            return RpcClient.object("bootstrap_profile", Api.obj());
        }, callback);
    }

    private static String challenge(String flow, String email) throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = android.util.Base64.encodeToString(random,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        SessionManager.getInstance().beginAuthFlow(flow, email, verifier);
        return android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    public static void signup(String email, String password, Api.Callback<JSONObject> callback) {
        final String challenge;
        try { challenge = challenge("signup", email); }
        catch (Exception error) { fail(callback, error); return; }
        Api.run(() -> (JSONObject) SupabaseHttpClient.raw("POST",
                "/auth/v1/signup?redirect_to=meeto%3A%2F%2Fauth%2Fcallback",
                Api.obj("email", email.trim(), "password", password,
                        "code_challenge", challenge, "code_challenge_method", "s256"), null), callback);
    }

    public static Subscription recover(String email, Api.Callback<JSONObject> callback) {
        final String challenge;
        try { challenge = challenge("recovery", email); }
        catch (Exception error) {
            fail(callback, error);
            return () -> { };
        }
        return Api.run(() -> (JSONObject) SupabaseHttpClient.raw("POST",
                "/auth/v1/recover?redirect_to=meeto%3A%2F%2Fauth%2Fcallback",
                Api.obj("email", email.trim(), "code_challenge", challenge,
                        "code_challenge_method", "s256"), null), callback);
    }

    public static JSONObject exchange(String code, long expectedGeneration) throws Exception {
        String flow = SessionManager.getInstance().authCallbackFlow(expectedGeneration);
        String verifier = SessionManager.getInstance().pending("pkce");
        if (code == null || code.isEmpty() || verifier.isEmpty())
            throw new java.io.IOException("Missing PKCE authorization code or verifier");
        JSONObject result = (JSONObject) SupabaseHttpClient.raw("POST",
                "/auth/v1/token?grant_type=pkce",
                Api.obj("auth_code", code, "code_verifier", verifier), null);
        SessionManager.getInstance().acceptAuthCallback(result, expectedGeneration, flow);
        RpcClient.object("bootstrap_profile", Api.obj());
        return result;
    }

    public static JSONObject verify(String hash, String type, long expectedGeneration,
                                     String expectedFlow) throws Exception {
        if (hash == null || hash.isEmpty()) throw new java.io.IOException("Missing email verification token");
        SessionManager sessions = SessionManager.getInstance();
        String flow = sessions.authCallbackFlow(expectedGeneration);
        if (!flow.equals(expectedFlow)) throw new java.io.IOException("Verification link does not match the pending flow");
        JSONObject result = (JSONObject) SupabaseHttpClient.raw("POST", "/auth/v1/verify",
                Api.obj("token_hash", hash, "type", type), null);
        sessions.acceptAuthCallback(result, expectedGeneration, expectedFlow);
        return result;
    }

    public static Subscription password(String newPassword, Api.Callback<JSONObject> callback) {
        return Api.run(() -> (JSONObject) SupabaseHttpClient.authorized("PUT", "/auth/v1/user",
                Api.obj("password", newPassword)), callback);
    }

    private static void fail(Api.Callback<JSONObject> callback, Exception error) {
        String message = ErrorMapper.message(error);
        Api.MAIN.post(() -> callback.error(message));
    }
}
