package com.example.meetomxh;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

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
import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

public class ProfileEditActivity extends AppCompatActivity {
    public static final String EXTRA_ONBOARDING_ASSETS_ONLY = "onboarding_assets_only";
    private ActivityProfileEditBinding binding;
    private JSONObject profile;
    private String avatar, cover;
    private String avatarPreset = "neutral";
    private String locationAudience = "private";
    private String birthDateAudience = "private";
    private String genderAudience = "private";
    private Uri selectedUri;
    private boolean busy, uploadingMedia, resumeUpload;
    private String restoredAvatar;
    private boolean retryUpload;
    private boolean onboardingAssetsOnly;
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
        onboardingAssetsOnly = getIntent().getBooleanExtra(EXTRA_ONBOARDING_ASSETS_ONLY, false);
        binding = ActivityProfileEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (state != null && state.getString("media_uri") != null) {
            selectedUri = Uri.parse(state.getString("media_uri"));
            resumeUpload = state.getBoolean("media_uploading");
            retryUpload = state.getBoolean("media_retry");
            restoredAvatar = state.getString("media_avatar");
        }
        binding.btnBack.setOnClickListener(v -> { if (onboardingAssetsOnly) openSuggestions(); else finish(); });
        if (onboardingAssetsOnly) {
            binding.btnReload.setText("Bỏ qua");
            binding.btnReload.setOnClickListener(v -> openSuggestions());
            ((android.widget.TextView)findViewById(R.id.tvProfileEditTitle)).setText("Chọn ảnh đại diện");
            hideCardContaining(binding.etName);
            binding.btnSavePublic.setText("Tiếp tục");
        } else {
            binding.btnReload.setOnClickListener(v -> recreate());
        }
        ProfileFieldSelectors.makeSelector(binding.etDob);
        binding.etDob.setOnClickListener(v -> ProfileFieldSelectors.date(this, binding.etDob));
        ProfileFieldSelectors.makeSelector(binding.etGender);
        binding.etGender.setOnClickListener(v -> ProfileFieldSelectors.gender(this, binding.etGender, () -> {
            avatarPreset = avatarPresetForGender(binding.etGender.getText().toString());
            showCloudPreview();
        }));
        ProfileFieldSelectors.makeSelector(binding.etLocation);
        binding.etLocation.setOnClickListener(v -> ProfileFieldSelectors.location(this, binding.etLocation));
        binding.btnLocationAudience.setOnClickListener(v -> chooseLocationAudience());
        binding.btnBirthDateAudience.setOnClickListener(v -> chooseFieldAudience("ngày sinh", true));
        binding.btnGenderAudience.setOnClickListener(v -> chooseFieldAudience("giới tính", false));
        binding.btnPickAvatar.setOnClickListener(v -> picker.launch(new String[]{"image/*"}));
        binding.btnRetryUpload.setOnClickListener(v -> { if (selectedUri != null && !busy) beginUpload(selectedUri); });
        binding.btnCancelUpload.setOnClickListener(v -> cancelUpload());
        binding.btnSavePublic.setOnClickListener(v -> save());

        setBusy(true);
        setStatus("Đang tải hồ sơ…");
        request = Api.run(ProfileClient::self, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject value) {
                if (!alive()) return;
                profile = value;
                binding.etName.setText(value.optString("display_name"));binding.etUsername.setText(Dto.nullable(value,"username"));
                binding.etBio.setText(value.optString("bio"));
                binding.etLocation.setText(Dto.nullable(value, "location"));
                locationAudience=value.optString("location_audience","private");
                updateLocationAudienceLabel();
                ProfileFieldSelectors.setDate(binding.etDob, Dto.nullable(value, "birth_date"));
                binding.etGender.setText(Dto.nullable(value, "gender"));
                birthDateAudience=value.optString("birth_date_audience","private");
                genderAudience=value.optString("gender_audience","private");
                updateFieldAudienceLabels();
                avatarPreset = value.optString("avatar_preset", "neutral");
                avatar = restoredAvatar == null ? Dto.nullable(value, "avatar_asset_id") : restoredAvatar;
                // Preserve the existing profile cover when saving this avatar-only screen.
                cover = Dto.nullable(value, "cover_asset_id");
                showCloudPreview();
                if (resumeUpload && selectedUri != null) { resumeUpload = false; setBusy(false); beginUpload(selectedUri); }
                else {
                    setBusy(false);
                    binding.btnRetryUpload.setVisibility(retryUpload ? View.VISIBLE : View.GONE);
                    setStatus(onboardingAssetsOnly ? "Bạn có thể chọn ảnh đại diện hoặc bỏ qua bước này." : "");
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
        Glide.with(this).load(uri).centerCrop().into(binding.avatarPreview);
        setBusy(true);
        setStatus("Đang kiểm tra tệp…");
        upload = SupabaseStorage.upload(getContentResolver(), uri, "avatar",
                SessionManager.getInstance().uid(), new SupabaseStorage.UploadListener() {
            @Override public void onProgress(int percent) {
                if (alive()) setStatus("Đang tải lên " + percent + "%");
            }
            @Override public void onSuccess(String reference) {
                if (!alive()) return;
                avatar = Dto.assetId(reference);
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
                showCloudPreview();
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
        showCloudPreview();
        setStatus("Đang lưu…");
    }

    private void showCloudPreview() {
        if (avatar == null || avatar.isEmpty()) AvatarViews.load(binding.avatarPreview, null, avatarPreset);
        else Glide.with(this).load(Dto.asset(avatar)).centerCrop().into(binding.avatarPreview);
    }

    private void chooseLocationAudience() {
        String[] labels={"Công khai","Người theo dõi","Chỉ mình tôi"};
        String[] values={"public","followers","private"}; int selected=2;
        for(int i=0;i<values.length;i++)if(values[i].equals(locationAudience))selected=i;
        new android.app.AlertDialog.Builder(this).setTitle("Ai có thể xem nơi ở?")
                .setSingleChoiceItems(labels,selected,(d,i)->{locationAudience=values[i];updateLocationAudienceLabel();d.dismiss();})
                .setNegativeButton("Đóng",null).show();
    }
    private void updateLocationAudienceLabel() {
        String label="public".equals(locationAudience)?"Công khai":"followers".equals(locationAudience)?"Người theo dõi":"Chỉ mình tôi";
        binding.btnLocationAudience.setText("Quyền xem nơi ở · "+label);
    }

    private void chooseFieldAudience(String field, boolean birthDate) {
        String[] labels={"Công khai","Người theo dõi","Chỉ mình tôi"};
        String[] values={"public","followers","private"};
        String current=birthDate?birthDateAudience:genderAudience;
        int selected=java.util.Arrays.asList(values).indexOf(current);
        new android.app.AlertDialog.Builder(this).setTitle("Ai có thể xem "+field+"?")
                .setSingleChoiceItems(labels,Math.max(0,selected),(dialog,index)->{
                    if(birthDate) birthDateAudience=values[index]; else genderAudience=values[index];
                    updateFieldAudienceLabels(); dialog.dismiss();
                }).setNegativeButton("Đóng",null).show();
    }

    private void updateFieldAudienceLabels() {
        binding.btnBirthDateAudience.setText("Quyền xem ngày sinh · "+audienceLabel(birthDateAudience));
        binding.btnGenderAudience.setText("Quyền xem giới tính · "+audienceLabel(genderAudience));
    }

    private String audienceLabel(String audience) {
        return "public".equals(audience)?"Công khai":"followers".equals(audience)?"Người theo dõi":"Chỉ mình tôi";
    }

    private static String avatarPresetForGender(String value) {
        if("nam".equalsIgnoreCase(value)||"male".equalsIgnoreCase(value)) return "male";
        if("nữ".equalsIgnoreCase(value)||"nu".equalsIgnoreCase(value)||"female".equalsIgnoreCase(value)) return "female";
        return "neutral";
    }

    private void setStatus(String message) { Forms.status(this, message); }
    private void hideCardContaining(View child) {
        android.view.ViewParent parent = child.getParent();
        while (parent instanceof View) {
            if (parent instanceof MaterialCardView) { ((View) parent).setVisibility(View.GONE); return; }
            parent = parent.getParent();
        }
    }
    private void openSuggestions() {
        startActivity(new android.content.Intent(this, PeopleSuggestionsActivity.class));
        finish();
    }
    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    private void setBusy(boolean value) {
        busy = value;
        binding.etName.setEnabled(!value);binding.etUsername.setEnabled(!value);
        binding.etBio.setEnabled(!value);
        binding.etDob.setEnabled(!value);
        binding.etGender.setEnabled(!value);
        binding.etLocation.setEnabled(!value);
        binding.btnLocationAudience.setEnabled(!value);
        binding.btnBirthDateAudience.setEnabled(!value);
        binding.btnGenderAudience.setEnabled(!value);
        binding.btnSavePublic.setEnabled(!value);
        binding.btnPickAvatar.setEnabled(!value);
    }

    private void save() {
        if (profile == null || busy) return;
        String birthDate = ProfileFieldSelectors.dateValue(binding.etDob);
        String gender = binding.etGender.getText().toString().trim();
        if ("Không muốn nêu".equals(gender)) gender = "";
        String location = binding.etLocation.getText().toString().trim();
        JSONObject args = Api.obj(
                "p_display_name", binding.etName.getText().toString(),
                "p_bio", binding.etBio.getText().toString(),
                "p_username", binding.etUsername.getText().toString().trim(),
                "p_avatar_asset_id", avatar,
                "p_cover_asset_id", cover,
                "p_location", location.isEmpty() ? JSONObject.NULL : location,
                "p_location_audience", locationAudience,
                "p_birth_date", birthDate.isEmpty() ? JSONObject.NULL : birthDate,
                "p_birth_date_audience", birthDateAudience,
                "p_gender", gender.isEmpty() ? JSONObject.NULL : gender,
                "p_gender_audience", genderAudience,
                "p_expected_version", profile.optLong("version"));
        setBusy(true);
        setStatus("Đang lưu hồ sơ…");
        request = Api.run(() -> {
            com.example.meetomxh.supabase.SocialContract.requireProfileFieldVisibility();
            return RpcClient.object("update_profile_v37", args);
        }, new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject value) {
                if (!alive()) return;
                profile = value;
                avatarPreset = value.optString("avatar_preset", avatarPreset);
                updateFieldAudienceLabels();
                showCloudPreview();
                setBusy(false);
                if (onboardingAssetsOnly) { openSuggestions(); return; }
                setStatus("Đã lưu hồ sơ.");
            }
            @Override public void error(String error) {
                if (alive()) { setBusy(false); setStatus(error); }
            }
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (selectedUri != null) out.putString("media_uri", selectedUri.toString());
        out.putBoolean("media_uploading", uploadingMedia);
        out.putBoolean("media_retry", binding.btnRetryUpload.getVisibility() == View.VISIBLE);
        if (avatar != null) out.putString("media_avatar", avatar);
    }

    @Override protected void onDestroy() {
        if (request != null) request.remove();
        if (upload != null) upload.cancel();
        super.onDestroy();
    }
}
