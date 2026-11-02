package com.example.meetomxh.adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;

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
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;

import java.util.ArrayList;
import java.util.List;

public class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_CREATE_POST = 0;
    public static final int TYPE_STORIES = 1;
    public static final int TYPE_POST = 2;

    public interface OnPostClickListener {
        void onLikeClick(Post post, Runnable completed);
        void onCommentClick(Post post);
        void onShareClick(Post post);
        void onCreatePostClick();
        void onAuthorClick(Post post);
        void onDeletePostClick(Post post);
    }

    private final List<Post> postList = new ArrayList<>();
    private final List<Story> storyList = new ArrayList<>();
    private final OnPostClickListener listener;

    public FeedAdapter(OnPostClickListener listener) {
        this.listener = listener;
        initUnsupportedStories();
    }

    private void initUnsupportedStories(){storyList.add(new Story("disabled","Stories chưa hỗ trợ","","",true));}

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
            int postIndex = position - 2;
            if (postIndex >= 0 && postIndex < postList.size()) {
                ((PostViewHolder) holder).bind(postList.get(postIndex), listener);
            }
        }
    }

    @Override
    public int getItemCount() {
        return 2 + postList.size();
    }

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
            binding.ivCreatePostAvatar.setOnClickListener(clickListener);
        }
    }

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

    public static class PostViewHolder extends RecyclerView.ViewHolder {
        private final ItemPostBinding binding;
        private String boundPostId;

        public PostViewHolder(@NonNull ItemPostBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Post post, OnPostClickListener listener) {
            boundPostId = post.getPostId();
            binding.btnLike.setEnabled(true);
            binding.tvUserName.setText(post.getUserName());
            binding.tvTimeAgo.setText(post.getTimeAgo() != null ? post.getTimeAgo() : "Vừa xong");
            binding.tvContent.setText(post.getContent());binding.tvContent.setOnClickListener(v->com.example.meetomxh.supabase.PostDetails.show((android.app.Activity)v.getContext(),post.getPostId()));

            binding.tvLikesCount.setText(post.getLikesCount() + " lượt thích");
            binding.tvCommentsCount.setText(post.getCommentsCount() + " bình luận");

            // Author Avatar
            if (post.getUserAvatar() != null && !post.getUserAvatar().isEmpty()) {
                Glide.with(binding.ivAvatar.getContext())
                        .applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(post.getUserAvatar())
                        .placeholder(R.drawable.ic_home)
                        .error(R.drawable.ic_home)
                        .into(binding.ivAvatar);
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_home);
            }

            View.OnClickListener authorClickListener = v -> {
                if (listener != null) listener.onAuthorClick(post);
            };
            binding.ivAvatar.setOnClickListener(authorClickListener);
            binding.tvUserName.setOnClickListener(authorClickListener);

            // Post Image
            String postImg = post.getPostImage();
            boolean isVideo = "video".equals(post.getMediaType());
            if (isVideo && postImg != null && !postImg.isEmpty()) {
                binding.ivPostImage.setVisibility(View.GONE);
                binding.videoPostPlayer.setVisibility(View.VISIBLE);
                binding.videoPostPlayer.setVideoURI(android.net.Uri.parse(postImg));
                binding.videoPostPlayer.setOnPreparedListener(player -> {
                    player.setLooping(true);
                    binding.videoPostPlayer.start();
                });
            } else if (postImg != null && !postImg.isEmpty()) {
                binding.videoPostPlayer.stopPlayback();
                binding.videoPostPlayer.setVisibility(View.GONE);
                binding.ivPostImage.setVisibility(View.VISIBLE);
                Glide.with(binding.ivPostImage.getContext())
                        .applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(postImg)
                        .placeholder(R.drawable.ic_home)
                        .error(R.drawable.ic_home)
                        .into(binding.ivPostImage);
            } else {
                binding.videoPostPlayer.stopPlayback();
                binding.videoPostPlayer.setVisibility(View.GONE);
                binding.ivPostImage.setVisibility(View.GONE);
            }

            // Like Status
            if (post.isLiked()) {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_filled);
                binding.tvLikeText.setTextColor(Color.parseColor("#1877F2"));
            } else {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_outline);
                binding.tvLikeText.setTextColor(Color.parseColor("#65676B"));
            }

            // Like Animation
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
                    binding.btnLike.setEnabled(false);
                    String requestedPostId = post.getPostId();
                    listener.onLikeClick(post, () -> {
                        if (requestedPostId.equals(boundPostId)) binding.btnLike.setEnabled(true);
                    });
                }
            });

            binding.btnLike.setOnLongClickListener(v->{new android.app.AlertDialog.Builder(v.getContext()).setItems(new String[]{"Thích","Yêu thích","Thương thương","Haha","Wow","Buồn","Phẫn nộ","Bỏ cảm xúc"},(d,i)->com.example.meetomxh.repositories.FeedRepository.getInstance().setReaction(post.getPostId(),i==7?null:new String[]{"like","love","care","haha","wow","sad","angry"}[i],new com.example.meetomxh.repositories.FeedRepository.OnPostActionListener(){public void onSuccess(String m){}public void onFailure(String e){android.widget.Toast.makeText(v.getContext(),e,android.widget.Toast.LENGTH_LONG).show();}})).show();return true;}); binding.ivPostImage.setOnClickListener(v->{if(post.getImageUrls().isEmpty())return;String[] labels=new String[post.getImageUrls().size()];for(int i=0;i<labels.length;i++)labels[i]="Ảnh/video "+(i+1);new android.app.AlertDialog.Builder(v.getContext()).setItems(labels,(d,i)->v.getContext().startActivity(new android.content.Intent(v.getContext(),com.example.meetomxh.ChatImageViewerActivity.class).putExtra("image_url",post.getImageUrls().get(i)))).show();}); binding.btnComment.setOnClickListener(v -> {
                if (listener != null) listener.onCommentClick(post);
            });

            binding.btnShare.setOnClickListener(v -> {
                if (listener != null) listener.onShareClick(post);
            });

            // More Options Popup Menu
            binding.ibMore.setOnClickListener(v -> {
                SessionUser currentUser = SessionManager.getInstance().getCurrentUser();
                if (currentUser != null && currentUser.getUid().equals(post.getAuthorUid())) {
                    PopupMenu popup = new PopupMenu(v.getContext(), v);
                    popup.getMenu().add("Xóa bài viết"); popup.getMenu().add(0,101,0,"Chỉnh sửa bài viết");
                    popup.setOnMenuItemClickListener(item -> { if(item.getItemId()==101){v.getContext().startActivity(new android.content.Intent(v.getContext(),com.example.meetomxh.CreatePostActivity.class).putExtra("post_id",post.getPostId()));return true;}
                        if ("Xóa bài viết".equals(item.getTitle().toString())) {
                            if (listener != null) listener.onDeletePostClick(post);
                            return true;
                        }
                        return false;
                    });
                    popup.show();
                }
            });
        }
    }
}
