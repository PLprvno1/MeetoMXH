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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.FragmentSearchBinding;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.AdvancedSearchRepository;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.PostDetails;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.imageview.ShapeableImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SearchFragment extends Fragment {

    private FragmentSearchBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private final List<Post> explorePosts = new ArrayList<>();
    private ExploreGridAdapter gridAdapter;
    private SearchResultAdapter resultAdapter;
    private final List<JSONObject> searchUsers = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSearchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Setup Explore 3-column Grid
        gridAdapter = new ExploreGridAdapter(explorePosts);
        binding.rvExploreGrid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        binding.rvExploreGrid.setAdapter(gridAdapter);

        // Setup Search Results list
        resultAdapter = new SearchResultAdapter(searchUsers);
        binding.rvSearchResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvSearchResults.setAdapter(resultAdapter);

        binding.swipeExplore.setOnRefreshListener(this::loadExploreContent);

        binding.btnClearSearch.setOnClickListener(v -> {
            binding.etSearchExplore.setText("");
            showExploreGrid();
        });

        binding.etSearchExplore.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
                String query = s.toString().trim();
                binding.btnClearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (query.length() < 2) {
                    showExploreGrid();
                    return;
                }
                pendingSearch = () -> performSearch(query);
                handler.postDelayed(pendingSearch, 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadExploreContent();
    }

    private void showExploreGrid() {
        binding.rvExploreGrid.setVisibility(View.VISIBLE);
        binding.swipeExplore.setVisibility(View.VISIBLE);
        binding.rvSearchResults.setVisibility(View.GONE);
        binding.tvExploreStatus.setVisibility(explorePosts.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadExploreContent() {
        binding.swipeExplore.setRefreshing(true);
        AdvancedSearchRepository.posts("", false, null, "newest", -1f, null, null, 30, new AdvancedSearchRepository.Callback<AdvancedSearchRepository.PostPage>() {
            @Override
            public void success(AdvancedSearchRepository.PostPage page) {
                if (!isAdded() || binding == null) return;
                binding.swipeExplore.setRefreshing(false);
                explorePosts.clear();
                if (page != null && page.posts != null) {
                    for (Post p : page.posts) {
                        if (p.getPostImage() != null && !p.getPostImage().isEmpty()) {
                            explorePosts.add(p);
                        }
                    }
                    if (explorePosts.isEmpty()) {
                        explorePosts.addAll(page.posts);
                    }
                }
                gridAdapter.notifyDataSetChanged();
                binding.tvExploreStatus.setVisibility(explorePosts.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void error(String error) {
                if (!isAdded() || binding == null) return;
                binding.swipeExplore.setRefreshing(false);
                binding.tvExploreStatus.setVisibility(explorePosts.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void performSearch(String query) {
        if (!isAdded() || binding == null) return;
        binding.progressExplore.setVisibility(View.VISIBLE);
        binding.rvExploreGrid.setVisibility(View.GONE);
        binding.swipeExplore.setVisibility(View.GONE);
        binding.rvSearchResults.setVisibility(View.VISIBLE);

        AdvancedSearchRepository.people(query, null, null, 20, new AdvancedSearchRepository.Callback<JSONArray>() {
            @Override
            public void success(JSONArray rows) {
                if (!isAdded() || binding == null) return;
                binding.progressExplore.setVisibility(View.GONE);
                searchUsers.clear();
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject obj = rows.optJSONObject(i);
                        if (obj != null) searchUsers.add(obj);
                    }
                }
                resultAdapter.notifyDataSetChanged();
                binding.tvExploreStatus.setVisibility(searchUsers.isEmpty() ? View.VISIBLE : View.GONE);
                if (searchUsers.isEmpty()) {
                    binding.tvExploreStatus.setText("Không tìm thấy kết quả cho \"" + query + "\"");
                }
            }

            @Override
            public void error(String error) {
                if (!isAdded() || binding == null) return;
                binding.progressExplore.setVisibility(View.GONE);
                binding.tvExploreStatus.setVisibility(View.VISIBLE);
                binding.tvExploreStatus.setText("Lỗi tìm kiếm: " + error);
            }
        });
    }

    // 3-Column Explore Grid Adapter
    private class ExploreGridAdapter extends RecyclerView.Adapter<ExploreGridAdapter.Holder> {
        private final List<Post> items;
        ExploreGridAdapter(List<Post> items) { this.items = items; }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView img = new ImageView(parent.getContext());
            int size = parent.getWidth() > 0 ? parent.getWidth() / 3 : (int)(120 * getResources().getDisplayMetrics().density);
            img.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size));
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(requireContext().getColor(R.color.surface_variant));
            img.setPadding(2, 2, 2, 2);
            return new Holder(img);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Post p = items.get(position);
            String url = p.getPostImage();
            if (url != null && !url.isEmpty()) {
                Glide.with(holder.itemView.getContext())
                        .load(url)
                        .centerCrop()
                        .into(holder.image);
            } else {
                AvatarViews.load(holder.image, p.getUserAvatar(), p.getUserAvatarPreset());
            }
            holder.itemView.setOnClickListener(v -> PostDetails.show(requireActivity(), p.getPostId()));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView image;
            Holder(View v) { super(v); image = (ImageView) v; }
        }
    }

    // Search Result Adapter (People & Accounts)
    private class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.Holder> {
        private final List<JSONObject> items;
        SearchResultAdapter(List<JSONObject> items) { this.items = items; }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_inbox, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            JSONObject user = items.get(position);
            String name = user.optString("display_name", user.optString("full_name", "Người dùng"));
            // Advanced Search returns profile_id; retain legacy aliases for older payloads.
            String uid = user.optString("profile_id", user.optString("id", user.optString("uid", "")));
            String avatarAsset = user.optString("avatar_asset_id", null);
            String preset = user.optString("avatar_preset", "neutral");

            holder.name.setText(name);
            holder.detail.setText(user.optString("bio", "Xem trang cá nhân"));
            holder.time.setVisibility(View.GONE);
            holder.unread.setVisibility(View.GONE);

            AvatarViews.load(holder.avatar, Dto.asset(avatarAsset), preset);

            holder.itemView.setOnClickListener(v -> {
                if (!uid.isEmpty()) {
                    startActivity(new Intent(requireContext(), ProfileActivity.class).putExtra("target_uid", uid));
                }
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        class Holder extends RecyclerView.ViewHolder {
            final ShapeableImageView avatar;
            final TextView name, detail, time;
            final View unread;
            Holder(View v) {
                super(v);
                avatar = v.findViewById(R.id.ivAvatar);
                name = v.findViewById(R.id.tvChatName);
                detail = v.findViewById(R.id.tvLastMessage);
                time = v.findViewById(R.id.tvTimestamp);
                unread = v.findViewById(R.id.viewUnread);
            }
        }
    }
}
