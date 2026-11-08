package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.example.meetomxh.databinding.ActivitySettingsBinding;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.MeetoPrefs;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;
import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        View connections=findViewById(R.id.connectionSettingsCard);
        View tags=connections.findViewById(R.id.settingsOptionOne);
        configureOption(tags,R.drawable.ic_tag,"Quyền gắn thẻ & bài viết có gắn thẻ",null);
        tags.setOnClickListener(v->startActivity(new Intent(this,TagManagementActivity.class)));
        View follows=connections.findViewById(R.id.settingsOptionTwo);
        configureOption(follows,R.drawable.ic_person,"Yêu cầu theo dõi",null);
        follows.setOnClickListener(v->startActivity(new Intent(this,RequestCenterActivity.class).putExtra("mode","follow")));
        View messages=connections.findViewById(R.id.settingsOptionThree);
        configureOption(messages,R.drawable.ic_messenger,"Tin nhắn chờ",null);
        messages.setOnClickListener(v->startActivity(new Intent(this,RequestCenterActivity.class).putExtra("mode","message")));
        binding.btnBack.setOnClickListener(v -> finish());
        binding.rowThemeModeSettings.setOnClickListener(v -> chooseThemeMode());
        updateThemeModeLabel();
        binding.rowEditProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));
        binding.rowAccountInfo.setOnClickListener(v -> accountInfo());
        binding.rowChangePassword.setOnClickListener(v -> startActivity(new Intent(this, ChangePasswordActivity.class)));
        binding.rowPrivacy.setOnClickListener(v -> privacyCenter());
        binding.rowBlocked.setOnClickListener(v -> blockedUsers());
        binding.rowNotificationsSetting.setOnClickListener(v -> notificationSettings());
        binding.rowPlatformModeration.setVisibility(View.GONE);
        Api.run(() -> RpcClient.call("admin_get_capabilities", Api.obj()), new Api.Callback<Object>() {
            @Override public void success(Object value) { if(!isFinishing() && !isDestroyed() && value instanceof JSONObject && ((JSONObject)value).optInt("contract_version")==2) binding.rowPlatformModeration.setVisibility(((JSONObject)value).optBoolean("can_review_reports")?View.VISIBLE:View.GONE); }
            @Override public void error(String error) { if(!isFinishing() && !isDestroyed()) binding.rowPlatformModeration.setVisibility(View.GONE); }
        });
        binding.rowPlatformModeration.setOnClickListener(v -> startActivity(new Intent(this, ModerationDashboardActivity.class)));
        binding.rowDeleteAccount.setOnClickListener(v -> requestDeletion());
        binding.rowHelp.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Về Meeto")
                .setMessage("Meeto · Android và Supabase").setPositiveButton("Đóng", null).show());
        binding.btnLogoutSettings.setOnClickListener(v -> {
            SessionManager.getInstance().signOut();
            startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
    }

    private void chooseThemeMode() {
        int current=MeetoPrefs.theme(this);
        int selected=current==AppCompatDelegate.MODE_NIGHT_NO?1:current==AppCompatDelegate.MODE_NIGHT_YES?2:0;
        new AlertDialog.Builder(this).setTitle("Giao diện")
                .setSingleChoiceItems(new String[]{"Theo hệ thống","Sáng","Tối"},selected,(dialog,which)->{
                    int mode=which==0?AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:
                            which==1?AppCompatDelegate.MODE_NIGHT_NO:AppCompatDelegate.MODE_NIGHT_YES;
                    setThemeMode(mode);
                    dialog.dismiss();
                }).setNegativeButton("Đóng",null).show();
    }

    private void updateThemeModeLabel() {
        int mode=MeetoPrefs.theme(this);
        String label=mode==AppCompatDelegate.MODE_NIGHT_YES?"Tối":
                mode==AppCompatDelegate.MODE_NIGHT_NO?"Sáng":"Theo hệ thống";
        binding.tvThemeModeSettings.setText(label);
    }

    private void setThemeMode(int mode) {
        MeetoPrefs.setTheme(this, mode);
        AppCompatDelegate.setDefaultNightMode(mode);
        updateThemeModeLabel();
    }

    private void accountInfo() {
        SessionUser user = SessionManager.getInstance().getCurrentUser();
        if (user == null) return;
        Api.run(() -> RpcClient.object("get_profile", Api.obj("p_target", user.getUid())), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject profile) {
                new AlertDialog.Builder(SettingsActivity.this).setTitle("Tài khoản")
                        .setMessage("Email: " + user.getEmail() + "\nXác minh email: "
                                + (profile.optBoolean("email_verified") ? "Đã xác minh" : "Chưa xác minh"))
                        .setPositiveButton("Đóng", null).show();
            }
            @Override public void error(String error) { toast(error); }
        });
    }

    private void privacyCenter() {
        SessionUser user = SessionManager.getInstance().getCurrentUser();
        if (user == null) return;
        Api.run(() -> RpcClient.object("get_profile", Api.obj("p_target", user.getUid())), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject profile) {
                JSONObject settings = profile.optJSONObject("settings");
                JSONObject values = new JSONObject();
                try {
                    values.put("profile", settings == null ? "public" : settings.optString("profile_audience", "public"));
                    values.put("last_seen", settings == null ? "private" : settings.optString("last_seen_audience", "private"));
                    values.put("location", profile.optString("location_audience", "private"));
                    values.put("preset", profile.optString("avatar_preset", "neutral"));
                } catch (Exception ignored) { }
                showPrivacyChoices(values);
            }
            @Override public void error(String error) { toast(error); }
        });
    }

    private void showPrivacyChoices(JSONObject v) {
        String[] labels={"Tài khoản","Trạng thái hoạt động","Nơi ở"};
        String[][] choices={{"Công khai","Riêng tư"},{"Mọi người","Người theo dõi","Chỉ mình tôi"},{"Mọi người","Người theo dõi","Chỉ mình tôi"}};
        String[][] values={{"public","private"},{"public","followers","private"},{"public","followers","private"}};
        String[] keys={"profile","last_seen","location"};LinearLayout list=new LinearLayout(this);list.setOrientation(1);
        int inset=(int)(24*getResources().getDisplayMetrics().density);
        list.setPadding(inset,inset/3,inset,inset/3);
        View card=getLayoutInflater().inflate(R.layout.card_settings_options,list,false);list.addView(card);
        int[] rowIds={R.id.settingsOptionOne,R.id.settingsOptionTwo,R.id.settingsOptionThree};
        int[] icons={R.drawable.ic_person,R.drawable.ic_notifications,R.drawable.ic_location};
        for(int i=0;i<labels.length;i++){final int row=i;int selected=java.util.Arrays.asList(values[i]).indexOf(v.optString(keys[i]));if(selected<0)selected=i==0?1:2;
            try{v.put(keys[i],values[i][selected]);}catch(Exception ignored){}
            View option=card.findViewById(rowIds[i]);configureOption(option,icons[i],labels[i],choices[i][selected]);
            option.setOnClickListener(x->{int checked=java.util.Arrays.asList(values[row]).indexOf(v.optString(keys[row]));
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle(labels[row])
                    .setSingleChoiceItems(choices[row],checked,(d,n)->{try{v.put(keys[row],values[row][n]);}catch(Exception ignored){}
                        configureOption(option,icons[row],labels[row],choices[row][n]);d.dismiss();})
                    .setNegativeButton("Hủy",null).show();});}
        ScrollView scroll=new ScrollView(this);scroll.addView(list);
        AlertDialog dialog=new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Quyền riêng tư").setMessage("Tài khoản riêng tư chỉ cho người theo dõi đã được duyệt xem nội dung. Yêu cầu đang chờ không tự được chấp nhận khi chuyển công khai.").setView(scroll).setNegativeButton("Hủy",null).setPositiveButton("Lưu",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(x->{dialog.getButton(-1).setEnabled(false);Api.run(()->RpcClient.object("update_privacy_settings",Api.obj("p_profile_audience",v.optString("profile"),"p_last_seen_audience",v.optString("last_seen"),"p_location_audience",v.optString("location"))),new Api.Callback<JSONObject>(){
            public void success(JSONObject r){dialog.dismiss();toast("Đã lưu quyền riêng tư.");com.example.meetomxh.repositories.FeedRepository.getInstance().refresh();}
            public void error(String e){dialog.getButton(-1).setEnabled(true);toast(e);}
        });}));dialog.show();
    }

    private void configureOption(View row,int icon,String label,String value){
        ((android.widget.ImageView)row.findViewById(R.id.settingsOptionIcon)).setImageResource(icon);
        ((android.widget.TextView)row.findViewById(R.id.settingsOptionLabel)).setText(label);
        android.widget.TextView selected=row.findViewById(R.id.settingsOptionValue);
        selected.setText(value);selected.setVisibility(value==null?View.GONE:View.VISIBLE);
    }






    private void blockedUsers() {
        Api.run(()->(JSONArray)RpcClient.call("list_blocked_users",Api.obj()),new Api.Callback<JSONArray>(){
            public void success(JSONArray users){
                if(isFinishing()||isDestroyed())return;
                if(users.length()==0){toast("Danh sách chặn đang trống.");return;}
                ScrollView scroll=new ScrollView(SettingsActivity.this);
                LinearLayout list=new LinearLayout(SettingsActivity.this);list.setOrientation(LinearLayout.VERTICAL);
                int padding=Math.round(20*getResources().getDisplayMetrics().density);
                list.setPadding(padding,padding/2,padding,padding/2);scroll.addView(list);
                AlertDialog dialog=new AlertDialog.Builder(SettingsActivity.this).setTitle("Tài khoản đã chặn")
                    .setView(scroll).setPositiveButton("Đóng",null).create();
                for(int i=0;i<users.length();i++){
                    JSONObject p=users.optJSONObject(i);if(p==null)continue;
                    com.example.meetomxh.databinding.ItemBlockedUserBinding row=
                        com.example.meetomxh.databinding.ItemBlockedUserBinding.inflate(getLayoutInflater(),list,false);
                    com.example.meetomxh.ui.AvatarViews.load(row.ivBlockedAvatar,
                        com.example.meetomxh.supabase.Dto.asset(com.example.meetomxh.supabase.Dto.nullable(p,"avatar_asset_id")),
                        p.optString("avatar_preset","neutral"));
                    row.tvBlockedName.setText(p.isNull("display_name")?"Người dùng không khả dụng":p.optString("display_name","Người dùng"));
                    String username=com.example.meetomxh.supabase.Dto.nullable(p,"username");
                    if(username!=null&&!username.trim().isEmpty()){
                        row.tvBlockedUsername.setText("@"+username);row.tvBlockedUsername.setVisibility(View.VISIBLE);
                    }
                    row.btnUnblock.setContentDescription("Bỏ chặn "+row.tvBlockedName.getText());
                    list.addView(row.getRoot());
                    row.btnUnblock.setOnClickListener(v->new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Bỏ chặn tài khoản?").setMessage("Quan hệ theo dõi đã gỡ sẽ không tự khôi phục.")
                        .setNegativeButton("Hủy",null).setPositiveButton("Bỏ chặn",(d,w)->{
                            row.btnUnblock.setEnabled(false);
                            Api.run(()->RpcClient.call("set_block",Api.obj("p_target",p.optString("user_id"),"p_blocked",false)),new Api.Callback<Object>(){
                                public void success(Object r){list.removeView(row.getRoot());if(list.getChildCount()==0)dialog.dismiss();}
                                public void error(String e){row.btnUnblock.setEnabled(true);toast(e);}
                            });
                        }).show());
                }
                dialog.show();
            }
            public void error(String e){toast(e);}
        });
    }

    private void requestDeletion() {
        new AlertDialog.Builder(this).setTitle("Yêu cầu xóa tài khoản")
                .setMessage("Yêu cầu sẽ được ghi nhận để xử lý. Tài khoản chưa bị vô hiệu hóa hoặc xóa ngay.")
                .setNegativeButton("Hủy", null).setPositiveButton("Gửi yêu cầu", (d, w) -> Api.run(
                        () -> RpcClient.object("request_account_deletion", Api.obj()), new Api.Callback<JSONObject>() {
                            @Override public void success(JSONObject r) { toast("Đã gửi yêu cầu xử lý tài khoản."); }
                            @Override public void error(String error) { toast(error); }
                        })).show();
    }

    private void notificationSettings() {
        Api.run(() -> com.example.meetomxh.repositories.NotificationRepository.settings(), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject settings) {
                JSONObject prefs = settings.optJSONObject("preferences"); if (prefs == null) prefs = new JSONObject();
                final JSONObject current = prefs;
                LinearLayout options = new LinearLayout(SettingsActivity.this); options.setOrientation(LinearLayout.VERTICAL); options.setPadding(20,8,20,8);
                CheckBox enabled = new CheckBox(SettingsActivity.this); enabled.setText("Bật thông báo"); enabled.setChecked(settings.optBoolean("enabled",true)); options.addView(enabled);
                com.google.android.material.button.MaterialButton androidSettings=new com.google.android.material.button.MaterialButton(SettingsActivity.this);
                androidSettings.setText("Cài đặt thông báo Android");androidSettings.setAllCaps(false);
                androidSettings.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));options.addView(androidSettings);
                String[] keys={"reaction","comment","reply","follow_request","follow_accepted","message_request","group_invite","follow","message","story","moderation","mention","tag"};
                String[] labels={"Cảm xúc","Bình luận","Trả lời","Yêu cầu theo dõi","Chấp nhận theo dõi","Tin nhắn chờ","Lời mời nhóm chat","Theo dõi","Tin nhắn","Tin trả lời story","Kiểm duyệt","Mention","Gắn thẻ"};
                CheckBox[] boxes=new CheckBox[keys.length];
                for(int i=0;i<keys.length;i++){boxes[i]=new CheckBox(SettingsActivity.this);boxes[i].setText(labels[i]);boxes[i].setChecked(current.optBoolean(keys[i],true));options.addView(boxes[i]);}
                ScrollView scroll=new ScrollView(SettingsActivity.this);scroll.addView(options);
                new AlertDialog.Builder(SettingsActivity.this).setTitle("Thông báo").setView(scroll).setNegativeButton("Hủy",null)
                        .setPositiveButton("Lưu",(d,w)->Api.run(()->{JSONObject updated=new JSONObject();for(int i=0;i<keys.length;i++)updated.put(keys[i],boxes[i].isChecked());return com.example.meetomxh.repositories.NotificationRepository.updateSettings(enabled.isChecked(),updated);},new Api.Callback<JSONObject>(){
                            @Override public void success(JSONObject r){toast("Đã lưu cài đặt thông báo.");}
                            @Override public void error(String e){toast(e);}
                        })).show();
            }
            @Override public void error(String error) { toast(error); }
        });
    }

    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
}
