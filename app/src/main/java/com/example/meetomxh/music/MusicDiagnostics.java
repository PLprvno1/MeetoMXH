package com.example.meetomxh.music;

import android.util.Log;

/** No exception messages/URIs/paths: causes and code locations only, safe for owner diagnostics. */
public final class MusicDiagnostics {
    private MusicDiagnostics() {}
    public static void failure(String stage, Throwable error) {
        StringBuilder detail=new StringBuilder("stage=").append(stage);
        Throwable cause=error;
        for(int depth=0;cause!=null&&depth<4;depth++){
            detail.append(" cause").append(depth).append('=').append(cause.getClass().getSimpleName());
            if(cause instanceof androidx.media3.common.PlaybackException)
                detail.append(" media3Code=").append(((androidx.media3.common.PlaybackException)cause).errorCode);
            if(cause instanceof androidx.media3.transformer.ExportException)
                detail.append(" exportCode=").append(((androidx.media3.transformer.ExportException)cause).errorCode);
            StackTraceElement[] frames=cause.getStackTrace();
            for(int i=0;i<Math.min(6,frames.length);i++){
                StackTraceElement frame=frames[i];
                detail.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber());
            }
            if(cause.getCause()==cause)break;
            cause=cause.getCause();
        }
        Log.w("MeetoMusic",detail.toString());
    }
}
