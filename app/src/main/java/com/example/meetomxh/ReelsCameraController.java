package com.example.meetomxh;

import android.content.Context;
import android.view.Surface;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/** Owns CameraX use cases and rebinds safely when the user switches lenses. */
public final class ReelsCameraController {
    public interface Callback { void ready(Camera camera,VideoCapture<Recorder> capture); void error(String message); }
    private ProcessCameraProvider provider;
    private Preview preview;
    private VideoCapture<Recorder> capture;
    private Camera camera;
    private int generation;

    public void bind(Context context,LifecycleOwner owner,PreviewView previewView,boolean front,Callback callback){
        final int token=++generation;
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(context);
        future.addListener(()->{
            if(token!=generation)return;
            try{
                provider=future.get();
                CameraSelector selector=new CameraSelector.Builder().requireLensFacing(front?CameraSelector.LENS_FACING_FRONT:CameraSelector.LENS_FACING_BACK).build();
                if(!provider.hasCamera(selector)){callback.error(front?"Camera trước không khả dụng trên thiết bị này.":"Camera sau không khả dụng trên thiết bị này.");return;}
                provider.unbindAll();
                int rotation=previewView.getDisplay()==null?Surface.ROTATION_0:previewView.getDisplay().getRotation();
                preview=new Preview.Builder().setTargetRotation(rotation).build();
                QualitySelector quality=QualitySelector.fromOrderedList(Arrays.asList(Quality.HD,Quality.SD),FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
                Recorder recorder=new Recorder.Builder().setQualitySelector(quality).build();
                capture=VideoCapture.withOutput(recorder);
                capture.setTargetRotation(rotation);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                camera=provider.bindToLifecycle(owner,selector,preview,capture);
                callback.ready(camera,capture);
            }catch(InterruptedException e){Thread.currentThread().interrupt();callback.error("Không thể khởi tạo camera.");}
            catch(Exception e){callback.error("Camera không sẵn sàng. Hãy đóng ứng dụng khác đang dùng camera rồi thử lại.");}
        },ContextCompat.getMainExecutor(context));
    }
    public boolean hasFrontCamera(){try{return provider!=null&&provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);}catch(Exception e){return false;}}
    public void release(){generation++;if(provider!=null){try{provider.unbindAll();}catch(Exception ignored){}}camera=null;capture=null;preview=null;provider=null;}
    public Camera camera(){return camera;}
    public VideoCapture<Recorder> videoCapture(){return capture;}
}
