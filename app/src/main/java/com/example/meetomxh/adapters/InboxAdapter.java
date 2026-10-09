package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.databinding.ItemInboxBinding;
import com.example.meetomxh.models.Inbox;

import java.util.List;

public class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.InboxViewHolder> {

    private final List<Inbox> inboxList;

    public InboxAdapter(List<Inbox> inboxList) {
        this.inboxList = inboxList;
    }

    @NonNull
    @Override
    public InboxViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemInboxBinding binding = ItemInboxBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new InboxViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull InboxViewHolder holder, int position) {
        Inbox inbox = inboxList.get(position);
        holder.bind(inbox);
    }

    @Override
    public int getItemCount() {
        return inboxList != null ? inboxList.size() : 0;
    }

    public static class InboxViewHolder extends RecyclerView.ViewHolder {
        private final ItemInboxBinding binding;

        public InboxViewHolder(@NonNull ItemInboxBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Inbox inbox) {
            binding.tvChatName.setText(inbox.getChatName());
            binding.tvLastMessage.setText(inbox.getLastMessage());
            binding.tvTimestamp.setText(inbox.getTimestamp());

            if (inbox.getAvatarUrl() != null && !inbox.getAvatarUrl().isEmpty()) {
                Glide.with(binding.ivAvatar.getContext())
                        .load(inbox.getAvatarUrl())
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .into(binding.ivAvatar);
            } else {
                binding.ivAvatar.setImageResource(android.R.drawable.ic_menu_gallery);
            }
        }
    }
}
