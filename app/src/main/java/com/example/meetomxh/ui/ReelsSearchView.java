package com.example.meetomxh.ui;

import android.content.Context;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import com.example.meetomxh.adapters.SearchPostAdapter;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.AdvancedSearchRepository;
import com.example.meetomxh.supabase.*;
import com.google.android.material.button.MaterialButton;
import java.util.*;

/** Bounded server search owned by the Reels bottom sheet, with no player ownership. */
public final class ReelsSearchView extends LinearLayout {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<Post> rows=new ArrayList<>();private final SearchPostAdapter adapter;
    private final TextView status;private final ProgressBar progress;private final MaterialButton more,retry;private final RecyclerView list;
    private Subscription request;private Runnable pending;private String query="",afterTime,afterId;
    private int generation;private boolean busy,hasMore,appendFailed,closed;
    public ReelsSearchView(Context context,SearchPostAdapter.Click click){
        super(context);setOrientation(VERTICAL);setPadding(dp(12),dp(12),dp(12),dp(20));setBackgroundColor(0xFF171719);
        TextView title=new TextView(context);title.setText("Tìm kiếm Reels");title.setTextColor(-1);title.setTextSize(20);addView(title);
        EditText input=new EditText(context);input.setSingleLine(true);input.setTextColor(-1);input.setHintTextColor(0xFFBBBBBB);input.setHint("Caption, hashtag, tên hoặc username");input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);addView(input,new LayoutParams(-1,dp(56)));
        status=new TextView(context);status.setTextColor(0xFFCCCCCC);status.setText("Nhập ít nhất 2 ký tự để tìm Reels.");addView(status);
        progress=new ProgressBar(context);progress.setVisibility(GONE);addView(progress,new LayoutParams(dp(28),dp(28)));
        list=new RecyclerView(context);list.setLayoutManager(new LinearLayoutManager(context));adapter=new SearchPostAdapter(click,true);list.setAdapter(adapter);addView(list,new LayoutParams(-1,Math.min(dp(360),context.getResources().getDisplayMetrics().heightPixels/2)));
        more=new MaterialButton(context);more.setText("Tải thêm");more.setVisibility(GONE);more.setOnClickListener(v->load(true));addView(more);
        retry=new MaterialButton(context);retry.setText("Thử lại");retry.setVisibility(GONE);retry.setOnClickListener(v->load(appendFailed));addView(retry);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString().trim();restart();}});
        input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){restart();return true;}return false;});
    }
    private void restart(){
        generation++;if(request!=null)request.remove();handler.removeCallbacksAndMessages(null);busy=false;hasMore=false;rows.clear();adapter.setRows(rows);afterTime=null;afterId=null;more.setVisibility(GONE);retry.setVisibility(GONE);progress.setVisibility(GONE);
        status.setText(query.length()<2?"Nhập ít nhất 2 ký tự để tìm Reels.":"Đang tìm kiếm…");
        if(query.length()<2)return;int token=generation;pending=()->{if(!closed&&token==generation)load(false);};handler.postDelayed(pending,350);
    }
    private void load(boolean append){
        if(closed||busy||query.length()<2||append&&!hasMore)return;busy=true;appendFailed=append;int token=++generation;progress.setVisibility(VISIBLE);retry.setVisibility(GONE);more.setEnabled(false);
        request=AdvancedSearchRepository.reels(query,append?afterTime:null,append?afterId:null,20,new Api.Callback<AdvancedSearchRepository.PostPage>(){
            public void success(AdvancedSearchRepository.PostPage page){if(closed||token!=generation)return;busy=false;progress.setVisibility(GONE);if(!append)rows.clear();Set<String> ids=new HashSet<>();for(Post row:rows)ids.add(row.getPostId());for(Post row:page.posts)if(ids.add(row.getPostId()))rows.add(row);afterId=page.afterId;afterTime=page.afterTime;hasMore=page.posts.size()==20;adapter.setRows(rows);status.setText(rows.isEmpty()?"Không tìm thấy Reel phù hợp.":"");more.setVisibility(hasMore?VISIBLE:GONE);more.setEnabled(true);}
            public void error(String message){if(closed||token!=generation)return;busy=false;progress.setVisibility(GONE);status.setText(message);more.setVisibility(GONE);retry.setVisibility(VISIBLE);}
        });
    }
    @Override protected void onDetachedFromWindow(){closed=true;generation++;handler.removeCallbacksAndMessages(null);if(request!=null)request.remove();list.setAdapter(null);super.onDetachedFromWindow();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
