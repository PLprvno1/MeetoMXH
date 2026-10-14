package com.example.meetomxh.models;

public class FriendItem {

    public enum RelationshipStatus {
        NO_RELATION,
        PENDING_SENT,
        PENDING_RECEIVED,
        ACCEPTED,
        SELF
    }

    private User user;
    private RelationshipStatus relationshipStatus;
    private long timestamp;

    public FriendItem() {
        this.relationshipStatus = RelationshipStatus.NO_RELATION;
    }

    public FriendItem(User user, RelationshipStatus relationshipStatus) {
        this.user = user;
        this.relationshipStatus = relationshipStatus;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public RelationshipStatus getRelationshipStatus() {
        return relationshipStatus;
    }

    public void setRelationshipStatus(RelationshipStatus relationshipStatus) {
        this.relationshipStatus = relationshipStatus;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
