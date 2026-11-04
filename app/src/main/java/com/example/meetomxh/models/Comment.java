package com.example.meetomxh.models;

import org.json.JSONArray;

public class Comment {
    public long version=1;
    public String parentId,state,serverCreatedAt;
    private String commentId;
    private String postId;
    private String authorUid;
    private String authorName;
    private String authorAvatar;
    private String authorAvatarPreset = "neutral";
    private String content;
    private long createdAt;
    public int reactionsCount;
    public String myReaction;
    public JSONArray mentions=new JSONArray();

    public Comment() {
    }

    public Comment(String commentId, String postId, String authorUid, String authorName, String authorAvatar, String content, long createdAt) {
        this.commentId = commentId;
        this.postId = postId;
        this.authorUid = authorUid;
        this.authorName = authorName;
        this.authorAvatar = authorAvatar;
        this.content = content;
        this.createdAt = createdAt;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getAuthorUid() {
        return authorUid;
    }

    public void setAuthorUid(String authorUid) {
        this.authorUid = authorUid;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAuthorAvatar() {
        return authorAvatar;
    }

    public void setAuthorAvatar(String authorAvatar) {
        this.authorAvatar = authorAvatar;
    }

    public String getAuthorAvatarPreset() { return authorAvatarPreset; }

    public void setAuthorAvatarPreset(String value) {
        authorAvatarPreset = "male".equals(value) || "female".equals(value) ? value : "neutral";
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
