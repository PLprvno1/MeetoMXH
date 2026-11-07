package com.example.meetomxh;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.bumptech.glide.Glide;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.ReelClipExporter;
import com.example.meetomxh.ui.ReelPlaybackMonitor;
import com.example.meetomxh.ui.ReelTrimTimelineView;
import org.json.JSONObject;
import java.io.*;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One selected local source, then Editor -> exported clip -> Publishing. No automatic picker relaunch. */
public final class CreateReelActivity extends AppCompatActivity {
    public static final String EXTRA_CAPTURE_PATH = ReelsCaptureSession.EXTRA_PATH;
    // Internal editor contract: return a local clip to Create Post; never upload in this mode.
    public static final String EXTRA_EDIT_ONLY = "reel_edit_only";
    public static final String EXTRA_OUTPUT_PATH = "reel_output_path";
    public static final String EXTRA_DURATION = "reel_output_duration";
    private static final long MAX_BYTES = com.example.meetomxh.ui.VideoPublishLimits.MAX_BYTES, MAX_DURATION = 60_000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ReelClipExporter exporter = new ReelClipExporter();
    private ActivityResultLauncher<PickVisualMediaRequest> picker;
    private PlayerView preview;
    private ExoPlayer player;
    private ReelPlaybackMonitor monitor;
    private ReelTrimTimelineView timeline;
    private ImageView poster, cover;
    private ProgressBar buffering, uploadProgress;
    private TextView status, range, header, fileInfo, previewError;
    private Button next, publish;
    private EditText caption;
    private Spinner audience;
    private SeekBar coverBar;
    private CheckBox mute;
    private View editorPanel, publishingPanel;
    private File draftDir, sourceFile, editedFile;
    private long sourceDuration, startMs, endMs, coverMs, playbackMs, outputDuration;
    private int width, height, generation, coverGeneration;
    private boolean busy, publishing, resumed, disposed, restorePublishing, policyValidated;
    private String uploadedAsset, uploadedCoverAsset, frozenCaption, frozenAudience;
    private Uri pendingUri;
    private SupabaseStorage.Transfer transfer;
    private Subscription createRequest;
    private final Runnable coverUpdate = this::extractCover;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (disposed || player == null || !resumed) return;
            if (!publishing && !timeline.isInteracting()) {
                if (player.getPlayWhenReady() && player.getCurrentPosition() >= endMs) {
                    player.pause();player.seekTo(startMs);
                }
                timeline.setPlayhead(player.getCurrentPosition());
            }
            main.postDelayed(this, 50);
        }
    };
    private final Runnable exportTicker = new Runnable() {
        @Override public void run() {
            if (disposed || !busy || publishing) return;
            int progress = exporter.progress();
            uploadProgress.setIndeterminate(progress < 0);
            if (progress >= 0) uploadProgress.setProgress(progress);
            main.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_reel_create);
        View root = findViewById(R.id.reel_create_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);return insets;
        });
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(false);
        preview = findViewById(R.id.reel_preview);poster = findViewById(R.id.reel_preview_poster);
        buffering = findViewById(R.id.reel_preview_loading);previewError = findViewById(R.id.reel_preview_error);
        timeline = findViewById(R.id.reel_timeline);range = findViewById(R.id.reel_trim_label);
        mute = findViewById(R.id.reel_mute_audio);cover = findViewById(R.id.reel_cover_preview);
        coverBar = findViewById(R.id.reel_cover_position);caption = findViewById(R.id.reel_caption);
        audience = findViewById(R.id.reel_audience);status = findViewById(R.id.reel_status);
        uploadProgress = findViewById(R.id.reel_upload_progress);publish = findViewById(R.id.reel_publish);
        next = findViewById(R.id.reel_next);header = findViewById(R.id.reel_header);
        fileInfo = findViewById(R.id.reel_file_info);editorPanel = findViewById(R.id.reel_editor_panel);
        publishingPanel = findViewById(R.id.reel_publishing_panel);
        audience.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Công khai", "Người theo dõi", "Chỉ mình tôi"}));
        AudienceChoices.spinner(this, audience, saved == null ? null : saved.getString("audience_value"));
        audience.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (view instanceof TextView) ((TextView) view).setTextColor(0xFFFFFFFF);
                if(frozenAudience!=null&&audience.getTag() instanceof String[]){
                    int frozen=java.util.Arrays.asList((String[])audience.getTag()).indexOf(frozenAudience);
                    if(frozen>=0&&frozen!=position)audience.setSelection(frozen);
                }
                audience.setEnabled(!busy && frozenCaption == null && audience.getTag() instanceof String[]);
            }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        picker = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) stage(uri, false);
        });
        findViewById(R.id.reel_pick_video).setOnClickListener(v -> {
            if (!busy && uploadedAsset == null) picker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE).build());
        });
        findViewById(R.id.reel_back).setOnClickListener(v -> back());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { back(); }
        });
        next.setOnClickListener(v -> exportForPublishing());
        if (editOnly()) {
            next.setText("Dùng video này");
            findViewById(R.id.reel_pick_video).setVisibility(View.GONE);
        }
        publish.setOnClickListener(v -> submit());
        previewError.setOnClickListener(v -> { if (!busy && sourceFile != null) preparePreview(); });
        mute.setOnCheckedChangeListener((button, checked) -> {
            if (player != null) player.setVolume(checked ? 0 : 1);
            if (!busy && !publishing) invalidateOutput();
        });
        timeline.setListener(new ReelTrimTimelineView.Listener() {
            public void rangeChanged(long from, long to, long seek) {
                startMs = from;endMs = to;coverMs = Math.min(coverMs, to - from - 1);
                invalidateOutput();updateRange();scrub(seek);
            }
            public void scrub(long time) { CreateReelActivity.this.scrub(time); }
            public void interactionFinished() { queueCover(); }
        });
        coverBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { }
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) { coverMs = value;queueCover(); }
            }
        });
        setBusy(false);showStep(false);
        if (saved != null) {
            startMs = saved.getLong("start");endMs = saved.getLong("end");coverMs = saved.getLong("cover");
            playbackMs = saved.getLong("playback");mute.setChecked(saved.getBoolean("mute"));
            uploadedAsset = saved.getString("asset");uploadedCoverAsset = saved.getString("cover_asset");
            frozenCaption = saved.getString("frozen_caption");frozenAudience = saved.getString("frozen_audience");
            String path = saved.getString("source");
            if (path != null && isDraftFile(new File(path)) && new File(path).isFile()) {
                sourceFile = new File(path);draftDir = sourceFile.getParentFile();
                sourceDuration = saved.getLong("duration");width = saved.getInt("width");height = saved.getInt("height");
                String output = saved.getString("output");
                if (output != null && isDraftFile(new File(output)) && new File(output).isFile()) {
                    editedFile = new File(output);outputDuration = saved.getLong("output_duration");
                    restorePublishing = saved.getBoolean("publishing");
                }
                restorePublishReceipt();
                acceptSource(false);return;
            }
        }
        Uri incoming = saved != null && saved.getString("pending_uri") != null
                ? Uri.parse(saved.getString("pending_uri")) : getIntent().getData();
        if (incoming == null && getIntent().getStringExtra(ReelsCaptureSession.EXTRA_URI) != null)
            incoming = Uri.parse(getIntent().getStringExtra(ReelsCaptureSession.EXTRA_URI));
        String capturePath = getIntent().getStringExtra(ReelsCaptureSession.EXTRA_PATH);
        if (incoming == null && capturePath != null) incoming = Uri.fromFile(new File(capturePath));
        if (incoming != null) stage(incoming, getIntent().getBooleanExtra(ReelsCaptureSession.EXTRA_OWNS_FILE, false));
        else status.setText("Chọn Đổi video để mở thư viện, hoặc quay video từ camera Reels.");
    }

    private void stage(Uri uri, boolean ownsCapture) {
        if (busy || uploadedAsset != null) return;
        if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) {
            status.setText("Nguồn video không hợp lệ. Hãy chọn Đổi video.");return;
        }
        pendingUri = uri;
        // Photo Picker grants are transient on some providers. Copy once while this grant is held.
        if ("content".equals(uri.getScheme())) try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { /* A private local copy is created below. */ }
        setBusy(true);status.setText("Đang chuẩn bị video đã chọn…");
        final int token = ++generation;
        work.execute(() -> {
            File staged = null;
            try {
                File folder = new File(getFilesDir(), "reel-drafts/" + UUID.randomUUID());
                if (!folder.mkdirs()) throw new IOException("Không tạo được bộ nhớ bản nháp.");
                staged = new File(folder, "source.video");
                long budget = Math.max(0, getFilesDir().getUsableSpace() - 64L * 1024 * 1024);
                // uri.getPath() is only valid for a genuine file URI, never a content URI.
                try (InputStream input = "file".equals(uri.getScheme()) ? new FileInputStream(new File(uri.getPath()))
                        : getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(staged)) {
                    if (input == null) throw new IOException("Không đọc được video đã chọn.");
                    byte[] bytes = new byte[32768];int count;long total = 0;
                    while ((count = input.read(bytes)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Đã dừng chuẩn bị video.");
                        total += count;if (total > budget) throw new IOException("Không đủ bộ nhớ để lưu video nguồn.");
                        output.write(bytes, 0, count);
                    }
                }
                MediaMetadataRetriever meta = new MediaMetadataRetriever();long duration;int w, h, rotation;
                try {
                    meta.setDataSource(staged.getAbsolutePath());
                    duration = number(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    w = (int) number(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    h = (int) number(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    rotation = (int) number(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                } finally { meta.release(); }
                if (duration < 1000 || w <= 0 || h <= 0) throw new IOException("Video phải có hình ảnh và dài ít nhất 1 giây.");
                int videoWidth = rotation == 90 || rotation == 270 ? h : w;
                int videoHeight = rotation == 90 || rotation == 270 ? w : h;
                File ready = staged;
                main.post(() -> {
                    if (disposed || token != generation) { deleteDraft(ready.getParentFile());return; }
                    releasePlayer();deleteDraft(draftDir);
                    sourceFile = ready;draftDir = ready.getParentFile();sourceDuration = duration;
                    width = videoWidth;height = videoHeight;startMs = 0;endMs = Math.min(MAX_DURATION, duration);
                    coverMs = 0;playbackMs = 0;editedFile = null;uploadedAsset = null;uploadedCoverAsset = null;
                    frozenCaption = null;frozenAudience = null;pendingUri = null;
                    if (ownsCapture && "file".equals(uri.getScheme())) deleteOwnedCapture(new File(uri.getPath()));
                    acceptSource(true);
                });
            } catch (Exception error) {
                if (staged != null) deleteDraft(staged.getParentFile());
                main.post(() -> { if (!disposed && token == generation) {
                    setBusy(false);status.setText(error.getMessage() == null ? "Không thể đọc video. Chọn Đổi video để thử lại." : error.getMessage());
                }});
            }
        });
    }

    private void acceptSource(boolean fresh) {
        if (endMs <= startMs || endMs > sourceDuration || endMs - startMs > MAX_DURATION) {
            startMs = 0;endMs = Math.min(MAX_DURATION, sourceDuration);
        }
        timeline.setSource(sourceFile, sourceDuration, startMs, endMs);updateRange();setBusy(false);
        showStep(restorePublishing && editedFile != null);restorePublishing = false;
        if (!publishing && resumed) preparePreview();
        status.setText(fresh ? "Kéo hai tay nắm để cắt, kéo thước phía dưới để di chuyển đoạn. Kéo vạch trắng để xem; chạm Play để phát." : "Đã khôi phục video và đoạn cắt.");
        queueCover();
    }
    private void updateRange() {
        range.setText("Bắt đầu " + ReelTrimTimelineView.format(startMs) + "   Kết thúc " + ReelTrimTimelineView.format(endMs)
                + "\nĐã chọn " + ReelTrimTimelineView.format(endMs - startMs) + " / 60 giây");
        coverBar.setMax((int) Math.max(1, endMs - startMs - 1));coverBar.setProgress((int) coverMs);
    }
    private void scrub(long time) {
        if (player != null) { player.pause();player.seekTo(Math.max(0, Math.min(sourceDuration - 1, time))); }
    }
    private void preparePreview() {
        if (sourceFile == null || !resumed || publishing) return;
        releasePlayer();previewError.setVisibility(View.GONE);poster.setVisibility(View.VISIBLE);
        buffering.setVisibility(View.VISIBLE);
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build();
        ExoPlayer observedPlayer = player;
        player.setSeekParameters(SeekParameters.EXACT);
        monitor = new ReelPlaybackMonitor(player, new ReelPlaybackMonitor.Callback() {
            public void waiting() { poster.setVisibility(View.VISIBLE); }
            public void firstFrame() { poster.setVisibility(View.GONE);buffering.setVisibility(View.GONE); }
            public void failed(String message) { buffering.setVisibility(View.GONE);previewError.setText(message + "\nThử lại");previewError.setVisibility(View.VISIBLE); }
        });
        monitor.begin("local_reel_preview");monitor.attach(preview);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (player != observedPlayer || monitor == null) return;
                buffering.setVisibility(state == Player.STATE_BUFFERING || state == Player.STATE_READY && !monitor.hasFrame() ? View.VISIBLE : View.GONE);
                if (state == Player.STATE_ENDED) { player.pause();player.seekTo(startMs); }
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (player != observedPlayer) return;
                if (playing && !timeline.isInteracting() && (player.getCurrentPosition() < startMs || player.getCurrentPosition() >= endMs)) player.seekTo(startMs);
            }
        });
        player.setMediaItem(new MediaItem.Builder().setUri(Uri.fromFile(sourceFile)).setMediaId("local_reel_preview").build());
        player.seekTo(Math.max(startMs, Math.min(endMs - 1, playbackMs)));
        player.setVolume(mute.isChecked() ? 0 : 1);player.prepare();player.play();
        main.removeCallbacks(ticker);main.post(ticker);
        Glide.with(this).load(sourceFile).frame(startMs * 1000).fitCenter().into(poster);
    }
    private void releasePlayer() {
        main.removeCallbacks(ticker);
        if (player != null) {
            playbackMs = player.getCurrentPosition();
            if (monitor != null) { monitor.release();monitor = null; }
            player.release();player = null;
        }
    }
    private void invalidateOutput() {
        policyValidated=false;
        if (uploadedAsset != null) return;
        if (editedFile != null) editedFile.delete();
        editedFile = null;outputDuration = 0;
    }
    private void exportForPublishing() {
        if (busy || sourceFile == null) return;
        if (endMs - startMs < 1000 || endMs - startMs > MAX_DURATION) { status.setText("Chọn đoạn từ 1 đến 60 giây.");return; }
        if (editedFile != null && editedFile.isFile()) { outputReady();return; }
        releasePlayer();setBusy(true);status.setText("Đang xuất đúng đoạn đã chọn…");
        uploadProgress.setVisibility(View.VISIBLE);uploadProgress.setIndeterminate(true);
        File result = new File(draftDir, "edited-" + UUID.randomUUID() + ".mp4");
        final int token = generation;
        // A full, already compliant MP4 needs a byte copy, not another encode.
        if (startMs == 0 && endMs == sourceDuration && !mute.isChecked() && sourceFile.length() <= com.example.meetomxh.ui.VideoPublishLimits.EXPORT_BYTES) {
            work.execute(() -> {
                try {
                    MediaMetadataRetriever meta = new MediaMetadataRetriever();String mime;
                    try { meta.setDataSource(sourceFile.getAbsolutePath());mime = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE); }
                    finally { meta.release(); }
                    if (!"video/mp4".equals(mime)||com.example.meetomxh.ui.VideoPublishLimits.needsEncoding(CreateReelActivity.this,Uri.fromFile(sourceFile))) { main.post(() -> { if (!disposed && token == generation) startExport(result, token); });return; }
                    copyVideo(sourceFile, result);
                    long duration = validateOutput(result, endMs - startMs);
                    main.post(() -> { if (disposed || token != generation) { result.delete();return; }
                        editedFile = result;outputDuration = duration;setBusy(false);outputReady(); });
                } catch (Exception e) { result.delete();main.post(() -> { if (!disposed && token == generation) exportFailed(e.getMessage()); }); }
            });
            return;
        }
        startExport(result, token);
    }
    private void startExport(File result, int token) {
        exporter.start(this, sourceFile, result, startMs, endMs, mute.isChecked(), width, height, new ReelClipExporter.Callback() {
            public void completed(File file) {
                if (disposed || token != generation) { file.delete();return; }
                work.execute(() -> {
                    try {
                        long duration = validateOutput(file, endMs - startMs);
                        main.post(() -> { if (disposed || token != generation) { file.delete();return; }
                            editedFile = file;outputDuration = duration;setBusy(false);outputReady();
                        });
                    } catch (Exception error) { file.delete();main.post(() -> { if (token == generation) exportFailed(error.getMessage()); }); }
                });
            }
            public void failed(String message) { if (token == generation) exportFailed(message); }
        });
        main.removeCallbacks(exportTicker);main.post(exportTicker);
    }
    private boolean editOnly() { return getIntent().getBooleanExtra(EXTRA_EDIT_ONLY, false); }
    private static void copyVideo(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[32768];int count;
            while ((count = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Đã dừng lưu video.");
                out.write(buffer, 0, count);
            }
        }
    }
    private void outputReady() {
        if (!editOnly()) {
            showStep(true);status.setText("Video đã sẵn sàng. Thêm nội dung và chọn quyền riêng tư.");queueCover();return;
        }
        setBusy(true);status.setText("Đang trả video về bài viết…");
        final int token = generation;
        work.execute(() -> {
            File target = null;
            try {
                String path = getIntent().getStringExtra(EXTRA_OUTPUT_PATH);
                if (path == null) throw new IOException("Thiếu bản nháp bài viết.");
                File candidate = new File(path).getCanonicalFile();
                File root = new File(getFilesDir(), "post-reel-drafts").getCanonicalFile();
                if (!candidate.getPath().startsWith(root.getPath() + File.separator) || !candidate.getName().endsWith(".mp4"))
                    throw new IOException("Bản nháp bài viết không hợp lệ.");
                target = candidate;
                copyVideo(editedFile, target);
                File ready = target;
                main.post(() -> { if (disposed || token != generation) { ready.delete();return; }
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_OUTPUT_PATH, ready.getAbsolutePath()).putExtra(EXTRA_DURATION, outputDuration));finish(); });
            } catch (Exception e) {
                if (target != null) target.delete();
                main.post(() -> { if (!disposed && token == generation) exportFailed(e.getMessage()); });
            }
        });
    }
    private long validateOutput(File file, long expectedDuration) throws Exception {
        if (!file.isFile() || file.length() == 0) throw new IOException("Chưa xuất được video.");
        if (file.length() > MAX_BYTES) throw new IOException("Video thành phẩm vượt 49 MB. Hãy cắt đoạn ngắn hơn.");
        MediaMetadataRetriever meta = new MediaMetadataRetriever();
        try {
            meta.setDataSource(file.getAbsolutePath());
            long duration = number(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (duration <= 0 || duration > MAX_DURATION || Math.abs(duration - expectedDuration) > 250
                    || !"yes".equals(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))
                throw new IOException("Video xuất không đúng đoạn đã chọn. Hãy chỉnh đoạn và xuất lại.");
            android.util.Log.i("MeetoPublish","stage=export_validated duration_ms="+duration+" bytes="+file.length());
            return duration;
        } finally { meta.release(); }
    }
    private void exportFailed(String message) {
        if (disposed) return;
        main.removeCallbacks(exportTicker);setBusy(false);uploadProgress.setVisibility(View.GONE);
        status.setText(message == null ? "Không thể xuất video. Chỉnh sửa vẫn được giữ." : message);
        if (resumed && !publishing) preparePreview();
    }
    private void showStep(boolean information) {
        publishing = information;
        editorPanel.setVisibility(information ? View.GONE : View.VISIBLE);
        publishingPanel.setVisibility(information ? View.VISIBLE : View.GONE);
        header.setText(information ? "Đăng Reel" : "Chỉnh sửa video");
        next.setVisibility(information ? View.GONE : View.VISIBLE);publish.setVisibility(information ? View.VISIBLE : View.GONE);
        if (information) {
            releasePlayer();
            fileInfo.setText(String.format(Locale.ROOT, "%.1f giây · %.1f MiB", outputDuration / 1000f, editedFile.length() / 1048576f));
        }
        setBusy(busy);
    }
    private void setBusy(boolean value) {
        busy = value;
        boolean editing = !value && uploadedAsset == null;
        timeline.setEnabled(editing);mute.setEnabled(editing);findViewById(R.id.reel_pick_video).setEnabled(editing);
        coverBar.setEnabled(editing);caption.setEnabled(!value && frozenCaption == null);audience.setEnabled(!value && frozenCaption == null && audience.getTag() instanceof String[]);
        next.setEnabled(editing && sourceFile != null);publish.setEnabled(!value && editedFile != null);
        if (!value) { main.removeCallbacks(exportTicker);uploadProgress.setVisibility(View.GONE); }
    }
    private void queueCover() { main.removeCallbacks(coverUpdate);main.postDelayed(coverUpdate, 160); }
    private void extractCover() {
        File file = publishing ? editedFile : sourceFile;
        if (file == null || disposed) return;
        long at = publishing ? coverMs : startMs + coverMs;
        int token = ++coverGeneration;
        work.execute(() -> {
            Bitmap frame = frame(file, at, 480);
            if (frame == null) return;
            main.post(() -> {
                if (disposed || token != coverGeneration) { frame.recycle();return; }
                cover.setImageBitmap(frame);
            });
        });
    }
    private Bitmap frame(File file, long at, int max) {
        MediaMetadataRetriever meta = new MediaMetadataRetriever();
        try {
            meta.setDataSource(file.getAbsolutePath());
            if (Build.VERSION.SDK_INT >= 27) return meta.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST, max, max);
            Bitmap original = meta.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
            if (original == null) return null;
            float factor = Math.min(1f, max / (float) Math.max(original.getWidth(), original.getHeight()));
            Bitmap result = Bitmap.createScaledBitmap(original, Math.max(1, Math.round(original.getWidth() * factor)), Math.max(1, Math.round(original.getHeight() * factor)), true);
            if (result != original) original.recycle();return result;
        } catch (Exception ignored) { return null; }
        finally { try { meta.release(); } catch (Exception ignored) { } }
    }

    private void submit() {
        if (editOnly()) return;
        if (busy || editedFile == null || !editedFile.isFile()) return;
        if (editedFile.length() > MAX_BYTES || outputDuration <= 0 || outputDuration > MAX_DURATION) {
            status.setText("Video thành phẩm phải dưới 49 MB và không quá 60 giây.");return;
        }
        if(!policyValidated){
            setBusy(true);status.setText("Đang kiểm tra cấu hình đăng Reel và giới hạn bucket…");
            createRequest=Api.run(()->RpcClient.object("get_reel_upload_policy",Api.obj()),new Api.Callback<JSONObject>(){
                public void success(JSONObject policy){if(disposed)return;
                    if(!policy.optBoolean("automatic_video_distribution")){failPublish("Cloud chưa có phân phối video tự động. Owner cần triển khai migration Task 47 trước khi đăng. Video và caption được giữ nguyên.");return;}
                    long limit=com.example.meetomxh.ui.VideoPublishLimits.effective(policy.optLong("max_bytes",0));
                    if(limit<=0||editedFile.length()>limit){failPublish("Video vượt giới hạn bucket thực tế ("+String.format(Locale.ROOT,"%.1f",limit/1000000f)+" MB). Hãy quay lại editor và chọn đoạn ngắn hơn.");return;}
                    policyValidated=true;setBusy(false);submit();
                }
                public void error(String e){failPublish("Chưa kiểm tra được cấu hình Cloud. "+e);}
            });return;
        }
        if (frozenCaption == null) {
            frozenCaption = caption.getText().toString();frozenAudience = AudienceChoices.value(audience);
        }
        if(!persistPublishReceipt())return;
        setBusy(true);uploadProgress.setVisibility(View.VISIBLE);uploadProgress.setIndeterminate(false);
        if (uploadedAsset != null) { if (uploadedCoverAsset == null) uploadCover();else create();return; }
        status.setText("Đang tải video thành phẩm lên…");
        transfer = SupabaseStorage.uploadVideo(getContentResolver(), editedFile, "posts", SessionManager.getInstance().uid(), new SupabaseStorage.UploadListener() {
            public void onStage(SupabaseStorage.Stage stage){if(disposed)return;
                switch(stage){case VALIDATING:status.setText("Đang kiểm tra video thành phẩm…");break;
                    case RESERVING:status.setText("Đang cấp phiên upload video…");break;
                    case UPLOADING:status.setText("Đang tải video thành phẩm…");break;
                    case FINALIZING:status.setText("Video đã tải. Đang xử lý và xác nhận media…");break;}
            }
            public void onProgress(int percent) { if (!disposed) uploadProgress.setProgress(percent); }
            public void onFailure(String error) { failPublish("Tải lên thất bại. Video và nội dung vẫn được giữ. " + error); }
            public void onSuccess(String reference) { if (!disposed) { uploadedAsset = reference;if(persistPublishReceipt())uploadCover(); } }
        });
    }
    private void uploadCover() {
        status.setText("Đang lưu ảnh bìa…");
        work.execute(() -> {
            File image = null;
            try {
                Bitmap bitmap = frame(editedFile, Math.min(coverMs, outputDuration - 1), 960);
                if (bitmap == null) throw new IOException("Không đọc được ảnh bìa.");
                image = new File(draftDir, "cover.jpg");
                try (OutputStream stream = new FileOutputStream(image)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 86, stream)) throw new IOException("Không lưu được ảnh bìa.");
                } finally { bitmap.recycle(); }
                File ready = image;
                main.post(() -> {
                    if (disposed) return;
                    transfer = SupabaseStorage.uploadImage(getContentResolver(), ready, "posts", SessionManager.getInstance().uid(), new SupabaseStorage.UploadListener() {
                        public void onStage(SupabaseStorage.Stage stage){if(!disposed)status.setText(stage==SupabaseStorage.Stage.FINALIZING?"Video đã sẵn sàng. Đang xác nhận ảnh bìa…":"Video đã sẵn sàng. Đang tải ảnh bìa…");}
                        public void onProgress(int percent) { if (!disposed) uploadProgress.setProgress(percent); }
                        public void onFailure(String error) { failPublish("Video đã tải lên; ảnh bìa chưa hoàn tất. Nhấn Đăng Reel để thử tiếp. " + error); }
                        public void onSuccess(String reference) { if (!disposed) { uploadedCoverAsset = reference;if(persistPublishReceipt())create(); } }
                    });
                });
            } catch (Exception error) { main.post(() -> failPublish("Không tạo được ảnh bìa. Nhấn Đăng Reel để thử lại.")); }
        });
    }
    private void create() {
        if(!persistPublishReceipt())return;
        status.setText("Đang xác nhận đăng Reel…");
        int duration = (int) outputDuration, coverPosition = (int) Math.min(coverMs, outputDuration - 1);
        // Preserve the existing operation/fingerprint; receipts retain its assets across recreation.
        String fingerprint = uploadedAsset + "|" + frozenCaption + "|" + frozenAudience + "|" + duration + "|" + coverPosition;
        String requestId = RequestIds.get("create_reel", fingerprint);
        createRequest = Api.run(() -> {
            return RpcClient.object("create_reel", Api.obj("p_video_asset_id", Dto.assetId(uploadedAsset),
                    "p_cover_asset_id", Dto.assetId(uploadedCoverAsset), "p_caption", frozenCaption, "p_audience", frozenAudience,
                    "p_duration_ms", duration, "p_cover_position_ms", coverPosition, "p_client_request_id", requestId));
        }, new Api.Callback<JSONObject>() {
            public void success(JSONObject result) {
                if (disposed) return;
                if(result.optString("id").isEmpty()||!result.optBoolean("is_reel")){android.util.Log.w("MeetoPublish","stage=rpc.create_reel response=invalid_shape");failPublish("Cloud chưa trả xác nhận Reel hợp lệ. Giữ bản nháp để thử lại cùng yêu cầu.");return;}
                RequestIds.complete("create_reel", fingerprint);
                com.example.meetomxh.repositories.ReelsRepository.invalidate();
                com.example.meetomxh.repositories.FeedRepository.getInstance().refresh();
                status.setText("Đã đăng Reel");setResult(RESULT_OK);busy = false;finish();
            }
            public void error(String error) { failPublish("Chưa xác nhận đăng thành công. Nhấn Đăng Reel để thử lại cùng yêu cầu. " + error); }
        });
    }
    private void failPublish(String message) {
        if (disposed) return;
        transfer = null;setBusy(false);status.setText(message);
        publish.setText("Thử lại");
    }
    private android.content.SharedPreferences publishReceipts(){return getSharedPreferences("reel_publish_receipts",MODE_PRIVATE);}
    private String receiptKey(){return SessionManager.getInstance().uid()+"|"+draftDir.getName();}
    private boolean persistPublishReceipt(){
        if(draftDir==null||editedFile==null)return false;
        boolean saved=publishReceipts().edit().putString(receiptKey(),Api.obj("asset",uploadedAsset,"cover_asset",uploadedCoverAsset,
                "caption",frozenCaption,"audience",frozenAudience,"cover_ms",coverMs,"output",editedFile.getAbsolutePath(),"duration",outputDuration).toString()).commit();
        if(!saved)failPublish("Không lưu được trạng thái đăng Reel. Giữ bản nháp, giải phóng bộ nhớ rồi nhấn Thử lại; chưa gửi yêu cầu tạo Reel.");
        return saved;
    }
    private void restorePublishReceipt(){
        String saved=publishReceipts().getString(receiptKey(),null);if(saved==null)return;
        try{
            JSONObject receipt=new JSONObject(saved);File output=new File(receipt.getString("output"));
            if(!isDraftFile(output)||!output.isFile()||!output.getCanonicalFile().getParentFile().equals(draftDir.getCanonicalFile()))return;
            editedFile=output;outputDuration=receipt.getLong("duration");restorePublishing=true;
            uploadedAsset=Dto.nullable(receipt,"asset");uploadedCoverAsset=Dto.nullable(receipt,"cover_asset");
            frozenCaption=Dto.nullable(receipt,"caption");frozenAudience=Dto.nullable(receipt,"audience");coverMs=receipt.optLong("cover_ms");
            if(frozenCaption!=null)caption.setText(frozenCaption);
        }catch(Exception ignored){/* Saved Bundle remains the fallback; never allocate a new publish ID here. */}
    }
    private void back() {
        if (busy) {
            if (!publishing && sourceFile != null) new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Dừng xuất video?").setMessage("Video nguồn và đoạn cắt vẫn được giữ.")
                    .setNegativeButton("Tiếp tục xuất", null).setPositiveButton("Dừng xuất", (dialog, which) -> {
                        generation++;exporter.cancel();setBusy(false);preparePreview();status.setText("Đã dừng xuất. Bạn có thể chỉnh sửa và nhấn Tiếp lại.");
                    }).show();
            else Toast.makeText(this, "Đang xử lý video. Hãy đợi hoàn tất hoặc thử lại sau khi có lỗi.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (publishing && uploadedAsset == null) { showStep(false);preparePreview();return; }
        if (sourceFile != null) new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(editOnly()?"Hủy chỉnh sửa video?":"Thoát tạo Reel?").setMessage(editOnly()?"Video gốc và nội dung bài viết vẫn được giữ.":"Bản nháp cục bộ này sẽ bị xóa.")
                .setNegativeButton("Ở lại", null).setPositiveButton("Thoát", (dialog, which) -> finish()).show();
        else finish();
    }
    private long number(String text) { try { return Long.parseLong(text); } catch (Exception ignored) { return 0; } }
    private boolean isDraftFile(File file) {
        try { return file.getCanonicalPath().startsWith(new File(getFilesDir(), "reel-drafts").getCanonicalPath() + File.separator); }
        catch (IOException ignored) { return false; }
    }
    private void deleteOwnedCapture(File file) {
        try { if (file.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath() + File.separator)
                && file.getName().startsWith("meeto-reel-camera-")) file.delete(); }
        catch (IOException ignored) { }
    }
    private void deleteDraft(File folder) {
        if (folder == null || !isDraftFile(folder)) return;
        // Only this selected session's flat directory; never recurse through arbitrary paths.
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) if (file.isFile() && isDraftFile(file)) file.delete();
        folder.delete();
    }
    @Override protected void onResume() { super.onResume();resumed = true;if (sourceFile != null && !busy && !publishing) preparePreview(); }
    @Override protected void onStop() { resumed = false;releasePlayer();super.onStop(); }
    @Override protected void onRestoreInstanceState(Bundle state){
        super.onRestoreInstanceState(state);
        if(frozenCaption!=null)caption.setText(frozenCaption);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("source", sourceFile == null ? null : sourceFile.getAbsolutePath());
        state.putString("output", editedFile == null ? null : editedFile.getAbsolutePath());
        state.putLong("duration", sourceDuration);state.putLong("start", startMs);state.putLong("end", endMs);
        state.putLong("cover", coverMs);state.putLong("playback", player == null ? playbackMs : player.getCurrentPosition());
        state.putInt("width", width);state.putInt("height", height);state.putBoolean("mute", mute.isChecked());
        state.putBoolean("publishing", publishing);state.putLong("output_duration", outputDuration);
        state.putString("asset", uploadedAsset);state.putString("cover_asset", uploadedCoverAsset);
        state.putString("frozen_caption", frozenCaption);state.putString("frozen_audience", frozenAudience);
        state.putString("audience_value", AudienceChoices.value(audience));
        state.putString("pending_uri", pendingUri == null ? null : pendingUri.toString());
        if (pendingUri != null) getIntent().setData(pendingUri);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        disposed = true;generation++;coverGeneration++;main.removeCallbacksAndMessages(null);
        exporter.cancel();if (transfer != null) transfer.cancel();if (createRequest != null) createRequest.remove();
        releasePlayer();timeline.release();work.shutdownNow();
        if (isFinishing()) {if(draftDir!=null)publishReceipts().edit().remove(receiptKey()).apply();deleteDraft(draftDir);}
        super.onDestroy();
    }
}
