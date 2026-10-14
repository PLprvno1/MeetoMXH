package com.example.meetomxh.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.adapters.FriendItemAdapter;
import com.example.meetomxh.databinding.FragmentFriendsBinding;
import com.example.meetomxh.models.FriendItem;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.FriendRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.List;

public class FriendsFragment extends Fragment implements FriendItemAdapter.OnFriendItemClickListener {

    private enum Mode {
        REQUESTS,
        ALL_FRIENDS,
        SUGGESTIONS,
        SEARCH
    }

    private FragmentFriendsBinding binding;
    private FriendRepository friendRepository;
    private FriendItemAdapter adapter;
    private Mode currentMode = Mode.REQUESTS;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentFriendsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        friendRepository = FriendRepository.getInstance();
        adapter = new FriendItemAdapter(this);
        binding.rvFriends.setAdapter(adapter);

        setupTabs();
        setupSearch();

        loadDataForMode(Mode.REQUESTS);
    }

    private void setupTabs() {
        binding.btnTabRequests.setOnClickListener(v -> switchTab(Mode.REQUESTS));
        binding.btnTabAllFriends.setOnClickListener(v -> switchTab(Mode.ALL_FRIENDS));
        binding.btnTabSuggestions.setOnClickListener(v -> switchTab(Mode.SUGGESTIONS));
    }

    private void switchTab(Mode mode) {
        this.currentMode = mode;

        binding.btnTabRequests.setBackgroundTintList(getResources().getColorStateList(
                mode == Mode.REQUESTS ? R.color.brand_blue : R.color.divider_color, requireContext().getTheme()));
        binding.btnTabRequests.setTextColor(mode == Mode.REQUESTS ? 0xFFFFFFFF : getResources().getColor(R.color.text_primary, requireContext().getTheme()));

        binding.btnTabAllFriends.setBackgroundTintList(getResources().getColorStateList(
                mode == Mode.ALL_FRIENDS ? R.color.brand_blue : R.color.divider_color, requireContext().getTheme()));
        binding.btnTabAllFriends.setTextColor(mode == Mode.ALL_FRIENDS ? 0xFFFFFFFF : getResources().getColor(R.color.text_primary, requireContext().getTheme()));

        binding.btnTabSuggestions.setBackgroundTintList(getResources().getColorStateList(
                mode == Mode.SUGGESTIONS ? R.color.brand_blue : R.color.divider_color, requireContext().getTheme()));
        binding.btnTabSuggestions.setTextColor(mode == Mode.SUGGESTIONS ? 0xFFFFFFFF : getResources().getColor(R.color.text_primary, requireContext().getTheme()));

        if (mode == Mode.REQUESTS) {
            binding.tvFriendsTitle.setText("Lời mời kết bạn");
        } else if (mode == Mode.ALL_FRIENDS) {
            binding.tvFriendsTitle.setText("Tất cả bạn bè");
        } else if (mode == Mode.SUGGESTIONS) {
            binding.tvFriendsTitle.setText("Gợi ý kết bạn");
        }

        loadDataForMode(mode);
    }

    private void setupSearch() {
        binding.etSearchUsers.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                String query = s.toString().trim();
                searchRunnable = () -> {
                    if (query.isEmpty()) {
                        switchTab(currentMode);
                    } else {
                        binding.tvFriendsTitle.setText("Kết quả tìm kiếm");
                        performSearch(query);
                    }
                };
                searchHandler.postDelayed(searchRunnable, 400);
            }
        });
    }

    private void loadDataForMode(Mode mode) {
        showLoading(true);

        FriendRepository.OnFriendItemsListener listener = new FriendRepository.OnFriendItemsListener() {
            @Override
            public void onItems(List<FriendItem> items) {
                if (!isAdded()) return;
                showLoading(false);
                adapter.setItems(items);

                if (items.isEmpty()) {
                    binding.layoutEmptyState.setVisibility(View.VISIBLE);
                    binding.rvFriends.setVisibility(View.GONE);
                    if (mode == Mode.REQUESTS) {
                        binding.tvEmptyMessage.setText("Không có lời mời kết bạn nào");
                    } else if (mode == Mode.ALL_FRIENDS) {
                        binding.tvEmptyMessage.setText("Bạn chưa có người bạn nào");
                    } else {
                        binding.tvEmptyMessage.setText("Không tìm thấy gợi ý kết bạn");
                    }
                } else {
                    binding.layoutEmptyState.setVisibility(View.GONE);
                    binding.rvFriends.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                showLoading(false);
                Toast.makeText(requireContext(), "Lỗi tải dữ liệu: " + error, Toast.LENGTH_SHORT).show();
            }
        };

        if (mode == Mode.REQUESTS) {
            friendRepository.getReceivedFriendRequests(listener);
        } else if (mode == Mode.ALL_FRIENDS) {
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                friendRepository.getFriendsList(FirebaseAuth.getInstance().getCurrentUser().getUid(), listener);
            }
        } else if (mode == Mode.SUGGESTIONS) {
            friendRepository.getSuggestedFriends(listener);
        }
    }

    private void performSearch(String query) {
        showLoading(true);
        friendRepository.searchUsers(query, new FriendRepository.OnFriendItemsListener() {
            @Override
            public void onItems(List<FriendItem> items) {
                if (!isAdded()) return;
                showLoading(false);
                adapter.setItems(items);

                if (items.isEmpty()) {
                    binding.layoutEmptyState.setVisibility(View.VISIBLE);
                    binding.rvFriends.setVisibility(View.GONE);
                    binding.tvEmptyMessage.setText("Không tìm thấy người dùng khớp với \"" + query + "\"");
                } else {
                    binding.layoutEmptyState.setVisibility(View.GONE);
                    binding.rvFriends.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                showLoading(false);
                Toast.makeText(requireContext(), "Lỗi tìm kiếm: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showLoading(boolean isLoading) {
        binding.loadingIndicator.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        if (isLoading) {
            binding.layoutEmptyState.setVisibility(View.GONE);
        }
    }

    @Override
    public void onItemClick(User user) {
        if (user == null || user.getUid() == null) return;
        Intent intent = new Intent(requireContext(), ProfileActivity.class);
        intent.putExtra("target_uid", user.getUid());
        startActivity(intent);
    }

    @Override
    public void onPrimaryActionClick(FriendItem item) {
        if (item == null || item.getUser() == null) return;
        User user = item.getUser();
        FriendItem.RelationshipStatus status = item.getRelationshipStatus();

        FriendRepository.OnFriendActionListener actionListener = new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                loadDataForMode(currentMode);
            }

            @Override
            public void onFailure(String error) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Thao tác thất bại: " + error, Toast.LENGTH_SHORT).show();
            }
        };

        if (status == FriendItem.RelationshipStatus.NO_RELATION) {
            friendRepository.sendFriendRequest(user.getUid(), actionListener);
        } else if (status == FriendItem.RelationshipStatus.PENDING_SENT || status == FriendItem.RelationshipStatus.ACCEPTED) {
            friendRepository.removeFriendRelation(user.getUid(), actionListener);
        } else if (status == FriendItem.RelationshipStatus.PENDING_RECEIVED) {
            friendRepository.acceptFriendRequest(user.getUid(), actionListener);
        }
    }

    @Override
    public void onSecondaryActionClick(FriendItem item) {
        if (item == null || item.getUser() == null) return;
        User user = item.getUser();

        friendRepository.removeFriendRelation(user.getUid(), new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Đã từ chối lời mời kết bạn", Toast.LENGTH_SHORT).show();
                loadDataForMode(currentMode);
            }

            @Override
            public void onFailure(String error) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Thao tác thất bại: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        searchHandler.removeCallbacksAndMessages(null);
        binding = null;
    }
}
