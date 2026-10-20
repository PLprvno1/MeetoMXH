package com.example.meetomxh.models;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Firestore representation of a private one-to-one conversation. */
public class Chat {
    private String chatId;
    private String type;
    private List<String> participants;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String lastMessage;
    private Timestamp lastMessageAt;
    private String lastMessageSenderId;
    private String lastMessageId;
    private List<String> lastMessageHiddenFor;
    private String lastMessageType;
    private Map<String, Timestamp> lastReadAtByUid = new HashMap<>();
    private Map<String, String> lastReadMessageIdByUid = new HashMap<>();
    private Map<String, Timestamp> lastIncomingMessageAtByUid = new HashMap<>();
    private Map<String, String> lastIncomingMessageIdByUid = new HashMap<>();

    public Chat() { }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getParticipants() { return participants; }
    public void setParticipants(List<String> participants) { this.participants = participants; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public Timestamp getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Timestamp lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public String getLastMessageSenderId() { return lastMessageSenderId; }
    public void setLastMessageSenderId(String lastMessageSenderId) { this.lastMessageSenderId = lastMessageSenderId; }
    public String getLastMessageId() { return lastMessageId; }
    public void setLastMessageId(String lastMessageId) { this.lastMessageId = lastMessageId; }
    public List<String> getLastMessageHiddenFor() { return lastMessageHiddenFor; }
    public void setLastMessageHiddenFor(List<String> lastMessageHiddenFor) { this.lastMessageHiddenFor = lastMessageHiddenFor; }
    public String getLastMessageType() { return lastMessageType; }
    public void setLastMessageType(String lastMessageType) { this.lastMessageType = lastMessageType; }
    public Map<String, Timestamp> getLastReadAtByUid() { return lastReadAtByUid; }
    public void setLastReadAtByUid(Map<String, Timestamp> value) { lastReadAtByUid = value; }
    public Map<String, String> getLastReadMessageIdByUid() { return lastReadMessageIdByUid; }
    public void setLastReadMessageIdByUid(Map<String, String> value) { lastReadMessageIdByUid = value; }
    public Map<String, Timestamp> getLastIncomingMessageAtByUid() { return lastIncomingMessageAtByUid; }
    public void setLastIncomingMessageAtByUid(Map<String, Timestamp> value) { lastIncomingMessageAtByUid = value; }
    public Map<String, String> getLastIncomingMessageIdByUid() { return lastIncomingMessageIdByUid; }
    public void setLastIncomingMessageIdByUid(Map<String, String> value) { lastIncomingMessageIdByUid = value; }

    public Timestamp getLastReadAt(String uid) {
        return lastReadAtByUid == null ? null : lastReadAtByUid.get(uid);
    }

    public String getLastIncomingMessageId(String uid) {
        return lastIncomingMessageIdByUid == null ? null : lastIncomingMessageIdByUid.get(uid);
    }

    public boolean isUnreadFor(String uid) {
        if (uid == null || uid.trim().isEmpty()) return false;
        Timestamp incoming = lastIncomingMessageAtByUid == null ? null : lastIncomingMessageAtByUid.get(uid);
        Timestamp read = getLastReadAt(uid);
        if (incoming != null) return read == null || incoming.compareTo(read) > 0;
        // Legacy summaries can only tell whether the latest message itself was incoming.
        return lastMessageAt != null && !uid.equals(lastMessageSenderId)
                && (read == null || lastMessageAt.compareTo(read) > 0);
    }

    public static Chat fromSnapshot(DocumentSnapshot snapshot) {
        if (snapshot == null || !snapshot.exists()) return null;
        Chat chat = new Chat();
        chat.setChatId(snapshot.getId());
        chat.setType(readString(snapshot, "type"));
        chat.setParticipants(readStringList(snapshot.get("participants")));
        chat.setCreatedAt(readTimestamp(snapshot, "createdAt"));
        chat.setUpdatedAt(readTimestamp(snapshot, "updatedAt"));
        chat.setLastMessage(readString(snapshot, "lastMessage"));
        chat.setLastMessageAt(readTimestamp(snapshot, "lastMessageAt"));
        chat.setLastMessageSenderId(readString(snapshot, "lastMessageSenderId"));
        chat.setLastMessageId(readString(snapshot, "lastMessageId"));
        chat.setLastMessageType(readString(snapshot, "lastMessageType"));
        chat.setLastMessageHiddenFor(readStringList(snapshot.get("lastMessageHiddenFor")));
        chat.setLastReadAtByUid(readTimestampMap(snapshot.get("lastReadAtByUid")));
        chat.setLastReadMessageIdByUid(readStringMap(snapshot.get("lastReadMessageIdByUid")));
        chat.setLastIncomingMessageAtByUid(readTimestampMap(snapshot.get("lastIncomingMessageAtByUid")));
        chat.setLastIncomingMessageIdByUid(readStringMap(snapshot.get("lastIncomingMessageIdByUid")));
        return chat;
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

    private static Map<String, Timestamp> readTimestampMap(Object value) {
        Map<String, Timestamp> result = new HashMap<>();
        if (value instanceof Map<?, ?>) for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            if (entry.getKey() instanceof String && entry.getValue() instanceof Timestamp)
                result.put((String) entry.getKey(), (Timestamp) entry.getValue());
        return result;
    }

    private static Map<String, String> readStringMap(Object value) {
        Map<String, String> result = new HashMap<>();
        if (value instanceof Map<?, ?>) for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            if (entry.getKey() instanceof String && entry.getValue() instanceof String)
                result.put((String) entry.getKey(), (String) entry.getValue());
        return result;
    }

    public boolean hasExactlyParticipants(String firstUid, String secondUid) {
        if (participants == null || participants.size() != 2) return false;
        return participants.contains(firstUid) && participants.contains(secondUid);
    }

    public static List<String> sortedParticipants(String firstUid, String secondUid) {
        List<String> result = new ArrayList<>();
        result.add(firstUid);
        result.add(secondUid);
        result.sort(String::compareTo);
        return result;
    }
}
