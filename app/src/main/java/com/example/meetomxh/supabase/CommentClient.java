package com.example.meetomxh.supabase;
import com.example.meetomxh.models.Comment;
import org.json.*;
import java.util.*;
public final class CommentClient {
 public static List<Comment> thread(String post,String parent)throws Exception{
  List<Comment> rows=new ArrayList<>();Map<String,JSONObject> authors=new HashMap<>();JSONObject args=Api.obj("p_post_id",post,"p_parent_id",parent,"p_limit",50);
  while(true){JSONArray page=(JSONArray)RpcClient.call("list_comments",args);for(int i=0;i<page.length();i++){Comment comment=Dto.comment(page.getJSONObject(i));String author=comment.getAuthorUid();if(!authors.containsKey(author)){JSONObject profile=null;try{profile=RpcClient.object("get_profile",Api.obj("p_target",author));}catch(ErrorMapper.HttpError e){if(e.status!=403&&e.status!=404)throw e;}authors.put(author,profile);}JSONObject profile=authors.get(author);if(profile!=null){comment.setAuthorName(profile.optString("display_name","Thành viên"));comment.setAuthorAvatar(Dto.asset(Dto.nullable(profile,"avatar_asset_id")));}rows.add(comment);}if(page.length()<50)break;JSONObject last=page.getJSONObject(page.length()-1);args.put("p_after_time",last.getString("created_at")).put("p_after_id",last.getString("id"));}
  return rows;
 }
}
