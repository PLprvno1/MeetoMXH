package com.example.meetomxh.repositories;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * THIẾT KẾ CẤU TRÚC FIRESTORE TỐI ƯU CHO TÍNH NĂNG KẾT BẠN (FRIENDSHIP ARCHITECTURE):
 *
 * Cấu trúc áp dụng: Subcollections kết hợp Dual-write Transaction
 *
 * 1. Collection "Users/{userId}/Friends/{targetUserId}":
 *    Document ID: targetUserId (UID của người được kết bạn)
 *    Fields:
 *      - friendId (String): UID đối phương
 *      - status (String):
 *          + "PENDING_SENT": Đã gửi lời mời (Đang chờ đối phương đồng ý)
 *          + "PENDING_RECEIVED": Nhận được lời mời (Đang chờ mình đồng ý)
 *          + "ACCEPTED": Đã là bạn bè
 *      - createdAt (Timestamp): Thời gian tạo/gửi lời mời
 *      - updatedAt (Timestamp): Thời gian đồng ý/cập nhật
 *
 * 2. Luồng Xử Lý:
 *    A. Gửi lời mời (A -> B):
 *       - Tạo/Ghi đè doc Users/A/Friends/B -> status = "PENDING_SENT"
 *       - Tạo/Ghi đè doc Users/B/Friends/A -> status = "PENDING_RECEIVED"
 *
 *    B. Đồng ý lời mời (B đồng ý lời mời từ A):
 *       - Cập nhật doc Users/B/Friends/A -> status = "ACCEPTED"
 *       - Cập nhật doc Users/A/Friends/B -> status = "ACCEPTED"
 *
 * 3. Ưu điểm kiến trúc:
 *    - Lấy danh sách bạn bè nhanh chóng: db.collection("Users").document(uid).collection("Friends").whereEqualTo("status", "ACCEPTED")
 *    - Bảo mật dữ liệu cấp độ cá nhân (Security Rules kiểm tra request.auth.uid == userId)
 *    - Dễ dàng kiểm tra trạng thái quan hệ giữa 2 user bất kỳ chỉ với 1 lượt đọc document đơn lẻ.
 */
public class FriendRepository {

    public interface OnFriendActionListener {
        void onSuccess(String message);
        void onFailure(String error);
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
     * Hàm gửi lời mời kết bạn từ Current User tới Target User.
     *
     * @param targetUserId UID của người nhận lời mời
     * @param listener Callback lắng nghe kết quả thành công/thất bại
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

        // Tạo dữ liệu bản ghi phía người gửi (A)
        Map<String, Object> senderData = new HashMap<>();
        senderData.put("friendId", targetUserId);
        senderData.put("status", "PENDING_SENT");
        senderData.put("createdAt", FieldValue.serverTimestamp());

        // Tạo dữ liệu bản ghi phía người nhận (B)
        Map<String, Object> receiverData = new HashMap<>();
        receiverData.put("friendId", currentUserId);
        receiverData.put("status", "PENDING_RECEIVED");
        receiverData.put("createdAt", FieldValue.serverTimestamp());

        // Sử dụng Firestore Batch Write để đảm bảo cả 2 thao tác đồng thời thành công
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
     * Hàm đồng ý lời mời kết bạn từ Request User.
     *
     * @param requestUserId UID của người đã gửi lời mời kết bạn cho Current User
     * @param listener Callback lắng nghe kết quả thành công/thất bại
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

        // Cập nhật trạng thái ACCEPTED cho cả 2 tài khoản
        db.runBatch(batch -> {
            batch.update(
                    db.collection("Users").document(currentUserId).collection("Friends").document(requestUserId),
                    updateData
            );
            batch.update(
                    db.collection("Users").document(requestUserId).collection("Friends").document(currentUserId),
                    updateData
            );
        }).addOnSuccessListener(aVoid -> {
            if (listener != null) listener.onSuccess("Đã đồng ý lời mời kết bạn");
        }).addOnFailureListener(e -> {
            if (listener != null) listener.onFailure("Đồng ý kết bạn thất bại: " + e.getMessage());
        });
    }
}
