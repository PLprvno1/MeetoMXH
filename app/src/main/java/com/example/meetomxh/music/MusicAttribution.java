package com.example.meetomxh.music;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import org.json.JSONObject;

/** Small display-only attribution; published caption text remains untouched. */
public final class MusicAttribution {
    private MusicAttribution(){}
    public static void bind(Context context,TextView view,JSONObject metadata){
        String title=metadata==null?"":metadata.optString("title","").trim();
        if(title.isEmpty()){view.setText("");view.setVisibility(View.GONE);view.setOnClickListener(null);return;}
        String creator=metadata.optString("artist",metadata.optString("creator","")).trim();
        view.setText("♫ "+title+(creator.isEmpty()?"":" · "+creator)+" · CC0");view.setVisibility(View.VISIBLE);
        if(OpenverseMusicClient.validPublishedMetadata(metadata))view.setOnClickListener(v->showLinks(context,metadata));
        else view.setOnClickListener(null);
    }
    public static void showLinks(Context context,JSONObject metadata){if(metadata==null||!OpenverseMusicClient.validPublishedMetadata(metadata))return;new AlertDialog.Builder(context)
            .setTitle(metadata.optString("title","Nhạc CC0")).setItems(new String[]{"Mở trang nguồn","Xem giấy phép CC0"},(dialog,which)->open(context,metadata.optString(which==0?"source_url":"license_url")))
            .setNegativeButton("Đóng",null).show();}
    private static void open(Context context,String address){try{context.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(address)));}catch(Exception ignored){}}
}
