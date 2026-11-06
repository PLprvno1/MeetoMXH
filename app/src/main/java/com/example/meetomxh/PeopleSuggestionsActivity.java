package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.ui.AvatarViews;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Optional first-login follow suggestions. A user can follow several profiles before finishing. */
public final class PeopleSuggestionsActivity extends AppCompatActivity {
    private final List<JSONObject> people = new ArrayList<>();
    private Adapter adapter;
    private TextView finishAction, status;
    private boolean loading, anyFollowSelected;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        anyFollowSelected = OnboardingFlow.socialFollowSelected(this, SessionManager.getInstance().uid());
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
        intro.setText("Theo dõi những người bạn quan tâm để xem bài viết của họ.");
        intro.setTextColor(getColor(R.color.text_secondary));
        intro.setPadding(pad, dp(2), pad, dp(12));
        root.addView(intro);

        status = new TextView(this);
        status.setTextColor(getColor(R.color.text_secondary));
        status.setPadding(pad, dp(8), pad, dp(8));
        status.setOnClickListener(v -> load());
        root.addView(status);

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        status.setText("Đang tải gợi ý…");
        Api.run(() -> (JSONArray) RpcClient.call("onboarding_suggestions", Api.obj("p_limit", 40)), new Api.Callback<JSONArray>() {
            @Override public void success(JSONArray result) {
                loading = false;
                people.clear();
                for (int i = 0; i < result.length(); i++) {
                    JSONObject row = result.optJSONObject(i);
                    if (row == null) continue;
                    people.add(row);
                    if (row.optBoolean("following") || row.optBoolean("requested")) anyFollowSelected = true;
                }
                updateFinishLabel();
                adapter.notifyDataSetChanged();
                status.setText(people.isEmpty() ? "Chưa có gợi ý phù hợp lúc này." : "");
            }
            @Override public void error(String message) { loading = false; status.setText(message + " · Chạm để thử lại"); }
        });
    }

    private void follow(JSONObject row, Button button) {
        if (button.isEnabled() == false) return;
        String id = row.optString("profile_id");
        button.setEnabled(false);
        Api.run(() -> RpcClient.object("set_follow", Api.obj("p_target", id, "p_follow", true)), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject result) {
                boolean following = result.optBoolean("following");
                boolean requested = result.optBoolean("requested");
                try { row.put("following", following).put("requested", requested); }
                catch (JSONException ignored) { }
                if (following || requested) {
                    anyFollowSelected = true;
                    OnboardingFlow.markSocialFollowSelected(PeopleSuggestionsActivity.this, SessionManager.getInstance().uid());
                }
                button.setEnabled(true);
                updateFinishLabel();
                adapter.notifyDataSetChanged();
            }
            @Override public void error(String message) {
                button.setEnabled(true);
                status.setText(message);
            }
        });
    }

    private void updateFinishLabel() { finishAction.setText(anyFollowSelected ? "Hoàn tất" : "Bỏ qua"); }

    private void finishSetup() {
        OnboardingFlow.markSocialSetupDone(this, SessionManager.getInstance().uid());
        startActivity(new Intent(this, RoleResolutionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

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
            name.setPadding(dp(12), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Button action = new Button(parent.getContext());
            action.setAllCaps(false);
            row.addView(action);
            return new PersonHolder(row, avatar, name, action);
        }
        @Override public void onBindViewHolder(PersonHolder holder, int position) {
            JSONObject person = people.get(position);
            holder.name.setText(person.optString("display_name", "Người dùng"));
            AvatarViews.load(holder.avatar, Dto.asset(Dto.nullable(person, "avatar_asset_id")), person.optString("avatar_preset", "neutral"));
            boolean following = person.optBoolean("following"), requested = person.optBoolean("requested");
            holder.action.setText(following ? "Đang theo dõi" : requested ? "Đã gửi yêu cầu" : "Theo dõi");
            holder.action.setEnabled(!following && !requested);
            holder.action.setOnClickListener(v -> follow(person, holder.action));
        }
        @Override public int getItemCount() { return people.size(); }
    }

    private static final class PersonHolder extends RecyclerView.ViewHolder {
        final ImageView avatar; final TextView name; final Button action;
        PersonHolder(View item, ImageView avatar, TextView name, Button action) { super(item); this.avatar=avatar; this.name=name; this.action=action; }
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
}
