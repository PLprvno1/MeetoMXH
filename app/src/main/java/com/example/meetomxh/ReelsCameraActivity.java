package com.example.meetomxh;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** In-app vertical Reels camera. Recording is finalized before its cache file is handed off. */
public final class ReelsCameraActivity extends AppCompatActivity {
    private enum State { IDLE, PREPARING, RECORDING, PAUSED, FINALIZING, READY, ERROR }

    private final ReelsCameraController cameraController=new ReelsCameraController();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable durationTicker=new Runnable(){@Override public void run(){tickDuration();}};
    private PreviewView previewView;private TextView status,countdown,elapsed,fileSize;private android.widget.ImageButton flashButton,switchButton,micButton,pauseButton,cancelButton,galleryButton;private com.example.meetomxh.ui.ReelRecordButton recordButton;private Button finishButton;private View options,idleControls,activeControls,recordInfo;private ProgressBar progress;
    private ActivityResultLauncher<String> cameraPermission,microphonePermission;private ActivityResultLauncher<PickVisualMediaRequest> galleryPicker;
    private Camera camera;private VideoCapture<Recorder> capture;private Recording recording;private File outputFile;private ReelsCaptureSession captureSession;private State state=State.IDLE;
    private boolean frontCamera,microphoneEnabled,recordingHasAudio,flashEnabled,bindingRequested,transferred,destroying,cancelAfterFinalize,stopped,editorPending;private int selectedDurationSeconds=30,countdownSeconds;private long activeElapsedMs,segmentStartMs,recordingStartedAt,activeBeforeSegment;private int generation;

    @Override protected void onCreate(Bundle saved){super.onCreate(saved);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(R.layout.activity_reels_camera);bindViews();applyInsets();setupLaunchers();setupActions();
        selectedDurationSeconds=saved==null?30:saved.getInt("duration_seconds",30);countdownSeconds=saved==null?0:saved.getInt("countdown_seconds",0);frontCamera=saved!=null&&saved.getBoolean("front_camera",false);setDuration(selectedDurationSeconds);setCountdown(countdownSeconds);
        if(saved!=null){String path=saved.getString("ready_path");if(path!=null){File f=new File(path);if(f.isFile()&&f.length()>0){try{captureSession=new ReelsCaptureSession(path,"camera",saved.getLong("ready_duration",0),saved.getInt("ready_width",0),saved.getInt("ready_height",0),saved.getInt("ready_rotation",0),f.length(),saved.getBoolean("ready_audio",false),selectedDurationSeconds*1000L,true);showReady();}catch(Exception ignored){}}}}
        if(state!=State.READY)ensureCameraPermission();
    }
    private void bindViews(){previewView=findViewById(R.id.reels_camera_preview);status=findViewById(R.id.reels_camera_status);countdown=findViewById(R.id.reels_camera_countdown);elapsed=findViewById(R.id.reels_camera_elapsed);fileSize=findViewById(R.id.reels_camera_file_size);flashButton=findViewById(R.id.reels_camera_flash);switchButton=findViewById(R.id.reels_camera_switch);micButton=findViewById(R.id.reels_camera_microphone_state);recordButton=findViewById(R.id.reels_camera_record);pauseButton=findViewById(R.id.reels_camera_pause);finishButton=findViewById(R.id.reels_camera_finish);cancelButton=findViewById(R.id.reels_camera_cancel);galleryButton=findViewById(R.id.reels_camera_gallery);options=findViewById(R.id.reels_camera_options);idleControls=findViewById(R.id.reels_camera_idle_controls);activeControls=findViewById(R.id.reels_camera_active_controls);recordInfo=findViewById(R.id.reels_camera_recording_info);progress=findViewById(R.id.reels_camera_progress);}
    private void applyInsets(){View top=findViewById(R.id.reels_camera_top),bottom=findViewById(R.id.reels_camera_bottom);ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.reels_camera_root),(v,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());top.setPadding(top.getPaddingLeft(),bars.top+dp(4),top.getPaddingRight(),top.getPaddingBottom());bottom.setPadding(bottom.getPaddingLeft(),bottom.getPaddingTop(),bottom.getPaddingRight(),bars.bottom+dp(8));return insets;});}
    private void setupLaunchers(){cameraPermission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted)bindCamera();else{state=State.ERROR;status.setText("Cần quyền camera để quay Reel. Chạm dòng này để thử lại.");status.setOnClickListener(v->ensureCameraPermission());recordButton.setEnabled(false);}});microphonePermission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{microphoneEnabled=granted;updateMicButton();if(!granted)status.setText("Micro bị từ chối; bạn vẫn có thể quay video không âm thanh.");});galleryPicker=registerForActivityResult(new ActivityResultContracts.PickVisualMedia(),uri->{
        if(uri==null||isFinishing())return;
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
        Intent next=new Intent(this,CreateReelActivity.class).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        next.setClipData(android.content.ClipData.newRawUri("Selected Reel",uri));startActivity(next);finish();
    });}
    private void setupActions(){findViewById(R.id.reels_camera_back).setOnClickListener(v->requestClose());status.setOnClickListener(v->{if(state==State.ERROR)bindCamera();});flashButton.setOnClickListener(v->toggleFlash());switchButton.setOnClickListener(v->switchCamera());micButton.setOnClickListener(v->toggleMicrophone());galleryButton.setOnClickListener(v->{if(state==State.IDLE||state==State.ERROR)galleryPicker.launch(new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE).build());});recordButton.setOnClickListener(v->{if(state==State.IDLE||state==State.ERROR)beginCountdown();else if(state==State.PREPARING)cancelCountdown();else if(state==State.RECORDING||state==State.PAUSED)stopRecording(false);});pauseButton.setOnClickListener(v->togglePause());finishButton.setOnClickListener(v->stopRecording(false));cancelButton.setOnClickListener(v->confirmCancelRecording());
        findViewById(R.id.reels_camera_duration_15).setOnClickListener(v->selectDuration(15));findViewById(R.id.reels_camera_duration_30).setOnClickListener(v->selectDuration(30));findViewById(R.id.reels_camera_duration_60).setOnClickListener(v->selectDuration(60));findViewById(R.id.reels_camera_timer).setOnClickListener(v->showCountdownMenu());}
    private void ensureCameraPermission(){if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bindCamera();else cameraPermission.launch(Manifest.permission.CAMERA);}
    private void bindCamera(){if(isFinishing()||stopped||state==State.READY||state==State.FINALIZING||recording!=null||bindingRequested)return;bindingRequested=true;status.setText("Đang mở camera…");cameraController.bind(this,this,previewView,frontCamera,new ReelsCameraController.Callback(){public void ready(Camera value,VideoCapture<Recorder> video){if(isFinishing()||stopped||destroying||state==State.READY||state==State.FINALIZING)return;camera=value;capture=video;bindingRequested=false;state=State.IDLE;status.setText(frontCamera?"Camera trước · 9:16":"Camera sau · 9:16");recordButton.setEnabled(true);switchButton.setEnabled(true);updateFlashAvailability();updateMicButton();}public void error(String message){bindingRequested=false;camera=null;capture=null;state=State.ERROR;status.setText(message+" Chạm để thử lại.");recordButton.setEnabled(false);flashButton.setVisibility(View.GONE);}});}
    private void updateFlashAvailability(){boolean supported=camera!=null&&!frontCamera&&camera.getCameraInfo().hasFlashUnit();flashButton.setVisibility(supported?View.VISIBLE:View.GONE);flashButton.setEnabled(supported);if(!supported)flashEnabled=false;}
    private void toggleFlash(){if(camera==null||!camera.getCameraInfo().hasFlashUnit())return;flashEnabled=!flashEnabled;camera.getCameraControl().enableTorch(flashEnabled);flashButton.setAlpha(flashEnabled?1f:.65f);}
    private void switchCamera(){if(state==State.RECORDING||state==State.PAUSED||state==State.PREPARING||state==State.FINALIZING)return;frontCamera=!frontCamera;flashEnabled=false;bindingRequested=false;cameraController.release();camera=null;capture=null;bindCamera();}
    private void toggleMicrophone(){if(recording!=null&&!recordingHasAudio){Toast.makeText(this,"Bản quay này không có track âm thanh; hãy bật micro trước lần quay tiếp theo.",Toast.LENGTH_SHORT).show();return;}if(microphoneEnabled){microphoneEnabled=false;if(recording!=null&&(state==State.RECORDING||state==State.PAUSED))recording.mute(true);updateMicButton();return;}if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){microphoneEnabled=true;if(recording!=null&&(state==State.RECORDING||state==State.PAUSED))recording.mute(false);updateMicButton();}else microphonePermission.launch(Manifest.permission.RECORD_AUDIO);}
    private void updateMicButton(){micButton.setImageResource(microphoneEnabled?R.drawable.ic_reel_mic:R.drawable.ic_reel_mic_off);micButton.setAlpha(microphoneEnabled?1f:.72f);micButton.setContentDescription(microphoneEnabled?"Tắt microphone khi quay":"Bật microphone để thu âm");}
    private void selectDuration(int seconds){if(state!=State.IDLE&&state!=State.ERROR)return;selectedDurationSeconds=seconds;setDuration(seconds);}
    private void setDuration(int seconds){selectedDurationSeconds=seconds;int[] ids={R.id.reels_camera_duration_15,R.id.reels_camera_duration_30,R.id.reels_camera_duration_60};int[] values={15,30,60};for(int i=0;i<ids.length;i++){Button b=findViewById(ids[i]);boolean selected=values[i]==seconds;b.setAlpha(1f);b.setSelected(selected);b.setTextColor(selected?0xFF151518:0xFFFFFFFF);b.animate().scaleX(selected?1f:.94f).scaleY(selected?1f:.94f).setDuration(120).start();}}
    private void setCountdown(int seconds){if(state==State.RECORDING||state==State.PAUSED||state==State.PREPARING||state==State.FINALIZING)return;countdownSeconds=seconds;((TextView)findViewById(R.id.reels_camera_timer_label)).setText(seconds==0?"Hẹn giờ":seconds+"s");findViewById(R.id.reels_camera_timer).setContentDescription(seconds==0?"Hẹn giờ quay đang tắt":"Hẹn giờ quay "+seconds+" giây");}
    private void showCountdownMenu(){if(state!=State.IDLE&&state!=State.ERROR)return;new AlertDialog.Builder(this).setTitle("Hẹn giờ quay").setSingleChoiceItems(new String[]{"Tắt","3 giây","5 giây","10 giây"},countdownSeconds==3?1:countdownSeconds==5?2:countdownSeconds==10?3:0,(dialog,index)->{setCountdown(new int[]{0,3,5,10}[index]);dialog.dismiss();}).show();}
    private void beginCountdown(){if((state!=State.IDLE&&state!=State.ERROR)||capture==null||camera==null||recording!=null)return;state=State.IDLE;if(microphoneEnabled&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){microphoneEnabled=false;updateMicButton();}if(countdownSeconds==0){startRecording();return;}state=State.PREPARING;options.setVisibility(View.GONE);idleControls.setVisibility(View.GONE);recordInfo.setVisibility(View.GONE);countdown.setVisibility(View.VISIBLE);final int token=++generation;countdownStep(countdownSeconds,token);}
    private void cancelCountdown(){if(state!=State.PREPARING)return;generation++;main.removeCallbacksAndMessages(null);countdown.setVisibility(View.GONE);options.setVisibility(View.VISIBLE);idleControls.setVisibility(View.VISIBLE);state=State.IDLE;status.setText("Đã hủy hẹn giờ · chạm nút quay để bắt đầu");}
    private void countdownStep(int remaining,int token){if(token!=generation||isFinishing()||state!=State.PREPARING)return;if(remaining<=0){countdown.setVisibility(View.GONE);startRecording();return;}countdown.setText(String.valueOf(remaining));main.postDelayed(()->countdownStep(remaining-1,token),1000);}
    private void startRecording(){if(state!=State.IDLE&&state!=State.PREPARING)return;if(capture==null){state=State.ERROR;status.setText("Camera chưa sẵn sàng. Chạm để thử lại.");return;}if(getCacheDir().getUsableSpace()<64L*1024L*1024L){state=State.ERROR;status.setText("Bộ nhớ thiết bị sắp đầy. Hãy giải phóng dung lượng rồi thử lại.");return;}state=State.PREPARING;recordButton.setEnabled(false);switchButton.setEnabled(false);flashButton.setEnabled(false);status.setText("Đang chuẩn bị quay…");File file;try{file=File.createTempFile("meeto-reel-camera-",".mp4",getCacheDir());}catch(IOException e){state=State.ERROR;recordButton.setEnabled(true);status.setText("Không tạo được file quay tạm. Kiểm tra dung lượng rồi thử lại.");return;}outputFile=file;FileOutputOptions output=new FileOutputOptions.Builder(file).setDurationLimitMillis(selectedDurationSeconds*1000L).setFileSizeLimit(49L*1024L*1024L).build();try{androidx.camera.video.PendingRecording pending=capture.getOutput().prepareRecording(this,output);recordingHasAudio=microphoneEnabled&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;if(recordingHasAudio)pending=pending.withAudioEnabled();recordingStartedAt=SystemClock.elapsedRealtime();activeElapsedMs=0;activeBeforeSegment=0;segmentStartMs=recordingStartedAt;recording=pending.start(ContextCompat.getMainExecutor(this),this::onRecordEvent);state=State.RECORDING;recordButton.setRecording(true);recordButton.setEnabled(true);options.setVisibility(View.GONE);idleControls.setVisibility(View.GONE);activeControls.setVisibility(View.VISIBLE);recordInfo.setVisibility(View.VISIBLE);progress.setVisibility(View.VISIBLE);pauseButton.setImageResource(R.drawable.ic_reel_pause);pauseButton.setContentDescription("Tạm dừng quay");status.setText("Đang quay · tối đa "+selectedDurationSeconds+" giây");main.removeCallbacks(durationTicker);main.post(durationTicker);}catch(SecurityException|IllegalStateException e){file.delete();outputFile=null;recording=null;state=State.ERROR;recordButton.setEnabled(true);switchButton.setEnabled(true);flashButton.setEnabled(true);status.setText(microphoneEnabled?"Không thể mở camera/microphone. Tắt micro để quay video không âm thanh.":"Không thể bắt đầu quay. Hãy thử lại.");}}
    private void onRecordEvent(VideoRecordEvent event){if(isFinishing()||isDestroyed()){if(event instanceof VideoRecordEvent.Finalize){cameraController.release();if(outputFile!=null)outputFile.delete();outputFile=null;recording=null;}return;}if(event instanceof VideoRecordEvent.Start){if(state!=State.FINALIZING&&!stopped)state=State.RECORDING;return;}if(event instanceof VideoRecordEvent.Status){long stats=event.getRecordingStats().getRecordedDurationNanos()/1_000_000L;activeElapsedMs=Math.max(activeElapsedMs,stats);updateRecordingProgress();if(activeElapsedMs>=selectedDurationSeconds*1000L)stopRecording(false);return;}if(event instanceof VideoRecordEvent.Finalize)finalizeRecording((VideoRecordEvent.Finalize)event);}
    private void tickDuration(){if(state!=State.RECORDING)return;activeElapsedMs=Math.max(activeElapsedMs,activeBeforeSegment+SystemClock.elapsedRealtime()-segmentStartMs);updateRecordingProgress();if(activeElapsedMs>=selectedDurationSeconds*1000L){stopRecording(false);return;}main.postDelayed(durationTicker,50);}
    private void updateRecordingProgress(){long max=selectedDurationSeconds*1000L;long shown=Math.min(activeElapsedMs,max);elapsed.setText(formatTime(shown)+" / "+formatTime(max));progress.setProgress((int)(shown*1000L/max));recordButton.setProgress(shown/(float)max);if(outputFile!=null)fileSize.setText(String.format(Locale.getDefault(),"%.1f MB",outputFile.length()/1048576f));}
    private void togglePause(){if(recording==null)return;if(state==State.RECORDING){activeElapsedMs=Math.max(activeElapsedMs,activeBeforeSegment+SystemClock.elapsedRealtime()-segmentStartMs);activeBeforeSegment=activeElapsedMs;recording.pause();state=State.PAUSED;pauseButton.setImageResource(R.drawable.ic_reel_play);pauseButton.setContentDescription("Tiếp tục quay");status.setText("Đã tạm dừng");main.removeCallbacks(durationTicker);updateRecordingProgress();}else if(state==State.PAUSED){recording.resume();segmentStartMs=SystemClock.elapsedRealtime();state=State.RECORDING;pauseButton.setImageResource(R.drawable.ic_reel_pause);pauseButton.setContentDescription("Tạm dừng quay");status.setText("Đang quay");main.post(durationTicker);}}
    private void stopRecording(boolean cancelled){if(recording==null||state==State.FINALIZING)return;cancelAfterFinalize=cancelled;state=State.FINALIZING;main.removeCallbacks(durationTicker);status.setText(cancelled?"Đang hủy bản quay…":"Đang hoàn tất video…");recordButton.setEnabled(false);pauseButton.setEnabled(false);finishButton.setEnabled(false);cancelButton.setEnabled(false);recording.stop();}
    private void finalizeRecording(VideoRecordEvent.Finalize event) {
        main.removeCallbacks(durationTicker);
        recording=null;
        // The recorder has finalized before we explicitly unbind the camera or hand off its file.
        if(stopped){cameraController.release();camera=null;capture=null;}
        state=State.FINALIZING;
        File file=outputFile;outputFile=null;
        recordButton.setRecording(false);recordButton.setEnabled(true);
        pauseButton.setEnabled(true);finishButton.setEnabled(true);cancelButton.setEnabled(true);
        switchButton.setEnabled(true);flashButton.setEnabled(true);
        if(cancelAfterFinalize){cancelAfterFinalize=false;if(file!=null)file.delete();resetForRetake();return;}
        int error=event.getError();
        boolean usablePartial=error==VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                ||error==VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                ||error==VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                ||error==VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE;
        if((event.hasError()&&!usablePartial)||file==null||!file.isFile()||file.length()==0){
            if(file!=null)file.delete();recordingError("Không lưu được bản quay. Kiểm tra camera và bộ nhớ rồi thử lại.");return;
        }
        MediaMetadataRetriever metadata=new MediaMetadataRetriever();
        try{
            metadata.setDataSource(file.getAbsolutePath());
            long duration=Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width=parse(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height=parse(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation=parse(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            boolean audio="yes".equalsIgnoreCase(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))&&recordingHasAudio;
            if(duration<1000||duration>selectedDurationSeconds*1000L+1000||width<=0||height<=0){
                file.delete();recordingError("Video phải dài ít nhất 1 giây và có hình ảnh. Hãy quay lại.");return;
            }
            captureSession=new ReelsCaptureSession(file.getAbsolutePath(),"camera",duration,width,height,rotation,file.length(),audio,selectedDurationSeconds*1000L,true);
            state=State.READY;editorPending=true;
            if(!stopped)continueToEditor();
        }catch(Exception errorReading){file.delete();recordingError("Không đọc được video vừa quay. Hãy quay lại.");}
        finally{try{metadata.release();}catch(Exception ignored){}}
    }
    private void recordingError(String message){
        state=State.ERROR;editorPending=false;
        activeControls.setVisibility(View.GONE);idleControls.setVisibility(View.VISIBLE);options.setVisibility(View.VISIBLE);
        recordInfo.setVisibility(View.GONE);progress.setVisibility(View.GONE);recordButton.setRecording(false);recordButton.setProgress(0);
        recordButton.setEnabled(capture!=null);switchButton.setEnabled(true);flashButton.setEnabled(true);
        status.setText(message+" Chạm dòng này để mở lại camera.");
    }
    private void showReady(){if(captureSession==null)return;state=State.READY;editorPending=true;}
    private void resetForRetake(){editorPending=false;findViewById(R.id.reels_camera_shutter_row).setVisibility(View.VISIBLE);previewView.setVisibility(View.VISIBLE);activeControls.setVisibility(View.GONE);recordInfo.setVisibility(View.GONE);progress.setVisibility(View.GONE);options.setVisibility(View.VISIBLE);idleControls.setVisibility(View.VISIBLE);countdown.setVisibility(View.GONE);flashEnabled=false;state=State.IDLE;recordButton.setEnabled(false);recordButton.setRecording(false);recordButton.setProgress(0);recordButton.setContentDescription("Bắt đầu quay video");pauseButton.setImageResource(R.drawable.ic_reel_pause);pauseButton.setContentDescription("Tạm dừng quay");status.setText("Đang mở camera…");if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bindCamera();else ensureCameraPermission();}
    private void continueToEditor(){if(state!=State.READY||captureSession==null)return;File f=new File(captureSession.localPath);if(!f.isFile()||f.length()<=0){status.setText("File video không còn khả dụng. Hãy quay lại.");return;}Intent intent=new Intent(this,CreateReelActivity.class);captureSession.addTo(intent);startActivity(intent);transferred=true;editorPending=false;finish();}
    private void confirmCancelRecording(){if(state!=State.RECORDING&&state!=State.PAUSED)return;new AlertDialog.Builder(this).setTitle("Hủy bản quay?").setMessage("Video đang quay sẽ bị xóa.").setNegativeButton("Tiếp tục quay",null).setPositiveButton("Hủy quay",(d,w)->stopRecording(true)).show();}
    private void requestClose(){if(state==State.FINALIZING){Toast.makeText(this,"Đang lưu bản quay, vui lòng đợi…",Toast.LENGTH_SHORT).show();return;}if(state==State.RECORDING||state==State.PAUSED){new AlertDialog.Builder(this).setTitle("Hủy bản quay?").setMessage("Video đang quay sẽ bị xóa.").setNegativeButton("Ở lại",null).setPositiveButton("Hủy quay",(d,w)->stopRecording(true)).show();}else if(state==State.READY){new AlertDialog.Builder(this).setTitle("Thoát khỏi camera?").setMessage("Video tạm chưa được chuyển sang tạo Reel.").setNegativeButton("Ở lại",null).setPositiveButton("Thoát",(d,w)->{deleteReadyFile();finish();}).show();}else finish();}
    private void deleteReadyFile(){if(captureSession!=null&&!transferred)new File(captureSession.localPath).delete();captureSession=null;}
    private void togglePauseFromLifecycle(){if(state==State.RECORDING||state==State.PAUSED)stopRecording(false);}
    @Override protected void onResume(){super.onResume();stopped=false;if(editorPending&&state==State.READY){continueToEditor();return;}if(state!=State.READY&&state!=State.FINALIZING&&ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED&&!bindingRequested&&camera==null)bindCamera();}
    @Override protected void onStop(){stopped=true;main.removeCallbacksAndMessages(null);if(state==State.PREPARING&&recording==null){generation++;countdown.setVisibility(View.GONE);state=State.IDLE;options.setVisibility(View.VISIBLE);idleControls.setVisibility(View.VISIBLE);}togglePauseFromLifecycle();if(state!=State.FINALIZING){cameraController.release();camera=null;capture=null;}bindingRequested=false;super.onStop();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("duration_seconds",selectedDurationSeconds);out.putInt("countdown_seconds",countdownSeconds);out.putBoolean("front_camera",frontCamera);if(state==State.READY&&captureSession!=null){out.putString("ready_path",captureSession.localPath);out.putLong("ready_duration",captureSession.durationMs);out.putInt("ready_width",captureSession.width);out.putInt("ready_height",captureSession.height);out.putInt("ready_rotation",captureSession.rotation);out.putBoolean("ready_audio",captureSession.hasAudio);}}
    @Override public void onBackPressed(){requestClose();}
    @Override protected void onDestroy(){destroying=true;generation++;main.removeCallbacksAndMessages(null);boolean finalizationPending=recording!=null;if(recording!=null){try{recording.stop();}catch(Exception ignored){}recording=null;}if(!finalizationPending)cameraController.release();if(captureSession!=null&&!transferred&&!isChangingConfigurations())new File(captureSession.localPath).delete();if(outputFile!=null&&!finalizationPending)outputFile.delete();super.onDestroy();}
    private int parse(String value){try{return Integer.parseInt(value);}catch(Exception e){return 0;}}
    private String formatTime(long ms){long seconds=Math.max(0,ms/1000L);return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}
    private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density+.5f);}
}

