package com.example.meetomxh;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RegisterActivity extends AppCompatActivity {

    private static final String IMGBB_API_KEY = "a6ded9f4bbefeed4cc6dac86bd0fed0a";
    private Uri selectedImageUri;

    // Khai báo các view
    private LinearLayout layoutStep1, layoutStep2, layoutStep3;
    private Button btnNextStep1, btnNextStep2, btnCompleteRegister;
    private ImageView imgAvatar;

    // Khai báo các ô nhập liệu
    private EditText etEmail, etPassword, etFullName, etLocation;

    private final ActivityResultLauncher<Intent> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedImageUri = result.getData().getData();
                    imgAvatar.setImageURI(selectedImageUri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // 1. Ánh xạ View
        layoutStep1 = findViewById(R.id.layoutStep1);
        layoutStep2 = findViewById(R.id.layoutStep2);
        layoutStep3 = findViewById(R.id.layoutStep3);

        btnNextStep1 = findViewById(R.id.btnNextStep1);
        btnNextStep2 = findViewById(R.id.btnNextStep2);
        btnCompleteRegister = findViewById(R.id.btnCompleteRegister);

        imgAvatar = findViewById(R.id.imgAvatar);

        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etFullName = findViewById(R.id.etFullName);
        etLocation = findViewById(R.id.etLocation);

        // 2. Xử lý chuyển bước 1 -> 2
        btnNextStep1.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            String password = etPassword.getText().toString().trim();
            if (email.isEmpty() || password.length() < 6) {
                Toast.makeText(this, "Email không hợp lệ hoặc mật khẩu dưới 6 ký tự", Toast.LENGTH_SHORT).show();
                return;
            }
            layoutStep1.setVisibility(View.GONE);
            layoutStep2.setVisibility(View.VISIBLE);
        });

        // 3. Xử lý chuyển bước 2 -> 3
        btnNextStep2.setOnClickListener(v -> {
            if (etFullName.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập Họ và tên", Toast.LENGTH_SHORT).show();
                return;
            }
            layoutStep2.setVisibility(View.GONE);
            layoutStep3.setVisibility(View.VISIBLE);
        });

        // 4. Chọn ảnh
        imgAvatar.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            imagePickerLauncher.launch(intent);
        });

        // 5. Hoàn tất đăng ký (Gộp 3 bước: Auth -> Imgbb -> Firestore)
        btnCompleteRegister.setOnClickListener(v -> {
            if (selectedImageUri == null) {
                Toast.makeText(this, "Vui lòng chọn ảnh đại diện", Toast.LENGTH_SHORT).show();
                return;
            }

            btnCompleteRegister.setEnabled(false);
            btnCompleteRegister.setText("Đang tạo tài khoản...");

            String email = etEmail.getText().toString().trim();
            String password = etPassword.getText().toString().trim();
            String fullName = etFullName.getText().toString().trim();
            String location = etLocation.getText().toString().trim();

            // BƯỚC A: Tạo tài khoản trên Firebase Authentication
            FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) {
                            // Tạo Auth thành công, chuyển sang up ảnh
                            btnCompleteRegister.setText("Đang tải ảnh lên...");
                            uploadImageToImgbb(selectedImageUri, new OnImageUploadListener() {
                                @Override
                                public void onSuccess(String imageUrl) {
                                    // BƯỚC B & C: Up ảnh thành công, lưu dữ liệu vào Firestore
                                    saveUserToFirestore(task.getResult().getUser().getUid(), email, fullName, location, imageUrl);
                                }

                                @Override
                                public void onFailure(String errorMsg) {
                                    resetButton("Lỗi tải ảnh: " + errorMsg);
                                }
                            });
                        } else {
                            resetButton("Lỗi tạo tài khoản: " + task.getException().getMessage());
                        }
                    });
        });
    }

    private void resetButton(String errorMsg) {
        btnCompleteRegister.setEnabled(true);
        btnCompleteRegister.setText("Hoàn tất Đăng ký");
        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
    }

    public interface OnImageUploadListener {
        void onSuccess(String imageUrl);
        void onFailure(String errorMsg);
    }

    private void uploadImageToImgbb(Uri imageUri, OnImageUploadListener listener) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(imageUri);
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
            byte[] imageBytes = byteBuffer.toByteArray();

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "avatar.jpg",
                            RequestBody.create(imageBytes, MediaType.parse("image/*")))
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.imgbb.com/1/upload?key=" + IMGBB_API_KEY)
                    .post(requestBody)
                    .build();

            new OkHttpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    runOnUiThread(() -> listener.onFailure(e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String responseBody = response.body().string();
                            String imageUrl = new JSONObject(responseBody).getJSONObject("data").getString("url");
                            runOnUiThread(() -> listener.onSuccess(imageUrl));
                        } catch (Exception e) {
                            runOnUiThread(() -> listener.onFailure("Lỗi xử lý JSON"));
                        }
                    } else {
                        runOnUiThread(() -> listener.onFailure("Imgbb lỗi mã: " + response.code()));
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure("Không đọc được file ảnh: " + e.getMessage());
        }
    }

    // Hàm lưu Firestore nhận trực tiếp dữ liệu từ các bước trên
    private void saveUserToFirestore(String userId, String email, String fullName, String location, String avatarUrl) {
        Map<String, Object> user = new HashMap<>();
        user.put("userId", userId);
        user.put("email", email);
        user.put("fullName", fullName);
        user.put("location", location);
        user.put("avatarUrl", avatarUrl);
        user.put("createdAt", System.currentTimeMillis());

        FirebaseFirestore.getInstance().collection("Users").document(userId)
                .set(user)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Đăng ký thành công!", Toast.LENGTH_SHORT).show();
                    // Bạn có thể thêm lệnh Intent chuyển sang MainActivity tại đây
                })
                .addOnFailureListener(e -> resetButton("Lỗi lưu dữ liệu: " + e.getMessage()));
    }
}