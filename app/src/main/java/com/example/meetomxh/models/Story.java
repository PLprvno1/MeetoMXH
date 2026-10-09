package com.example.meetomxh.models;

public class Story {
    private String id;
    private String userName;
    private String avatarUrl;
    private String imageUrl;
    private boolean isAddStory;

    public Story() {
    }

    public Story(String id, String userName, String avatarUrl, String imageUrl, boolean isAddStory) {
        this.id = id;
        this.userName = userName;
        this.avatarUrl = avatarUrl;
        this.imageUrl = imageUrl;
        this.isAddStory = isAddStory;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public boolean isAddStory() {
        return isAddStory;
    }

    public void setAddStory(boolean addStory) {
        isAddStory = addStory;
    }
}
