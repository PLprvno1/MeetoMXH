package com.example.meetomxh.supabase;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import com.example.meetomxh.ui.ChatThemeRegistry;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

public final class ChatCustomizationUi {
    public static void own(Activity a,Dialog dialog,Runnable dismissed){
        LifecycleEventObserver observer=(owner,event)->{if(event==Lifecycle.Event.ON_DESTROY)dialog.dismiss();};
        if(a instanceof LifecycleOwner)((LifecycleOwner)a).getLifecycle().addObserver(observer);
        dialog.setOnDismissListener(d->{if(a instanceof LifecycleOwner)((LifecycleOwner)a).getLifecycle().removeObserver(observer);dismissed.run();});
    }
    private static boolean alive(Activity a,Dialog d){return !a.isFinishing()&&!a.isDestroyed()&&d.isShowing();}
    private static int dp(Activity a,int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    private static LinearLayout column(Activity a){LinearLayout c=new LinearLayout(a);c.setOrientation(1);c.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));return c;}
    private static TextView label(Activity a,String text){TextView v=new TextView(a);v.setText(text);v.setTextSize(15);v.setTextColor(a.getColor(com.example.meetomxh.R.color.text_primary));v.setPadding(0,dp(a,8),0,dp(a,8));return v;}
    public static void nicknames(Activity a,String id,Runnable changed){
        LinearLayout body=column(a);TextView state=label(a,"Đang tải thành viên…");body.addView(state);
        ScrollView scroll=new ScrollView(a);LinearLayout rows=column(a);scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(a,340)));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Biệt danh trong hội thoại").setView(body).setNegativeButton("Đóng",null).create();
        Subscription[] request={null};
        Runnable load=()->{if(request[0]!=null)request[0].remove();state.setText("Đang tải thành viên…");request[0]=Api.run(()->ChatCustomizationClient.get(id),new Api.Callback<JSONObject>(){
            public void success(JSONObject settings){if(!alive(a,dialog))return;state.setText("Biệt danh dùng chung trong hội thoại này. Để trống để khôi phục tên hồ sơ.");rows.removeAllViews();JSONArray members=settings.optJSONArray("members");if(members==null)return;
                for(int i=0;i<members.length();i++){JSONObject member=members.optJSONObject(i);if(member==null)continue;
                    LinearLayout row=new LinearLayout(a);row.setGravity(android.view.Gravity.CENTER_VERTICAL);ImageView avatar=new ImageView(a);row.addView(avatar,new LinearLayout.LayoutParams(dp(a,40),dp(a,40)));AvatarViews.load(avatar,Dto.asset(Dto.nullable(member,"avatar_asset_id")),member.optString("avatar_preset","neutral"));
                    TextView name=label(a,ChatCustomizationClient.name(member));name.setPadding(dp(a,12),dp(a,14),0,dp(a,14));row.addView(name,new LinearLayout.LayoutParams(0,-2,1));rows.addView(row);
                    row.setOnClickListener(v->editNickname(a,id,member,settings.optLong("version"),()->{dialog.dismiss();changed.run();nicknames(a,id,changed);}));
                }}
            public void error(String error){if(alive(a,dialog)){state.setText(error+" · Chạm để thử lại");}}
        });};
        state.setOnClickListener(v->load.run());own(a,dialog,()->{if(request[0]!=null)request[0].remove();});dialog.setOnShowListener(d->load.run());dialog.show();
    }
    private static void editNickname(Activity a,String id,JSONObject member,long version,Runnable changed){
        EditText value=new EditText(a);value.setSingleLine(true);value.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});value.setText(Dto.nullable(member,"nickname"));String profile=Dto.nullable(member,"display_name");value.setHint("Tên hồ sơ: "+(profile==null?"Người dùng không khả dụng":profile));
        AlertDialog edit=new AlertDialog.Builder(a).setTitle(ChatCustomizationClient.name(member)).setMessage("Tối đa 40 ký tự. Để trống để xóa biệt danh.").setView(value).setNegativeButton("Hủy",null).setPositiveButton("Lưu",null).create();
        Subscription[] request={null};own(a,edit,()->{if(request[0]!=null)request[0].remove();});
        edit.setOnShowListener(d->edit.getButton(-1).setOnClickListener(v->{String nickname=value.getText().toString().trim();edit.getButton(-1).setEnabled(false);value.setEnabled(false);
            request[0]=Api.run(()->{ChatCustomizationClient.require();return RpcClient.object("set_chat_nickname",Api.obj("p_conversation_id",id,"p_target",member.optString("user_id"),"p_nickname",nickname,"p_expected_version",version));},new Api.Callback<JSONObject>(){
                public void success(JSONObject result){if(alive(a,edit)){edit.dismiss();changed.run();}}
                public void error(String error){if(alive(a,edit)){edit.getButton(-1).setEnabled(true);value.setEnabled(true);value.setError(error+" Nếu dữ liệu đã thay đổi, đóng và mở lại danh sách.");}}
            });}));edit.show();
    }
    /** Runs over ChatActivity, so each selection previews the real visible conversation. */
    public static void themes(Activity a,String id,Consumer<String> preview,Runnable changed,Runnable closed){
        BottomSheetDialog dialog=new BottomSheetDialog(a);LinearLayout body=column(a);
        TextView title=label(a,"Chủ đề đoạn chat");title.setTextSize(20);body.addView(title);
        TextView state=label(a,"Đang tải chủ đề…");body.addView(state);
        ScrollView scroll=new ScrollView(a);LinearLayout cards=column(a);scroll.addView(cards);body.addView(scroll,new LinearLayout.LayoutParams(-1,dp(a,300)));
        Button apply=new Button(a);apply.setText("Áp dụng");apply.setEnabled(false);body.addView(apply);
        Button cancel=new Button(a);cancel.setText("Hủy");body.addView(cancel);cancel.setOnClickListener(v->dialog.dismiss());dialog.setContentView(body);
        ChatThemeRegistry.Palette sheetPalette=ChatThemeRegistry.get("meeto_pink").palette(a);
        body.setBackgroundColor(sheetPalette.input);title.setTextColor(sheetPalette.text);state.setTextColor(sheetPalette.secondary);
        apply.setBackgroundTintList(android.content.res.ColorStateList.valueOf(sheetPalette.sent));apply.setTextColor(sheetPalette.sentText);
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(sheetPalette.received));cancel.setTextColor(sheetPalette.receivedText);
        final String[] original={null},selected={null};final long[] version={0};final Subscription[] request={null};
        own(a,dialog,()->{if(request[0]!=null)request[0].remove();if(original[0]!=null&&!a.isDestroyed())preview.accept(original[0]);closed.run();});
        dialog.setOnShowListener(d->{request[0]=Api.run(()->ChatCustomizationClient.get(id),new Api.Callback<JSONObject>(){
            public void success(JSONObject settings){if(!alive(a,dialog))return;original[0]=settings.optString("theme_id","meeto_pink");selected[0]=original[0];version[0]=settings.optLong("version");apply.setEnabled(true);
                state.setText("Đang dùng: "+ChatThemeRegistry.get(original[0]).name+" · Chọn để xem thử");
                for(ChatThemeRegistry.Theme theme:ChatThemeRegistry.ALL){ChatThemeRegistry.Palette p=theme.palette(a);
                    LinearLayout card=column(a);android.graphics.drawable.GradientDrawable border=ChatThemeRegistry.shape(p.background,12,a);border.setStroke(dp(a,1),p.border);card.setBackground(border);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(a,10);cards.addView(card,lp);
                    TextView name=label(a,theme.name);name.setTextColor(p.text);card.addView(name);
                    TextView incoming=label(a,"Chào bạn! · 09:45");incoming.setTextColor(p.receivedText);incoming.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));incoming.setBackground(ChatThemeRegistry.shape(p.received,12,a));LinearLayout.LayoutParams in=new LinearLayout.LayoutParams(-2,-2);in.gravity=android.view.Gravity.START;card.addView(incoming,in);
                    TextView outgoing=label(a,"Hẹn gặp nhé! · Đã gửi");outgoing.setTextColor(p.sentText);outgoing.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));outgoing.setBackground(ChatThemeRegistry.shape(p.sent,12,a));LinearLayout.LayoutParams out=new LinearLayout.LayoutParams(-2,-2);out.gravity=android.view.Gravity.END;out.topMargin=dp(a,6);card.addView(outgoing,out);
                    View.OnClickListener choose=v->{selected[0]=theme.id;state.setText("Xem thử: "+theme.name+" · Nhấn Áp dụng để lưu");preview.accept(theme.id);};card.setOnClickListener(choose);incoming.setOnClickListener(choose);outgoing.setOnClickListener(choose);name.setOnClickListener(choose);
                }}
            public void error(String error){if(alive(a,dialog))state.setText(error+" Đóng và mở lại để thử lại.");}
        });});
        apply.setOnClickListener(v->{if(selected[0]==null)return;String desired=selected[0];apply.setEnabled(false);cancel.setEnabled(false);dialog.setCancelable(false);state.setText("Đang lưu…");
            request[0]=Api.run(()->{ChatCustomizationClient.require();return RpcClient.object("set_chat_theme",Api.obj("p_conversation_id",id,"p_theme_id",desired,"p_expected_version",version[0]));},new Api.Callback<JSONObject>(){
                public void success(JSONObject result){if(!alive(a,dialog))return;if(!desired.equals(result.optString("theme_id"))){error("Máy chủ chưa xác nhận chủ đề.");return;}original[0]=desired;dialog.dismiss();changed.run();}
                public void error(String error){if(alive(a,dialog)){state.setText(error+" Đóng và mở lại để lấy trạng thái mới.");cancel.setEnabled(true);dialog.setCancelable(true);}}
            });});dialog.show();
    }
    private ChatCustomizationUi(){}
}
