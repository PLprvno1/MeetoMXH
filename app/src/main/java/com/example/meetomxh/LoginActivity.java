package com.example.meetomxh;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.databinding.ActivityLoginBinding;
import com.example.meetomxh.supabase.*;
import org.json.JSONObject;
public class LoginActivity extends AppCompatActivity {
 private ActivityLoginBinding binding; private Subscription request;
 @Override protected void onCreate(Bundle state){super.onCreate(state);binding=ActivityLoginBinding.inflate(getLayoutInflater());setContentView(binding.getRoot());binding.etEmail.setHint("Email");binding.btnLogin.setOnClickListener(v->{binding.btnLogin.setEnabled(false);request=SupabaseAuthClient.login(binding.etEmail.getText().toString(),binding.etPassword.getText().toString(),profileCallback());});binding.btnRegister.setOnClickListener(v->startActivity(new Intent(this,RegisterActivity.class)));binding.tvForgotPassword.setOnClickListener(v->startActivity(new Intent(this,ForgotPasswordActivity.class)));if(SessionManager.getInstance().getCurrentUser()!=null)request=Api.run(ProfileClient::self,profileCallback());}
 @Override protected void onDestroy(){if(request!=null)request.remove();super.onDestroy();}
 private Api.Callback<JSONObject> profileCallback(){return new Api.Callback<JSONObject>(){public void success(JSONObject p){binding.btnLogin.setEnabled(true);if(!p.optString("account_state","active").equals("active")){Toast.makeText(LoginActivity.this,"Tài khoản không khả dụng",Toast.LENGTH_LONG).show();SessionManager.getInstance().clear();return;}Class<?> next=p.optBoolean("onboarding_completed")?MainActivity.class:RegisterActivity.class;Intent i=new Intent(LoginActivity.this,next);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(i);finish();}public void error(String e){binding.btnLogin.setEnabled(true);Toast.makeText(LoginActivity.this,e,Toast.LENGTH_LONG).show();}};}
}