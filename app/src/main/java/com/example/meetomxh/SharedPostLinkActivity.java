package com.example.meetomxh;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.PostDetails;
import java.util.UUID;

/** Opens an internal meeto://post/{uuid} link through the authorized post RPC. */
public final class SharedPostLinkActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id=getIntent().getData()==null?null:getIntent().getData().getLastPathSegment();
        try { UUID.fromString(id); PostDetails.show(this,id); }
        catch (Exception invalid) { Toast.makeText(this,"Liên kết bài viết không hợp lệ.",Toast.LENGTH_LONG).show();finish(); }
    }
}
