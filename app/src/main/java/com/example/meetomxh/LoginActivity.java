package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.meetomxh.databinding.ActivityLoginBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

        // Auto-login: Nếu người dùng đã đăng nhập thì vào thẳng MainActivity
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            startActivity(new Intent(LoginActivity.this, MainActivity.class));
            finish();
            return;
        }

        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Bắt sự kiện nút "Đăng nhập"
        binding.btnLogin.setOnClickListener(v -> handleLogin());

        // Bắt sự kiện nút "Tạo tài khoản mới"
        binding.btnRegister.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
            startActivity(intent);
        });

        // Bắt sự kiện Quên mật khẩu
        binding.tvForgotPassword.setOnClickListener(v -> {
            Toast.makeText(this, "Tính năng Quên mật khẩu đang được phát triển", Toast.LENGTH_SHORT).show();
        });
    }

    private void handleLogin() {
        String email = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim();

        if (email.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập Email hoặc Số điện thoại", Toast.LENGTH_SHORT).show();
            return;
        }

        if (password.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập Mật khẩu", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnLogin.setEnabled(false);
        binding.btnLogin.setText("Đang đăng nhập...");

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(LoginActivity.this, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                        finish();
                    } else {
                        binding.btnLogin.setEnabled(true);
                        binding.btnLogin.setText("Đăng nhập");
                        String errorMsg = task.getException() != null ? task.getException().getMessage() : "Đăng nhập thất bại";
                        Toast.makeText(LoginActivity.this, "Đăng nhập thất bại: " + errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }
}
