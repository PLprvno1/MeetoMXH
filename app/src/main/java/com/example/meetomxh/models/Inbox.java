package com.example.meetomxh.models;

public class Inbox {
    private String id;
    private String otherUid;
    private String chatName;
    private String lastMessage;
    private String avatarUrl;
    private String timestamp;
    private boolean unread;

    public Inbox() {
    }

    public Inbox(String id, String chatName, String lastMessage, String avatarUrl, String timestamp) {
        this.id = id;
        this.chatName = chatName;
        this.lastMessage = lastMessage;
        this.avatarUrl = avatarUrl;
        this.timestamp = timestamp;
    }

    public Inbox(String id, String otherUid, String chatName, String lastMessage, String avatarUrl, String timestamp) {
        this(id, chatName, lastMessage, avatarUrl, timestamp);
        this.otherUid = otherUid;
    }

    public String getId() {
        return id;
    }

    public String getOtherUid() { return otherUid; }
    public void setOtherUid(String otherUid) { this.otherUid = otherUid; }

    public void setId(String id) {
        this.id = id;
    }

    public String getChatName() {
        return chatName;
    }

    public void setChatName(String chatName) {
        this.chatName = chatName;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public boolean isUnread() { return unread; }
    public void setUnread(boolean unread) { this.unread = unread; }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
