package com.example.meetomxh.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.example.meetomxh.R;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.imageview.ShapeableImageView;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MentionComposer implements TextWatcher {
    private final EditText input;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchTask = this::search;
    private PopupWindow popup;
    private LinearLayout rows;
    private int tokenStart, cursorAt, requestGeneration;
    private boolean disposed;

    public MentionComposer(EditText input) {
        this.input = input;
        input.addTextChangedListener(this);
    }

    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    @Override public void afterTextChanged(Editable e) {
        MentionSpan[] spans=e.getSpans(0,e.length(),MentionSpan.class);
        for(MentionSpan span:spans){int a=e.getSpanStart(span),b=e.getSpanEnd(span);
            if(a<0||b<=a||b>e.length()||!e.subSequence(a,b).toString().equals(span.label))e.removeSpan(span);}
        schedule();
    }

    private void schedule() {
        if(disposed)return;
        handler.removeCallbacks(searchTask); requestGeneration++;
        String text=input.getText().toString(); int cursor=Math.max(0,Math.min(input.getSelectionStart(),text.length()));
        int at=text.lastIndexOf('@',Math.max(0,cursor-1));
        if(at<0||cursor<=at+1||(at>0&&(Character.isLetterOrDigit(text.charAt(at-1))||text.charAt(at-1)=='_'))){dismiss();return;}
        String query=text.substring(at+1,cursor);
        if(query.length()>60||query.indexOf(' ')>=0||query.indexOf('\n')>=0){dismiss();return;}
        tokenStart=at;cursorAt=cursor;showLoading();handler.postDelayed(searchTask,320);
    }

    private void search() {
        if(disposed)return;
        String query=input.getText().toString().substring(tokenStart+1,cursorAt); int generation=requestGeneration;
        Api.run(()->(JSONArray)RpcClient.call("search_mention_people",Api.obj("p_query",query,"p_limit",8)),new Api.Callback<JSONArray>(){
            @Override public void success(JSONArray people){if(generation!=requestGeneration||disposed)return;showPeople(people);}
            @Override public void error(String ignored){if(generation!=requestGeneration||disposed)return;showMessage("Không tải được gợi ý · tiếp tục nhập để thử lại");}
        });
    }

    private void ensurePopup(){
        if(popup!=null)return; Context c=input.getContext(); rows=new LinearLayout(c);rows.setOrientation(LinearLayout.VERTICAL);
        int surface=c.getColor(R.color.surface_bg);rows.setBackgroundColor(surface);popup=new PopupWindow(rows,Math.max(input.getWidth(),dp(c,250)),dp(c,48),false);
        popup.setBackgroundDrawable(new ColorDrawable(surface));popup.setOutsideTouchable(true);popup.setElevation(dp(c,8));
    }
    private void showLoading(){ensurePopup();rows.removeAllViews();TextView t=label("Đang tìm người…",false);rows.addView(t);showPopup();}
    private void showMessage(String message){ensurePopup();rows.removeAllViews();rows.addView(label(message,false));showPopup();}
    private void showPeople(JSONArray people){
        ensurePopup();rows.removeAllViews();
        if(people.length()==0){showMessage("Không tìm thấy tài khoản phù hợp");return;}
        for(int i=0;i<people.length();i++){
            JSONObject person=people.optJSONObject(i);if(person==null)continue;
            LinearLayout row=new LinearLayout(input.getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(input.getContext(),12),dp(input.getContext(),6),dp(input.getContext(),12),dp(input.getContext(),6));
            ShapeableImageView avatar=new ShapeableImageView(input.getContext());LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(input.getContext(),34),dp(input.getContext(),34));row.addView(avatar,ap);
            AvatarViews.load(avatar,Dto.asset(Dto.nullable(person,"avatar_asset_id")),person.optString("avatar_preset","neutral"));
            TextView name=label(person.optString("display_name","Meeto user"),true);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);np.leftMargin=dp(input.getContext(),10);row.addView(name,np);
            JSONObject choice=person;row.setOnClickListener(v->insert(choice));rows.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(input.getContext(),48)));
        }
        showPopup();
    }
    private TextView label(String value,boolean bold){TextView t=new TextView(input.getContext());t.setText(value);t.setTextColor(input.getContext().getColor(R.color.text_primary));t.setTextSize(14);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(input.getContext(),12),dp(input.getContext(),12),dp(input.getContext(),12),dp(input.getContext(),12));if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);return t;}
    private void showPopup(){if(!input.isAttachedToWindow())return;try{if(popup.isShowing())popup.update(Math.max(input.getWidth(),dp(input.getContext(),250)),ViewGroup.LayoutParams.WRAP_CONTENT);else popup.showAsDropDown(input,0,dp(input.getContext(),2));}catch(Exception ignored){}}
    private void insert(JSONObject person){
        Editable e=input.getText();int start=Math.max(0,Math.min(tokenStart,e.length()));int end=Math.max(start,Math.min(cursorAt,e.length()));
        String display=person.optString("display_name","Meeto user"),label="@"+display;
        e.replace(start,end,label+" ");MentionSpan span=new MentionSpan(person.optString("user_id"),label);
        e.setSpan(span,start,start+label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        input.setSelection(start+label.length()+1);dismiss();input.requestFocus();
    }

    public JSONArray getMentions(){JSONArray out=new JSONArray();Editable e=input.getText();for(MentionSpan s:e.getSpans(0,e.length(),MentionSpan.class)){
        int start=e.getSpanStart(s),end=e.getSpanEnd(s);if(start>=0&&end>start&&MentionRange.textAt(e.toString(),start,end)!=null&&e.subSequence(start,end).toString().equals(s.label))
            out.put(Api.obj("user_id",s.userId,"label",s.label,"start_utf16",start,"end_utf16",end));}return out;}
    public void restore(JSONArray mentions){Editable e=input.getText();if(mentions==null)return;for(int i=0;i<mentions.length();i++){JSONObject m=mentions.optJSONObject(i);if(m==null)continue;int a=m.optInt("start_utf16",-1),b=m.optInt("end_utf16",-1);String id=m.optString("user_id",null);if(id==null||id.equals("null")||a<0||b<=a||b>e.length())continue;String label=e.subSequence(a,b).toString();if(label.startsWith("@"))e.setSpan(new MentionSpan(id,label),a,b,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}}
    private void dismiss(){if(popup!=null&&popup.isShowing())popup.dismiss();}
    public void dispose(){disposed=true;handler.removeCallbacks(searchTask);dismiss();input.removeTextChangedListener(this);}
    private static int dp(Context c,int x){return (int)(x*c.getResources().getDisplayMetrics().density+.5f);}
}
