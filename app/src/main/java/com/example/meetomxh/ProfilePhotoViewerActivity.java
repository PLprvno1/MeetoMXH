package com.example.meetomxh;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import org.json.JSONArray;
import java.util.ArrayList;

/** Fullscreen, swipeable viewer for references returned by an authorized media RPC. */
public final class ProfilePhotoViewerActivity extends AppCompatActivity {
    private static final String EXTRA_ITEMS="items", EXTRA_INDEX="index";
    /** Keep profile callers on the same authenticated viewer and its retry/zoom behavior. */
    public static Intent intent(Context c,JSONArray items,int index){
        ArrayList<String> refs=new ArrayList<>();
        if(items!=null)for(int i=0;i<items.length();i++){
            String ref=items.optString(i);
            if(ref!=null&&!ref.isEmpty()&&!"null".equals(ref))refs.add(ref);
        }
        return new Intent(c,ChatImageViewerActivity.class)
                .putExtra(ChatImageViewerActivity.EXTRA_IMAGE_URLS,refs.toArray(new String[0]))
                .putExtra(ChatImageViewerActivity.EXTRA_START_INDEX,Math.max(0,Math.min(index,refs.size()-1)));
    }
    @Override protected void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);RecyclerView pager=new RecyclerView(this);pager.setBackgroundColor(Color.BLACK);pager.setLayoutManager(new LinearLayoutManager(this,RecyclerView.HORIZONTAL,false));setContentView(pager);JSONArray refs;try{refs=new JSONArray(getIntent().getStringExtra(EXTRA_ITEMS));}catch(Exception e){finish();return;}pager.setAdapter(new RecyclerView.Adapter<Holder>(){public Holder onCreateViewHolder(ViewGroup p,int t){ImageView v=new ImageView(ProfilePhotoViewerActivity.this);v.setBackgroundColor(Color.BLACK);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setOnClickListener(x->finish());return new Holder(v);}public void onBindViewHolder(Holder h,int pos){Glide.with(ProfilePhotoViewerActivity.this).load(refs.optString(pos)).fitCenter().into(h.image);}public int getItemCount(){return refs.length();}});new PagerSnapHelper().attachToRecyclerView(pager);pager.scrollToPosition(Math.max(0,Math.min(refs.length()-1,getIntent().getIntExtra(EXTRA_INDEX,0))));}
    private static final class Holder extends RecyclerView.ViewHolder{final ImageView image;Holder(ImageView v){super(v);image=v;}}
}
