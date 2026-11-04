package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.SupabaseAuthClient;

import org.json.JSONObject;

public class ForgotPasswordActivity extends AppCompatActivity {
    private EditText field;
    private Button submitButton;
    private Subscription request;
    private boolean reset;
    private boolean submitting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        reset = getIntent().getBooleanExtra("reset", false);
        LinearLayout form = Forms.start(this, reset ? "Đặt lại mật khẩu" : "Quên mật khẩu");
        field = Forms.field(form, reset ? "Mật khẩu mới" : "Email", "",
                reset
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        submitButton = Forms.button(form,
                reset ? "Lưu mật khẩu mới" : "Gửi email khôi phục", this::submit);
        Forms.button(form, "Quay lại", this::finish);

        if (!reset) {
            Forms.status(this, "Nhập email tài khoản. Email chỉ được gửi nếu tài khoản tồn tại và dịch vụ email Supabase cho phép.");
        }
    }

    private void submit() {
        if (submitting) return;
        String value = field.getText().toString().trim();
        if (reset) {
            if (value.length() < 8) {
                field.setError("Mật khẩu cần có ít nhất 8 ký tự");
                field.requestFocus();
                return;
            }
        } else if (!Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
            field.setError("Nhập địa chỉ email hợp lệ");
            field.requestFocus();
            return;
        }

        field.setError(null);
        Forms.status(this, "");
        setSubmitting(true);
        Api.Callback<JSONObject> callback = new Api.Callback<JSONObject>() {
            @Override
            public void success(JSONObject result) {
                setSubmitting(false);
                if (reset) {
                    Forms.status(ForgotPasswordActivity.this, "Đã cập nhật mật khẩu. Vui lòng đăng nhập lại.");
                    SessionManager.getInstance().signOut();
                    Intent login = new Intent(ForgotPasswordActivity.this, LoginActivity.class);
                    login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(login);
                    finish();
                } else {
                    Forms.status(ForgotPasswordActivity.this,
                            "Nếu email thuộc một tài khoản, Supabase sẽ gửi liên kết khi dịch vụ email cho phép. Nếu chưa nhận được, hãy thử lại sau.");
                }
            }

            @Override
            public void error(String message) {
                setSubmitting(false);
                Forms.status(ForgotPasswordActivity.this, message);
            }
        };
        request = reset
                ? SupabaseAuthClient.password(value, callback)
                : SupabaseAuthClient.recover(value, callback);
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        submitButton.setEnabled(!value);
        field.setEnabled(!value);
        submitButton.setText(value
                ? (reset ? "Đang cập nhật…" : "Đang gửi…")
                : (reset ? "Lưu mật khẩu mới" : "Gửi email khôi phục"));
    }

    @Override
    protected void onDestroy() {
        if (request != null) request.remove();
        super.onDestroy();
    }
}
