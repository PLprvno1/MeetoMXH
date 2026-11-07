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
import com.example.meetomxh.models.Post;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.ChatRepository;
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
    private SessionManager mAuth;

    private String targetUid;
    private boolean targetProfileExists;
    private boolean profileContentAllowed=true;

    private FeedAdapter timelineAdapter;
    private final List<Post> timelinePosts = new ArrayList<>();
    private String timelineAfterTime, timelineAfterId;
    private boolean timelineLoading;
    private boolean profileReelsLoaded;
    private final List<Post> profileReels = new ArrayList<>();
    private String reelAfterTime, reelAfterId;
    private boolean reelsLoading;
    private ProfileReelAdapter profileReelAdapter;
    private String profileTab = "posts";
    private int profileRefreshPending;
    private int profileRefreshGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
        binding.layoutFollowStats.setVisibility(View.VISIBLE);
        binding.btnFollow.setVisibility(isSelf ? View.GONE : View.VISIBLE);
        binding.tvFollowers.setOnClickListener(v -> openFollowList("followers"));
        binding.tvFollowing.setOnClickListener(v -> openFollowList("following"));
        binding.btnFollow.setOnClickListener(v -> toggleFollow());
        loadFollowStats();

        if (isSelf) {
            binding.btnChangeAvatar.setVisibility(View.VISIBLE);
            binding.btnPrivacyAction.setVisibility(View.GONE);

            binding.btnChangeAvatar.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));

        } else {
            binding.btnChangeAvatar.setVisibility(View.GONE);
            binding.btnPrivacyAction.setVisibility(View.VISIBLE);
            binding.btnPrivacyAction.setOnClickListener(v -> showBlockDialog());
        }

        setupProfileSections();
        setupTimeline();
        binding.swipeProfile.setOnRefreshListener(this::refreshProfile);
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
                // Profile timelines do not expose the Feed create-post composer.
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
                        .setMessage(post.isReel()?"Đây là cùng một nội dung trên Feed và Reels. Xóa sẽ gỡ khỏi cả hai nơi.":"Bài viết sẽ được xóa theo quyền và trạng thái trên Cloud.")
                        .setNegativeButton("Hủy", null)
                        .setPositiveButton("Xóa", (d, w) -> Api.run(
                                () -> RpcClient.call(post.isReel()?"delete_reel":"delete_post", Api.obj("p_post_id", post.getPostId(), "p_expected_version", post.version)),
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
                    public void success(Object r) { loadProfilePinned(); toast("Đã cập nhật bài viết ghim"); }
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
        timelineAdapter.setShowPersonFallback(false);

        binding.rvTimeline.setAdapter(timelineAdapter);com.example.meetomxh.supabase.PostViewerUpdates.changes.observe(this,ignored->{for(com.example.meetomxh.supabase.PostViewerUpdates.Event event:com.example.meetomxh.supabase.PostViewerUpdates.events())if(event.deleted)timelinePosts.removeIf(p->event.id.equals(p.getPostId()));});com.example.meetomxh.supabase.PostUpdates.counts.observe(this,c->{if(c!=null&&timelineAdapter!=null)timelineAdapter.updateCommentCount(c.id,c.comments);});

        binding.scrollProfile.setOnScrollChangeListener((androidx.core.widget.NestedScrollView.OnScrollChangeListener)(v,x,y,oldX,oldY)->{if(y>oldY&&v.getChildAt(0)!=null&&y+v.getHeight()>=v.getChildAt(0).getHeight()-200){if("posts".equals(profileTab)&&timelineAfterId!=null&&!timelineLoading)loadTimeline(true);else if("reels".equals(profileTab)&&reelAfterId!=null&&!reelsLoading)loadProfileReels(true);}});
        binding.rvTimeline.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener(){public void onScrolled(androidx.recyclerview.widget.RecyclerView r,int dx,int dy){if(dy>0&&!r.canScrollVertically(1)&&timelineAfterId!=null&&!timelineLoading)loadTimeline(true);}});
    }

    private void refreshProfile() {
        final int generation = ++profileRefreshGeneration;
        profileRefreshPending = 4 + ("posts".equals(profileTab) || "reels".equals(profileTab) ? 1 : 0);
        Runnable complete = () -> {
            if (generation != profileRefreshGeneration || binding == null) return;
            if (--profileRefreshPending <= 0) binding.swipeProfile.setRefreshing(false);
        };

        loadUserProfile(complete);
        loadFollowStats(complete);
        loadProfilePinned(complete);
        loadFeatured(complete);
        loadRelationshipStatus();
        if ("posts".equals(profileTab)) {
            loadTimeline(false, complete);
        } else if ("reels".equals(profileTab)) {
            loadProfileReels(false, complete);
        }
    }

    private void setupProfileSections() {
        binding.btnProfilePosts.setOnClickListener(v -> showProfileTab("posts"));
        binding.btnProfileReels.setOnClickListener(v -> showProfileTab("reels"));
        binding.btnProfileAbout.setOnClickListener(v -> showProfileTab("about"));
        binding.btnProfileMediaMore.setOnClickListener(v -> loadProfileReels(true));
        binding.rvProfileMedia.setLayoutManager(new GridLayoutManager(this, 3));
        profileReelAdapter = new ProfileReelAdapter();
        binding.rvProfileMedia.setAdapter(profileReelAdapter);
        showProfileTab("posts");
        binding.btnProfileUnpin.setVisibility(targetUid.equals(mAuth.uid()) ? View.VISIBLE : View.GONE);
        binding.btnAddFeatured.setVisibility(targetUid.equals(mAuth.uid()) ? View.VISIBLE : View.GONE);
        binding.btnAddFeatured.setOnClickListener(v -> chooseFeaturedPhoto());
        binding.btnProfileUnpin.setOnClickListener(v -> Api.run(() -> RpcClient.call("clear_profile_pinned_post", Api.obj()), new Api.Callback<Object>() {
            public void success(Object r) { loadProfilePinned(); }
            public void error(String e) { toast(e); }
        }));
    }

    private void showProfileTab(String tab) {
        profileTab = tab;
        boolean posts = "posts".equals(tab), about = "about".equals(tab), reels = "reels".equals(tab);
        binding.layoutTimelineSection.setVisibility(posts ? View.VISIBLE : View.GONE);
        binding.layoutProfileMedia.setVisibility(reels ? View.VISIBLE : View.GONE);
        binding.cardPersonalDetails.setVisibility(about ? View.VISIBLE : View.GONE);
        if (reels && !profileReelsLoaded) {
            profileReelsLoaded = true; reelAfterTime = null; reelAfterId = null; profileReels.clear();
            profileReelAdapter.set(profileReels); loadProfileReels(false);
        }
        binding.btnProfilePosts.setText(posts ? "B\u00e0i vi\u1ebft \u2713" : "B\u00e0i vi\u1ebft");
        binding.btnProfileReels.setText("Reels");
        binding.btnProfileAbout.setText("About");
    }

    private void loadProfileReels(boolean append){loadProfileReels(append,null);}
    private void loadProfileReels(boolean append,Runnable onComplete){
        if(reelsLoading){runCompletion(onComplete);return;}
        if(!append){reelAfterTime=null;reelAfterId=null;profileReels.clear();profileReelAdapter.set(profileReels);}
        reelsLoading=true;binding.tvProfileMediaStatus.setVisibility(View.VISIBLE);binding.tvProfileMediaStatus.setText("Đang tải Reels…");binding.btnProfileMediaMore.setEnabled(false);
        Api.run(()->(JSONArray)RpcClient.call("get_user_reels",Api.obj("p_author",targetUid,"p_after_time",append?reelAfterTime:null,"p_after_id",append?reelAfterId:null,"p_limit",20)),new Api.Callback<JSONArray>(){
            public void success(JSONArray rows){if(binding==null)return;reelsLoading=false;binding.btnProfileMediaMore.setEnabled(true);Set<String> seen=new HashSet<>();for(Post item:profileReels)seen.add(item.getPostId());if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null){Post reel=Dto.post(row);if(seen.add(reel.getPostId()))profileReels.add(reel);}}if(rows!=null&&rows.length()>0){JSONObject last=rows.optJSONObject(rows.length()-1);reelAfterTime=last.optString("created_at",null);reelAfterId=last.optString("id",null);}profileReelAdapter.set(profileReels);binding.tvProfileMediaStatus.setVisibility(profileReels.isEmpty()?View.VISIBLE:View.GONE);if(profileReels.isEmpty())binding.tvProfileMediaStatus.setText("Hồ sơ này chưa có Reel nào.");boolean more=rows!=null&&rows.length()==20;binding.btnProfileMediaMore.setVisibility(more?View.VISIBLE:View.GONE);runCompletion(onComplete);}
            public void error(String error){if(binding==null)return;reelsLoading=false;binding.btnProfileMediaMore.setEnabled(true);binding.tvProfileMediaStatus.setVisibility(View.VISIBLE);binding.tvProfileMediaStatus.setText("Không thể tải Reels. Chạm để thử lại.");binding.tvProfileMediaStatus.setOnClickListener(v->loadProfileReels(false));binding.btnProfileMediaMore.setVisibility(View.GONE);runCompletion(onComplete);}
        });
    }
    private final class ProfileReelAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<ProfileReelHolder>{
        private final List<Post> items=new ArrayList<>();
        void set(List<Post> next){items.clear();if(next!=null)items.addAll(next);notifyDataSetChanged();}
        @androidx.annotation.NonNull @Override public ProfileReelHolder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent,int type){View v=android.view.LayoutInflater.from(parent.getContext()).inflate(com.example.meetomxh.R.layout.item_profile_reel,parent,false);int cellWidth=getResources().getDisplayMetrics().widthPixels/3;v.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(-1,cellWidth*16/9));return new ProfileReelHolder(v);}
        @Override public void onBindViewHolder(@androidx.annotation.NonNull ProfileReelHolder holder,int position){Post reel=items.get(position);String cover=reel.getReelCoverAssetId()==null?(reel.getImageUrls().isEmpty()?null:reel.getImageUrls().get(0)):Dto.asset(reel.getReelCoverAssetId());if(cover==null){holder.cover.setImageResource(com.example.meetomxh.R.drawable.ic_media_error);}else Glide.with(ProfileActivity.this).load(cover).apply(com.bumptech.glide.request.RequestOptions.frameOf(1_000_000)).centerCrop().placeholder(com.example.meetomxh.R.drawable.ic_media_error).error(com.example.meetomxh.R.drawable.ic_media_error).into(holder.cover);holder.views.setText("\u25c9 "+formatCount(reel.getReelViewsCount()));holder.likes.setText("\u2665 "+formatCount(reel.getLikesCount()));holder.itemView.setOnClickListener(v->com.example.meetomxh.supabase.PostDetails.show(ProfileActivity.this,reel.getPostId()));}
        @Override public int getItemCount(){return items.size();}
    }
    private static final class ProfileReelHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder{final ImageView cover;final android.widget.TextView views,likes;ProfileReelHolder(View v){super(v);cover=v.findViewById(com.example.meetomxh.R.id.profile_reel_cover);views=v.findViewById(com.example.meetomxh.R.id.profile_reel_views);likes=v.findViewById(com.example.meetomxh.R.id.profile_reel_likes);}}

    private void loadFeatured() {
        loadFeatured(null);
    }

    private void loadFeatured(Runnable onComplete) {
        Api.run(() -> (JSONArray)RpcClient.call("list_profile_featured",Api.obj("p_owner_id",targetUid)),new Api.Callback<JSONArray>() {
            public void success(JSONArray rows) {
                binding.layoutFeaturedItems.removeAllViews();
                if(rows==null || rows.length()==0){binding.cardFeatured.setVisibility(targetUid.equals(mAuth.uid())?View.VISIBLE:View.GONE);runCompletion(onComplete);return;}
                binding.cardFeatured.setVisibility(View.VISIBLE);
                for(int i=0;i<rows.length();i++){ JSONObject item=rows.optJSONObject(i); if(item==null)continue;
                    ImageView photo=new ImageView(ProfileActivity.this); int size=(int)(100*getResources().getDisplayMetrics().density); photo.setLayoutParams(new LinearLayout.LayoutParams(size,size)); photo.setScaleType(ImageView.ScaleType.CENTER_CROP); photo.setContentDescription("Ảnh nổi bật trên hồ sơ");
                    Glide.with(ProfileActivity.this).load(Dto.asset(Dto.nullable(item,"asset_id"))).centerCrop().into(photo);
                    photo.setOnLongClickListener(v->{ if(!targetUid.equals(mAuth.uid()))return false; new AlertDialog.Builder(ProfileActivity.this).setMessage("Gỡ ảnh nổi bật này?").setNegativeButton("Hủy",null).setPositiveButton("Gỡ",(d,w)->Api.run(()->RpcClient.call("remove_profile_featured",Api.obj("p_item_id",item.optString("id"))),new Api.Callback<Object>(){public void success(Object r){loadFeatured();}public void error(String e){toast(e);}})).show();return true;});
                    photo.setOnClickListener(v->{JSONArray refs=new JSONArray().put(Dto.asset(Dto.nullable(item,"asset_id")));startActivity(ProfilePhotoViewerActivity.intent(ProfileActivity.this,refs,0));});
                    LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)photo.getLayoutParams();lp.rightMargin=(int)(8*getResources().getDisplayMetrics().density);photo.setLayoutParams(lp);binding.layoutFeaturedItems.addView(photo);
                }
                runCompletion(onComplete);
            }
            public void error(String e){binding.cardFeatured.setVisibility(View.GONE);runCompletion(onComplete);}
        });
    }
    private void chooseFeaturedPhoto() {
        Api.run(()->(JSONArray)RpcClient.call("list_profile_photo_candidates",Api.obj("p_after_time",null,"p_after_id",null,"p_limit",50)),new Api.Callback<JSONArray>(){
            public void success(JSONArray rows){if(rows==null||rows.length()==0){toast("Trước tiên, hãy tạo bài viết cá nhân có ảnh.");return;}List<JSONObject> options=new ArrayList<>();List<String> labels=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x!=null){options.add(x);labels.add("Ảnh · "+x.optString("created_at"));}}new AlertDialog.Builder(ProfileActivity.this).setTitle("Chọn ảnh nổi bật").setItems(labels.toArray(new String[0]),(d,i)->{JSONObject x=options.get(i);Api.run(()->RpcClient.call("set_profile_featured",Api.obj("p_source_post_id",x.optString("post_id"),"p_asset_id",x.optString("asset_id"),"p_position",Math.min(5,binding.layoutFeaturedItems.getChildCount()))),new Api.Callback<Object>(){public void success(Object r){loadFeatured();}public void error(String e){toast(e);}});}).setNegativeButton("Hủy",null).show();}
            public void error(String e){toast(e);}
        });
    }
    private void loadProfilePinned() {
        loadProfilePinned(null);
    }

    private void loadProfilePinned(Runnable onComplete) {
        Api.run(() -> (JSONObject)RpcClient.call("get_profile_pinned_post",Api.obj("p_owner_id",targetUid)),new Api.Callback<JSONObject>() {
            public void success(JSONObject post) { if(post==null || post.isNull("id")){binding.cardProfilePinned.setVisibility(View.GONE);runCompletion(onComplete);return;} binding.cardProfilePinned.setVisibility(View.VISIBLE); binding.tvProfilePinned.setText("Bài viết đã ghim · "+post.optString("body","Bài viết có ảnh")); binding.tvProfilePinned.setOnClickListener(v -> com.example.meetomxh.supabase.PostDetails.show(ProfileActivity.this,post.optString("id")));runCompletion(onComplete); }
            public void error(String e) { binding.cardProfilePinned.setVisibility(View.GONE);runCompletion(onComplete); }
        });
    }


    private void showBlockDialog() {
        new AlertDialog.Builder(this).setTitle("Quyền riêng tư và báo cáo")
            .setItems(new String[]{"Chặn người dùng", "Bỏ chặn người dùng", "Báo cáo tài khoản"}, (d,i)->{
                if(i==2){startActivity(new Intent(this,ReportContentActivity.class).putExtra(ReportContentActivity.EXTRA_TARGET_TYPE,"profile").putExtra(ReportContentActivity.EXTRA_TARGET_ID,targetUid));return;}
                new AlertDialog.Builder(this).setTitle(i==0?"Chặn người dùng?":"Bỏ chặn người dùng?")
                    .setNegativeButton("Hủy",null).setPositiveButton("Xác nhận",(dialog,which)->Api.run(()->RpcClient.call("set_block",Api.obj("p_target",targetUid,"p_blocked",i==0)),new Api.Callback<Object>(){
                        public void success(Object r){if(i==0)finish();else loadFollowStats();}
                        public void error(String e){Toast.makeText(ProfileActivity.this,e,Toast.LENGTH_LONG).show();}
                    })).show();
            }).setNegativeButton("Đóng",null).show();
    }
    private void loadTimeline(boolean append) {
        loadTimeline(append, null);
    }

    private void loadTimeline(boolean append, Runnable onComplete) {
        if (timelineLoading || targetUid == null) { runCompletion(onComplete); return; }
        if (!append) {
            timelineAfterTime = null;
            timelineAfterId = null;
            timelinePosts.clear();
            timelineAdapter.setPosts(timelinePosts);
        }
        timelineLoading = true;
        binding.cardTimelineStatus.setVisibility(View.VISIBLE);
        binding.tvTimelineStatus.setText("Đang tải bài viết...");

        final String afterTime = append ? timelineAfterTime : null;
        final String afterId = append ? timelineAfterId : null;

        Api.run(() -> (JSONArray) RpcClient.call("get_user_posts", Api.obj("p_author", targetUid, "p_after_time", afterTime, "p_after_id", afterId, "p_limit", 20)), new Api.Callback<JSONArray>() {
            public void success(JSONArray rows) {
                timelineLoading = false;
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
                binding.tvTimelineStatus.setText(empty ? (profileContentAllowed?"Chưa có bài viết nào được phép hiển thị.":"Tài khoản riêng tư. Theo dõi và chờ chấp nhận để xem nội dung.") : "");
                binding.cardTimelineStatus.setVisibility(empty ? View.VISIBLE : View.GONE);
                if(rows.length()<20)timelineAfterId=null;
                runCompletion(onComplete);
            }

            public void error(String error) {
                timelineLoading = false;
                binding.cardTimelineStatus.setVisibility(View.VISIBLE);
                binding.tvTimelineStatus.setText("Không thể tải bài viết. Kéo xuống để thử lại.");
                runCompletion(onComplete);
            }
        });
    }

    private void runCompletion(Runnable completion) {
        if (completion != null) completion.run();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(com.example.meetomxh.supabase.PostViewerUpdates.returning(this))return;
        com.example.meetomxh.ui.StoryRings.invalidate();
        if (targetUid != null) {
            loadUserProfile();
            loadFollowStats();
            loadRelationshipStatus();

            if (profileReelsLoaded && profileReelAdapter != null) loadProfileReels(false);

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
        loadUserProfile(null);
    }

    private void loadUserProfile(Runnable onComplete) {
        ProfileClient.get(targetUid, new Api.Callback<User>() {
            public void success(User user) {
                if (isFinishing() || binding == null) { runCompletion(onComplete); return; }
                targetProfileExists = true;
                binding.tvFullName.setText(user.getFullName());binding.tvUsername.setText(user.username==null?"":"@"+user.username);binding.tvUsername.setVisibility(user.username==null?View.GONE:View.VISIBLE);profileContentAllowed=user.contentAllowed;
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
                // Loading profile data must respect the selected tab.
                binding.cardPersonalDetails.setVisibility("about".equals(profileTab) ? View.VISIBLE : View.GONE);

                AvatarViews.load(binding.imgAvatar, user.getAvatarUrl(), user.getAvatarPreset());
                com.example.meetomxh.ui.StoryRings.bind((View)binding.imgAvatar.getParent(),binding.imgAvatar,targetUid,null);

                updateMessageButton();
                runCompletion(onComplete);
            }

            public void error(String e) {
                targetProfileExists = false;
                updateMessageButton();
                Toast.makeText(ProfileActivity.this, e, Toast.LENGTH_LONG).show();
                runCompletion(onComplete);
            }
        });
    }

        private String formatCount(int count) {
        if (count < 1000) return String.valueOf(count);
        if (count < 10000) return String.format(Locale.US, "%.1fK", count / 1000.0);
        if (count < 1000000) return (count / 1000) + "K";
        return String.format(Locale.US, "%.1fM", count / 1000000.0);
    }

    private boolean following, followRequested;
    private void updateFollowButtonUI(boolean isFollowing) {
        binding.btnFollow.setText(followRequested ? "Đã gửi yêu cầu" : isFollowing ? "Đang theo dõi" : "Theo dõi");
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
        loadFollowStats(null);
    }

    private void loadFollowStats(Runnable onComplete) {
        Api.run(() -> RpcClient.object("get_follow_counts", Api.obj("p_target", targetUid)), new Api.Callback<JSONObject>() {
            public void success(JSONObject value) {
                if (isFinishing() || binding == null) { runCompletion(onComplete); return; }
                binding.layoutFollowStats.setVisibility(View.VISIBLE);
                boolean hasPostCount=value.has("posts_count")&&!value.isNull("posts_count");
                binding.layoutStatPosts.setVisibility(View.VISIBLE);
                if(hasPostCount) binding.tvPostsCount.setText(formatCount(value.optInt("posts_count")));
                else loadVisiblePostCount(null,null,0);
                binding.tvFollowers.setText(formatCount(value.optInt("followers_count")));
                binding.tvFollowing.setText(formatCount(value.optInt("following_count")));
                following = value.optBoolean("following");
                followRequested=value.optBoolean("requested");
                updateFollowButtonUI(following);
                runCompletion(onComplete);
            }
            public void error(String error) { if (!isFinishing()) { loadVisiblePostCount(null,null,0); Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show(); } runCompletion(onComplete); }
        });
    }
    private void loadVisiblePostCount(String afterTime,String afterId,int total){
        Api.run(()->(JSONArray)RpcClient.call("get_user_posts",Api.obj("p_author",targetUid,"p_after_time",afterTime,"p_after_id",afterId,"p_limit",20)),new Api.Callback<JSONArray>(){
            public void success(JSONArray rows){if(binding==null||isFinishing())return;int count=rows==null?0:rows.length();int sum=total+count;if(count==20){JSONObject last=rows.optJSONObject(count-1);if(last!=null){loadVisiblePostCount(last.optString("created_at",null),last.optString("id",null),sum);return;}}binding.tvPostsCount.setText(formatCount(sum));}
            public void error(String error){if(binding!=null)binding.tvPostsCount.setText(formatCount(total));}
        });
    }
    private void toggleFollow() {
        if(following||followRequested){new AlertDialog.Builder(this).setTitle(followRequested?"Hủy yêu cầu theo dõi?":"Bỏ theo dõi?").setNegativeButton("Hủy",null).setPositiveButton("Xác nhận",(d,w)->changeFollow(false)).show();}
        else changeFollow(true);
    }
    private void changeFollow(boolean desired) {
        binding.btnFollow.setEnabled(false);
        Api.run(()->RpcClient.object("set_follow",Api.obj("p_target",targetUid,"p_follow",desired)),new Api.Callback<JSONObject>(){
            public void success(JSONObject r){if(isFinishing())return;binding.btnFollow.setEnabled(true);loadFollowStats();loadTimeline(false);}
            public void error(String e){binding.btnFollow.setEnabled(true);toast(e);}
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

    private void loadRelationshipStatus() { updateActionButtonsUI(); }

    private void updateActionButtonsUI() {
        boolean self=targetUid.equals(mAuth.uid());
        binding.btnPrimaryAction.setVisibility(View.GONE);
        binding.btnSecondaryAction.setVisibility(View.GONE);
        binding.btnEditProfile.setVisibility(self?View.VISIBLE:View.GONE);
        binding.btnEditProfile.setOnClickListener(v->startActivity(new Intent(this,ProfileEditActivity.class)));
        updateMessageButton();
    }

    private void updateMessageButton() {
        SessionUser currentUser = mAuth.getCurrentUser();
        boolean isSelf = currentUser != null && currentUser.getUid().equals(targetUid);
        binding.btnMessage.setVisibility(targetProfileExists && !isSelf ? View.VISIBLE : View.GONE);
    }

    private void openChatWithTarget() {
        if(targetProfileExists)com.example.meetomxh.supabase.SocialActions.contact(this,targetUid);
    }






}
