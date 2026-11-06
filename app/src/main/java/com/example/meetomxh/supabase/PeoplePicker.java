package com.example.meetomxh.supabase;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Server-paged search; selections survive searches and pagination. */
public final class PeoplePicker {
    public interface Selection { void selected(List<String> ids); }
    private PeoplePicker() { }
    public static void show(Activity a,Set<String> excluded,int minimum,boolean multiple,Selection callback) {
        new Picker(a,excluded,minimum,multiple,callback).show();
    }
    private static final class Picker {
        final Activity a; final Set<String> excluded; final int minimum; final boolean multiple; final Selection callback;
        final LinkedHashMap<String,String> selected=new LinkedHashMap<>();
        final Handler handler=new Handler(Looper.getMainLooper());
        LinearLayout rows; TextView state,chosen; EditText search; Button more; AlertDialog dialog;
        String afterName,afterId; long generation; boolean loading; Subscription request; Runnable pending;
        Picker(Activity a,Set<String> excluded,int minimum,boolean multiple,Selection callback){this.a=a;this.excluded=new HashSet<>(excluded);this.excluded.add(SessionManager.getInstance().uid());this.minimum=minimum;this.multiple=multiple;this.callback=callback;}
        void show(){
            LinearLayout body=new LinearLayout(a);body.setOrientation(1);int pad=(int)(20*a.getResources().getDisplayMetrics().density);body.setPadding(pad,0,pad,0);
            search=new EditText(a);search.setSingleLine(true);search.setHint("Tìm theo tên (ít nhất 2 ký tự)");body.addView(search);
            chosen=new TextView(a);chosen.setMaxLines(3);chosen.setEllipsize(android.text.TextUtils.TruncateAt.END);body.addView(chosen);state=new TextView(a);body.addView(state);
            ScrollView scroll=new ScrollView(a);rows=new LinearLayout(a);rows.setOrientation(1);scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,(int)(260*a.getResources().getDisplayMetrics().density)));
            more=new Button(a);more.setText("Tải thêm");more.setVisibility(android.view.View.GONE);more.setOnClickListener(v->load(false));body.addView(more);
            dialog=new AlertDialog.Builder(a).setTitle(multiple?"Chọn người nhận lời mời":"Tin nhắn mới").setView(body).setNegativeButton("Hủy",null).setPositiveButton(multiple?"Tiếp tục":"Nhắn tin",null).create();
            dialog.setOnShowListener(d->{update();dialog.getButton(-1).setOnClickListener(v->{callback.selected(new ArrayList<>(selected.keySet()));dialog.dismiss();});});
            dialog.setOnDismissListener(d->{generation++;if(request!=null)request.remove();if(pending!=null)handler.removeCallbacks(pending);});
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int aft){} public void afterTextChanged(Editable e){} public void onTextChanged(CharSequence s,int st,int before,int count){generation++;if(request!=null)request.remove();loading=false;if(pending!=null)handler.removeCallbacks(pending);pending=()->load(true);handler.postDelayed(pending,300);}});
            state.setText("Tìm người để chọn. Thành viên đã có và lời mời đang chờ sẽ được bỏ qua.");dialog.show();
        }
        void update(){chosen.setText("Đã chọn "+selected.size()+": "+android.text.TextUtils.join(", ",selected.values()));if(dialog.getButton(-1)!=null)dialog.getButton(-1).setEnabled(selected.size()>=minimum&&selected.size()<=49);}
        void load(boolean reset){
            String query=search.getText().toString().trim();if(query.length()<2){rows.removeAllViews();more.setVisibility(android.view.View.GONE);return;}
            if(loading)return;if(reset){afterName=null;afterId=null;rows.removeAllViews();}loading=true;state.setText("Đang tìm…");more.setEnabled(false);long token=generation;
            request=Api.run(()->(JSONArray)RpcClient.call("search_advanced_people",Api.obj("p_query",query,"p_after_name",afterName,"p_after_id",afterId,"p_limit",30)),new Api.Callback<JSONArray>(){
                public void success(JSONArray data){if(token!=generation||a.isFinishing())return;loading=false;more.setEnabled(true);more.setVisibility(data.length()==30?android.view.View.VISIBLE:android.view.View.GONE);
                    for(int i=0;i<data.length();i++){JSONObject p=data.optJSONObject(i);if(p==null)continue;String id=p.optString("profile_id"),name=p.optString("display_name","Người dùng");afterName=p.optString("cursor_name");afterId=id;if(excluded.contains(id))continue;
                        LinearLayout row=new LinearLayout(a);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,(int)(4*a.getResources().getDisplayMetrics().density),0,(int)(4*a.getResources().getDisplayMetrics().density));
                        ImageView avatar=new ImageView(a);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(avatar,new LinearLayout.LayoutParams((int)(44*a.getResources().getDisplayMetrics().density),(int)(44*a.getResources().getDisplayMetrics().density)));
                        com.example.meetomxh.ui.AvatarViews.load(avatar,Dto.asset(Dto.nullable(p,"avatar_asset_id")),p.optString("avatar_preset","neutral"));
                        LinearLayout identity=new LinearLayout(a);identity.setOrientation(1);identity.setPadding((int)(10*a.getResources().getDisplayMetrics().density),0,0,0);row.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
                        TextView display=new TextView(a);display.setText(name);display.setTextSize(16);display.setTextColor(a.getColor(com.example.meetomxh.R.color.text_primary));identity.addView(display);
                        String username=p.optString("username","");if(!username.isEmpty()){TextView handle=new TextView(a);handle.setText("@"+username);handle.setTextSize(13);handle.setTextColor(a.getColor(com.example.meetomxh.R.color.text_secondary));identity.addView(handle);}
                        CheckBox box=new CheckBox(a);box.setChecked(selected.containsKey(id));row.addView(box);rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
                        Runnable toggle=()->box.setChecked(!box.isChecked());row.setOnClickListener(v->toggle.run());display.setOnClickListener(v->toggle.run());avatar.setOnClickListener(v->toggle.run());
                        box.setOnCheckedChangeListener((b,checked)->{if(checked){if(multiple&&selected.size()>=49&&!selected.containsKey(id)){box.setChecked(false);Toast.makeText(a,"Chọn tối đa 49 người.",Toast.LENGTH_SHORT).show();return;}if(!multiple){selected.clear();for(int j=0;j<rows.getChildCount();j++){android.view.View child=rows.getChildAt(j);if(child!=row&&child instanceof LinearLayout){CheckBox other=(CheckBox)((LinearLayout)child).getChildAt(2);other.setChecked(false);}}}selected.put(id,name);}else selected.remove(id);update();});
                    }state.setText(rows.getChildCount()==0?"Không có kết quả phù hợp.":"");}
                public void error(String e){if(token!=generation)return;loading=false;state.setText(e+" · Chạm để thử lại");state.setOnClickListener(v->load(true));more.setEnabled(true);}
            });
        }
    }
}
