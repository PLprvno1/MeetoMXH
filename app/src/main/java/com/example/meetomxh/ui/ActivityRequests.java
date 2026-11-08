package com.example.meetomxh.ui;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/** Cancels screen requests and prevents late callbacks from touching a closed screen. */
public final class ActivityRequests implements DefaultLifecycleObserver {
    private final AppCompatActivity host;
    private final long epoch=SessionManager.getInstance().generation();
    private final Set<Subscription> jobs=new HashSet<>();
    private boolean closed;

    public ActivityRequests(AppCompatActivity host){this.host=host;host.getLifecycle().addObserver(this);}
    public boolean alive(){return !closed&&!host.isFinishing()&&!host.isDestroyed()
            &&epoch==SessionManager.getInstance().generation();}
    public <T> Api.Callback<T> guard(Api.Callback<T> callback){
        return new Api.Callback<T>(){
            public void success(T value){if(alive())callback.success(value);}
            public void error(String message){if(alive())callback.error(message);}
        };
    }
    public <T> Subscription run(Callable<T> task,Api.Callback<T> callback){
        if(!alive())return ()->{};
        Subscription[] job=new Subscription[1];
        job[0]=Api.run(task,new Api.Callback<T>(){
            public void success(T value){jobs.remove(job[0]);if(alive())callback.success(value);}
            public void error(String message){jobs.remove(job[0]);if(alive())callback.error(message);}
        });
        jobs.add(job[0]);return job[0];
    }
    public void close(){closed=true;for(Subscription job:jobs)job.remove();jobs.clear();}
    @Override public void onDestroy(LifecycleOwner owner){close();}
}
