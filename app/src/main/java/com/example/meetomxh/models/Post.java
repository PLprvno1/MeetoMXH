package com.example.meetomxh.models;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

public class Post {
    public long version=1;
    public boolean canShareMemory;
    public String serverCreatedAt;
    private String postId;
    private String groupId;
    private String postState;
    private String authorUid;
    private String userName;
    private String userAvatar;
    private String timeAgo;
    private String content;
    private String postImage;
    private String mediaType;
    private List<String> imageUrls;
    private List<String> mediaTypes;
    private String userAvatarPreset = "neutral";
    private int likesCount;
    private int commentsCount;
    private int sharesCount;
    private boolean isLiked;
    private String reaction;
    private boolean isSaved;
    private String type; // "normal" or "share"
    private String originalPostId;
    private Post originalPost;
    private long createdAt;
    private long updatedAt;
    private String visibility; // "public", "friends", "private"
    private JSONArray mentions = new JSONArray();
    private JSONArray tags = new JSONArray();
    private boolean reel;
    private boolean reelFollowing;
    private int reelDurationMs;
    private int reelCoverPositionMs;
    private String reelCoverAssetId;

    public Post() {
        this.imageUrls = new ArrayList<>();
        this.mediaTypes = new ArrayList<>();
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

    public String getGroupId() { return groupId; }
    public void setGroupId(String value) { groupId = value; }
    public String getPostState() { return postState; }
    public void setPostState(String value) { postState = value; }

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

    public String getUserAvatarPreset() { return userAvatarPreset; }

    public void setUserAvatarPreset(String value) {
        this.userAvatarPreset = "male".equals(value) || "female".equals(value) ? value : "neutral";
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

    public String getMediaType() { return mediaType; }

    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public List<String> getMediaTypes() { return mediaTypes; }

    public void setMediaTypes(List<String> types) {
        this.mediaTypes = types == null ? new ArrayList<>() : new ArrayList<>(types);
    }

    public void setMediaTypeAt(int index, String mimeType) {
        while (mediaTypes.size() <= index) mediaTypes.add("image/*");
        mediaTypes.set(index, mimeType == null ? "image/*" : mimeType);
        if (index == 0) mediaType = mimeType != null && mimeType.startsWith("video/") ? "video" : "image";
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
        return reaction != null ? !reaction.isEmpty() : isLiked;
    }

    public void setLiked(boolean liked) {
        isLiked = liked;
        if (!liked) reaction = null;
        else if (reaction == null) reaction = "like";
    }

    public String getReaction() { return reaction; }
    public void setReaction(String value) { reaction = value; isLiked = value != null; }

    public boolean isSaved() { return isSaved; }
    public void setSaved(boolean saved) { isSaved = saved; }

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
    public JSONArray getMentions(){return mentions;}
    public void setMentions(JSONArray value){mentions=value==null?new JSONArray():value;}
    public JSONArray getTags(){return tags;}
    public void setTags(JSONArray value){tags=value==null?new JSONArray():value;}
    public boolean isReel(){return reel;}
    public void setReel(boolean value){reel=value;}
    public boolean isReelFollowing(){return reelFollowing;}
    public void setReelFollowing(boolean value){reelFollowing=value;}
    public int getReelDurationMs(){return reelDurationMs;}
    public void setReelDurationMs(int value){reelDurationMs=value;}
    public int getReelCoverPositionMs(){return reelCoverPositionMs;}
    public void setReelCoverPositionMs(int value){reelCoverPositionMs=value;}
    public String getReelCoverAssetId(){return reelCoverAssetId;}
    public void setReelCoverAssetId(String value){reelCoverAssetId=value;}
}
