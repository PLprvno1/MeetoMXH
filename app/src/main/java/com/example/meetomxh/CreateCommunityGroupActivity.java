package com.example.meetomxh;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.databinding.ActivityCreateCommunityGroupBinding;
import com.example.meetomxh.repositories.CommunityGroupRepository;
import com.example.meetomxh.supabase.RequestIds;

public class CreateCommunityGroupActivity extends AppCompatActivity {
    private ActivityCreateCommunityGroupBinding binding;
    private String privacy="public";
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); binding=ActivityCreateCommunityGroupBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        binding.btnBack.setOnClickListener(v->finish());
        binding.btnSelectPrivacy.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Quyền riêng tư")
                .setItems(new String[]{"Công khai — mọi người có thể tìm thấy","Riêng tư — thành viên mới xem được bài viết","Ẩn — chỉ thành viên và người được mời"},(d,n)->{
                    privacy=n==0?"public":n==1?"private":"hidden";
                    binding.btnSelectPrivacy.setText(n==0?"Công khai":n==1?"Riêng tư":"Ẩn");
                }).show());
        binding.btnSubmitCreateGroup.setOnClickListener(v->{
            String name=binding.etGroupName.getText().toString().trim(), description=binding.etGroupDescription.getText().toString().trim();
            if(name.isEmpty()){binding.etGroupName.setError("Nhập tên nhóm");return;}
            binding.btnSubmitCreateGroup.setEnabled(false);
            String fingerprint=name+"|"+description+"|"+privacy;
            String requestId=RequestIds.get("community_group",fingerprint);
            CommunityGroupRepository.create(name,description,privacy,true,"",requestId,new CommunityGroupRepository.Callback<org.json.JSONObject>(){
                public void success(org.json.JSONObject group){RequestIds.complete("community_group",fingerprint);setResult(RESULT_OK);Toast.makeText(CreateCommunityGroupActivity.this,"Đã tạo nhóm",Toast.LENGTH_SHORT).show();finish();}
                public void error(String message){binding.btnSubmitCreateGroup.setEnabled(true);Toast.makeText(CreateCommunityGroupActivity.this,message,Toast.LENGTH_LONG).show();}
            });
        });
    }
}
