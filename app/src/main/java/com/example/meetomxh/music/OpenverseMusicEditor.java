package com.example.meetomxh.music;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import com.example.meetomxh.R;
import com.example.meetomxh.supabase.SessionManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.io.File;

/** One editor for Reel, Create Post video and both Story media types. */
public final class OpenverseMusicEditor {
    public interface Callback {
        void saved(OpenverseTrack track);
        void removed();
        void change();
        default void closed() {}
    }
    /** Video hosts build the same mix as their main preview, without changing export. */
    public interface PreviewFactory { Player create(OpenverseTrack draft) throws Exception; }
    public static final class DraftState extends ViewModel {
        OpenverseTrack track;
        String key;
        long epoch;
        long session;
        boolean open, showing;
    }
    private OpenverseMusicEditor() {}
    private static DraftState state(AppCompatActivity activity) {
        return new ViewModelProvider(activity).get(DraftState.class);
    }
    public static boolean isShowing(AppCompatActivity activity) { return state(activity).showing; }
    public static OpenverseTrack pending(AppCompatActivity activity, String key) {
        DraftState state = state(activity);
        if (state.epoch != SessionManager.getInstance().generation() || !key.equals(state.key)) {
            state.open = false; state.track = null; return null;
        }
        return state.open && !state.showing && state.track != null ? state.track.copy() : null;
    }
    public static void saveState(AppCompatActivity activity, Bundle out) {
        DraftState state = state(activity);
        if (!state.open || state.track == null) return;
        try {
            out.putString("music_editor_working", state.track.draftJson().toString());
            out.putString("music_editor_key", state.key); out.putLong("music_editor_epoch", state.epoch);
        } catch (Exception ignored) {}
    }
    public static void restoreState(AppCompatActivity activity, Bundle saved) {
        if (saved == null || !saved.containsKey("music_editor_working")) return;
        DraftState state = state(activity);
        if (state.open || saved.getLong("music_editor_epoch", -1) != SessionManager.getInstance().generation()) return;
        try {
            state.track = OpenverseTrack.fromDraft(new org.json.JSONObject(saved.getString("music_editor_working")));
            state.key = saved.getString("music_editor_key"); state.epoch = saved.getLong("music_editor_epoch");
            state.open = state.track != null;
        } catch (Exception ignored) {}
    }

    public static void show(AppCompatActivity activity, OpenverseTrack selected, long outputDurationMs,
                            boolean sourceAudio, boolean stillStory, String draftKey,
                            PreviewFactory previewFactory, Callback callback) {
        DraftState state = state(activity);
        if (state.showing) return;
        OpenverseTrack working = selected.copy();
        MusicLibraryCache.protect(activity,working);
        state.track = working; state.key = draftKey; state.open = true; state.showing = true;
        long session = ++state.session;
        state.epoch = SessionManager.getInstance().generation();
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View view = activity.getLayoutInflater().inflate(R.layout.bottom_sheet_music_editor, null, false);
        dialog.setContentView(view);
        TextView title = view.findViewById(R.id.music_edit_title), creator = view.findViewById(R.id.music_edit_creator);
        TextView note = view.findViewById(R.id.music_edit_preview_note);
        TextView startLabel = view.findViewById(R.id.music_edit_start_label), volumeLabel = view.findViewById(R.id.music_edit_volume_label);
        TextView sourceLabel = view.findViewById(R.id.music_edit_source_label), durationLabel = view.findViewById(R.id.music_edit_duration_label);
        SeekBar start = view.findViewById(R.id.music_edit_start), volume = view.findViewById(R.id.music_edit_volume);
        SeekBar source = view.findViewById(R.id.music_edit_source), duration = view.findViewById(R.id.music_edit_duration);
        Button preview = view.findViewById(R.id.music_edit_preview);
        MusicMiniPlayer mini = view.findViewById(R.id.music_edit_mini);
        final boolean[] suspending = {false}, completed = {false}, keepDraft = {false};
        title.setText(working.title);
        creator.setText((working.creator.isEmpty() ? "Không rõ tác giả" : working.creator) + " · " + (working.library==null?working.source:"Thư viện Meeto") + " · CC0");
        volume.setProgress(working.musicVolume); source.setProgress(working.sourceVolume);
        duration.setProgress(Math.max(0, Math.min(55, working.stillDurationMs / 1000 - 5)));
        java.util.function.LongSupplier target = () -> stillStory ? (duration.getProgress() + 5L) * 1000L : outputDurationMs;
        start.setMax((int)Math.max(0, working.durationMs - (working.library!=null?1:target.getAsLong())));
        start.setProgress(Math.min(start.getMax(), working.startMs));

        Runnable sync = () -> {
            working.startMs = start.getProgress(); working.musicVolume = volume.getProgress();
            if (!stillStory) working.sourceVolume = source.getProgress();
            else { working.stillDurationMs = (duration.getProgress() + 5) * 1000; working.sourceVolume=0; }
            working.selectedDurationMs = 0;
            startLabel.setText("Bắt đầu nhạc: " + format(working.startMs));
            volumeLabel.setText("Âm lượng nhạc: " + working.musicVolume + "%");
            sourceLabel.setText(sourceAudio ? "Âm lượng video gốc: " + working.sourceVolume + "%" : "Video không có âm thanh gốc");
            durationLabel.setText("Thời lượng Story ảnh: " + (duration.getProgress() + 5) + " giây");
            long used = working.library!=null?target.getAsLong():Math.min(target.getAsLong(), Math.max(0, working.durationMs - working.startMs));
            note.setText("Đoạn dùng: " + format(working.startMs) + " · " + format(used)
                    + (stillStory ? ". Nghe thử đúng đoạn nhạc và âm lượng đã chỉnh." : ". Nghe thử bản phối với âm lượng nhạc và video gốc đã chỉnh.")
                    + (working.library!=null&&used>working.durationMs-working.startMs?" Nhạc tự lặp từ đầu khi hết bài.":"")
                    + (target.getAsLong()-used>300?" Bài nhạc không đủ dài; hãy đổi bài hoặc rút ngắn media.":""));
        };
        SeekBar.OnSeekBarChangeListener controls = new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (bar == duration) start.setMax((int)Math.max(0, working.durationMs - (working.library!=null?1:target.getAsLong())));
                sync.run(); if (user) mini.close();
            }
            public void onStartTrackingTouch(SeekBar bar) { mini.close(); }
            public void onStopTrackingTouch(SeekBar bar) { sync.run(); }
        };
        start.setOnSeekBarChangeListener(controls); volume.setOnSeekBarChangeListener(controls);
        source.setOnSeekBarChangeListener(controls); duration.setOnSeekBarChangeListener(controls);
        source.setVisibility(stillStory ? View.GONE : View.VISIBLE);
        sourceLabel.setVisibility(stillStory ? View.GONE : View.VISIBLE); source.setEnabled(sourceAudio);
        duration.setVisibility(stillStory ? View.VISIBLE : View.GONE);
        durationLabel.setVisibility(stillStory ? View.VISIBLE : View.GONE);
        sync.run(); mini.setOnError(note::setText);
        com.example.meetomxh.music.MusicLibraryCache.Lease acquired=null;
        if(working.library!=null)try{acquired=new MusicLibraryCache(activity).acquire(working.library);}catch(java.io.IOException error){MusicDiagnostics.failure("editor_cache_lease",error);}
        final MusicLibraryCache.Lease editingLease=acquired;
        if(working.library!=null&&editingLease==null){android.util.Log.w("MeetoMusic","stage=editor_cache_lease available=false");note.setText("Tệp nhạc không còn hợp lệ. Hãy đổi bài để tải lại.");preview.setEnabled(false);view.findViewById(R.id.music_edit_save).setEnabled(false);}
        preview.setOnClickListener(v -> {
            if(mini.isOpen()){mini.toggle();return;}
            sync.run(); mini.close();
            long used = working.library!=null?target.getAsLong():Math.min(target.getAsLong(), working.durationMs - working.startMs);
            if (used < 1000 || target.getAsLong() - used > 300) {
                note.setText("Bài nhạc không đủ dài. Hãy đổi bài hoặc giảm thời lượng Story/cắt video rồi nghe thử."); return;
            }
            if (!OpenverseMusicCache.isManaged(activity, working)) {
                android.util.Log.w("MeetoMusic","stage=editor_cache rejected=true library="+(working.library!=null));
                note.setText("Tệp nhạc đã hết khỏi cache. Hãy đổi bài hoặc bỏ nhạc."); return;
            }
            Player prepared = null;
            String step="editor_preview_factory";
            boolean handedToMini=false;
            try {
                android.util.Log.i("MeetoMusic","stage=editor_preview_request library="+(working.library!=null)+" sourceAudio="+sourceAudio
                        +" still="+stillStory+" offsetMs="+working.startMs+" usedMs="+used+" musicVolume="+working.musicVolume+" sourceVolume="+working.sourceVolume);
                if (!stillStory && previewFactory != null) prepared = previewFactory.create(working.copy());
                else if(working.library!=null){
                    androidx.media3.transformer.CompositionPlayer audio=MusicComposition.previewPlayer(activity);prepared=audio;
                    audio.setComposition(MusicComposition.audio(working.copy(),used));audio.prepare();
                }
                else {
                    MediaItem item = new MediaItem.Builder().setUri(Uri.fromFile(new File(working.localPath)))
                            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(working.startMs).setEndPositionMs(working.startMs + used).build()).build();
                    ExoPlayer audio = new ExoPlayer.Builder(activity).build(); prepared = audio;
                    audio.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
                    audio.setMediaItem(item); audio.setVolume(working.musicVolume / 100f); audio.prepare();
                }
                step="editor_mini_attach_seek_play";
                handedToMini=true;
                mini.show(working, MusicMiniPlayer.Mode.SEGMENT, prepared, used, 0, true, () -> {
                    mini.close(); note.setText("Đã xác nhận đoạn nghe thử. Nhấn Lưu để áp dụng nhạc vào bản nháp.");
                });
            } catch (Exception failure) {
                MusicDiagnostics.failure(step,failure);
                // show() may have taken ownership before seek/play threw. Release exactly once.
                if(handedToMini)mini.close();
                else if(prepared!=null)try{prepared.release();}catch(RuntimeException releaseError){MusicDiagnostics.failure("editor_release_failed_preview",releaseError);}
                note.setText("Không nghe thử được bản phối khi "+(handedToMini?"bắt đầu phát":"chuẩn bị audio/video")
                        +" ("+failure.getClass().getSimpleName()+"). Bản chỉnh sửa vẫn được giữ.");
            }
        });
        Runnable finishEditing = () -> {
            completed[0] = true; mini.close(); state.showing = false; state.open = keepDraft[0]; dialog.dismiss();
        };
        view.findViewById(R.id.music_edit_remove).setOnClickListener(v -> {
            finishEditing.run(); callback.removed();
        });
        view.findViewById(R.id.music_edit_change).setOnClickListener(v -> {
            sync.run(); keepDraft[0] = true; finishEditing.run(); callback.change();
        });
        view.findViewById(R.id.music_edit_save).setOnClickListener(v -> {
            sync.run(); long used = working.library!=null?target.getAsLong():Math.min(target.getAsLong(), working.durationMs - working.startMs);
            if (used < 1000 || target.getAsLong() - used > 300) {
                note.setText("Bài nhạc không đủ dài. Hãy đổi bài hoặc giảm thời lượng Story/cắt video rồi lưu."); return;
            }
            finishEditing.run(); callback.saved(working.copy());
        });
        LifecycleEventObserver observer = (owner, event) -> {
            if (event == Lifecycle.Event.ON_STOP && !completed[0]) { sync.run(); suspending[0] = true; mini.close(); state.showing = false; dialog.dismiss(); }
        };
        activity.getLifecycle().addObserver(observer);
        dialog.setOnDismissListener(d -> {
            mini.close();
            if(editingLease!=null)editingLease.close();
            if (state.session == session) { state.showing = false; state.open = suspending[0] || keepDraft[0]; }
            activity.getLifecycle().removeObserver(observer);
            if (!suspending[0] && !completed[0] && !activity.isFinishing()
                    && state.epoch == SessionManager.getInstance().generation()) callback.closed();
        });
        dialog.setOnShowListener(d -> {
            dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            androidx.core.view.ViewCompat.requestApplyInsets(view);
        });
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bars.bottom); return insets;
        });
        try{dialog.show();}catch(RuntimeException unavailable){
            mini.close();if(editingLease!=null)editingLease.close();activity.getLifecycle().removeObserver(observer);
            state.showing=false;state.open=false;callback.closed();
        }
    }
    private static String format(long ms) { return String.format(java.util.Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1000 % 60); }
}
