package com.example.meetomxh;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.meetomxh.adapters.CommentAdapter;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.ActivityPostDetailsBinding;
import com.example.meetomxh.models.Comment;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.FeedRepository;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.MentionComposer;
import org.json.JSONObject;
import java.util.*;

/** A single authorized source post followed by its paged comment thread. */
public final class PostDetailsActivity extends AppCompatActivity implements CommentAdapter.OnCommentClickListener {
    private ActivityPostDetailsBinding binding;
    private FeedAdapter postAdapter;
    private CommentAdapter comments;
    private MentionComposer composer;
    private String postId, afterTime, afterId, parentId, replyId;
    private boolean loadingMore, hasMore=true;
    private Post post;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        binding=ActivityPostDetailsBinding.inflate(getLayoutInflater());setContentView(binding.getRoot());
        postId=getIntent().getStringExtra(PostDetails.EXTRA_POST_ID);
        if(postId==null){PostDetails.unavailable(this);finish();return;}
        binding.postDetailBack.setOnClickListener(v->finish());
        binding.postDetailPost.setLayoutManager(new LinearLayoutManager(this));
        postAdapter=new FeedAdapter(new FeedAdapter.OnPostClickListener(){
            public void onLikeClick(Post p,Runnable done){FeedRepository.getInstance().setReaction(p.getPostId(),p.isLiked()?null:"like",new FeedRepository.OnPostActionListener(){public void onSuccess(String m){done.run();}public void onFailure(String e){Toast.makeText(PostDetailsActivity.this,e,Toast.LENGTH_LONG).show();done.run();}});}
            @Override public void onReactionSelected(Post p,String reaction,FeedAdapter.ReactionCallback callback){Api.run(()->RpcClient.object("set_post_reaction",Api.obj("p_post_id",p.getPostId(),"p_reaction",reaction)),new Api.Callback<JSONObject>(){public void success(JSONObject result){p.setReaction(Dto.nullable(result,"reaction"));p.setLikesCount(result.optInt("reactions_count",p.getLikesCount()));callback.onResult(true,null);}public void error(String e){callback.onResult(false,e);}});}
            public void onCommentClick(Post p){binding.postDetailInput.requestFocus();((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(binding.postDetailInput,InputMethodManager.SHOW_IMPLICIT);}
            public void onShareClick(Post p){ShareActions.show(PostDetailsActivity.this,p,false);}
            public void onCreatePostClick(){}
            public void onAuthorClick(Post p){if(p.getAuthorUid()!=null)startActivity(new android.content.Intent(PostDetailsActivity.this,ProfileActivity.class).putExtra("target_uid",p.getAuthorUid()));}
            public void onDeletePostClick(Post p){}
            public void onSavePostClick(Post p,boolean save,Runnable done){SavedPostClient.set(p,save,new Api.Callback<Boolean>(){public void success(Boolean b){p.setSaved(b);done.run();}public void error(String e){p.setSaved(!save);Toast.makeText(PostDetailsActivity.this,e,Toast.LENGTH_LONG).show();done.run();}});}
        },false);
        binding.postDetailPost.setAdapter(postAdapter);
        comments=new CommentAdapter(SessionManager.getInstance().uid(),this);
        binding.postDetailComments.setLayoutManager(new LinearLayoutManager(this));binding.postDetailComments.setAdapter(comments);
        composer=new MentionComposer(binding.postDetailInput);
        binding.postDetailSend.setOnClickListener(v->sendComment());binding.postDetailMore.setOnClickListener(v->loadMore());
        binding.postDetailComments.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener(){@Override public void onScrolled(androidx.recyclerview.widget.RecyclerView rv,int dx,int dy){if(dy>0&&((LinearLayoutManager)rv.getLayoutManager()).findLastVisibleItemPosition()>=comments.getItemCount()-3)loadMore();}});
        binding.postDetailScroll.setOnScrollChangeListener((android.view.View v,int x,int y,int oldX,int oldY)->{if(y>oldY&&y+v.getHeight()>=((android.widget.ScrollView)v).getChildAt(0).getHeight()-180)loadMore();});
        loadPost();
    }
    private void loadPost(){binding.postDetailLoading.setVisibility(View.VISIBLE);Api.run(()->PostDetails.resolve(postId),new Api.Callback<JSONObject>(){public void success(JSONObject json){if(binding==null)return;post=Dto.post(json);if(post.isReel()){startActivity(new android.content.Intent(PostDetailsActivity.this,MainActivity.class).putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED,true).putExtra(PostDetails.EXTRA_REEL_ID,post.getPostId()));finish();return;}postId=post.getPostId();binding.postDetailLoading.setVisibility(View.GONE);postAdapter.setPosts(Collections.singletonList(post));loadComments(true);}public void error(String e){if(binding==null)return;PostDetails.unavailable(PostDetailsActivity.this);binding.postDetailLoading.setVisibility(View.GONE);binding.postDetailMessage.setText("Nội dung không còn khả dụng");binding.postDetailMessage.setVisibility(View.VISIBLE);binding.postDetailSend.setEnabled(false);}});}
    private void loadComments(boolean reset){if(reset){afterTime=null;afterId=null;hasMore=true;Api.run(()->CommentClient.page(postId,parentId,null,null,30),new Api.Callback<List<Comment>>(){public void success(List<Comment> rows){if(binding==null)return;comments.setComments(rows);hasMore=rows.size()==30;setCursor(rows);binding.postDetailMore.setVisibility(hasMore?View.VISIBLE:View.GONE);if(parentId!=null)binding.postDetailMore.setText("Quay lại bình luận");}public void error(String e){showCommentError(e);}});}else loadMore();}
    private void loadMore(){if(parentId!=null){parentId=null;replyId=null;binding.postDetailInput.setHint("Viết bình luận…");loadComments(true);return;}if(loadingMore||!hasMore||afterTime==null)return;loadingMore=true;binding.postDetailMore.setText("Đang tải…");Api.run(()->CommentClient.page(postId,null,afterTime,afterId,30),new Api.Callback<List<Comment>>(){public void success(List<Comment> rows){loadingMore=false;comments.appendComments(rows);hasMore=rows.size()==30;setCursor(rows);binding.postDetailMore.setText("Tải bình luận cũ hơn");binding.postDetailMore.setVisibility(hasMore?View.VISIBLE:View.GONE);}public void error(String e){loadingMore=false;showCommentError(e);}});}
    private void setCursor(List<Comment> rows){if(rows!=null&&!rows.isEmpty()){Comment last=rows.get(rows.size()-1);afterTime=last.serverCreatedAt;afterId=last.getCommentId();}}
    private void sendComment(){String body=binding.postDetailInput.getText().toString().trim();if(body.isEmpty())return;binding.postDetailSend.setEnabled(false);JSONObject args=Api.obj("p_post_id",postId,"p_parent_id",replyId==null?parentId:replyId,"p_body",body,"p_mentions",composer.getMentions());String fp=args.toString();Api.run(()->{args.put("p_client_request_id",RequestIds.get("comment",fp));Object result=RpcClient.call("create_comment_with_mentions",args);RequestIds.complete("comment",fp);return result;},new Api.Callback<Object>(){public void success(Object value){if(binding==null)return;binding.postDetailInput.setText("");binding.postDetailSend.setEnabled(true);if(replyId!=null){parentId=replyId;replyId=null;}PostUpdates.reconcile(postId);loadPost();}public void error(String e){if(binding!=null)binding.postDetailSend.setEnabled(true);Toast.makeText(PostDetailsActivity.this,e,Toast.LENGTH_LONG).show();}});}
    @Override public void onCommentClick(Comment c){new android.app.AlertDialog.Builder(this).setItems(new String[]{"Trả lời","Xem trả lời"},(d,which)->{if(which==0){replyId=c.getCommentId();binding.postDetailInput.setHint("Trả lời bình luận");binding.postDetailInput.requestFocus();((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(binding.postDetailInput,InputMethodManager.SHOW_IMPLICIT);}else{parentId=c.getCommentId();replyId=null;loadComments(true);}}).show();}
    @Override public void onDeleteCommentClick(Comment c){Api.run(()->RpcClient.call("delete_comment",Api.obj("p_comment_id",c.getCommentId(),"p_expected_version",c.version)),new Api.Callback<Object>(){public void success(Object r){PostUpdates.reconcile(postId);loadComments(true);}public void error(String e){Toast.makeText(PostDetailsActivity.this,e,Toast.LENGTH_LONG).show();}});}
    @Override public void onCommentReaction(Comment c,String reaction,FeedAdapter.ReactionCallback callback){Api.run(()->RpcClient.call("set_comment_reaction",Api.obj("p_comment_id",c.getCommentId(),"p_reaction",reaction)),new Api.Callback<Object>(){public void success(Object r){callback.onResult(true,null);}public void error(String e){callback.onResult(false,e);}});}
    private void showCommentError(String e){binding.postDetailMore.setText(e+" · Chạm để thử lại");binding.postDetailMore.setVisibility(View.VISIBLE);}
    @Override protected void onDestroy(){if(composer!=null)composer.dispose();binding=null;super.onDestroy();}
}
