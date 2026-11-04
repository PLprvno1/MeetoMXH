package com.example.meetomxh;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.ErrorMapper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Virtual photo albums backed by authorized references to existing post media. */
public final class ProfileAlbumsActivity extends AppCompatActivity {
    private LinearLayout root, header;
    private RecyclerView grid;
    private TextView status;
    private String albumId, ownerId;
    private JSONObject album;
    private final List<JSONObject> items = new ArrayList<>();
    private final List<JSONObject> albums = new ArrayList<>();

    private static Object albumRpc(String name, JSONObject args) throws Exception {
        try {
            Object result = RpcClient.call(name, args);
            if (result instanceof JSONObject) {
                JSONObject object = (JSONObject)result;
                java.util.List<String> keys = new java.util.ArrayList<>();
                java.util.Iterator<String> it = object.keys(); while (it.hasNext()) keys.add(it.next());
                java.util.Collections.sort(keys);
                android.util.Log.i("ProfileAlbums", name + " success object_keys=" + keys);
            } else if (result instanceof JSONArray) {
                android.util.Log.i("ProfileAlbums", name + " success array_count=" + ((JSONArray)result).length());
            } else {
                android.util.Log.i("ProfileAlbums", name + " success response_type=" + (result == null ? "null" : result.getClass().getSimpleName()));
            }
            return result;
        }
        catch (Exception e) {
            if (e instanceof ErrorMapper.HttpError) {
                ErrorMapper.HttpError h = (ErrorMapper.HttpError)e;
                android.util.Log.w("ProfileAlbums", name + " HTTP " + h.status + " code=" + safeRpcCode(h.code));
            } else {
                android.util.Log.w("ProfileAlbums", name + " failed: " + e.getClass().getSimpleName());
            }
            throw e;
        }
    }
    private static String safeRpcCode(String code) {
        return code != null && code.matches("[A-Z0-9_]{1,32}") ? code : "unknown";
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        albumId = getIntent().getStringExtra("album_id");
        ownerId = getIntent().getStringExtra("owner_id");
        if (ownerId == null) ownerId = com.example.meetomxh.supabase.SessionManager.getInstance().uid();
        buildUi();
        if (albumId == null) loadAlbums(); else loadAlbum();
    }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(com.example.meetomxh.R.color.app_bg));
        setContentView(root);
        header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(16),dp(10),dp(16),dp(10));
        root.addView(header, new LinearLayout.LayoutParams(-1,-2));
        Button back = new Button(this); back.setText("‹"); back.setContentDescription("Back");
        header.addView(back); back.setOnClickListener(v -> finish());
        TextView title = new TextView(this); title.setText("Photo albums"); title.setTextSize(20); title.setTextColor(getColor(R.color.text_primary)); title.setTypeface(null,1);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1); tp.leftMargin=dp(12); header.addView(title,tp);
        Button action = new Button(this); action.setText("＋"); action.setContentDescription("Create album"); header.addView(action); action.setVisibility(ownerId.equals(com.example.meetomxh.supabase.SessionManager.getInstance().uid()) ? View.VISIBLE : View.GONE);
        action.setOnClickListener(v -> { if (albumId == null) showAlbumForm(null); else showAlbumActions(); });
        grid = new RecyclerView(this); grid.setLayoutManager(new GridLayoutManager(this,2));
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        status = new TextView(this); status.setTextColor(getColor(R.color.text_secondary)); status.setGravity(Gravity.CENTER); status.setPadding(dp(16),dp(12),dp(16),dp(12));
        status.setText("Albums use photos already attached to your posts. Access follows the source post."); root.addView(status);
    }
    private void loadAlbums() {
        grid.setAdapter(null); albums.clear(); status.setVisibility(View.VISIBLE); status.setText("Loading albums…");
        Api.run(() -> (JSONArray)albumRpc("list_profile_albums",Api.obj("p_owner_id",ownerId,"p_after_time",null,"p_after_id",null,"p_limit",50)), new Api.Callback<JSONArray>() {
            public void success(JSONArray rows) {
                albums.clear(); for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i); if(x!=null)albums.add(x);}
                renderAlbums();
                status.setText(albums.isEmpty()?"No albums yet. Tap + to create one.":"Albums use existing post photos; source post access still applies.");
                status.setVisibility(albums.isEmpty()?View.VISIBLE:View.GONE);
            }
            public void error(String e) {
                status.setText("Could not load albums. Tap here to retry.\n"+e); status.setVisibility(View.VISIBLE);
                status.setOnClickListener(v->loadAlbums()); toast(e);
            }
        });
    }    private void renderAlbums() {
        grid.setLayoutManager(new GridLayoutManager(this,2));
        grid.setAdapter(new RecyclerView.Adapter<CardHolder>() {
            public CardHolder onCreateViewHolder(android.view.ViewGroup p,int t) {
                LinearLayout card=new LinearLayout(ProfileAlbumsActivity.this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(6),dp(6),dp(6),dp(6));
                ImageView image=new ImageView(ProfileAlbumsActivity.this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); card.addView(image,new LinearLayout.LayoutParams(-1,dp(130)));
                TextView label=new TextView(ProfileAlbumsActivity.this); label.setTextColor(getColor(R.color.text_primary)); label.setPadding(dp(6),dp(8),dp(6),dp(8)); card.addView(label);
                return new CardHolder(card,image,label);
            }
            public void onBindViewHolder(CardHolder h,int pos) { JSONObject a=albums.get(pos); h.label.setText(a.optString("title")+" · "+a.optInt("photo_count")+" photos\n"+a.optString("audience")); String ref=Dto.asset(Dto.nullable(a,"cover_asset_id")); if(ref!=null) Glide.with(ProfileAlbumsActivity.this).load(ref).centerCrop().into(h.image); else h.image.setImageResource(R.drawable.bg_cover_placeholder); h.itemView.setOnClickListener(v->{albumId=a.optString("id"); loadAlbum();}); }
            public int getItemCount(){return albums.size();}
        });
    }
    private void loadAlbum() {
        grid.setAdapter(null); items.clear(); status.setVisibility(View.VISIBLE); status.setText("Loading album photos…");
        Api.run(() -> (JSONObject)albumRpc("get_profile_album",Api.obj("p_album_id",albumId,"p_after_time",null,"p_after_id",null,"p_limit",50)), new Api.Callback<JSONObject>() {
            public void success(JSONObject value) {
                album=value.optJSONObject("album"); if(album==null){status.setText("Album unavailable.");status.setVisibility(View.VISIBLE);return;}
                JSONArray rows=value.optJSONArray("items"); if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x!=null)items.add(x);}
                renderItems(); status.setText(items.isEmpty()?"No photos yet. Tap + to add existing post photos.":"Album photos remain subject to source post privacy.");
                status.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);
            }
            public void error(String e) {
                status.setText("Could not load this album. Tap to retry.\n"+e); status.setVisibility(View.VISIBLE);
                status.setOnClickListener(v->loadAlbum()); toast(e);
            }
        });
    }    private void renderItems() {
        grid.setLayoutManager(new GridLayoutManager(this,3));
        grid.setAdapter(new RecyclerView.Adapter<PhotoHolder>() {
            public PhotoHolder onCreateViewHolder(android.view.ViewGroup p,int t) { ImageView im=new ImageView(ProfileAlbumsActivity.this); im.setScaleType(ImageView.ScaleType.CENTER_CROP); return new PhotoHolder(im); }
            public void onBindViewHolder(PhotoHolder h,int pos) { JSONObject x=items.get(pos); String ref=Dto.asset(Dto.nullable(x,"asset_id")); Glide.with(ProfileAlbumsActivity.this).load(ref).centerCrop().into(h.image); h.image.setContentDescription("Album photo"); h.image.setOnClickListener(v->{JSONArray refs=new JSONArray(); for(JSONObject it:items)refs.put("asset://"+it.optString("asset_id")); startActivity(ProfilePhotoViewerActivity.intent(ProfileAlbumsActivity.this,refs,pos));}); h.image.setOnLongClickListener(v->{if(!ownerId.equals(com.example.meetomxh.supabase.SessionManager.getInstance().uid()))return false;String[] actions={"Set as album cover","Remove from album"}; new AlertDialog.Builder(ProfileAlbumsActivity.this).setItems(actions,(d,which)->{String rpc=which==0?"set_profile_album_cover":"remove_profile_album_photo"; JSONObject args=which==0?Api.obj("p_album_id",albumId,"p_item_id",x.optString("id")):Api.obj("p_album_id",albumId,"p_item_id",x.optString("id")); Api.run(()->RpcClient.call(rpc,args),new Api.Callback<Object>(){public void success(Object r){loadAlbum();}public void error(String e){toast(e);}});}).show();return true;}); }
            public int getItemCount(){return items.size();}
        });
    }
    private void showAlbumActions() {
        String[] actions={"Add existing photos","Edit album","Delete album"};
        new AlertDialog.Builder(this).setTitle(album==null?"Album":album.optString("title")).setItems(actions,(d,i)->{if(i==0)addPhotos();else if(i==1)showAlbumForm(album);else new AlertDialog.Builder(this).setMessage("Delete this album? Original posts and media stay unchanged.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(a,b)->Api.run(()->albumRpc("delete_profile_album",Api.obj("p_album_id",albumId)),new Api.Callback<Object>(){public void success(Object r){albumId=null;loadAlbums();}public void error(String e){toast(e);}})).show();}).show();
    }
    private void showAlbumForm(JSONObject existing) {
        LinearLayout form=new LinearLayout(this); form.setPadding(dp(20),dp(8),dp(20),dp(8)); form.setOrientation(LinearLayout.VERTICAL);
        EditText name=new EditText(this); name.setHint("Album name"); name.setText(existing==null?"":existing.optString("title")); form.addView(name);
        EditText description=new EditText(this); description.setHint("Description"); description.setText(existing==null?"":existing.optString("description")); form.addView(description);
        Spinner audience=new Spinner(this); String[] options={"friends","public","private"}; audience.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options)); if(existing!=null){for(int i=0;i<options.length;i++)if(options[i].equals(existing.optString("audience")))audience.setSelection(i);} form.addView(audience);
        new AlertDialog.Builder(this).setTitle(existing==null?"Create album":"Edit album").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
            JSONObject args; String rpc;
            if(existing==null){rpc="create_profile_album";args=Api.obj("p_title",name.getText().toString(),"p_description",description.getText().toString(),"p_audience",audience.getSelectedItem().toString());}
            else{rpc="update_profile_album";args=Api.obj("p_album_id",albumId,"p_title",name.getText().toString(),"p_description",description.getText().toString(),"p_audience",audience.getSelectedItem().toString(),"p_expected_version",existing.optLong("version",1));}
            Api.run(()->(JSONObject)albumRpc(rpc,args),new Api.Callback<JSONObject>(){public void success(JSONObject r){if(albumId==null)loadAlbums();else loadAlbum();}public void error(String e){toast(e);}});
        }).show();
    }
    private void addPhotos() {
        Api.run(()->(JSONArray)albumRpc("list_profile_photo_candidates",Api.obj("p_after_time",null,"p_after_id",null,"p_limit",50)),new Api.Callback<JSONArray>(){
            public void success(JSONArray rows){if(rows==null||rows.length()==0){toast("No eligible photos found. Add a photo to a personal post first.");return;} List<JSONObject> choices=new ArrayList<>();List<String> labels=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject x=rows.optJSONObject(i);if(x!=null){choices.add(x);labels.add("Photo from "+x.optString("created_at","post"));}} String[] l=labels.toArray(new String[0]); boolean[] checked=new boolean[l.length]; new AlertDialog.Builder(ProfileAlbumsActivity.this).setTitle("Choose photos").setMultiChoiceItems(l,checked,(d,index,on)->checked[index]=on).setNegativeButton("Cancel",null).setPositiveButton("Add",(d,w)->addCandidates(choices,checked)).show();}
            public void error(String e){toast(e);}
        });
    }
    private void addCandidates(List<JSONObject> choices, boolean[] checked) {
        Api.run(() -> {
            java.util.LinkedHashMap<String,JSONArray> byPost=new java.util.LinkedHashMap<>();
            for(int i=0;i<checked.length;i++) if(checked[i]) { JSONObject item=choices.get(i); String post=item.optString("post_id"); byPost.computeIfAbsent(post,k->new JSONArray()).put(item.optString("asset_id")); }
            if(byPost.isEmpty()) return Boolean.FALSE;
            for(java.util.Map.Entry<String,JSONArray> entry:byPost.entrySet()) albumRpc("add_profile_album_photos",Api.obj("p_album_id",albumId,"p_source_post_id",entry.getKey(),"p_asset_ids",entry.getValue()));
            return Boolean.TRUE;
        },new Api.Callback<Boolean>(){public void success(Boolean added){if(added)loadAlbum();}public void error(String e){toast(e);}});
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private static class CardHolder extends RecyclerView.ViewHolder { ImageView image;TextView label;CardHolder(View v,ImageView i,TextView t){super(v);image=i;label=t;} }
    private static class PhotoHolder extends RecyclerView.ViewHolder { ImageView image;PhotoHolder(View v){super(v);image=(ImageView)v;} }
}

