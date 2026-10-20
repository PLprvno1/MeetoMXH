package com.example.meetomxh.models;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

/** Chat message stored below Chats/{chatId}/Messages/{messageId}. */
public class ChatMessage {
    private String messageId;
    private String senderId;
    private String receiverId;
    private String type;
    private String text;
    private String imageUrl;
    private Timestamp createdAt;
    private boolean isRecalled;
    private Timestamp recalledAt;
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
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public boolean isRecalled() { return isRecalled; }
    public void setRecalled(boolean recalled) { isRecalled = recalled; }
    public Timestamp getRecalledAt() { return recalledAt; }
    public void setRecalledAt(Timestamp recalledAt) { this.recalledAt = recalledAt; }
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

    public static ChatMessage fromSnapshot(DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;
        ChatMessage message = new ChatMessage();
        message.setMessageId(snapshot.getId());
        message.setSenderId(readString(snapshot, "senderId"));
        message.setReceiverId(readString(snapshot, "receiverId"));
        message.setType(readString(snapshot, "type"));
        message.setText(readString(snapshot, "text"));
        message.setImageUrl(readString(snapshot, "imageUrl"));
        message.setCreatedAt(readTimestamp(snapshot, "createdAt"));
        message.setRecalled(Boolean.TRUE.equals(snapshot.get("isRecalled")));
        message.setRecalledAt(readTimestamp(snapshot, "recalledAt"));
        message.setDeletedFor(readStringList(snapshot.get("deletedFor")));
        message.setReplyToMessageId(readString(snapshot, "replyToMessageId"));
        message.setReplyToSenderId(readString(snapshot, "replyToSenderId"));
        message.setReplyPreview(readString(snapshot, "replyPreview"));
        return message;
    }

    private static String readString(DocumentSnapshot snapshot, String key) {
        Object value = snapshot.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static Timestamp readTimestamp(DocumentSnapshot snapshot, String key) {
        Object value = snapshot.get(key);
        return value instanceof Timestamp ? (Timestamp) value : null;
    }

    private static List<String> readStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?>) for (Object item : (List<?>) value)
            if (item instanceof String) result.add((String) item);
        return result;
    }
}
