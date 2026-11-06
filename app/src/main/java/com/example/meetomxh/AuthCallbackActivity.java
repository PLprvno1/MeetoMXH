package com.example.meetomxh;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.*;
import org.json.JSONObject;

/** Visible confirmation result for PKCE, token-hash and server-issued implicit redirects. */
public final class AuthCallbackActivity extends AppCompatActivity {
 private Subscription request; private Button next;
 @Override protected void onCreate(Bundle saved){super.onCreate(saved);LinearLayout form=Forms.start(this,"Xác minh email");next=Forms.button(form,"Quay lại đăng nhập",()->startActivity(new Intent(this,LoginActivity.class)));Forms.status(this,"Đang xác minh liên kết…");
  Uri uri=getIntent().getData();
  if(uri==null||!"meeto".equals(uri.getScheme())||!"auth".equals(uri.getHost())||!"/callback".equals(uri.getPath())){fail("Liên kết không hợp lệ.");return;}
  Uri fragment=Uri.parse("https://callback.invalid/?"+(uri.getFragment()==null?"":uri.getFragment()));
  if(uri.getQueryParameter("error")!=null||fragment.getQueryParameter("error")!=null){fail("Liên kết đã hết hạn hoặc đã được sử dụng. Đăng nhập hoặc yêu cầu gửi lại email xác minh.");return;}
  String code=uri.getQueryParameter("code"),hash=uri.getQueryParameter("token_hash"),type=uri.getQueryParameter("type");
  String access=fragment.getQueryParameter("access_token"),refresh=fragment.getQueryParameter("refresh_token");
  boolean recovery="yes".equals(SessionManager.getInstance().pending("recovery"));
  next.setEnabled(false);
  request=Api.run(()->{
   if(code!=null&&!code.isEmpty())return SupabaseAuthClient.exchange(code);
   if(hash!=null&&("signup".equals(type)||"email".equals(type)||recovery&&"recovery".equals(type))){JSONObject r=SupabaseAuthClient.verify(hash,type);RpcClient.object("bootstrap_profile",Api.obj());return r;}
   if(access!=null&&refresh!=null){
    // Validate with Auth before saving credentials supplied by an external intent.
    JSONObject user=(JSONObject)SupabaseHttpClient.raw("GET","/auth/v1/user",null,access);
    JSONObject session=Api.obj("access_token",access,"refresh_token",refresh,"user",user,"expires_in",3600);
    SessionManager.getInstance().accept(session);RpcClient.object("bootstrap_profile",Api.obj());return session;
   }
   throw new IllegalStateException("Liên kết thiếu thông tin xác minh. Hãy đăng nhập; nếu email chưa xác minh, yêu cầu gửi lại từ màn hình Đăng ký.");
  },new Api.Callback<JSONObject>(){
   public void success(JSONObject r){
    if(isFinishing())return;
    if(recovery){Forms.status(AuthCallbackActivity.this,"Đã xác minh yêu cầu khôi phục. Tiếp tục đặt mật khẩu mới.");next.setEnabled(true);next.setText("Đặt mật khẩu mới");next.setOnClickListener(v->{startActivity(new Intent(AuthCallbackActivity.this,ForgotPasswordActivity.class).putExtra("reset",true));finish();});return;}
    Forms.status(AuthCallbackActivity.this,"Đã xác minh email. Đang lưu thông tin bạn đã nhập khi đăng ký…");
    Api.run(OnboardingFlow::completeProfileFromPending,new Api.Callback<JSONObject>(){
     public void success(JSONObject profile){if(isFinishing())return;String email=r.optJSONObject("user")!=null?r.optJSONObject("user").optString("email"):SessionManager.getInstance().getCurrentUser()==null?"":SessionManager.getInstance().getCurrentUser().getEmail();startActivity(new Intent(AuthCallbackActivity.this,EmailConfirmationActivity.class).putExtra(EmailConfirmationActivity.EXTRA_EMAIL,email).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();}
     public void error(String e){fail(e);}
    });
   }   public void error(String e){fail(e);}
  });
 }
 private void fail(String e){Forms.status(this,e);next.setEnabled(true);}
 @Override protected void onDestroy(){if(request!=null)request.remove();super.onDestroy();}
}
