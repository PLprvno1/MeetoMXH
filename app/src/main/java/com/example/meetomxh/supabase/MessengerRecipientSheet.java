package com.example.meetomxh.supabase;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.meetomxh.R;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.checkbox.MaterialCheckBox;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Presentation-only recipient picker. The caller keeps the existing share RPC/payload. */
public final class MessengerRecipientSheet {
    private MessengerRecipientSheet() {}
    public static void show(Activity activity, Consumer<List<String>> chosen) {
        if (!activity.isFinishing() && !activity.isDestroyed()) new Picker(activity, chosen).show();
    }
    private static final class Recipient {
        final String id, name, avatar, preset;
        final boolean group;
        Recipient(String id, String name, String avatar, String preset, boolean group) {
            this.id=id; this.name=name; this.avatar=avatar; this.preset=preset; this.group=group;
        }
    }
    private static final class Page {
        final List<Recipient> recipients;
        final String time, id;
        final boolean more;
        Page(List<Recipient> recipients, String time, String id, boolean more) {
            this.recipients=recipients; this.time=time; this.id=id; this.more=more;
        }
    }
    private static final class Picker {
        final Activity activity;
        final Consumer<List<String>> chosen;
        final long epoch = SessionManager.getInstance().generation();
        final String uid = SessionManager.getInstance().uid();
        final LinkedHashMap<String,Recipient> recipients = new LinkedHashMap<>();
        final LinkedHashSet<String> selected = new LinkedHashSet<>();
        final List<Recipient> visible = new ArrayList<>();
        final RecipientAdapter adapter = new RecipientAdapter();
        BottomSheetDialog sheet;
        EditText search;
        TextView status, count;
        View progress;
        Button send, more;
        Subscription request;
        String afterTime, afterId, failure;
        boolean loading, hasMore=true;
        Picker(Activity activity, Consumer<List<String>> chosen) { this.activity=activity; this.chosen=chosen; }
        boolean alive() { return !activity.isFinishing() && !activity.isDestroyed() && sheet.isShowing()
                && SessionManager.getInstance().generation()==epoch; }
        void show() {
            sheet=new BottomSheetDialog(activity);
            View body=LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_messenger_recipients,null,false);
            search=body.findViewById(R.id.share_recipients_search); status=body.findViewById(R.id.share_recipients_status);
            count=body.findViewById(R.id.share_recipients_count); progress=body.findViewById(R.id.share_recipients_loading);
            send=body.findViewById(R.id.share_recipients_send); more=body.findViewById(R.id.share_recipients_more);
            RecyclerView list=body.findViewById(R.id.share_recipients_list);
            list.setLayoutManager(new LinearLayoutManager(activity)); list.setAdapter(adapter); list.setItemAnimator(null);
            body.findViewById(R.id.share_recipients_cancel).setOnClickListener(v->sheet.dismiss());
            more.setOnClickListener(v->load());
            send.setOnClickListener(v->{if(!alive()||selected.isEmpty())return;List<String> ids=new ArrayList<>(selected);sheet.dismiss();chosen.accept(ids);});
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
                public void afterTextChanged(Editable text) {}
                public void onTextChanged(CharSequence s,int start,int before,int count) { filter(); }
            });
            sheet.setContentView(body);
            if(sheet.getWindow()!=null)sheet.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int screenHeight=activity.getResources().getDisplayMetrics().heightPixels;
            int normalHeight=Math.round(screenHeight*.8f), pad=dp(20);
            ViewCompat.setOnApplyWindowInsetsListener(body,(v,insets)->{
                androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                int keyboard=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
                ViewGroup.LayoutParams params=v.getLayoutParams();
                int height=Math.min(normalHeight,Math.max(dp(220),screenHeight-bars.top-Math.max(keyboard,bars.bottom)));
                if(params.height!=height){params.height=height;v.setLayoutParams(params);}
                v.setPadding(pad,pad,pad,pad+bars.bottom);return insets;
            });
            Runnable identityChanged=()->{if(SessionManager.getInstance().generation()!=epoch)sheet.dismiss();};
            SessionManager.getInstance().identityListeners.add(identityChanged);
            ChatCustomizationUi.own(activity,sheet,()->{
                if(request!=null)request.remove(); SessionManager.getInstance().identityListeners.remove(identityChanged);
            });
            sheet.setOnShowListener(d->{
                ViewGroup.LayoutParams params=body.getLayoutParams();params.height=normalHeight;body.setLayoutParams(params);
                sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
                ViewCompat.requestApplyInsets(body); load();
            });
            sheet.show();
        }
        void load() {
            if(!alive()||loading||!hasMore)return;
            loading=true;failure=null;update();
            String time=afterTime,id=afterId;
            request=Api.run(()->{
                JSONArray rows=(JSONArray)RpcClient.call("get_inbox",Api.obj("p_limit",30,"p_include_archived",false,
                        "p_after_time",time,"p_after_id",id));
                List<Recipient> found=new ArrayList<>();
                for(int i=0;i<rows.length();i++) {
                    Api.checkCancelled(); JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                    Chat chat=Dto.chat(row);if(ConversationVisibility.isHidden(uid,chat))continue;
                    boolean group="group".equals(chat.getType());
                    String name=group?clean(Dto.nullable(row,"title")):clean(chat.peerName);
                    String avatar=group?chat.avatar:null,preset="neutral";
                    if(!group) {
                        JSONArray members=(JSONArray)RpcClient.call("get_conversation_members",Api.obj("p_conversation_id",chat.getChatId()));
                        for(int j=0;j<members.length();j++) {
                            JSONObject member=members.optJSONObject(j);if(member==null||uid.equals(member.optString("user_id")))continue;
                            if(name.isEmpty())name=clean(ChatCustomizationClient.name(member));
                            avatar=Dto.asset(Dto.nullable(member,"avatar_asset_id"));preset=clean(Dto.nullable(member,"avatar_preset"));break;
                        }
                    }
                    if(name.isEmpty())name=group?"Nhóm chat":"Người dùng không khả dụng";
                    found.add(new Recipient(chat.getChatId(),name,avatar,preset,group));
                }
                JSONObject last=rows.length()==0?null:rows.optJSONObject(rows.length()-1);
                return new Page(found,last==null?time:last.getString("updated_at"),last==null?id:last.getString("id"),rows.length()==30);
            },new Api.Callback<Page>() {
                public void success(Page page) {
                    if(!alive())return;loading=false;
                    for(Recipient recipient:page.recipients)recipients.put(recipient.id,recipient);
                    afterTime=page.time;afterId=page.id;hasMore=page.more;filter();
                }
                public void error(String error) { if(alive()){loading=false;failure=error;update();} }
            });
        }
        void filter() {
            String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);visible.clear();
            for(Recipient recipient:recipients.values())if(recipient.name.toLowerCase(Locale.ROOT).contains(query))visible.add(recipient);
            adapter.notifyDataSetChanged();update();
        }
        void update() {
            count.setText(selected.isEmpty()?"Chọn một hoặc nhiều cuộc trò chuyện":"Đã chọn "+selected.size()+" cuộc trò chuyện");
            send.setEnabled(!selected.isEmpty());send.setText(selected.isEmpty()?"Gửi":"Gửi ("+selected.size()+")");
            progress.setVisibility(loading?View.VISIBLE:View.INVISIBLE);
            status.setText(failure!=null?failure:loading?"Đang tải cuộc trò chuyện…":visible.isEmpty()
                    ?(recipients.isEmpty()?"Chưa có cuộc trò chuyện để gửi.":"Không tìm thấy người nhận trong danh sách đã tải.")
                        +(hasMore?" Bạn có thể tải thêm cuộc trò chuyện.":""):"");
            status.setVisibility(status.getText().length()==0?View.GONE:View.VISIBLE);
            more.setVisibility(hasMore||failure!=null?View.VISIBLE:View.GONE);more.setEnabled(!loading);
            more.setText(failure==null?"Tải thêm cuộc trò chuyện":"Thử tải lại");
        }
        int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
        final class RecipientAdapter extends RecyclerView.Adapter<RecipientHolder> {
            @NonNull public RecipientHolder onCreateViewHolder(@NonNull ViewGroup parent,int type) {
                return new RecipientHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_messenger_recipient,parent,false));
            }
            public int getItemCount() { return visible.size(); }
            public void onBindViewHolder(@NonNull RecipientHolder holder,int position) {
                Recipient recipient=visible.get(position); boolean checked=selected.contains(recipient.id);
                holder.name.setText(recipient.name);holder.subtitle.setText(recipient.group?"Nhóm chat":"Cuộc trò chuyện cá nhân");
                holder.initials.setText(initials(recipient.name));holder.initials.setVisibility(recipient.group?View.GONE:View.VISIBLE);
                holder.groupIcon.setVisibility(recipient.group?View.VISIBLE:View.GONE);
                AvatarViews.loadWithoutPersonFallback(holder.avatar,recipient.avatar);
                holder.checked.setChecked(checked);holder.itemView.setSelected(checked);
                holder.itemView.setContentDescription(recipient.name+", "+(recipient.group?"nhóm chat":"cuộc trò chuyện cá nhân"));
                ViewCompat.setStateDescription(holder.itemView,checked?"Đã chọn":"Chưa chọn");
                ViewCompat.setAccessibilityDelegate(holder.itemView,new androidx.core.view.AccessibilityDelegateCompat(){
                    @Override public void onInitializeAccessibilityNodeInfo(@NonNull View host,@NonNull AccessibilityNodeInfoCompat info){
                        super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.CheckBox");
                        info.setCheckable(true);info.setChecked(selected.contains(recipient.id));
                    }
                });
                holder.itemView.setOnClickListener(v->{
                    if(!selected.remove(recipient.id))selected.add(recipient.id);
                    int index=holder.getBindingAdapterPosition();if(index!=RecyclerView.NO_POSITION)notifyItemChanged(index);update();
                });
            }
        }
    }
    private static final class RecipientHolder extends RecyclerView.ViewHolder {
        final TextView name,subtitle,initials;final ImageView avatar,groupIcon;final MaterialCheckBox checked;
        RecipientHolder(View view) {
            super(view);name=view.findViewById(R.id.recipient_name);subtitle=view.findViewById(R.id.recipient_subtitle);
            initials=view.findViewById(R.id.recipient_initials);avatar=view.findViewById(R.id.recipient_avatar);
            groupIcon=view.findViewById(R.id.recipient_group_icon);checked=view.findViewById(R.id.recipient_checked);
        }
    }
    private static String clean(String value) { return value==null||"null".equalsIgnoreCase(value.trim())?"":value.trim(); }
    private static String initials(String name) {
        String[] parts=name.trim().split("\\s+");StringBuilder result=new StringBuilder();
        if(parts.length>0&&!parts[0].isEmpty())result.appendCodePoint(parts[0].codePointAt(0));
        if(parts.length>1&&!parts[parts.length-1].isEmpty())result.appendCodePoint(parts[parts.length-1].codePointAt(0));
        return result.toString().toUpperCase(Locale.ROOT);
    }
}
