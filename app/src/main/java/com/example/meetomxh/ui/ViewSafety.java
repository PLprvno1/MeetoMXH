package com.example.meetomxh.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

/** Allows callbacks to reject views belonging to a finished Activity, including themed contexts. */
public final class ViewSafety {
    private ViewSafety() {}
    public static boolean isAlive(View view) {
        if(view==null)return false;
        Context context=view.getContext();
        while(context instanceof ContextWrapper){
            if(context instanceof Activity){Activity activity=(Activity)context;return !activity.isFinishing()&&!activity.isDestroyed();}
            Context base=((ContextWrapper)context).getBaseContext();
            if(base==context)break;
            context=base;
        }
        return true;
    }
}
