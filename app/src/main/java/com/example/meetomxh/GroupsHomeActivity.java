package com.example.meetomxh;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import com.bumptech.glide.Glide;
import com.example.meetomxh.supabase.Dto;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.databinding.ActivityGroupsHomeBinding;
import com.example.meetomxh.repositories.CommunityGroupRepository;
import org.json.JSONArray;
import org.json.JSONObject;

public class GroupsHomeActivity extends AppCompatActivity {
    private ActivityGroupsHomeBinding binding;
    private LinearLayout rows;
    private TextView state;
    private boolean mine = true, loading;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = () -> load();

    @Override protected void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        binding = ActivityGroupsHomeBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnCreateGroup.setOnClickListener(v -> startActivity(new Intent(this, CreateCommunityGroupActivity.class)));
        LinearLayout content = (LinearLayout)((androidx.core.widget.NestedScrollView)binding.getRoot().getChildAt(2)).getChildAt(0);
        binding.tvGroupsEmptyTitle.setVisibility(View.GONE);
        binding.tvGroupsEmptySubtitle.setVisibility(View.GONE);
        if (content.getChildCount() > 0) content.getChildAt(0).setVisibility(View.GONE);
        if (content.getChildCount() > 3) content.getChildAt(3).setVisibility(View.GONE);
        com.google.android.material.button.MaterialButton invites=new com.google.android.material.button.MaterialButton(this);invites.setText("Lời mời nhóm");invites.setAllCaps(false);invites.setOnClickListener(v->startActivity(new Intent(this,CommunityGroupInvitesActivity.class)));content.addView(invites,1);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(12, 8, 12, 24);
        content.addView(rows);
        state = new TextView(this); state.setTextColor(ContextCompat.getColor(this, R.color.text_secondary)); state.setPadding(16, 20, 16, 20); state.setGravity(android.view.Gravity.CENTER);
        rows.addView(state);
        binding.tabMyGroups.setOnClickListener(v -> { mine = true; paintTabs(); load(); });
        binding.tabDiscoverGroups.setOnClickListener(v -> { mine = false; paintTabs(); load(); });
        binding.etSearchGroups.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int c,int a) {}
            public void onTextChanged(CharSequence s,int st,int before,int count) { handler.removeCallbacks(searchRunnable); handler.postDelayed(searchRunnable, 350); }
            public void afterTextChanged(Editable e) {}
        });
        paintTabs(); load();
    }
    private void paintTabs() {
        int blue=ContextCompat.getColor(this,R.color.brand_blue), neutral=ContextCompat.getColor(this,R.color.surface_variant), white=ContextCompat.getColor(this,R.color.white), dark=ContextCompat.getColor(this,R.color.text_primary);
        binding.tabMyGroups.setBackgroundTintList(ColorStateList.valueOf(mine?blue:neutral)); binding.tabMyGroups.setTextColor(mine?white:dark);
        binding.tabDiscoverGroups.setBackgroundTintList(ColorStateList.valueOf(mine?neutral:blue)); binding.tabDiscoverGroups.setTextColor(mine?dark:white);
    }
    private void load() {
        if (loading || rows == null) return; loading=true; rows.removeAllViews(); ProgressBar progress=new ProgressBar(this); rows.addView(progress); state=null;
        CommunityGroupRepository.search(binding.etSearchGroups.getText().toString().trim(),mine,null,null,new CommunityGroupRepository.Callback<JSONArray>() {
            public void success(JSONArray groups) { loading=false; rows.removeAllViews(); if(groups.length()==0){ showState(mine?"Bạn chưa tham gia nhóm nào":"Chưa tìm thấy nhóm phù hợp",null); return; }
                for(int i=0;i<groups.length();i++){ JSONObject g=groups.optJSONObject(i); if(g!=null) addGroup(g); }
            }
            public void error(String message) { loading=false; rows.removeAllViews(); showState("Không tải được danh sách nhóm. Chạm để thử lại.",message); }
        });
    }
    private void showState(String title,String error) { TextView label=new TextView(this); label.setText(title); label.setTextColor(ContextCompat.getColor(this,R.color.text_secondary)); label.setGravity(android.view.Gravity.CENTER); label.setPadding(20,28,20,28); rows.addView(label); if(error!=null) label.setOnClickListener(v->load()); }
    private void addGroup(JSONObject g) {
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(18,16,18,16); card.setBackgroundResource(R.drawable.bg_card_rounded);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(4,6,4,6); rows.addView(card,lp);
        ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setImageResource(R.drawable.ic_groups);LinearLayout.LayoutParams coverLp=new LinearLayout.LayoutParams(-1,120);card.addView(cover,coverLp);String ref=Dto.asset(Dto.nullable(g,"cover_asset_id"));if(!ref.isEmpty())Glide.with(this).load(ref).placeholder(R.drawable.ic_groups).error(R.drawable.ic_groups).into(cover);
        TextView name=new TextView(this); name.setText(g.optString("name","Nhóm cộng đồng")); name.setTextSize(17); name.setTypeface(null,android.graphics.Typeface.BOLD); name.setTextColor(ContextCompat.getColor(this,R.color.text_primary)); card.addView(name);
        TextView meta=new TextView(this); meta.setText(("public".equals(g.optString("privacy"))?"Công khai":"private".equals(g.optString("privacy"))?"Riêng tư":"Ẩn")+" · "+g.optInt("member_count")+" thành viên"); meta.setTextColor(ContextCompat.getColor(this,R.color.text_secondary)); card.addView(meta);
        TextView desc=new TextView(this); desc.setText(g.optString("description","")); desc.setMaxLines(2); desc.setTextColor(ContextCompat.getColor(this,R.color.text_secondary)); card.addView(desc);
        card.setOnClickListener(v->startActivity(new Intent(this,CommunityGroupDetailActivity.class).putExtra("group_id",g.optString("id"))));
    }
    @Override protected void onResume(){super.onResume(); if(binding!=null)load();}
}
