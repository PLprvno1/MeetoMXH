package com.example.meetomxh.models;

import com.example.meetomxh.supabase.ServerTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;

/** Chat message projected by the authorized message RPC. */
public class ChatMessage {
    public long seq,version;
    private String messageId;
    private String senderId;
    private String receiverId;
    private String type;
    private String text;
    private String imageUrl;
    private String mediaMimeType;
    private ServerTime createdAt;
    private boolean isRecalled;
    private ServerTime recalledAt;
    private List<String> deletedFor = new ArrayList<>();
    private String replyToMessageId;
    private long replyToSeq;
    private String replyToSenderId;
    private String replyPreview;
    private String sharedPostId;
    private String sharedPostVideoAssetId;
    private String storyAuthor;public void setStoryAuthor(String value){storyAuthor=value;}public String getStoryAuthor(){return storyAuthor;}
    private String sharedAuthor,sharedCaption,storyId,storyCaption,sharedPreview,sharedMime;
    public void setSharedPreview(String asset,String mime){sharedPreview=asset;sharedMime=mime;}
    public String getSharedPreview(){return sharedPreview;}
    public String getSharedMime(){return sharedMime;}
    private String sharedAvatar,sharedAvatarPreset;
    public void setSharedAvatar(String asset,String preset){sharedAvatar=asset;sharedAvatarPreset=preset;}
    public String getSharedAvatar(){return sharedAvatar;} public String getSharedAvatarPreset(){return sharedAvatarPreset;}
    public void setSharedContext(String author,String caption){sharedAuthor=author;sharedCaption=caption!=null&&caption.length()>240?caption.substring(0,240)+"…":caption;}
    public void setStoryContext(String id,String caption){storyId=id;storyCaption=caption;}
    public String getStoryId(){return storyId;}
    private boolean sharedPostPresent;
    private boolean sharedPostAvailable;
    private boolean forwarded;
    private ServerTime editedAt;
    private long voiceDurationMs;

    public ChatMessage() { }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getReceiverId() { return receiverId; }
    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getMediaMimeType(){return mediaMimeType;}
    public void setMediaMimeType(String value){mediaMimeType=value;}
    public ServerTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ServerTime createdAt) { this.createdAt = createdAt; }
    public boolean isRecalled() { return isRecalled; }
    public void setRecalled(boolean recalled) { isRecalled = recalled; }
    public ServerTime getRecalledAt() { return recalledAt; }
    public void setRecalledAt(ServerTime recalledAt) { this.recalledAt = recalledAt; }
    public List<String> getDeletedFor() { return deletedFor; }
    public void setDeletedFor(List<String> deletedFor) { this.deletedFor = deletedFor; }
    public String getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(String replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public long getReplyToSeq(){return replyToSeq;}
    public void setReplyToSeq(long value){replyToSeq=value;}
    public String getReplyToSenderId() { return replyToSenderId; }
    public void setReplyToSenderId(String replyToSenderId) { this.replyToSenderId = replyToSenderId; }
    public String getReplyPreview() { return replyPreview; }
    public void setReplyPreview(String replyPreview) { this.replyPreview = replyPreview; }
    public String getSharedPostId(){return sharedPostId;}
    public String getSharedPostVideoAssetId(){return sharedPostVideoAssetId;}
    public void setSharedPostVideoAssetId(String value){sharedPostVideoAssetId=value;}
    public void setSharedPost(String id,boolean present,boolean available){sharedPostId=id;sharedPostPresent=present;sharedPostAvailable=available;}
    public boolean hasSharedPost(){return sharedPostPresent;}
    public boolean isSharedPostAvailable(){return sharedPostAvailable;}
    public boolean isForwarded(){return forwarded;}
    public void setForwarded(boolean value){forwarded=value;}
    public ServerTime getEditedAt(){return editedAt;}
    public void setEditedAt(ServerTime value){editedAt=value;}
    public long getVoiceDurationMs(){return voiceDurationMs;}
    public void setVoiceDurationMs(long value){voiceDurationMs=Math.max(0,value);}

    public boolean isDeletedFor(String uid) {
        return uid != null && deletedFor != null && deletedFor.contains(uid);
    }

    public String getDisplayText() {
        if (sharedPostPresent) return sharedPostAvailable ? (sharedAuthor==null?"Bài viết được chia sẻ":sharedAuthor)+"\n"+(sharedCaption==null?"":sharedCaption)+"\nXem bài viết gốc" : "Bài viết không còn khả dụng";
        if(storyId!=null)return "Trả lời tin: "+(storyCaption==null?"Tin đã hết hạn hoặc không còn khả dụng":storyCaption)+"\n"+(text==null?"":text);
        if (!isRecalled && "image".equals(type)) return "\u1ea2nh";
        if (!isRecalled && "video".equals(type)) return "Video";
        if (!isRecalled && "voice".equals(type)) return "Tin nhắn thoại";
        return isRecalled ? "Tin nhắn đã được thu hồi" : (text == null ? "" : text);
    }

    public boolean matchesVisibleTextQuery(String uid, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return !needle.isEmpty() && "text".equals(type) && !isRecalled && !isDeletedFor(uid)
                && text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }

}
