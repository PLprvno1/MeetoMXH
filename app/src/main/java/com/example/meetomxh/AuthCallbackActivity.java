package com.example.meetomxh;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.*;
import org.json.JSONObject;

/** Visible confirmation result for the app's PKCE and server-issued email-token flows. */
public final class AuthCallbackActivity extends AppCompatActivity {
    private Subscription request;
    private Button next;
    private long callbackGeneration;
    private boolean recovery;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout form = Forms.start(this, "Xác minh email");
        next = Forms.button(form, "Quay lại đăng nhập", () ->
                startActivity(new Intent(this, LoginActivity.class)));
        Forms.status(this, "Đang xác minh liên kết…");

        SessionManager sessions = SessionManager.getInstance();
        callbackGeneration = sessions.generation();
        recovery = "recovery".equals(sessions.pending("auth_flow_type"));
        Uri uri = getIntent().getData();
        if (!Intent.ACTION_VIEW.equals(getIntent().getAction()) || uri == null
                || !"meeto".equals(uri.getScheme()) || !"auth".equals(uri.getHost())
                || !"/callback".equals(uri.getPath())) {
            fail("Liên kết không hợp lệ.");
            return;
        }
        if (uri.getQueryParameter("error") != null) {
            fail("Liên kết đã hết hạn hoặc đã được sử dụng. Đăng nhập hoặc yêu cầu gửi lại email xác minh.");
            return;
        }

        String code = uri.getQueryParameter("code");
        String hash = uri.getQueryParameter("token_hash");
        String type = uri.getQueryParameter("type");
        if ((code != null && hash != null) || (code == null && hash == null)) {
            fail("Liên kết thiếu hoặc có thông tin xác minh không nhất quán.");
            return;
        }

        next.setEnabled(false);
        request = Api.run(() -> {
            String flow = sessions.authCallbackFlow(callbackGeneration);
            if (code != null) return SupabaseAuthClient.exchange(code, callbackGeneration);

            String expectedFlow;
            if ("recovery".equals(type) && "recovery".equals(flow)) expectedFlow = "recovery";
            else if (("signup".equals(type) || "email".equals(type)) && "signup".equals(flow)) expectedFlow = "signup";
            else throw new IllegalStateException("Liên kết không khớp với yêu cầu xác minh đang chờ.");
            JSONObject result = SupabaseAuthClient.verify(hash, type, callbackGeneration, expectedFlow);
            RpcClient.object("bootstrap_profile", Api.obj());
            return result;
        }, new Api.Callback<JSONObject>() {
            public void success(JSONObject result) {
                if (isFinishing() || isDestroyed()) return;
                if (recovery) {
                    Forms.status(AuthCallbackActivity.this, "Đã xác minh yêu cầu khôi phục. Tiếp tục đặt mật khẩu mới.");
                    next.setEnabled(true);
                    next.setText("Đặt mật khẩu mới");
                    next.setOnClickListener(view -> {
                        startActivity(new Intent(AuthCallbackActivity.this, ForgotPasswordActivity.class).putExtra("reset", true));
                        finish();
                    });
                    return;
                }
                Forms.status(AuthCallbackActivity.this, "Đã xác minh email. Đang lưu thông tin bạn đã nhập khi đăng ký…");
                Api.run(OnboardingFlow::completeProfileFromPending, new Api.Callback<JSONObject>() {
                    public void success(JSONObject profile) {
                        if (isFinishing() || isDestroyed()) return;
                        String email = result.optJSONObject("user") != null
                                ? result.optJSONObject("user").optString("email")
                                : SessionManager.getInstance().getCurrentUser() == null ? ""
                                : SessionManager.getInstance().getCurrentUser().getEmail();
                        startActivity(new Intent(AuthCallbackActivity.this, EmailConfirmationActivity.class)
                                .putExtra(EmailConfirmationActivity.EXTRA_EMAIL, email)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                        finish();
                    }
                    public void error(String error) { fail(error); }
                });
            }
            public void error(String error) { fail(error); }
        });
    }

    private void fail(String message) {
        Forms.status(this, message);
        next.setEnabled(true);
    }

    @Override protected void onDestroy() {
        if (request != null) request.remove();
        super.onDestroy();
    }
}
