package com.example.meetomxh.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;

import com.example.meetomxh.R;
import com.example.meetomxh.supabase.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Named featured albums. Media references always come from authorized RPC responses. */
public final class FeaturedAlbumsUi {
    private final AppCompatActivity host;
    private final String owner;
    private final View section;
    private final LinearLayout container;
    private final long sessionEpoch=SessionManager.getInstance().generation();
    private final List<Subscription> jobs=new ArrayList<>();
    private final List<AlertDialog> dialogs=new ArrayList<>();
    private Subscription listJob;
    private boolean closed;

    public FeaturedAlbumsUi(AppCompatActivity host,String owner,View section,LinearLayout container){
        this.host=host;this.owner=owner;this.section=section;this.container=container;
    }
    private boolean alive(){return !closed&&!host.isFinishing()&&!host.isDestroyed()&&sessionEpoch==SessionManager.getInstance().generation();}
    private boolean self(){return owner.equals(SessionManager.getInstance().uid());}
    private int dp(int n){return Math.round(n*host.getResources().getDisplayMetrics().density);}
    private int color(int id){return ContextCompat.getColor(host,id);}
    private void toast(String message){if(alive())Toast.makeText(host,message,Toast.LENGTH_LONG).show();}
    private <T> Subscription run(Callable<T> call,Api.Callback<T> cb){
        if(!alive())return ()->{};
        Subscription job=Api.run(call,new Api.Callback<T>(){
            public void success(T value){if(alive())cb.success(value);}
            public void error(String e){if(alive())cb.error(e);}
        });jobs.add(job);return job;
    }
    private void show(AlertDialog d){if(!alive())return;dialogs.add(d);d.setOnDismissListener(x->dialogs.remove(d));d.show();}
    private TextView text(String value,int size){
        TextView t=new TextView(host);t.setText(value);t.setTextSize(size);t.setTextColor(color(R.color.text_primary));return t;
    }
    private LinearLayout column(){LinearLayout box=new LinearLayout(host);box.setOrientation(LinearLayout.VERTICAL);return box;}
    private GradientDrawable rounded(int fill,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(14));
        if(stroke!=0)d.setStroke(dp(2),stroke);return d;
    }
    private ImageView photo(String asset){
        ImageView image=new ImageView(host);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(color(R.color.surface_variant),0));image.setClipToOutline(true);
        Glide.with(host).load(Dto.asset(asset)).centerCrop().placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo).into(image);return image;
    }
    public void load(Runnable complete){
        if(!alive())return;
        if(listJob!=null)listJob.remove();
        listJob=run(()->(JSONArray)RpcClient.call("list_profile_featured_albums",Api.obj("p_owner_id",owner)),new Api.Callback<JSONArray>(){
            public void success(JSONArray albums){
                container.removeAllViews();section.setVisibility(self()||albums.length()>0?View.VISIBLE:View.GONE);
                LinearLayout row=null;
                for(int n=0;n<albums.length();n++){
                    JSONObject album=albums.optJSONObject(n);if(album==null)continue;
                    if(n%3==0){row=new LinearLayout(host);container.addView(row,new LinearLayout.LayoutParams(-1,-2));}
                    LinearLayout tile=column();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(3),dp(4),dp(3),dp(8));row.addView(tile,lp);
                    JSONArray items=album.optJSONArray("items");int count=items==null?0:items.length();
                    JSONObject first=count==0?null:items.optJSONObject(0);
                    ImageView cover=photo(first==null?null:first.optString("asset_id"));
                    cover.setContentDescription(album.optString("title")+", "+count+" ảnh");tile.addView(cover,new LinearLayout.LayoutParams(-1,dp(105)));
                    TextView title=text(album.optString("title"),13);title.setMaxLines(2);title.setTypeface(null,Typeface.BOLD);title.setPadding(0,dp(6),0,0);tile.addView(title);
                    if(count>1){TextView extra=text("+"+(count-1),12);extra.setTextColor(color(R.color.brand_blue));tile.addView(extra);}
                    tile.setOnClickListener(v->open(album));
                    if(self())tile.setOnLongClickListener(v->{edit(album);return true;});
                }
                if(row!=null){int missing=(3-albums.length()%3)%3;for(int n=0;n<missing;n++)row.addView(new View(host),new LinearLayout.LayoutParams(0,1,1));}
                if(albums.length()==0&&self())container.addView(text("Tạo album để lưu những ảnh bạn yêu thích.",13));
                if(albums.length()>0&&self())container.addView(text("Nhấn giữ album để đổi tên hoặc chỉnh sửa ảnh.",12));
                if(complete!=null)complete.run();
            }
            public void error(String e){
                container.removeAllViews();section.setVisibility(self()?View.VISIBLE:View.GONE);
                if(self()){TextView retry=text("Chưa tải được ảnh nổi bật. Nhấn để thử lại.",13);retry.setPadding(0,dp(8),0,dp(8));retry.setOnClickListener(v->load(null));container.addView(retry);toast(e);}
                if(complete!=null)complete.run();
            }
        });
    }
    public void create(){if(alive()&&self())edit(null);}
    private void open(JSONObject album){
        if(!alive())return;
        host.startActivity(com.example.meetomxh.FeaturedAlbumViewerActivity.intent(host,owner,album.optString("id")));
    }
    private void removeAlbum(JSONObject album,Runnable removed){
        if(!alive()||!self())return;
        AlertDialog confirm=new MaterialAlertDialogBuilder(host).setTitle("Gỡ album ảnh nổi bật?")
                .setMessage("Ảnh trong bài viết gốc vẫn được giữ.").setNegativeButton("Hủy",null).setPositiveButton("Gỡ",null).create();
        show(confirm);
        confirm.getButton(-1).setOnClickListener(v->{
            confirm.getButton(-1).setEnabled(false);confirm.getButton(-2).setEnabled(false);confirm.setCancelable(false);
            run(()->RpcClient.call("delete_profile_album",Api.obj("p_album_id",album.optString("id"))),new Api.Callback<Object>(){
                public void success(Object r){confirm.dismiss();removed.run();load(null);}
                public void error(String e){confirm.getButton(-1).setEnabled(true);confirm.getButton(-2).setEnabled(true);confirm.setCancelable(true);toast(e);}
            });
        });
    }
    private void edit(JSONObject album){if(!self()||!alive())return;new Editor(album).show();}
    private final class Editor {
        final JSONObject album;
        final String id;
        final LinkedHashMap<String,JSONObject> selected=new LinkedHashMap<>();
        final List<JSONObject> options=new ArrayList<>();
        final Set<String> seen=new HashSet<>();
        final Map<String,String> fingerprints=new HashMap<>();
        final TextInputEditText title=new TextInputEditText(host);
        final TextView summary=text("",13);
        final MaterialButton more=new MaterialButton(host,null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
        final RecyclerView grid=new RecyclerView(host);
        final Picker adapter=new Picker();
        final AlertDialog dialog;
        String afterTime,afterId;
        boolean fetching,saving,finished;
        Subscription fetch;
        Editor(JSONObject value){
            album=value;id=value==null?UUID.randomUUID().toString():value.optString("id");
            JSONArray items=value==null?null:value.optJSONArray("items");
            if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null){String key=item.optString("asset_id");selected.put(key,item);if(seen.add(key))options.add(item);}}
            LinearLayout box=column();box.setPadding(dp(20),0,dp(20),dp(8));
            TextInputLayout input=new TextInputLayout(host);input.setHint("Tên album");input.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            title.setSingleLine(true);title.setSaveEnabled(false);title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
            title.setText(value==null?"":value.optString("title"));input.addView(title,new LinearLayout.LayoutParams(-1,-2));box.addView(input);
            summary.setPadding(0,dp(12),0,dp(8));box.addView(summary);
            grid.setLayoutManager(new GridLayoutManager(host,3));grid.setAdapter(adapter);box.addView(grid,new LinearLayout.LayoutParams(-1,dp(290)));
            more.setAllCaps(false);more.setText("Tải thêm ảnh");box.addView(more,new LinearLayout.LayoutParams(-1,-2));more.setOnClickListener(v->fetch());
            ScrollView scroll=new ScrollView(host);scroll.setFillViewport(true);scroll.addView(box);
            MaterialAlertDialogBuilder builder=new MaterialAlertDialogBuilder(host).setTitle(value==null?"Thêm album ảnh nổi bật":"Chỉnh sửa album")
                    .setView(scroll).setNegativeButton("Hủy",null).setPositiveButton("Lưu",null);
            if(value!=null)builder.setNeutralButton("Gỡ album",null);
            dialog=builder.create();
        }
        void show(){
            FeaturedAlbumsUi.this.show(dialog);
            dialog.setOnDismissListener(d->{finished=true;if(fetch!=null)fetch.remove();dialogs.remove(dialog);});
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->save());
            if(album!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{if(!saving)removeAlbum(album,dialog::dismiss);});
            update();fetch();
        }
        void update(){summary.setText("Đã chọn "+selected.size()+"/50 ảnh · Ảnh đầu tiên làm ảnh bìa");}
        JSONObject fingerprintPage(JSONArray page,List<JSONObject> previous,Map<String,String> known) throws Exception {
            LinkedHashMap<String,JSONObject> all=new LinkedHashMap<>();
            for(JSONObject item:previous)all.put(item.optString("asset_id"),item);
            for(int i=0;i<page.length();i++){JSONObject item=page.optJSONObject(i);if(item!=null)all.put(item.optString("asset_id"),item);}
            Map<Long,List<JSONObject>> sizes=new HashMap<>();
            for(JSONObject item:all.values()){
                long size=item.optLong("size_bytes");if(size<=0||size>50L*1024*1024)continue;
                List<JSONObject> group=sizes.get(size);if(group==null){group=new ArrayList<>();sizes.put(size,group);}group.add(item);
            }
            // Identical files must have identical lengths; download only colliding size groups.
            // Do not trust byte length, dimensions, filenames or thumbnails as proof of equality.
            for(Map.Entry<Long,List<JSONObject>> entry:sizes.entrySet()){
                if(entry.getValue().size()<2)continue;
                for(JSONObject item:entry.getValue()){
                    Api.checkCancelled();String asset=item.optString("asset_id");if(known.containsKey(asset))continue;
                    try{known.put(asset,ExactPhotoFingerprint.read(asset,entry.getKey()));}
                    catch(Exception unavailable){Api.checkCancelled();/* Keep unreadable/unverified candidates separate. */}
                }
            }
            return Api.obj("page",page,"fingerprints",new JSONObject(known));
        }
        void deduplicateOptions(){
            Map<String,String> representatives=new HashMap<>();
            // Prefer the asset already selected, so adding a page does not replace its source post.
            for(JSONObject item:options){String asset=item.optString("asset_id"),hash=fingerprints.get(asset);
                if(hash==null)continue;String prior=representatives.get(hash);
                if(prior==null||(!selected.containsKey(prior)&&selected.containsKey(asset)))representatives.put(hash,asset);
            }
            Iterator<JSONObject> iterator=options.iterator();
            while(iterator.hasNext()){
                String asset=iterator.next().optString("asset_id"),hash=fingerprints.get(asset);
                if(hash!=null&&!asset.equals(representatives.get(hash))){iterator.remove();selected.remove(asset);}
            }
            update();
        }
        void fetch(){
            if(fetching||finished||saving)return;fetching=true;more.setEnabled(false);more.setText("Đang tải và lọc ảnh trùng…");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            List<JSONObject> previous=new ArrayList<>(options);Map<String,String> known=new HashMap<>(fingerprints);
            String time=afterTime,cursorId=afterId;
            fetch=run(()->fingerprintPage((JSONArray)RpcClient.call("list_profile_photo_candidates",Api.obj("p_after_time",time,"p_after_id",cursorId,"p_limit",50)),previous,known),new Api.Callback<JSONObject>(){
                public void success(JSONObject result){
                    if(finished)return;fetching=false;
                    JSONArray page=result.optJSONArray("page");JSONObject hashes=result.optJSONObject("fingerprints");
                    if(hashes!=null){Iterator<String> keys=hashes.keys();while(keys.hasNext()){String key=keys.next();fingerprints.put(key,hashes.optString(key));}}
                    for(int n=0;n<page.length();n++){JSONObject x=page.optJSONObject(n);if(x!=null&&seen.add(x.optString("asset_id")))options.add(x);}
                    if(page.length()>0){JSONObject last=page.optJSONObject(page.length()-1);afterTime=last.optString("created_at");afterId=last.optString("asset_id");}
                    deduplicateOptions();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving);
                    adapter.notifyDataSetChanged();more.setVisibility(page.length()==50?View.VISIBLE:View.GONE);more.setEnabled(true);more.setText("Tải thêm ảnh");
                    if(options.isEmpty())summary.setText("Chưa có ảnh. Hãy đăng bài viết cá nhân có ảnh trước.");
                }
                public void error(String e){if(finished)return;fetching=false;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!saving);more.setEnabled(true);more.setText("Thử tải ảnh lại");toast(e);}
            });
        }
        void save(){
            if(saving||fetching||finished||!alive()||!self())return;String name=title.getText()==null?"":title.getText().toString().trim();
            if(name.isEmpty()){title.setError("Nhập tên album");title.requestFocus();return;}
            if(selected.isEmpty()){toast("Chọn ít nhất một ảnh cho album.");return;}
            JSONArray items=new JSONArray();for(JSONObject item:selected.values())items.put(Api.obj("post_id",item.optString("post_id"),"asset_id",item.optString("asset_id")));
            saving=true;title.setEnabled(false);more.setEnabled(false);dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            run(()->RpcClient.object("save_profile_featured_album",Api.obj("p_album_id",id,"p_title",name,"p_items",items,"p_expected_version",album==null?0:album.optLong("version"))),new Api.Callback<JSONObject>(){
                public void success(JSONObject result){dialog.dismiss();toast("Đã lưu album ảnh nổi bật.");load(null);}
                public void error(String e){if(finished)return;saving=false;title.setEnabled(true);more.setEnabled(true);dialog.setCancelable(true);dialog.getButton(-1).setEnabled(true);dialog.getButton(-2).setEnabled(true);toast(e);}
            });
        }
        final class Picker extends RecyclerView.Adapter<PhotoHolder>{
            public PhotoHolder onCreateViewHolder(ViewGroup p,int type){
                FrameLayout frame=new FrameLayout(host);frame.setPadding(dp(3),dp(3),dp(3),dp(3));frame.setLayoutParams(new RecyclerView.LayoutParams(-1,dp(104)));
                ImageView image=photo(null);frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
                TextView check=text("",15);check.setGravity(Gravity.CENTER);check.setTextColor(Color.WHITE);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(27),dp(27),Gravity.TOP|Gravity.END);lp.setMargins(dp(7),dp(7),dp(7),dp(7));frame.addView(check,lp);return new PhotoHolder(frame,image,check);
            }
            public void onBindViewHolder(PhotoHolder h,int pos){
                JSONObject item=options.get(pos);String key=item.optString("asset_id");boolean chosen=selected.containsKey(key);
                Glide.with(host).load(Dto.asset(key)).centerCrop().placeholder(R.drawable.ic_photo).into(h.image);
                h.check.setText(chosen?"✓":"○");h.check.setBackground(rounded(chosen?color(R.color.brand_blue):0x99000000,0));
                h.itemView.setContentDescription("Ảnh "+(pos+1)+(chosen?", đã chọn":""));h.itemView.setSelected(chosen);
                h.itemView.setOnClickListener(v->{int index=h.getBindingAdapterPosition();if(saving||!alive()||index==RecyclerView.NO_POSITION)return;if(selected.containsKey(key))selected.remove(key);else{if(selected.size()>=50){toast("Mỗi album tối đa 50 ảnh.");return;}selected.put(key,item);}notifyItemChanged(index);update();});
            }
            public int getItemCount(){return options.size();}
        }
    }
    private static final class PhotoHolder extends RecyclerView.ViewHolder{
        final ImageView image;final TextView check;PhotoHolder(View view,ImageView image,TextView check){super(view);this.image=image;this.check=check;}
    }
    public void close(){closed=true;for(Subscription job:jobs)job.remove();jobs.clear();for(AlertDialog d:new ArrayList<>(dialogs))d.dismiss();dialogs.clear();}
}
