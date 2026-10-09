package com.example.meetomxh.adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.ItemCreatePostBinding;
import com.example.meetomxh.databinding.ItemPostBinding;
import com.example.meetomxh.databinding.ItemStoryListBinding;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.models.Story;

import java.util.ArrayList;
import java.util.List;

public class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_CREATE_POST = 0;
    public static final int TYPE_STORIES = 1;
    public static final int TYPE_POST = 2;

    public interface OnPostClickListener {
        void onLikeClick(Post post);
        void onCommentClick(Post post);
        void onCreatePostClick();
    }

    private final List<Post> postList = new ArrayList<>();
    private final List<Story> storyList = new ArrayList<>();
    private final OnPostClickListener listener;

    public FeedAdapter(OnPostClickListener listener) {
        this.listener = listener;
        initDummyStories();
    }

    private void initDummyStories() {
        storyList.add(new Story("s0", "Tạo tin", "", "", true));
        storyList.add(new Story("s1", "Hoàng Long", "https://i.pravatar.cc/150?img=11", "https://picsum.photos/id/1011/400/600", false));
        storyList.add(new Story("s2", "Nguyễn Mỹ Anh", "https://i.pravatar.cc/150?img=5", "https://picsum.photos/id/1025/400/600", false));
        storyList.add(new Story("s3", "Trịnh Văn E", "https://i.pravatar.cc/150?img=8", "https://picsum.photos/id/1035/400/600", false));
        storyList.add(new Story("s4", "Lê Yến Vy", "https://i.pravatar.cc/150?img=9", "https://picsum.photos/id/1040/400/600", false));
    }

    public void setPosts(List<Post> posts) {
        this.postList.clear();
        if (posts != null) {
            this.postList.addAll(posts);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return TYPE_CREATE_POST;
        if (position == 1) return TYPE_STORIES;
        return TYPE_POST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_CREATE_POST) {
            ItemCreatePostBinding binding = ItemCreatePostBinding.inflate(inflater, parent, false);
            return new CreatePostViewHolder(binding);
        } else if (viewType == TYPE_STORIES) {
            ItemStoryListBinding binding = ItemStoryListBinding.inflate(inflater, parent, false);
            return new StoriesViewHolder(binding);
        } else {
            ItemPostBinding binding = ItemPostBinding.inflate(inflater, parent, false);
            return new PostViewHolder(binding);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof CreatePostViewHolder) {
            ((CreatePostViewHolder) holder).bind(listener);
        } else if (holder instanceof StoriesViewHolder) {
            ((StoriesViewHolder) holder).bind(storyList);
        } else if (holder instanceof PostViewHolder) {
            // Position in postList is position - 2
            int postIndex = position - 2;
            if (postIndex >= 0 && postIndex < postList.size()) {
                ((PostViewHolder) holder).bind(postList.get(postIndex), listener);
            }
        }
    }

    @Override
    public int getItemCount() {
        // 2 header items (Create Post & Stories) + postList.size()
        return 2 + postList.size();
    }

    // ViewHolder 1: Create Post
    public static class CreatePostViewHolder extends RecyclerView.ViewHolder {
        private final ItemCreatePostBinding binding;

        public CreatePostViewHolder(@NonNull ItemCreatePostBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(OnPostClickListener listener) {
            View.OnClickListener clickListener = v -> {
                if (listener != null) listener.onCreatePostClick();
            };
            binding.tvWhatOnYourMind.setOnClickListener(clickListener);
            binding.btnCreatePostPhoto.setOnClickListener(clickListener);
        }
    }

    // ViewHolder 2: Stories
    public static class StoriesViewHolder extends RecyclerView.ViewHolder {
        private final ItemStoryListBinding binding;

        public StoriesViewHolder(@NonNull ItemStoryListBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(List<Story> stories) {
            StoryAdapter adapter = new StoryAdapter();
            binding.rvStories.setLayoutManager(new LinearLayoutManager(binding.getRoot().getContext(), RecyclerView.HORIZONTAL, false));
            binding.rvStories.setAdapter(adapter);
            adapter.setStories(stories);
        }
    }

    // ViewHolder 3: Post
    public static class PostViewHolder extends RecyclerView.ViewHolder {
        private final ItemPostBinding binding;

        public PostViewHolder(@NonNull ItemPostBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Post post, OnPostClickListener listener) {
            binding.tvUserName.setText(post.getUserName());
            binding.tvTimeAgo.setText(post.getTimeAgo());
            binding.tvContent.setText(post.getContent());

            binding.tvLikesCount.setText(post.getLikesCount() + " lượt thích");
            binding.tvCommentsCount.setText(post.getCommentsCount() + " bình luận");

            // Avatar
            if (post.getUserAvatar() != null && !post.getUserAvatar().isEmpty()) {
                Glide.with(binding.ivAvatar.getContext())
                        .load(post.getUserAvatar())
                        .placeholder(R.drawable.ic_home)
                        .error(R.drawable.ic_home)
                        .into(binding.ivAvatar);
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_home);
            }

            // Post Image
            if (post.getPostImage() != null && !post.getPostImage().isEmpty()) {
                binding.ivPostImage.setVisibility(View.VISIBLE);
                Glide.with(binding.ivPostImage.getContext())
                        .load(post.getPostImage())
                        .placeholder(R.drawable.ic_home)
                        .error(R.drawable.ic_home)
                        .into(binding.ivPostImage);
            } else {
                binding.ivPostImage.setVisibility(View.GONE);
            }

            // Like status
            if (post.isLiked()) {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_filled);
                binding.tvLikeText.setTextColor(Color.parseColor("#1877F2"));
            } else {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_outline);
                binding.tvLikeText.setTextColor(Color.parseColor("#65676B"));
            }

            // Pop/Bounce Animation on Like button
            binding.btnLike.setOnClickListener(v -> {
                binding.ivLikeIcon.animate()
                        .scaleX(1.4f)
                        .scaleY(1.4f)
                        .setDuration(150)
                        .withEndAction(() -> binding.ivLikeIcon.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(120)
                                .start())
                        .start();

                if (listener != null) {
                    listener.onLikeClick(post);
                }
            });

            binding.btnComment.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCommentClick(post);
                }
            });
        }
    }
}
