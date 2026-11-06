package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.ItemPostBinding;
import com.example.meetomxh.models.Post;

import java.util.ArrayList;
import java.util.List;

public class PostAdapter extends RecyclerView.Adapter<PostAdapter.PostViewHolder> {

    public interface OnPostClickListener {
        void onLikeClick(Post post);
        void onCommentClick(Post post);
    }

    private final List<Post> postList = new ArrayList<>();
    private final OnPostClickListener listener;

    public PostAdapter(OnPostClickListener listener) {
        this.listener = listener;
    }

    public void setPosts(List<Post> posts) {
        this.postList.clear();
        if (posts != null) {
            this.postList.addAll(posts);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public PostViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPostBinding binding = ItemPostBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new PostViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull PostViewHolder holder, int position) {
        Post post = postList.get(position);
        holder.bind(post, listener);
    }

    @Override
    public int getItemCount() {
        return postList.size();
    }

    public static class PostViewHolder extends RecyclerView.ViewHolder {
        private final ItemPostBinding binding;

        public PostViewHolder(@NonNull ItemPostBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Post post, OnPostClickListener listener) {
            binding.tvUserName.setText(post.getUserName());
            binding.tvTimeAgo.setText(com.example.meetomxh.ui.PostTimestampFormatter.format(post.serverCreatedAt));
            binding.tvContent.setText(post.getContent());

            binding.tvLikesCount.setText(post.getLikesCount() + " lượt thích");
            binding.tvCommentsCount.setText(post.getCommentsCount() + " bình luận");

            // Xử lý avatar
            com.example.meetomxh.ui.AvatarViews.load(binding.ivAvatar,post.getUserAvatar(),post.getUserAvatarPreset());

            // Xử lý ảnh bài đăng
            if (post.getPostImage() != null && !post.getPostImage().isEmpty()) {
                binding.ivPostImage.setVisibility(View.VISIBLE);
                Glide.with(binding.ivPostImage.getContext())
                        .applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(post.getPostImage())
                        .placeholder(R.drawable.ic_media_error)
                        .error(R.drawable.ic_media_error)
                        .into(binding.ivPostImage);
            } else {
                binding.ivPostImage.setVisibility(View.GONE);
            }

            // Trạng thái nút Like
            if (post.isLiked()) {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_filled);
                binding.tvLikeText.setTextColor(ContextCompat.getColor(binding.tvLikeText.getContext(), R.color.brand_blue));
            } else {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_outline);
                binding.tvLikeText.setTextColor(ContextCompat.getColor(binding.tvLikeText.getContext(), R.color.text_secondary));
            }

            // Sự kiện click nút Like
            binding.btnLike.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onLikeClick(post);
                }
            });

            // Sự kiện click nút Bình luận
            binding.btnComment.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onCommentClick(post);
                }
            });
        }
    }
}
