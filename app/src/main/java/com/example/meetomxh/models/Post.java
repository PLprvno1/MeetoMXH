package com.example.meetomxh.models;

import java.util.ArrayList;
import java.util.List;

public class Post {
    private String postId;
    private String authorUid;
    private String userName;
    private String userAvatar;
    private String timeAgo;
    private String content;
    private String postImage;
    private List<String> imageUrls;
    private int likesCount;
    private int commentsCount;
    private int sharesCount;
    private boolean isLiked;
    private String type; // "normal" or "share"
    private String originalPostId;
    private Post originalPost;
    private long createdAt;
    private long updatedAt;
    private String visibility; // "public", "friends", "private"

    public Post() {
        this.imageUrls = new ArrayList<>();
        this.type = "normal";
        this.visibility = "public";
    }

    public Post(String postId, String authorUid, String userName, String userAvatar, String timeAgo, String content, String postImage, int likesCount, int commentsCount, boolean isLiked) {
        this();
        this.postId = postId;
        this.authorUid = authorUid;
        this.userName = userName;
        this.userAvatar = userAvatar;
        this.timeAgo = timeAgo;
        this.content = content;
        this.postImage = postImage;
        if (postImage != null && !postImage.isEmpty()) {
            this.imageUrls.add(postImage);
        }
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

    public String getAuthorUid() {
        return authorUid;
    }

    public void setAuthorUid(String authorUid) {
        this.authorUid = authorUid;
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
        if (postImage != null && !postImage.isEmpty()) {
            return postImage;
        }
        if (imageUrls != null && !imageUrls.isEmpty()) {
            return imageUrls.get(0);
        }
        return "";
    }

    public void setPostImage(String postImage) {
        this.postImage = postImage;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
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

    public int getSharesCount() {
        return sharesCount;
    }

    public void setSharesCount(int sharesCount) {
        this.sharesCount = sharesCount;
    }

    public boolean isLiked() {
        return isLiked;
    }

    public void setLiked(boolean liked) {
        isLiked = liked;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOriginalPostId() {
        return originalPostId;
    }

    public void setOriginalPostId(String originalPostId) {
        this.originalPostId = originalPostId;
    }

    public Post getOriginalPost() {
        return originalPost;
    }

    public void setOriginalPost(Post originalPost) {
        this.originalPost = originalPost;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }
}
