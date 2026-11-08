package com.example.meetomxh;

import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.SupabaseAuthClient;
import com.google.android.material.textfield.TextInputLayout;
import org.json.JSONObject;

public final class ChangePasswordActivity extends AppCompatActivity {
    private EditText current,newPassword,confirm;
    private Button submit;
    private final java.util.List<TextInputLayout> inputs=new java.util.ArrayList<>();
    private Subscription request;
    private boolean submitting;
    private long epoch;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(SessionManager.getInstance().getCurrentUser()==null){finish();return;}
        epoch=SessionManager.getInstance().generation();
        LinearLayout form=Forms.start(this,"Đổi mật khẩu");
        current=passwordField(form,"Mật khẩu hiện tại","password");
        newPassword=passwordField(form,"Mật khẩu mới","newPassword");
        confirm=passwordField(form,"Nhập lại mật khẩu mới","newPassword");
        confirm.setImeOptions(EditorInfo.IME_ACTION_DONE);
        confirm.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_DONE){submit();return true;}return false;
        });
        submit=Forms.button(form,"Xác nhận",this::submit);
        Forms.status(this,"Mật khẩu mới cần ít nhất 8 ký tự và khác mật khẩu hiện tại.");
    }

    private EditText passwordField(LinearLayout form,String label,String autofill){
        TextInputLayout wrapper=new TextInputLayout(this);
        wrapper.setHint(label);wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        wrapper.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        wrapper.setEndIconContentDescription("Hiện hoặc ẩn mật khẩu");
        com.google.android.material.textfield.TextInputEditText field=new com.google.android.material.textfield.TextInputEditText(wrapper.getContext());
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true);field.setSaveEnabled(false);field.setAutofillHints(autofill);
        field.setTextColor(getColor(R.color.text_primary));
        wrapper.addView(field,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
        layout.bottomMargin=(int)(12*getResources().getDisplayMetrics().density);
        form.addView(wrapper,layout);inputs.add(wrapper);return field;
    }

    private void submit(){
        if(submitting)return;
        if(!alive()){finish();return;}
        current.setError(null);newPassword.setError(null);confirm.setError(null);
        String oldValue=current.getText().toString(),newValue=newPassword.getText().toString();
        String confirmed=confirm.getText().toString();
        if(oldValue.isEmpty()){current.setError("Nhập mật khẩu hiện tại");current.requestFocus();return;}
        if(newValue.length()<8){newPassword.setError("Mật khẩu mới cần ít nhất 8 ký tự");newPassword.requestFocus();return;}
        if(oldValue.equals(newValue)){newPassword.setError("Mật khẩu mới phải khác mật khẩu hiện tại");newPassword.requestFocus();return;}
        if(!newValue.equals(confirmed)){confirm.setError("Mật khẩu nhập lại không khớp");confirm.requestFocus();return;}
        setSubmitting(true);Forms.status(this,"Đang xác minh và đổi mật khẩu…");
        request=SupabaseAuthClient.changePassword(oldValue,newValue,new Api.Callback<JSONObject>(){
            public void success(JSONObject result){
                if(!alive())return;
                current.setText("");newPassword.setText("");confirm.setText("");
                Toast.makeText(ChangePasswordActivity.this,"Đã đổi mật khẩu thành công",Toast.LENGTH_LONG).show();
                finish();
            }
            public void error(String message){if(alive()){setSubmitting(false);Forms.status(ChangePasswordActivity.this,message);}}
        });
    }
    private boolean alive(){return !isFinishing()&&!isDestroyed()&&epoch==SessionManager.getInstance().generation();}
    private void setSubmitting(boolean value){
        submitting=value;for(TextInputLayout input:inputs)input.setEnabled(!value);
        submit.setEnabled(!value);submit.setText(value?"Đang cập nhật…":"Xác nhận");
    }
    @Override protected void onDestroy(){if(request!=null)request.remove();super.onDestroy();}
}
