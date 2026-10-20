package com.example.meetomxh.adapters;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;

import com.example.meetomxh.R;
import com.example.meetomxh.databinding.ItemChatMessageBinding;
import com.example.meetomxh.models.ChatMessage;
import com.google.firebase.Timestamp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder> {
    public interface OnMessageInteractionListener {
        void onLongPress(ChatMessage message);
        void onReplyTap(String messageId);
        void onImageTap(String imageUrl);
    }
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<ChatMessage> allMessages = new ArrayList<>();
    private final String currentUid;
    private OnMessageInteractionListener interactionListener;
    private final Set<String> busyMessageIds = new HashSet<>();
    private Timestamp otherParticipantLastReadAt;

    public ChatMessageAdapter(String currentUid) {
        this.currentUid = currentUid;
    }

    public void submitList(List<ChatMessage> items) {
        allMessages.clear();
        if (items != null) allMessages.addAll(items);
        List<ChatMessage> next = new ArrayList<>();
        if (items != null) for (ChatMessage message : items)
            if (message != null && !message.isDeletedFor(currentUid)) next.add(message);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return messages.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return safeEquals(messages.get(oldPosition).getMessageId(), next.get(newPosition).getMessageId());
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                ChatMessage oldMessage = messages.get(oldPosition);
                ChatMessage newMessage = next.get(newPosition);
                return safeEquals(oldMessage.getText(), newMessage.getText())
                        && safeEquals(oldMessage.getSenderId(), newMessage.getSenderId())
                        && oldMessage.isRecalled() == newMessage.isRecalled()
                        && safeEquals(oldMessage.getReplyToMessageId(), newMessage.getReplyToMessageId())
                        && safeEquals(oldMessage.getReplyPreview(), newMessage.getReplyPreview())
                        && safeEquals(oldMessage.getImageUrl(), newMessage.getImageUrl())
                        && (oldMessage.getDeletedFor() == null ? newMessage.getDeletedFor() == null
                        : oldMessage.getDeletedFor().equals(newMessage.getDeletedFor()))
                        && (oldMessage.getCreatedAt() == null ? newMessage.getCreatedAt() == null
                        : oldMessage.getCreatedAt().equals(newMessage.getCreatedAt()));
            }
        });
        messages.clear();
        messages.addAll(next);
        diff.dispatchUpdatesTo(this);
        notifyReadReceiptsChanged();
    }

    public void setInteractionListener(OnMessageInteractionListener listener) { interactionListener = listener; }
    public void setOtherParticipantLastReadAt(Timestamp timestamp) {
        if (otherParticipantLastReadAt == null ? timestamp == null : otherParticipantLastReadAt.equals(timestamp)) return;
        otherParticipantLastReadAt = timestamp;
        notifyReadReceiptsChanged();
    }

    private void notifyReadReceiptsChanged() {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (currentUid != null && currentUid.equals(message.getSenderId())) {
                notifyItemChanged(index);
                return;
            }
        }
    }
    public void setMessageBusy(String messageId, boolean busy) {
        if (messageId == null) return;
        if (busy) busyMessageIds.add(messageId); else busyMessageIds.remove(messageId);
        int index = indexOfMessage(messageId);
        if (index >= 0) notifyItemChanged(index);
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new MessageViewHolder(ItemChatMessageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        String latestOutgoingId = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (currentUid != null && currentUid.equals(messages.get(i).getSenderId())) {
                latestOutgoingId = messages.get(i).getMessageId();
                break;
            }
        }
        holder.bind(messages.get(position), currentUid, interactionListener,
                busyMessageIds.contains(messages.get(position).getMessageId()), allMessages,
                otherParticipantLastReadAt, latestOutgoingId);
    }

    @Override
    public int getItemCount() { return messages.size(); }

    public int indexOfMessage(String messageId) {
        if (messageId == null) return -1;
        for (int index = 0; index < messages.size(); index++) {
            if (messageId.equals(messages.get(index).getMessageId())) return index;
        }
        return -1;
    }

    /** Searches only the message window currently loaded by the chat listener (up to 50 rows). */
    public List<ChatMessage> searchLoadedTextMessages(String query) {
        List<ChatMessage> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return results;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message.matchesVisibleTextQuery(currentUid, query)) results.add(message);
        }
        return results;
    }

    private static boolean safeEquals(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemChatMessageBinding binding;

        MessageViewHolder(ItemChatMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ChatMessage message, String currentUid, OnMessageInteractionListener listener, boolean busy,
                  List<ChatMessage> allMessages, Timestamp otherReadAt, String latestOutgoingId) {
            boolean mine = currentUid != null && currentUid.equals(message.getSenderId());
            binding.messageRow.setGravity(mine ? Gravity.END : Gravity.START);
            binding.messageBubble.setBackgroundResource(mine
                    ? R.drawable.bg_chat_bubble_mine : R.drawable.bg_chat_bubble_other);
            binding.tvMessageText.setText(message.getDisplayText());
            boolean hasImage = !message.isRecalled() && "image".equals(message.getType())
                    && message.getImageUrl() != null && message.getImageUrl().startsWith("https://");
            binding.ivMessageImage.setVisibility(hasImage ? android.view.View.VISIBLE : android.view.View.GONE);
            binding.tvMessageText.setVisibility(hasImage ? android.view.View.GONE : android.view.View.VISIBLE);
            if (hasImage) {
                Glide.with(binding.ivMessageImage).load(message.getImageUrl())
                        .placeholder(R.drawable.ic_photo).error(R.drawable.ic_photo)
                        .centerCrop().into(binding.ivMessageImage);
                binding.ivMessageImage.setOnClickListener(v -> {
                    if (listener != null) listener.onImageTap(message.getImageUrl());
                });
            } else {
                Glide.with(binding.ivMessageImage).clear(binding.ivMessageImage);
                binding.ivMessageImage.setOnClickListener(null);
            }
            binding.tvMessageText.setTypeface(null, message.isRecalled() ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL);
            if (message.getReplyPreview() != null && !message.getReplyPreview().isEmpty()) {
                binding.tvReplyPreview.setVisibility(android.view.View.VISIBLE);
                ChatMessage source = findMessage(message.getReplyToMessageId(), allMessages);
                String preview = source != null
                        ? (source.isDeletedFor(currentUid) ? "Tin nhắn không còn hiển thị" : source.getDisplayText())
                        : (message.getReplyPreview().length() == 0 ? "Tin nhắn không còn hiển thị" : message.getReplyPreview());
                binding.tvReplyPreview.setText("↪ " + preview);
                binding.tvReplyPreview.setOnClickListener(v -> {
                    if (listener != null && message.getReplyToMessageId() != null) listener.onReplyTap(message.getReplyToMessageId());
                });
            } else {
                binding.tvReplyPreview.setVisibility(android.view.View.GONE);
                binding.tvReplyPreview.setOnClickListener(null);
            }
            binding.messageBubble.setOnLongClickListener(v -> {
                if (busy || message.getMessageId() == null || message.getSenderId() == null
                        || message.getSenderId().trim().isEmpty()
                        || listener == null) return true;
                listener.onLongPress(message);
                return true;
            });
            int textColor = ContextCompat.getColor(binding.getRoot().getContext(),
                    mine ? R.color.chat_bubble_mine_text : R.color.text_primary);
            binding.tvMessageText.setTextColor(textColor);
            binding.tvMessageTime.setText(message.getCreatedAt() == null ? ""
                    : new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(message.getCreatedAt().toDate().getTime())));
            binding.tvMessageTime.setTextColor(ContextCompat.getColor(binding.getRoot().getContext(),
                    mine ? R.color.chat_bubble_mine_secondary : R.color.text_secondary));
            boolean showReceipt = mine && message.getMessageId() != null
                    && message.getMessageId().equals(latestOutgoingId);
            binding.tvMessageReceipt.setVisibility(showReceipt ? android.view.View.VISIBLE : android.view.View.GONE);
            if (showReceipt) {
                boolean read = otherReadAt != null && message.getCreatedAt() != null
                        && message.getCreatedAt().compareTo(otherReadAt) <= 0;
                binding.tvMessageReceipt.setText(read ? "Đã xem" : "Đã gửi");
            }
        }

        private ChatMessage findMessage(String messageId, List<ChatMessage> allMessages) {
            if (messageId == null) return null;
            for (ChatMessage candidate : allMessages)
                if (messageId.equals(candidate.getMessageId())) return candidate;
            return null;
        }
    }
}
