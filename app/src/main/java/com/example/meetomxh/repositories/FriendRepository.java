package com.example.meetomxh.repositories;

import com.example.meetomxh.models.FriendItem;
import com.example.meetomxh.models.User;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FriendRepository {

    public interface OnFriendActionListener {
        void onSuccess(String message);
        void onFailure(String error);
    }

    public interface OnRelationshipStatusListener {
        void onStatus(FriendItem.RelationshipStatus status);
        void onError(String error);
    }

    public interface OnFriendItemsListener {
        void onItems(List<FriendItem> items);
        void onError(String error);
    }

    private static FriendRepository instance;
    private final FirebaseFirestore db;
    private final FirebaseAuth auth;

    private FriendRepository() {
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
    }

    public static synchronized FriendRepository getInstance() {
        if (instance == null) {
            instance = new FriendRepository();
        }
        return instance;
    }

    /**
     * Gửi lời mời kết bạn từ Current User tới Target User.
     */
    public void sendFriendRequest(String targetUserId, OnFriendActionListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onFailure("Người dùng chưa đăng nhập");
            return;
        }

        String currentUserId = currentUser.getUid();
        if (currentUserId.equals(targetUserId)) {
            if (listener != null) listener.onFailure("Không thể gửi lời mời kết bạn cho chính mình");
            return;
        }

        Map<String, Object> senderData = new HashMap<>();
        senderData.put("friendId", targetUserId);
        senderData.put("status", "PENDING_SENT");
        senderData.put("createdAt", FieldValue.serverTimestamp());

        Map<String, Object> receiverData = new HashMap<>();
        receiverData.put("friendId", currentUserId);
        receiverData.put("status", "PENDING_RECEIVED");
        receiverData.put("createdAt", FieldValue.serverTimestamp());

        db.runBatch(batch -> {
            batch.set(
                    db.collection("Users").document(currentUserId).collection("Friends").document(targetUserId),
                    senderData,
                    SetOptions.merge()
            );
            batch.set(
                    db.collection("Users").document(targetUserId).collection("Friends").document(currentUserId),
                    receiverData,
                    SetOptions.merge()
            );
        }).addOnSuccessListener(aVoid -> {
            if (listener != null) listener.onSuccess("Đã gửi lời mời kết bạn");
        }).addOnFailureListener(e -> {
            if (listener != null) listener.onFailure("Gửi lời mời thất bại: " + e.getMessage());
        });
    }

    /**
     * Đồng ý lời mời kết bạn.
     */
    public void acceptFriendRequest(String requestUserId, OnFriendActionListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onFailure("Người dùng chưa đăng nhập");
            return;
        }

        String currentUserId = currentUser.getUid();

        Map<String, Object> updateData = new HashMap<>();
        updateData.put("status", "ACCEPTED");
        updateData.put("updatedAt", FieldValue.serverTimestamp());

        db.runBatch(batch -> {
            batch.set(
                    db.collection("Users").document(currentUserId).collection("Friends").document(requestUserId),
                    updateData,
                    SetOptions.merge()
            );
            batch.set(
                    db.collection("Users").document(requestUserId).collection("Friends").document(currentUserId),
                    updateData,
                    SetOptions.merge()
            );
        }).addOnSuccessListener(aVoid -> {
            if (listener != null) listener.onSuccess("Đã đồng ý lời mời kết bạn");
        }).addOnFailureListener(e -> {
            if (listener != null) listener.onFailure("Đồng ý kết bạn thất bại: " + e.getMessage());
        });
    }

    /**
     * Hủy lời mời kết bạn đã gửi / Từ chối lời mời kết bạn đã nhận / Hủy kết bạn (Unfriend).
     */
    public void removeFriendRelation(String targetUserId, OnFriendActionListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onFailure("Người dùng chưa đăng nhập");
            return;
        }

        String currentUserId = currentUser.getUid();

        db.runBatch(batch -> {
            batch.delete(db.collection("Users").document(currentUserId).collection("Friends").document(targetUserId));
            batch.delete(db.collection("Users").document(targetUserId).collection("Friends").document(currentUserId));
        }).addOnSuccessListener(aVoid -> {
            if (listener != null) listener.onSuccess("Đã xóa quan hệ bạn bè/lời mời");
        }).addOnFailureListener(e -> {
            if (listener != null) listener.onFailure("Thao tác thất bại: " + e.getMessage());
        });
    }

    /**
     * Kiểm tra trạng thái quan hệ giữa Current User và Target User.
     */
    public void getRelationshipStatus(String targetUserId, OnRelationshipStatusListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onError("Người dùng chưa đăng nhập");
            return;
        }

        String currentUserId = currentUser.getUid();
        if (currentUserId.equals(targetUserId)) {
            if (listener != null) listener.onStatus(FriendItem.RelationshipStatus.SELF);
            return;
        }

        db.collection("Users").document(currentUserId)
                .collection("Friends").document(targetUserId)
                .get()
                .addOnSuccessListener(document -> {
                    if (document.exists() && document.contains("status")) {
                        String statusStr = document.getString("status");
                        if ("ACCEPTED".equals(statusStr)) {
                            listener.onStatus(FriendItem.RelationshipStatus.ACCEPTED);
                        } else if ("PENDING_SENT".equals(statusStr)) {
                            listener.onStatus(FriendItem.RelationshipStatus.PENDING_SENT);
                        } else if ("PENDING_RECEIVED".equals(statusStr)) {
                            listener.onStatus(FriendItem.RelationshipStatus.PENDING_RECEIVED);
                        } else {
                            listener.onStatus(FriendItem.RelationshipStatus.NO_RELATION);
                        }
                    } else {
                        listener.onStatus(FriendItem.RelationshipStatus.NO_RELATION);
                    }
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    /**
     * Lấy danh sách Lời mời kết bạn đã nhận.
     */
    public void getReceivedFriendRequests(OnFriendItemsListener listener) {
        getFriendsByStatus("PENDING_RECEIVED", listener);
    }

    /**
     * Lấy danh sách Lời mời kết bạn đã gửi.
     */
    public void getSentFriendRequests(OnFriendItemsListener listener) {
        getFriendsByStatus("PENDING_SENT", listener);
    }

    /**
     * Lấy danh sách Bạn bè đã kết bạn.
     */
    public void getFriendsList(String userId, OnFriendItemsListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onError("Người dùng chưa đăng nhập");
            return;
        }

        db.collection("Users").document(userId)
                .collection("Friends")
                .whereEqualTo("status", "ACCEPTED")
                .get()
                .addOnSuccessListener(querySnapshots -> {
                    List<String> friendUids = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : querySnapshots) {
                        friendUids.add(doc.getId());
                    }
                    fetchUsersAndBuildItems(friendUids, FriendItem.RelationshipStatus.ACCEPTED, listener);
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    private void getFriendsByStatus(String statusStr, OnFriendItemsListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onError("Người dùng chưa đăng nhập");
            return;
        }

        FriendItem.RelationshipStatus relStatus = "PENDING_RECEIVED".equals(statusStr) ?
                FriendItem.RelationshipStatus.PENDING_RECEIVED : FriendItem.RelationshipStatus.PENDING_SENT;

        db.collection("Users").document(currentUser.getUid())
                .collection("Friends")
                .whereEqualTo("status", statusStr)
                .get()
                .addOnSuccessListener(querySnapshots -> {
                    List<String> friendUids = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : querySnapshots) {
                        friendUids.add(doc.getId());
                    }
                    fetchUsersAndBuildItems(friendUids, relStatus, listener);
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    /**
     * Tìm kiếm người dùng theo tên và trả về trạng thái quan hệ tương ứng.
     */
    public void searchUsers(String queryText, OnFriendItemsListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onError("Người dùng chưa đăng nhập");
            return;
        }

        String currentUid = currentUser.getUid();
        String trimmedQuery = queryText != null ? queryText.trim() : "";

        if (trimmedQuery.isEmpty()) {
            getSuggestedFriends(listener);
            return;
        }

        db.collection("Users")
                .whereGreaterThanOrEqualTo("fullName", trimmedQuery)
                .whereLessThanOrEqualTo("fullName", trimmedQuery + "\uf8ff")
                .limit(20)
                .get()
                .addOnSuccessListener(querySnapshots -> {
                    List<User> userList = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : querySnapshots) {
                        if (!doc.getId().equals(currentUid)) {
                            User u = doc.toObject(User.class);
                            u.setUid(doc.getId());
                            userList.add(u);
                        }
                    }
                    attachRelationshipsAndReturn(userList, listener);
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    /**
     * Lấy danh sách gợi ý kết bạn (những người dùng chưa kết bạn).
     */
    public void getSuggestedFriends(OnFriendItemsListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onError("Người dùng chưa đăng nhập");
            return;
        }

        String currentUid = currentUser.getUid();

        db.collection("Users")
                .limit(15)
                .get()
                .addOnSuccessListener(querySnapshots -> {
                    List<User> userList = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : querySnapshots) {
                        if (!doc.getId().equals(currentUid)) {
                            User u = doc.toObject(User.class);
                            u.setUid(doc.getId());
                            userList.add(u);
                        }
                    }
                    attachRelationshipsAndReturn(userList, listener);
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    private void attachRelationshipsAndReturn(List<User> users, OnFriendItemsListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null || users.isEmpty()) {
            if (listener != null) listener.onItems(new ArrayList<>());
            return;
        }

        String currentUid = currentUser.getUid();

        db.collection("Users").document(currentUid).collection("Friends")
                .get()
                .addOnSuccessListener(friendsSnap -> {
                    Map<String, String> statusMap = new HashMap<>();
                    for (QueryDocumentSnapshot doc : friendsSnap) {
                        statusMap.put(doc.getId(), doc.getString("status"));
                    }

                    List<FriendItem> items = new ArrayList<>();
                    for (User u : users) {
                        String statusStr = statusMap.get(u.getUid());
                        FriendItem.RelationshipStatus status;
                        if ("ACCEPTED".equals(statusStr)) {
                            status = FriendItem.RelationshipStatus.ACCEPTED;
                        } else if ("PENDING_SENT".equals(statusStr)) {
                            status = FriendItem.RelationshipStatus.PENDING_SENT;
                        } else if ("PENDING_RECEIVED".equals(statusStr)) {
                            status = FriendItem.RelationshipStatus.PENDING_RECEIVED;
                        } else {
                            status = FriendItem.RelationshipStatus.NO_RELATION;
                        }
                        items.add(new FriendItem(u, status));
                    }
                    if (listener != null) listener.onItems(items);
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    private void fetchUsersAndBuildItems(List<String> uids, FriendItem.RelationshipStatus relStatus, OnFriendItemsListener listener) {
        if (uids.isEmpty()) {
            if (listener != null) listener.onItems(new ArrayList<>());
            return;
        }

        List<FriendItem> items = new ArrayList<>();
        final int total = uids.size();
        final int[] completed = {0};

        for (String friendUid : uids) {
            db.collection("Users").document(friendUid)
                    .get()
                    .addOnCompleteListener(task -> {
                        completed[0]++;
                        if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                            DocumentSnapshot doc = task.getResult();
                            User user = doc.toObject(User.class);
                            if (user != null) {
                                user.setUid(doc.getId());
                                items.add(new FriendItem(user, relStatus));
                            }
                        }
                        if (completed[0] == total) {
                            if (listener != null) listener.onItems(items);
                        }
                    });
        }
    }
}
