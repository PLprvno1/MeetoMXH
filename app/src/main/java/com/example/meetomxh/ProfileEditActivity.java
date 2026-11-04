package com.example.meetomxh;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ActivityProfileEditBinding;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.ui.AvatarViews;

import org.json.JSONObject;

public class ProfileEditActivity extends AppCompatActivity {
    private ActivityProfileEditBinding binding;
    private JSONObject profile;
    private String avatar, cover;
    private String avatarPreset = "neutral";
    private String locationAudience = "private";
    private Uri selectedUri;
    private boolean pickingCover, busy, uploadingMedia, resumeUpload;
    private String restoredAvatar, restoredCover;
    private boolean retryUpload;
    private Subscription request;
    private SupabaseStorage.Transfer upload;

    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri == null || busy) return;
        try { getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        selectedUri = uri;
        beginUpload(uri);
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityProfileEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (state != null && state.getString("media_uri") != null) {
            selectedUri = Uri.parse(state.getString("media_uri"));
            pickingCover = state.getBoolean("media_cover");
            resumeUpload = state.getBoolean("media_uploading");
            retryUpload = state.getBoolean("media_retry");
            restoredAvatar = state.getString("media_avatar");
            restoredCover = state.getString("media_cover_asset");
        }
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnReload.setOnClickListener(v -> recreate());
        ProfileFieldSelectors.makeSelector(binding.etDob);
        binding.etDob.setOnClickListener(v -> ProfileFieldSelectors.date(this, binding.etDob));
        ProfileFieldSelectors.makeSelector(binding.etGender);
        binding.etGender.setOnClickListener(v -> ProfileFieldSelectors.gender(this, binding.etGender));
        ProfileFieldSelectors.makeSelector(binding.etLocation);
        binding.etLocation.setOnClickListener(v -> ProfileFieldSelectors.location(this, binding.etLocation));
        binding.btnLocationAudience.setOnClickListener(v -> chooseLocationAudience());
        binding.btnPickAvatar.setOnClickListener(v -> { pickingCover = false; picker.launch(new String[]{"image/*"}); });
        binding.btnPickCover.setOnClickListener(v -> { pickingCover = true; picker.launch(new String[]{"image/*"}); });
        binding.btnRetryUpload.setOnClickListener(v -> { if (selectedUri != null && !busy) beginUpload(selectedUri); });
        binding.btnCancelUpload.setOnClickListener(v -> cancelUpload());
        binding.btnSavePublic.setOnClickListener(v -> save(false));
        binding.btnSavePrivate.setOnClickListener(v -> save(true));
        binding.btnAvatarPreset.setOnClickListener(v -> chooseAvatarPreset());

        setBusy(true);
        setStatus("Đang tải hồ sơ…");
        request = Api.run(ProfileClient::self, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject value) {
                if (!alive()) return;
                profile = value;
                binding.etName.setText(value.optString("display_name"));
                binding.etBio.setText(value.optString("bio"));
                binding.etLocation.setText(Dto.nullable(value, "location"));
                locationAudience=value.optString("location_audience","private");
                updateLocationAudienceLabel();
                ProfileFieldSelectors.setDate(binding.etDob, Dto.nullable(value, "birth_date"));
                binding.etGender.setText(Dto.nullable(value, "gender"));
                avatarPreset = value.optString("avatar_preset", "neutral");
                updateAvatarPresetLabel();
                avatar = restoredAvatar == null ? Dto.nullable(value, "avatar_asset_id") : restoredAvatar;
                cover = restoredCover == null ? Dto.nullable(value, "cover_asset_id") : restoredCover;
                showCloudPreview(false);
                showCloudPreview(true);
                if (resumeUpload && selectedUri != null) { resumeUpload = false; setBusy(false); beginUpload(selectedUri); }
                else {
                    setBusy(false);
                    binding.btnRetryUpload.setVisibility(retryUpload ? View.VISIBLE : View.GONE);
                    setStatus("");
                }
            }
            @Override public void error(String error) {
                if (alive()) { setBusy(false); setStatus(error); }
            }
        });
    }

    private void beginUpload(Uri uri) {
        if (uri == null || busy) return;
        selectedUri = uri;
        uploadingMedia = true;
        binding.btnCancelUpload.setVisibility(View.VISIBLE);
        binding.btnRetryUpload.setVisibility(View.GONE);
        if (pickingCover) Glide.with(this).load(uri).centerCrop().into(binding.coverPreview);
        else Glide.with(this).load(uri).centerCrop().into(binding.avatarPreview);
        setBusy(true);
        setStatus("Đang kiểm tra tệp…");
        upload = SupabaseStorage.upload(getContentResolver(), uri, pickingCover ? "cover" : "avatar",
                SessionManager.getInstance().uid(), new SupabaseStorage.UploadListener() {
            @Override public void onProgress(int percent) {
                if (alive()) setStatus("Đang tải lên " + percent + "%");
            }
            @Override public void onSuccess(String reference) {
                if (!alive()) return;
                if (pickingCover) cover = Dto.assetId(reference); else avatar = Dto.assetId(reference);
                uploadingMedia = false;
                setBusy(false);
                binding.btnCancelUpload.setVisibility(View.GONE);
                binding.btnRetryUpload.setVisibility(View.GONE);
                setStatus("Đang lưu…");
            }
            @Override public void onFailure(String error) {
                if (!alive()) return;
                uploadingMedia = false;
                setBusy(false);
                binding.btnCancelUpload.setVisibility(View.GONE);
                binding.btnRetryUpload.setVisibility(View.VISIBLE);
                showCloudPreview(pickingCover);
                setStatus(error + " Thử tải lại bằng nút bên dưới.");
            }
        });
    }

    private void cancelUpload() {
        if (upload != null) upload.cancel();
        upload = null;
        uploadingMedia = false;
        selectedUri = null;
        setBusy(false);
        binding.btnCancelUpload.setVisibility(View.GONE);
        binding.btnRetryUpload.setVisibility(View.GONE);
        showCloudPreview(pickingCover);
        setStatus("Đang lưu…");
    }

    private void showCloudPreview(boolean coverImage) {
        String ref = coverImage ? cover : avatar;
        android.widget.ImageView target = coverImage ? binding.coverPreview : binding.avatarPreview;
        if (ref == null || ref.isEmpty()) {
            if (coverImage) target.setImageResource(R.drawable.bg_cover_placeholder);
            else AvatarViews.load(target, null, avatarPreset);
        }
        else Glide.with(this).load(Dto.asset(ref)).centerCrop().into(target);
    }

    private void updateAvatarPresetLabel() {
        String label = "male".equals(avatarPreset) ? "Minh hoạ nam"
                : "female".equals(avatarPreset) ? "Minh hoạ nữ" : "Trung tính";
        binding.btnAvatarPreset.setText("Avatar mặc định · " + label);
    }

    private void chooseAvatarPreset() {
        String[] labels = {"Trung tính", "Minh hoạ nam", "Minh hoạ nữ"};
        String[] values = {"neutral", "male", "female"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Avatar mặc định công khai")
                .setMessage("Đây là tùy chọn công khai, độc lập với giới tính riêng tư.")
                .setItems(labels, (dialog, index) -> {
                    String selected = values[index];
                    if (selected.equals(avatarPreset) || busy) return;
                    setBusy(true);
                    setStatus("Đang cập nhật avatar mặc định…");
                    request = Api.run(() -> RpcClient.object("set_avatar_preset", Api.obj("p_preset", selected)),
                            new Api.Callback<JSONObject>() {
                                @Override public void success(JSONObject value) {
                                    if (!alive()) return;
                                    profile = value;
                                    avatarPreset = value.optString("avatar_preset", selected);
                                    updateAvatarPresetLabel();
                                    showCloudPreview(false);
                                    setBusy(false);
                                    setStatus("Đã cập nhật avatar mặc định");
                                }
                                @Override public void error(String error) {
                                    if (alive()) { setBusy(false); setStatus(error); }
                                }
                            });
                }).setNegativeButton("Đóng", null).show();
    }

    private void chooseLocationAudience() {
        String[] labels={"Công khai","Bạn bè","Chỉ mình tôi"};
        String[] values={"public","friends","private"}; int selected=2;
        for(int i=0;i<values.length;i++)if(values[i].equals(locationAudience))selected=i;
        new android.app.AlertDialog.Builder(this).setTitle("Ai có thể xem nơi ở?")
                .setSingleChoiceItems(labels,selected,(d,i)->{locationAudience=values[i];updateLocationAudienceLabel();d.dismiss();})
                .setNegativeButton("Đóng",null).show();
    }
    private void updateLocationAudienceLabel() {
        String label="public".equals(locationAudience)?"Công khai":"friends".equals(locationAudience)?"Bạn bè":"Chỉ mình tôi";
        binding.btnLocationAudience.setText("Quyền xem nơi ở · "+label);
    }

    private void setStatus(String message) { Forms.status(this, message); }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    private void setBusy(boolean value) {
        busy = value;
        binding.etName.setEnabled(!value);
        binding.etBio.setEnabled(!value);
        binding.etDob.setEnabled(!value);
        binding.etGender.setEnabled(!value);
        binding.etLocation.setEnabled(!value);
        binding.btnLocationAudience.setEnabled(!value);
        binding.btnAvatarPreset.setEnabled(!value);
        binding.btnSavePublic.setEnabled(!value);
        binding.btnSavePrivate.setEnabled(!value);
        binding.btnPickAvatar.setEnabled(!value);
        binding.btnPickCover.setEnabled(!value);
    }

    private void save(boolean secret) {
        if (profile == null || busy) return;
        String birthDate = ProfileFieldSelectors.dateValue(binding.etDob);
        if (secret && birthDate.isEmpty()) { setStatus("Chọn ngày sinh hợp lệ."); return; }
        String gender = binding.etGender.getText().toString().trim();
        if ("Không muốn nêu".equals(gender)) gender = "";
        JSONObject args = secret
                ? Api.obj("p_birth_date", birthDate, "p_gender", gender.isEmpty() ? null : gender,
                    "p_expected_version", profile.optLong("version"))
                : Api.obj("p_display_name", binding.etName.getText().toString(), "p_bio", binding.etBio.getText().toString(),
                    "p_avatar_asset_id", avatar, "p_cover_asset_id", cover,
                    "p_location", binding.etLocation.getText().toString().trim().isEmpty()?null:binding.etLocation.getText().toString().trim(),
                    "p_location_audience", locationAudience, "p_expected_version", profile.optLong("version"));
        setBusy(true);
        setStatus("Đang lưu…");
        request = Api.run(() -> RpcClient.object(secret ? "update_profile_private" : "update_profile_with_location", args), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject value) {
                if (!alive()) return;
                profile = value;
                setBusy(false);
                setStatus("Đang lưu…");
            }
            @Override public void error(String error) {
                if (alive()) { setBusy(false); setStatus(error); }
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (selectedUri != null) out.putString("media_uri", selectedUri.toString());
        out.putBoolean("media_cover", pickingCover);
        out.putBoolean("media_uploading", uploadingMedia);
        out.putBoolean("media_retry", binding.btnRetryUpload.getVisibility() == View.VISIBLE);
        if (avatar != null) out.putString("media_avatar", avatar);
        if (cover != null) out.putString("media_cover_asset", cover);
    }

    @Override protected void onDestroy() {
        if (request != null) request.remove();
        if (upload != null) upload.cancel();
        super.onDestroy();
    }
}
