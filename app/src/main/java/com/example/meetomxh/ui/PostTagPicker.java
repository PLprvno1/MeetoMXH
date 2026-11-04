package com.example.meetomxh.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RpcClient;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;

public final class PostTagPicker {
    public interface Result { void selected(JSONArray userIds,JSONArray people); }
    private PostTagPicker(){}
    public static void show(Activity activity,JSONArray existing,Result callback){
        LinkedHashMap<String,JSONObject> selected=new LinkedHashMap<>();
        if(existing!=null)for(int i=0;i<existing.length();i++){JSONObject p=existing.optJSONObject(i);if(p!=null){String id=p.optString("user_id",null);if(id!=null&&!id.equals("null"))selected.put(id,p);}}
        BottomSheetDialog sheet=new BottomSheetDialog(activity);LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*activity.getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(activity);title.setText("Gắn thẻ bạn bè");title.setTextSize(20);title.setTypeface(null,android.graphics.Typeface.BOLD);root.addView(title);
        EditText query=new EditText(activity);query.setSingleLine(true);query.setHint("Tìm bạn bè");root.addView(query);
        ChipGroup chips=new ChipGroup(activity);chips.setSingleLine(false);root.addView(chips);
        TextView state=new TextView(activity);state.setText("Nhập tên bạn bè để tìm.");root.addView(state);
        LinearLayout results=new LinearLayout(activity);results.setOrientation(LinearLayout.VERTICAL);root.addView(results);
        TextView done=new TextView(activity);done.setText("Xong");done.setTextColor(0xff1877f2);done.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);done.setPadding(0,pad,pad,pad);root.addView(done);
        Handler handler=new Handler(Looper.getMainLooper());final int[] generation={0};final Runnable[] pending={null};
        Runnable renderChips=()->{chips.removeAllViews();for(java.util.Map.Entry<String,JSONObject> e:selected.entrySet()){JSONObject p=e.getValue();Chip c=new Chip(activity);c.setText(p.optString("display_name","Bạn bè"));c.setCloseIconVisible(true);c.setOnCloseIconClickListener(v->{selected.remove(e.getKey());chips.removeView(c);});chips.addView(c);}};
        renderChips.run();
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(pending[0]!=null)handler.removeCallbacks(pending[0]);int g=++generation[0];String q=s.toString().trim();if(q.length()<1){results.removeAllViews();state.setText("Nhập tên bạn bè để tìm.");return;}state.setText("Đang tìm…");pending[0]=()->Api.run(()->(JSONArray)RpcClient.call("search_mention_people",Api.obj("p_query",q,"p_limit",12)),new Api.Callback<JSONArray>(){public void success(JSONArray people){if(!sheet.isShowing()||g!=generation[0])return;results.removeAllViews();int count=0;for(int i=0;i<people.length();i++){JSONObject p=people.optJSONObject(i);if(p==null||!p.optBoolean("is_friend"))continue;count++;TextView row=new TextView(activity);row.setText((selected.containsKey(p.optString("user_id"))?"✓  ":"＋  ")+p.optString("display_name","Meeto user"));row.setTextSize(15);row.setPadding(pad, pad/2,pad,pad/2);row.setMinHeight((int)(48*activity.getResources().getDisplayMetrics().density));row.setOnClickListener(v->{String id=p.optString("user_id");if(selected.containsKey(id))selected.remove(id);else if(selected.size()<10)selected.put(id,p);else state.setText("Có thể gắn thẻ tối đa 10 người.");renderChips.run();});results.addView(row);}state.setText(count==0?"Không tìm thấy bạn bè phù hợp.":"Đã chọn "+selected.size()+"/10");}public void error(String e){if(g==generation[0])state.setText(e+" · Thử tìm lại.");}});handler.postDelayed(pending[0],300);}
            public void afterTextChanged(Editable e){} });
        done.setOnClickListener(v->{JSONArray ids=new JSONArray(),people=new JSONArray();for(java.util.Map.Entry<String,JSONObject> e:selected.entrySet()){ids.put(e.getKey());people.put(e.getValue());}callback.selected(ids,people);sheet.dismiss();});
        sheet.setContentView(root);sheet.show();
    }
}
