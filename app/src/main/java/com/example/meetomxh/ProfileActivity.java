package com.example.meetomxh;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ActivityProfileBinding;
import com.example.meetomxh.models.FriendItem;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.FriendRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

public class ProfileActivity extends AppCompatActivity {

    private ActivityProfileBinding binding;
    private FriendRepository friendRepository;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    private String targetUid;
    private FriendItem.RelationshipStatus currentRelationshipStatus = FriendItem.RelationshipStatus.NO_RELATION;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        friendRepository = FriendRepository.getInstance();
        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();

        targetUid = getIntent().getStringExtra("target_uid");
        FirebaseUser currentUser = mAuth.getCurrentUser();

        if (targetUid == null || targetUid.isEmpty()) {
            if (currentUser != null) {
                targetUid = currentUser.getUid();
            } else {
                Toast.makeText(this, "Không tìm thấy thông tin người dùng", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        binding.btnBack.setOnClickListener(v -> finish());

        loadUserProfile();
        loadRelationshipStatus();
    }

    private void loadUserProfile() {
        db.collection("Users").document(targetUid)
                .get()
                .addOnSuccessListener(document -> {
                    if (isDestroyed() || isFinishing()) return;
                    if (document.exists()) {
                        User user = document.toObject(User.class);
                        if (user != null) {
                            binding.tvFullName.setText(user.getFullName() != null ? user.getFullName() : user.getEmail());
                            binding.tvBio.setText(user.getBio() != null && !user.getBio().isEmpty() ? user.getBio() : "Chưa có tiểu sử");
                            binding.tvGender.setText("Giới tính: " + (user.getGender() != null ? user.getGender() : "Chưa cập nhật"));
                            binding.tvDob.setText("Ngày sinh: " + (user.getDateOfBirth() != null ? user.getDateOfBirth() : "Chưa cập nhật"));

                            if (user.getAvatarUrl() != null && !user.getAvatarUrl().isEmpty()) {
                                Glide.with(this)
                                        .load(user.getAvatarUrl())
                                        .placeholder(R.drawable.ic_home)
                                        .error(R.drawable.ic_home)
                                        .into(binding.imgAvatar);
                            }

                            if (user.getCoverUrl() != null && !user.getCoverUrl().isEmpty()) {
                                Glide.with(this)
                                        .load(user.getCoverUrl())
                                        .placeholder(R.color.divider_color)
                                        .into(binding.imgCover);
                            }
                        }
                    } else {
                        Toast.makeText(this, "Người dùng không tồn tại", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Lỗi tải thông tin: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
    }

    private void loadRelationshipStatus() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null && currentUser.getUid().equals(targetUid)) {
            currentRelationshipStatus = FriendItem.RelationshipStatus.SELF;
            updateActionButtonsUI();
            return;
        }

        friendRepository.getRelationshipStatus(targetUid, new FriendRepository.OnRelationshipStatusListener() {
            @Override
            public void onStatus(FriendItem.RelationshipStatus status) {
                if (isDestroyed() || isFinishing()) return;
                currentRelationshipStatus = status;
                updateActionButtonsUI();
            }

            @Override
            public void onError(String error) {
                if (isDestroyed() || isFinishing()) return;
                Toast.makeText(ProfileActivity.this, "Lỗi kiểm tra quan hệ: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateActionButtonsUI() {
        if (currentRelationshipStatus == FriendItem.RelationshipStatus.SELF) {
            binding.btnEditProfile.setVisibility(View.VISIBLE);
            binding.btnPrimaryAction.setVisibility(View.GONE);
            binding.btnSecondaryAction.setVisibility(View.GONE);

            binding.btnEditProfile.setOnClickListener(v ->
                    Toast.makeText(this, "Mở màn hình Chỉnh sửa trang cá nhân", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        binding.btnEditProfile.setVisibility(View.GONE);

        if (currentRelationshipStatus == FriendItem.RelationshipStatus.NO_RELATION) {
            binding.btnPrimaryAction.setText("Thêm bạn bè");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.GONE);

            binding.btnPrimaryAction.setOnClickListener(v -> handleSendRequest());
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.PENDING_SENT) {
            binding.btnPrimaryAction.setText("Hủy lời mời");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.GONE);

            binding.btnPrimaryAction.setOnClickListener(v -> handleRemoveRelation("Đã hủy lời mời kết bạn"));
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.PENDING_RECEIVED) {
            binding.btnPrimaryAction.setText("Chấp nhận");
            binding.btnSecondaryAction.setText("Từ chối");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.VISIBLE);

            binding.btnPrimaryAction.setOnClickListener(v -> handleAcceptRequest());
            binding.btnSecondaryAction.setOnClickListener(v -> handleRemoveRelation("Đã từ chối lời mời"));
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.ACCEPTED) {
            binding.btnPrimaryAction.setText("Bạn bè");
            binding.btnSecondaryAction.setText("Hủy kết bạn");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.VISIBLE);

            binding.btnPrimaryAction.setOnClickListener(v ->
                    Toast.makeText(this, "Các bạn đang là bạn bè", Toast.LENGTH_SHORT).show()
            );
            binding.btnSecondaryAction.setOnClickListener(v -> handleRemoveRelation("Đã hủy kết bạn"));
        }
    }

    private void handleSendRequest() {
        binding.btnPrimaryAction.setEnabled(false);
        friendRepository.sendFriendRequest(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, message, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleAcceptRequest() {
        binding.btnPrimaryAction.setEnabled(false);
        friendRepository.acceptFriendRequest(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, message, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleRemoveRelation(String successToast) {
        binding.btnPrimaryAction.setEnabled(false);
        binding.btnSecondaryAction.setEnabled(false);

        friendRepository.removeFriendRelation(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                binding.btnSecondaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, successToast, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                binding.btnSecondaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
