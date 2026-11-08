package com.example.meetomxh.music;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.android.material.chip.Chip;
import com.example.meetomxh.R;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import java.io.File;
import java.util.*;

/** Meeto Library picker; the activity/intent name is retained for existing hosts. */
public final class OpenverseMusicPickerActivity extends AppCompatActivity {
    public static final String EXTRA_TRACK_JSON="openverse_track_json";
    private MusicLibraryRepository repository;
    private PickerState retained;
    private EditText query; private LinearLayout rows; private TextView status;
    private ProgressBar progress; private Button retry; private MusicMiniPlayer mini;
    private Subscription catalogJob,audioJob;
    private long epoch; private int catalogVersion,audioVersion;
    private boolean foreground,disposed,initialised,refreshNeeded=true,catalogLoading;
    private String downloading;
    private final Map<String,String> errors=new HashMap<>();
    private Chip all,cached;
    private final Runnable identityChanged=()->{
        if(SessionManager.getInstance().generation()!=epoch){cancelJobs();mini.close();setResult(RESULT_CANCELED);finish();}
    };
    public static final class PickerState extends ViewModel {
        MusicLibraryRepository.Catalog catalog; String query=""; boolean cachedOnly;
        OpenverseTrack preview; long position,epoch=-1; boolean previewOpen;
    }
    @Override protected void onCreate(@Nullable Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_openverse_music_picker);
        epoch=SessionManager.getInstance().generation();repository=new MusicLibraryRepository(this);
        retained=new ViewModelProvider(this).get(PickerState.class);
        if(retained.epoch!=epoch){retained.catalog=null;retained.preview=null;retained.previewOpen=false;}
        retained.epoch=epoch;
        View root=findViewById(R.id.music_picker_root);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());
            v.setPadding(dp(16)+b.left,b.top,dp(16)+b.right,b.bottom);return insets;
        });
        query=findViewById(R.id.music_picker_query);rows=findViewById(R.id.music_picker_rows);
        status=findViewById(R.id.music_picker_status);progress=findViewById(R.id.music_picker_progress);
        retry=findViewById(R.id.music_picker_more);mini=findViewById(R.id.music_picker_mini);
        mini.setOnClosed(()->{retained.previewOpen=false;render();});
        mini.setOnError(status::setText);
        findViewById(R.id.music_picker_back).setOnClickListener(v->finish());
        LinearLayout categories=findViewById(R.id.music_picker_categories);
        all=chip("Tất cả",()->{retained.cachedOnly=false;render();});
        cached=chip("Đã tải",()->{retained.cachedOnly=true;render();});
        categories.addView(all);categories.addView(cached);
        if(state!=null){
            retained.query=state.getString("query",retained.query);retained.cachedOnly=state.getBoolean("cached_only",retained.cachedOnly);
            if(state.getLong("epoch",-1)==epoch&&retained.preview==null)try{
                retained.preview=OpenverseTrack.fromDraft(new org.json.JSONObject(state.getString("preview","")));
                retained.position=state.getLong("position");retained.previewOpen=state.getBoolean("preview_open");
            }catch(Exception ignored){}
        }
        query.setText(retained.query);
        query.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){retained.query=s.toString();render();}
            public void afterTextChanged(android.text.Editable s){}
        });
        query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){hideKeyboard();render();return true;}return false;});
        retry.setText("Tải lại thư viện");retry.setOnClickListener(v->refresh());
        SessionManager.getInstance().identityListeners.add(identityChanged);render();
    }
    private Chip chip(String label,Runnable action){
        Chip chip=new Chip(this);chip.setText(label);chip.setCheckable(true);
        chip.setChipBackgroundColorResource(R.color.music_chip_background);
        chip.setTextColor(getResources().getColorStateList(R.color.music_chip_text,getTheme()));
        chip.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMarginEnd(dp(8));chip.setLayoutParams(lp);return chip;
    }
    private void loadSaved(){
        int version=++catalogVersion;catalogLoading=true;updateProgress();
        catalogJob=Api.run(()->repository.cached(epoch),new Api.Callback<MusicLibraryRepository.Catalog>(){
            public void success(MusicLibraryRepository.Catalog result){
                if(!active()||version!=catalogVersion)return;
                if(result!=null){retained.catalog=result;render();status.setText("Danh sách đã lưu · Đang cập nhật thư viện…");}
                refresh();
            }
            public void error(String message){if(active()&&version==catalogVersion)refresh();}
        });
    }
    private void refresh(){
        if(!active())return;
        if(catalogJob!=null)catalogJob.remove();
        int version=++catalogVersion;catalogLoading=true;refreshNeeded=false;updateProgress();
        retry.setEnabled(false);retry.setVisibility(View.VISIBLE);
        if(retained.catalog==null)status.setText("Đang tải thư viện nhạc Meeto…");
        catalogJob=Api.run(()->repository.load(epoch),new Api.Callback<MusicLibraryRepository.Catalog>(){
            public void success(MusicLibraryRepository.Catalog result){
                if(!active()||version!=catalogVersion)return;
                catalogLoading=false;retained.catalog=result;
                if(retained.preview!=null&&!contains(retained.preview.library))mini.close();
                updateProgress();retry.setEnabled(true);render();
                status.setText(result.tracks.isEmpty()?"Thư viện hiện chưa có bài nhạc được bật."
                        :(result.offline?"Offline · Nghe hoặc chọn các bài đã tải.":"Thư viện Meeto · "+result.tracks.size()+" bài nhạc"));
                restorePreview();
            }
            public void error(String message){
                if(!active()||version!=catalogVersion)return;
                catalogLoading=false;retained.catalog=null;mini.close();render();updateProgress();retry.setEnabled(true);
                status.setText(message+" Chạm Tải lại thư viện để thử lại.");
            }
        });
    }
    private boolean contains(MusicLibraryTrack track){
        if(track==null||retained.catalog==null)return false;
        for(MusicLibraryTrack row:retained.catalog.tracks)if(key(row).equals(key(track)))return true;
        return false;
    }
    private void render(){
        if(rows==null||retained==null)return;rows.removeAllViews();
        all.setChecked(!retained.cachedOnly);cached.setChecked(retained.cachedOnly);
        if(retained.catalog==null)return;
        String term=retained.query.trim().toLowerCase(Locale.ROOT);int shown=0;
        for(MusicLibraryTrack track:retained.catalog.tracks){
            String id=key(track);boolean ready=retained.catalog.cached.contains(id);
            if(retained.cachedOnly&&!ready)continue;
            if(!(track.title+" "+track.artist).toLowerCase(Locale.ROOT).contains(term))continue;
            shown++;
            View row=getLayoutInflater().inflate(R.layout.item_openverse_music,rows,false);
            ((TextView)row.findViewById(R.id.music_item_title)).setText(track.title);
            ((TextView)row.findViewById(R.id.music_item_creator)).setText(track.artist+" · "+format(track.durationMs)+" · CC0");
            TextView state=row.findViewById(R.id.music_item_status);
            state.setText(id.equals(downloading)?"Đang tải và kiểm tra bài nhạc…":errors.containsKey(id)?errors.get(id):ready?"Đã tải · có thể nghe offline":"Chưa tải");
            Button preview=row.findViewById(R.id.music_item_preview),choose=row.findViewById(R.id.music_item_choose);
            preview.setText(mini.isOpen()&&retained.preview!=null&&key(retained.preview.library).equals(id)?"Phát / tạm dừng":"Nghe thử");
            preview.setEnabled(!id.equals(downloading));choose.setEnabled(!id.equals(downloading));
            preview.setOnClickListener(v->{if(mini.isOpen()&&retained.preview!=null&&key(retained.preview.library).equals(id))mini.toggle();else select(track,false);});
            choose.setOnClickListener(v->select(track,true));rows.addView(row);
        }
        if(shown==0){TextView empty=new TextView(this);empty.setText(retained.cachedOnly?"Chưa có bài đã tải khớp tìm kiếm.":"Không có bài nhạc khớp tìm kiếm.");empty.setPadding(0,dp(24),0,dp(24));empty.setTextColor(getColor(R.color.text_secondary));rows.addView(empty);}
    }
    private void select(MusicLibraryTrack track,boolean choose){
        if(!active())return;hideKeyboard();mini.close();errors.remove(key(track));
        if(audioJob!=null)audioJob.remove();
        int version=++audioVersion;downloading=key(track);updateProgress();render();
        status.setText("Đang tải / kiểm tra "+track.title+"…");
        audioJob=Api.run(()->repository.audio(track,epoch),new Api.Callback<MusicLibraryRepository.Audio>(){
            public void success(MusicLibraryRepository.Audio audio){
                try{
                    if(!active()||version!=audioVersion)return;
                    downloading=null;updateProgress();
                    if(!contains(track)){render();status.setText("Bài nhạc không còn trong thư viện hiện tại. Hãy tải lại danh sách.");return;}
                    retained.catalog.cached.add(key(track));
                    MusicLibraryCache.protect(OpenverseMusicPickerActivity.this,audio.track);
                    if(choose){
                        setResult(RESULT_OK,new Intent().putExtra(EXTRA_TRACK_JSON,audio.track.draftJson().toString()));finish();
                    // audio() already verified and leased this immutable file on its worker.
                    }else openVerifiedPreview(audio.track,0,true);
                    render();
                }catch(Exception invalid){status.setText("Không đọc được bản nhạc đã tải. Hãy chọn lại.");}
                finally{audio.close();}
            }
            public void error(String message){
                if(!active()||version!=audioVersion)return;
                downloading=null;errors.put(key(track),"Lỗi · "+message);updateProgress();render();status.setText(message);
            }
        },MusicLibraryRepository.Audio::close);
    }
    private void openPreview(OpenverseTrack track,long position,boolean autoplay){
        mini.close();
        if(!OpenverseMusicCache.isManaged(this,track)){status.setText("Cache nhạc không hợp lệ. Hãy chọn lại để tải bài nhạc.");return;}
        openVerifiedPreview(track,position,autoplay);
    }
    private void openVerifiedPreview(OpenverseTrack track,long position,boolean autoplay){
        mini.close();
        ExoPlayer player=null;boolean handedToMini=false;
        try{
            player=new ExoPlayer.Builder(this).build();
            player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);
            player.setHandleAudioBecomingNoisy(true);
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(track.localPath))));player.prepare();
            retained.preview=track;retained.previewOpen=true;retained.position=position;
            handedToMini=true;
            mini.show(track,MusicMiniPlayer.Mode.TRACK,player,track.durationMs,position,autoplay,()->select(track.library,true));
            retained.previewOpen=mini.isOpen();if(retained.previewOpen)status.setText("Nghe thử · "+track.title);
        }catch(RuntimeException invalid){
            MusicDiagnostics.failure("picker_preview",invalid);
            if(handedToMini)mini.close();
            else if(player!=null)try{player.release();}catch(RuntimeException releaseError){MusicDiagnostics.failure("picker_preview_release",releaseError);}
            retained.previewOpen=false;status.setText("Không phát được bài nhạc này. Hãy nghe thử lại hoặc chọn bài khác.");
        }
    }
    private void restorePreview(){if(retained.previewOpen&&retained.preview!=null&&!mini.isOpen()&&contains(retained.preview.library))openPreview(retained.preview,retained.position,false);}
    private void updateProgress(){progress.setVisibility(catalogLoading||downloading!=null?View.VISIBLE:View.INVISIBLE);}
    private boolean active(){return foreground&&!disposed&&SessionManager.getInstance().generation()==epoch;}
    private void cancelJobs(){catalogVersion++;audioVersion++;if(catalogJob!=null)catalogJob.remove();if(audioJob!=null)audioJob.remove();}
    private String key(MusicLibraryTrack track){return track==null?"":MusicLibraryCache.key(track);}
    private void hideKeyboard(){query.clearFocus();android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String format(long ms){return String.format(Locale.ROOT,"%d:%02d",ms/60000,ms/1000%60);}
    @Override protected void onStart(){
        super.onStart();foreground=true;
        if(SessionManager.getInstance().generation()!=epoch){identityChanged.run();return;}
        if(!initialised){initialised=true;if(retained.catalog!=null)render();loadSaved();}
        else if(refreshNeeded)refresh();
        render();updateProgress();retry.setEnabled(!catalogLoading);
        restorePreview();
    }
    @Override protected void onStop(){
        foreground=false;refreshNeeded=catalogLoading;cancelJobs();
        boolean open=mini.isOpen();long position=mini.positionMs();mini.close();retained.previewOpen=open;retained.position=position;
        downloading=null;catalogLoading=false;super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("query",retained.query);out.putBoolean("cached_only",retained.cachedOnly);out.putLong("epoch",epoch);
        out.putBoolean("preview_open",mini.isOpen()||retained.previewOpen);out.putLong("position",mini.isOpen()?mini.positionMs():retained.position);
        try{if(retained.preview!=null)out.putString("preview",retained.preview.draftJson().toString());}catch(Exception ignored){}
        super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy(){disposed=true;cancelJobs();mini.close();SessionManager.getInstance().identityListeners.remove(identityChanged);super.onDestroy();}
}
