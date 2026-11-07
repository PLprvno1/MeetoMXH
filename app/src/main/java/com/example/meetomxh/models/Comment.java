package com.example.meetomxh.models;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** A readable comment plus the paging state for its immediate reply branch. */
public class Comment {
    public long version = 1;
    /** Physical parent ID when that parent is readable; null when it is redacted or absent. */
    public String parentId;
    /** Parent shown in the current UI tree; this can differ from parentId after a tombstone. */
    public String displayParentId;
    /** Current visible UI root, never a hidden/deleted ancestor. */
    public String rootId;
    public String state;
    public String serverCreatedAt;
    public int depth;
    public int displayDepth;
    public int replyCount;
    public boolean expanded;
    public boolean loadingReplies;
    public boolean hasMoreReplies;
    public boolean deleting;
    public boolean reactionPending;
    public String repliesError;
    public String afterRepliesTime;
    public String afterRepliesId;
    public Comment previewReply;
    public final List<Comment> loadedReplies = new ArrayList<>();

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
    public JSONArray mentions = new JSONArray();

    public Comment() { }

    public Comment(String commentId, String postId, String authorUid, String authorName,
                   String authorAvatar, String content, long createdAt) {
        this.commentId = commentId;
        this.postId = postId;
        this.authorUid = authorUid;
        this.authorName = authorName;
        this.authorAvatar = authorAvatar;
        this.content = content;
        this.createdAt = createdAt;
    }

    public String getCommentId() { return commentId; }
    public void setCommentId(String value) { commentId = value; }
    public String getPostId() { return postId; }
    public void setPostId(String value) { postId = value; }
    public String getAuthorUid() { return authorUid; }
    public void setAuthorUid(String value) { authorUid = value; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String value) { authorName = value; }
    public String getAuthorAvatar() { return authorAvatar; }
    public void setAuthorAvatar(String value) { authorAvatar = value; }
    public String getAuthorAvatarPreset() { return authorAvatarPreset; }
    public void setAuthorAvatarPreset(String value) {
        authorAvatarPreset = "male".equals(value) || "female".equals(value) ? value : "neutral";
    }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { createdAt = value; }
}
