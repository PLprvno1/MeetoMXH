package com.example.meetomxh;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.ActivityPostDetailsBinding;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.FeedRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.PostDetails;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SavedPostClient;
import com.example.meetomxh.supabase.ShareActions;

import org.json.JSONObject;

import java.util.Collections;

/** A single authorized source post; comments use the shared Post-ID bottom sheet. */
public final class PostDetailsActivity extends AppCompatActivity {
    private ActivityPostDetailsBinding binding;
    private FeedAdapter postAdapter;
    private String postId;
    private Post post;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding = ActivityPostDetailsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        postId = getIntent().getStringExtra(PostDetails.EXTRA_POST_ID);
        if (postId == null) { PostDetails.unavailable(this); finish(); return; }

        binding.postDetailBack.setOnClickListener(v -> finish());
        binding.postDetailPost.setLayoutManager(new LinearLayoutManager(this));
        postAdapter = new FeedAdapter(new FeedAdapter.OnPostClickListener() {
            @Override public void onLikeClick(Post item, Runnable done) {
                FeedRepository.getInstance().setReaction(item.getPostId(), item.isLiked() ? null : "like",
                        new FeedRepository.OnPostActionListener() {
                            @Override public void onSuccess(String message) { done.run(); }
                            @Override public void onFailure(String error) {
                                Toast.makeText(PostDetailsActivity.this, error, Toast.LENGTH_LONG).show();
                                done.run();
                            }
                        });
            }
            @Override public void onReactionSelected(Post item, String reaction, FeedAdapter.ReactionCallback callback) {
                Api.run(() -> RpcClient.object("set_post_reaction", Api.obj(
                        "p_post_id", item.getPostId(), "p_reaction", reaction)), new Api.Callback<JSONObject>() {
                    @Override public void success(JSONObject result) {
                        item.setReaction(Dto.nullable(result, "reaction"));
                        item.setLikesCount(result.optInt("reactions_count", item.getLikesCount()));
                        callback.onResult(true, null);
                    }
                    @Override public void error(String error) { callback.onResult(false, error); }
                });
            }
            @Override public void onCommentClick(Post item) { showComments(item); }
            @Override public void onShareClick(Post item) { ShareActions.show(PostDetailsActivity.this, item, false); }
            @Override public void onCreatePostClick() { }
            @Override public void onAuthorClick(Post item) {
                if (item.getAuthorUid() != null) startActivity(new android.content.Intent(PostDetailsActivity.this,
                        ProfileActivity.class).putExtra("target_uid", item.getAuthorUid()));
            }
            @Override public void onDeletePostClick(Post item) { }
            @Override public void onSavePostClick(Post item, boolean save, Runnable done) {
                SavedPostClient.set(item, save, new Api.Callback<Boolean>() {
                    @Override public void success(Boolean value) { item.setSaved(value); done.run(); }
                    @Override public void error(String error) {
                        item.setSaved(!save);
                        Toast.makeText(PostDetailsActivity.this, error, Toast.LENGTH_LONG).show();
                        done.run();
                    }
                });
            }
        }, false);
        binding.postDetailPost.setAdapter(postAdapter);
        loadPost();
    }

    private void loadPost() {
        binding.postDetailLoading.setVisibility(View.VISIBLE);
        Api.run(() -> PostDetails.resolve(postId), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject json) {
                if (binding == null || isFinishing()) return;
                post = Dto.post(json);
                if (post.isReel()) {
                    startActivity(new android.content.Intent(PostDetailsActivity.this, MainActivity.class)
                            .putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED, true)
                            .putExtra(PostDetails.EXTRA_REEL_ID, post.getPostId()));
                    finish();
                    return;
                }
                postId = post.getPostId();
                binding.postDetailLoading.setVisibility(View.GONE);
                postAdapter.setPosts(Collections.singletonList(post));
            }
            @Override public void error(String error) {
                if (binding == null) return;
                PostDetails.unavailable(PostDetailsActivity.this);
                binding.postDetailLoading.setVisibility(View.GONE);
                binding.postDetailMessage.setText("Nội dung không còn khả dụng");
                binding.postDetailMessage.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showComments(Post item) {
        if (item == null || isFinishing() || getSupportFragmentManager().isStateSaved()
                || getSupportFragmentManager().findFragmentByTag("PostDetailsComments") != null) return;
        CommentsBottomSheetFragment.newInstance(item.getPostId())
                .show(getSupportFragmentManager(), "PostDetailsComments");
    }

    @Override protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
