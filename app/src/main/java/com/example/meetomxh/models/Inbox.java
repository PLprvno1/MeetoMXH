package com.example.meetomxh.models;

public class Inbox {
    private String id;
    private String chatName;
    private String lastMessage;
    private String avatarUrl;
    private String timestamp;

    public Inbox() {
    }

    public Inbox(String id, String chatName, String lastMessage, String avatarUrl, String timestamp) {
        this.id = id;
        this.chatName = chatName;
        this.lastMessage = lastMessage;
        this.avatarUrl = avatarUrl;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

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

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
