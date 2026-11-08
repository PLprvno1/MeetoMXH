package com.example.meetomxh.adapters;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.PostMediaViewerActivity;
import com.example.meetomxh.supabase.PostViewerUpdates;
import com.example.meetomxh.CreatePostActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.ReportContentActivity;
import com.example.meetomxh.databinding.ItemCreatePostBinding;
import com.example.meetomxh.databinding.ItemPostBinding;
import com.example.meetomxh.databinding.ItemStoryListBinding;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.models.Story;
import com.example.meetomxh.repositories.FeedRepository;
import com.example.meetomxh.supabase.PostDetails;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;
import com.example.meetomxh.ui.AvatarViews;
import com.example.meetomxh.ui.MentionText;
import com.example.meetomxh.ui.ReactionBarPopup;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.storage.SupabaseStorage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final LruCache<String, Bitmap> VIDEO_THUMBNAILS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    public static final int TYPE_CREATE_POST = 0;
    public static final int TYPE_STORIES = 1;
    public static final int TYPE_POST = 2;

    public interface OnPostClickListener {
        void onLikeClick(Post post, Runnable completed);
        default void onReactionSelected(Post post, String reaction, ReactionCallback callback) {
            FeedRepository.getInstance().setReaction(post.getPostId(), reaction, new FeedRepository.OnPostActionListener() {
                @Override public void onSuccess(String message) { callback.onResult(true, null); }
                @Override public void onFailure(String error) { callback.onResult(false, error); }
            });
        }
        void onCommentClick(Post post);
        void onShareClick(Post post);
        void onCreatePostClick();
        void onAuthorClick(Post post);
        void onDeletePostClick(Post post);
        default void onSavePostClick(Post post, boolean save, Runnable completed) { completed.run(); }
        default void onPinPostClick(Post post) { }
        default void onMemoryVisibilityClick(Post post, boolean hide, Runnable completed) { completed.run(); }
    }
    public interface ReactionCallback { void onResult(boolean success, String error); }

    private final List<Post> postList = new ArrayList<>();
    private final List<Story> storyList = new ArrayList<>();
    private final OnPostClickListener listener;
    private final boolean showFeedHeader;
    private final boolean memoryMode;
    private final boolean hiddenMemoryMode;
    private boolean showPersonFallback = true;
    private static final Object ENGAGEMENT_PAYLOAD=new Object();
    private final java.util.Map<String,Long> viewerVersions=new java.util.HashMap<>();
    private androidx.lifecycle.LifecycleOwner viewerOwner;
    private final androidx.lifecycle.Observer<Long> viewerObserver=ignored->applyViewerUpdates();
    @Override public void onAttachedToRecyclerView(@NonNull RecyclerView recycler){
        super.onAttachedToRecyclerView(recycler);android.content.Context context=recycler.getContext();
        while(context instanceof android.content.ContextWrapper&&!(context instanceof androidx.lifecycle.LifecycleOwner))context=((android.content.ContextWrapper)context).getBaseContext();
        if(context instanceof androidx.lifecycle.LifecycleOwner){viewerOwner=(androidx.lifecycle.LifecycleOwner)context;PostViewerUpdates.changes.observe(viewerOwner,viewerObserver);}
    }
    @Override public void onDetachedFromRecyclerView(@NonNull RecyclerView recycler){PostViewerUpdates.changes.removeObserver(viewerObserver);viewerOwner=null;super.onDetachedFromRecyclerView(recycler);}
    private void applyViewerUpdates(){
        for(PostViewerUpdates.Event event:PostViewerUpdates.events()){
            if(viewerVersions.getOrDefault(event.id,0L)>=event.revision)continue;viewerVersions.put(event.id,event.revision);
            for(int i=0;i<postList.size();i++)if(event.id.equals(postList.get(i).getPostId())){
                if(event.deleted){postList.remove(i);notifyItemRemoved(getHeaderOffset()+i);}else{event.apply(postList.get(i));if(event.engagementOnly)notifyItemChanged(getHeaderOffset()+i,ENGAGEMENT_PAYLOAD);else notifyItemChanged(getHeaderOffset()+i);}break;
            }
        }
    }

    public FeedAdapter(OnPostClickListener listener) {
        this(listener, true);
    }

    /** Reuses the feed post card for profile timelines without create-post/story headers. */
    public FeedAdapter(OnPostClickListener listener, boolean showFeedHeader) {
        this(listener, showFeedHeader, false, false);
    }

    public FeedAdapter(OnPostClickListener listener, boolean showFeedHeader, boolean memoryMode, boolean hiddenMemoryMode) {
        this.listener = listener;
        this.showFeedHeader = showFeedHeader;
        this.memoryMode = memoryMode;
        this.hiddenMemoryMode = hiddenMemoryMode;
    }

    public void setShowPersonFallback(boolean show) { showPersonFallback = show; }

    public void refreshStories() {
        com.example.meetomxh.supabase.Api.run(() -> {
            org.json.JSONObject me=com.example.meetomxh.supabase.ProfileClient.self();
            List<Story> rows=com.example.meetomxh.supabase.StoryClient.list(null);
            List<Story> out=new ArrayList<>(); Story add=new Story("create_story", "Tin của bạn", com.example.meetomxh.supabase.Dto.asset(com.example.meetomxh.supabase.Dto.nullable(me,"avatar_asset_id")), "", true);
            add.setAvatarPreset(me.optString("avatar_preset","neutral")); out.add(add);
            String self=com.example.meetomxh.supabase.SessionManager.getInstance().uid();add.setAuthorId(self);
            java.util.LinkedHashMap<String,Story> latestByAuthor=new java.util.LinkedHashMap<>();
            for(Story s:rows){
                if(self.equals(s.getAuthorId())){
                    if(!com.example.meetomxh.supabase.StoryClient.validId(add.getId())){add.setId(s.getId());add.setExpiresAt(s.getExpiresAt());add.setCreatedAt(s.getCreatedAt());}
                }else latestByAuthor.putIfAbsent(s.getAuthorId(),s);
            }
            out.addAll(latestByAuthor.values());
            return out;
        },new com.example.meetomxh.supabase.Api.Callback<List<Story>>() {
            public void success(List<Story> rows){setStoryList(rows);}
            public void error(String e){ }
        });
    }
    private int getHeaderOffset() { return showFeedHeader ? 1 : 0; }
    private void setStoryList(List<Story> rows) { storyList.clear(); if(rows!=null)storyList.addAll(rows); if(showFeedHeader) notifyItemChanged(0); }

    public void updateCommentCount(String id,int count){for(int i=0;i<postList.size();i++)if(id.equals(postList.get(i).getPostId())){postList.get(i).setCommentsCount(count);notifyItemChanged(getHeaderOffset()+i);break;}}
    public void setPosts(List<Post> posts) {
        List<Post> next = posts == null ? java.util.Collections.emptyList() : posts;
        int oldSize = postList.size();
        int shared = Math.min(oldSize, next.size());
        boolean sameOrder = true;
        for (int i = 0; i < shared; i++) {
            if (!java.util.Objects.equals(postList.get(i).getPostId(), next.get(i).getPostId())) {
                sameOrder = false;
                break;
            }
        }
        if (sameOrder && next.size() >= oldSize) {
            for (int i = 0; i < oldSize; i++) {
                if (postList.get(i) != next.get(i)) {
                    postList.set(i, next.get(i));
                    notifyItemChanged(getHeaderOffset() + i);
                }
            }
            if (next.size() > oldSize) {
                postList.addAll(next.subList(oldSize, next.size()));
                notifyItemRangeInserted(getHeaderOffset() + oldSize, next.size() - oldSize);
            }
            return;
        }
        this.postList.clear();
        this.postList.addAll(next);
        notifyDataSetChanged();
    }

    public void appendPosts(List<Post> posts) {
        if (posts == null || posts.isEmpty()) return;
        int start = postList.size();
        postList.addAll(posts);
        notifyItemRangeInserted(getHeaderOffset() + start, posts.size());
    }

    public void removePost(String id) {
        for (int i=0;i<postList.size();i++) if (id.equals(postList.get(i).getPostId())) {
            postList.remove(i); notifyItemRemoved(getHeaderOffset() + i); return;
        }
    }
    public int positionOfPost(String id) { for(int i=0;i<postList.size();i++)if(java.util.Objects.equals(id,postList.get(i).getPostId()))return i + getHeaderOffset(); return -1; }
    public String getPostIdAtAdapterPosition(int position){int i=position - getHeaderOffset();return i<0||i>=postList.size()?null:postList.get(i).getPostId();}

    @Override
    public int getItemViewType(int position) {
        if (showFeedHeader && position == 0) return TYPE_STORIES;
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
            return new PostViewHolder(binding, memoryMode, hiddenMemoryMode);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position,@NonNull List<Object> payloads){
        int index=position-getHeaderOffset();
        if(holder instanceof PostViewHolder&&index>=0&&index<postList.size()&&!payloads.isEmpty()){
            boolean engagementOnly=true;for(Object payload:payloads)if(payload!=ENGAGEMENT_PAYLOAD){engagementOnly=false;break;}
            if(engagementOnly){((PostViewHolder)holder).bindEngagement(postList.get(index));return;}
        }
        onBindViewHolder(holder,position);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof StoriesViewHolder) {
            ((StoriesViewHolder) holder).bind(storyList);
        } else if (holder instanceof PostViewHolder) {
            int postIndex = position - getHeaderOffset();
            if (postIndex >= 0 && postIndex < postList.size()) {
                ((PostViewHolder) holder).bind(postList.get(postIndex), listener, showPersonFallback);
            }
        }
    }

    @Override
    public int getItemCount() {
        return getHeaderOffset() + postList.size();
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof PostViewHolder) {
            ((PostViewHolder) holder).cleanupAnimations();
        }
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
        private final boolean memoryMode;
        private final boolean hiddenMemoryMode;
        private String boundPostId;

        public PostViewHolder(@NonNull ItemPostBinding binding, boolean memoryMode, boolean hiddenMemoryMode) {
            super(binding.getRoot());
            this.binding = binding;
            this.memoryMode = memoryMode;
            this.hiddenMemoryMode = hiddenMemoryMode;
        }

        private void playDoubleTapHeartAnimation(ImageView overlay) {
            if (overlay == null) return;
            overlay.setVisibility(View.VISIBLE);
            overlay.setScaleX(0f);
            overlay.setScaleY(0f);
            overlay.setAlpha(0.95f);
            overlay.setTranslationY(0f);
            overlay.animate().scaleX(1.3f).scaleY(1.3f).alpha(1f).setDuration(180)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f))
                .withEndAction(() -> overlay.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80)
                    .withEndAction(() -> overlay.animate().scaleX(1.3f).scaleY(1.3f).alpha(0f).translationY(-40f).setDuration(240).setStartDelay(180)
                        .withEndAction(() -> {
                            overlay.setVisibility(View.GONE);
                            overlay.setTranslationY(0f);
                        }).start()
                    ).start()
                ).start();
        }

        public void cleanupAnimations() {
            if (binding != null) {
                if (binding.ivHeartOverlay != null) {
                    binding.ivHeartOverlay.animate().cancel();
                    binding.ivHeartOverlay.setVisibility(View.GONE);
                }
                if (binding.ivLikeIcon != null) binding.ivLikeIcon.animate().cancel();
                if (binding.btnBookmark != null) binding.btnBookmark.animate().cancel();
            }
        }

        private void chooseReaction(Post post, String requested, OnPostClickListener listener) {
            if (listener == null || post == null || post.getPostId() == null || !binding.btnLike.isEnabled()) return;
            String old = post.getReaction();
            int oldCount = post.getLikesCount();
            boolean previouslyLiked = post.isLiked();
            boolean nowLiked = (requested != null && !requested.isEmpty());

            if (previouslyLiked != nowLiked) {
                post.setLikesCount(Math.max(0, oldCount + (nowLiked ? 1 : -1)));
            }
            post.setReaction(requested);
            renderReaction(post);
            binding.tvLikesCount.setText(post.getLikesCount() + " lượt thích");

            binding.btnLike.setEnabled(false);
            String id = post.getPostId();
            listener.onReactionSelected(post, requested, (success, error) -> {
                if (!success) {
                    post.setReaction(old);
                    post.setLikesCount(oldCount);
                }
                if (id.equals(boundPostId)) {
                    binding.btnLike.setEnabled(true);
                    renderReaction(post);
                    binding.tvLikesCount.setText(post.getLikesCount() + " lượt thích");
                }
                if (!success && error != null) {
                    Toast.makeText(binding.getRoot().getContext(), error, Toast.LENGTH_LONG).show();
                }
            });
        }

        private void renderReaction(Post post) {
            boolean isLiked = post.isLiked();
            if (isLiked) {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_filled);
                binding.ivLikeIcon.setColorFilter(0xFFED4956); // Instagram red
                binding.btnLike.setContentDescription("Đã thích. Nhấn để bỏ thích.");
            } else {
                binding.ivLikeIcon.setImageResource(R.drawable.ic_like_outline);
                int primaryColor = binding.getRoot().getContext().getColor(R.color.text_primary);
                binding.ivLikeIcon.setColorFilter(primaryColor);
                binding.btnLike.setContentDescription("Thích bài viết");
            }
            binding.tvLikeText.setVisibility(View.GONE);
        }
        private void bindEngagement(Post post){
            renderReaction(post);
            binding.tvLikesCount.setText(post.getLikesCount()+" lượt thích");
            binding.tvCommentsCount.setText(post.getCommentsCount()+" bình luận");
            binding.btnBookmark.setImageResource(post.isSaved()?R.drawable.ic_bookmark:R.drawable.ic_bookmark_outline);
            binding.btnBookmark.setContentDescription(post.isSaved()?"Bỏ lưu bài viết":"Lưu bài viết");
        }
        public void bind(Post post, OnPostClickListener listener, boolean showPersonFallback) {
            boundPostId = post.getPostId();
            binding.btnLike.setEnabled(true);
            binding.tvUserName.setText(post.getUserName());
            String timestamp = memoryMode && post.getTimeAgo() != null ? post.getTimeAgo() : com.example.meetomxh.ui.PostTimestampFormatter.format(post.serverCreatedAt);
            String audience = "group".equals(post.getVisibility()) ? "Nh\u00f3m" : "friends".equals(post.getVisibility()) ? "B\u1ea1n b\u00e8"
                    : "private".equals(post.getVisibility()) ? "Ch\u1ec9 m\u00ecnh t\u00f4i" : "C\u00f4ng khai";
            String groupStatus="pending_review".equals(post.getPostState())?" · Đang chờ duyệt":"";
            binding.tvTimeAgo.setText(timestamp + " \u00b7 " + audience + groupStatus);
            MentionText.bindPost(binding.tvContent,post.getContent(),post.getMentions(),post.getTags());
            binding.tvContent.setOnClickListener(v -> PostDetails.show((android.app.Activity) v.getContext(), post.getPostId()));
            com.example.meetomxh.music.MusicAttribution.bind(binding.getRoot().getContext(),binding.tvMusicMetadata,post.getMusicMetadata());
            binding.tvTaggedPeople.setText("");binding.tvTaggedPeople.setVisibility(View.GONE);binding.tvTaggedPeople.setOnClickListener(null);
            bindSharedPost(post, showPersonFallback);

            binding.tvLikesCount.setText(post.getLikesCount() + " lượt thích");
            binding.tvCommentsCount.setText(post.getCommentsCount() + " bình luận");

            if (showPersonFallback) AvatarViews.load(binding.ivAvatar, post.getUserAvatar(), post.getUserAvatarPreset());
            else AvatarViews.loadWithoutPersonFallback(binding.ivAvatar, post.getUserAvatar());

            View.OnClickListener authorClickListener = v -> {
                if (listener != null) listener.onAuthorClick(post);
            };
            binding.ivAvatar.setOnClickListener(authorClickListener);
            com.example.meetomxh.ui.StoryRings.bind(binding.ivAvatar,binding.ivAvatar,post.getAuthorUid(),authorClickListener);
            binding.tvUserName.setOnClickListener(authorClickListener);

            // Post Image
            String postImg = post.getPostImage();
            boolean isVideo = "video".equals(post.getMediaType());
            if (isVideo && postImg != null && !postImg.isEmpty()) {
                binding.ivPostImage.setVisibility(View.GONE);
                binding.videoPostPlayer.setVisibility(View.VISIBLE);
                binding.videoPostPlayer.setContentDescription("Mở video");
                binding.ivVideoThumbnail.setImageDrawable(null);
                if (memoryMode) {
                    binding.ivVideoThumbnail.setImageResource(R.drawable.ic_memories);
                    binding.progressVideoThumbnail.setVisibility(View.GONE);
                } else {
                    binding.progressVideoThumbnail.setVisibility(View.VISIBLE);
                    String cacheKey = SessionManager.getInstance().uid() + ":" + postImg;
                    Bitmap cached = VIDEO_THUMBNAILS.get(cacheKey);
                    if (cached != null) {
                        binding.ivVideoThumbnail.setImageBitmap(cached);
                        binding.progressVideoThumbnail.setVisibility(View.GONE);
                    } else {
                        Api.run(() -> SupabaseStorage.videoThumbnail(postImg), new Api.Callback<Bitmap>() {
                            @Override public void success(Bitmap frame) {
                                if (frame == null) return;
                                VIDEO_THUMBNAILS.put(cacheKey, frame);
                                if (post.getPostId().equals(boundPostId)) {
                                    binding.ivVideoThumbnail.setImageBitmap(frame);
                                    binding.progressVideoThumbnail.setVisibility(View.GONE);
                                }
                            }
                            @Override public void error(String error) {
                                if (post.getPostId().equals(boundPostId)) binding.progressVideoThumbnail.setVisibility(View.GONE);
                            }
                        });
                    }
                }
                View.OnClickListener play = v -> openMediaViewer(v, post);
                binding.videoPostPlayer.setOnClickListener(play);
                binding.btnVideoPlay.setOnClickListener(play);
            } else if (postImg != null && !postImg.isEmpty()) {
                binding.videoPostPlayer.setVisibility(View.GONE);
                binding.ivPostImage.setVisibility(View.VISIBLE);
                if (memoryMode) {
                    Glide.with(binding.ivPostImage.getContext()).clear(binding.ivPostImage);
                    binding.ivPostImage.setImageResource(R.drawable.ic_memories);
                    binding.ivPostImage.setContentDescription("Ảnh kỷ niệm — chạm để mở");
                } else {
                    Glide.with(binding.ivPostImage.getContext())
                            .applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true))
                            .load(postImg)
                            .placeholder(R.drawable.ic_media_error)
                            .error(R.drawable.ic_media_error)
                            .into(binding.ivPostImage);
                }
            } else {
                binding.videoPostPlayer.setVisibility(View.GONE);
                binding.ivPostImage.setVisibility(View.GONE);
            }

            renderReaction(post);
            binding.btnLike.setOnClickListener(v -> {
                boolean nextIsLiked = !post.isLiked();
                if (nextIsLiked) {
                    binding.ivLikeIcon.animate().scaleX(1.3f).scaleY(1.3f).setInterpolator(new android.view.animation.OvershootInterpolator(3.0f)).setDuration(180)
                            .withEndAction(() -> binding.ivLikeIcon.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
                } else {
                    binding.ivLikeIcon.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100)
                            .withEndAction(() -> binding.ivLikeIcon.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
                }
                chooseReaction(post, nextIsLiked ? "like" : null, listener);
            });
            binding.btnLike.setOnLongClickListener(v -> {
                new ReactionBarPopup(v.getContext()).show(binding.btnLike, type -> chooseReaction(post, type, listener));
                return true;
            });

            // Double tap gesture detector for media (photo or video)
            android.view.GestureDetector mediaGestureDetector = new android.view.GestureDetector(
                binding.getRoot().getContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        // Claim ACTION_DOWN so non-clickable ImageViews continue
                        // receiving MOVE/UP and can complete single/double taps.
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                        openMediaViewer(binding.getRoot(), post);
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(android.view.MotionEvent e) {
                        playDoubleTapHeartAnimation(binding.ivHeartOverlay);
                        if (!post.isLiked()) {
                            binding.ivLikeIcon.animate().scaleX(1.3f).scaleY(1.3f).setInterpolator(new android.view.animation.OvershootInterpolator(3.0f)).setDuration(180)
                                    .withEndAction(() -> binding.ivLikeIcon.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
                            chooseReaction(post, "like", listener);
                        }
                        return true;
                    }
                }
            );

            binding.ivPostImage.setOnTouchListener((v, event) -> mediaGestureDetector.onTouchEvent(event));
            binding.videoPostPlayer.setOnTouchListener((v, event) -> mediaGestureDetector.onTouchEvent(event));

            // Bookmark / Save button binding
            binding.btnBookmark.setImageResource(post.isSaved() ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
            binding.btnBookmark.setContentDescription(post.isSaved() ? "Bỏ lưu bài viết" : "Lưu bài viết");
            binding.btnBookmark.setOnClickListener(v -> {
                boolean desired = !post.isSaved();
                binding.btnBookmark.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100)
                        .withEndAction(() -> binding.btnBookmark.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
                post.setSaved(desired);
                binding.btnBookmark.setImageResource(desired ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
                binding.btnBookmark.setContentDescription(desired ? "Bỏ lưu bài viết" : "Lưu bài viết");
                if (listener != null) {
                    binding.btnBookmark.setEnabled(false);
                    listener.onSavePostClick(post, desired, () -> {
                        if (post.getPostId().equals(boundPostId)) {
                            binding.btnBookmark.setEnabled(true);
                            binding.btnBookmark.setImageResource(post.isSaved() ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
                        }
                    });
                }
            });

            binding.btnComment.setOnClickListener(v -> {
                if (listener != null) listener.onCommentClick(post);
            });

            binding.btnShare.setOnClickListener(v -> {
                if (listener != null) listener.onShareClick(post);
            });
            binding.btnShare.setVisibility(memoryMode && !post.canShareMemory ? View.GONE : View.VISIBLE);

            // More Options Popup Menu
            binding.ibMore.setOnClickListener(v -> {
                SessionUser currentUser = SessionManager.getInstance().getCurrentUser();
                PopupMenu popup = new PopupMenu(v.getContext(), v);
                if (!memoryMode && currentUser != null && currentUser.getUid().equals(post.getAuthorUid())) {
                    popup.getMenu().add(0, 100, 0, "Xóa bài viết");
                    if (post.getGroupId() == null) { popup.getMenu().add(0, 101, 1, "Chỉnh sửa bài viết"); popup.getMenu().add(0, 105, 2, "Ghim lên trang cá nhân"); }
                }
                if (!memoryMode) popup.getMenu().add(0, 102, 2, "Báo cáo bài viết");
                popup.getMenu().add(0, 103, 2, post.isSaved() ? "Bỏ lưu bài viết" : "Lưu bài viết");
                if (memoryMode) popup.getMenu().add(0, 104, 3, hiddenMemoryMode ? "Ẩn kỷ niệm" : "Ẩn kỷ niệm");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 100) {
                        if (listener != null) listener.onDeletePostClick(post);
                        return true;
                    }
                    if (item.getItemId() == 101) {
                        v.getContext().startActivity(new Intent(v.getContext(), CreatePostActivity.class)
                                .putExtra("post_id", post.getPostId()));
                        return true;
                    }
                    if (item.getItemId() == 105) { if (listener != null) listener.onPinPostClick(post); return true; }
                    if (item.getItemId() == 102) {
                        v.getContext().startActivity(new Intent(v.getContext(), ReportContentActivity.class)
                                .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE, "post")
                                .putExtra(ReportContentActivity.EXTRA_TARGET_ID, post.getPostId())
                                .putExtra(ReportContentActivity.EXTRA_GROUP_ID, post.getGroupId()));
                        return true;
                    }
                    if (item.getItemId() == 103) {
                        if (listener != null) {
                            binding.ibMore.setEnabled(false);
                            listener.onSavePostClick(post, !post.isSaved(), () -> {
                                if (post.getPostId().equals(boundPostId)) binding.ibMore.setEnabled(true);
                            });
                        }
                        return true;
                    }
                    if (item.getItemId() == 104) {
                        if (listener != null) {
                            binding.ibMore.setEnabled(false);
                            listener.onMemoryVisibilityClick(post, !hiddenMemoryMode, () -> {
                                if (post.getPostId().equals(boundPostId)) binding.ibMore.setEnabled(true);
                            });
                        }
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }

        private void bindSharedPost(Post post, boolean showPersonFallback) {
            binding.cardSharedPost.setVisibility(View.GONE);
            binding.ivSharedPostMedia.setVisibility(View.GONE);
            binding.ivSharedPostMedia.setOnClickListener(null);
            if (!"share".equals(post.getType())) return;
            binding.cardSharedPost.setVisibility(View.VISIBLE);
            binding.tvSharedPostAuthor.setText("Đang tải bài viết gốc…");
            binding.tvSharedPostBody.setText("");
            String source=post.getOriginalPostId();
            if(source==null){binding.tvSharedPostAuthor.setText("Nội dung gốc không còn khả dụng với bạn.");return;}
            binding.cardSharedPost.setOnClickListener(v -> PostDetails.show((android.app.Activity)v.getContext(),source));
            com.example.meetomxh.supabase.Api.run(() -> com.example.meetomxh.supabase.RpcClient.object("get_post",
                    com.example.meetomxh.supabase.Api.obj("p_post_id",source)),new com.example.meetomxh.supabase.Api.Callback<org.json.JSONObject>() {
                @Override public void success(org.json.JSONObject original) {
                    if(!post.getPostId().equals(boundPostId))return;
                    org.json.JSONObject author=original.optJSONObject("author");
                    binding.tvSharedPostAuthor.setText(author==null?"Người dùng Meeto":author.optString("display_name","Người dùng Meeto"));
                    String sharedAvatar=com.example.meetomxh.supabase.Dto.asset(author==null?null:com.example.meetomxh.supabase.Dto.nullable(author,"avatar_asset_id"));if(showPersonFallback)com.example.meetomxh.ui.AvatarViews.load(binding.ivSharedPostAvatar,sharedAvatar,"neutral");else com.example.meetomxh.ui.AvatarViews.loadWithoutPersonFallback(binding.ivSharedPostAvatar,sharedAvatar);
                    binding.tvSharedPostBody.setText(original.optString("body",""));
                    org.json.JSONArray media=original.optJSONArray("media");
                    if(media!=null&&media.length()>0){String ref=com.example.meetomxh.supabase.Dto.asset(media.optJSONObject(0).optString("asset_id"));binding.ivSharedPostMedia.setVisibility(View.VISIBLE);Glide.with(binding.ivSharedPostMedia).load(ref).placeholder(R.drawable.ic_media_error).error(R.drawable.ic_media_error).into(binding.ivSharedPostMedia);
                        if(!original.optBoolean("is_reel")&&media.optJSONObject(0).optString("mime_type").startsWith("image/"))binding.ivSharedPostMedia.setOnClickListener(v->PostMediaViewerActivity.open(v.getContext(),source,ref,0));
                    }
                }
                @Override public void error(String error) { if(post.getPostId().equals(boundPostId)){binding.tvSharedPostAuthor.setText("Bài viết gốc không còn khả dụng với bạn.");binding.tvSharedPostBody.setText("");} }
            });
        }

        private void openMediaViewer(View view, Post post) {
            if (post.getImageUrls() == null || post.getImageUrls().isEmpty()) return;
            if ("video".equals(post.getMediaType())) {
                com.example.meetomxh.supabase.PostDetails.video(view.getContext(),post.getPostId());
                return;
            }
            PostMediaViewerActivity.open(view.getContext(),post.getPostId(),post.getPostImage(),Math.max(0,post.getImageUrls().indexOf(post.getPostImage())));
        }
    }
}
