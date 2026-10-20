package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.ItemFriendBinding;
import com.example.meetomxh.models.FriendItem;
import com.example.meetomxh.models.User;

import java.util.ArrayList;
import java.util.List;

public class FriendItemAdapter extends RecyclerView.Adapter<FriendItemAdapter.FriendViewHolder> {

    public interface OnFriendItemClickListener {
        void onItemClick(User user);
        void onPrimaryActionClick(FriendItem item);
        void onSecondaryActionClick(FriendItem item);
    }

    private final List<FriendItem> items = new ArrayList<>();
    private final OnFriendItemClickListener listener;

    public FriendItemAdapter(OnFriendItemClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<FriendItem> newItems) {
        this.items.clear();
        if (newItems != null) {
            this.items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FriendViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFriendBinding binding = ItemFriendBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new FriendViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull FriendViewHolder holder, int position) {
        holder.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class FriendViewHolder extends RecyclerView.ViewHolder {
        private final ItemFriendBinding binding;

        public FriendViewHolder(@NonNull ItemFriendBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(FriendItem item, OnFriendItemClickListener listener) {
            User user = item.getUser();
            if (user == null) return;

            String displayName = user.getFullName();
            if (displayName == null || displayName.trim().isEmpty()) displayName = user.getEmail();
            if (displayName == null || displayName.trim().isEmpty()) displayName = "Người dùng";
            binding.tvFriendName.setText(displayName);

            if (user.getAvatarUrl() != null && !user.getAvatarUrl().isEmpty()) {
                Glide.with(binding.ivFriendAvatar.getContext())
                        .load(user.getAvatarUrl())
                        .placeholder(R.drawable.ic_home)
                        .error(R.drawable.ic_home)
                        .into(binding.ivFriendAvatar);
            } else {
                Glide.with(binding.ivFriendAvatar.getContext()).clear(binding.ivFriendAvatar);
                binding.ivFriendAvatar.setImageResource(R.drawable.ic_home);
            }

            // Relationship Status Buttons Logic
            FriendItem.RelationshipStatus status = item.getRelationshipStatus();
            if (status == FriendItem.RelationshipStatus.NO_RELATION) {
                binding.tvFriendSubtext.setText("Gợi ý kết bạn");
                binding.btnPrimaryAction.setText("Thêm bạn bè");
                binding.btnPrimaryAction.setVisibility(View.VISIBLE);
                binding.btnSecondaryAction.setVisibility(View.GONE);
            } else if (status == FriendItem.RelationshipStatus.PENDING_SENT) {
                binding.tvFriendSubtext.setText("Đã gửi lời mời kết bạn");
                binding.btnPrimaryAction.setText("Hủy lời mời");
                binding.btnPrimaryAction.setVisibility(View.VISIBLE);
                binding.btnSecondaryAction.setVisibility(View.GONE);
            } else if (status == FriendItem.RelationshipStatus.PENDING_RECEIVED) {
                binding.tvFriendSubtext.setText("Đã gửi cho bạn lời mời kết bạn");
                binding.btnPrimaryAction.setText("Chấp nhận");
                binding.btnSecondaryAction.setText("Xóa / Từ chối");
                binding.btnPrimaryAction.setVisibility(View.VISIBLE);
                binding.btnSecondaryAction.setVisibility(View.VISIBLE);
            } else if (status == FriendItem.RelationshipStatus.ACCEPTED) {
                binding.tvFriendSubtext.setText("Bạn bè");
                binding.btnPrimaryAction.setText("Hủy kết bạn");
                binding.btnPrimaryAction.setVisibility(View.VISIBLE);
                binding.btnSecondaryAction.setVisibility(View.GONE);
            } else { // SELF
                binding.tvFriendSubtext.setText("Trang cá nhân của bạn");
                binding.btnPrimaryAction.setVisibility(View.GONE);
                binding.btnSecondaryAction.setVisibility(View.GONE);
            }

            // Click Listeners
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(user);
            });

            binding.btnPrimaryAction.setOnClickListener(v -> {
                if (listener != null) listener.onPrimaryActionClick(item);
            });

            binding.btnSecondaryAction.setOnClickListener(v -> {
                if (listener != null) listener.onSecondaryActionClick(item);
            });
        }
    }
}
