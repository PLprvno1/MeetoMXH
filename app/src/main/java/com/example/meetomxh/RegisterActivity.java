package com.example.meetomxh;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SupabaseAuthClient;
import org.json.JSONObject;

public class RegisterActivity extends AppCompatActivity {
    private EditText name, dob, email, password;
    private EditText gender, location;
    private boolean onboarding;
    private final ActivityResultLauncher<Intent> photoEditor = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> enterMain());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        onboarding = SessionManager.getInstance().getCurrentUser() != null;
        LinearLayout form = Forms.start(this, onboarding ? "Hoàn tất hồ sơ" : "Đăng ký Meeto");
        name = Forms.field(form, "Họ và tên", SessionManager.getInstance().pending("signup_name"), InputType.TYPE_CLASS_TEXT);
        dob = Forms.field(form, "Ngày sinh (bắt buộc)", "", InputType.TYPE_CLASS_DATETIME);
        ProfileFieldSelectors.makeSelector(dob);
        dob.setOnClickListener(v -> ProfileFieldSelectors.date(this, dob));
        gender = Forms.field(form, "Giới tính (tùy chọn)", "", InputType.TYPE_CLASS_TEXT);
        ProfileFieldSelectors.makeSelector(gender);
        gender.setOnClickListener(v -> ProfileFieldSelectors.gender(this, gender));
        location = Forms.field(form, "Nơi ở hiện tại (tùy chọn)", "", InputType.TYPE_CLASS_TEXT);
        ProfileFieldSelectors.makeSelector(location);
        location.setOnClickListener(v -> ProfileFieldSelectors.location(this, location));
        email = Forms.field(form, "Email", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = Forms.field(form, "Mật khẩu (ít nhất 8 ký tự)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        email.setVisibility(onboarding ? android.view.View.GONE : android.view.View.VISIBLE);
        password.setVisibility(onboarding ? android.view.View.GONE : android.view.View.VISIBLE);
        if (onboarding) {
            String pendingDob = SessionManager.getInstance().pending("signup_dob");
            if (pendingDob != null) ProfileFieldSelectors.setDate(dob, pendingDob);
            name.setText(SessionManager.getInstance().pending("signup_name"));
            gender.setText(SessionManager.getInstance().pending("signup_gender"));
            location.setText(SessionManager.getInstance().pending("signup_location"));
        }
        Forms.status(this, onboarding ? "Chào mừng bạn đến với Meeto! Hoàn tất hồ sơ để bắt đầu. Nơi ở đã chọn được giữ lại và mặc định chỉ mình bạn xem." : "Email và mật khẩu để tạo tài khoản. Ngày sinh để hoàn tất hồ sơ; giới tính và nơi ở là tùy chọn. Nơi ở mặc định riêng tư.");
        Forms.button(form, onboarding ? "Hoàn tất hồ sơ" : "Đăng ký bằng email", this::submit);
        Forms.button(form, "Quay lại đăng nhập", () -> { if (onboarding) SessionManager.getInstance().signOut(); startActivity(new Intent(this, LoginActivity.class)); finish(); });
    }

    private void submit() {
        String displayName = name.getText().toString().trim();
        String birthDate = ProfileFieldSelectors.dateValue(dob);
        String selectedGender = gender.getText().toString().trim();
        if (displayName.isEmpty()) { Forms.status(this, "Vui lòng nhập họ và tên."); return; }
        if (!ProfileFieldSelectors.validDate(birthDate)) { Forms.status(this, "Vui lòng chọn ngày sinh hợp lệ (tối thiểu 13 tuổi)."); return; }
        if ("Không muốn nêu".equals(selectedGender)) selectedGender = "";
        if (!onboarding) {
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.getText().toString().trim()).matches()) { Forms.status(this, "Vui lòng nhập địa chỉ email hợp lệ."); return; }
            if (password.getText().length() < 8) { Forms.status(this, "Mật khẩu phải có ít nhất 8 ký tự."); return; }
        }
        final String genderValue = selectedGender;
        final String locationValue = location.getText().toString().trim();
        Forms.status(this, "Đang xử lý…");
        if (onboarding) {
            Api.run(() -> {
                JSONObject current = ProfileClient.self();
                return RpcClient.object("complete_onboarding_with_location", Api.obj("p_display_name", displayName,
                        "p_birth_date", birthDate, "p_gender", genderValue.isEmpty() ? null : genderValue,
                        "p_location", locationValue.isEmpty() ? null : locationValue,
                        "p_location_audience", "private",
                        "p_expected_version", current.getLong("version")));
            }, new Api.Callback<JSONObject>() {
                public void success(JSONObject profile) { chooseProfilePhotos(); }
                public void error(String error) { Forms.status(RegisterActivity.this, error); }
            });
        } else {
            try {
                SessionManager.getInstance().pending("signup_name", displayName);
                SessionManager.getInstance().pending("signup_dob", birthDate);
                SessionManager.getInstance().pending("signup_gender", genderValue);
                SessionManager.getInstance().pending("signup_location", locationValue);
            } catch (Exception e) { Forms.status(this, "Không lưu được thông tin đăng ký."); return; }
            SupabaseAuthClient.signup(email.getText().toString().trim(), password.getText().toString(), new Api.Callback<JSONObject>() {
                public void success(JSONObject response) { Forms.status(RegisterActivity.this, "Đã gửi yêu cầu xác minh email. Mở liên kết trong email, sau đó đăng nhập để hoàn tất hồ sơ."); }
                public void error(String error) { Forms.status(RegisterActivity.this, error); }
            });
        }
    }

    private void chooseProfilePhotos() {
        new AlertDialog.Builder(this)
                .setTitle("Ảnh đại diện & Ảnh bìa")
                .setMessage("Thêm ảnh đại diện và ảnh bìa để bạn bè dễ dàng nhận ra bạn trên Meeto. Bạn có thể chọn ảnh ngay bây giờ hoặc bỏ qua để khám phá ứng dụng.")
                .setPositiveButton("Chọn ảnh ngay", (dialog, which) -> photoEditor.launch(new Intent(this, ProfileEditActivity.class)))
                .setNegativeButton("Bỏ qua", (dialog, which) -> enterMain())
                .setOnCancelListener(dialog -> enterMain())
                .show();
    }

    private void enterMain() {
        startActivity(new Intent(this, RoleResolutionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
