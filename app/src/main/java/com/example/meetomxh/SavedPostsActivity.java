package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.ActivitySavedPostsBinding;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class SavedPostsActivity extends AppCompatActivity {
    private ActivitySavedPostsBinding binding;
    private FeedAdapter adapter;
    private String afterTime, afterId;
    private boolean loading, hasMore;
    private boolean loadFailed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivitySavedPostsBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        adapter = new FeedAdapter(new FeedAdapter.OnPostClickListener() {
            public void onLikeClick(Post p, Runnable done) { done.run(); }
            public void onCommentClick(Post p) { CommentsBottomSheetFragment.newInstance(p.getPostId()).show(getSupportFragmentManager(), "SavedComments"); }
            public void onShareClick(Post p) { com.example.meetomxh.supabase.ShareActions.show(SavedPostsActivity.this,p,false); }
            public void onCreatePostClick() { }
            public void onAuthorClick(Post p) { if (p.getAuthorUid()!=null) startActivity(new Intent(SavedPostsActivity.this,ProfileActivity.class).putExtra("target_uid",p.getAuthorUid())); }
            public void onDeletePostClick(Post p) { }
            public void onSavePostClick(Post p, boolean save, Runnable completed) {
                if (save) { completed.run(); return; }
                binding.tvStatus.setText("Đang bỏ lưu…");
                com.example.meetomxh.supabase.SavedPostClient.set(p,false,new Api.Callback<Boolean>() {
                    public void success(Boolean ignored) { completed.run(); adapter.removePost(p.getPostId()); updateEmpty(); binding.tvStatus.setText("Đã bỏ lưu bài viết"); }
                    public void error(String e) { completed.run(); binding.tvStatus.setText(e); }
                });
            }
        }, false);
        binding.rvSavedPosts.setLayoutManager(new LinearLayoutManager(this)); binding.rvSavedPosts.setAdapter(adapter);com.example.meetomxh.supabase.PostUpdates.counts.observe(this,c->{if(c!=null)adapter.updateCommentCount(c.id,c.comments);});
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnRefresh.setOnClickListener(v -> load(false));
        binding.btnRetry.setOnClickListener(v -> load(false));
        binding.btnLoadMore.setOnClickListener(v -> load(true));
        binding.rvSavedPosts.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView rv,int dx,int dy) {
                if(dy>0 && !rv.canScrollVertically(1) && hasMore && !loading) load(true);
            }
        });
        load(false);
    }

    @Override protected void onResume(){super.onResume();if(binding!=null&&!loading)load(false);}
    private void load(boolean append) {
        if (loading) return;
        if (!append) { afterTime=null; afterId=null; hasMore=false; }
        loading=true; binding.btnRetry.setVisibility(View.GONE); binding.btnLoadMore.setVisibility(View.GONE);
        binding.tvStatus.setText(append ? "Đang tải thêm…" : "Đang tải bài đã lưu…");
        final String t=append?afterTime:null, id=append?afterId:null;
        Api.run(() -> {
            JSONArray rows=(JSONArray)RpcClient.call("get_saved_posts",Api.obj("p_after_time",t,"p_after_id",id,"p_limit",20));
            List<Post> page=new ArrayList<>(); String nextTime=null,nextId=null;
            for(int i=0;i<rows.length();i++) { JSONObject row=rows.getJSONObject(i); JSONObject p=row.optJSONObject("post"); if(p!=null)page.add(Dto.post(p)); if(i==rows.length()-1){nextTime=row.optString("saved_at");nextId=p==null?null:p.optString("id");} }
            return new Page(page,nextTime,nextId,rows.length());
        },new Api.Callback<Page>() {
            public void success(Page page) {
                loading=false; loadFailed=false; afterTime=page.time;afterId=page.id;hasMore=page.count==20;
                if(append) adapter.appendPosts(page.posts); else adapter.setPosts(page.posts);
                binding.tvStatus.setText(page.posts.isEmpty()&&!append?"":"Đã tải xong");
                binding.btnLoadMore.setVisibility(hasMore?View.VISIBLE:View.GONE); updateEmpty();
            }
            public void error(String error) {
                loading=false; loadFailed=true; binding.tvStatus.setText(error); binding.btnRetry.setVisibility(View.VISIBLE);
                if(!append) { adapter.setPosts(new ArrayList<>()); updateEmpty(); }
            }
        });
    }
    private void updateEmpty() { binding.tvEmpty.setVisibility(adapter.getItemCount()==0&&!loading&&!loadFailed?View.VISIBLE:View.GONE); }
    private static final class Page { final List<Post> posts;final String time,id;final int count;Page(List<Post> p,String t,String i,int c){posts=p;time=t;id=i;count=c;} }
}
