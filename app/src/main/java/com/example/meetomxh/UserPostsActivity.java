package com.example.meetomxh;
import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.*;
import org.json.*;
public class UserPostsActivity extends AppCompatActivity {
 private LinearLayout form;private String author,time,id;private boolean loading;
 @Override protected void onCreate(Bundle state){super.onCreate(state);author=getIntent().getStringExtra("author_id");form=Forms.start(this,"Bài viết của người dùng");Forms.button(form,"Tải lại",()->{time=null;id=null;form.removeAllViews();load();});load();}
 private void load(){if(loading)return;loading=true;Api.run(()->(JSONArray)RpcClient.call("get_user_posts",Api.obj("p_author",author,"p_after_time",time,"p_after_id",id,"p_limit",20)),new Api.Callback<JSONArray>(){public void success(JSONArray rows){loading=false;for(int i=0;i<rows.length();i++){JSONObject p=rows.optJSONObject(i);Forms.button(form,p.optString("body").isEmpty()?"Bài viết có ảnh/video":p.optString("body"),()->PostDetails.show(UserPostsActivity.this,p.optString("id")));}if(rows.length()==20){JSONObject last=rows.optJSONObject(19);time=last.optString("created_at");id=last.optString("id");Forms.button(form,"Tải thêm",()->load());}Forms.status(UserPostsActivity.this,rows.length()==0?"Không có bài viết được phép xem.":"Chọn bài viết để xem.");}public void error(String e){loading=false;Forms.status(UserPostsActivity.this,e);}});}
}
