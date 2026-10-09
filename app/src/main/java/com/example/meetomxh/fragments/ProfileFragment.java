package com.example.meetomxh.fragments;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.example.meetomxh.LoginActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.FragmentProfileBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        loadUserProfile();
        setupDarkModeSwitch();

        // Xử lý nút Đăng xuất
        binding.btnLogout.setOnClickListener(v -> handleLogout());

        // Xử lý các shortcut
        binding.cardFriendsShortcut.setOnClickListener(v ->
                Toast.makeText(requireContext(), "Mở danh sách Bạn bè", Toast.LENGTH_SHORT).show()
        );

        binding.cardSavedShortcut.setOnClickListener(v ->
                Toast.makeText(requireContext(), "Mở mục Đã lưu", Toast.LENGTH_SHORT).show()
        );
    }

    private void setupDarkModeSwitch() {
        int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isNightMode = (currentNightMode == Configuration.UI_MODE_NIGHT_YES);
        binding.switchDarkMode.setChecked(isNightMode);

        binding.switchDarkMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                setDarkMode(AppCompatDelegate.MODE_NIGHT_YES);
            } else {
                setDarkMode(AppCompatDelegate.MODE_NIGHT_NO);
            }
        });
    }

    /**
     * Hàm hỗ trợ chuyển đổi chế độ giao diện Sáng / Tối / Theo hệ thống
     * @param mode AppCompatDelegate.MODE_NIGHT_YES, MODE_NIGHT_NO, hoặc MODE_NIGHT_FOLLOW_SYSTEM
     */
    public void setDarkMode(int mode) {
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void loadUserProfile() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            return;
        }

        String userId = currentUser.getUid();

        // Query Firestore collection Users
        db.collection("Users").document(userId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (isAdded() && documentSnapshot.exists()) {
                        String fullName = documentSnapshot.getString("fullName");
                        String avatarUrl = documentSnapshot.getString("avatarUrl");

                        if (fullName != null && !fullName.isEmpty()) {
                            binding.tvFullName.setText(fullName);
                        } else if (currentUser.getEmail() != null) {
                            binding.tvFullName.setText(currentUser.getEmail());
                        }

                        if (avatarUrl != null && !avatarUrl.isEmpty()) {
                            Glide.with(requireContext())
                                    .load(avatarUrl)
                                    .placeholder(R.drawable.ic_home)
                                    .error(R.drawable.ic_home)
                                    .into(binding.ivAvatar);
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "Không thể tải thông tin người dùng: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void handleLogout() {
        mAuth.signOut();
        Toast.makeText(requireContext(), "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        if (getActivity() != null) {
            getActivity().finish();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
