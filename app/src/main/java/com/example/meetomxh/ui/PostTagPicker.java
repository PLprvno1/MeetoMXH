package com.example.meetomxh.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.example.meetomxh.R;
import com.example.meetomxh.supabase.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.imageview.ShapeableImageView;
import org.json.*;
import java.util.LinkedHashMap;

public final class PostTagPicker {
    public interface Result { void selected(JSONArray userIds,JSONArray people); }
    private PostTagPicker(){}
    public static void show(Activity activity,JSONArray existing,Result callback){
        LinkedHashMap<String,JSONObject> selected=new LinkedHashMap<>();
        if(existing!=null)for(int i=0;i<existing.length();i++){JSONObject p=existing.optJSONObject(i);if(p!=null){String id=Dto.nullable(p,"user_id");if(id!=null)selected.put(id,p);}}
        long epoch=SessionManager.getInstance().generation();
        BottomSheetDialog sheet=new BottomSheetDialog(activity);
        int pad=Math.round(16*activity.getResources().getDisplayMetrics().density);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(activity);title.setText("Gắn thẻ người dùng");title.setTextSize(20);title.setTextColor(activity.getColor(R.color.text_primary));title.setTypeface(null,android.graphics.Typeface.BOLD);root.addView(title);
        TextView hint=new TextView(activity);hint.setText("Chọn người bạn đang theo dõi hoặc người đang theo dõi bạn.");hint.setTextColor(activity.getColor(R.color.text_secondary));root.addView(hint);
        EditText query=new EditText(activity);query.setSingleLine(true);query.setHint("Tìm tên hoặc biệt danh");query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});root.addView(query);
        ScrollView scroll=new ScrollView(activity);LinearLayout list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,Math.round(320*activity.getResources().getDisplayMetrics().density)));
        ChipGroup chips=new ChipGroup(activity);chips.setSingleLine(false);list.addView(chips);
        TextView state=new TextView(activity);state.setTextColor(activity.getColor(R.color.text_secondary));list.addView(state);
        LinearLayout results=new LinearLayout(activity);results.setOrientation(LinearLayout.VERTICAL);list.addView(results);
        MaterialButton done=new MaterialButton(activity);done.setText("Xong");done.setAllCaps(false);root.addView(done,new LinearLayout.LayoutParams(-1,-2));
        Handler handler=new Handler(Looper.getMainLooper());int[] generation={0};Runnable[] pending={null},renderResults={null};Subscription[] request={null};JSONArray[] found={new JSONArray()};
        Runnable renderChips=()->{
            chips.removeAllViews();for(java.util.Map.Entry<String,JSONObject> e:selected.entrySet()){
                Chip c=new Chip(activity);c.setText(e.getValue().optString("display_name","Người dùng"));c.setCloseIconVisible(true);
                c.setOnCloseIconClickListener(v->{selected.remove(e.getKey());renderResults[0].run();});chips.addView(c);
            }
            done.setText("Xong · "+selected.size()+"/10");
        };
        renderResults[0]=()->{
            renderChips.run();results.removeAllViews();
            for(int i=0;i<found[0].length();i++){
                JSONObject p=found[0].optJSONObject(i);if(p==null)continue;String id=Dto.nullable(p,"user_id");if(id==null)continue;
                boolean chosen=selected.containsKey(id),eligible=p.optBoolean("can_tag",false);
                LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,pad/2,0,pad/2);
                ShapeableImageView avatar=new ShapeableImageView(activity);avatar.setShapeAppearanceModel(avatar.getShapeAppearanceModel().toBuilder().setAllCornerSizes(pad*2f).build());row.addView(avatar,new LinearLayout.LayoutParams(pad*3,pad*3));
                AvatarViews.load(avatar,Dto.asset(Dto.nullable(p,"avatar_asset_id")),p.optString("avatar_preset","neutral"));
                TextView name=new TextView(activity);String username=Dto.nullable(p,"username");
                name.setText(p.optString("display_name","Người dùng")+(username==null?"":"\n@"+username)+(!eligible?"\nKhông cho phép gắn thẻ":""));name.setTextSize(14);name.setTextColor(activity.getColor(R.color.text_primary));name.setPadding(pad/2,0,pad/2,0);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                TextView mark=new TextView(activity);mark.setText(chosen?"✓":"＋");mark.setTextSize(22);mark.setTextColor(ContextCompat.getColor(activity,R.color.brand_blue));row.addView(mark);
                row.setAlpha(eligible||chosen?1f:.5f);row.setSelected(chosen);row.setContentDescription(name.getText()+(chosen?", đã chọn":""));
                row.setOnClickListener(v->{
                    if(selected.containsKey(id))selected.remove(id);
                    else if(!eligible){state.setText("Người này không cho phép gắn thẻ. Bạn vẫn có thể bỏ lựa chọn cũ.");return;}
                    else if(selected.size()<10)selected.put(id,p);
                    else{state.setText("Có thể gắn thẻ tối đa 10 người.");return;}
                    renderResults[0].run();
                });results.addView(row,new LinearLayout.LayoutParams(-1,-2));
            }
            state.setText(found[0].length()==0?"Không có người phù hợp trong danh sách theo dõi. Hãy thử tên hoặc biệt danh khác.":"Đã chọn "+selected.size()+"/10 · Thẻ có thể cần người nhận duyệt.");
        };
        Runnable search=()->{
            if(pending[0]!=null)handler.removeCallbacks(pending[0]);if(request[0]!=null)request[0].remove();
            int token=++generation[0];String q=query.getText().toString().trim();results.removeAllViews();state.setText("Đang tải danh sách…");
            pending[0]=()->request[0]=Api.run(()->(JSONArray)RpcClient.call("search_mention_people",Api.obj("p_query",q,"p_limit",12)),new Api.Callback<JSONArray>(){
                public void success(JSONArray people){if(!sheet.isShowing()||token!=generation[0]||epoch!=SessionManager.getInstance().generation())return;found[0]=people;renderResults[0].run();}
                public void error(String e){if(sheet.isShowing()&&token==generation[0])state.setText(e+" · Thay đổi từ khóa để thử lại.");}
            });handler.postDelayed(pending[0],q.isEmpty()?0:300);
        };
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){search.run();}public void afterTextChanged(Editable e){}});
        done.setOnClickListener(v->{
            if(epoch!=SessionManager.getInstance().generation()){sheet.dismiss();return;}
            JSONArray ids=new JSONArray(),people=new JSONArray();for(java.util.Map.Entry<String,JSONObject> e:selected.entrySet()){ids.put(e.getKey());people.put(e.getValue());}callback.selected(ids,people);sheet.dismiss();
        });
        DefaultLifecycleObserver observer=new DefaultLifecycleObserver(){@Override public void onDestroy(LifecycleOwner owner){sheet.dismiss();}};
        if(activity instanceof LifecycleOwner)((LifecycleOwner)activity).getLifecycle().addObserver(observer);
        sheet.setOnDismissListener(d->{generation[0]++;handler.removeCallbacksAndMessages(null);if(request[0]!=null)request[0].remove();if(activity instanceof LifecycleOwner)((LifecycleOwner)activity).getLifecycle().removeObserver(observer);});
        sheet.setContentView(root);sheet.show();renderChips.run();search.run();
    }
}