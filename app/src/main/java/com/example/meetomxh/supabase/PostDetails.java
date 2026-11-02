package com.example.meetomxh.supabase;
import android.app.*;
import android.content.Intent;
import android.widget.*;
import org.json.*;
public final class PostDetails {
 public static void show(Activity a,String id){Api.run(()->RpcClient.object("get_post",Api.obj("p_post_id",id)),new Api.Callback<JSONObject>(){public void success(JSONObject p){if(a.isFinishing())return;LinearLayout rows=new LinearLayout(a);rows.setOrientation(LinearLayout.VERTICAL);TextView body=new TextView(a);body.setText(p.optString("body"));body.setPadding(24,24,24,24);rows.addView(body);JSONArray media=p.optJSONArray("media");if(media!=null)for(int i=0;i<media.length();i++){String asset=media.optJSONObject(i).optString("asset_id");Forms.button(rows,"Ảnh/video "+(i+1),()->a.startActivity(new Intent(a,com.example.meetomxh.ChatImageViewerActivity.class).putExtra("image_url",Dto.asset(asset))));}if("share".equals(p.optString("kind"))){if(p.optBoolean("original_available")&&!p.isNull("original_post_id"))Forms.button(rows,"Xem bài viết gốc",()->show(a,p.optString("original_post_id")));else{TextView unavailable=new TextView(a);unavailable.setText("Bài viết gốc không còn khả dụng với bạn.");rows.addView(unavailable);}}new AlertDialog.Builder(a).setTitle("Bài viết").setView(rows).setPositiveButton("Đóng",null).show();}public void error(String e){Toast.makeText(a,e,Toast.LENGTH_LONG).show();}});}
}
