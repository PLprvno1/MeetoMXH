package com.example.meetomxh.models;

import com.example.meetomxh.supabase.ServerTime;


import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Server projection of a private one-to-one conversation. */
public class Chat {
    public long version,highWater,lastReadSeq,lastDeliveredSeq,unreadCount;
    public String title,state,role;
    public boolean archived,muted; public String avatar; public String ownerId;
    private String chatId;
    private String type;
    private List<String> participants;
    private ServerTime createdAt;
    private ServerTime updatedAt;
    private String lastMessage;
    private ServerTime lastMessageAt;
    private String lastMessageSenderId;
    private String lastMessageId;
    private List<String> lastMessageHiddenFor;
    private String lastMessageType;
    private Map<String, ServerTime> lastReadAtByUid = new HashMap<>();
    private Map<String, Long> lastReadSeqByUid = new HashMap<>();
    private Map<String, String> lastReadMessageIdByUid = new HashMap<>();
    private Map<String, ServerTime> lastIncomingMessageAtByUid = new HashMap<>();
    private Map<String, String> lastIncomingMessageIdByUid = new HashMap<>();

    public Chat() { }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public List<String> getParticipants() { return participants; }
    public void setParticipants(List<String> participants) { this.participants = participants; }
    public ServerTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ServerTime createdAt) { this.createdAt = createdAt; }
    public ServerTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ServerTime updatedAt) { this.updatedAt = updatedAt; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public ServerTime getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(ServerTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public String getLastMessageSenderId() { return lastMessageSenderId; }
    public void setLastMessageSenderId(String lastMessageSenderId) { this.lastMessageSenderId = lastMessageSenderId; }
    public String getLastMessageId() { return lastMessageId; }
    public void setLastMessageId(String lastMessageId) { this.lastMessageId = lastMessageId; }
    public List<String> getLastMessageHiddenFor() { return lastMessageHiddenFor; }
    public void setLastMessageHiddenFor(List<String> lastMessageHiddenFor) { this.lastMessageHiddenFor = lastMessageHiddenFor; }
    public String getLastMessageType() { return lastMessageType; }
    public void setLastMessageType(String lastMessageType) { this.lastMessageType = lastMessageType; }
    public Map<String, ServerTime> getLastReadAtByUid() { return lastReadAtByUid; }
    public void setLastReadAtByUid(Map<String, ServerTime> value) { lastReadAtByUid = value; }
    public void setLastReadSeqByUid(Map<String, Long> value) { lastReadSeqByUid = value; }
    public Long getLastReadSeq(String uid) { return lastReadSeqByUid.get(uid); }
    public Map<String, String> getLastReadMessageIdByUid() { return lastReadMessageIdByUid; }
    public void setLastReadMessageIdByUid(Map<String, String> value) { lastReadMessageIdByUid = value; }
    public Map<String, ServerTime> getLastIncomingMessageAtByUid() { return lastIncomingMessageAtByUid; }
    public void setLastIncomingMessageAtByUid(Map<String, ServerTime> value) { lastIncomingMessageAtByUid = value; }
    public Map<String, String> getLastIncomingMessageIdByUid() { return lastIncomingMessageIdByUid; }
    public void setLastIncomingMessageIdByUid(Map<String, String> value) { lastIncomingMessageIdByUid = value; }

    public ServerTime getLastReadAt(String uid) {
        return lastReadAtByUid == null ? null : lastReadAtByUid.get(uid);
    }

    public String getLastIncomingMessageId(String uid) {
        return lastIncomingMessageIdByUid == null ? null : lastIncomingMessageIdByUid.get(uid);
    }

    public boolean isUnreadFor(String uid) {
        return uid != null && !uid.trim().isEmpty() && unreadCount > 0;
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
