package com.example.meetomxh.music;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.google.android.material.chip.Chip;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import com.example.meetomxh.R;
import com.example.meetomxh.supabase.SessionManager;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared real Openverse picker for Story and Reel editing. */
public final class OpenverseMusicPickerActivity extends AppCompatActivity {
    public static final String EXTRA_TRACK_JSON="openverse_track_json";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private OpenverseMusicClient.CallHolder call=new OpenverseMusicClient.CallHolder();
    private final List<OpenverseTrack> results=new ArrayList<>();
    private long epoch;
    private final Runnable identityChanged=()->{if(SessionManager.getInstance().generation()!=epoch){OpenverseMusicClient.clearSearchCache();call.cancel();releasePlayer();setResult(RESULT_CANCELED);finish();}};
    private EditText query;private LinearLayout rows,categories;private TextView status;private ProgressBar progress;private Button more;
    private MusicMiniPlayer miniPlayer;private String queryValue="music";private int page=0,pageCount=1,requestVersion;
    private boolean loading,loadingTrack,disposed,foreground;private Runnable debounce,retryEnable;private long retryAt;private int failedPage=1;
    private PickerState retained;
    public static final class PickerState extends ViewModel {final List<OpenverseTrack> tracks=new ArrayList<>();String query="music";int page,pages=1;boolean loaded;long retryAt;OpenverseTrack previewTrack;long previewPosition;boolean previewOpen;long epoch=-1;}

    @Override protected void onCreate(@Nullable Bundle state){super.onCreate(state);setContentView(R.layout.activity_openverse_music_picker);
        epoch=SessionManager.getInstance().generation();SessionManager.getInstance().identityListeners.add(identityChanged);OpenverseMusicClient.initialize(this);
        retained=new ViewModelProvider(this).get(PickerState.class);foreground=true;
        if(retained.epoch!=-1&&retained.epoch!=epoch){retained.tracks.clear();retained.loaded=false;retained.previewTrack=null;retained.previewOpen=false;}
        retained.epoch=epoch;
        retryAt=Math.max(retained.retryAt,System.currentTimeMillis()+OpenverseMusicClient.retryAfterMs());
        View root=findViewById(R.id.music_picker_root);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());v.setPadding(dp(16)+b.left,b.top,dp(16)+b.right,b.bottom);return insets;});
        query=findViewById(R.id.music_picker_query);rows=findViewById(R.id.music_picker_rows);categories=findViewById(R.id.music_picker_categories);
        status=findViewById(R.id.music_picker_status);progress=findViewById(R.id.music_picker_progress);more=findViewById(R.id.music_picker_more);
        miniPlayer=findViewById(R.id.music_picker_mini);
        miniPlayer.setOnClosed(()->retained.previewOpen=false);
        miniPlayer.setOnError(message->status.setText(message));
        if(state!=null&&state.getLong("preview_epoch",-1)==epoch&&retained.previewTrack==null)try{retained.previewTrack=OpenverseTrack.fromDraft(new org.json.JSONObject(state.getString("preview_track","")));retained.previewPosition=state.getLong("preview_position");retained.previewOpen=state.getBoolean("preview_open");}catch(Exception ignored){}
        findViewById(R.id.music_picker_back).setOnClickListener(v->finish());
        String[] labels={"Chill","Piano","Vui vẻ","Nhẹ nhàng"};String[] terms={"chill music","piano","upbeat music","soft music"};
        for(int i=0;i<labels.length;i++){final String term=terms[i];Chip chip=new Chip(this);chip.setText(labels[i]);chip.setTag(term);chip.setCheckable(true);chip.setChipBackgroundColorResource(R.color.music_chip_background);chip.setTextColor(getResources().getColorStateList(R.color.music_chip_text,getTheme()));chip.setChipStrokeColorResource(R.color.outline_color);chip.setChipStrokeWidth(dp(1));chip.setOnClickListener(v->{query.setText(term);query.setSelection(term.length());beginSearch(term);});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMarginEnd(dp(8));categories.addView(chip,lp);}
        queryValue=retained.loaded?retained.query:state==null?"music":state.getString("query","music");
        query.setText(queryValue);query.setSelection(query.length());
        query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){
            String value=s.toString().trim();if(value.equals(queryValue))return;queryValue=value;if(debounce!=null)main.removeCallbacks(debounce);
            requestVersion++;call.cancel();call=new OpenverseMusicClient.CallHolder(requestVersion);releasePlayer();loading=false;progress.setVisibility(View.INVISIBLE);results.clear();render();more.setVisibility(View.GONE);retained.loaded=false;updateChips();
            debounce=()->beginSearch(value);main.postDelayed(debounce,500);
        }public void afterTextChanged(android.text.Editable s){}});
        query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){if(debounce!=null)main.removeCallbacks(debounce);beginSearch(query.getText().toString().trim());return true;}return false;});
        more.setOnClickListener(v->loadPage(failedPage>0?failedPage:page+1));
        if(debounce!=null)main.removeCallbacks(debounce);
        if(retained.loaded){results.addAll(retained.tracks);page=retained.page;pageCount=retained.pages;failedPage=0;render();status.setText(results.isEmpty()?"Không có kết quả CC0 phù hợp. Thử Music hoặc Piano.":"Tìm thấy "+results.size()+" bản nhạc CC0.");more.setVisibility(page<pageCount?View.VISIBLE:View.GONE);updateChips();}else beginSearch(queryValue);
        if(retryAt>System.currentTimeMillis())applyBackoff(new OpenverseMusicClient.RateLimitException("",retryAt-System.currentTimeMillis()));
    }

    private void beginSearch(String value){if(disposed)return;if(debounce!=null){main.removeCallbacks(debounce);debounce=null;}call.cancel();releasePlayer();loading=false;queryValue=value.trim().isEmpty()?"music":value.trim();requestVersion++;page=0;pageCount=1;results.clear();retained.loaded=false;rows.removeAllViews();updateChips();loadPage(1);}
    private void updateChips(){for(int i=0;i<categories.getChildCount();i++){Chip chip=(Chip)categories.getChildAt(i);chip.setChecked(queryValue.equals(chip.getTag()));}}
    private void loadPage(int requestedPage){if(disposed||loading||requestedPage<1||requestedPage>20||(requestedPage>1&&requestedPage>pageCount))return;if(System.currentTimeMillis()<retryAt){failedPage=requestedPage;more.setVisibility(View.VISIBLE);more.setText("Thử lại");status.setText("Openverse đang giới hạn yêu cầu. Hãy đợi rồi thử lại.");return;}loading=true;int version=++requestVersion;String search=queryValue;
        call.cancel();call=new OpenverseMusicClient.CallHolder(version);OpenverseMusicClient.CallHolder request=call;
        loadingTrack=false;
        progress.setVisibility(View.VISIBLE);more.setEnabled(false);status.setText("Đang tìm nhạc CC0…");
        work.execute(()->{try{OpenverseMusicClient.Page found=OpenverseMusicClient.search(search,requestedPage,request);main.post(()->{
            if(!active(version))return;Set<String> excluded=exclusions();Set<String> seen=new HashSet<>();for(OpenverseTrack t:results)seen.add(t.id);for(OpenverseTrack track:found.tracks)if(seen.add(track.id)&&!excluded.contains("track:"+track.id)&&!excluded.contains("source:"+track.source.toLowerCase(java.util.Locale.ROOT)))results.add(track);
            page=found.page;pageCount=found.pages;failedPage=0;loading=false;progress.setVisibility(View.INVISIBLE);more.setEnabled(true);more.setText("Xem thêm");more.setVisibility(page<pageCount&&page<20?View.VISIBLE:View.GONE);
            retained.tracks.clear();retained.tracks.addAll(results);retained.query=search;retained.page=page;retained.pages=pageCount;retained.loaded=true;
            status.setText(results.isEmpty()?"Không có kết quả CC0 phù hợp. Thử Music, Piano hoặc từ khóa khác.":"Tìm thấy "+results.size()+" bài nhạc CC0.");render();
        });}catch(Exception error){main.post(()->{if(!active(version))return;failedPage=requestedPage;loading=false;progress.setVisibility(View.INVISIBLE);more.setEnabled(true);more.setVisibility(View.VISIBLE);more.setText("Thử lại");status.setText(error.getMessage()==null?"Không kết nối được Openverse. Kiểm tra mạng rồi thử lại.":error.getMessage());applyBackoff(error);});}});
    }

    private boolean active(int version){return foreground&&!disposed&&version==requestVersion&&SessionManager.getInstance().generation()==epoch;}
    private void applyBackoff(Exception error){if(!(error instanceof OpenverseMusicClient.RateLimitException))return;long wait=((OpenverseMusicClient.RateLimitException)error).waitMs;retryAt=System.currentTimeMillis()+wait;retained.retryAt=retryAt;more.setEnabled(false);status.append(" Chờ khoảng "+Math.max(1,(wait+999)/1000)+" giây.");if(retryEnable!=null)main.removeCallbacks(retryEnable);retryEnable=()->{if(!disposed)more.setEnabled(true);};main.postDelayed(retryEnable,wait);}
    private void render(){rows.removeAllViews();for(OpenverseTrack track:results)rows.addView(row(track));}
    private View row(OpenverseTrack track){View card=getLayoutInflater().inflate(R.layout.item_openverse_music,rows,false);
        ((TextView)card.findViewById(R.id.music_item_title)).setText(track.title);
        ((TextView)card.findViewById(R.id.music_item_creator)).setText((track.creator.isEmpty()?"Không rõ tác giả":track.creator)+" · "+track.source+" · "+format(track.durationMs)+" · CC0");
        card.findViewById(R.id.music_item_preview).setOnClickListener(v->validateAndOpen(track,false));
        card.findViewById(R.id.music_item_choose).setOnClickListener(v->validateAndOpen(track,true));
        return card;
    }
    private void validateAndOpen(OpenverseTrack track,boolean choose){if(System.currentTimeMillis()<retryAt)return;query.clearFocus();android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);releasePlayer();loading=true;loadingTrack=true;call.cancel();int version=++requestVersion;call=new OpenverseMusicClient.CallHolder(version);OpenverseMusicClient.CallHolder request=call;progress.setVisibility(View.VISIBLE);status.setText("Đang xác minh giấy phép và tải tạm bài nhạc…");
        work.execute(()->{try{OpenverseTrack verified=OpenverseMusicClient.downloadValidated(this,track,request,epoch);main.post(()->{
            if(!active(version))return;loading=false;more.setEnabled(true);progress.setVisibility(View.INVISIBLE);
            if(choose){try{Intent result=new Intent().putExtra(EXTRA_TRACK_JSON,verified.draftJson().toString());setResult(RESULT_OK,result);finish();}
                catch(Exception e){status.setText("Không lưu được thông tin bài nhạc. Hãy thử lại.");}return;}
            openPreview(verified,0,true);
        });}catch(Exception error){main.post(()->{if(!active(version))return;loading=false;more.setEnabled(true);progress.setVisibility(View.INVISIBLE);status.setText(error.getMessage()==null?"Không xác minh hoặc tải được bài nhạc. Hãy thử bài khác.":error.getMessage());applyBackoff(error);});}});
    }
    private Set<String> exclusions(){Set<String> out=new HashSet<>();for(String key:getSharedPreferences("openverse_music_exclusions_v1",MODE_PRIVATE).getAll().keySet())out.add(key);return out;}
    private void openPreview(OpenverseTrack track,long position,boolean autoplay){
        ExoPlayer player=new ExoPlayer.Builder(this).build();
        try{player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(track.localPath))));player.prepare();
            miniPlayer.show(track,MusicMiniPlayer.Mode.TRACK,player,track.durationMs,position,autoplay,()->validateAndOpen(track,true));
            retained.previewTrack=track;retained.previewPosition=position;retained.previewOpen=true;status.setText("Nghe thử · "+track.title);
        }catch(RuntimeException error){player.release();status.setText("Không thể nghe thử bài nhạc này.");}
    }
    private void restorePreview(){if(retained.previewOpen&&!miniPlayer.isOpen()&&retained.previewTrack!=null){OpenverseTrack t=retained.previewTrack;if(OpenverseMusicCache.isManaged(this,t.id,t.localPath))openPreview(t,retained.previewPosition,false);else retained.previewOpen=false;}}
    private void releasePlayer(){if(miniPlayer!=null)miniPlayer.close();}
    private void suspendPreview(){boolean open=miniPlayer.isOpen();long position=miniPlayer.positionMs();releasePlayer();retained.previewOpen=open;retained.previewPosition=position;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String format(long ms){long sec=ms/1000;return String.format(java.util.Locale.ROOT,"%d:%02d",sec/60,sec%60);}
    @Override protected void onSaveInstanceState(Bundle out){out.putLong("preview_epoch",epoch);out.putString("query",queryValue);out.putInt("page",page);out.putBoolean("preview_open",miniPlayer.isOpen()||retained.previewOpen);out.putLong("preview_position",miniPlayer.isOpen()?miniPlayer.positionMs():retained.previewPosition);try{out.putString("preview_track",retained.previewTrack==null?null:retained.previewTrack.draftJson().toString());}catch(Exception ignored){}super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){releasePlayer();super.onBackPressed();}
    @Override protected void onStart(){super.onStart();foreground=true;if(SessionManager.getInstance().generation()!=epoch)identityChanged.run();else restorePreview();}
    @Override protected void onStop(){foreground=false;requestVersion++;if(debounce!=null)main.removeCallbacks(debounce);call.cancel();suspendPreview();if(loading){loading=false;progress.setVisibility(View.INVISIBLE);failedPage=loadingTrack?0:page+1;more.setText(loadingTrack?"Xem thêm":"Thử lại");more.setVisibility(!loadingTrack||page<pageCount?View.VISIBLE:View.GONE);more.setEnabled(System.currentTimeMillis()>=retryAt);status.setText(loadingTrack?"Đã dừng tải nhạc. Chọn lại bài để tiếp tục.":"Đã tạm dừng yêu cầu. Chạm để thử lại.");}super.onStop();}
    @Override protected void onDestroy(){disposed=true;requestVersion++;if(debounce!=null)main.removeCallbacks(debounce);if(retryEnable!=null)main.removeCallbacks(retryEnable);call.cancel();releasePlayer();SessionManager.getInstance().identityListeners.remove(identityChanged);work.shutdownNow();super.onDestroy();}
}
