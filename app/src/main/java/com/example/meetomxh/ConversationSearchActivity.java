package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.ServerTime;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

/** Server-paged, permission checked message search for one conversation. */
public final class ConversationSearchActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID="chat_id";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String chatId,query; private long beforeSeq; private boolean loading,more;
    private long generation; private Runnable pending;
    private LinearLayout results; private TextView state; private Button next;
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);chatId=getIntent().getStringExtra(EXTRA_CHAT_ID);if(chatId==null){finish();return;}build();}
    private void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(16),dp(10),dp(16),dp(12));root.setBackgroundColor(getColor(R.color.app_bg));
        Button back=new Button(this);back.setText("‹  Tìm trong cuộc trò chuyện");back.setAllCaps(false);back.setOnClickListener(v->finish());root.addView(back);
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Nhập ít nhất 2 ký tự");root.addView(input);
        state=new TextView(this);state.setTextColor(getColor(R.color.text_secondary));root.addView(state);
        ScrollView scroll=new ScrollView(this);results=new LinearLayout(this);results.setOrientation(1);scroll.addView(results);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        next=new Button(this);next.setText("Tải kết quả cũ hơn");next.setVisibility(View.GONE);next.setOnClickListener(v->load(false));root.addView(next);setContentView(root);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){if(pending!=null)handler.removeCallbacks(pending);query=s.toString().trim();long token=++generation;pending=()->{if(token==generation)load(true);};handler.postDelayed(pending,300);}public void afterTextChanged(Editable e){}});
    }
    private void load(boolean reset){if(loading)return;if(query==null||query.length()<2){results.removeAllViews();state.setText("Nhập ít nhất 2 ký tự để tìm tin nhắn.");next.setVisibility(View.GONE);return;}if(reset){beforeSeq=0;more=false;results.removeAllViews();}loading=true;state.setText("Đang tìm…");next.setEnabled(false);String q=query;long cursor=beforeSeq;long token=++generation;
        Api.run(()->{JSONObject page=com.example.meetomxh.supabase.RpcClient.object("search_messages",com.example.meetomxh.supabase.Api.obj("p_conversation_id",chatId,"p_query",q,"p_before_seq",cursor==0?null:cursor,"p_limit",50));return page.optJSONArray("items")==null?new JSONArray():page.getJSONArray("items");},new Api.Callback<JSONArray>(){public void success(JSONArray rows){if(isFinishing())return;loading=false;if(token!=generation||!q.equals(query)){load(true);return;}next.setEnabled(true);more=rows.length()==50;next.setVisibility(more?View.VISIBLE:View.GONE);for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;ChatMessage message=com.example.meetomxh.supabase.Dto.message(row);beforeSeq=message.seq;addResult(message);}state.setText(results.getChildCount()==0?"Không tìm thấy tin nhắn phù hợp.":"");}public void error(String e){if(isFinishing())return;loading=false;if(token!=generation)return;next.setEnabled(true);state.setText(e+" · Chạm để thử lại");state.setOnClickListener(v->load(true));}});
    }
    private void addResult(ChatMessage m){Button row=new Button(this);row.setAllCaps(false);String body=m.getDisplayText();if(body==null||body.trim().isEmpty())body="Tin nhắn";String time="";if(m.getCreatedAt()!=null)time=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(m.getCreatedAt().toDate());row.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);row.setText(body+"\n"+time);row.setOnClickListener(v->{Intent intent=new Intent(this,ChatActivity.class).putExtra(ChatActivity.EXTRA_CHAT_ID,chatId).putExtra(ChatActivity.EXTRA_JUMP_MESSAGE_ID,m.getMessageId()).putExtra(ChatActivity.EXTRA_JUMP_MESSAGE_SEQ,m.seq).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(intent);finish();});results.addView(row,new LinearLayout.LayoutParams(-1,-2));}
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void onDestroy(){generation++;if(pending!=null)handler.removeCallbacks(pending);super.onDestroy();}
}
