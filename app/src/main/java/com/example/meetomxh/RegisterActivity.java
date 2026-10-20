package com.example.meetomxh;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.meetomxh.databinding.ActivityRegisterBinding;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RegisterActivity extends AppCompatActivity {

    private static final String IMGBB_API_KEY = "a6ded9f4bbefeed4cc6dac86bd0fed0a";

    private ActivityRegisterBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    // Quản lý luồng các bước
    private int currentStep = 1;
    private boolean isEmailMode = true;

    // Dữ liệu người dùng thu thập qua các bước
    private String fullName = "";
    private String selectedDob = "";
    private Calendar dobCalendar = Calendar.getInstance();
    private String gender = "Nam";
    private String email = "";
    private String phoneNumber = "";
    private String password = "";
    private String bio = "";

    // Image Uris
    private Uri selectedAvatarUri;
    private Uri selectedCoverUri;

    // Phone Auth
    private String phoneVerificationId;
    private PhoneAuthProvider.ForceResendingToken phoneResendToken;
    private CountDownTimer otpTimer;

    // Password visibility toggles
    private boolean isPasswordVisible = false;
    private boolean isConfirmPasswordVisible = false;

    // Pickers
    private final ActivityResultLauncher<Intent> avatarPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedAvatarUri = result.getData().getData();
                    binding.imgAvatarPreview.setImageURI(selectedAvatarUri);
                }
            }
    );

    private final ActivityResultLauncher<Intent> coverPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedCoverUri = result.getData().getData();
                    binding.imgCoverPreview.setImageURI(selectedCoverUri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityRegisterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Lắng nghe WindowInsets để đẩy khoảng cách trên (padding top) xuống an toàn khỏi viền/camera notch
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.registerMainContainer, (v, insets) -> {
            androidx.core.graphics.Insets statusBarInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars());
            int topPadding = Math.max(statusBarInsets.top + 16, (int) (36 * getResources().getDisplayMetrics().density));
            v.setPadding(v.getPaddingLeft(), topPadding, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        setupUI();
        showStep(1);
    }

    private void setupUI() {
        // Nút Back trên Toolbar
        binding.btnBack.setOnClickListener(v -> handleBackNavigation());

        // BƯỚC 1: Chọn ngày sinh
        binding.btnDatePickerCard.setOnClickListener(v -> showDatePicker());

        // BƯỚC 1: Chọn giới tính
        binding.rgGender.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbMale) {
                gender = "Nam";
                binding.etCustomGenderText.setVisibility(View.GONE);
            } else if (checkedId == R.id.rbFemale) {
                gender = "Nữ";
                binding.etCustomGenderText.setVisibility(View.GONE);
            } else if (checkedId == R.id.rbCustomGender) {
                gender = "Tùy chỉnh";
                binding.etCustomGenderText.setVisibility(View.VISIBLE);
            }
        });

        binding.btnNextStep1.setOnClickListener(v -> processStep1());

        // BƯỚC 2: Chuyển Tab Email vs Phone
        binding.btnTabEmail.setOnClickListener(v -> switchContactTab(true));
        binding.btnTabPhone.setOnClickListener(v -> switchContactTab(false));
        binding.btnNextStep2.setOnClickListener(v -> processStep2());

        // BƯỚC 3: Mật khẩu & Nhập lại mật khẩu
        binding.btnTogglePassword.setOnClickListener(v -> togglePasswordVisibility(true));
        binding.btnToggleConfirmPassword.setOnClickListener(v -> togglePasswordVisibility(false));

        binding.etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePasswordStrength(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        binding.btnSubmitRegister.setOnClickListener(v -> processStep3Submit());

        // BƯỚC 4: Xác minh OTP / Email
        binding.btnVerifyOtp.setOnClickListener(v -> verifyPhoneOtp());
        binding.btnResendOtp.setOnClickListener(v -> resendPhoneOtp());

        binding.btnCheckEmailVerified.setOnClickListener(v -> checkEmailVerificationStatus());
        binding.btnResendEmail.setOnClickListener(v -> resendEmailVerification());

        // BƯỚC 5: Hồ sơ
        binding.btnPickAvatar.setOnClickListener(v -> pickImage(avatarPickerLauncher));
        binding.btnPickCover.setOnClickListener(v -> pickImage(coverPickerLauncher));

        binding.btnCompleteProfile.setOnClickListener(v -> completeUserProfile(false));
        binding.btnSkipProfile.setOnClickListener(v -> completeUserProfile(true));
    }

    private void handleBackNavigation() {
        if (currentStep > 1 && currentStep != 4) {
            showStep(currentStep - 1);
        } else {
            finish();
        }
    }

    private void showStep(int step) {
        this.currentStep = step;
        binding.tvStepIndicator.setText("Bước " + step + "/5");
        binding.progressBar.setProgress(step, true);

        binding.layoutStep1.setVisibility(step == 1 ? View.VISIBLE : View.GONE);
        binding.layoutStep2.setVisibility(step == 2 ? View.VISIBLE : View.GONE);
        binding.layoutStep3.setVisibility(step == 3 ? View.VISIBLE : View.GONE);
        binding.layoutStep4.setVisibility(step == 4 ? View.VISIBLE : View.GONE);
        binding.layoutStep5.setVisibility(step == 5 ? View.VISIBLE : View.GONE);
    }

    // ==================== XỬ LÝ BƯỚC 1: THÔNG TIN CÁ NHÂN ====================
    private void showDatePicker() {
        Calendar now = Calendar.getInstance();
        DatePickerDialog datePickerDialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    dobCalendar.set(year, month, dayOfMonth);
                    selectedDob = String.format(Locale.getDefault(), "%02d/%02d/%04d", dayOfMonth, month + 1, year);
                    binding.tvSelectedDob.setText(selectedDob);
                    binding.tvSelectedDob.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
                },
                now.get(Calendar.YEAR) - 18,
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)
        );
        datePickerDialog.getDatePicker().setMaxDate(now.getTimeInMillis());
        datePickerDialog.show();
    }

    private void processStep1() {
        fullName = binding.etFullName.getText().toString().trim();
        if (fullName.isEmpty() || fullName.length() < 2) {
            Toast.makeText(this, "Vui lòng nhập họ và tên hợp lệ", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedDob.isEmpty()) {
            Toast.makeText(this, "Vui lòng chọn ngày sinh của bạn", Toast.LENGTH_SHORT).show();
            return;
        }

        // Kiểm tra độ tuổi tối thiểu (13 tuổi)
        Calendar minAgeCal = Calendar.getInstance();
        minAgeCal.add(Calendar.YEAR, -13);
        if (dobCalendar.after(minAgeCal)) {
            Toast.makeText(this, "Bạn phải đủ ít nhất 13 tuổi để sử dụng Meeto", Toast.LENGTH_LONG).show();
            return;
        }

        if (binding.rgGender.getCheckedRadioButtonId() == R.id.rbCustomGender) {
            String customText = binding.etCustomGenderText.getText().toString().trim();
            if (!customText.isEmpty()) {
                gender = "Tùy chỉnh: " + customText;
            }
        }

        showStep(2);
    }

    // ==================== XỬ LÝ BƯỚC 2: PHƯƠNG THỨC LIÊN HỆ ====================
    private void switchContactTab(boolean isEmail) {
        this.isEmailMode = isEmail;
        if (isEmail) {
            binding.btnTabEmail.setBackgroundTintList(getResources().getColorStateList(R.color.brand_blue, getTheme()));
            binding.btnTabEmail.setTextColor(0xFFFFFFFF);
            binding.btnTabPhone.setBackgroundTintList(null);
            binding.btnTabPhone.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));

            binding.layoutEmailContainer.setVisibility(View.VISIBLE);
            binding.layoutPhoneContainer.setVisibility(View.GONE);
        } else {
            binding.btnTabPhone.setBackgroundTintList(getResources().getColorStateList(R.color.brand_blue, getTheme()));
            binding.btnTabPhone.setTextColor(0xFFFFFFFF);
            binding.btnTabEmail.setBackgroundTintList(null);
            binding.btnTabEmail.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));

            binding.layoutPhoneContainer.setVisibility(View.VISIBLE);
            binding.layoutEmailContainer.setVisibility(View.GONE);
        }
    }

    private void processStep2() {
        if (isEmailMode) {
            email = binding.etEmail.getText().toString().trim().toLowerCase();
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Vui lòng nhập địa chỉ Email đúng định dạng", Toast.LENGTH_SHORT).show();
                return;
            }
            phoneNumber = "";
        } else {
            String rawPhone = binding.etPhone.getText().toString().trim();
            if (rawPhone.isEmpty() || rawPhone.length() < 9 || rawPhone.length() > 11) {
                Toast.makeText(this, "Vui lòng nhập số điện thoại hợp lệ (9 - 11 chữ số)", Toast.LENGTH_SHORT).show();
                return;
            }
            if (rawPhone.startsWith("0")) {
                rawPhone = rawPhone.substring(1);
            }
            phoneNumber = "+84" + rawPhone;
            email = "";
        }

        // Cập nhật câu tóm tắt ở bước 3
        if (isEmailMode) {
            binding.tvContactSummary.setText("Đăng ký tài khoản với Email: " + email);
        } else {
            binding.tvContactSummary.setText("Đăng ký tài khoản với SĐT: " + phoneNumber);
        }

        showStep(3);
    }

    // ==================== XỬ LÝ BƯỚC 3: MẬT KHẨU ====================
    private void togglePasswordVisibility(boolean isMainPassword) {
        if (isMainPassword) {
            isPasswordVisible = !isPasswordVisible;
            binding.etPassword.setInputType(isPasswordVisible ?
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                    (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            binding.btnTogglePassword.setImageResource(isPasswordVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
            binding.etPassword.setSelection(binding.etPassword.getText().length());
        } else {
            isConfirmPasswordVisible = !isConfirmPasswordVisible;
            binding.etConfirmPassword.setInputType(isConfirmPasswordVisible ?
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD :
                    (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            binding.btnToggleConfirmPassword.setImageResource(isConfirmPasswordVisible ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
            binding.etConfirmPassword.setSelection(binding.etConfirmPassword.getText().length());
        }
    }

    private void updatePasswordStrength(String pwd) {
        if (pwd.isEmpty()) {
            binding.tvPasswordStrength.setText("Độ mạnh mật khẩu: Chưa nhập");
            binding.tvPasswordStrength.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            return;
        }

        if (pwd.length() < 8) {
            binding.tvPasswordStrength.setText("Độ mạnh mật khẩu: Yếu (Yêu cầu ít nhất 8 ký tự)");
            binding.tvPasswordStrength.setTextColor(0xFFE41E3F);
        } else if (pwd.length() >= 10 && pwd.matches(".*[0-9].*") && pwd.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            binding.tvPasswordStrength.setText("Độ mạnh mật khẩu: Rất mạnh ✅");
            binding.tvPasswordStrength.setTextColor(0xFF45BD62);
        } else {
            binding.tvPasswordStrength.setText("Độ mạnh mật khẩu: Khá tốt 👍");
            binding.tvPasswordStrength.setTextColor(0xFF1877F2);
        }
    }

    private void processStep3Submit() {
        password = binding.etPassword.getText().toString().trim();
        String confirmPwd = binding.etConfirmPassword.getText().toString().trim();

        if (password.length() < 8) {
            Toast.makeText(this, "Mật khẩu phải có ít nhất 8 ký tự", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirmPwd)) {
            Toast.makeText(this, "Mật khẩu xác nhận không trùng khớp", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnSubmitRegister.setEnabled(false);
        binding.btnSubmitRegister.setText("Đang khởi tạo tài khoản...");

        if (isEmailMode) {
            startEmailAccountCreation();
        } else {
            startPhoneAccountCreation();
        }
    }

    // ==================== BƯỚC 4: THỰC HIỆN TẠO TÀI KHOẢN & XÁC MINH ====================

    // A. LUỒNG EMAIL
    private void startEmailAccountCreation() {
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    binding.btnSubmitRegister.setEnabled(true);
                    binding.btnSubmitRegister.setText("Tạo tài khoản");

                    if (task.isSuccessful() && mAuth.getCurrentUser() != null) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        sendEmailVerificationLink(user);
                    } else {
                        String errorMsg = "Tạo tài khoản thất bại";
                        if (task.getException() instanceof FirebaseAuthUserCollisionException) {
                            errorMsg = "Địa chỉ email này đã được sử dụng bởi tài khoản khác";
                        } else if (task.getException() != null) {
                            errorMsg = task.getException().getMessage();
                        }
                        Toast.makeText(RegisterActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void sendEmailVerificationLink(FirebaseUser user) {
        user.sendEmailVerification().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                showStep(4);
                binding.layoutPhoneVerification.setVisibility(View.GONE);
                binding.layoutEmailVerification.setVisibility(View.VISIBLE);

                String maskedEmail = email.replaceAll("(^.?)[^@]*", "$1***");
                binding.tvEmailVerifySubtitle.setText("Chúng tôi đã gửi liên kết xác minh tới email: " + maskedEmail + ". Vui lòng mở hộp thư email, nhấn vào liên kết xác minh rồi bấm nút bên dưới.");
            } else {
                Toast.makeText(this, "Không thể gửi email xác minh: " + (task.getException() != null ? task.getException().getMessage() : ""), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void checkEmailVerificationStatus() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Phiên đăng ký hết hạn. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnCheckEmailVerified.setEnabled(false);
        binding.btnCheckEmailVerified.setText("Đang kiểm tra...");

        user.reload().addOnCompleteListener(task -> {
            binding.btnCheckEmailVerified.setEnabled(true);
            binding.btnCheckEmailVerified.setText("Tôi đã xác minh Email");

            if (user.isEmailVerified()) {
                Toast.makeText(RegisterActivity.this, "Xác minh Email thành công!", Toast.LENGTH_SHORT).show();
                showStep(5);
            } else {
                Toast.makeText(RegisterActivity.this, "Email chưa được xác minh. Vui lòng kiểm tra hộp thư của bạn.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void resendEmailVerification() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            user.sendEmailVerification().addOnSuccessListener(aVoid ->
                    Toast.makeText(this, "Đã gửi lại email xác minh!", Toast.LENGTH_SHORT).show()
            ).addOnFailureListener(e ->
                    Toast.makeText(this, "Gửi lại thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show()
            );
        }
    }

    // B. LUỒNG PHONE OTP
    private void startPhoneAccountCreation() {
        PhoneAuthOptions options = PhoneAuthOptions.newBuilder(mAuth)
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(this)
                .setCallbacks(mPhoneCallbacks)
                .build();
        PhoneAuthProvider.verifyPhoneNumber(options);
    }

    private final PhoneAuthProvider.OnVerificationStateChangedCallbacks mPhoneCallbacks =
            new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                @Override
                public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                    signInWithPhoneCredential(credential);
                }

                @Override
                public void onVerificationFailed(@NonNull FirebaseException e) {
                    binding.btnSubmitRegister.setEnabled(true);
                    binding.btnSubmitRegister.setText("Tạo tài khoản");
                    Toast.makeText(RegisterActivity.this, "Xác minh SĐT thất bại: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }

                @Override
                public void onCodeSent(@NonNull String verificationId, @NonNull PhoneAuthProvider.ForceResendingToken token) {
                    binding.btnSubmitRegister.setEnabled(true);
                    binding.btnSubmitRegister.setText("Tạo tài khoản");

                    phoneVerificationId = verificationId;
                    phoneResendToken = token;

                    showStep(4);
                    binding.layoutEmailVerification.setVisibility(View.GONE);
                    binding.layoutPhoneVerification.setVisibility(View.VISIBLE);

                    String maskedPhone = phoneNumber.substring(0, 3) + "****" + phoneNumber.substring(phoneNumber.length() - 3);
                    binding.tvPhoneOtpSubtitle.setText("Mã xác minh OTP 6 chữ số vừa được gửi tới " + maskedPhone);

                    startOtpCountdownTimer();
                }
            };

    private void startOtpCountdownTimer() {
        binding.btnResendOtp.setVisibility(View.GONE);
        binding.tvOtpTimer.setVisibility(View.VISIBLE);

        if (otpTimer != null) {
            otpTimer.cancel();
        }

        otpTimer = new CountDownTimer(60000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                binding.tvOtpTimer.setText("Gửi lại mã sau " + (millisUntilFinished / 1000) + "s");
            }

            @Override
            public void onFinish() {
                binding.tvOtpTimer.setVisibility(View.GONE);
                binding.btnResendOtp.setVisibility(View.VISIBLE);
            }
        }.start();
    }

    private void resendPhoneOtp() {
        if (phoneResendToken != null) {
            PhoneAuthOptions options = PhoneAuthOptions.newBuilder(mAuth)
                    .setPhoneNumber(phoneNumber)
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setActivity(this)
                    .setCallbacks(mPhoneCallbacks)
                    .setForceResendingToken(phoneResendToken)
                    .build();
            PhoneAuthProvider.verifyPhoneNumber(options);
            Toast.makeText(this, "Đang gửi lại mã OTP...", Toast.LENGTH_SHORT).show();
        }
    }

    private void verifyPhoneOtp() {
        String code = binding.etOtpCode.getText().toString().trim();
        if (code.length() != 6) {
            Toast.makeText(this, "Vui lòng nhập đủ 6 chữ số mã OTP", Toast.LENGTH_SHORT).show();
            return;
        }

        if (phoneVerificationId == null) {
            Toast.makeText(this, "Mã OTP đã hết hạn. Vui lòng bấm gửi lại mã.", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnVerifyOtp.setEnabled(false);
        binding.btnVerifyOtp.setText("Đang kiểm tra...");

        PhoneAuthCredential credential = PhoneAuthProvider.getCredential(phoneVerificationId, code);
        signInWithPhoneCredential(credential);
    }

    private void signInWithPhoneCredential(PhoneAuthCredential credential) {
        mAuth.signInWithCredential(credential).addOnCompleteListener(this, task -> {
            binding.btnVerifyOtp.setEnabled(true);
            binding.btnVerifyOtp.setText("Xác minh & Tiếp tục");

            if (task.isSuccessful()) {
                Toast.makeText(RegisterActivity.this, "Xác minh số điện thoại thành công!", Toast.LENGTH_SHORT).show();
                showStep(5);
            } else {
                String error = "Mã OTP không chính xác hoặc đã hết hạn";
                if (task.getException() instanceof FirebaseAuthInvalidCredentialsException) {
                    error = "Mã OTP không hợp lệ. Vui lòng kiểm tra lại.";
                }
                Toast.makeText(RegisterActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ==================== BƯỚC 5: KHỞI TẠO HỒ SƠ ====================
    private void pickImage(ActivityResultLauncher<Intent> launcher) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        launcher.launch(intent);
    }

    private void completeUserProfile(boolean isSkip) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "Không tìm thấy phiên đăng nhập. Vui lòng thử lại.", Toast.LENGTH_SHORT).show();
            return;
        }

        bio = binding.etBio.getText().toString().trim();

        binding.btnCompleteProfile.setEnabled(false);
        binding.btnCompleteProfile.setText("Đang lưu hồ sơ...");

        if (!isSkip && selectedAvatarUri != null) {
            uploadImageToImgbb(selectedAvatarUri, new OnImageUploadListener() {
                @Override
                public void onSuccess(String avatarUrl) {
                    if (selectedCoverUri != null) {
                        uploadImageToImgbb(selectedCoverUri, new OnImageUploadListener() {
                            @Override
                            public void onSuccess(String coverUrl) {
                                saveUserProfileToFirestore(currentUser.getUid(), avatarUrl, coverUrl);
                            }

                            @Override
                            public void onFailure(String errorMsg) {
                                handleProfileImageUploadFailure(errorMsg);
                            }
                        });
                    } else {
                        saveUserProfileToFirestore(currentUser.getUid(), avatarUrl, "");
                    }
                }

                @Override
                public void onFailure(String errorMsg) {
                    handleProfileImageUploadFailure(errorMsg);
                }
            });
        } else {
            saveUserProfileToFirestore(currentUser.getUid(), "", "");
        }
    }

    private void handleProfileImageUploadFailure(String errorMsg) {
        Log.w("RegisterActivity", "Profile image upload failed: " + errorMsg);
        binding.btnCompleteProfile.setEnabled(true);
        binding.btnCompleteProfile.setText("Hoàn tất & Vào trang chủ");
        Toast.makeText(this, "Tải ảnh hồ sơ thất bại. Vui lòng thử lại.", Toast.LENGTH_LONG).show();
    }

    private void saveUserProfileToFirestore(String uid, String avatarUrl, String coverUrl) {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("uid", uid);
        userMap.put("fullName", fullName);
        userMap.put("dateOfBirth", selectedDob);
        userMap.put("gender", gender);
        userMap.put("email", email);
        userMap.put("phoneNumber", phoneNumber);
        userMap.put("avatarUrl", avatarUrl);
        userMap.put("coverUrl", coverUrl);
        userMap.put("bio", bio);
        userMap.put("contactVerified", true);
        userMap.put("profileCompleted", true);
        userMap.put("createdAt", System.currentTimeMillis());
        userMap.put("updatedAt", System.currentTimeMillis());

        db.collection("Users").document(uid)
                .set(userMap)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(RegisterActivity.this, "Đăng ký thành công! Chào mừng bạn đến với Meeto.", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e -> {
                    binding.btnCompleteProfile.setEnabled(true);
                    binding.btnCompleteProfile.setText("Hoàn tất & Vào trang chủ");
                    Toast.makeText(RegisterActivity.this, "Lưu thông tin hồ sơ thất bại: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    public interface OnImageUploadListener {
        void onSuccess(String imageUrl);
        void onFailure(String errorMsg);
    }

    private void uploadImageToImgbb(Uri imageUri, OnImageUploadListener listener) {
        try {
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
                if (inputStream == null) {
                    throw new IOException("Không thể mở file ảnh");
                }
                while ((len = inputStream.read(buffer)) != -1) {
                    byteBuffer.write(buffer, 0, len);
                }
            }
            byte[] imageBytes = byteBuffer.toByteArray();

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "upload.jpg",
                            RequestBody.create(imageBytes, MediaType.parse("image/*")))
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.imgbb.com/1/upload?key=" + IMGBB_API_KEY)
                    .post(requestBody)
                    .build();

            new OkHttpClient().newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    runOnUiThread(() -> listener.onFailure(e.getMessage()));
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (Response closeableResponse = response) {
                        if (closeableResponse.isSuccessful() && closeableResponse.body() != null) {
                            try {
                                String responseBody = closeableResponse.body().string();
                                String imageUrl = new JSONObject(responseBody).getJSONObject("data").getString("url");
                                runOnUiThread(() -> listener.onSuccess(imageUrl));
                            } catch (Exception e) {
                                runOnUiThread(() -> listener.onFailure("Lỗi xử lý JSON"));
                            }
                        } else {
                            runOnUiThread(() -> listener.onFailure("Imgbb lỗi mã: " + closeableResponse.code()));
                        }
                    }
                }
            });
        } catch (Exception e) {
            listener.onFailure("Không đọc được file ảnh: " + e.getMessage());
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (otpTimer != null) {
            otpTimer.cancel();
        }
    }
}
