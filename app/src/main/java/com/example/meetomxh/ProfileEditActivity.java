package com.example.meetomxh;

import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.*;
import org.json.JSONObject;

public class ProfileEditActivity extends AppCompatActivity {
    private JSONObject profile;
    private EditText name, bio, dob, gender;
    private Button savePublic, savePrivate, pickAvatar, pickCover;
    private String avatar, cover;
    private boolean pickingCover, busy;
    private Subscription request;
    private SupabaseStorage.Transfer upload;

    private final ActivityResultLauncher<String> picker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null || busy) return;
                setBusy(true);
                Forms.status(this, "Đang tải lên và chờ xác minh…");
                upload = SupabaseStorage.upload(getContentResolver(), uri, pickingCover ? "cover" : "avatar",
                        SessionManager.getInstance().uid(), new SupabaseStorage.UploadListener() {
                    public void onProgress(int percent) {
                        if (alive()) Forms.status(ProfileEditActivity.this, "Tải lên " + percent + "% · chờ xác minh");
                    }
                    public void onSuccess(String reference) {
                        if (!alive()) return;
                        if (pickingCover) cover = Dto.assetId(reference); else avatar = Dto.assetId(reference);
                        setBusy(false);
                        Forms.status(ProfileEditActivity.this, "Tệp đã xác minh. Nhấn Lưu hồ sơ để áp dụng.");
                    }
                    public void onFailure(String error) {
                        if (!alive()) return;
                        setBusy(false);
                        Forms.status(ProfileEditActivity.this, error);
                    }
                });
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout form = Forms.start(this, "Chỉnh sửa hồ sơ");
        name = Forms.field(form, "Họ và tên", "", InputType.TYPE_CLASS_TEXT);
        bio = Forms.field(form, "Tiểu sử", "", InputType.TYPE_CLASS_TEXT);
        dob = Forms.field(form, "Ngày sinh YYYY-MM-DD", "", InputType.TYPE_CLASS_DATETIME);
        gender = Forms.field(form, "Giới tính", "", InputType.TYPE_CLASS_TEXT);
        pickAvatar = Forms.button(form, "Chọn ảnh đại diện", () -> { pickingCover = false; picker.launch("image/*"); });
        pickCover = Forms.button(form, "Chọn ảnh bìa", () -> { pickingCover = true; picker.launch("image/*"); });
        savePublic = Forms.button(form, "Lưu hồ sơ", () -> save(false));
        savePrivate = Forms.button(form, "Lưu thông tin riêng", () -> save(true));
        Forms.button(form, "Tải lại hồ sơ", this::recreate);
        Forms.button(form, "Quay lại", this::finish);
        setBusy(true);
        Forms.status(this, "Đang tải hồ sơ…");
        request = Api.run(ProfileClient::self, new Api.Callback<JSONObject>() {
            public void success(JSONObject value) {
                if (!alive()) return;
                profile = value;
                name.setText(value.optString("display_name"));
                bio.setText(value.optString("bio"));
                dob.setText(Dto.nullable(value, "birth_date"));
                gender.setText(Dto.nullable(value, "gender"));
                avatar = Dto.nullable(value, "avatar_asset_id");
                cover = Dto.nullable(value, "cover_asset_id");
                setBusy(false);
                Forms.status(ProfileEditActivity.this, "");
            }
            public void error(String error) {
                if (alive()) Forms.status(ProfileEditActivity.this, error);
            }
        });
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    private void setBusy(boolean value) {
        busy = value;
        name.setEnabled(!value); bio.setEnabled(!value); dob.setEnabled(!value); gender.setEnabled(!value);
        savePublic.setEnabled(!value); savePrivate.setEnabled(!value);
        pickAvatar.setEnabled(!value); pickCover.setEnabled(!value);
    }

    private void save(boolean secret) {
        if (profile == null || busy) return;
        JSONObject args = secret ? Api.obj("p_birth_date", dob.getText().toString(), "p_gender", gender.getText().toString(),
                "p_expected_version", profile.optLong("version")) : Api.obj(
                "p_display_name", name.getText().toString(), "p_bio", bio.getText().toString(),
                "p_avatar_asset_id", avatar, "p_cover_asset_id", cover,
                "p_expected_version", profile.optLong("version"));
        setBusy(true);
        Forms.status(this, "Đang lưu…");
        request = Api.run(() -> RpcClient.object(secret ? "update_profile_private" : "update_profile", args),
                new Api.Callback<JSONObject>() {
            public void success(JSONObject value) {
                if (!alive()) return;
                profile = value;
                setBusy(false);
                Forms.status(ProfileEditActivity.this, "Đã lưu thay đổi");
            }
            public void error(String error) {
                if (!alive()) return;
                setBusy(false);
                Forms.status(ProfileEditActivity.this, error);
            }
        });
    }

    @Override protected void onDestroy() {
        if (request != null) request.remove();
        if (upload != null) upload.cancel();
        super.onDestroy();
    }
}
