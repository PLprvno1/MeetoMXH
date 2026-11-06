package com.example.meetomxh.supabase;
import androidx.lifecycle.MutableLiveData;
import org.json.JSONObject;
/** Server-authoritative counts; no optimistic increments or feed reloads. */
public final class PostUpdates {
 public static final class Counts { public final String id; public final int comments; Counts(String id,int comments){this.id=id;this.comments=comments;} }
 public static final MutableLiveData<Counts> counts=new MutableLiveData<>();
 private static final java.util.Map<String,Long> versions=new java.util.HashMap<>();
 private PostUpdates(){}
 public static void reconcile(String id){long version=versions.getOrDefault(id,0L)+1;versions.put(id,version);Api.run(()->RpcClient.object("get_post",Api.obj("p_post_id",id)),new Api.Callback<JSONObject>(){
  public void success(JSONObject p){if(version!=versions.getOrDefault(id,0L))return;counts.setValue(new Counts(id,p.optInt("comments_count")));}
  public void error(String e){}
 });}
}
