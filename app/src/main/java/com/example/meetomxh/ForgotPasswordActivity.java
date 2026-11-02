package com.example.meetomxh;
import android.os.Bundle;
import android.widget.*;
import android.text.InputType;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.*;
import org.json.JSONObject;
public class ForgotPasswordActivity extends AppCompatActivity {
 @Override protected void onCreate(Bundle state){super.onCreate(state);boolean reset=getIntent().getBooleanExtra("reset",false);LinearLayout form=Forms.start(this,reset?"Đặt lại mật khẩu":"Quên mật khẩu");EditText field=Forms.field(form,reset?"Mật khẩu mới":"Email","",InputType.TYPE_CLASS_TEXT|(reset?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));Forms.button(form,reset?"Lưu mật khẩu mới":"Gửi email khôi phục",()->{Api.Callback<JSONObject> cb=new Api.Callback<JSONObject>(){public void success(JSONObject r){Forms.status(ForgotPasswordActivity.this,reset?"Đã đổi mật khẩu. Vui lòng đăng nhập lại.":"Nếu email hợp lệ, liên kết khôi phục đã được gửi.");if(reset){SessionManager.getInstance().signOut();startActivity(new android.content.Intent(ForgotPasswordActivity.this,LoginActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();}}public void error(String e){Forms.status(ForgotPasswordActivity.this,e);}};if(reset)SupabaseAuthClient.password(field.getText().toString(),cb);else SupabaseAuthClient.recover(field.getText().toString(),cb);});Forms.button(form,"Quay lại",this::finish);}
}