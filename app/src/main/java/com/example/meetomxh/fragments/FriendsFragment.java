package com.example.meetomxh.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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
import com.example.meetomxh.repositories.UserSearchQuery;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Collections;
import java.util.List;

public class FriendsFragment extends Fragment implements FriendItemAdapter.OnFriendItemClickListener {
    public static final String ARG_OPEN_SEARCH = "open_user_search";
    private static final long SEARCH_DEBOUNCE_MS = 350;

    private enum Mode { REQUESTS, ALL_FRIENDS, SUGGESTIONS }

    private FragmentFriendsBinding binding;
    private FriendRepository friendRepository;
    private FriendItemAdapter adapter;
    private Mode currentMode = Mode.REQUESTS;
    private boolean searchOnly;
    private long requestVersion;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
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

        searchOnly = getArguments() != null && getArguments().getBoolean(ARG_OPEN_SEARCH, false);
        if (searchOnly) {
            binding.layoutTabs.setVisibility(View.GONE);
            binding.tvFriendsTitle.setText("Tìm người dùng");
            showSearchPrompt("Nhập tên người dùng để tìm kiếm.");
            binding.etSearchUsers.requestFocus();
            binding.etSearchUsers.postDelayed(() -> {
                if (binding == null || !isAdded()) return;
                InputMethodManager inputMethodManager = (InputMethodManager)
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (inputMethodManager != null) {
                    inputMethodManager.showSoftInput(binding.etSearchUsers, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 200);
        } else {
            loadDataForMode(currentMode);
        }
    }

    private void setupTabs() {
        binding.btnTabRequests.setOnClickListener(v -> switchTab(Mode.REQUESTS));
        binding.btnTabAllFriends.setOnClickListener(v -> switchTab(Mode.ALL_FRIENDS));
        binding.btnTabSuggestions.setOnClickListener(v -> switchTab(Mode.SUGGESTIONS));
    }

    private void switchTab(Mode mode) {
        currentMode = mode;
        binding.layoutTabs.setVisibility(View.VISIBLE);
        binding.btnTabRequests.setBackgroundTintList(getResources().getColorStateList(
                mode == Mode.REQUESTS ? R.color.brand_blue : R.color.divider_color, requireContext().getTheme()));
        binding.btnTabRequests.setTextColor(mode == Mode.REQUESTS ? 0xFFFFFFFF
                : getResources().getColor(R.color.text_primary, requireContext().getTheme()));
        binding.btnTabAllFriends.setBackgroundTintList(getResources().getColorStateList(
                mode == Mode.ALL_FRIENDS ? R.color.brand_blue : R.color.divider_color, requireContext().getTheme()));
        binding.btnTabAllFriends.setTextColor(mode == Mode.ALL_FRIENDS ? 0xFFFFFFFF
                : getResources().getColor(R.color.text_primary, requireContext().getTheme()));
        binding.btnTabSuggestions.setBackgroundTintList(getResources().getColorStateList(
                mode == Mode.SUGGESTIONS ? R.color.brand_blue : R.color.divider_color, requireContext().getTheme()));
        binding.btnTabSuggestions.setTextColor(mode == Mode.SUGGESTIONS ? 0xFFFFFFFF
                : getResources().getColor(R.color.text_primary, requireContext().getTheme()));

        if (mode == Mode.REQUESTS) binding.tvFriendsTitle.setText("Lời mời kết bạn");
        else if (mode == Mode.ALL_FRIENDS) binding.tvFriendsTitle.setText("Tất cả bạn bè");
        else binding.tvFriendsTitle.setText("Gợi ý kết bạn");
        loadDataForMode(mode);
    }

    private void setupSearch() {
        binding.etSearchUsers.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                requestVersion++;
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
            }

            @Override
            public void afterTextChanged(Editable editable) {
                String query = UserSearchQuery.normalize(editable.toString());
                long version = requestVersion;
                binding.btnClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (!query.isEmpty()) {
                    binding.layoutTabs.setVisibility(View.GONE);
                    binding.tvFriendsTitle.setText("Kết quả tìm kiếm");
                    binding.rvFriends.setVisibility(View.GONE);
                    showLoading(true);
                }
                searchRunnable = () -> {
                    if (!isCurrentRequest(version)) return;
                    if (query.isEmpty()) {
                        adapter.setItems(Collections.emptyList());
                        if (searchOnly) {
                            binding.layoutTabs.setVisibility(View.GONE);
                            binding.tvFriendsTitle.setText("Tìm người dùng");
                            showSearchPrompt("Nhập tên người dùng để tìm kiếm.");
                        } else {
                            switchTab(currentMode);
                        }
                    } else {
                        searchUsers(query, version);
                    }
                };
                searchHandler.postDelayed(searchRunnable, query.isEmpty() ? 150 : SEARCH_DEBOUNCE_MS);
            }
        });
        binding.btnClearSearch.setOnClickListener(v -> binding.etSearchUsers.setText(""));
    }

    private void loadDataForMode(Mode mode) {
        if (binding == null) return;
        long version = ++requestVersion;
        showLoading(true);
        binding.rvFriends.setVisibility(View.GONE);

        FriendRepository.OnFriendItemsListener listener = new FriendRepository.OnFriendItemsListener() {
            @Override
            public void onItems(List<FriendItem> items) {
                if (!isCurrentRequest(version) || mode != currentMode
                        || !binding.etSearchUsers.getText().toString().trim().isEmpty()) return;
                showItems(items, emptyMessageFor(mode));
            }

            @Override
            public void onError(String error) {
                if (!isCurrentRequest(version)) return;
                showError("Không thể tải danh sách. Hãy kiểm tra kết nối hoặc quyền truy cập.");
            }
        };

        if (mode == Mode.REQUESTS) {
            friendRepository.getReceivedFriendRequests(listener);
        } else if (mode == Mode.ALL_FRIENDS) {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) {
                showError("Bạn cần đăng nhập để xem bạn bè.");
            } else {
                friendRepository.getFriendsList(FirebaseAuth.getInstance().getCurrentUser().getUid(), listener);
            }
        } else {
            friendRepository.getSuggestedFriends(listener);
        }
    }

    private void searchUsers(String query, long version) {
        if (!isCurrentRequest(version)) return;
        showLoading(true);
        friendRepository.searchUsers(query, new FriendRepository.OnFriendItemsListener() {
            @Override
            public void onItems(List<FriendItem> items) {
                if (!isCurrentSearch(query, version)) return;
                showItems(items, "Không tìm thấy người dùng khớp với \"" + query + "\"");
            }

            @Override
            public void onError(String error) {
                if (!isCurrentSearch(query, version)) return;
                showError("Không thể tìm kiếm lúc này. Hãy kiểm tra kết nối hoặc quyền truy cập.");
            }
        });
    }

    private boolean isCurrentRequest(long version) {
        return binding != null && isAdded() && version == requestVersion;
    }

    private boolean isCurrentSearch(String query, long version) {
        return isCurrentRequest(version) && UserSearchQuery.isCurrent(query, version,
                binding.etSearchUsers.getText().toString(), requestVersion);
    }

    private void showItems(List<FriendItem> items, String emptyMessage) {
        showLoading(false);
        List<FriendItem> safeItems = items == null ? Collections.emptyList() : items;
        adapter.setItems(safeItems);
        if (safeItems.isEmpty()) {
            binding.rvFriends.setVisibility(View.GONE);
            binding.layoutEmptyState.setVisibility(View.VISIBLE);
            binding.tvEmptyMessage.setText(emptyMessage);
        } else {
            binding.layoutEmptyState.setVisibility(View.GONE);
            binding.rvFriends.setVisibility(View.VISIBLE);
        }
    }

    private String emptyMessageFor(Mode mode) {
        if (mode == Mode.REQUESTS) return "Không có lời mời kết bạn nào";
        if (mode == Mode.ALL_FRIENDS) return "Bạn chưa có người bạn nào";
        return "Không tìm thấy gợi ý kết bạn";
    }

    private void showSearchPrompt(String message) {
        if (binding == null) return;
        showLoading(false);
        adapter.setItems(Collections.emptyList());
        binding.rvFriends.setVisibility(View.GONE);
        binding.layoutEmptyState.setVisibility(View.VISIBLE);
        binding.tvEmptyMessage.setText(message);
    }

    private void showError(String message) {
        if (binding == null) return;
        showLoading(false);
        adapter.setItems(Collections.emptyList());
        binding.rvFriends.setVisibility(View.GONE);
        binding.layoutEmptyState.setVisibility(View.VISIBLE);
        binding.tvEmptyMessage.setText(message);
    }

    private void showLoading(boolean loading) {
        if (binding == null) return;
        binding.loadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) binding.layoutEmptyState.setVisibility(View.GONE);
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
        FriendRepository.OnFriendActionListener actionListener = new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                refreshCurrentView();
            }

            @Override
            public void onFailure(String error) {
                if (isAdded()) Toast.makeText(requireContext(), "Thao tác thất bại: " + error, Toast.LENGTH_SHORT).show();
            }
        };

        FriendItem.RelationshipStatus status = item.getRelationshipStatus();
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
        friendRepository.removeFriendRelation(item.getUser().getUid(), new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), "Đã từ chối lời mời kết bạn", Toast.LENGTH_SHORT).show();
                refreshCurrentView();
            }

            @Override
            public void onFailure(String error) {
                if (isAdded()) Toast.makeText(requireContext(), "Thao tác thất bại: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDestroyView() {
        requestVersion++;
        searchHandler.removeCallbacksAndMessages(null);
        binding = null;
        super.onDestroyView();
    }

    private void refreshCurrentView() {
        if (binding == null) return;
        String query = binding.etSearchUsers.getText().toString().trim();
        if (query.isEmpty()) {
            loadDataForMode(currentMode);
        } else {
            long version = ++requestVersion;
            searchUsers(query, version);
        }
    }
}
