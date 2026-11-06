package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.meetomxh.supabase.AdminRoleResolver;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.SessionManager;
import com.google.android.material.button.MaterialButton;

import org.json.JSONObject;

/** Neutral gate used before either the social shell or the admin shell is created. */
public final class RoleResolutionActivity extends AppCompatActivity {
    public static final String EXTRA_ROLE_VERIFIED = "role_verified_by_server";
    private TextView message;
    private ProgressBar progress;
    private MaterialButton retry, signOut;
    private boolean checking;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
        resolve();
    }

    private void render() {
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getColor(com.example.meetomxh.R.color.app_bg));

        TextView brand = new TextView(this);
        brand.setText("Meeto");
        brand.setTextColor(getColor(com.example.meetomxh.R.color.brand_blue));
        brand.setTextSize(34);
        brand.setTypeface(null, android.graphics.Typeface.BOLD);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Xác minh quyền truy cập");
        title.setTextColor(getColor(com.example.meetomxh.R.color.text_primary));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = pad / 2;
        root.addView(title, titleLp);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-2, -2);
        progressLp.topMargin = pad;
        root.addView(progress, progressLp);

        message = new TextView(this);
        message.setText("Đang xác minh quyền truy cập…");
        message.setTextColor(getColor(com.example.meetomxh.R.color.text_secondary));
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(-1, -2);
        messageLp.topMargin = pad / 2;
        root.addView(message, messageLp);

        retry = new MaterialButton(this);
        retry.setText("Thử lại");
        retry.setOnClickListener(v -> resolve());
        retry.setVisibility(View.GONE);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, -2);
        buttonLp.topMargin = pad / 2;
        root.addView(retry, buttonLp);

        signOut = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        signOut.setText("Về màn hình đăng nhập");
        signOut.setOnClickListener(v -> {
            SessionManager.getInstance().signOut();
            route(LoginActivity.class, false);
        });
        signOut.setVisibility(View.GONE);
        root.addView(signOut, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void resolve() {
        if (checking || isFinishing()) return;
        if (SessionManager.getInstance().getCurrentUser() == null) {
            route(LoginActivity.class, false);
            return;
        }
        checking = true;
        progress.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        signOut.setVisibility(View.GONE);
        message.setText("Đang xác minh quyền truy cập…");
        Api.run(ProfileClient::self, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject profile) {
                if (isFinishing()) return;
                if (!"active".equals(profile.optString("account_state", "active"))) {
                    SessionManager.getInstance().signOut();
                    route(LoginActivity.class, false);
                    return;
                }
                if (!profile.optBoolean("onboarding_completed")) {
                    Api.run(OnboardingFlow::completeProfileFromPending, new Api.Callback<JSONObject>() {
                        @Override public void success(JSONObject completed) { routeOnboardingPhotos(); }
                        @Override public void error(String error) { showRetry(error); }
                    });
                    return;
                }
                String userId = SessionManager.getInstance().uid();
                if (!OnboardingFlow.socialSetupDone(RoleResolutionActivity.this, userId)) {
                    routeOnboardingPhotos();
                    return;
                }
                resolveRole();
            }

            @Override public void error(String error) {
                if(SessionManager.getInstance().getCurrentUser()==null) route(LoginActivity.class,false);
                else showRetry("Không thể tải hồ sơ để xác minh quyền.");
            }
        });
    }

    private void resolveRole() {
        Api.run(AdminRoleResolver::resolve, new Api.Callback<AdminRoleResolver.Result>() {
            @Override public void success(AdminRoleResolver.Result result) {
                if (isFinishing()) return;
                switch (result.destination) {
                    case ADMIN: route(AdminPanelActivity.class, true); break;
                    case SOCIAL: route(MainActivity.class, true); break;
                    case LOGIN: SessionManager.getInstance().signOut(); route(LoginActivity.class, false); break;
                    case RETRY: showRetry(result.message); break;
                }
            }
            @Override public void error(String error) { showRetry("Không thể xác minh quyền. Hãy thử lại."); }
        });
    }

    private void showRetry(String detail) {
        checking = false;
        progress.setVisibility(View.GONE);
        message.setText("Không thể xác minh quyền truy cập. " + (detail == null ? "Kiểm tra kết nối rồi thử lại." : detail));
        retry.setVisibility(View.VISIBLE);
        signOut.setVisibility(View.VISIBLE);
    }

    private void route(Class<?> destination, boolean verified) {
        Intent intent = new Intent(this, destination)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (verified) intent.putExtra(EXTRA_ROLE_VERIFIED, true);
        startActivity(intent);
        finish();
    }

    private void routeOnboardingPhotos() {
        Intent intent = new Intent(this, ProfileEditActivity.class)
                .putExtra(ProfileEditActivity.EXTRA_ONBOARDING_ASSETS_ONLY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
