package com.example.meetomxh.ui;

import android.content.Context;
import android.media.metrics.LogSessionId;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import com.example.meetomxh.music.MusicComposition;
import com.example.meetomxh.music.OpenverseTrack;
import com.example.meetomxh.music.MusicLibraryCache;
import java.io.File;
import java.io.IOException;
import java.util.Collections;

/** Main-thread Transformer owner. Always encodes the requested timestamps, never previous keyframes. */
public final class ReelClipExporter {
    public interface Callback { void completed(File file); void failed(String message); }
    private Transformer transformer;
    private File output;
    private long generation;
    private MusicLibraryCache.Lease musicLease;
    private final ProgressHolder progress = new ProgressHolder();

    public void start(Context context, File source, File destination, long start, long end,
                      boolean mute, int width, int height, Callback callback) {
        start(context,source,destination,start,end,mute,100,width,height,null,0,callback);
    }

    public void start(Context context, File source, File destination, long start, long end,
                      boolean mute, int sourceVolume, int width, int height, OpenverseTrack music,
                      int musicVolume, Callback callback) {
        cancel();
        final long run = generation;
        // Refuse aliasing before assigning output: cancel must never delete the source.
        try {
            if (source.getCanonicalFile().equals(destination.getCanonicalFile()))
                throw new IOException("Tệp xuất phải khác video nguồn.");
        } catch (IOException error) { callback.failed(error.getMessage());return; }
        output = destination;
        try {
        File parent = destination.getParentFile();
        if (parent == null || !parent.isDirectory() || parent.getUsableSpace() < 64L * 1024 * 1024)
            throw new IOException("Cần ít nhất 64 MiB trống để xuất Reel. Bản chỉnh sửa vẫn được giữ.");
        if (music != null && music.library != null) {
            musicLease = new MusicLibraryCache(context).acquire(music.library);
            if (musicLease == null || !musicLease.file.getCanonicalPath().equals(new File(music.localPath).getCanonicalPath()))
                throw new IOException("Tệp nhạc thiếu hoặc hỏng. Hãy tải lại bài nhạc.");
        }
        DefaultEncoderFactory delegate = new DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate((int)Math.min(4_000_000L,
                        Math.max(250_000L, VideoPublishLimits.EXPORT_BYTES * 8L * 1000L / Math.max(1000L,end-start) - 256_000L))).build())
                .setEnableFallback(true).build();
        Codec.EncoderFactory encoder = new Codec.EncoderFactory() {
            @Override public Codec createForAudioEncoding(Format format, @Nullable LogSessionId id) throws ExportException {
                return delegate.createForAudioEncoding(format, id);
            }
            @Override public Codec createForVideoEncoding(Format format, @Nullable LogSessionId id) throws ExportException {
                return delegate.createForVideoEncoding(format, id);
            }
            @Override public boolean videoNeedsEncoding() { return true; }
        };
        MediaItem input = new MediaItem.Builder().setUri(Uri.fromFile(source))
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start).setEndPositionMs(end).build()).build();
        float scale = Math.min(1f, Math.min(1280f / Math.max(1, Math.max(width, height)),
                720f / Math.max(1, Math.min(width,height))));
        int targetWidth = Math.max(2, Math.round(width * scale / 2) * 2);
        int targetHeight = Math.max(2, Math.round(height * scale / 2) * 2);
        EditedMediaItem edited = new EditedMediaItem.Builder(input).setRemoveAudio(mute).setFrameRate(30)
                .setEffects(new Effects(Collections.emptyList(), java.util.Arrays.asList(
                        Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT),
                        androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(30))))
                .build();
        Composition composition;
        if(music!=null){
            try{composition=MusicComposition.video(source,start,end,mute,sourceVolume,music,musicVolume,
                    java.util.Arrays.asList(Presentation.createForWidthAndHeight(targetWidth,targetHeight,Presentation.LAYOUT_SCALE_TO_FIT),
                            androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(30)),
                    Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);}
            catch(Exception error){fail(run,callback,"Không thể chuẩn bị đoạn nhạc đã chọn. Hãy tải lại nhạc hoặc chọn bài khác. Bản chỉnh sửa vẫn được giữ.");return;}
        }else{
            EditedMediaItemSequence sequence=mute
                    ?EditedMediaItemSequence.withVideoFrom(Collections.singletonList(edited))
                    :EditedMediaItemSequence.withAudioAndVideoFrom(Collections.singletonList(edited));
            composition=new Composition.Builder(sequence)
                    .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build();
        }
        transformer = new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(encoder)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult result) {
                        if (run != generation) return;
                        transformer = null;output = null;releaseLease();
                        if (!destination.isFile() || destination.length() <= 0 || destination.length() > VideoPublishLimits.EXPORT_BYTES) {
                            destination.delete();callback.failed("Video xuất vẫn quá lớn (mục tiêu dưới 40 MB). Hãy chọn đoạn ngắn hơn rồi xuất lại.");return;
                        }
                        callback.completed(destination);
                    }
                    @Override public void onError(Composition composition, ExportResult result, ExportException error) {
                        fail(run,callback,"Không thể xuất đoạn video trên thiết bị này. Hãy kiểm tra dung lượng, codec hoặc thử lại. Bản nguồn vẫn được giữ.");
                    }
                }).build();
        try { transformer.start(composition, destination.getAbsolutePath()); }
        catch (RuntimeException error) { fail(run,callback,"Không thể bắt đầu xuất video. Chỉnh sửa của bạn vẫn được giữ."); }
        } catch (Exception error) {
            fail(run,callback,error instanceof IOException ? error.getMessage() : "Không chuẩn bị được bản xuất Reel. Bản nguồn vẫn được giữ.");
        }
    }
    public int progress() {
        return transformer != null && transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE
                ? progress.progress : -1;
    }
    public void cancel() {
        generation++;
        Transformer old = transformer;transformer = null;
        try { if (old != null) old.cancel(); }
        catch (RuntimeException ignored) { /* Cleanup must also run after a codec failure. */ }
        finally { if (output != null) { output.delete();output = null; } releaseLease(); }
    }
    private void releaseLease() { if (musicLease != null) { musicLease.close();musicLease = null; } }
    private void fail(long run, Callback callback, String message) {
        if (run != generation) return;
        cancel();callback.failed(message);
    }
}
