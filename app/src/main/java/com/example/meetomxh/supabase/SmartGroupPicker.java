package com.example.meetomxh.supabase;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import com.example.meetomxh.ui.AvatarViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Follow suggestions and server-authorized invitation eligibility, not automatic membership. */
final class SmartGroupPicker {
    private final Activity activity;
    private final String conversation,preselect;
    private final Set<String> excluded;
    private final int minimum;
    private final PeoplePicker.Selection callback;
    private final LinkedHashMap<String,JSONObject> selected=new LinkedHashMap<>(),shown=new LinkedHashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<Subscription> requests=new ArrayList<>();
    private AlertDialog dialog;
    private LinearLayout rows,chips;
    private TextView state,count;
    private EditText search;
    private Button more;
    private String afterName,afterId;
    private boolean loading;
    private long generation;
    private Subscription pageRequest;
    private Runnable debounce;
    SmartGroupPicker(Activity a,Set<String> excluded,int minimum,String conversation,String preselect,PeoplePicker.Selection callback){
        activity=a;this.excluded=new HashSet<>(excluded);this.excluded.add(SessionManager.getInstance().uid());
        this.minimum=minimum;this.conversation=conversation;this.preselect=preselect;this.callback=callback;
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    void show(){
        LinearLayout body=new LinearLayout(activity);body.setOrientation(1);body.setPadding(dp(16),0,dp(16),0);
        LinearLayout query=new LinearLayout(activity);search=new EditText(activity);search.setSingleLine(true);search.setHint("Tìm tên hoặc username");query.addView(search,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=new Button(activity);clear.setText("×");clear.setContentDescription("Xóa từ khóa");query.addView(clear,new LinearLayout.LayoutParams(dp(48),dp(48)));clear.setOnClickListener(v->search.setText(""));body.addView(query);
        count=new TextView(activity);body.addView(count);
        HorizontalScrollView selectedScroll=new HorizontalScrollView(activity);chips=new LinearLayout(activity);selectedScroll.addView(chips);body.addView(selectedScroll);
        state=new TextView(activity);state.setPadding(0,dp(8),0,dp(8));state.setOnClickListener(v->load(true));body.addView(state);
        ScrollView scroll=new ScrollView(activity);rows=new LinearLayout(activity);rows.setOrientation(1);scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(300)));
        more=new Button(activity);more.setText("Tải thêm");more.setVisibility(View.GONE);more.setOnClickListener(v->load(false));body.addView(more);
        dialog=new AlertDialog.Builder(activity).setTitle("Chọn người nhận lời mời").setView(body).setNegativeButton("Hủy",null).setPositiveButton("Tiếp tục",null).create();
        dialog.setOnShowListener(d->{updateSelection();dialog.getButton(-1).setOnClickListener(v->{List<String> ids=new ArrayList<>(selected.keySet());dialog.dismiss();callback.selected(ids);});load(true);loadPreselected();});
        ChatCustomizationUi.own(activity,dialog,()->{generation++;for(Subscription request:requests)request.remove();if(debounce!=null)handler.removeCallbacks(debounce);});
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void afterTextChanged(android.text.Editable e){}
            public void onTextChanged(CharSequence s,int start,int before,int count){generation++;if(pageRequest!=null)pageRequest.remove();loading=false;afterName=null;afterId=null;shown.clear();rows.removeAllViews();more.setVisibility(View.GONE);state.setText("Đang tìm…");if(debounce!=null)handler.removeCallbacks(debounce);debounce=()->load(true);handler.postDelayed(debounce,300);}
        });dialog.show();
    }
    private boolean alive(){return !activity.isFinishing()&&!activity.isDestroyed()&&dialog.isShowing();}
    private void loadPreselected(){
        if(preselect==null||excluded.contains(preselect))return;
        Subscription request=Api.run(()->ChatCustomizationClient.people("",null,null,conversation,preselect),new Api.Callback<JSONArray>(){
            public void success(JSONArray page){if(!alive())return;JSONObject p=page.optJSONObject(0);if(p!=null&&"eligible".equals(p.optString("eligibility"))){selected.put(p.optString("profile_id"),p);updateSelection();render();}
                else Toast.makeText(activity,"Người đang chat chưa đủ điều kiện mời. Bạn có thể chọn người khác.",Toast.LENGTH_LONG).show();}
            public void error(String error){if(alive())Toast.makeText(activity,"Chưa chọn sẵn người đang chat: "+error,Toast.LENGTH_LONG).show();}
        });requests.add(request);
    }
    private void load(boolean reset){
        if(loading||!alive())return;
        if(reset){afterName=null;afterId=null;shown.clear();rows.removeAllViews();}
        loading=true;state.setText("Đang tải danh sách…");more.setEnabled(false);long token=generation;
        String query=search.getText().toString().trim(),name=afterName,id=afterId;
        pageRequest=Api.run(()->ChatCustomizationClient.people(query,name,id,conversation,null),new Api.Callback<JSONArray>(){
            public void success(JSONArray page){if(!alive()||token!=generation)return;loading=false;more.setEnabled(true);more.setVisibility(page.length()==30?View.VISIBLE:View.GONE);
                for(int i=0;i<page.length();i++){JSONObject p=page.optJSONObject(i);if(p==null)continue;String uid=p.optString("profile_id");afterName=p.optString("cursor_name");afterId=uid;if(!uid.equals(SessionManager.getInstance().uid()))shown.put(uid,p);}
                render();state.setText(shown.isEmpty()?(query.isEmpty()?"Chưa có người đang theo dõi hoặc theo dõi bạn có thể hiển thị. Hãy tìm theo tên/username.":"Không tìm thấy người phù hợp."):query.isEmpty()?"Đang theo dõi / Theo dõi bạn · Chọn nhiều người để gửi lời mời":"Kết quả tìm kiếm");}
            public void error(String error){if(!alive()||token!=generation)return;loading=false;more.setEnabled(true);state.setText(error+" · Chạm để thử lại");}
        });requests.add(pageRequest);
    }
    private void render(){
        rows.removeAllViews();
        for(JSONObject p:shown.values()){
            String id=p.optString("profile_id"),eligibility=p.optString("eligibility");boolean eligible="eligible".equals(eligibility)&&!excluded.contains(id);
            LinearLayout row=new LinearLayout(activity);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,dp(8),0,dp(8));
            ImageView avatar=new ImageView(activity);row.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));AvatarViews.load(avatar,Dto.asset(Dto.nullable(p,"avatar_asset_id")),p.optString("avatar_preset","neutral"));
            TextView identity=new TextView(activity);identity.setTextColor(activity.getColor(com.example.meetomxh.R.color.text_primary));identity.setTextSize(14);identity.setPadding(dp(10),0,dp(6),0);
            boolean following=p.optBoolean("following"),follower=p.optBoolean("follower");String relation=following&&follower?"Theo dõi lẫn nhau":following?"Đang theo dõi":follower?"Theo dõi bạn":"";
            String username=Dto.nullable(p,"username");String reason=eligible?"":"member".equals(eligibility)?"Đã là thành viên":"invited_or_declined".equals(eligibility)||excluded.contains(id)?"Đã mời / không thể mời lại":"follow_required".equals(eligibility)?"Cần được chấp nhận theo dõi":"Không có quyền mời";
            identity.setText(p.optString("display_name","Người dùng")+(username==null?"":"\n@"+username)+(relation.isEmpty()?"":"\n"+relation)+(reason.isEmpty()?"":"\n"+reason));row.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
            CheckBox box=new CheckBox(activity);box.setChecked(selected.containsKey(id));box.setClickable(false);box.setFocusable(false);row.addView(box);row.setEnabled(eligible);row.setAlpha(eligible?1f:.65f);
            row.setOnClickListener(v->{if(!eligible)return;if(selected.containsKey(id))selected.remove(id);else if(selected.size()<49)selected.put(id,p);else Toast.makeText(activity,"Chọn tối đa 49 người.",Toast.LENGTH_SHORT).show();updateSelection();render();});rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
        }
    }
    private void updateSelection(){
        count.setText("Đã chọn: "+selected.size()+" · Tối thiểu "+minimum+" người");chips.removeAllViews();
        for(Map.Entry<String,JSONObject> entry:selected.entrySet()){
            Button chip=new Button(activity);chip.setAllCaps(false);chip.setText(entry.getValue().optString("display_name","Người dùng")+" ×");chip.setOnClickListener(v->{selected.remove(entry.getKey());updateSelection();render();});chips.addView(chip);
        }
        if(dialog.getButton(-1)!=null)dialog.getButton(-1).setEnabled(selected.size()>=minimum&&selected.size()<=49);
    }
}
