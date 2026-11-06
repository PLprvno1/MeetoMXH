package com.example.meetomxh;

import android.content.Context;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import org.json.JSONObject;

/** Persists registration details once and tracks the optional first-login social setup locally. */
public final class OnboardingFlow {
    private static final String PREFS = "meeto_onboarding";
    private OnboardingFlow() { }

    public static JSONObject completeProfileFromPending() throws Exception {
        JSONObject profile = ProfileClient.self();
        if (profile.optBoolean("onboarding_completed")) return profile;
        SessionManager session = SessionManager.getInstance();
        String name = session.pending("signup_name").trim();
        String birthDate = session.pending("signup_dob").trim();
        String gender = session.pending("signup_gender").trim();
        String location = session.pending("signup_location").trim();
        if (name.isEmpty() || birthDate.isEmpty()) {
            throw new IllegalStateException("Không tìm thấy thông tin hồ sơ đã nhập khi đăng ký. Hãy liên hệ hỗ trợ để hoàn tất tài khoản.");
        }
        JSONObject completed = RpcClient.object("complete_onboarding_with_location", Api.obj(
                "p_display_name", name,
                "p_birth_date", birthDate,
                "p_gender", gender.isEmpty() ? null : gender,
                "p_location", location.isEmpty() ? null : location,
                "p_location_audience", "private",
                "p_expected_version", profile.getLong("version")));
        session.pending("signup_name", "");
        session.pending("signup_dob", "");
        session.pending("signup_gender", "");
        session.pending("signup_location", "");
        return completed;
    }

    public static boolean socialSetupDone(Context context, String userId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done_" + userId, false);
    }

    public static void markSocialSetupDone(Context context, String userId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done_" + userId, true).apply();
    }

    public static boolean socialFollowSelected(Context context, String userId) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("followed_" + userId, false);
    }

    public static void markSocialFollowSelected(Context context, String userId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("followed_" + userId, true).apply();
    }
}
