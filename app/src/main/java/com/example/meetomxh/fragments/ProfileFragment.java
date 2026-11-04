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
import com.example.meetomxh.GroupsHomeActivity;
import com.example.meetomxh.LoginActivity;
import com.example.meetomxh.MarketplaceActivity;
import com.example.meetomxh.MemoriesActivity;
import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.SavedPostsActivity;
import com.example.meetomxh.SettingsActivity;
import com.example.meetomxh.databinding.FragmentProfileBinding;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.ui.AvatarViews;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;

public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private SessionManager mAuth;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAuth = SessionManager.getInstance();

        binding.cardProfile.setOnClickListener(v -> startActivity(new Intent(requireContext(),
                ProfileActivity.class).putExtra("target_uid", mAuth.uid())));
        setupDarkModeSwitch();

        // Xử lý nút Đăng xuất
        binding.btnLogout.setOnClickListener(v -> handleLogout());

        // Xử lý các shortcut
        binding.cardFriendsShortcut.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction().replace(R.id.fragment_container, new FriendsFragment()).addToBackStack("friends").commit()
        );

        binding.cardSavedShortcut.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SavedPostsActivity.class))
        );

        if (binding.cardSettingsShortcut != null) {
            binding.cardSettingsShortcut.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), SettingsActivity.class))
            );
        }

        if (binding.cardGroupsShortcut != null) {
            binding.cardGroupsShortcut.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), GroupsHomeActivity.class))
            );
        }

        if (binding.cardMemoriesShortcut != null) {
            binding.cardMemoriesShortcut.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), MemoriesActivity.class))
            );
        }

        if (binding.cardMarketplaceShortcut != null) {
            binding.cardMarketplaceShortcut.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), MarketplaceActivity.class))
            );
        }
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

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null && mAuth != null) loadUserProfile();
    }

    /**
     * Hàm hỗ trợ chuyển đổi chế độ giao diện Sáng / Tối / Theo hệ thống
     * @param mode AppCompatDelegate.MODE_NIGHT_YES, MODE_NIGHT_NO, hoặc MODE_NIGHT_FOLLOW_SYSTEM
     */
    public void setDarkMode(int mode) {
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void loadUserProfile() {
        if (mAuth.getCurrentUser() == null) return;
        ProfileClient.get(mAuth.uid(), new Api.Callback<com.example.meetomxh.models.User>() {
            public void success(com.example.meetomxh.models.User user) {
                if (binding == null || !isAdded()) return;
                binding.tvFullName.setText(user.getFullName());
                AvatarViews.load(binding.ivAvatar, user.getAvatarUrl(), user.getAvatarPreset());
                binding.ivAvatar.setOnClickListener(v -> startActivity(new Intent(requireContext(), ProfileActivity.class).putExtra("target_uid", mAuth.uid())));
            }
            public void error(String e) {
                if (isAdded()) Toast.makeText(requireContext(), e, Toast.LENGTH_LONG).show();
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
