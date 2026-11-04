package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.util.Patterns;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.meetomxh.databinding.ActivityLoginBinding;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.SupabaseAuthClient;

import org.json.JSONObject;

public class LoginActivity extends AppCompatActivity {
    private ActivityLoginBinding binding;
    private Subscription request;
    private boolean submitting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.etEmail.setHint("Email");
        binding.btnLogin.setOnClickListener(v -> submitLogin());
        binding.etPassword.setImeOptions(EditorInfo.IME_ACTION_DONE);
        binding.etPassword.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitLogin();
                return true;
            }
            return false;
        });
        binding.btnRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        binding.tvForgotPassword.setOnClickListener(v ->
                startActivity(new Intent(this, ForgotPasswordActivity.class)));

        if (SessionManager.getInstance().getCurrentUser() != null) {
            openRoleResolution();
        }
    }

    private void submitLogin() {
        if (submitting) return;

        String email = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString();
        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.setError("Nhập địa chỉ email hợp lệ");
            binding.etEmail.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            binding.etPassword.setError("Nhập mật khẩu");
            binding.etPassword.requestFocus();
            return;
        }

        binding.etEmail.setError(null);
        binding.etPassword.setError(null);
        setSubmitting(true);
        request = SupabaseAuthClient.login(email, password, profileCallback());
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        binding.btnLogin.setEnabled(!value);
        binding.etEmail.setEnabled(!value);
        binding.etPassword.setEnabled(!value);
        binding.btnLogin.setText(value ? "Đang đăng nhập…" : "Đăng nhập");
    }

    @Override
    protected void onDestroy() {
        if (request != null) request.remove();
        super.onDestroy();
    }

    private Api.Callback<JSONObject> profileCallback() {
        return new Api.Callback<JSONObject>() {
            @Override
            public void success(JSONObject profile) {
                setSubmitting(false);
                if (!profile.optString("account_state", "active").equals("active")) {
                    Toast.makeText(LoginActivity.this, "Tài khoản hiện không khả dụng", Toast.LENGTH_LONG).show();
                    SessionManager.getInstance().clear();
                    return;
                }
                Class<?> next = RoleResolutionActivity.class;
                Intent intent = new Intent(LoginActivity.this, next);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            }

            @Override
            public void error(String message) {
                setSubmitting(false);
                if (SessionManager.getInstance().getCurrentUser() != null) {
                    openRoleResolution();
                    return;
                }
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
            }
        };
    }

    private void openRoleResolution() {
        startActivity(new Intent(this, RoleResolutionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
