package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SupabaseAuthClient;
import com.example.meetomxh.supabase.SupabaseHttpClient;
import org.json.JSONObject;

public final class EmailConfirmationActivity extends androidx.appcompat.app.AppCompatActivity {
    public static final String EXTRA_EMAIL = "email";
    private String email;
    private android.widget.Button resendButton;
    private android.widget.Button confirmButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        email = getIntent().getStringExtra(EXTRA_EMAIL);
        if (email == null) email = "";
        LinearLayout form = Forms.start(this, "Xác nhận email");
        ImageView back = findViewById(com.example.meetomxh.R.id.btnFormBack);
        if (back != null) back.setVisibility(View.GONE);
        Forms.status(this, "Hãy xác nhận qua Gmail của bạn. Mở email từ Meeto và nhấn vào liên kết xác minh, sau đó quay lại đây.");
        resendButton = Forms.button(form, "Gửi lại email", this::resend);
        confirmButton = Forms.button(form, "Xác nhận thành công", this::confirm);
    }

    private void resend() {
        resendButton.setEnabled(false);
        SupabaseAuthClient.resend(email, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject result) {
                resendButton.setEnabled(true);
                Forms.status(EmailConfirmationActivity.this, "Nếu tài khoản cần xác minh, email mới đã được gửi. Kiểm tra hộp thư đến và thư rác.");
            }
            @Override public void error(String message) {
                resendButton.setEnabled(true);
                Forms.status(EmailConfirmationActivity.this, message);
            }
        });
    }

    private void confirm() {
        confirmButton.setEnabled(false);
        Api.run(() -> {
            SessionManager sessions = SessionManager.getInstance();
            if (sessions.getCurrentUser() == null || !email.equalsIgnoreCase(sessions.getCurrentUser().getEmail())) {
                throw new IllegalStateException("Chưa nhận được xác nhận từ Gmail. Mở liên kết xác minh rồi thử lại.");
            }
            JSONObject user = (JSONObject) SupabaseHttpClient.raw("GET", "/auth/v1/user", null, sessions.access(false));
            if (user.optString("email_confirmed_at").isEmpty() && user.optString("confirmed_at").isEmpty()) {
                throw new IllegalStateException("Email chưa được xác minh. Mở liên kết trong Gmail rồi thử lại.");
            }
            return user;
        }, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject user) {
                SessionManager.getInstance().clear();
                startActivity(new Intent(EmailConfirmationActivity.this, LoginActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            }
            @Override public void error(String message) {
                confirmButton.setEnabled(true);
                Forms.status(EmailConfirmationActivity.this, message);
            }
        });
    }
}
