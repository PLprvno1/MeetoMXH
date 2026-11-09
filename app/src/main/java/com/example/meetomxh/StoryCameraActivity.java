package com.example.meetomxh;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.*;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.*;

/** CameraX capture for Stories. Only finalized private files are handed to the existing creator. */
public final class StoryCameraActivity extends AppCompatActivity{
 private PreviewView preview;private TextView status;private Button shutter,mode,flip,mic;private ProcessCameraProvider provider;private ImageCapture photo;private VideoCapture<Recorder> video;private Recording recording;private File output;private boolean videoMode,front,binding,busy,stopped,pending,transferred,finalizing;private int generation;
 private final ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),ok->{if(ok)bind();else status.setText("Cần quyền camera. Bạn vẫn có thể dùng thư viện trong màn tạo Story.");});
 private final ActivityResultLauncher<String> audio=registerForActivityResult(new ActivityResultContracts.RequestPermission(),ok->{mic.setText(ok?"Micro bật":"Micro tắt");status.setText(ok?"Micro đã sẵn sàng":"Quay video không có âm thanh");});
 @Override protected void onCreate(Bundle saved){super.onCreate(saved);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);setContentView(R.layout.activity_story_camera);View root=findViewById(R.id.story_camera_root);ViewCompat.setOnApplyWindowInsetsListener(root,(v,i)->{androidx.core.graphics.Insets b=i.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());v.setPadding(b.left,b.top,b.right,b.bottom);return i;});WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
  status=findViewById(R.id.story_camera_status);preview=findViewById(R.id.story_camera_preview);preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
  mode=findViewById(R.id.story_camera_mode);shutter=findViewById(R.id.story_camera_shutter);flip=findViewById(R.id.story_camera_flip);mic=findViewById(R.id.story_camera_mic);View back=findViewById(R.id.story_camera_back);back.setOnClickListener(v->close());mode.setOnClickListener(v->{if(!busy){videoMode=!videoMode;mode.setText(videoMode?"Video":"Ảnh");shutter.setText(videoMode?"Quay":"Chụp");bind();}});flip.setOnClickListener(v->{if(!busy){front=!front;bind();}});mic.setOnClickListener(v->{if(!busy)audio.launch(Manifest.permission.RECORD_AUDIO);});shutter.setOnClickListener(v->capture());getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){public void handleOnBackPressed(){close();}});
  if(saved!=null){String path=saved.getString("pending_file");if(path!=null){File file=new File(path);try{if(file.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath()+File.separator+"story-camera-")&&file.isFile()&&file.length()>0){output=file;pending=true;}}catch(Exception ignored){}}}
  if(!pending){if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind();else permission.launch(Manifest.permission.CAMERA);}
 }
 private void bind(){if(stopped||busy||pending||isFinishing())return;binding=true;shutter.setEnabled(false);int token=++generation;ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);future.addListener(()->{if(token!=generation||isFinishing()||stopped)return;try{provider=future.get();provider.unbindAll();CameraSelector selector=front?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;if(!provider.hasCamera(selector))throw new IllegalStateException();Preview p=new Preview.Builder().build();p.setSurfaceProvider(preview.getSurfaceProvider());
   if(videoMode){Recorder r=new Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(Arrays.asList(Quality.HD,Quality.SD),FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build();video=VideoCapture.withOutput(r);photo=null;provider.bindToLifecycle(this,selector,p,video);}else{photo=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();video=null;provider.bindToLifecycle(this,selector,p,photo);}status.setText(videoMode?"Video Story · tối đa 60 giây":"Ảnh Story");shutter.setEnabled(true);
  }catch(Exception error){status.setText("Không mở được camera này. Đổi camera hoặc quay lại dùng thư viện.");}finally{binding=false;}},ContextCompat.getMainExecutor(this));}
 private void capture(){if(finalizing)return;if(recording!=null){finalizing=true;shutter.setEnabled(false);recording.stop();return;}if(busy||binding)return;busy=true;mode.setEnabled(false);flip.setEnabled(false);mic.setEnabled(false);
  try{output=File.createTempFile("story-camera-",videoMode?".mp4":".jpg",getCacheDir());if(!videoMode){shutter.setEnabled(false);photo.takePicture(new ImageCapture.OutputFileOptions.Builder(output).build(),ContextCompat.getMainExecutor(this),new ImageCapture.OnImageSavedCallback(){public void onImageSaved(ImageCapture.OutputFileResults result){ready();}public void onError(ImageCaptureException error){failed();}});}else{PendingRecording next=video.getOutput().prepareRecording(this,new FileOutputOptions.Builder(output).setDurationLimitMillis(60000).setFileSizeLimit(49L*1024*1024).build());if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)next=next.withAudioEnabled();recording=next.start(ContextCompat.getMainExecutor(this),event->{if(event instanceof VideoRecordEvent.Status)status.setText("Đang quay · "+(event.getRecordingStats().getRecordedDurationNanos()/1000000000L)+" / 60 giây");if(event instanceof VideoRecordEvent.Finalize){recording=null;finalizing=false;VideoRecordEvent.Finalize f=(VideoRecordEvent.Finalize)event;if(f.hasError()&&f.getError()!=VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED&&f.getError()!=VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED&&f.getError()!=VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE)failed();else ready();}});shutter.setText("Xong");}}
  catch(Exception error){failed();}
 }
 private void ready(){busy=false;pending=true;if(isDestroyed()||isFinishing()){if(output!=null&&!transferred)output.delete();if(provider!=null)provider.unbindAll();return;}if(provider!=null)provider.unbindAll();if(!stopped)deliver();}
 private void deliver(){if(output==null||!output.isFile()||output.length()==0){failed();return;}setResult(RESULT_OK,new Intent().putExtra("story_media_uri",Uri.fromFile(output).toString()));transferred=true;finish();}
 private void failed(){busy=false;pending=false;finalizing=false;recording=null;if(output!=null)output.delete();output=null;if(isDestroyed())return;shutter.setEnabled(true);mode.setEnabled(true);flip.setEnabled(true);mic.setEnabled(true);shutter.setText(videoMode?"Quay":"Chụp");status.setText("Chưa lưu được media. Hãy thử lại.");}
 private void close(){if(busy||finalizing){if(recording!=null&&!finalizing){finalizing=true;shutter.setEnabled(false);recording.stop();}else Toast.makeText(this,"Đang lưu media, vui lòng đợi…",Toast.LENGTH_SHORT).show();return;}finish();}
 @Override protected void onResume(){super.onResume();stopped=false;if(pending)deliver();else if(provider==null&&!binding&&ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bind();}
 @Override protected void onStop(){stopped=true;generation++;binding=false;if(recording!=null&&!finalizing){finalizing=true;recording.stop();}if(!busy&&provider!=null){provider.unbindAll();provider=null;}super.onStop();}
 @Override protected void onSaveInstanceState(Bundle out){if(pending&&output!=null)out.putString("pending_file",output.getAbsolutePath());super.onSaveInstanceState(out);}
 @Override protected void onDestroy(){generation++;if(recording!=null){recording.stop();}else if(provider!=null)provider.unbindAll();if(!busy&&!transferred&&!isChangingConfigurations()&&output!=null)output.delete();super.onDestroy();}
}
