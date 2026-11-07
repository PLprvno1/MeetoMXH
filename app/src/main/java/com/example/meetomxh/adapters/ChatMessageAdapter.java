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
import com.example.meetomxh.supabase.ServerTime;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder> {
    private com.example.meetomxh.ui.ChatThemeRegistry.Palette palette;
    private String paletteKey;
    private boolean groupConversation;
    private final java.util.Map<String,String> memberNames=new java.util.HashMap<>();
    public void setTheme(android.content.Context context,String id){
        String key=id+":"+context.getResources().getConfiguration().uiMode;
        if(key.equals(paletteKey))return;paletteKey=key;
        palette=com.example.meetomxh.ui.ChatThemeRegistry.get(id).palette(context);notifyDataSetChanged();
    }
    public void setMemberNames(java.util.Map<String,String> names,boolean group){
        if(groupConversation==group&&memberNames.equals(names))return;
        groupConversation=group;memberNames.clear();memberNames.putAll(names);notifyDataSetChanged();
    }
    private static final Object RECEIPT_CHANGED = new Object();
    public interface OnMessageInteractionListener {
        void onLongPress(ChatMessage message);
        void onReplyTap(String messageId, long seq);
        void onImageTap(String imageUrl);
        void onVoiceTap(ChatMessage message);
    }
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<ChatMessage> allMessages = new ArrayList<>();
    private final String currentUid;
    private OnMessageInteractionListener interactionListener;
    private final Set<String> busyMessageIds = new HashSet<>();
    private Long otherParticipantLastReadSeq;
    private String voiceMessageId;
    private int voicePositionMs, voiceDurationMs;
    private boolean voicePlaying;

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
                        && oldMessage.isForwarded() == newMessage.isForwarded()
                        && ((oldMessage.getEditedAt() == null && newMessage.getEditedAt() == null)
                          || (oldMessage.getEditedAt() != null && oldMessage.getEditedAt().equals(newMessage.getEditedAt())))
                        && safeEquals(oldMessage.getReplyToMessageId(), newMessage.getReplyToMessageId())
                        && oldMessage.getReplyToSeq() == newMessage.getReplyToSeq()
                        && safeEquals(oldMessage.getReplyPreview(), newMessage.getReplyPreview())
                        && safeEquals(oldMessage.getImageUrl(), newMessage.getImageUrl())
                        && safeEquals(oldMessage.getType(), newMessage.getType())
                        && safeEquals(oldMessage.getMediaMimeType(), newMessage.getMediaMimeType())
                        && oldMessage.getVoiceDurationMs() == newMessage.getVoiceDurationMs()
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
    public void setOtherParticipantLastReadSeq(Long sequence) {
        if (otherParticipantLastReadSeq == null ? sequence == null : otherParticipantLastReadSeq.equals(sequence)) return;
        otherParticipantLastReadSeq = sequence;
        notifyReadReceiptsChanged();
    }

    private void notifyReadReceiptsChanged() {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (currentUid != null && currentUid.equals(message.getSenderId())) {
                notifyItemChanged(index, RECEIPT_CHANGED);
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
    public void setVoicePlayback(String messageId, int positionMs, int durationMs, boolean playing) {
        if (messageId == null) return;
        String oldId = voiceMessageId;
        voiceMessageId = playing ? messageId : (messageId.equals(voiceMessageId) ? messageId : voiceMessageId);
        voicePositionMs = Math.max(0, positionMs);
        voiceDurationMs = Math.max(1, durationMs);
        voicePlaying = playing;
        int oldIndex = indexOfMessage(oldId);
        if (oldIndex >= 0 && !oldId.equals(messageId)) notifyItemChanged(oldIndex);
        int index = indexOfMessage(messageId);
        if (index >= 0) notifyItemChanged(index);
        if (!playing && messageId != null && messageId.equals(voiceMessageId)) voiceMessageId = null;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new MessageViewHolder(ItemChatMessageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        String latestOutgoingId = latestOutgoingId();
        holder.bind(messages.get(position), currentUid, interactionListener,
                busyMessageIds.contains(messages.get(position).getMessageId()), allMessages,
                otherParticipantLastReadSeq, latestOutgoingId,
                safeEquals(messages.get(position).getMessageId(), voiceMessageId), voicePositionMs, voiceDurationMs, voicePlaying,
                palette,memberNames,groupConversation);
    }

    private String latestOutgoingId() {
        String latestOutgoingId = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (currentUid != null && currentUid.equals(messages.get(i).getSenderId())) {
                latestOutgoingId = messages.get(i).getMessageId();
                break;
            }
        }
        return latestOutgoingId;
    }

    @Override public void onBindViewHolder(@NonNull MessageViewHolder holder, int position,
                                         @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.stream().allMatch(value -> value == RECEIPT_CHANGED)) {
            ChatMessage message = messages.get(position);
            holder.bindReceipt(message, currentUid, otherParticipantLastReadSeq, latestOutgoingId());
        } else onBindViewHolder(holder, position);
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
    public String messageIdAt(int position) { return position < 0 || position >= messages.size() ? null : messages.get(position).getMessageId(); }

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
        private com.example.meetomxh.ui.ChatThemeRegistry.Palette palette;

        MessageViewHolder(ItemChatMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(ChatMessage message, String currentUid, OnMessageInteractionListener listener, boolean busy,
                  List<ChatMessage> allMessages, Long otherReadSeq, String latestOutgoingId,
                  boolean activeVoice, int voicePositionMs, int voiceDurationMs, boolean voicePlaying,
                  com.example.meetomxh.ui.ChatThemeRegistry.Palette palette,java.util.Map<String,String> memberNames,boolean group) {
            this.palette=palette;
            boolean mine = currentUid != null && currentUid.equals(message.getSenderId());
            binding.messageRow.setGravity(mine ? Gravity.END : Gravity.START);
            binding.messageBubble.setBackgroundResource(mine
                    ? R.drawable.bg_chat_bubble_mine : R.drawable.bg_chat_bubble_other);
            binding.tvMessageText.setText(message.getDisplayText());binding.sharedAuthorAvatar.setVisibility(message.hasSharedPost()&&message.isSharedPostAvailable()?android.view.View.VISIBLE:android.view.View.GONE);if(message.hasSharedPost()&&message.isSharedPostAvailable())com.example.meetomxh.ui.AvatarViews.load(binding.sharedAuthorAvatar,message.getSharedAvatar(),message.getSharedAvatarPreset());else Glide.with(binding.sharedAuthorAvatar).clear(binding.sharedAuthorAvatar);
            String context = message.isForwarded() ? "Đã chuyển tiếp" : "";
            if (message.getEditedAt() != null && !message.isRecalled())
                context = context.isEmpty() ? "Đã chỉnh sửa" : context + " · Đã chỉnh sửa";
            binding.tvMessageContext.setText(context);
            binding.tvMessageContext.setVisibility(context.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            if(message.hasSharedPost()&&message.isSharedPostAvailable()&&message.getSharedPostId()!=null){
                binding.tvMessageText.setOnClickListener(v->{
                    com.example.meetomxh.supabase.PostDetails.show((android.app.Activity)v.getContext(),message.getSharedPostId());
                });
                binding.tvMessageText.setPaintFlags(binding.tvMessageText.getPaintFlags()|android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            }else{
                binding.tvMessageText.setOnClickListener(message.getStoryAuthor()==null?null:v->v.getContext().startActivity(new android.content.Intent(v.getContext(),com.example.meetomxh.StoryViewActivity.class).putExtra(com.example.meetomxh.StoryViewActivity.EXTRA_AUTHOR_ID,message.getStoryAuthor()).putExtra(com.example.meetomxh.StoryViewActivity.EXTRA_STORY_ID,message.getStoryId())));
                binding.tvMessageText.setPaintFlags(binding.tvMessageText.getPaintFlags()&~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            }
            boolean shared=message.hasSharedPost()&&message.isSharedPostAvailable()&&message.getSharedPreview()!=null;
            String mediaReference=shared?message.getSharedPreview():message.getImageUrl();
            boolean video = shared?message.getSharedMime().startsWith("video/"):"video".equals(message.getType());
            boolean voice = "voice".equals(message.getType()) && !message.isRecalled()
                    && com.example.meetomxh.repositories.ChatRepository.isValidStorageMediaReference(message.getImageUrl());
            boolean hasMedia = shared || !message.isRecalled() && ("image".equals(message.getType()) || video)
                    && com.example.meetomxh.repositories.ChatRepository.isValidStorageMediaReference(message.getImageUrl());
            boolean bareMedia = hasMedia && !shared;
            float density=binding.getRoot().getResources().getDisplayMetrics().density;
            binding.messageBubble.setBackgroundResource(bareMedia?0:mine?R.drawable.bg_chat_bubble_mine:R.drawable.bg_chat_bubble_other);
            binding.messageBubble.setPadding(bareMedia?0:Math.round(14*density),bareMedia?0:Math.round(9*density),
                    bareMedia?0:Math.round(14*density),bareMedia?0:Math.round(7*density));
            binding.messageBubble.setElevation(bareMedia?0:density);
            boolean mediaCaption=bareMedia&&message.getText()!=null&&!message.getText().trim().isEmpty();
            if(mediaCaption)binding.tvMessageText.setText(message.getText());
            binding.layoutMessageMedia.setVisibility(hasMedia ? android.view.View.VISIBLE : android.view.View.GONE);
            binding.layoutVoiceMessage.setVisibility(voice ? android.view.View.VISIBLE : android.view.View.GONE);
            binding.tvMessageText.setVisibility((bareMedia&&!mediaCaption) || voice ? android.view.View.GONE : android.view.View.VISIBLE);
            if (voice) {
                binding.btnVoicePlay.setImageResource(activeVoice && voicePlaying ? R.drawable.ic_pause : R.drawable.ic_play);
                binding.btnVoicePlay.setImageTintList(ContextCompat.getColorStateList(binding.getRoot().getContext(),
                        mine ? R.color.chat_bubble_mine_text : R.color.brand_blue));
                binding.progressVoicePlayback.setProgress(activeVoice && voiceDurationMs > 0
                        ? Math.min(1000, voicePositionMs * 1000 / voiceDurationMs) : 0);
                int duration = activeVoice && voiceDurationMs > 0 ? voiceDurationMs : (int) message.getVoiceDurationMs();
                binding.tvVoiceDuration.setText(formatDuration(duration));
                binding.btnVoicePlay.setOnClickListener(v -> { if (listener != null) listener.onVoiceTap(message); });
            } else binding.btnVoicePlay.setOnClickListener(null);
            binding.tvVideoPlay.setVisibility(hasMedia && video ? android.view.View.VISIBLE : android.view.View.GONE);
            binding.tvMessageText.setVisibility((bareMedia&&!mediaCaption) || voice ? android.view.View.GONE : android.view.View.VISIBLE);
            binding.ivMessageImage.setTag(hasMedia ? mediaReference : null);
            if (hasMedia && !video) {
                Glide.with(binding.ivMessageImage).applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(mediaReference)
                        .placeholder(R.drawable.ic_photo).error(R.drawable.ic_photo).centerCrop().into(binding.ivMessageImage);
            } else if (hasMedia) {
                Glide.with(binding.ivMessageImage).clear(binding.ivMessageImage);
                String reference = mediaReference;
                com.example.meetomxh.supabase.Api.run(() -> com.example.meetomxh.storage.SupabaseStorage.videoThumbnail(reference),
                        new com.example.meetomxh.supabase.Api.Callback<android.graphics.Bitmap>() {
                            @Override public void success(android.graphics.Bitmap bitmap) {
                                if (reference.equals(binding.ivMessageImage.getTag()))
                                    Glide.with(binding.ivMessageImage).load(bitmap).centerCrop().into(binding.ivMessageImage);
                                else if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                            }
                            @Override public void error(String error) { }
                        });
            } else {
                Glide.with(binding.ivMessageImage).clear(binding.ivMessageImage);
                binding.ivMessageImage.setTag(null);
            }
            if (hasMedia) {
                binding.ivMessageImage.setOnClickListener(v -> {
                    if(shared){com.example.meetomxh.supabase.PostDetails.show((android.app.Activity)v.getContext(),message.getSharedPostId());return;}
                    if (video) binding.getRoot().getContext().startActivity(new android.content.Intent(
                            binding.getRoot().getContext(), com.example.meetomxh.VideoPlayerActivity.class)
                            .putExtra(com.example.meetomxh.VideoPlayerActivity.EXTRA_ASSET, message.getImageUrl()));
                    else if (listener != null) listener.onImageTap(message.getImageUrl());
                });
            } else {
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
                    if (listener != null && message.getReplyToMessageId() != null)
                        listener.onReplyTap(message.getReplyToMessageId(), message.getReplyToSeq());
                });
            } else {
                binding.tvReplyPreview.setVisibility(android.view.View.GONE);
                binding.tvReplyPreview.setOnClickListener(null);
            }
            android.view.View.OnLongClickListener messageActions = v -> {
                if (busy || message.getMessageId() == null || message.getSenderId() == null
                        || message.getSenderId().trim().isEmpty()
                        || listener == null) return true;
                listener.onLongPress(message);
                return true;
            };
            binding.messageBubble.setOnLongClickListener(messageActions);
            binding.tvMessageText.setOnLongClickListener(messageActions);
            binding.tvReplyPreview.setOnLongClickListener(messageActions);
            binding.ivMessageImage.setOnLongClickListener(messageActions);
            binding.tvVideoPlay.setOnLongClickListener(messageActions);
            binding.tvVideoPlay.setOnClickListener(hasMedia&&video?v->binding.ivMessageImage.performClick():null);
            binding.layoutVoiceMessage.setOnLongClickListener(messageActions);
            int textColor = ContextCompat.getColor(binding.getRoot().getContext(),
                    mine&&!bareMedia ? R.color.chat_bubble_mine_text : R.color.text_primary);
            binding.tvMessageText.setTextColor(textColor);
            binding.tvMessageTime.setText(message.getCreatedAt() == null ? ""
                    : new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(message.getCreatedAt().toDate().getTime())));
            binding.tvMessageTime.setTextColor(ContextCompat.getColor(binding.getRoot().getContext(),
                    mine&&!bareMedia ? R.color.chat_bubble_mine_secondary : R.color.text_secondary));
            binding.tvMessageSender.setVisibility(group&&!mine?android.view.View.VISIBLE:android.view.View.GONE);
            binding.tvMessageSender.setText(memberNames.getOrDefault(message.getSenderId(),"Người dùng không khả dụng"));
            if(palette!=null){
                int bubbleText=bareMedia?palette.text:mine?palette.sentText:palette.receivedText;
                int secondary=bareMedia?palette.secondary:bubbleText;
                if(!bareMedia)binding.messageBubble.setBackground(com.example.meetomxh.ui.ChatThemeRegistry.shape(mine?palette.sent:palette.received,18,binding.getRoot().getContext()));
                binding.tvMessageText.setTextColor(bubbleText);binding.tvMessageText.setLinkTextColor(bubbleText);
                binding.tvMessageContext.setTextColor(secondary);binding.tvReplyPreview.setTextColor(secondary);
                binding.tvMessageTime.setTextColor(secondary);binding.tvMessageSender.setTextColor(bubbleText);
                binding.tvVideoPlay.setBackground(com.example.meetomxh.ui.ChatThemeRegistry.shape(palette.sent,24,binding.getRoot().getContext()));binding.tvVideoPlay.setTextColor(palette.sentText);
                binding.tvVoiceDuration.setTextColor(bubbleText);binding.btnVoicePlay.setImageTintList(android.content.res.ColorStateList.valueOf(bubbleText));
                binding.progressVoicePlayback.setProgressTintList(android.content.res.ColorStateList.valueOf(bubbleText));
            }
            bindReceipt(message, currentUid, otherReadSeq, latestOutgoingId);
        }

        private String formatDuration(long millis) {
            long seconds = Math.max(0, millis / 1000);
            return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
        }

        void bindReceipt(ChatMessage message, String currentUid, Long otherReadSeq, String latestOutgoingId) {
            boolean bareMedia=!message.hasSharedPost()&&!message.isRecalled()
                    &&("image".equals(message.getType())||"video".equals(message.getType()))
                    &&com.example.meetomxh.repositories.ChatRepository.isValidStorageMediaReference(message.getImageUrl());
            binding.tvMessageReceipt.setTextColor(ContextCompat.getColor(binding.getRoot().getContext(),
                    bareMedia?R.color.text_secondary:R.color.chat_bubble_mine_secondary));
            if(palette!=null)binding.tvMessageReceipt.setTextColor(bareMedia?palette.secondary:palette.sentText);
            boolean mine = currentUid != null && currentUid.equals(message.getSenderId());
            boolean showReceipt = mine && message.getMessageId() != null
                    && message.getMessageId().equals(latestOutgoingId);
            binding.tvMessageReceipt.setVisibility(showReceipt ? android.view.View.VISIBLE : android.view.View.GONE);
            if (showReceipt) {
                boolean read = otherReadSeq != null && message.seq > 0 && message.seq <= otherReadSeq;
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
