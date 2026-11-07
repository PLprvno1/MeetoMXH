package com.example.meetomxh.supabase;
import androidx.lifecycle.MutableLiveData;
import org.json.JSONObject;
/** Server-authoritative counts; no optimistic increments or feed reloads. */
public final class PostUpdates {
 public static final class Counts { public final String id; public final int comments; Counts(String id,int comments){this.id=id;this.comments=comments;} }
 public static final MutableLiveData<Counts> counts=new MutableLiveData<>();
 public static final class Content {
  public final String id,audience;public final long version;public final boolean deleted;
  Content(String id,String audience,long version,boolean deleted){this.id=id;this.audience=audience;this.version=version;this.deleted=deleted;}
 }
 public static final MutableLiveData<Content> content=new MutableLiveData<>();
 public static void changed(JSONObject result,boolean deleted){
  String id=result.optString("id");if(id.isEmpty())return;
  Content change=new Content(id,result.optString("audience"),result.optLong("version"),deleted);
  com.example.meetomxh.repositories.ReelsRepository.contentChanged(id,change.audience,change.version,deleted);
  content.setValue(change);
  if(deleted){com.example.meetomxh.models.Post post=new com.example.meetomxh.models.Post();post.setPostId(id);PostViewerUpdates.publish(post,true);}
  else PostViewerUpdates.reconcile(id);
 }
 private static final java.util.Map<String,Long> versions=new java.util.HashMap<>();
 private PostUpdates(){}
 public static void reconcile(String id){long version=versions.getOrDefault(id,0L)+1;versions.put(id,version);Api.run(()->RpcClient.object("get_post",Api.obj("p_post_id",id)),new Api.Callback<JSONObject>(){
  public void success(JSONObject p){if(version!=versions.getOrDefault(id,0L))return;counts.setValue(new Counts(id,p.optInt("comments_count")));PostViewerUpdates.publish(Dto.post(p),false);}
  public void error(String e){}
 });}
}
