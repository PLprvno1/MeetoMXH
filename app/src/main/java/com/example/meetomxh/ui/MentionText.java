package com.example.meetomxh.ui;

import android.app.Activity;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;

import com.example.meetomxh.ProfileActivity;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MentionText {
    private MentionText() {}
    public static void bind(TextView view,String body,JSONArray mentions){
        bindPost(view,body,mentions,null);
    }
    public static void bindPost(TextView view,String body,JSONArray mentions,JSONArray tags){
        PostCaption caption=PostCaption.format(body,tags);
        SpannableString text=new SpannableString(caption.text);
        if(mentions!=null)for(int i=0;i<mentions.length();i++){
            JSONObject mention=mentions.optJSONObject(i);if(mention==null)continue;
            int start=mention.optInt("start_utf16",-1),end=mention.optInt("end_utf16",-1);
            if(MentionRange.textAt(body,start,end)==null)continue;
            String uid=mention.optString("user_id",null);
            if(uid==null||uid.equals("null"))text.setSpan(new MentionSpan(null,text.subSequence(start,end).toString()),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else text.setSpan(new ProfileMention(uid,text.subSequence(start,end).toString()),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for(PostCaption.Tag tag:caption.tags)if(tag.userId!=null)
            text.setSpan(new ProfileMention(tag.userId,text.subSequence(tag.start,tag.end).toString()),tag.start,tag.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }
    private static final class ProfileMention extends ClickableSpan {
        private final String uid,label;
        ProfileMention(String uid,String label){this.uid=uid;this.label=label;}
        @Override public void onClick(View widget){widget.getContext().startActivity(new android.content.Intent(widget.getContext(),ProfileActivity.class).putExtra("target_uid",uid));}
        @Override public void updateDrawState(android.text.TextPaint ds){ds.setColor(0xff1877f2);ds.setUnderlineText(false);}
    }
}
