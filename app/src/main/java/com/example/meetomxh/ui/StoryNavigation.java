package com.example.meetomxh.ui;

import android.content.Intent;
import android.view.View;
import android.widget.Toast;
import com.example.meetomxh.StoryViewActivity;
import com.example.meetomxh.supabase.StoryClient;

/** One Story entry point for tray and avatar rings; never sends synthetic IDs to RPCs. */
public final class StoryNavigation {
    private StoryNavigation(){}
    public static View.OnClickListener listener(String owner,String story){return view->open(view,owner,story);}
    public static void open(View view,String owner,String story){
        if(!StoryClient.validId(owner)||(story!=null&&!StoryClient.validId(story))){
            Toast.makeText(view.getContext(),"Không xác định được Story. Hãy tải lại khay tin.",Toast.LENGTH_SHORT).show();return;
        }
        Intent intent=new Intent(view.getContext(),StoryViewActivity.class).putExtra(StoryViewActivity.EXTRA_AUTHOR_ID,owner);
        if(story!=null)intent.putExtra(StoryViewActivity.EXTRA_STORY_ID,story);
        view.getContext().startActivity(intent);
    }
}
