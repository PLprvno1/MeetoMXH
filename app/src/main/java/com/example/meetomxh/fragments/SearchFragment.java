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
import android.widget.LinearLayout;
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
    private PostSearchAdapter postSearchAdapter;
    private final List<JSONObject> searchUsers = new ArrayList<>();
    private final List<Post> searchPosts = new ArrayList<>();
    private int searchRequestsPending;

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
        postSearchAdapter = new PostSearchAdapter(searchPosts);
        binding.rvSearchPosts.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvSearchPosts.setAdapter(postSearchAdapter);

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
        binding.searchResultsContainer.setVisibility(View.GONE);
        binding.progressExplore.setVisibility(View.GONE);
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
        binding.searchResultsContainer.setVisibility(View.VISIBLE);
        searchUsers.clear();
        searchPosts.clear();
        searchRequestsPending = 2;
        resultAdapter.notifyDataSetChanged();
        postSearchAdapter.notifyDataSetChanged();
        binding.tvPeopleHeader.setVisibility(View.GONE);
        binding.rvSearchResults.setVisibility(View.GONE);
        binding.tvPostsHeader.setVisibility(View.GONE);
        binding.rvSearchPosts.setVisibility(View.GONE);
        binding.tvExploreStatus.setVisibility(View.GONE);

        AdvancedSearchRepository.people(query, null, null, 20, new AdvancedSearchRepository.Callback<JSONArray>() {
            @Override public void success(JSONArray rows) {
                if (!isCurrentQuery(query)) return;
                searchUsers.clear();
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject obj = rows.optJSONObject(i);
                    if (obj != null) searchUsers.add(obj);
                }
                resultAdapter.notifyDataSetChanged();
                updateSearchStatus(query, true);
            }
            @Override public void error(String error) {
                if (isCurrentQuery(query)) updateSearchStatus(query, true);
            }
        });

        AdvancedSearchRepository.posts(query, false, null, "relevance", -1f, null, null, 20,
                new AdvancedSearchRepository.Callback<AdvancedSearchRepository.PostPage>() {
                    @Override public void success(AdvancedSearchRepository.PostPage page) {
                        if (!isCurrentQuery(query)) return;
                        searchPosts.clear();
                        if (page != null && page.posts != null) searchPosts.addAll(page.posts);
                        postSearchAdapter.notifyDataSetChanged();
                        updateSearchStatus(query, true);
                    }
                    @Override public void error(String error) {
                        if (isCurrentQuery(query)) updateSearchStatus(query, true);
                    }
                });
    }

    private boolean isCurrentQuery(String query) {
        return isAdded() && binding != null && query.equals(binding.etSearchExplore.getText().toString().trim());
    }

    private void updateSearchStatus(String query, boolean requestFinished) {
        if (!isCurrentQuery(query)) return;
        if (requestFinished && searchRequestsPending > 0) searchRequestsPending--;
        boolean peopleFound = !searchUsers.isEmpty();
        boolean postsFound = !searchPosts.isEmpty();
        binding.tvPeopleHeader.setVisibility(peopleFound ? View.VISIBLE : View.GONE);
        binding.rvSearchResults.setVisibility(peopleFound ? View.VISIBLE : View.GONE);
        binding.tvPostsHeader.setVisibility(postsFound ? View.VISIBLE : View.GONE);
        binding.rvSearchPosts.setVisibility(postsFound ? View.VISIBLE : View.GONE);
        binding.progressExplore.setVisibility(searchRequestsPending > 0 ? View.VISIBLE : View.GONE);
        boolean empty = searchUsers.isEmpty() && searchPosts.isEmpty();
        binding.tvExploreStatus.setVisibility(empty && searchRequestsPending == 0 ? View.VISIBLE : View.GONE);
        if (empty && searchRequestsPending == 0) binding.tvExploreStatus.setText("No accounts or posts found for \"" + query + "\"");
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


    private class PostSearchAdapter extends RecyclerView.Adapter<PostSearchAdapter.Holder> {
        private final List<Post> items;
        PostSearchAdapter(List<Post> items) { this.items = items; }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad / 2, pad, pad / 2);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (88 * getResources().getDisplayMetrics().density)));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(image, new LinearLayout.LayoutParams((int) (72 * getResources().getDisplayMetrics().density), (int) (72 * getResources().getDisplayMetrics().density)));
            LinearLayout labels = new LinearLayout(parent.getContext());
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(pad, 0, 0, 0);
            row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView author = new TextView(parent.getContext());
            author.setTextColor(requireContext().getColor(R.color.text_primary));
            author.setTextSize(14);
            author.setTypeface(null, android.graphics.Typeface.BOLD);
            labels.addView(author);
            TextView caption = new TextView(parent.getContext());
            caption.setTextColor(requireContext().getColor(R.color.text_secondary));
            caption.setTextSize(13);
            caption.setMaxLines(2);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(caption);
            return new Holder(row, image, author, caption);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Post post = items.get(position);
            holder.author.setText(post.getUserName() == null ? "Post" : post.getUserName());
            String content = post.getContent() == null ? "" : post.getContent().trim();
            holder.caption.setText(content.isEmpty() ? "Open post" : content);
            String image = post.isReel() && post.getReelCoverAssetId() != null ? Dto.asset(post.getReelCoverAssetId()) : post.getPostImage();
            if (image == null || image.isEmpty()) AvatarViews.load(holder.image, post.getUserAvatar(), post.getUserAvatarPreset());
            else Glide.with(holder.image).load(image).centerCrop().into(holder.image);
            holder.itemView.setOnClickListener(v -> PostDetails.show(requireActivity(), post.getPostId()));
        }
        @Override public int getItemCount() { return items.size(); }
        class Holder extends RecyclerView.ViewHolder {
            final ImageView image; final TextView author, caption;
            Holder(View item, ImageView image, TextView author, TextView caption) { super(item); this.image=image; this.author=author; this.caption=caption; }
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
