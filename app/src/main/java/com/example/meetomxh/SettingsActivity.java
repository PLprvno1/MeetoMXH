package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Button;
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
    private boolean updatingThemeControl;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        androidx.core.widget.NestedScrollView settingsScroll=binding.getRoot().findViewById(R.id.settingsScroll);
        if(settingsScroll!=null&&settingsScroll.getChildAt(0) instanceof LinearLayout){
            Button tagButton=new Button(this);tagButton.setText("Quyền gắn thẻ & bài viết có gắn thẻ");
            tagButton.setOnClickListener(v->startActivity(new Intent(this,TagManagementActivity.class)));
            ((LinearLayout)settingsScroll.getChildAt(0)).addView(tagButton,0);
        }
        binding.btnBack.setOnClickListener(v -> finish());
        int nightMode = MeetoPrefs.theme(this);
        binding.switchDarkModeSettings.setChecked(nightMode == AppCompatDelegate.MODE_NIGHT_YES);
        binding.switchDarkModeSettings.setOnCheckedChangeListener((v, checked) -> {
            if (!updatingThemeControl) setThemeMode(checked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        binding.switchDarkModeSettings.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this).setTitle("Giao diện")
                    .setItems(new String[]{"Theo hệ thống", "Sáng", "Tối"}, (d, i) -> setThemeMode(
                            i == 0 ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : i == 1
                                    ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES)).show();
            return true;
        });
        binding.rowEditProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));
        binding.rowAccountInfo.setOnClickListener(v -> accountInfo());
        binding.rowPrivacy.setOnClickListener(v -> privacyCenter());
        binding.rowBlocked.setOnClickListener(v -> blockedUsers());
        binding.rowNotificationsSetting.setOnClickListener(v -> notificationSettings());
        binding.rowPlatformModeration.setVisibility(View.GONE);
        Api.run(() -> RpcClient.call("list_platform_reports", Api.obj("p_state","open","p_after_time",JSONObject.NULL,
                "p_after_id",JSONObject.NULL,"p_limit",1)), new Api.Callback<Object>() {
            @Override public void success(Object value) { if(!isFinishing()) binding.rowPlatformModeration.setVisibility(View.VISIBLE); }
            @Override public void error(String error) { if(!isFinishing()) binding.rowPlatformModeration.setVisibility(View.GONE); }
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

    private void setThemeMode(int mode) {
        MeetoPrefs.setTheme(this, mode);
        AppCompatDelegate.setDefaultNightMode(mode);
        updatingThemeControl = true;
        binding.switchDarkModeSettings.setChecked(mode == AppCompatDelegate.MODE_NIGHT_YES);
        updatingThemeControl = false;
    }

    private void accountInfo() {
        SessionUser user = SessionManager.getInstance().getCurrentUser();
        if (user == null) return;
        Api.run(() -> RpcClient.object("get_profile", Api.obj("p_target", user.getUid())), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject profile) {
                new AlertDialog.Builder(SettingsActivity.this).setTitle("Tài khoản")
                        .setMessage("Email: " + user.getEmail() + "\nXác minh email: "
                                + (profile.optBoolean("email_verified") ? "Đã xác minh" : "Chưa xác minh"))
                        .setNeutralButton("Đổi mật khẩu", (d, w) -> startActivity(new Intent(SettingsActivity.this, ForgotPasswordActivity.class).putExtra("reset", true)))
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
                    values.put("last_seen", settings == null ? "friends" : settings.optString("last_seen_audience", "friends"));
                    values.put("location", profile.optString("location_audience", "private"));
                    values.put("preset", profile.optString("avatar_preset", "neutral"));
                } catch (Exception ignored) { }
                showPrivacyChoices(values);
            }
            @Override public void error(String error) { toast(error); }
        });
    }

    private void showPrivacyChoices(JSONObject v) {
        String[] labels = {"Ai xem hồ sơ", "Ai xem trạng thái hoạt động", "Ai xem nơi ở", "Avatar công khai"};
        String[][] choices = {{"Công khai", "Bạn bè", "Chỉ mình tôi"}, {"Công khai", "Bạn bè", "Chỉ mình tôi"},
                {"Công khai", "Bạn bè", "Chỉ mình tôi"}, {"Trung tính", "Nam", "Nữ"}};
        String[] keys = {"profile", "last_seen", "location", "preset"};
        String[][] values = {{"public", "friends", "private"}, {"public", "friends", "private"},
                {"public", "friends", "private"}, {"neutral", "male", "female"}};
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(32, 8, 32, 8);
        for (int row = 0; row < labels.length; row++) {
            final int index = row;
            int selected = index == 3 ? presetIndex(v.optString(keys[index])) : audienceIndex(v.optString(keys[index]));
            android.widget.Button button = new android.widget.Button(this);
            button.setText(labels[row] + ": " + choices[row][selected]);
            button.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(labels[index]).setItems(choices[index], (d, choice) -> {
                try { v.put(keys[index], values[index][choice]); } catch (Exception ignored) { }
                button.setText(labels[index] + ": " + choices[index][choice]);
            }).show());
            list.addView(button);
        }
        new AlertDialog.Builder(this).setTitle("Trung tâm quyền riêng tư").setView(list)
                .setNegativeButton("Đóng", null).setPositiveButton("Lưu", (d, w) -> savePrivacy(v)).show();
    }

    private int audienceIndex(String value) { return "friends".equals(value) ? 1 : "private".equals(value) ? 2 : 0; }
    private int presetIndex(String value) { return "male".equals(value) ? 1 : "female".equals(value) ? 2 : 0; }

    private void savePrivacy(JSONObject v) {
        Api.run(() -> {
            JSONObject result = RpcClient.object("update_privacy_settings", Api.obj(
                    "p_profile_audience", v.optString("profile"), "p_last_seen_audience", v.optString("last_seen"),
                    "p_location_audience", v.optString("location")));
            RpcClient.object("set_avatar_preset", Api.obj("p_preset", v.optString("preset")));
            return result;
        }, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject result) { toast("Đã lưu quyền riêng tư."); }
            @Override public void error(String error) { toast(error); }
        });
    }

    private void blockedUsers() {
        Api.run(() -> (JSONArray) RpcClient.call("list_blocked_users", Api.obj()), new Api.Callback<JSONArray>() {
            @Override public void success(JSONArray users) {
                if (users.length() == 0) { toast("Danh sách chặn đang trống."); return; }
                String[] items = new String[users.length()];
                for (int i=0;i<users.length();i++) items[i] = users.optJSONObject(i).optString("user_id");
                new AlertDialog.Builder(SettingsActivity.this).setTitle("Tài khoản đã chặn")
                        .setItems(items, (d, i) -> new AlertDialog.Builder(SettingsActivity.this)
                                .setMessage("Bỏ chặn tài khoản này? Quan hệ bạn bè/theo dõi đã gỡ sẽ không tự khôi phục.")
                                .setNegativeButton("Hủy", null).setPositiveButton("Bỏ chặn", (dialog, which) ->
                                        Api.run(() -> RpcClient.call("set_block", Api.obj("p_target", items[i], "p_blocked", false)), new Api.Callback<Object>() {
                                            @Override public void success(Object result) { toast("Đã bỏ chặn."); blockedUsers(); }
                                            @Override public void error(String error) { toast(error); }
                                        })).show()).show();
            }
            @Override public void error(String error) { toast(error); }
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
                CheckBox enabled = new CheckBox(SettingsActivity.this); enabled.setText("Thông báo trong ứng dụng"); enabled.setChecked(settings.optBoolean("enabled",true)); options.addView(enabled);
                String[] keys={"reaction","comment","reply","friend_request","friend_accepted","follow","message","story","group","moderation","mention","tag"};
                String[] labels={"Cảm xúc","Bình luận","Trả lời","Lời mời kết bạn","Chấp nhận kết bạn","Theo dõi","Tin nhắn","Tin trả lời story","Nhóm","Kiểm duyệt","Mention","Gắn thẻ"};
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
