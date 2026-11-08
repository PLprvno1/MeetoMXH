package com.example.meetomxh.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.ScrollView;

import com.example.meetomxh.R;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.imageview.ShapeableImageView;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MentionComposer implements TextWatcher {
    private final EditText input;
    private final long sessionEpoch=SessionManager.getInstance().generation();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchTask = this::search;
    private PopupWindow popup;
    private final Rect popupBounds=new Rect();
    private LinearLayout rows;
    private Subscription request;
    private int lastSelection=-1;
    private boolean lastFocused;
    private final ViewTreeObserver.OnPreDrawListener selectionWatcher=this::selectionChanged;
    private boolean selectionChanged(){
        boolean focused=input.hasFocus();int position=input.getSelectionStart();
        if(focused&&(position!=lastSelection||!lastFocused)){lastSelection=position;lastFocused=true;schedule();}
        else if(!focused&&lastFocused){lastFocused=false;cancelSearch();dismiss();}
        if(popup!=null&&popup.isShowing())showPopup();
        return true;
    }
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener(){
        public void onViewAttachedToWindow(View v){}
        public void onViewDetachedFromWindow(View v){cancelSearch();dismiss();}
    };
    private int tokenStart, cursorAt, requestGeneration;
    private boolean disposed;
    private boolean suppressSearchOnce;

    public MentionComposer(EditText input) {
        this.input = input;
        input.addTextChangedListener(this);
        input.getViewTreeObserver().addOnPreDrawListener(selectionWatcher);
        input.addOnAttachStateChangeListener(attachment);
    }

    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
    @Override public void afterTextChanged(Editable e) {
        MentionSpan[] spans=e.getSpans(0,e.length(),MentionSpan.class);
        for(MentionSpan span:spans){int a=e.getSpanStart(span),b=e.getSpanEnd(span);
            if(a<0||b<=a||b>e.length()||!e.subSequence(a,b).toString().equals(span.label))e.removeSpan(span);}
        if(suppressSearchOnce){suppressSearchOnce=false;dismiss();return;}
        schedule();
    }

    /** Inserts a real, server-valid mention span before a reply draft. */
    public void prependMention(String userId,String displayName){
        if(disposed||userId==null||userId.isEmpty()||displayName==null||displayName.trim().isEmpty())return;
        Editable e=input.getText();String label="@"+displayName.trim();
        for(MentionSpan existing:e.getSpans(0,e.length(),MentionSpan.class)){
            if(userId.equals(existing.userId)&&e.getSpanStart(existing)==0){
                int end=e.getSpanEnd(existing);input.setSelection(Math.min(e.length(),end+1));input.requestFocus();return;
            }
        }
        suppressSearchOnce=true;e.insert(0,label+" ");
        e.setSpan(new MentionSpan(userId,label),0,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        input.setSelection(label.length()+1);input.requestFocus();
    }

    private void schedule() {
        if(disposed)return;
        cancelSearch();
        if(sessionEpoch!=SessionManager.getInstance().generation()){dismiss();return;}
        if(!input.hasFocus()||!input.isEnabled()||input.getSelectionStart()!=input.getSelectionEnd()){dismiss();return;}
        String text=input.getText().toString(); int cursor=Math.max(0,Math.min(input.getSelectionStart(),text.length()));
        int at=text.lastIndexOf('@',Math.max(0,cursor-1));
        if(at<0||cursor<=at||(at>0&&(Character.isLetterOrDigit(text.charAt(at-1))||text.charAt(at-1)=='_'))){dismiss();return;}
        String query=text.substring(at+1,cursor);
        if(query.length()>60||query.matches("(?s).*\\s.*")||input.getText().getSpans(at,cursor,MentionSpan.class).length>0){dismiss();return;}
        tokenStart=at;cursorAt=cursor;showLoading();handler.postDelayed(searchTask,320);
    }

    private void search() {
        if(disposed)return;
        if(tokenStart<0||cursorAt>input.length()||cursorAt<=tokenStart||input.getSelectionStart()!=cursorAt){dismiss();return;}
        String query=input.getText().toString().substring(tokenStart+1,cursorAt); int generation=requestGeneration;
        request=Api.run(()->(JSONArray)RpcClient.call("search_mention_people",Api.obj("p_query",query,"p_limit",8)),new Api.Callback<JSONArray>(){
            @Override public void success(JSONArray people){if(generation!=requestGeneration||disposed)return;showPeople(people);}
            @Override public void error(String ignored){if(generation!=requestGeneration||disposed)return;showMessage("Không tải được gợi ý · tiếp tục nhập để thử lại");}
        });
    }

    private void ensurePopup(){
        if(popup!=null)return; Context c=input.getContext(); rows=new LinearLayout(c);rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(c);scroll.setFillViewport(false);scroll.setClipToOutline(true);
        scroll.setPadding(0,dp(c,4),0,dp(c,4));scroll.addView(rows,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        GradientDrawable background=new GradientDrawable();background.setColor(c.getColor(R.color.surface_bg));background.setCornerRadius(dp(c,12));background.setStroke(dp(c,1),c.getColor(R.color.outline_color));
        scroll.setBackground(background);popup=new PopupWindow(scroll,Math.max(input.getWidth(),dp(c,250)),dp(c,64),false);
        popup.setBackgroundDrawable(background);popup.setOutsideTouchable(true);popup.setElevation(dp(c,12));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
    }
    private void showLoading(){ensurePopup();rows.removeAllViews();TextView t=label("Đang tìm người…",false);rows.addView(t);showPopup();}
    private void showMessage(String message){ensurePopup();rows.removeAllViews();rows.addView(label(message,false));showPopup();}
    private void showPeople(JSONArray people){
        ensurePopup();rows.removeAllViews();
        if(people.length()==0){showMessage("Không tìm thấy tài khoản phù hợp");return;}
        for(int i=0;i<people.length();i++){
            JSONObject person=people.optJSONObject(i);if(person==null)continue;
            LinearLayout row=new LinearLayout(input.getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(input.getContext(),12),dp(input.getContext(),6),dp(input.getContext(),12),dp(input.getContext(),6));
            row.setMinimumHeight(dp(input.getContext(),64));
            ShapeableImageView avatar=new ShapeableImageView(input.getContext());avatar.setShapeAppearanceModel(avatar.getShapeAppearanceModel().toBuilder().setAllCornerSizes(dp(input.getContext(),20)).build());avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(dp(input.getContext(),40),dp(input.getContext(),40));row.addView(avatar,ap);
            AvatarViews.load(avatar,Dto.asset(Dto.nullable(person,"avatar_asset_id")),person.optString("avatar_preset","neutral"));
            String username=Dto.nullable(person,"username");
            LinearLayout details=new LinearLayout(input.getContext());details.setOrientation(LinearLayout.VERTICAL);
            TextView name=personLabel(person.optString("display_name","Người dùng"),true);details.addView(name);
            if(username!=null){TextView handle=personLabel("@"+username,false);handle.setTextSize(12);handle.setTextColor(input.getContext().getColor(R.color.text_secondary));details.addView(handle);}
            LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1);np.leftMargin=dp(input.getContext(),12);row.addView(details,np);
            android.util.TypedValue feedback=new android.util.TypedValue();input.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground,feedback,true);if(feedback.resourceId!=0)row.setBackgroundResource(feedback.resourceId);
            JSONObject choice=person;row.setOnClickListener(v->insert(choice));rows.addView(row,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        showPopup();
    }
    private TextView label(String value,boolean bold){TextView t=new TextView(input.getContext());t.setText(value);t.setTextColor(input.getContext().getColor(R.color.text_primary));t.setTextSize(14);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(input.getContext(),12),dp(input.getContext(),12),dp(input.getContext(),12),dp(input.getContext(),12));if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);return t;}
    private TextView personLabel(String value,boolean bold){TextView t=label(value,bold);t.setPadding(0,0,0,0);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));return t;}
    private void showPopup(){
        if(!input.isAttachedToWindow()||!input.hasFocus()||disposed)return;
        Context c=input.getContext();Rect visible=new Rect();input.getWindowVisibleDisplayFrame(visible);int[] location=new int[2];input.getLocationOnScreen(location);
        int gap=dp(c,8),margin=dp(c,12);
        int above=Math.max(0,location[1]-visible.top-gap),below=Math.max(0,visible.bottom-location[1]-input.getHeight()-gap);
        boolean up=above>below;int available=up?above:below;if(available<dp(c,40)){dismiss();return;}
        int width=Math.min(visible.width()-2*margin,Math.max(input.getWidth(),dp(c,250)));if(width<=0){dismiss();return;}
        rows.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int height=Math.min(available,Math.min(dp(c,328),rows.getMeasuredHeight()+dp(c,8)));
        int x=Math.max(visible.left+margin,Math.min(location[0],visible.right-margin-width));
        int y=up?location[1]-gap-height:location[1]+input.getHeight()+gap;
        // Position in the input's window without scrolling its parent to make room.
        View root=input.getRootView();int[] screen=new int[2],window=new int[2];root.getLocationOnScreen(screen);root.getLocationInWindow(window);x-=screen[0]-window[0];y-=screen[1]-window[1];
        try{
            if(popup.isShowing()){if(!popupBounds.equals(new Rect(x,y,x+width,y+height)))popup.update(x,y,width,height);}
            else{popup.setWidth(width);popup.setHeight(height);popup.showAtLocation(input,Gravity.TOP|Gravity.LEFT,x,y);}
            popupBounds.set(x,y,x+width,y+height);
        }catch(android.view.WindowManager.BadTokenException ignored){dismiss();}
    }
    private void insert(JSONObject person){
        Editable e=input.getText();int start=Math.max(0,Math.min(tokenStart,e.length()));int end=Math.max(start,Math.min(cursorAt,e.length()));
        if(disposed||sessionEpoch!=SessionManager.getInstance().generation()||input.getSelectionStart()!=cursorAt||start>=e.length()||e.charAt(start)!='@')return;
        cancelSearch();suppressSearchOnce=true;
        String display=person.optString("display_name","Người dùng"),label="@"+display;
        e.replace(start,end,label+" ");MentionSpan span=new MentionSpan(person.optString("user_id"),label);
        e.setSpan(span,start,start+label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        input.setSelection(start+label.length()+1);dismiss();input.requestFocus();
    }

    public JSONArray getMentions(){JSONArray out=new JSONArray();Editable e=input.getText();for(MentionSpan s:e.getSpans(0,e.length(),MentionSpan.class)){
        int start=e.getSpanStart(s),end=e.getSpanEnd(s);if(start>=0&&end>start&&MentionRange.textAt(e.toString(),start,end)!=null&&e.subSequence(start,end).toString().equals(s.label))
            out.put(Api.obj("user_id",s.userId,"label",s.label,"start_utf16",start,"end_utf16",end));}return out;}
    public void restore(JSONArray mentions){Editable e=input.getText();if(mentions==null)return;for(int i=0;i<mentions.length();i++){JSONObject m=mentions.optJSONObject(i);if(m==null)continue;int a=m.optInt("start_utf16",-1),b=m.optInt("end_utf16",-1);String id=m.optString("user_id",null);if(id==null||id.equals("null")||a<0||b<=a||b>e.length())continue;String label=e.subSequence(a,b).toString();if(label.startsWith("@"))e.setSpan(new MentionSpan(id,label),a,b,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}}
    private void dismiss(){if(popup!=null&&popup.isShowing())popup.dismiss();}
    private void cancelSearch(){handler.removeCallbacks(searchTask);requestGeneration++;if(request!=null){request.remove();request=null;}}
    public void dispose(){disposed=true;cancelSearch();dismiss();input.removeTextChangedListener(this);input.removeOnAttachStateChangeListener(attachment);if(input.getViewTreeObserver().isAlive())input.getViewTreeObserver().removeOnPreDrawListener(selectionWatcher);}
    private static int dp(Context c,int x){return (int)(x*c.getResources().getDisplayMetrics().density+.5f);}
}
