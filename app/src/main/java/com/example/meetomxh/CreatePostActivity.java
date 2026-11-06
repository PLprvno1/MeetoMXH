package com.example.meetomxh;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ActivityCreatePostBinding;
import com.example.meetomxh.repositories.FeedRepository;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.ErrorMapper;
import com.example.meetomxh.supabase.ProfileClient;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ui.MentionComposer;
import com.example.meetomxh.ui.PostTagPicker;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class CreatePostActivity extends AppCompatActivity {
    private ActivityCreatePostBinding binding;
    private final List<Uri> selected = new ArrayList<>();
    private final List<String> assets = new ArrayList<>();
    private String audience = "followers", editingId;
    private long version;
    private boolean busy;
    private String previewKey;
    private SupabaseStorage.Transfer transfer;
    private MentionComposer mentionComposer;
    private JSONArray taggedUsers=new JSONArray(),taggedPeople=new JSONArray();
    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != RESULT_OK || result.getData() == null || busy) return;
        Intent data = result.getData();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount() && selected.size() + assets.size() < 10; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                keepReadPermission(uri);
                selected.add(uri);
            }
        } else if (data.getData() != null && selected.size() + assets.size() < 10) {
            Uri uri = data.getData();
            keepReadPermission(uri);
            selected.add(uri);
        }
        preview();
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mentionComposer=new MentionComposer(binding.etPostContent);
        if (SessionManager.getInstance().getCurrentUser() == null) { finish(); return; }
        if (state != null) {
            ArrayList<Uri> restoredUris = state.getParcelableArrayList("selected_media");
            if (restoredUris != null) selected.addAll(restoredUris);
            ArrayList<String> restoredAssets = state.getStringArrayList("uploaded_assets");
            if (restoredAssets != null) assets.addAll(restoredAssets);
            audience = state.getString("audience", "public");
            mentionComposer.restore(state.getString("mentions_json")==null?null:parseArray(state.getString("mentions_json")));
            taggedUsers=parseArray(state.getString("tagged_users_json"));taggedPeople=parseArray(state.getString("tagged_people_json"));renderTags();
        }
        editingId = getIntent().getStringExtra("post_id");
        if (editingId != null) setComposerReady(false);
        binding.btnTagPeople.setOnClickListener(v->PostTagPicker.show(this,taggedPeople,(ids,people)->{taggedUsers=ids;taggedPeople=people;renderTags();}));
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnPickImage.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg", "image/webp", "video/mp4"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            picker.launch(pick);
        });
        binding.btnRemoveImage.setOnClickListener(v -> {
            if (transfer != null) transfer.cancel();
            transfer = null;
            selected.clear();
            assets.clear();
            preview();
            if (busy) { busy = false; binding.btnPostSubmit.setEnabled(true); binding.btnPostSubmit.setText("Đăng"); }
        });
        binding.btnAudience.setOnClickListener(v->com.example.meetomxh.supabase.AudienceChoices.choose(this,(value,label)->{audience=value;binding.btnAudience.setText(label);}));
        binding.btnPostSubmit.setOnClickListener(v -> submit());
        ProfileClient.get(SessionManager.getInstance().uid(), new Api.Callback<com.example.meetomxh.models.User>() {
            @Override public void success(com.example.meetomxh.models.User user) {
                if (!alive()) return;
                binding.tvAuthorName.setText(user.getFullName());if(user.accountPrivate&&"public".equals(audience)){audience="followers";binding.btnAudience.setText("Người theo dõi");}
                Glide.with(CreatePostActivity.this).load(user.getAvatarUrl()).into(binding.ivAuthorAvatar);
            }
            @Override public void error(String error) { toast(error); }
        });
        if (editingId != null) loadPostForEdit();
        else {
            String label = "followers".equals(audience) ? "Người theo dõi" : "private".equals(audience) ? "Chỉ mình tôi" : "Công khai";
            binding.btnAudience.setText(label);
            preview();
        }
    }

    private void keepReadPermission(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
    }

    private void loadPostForEdit() {
        Api.run(() -> RpcClient.object("get_post", Api.obj("p_post_id", editingId)), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject post) {
                if (!alive()) return;
                binding.etPostContent.setText(post.optString("body"));
                mentionComposer.restore(post.optJSONArray("mentions"));
                JSONArray existingTags=post.optJSONArray("tags");if(existingTags!=null){taggedPeople=existingTags;taggedUsers=new JSONArray();for(int i=0;i<existingTags.length();i++){JSONObject tag=existingTags.optJSONObject(i);if(tag!=null&&!tag.isNull("user_id"))taggedUsers.put(tag.optString("user_id"));}renderTags();}
                audience = post.optString("audience", "public");
                version = post.optLong("version");
                binding.btnAudience.setText("private".equals(audience) ? "Chỉ mình tôi" : "followers".equals(audience) ? "Người theo dõi" : "Công khai");
                binding.btnPostSubmit.setText("Lưu thay đổi");
                JSONArray media = post.optJSONArray("media");
                if (media != null) for (int i = 0; i < media.length(); i++) assets.add(Dto.asset(media.optJSONObject(i).optString("asset_id")));
                setComposerReady(true);
                preview();
            }
            @Override public void error(String error) { toast(error); finish(); }
        });
    }

    private void setComposerReady(boolean ready) {
        binding.etPostContent.setEnabled(ready);
        binding.btnAudience.setEnabled(ready);
        binding.btnPickImage.setEnabled(ready);
        binding.btnRemoveImage.setEnabled(ready);
        binding.btnPostSubmit.setEnabled(ready);
    }

    private void preview() {
        boolean hasMedia = !selected.isEmpty() || !assets.isEmpty();
        binding.layoutImagePreview.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        if (!selected.isEmpty()) {
            Uri uri = selected.get(0);
            String key = uri.toString();
            String mime = getContentResolver().getType(uri);
            if (mime != null && mime.startsWith("video/")) {
                binding.ivSelectedImagePreview.setImageDrawable(null);
                if (!key.equals(previewKey)) {
                    previewKey = key;
                    Api.run(() -> SupabaseStorage.videoThumbnail(getContentResolver(), uri), new Api.Callback<Bitmap>() {
                        public void success(Bitmap frame) { if (alive() && key.equals(previewKey)) binding.ivSelectedImagePreview.setImageBitmap(frame); }
                        public void error(String ignored) { if (alive() && key.equals(previewKey)) binding.ivSelectedImagePreview.setImageResource(R.drawable.ic_media_error); }
                    });
                }
            } else {
                previewKey = key;
                Glide.with(this).load(uri).placeholder(R.drawable.ic_media_error).error(R.drawable.ic_media_error).into(binding.ivSelectedImagePreview);
            }
        } else if (!assets.isEmpty()) {
            String ref = assets.get(0);
            if (!ref.equals(previewKey)) {
                previewKey = ref;
                Api.run(() -> {
                    JSONObject info = SupabaseStorage.info(ref);
                    return info.optString("mime_type").startsWith("video/") ? SupabaseStorage.videoThumbnail(ref) : null;
                }, new Api.Callback<Bitmap>() {
                    public void success(Bitmap frame) {
                        if (!alive() || !ref.equals(previewKey)) return;
                        if (frame != null) binding.ivSelectedImagePreview.setImageBitmap(frame);
                        else Glide.with(CreatePostActivity.this).load(ref).placeholder(R.drawable.ic_media_error).error(R.drawable.ic_media_error).into(binding.ivSelectedImagePreview);
                    }
                    public void error(String ignored) {
                        if (alive() && ref.equals(previewKey)) Glide.with(CreatePostActivity.this).load(ref).placeholder(R.drawable.ic_media_error).error(R.drawable.ic_media_error).into(binding.ivSelectedImagePreview);
                    }
                });
            }
        } else previewKey = null;
        binding.btnPickImage.setContentDescription("Ảnh/video (" + (selected.size() + assets.size()) + "/10)");
    }

    private void submit() {
        if (busy) return;
        String body = binding.etPostContent.getText().toString();
        if (body.trim().isEmpty() && selected.isEmpty() && assets.isEmpty()) {
            toast("Nhập nội dung hoặc chọn ảnh/video.");
            return;
        }
        busy = true;
        binding.btnPostSubmit.setEnabled(false);
        nextUpload(body);
    }

    private void nextUpload(String body) {
        if (selected.isEmpty()) { save(body); return; }
        Uri uri = selected.get(0);
        binding.btnPostSubmit.setText("Đang chuẩn bị tệp…");
        transfer = SupabaseStorage.upload(getContentResolver(), uri, "posts", SessionManager.getInstance().uid(), new SupabaseStorage.UploadListener() {
            @Override public void onProgress(int percent) { if (alive()) binding.btnPostSubmit.setText("Đang tải lên " + percent + "%"); }
            @Override public void onSuccess(String reference) {
                if (!alive()) return;
                assets.add(reference);
                selected.remove(0);
                preview();
                nextUpload(body);
            }
            @Override public void onFailure(String error) {
                if (!alive()) return;
                busy = false;
                transfer = null;
                binding.btnPostSubmit.setEnabled(true);
                binding.btnPostSubmit.setText("Thử tải lại / đăng");
                toast(error + " Bấm lại để thử tiếp.");
            }
        });
    }

    private void save(String body) {
        JSONArray ids = new JSONArray();
        for (String ref : assets) ids.put(Dto.assetId(ref));
        JSONObject input = Api.obj("p_body", body, "p_audience", audience, "p_assets", ids,
                "p_mentions",mentionComposer.getMentions(),"p_tagged_users",taggedUsers);
        String fingerprint = input.toString();
        binding.btnPostSubmit.setText(editingId == null ? "Đang đăng…" : "Đang lưu…");
        Api.run(() -> {
            if (editingId != null) {
                input.put("p_post_id", editingId).put("p_expected_version", version);
                return RpcClient.call("edit_post_with_mentions", input);
            }

            input.put("p_client_request_id", RequestIds.get("post", fingerprint));
            Object result = RpcClient.call("create_post_with_mentions", input);
            RequestIds.complete("post", fingerprint);
            return result;
        }, new Api.Callback<Object>() {
            @Override public void success(Object result) {
                if (!alive()) return;
                FeedRepository.getInstance().refresh();
                setResult(RESULT_OK);
                finish();
            }
            @Override public void error(String error) {
                if (!alive()) return;
                busy = false;
                binding.btnPostSubmit.setEnabled(true);
                binding.btnPostSubmit.setText("Thử lại");
                toast(error);
            }
        });
    }

    private boolean alive() { return !isFinishing() && !isDestroyed(); }
    private void renderTags(){if(binding==null)return;binding.chipsTaggedPeople.removeAllViews();for(int i=0;i<taggedPeople.length();i++){JSONObject person=taggedPeople.optJSONObject(i);if(person==null)continue;String id=person.optString("user_id",null);if(id==null||id.equals("null"))continue;Chip chip=new Chip(this);chip.setText(person.optString("display_name","Người theo dõi"));chip.setCloseIconVisible(true);chip.setOnCloseIconClickListener(v->{JSONArray ids=new JSONArray(),people=new JSONArray();for(int j=0;j<taggedPeople.length();j++){JSONObject p=taggedPeople.optJSONObject(j);if(p!=null&&!id.equals(p.optString("user_id"))){ids.put(p.optString("user_id"));people.put(p);}}taggedUsers=ids;taggedPeople=people;renderTags();});binding.chipsTaggedPeople.addView(chip);}binding.chipsTaggedPeople.setVisibility(taggedPeople.length()>0?View.VISIBLE:View.GONE);binding.btnTagPeople.setText(taggedPeople.length()==0?"Gắn thẻ người dùng":"Gắn thẻ người dùng · "+taggedPeople.length());}
    private static JSONArray parseArray(String json){try{return json==null?new JSONArray():new JSONArray(json);}catch(Exception ignored){return new JSONArray();}}
    private void toast(String text) { if (alive()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList("selected_media", new ArrayList<>(selected));
        out.putStringArrayList("uploaded_assets", new ArrayList<>(assets));
        out.putString("audience", audience);
        out.putString("mentions_json",mentionComposer==null?"[]":mentionComposer.getMentions().toString());
        out.putString("tagged_users_json",taggedUsers.toString());out.putString("tagged_people_json",taggedPeople.toString());
    }

    @Override protected void onRestoreInstanceState(Bundle saved){
        super.onRestoreInstanceState(saved);
        if(mentionComposer!=null)mentionComposer.restore(parseArray(saved.getString("mentions_json")));
    }

    @Override protected void onDestroy() {
        if (transfer != null) transfer.cancel();
        if(mentionComposer!=null)mentionComposer.dispose();
        super.onDestroy();
    }
}
