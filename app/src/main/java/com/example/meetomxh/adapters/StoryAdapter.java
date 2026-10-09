package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.ItemStoryBinding;
import com.example.meetomxh.models.Story;

import java.util.ArrayList;
import java.util.List;

public class StoryAdapter extends RecyclerView.Adapter<StoryAdapter.StoryViewHolder> {

    private final List<Story> stories = new ArrayList<>();

    public void setStories(List<Story> newStories) {
        this.stories.clear();
        if (newStories != null) {
            this.stories.addAll(newStories);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public StoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemStoryBinding binding = ItemStoryBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new StoryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull StoryViewHolder holder, int position) {
        holder.bind(stories.get(position));
    }

    @Override
    public int getItemCount() {
        return stories.size();
    }

    public static class StoryViewHolder extends RecyclerView.ViewHolder {
        private final ItemStoryBinding binding;

        public StoryViewHolder(@NonNull ItemStoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Story story) {
            binding.tvStoryUserName.setText(story.getUserName());

            if (story.isAddStory()) {
                binding.layoutAvatarRing.setVisibility(View.GONE);
                binding.ivAddStoryIcon.setVisibility(View.VISIBLE);
                binding.ivStoryBg.setImageResource(R.drawable.ic_home);
            } else {
                binding.layoutAvatarRing.setVisibility(View.VISIBLE);
                binding.ivAddStoryIcon.setVisibility(View.GONE);

                if (story.getImageUrl() != null && !story.getImageUrl().isEmpty()) {
                    Glide.with(binding.ivStoryBg.getContext())
                            .load(story.getImageUrl())
                            .placeholder(R.drawable.ic_home)
                            .into(binding.ivStoryBg);
                } else {
                    binding.ivStoryBg.setImageResource(R.drawable.ic_home);
                }

                if (story.getAvatarUrl() != null && !story.getAvatarUrl().isEmpty()) {
                    Glide.with(binding.ivStoryAvatar.getContext())
                            .load(story.getAvatarUrl())
                            .placeholder(R.drawable.ic_home)
                            .into(binding.ivStoryAvatar);
                } else {
                    binding.ivStoryAvatar.setImageResource(R.drawable.ic_home);
                }
            }
        }
    }
}
