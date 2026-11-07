package com.example.meetomxh;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
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
import com.example.meetomxh.music.MusicPublishingClient;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class CreatePostActivity extends AppCompatActivity {
    private ActivityCreatePostBinding binding;
    private final List<Uri> selected = new ArrayList<>();
    private final List<String> assets = new ArrayList<>();
    private final ArrayList<String> ownedReelDrafts = new ArrayList<>();
    private String audience = "followers", editingId;
    private long version;
    private boolean busy;
    private String previewKey;
    private SupabaseStorage.Transfer transfer;
    private MentionComposer mentionComposer;
    private JSONArray taggedUsers=new JSONArray(),taggedPeople=new JSONArray();
    private long reelDuration, reelBytes;
    private int eligibilityGeneration;
    private boolean reelEligible, checkingVideo, unifiedLocked, reelNeedsEncoding;
    private String videoChecked, editorOutput, frozenUnified, editorMusicMetadata;
    private boolean musicCapabilityValidated;
    private long musicSessionEpoch;
    private final Runnable musicIdentityChanged=()->{if(musicSessionEpoch==SessionManager.getInstance().generation())return;if(editorMusicMetadata!=null||frozenUnified!=null&&frozenUnified.contains("p_music_metadata")){editorMusicMetadata=null;musicCapabilityValidated=false;if(transfer!=null)transfer.cancel();finish();}};
    private String publishDraftId=UUID.randomUUID().toString();
    private final ActivityResultLauncher<Intent> reelEditor = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null && !busy && editorOutput != null
                && editorOutput.equals(result.getData().getStringExtra(CreateReelActivity.EXTRA_OUTPUT_PATH))) {
            File file = new File(editorOutput);
            if (file.isFile()) {
                selected.clear();selected.add(Uri.fromFile(file));assets.clear();videoChecked = null;
                editorMusicMetadata=result.getData().getStringExtra(CreateReelActivity.EXTRA_MUSIC_METADATA);
                musicCapabilityValidated=false;
                preview();toast("Đã dùng đoạn đã chọn cho cả bài viết và Reel.");return;
            }
        }
        if (editorOutput != null) new File(editorOutput).delete();
        editorOutput = null;
        editorMusicMetadata=null;musicCapabilityValidated=false;
        // Cancellation retains the original video.
        // The original selected URI is retained on cancellation.
    });
    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != RESULT_OK || result.getData() == null || busy || unifiedLocked) return;
        editorMusicMetadata=null;musicCapabilityValidated=false;
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
        musicSessionEpoch=SessionManager.getInstance().generation();SessionManager.getInstance().identityListeners.add(musicIdentityChanged);
        binding = ActivityCreatePostBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mentionComposer=new MentionComposer(binding.etPostContent);
        if (SessionManager.getInstance().getCurrentUser() == null) { finish(); return; }
        if (state != null) {
            publishDraftId=state.getString("unified_draft_id",publishDraftId);
            ArrayList<Uri> restoredUris = state.getParcelableArrayList("selected_media");
            if (restoredUris != null) selected.addAll(restoredUris);
            ArrayList<String> restoredAssets = state.getStringArrayList("uploaded_assets");
            if (restoredAssets != null) assets.addAll(restoredAssets);
            audience = state.getString("audience", "public");
            mentionComposer.restore(state.getString("mentions_json")==null?null:parseArray(state.getString("mentions_json")));
            taggedUsers=parseArray(state.getString("tagged_users_json"));taggedPeople=parseArray(state.getString("tagged_people_json"));renderTags();
            editorOutput=state.getString("reel_editor_output");frozenUnified=state.getString("unified_snapshot");
            editorMusicMetadata=state.getString("reel_music_metadata");
            ArrayList<String> owned=state.getStringArrayList("owned_reel_drafts");if(owned!=null)ownedReelDrafts.addAll(owned);
            unifiedLocked=frozenUnified!=null;
            restoreUnifiedReceipt();
        }
        editingId = getIntent().getStringExtra("post_id");
        binding.btnEditReelVideo.setOnClickListener(v -> openReelEditor());
        binding.tvReelEligibility.setOnClickListener(v->{if(!busy&&!unifiedLocked&&!checkingVideo){videoChecked=null;updateReelOption();}});
        if (editingId != null) setComposerReady(false);
        binding.btnTagPeople.setOnClickListener(v->PostTagPicker.show(this,taggedPeople,(ids,people)->{taggedUsers=ids;taggedPeople=people;renderTags();}));
        binding.btnBack.setOnClickListener(v -> leaveComposer());
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){leaveComposer();}
        });
        binding.btnPickImage.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg", "image/webp", "video/mp4"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            picker.launch(pick);
        });
        binding.btnRemoveImage.setOnClickListener(v -> {
            if (busy || unifiedLocked) return;
            if (transfer != null) transfer.cancel();
            transfer = null;
            selected.clear();
            assets.clear();
            preview();
            if (busy) { busy = false; binding.btnPostSubmit.setEnabled(true); binding.btnPostSubmit.setText("Đăng"); }
        });
        binding.btnAudience.setOnClickListener(v->com.example.meetomxh.supabase.AudienceChoices.choose(this,(value,label)->{if(!busy&&!unifiedLocked){audience=value;binding.btnAudience.setText(label);}}));
        binding.btnPostSubmit.setOnClickListener(v -> submit());
        ProfileClient.get(SessionManager.getInstance().uid(), new Api.Callback<com.example.meetomxh.models.User>() {
            @Override public void success(com.example.meetomxh.models.User user) {
                if (!alive()) return;
                binding.tvAuthorName.setText(user.getFullName());if(!busy&&!unifiedLocked&&user.accountPrivate&&"public".equals(audience)){audience="followers";binding.btnAudience.setText("Người theo dõi");}
                Glide.with(CreatePostActivity.this).load(user.getAvatarUrl()).into(binding.ivAuthorAvatar);
            }
            @Override public void error(String error) { toast(error); }
        });
        if (editingId != null) loadPostForEdit();
        else {
            String label = "followers".equals(audience) ? "Người theo dõi" : "private".equals(audience) ? "Chỉ mình tôi" : "Công khai";
            binding.btnAudience.setText(label);
            preview();
            if (unifiedLocked) lockUnifiedComposer();
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
        updateReelOption();
        boolean hasMedia = !selected.isEmpty() || !assets.isEmpty();
        binding.layoutImagePreview.setVisibility(hasMedia ? View.VISIBLE : View.GONE);
        if (!selected.isEmpty()) {
            Uri uri = selected.get(0);
            String key = uri.toString();
            String mime = mediaType(uri);
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
        if (checkingVideo) { toast("Đang kiểm tra video, vui lòng chờ.");return; }
        boolean hasVideo = false;
        for(Uri uri:selected){String mime=mediaType(uri);if(mime!=null&&mime.startsWith("video/"))hasVideo=true;}
        if(hasVideo && (editingId!=null || selected.size()!=1 || !assets.isEmpty())) { toast("Bài video mới chỉ hỗ trợ một video, không kèm ảnh/album. Bản nháp được giữ nguyên.");return; }
        boolean shareReel = hasVideo && editingId==null;
        if (shareReel && !unifiedLocked) {
            if (!reelEligible || selected.size()!=1 || !assets.isEmpty()) { toast("Reels hiện hỗ trợ bài viết chỉ có một video.");return; }
            if (reelNeedsEncoding || reelDuration>60000 || reelBytes>com.example.meetomxh.ui.VideoPublishLimits.EXPORT_BYTES) { openReelEditor();return; }
            if (bodyTooLong() || !("public".equals(audience)||"followers".equals(audience)||"private".equals(audience))) {
                toast("Reels hỗ trợ caption tối đa 2.000 ký tự và quyền Công khai / Người theo dõi / Chỉ mình tôi.");return;
            }
            checkPolicyAndStart();return;
        }
        String body = binding.etPostContent.getText().toString();
        if (body.trim().isEmpty() && selected.isEmpty() && assets.isEmpty()) {
            toast("Nhập nội dung hoặc chọn ảnh/video.");
            return;
        }
        busy = true;
        binding.btnPostSubmit.setEnabled(false);
        if(unifiedLocked){
            if(editorMusicMetadata!=null&&!musicCapabilityValidated){
                Api.run(()->{MusicPublishingClient.requireEnabled();return true;},new Api.Callback<Boolean>(){
                    public void success(Boolean enabled){if(!alive())return;musicCapabilityValidated=true;busy=false;submit();}
                    public void error(String error){if(!alive())return;busy=false;binding.btnPostSubmit.setEnabled(true);toast(error+" Bản nháp đã được giữ.");}
                });return;
            }
            Api.run(()->RpcClient.object("get_reel_upload_policy",Api.obj()),new Api.Callback<JSONObject>(){
                public void success(JSONObject p){if(!alive())return;
                    long limit=com.example.meetomxh.ui.VideoPublishLimits.effective(p.optLong("max_bytes",0));
                    if(!p.optBoolean("automatic_video_distribution")||limit<=0){busy=false;binding.btnPostSubmit.setEnabled(true);toast("Cloud chưa sẵn sàng cho Task 47. Bản nháp được giữ.");return;}
                    if(!selected.isEmpty()&&reelBytes>limit){busy=false;binding.btnPostSubmit.setEnabled(true);toast("Video vượt giới hạn bucket hiện tại. Bản nháp được giữ.");return;}
                    nextUpload(body);
                }
                public void error(String e){if(!alive())return;busy=false;binding.btnPostSubmit.setEnabled(true);toast(e);}
            });return;
        }
        nextUpload(body);
    }

    private void nextUpload(String body) {
        if (selected.isEmpty()) { save(body); return; }
        Uri uri = selected.get(0);
        binding.btnPostSubmit.setText("Đang chuẩn bị tệp…");
        SupabaseStorage.UploadListener listener = new SupabaseStorage.UploadListener() {
            @Override public void onProgress(int percent) { if (alive()) binding.btnPostSubmit.setText("Đang tải lên " + percent + "%"); }
            @Override public void onSuccess(String reference) {
                if (!alive()) return;
                assets.add(reference);
                selected.remove(0);
                if(unifiedLocked&&!persistUnifiedReceipt())return;
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
        };
        transfer = "file".equals(uri.getScheme()) && "video/mp4".equals(mediaType(uri))
                ? SupabaseStorage.uploadVideo(getContentResolver(),new File(uri.getPath()),"posts",SessionManager.getInstance().uid(),listener)
                : SupabaseStorage.upload(getContentResolver(),uri,"posts",SessionManager.getInstance().uid(),listener);
    }

    private void save(String body) {
        if (unifiedLocked) { saveUnified();return; }
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
    private void leaveComposer(){if(busy){toast("Đang xử lý bài viết. Vui lòng chờ kết quả.");return;}finish();}
    private String mediaType(Uri uri) {
        return "file".equals(uri.getScheme()) && uri.getPath()!=null && uri.getPath().endsWith(".mp4")
                ? "video/mp4" : getContentResolver().getType(uri);
    }
    private boolean bodyTooLong(){return binding.etPostContent.getText().length()>2000;}
    private void updateReelOption() {
        if (unifiedLocked) return;
        boolean hasVideo=false;
        for(Uri uri:selected){String mime=mediaType(uri);if(mime!=null&&mime.startsWith("video/"))hasVideo=true;}
        boolean single=editingId==null&&assets.isEmpty()&&selected.size()==1&&hasVideo;

        binding.btnEditReelVideo.setVisibility(single?View.VISIBLE:View.GONE);
        binding.tvReelEligibility.setVisibility(hasVideo?View.VISIBLE:View.GONE);
        if(!single){eligibilityGeneration++;videoChecked=null;checkingVideo=false;reelEligible=false;
            binding.tvReelEligibility.setText("Bài video mới dùng đúng một video, không kèm ảnh/album. Album cũ được giữ nguyên.");return;}
        Uri uri=selected.get(0);String key=uri.toString();if(key.equals(videoChecked))return;
        videoChecked=key;checkingVideo=true;reelEligible=false;
        binding.btnEditReelVideo.setEnabled(false);binding.tvReelEligibility.setText("Đang kiểm tra thời lượng và dung lượng video…");
        final int token=++eligibilityGeneration;
        Api.run(()->{
            MediaMetadataRetriever meta=new MediaMetadataRetriever();long duration;
            try{meta.setDataSource(CreatePostActivity.this,uri);duration=Long.parseLong(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                if(!"yes".equals(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))throw new IllegalStateException("Video không có hình ảnh.");}
            finally{meta.release();}
            long bytes;
            try(android.os.ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(uri,"r")){
                bytes=descriptor==null?-1:descriptor.getStatSize();
            }
            if(duration<1000||bytes<=0)throw new IllegalStateException("Không xác định được thời lượng/dung lượng video để chia sẻ lên Reels. Chọn tệp cục bộ hoặc chạm để kiểm tra lại.");
            return new long[]{duration,bytes,com.example.meetomxh.ui.VideoPublishLimits.needsEncoding(CreatePostActivity.this,uri)?1:0};
        },new Api.Callback<long[]>(){
            public void success(long[] info){if(!alive()||token!=eligibilityGeneration)return;checkingVideo=false;reelEligible=true;reelDuration=info[0];reelBytes=info[1];reelNeedsEncoding=info[2]!=0;
                binding.btnEditReelVideo.setEnabled(true);
                binding.tvReelEligibility.setText(reelNeedsEncoding||reelDuration>60000||reelBytes>com.example.meetomxh.ui.VideoPublishLimits.EXPORT_BYTES?"Bấm Đăng để xử lý video trong editor: tối đa 60 giây, mục tiêu dưới 40 MB.":"Video tự động xuất hiện trên Feed và Reels, dùng chung caption, quyền riêng tư và tương tác.");}
            public void error(String e){if(!alive()||token!=eligibilityGeneration)return;checkingVideo=false;binding.tvReelEligibility.setText(e);}
        });
    }
    private void openReelEditor(){
        if(busy||unifiedLocked||checkingVideo||!reelEligible||selected.size()!=1)return;
        try{
            File folder=new File(getFilesDir(),"post-reel-drafts");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Không tạo được bản nháp video.");
            editorOutput=new File(folder,UUID.randomUUID()+".mp4").getAbsolutePath();
            ownedReelDrafts.add(editorOutput);
            reelEditor.launch(new Intent(this,CreateReelActivity.class).putExtra(CreateReelActivity.EXTRA_EDIT_ONLY,true)
                    .putExtra(ReelsCaptureSession.EXTRA_URI,selected.get(0).toString()).putExtra(CreateReelActivity.EXTRA_OUTPUT_PATH,editorOutput));
        }catch(Exception e){toast(e.getMessage());}
    }
    private void lockUnifiedComposer(){
        setComposerReady(false);binding.btnTagPeople.setEnabled(false);binding.chipsTaggedPeople.setEnabled(false);
        for(int i=0;i<binding.chipsTaggedPeople.getChildCount();i++)binding.chipsTaggedPeople.getChildAt(i).setEnabled(false);
        binding.btnEditReelVideo.setEnabled(false);binding.btnPostSubmit.setEnabled(!busy);
        if(unifiedLocked){
            binding.tvReelEligibility.setVisibility(View.VISIBLE);binding.tvReelEligibility.setText("Bản đăng Feed/Reels được giữ nguyên để thử lại. Video đã tải sẽ được tái sử dụng.");}
    }
    private void checkPolicyAndStart(){
        final String body=binding.etPostContent.getText().toString();
        JSONObject snapshotObject=Api.obj("p_caption",body,"p_audience",audience,"p_duration_ms",reelDuration,
                "p_mentions",mentionComposer.getMentions(),"p_tagged_users",taggedUsers);
        try{if(editorMusicMetadata!=null){JSONObject metadata=new JSONObject(editorMusicMetadata);if(!com.example.meetomxh.music.OpenverseMusicClient.validPublishedMetadata(metadata,reelDuration))throw new IllegalArgumentException("invalid");snapshotObject.put("p_music_metadata",metadata);}}
        catch(Exception error){toast("Metadata nguồn nhạc không hợp lệ. Hãy mở editor và xuất lại video.");return;}
        final String snapshot=snapshotObject.toString();
        if(editorMusicMetadata!=null&&!musicCapabilityValidated){
            busy=true;lockUnifiedComposer();binding.btnPostSubmit.setText("Đang kiểm tra máy chủ lưu nguồn nhạc…");
            Api.run(()->{MusicPublishingClient.requireEnabled();return true;},new Api.Callback<Boolean>(){
                public void success(Boolean enabled){if(!alive())return;musicCapabilityValidated=true;checkVideoPolicy(body,snapshot);}
                public void error(String error){if(!alive())return;restoreBeforeUpload();toast(error+" Có thể bỏ nhạc để dùng luồng đăng hiện tại.");}
            });return;
        }
        checkVideoPolicy(body,snapshot);
    }
    private void checkVideoPolicy(String body,String snapshot){
        busy=true;lockUnifiedComposer();binding.btnPostSubmit.setText("Đang kiểm tra giới hạn video…");
        Api.run(()->RpcClient.object("get_reel_upload_policy",Api.obj()),new Api.Callback<JSONObject>(){
            public void success(JSONObject policy){if(!alive())return;
                if(!policy.optBoolean("automatic_video_distribution")){restoreBeforeUpload();toast("Cloud cần migration Task 47 để tự động phân phối video lên Feed và Reels. Chưa tải video lên.");return;}
                long limit=com.example.meetomxh.ui.VideoPublishLimits.effective(policy.optLong("max_bytes",0));
                if(limit<=0||reelBytes>limit){restoreBeforeUpload();toast(limit>0?"Storage hiện giới hạn "+String.format(java.util.Locale.ROOT,"%.1f",limit/1000000f)+" MB. Hãy cắt đoạn ngắn hơn trước khi đăng.":"Chưa xác định được giới hạn Storage. Chưa tải video lên.");return;}
                frozenUnified=snapshot;unifiedLocked=true;lockUnifiedComposer();if(persistUnifiedReceipt())nextUpload(body);
            }
            public void error(String e){if(!alive())return;restoreBeforeUpload();toast(e+" Chưa tải video lên.");}
        });
    }
    private void restoreBeforeUpload(){
        busy=false;setComposerReady(true);binding.btnTagPeople.setEnabled(true);binding.chipsTaggedPeople.setEnabled(true);
        for(int i=0;i<binding.chipsTaggedPeople.getChildCount();i++)binding.chipsTaggedPeople.getChildAt(i).setEnabled(true);
        binding.btnEditReelVideo.setEnabled(reelEligible);binding.btnPostSubmit.setText("Đăng");
    }
    private void saveUnified(){
        if(assets.size()!=1){busy=false;binding.btnPostSubmit.setEnabled(true);toast("Chưa có đúng một video đã tải lên. Hãy thử lại.");return;}
        if(!persistUnifiedReceipt())return;
        final JSONObject input;
        try{input=new JSONObject(frozenUnified);input.put("p_video_asset_id",Dto.assetId(assets.get(0)));}
        catch(Exception e){busy=false;binding.btnPostSubmit.setEnabled(true);toast("Không đọc được bản nháp đăng video.");return;}
        // The request key belongs to the draft, not a potentially re-uploaded asset.
        String fingerprint=publishDraftId;binding.btnPostSubmit.setText("Đang đăng Feed và Reels…");
        Api.run(()->{input.put("p_client_request_id",RequestIds.get("unified_video",fingerprint));
            JSONObject result=input.has("p_music_metadata")
                    ?RpcClient.object("create_post_reel_with_music",input)
                    :RpcClient.object("create_post_reel_with_mentions",input);
            if(!result.optBoolean("is_reel")||result.optString("id").isEmpty())throw new IOException("Máy chủ chưa xác nhận nội dung Feed/Reels.");
            return result;
        },new Api.Callback<JSONObject>(){
            public void success(JSONObject result){if(!alive())return;RequestIds.complete("unified_video",fingerprint);
                unifiedReceipts().edit().remove(receiptKey()).apply();
                com.example.meetomxh.repositories.ReelsRepository.invalidate();FeedRepository.getInstance().refresh();
                toast("Đã đăng cùng một nội dung lên Feed và Reels.");setResult(RESULT_OK);finish();}
            public void error(String e){if(!alive())return;busy=false;binding.btnPostSubmit.setEnabled(true);binding.btnPostSubmit.setText("Thử đăng lại");
                toast(e+" Bản nháp và video đã tải được giữ để thử lại, không cần chọn lại.");}
        });
    }
    private android.content.SharedPreferences unifiedReceipts(){return getSharedPreferences("unified_post_drafts",MODE_PRIVATE);}
    private String receiptKey(){return SessionManager.getInstance().uid()+"|"+publishDraftId;}
    private boolean persistUnifiedReceipt(){
        JSONArray references=new JSONArray();for(String ref:assets)references.put(ref);
        JSONArray pending=new JSONArray();for(Uri uri:selected)pending.put(uri.toString());
        JSONArray owned=new JSONArray();for(String path:ownedReelDrafts)owned.put(path);
        // Persist before create RPC, closing the Bundle-save / upload-callback gap.
        boolean saved=unifiedReceipts().edit().putString(receiptKey(),Api.obj("snapshot",frozenUnified,"assets",references,"pending",pending,"owned",owned).toString()).commit();
        if(!saved){busy=false;binding.btnPostSubmit.setEnabled(true);binding.btnPostSubmit.setText("Thử lưu / đăng lại");toast("Không lưu được bản nháp đăng video. Chưa gửi yêu cầu tạo bài; hãy giải phóng bộ nhớ rồi thử lại.");}
        return saved;
    }
    private void restoreUnifiedReceipt(){
        String saved=unifiedReceipts().getString(receiptKey(),null);if(saved==null)return;
        try{
            JSONObject receipt=new JSONObject(saved);String snapshot=receipt.getString("snapshot");
            JSONObject payload=new JSONObject(snapshot);frozenUnified=snapshot;unifiedLocked=true;audience=payload.getString("p_audience");
            JSONObject savedMusic=payload.optJSONObject("p_music_metadata");editorMusicMetadata=savedMusic==null?null:savedMusic.toString();musicCapabilityValidated=false;
            binding.etPostContent.setText(payload.getString("p_caption"));
            JSONArray references=receipt.optJSONArray("assets"),pending=receipt.optJSONArray("pending");
            if(references!=null){assets.clear();for(int i=0;i<references.length();i++)assets.add(references.getString(i));}
            if(!assets.isEmpty())selected.clear();else if(pending!=null){selected.clear();for(int i=0;i<pending.length();i++)selected.add(Uri.parse(pending.getString(i)));}
            JSONArray owned=receipt.optJSONArray("owned");if(owned!=null)for(int i=0;i<owned.length();i++){
                String path=owned.getString(i);if(!ownedReelDrafts.contains(path))ownedReelDrafts.add(path);
            }
        }catch(Exception ignored){/* Bundle and retained request key remain the fallback. */}
    }
    private void renderTags(){if(binding==null)return;binding.chipsTaggedPeople.removeAllViews();for(int i=0;i<taggedPeople.length();i++){JSONObject person=taggedPeople.optJSONObject(i);if(person==null)continue;String id=person.optString("user_id",null);if(id==null||id.equals("null"))continue;Chip chip=new Chip(this);chip.setText(person.optString("display_name","Người theo dõi"));chip.setCloseIconVisible(true);chip.setOnCloseIconClickListener(v->{JSONArray ids=new JSONArray(),people=new JSONArray();for(int j=0;j<taggedPeople.length();j++){JSONObject p=taggedPeople.optJSONObject(j);if(p!=null&&!id.equals(p.optString("user_id"))){ids.put(p.optString("user_id"));people.put(p);}}taggedUsers=ids;taggedPeople=people;renderTags();});binding.chipsTaggedPeople.addView(chip);}binding.chipsTaggedPeople.setVisibility(taggedPeople.length()>0?View.VISIBLE:View.GONE);binding.btnTagPeople.setText(taggedPeople.length()==0?"Gắn thẻ người dùng":"Gắn thẻ người dùng · "+taggedPeople.length());}
    private static JSONArray parseArray(String json){try{return json==null?new JSONArray():new JSONArray(json);}catch(Exception ignored){return new JSONArray();}}
    private void toast(String text) { if (alive()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList("selected_media", new ArrayList<>(selected));
        out.putStringArrayList("uploaded_assets", new ArrayList<>(assets));
        out.putString("audience", audience);
        out.putString("unified_draft_id",publishDraftId);
        out.putString("reel_editor_output",editorOutput);out.putString("unified_snapshot",frozenUnified);
        out.putString("reel_music_metadata",editorMusicMetadata);
        out.putStringArrayList("owned_reel_drafts",ownedReelDrafts);
        out.putString("mentions_json",mentionComposer==null?"[]":mentionComposer.getMentions().toString());
        out.putString("tagged_users_json",taggedUsers.toString());out.putString("tagged_people_json",taggedPeople.toString());
    }

    @Override protected void onRestoreInstanceState(Bundle saved){
        super.onRestoreInstanceState(saved);
        if(mentionComposer!=null)mentionComposer.restore(parseArray(saved.getString("mentions_json")));
        if(frozenUnified!=null)try{JSONObject frozen=new JSONObject(frozenUnified);binding.etPostContent.setText(frozen.getString("p_caption"));
            audience=frozen.getString("p_audience");binding.btnAudience.setText(com.example.meetomxh.supabase.AudienceChoices.label(audience));
            mentionComposer.restore(frozen.optJSONArray("p_mentions"));lockUnifiedComposer();}catch(Exception ignored){}
    }

    @Override protected void onDestroy() {
        SessionManager.getInstance().identityListeners.remove(musicIdentityChanged);
        eligibilityGeneration++;
        if (transfer != null) transfer.cancel();
        if (isFinishing()) for(String path:ownedReelDrafts) {
            try {
                File file=new File(path).getCanonicalFile(),folder=new File(getFilesDir(),"post-reel-drafts").getCanonicalFile();
                if(folder.equals(file.getParentFile())&&file.getName().endsWith(".mp4")&&file.isFile())file.delete();
            }catch(IOException ignored){}
        }
        if(isFinishing())unifiedReceipts().edit().remove(receiptKey()).apply();
        if(mentionComposer!=null)mentionComposer.dispose();
        super.onDestroy();
    }
}
