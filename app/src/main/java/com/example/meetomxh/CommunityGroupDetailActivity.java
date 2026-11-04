package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.ActivityCommunityGroupDetailBinding;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.CommunityGroupRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Dto;
import com.bumptech.glide.Glide;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class CommunityGroupDetailActivity extends AppCompatActivity implements FeedAdapter.OnPostClickListener {
    private ActivityCommunityGroupDetailBinding binding;
    private FeedAdapter adapter;
    private String groupId, afterTime, afterId, pendingRequestId, groupAbout="";
    private boolean joined, manager, loading, hasMore=true;
    private SupabaseStorage.Transfer coverTransfer;
    private final ActivityResultLauncher<String> coverPicker=registerForActivityResult(new ActivityResultContracts.GetContent(),this::uploadCover);
    private final List<Post> posts=new ArrayList<>();
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); binding=ActivityCommunityGroupDetailBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        groupId=getIntent().getStringExtra("group_id"); binding.btnBack.setOnClickListener(v->finish());
        binding.cardGroupPlaceholder.setVisibility(View.GONE);
        adapter=new FeedAdapter(this,false); binding.rvGroupPosts.setLayoutManager(new LinearLayoutManager(this)); binding.rvGroupPosts.setAdapter(adapter);
        binding.btnJoinGroup.setOnClickListener(v->changeMembership());
        binding.btnCreateGroupPost.setOnClickListener(v->startActivity(new Intent(this,CreatePostActivity.class).putExtra("group_id",groupId)));
        binding.btnGroupManage.setOnClickListener(v->startActivity(new Intent(this,CommunityGroupAdminActivity.class).putExtra("group_id",groupId)));
        binding.btnInviteFriends.setOnClickListener(v->startActivity(new Intent(this,CommunityGroupInvitesActivity.class).putExtra("group_id",groupId)));
        binding.btnChangeGroupCover.setOnClickListener(v->{if(coverTransfer!=null)new androidx.appcompat.app.AlertDialog.Builder(this).setMessage("Hủy tải ảnh bìa?").setNegativeButton("Tiếp tục",null).setPositiveButton("Hủy tải",(d,w)->{coverTransfer.cancel();coverTransfer=null;binding.tvGroupFeedStatus.setVisibility(View.GONE);}).show();else coverPicker.launch("image/*");});
        binding.tabGroupPosts.setOnClickListener(v->binding.rvGroupPosts.smoothScrollToPosition(0));
        binding.tabGroupMembers.setOnClickListener(v->startActivity(new Intent(this,CommunityGroupMembersActivity.class).putExtra("group_id",groupId)));
        binding.tabGroupAbout.setOnClickListener(v->new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Giới thiệu và quy định").setMessage(groupAbout).setPositiveButton("Đóng",null).show());
        binding.swipeGroupPosts.setOnRefreshListener(()->loadPosts(false));
        binding.rvGroupPosts.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener(){@Override public void onScrolled(androidx.recyclerview.widget.RecyclerView r,int dx,int dy){if(dy>0&&!r.canScrollVertically(1)&&hasMore)loadPosts(true);}});
        if(groupId==null){unavailable("Không tìm thấy nhóm");return;} binding.tvGroupFeedStatus.setVisibility(View.VISIBLE);binding.tvGroupFeedStatus.setText("Đang tải nhóm…");loadGroup();
    }
    private void loadGroup(){CommunityGroupRepository.get(groupId,new CommunityGroupRepository.Callback<JSONObject>(){
        public void success(JSONObject g){if(isFinishing())return;String name=g.optString("name","");if(name.isEmpty()){unavailable("Nhóm không khả dụng");return;}
            binding.tvDetailGroupName.setText(name);binding.tvGroupMeta.setText(("public".equals(g.optString("privacy"))?"Công khai":"hidden".equals(g.optString("privacy"))?"Nhóm ẩn":"Riêng tư")+" · "+g.optInt("member_count")+" thành viên");
            String cover=Dto.asset(Dto.nullable(g,"cover_asset_id"));if(!cover.isEmpty())Glide.with(CommunityGroupDetailActivity.this).load(cover).placeholder(R.drawable.ic_groups).error(R.drawable.ic_groups).into(binding.ivGroupCover);
            groupAbout=g.optString("description")+"\n\nQuy định:\n"+g.optString("rules");binding.tvGroupMeta.setContentDescription(groupAbout);
            joined="active".equals(g.optString("membership_state"));boolean banned="banned".equals(g.optString("membership_state"));String request=g.optString("request_state");pendingRequestId=g.optString("request_id",null);String role=g.optString("role");manager="owner".equals(role)||"admin".equals(role)||"moderator".equals(role);
            binding.btnJoinGroup.setText(banned?"Bạn đã bị cấm":joined?"Rời nhóm":"pending".equals(request)?"Hủy yêu cầu":"Tham gia nhóm");binding.btnJoinGroup.setEnabled(!banned);
            binding.btnInviteFriends.setVisibility(joined?View.VISIBLE:View.GONE);binding.btnCreateGroupPost.setVisibility(joined?View.VISIBLE:View.GONE);binding.btnGroupManage.setVisibility(manager?View.VISIBLE:View.GONE);
            binding.btnChangeGroupCover.setVisibility(("owner".equals(role)||"admin".equals(role))?View.VISIBLE:View.GONE);
            binding.tvGroupFeedStatus.setText("Đang tải bài viết…");loadPosts(false);
        }
        public void error(String e){unavailable("Không thể mở nhóm. "+e);}
    });}
    private void loadPosts(boolean append){if(loading||(!hasMore&&append))return;loading=true;if(!append){afterTime=null;afterId=null;hasMore=true;posts.clear();adapter.setPosts(posts);binding.tvGroupFeedStatus.setVisibility(View.VISIBLE);binding.tvGroupFeedStatus.setText("Đang tải bài viết…");}else binding.tvGroupFeedStatus.setVisibility(View.GONE);
        CommunityGroupRepository.posts(groupId,false,append?afterTime:null,append?afterId:null,new CommunityGroupRepository.Callback<List<Post>>(){
            public void success(List<Post> page){loading=false;binding.swipeGroupPosts.setRefreshing(false);if(!append)posts.clear();posts.addAll(page);adapter.setPosts(posts);hasMore=page.size()==20;if(hasMore){Post last=page.get(page.size()-1);afterTime=last.serverCreatedAt;afterId=last.getPostId();}binding.tvGroupFeedStatus.setVisibility(posts.isEmpty()?View.VISIBLE:View.GONE);if(posts.isEmpty())binding.tvGroupFeedStatus.setText("Chưa có bài viết trong nhóm.");}
            public void error(String e){loading=false;binding.swipeGroupPosts.setRefreshing(false);binding.tvGroupFeedStatus.setVisibility(View.VISIBLE);binding.tvGroupFeedStatus.setText(e+" · chạm để thử lại");binding.tvGroupFeedStatus.setOnClickListener(v->loadPosts(false));}
        });
    }
    private void changeMembership(){if(joined){CommunityGroupRepository.leave(groupId,callback("Đã rời nhóm"));}else if(pendingRequestId!=null){CommunityGroupRepository.action("cancel_group_request",Api.obj("p_request_id",pendingRequestId),callback("Đã hủy yêu cầu"));}else CommunityGroupRepository.join(groupId,new CommunityGroupRepository.Callback<JSONObject>(){public void success(JSONObject o){Toast.makeText(CommunityGroupDetailActivity.this,"requested".equals(o.optString("status"))?"Đã gửi yêu cầu tham gia":"Đã tham gia nhóm",Toast.LENGTH_SHORT).show();loadGroup();}public void error(String e){Toast.makeText(CommunityGroupDetailActivity.this,e,Toast.LENGTH_LONG).show();}});}
    private CommunityGroupRepository.Callback<JSONObject> callback(String msg){return new CommunityGroupRepository.Callback<JSONObject>(){public void success(JSONObject r){Toast.makeText(CommunityGroupDetailActivity.this,msg,Toast.LENGTH_SHORT).show();loadGroup();}public void error(String e){Toast.makeText(CommunityGroupDetailActivity.this,e,Toast.LENGTH_LONG).show();}};}
    private void unavailable(String text){binding.tvDetailGroupName.setText("Nhóm không khả dụng");binding.tvGroupMeta.setText(text);binding.btnJoinGroup.setVisibility(View.GONE);binding.btnInviteFriends.setVisibility(View.GONE);binding.btnCreateGroupPost.setVisibility(View.GONE);binding.tvGroupFeedStatus.setText(text);binding.tvGroupFeedStatus.setVisibility(View.VISIBLE);}
    private void uploadCover(Uri uri){if(uri==null)return;android.widget.ImageView preview=new android.widget.ImageView(this);preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);preview.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1,(int)(220*getResources().getDisplayMetrics().density)));Glide.with(this).load(uri).into(preview);new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Xem trước ảnh bìa").setView(preview).setNegativeButton("Hủy",null).setPositiveButton("Tải ảnh lên",(d,w)->beginCoverUpload(uri)).show();}
    private void beginCoverUpload(Uri uri){coverTransfer=SupabaseStorage.upload(getContentResolver(),uri,"group-cover/"+groupId,SessionManager.getInstance().uid(),new SupabaseStorage.UploadListener(){public void onProgress(int percent){binding.btnChangeGroupCover.setContentDescription("Đang tải ảnh bìa "+percent+"%");binding.tvGroupFeedStatus.setVisibility(View.VISIBLE);binding.tvGroupFeedStatus.setText("Đang tải ảnh bìa lên · "+percent+"%");}public void onSuccess(String ref){coverTransfer=null;binding.btnChangeGroupCover.setContentDescription("Thay ảnh bìa nhóm");Glide.with(CommunityGroupDetailActivity.this).load(ref).into(binding.ivGroupCover);Toast.makeText(CommunityGroupDetailActivity.this,"Đã cập nhật ảnh bìa",Toast.LENGTH_SHORT).show();loadGroup();}public void onFailure(String error){coverTransfer=null;binding.tvGroupFeedStatus.setVisibility(View.VISIBLE);binding.tvGroupFeedStatus.setText(error+" · Chạm để thử lại.");binding.tvGroupFeedStatus.setOnClickListener(v->beginCoverUpload(uri));Toast.makeText(CommunityGroupDetailActivity.this,error+" · Chọn ảnh để thử lại.",Toast.LENGTH_LONG).show();}});}
    @Override public void onLikeClick(Post post,Runnable done){Api.run(()->{RpcClient.call("set_post_reaction",Api.obj("p_post_id",post.getPostId(),"p_reaction",post.isLiked()?null:"like"));return true;},new Api.Callback<Boolean>(){public void success(Boolean v){done.run();loadPosts(false);}public void error(String e){done.run();Toast.makeText(CommunityGroupDetailActivity.this,e,Toast.LENGTH_LONG).show();}});}
    @Override public void onCommentClick(Post post){CommentsBottomSheetFragment.newInstance(post.getPostId()).show(getSupportFragmentManager(),"GroupComments");}
    @Override public void onShareClick(Post post){com.example.meetomxh.supabase.ShareActions.show(this,post,true);}
    @Override public void onCreatePostClick(){startActivity(new Intent(this,CreatePostActivity.class).putExtra("group_id",groupId));}
    @Override public void onAuthorClick(Post post){if(post.getAuthorUid()!=null)startActivity(new Intent(this,ProfileActivity.class).putExtra("target_uid",post.getAuthorUid()));}
    @Override public void onDeletePostClick(Post post){new androidx.appcompat.app.AlertDialog.Builder(this).setMessage("Xóa bài viết trong nhóm?").setNegativeButton("Hủy",null).setPositiveButton("Xóa",(d,w)->CommunityGroupRepository.action("delete_community_post",Api.obj("p_post_id",post.getPostId()),new CommunityGroupRepository.Callback<JSONObject>(){public void success(JSONObject x){loadPosts(false);}public void error(String e){Toast.makeText(CommunityGroupDetailActivity.this,e,Toast.LENGTH_LONG).show();}})).show();}
    @Override public void onSavePostClick(Post post,boolean save,Runnable done){Api.run(()->{RpcClient.call("set_saved_post",Api.obj("p_post_id",post.getPostId(),"p_saved",save));return true;},new Api.Callback<Boolean>(){public void success(Boolean v){done.run();}public void error(String e){done.run();Toast.makeText(CommunityGroupDetailActivity.this,e,Toast.LENGTH_LONG).show();}});}
    @Override protected void onResume(){super.onResume();if(groupId!=null&&!isFinishing())loadPosts(false);}
    @Override protected void onDestroy(){if(coverTransfer!=null)coverTransfer.cancel();super.onDestroy();}
}
