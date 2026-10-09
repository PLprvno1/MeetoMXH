package com.example.meetomxh.models;

public class Post {
    private String postId;
    private String userName;
    private String userAvatar;
    private String timeAgo;
    private String content;
    private String postImage;
    private int likesCount;
    private int commentsCount;
    private boolean isLiked;

    public Post() {
    }

    public Post(String postId, String userName, String userAvatar, String timeAgo, String content, String postImage, int likesCount, int commentsCount, boolean isLiked) {
        this.postId = postId;
        this.userName = userName;
        this.userAvatar = userAvatar;
        this.timeAgo = timeAgo;
        this.content = content;
        this.postImage = postImage;
        this.likesCount = likesCount;
        this.commentsCount = commentsCount;
        this.isLiked = isLiked;
    }

    public String getPostId() {
        return postId;
    }

    public void setPostId(String postId) {
        this.postId = postId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserAvatar() {
        return userAvatar;
    }

    public void setUserAvatar(String userAvatar) {
        this.userAvatar = userAvatar;
    }

    public String getTimeAgo() {
        return timeAgo;
    }

    public void setTimeAgo(String timeAgo) {
        this.timeAgo = timeAgo;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPostImage() {
        return postImage;
    }

    public void setPostImage(String postImage) {
        this.postImage = postImage;
    }

    public int getLikesCount() {
        return likesCount;
    }

    public void setLikesCount(int likesCount) {
        this.likesCount = likesCount;
    }

    public int getCommentsCount() {
        return commentsCount;
    }

    public void setCommentsCount(int commentsCount) {
        this.commentsCount = commentsCount;
    }

    public boolean isLiked() {
        return isLiked;
    }

    public void setLiked(boolean liked) {
        isLiked = liked;
    }
}
