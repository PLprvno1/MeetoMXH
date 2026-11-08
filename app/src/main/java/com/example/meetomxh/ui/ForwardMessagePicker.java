package com.example.meetomxh.ui;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.meetomxh.R;
import com.example.meetomxh.supabase.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.*;
import java.util.*;

/** Explicit multi-destination forwarding with stable per-destination retry identities. */
public final class ForwardMessagePicker {
    private final AppCompatActivity host;
    private final String sourceChat,sourceMessage;
    private final long epoch=SessionManager.getInstance().generation();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<JSONObject> rows=new ArrayList<>();
    private final LinkedHashMap<String,JSONObject> selected=new LinkedHashMap<>();
    private final Map<String,String> requestIds=new HashMap<>(),errors=new HashMap<>();
    private final Set<String> completed=new HashSet<>();
    private final List<Subscription> jobs=new ArrayList<>();
    private final Targets adapter=new Targets();
    private final TextInputEditText query;
    private final TextView status;
    private final MaterialButton send,more,close;
    private final AlertDialog dialog;
    private Subscription pageRequest;
    private String afterTime,afterId;
    private boolean loading,sending,closed,hasMore,failedPage,lastAppend;
    private int generation;
    private final Runnable search=()->load(false);
    private final DefaultLifecycleObserver lifecycle=new DefaultLifecycleObserver(){@Override public void onDestroy(LifecycleOwner owner){ForwardMessagePicker.this.close();}};

    public ForwardMessagePicker(AppCompatActivity host,String sourceChat,String sourceMessage,String preview){
        this.host=host;this.sourceChat=sourceChat;this.sourceMessage=sourceMessage;
        LinearLayout root=new LinearLayout(host);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(16));
        GradientDrawable background=new GradientDrawable();background.setColor(host.getColor(R.color.surface_bg));background.setCornerRadius(dp(24));root.setBackground(background);
        TextView title=text("Chuyển tiếp tin nhắn",20);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        TextView snippet=text(preview==null?"":preview,14);snippet.setMaxLines(2);snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);snippet.setTextColor(host.getColor(R.color.text_secondary));snippet.setPadding(0,dp(8),0,dp(12));root.addView(snippet);
        TextInputLayout searchBox=new TextInputLayout(host);searchBox.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);searchBox.setHint("Tìm nơi nhận");query=new TextInputEditText(host);query.setSingleLine(true);query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});searchBox.addView(query,new LinearLayout.LayoutParams(-1,-2));root.addView(searchBox);
        status=text("Đang tải nơi có thể chuyển tiếp…",13);status.setTextColor(host.getColor(R.color.text_secondary));status.setPadding(0,dp(10),0,dp(6));root.addView(status);
        RecyclerView list=new RecyclerView(host);list.setLayoutManager(new LinearLayoutManager(host));list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        more=new MaterialButton(host,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);more.setAllCaps(false);more.setText("Tải thêm");root.addView(more,new LinearLayout.LayoutParams(-1,-2));more.setOnClickListener(v->load(failedPage?lastAppend:true));
        LinearLayout actions=new LinearLayout(host);actions.setGravity(Gravity.CENTER_VERTICAL);root.addView(actions);
        close=new MaterialButton(host,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);close.setAllCaps(false);close.setText("Đóng");actions.addView(close,new LinearLayout.LayoutParams(-2,-2));
        send=new MaterialButton(host);send.setAllCaps(false);send.setText("Chuyển tiếp");send.setCornerRadius(dp(14));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,1);sp.leftMargin=dp(12);actions.addView(send,sp);send.setOnClickListener(v->send());
        dialog=new MaterialAlertDialogBuilder(host).setView(root).create();close.setOnClickListener(v->dialog.dismiss());
        dialog.setOnDismissListener(d->dispose());
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){if(sending)return;generation++;if(pageRequest!=null)pageRequest.remove();loading=false;hasMore=false;failedPage=false;afterTime=null;afterId=null;rows.clear();adapter.notifyDataSetChanged();update();handler.removeCallbacks(search);handler.postDelayed(search,300);}});
    }
    private int dp(int n){return Math.round(n*host.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(host);v.setText(value);v.setTextSize(size);v.setTextColor(host.getColor(R.color.text_primary));return v;}
    private boolean alive(){return !closed&&!host.isFinishing()&&!host.isDestroyed()&&epoch==SessionManager.getInstance().generation();}
    public boolean isShowing(){return !closed&&dialog.isShowing();}
    public void show(){
        if(!alive())return;host.getLifecycle().addObserver(lifecycle);dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setLayout(host.getResources().getDisplayMetrics().widthPixels-dp(32),Math.min(dp(650),(int)(host.getResources().getDisplayMetrics().heightPixels*.85f)));dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
        update();load(false);
    }
    private void update(){
        send.setEnabled(!sending&&!selected.isEmpty());send.setText(sending?"Đang chuyển tiếp…":"Chuyển tiếp"+(selected.isEmpty()?"":" · "+selected.size()));
        query.setEnabled(!sending);close.setEnabled(!sending);dialog.setCancelable(!sending);more.setEnabled(!loading&&!sending);
        more.setVisibility(!sending&&(hasMore||failedPage)?View.VISIBLE:View.GONE);more.setText(failedPage?"Thử tải lại":"Tải thêm");
    }
    private void load(boolean append){
        if(!alive()||sending||loading)return;
        handler.removeCallbacks(search);if(pageRequest!=null)pageRequest.remove();int token=++generation;
        if(!append){afterTime=null;afterId=null;hasMore=false;rows.clear();adapter.notifyDataSetChanged();}
        String term=query.getText()==null?"":query.getText().toString().trim(),time=afterTime,id=afterId;
        loading=true;lastAppend=append;failedPage=false;status.setText("Đang tải nơi có thể chuyển tiếp…");update();
        pageRequest=Api.run(()->(JSONArray)RpcClient.call("list_message_forward_targets",Api.obj("p_source_conversation_id",sourceChat,"p_query",term,"p_after_time",time,"p_after_id",id,"p_limit",30)),new Api.Callback<JSONArray>(){
            public void success(JSONArray page){
                if(!alive()||token!=generation)return;loading=false;Set<String> seen=new HashSet<>();for(JSONObject row:rows)seen.add(row.optString("id"));
                for(int n=0;n<page.length();n++){JSONObject row=page.optJSONObject(n);if(row!=null&&seen.add(row.optString("id")))rows.add(row);}
                hasMore=page.length()==30;if(page.length()>0){JSONObject last=page.optJSONObject(page.length()-1);afterTime=last.optString("updated_at");afterId=last.optString("id");}
                status.setText(rows.isEmpty()?"Chưa có nơi nhận phù hợp. Hãy thử tên khác hoặc kiểm tra quyền nhắn tin.":"Chọn nơi nhận · tối đa 20 cuộc trò chuyện");adapter.notifyDataSetChanged();update();
            }
            public void error(String e){if(!alive()||token!=generation)return;loading=false;failedPage=true;status.setText(e);update();}
        });jobs.add(pageRequest);
    }
    private static final class Batch {final Set<String> done=new HashSet<>();final Map<String,String> failed=new HashMap<>();}
    private void send(){
        if(!alive()||sending||selected.isEmpty())return;
        handler.removeCallbacks(search);generation++;if(pageRequest!=null)pageRequest.remove();loading=false;
        List<String> targets=new ArrayList<>(selected.keySet());for(String id:targets)if(!requestIds.containsKey(id))requestIds.put(id,UUID.randomUUID().toString());
        sending=true;update();adapter.notifyDataSetChanged();status.setText("Đang gửi đến "+targets.size()+" nơi đã chọn…");
        jobs.add(Api.run(()->{
            Batch batch=new Batch();for(String id:targets){Api.checkCancelled();try{
                RpcClient.call("forward_text_message",Api.obj("p_source_message_id",sourceMessage,"p_target_conversation_id",id,"p_client_request_id",requestIds.get(id)));batch.done.add(id);
            }catch(Exception e){Api.checkCancelled();batch.failed.put(id,ErrorMapper.message(e));}}return batch;
        },new Api.Callback<Batch>(){
            public void success(Batch batch){
                if(!alive())return;sending=false;completed.addAll(batch.done);for(String id:batch.done){selected.remove(id);errors.remove(id);}errors.putAll(batch.failed);adapter.notifyDataSetChanged();update();
                if(batch.failed.isEmpty()){Toast.makeText(host,"Đã chuyển tiếp đến "+batch.done.size()+" cuộc trò chuyện.",Toast.LENGTH_LONG).show();dialog.dismiss();}
                else status.setText("Đã gửi "+batch.done.size()+" nơi; "+batch.failed.size()+" nơi chưa gửi được. Có thể bỏ chọn hoặc thử lại các nơi còn lại.");
            }
            public void error(String e){if(!alive())return;sending=false;status.setText(e);adapter.notifyDataSetChanged();update();}
        }));
    }
    private final class Targets extends RecyclerView.Adapter<Holder>{
        public Holder onCreateViewHolder(ViewGroup parent,int type){return new Holder(LayoutInflater.from(host).inflate(R.layout.item_forward_target,parent,false));}
        public void onBindViewHolder(Holder h,int position){
            JSONObject row=rows.get(position);String id=row.optString("id");boolean sent=completed.contains(id);h.name.setText(row.optString("display_name","Cuộc trò chuyện"));
            h.detail.setText(sent?"Đã chuyển tiếp":errors.containsKey(id)?errors.get(id):("group".equals(row.optString("kind"))?"Nhóm chat":"Tin nhắn riêng")+(row.optBoolean("archived")?" · Đã lưu trữ":""));
            String asset=Dto.nullable(row,"avatar_asset_id");AvatarViews.load(h.avatar,Dto.asset(asset),row.optString("avatar_preset","neutral"));if(asset==null&&"group".equals(row.optString("kind")))h.avatar.setImageResource(R.drawable.ic_groups);
            h.check.setOnCheckedChangeListener(null);h.check.setChecked(sent||selected.containsKey(id));h.check.setEnabled(!sending&&!sent);h.check.setContentDescription("Chọn nơi nhận "+h.name.getText());
            h.check.setOnCheckedChangeListener((button,checked)->{
                if(!alive()||sending||sent)return;if(checked){if(selected.size()>=20){button.setChecked(false);status.setText("Chọn tối đa 20 nơi mỗi lần.");return;}selected.put(id,row);}else selected.remove(id);update();
            });h.itemView.setOnClickListener(v->{if(h.check.isEnabled())h.check.performClick();});h.itemView.setAlpha(sent?.65f:1f);
        }
        public int getItemCount(){return rows.size();}
    }
    private static final class Holder extends RecyclerView.ViewHolder{
        final ImageView avatar;final TextView name,detail;final MaterialCheckBox check;
        Holder(View v){super(v);avatar=v.findViewById(R.id.forwardAvatar);name=v.findViewById(R.id.forwardName);detail=v.findViewById(R.id.forwardDetail);check=v.findViewById(R.id.forwardCheck);}
    }
    private void dispose(){if(closed)return;closed=true;generation++;handler.removeCallbacksAndMessages(null);for(Subscription job:jobs)job.remove();jobs.clear();host.getLifecycle().removeObserver(lifecycle);}
    public void close(){dialog.dismiss();dispose();}
}
