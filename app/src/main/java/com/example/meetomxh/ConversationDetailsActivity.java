package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.supabase.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-user conversation controls; every setting is authorized by its own RPC. */
public final class ConversationDetailsActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID="chat_id";
    private String chatId,peerId; private Switch muteSwitch; private Button blockButton,reportButton; private TextView conversationName;
    @Override protected void onCreate(Bundle state){super.onCreate(state);chatId=getIntent().getStringExtra(EXTRA_CHAT_ID);if(chatId==null){finish();return;}buildUi();load();}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(ContextCompat.getColor(this,R.color.app_bg));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(8),dp(16),dp(8));header.setBackgroundColor(ContextCompat.getColor(this,R.color.surface_bg));
        TextView back=new TextView(this);back.setText("‹");back.setTextSize(34);back.setTextColor(ContextCompat.getColor(this,R.color.text_primary));header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));back.setOnClickListener(v->finish());
        TextView title=new TextView(this);title.setText("Conversation details");title.setTextSize(18);title.setTextColor(ContextCompat.getColor(this,R.color.text_primary));title.setTypeface(null,1);header.addView(title);root.addView(header);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(20),dp(20),dp(24));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        conversationName=new TextView(this);conversationName.setId(View.generateViewId());conversationName.setText("Loading conversation…");conversationName.setTextSize(20);conversationName.setTextColor(ContextCompat.getColor(this,R.color.text_primary));conversationName.setTypeface(null,1);body.addView(conversationName);
        TextView subtitle=new TextView(this);subtitle.setText("Settings apply only to your account.");subtitle.setTextColor(ContextCompat.getColor(this,R.color.text_secondary));body.addView(subtitle,new LinearLayout.LayoutParams(-1,-2));
        addDivider(body);
        Button gallery=button("Shared photos and videos");body.addView(gallery,lp());gallery.setOnClickListener(v->startActivity(new Intent(this,SharedMediaGalleryActivity.class).putExtra(EXTRA_CHAT_ID,chatId)));
        muteSwitch=new Switch(this);muteSwitch.setText("Mute conversation notifications");muteSwitch.setTextColor(ContextCompat.getColor(this,R.color.text_primary));muteSwitch.setPadding(0,dp(14),0,dp(14));body.addView(muteSwitch,lp());muteSwitch.setOnCheckedChangeListener((b,checked)->setMuted(checked));
        blockButton=button("Block this person");body.addView(blockButton,lp());blockButton.setOnClickListener(v->toggleBlock());
        reportButton=button("Report this person");body.addView(reportButton,lp());reportButton.setOnClickListener(v->{if(peerId!=null)startActivity(new Intent(this,ReportContentActivity.class).putExtra(ReportContentActivity.EXTRA_TARGET_TYPE,"profile").putExtra(ReportContentActivity.EXTRA_TARGET_ID,peerId));});
        blockButton.setVisibility(View.GONE);reportButton.setVisibility(View.GONE);
        setContentView(root);
    }
    private void load(){Api.run(()->{Chat c=ChatRepository.getInstance().load(chatId);JSONObject mute=RpcClient.object("get_conversation_mute",Api.obj("p_conversation_id",chatId));JSONArray blocked=(JSONArray)RpcClient.call("list_blocked_users",new JSONObject());return new Object[]{c,mute,blocked};},new Api.Callback<Object[]>(){public void success(Object[] v){Chat c=(Chat)v[0];JSONObject m=(JSONObject)v[1];JSONArray blocked=(JSONArray)v[2];for(String id:c.getParticipants())if(!id.equals(SessionManager.getInstance().uid()))peerId=id;conversationName.setText(c.title==null?"Conversation":c.title);muteSwitch.setOnCheckedChangeListener(null);muteSwitch.setChecked(m.optBoolean("muted"));muteSwitch.setOnCheckedChangeListener((b,checked)->setMuted(checked));boolean isBlocked=false;for(int i=0;i<blocked.length();i++)if(peerId!=null&&peerId.equals(blocked.optJSONObject(i).optString("user_id")))isBlocked=true;blockButton.setText(isBlocked?"Unblock this person":"Block this person");blockButton.setTag(isBlocked);blockButton.setVisibility("private".equals(c.getType())?View.VISIBLE:View.GONE);reportButton.setVisibility("private".equals(c.getType())?View.VISIBLE:View.GONE);}public void error(String e){Toast.makeText(ConversationDetailsActivity.this,e,Toast.LENGTH_LONG).show();}});}
    private void setMuted(boolean value){muteSwitch.setEnabled(false);Api.run(()->RpcClient.call("set_conversation_muted",Api.obj("p_conversation_id",chatId,"p_muted",value)),new Api.Callback<Object>(){public void success(Object v){muteSwitch.setEnabled(true);Toast.makeText(ConversationDetailsActivity.this,value?"Conversation muted":"Notifications restored",Toast.LENGTH_SHORT).show();}public void error(String e){muteSwitch.setOnCheckedChangeListener(null);muteSwitch.setChecked(!value);muteSwitch.setOnCheckedChangeListener((b,checked)->setMuted(checked));muteSwitch.setEnabled(true);Toast.makeText(ConversationDetailsActivity.this,e,Toast.LENGTH_LONG).show();}});}
    private void toggleBlock(){if(peerId==null)return;boolean blocked=Boolean.TRUE.equals(blockButton.getTag());new AlertDialog.Builder(this).setTitle(blocked?"Unblock this person?":"Block this person?").setMessage(blocked?"They may be able to contact you again.":"They will not be able to send you messages while blocked.").setNegativeButton("Cancel",null).setPositiveButton(blocked?"Unblock":"Block",(d,w)->{blockButton.setEnabled(false);Api.run(()->RpcClient.call("set_block",Api.obj("p_target",peerId,"p_blocked",!blocked)),new Api.Callback<Object>(){public void success(Object r){blockButton.setTag(!blocked);blockButton.setText(!blocked?"Unblock this person":"Block this person");blockButton.setEnabled(true);Toast.makeText(ConversationDetailsActivity.this,!blocked?"Person blocked":"Person unblocked",Toast.LENGTH_SHORT).show();}public void error(String e){blockButton.setEnabled(true);Toast.makeText(ConversationDetailsActivity.this,e,Toast.LENGTH_LONG).show();}});}).show();}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);return b;} private LinearLayout.LayoutParams lp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);p.bottomMargin=dp(8);return p;} private void addDivider(LinearLayout p){View v=new View(this);v.setBackgroundColor(ContextCompat.getColor(this,R.color.divider_color));LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-1,dp(1));l.topMargin=dp(16);l.bottomMargin=dp(16);p.addView(v,l);}private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
}
