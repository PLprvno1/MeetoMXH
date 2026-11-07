package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.OnboardingSuggestionsClient;
import com.example.meetomxh.supabase.OnboardingSuggestionsClient.Person;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ui.AvatarViews;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Optional first-login follow suggestions. A user can follow several profiles before finishing. */
public final class PeopleSuggestionsActivity extends AppCompatActivity {
    private final List<Person> people = new ArrayList<>();
    private final Set<String> loadedIds=new HashSet<>(),followingIds=new HashSet<>();
    private final List<Subscription> requests=new ArrayList<>();
    private Adapter adapter;
    private TextView finishAction, status;
    private boolean loading, anyFollowSelected;
    private boolean hasMore=true,leaving;
    private String afterTime,afterId,userId;
    private Button retry,more;
    private ProgressBar progress;
    private Runnable retryAction;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        userId=SessionManager.getInstance().uid();
        if(userId.isEmpty()){startActivity(new Intent(this,LoginActivity.class));finish();return;}
        anyFollowSelected = OnboardingFlow.socialFollowSelected(this,userId);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.app_bg));
        int pad = dp(16);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(pad, dp(8), pad, dp(8));
        TextView title = new TextView(this);
        title.setText("Gợi ý theo dõi");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        finishAction = new TextView(this);
        finishAction.setText("Bỏ qua");
        finishAction.setTextColor(getColor(R.color.brand_blue));
        finishAction.setTextSize(15);
        finishAction.setTypeface(null, android.graphics.Typeface.BOLD);
        finishAction.setPadding(dp(8), dp(10), dp(4), dp(10));
        finishAction.setOnClickListener(v -> finishSetup());
        top.addView(finishAction);
        root.addView(top);

        TextView intro = new TextView(this);
        intro.setText("Khám phá người dùng trên Meeto. Với tài khoản riêng tư, yêu cầu theo dõi cần được chấp nhận.");
        intro.setTextColor(getColor(R.color.text_secondary));
        intro.setPadding(pad, dp(2), pad, dp(12));
        root.addView(intro);

        status = new TextView(this);
        status.setTextColor(getColor(R.color.text_secondary));
        status.setPadding(pad, dp(8), pad, dp(8));
        root.addView(status);
        progress=new ProgressBar(this);root.addView(progress,new LinearLayout.LayoutParams(dp(28),dp(28)));
        retry=new Button(this);retry.setText("Thử lại");retry.setVisibility(View.GONE);retry.setOnClickListener(v->{if(retryAction!=null)retryAction.run();});root.addView(retry);

        RecyclerView list = new RecyclerView(this);
        LinearLayoutManager layout=new LinearLayoutManager(this);list.setLayoutManager(layout);
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.addOnScrollListener(new RecyclerView.OnScrollListener(){@Override public void onScrolled(@androidx.annotation.NonNull RecyclerView rv,int dx,int dy){if(dy>0&&retry.getVisibility()!=View.VISIBLE&&layout.findLastVisibleItemPosition()>=people.size()-4)load();}});
        more=new Button(this);more.setText("Xem thêm gợi ý");more.setVisibility(View.GONE);more.setOnClickListener(v->load());root.addView(more);
        setContentView(root);
        updateFinishLabel();
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){@Override public void handleOnBackPressed(){finishSetup();}});
        load();
    }

    private void load() {
        if (loading||!hasMore||!alive()) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);retry.setVisibility(View.GONE);more.setEnabled(false);
        status.setText("Đang tải gợi ý…");
        final String time=afterTime,id=afterId;
        requests.add(Api.run(() -> OnboardingSuggestionsClient.page(time,id), new Api.Callback<OnboardingSuggestionsClient.Page>() {
            @Override public void success(OnboardingSuggestionsClient.Page page) {
                if(!alive())return;
                loading = false;
                progress.setVisibility(View.GONE);
                for (Person person:page.people) {
                    if(person.id.equals(userId)||!loadedIds.add(person.id))continue;
                    people.add(person);
                    if (person.following||person.requested) anyFollowSelected = true;
                }
                afterTime=page.afterTime;afterId=page.afterId;hasMore=page.hasMore;
                more.setEnabled(true);more.setVisibility(hasMore?View.VISIBLE:View.GONE);
                updateFinishLabel();
                adapter.notifyDataSetChanged();
                status.setText(people.isEmpty() ? "Chưa có tài khoản đủ điều kiện để gợi ý. Bạn vẫn có thể Bỏ qua." : hasMore?"":"Bạn đã xem hết gợi ý hiện có.");
            }
            @Override public void error(String message) {if(!alive())return;loading=false;progress.setVisibility(View.GONE);more.setEnabled(true);showError(message,PeopleSuggestionsActivity.this::load);}
        }));
    }

    private void follow(Person person) {
        if(!alive()||person.following||person.requested||!followingIds.add(person.id))return;
        notifyPerson(person.id);retry.setVisibility(View.GONE);
        requests.add(Api.run(() -> OnboardingSuggestionsClient.follow(person.id), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject result) {
                if(!alive())return;followingIds.remove(person.id);
                person.following=result.optBoolean("following");person.requested=result.optBoolean("requested");
                if (person.following||person.requested) {
                    anyFollowSelected = true;
                    OnboardingFlow.markSocialFollowSelected(PeopleSuggestionsActivity.this,userId);
                }
                updateFinishLabel();
                notifyPerson(person.id);
                status.setText(person.following?"Đã theo dõi "+person.name:person.requested?"Đã gửi yêu cầu theo dõi "+person.name:"Máy chủ chưa ghi nhận theo dõi. Bạn có thể thử lại.");
            }
            @Override public void error(String message) {
                if(!alive())return;followingIds.remove(person.id);notifyPerson(person.id);showError("Chưa thể theo dõi: "+message,()->follow(person));
            }
        }));
    }

    private void updateFinishLabel() { finishAction.setText(anyFollowSelected ? "Tiếp tục" : "Bỏ qua"); }
    private boolean alive(){return !leaving&&!isFinishing()&&!isDestroyed()&&userId.equals(SessionManager.getInstance().uid());}
    private void showError(String message,Runnable action){status.setText(message);retryAction=action;retry.setVisibility(View.VISIBLE);}
    private void notifyPerson(String id){for(int i=0;i<people.size();i++)if(id.equals(people.get(i).id)){adapter.notifyItemChanged(i);return;}}

    private void finishSetup() {
        if(!alive())return;leaving=true;for(Subscription request:requests)request.remove();
        OnboardingFlow.markSocialSetupDone(this,userId);
        startActivity(new Intent(this, RoleResolutionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
    @Override protected void onDestroy(){for(Subscription request:requests)request.remove();super.onDestroy();}

    private final class Adapter extends RecyclerView.Adapter<PersonHolder> {
        @Override public PersonHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            ImageView avatar = new ImageView(parent.getContext());
            row.addView(avatar, new LinearLayout.LayoutParams(dp(50), dp(50)));
            TextView name = new TextView(parent.getContext());
            name.setTextColor(getColor(R.color.text_primary));
            name.setTextSize(16);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout identity=new LinearLayout(parent.getContext());identity.setOrientation(1);identity.setPadding(dp(12),0,dp(8),0);
            identity.addView(name);TextView username=new TextView(parent.getContext());username.setTextColor(getColor(R.color.text_secondary));username.setTextSize(13);identity.addView(username);
            row.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            Button action = new Button(parent.getContext());
            action.setAllCaps(false);
            row.addView(action);
            return new PersonHolder(row, avatar, name, username, action);
        }
        @Override public void onBindViewHolder(PersonHolder holder, int position) {
            Person person = people.get(position);
            holder.name.setText(person.name);holder.username.setText(person.username.isEmpty()?"":"@"+person.username);holder.username.setVisibility(person.username.isEmpty()?View.GONE:View.VISIBLE);
            AvatarViews.load(holder.avatar, person.avatar,person.preset);
            boolean busy=followingIds.contains(person.id);
            holder.action.setText(busy?"Đang gửi…":person.following ? "Đã theo dõi" : person.requested ? "Đã gửi yêu cầu" : "Theo dõi");
            holder.action.setEnabled(!busy&&!person.following&&!person.requested);
            holder.action.setOnClickListener(v -> follow(person));
        }
        @Override public int getItemCount() { return people.size(); }
    }

    private static final class PersonHolder extends RecyclerView.ViewHolder {
        final ImageView avatar; final TextView name,username; final Button action;
        PersonHolder(View item, ImageView avatar, TextView name, TextView username,Button action) { super(item); this.avatar=avatar; this.name=name;this.username=username;this.action=action; }
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
}
