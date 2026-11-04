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
import com.example.meetomxh.StoryViewActivity;
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
        void bind(Story s){b.tvStoryUserName.setText(s.getUserName());
            AvatarViews.load(b.ivStoryAvatar,s.getAvatarUrl(),s.getAvatarPreset());
            if (s.isAddStory()) {
                b.layoutAvatarRing.setVisibility(View.GONE);
                b.ivAddStoryIcon.setVisibility(View.VISIBLE);
                b.ivStoryBg.setImageResource(R.drawable.avatar_neutral);
            } else {
                b.layoutAvatarRing.setVisibility(View.VISIBLE);
                b.ivAddStoryIcon.setVisibility(View.GONE);
                b.layoutAvatarRing.setBackgroundResource(s.isViewed() ? R.drawable.shape_story_ring_seen : R.drawable.shape_story_ring);
                b.layoutAvatarRing.setAlpha(s.isViewed() ? 0.7f : 1f);
                Glide.with(b.ivStoryBg).load(s.getImageUrl()).placeholder(R.drawable.avatar_neutral).error(R.drawable.ic_media_error).into(b.ivStoryBg);
            }
            b.getRoot().setOnClickListener(v -> {
                b.getRoot().animate().scaleX(0.92f).scaleY(0.92f).setDuration(70)
                        .withEndAction(() -> {
                            b.getRoot().animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start();
                            if (s.isAddStory()) v.getContext().startActivity(new Intent(v.getContext(), StoryCreateActivity.class));
                            else v.getContext().startActivity(new Intent(v.getContext(), StoryViewActivity.class).putExtra(StoryViewActivity.EXTRA_AUTHOR_ID, s.getAuthorId()).putExtra(StoryViewActivity.EXTRA_STORY_ID, s.getId()));
                        }).start();
            });
            b.getRoot().setOnLongClickListener(v->{if(s.isAddStory())return false;
                new android.app.AlertDialog.Builder(v.getContext()).setItems(new String[]{"Báo cáo tin"},(d,w)->
                        v.getContext().startActivity(new Intent(v.getContext(),ReportContentActivity.class)
                                .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE,"story")
                                .putExtra(ReportContentActivity.EXTRA_TARGET_ID,s.getId()))).show();return true;});
        }
    }
}
