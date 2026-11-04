package com.example.meetomxh;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.ActivityProfileBinding;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.models.FriendItem;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.repositories.FriendRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.ui.AvatarViews;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;
import com.google.android.material.imageview.ShapeableImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class ProfileActivity extends AppCompatActivity {

    private ActivityProfileBinding binding;
    private FriendRepository friendRepository;
    private SessionManager mAuth;

    private String targetUid;
    private boolean targetProfileExists;
    private FriendItem.RelationshipStatus currentRelationshipStatus = FriendItem.RelationshipStatus.NO_RELATION;

    private FeedAdapter timelineAdapter;
    private final List<Post> timelinePosts = new ArrayList<>();
    private String timelineAfterTime, timelineAfterId;
    private boolean timelineLoading;
    private String profileMediaKind, mediaAfterTime, mediaAfterId;
    private boolean mediaLoading;
    private final List<JSONObject> profileMedia = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        friendRepository = FriendRepository.getInstance();
        mAuth = SessionManager.getInstance();

        targetUid = getIntent().getStringExtra("target_uid");
        binding.btnPrimaryAction.setEnabled(false);
        binding.btnSecondaryAction.setEnabled(false);
        SessionUser currentUser = mAuth.getCurrentUser();

        if (targetUid == null || targetUid.isEmpty()) {
            if (currentUser != null) {
                targetUid = currentUser.getUid();
            } else {
                Toast.makeText(this, "Đóng", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnMessage.setVisibility(View.GONE);
        binding.btnMessage.setOnClickListener(v -> openChatWithTarget());

        boolean isSelf = targetUid.equals(mAuth.uid());
        binding.layoutFollowStats.setVisibility(View.GONE);
        binding.btnFollow.setVisibility(isSelf ? View.GONE : View.VISIBLE);
        binding.tvFollowers.setOnClickListener(v -> openFollowList("followers"));
        binding.tvFollowing.setOnClickListener(v -> openFollowList("Đóng"));
        binding.btnFollow.setOnClickListener(v -> toggleFollow());
        loadFollowStats();

        if (isSelf) {
            binding.cardFriendsPreview.setVisibility(View.VISIBLE);
            binding.cardCreatePost.setVisibility(View.VISIBLE);
            binding.btnChangeAvatar.setVisibility(View.VISIBLE);
            binding.btnChangeCover.setVisibility(View.VISIBLE);
            binding.btnPrivacyAction.setVisibility(View.GONE);

            binding.btnChangeAvatar.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));
            binding.btnChangeCover.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));

            binding.tvCreatePostPrompt.setOnClickListener(v -> startActivity(new Intent(this, CreatePostActivity.class)));
            binding.btnCreatePostPhoto.setOnClickListener(v -> startActivity(new Intent(this, CreatePostActivity.class)));
            binding.ivCreatePostAvatar.setOnClickListener(v -> startActivity(new Intent(this, CreatePostActivity.class)));

            binding.btnSeeAllFriends.setOnClickListener(v -> {
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("open_tab", "friends");
                startActivity(intent);
            });
            loadFriendsPreview();
        } else {
            binding.cardFriendsPreview.setVisibility(View.GONE);
            binding.cardCreatePost.setVisibility(View.GONE);
            binding.btnChangeAvatar.setVisibility(View.GONE);
            binding.btnChangeCover.setVisibility(View.GONE);
            binding.btnPrivacyAction.setVisibility(View.VISIBLE);
            binding.btnPrivacyAction.setOnClickListener(v -> showBlockDialog());
        }

        setupProfileSections();
        setupTimeline();
        loadProfilePinned();
        loadFeatured();
    }

    private void setupTimeline() {
        binding.rvTimeline.setLayoutManager(new LinearLayoutManager(this));
        binding.rvTimeline.setNestedScrollingEnabled(false);

        timelineAdapter = new FeedAdapter(new FeedAdapter.OnPostClickListener() {
            @Override
            public void onLikeClick(Post post, Runnable done) {
                String reaction = post.isLiked() ? null : "like";
                Api.run(() -> RpcClient.call("set_post_reaction", Api.obj("p_post_id", post.getPostId(), "p_reaction", reaction)), new Api.Callback<Object>() {
                    public void success(Object ignored) {
                        done.run();
                        loadTimeline(false);
                    }
                    public void error(String error) {
                        done.run();
                        toast(error);
                    }
                });
            }

            @Override
            public void onCommentClick(Post post) {
                CommentsBottomSheetFragment.newInstance(post.getPostId()).show(getSupportFragmentManager(), "ProfileComments");
            }

            @Override
            public void onShareClick(Post post) {
                com.example.meetomxh.supabase.ShareActions.show(ProfileActivity.this,post,false);
            }

            @Override
            public void onCreatePostClick() {
                startActivity(new Intent(ProfileActivity.this, CreatePostActivity.class));
            }

            @Override
            public void onAuthorClick(Post post) {
                if (post.getAuthorUid() != null) {
                    Intent i = new Intent(ProfileActivity.this, ProfileActivity.class);
                    i.putExtra("target_uid", post.getAuthorUid());
                    startActivity(i);
                }
            }

            @Override
            public void onDeletePostClick(Post post) {
                new AlertDialog.Builder(ProfileActivity.this)
                        .setTitle("Xóa bài viết?")
                        .setMessage("Bài viết sẽ được xóa theo quyền và trạng thái trên Cloud.")
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Xóa", (d, w) -> Api.run(
                                () -> RpcClient.call("delete_post", Api.obj("p_post_id", post.getPostId(), "p_expected_version", post.version)),
                                new Api.Callback<Object>() {
                                    public void success(Object x) {
                                        com.example.meetomxh.repositories.ReelsRepository.invalidate();
                                        timelineAfterTime = null;
                                        timelineAfterId = null;
                                        timelinePosts.clear();
                                        timelineAdapter.setPosts(timelinePosts);
                                        loadTimeline(false);
                                    }
                                    public void error(String e) { toast(e); }
                                }))
                        .show();
            }

            @Override public void onPinPostClick(Post post) {
                Api.run(() -> RpcClient.call("set_profile_pinned_post", Api.obj("p_post_id", post.getPostId())), new Api.Callback<Object>() {
                    public void success(Object r) { loadProfilePinned(); toast("Pinned post updated"); }
                    public void error(String e) { toast(e); }
                });
            }

            @Override public void onSavePostClick(Post post, boolean save, Runnable completed) {
                com.example.meetomxh.supabase.SavedPostClient.set(post, save, new Api.Callback<Boolean>() {
                    public void success(Boolean value) { post.setSaved(value); completed.run(); toast(value ? "Đã lưu bài viết" : "Đã bỏ lưu"); }
                    public void error(String error) { completed.run(); toast(error); }
                });
            }
        }, false);

        binding.rvTimeline.setAdapter(timelineAdapter);

        binding.btnTimelineRefresh.setOnClickListener(v -> {
            timelineAfterTime = null;
            timelineAfterId = null;
            timelinePosts.clear();
            timelineAdapter.setPosts(timelinePosts);
            loadTimeline(false);
        });

        binding.btnTimelineMore.setOnClickListener(v -> loadTimeline(true));
    }

    private void setupProfileSections() {
        binding.btnProfilePosts.setOnClickListener(v -> showProfileTab("posts"));
        binding.btnProfilePhotos.setOnClickListener(v -> showProfileTab("image"));
        binding.btnProfileVideos.setOnClickListener(v -> showProfileTab("video"));
        binding.btnProfileAbout.setOnClickListener(v -> showProfileTab("about"));
        binding.btnProfileAlbums.setOnClickListener(v -> startActivity(new Intent(this, ProfileAlbumsActivity.class).putExtra("owner_id", targetUid)));
        binding.btnProfileMediaMore.setOnClickListener(v -> loadProfileMedia(true));
        binding.rvProfileMedia.setLayoutManager(new GridLayoutManager(this, 3));
        showProfileTab("posts");
        binding.btnProfileAlbums.setVisibility(View.VISIBLE);
        binding.btnProfileUnpin.setVisibility(targetUid.equals(mAuth.uid()) ? View.VISIBLE : View.GONE);
        binding.btnAddFeatured.setVisibility(targetUid.equals(mAuth.uid()) ? View.VISIBLE : View.GONE);
        binding.btnAddFeatured.setOnClickListener(v -> chooseFeaturedPhoto());
        binding.btnProfileUnpin.setOnClickListener(v -> Api.run(() -> RpcClient.call("clear_profile_pinned_post", Api.obj()), new Api.Callback<Object>() {
            public void success(Object r) { loadProfilePinned(); }
            public void error(String e) { toast(e); }
        }));
    }

    private void showProfileTab(String tab) {
        boolean posts = "posts".equals(tab), about = "about".equals(tab), gallery = "image".equals(tab) || "video".equals(tab);
        binding.layoutTimelineSection.setVisibility(posts ? View.VISIBLE : View.GONE);
        binding.layoutProfileMedia.setVisibility(gallery ? View.VISIBLE : View.GONE);
        binding.cardPersonalDetails.setVisibility(about ? View.VISIBLE : View.GONE);
        if (gallery && !tab.equals(profileMediaKind)) {
            profileMediaKind = tab; mediaAfterTime = null; mediaAfterId = null; profileMedia.clear();
            renderProfileMedia(); loadProfileMedia(false);
        }
        binding.btnProfilePosts.setText(posts ? "Posts ✓" : "Posts");
        binding.btnProfilePhotos.setText("image".equals(tab) ? "Photos ✓" : "Photos");
        binding.btnProfileVideos.setText("video".equals(tab) ? "Videos ✓" : "Videos");
        binding.btnProfileAbout.setText(about ? "About ✓" : "About");
    }

    private void loadProfileMedia(boolean append) {
        if (mediaLoading || profileMediaKind == null) return;
        if (!append) { mediaAfterTime = null; mediaAfterId = null; profileMedia.clear(); }
        mediaLoading = true; binding.tvProfileMediaStatus.setVisibility(View.VISIBLE);
        binding.tvProfileMediaStatus.setText("Loading media…"); binding.btnProfileMediaMore.setEnabled(false);
        Api.run(() -> (JSONArray) RpcClient.call("list_profile_media", Api.obj("p_owner_id", targetUid,
                "p_kind", profileMediaKind, "p_after_time", append ? mediaAfterTime : null,
                "p_after_id", append ? mediaAfterId : null, "p_limit", 30)), new Api.Callback<JSONArray>() {
            public void success(JSONArray rows) {
                mediaLoading = false; binding.btnProfileMediaMore.setEnabled(true);
                if (rows != null) for (int i=0;i<rows.length();i++) { JSONObject row=rows.optJSONObject(i); if(row!=null) profileMedia.add(row); }
                if (rows != null && rows.length()>0) { JSONObject last=rows.optJSONObject(rows.length()-1); mediaAfterTime=last.optString("created_at",null); mediaAfterId=last.optString("asset_id",null); }
                renderProfileMedia(); binding.tvProfileMediaStatus.setVisibility(profileMedia.isEmpty()?View.VISIBLE:View.GONE);
                if (profileMedia.isEmpty()) binding.tvProfileMediaStatus.setText("No media is available to this profile viewer.");
                binding.btnProfileMediaMore.setVisibility(rows!=null && rows.length()==30?View.VISIBLE:View.GONE);
            }
            public void error(String e) { mediaLoading=false; binding.btnProfileMediaMore.setEnabled(true); binding.tvProfileMediaStatus.setVisibility(View.VISIBLE); binding.tvProfileMediaStatus.setText("Could not load media. Tap to retry."); binding.tvProfileMediaStatus.setOnClickListener(v -> loadProfileMedia(false)); binding.btnProfileMediaMore.setVisibility(View.GONE); }
        });
    }

    private void renderProfileMedia() {
        binding.rvProfileMedia.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<MediaHolder>() {
            public MediaHolder onCreateViewHolder(ViewGroup parent,int type) { ImageView image=new ImageView(ProfileActivity.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackgroundColor(getColor(R.color.surface_variant)); image.setContentDescription("Profile media"); image.setLayoutParams(new ViewGroup.LayoutParams(-1, (int)(120*getResources().getDisplayMetrics().density))); return new MediaHolder(image); }
            public void onBindViewHolder(MediaHolder holder,int position) { JSONObject item=profileMedia.get(position); String ref=Dto.asset(Dto.nullable(item,"asset_id")); Glide.with(ProfileActivity.this).load(ref).centerCrop().into(holder.image); holder.image.setOnClickListener(v -> { if("video".equals(profileMediaKind)) startActivity(new Intent(ProfileActivity.this,VideoPlayerActivity.class).putExtra(VideoPlayerActivity.EXTRA_ASSET,ref)); else { JSONArray refs=new JSONArray(); for(JSONObject x:profileMedia) refs.put(Dto.asset(Dto.nullable(x,"asset_id"))); startActivity(ProfilePhotoViewerActivity.intent(ProfileActivity.this,refs,position)); } }); }
            public int getItemCount(){return profileMedia.size();}
        });
    }
    private static class MediaHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder { final ImageView image; MediaHolder(View v){super(v);image=(ImageView)v;} }

    private void loadFeatured() {
        Api.run(() -> (JSONArray)RpcClient.call("list_profile_featured",Api.obj("p_owner_id",targetUid)),new Api.Callback<JSONArray>() {
            public void success(JSONArray rows) {
                binding.layoutFeaturedItems.removeAllViews();
                if(rows==null || rows.length()==0){binding.cardFeatured.setVisibility(targetUid.equals(mAuth.uid())?View.VISIBLE:View.GONE);return;}
                binding.cardFeatured.setVisibility(View.VISIBLE);
                for(int i=0;i<rows.length();i++){ JSONObject item=rows.optJSONObject(i); if(item==null)continue;
                    ImageView photo=new ImageView(ProfileActivity.this); int size=(int)(100*getResources().getDisplayMetrics().density); photo.setLayoutParams(new LinearLayout.LayoutParams(size,size)); photo.setScaleType(ImageView.ScaleType.CENTER_CROP); photo.setContentDescription("Featured profile photo");
                    Glide.with(ProfileActivity.this).load(Dto.asset(Dto.nullable(item,"asset_id"))).centerCrop().into(photo);
                    photo.setOnLongClickListener(v->{ if(!targetUid.equals(mAuth.uid()))return false; new AlertDialog.Builder(ProfileActivity.this).setMessage("Remove this featured photo?").setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->Api.run(()->RpcClient.call("remove_profile_featured",Api.obj("p_item_id",item.optString("id"))),new Api.Callback<Object>(){public void success(Object r){loadFeatured();}public void error(String e){toast(e);}})).show();return true;});
                    photo.setOnClickListener(v->{JSONArray refs=new JSONArray().put(Dto.asset(Dto.nullable(item,"asset_id")));startActivity(ProfilePhotoViewerActivity.intent(ProfileActivity.this,refs,0));});
                    LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)photo.getLayoutParams();lp.rightMargin=(int)(8*getResources().getDisplayMetrics().density);photo.setLayoutParams(lp);binding.layoutFeaturedItems.addView(photo);
                }
            }
            public void error(String e){binding.cardFeatured.setVisibility(View.GONE);}
        });
    }
    private void chooseFeaturedPhoto() {
        Api.run(()->(JSONArray)RpcClient.call("list_profile_photo_candidates",Api.obj("p_after_time",null,"p_after_id",null,"p_limit",50)),new Api.Callback<JSONArray>(){
            public void success(JSONArray rows){if(rows==null||rows.length()==0){toast("Create a personal post with a photo first.");return;}List<JSONObject> options=new ArrayList<>();List<String> labels=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x!=null){options.add(x);labels.add("Photo · "+x.optString("created_at"));}}new AlertDialog.Builder(ProfileActivity.this).setTitle("Choose featured photo").setItems(labels.toArray(new String[0]),(d,i)->{JSONObject x=options.get(i);Api.run(()->RpcClient.call("set_profile_featured",Api.obj("p_source_post_id",x.optString("post_id"),"p_asset_id",x.optString("asset_id"),"p_position",Math.min(5,binding.layoutFeaturedItems.getChildCount()))),new Api.Callback<Object>(){public void success(Object r){loadFeatured();}public void error(String e){toast(e);}});}).setNegativeButton("Cancel",null).show();}
            public void error(String e){toast(e);}
        });
    }
    private void loadProfilePinned() {
        Api.run(() -> (JSONObject)RpcClient.call("get_profile_pinned_post",Api.obj("p_owner_id",targetUid)),new Api.Callback<JSONObject>() {
            public void success(JSONObject post) { if(post==null || post.isNull("id")){binding.cardProfilePinned.setVisibility(View.GONE);return;} binding.cardProfilePinned.setVisibility(View.VISIBLE); binding.tvProfilePinned.setText("📌 Pinned · "+post.optString("body","Photo post")); binding.tvProfilePinned.setOnClickListener(v -> com.example.meetomxh.supabase.PostDetails.show(ProfileActivity.this,post.optString("id"))); }
            public void error(String e) { binding.cardProfilePinned.setVisibility(View.GONE); }
        });
    }
    private void loadFriendsPreview() {
        friendRepository.getFriendsList(targetUid, new FriendRepository.OnFriendItemsListener() {
            @Override
            public void onItems(List<FriendItem> items) {
                if (isFinishing() || isDestroyed()) return;
                binding.layoutFriendsGrid.removeAllViews();
                if (items == null || items.isEmpty()) {
                    binding.tvFriendsCount.setText("0 người bạn");
                    binding.tvFriendsCountSummary.setText("0");
                    binding.tvFriendsCountSummary.setVisibility(View.VISIBLE);
                    binding.tvFriendsStatus.setText("Chưa có bạn bè.");
                    binding.tvFriendsStatus.setVisibility(View.VISIBLE);
                    return;
                }
                binding.tvFriendsCount.setText(items.size() + " người bạn");
                binding.tvFriendsCountSummary.setText(formatCount(items.size()));
                binding.tvFriendsCountSummary.setVisibility(View.VISIBLE);
                binding.tvFriendsStatus.setVisibility(View.GONE);

                int limit = Math.min(items.size(), 6);
                for (int i = 0; i < limit; i++) {
                    User user = items.get(i).getUser();
                    if (user == null) continue;

                    LinearLayout card = new LinearLayout(ProfileActivity.this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setGravity(Gravity.CENTER_HORIZONTAL);
                    card.setBackgroundResource(R.drawable.bg_card_rounded);
                    int pad = (int) (6 * getResources().getDisplayMetrics().density);
                    card.setPadding(pad, pad, pad, pad);

                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = 0;
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
                    params.setMargins(pad / 2, pad / 2, pad / 2, pad / 2);
                    card.setLayoutParams(params);

                    ShapeableImageView avatar = new ShapeableImageView(ProfileActivity.this);
                    int avatarSize = (int) (64 * getResources().getDisplayMetrics().density);
                    avatar.setLayoutParams(new LinearLayout.LayoutParams(avatarSize, avatarSize));
                    avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    AvatarViews.load(avatar, user.getAvatarUrl(), user.getAvatarPreset());
                    avatar.setShapeAppearanceModel(
                            avatar.getShapeAppearanceModel().toBuilder()
                                    .setAllCornerSizes(8 * getResources().getDisplayMetrics().density)
                                    .build()
                    );
                    card.addView(avatar);

                    TextView label = new TextView(ProfileActivity.this);
                    label.setText(user.getFullName());
                    label.setTextColor(getColor(R.color.text_primary));
                    label.setTextSize(12f);
                    label.setTypeface(null, Typeface.BOLD);
                    label.setMaxLines(2);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    label.setGravity(Gravity.CENTER_HORIZONTAL);
                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    textParams.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
                    label.setLayoutParams(textParams);
                    card.addView(label);

                    card.setOnClickListener(v -> {
                        Intent intent = new Intent(ProfileActivity.this, ProfileActivity.class);
                        intent.putExtra("target_uid", user.getUid());
                        startActivity(intent);
                    });
                    binding.layoutFriendsGrid.addView(card);
                }
            }

            @Override
            public void onError(String error) {
                if (!isFinishing() && !isDestroyed()) {
                    binding.tvFriendsStatus.setText("Không tải được danh sách bạn bè.");
                    binding.tvFriendsStatus.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void showBlockDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Quyền riêng tư")
                .setItems(new String[]{"Đóng", "Đóng", "Báo cáo tài khoản"}, (d, i) -> {
                    if (i == 2) {
                        startActivity(new Intent(this, ReportContentActivity.class)
                                .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE, "profile")
                                .putExtra(ReportContentActivity.EXTRA_TARGET_ID, targetUid));
                        return;
                    }
                    Api.run(() -> RpcClient.call("set_block", Api.obj("p_target", targetUid, "p_blocked", i == 0)),
                            new Api.Callback<Object>() {
                                public void success(Object r) {
                                    Toast.makeText(ProfileActivity.this, "Đã cập nhật. Bỏ chặn không khôi phục quan hệ cũ.", Toast.LENGTH_LONG).show();
                                    finish();
                                }
                                public void error(String e) { Toast.makeText(ProfileActivity.this, e, Toast.LENGTH_LONG).show(); }
                            });
                })
                .setNegativeButton("Đóng", null)
                .show();
    }
    private void loadTimeline(boolean append) {
        if (timelineLoading || targetUid == null) return;
        if (!append) {
            timelineAfterTime = null;
            timelineAfterId = null;
            timelinePosts.clear();
            timelineAdapter.setPosts(timelinePosts);
        }
        timelineLoading = true;
        binding.btnTimelineRefresh.setEnabled(false);
        binding.btnTimelineMore.setEnabled(false);
        binding.cardTimelineStatus.setVisibility(View.VISIBLE);
        binding.tvTimelineStatus.setText("Đang tải bài viết…");

        final String afterTime = append ? timelineAfterTime : null;
        final String afterId = append ? timelineAfterId : null;

        Api.run(() -> (JSONArray) RpcClient.call("get_user_posts", Api.obj("p_author", targetUid, "p_after_time", afterTime, "p_after_id", afterId, "p_limit", 20)), new Api.Callback<JSONArray>() {
            public void success(JSONArray rows) {
                timelineLoading = false;
                binding.btnTimelineRefresh.setEnabled(true);
                binding.btnTimelineMore.setEnabled(true);
                Set<String> ids = new HashSet<>();
                for (Post p : timelinePosts) ids.add(p.getPostId());
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    Post p = Dto.post(row);
                    if (ids.add(p.getPostId())) timelinePosts.add(p);
                }
                if (rows.length() > 0) {
                    JSONObject last = rows.optJSONObject(rows.length() - 1);
                    timelineAfterTime = last.optString("created_at", null);
                    timelineAfterId = last.optString("id", null);
                }
                timelineAdapter.setPosts(timelinePosts);
                boolean empty = timelinePosts.isEmpty();
                binding.tvTimelineStatus.setText(empty ? "Chưa có bài viết nào được phép hiển thị." : "");
                binding.cardTimelineStatus.setVisibility(empty ? View.VISIBLE : View.GONE);
                binding.btnTimelineMore.setVisibility(rows.length() == 20 ? View.VISIBLE : View.GONE);
            }

            public void error(String error) {
                timelineLoading = false;
                binding.btnTimelineRefresh.setEnabled(true);
                binding.btnTimelineMore.setEnabled(true);
                binding.cardTimelineStatus.setVisibility(View.VISIBLE);
                binding.tvTimelineStatus.setText("Không tải được bài viết. Nhấn Làm mới để thử lại.");
                binding.btnTimelineMore.setVisibility(View.GONE);
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (targetUid != null) {
            loadUserProfile();
            loadRelationshipStatus();
            if (targetUid.equals(mAuth.uid())) {
                loadFriendsPreview();
            }
            if (timelineAdapter != null) {
                timelineAfterTime = null;
                timelineAfterId = null;
                timelinePosts.clear();
                timelineAdapter.setPosts(timelinePosts);
                loadTimeline(false);
            }
        }
    }

    private void loadUserProfile() {
        ProfileClient.get(targetUid, new Api.Callback<User>() {
            public void success(User user) {
                if (isFinishing()) return;
                targetProfileExists = true;
                binding.tvFullName.setText(user.getFullName());
                binding.tvBio.setText(user.getBio());
                String location = user.getLocation();
                boolean hasLocation = location != null && !location.trim().isEmpty();
                binding.tvLocation.setText(hasLocation ? "Nơi ở: " + location : "");
                binding.layoutLocationRow.setVisibility(hasLocation ? View.VISIBLE : View.GONE);

                String gender = user.getGender();
                String dob = user.getDateOfBirth();

                binding.tvGender.setText(gender == null || gender.trim().isEmpty() ? "" : "Giới tính: " + gender);
                binding.tvDob.setText(formatPrivateDate(dob));

                boolean hasGender = gender != null && !gender.trim().isEmpty();
                boolean hasDob = dob != null && !dob.trim().isEmpty();

                binding.layoutGenderRow.setVisibility(hasGender ? View.VISIBLE : View.GONE);
                binding.layoutDobRow.setVisibility(hasDob ? View.VISIBLE : View.GONE);
                binding.cardPersonalDetails.setVisibility(hasGender || hasDob ? View.VISIBLE : View.GONE);

                AvatarViews.load(binding.imgAvatar, user.getAvatarUrl(), user.getAvatarPreset());

                if (binding.ivCreatePostAvatar.getVisibility() == View.VISIBLE) {
                    AvatarViews.load(binding.ivCreatePostAvatar, user.getAvatarUrl(), user.getAvatarPreset());
                }

                if (user.getCoverUrl() != null && !user.getCoverUrl().isEmpty()) {
                    Glide.with(ProfileActivity.this)
                            .applyDefaultRequestOptions(new RequestOptions()
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                                    .skipMemoryCache(true))
                            .load(user.getCoverUrl())
                            .into(binding.imgCover);
                } else {
                    binding.imgCover.setImageResource(R.drawable.bg_cover_placeholder);
                }

                updateMessageButton();
            }

            public void error(String e) {
                targetProfileExists = false;
                updateMessageButton();
                Toast.makeText(ProfileActivity.this, e, Toast.LENGTH_LONG).show();
            }
        });
    }

        private String formatCount(int count) {
        if (count < 1000) return String.valueOf(count);
        if (count < 10000) return String.format(Locale.US, "%.1fK", count / 1000.0);
        if (count < 1000000) return (count / 1000) + "K";
        return String.format(Locale.US, "%.1fM", count / 1000000.0);
    }

    private boolean following;
    private void updateFollowButtonUI(boolean isFollowing) {
        binding.btnFollow.setText(isFollowing ? "Đang theo dõi" : "Theo dõi");
        if (isFollowing) {
            binding.btnFollow.setTextColor(getColor(R.color.text_primary));
            binding.btnFollow.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.surface_bg)));
            binding.btnFollow.setStrokeColor(android.content.res.ColorStateList.valueOf(getColor(R.color.divider_color)));
            binding.btnFollow.setStrokeWidth((int)(1 * getResources().getDisplayMetrics().density));
        } else {
            binding.btnFollow.setTextColor(getColor(R.color.white));
            binding.btnFollow.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.brand_blue)));
            binding.btnFollow.setStrokeWidth(0);
        }
    }
    private void loadFollowStats() {
        Api.run(() -> RpcClient.object("get_follow_counts", Api.obj("p_target", targetUid)), new Api.Callback<JSONObject>() {
            public void success(JSONObject value) {
                if (isFinishing() || binding == null) return;
                binding.layoutFollowStats.setVisibility(View.VISIBLE);
                binding.tvFollowers.setText(formatCount(value.optInt("followers_count")));
                binding.tvFollowing.setText(formatCount(value.optInt("following_count")));
                following = value.optBoolean("following");
                updateFollowButtonUI(following);
            }
            public void error(String error) { if (!isFinishing()) Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show(); }
        });
    }
    private void toggleFollow() {
        binding.btnFollow.setEnabled(false);
        binding.btnFollow.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
            .withEndAction(() -> binding.btnFollow.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start())
            .start();
        boolean requested = !following;
        updateFollowButtonUI(requested);
        Api.run(() -> RpcClient.object("set_follow", Api.obj("p_target", targetUid, "p_follow", requested)), new Api.Callback<JSONObject>() {
            public void success(JSONObject value) {
                if (binding == null) return;
                following = value.optBoolean("following");
                updateFollowButtonUI(following);
                if (value.has("followers_count") && value.has("following_count")) {
                    binding.tvFollowers.setText(formatCount(value.optInt("followers_count")));
                    binding.tvFollowing.setText(formatCount(value.optInt("following_count")));
                } else binding.layoutFollowStats.setVisibility(View.GONE);
                binding.btnFollow.setEnabled(true);
            }
            public void error(String e) {
                if (binding != null) {
                    updateFollowButtonUI(following);
                    binding.btnFollow.setEnabled(true);
                }
                toast(e);
            }
        });
    }
    private void openFollowList(String direction) {
        startActivity(new Intent(this,FollowListActivity.class).putExtra("target_uid",targetUid).putExtra("direction",direction));
    }

    private String formatPrivateDate(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            LocalDate date = LocalDate.parse(value.substring(0, Math.min(10, value.length())));
            return "Ngày sinh: " + date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", new Locale("vi", "VN")));
        } catch (Exception ignored) {
            return "";
        }
    }

    private void loadRelationshipStatus() {
        SessionUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null && currentUser.getUid().equals(targetUid)) {
            currentRelationshipStatus = FriendItem.RelationshipStatus.SELF;
            updateActionButtonsUI();
            return;
        }

        friendRepository.getRelationshipStatus(targetUid, new FriendRepository.OnRelationshipStatusListener() {
            @Override
            public void onStatus(FriendItem.RelationshipStatus status) {
                if (isDestroyed() || isFinishing()) return;
                currentRelationshipStatus = status;
                updateActionButtonsUI();
            }

            @Override
            public void onError(String error) {
                if (isDestroyed() || isFinishing()) return;
                Toast.makeText(ProfileActivity.this, "Lỗi kiểm tra quan hệ: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateActionButtonsUI() {
        binding.btnPrimaryAction.setEnabled(true);
        binding.btnSecondaryAction.setEnabled(true);
        updateMessageButton();

        if (currentRelationshipStatus == FriendItem.RelationshipStatus.SELF) {
            binding.btnEditProfile.setVisibility(View.VISIBLE);
            binding.btnPrimaryAction.setVisibility(View.GONE);
            binding.btnSecondaryAction.setVisibility(View.GONE);
            binding.btnEditProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));
            return;
        }

        binding.btnEditProfile.setVisibility(View.GONE);

        if (currentRelationshipStatus == FriendItem.RelationshipStatus.NO_RELATION) {
            binding.btnPrimaryAction.setText("Thêm bạn bè");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.GONE);
            binding.btnPrimaryAction.setOnClickListener(v -> handleSendRequest());
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.PENDING_SENT) {
            binding.btnPrimaryAction.setText("Hủy lời mời");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.GONE);
            binding.btnPrimaryAction.setOnClickListener(v -> handleRemoveRelation("Đã hủy lời mời kết bạn"));
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.PENDING_RECEIVED) {
            binding.btnPrimaryAction.setText("Chấp nhận");
            binding.btnSecondaryAction.setText("Từ chối");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.VISIBLE);
            binding.btnPrimaryAction.setOnClickListener(v -> handleAcceptRequest());
            binding.btnSecondaryAction.setOnClickListener(v -> handleRemoveRelation("Đã từ chối lời mời"));
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.ACCEPTED) {
            binding.btnPrimaryAction.setText("Bạn bè");
            binding.btnSecondaryAction.setText("Hủy kết bạn");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.VISIBLE);
            binding.btnPrimaryAction.setOnClickListener(v ->
                    Toast.makeText(this, "Các bạn đang là bạn bè", Toast.LENGTH_SHORT).show()
            );
            binding.btnSecondaryAction.setOnClickListener(v -> handleRemoveRelation("Đã hủy kết bạn"));
        }
    }

    private void updateMessageButton() {
        SessionUser currentUser = mAuth.getCurrentUser();
        boolean isSelf = currentUser != null && currentUser.getUid().equals(targetUid);
        binding.btnMessage.setVisibility(targetProfileExists && !isSelf ? View.VISIBLE : View.GONE);
    }

    private void openChatWithTarget() {
        if (!targetProfileExists || targetUid == null || targetUid.trim().isEmpty()) {
            Toast.makeText(this, "Không thể mở cuộc trò chuyện với hồ sơ này", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.btnMessage.setEnabled(false);
        ChatRepository.getInstance().getOrCreatePrivateChat(targetUid, new ChatRepository.OnChatListener() {
            @Override
            public void onSuccess(com.example.meetomxh.models.Chat chat) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnMessage.setEnabled(true);
                Intent intent = new Intent(ProfileActivity.this, ChatActivity.class);
                intent.putExtra(ChatActivity.EXTRA_CHAT_ID, chat.getChatId());
                intent.putExtra(ChatActivity.EXTRA_OTHER_UID, targetUid);
                startActivity(intent);
            }

            @Override
            public void onError(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnMessage.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleSendRequest() {
        binding.btnPrimaryAction.setEnabled(false);
        friendRepository.sendFriendRequest(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, message, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleAcceptRequest() {
        binding.btnPrimaryAction.setEnabled(false);
        friendRepository.acceptFriendRequest(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, message, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleRemoveRelation(String successToast) {
        binding.btnPrimaryAction.setEnabled(false);
        binding.btnSecondaryAction.setEnabled(false);

        friendRepository.removeFriendRelation(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                binding.btnSecondaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, successToast, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                binding.btnSecondaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
