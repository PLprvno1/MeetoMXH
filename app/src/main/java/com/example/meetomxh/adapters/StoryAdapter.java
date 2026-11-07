package com.example.meetomxh.adapters;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.example.meetomxh.R;
import com.example.meetomxh.ReportContentActivity;
import com.example.meetomxh.StoryCreateActivity;
import com.example.meetomxh.databinding.ItemStoryBinding;
import com.example.meetomxh.models.Story;
import com.example.meetomxh.ui.AvatarViews;
import java.util.ArrayList;
import java.util.List;

public class StoryAdapter extends RecyclerView.Adapter<StoryAdapter.Holder> {
    private final List<Story> stories=new ArrayList<>();
    public void setStories(List<Story> rows){stories.clear();if(rows!=null)stories.addAll(rows);notifyDataSetChanged();}
    @NonNull public Holder onCreateViewHolder(@NonNull ViewGroup p,int t){return new Holder(ItemStoryBinding.inflate(LayoutInflater.from(p.getContext()),p,false));}
    public void onBindViewHolder(@NonNull Holder h,int p){h.bind(stories.get(p));}
    public int getItemCount(){return stories.size();}
    static final class Holder extends RecyclerView.ViewHolder {
        final ItemStoryBinding b; Holder(ItemStoryBinding binding){super(binding.getRoot());b=binding;}
        void bind(Story s){
            b.tvStoryUserName.setText(s.getUserName());
            AvatarViews.load(b.ivStoryAvatar,s.getAvatarUrl(),s.getAvatarPreset());
            String owner=s.getAuthorId();
            if(s.isAddStory()&&!com.example.meetomxh.supabase.StoryClient.validId(owner)){
                owner=com.example.meetomxh.supabase.SessionManager.getInstance().uid();s.setAuthorId(owner);
            }
            String id=com.example.meetomxh.supabase.StoryClient.validId(s.getId())?s.getId():null;
            View.OnClickListener open=com.example.meetomxh.ui.StoryNavigation.listener(owner,id);
            b.getRoot().setOnClickListener(open);
            b.tvStoryUserName.setOnClickListener(open);
            b.layoutAvatarRing.setVisibility(View.VISIBLE);b.layoutAvatarRing.setAlpha(1f);
            b.layoutAvatarRing.setOnClickListener(open);
            com.example.meetomxh.ui.StoryRings.bindTray(b.layoutAvatarRing,b.ivStoryAvatar,s,open);
            b.ivAddStoryIcon.setVisibility(s.isAddStory()?View.VISIBLE:View.GONE);
            b.ivAddStoryIcon.setOnClickListener(s.isAddStory()?v->v.getContext().startActivity(new Intent(v.getContext(),StoryCreateActivity.class)):null);
            b.ivAddStoryIcon.setClickable(s.isAddStory());
            Glide.with(b.ivStoryBg).clear(b.ivStoryBg);
            View.OnLongClickListener report=v->{if(s.isAddStory()||id==null)return false;
                new android.app.AlertDialog.Builder(v.getContext()).setItems(new String[]{"Báo cáo tin"},(d,w)->
                    v.getContext().startActivity(new Intent(v.getContext(),ReportContentActivity.class)
                        .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE,"story").putExtra(ReportContentActivity.EXTRA_TARGET_ID,id))).show();return true;
            };
            b.getRoot().setOnLongClickListener(report);b.ivStoryAvatar.setOnLongClickListener(report);
        }
    }
    @Override public void onViewRecycled(@NonNull Holder holder){
        com.example.meetomxh.ui.StoryRings.unbind(holder.b.ivStoryAvatar);super.onViewRecycled(holder);
    }
}
