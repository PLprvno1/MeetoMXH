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
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;
import com.example.meetomxh.supabase.*;

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
                com.example.meetomxh.ProfileActivity.class).putExtra("target_uid", mAuth.uid())));
        setupDarkModeSwitch();

        // Xử lý nút Đăng xuất
        binding.btnLogout.setOnClickListener(v -> handleLogout());

        // Xử lý các shortcut
        binding.cardFriendsShortcut.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction().replace(com.example.meetomxh.R.id.fragment_container,new FriendsFragment()).addToBackStack("friends").commit()
        );

        binding.cardSavedShortcut.setOnClickListener(v ->
                Toast.makeText(requireContext(), "Đã lưu chưa được hỗ trợ trên Supabase", Toast.LENGTH_SHORT).show()
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

    @Override public void onResume() {
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

    private void loadUserProfile() {if(mAuth.getCurrentUser()==null)return;ProfileClient.get(mAuth.uid(),new Api.Callback<com.example.meetomxh.models.User>(){public void success(com.example.meetomxh.models.User user){if(binding==null||!isAdded())return;binding.tvFullName.setText(user.getFullName());Glide.with(requireContext()).applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(user.getAvatarUrl()).placeholder(R.drawable.ic_home).into(binding.ivAvatar);binding.ivAvatar.setOnClickListener(v->startActivity(new Intent(requireContext(),com.example.meetomxh.ProfileActivity.class).putExtra("target_uid",mAuth.uid())));}public void error(String e){if(isAdded())Toast.makeText(requireContext(),e,Toast.LENGTH_LONG).show();}});}

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
