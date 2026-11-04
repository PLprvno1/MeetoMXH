package com.example.meetomxh.repositories;
import androidx.lifecycle.*;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.*;
import org.json.*;
import java.util.*;
public final class FeedRepository {
 public interface OnPostActionListener{void onSuccess(String message);void onFailure(String error);}
 private static FeedRepository instance;private final MutableLiveData<List<Post>> posts=new MutableLiveData<>();public final MutableLiveData<String> error=new MutableLiveData<>();public final MutableLiveData<Boolean> loadingState=new MutableLiveData<>(false);private boolean loading,refreshPending;private String afterTime,afterId;private long ownerEpoch=-1;
 private FeedRepository(){SessionManager.getInstance().listeners.add(()->{if(ownerEpoch!=SessionManager.getInstance().generation()){loading=false;refreshPending=false;loadingState.setValue(false);posts.setValue(new ArrayList<>());afterTime=null;afterId=null;ownerEpoch=SessionManager.getInstance().generation();}});}
 public static synchronized FeedRepository getInstance(){if(instance==null)instance=new FeedRepository();return instance;}
 public LiveData<List<Post>> getPosts(){return posts;}
 public void listenToRealPosts(){refresh();}
 public void refresh(){if(loading){refreshPending=true;return;}afterTime=null;afterId=null;load(false);}
 public void loadMore(){if(afterId!=null)load(true);}
 private static final class Page {final List<Post> posts;final String time,id;Page(List<Post> posts,String time,String id){this.posts=posts;this.time=time;this.id=id;}}
 private void finishLoad(){loading=false;loadingState.setValue(false);if(refreshPending){refreshPending=false;refresh();}}
 private void load(boolean append){
  if(loading)return;loading=true;loadingState.setValue(true);ownerEpoch=SessionManager.getInstance().generation();
  final String cursorTime=append?afterTime:null,cursorId=append?afterId:null;
  final List<Post> previous=append&&posts.getValue()!=null?new ArrayList<>(posts.getValue()):new ArrayList<>();
  Api.run(()->{
   JSONArray rows=(JSONArray)RpcClient.call("get_feed",Api.obj("p_limit",20,"p_after_time",cursorTime,"p_after_id",cursorId));
   List<Post> result=new ArrayList<>(previous);Set<String> ids=new HashSet<>();for(Post p:result)ids.add(p.getPostId());
   for(int i=0;i<rows.length();i++){Post p=Dto.post(rows.getJSONObject(i));if(ids.add(p.getPostId()))result.add(p);}
   // get_feed already returns mime_type for each authorized media item. Avoid
   // an extra REST round trip over every asset accumulated across prior pages.
   JSONObject last=rows.length()==0?null:rows.getJSONObject(rows.length()-1);
   return new Page(result,last==null?null:last.optString("created_at"),last==null?null:last.optString("id"));
  },new Api.Callback<Page>(){
   public void success(Page page){afterTime=page.time;afterId=page.id;error.setValue(null);posts.setValue(page.posts);finishLoad();}
   public void error(String message){if(!append)posts.setValue(new ArrayList<>());error.setValue(message);finishLoad();}
  });
 } public void toggleLikePost(String id,OnPostActionListener cb){Post p=find(id);if(p==null){cb.onFailure("Hãy tải lại bài viết rồi thử lại.");return;}setReaction(id,p.isLiked()?null:"like",cb);}
 public void setReaction(String id,String type,OnPostActionListener cb){Post p=find(id);if(p==null){cb.onFailure("Hãy tải lại bài viết rồi thử lại.");return;}Api.run(()->RpcClient.object("set_post_reaction",Api.obj("p_post_id",id,"p_reaction",type)),new Api.Callback<JSONObject>(){public void success(JSONObject result){p.setReaction(Dto.nullable(result,"reaction"));p.setLikesCount(result.optInt("reactions_count",p.getLikesCount()));error.setValue(null);cb.onSuccess("Updated");}public void error(String message){error.setValue(message);cb.onFailure(message);}});}
 public void sharePost(Post p,String text,OnPostActionListener cb){String root=p.getOriginalPostId()==null?p.getPostId():p.getOriginalPostId();String payload=root+text;String uuid=RequestIds.get("share",payload);Api.run(()->{Object row=RpcClient.call("share_post",Api.obj("p_original",root,"p_body",text,"p_audience","public","p_client_request_id",uuid));RequestIds.complete("share",payload);return row;},callback(cb));}
 public void deletePost(String id,OnPostActionListener cb){Post p=find(id);if(p==null){cb.onFailure("Hãy tải lại bài viết.");return;}Api.run(()->RpcClient.call("delete_post",Api.obj("p_post_id",id,"p_expected_version",p.version)),new Api.Callback<Object>(){public void success(Object value){ReelsRepository.invalidate();refresh();cb.onSuccess("Đã cập nhật");}public void error(String e){cb.onFailure(e);}});}
 public void edit(Post p,String body,String audience,OnPostActionListener cb){JSONArray assets=new JSONArray();for(String ref:p.getImageUrls())assets.put(Dto.assetId(ref));action("edit_post",Api.obj("p_post_id",p.getPostId(),"p_body",body,"p_audience",audience,"p_assets",assets,"p_expected_version",p.version),cb);}
 private Post find(String id){if(posts.getValue()!=null)for(Post p:posts.getValue())if(id.equals(p.getPostId()))return p;return null;}
 private void action(String rpc,JSONObject args,OnPostActionListener cb){Api.run(()->RpcClient.call(rpc,args),callback(cb));}
 private Api.Callback<Object> callback(OnPostActionListener cb){return new Api.Callback<Object>(){public void success(Object value){refresh();cb.onSuccess("Đã cập nhật");}public void error(String e){cb.onFailure(e);}};}
}
