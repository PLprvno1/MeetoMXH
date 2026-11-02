package com.example.meetomxh.models;

import com.example.meetomxh.supabase.ServerTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

/** Chat message projected by the authorized message RPC. */
public class ChatMessage {
    public long seq,version;
    private String messageId;
    private String senderId;
    private String receiverId;
    private String type;
    private String text;
    private String imageUrl;
    private ServerTime createdAt;
    private boolean isRecalled;
    private ServerTime recalledAt;
    private List<String> deletedFor = new ArrayList<>();
    private String replyToMessageId;
    private String replyToSenderId;
    private String replyPreview;

    public ChatMessage() { }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getReceiverId() { return receiverId; }
    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public ServerTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ServerTime createdAt) { this.createdAt = createdAt; }
    public boolean isRecalled() { return isRecalled; }
    public void setRecalled(boolean recalled) { isRecalled = recalled; }
    public ServerTime getRecalledAt() { return recalledAt; }
    public void setRecalledAt(ServerTime recalledAt) { this.recalledAt = recalledAt; }
    public List<String> getDeletedFor() { return deletedFor; }
    public void setDeletedFor(List<String> deletedFor) { this.deletedFor = deletedFor; }
    public String getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(String replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public String getReplyToSenderId() { return replyToSenderId; }
    public void setReplyToSenderId(String replyToSenderId) { this.replyToSenderId = replyToSenderId; }
    public String getReplyPreview() { return replyPreview; }
    public void setReplyPreview(String replyPreview) { this.replyPreview = replyPreview; }

    public boolean isDeletedFor(String uid) {
        return uid != null && deletedFor != null && deletedFor.contains(uid);
    }

    public String getDisplayText() {
        if (!isRecalled && "image".equals(type)) return "\u1ea2nh";
        return isRecalled ? "Tin nhắn đã được thu hồi" : (text == null ? "" : text);
    }

    public boolean matchesVisibleTextQuery(String uid, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return !needle.isEmpty() && "text".equals(type) && !isRecalled && !isDeletedFor(uid)
                && text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

}
