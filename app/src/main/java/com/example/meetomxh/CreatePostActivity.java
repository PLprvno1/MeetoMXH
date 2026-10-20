package com.example.meetomxh;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ActivityCreatePostBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CreatePostActivity extends AppCompatActivity {

    private static final String IMGBB_API_KEY = "a6ded9f4bbefeed4cc6dac86bd0fed0a";

    private ActivityCreatePostBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private Uri selectedImageUri;
    private String visibility = "public"; // "public", "friends", "private"
    private String authorName = "";
    private String authorAvatar = "";

    private final ActivityResultLauncher<Intent> imagePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedImageUri = result.getData().getData();
                    binding.layoutImagePreview.setVisibility(View.VISIBLE);
                    binding.ivSelectedImagePreview.setImageURI(selectedImageUri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Dynamic Status Bar WindowInsets padding for top bar safe area
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.createPostMainContainer, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars());
            int topPadding = Math.max(statusBarInsets.top + 16, (int) (36 * getResources().getDisplayMetrics().density));
            v.setPadding(v.getPaddingLeft(), topPadding, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "Vui lòng đăng nhập để đăng bài viết", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupUI(currentUser);
        loadAuthorDetails(currentUser.getUid());
    }

    private void setupUI(FirebaseUser currentUser) {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnPickImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            imagePickerLauncher.launch(intent);
        });

        binding.btnRemoveImage.setOnClickListener(v -> {
            selectedImageUri = null;
            binding.layoutImagePreview.setVisibility(View.GONE);
        });

        binding.btnAudience.setOnClickListener(v -> {
            if ("public".equals(visibility)) {
                visibility = "friends";
                binding.btnAudience.setText("👥 Bạn bè");
            } else if ("friends".equals(visibility)) {
                visibility = "private";
                binding.btnAudience.setText("🔒 Chỉ mình tôi");
            } else {
                visibility = "public";
                binding.btnAudience.setText("🌎 Công khai");
            }
        });

        binding.btnPostSubmit.setOnClickListener(v -> submitPost(currentUser.getUid()));
    }

    private void loadAuthorDetails(String uid) {
        db.collection("Users").document(uid)
                .get()
                .addOnSuccessListener(document -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (document.exists()) {
                        authorName = document.getString("fullName");
                        authorAvatar = document.getString("avatarUrl");

                        if (authorName != null && !authorName.isEmpty()) {
                            binding.tvAuthorName.setText(authorName);
                        }

                        if (authorAvatar != null && !authorAvatar.isEmpty()) {
                            Glide.with(this)
                                    .load(authorAvatar)
                                    .placeholder(R.drawable.ic_home)
                                    .into(binding.ivAuthorAvatar);
                        }
                    }
                });
    }

    private void submitPost(String authorUid) {
        String content = binding.etPostContent.getText().toString().trim();

        if (content.isEmpty() && selectedImageUri == null) {
            Toast.makeText(this, "Vui lòng nhập nội dung hoặc chọn ảnh để đăng bài", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnPostSubmit.setEnabled(false);
        binding.btnPostSubmit.setText("Đang đăng...");

        if (selectedImageUri != null) {
            uploadImageToImgbb(selectedImageUri, new OnImageUploadListener() {
                @Override
                public void onSuccess(String imageUrl) {
                    savePostToFirestore(authorUid, content, imageUrl);
                }

                @Override
                public void onFailure(String errorMsg) {
                    binding.btnPostSubmit.setEnabled(true);
                    binding.btnPostSubmit.setText("Đăng");
                    Toast.makeText(CreatePostActivity.this, "Lỗi tải ảnh: " + errorMsg, Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            savePostToFirestore(authorUid, content, "");
        }
    }

    private void savePostToFirestore(String authorUid, String content, String imageUrl) {
        String postId = db.collection("Posts").document().getId();
        long now = System.currentTimeMillis();

        List<String> imageUrls = new ArrayList<>();
        if (!imageUrl.isEmpty()) {
            imageUrls.add(imageUrl);
        }

        Map<String, Object> postMap = new HashMap<>();
        postMap.put("postId", postId);
        postMap.put("authorUid", authorUid);
        postMap.put("userName", authorName != null && !authorName.isEmpty() ? authorName : "Thành viên Meeto");
        postMap.put("userAvatar", authorAvatar != null ? authorAvatar : "");
        postMap.put("content", content);
        postMap.put("postImage", imageUrl);
        postMap.put("imageUrls", imageUrls);
        postMap.put("likesCount", 0);
        postMap.put("commentsCount", 0);
        postMap.put("sharesCount", 0);
        postMap.put("type", "normal");
        postMap.put("originalPostId", "");
        postMap.put("visibility", visibility);
        postMap.put("createdAt", now);
        postMap.put("updatedAt", now);

        db.collection("Posts").document(postId)
                .set(postMap)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(CreatePostActivity.this, "Đăng bài viết thành công!", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                })
                .addOnFailureListener(e -> {
                    binding.btnPostSubmit.setEnabled(true);
                    binding.btnPostSubmit.setText("Đăng");
                    Toast.makeText(CreatePostActivity.this, "Đăng bài thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    public interface OnImageUploadListener {
        void onSuccess(String imageUrl);
        void onFailure(String errorMsg);
    }

    private void uploadImageToImgbb(Uri imageUri, OnImageUploadListener listener) {
        try {
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
                if (inputStream == null) {
                    throw new IOException("Không thể mở file ảnh");
                }
                while ((len = inputStream.read(buffer)) != -1) {
                    byteBuffer.write(buffer, 0, len);
                }
            }
            byte[] imageBytes = byteBuffer.toByteArray();

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "post.jpg",
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
                    try (Response closeableResponse = response) {
                        if (closeableResponse.isSuccessful() && closeableResponse.body() != null) {
                            try {
                                String responseBody = closeableResponse.body().string();
                                String imageUrl = new JSONObject(responseBody).getJSONObject("data").getString("url");
                                runOnUiThread(() -> listener.onSuccess(imageUrl));
                            } catch (Exception e) {
                                runOnUiThread(() -> listener.onFailure("Lỗi xử lý JSON"));
                            }
                        } else {
                            runOnUiThread(() -> listener.onFailure("Imgbb lỗi mã: " + closeableResponse.code()));
                        }
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure("Không đọc được file ảnh: " + e.getMessage());
        }
    }
}
