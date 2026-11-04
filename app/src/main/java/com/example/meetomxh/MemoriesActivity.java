package com.example.meetomxh;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.ActivityMemoriesBinding;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.MemoriesRepository;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SavedPostClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

public class MemoriesActivity extends AppCompatActivity {
    private enum Mode { TODAY, YEAR, HIDDEN, SETTINGS }
    private ActivityMemoriesBinding binding;
    private FeedAdapter adapter;
    private Mode mode=Mode.TODAY;
    private String afterTime,afterId,timezone="UTC";
    private boolean loading,hasMore,settingsReady,memoriesEnabled=true,remindersEnabled;
    private int yearsAgo=1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding=ActivityMemoriesBinding.inflate(getLayoutInflater());setContentView(binding.getRoot());
        if(SessionManager.getInstance().getCurrentUser()==null){finish();return;}
        if(state!=null){mode=Mode.valueOf(state.getString("mode",Mode.TODAY.name()));yearsAgo=Math.max(1,Math.min(20,state.getInt("years",1)));}
        binding.rvMemories.setLayoutManager(new LinearLayoutManager(this));
        installAdapter(mode==Mode.HIDDEN);
        String[] years=new String[20];for(int i=0;i<years.length;i++)years[i]=(i+1)+" năm trước";
        binding.spinnerYears.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,years));
        binding.spinnerYears.setSelection(Math.max(0,Math.min(19,yearsAgo-1)));
        binding.btnBack.setOnClickListener(v->finish());
        binding.btnRefresh.setOnClickListener(v->load(false));
        binding.btnRetry.setOnClickListener(v->{if(mode==Mode.SETTINGS)loadSettingsUi();else load(false);});
        binding.btnLoadMore.setOnClickListener(v->load(true));
        binding.btnToday.setOnClickListener(v->switchMode(Mode.TODAY));
        binding.btnYears.setOnClickListener(v->switchMode(Mode.YEAR));
        binding.btnHidden.setOnClickListener(v->switchMode(Mode.HIDDEN));
        binding.btnSettings.setOnClickListener(v->switchMode(Mode.SETTINGS));
        binding.spinnerYears.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){int value=position+1;if(yearsAgo!=value){yearsAgo=value;if(mode==Mode.YEAR)load(false);}}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        binding.swipeRefresh.setOnRefreshListener(()->load(false));
        binding.rvMemories.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener(){
            @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView rv,int dx,int dy){
                LinearLayoutManager lm=(LinearLayoutManager)rv.getLayoutManager();
                if(dy>0&&lm!=null&&lm.findLastVisibleItemPosition()>=adapter.getItemCount()-3&&hasMore&&!loading)load(true);
            }
        });
        MemoriesRepository.settings(new Api.Callback<JSONObject>(){
            @Override public void success(JSONObject s){
                memoriesEnabled=s.optBoolean("enabled",true);remindersEnabled=s.optBoolean("reminders_enabled",false);
                String deviceZone=TimeZone.getDefault().getID();timezone=s.optString("timezone","UTC");
                if(!timezone.equals(deviceZone)){
                    MemoriesRepository.updateSettings(memoriesEnabled,remindersEnabled,deviceZone,new Api.Callback<JSONObject>(){
                        @Override public void success(JSONObject updated){timezone=updated.optString("timezone",deviceZone);settingsReady=true;showMode();if(mode==Mode.SETTINGS)loadSettingsUi();else load(false);}
                        @Override public void error(String e){settingsReady=true;showMode();if(mode==Mode.SETTINGS)loadSettingsUi();else load(false);}
                    });
                }else{settingsReady=true;showMode();if(mode==Mode.SETTINGS)loadSettingsUi();else load(false);}
            }
            @Override public void error(String e){settingsReady=true;binding.tvStatus.setText("Không tải được cài đặt kỷ niệm.");binding.btnRetry.setVisibility(View.VISIBLE);}
        });
    }

    private void installAdapter(boolean hidden){
        adapter=new FeedAdapter(new FeedAdapter.OnPostClickListener(){
            @Override public void onLikeClick(Post p,Runnable done){
                com.example.meetomxh.repositories.FeedRepository.getInstance().setReaction(p.getPostId(),p.isLiked()?null:"like",new com.example.meetomxh.repositories.FeedRepository.OnPostActionListener(){
                    @Override public void onSuccess(String message){done.run();load(false);}
                    @Override public void onFailure(String error){done.run();toast(error);}
                });
            }
            @Override public void onCommentClick(Post p){CommentsBottomSheetFragment.newInstance(p.getPostId()).show(getSupportFragmentManager(),"MemoryComments");}
            @Override public void onShareClick(Post p){shareMemory(p);}
            @Override public void onCreatePostClick(){}
            @Override public void onAuthorClick(Post p){SessionUser u=SessionManager.getInstance().getCurrentUser();if(u!=null)startActivity(new android.content.Intent(MemoriesActivity.this,ProfileActivity.class).putExtra("target_uid",u.getUid()));}
            @Override public void onDeletePostClick(Post p){}
            @Override public void onSavePostClick(Post p,boolean save,Runnable done){
                SavedPostClient.set(p,save,new Api.Callback<Boolean>(){
                    @Override public void success(Boolean result){done.run();p.setSaved(save);toast(save?"Đã lưu bài viết.":"Đã bỏ lưu bài viết.");}
                    @Override public void error(String e){done.run();toast(e);}
                });
            }
            @Override public void onMemoryVisibilityClick(Post p,boolean hide,Runnable done){
                Api.run(()->RpcClient.call(hide?"hide_memory":"unhide_memory",com.example.meetomxh.supabase.Api.obj("p_post_id",p.getPostId())),new Api.Callback<Object>(){
                    @Override public void success(Object ignored){done.run();toast(hide?"Đã ẩn khỏi kỷ niệm.":"Đã bỏ ẩn kỷ niệm.");load(false);}
                    @Override public void error(String e){done.run();toast(e);}
                });
            }
        },false,true,hidden);
        binding.rvMemories.setAdapter(adapter);
    }

    private void switchMode(Mode next){
        if(mode==next)return;mode=next;afterTime=null;afterId=null;hasMore=false;
        if(next==Mode.HIDDEN)installAdapter(true);else if(mode==Mode.TODAY||mode==Mode.YEAR)installAdapter(false);
        showMode();if(next==Mode.SETTINGS){loadSettingsUi();}else load(false);
    }

    private void showMode(){
        boolean settings=mode==Mode.SETTINGS;
        binding.layoutSettings.setVisibility(settings?View.VISIBLE:View.GONE);
        binding.layoutYearPicker.setVisibility(mode==Mode.YEAR?View.VISIBLE:View.GONE);
        binding.swipeRefresh.setVisibility(settings?View.GONE:View.VISIBLE);
        binding.tvEmpty.setVisibility(View.GONE);binding.btnRetry.setVisibility(View.GONE);binding.btnLoadMore.setVisibility(View.GONE);
        binding.btnRefresh.setVisibility(settings?View.GONE:View.VISIBLE);
        if(mode==Mode.TODAY)binding.tvStatus.setText("Ngày này năm xưa");
        else if(mode==Mode.YEAR)binding.tvStatus.setText("Bài viết của bạn · "+yearsAgo+" năm trước");
        else if(mode==Mode.HIDDEN)binding.tvStatus.setText("Kỷ niệm bạn đã ẩn");
        else binding.tvStatus.setText("Cài đặt kỷ niệm");
    }

    private void loadSettingsUi(){
        MemoriesRepository.settings(new Api.Callback<JSONObject>(){
            @Override public void success(JSONObject s){
                memoriesEnabled=s.optBoolean("enabled",true);remindersEnabled=s.optBoolean("reminders_enabled",false);timezone=s.optString("timezone",timezone);
                binding.switchMemoriesEnabled.setChecked(memoriesEnabled);binding.switchRemindersEnabled.setChecked(remindersEnabled);
                binding.tvTimezone.setText("Múi giờ: "+timezone+" (theo thiết bị hiện tại)");
                binding.btnSaveSettings.setOnClickListener(v->saveSettings());
            }
            @Override public void error(String e){binding.tvStatus.setText("Không tải được cài đặt.");binding.btnRetry.setVisibility(View.VISIBLE);}
        });
    }

    private void saveSettings(){
        boolean enabled=binding.switchMemoriesEnabled.isChecked(), reminders=binding.switchRemindersEnabled.isChecked();
        String zone=TimeZone.getDefault().getID();binding.btnSaveSettings.setEnabled(false);
        MemoriesRepository.updateSettings(enabled,reminders,zone,new Api.Callback<JSONObject>(){
            @Override public void success(JSONObject result){binding.btnSaveSettings.setEnabled(true);memoriesEnabled=enabled;remindersEnabled=reminders;timezone=result.optString("timezone",zone);toast("Đã lưu cài đặt kỷ niệm.");if(!enabled)switchMode(Mode.TODAY);}
            @Override public void error(String e){binding.btnSaveSettings.setEnabled(true);toast(e);}
        });
    }

    private void load(boolean append){
        if(loading||mode==Mode.SETTINGS||!settingsReady)return;
        if(!append){afterTime=null;afterId=null;hasMore=false;}
        loading=true;binding.swipeRefresh.setRefreshing(!append);binding.btnRetry.setVisibility(View.GONE);
        binding.btnLoadMore.setVisibility(View.GONE);binding.tvEmpty.setVisibility(View.GONE);
        binding.tvStatus.setText(append?"Đang tải thêm…":"Đang tải kỷ niệm…");
        Api.Callback<MemoriesRepository.Page> cb=new Api.Callback<MemoriesRepository.Page>(){
            @Override public void success(MemoriesRepository.Page page){
                loading=false;binding.swipeRefresh.setRefreshing(false);afterTime=page.afterTime;afterId=page.afterId;hasMore=page.hasMore;
                if(append)adapter.appendPosts(page.posts);else adapter.setPosts(page.posts);
                binding.tvStatus.setText(page.posts.isEmpty()&&!append?"":"Đã tải xong");
                binding.tvEmpty.setText(mode==Mode.HIDDEN?"Bạn chưa ẩn kỷ niệm nào.":!memoriesEnabled?"Kỷ niệm đang tắt trong cài đặt.":mode==Mode.YEAR?"Chưa có bài viết trong năm này.":"Chưa có kỷ niệm vào ngày này.");
                binding.tvEmpty.setVisibility(adapter.getItemCount()==0?View.VISIBLE:View.GONE);
                binding.btnLoadMore.setVisibility(hasMore?View.VISIBLE:View.GONE);
            }
            @Override public void error(String e){loading=false;binding.swipeRefresh.setRefreshing(false);binding.tvStatus.setText("Không tải được kỷ niệm.");binding.btnRetry.setVisibility(View.VISIBLE);if(!append){adapter.setPosts(new ArrayList<>());binding.tvEmpty.setVisibility(View.GONE);}toast(e);}
        };
        if(mode==Mode.TODAY)MemoriesRepository.today(append?afterTime:null,append?afterId:null,cb);
        else if(mode==Mode.YEAR)MemoriesRepository.byYear(yearsAgo,append?afterTime:null,append?afterId:null,cb);
        else MemoriesRepository.hidden(append?afterTime:null,append?afterId:null,cb);
    }

    private void shareMemory(Post post){
        if(!post.canShareMemory){toast("Bài viết này không thể được chia sẻ lại.");return;}
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(24,8,24,8);
        TextView preview=new TextView(this);preview.setText("Bài viết gốc của bạn: "+(post.getContent()==null?"(không có nội dung)":post.getContent()));form.addView(preview);
        EditText note=new EditText(this);note.setHint("Thêm lời nhắn");form.addView(note);
        RadioGroup audience=new RadioGroup(this);audience.setOrientation(RadioGroup.VERTICAL);
        android.widget.RadioButton pub=new android.widget.RadioButton(this);pub.setText("Công khai");pub.setId(View.generateViewId());
        android.widget.RadioButton friends=new android.widget.RadioButton(this);friends.setText("Bạn bè");friends.setId(View.generateViewId());
        android.widget.RadioButton onlyMe=new android.widget.RadioButton(this);onlyMe.setText("Chỉ mình tôi");onlyMe.setId(View.generateViewId());
        audience.addView(pub);audience.addView(friends);audience.addView(onlyMe);pub.setChecked(true);form.addView(audience);
        new AlertDialog.Builder(this).setTitle("Chia sẻ kỷ niệm").setMessage("Bài mới liên kết tới bài gốc. Người xem chỉ thấy nội dung gốc nếu vẫn có quyền truy cập.")
                .setView(form).setNegativeButton("Hủy",null).setPositiveButton("Chia sẻ",(d,w)->{
                    String visibility=audience.getCheckedRadioButtonId()==friends.getId()?"friends":audience.getCheckedRadioButtonId()==onlyMe.getId()?"private":"public";
                    String body=note.getText().toString();String fingerprint=post.getPostId()+"|"+body+"|"+visibility;
                    String request=RequestIds.get("memory_share",fingerprint);toast("Đang chia sẻ…");
                    Api.run(()->{Object result=RpcClient.call("share_post",com.example.meetomxh.supabase.Api.obj("p_original",post.getPostId(),"p_body",body,"p_audience",visibility,"p_client_request_id",request));RequestIds.complete("memory_share",fingerprint);return result;},new Api.Callback<Object>(){
                        @Override public void success(Object ignored){toast("Đã chia sẻ kỷ niệm lên Feed.");com.example.meetomxh.repositories.FeedRepository.getInstance().refresh();}
                        @Override public void error(String e){toast(e);}
                    });
                }).show();
    }

    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("mode",mode.name());out.putInt("years",yearsAgo);}
}
