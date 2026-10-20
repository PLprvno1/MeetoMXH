package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ItemInboxBinding;
import com.example.meetomxh.models.Inbox;

import java.util.List;
import java.util.ArrayList;

public class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.InboxViewHolder> {

    public interface OnInboxClickListener {
        void onInboxClick(Inbox inbox);
    }

    private final List<Inbox> inboxList = new ArrayList<>();
    private final OnInboxClickListener clickListener;

    public InboxAdapter(OnInboxClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void submitList(List<Inbox> items) {
        List<Inbox> next = items == null ? new ArrayList<>() : new ArrayList<>(items);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return inboxList.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return inboxList.get(oldItemPosition).getId().equals(next.get(newItemPosition).getId());
            }
            @Override public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                Inbox oldItem = inboxList.get(oldItemPosition);
                Inbox newItem = next.get(newItemPosition);
                return equals(oldItem.getChatName(), newItem.getChatName())
                        && equals(oldItem.getLastMessage(), newItem.getLastMessage())
                        && equals(oldItem.getAvatarUrl(), newItem.getAvatarUrl())
                        && equals(oldItem.getTimestamp(), newItem.getTimestamp())
                        && oldItem.isUnread() == newItem.isUnread();
            }
            private boolean equals(String first, String second) {
                return first == null ? second == null : first.equals(second);
            }
        });
        inboxList.clear();
        inboxList.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    @NonNull
    @Override
    public InboxViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemInboxBinding binding = ItemInboxBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new InboxViewHolder(binding, clickListener);
    }

    @Override
    public void onBindViewHolder(@NonNull InboxViewHolder holder, int position) {
        Inbox inbox = inboxList.get(position);
        holder.bind(inbox);
    }

    @Override
    public int getItemCount() {
        return inboxList.size();
    }

    public static class InboxViewHolder extends RecyclerView.ViewHolder {
        private final ItemInboxBinding binding;
        private final OnInboxClickListener clickListener;

        public InboxViewHolder(@NonNull ItemInboxBinding binding, OnInboxClickListener clickListener) {
            super(binding.getRoot());
            this.binding = binding;
            this.clickListener = clickListener;
        }

        public void bind(Inbox inbox) {
            binding.tvChatName.setText(inbox.getChatName());
            binding.tvLastMessage.setText(inbox.getLastMessage());
            binding.tvTimestamp.setText(inbox.getTimestamp());
            binding.viewUnread.setVisibility(inbox.isUnread() ? android.view.View.VISIBLE : android.view.View.GONE);

            if (inbox.getAvatarUrl() != null && !inbox.getAvatarUrl().isEmpty()) {
                Glide.with(binding.ivAvatar.getContext())
                        .load(inbox.getAvatarUrl())
                        .placeholder(com.example.meetomxh.R.drawable.ic_home)
                        .error(com.example.meetomxh.R.drawable.ic_home)
                        .into(binding.ivAvatar);
            } else {
                Glide.with(binding.ivAvatar.getContext()).clear(binding.ivAvatar);
                binding.ivAvatar.setImageResource(com.example.meetomxh.R.drawable.ic_home);
            }
            binding.getRoot().setOnClickListener(v -> {
                if (clickListener != null) clickListener.onInboxClick(inbox);
            });
        }
    }
}
