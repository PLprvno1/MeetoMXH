package com.example.meetomxh;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.*;
import org.json.JSONObject;
public class AuthCallbackActivity extends AppCompatActivity {
 @Override protected void onCreate(Bundle state){super.onCreate(state);TextView message=new TextView(this);message.setText("Đang xác minh liên kết…");message.setPadding(32,80,32,32);setContentView(message);Uri uri=getIntent().getData();if(uri==null||!"meeto".equals(uri.getScheme())||!"auth".equals(uri.getHost())||!"/callback".equals(uri.getPath())||uri.getQueryParameter("code")==null){message.setText("Liên kết không hợp lệ hoặc đã hết hạn.");return;}if(SessionManager.getInstance().pending("pkce").isEmpty()){message.setText("Liên kết không khớp yêu cầu trên thiết bị này.");return;}boolean recovery="yes".equals(SessionManager.getInstance().pending("recovery"));if(SessionManager.getInstance().getCurrentUser()!=null){String verifier=SessionManager.getInstance().pending("pkce");SessionManager.getInstance().clear();try{SessionManager.getInstance().pending("pkce",verifier);}catch(Exception e){message.setText("Không khôi phục được yêu cầu xác minh.");return;}}Api.run(()->SupabaseAuthClient.exchange(uri.getQueryParameter("code")),new Api.Callback<JSONObject>(){public void success(JSONObject r){Intent next=new Intent(AuthCallbackActivity.this,recovery?ForgotPasswordActivity.class:LoginActivity.class);next.putExtra("reset",recovery);next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(next);finish();}public void error(String e){message.setText(e);}});}
}
