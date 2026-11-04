package com.example.meetomxh;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.databinding.ActivityStoryCreateBinding;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.StoryClient;

public class StoryCreateActivity extends AppCompatActivity {
    private ActivityStoryCreateBinding binding; private Uri selected; private String audience="friends",uploadedAsset;
    private SupabaseStorage.Transfer transfer;
    private final ActivityResultLauncher<String[]> picker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
        if(uri==null)return; selected=uri; String mime=getContentResolver().getType(uri);
        binding.layoutStoryPlaceholder.setVisibility(View.GONE); binding.ivStoryPreview.setVisibility(View.VISIBLE);
        if(mime!=null&&mime.startsWith("video/")) Api.run(()->SupabaseStorage.videoThumbnail(getContentResolver(),uri),new Api.Callback<android.graphics.Bitmap>(){
            public void success(android.graphics.Bitmap b){if(b!=null)binding.ivStoryPreview.setImageBitmap(b);else showError("Không đọc được video");}
            public void error(String e){showError(e);}
        }); else com.bumptech.glide.Glide.with(this).load(uri).into(binding.ivStoryPreview);
    });
    @Override protected void onCreate(Bundle b){super.onCreate(b);binding=ActivityStoryCreateBinding.inflate(getLayoutInflater());setContentView(binding.getRoot());
        binding.btnCloseStory.setOnClickListener(v->finish()); binding.btnPickStoryMedia.setText("Chọn ảnh hoặc video");
        binding.btnPickStoryMedia.setOnClickListener(v->picker.launch(new String[]{"image/*","video/mp4"}));
        binding.btnStoryPrivacy.setText("Bạn bè"); binding.btnStoryPrivacy.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Ai xem được tin này?")
                .setItems(new String[]{"Công khai","Bạn bè","Chỉ mình tôi"},(d,n)->{audience=n==0?"public":n==1?"friends":"private";binding.btnStoryPrivacy.setText(n==0?"Công khai":n==1?"Bạn bè":"Chỉ mình tôi");}).show());
        binding.btnShareStory.setOnClickListener(v->publish());
    }
    private void publish(){ if(uploadedAsset!=null){createStory();return;} if(selected==null){showError("Chọn ảnh hoặc video trước");return;} if(transfer!=null)return;
        binding.btnShareStory.setEnabled(false); binding.btnShareStory.setText("Đang tải 0%");
        String owner=SessionManager.getInstance().uid();
        transfer=SupabaseStorage.upload(getContentResolver(),selected,"stories",owner,new SupabaseStorage.UploadListener(){
            public void onProgress(int p){binding.btnShareStory.setText("Đang tải "+p+"%");}
            public void onSuccess(String ref){uploadedAsset=ref;createStory();}
            public void onFailure(String e){transfer=null;binding.btnShareStory.setEnabled(true);binding.btnShareStory.setText("Thử tải lại");showError(e);}
        });
    }
    private void createStory(){String caption=binding.etStoryCaption.getText().toString().trim();String op=uploadedAsset+"|"+caption+"|"+audience;
        String id=RequestIds.get("create_story_v1",op); Api.run(()->{StoryClient.create(uploadedAsset,caption,audience,id);RequestIds.complete("create_story_v1",op);return true;},new Api.Callback<Boolean>(){
            public void success(Boolean ok){setResult(RESULT_OK);Toast.makeText(StoryCreateActivity.this,"Đã đăng tin",Toast.LENGTH_SHORT).show();finish();}
            public void error(String e){transfer=null;binding.btnShareStory.setEnabled(true);binding.btnShareStory.setText("Thử lại đăng tin");showError(e);}
        });
    }
    private void showError(String e){Toast.makeText(this,e,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){if(isFinishing()&&transfer!=null)transfer.cancel();super.onDestroy();}
}
