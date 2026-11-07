package com.example.meetomxh.models;

public class Story {
    private String id, userName, avatarUrl, imageUrl, mediaType = "image/*";
    private String avatarPreset = "neutral", audience = "private", caption = "";
    private String authorId, createdAt, expiresAt;
    private boolean isAddStory, viewed, own, liked;
    private int viewsCount;
    public boolean isLiked(){return liked;}
    public void setLiked(boolean value){liked=value;}
    public int getViewsCount(){return viewsCount;}
    public void setViewsCount(int value){viewsCount=value;}
    public boolean isActive(){return !isAddStory && expiryMillis()>System.currentTimeMillis();}
    public long expiryMillis(){return com.example.meetomxh.ui.StoryTime.millis(expiresAt);}

    public Story() { }

    public Story(String id, String userName, String avatarUrl, String imageUrl, boolean isAddStory) {
        this.id = id; this.userName = userName; this.avatarUrl = avatarUrl;
        this.imageUrl = imageUrl; this.isAddStory = isAddStory;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String value) { mediaType = value == null ? "image/*" : value; }
    public String getAvatarPreset() { return avatarPreset; }
    public void setAvatarPreset(String value) { avatarPreset = "male".equals(value) || "female".equals(value) ? value : "neutral"; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value == null ? "private" : value; }
    public String getCaption() { return caption; }
    public void setCaption(String value) { caption = value == null ? "" : value; }
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String value) { authorId = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { createdAt = value; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String value) { expiresAt = value; }
    public boolean isAddStory() { return isAddStory; }
    public void setAddStory(boolean value) { isAddStory = value; }
    public boolean isViewed() { return viewed; }
    public void setViewed(boolean value) { viewed = value; }
    public boolean isOwn() { return own; }
    public void setOwn(boolean value) { own = value; }
}
