package com.example.meetomxh;

import android.content.Intent;

/** Camera metadata handoff. The editor adopts a private file; video bytes never travel in an Intent. */
public final class ReelsCaptureSession {
    public static final String EXTRA_PATH="reel_capture_path";
    public static final String EXTRA_URI="reel_capture_uri";
    public static final String EXTRA_SOURCE="reel_capture_source";
    public static final String EXTRA_DURATION="reel_capture_duration_ms";
    public static final String EXTRA_WIDTH="reel_capture_width";
    public static final String EXTRA_HEIGHT="reel_capture_height";
    public static final String EXTRA_ROTATION="reel_capture_rotation";
    public static final String EXTRA_SIZE="reel_capture_size";
    public static final String EXTRA_AUDIO="reel_capture_audio";
    public static final String EXTRA_MAX_DURATION="reel_capture_limit_ms";
    public static final String EXTRA_OWNS_FILE="reel_capture_owns_temporary_file";

    public final String localPath,source;
    public final long durationMs,fileSizeBytes,maxDurationMs;
    public final int width,height,rotation;
    public final boolean hasAudio,ownsTemporaryFile;

    public ReelsCaptureSession(String path,String source,long duration,int width,int height,int rotation,long size,boolean audio,long maxDuration,boolean ownsFile){
        this.localPath=path;this.source=source;this.durationMs=duration;this.width=width;this.height=height;this.rotation=rotation;this.fileSizeBytes=size;this.hasAudio=audio;this.maxDurationMs=maxDuration;this.ownsTemporaryFile=ownsFile;
    }
    public void addTo(Intent intent){intent.putExtra(EXTRA_URI,android.net.Uri.fromFile(new java.io.File(localPath)).toString()).putExtra(EXTRA_PATH,localPath).putExtra(EXTRA_SOURCE,source).putExtra(EXTRA_DURATION,durationMs).putExtra(EXTRA_WIDTH,width).putExtra(EXTRA_HEIGHT,height).putExtra(EXTRA_ROTATION,rotation).putExtra(EXTRA_SIZE,fileSizeBytes).putExtra(EXTRA_AUDIO,hasAudio).putExtra(EXTRA_MAX_DURATION,maxDurationMs).putExtra(EXTRA_OWNS_FILE,ownsTemporaryFile);}
}
