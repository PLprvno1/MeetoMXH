package com.example.meetomxh;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.example.meetomxh.adapters.NotificationAdapter;
import com.example.meetomxh.databinding.ActivityNotificationsBinding;
import com.example.meetomxh.models.NotificationItem;
import com.example.meetomxh.repositories.NotificationRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Observer;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class NotificationsActivity extends AppCompatActivity {
    private static final int PAGE_SIZE=20;
    private ActivityNotificationsBinding binding;
    private NotificationAdapter adapter;
    private Observer<JSONArray> observer;
    private Subscription pageRequest;
    private boolean unreadOnly,loadingMore,hasMore=true;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);binding=ActivityNotificationsBinding.inflate(getLayoutInflater());setContentView(binding.getRoot());
        adapter=new NotificationAdapter(this::openNotification);
        LinearLayoutManager manager=new LinearLayoutManager(this);
        binding.rvNotifications.setLayoutManager(manager);binding.rvNotifications.setAdapter(adapter);
        binding.btnBack.setOnClickListener(v->finish());
        binding.btnFilterAll.setOnClickListener(v->{unreadOnly=false;selectFilter();observe();});
        binding.btnFilterUnread.setOnClickListener(v->{unreadOnly=true;selectFilter();observe();});
        binding.btnMarkAllRead.setOnClickListener(v->markAll());
        binding.btnNotificationsRetry.setOnClickListener(v->observe());
        binding.swipeNotifications.setOnRefreshListener(()->{if(observer!=null)observer.refresh();else observe();});
        binding.rvNotifications.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener(){
            @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView rv,int dx,int dy){
                int last=manager.findLastVisibleItemPosition();
                if(dy>0&&hasMore&&!loadingMore&&last>=adapter.getItemCount()-4)loadMore();
            }
        });
        selectFilter();observe();
        String pushId=getIntent().getStringExtra("push_notification_id"),recipient=getIntent().getStringExtra("push_recipient");
        if(com.example.meetomxh.supabase.StoryClient.validId(pushId)&&com.example.meetomxh.supabase.SessionManager.getInstance().uid().equals(recipient)){
            try{JSONObject row=new JSONObject(getIntent().getStringExtra("push_row"));
                if(pushId.equals(row.optString("id")))openNotification(new NotificationItem(row));
            }catch(Exception ignored){}
        }
    }
    private void selectFilter(){
        int blue=ContextCompat.getColor(this,R.color.brand_blue),white=ContextCompat.getColor(this,R.color.white);
        int bg=ContextCompat.getColor(this,R.color.surface_variant),text=ContextCompat.getColor(this,R.color.text_primary);
        binding.btnFilterAll.setBackgroundTintList(ColorStateList.valueOf(unreadOnly?bg:blue));
        binding.btnFilterAll.setTextColor(unreadOnly?text:white);
        binding.btnFilterUnread.setBackgroundTintList(ColorStateList.valueOf(unreadOnly?blue:bg));
        binding.btnFilterUnread.setTextColor(unreadOnly?white:text);
    }
    private void observe(){
        if(observer!=null)observer.remove();if(pageRequest!=null)pageRequest.remove();
        loadingMore=false;hasMore=true;showStatus("Đang tải thông báo…",false);
        binding.layoutNotificationsEmpty.setVisibility(View.GONE);
        observer=new Observer<>(()->NotificationRepository.page(unreadOnly,null,null,PAGE_SIZE),new Api.Callback<JSONArray>(){
            @Override public void success(JSONArray rows){
                List<NotificationItem> data=parse(rows);adapter.replace(data);hasMore=rows.length()==PAGE_SIZE;
                binding.layoutNotificationsEmpty.setVisibility(data.isEmpty()?View.VISIBLE:View.GONE);
                binding.rvNotifications.setVisibility(data.isEmpty()?View.GONE:View.VISIBLE);
                binding.swipeNotifications.setRefreshing(false);
                hideStatus();
            }
            @Override public void error(String error){binding.swipeNotifications.setRefreshing(false);showStatus(error,true);binding.rvNotifications.setVisibility(View.GONE);}
        });
    }
    private void loadMore(){
        if(adapter.getItemCount()==0)return;
        NotificationItem last=adapter.get(adapter.getItemCount()-1);loadingMore=true;
        pageRequest=Api.run(()->NotificationRepository.page(unreadOnly,last.createdAt,last.id,PAGE_SIZE),new Api.Callback<JSONArray>(){
            @Override public void success(JSONArray rows){List<NotificationItem> data=parse(rows);adapter.append(data);hasMore=rows.length()==PAGE_SIZE;loadingMore=false;}
            @Override public void error(String error){loadingMore=false;Toast.makeText(NotificationsActivity.this,error,Toast.LENGTH_SHORT).show();}
        });
    }
    private List<NotificationItem> parse(JSONArray rows){List<NotificationItem> out=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o!=null)out.add(new NotificationItem(o));}return out;}
    private void showStatus(String text,boolean retry){binding.layoutNotificationsStatus.setVisibility(View.VISIBLE);binding.tvNotificationsStatus.setText(text);binding.btnNotificationsRetry.setVisibility(retry?View.VISIBLE:View.GONE);}
    private void hideStatus(){binding.layoutNotificationsStatus.setVisibility(View.GONE);}
    private void markAll(){
        binding.btnMarkAllRead.setEnabled(false);
        Api.run(()->{NotificationRepository.markAllRead();return true;},new Api.Callback<Boolean>(){
            @Override public void success(Boolean ignored){binding.btnMarkAllRead.setEnabled(true);if(observer!=null)observer.refresh();}
            @Override public void error(String error){binding.btnMarkAllRead.setEnabled(true);Toast.makeText(NotificationsActivity.this,error,Toast.LENGTH_LONG).show();}
        });
    }
    private void openNotification(NotificationItem item){
        if(!item.targetAvailable){Toast.makeText(this,"Nội dung này hiện không còn khả dụng.",Toast.LENGTH_SHORT).show();return;}
        Api.run(()->{NotificationRepository.markRead(item.id);return true;},new Api.Callback<Boolean>(){
            @Override public void success(Boolean ignored){if(observer!=null)observer.refresh();navigate(item);}
            @Override public void error(String error){Toast.makeText(NotificationsActivity.this,error,Toast.LENGTH_LONG).show();}
        });
    }
    private void navigate(NotificationItem item){
        if("follow_request".equals(item.kind)||"message_request".equals(item.kind)||"group_invite".equals(item.kind)){startActivity(new Intent(this,RequestCenterActivity.class).putExtra("mode","follow_request".equals(item.kind)?"follow":"message_request".equals(item.kind)?"message":"group"));return;}
        switch(item.targetType){
            case "post":
                com.example.meetomxh.supabase.PostDetails.show(this,item.targetId);break;
            case "comment":
                if(item.targetContextId!=null&&!item.targetContextId.isEmpty())
                    CommentsBottomSheetFragment.newInstance(item.targetContextId).show(getSupportFragmentManager(),"notification_comments");
                else Toast.makeText(this,"Bài viết không còn khả dụng.",Toast.LENGTH_SHORT).show();
                break;
            case "profile":
                startActivity(new Intent(this,ProfileActivity.class).putExtra("target_uid",item.targetId));break;
            case "conversation":
                startActivity(new Intent(this,ChatActivity.class).putExtra(ChatActivity.EXTRA_CHAT_ID,item.targetId));break;
            case "story":
                startActivity(new Intent(this,StoryViewActivity.class).putExtra(StoryViewActivity.EXTRA_STORY_ID,item.targetId));break;
            default: Toast.makeText(this,"Đích đến chưa được hỗ trợ.",Toast.LENGTH_SHORT).show();
        }
    }
    @Override protected void onDestroy(){if(observer!=null)observer.remove();if(pageRequest!=null)pageRequest.remove();binding=null;super.onDestroy();}
}
