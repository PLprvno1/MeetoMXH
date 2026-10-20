package com.example.meetomxh;

import android.content.Context;
import android.os.Bundle;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;

import androidx.appcompat.app.AppCompatActivity;

import com.example.meetomxh.databinding.ActivityForgotPasswordBinding;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;

public class ForgotPasswordActivity extends AppCompatActivity {

    private static final String SUCCESS_MESSAGE =
            "Nếu email này được đăng ký và có thể nhận thư, bạn sẽ nhận được hướng dẫn đặt lại mật khẩu. "
                    + "Hãy kiểm tra hộp thư đến, thư rác và các thư mục lọc khác.";

    private ActivityForgotPasswordBinding binding;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityForgotPasswordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        auth = FirebaseAuth.getInstance();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSendResetLink.setOnClickListener(v -> sendResetLink());
        binding.etEmail.setOnEditorActionListener((view, actionId, event) -> {
            if (!binding.btnSendResetLink.isEnabled()) {
                return true;
            }
            boolean submitAction = actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (submitAction) {
                sendResetLink();
                return true;
            }
            return false;
        });
        binding.etEmail.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.etEmail.setError(null);
                binding.tvResult.setText("");
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
    }

    private void sendResetLink() {
        String email = binding.etEmail.getText() != null
                ? binding.etEmail.getText().toString().trim()
                : "";

        if (email.isEmpty()) {
            binding.etEmail.setError("Vui lòng nhập email đã đăng ký");
            binding.etEmail.requestFocus();
            return;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.setError("Vui lòng nhập email đúng định dạng");
            binding.etEmail.requestFocus();
            return;
        }

        hideKeyboard();
        setLoading(true);

        auth.sendPasswordResetEmail(email).addOnCompleteListener(this, task -> {
            if (isFinishing() || isDestroyed() || binding == null) {
                return;
            }

            setLoading(false);
            if (task.isSuccessful()) {
                binding.tvResult.setText(SUCCESS_MESSAGE);
            } else {
                Exception exception = task.getException();
                if (exception instanceof FirebaseNetworkException) {
                    binding.tvResult.setText("Không có kết nối mạng. Kiểm tra Internet rồi thử lại.");
                } else if (exception instanceof FirebaseAuthException
                        && "ERROR_TOO_MANY_REQUESTS".equals(((FirebaseAuthException) exception).getErrorCode())) {
                    binding.tvResult.setText("Bạn đã thử quá nhiều lần. Vui lòng đợi một lúc rồi thử lại.");
                } else if (exception instanceof FirebaseAuthInvalidUserException) {
                    binding.tvResult.setText(SUCCESS_MESSAGE);
                } else if (exception instanceof FirebaseAuthInvalidCredentialsException) {
                    binding.tvResult.setText("Không thể xử lý yêu cầu lúc này. Kiểm tra email rồi thử lại.");
                } else {
                    binding.tvResult.setText("Chưa thể gửi yêu cầu. Vui lòng thử lại sau.");
                }
            }
        });
    }

    private void setLoading(boolean isLoading) {
        binding.btnSendResetLink.setEnabled(!isLoading);
        binding.etEmail.setEnabled(!isLoading);
        binding.btnSendResetLink.setText(isLoading ? "Đang gửi..." : "Gửi liên kết đặt lại mật khẩu");
        binding.progressSending.setVisibility(isLoading ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null && binding.etEmail.getWindowToken() != null) {
            inputMethodManager.hideSoftInputFromWindow(binding.etEmail.getWindowToken(), 0);
        }
        binding.etEmail.clearFocus();
    }
}
