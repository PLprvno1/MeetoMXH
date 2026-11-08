package com.example.meetomxh;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.example.meetomxh.databinding.ActivityReportContentBinding;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.RpcClient;

public class ReportContentActivity extends AppCompatActivity {
    private com.example.meetomxh.ui.ActivityRequests requests;
    public static final String EXTRA_TARGET_TYPE = "extra_target_type";
    public static final String EXTRA_TARGET_ID = "extra_target_id";
    public static final String EXTRA_GROUP_ID = "extra_group_id";
    private ActivityReportContentBinding binding;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requests=new com.example.meetomxh.ui.ActivityRequests(this);
        binding = ActivityReportContentBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout, (v, insets) -> {
            Insets top = insets.getInsets(WindowInsetsCompat.Type.statusBars()); v.setPadding(0, top.top, 0, 0); return insets;
        });
        int maxDetails=getIntent().getStringExtra(EXTRA_GROUP_ID)==null?1000:3000;
        binding.etReportDetails.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(maxDetails)});
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnSubmitReport.setOnClickListener(v -> submit());
    }

    private void submit() {
        String type=getIntent().getStringExtra(EXTRA_TARGET_TYPE), target=getIntent().getStringExtra(EXTRA_TARGET_ID);
        String group=getIntent().getStringExtra(EXTRA_GROUP_ID);
        if(type==null||target==null){toast("Không tìm thấy nội dung cần báo cáo.");return;}
        String reason=binding.rbSpam.isChecked()?"spam":binding.rbHarassment.isChecked()?"harassment":
                binding.rbViolence.isChecked()?"inappropriate":binding.rbFalseInfo.isChecked()?"misinformation":"other";
        String submittedReason=group==null?reason:(binding.rbViolence.isChecked()?"violence":reason);
        String details=binding.etReportDetails.getText().toString().trim();
        String operation=group==null?"content_report":"group_report";
        String fingerprint=(group==null?"":group+"|")+type+"|"+target+"|"+submittedReason+"|"+details;
        binding.btnSubmitReport.setEnabled(false);
        requests.run(()->{
            String request=RequestIds.get(operation,fingerprint);
            Object result;
            if(group!=null) result=RpcClient.call("report_group_target",Api.obj("p_group_id",group,"p_target_type",type,
                    "p_target_id",target,"p_reason",submittedReason,"p_details",details,"p_client_request_id",request));
            else result=RpcClient.call("submit_content_report",Api.obj("p_target_type",type,"p_target_id",target,
                    "p_reason",submittedReason,"p_details",details,"p_client_request_id",request));
            RequestIds.complete(operation,fingerprint);return result;
        },new Api.Callback<Object>(){
            @Override public void success(Object result){toast("Đã gửi báo cáo để xem xét.");finish();}
            @Override public void error(String error){binding.btnSubmitReport.setEnabled(true);toast(error);}
        });
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
