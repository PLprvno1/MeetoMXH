package com.example.meetomxh.repositories;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.meetomxh.models.Post;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FeedRepository {

    public interface OnPostActionListener {
        void onSuccess(String message);
        void onFailure(String error);
    }

    private static FeedRepository instance;
    private final FirebaseFirestore db;
    private final FirebaseAuth auth;
    private final MutableLiveData<List<Post>> postsLiveData = new MutableLiveData<>();
    private ListenerRegistration postsListener;

    private FeedRepository() {
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        listenToRealPosts();
    }

    public static synchronized FeedRepository getInstance() {
        if (instance == null) {
            instance = new FeedRepository();
        }
        return instance;
    }

    public LiveData<List<Post>> getPosts() {
        return postsLiveData;
    }

    public void listenToRealPosts() {
        if (postsListener != null) {
            postsListener.remove();
        }

        FirebaseUser currentUser = auth.getCurrentUser();
        String currentUid = currentUser != null ? currentUser.getUid() : "";

        postsListener = db.collection("Posts")
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(30)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        loadFallbackPosts();
                        return;
                    }

                    if (snapshots != null && !snapshots.isEmpty()) {
                        List<Post> posts = new ArrayList<>();
                        final int total = snapshots.size();
                        final int[] count = {0};

                        snapshots.forEach(doc -> {
                            Post post = doc.toObject(Post.class);
                            post.setPostId(doc.getId());

                            // Load timeAgo calculation from createdAt
                            if (post.getCreatedAt() > 0) {
                                post.setTimeAgo(calculateTimeAgo(post.getCreatedAt()));
                            }

                            // Check current user reaction status
                            if (!currentUid.isEmpty()) {
                                db.collection("Posts").document(doc.getId())
                                        .collection("Reactions").document(currentUid)
                                        .get()
                                        .addOnCompleteListener(task -> {
                                            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                                                post.setLiked(true);
                                            } else {
                                                post.setLiked(false);
                                            }
                                            posts.add(post);
                                            count[0]++;
                                            if (count[0] == total) {
                                                // Sort by createdAt descending
                                                posts.sort((p1, p2) -> Long.compare(p2.getCreatedAt(), p1.getCreatedAt()));
                                                postsLiveData.setValue(posts);
                                            }
                                        });
                            } else {
                                posts.add(post);
                                count[0]++;
                                if (count[0] == total) {
                                    posts.sort((p1, p2) -> Long.compare(p2.getCreatedAt(), p1.getCreatedAt()));
                                    postsLiveData.setValue(posts);
                                }
                            }
                        });
                    } else {
                        loadFallbackPosts();
                    }
                });
    }

    private void loadFallbackPosts() {
        List<Post> dummyPosts = new ArrayList<>();
        dummyPosts.add(new Post(
                "p1",
                "u1",
                "Nguyễn Văn A",
                "https://i.pravatar.cc/150?img=1",
                "2 giờ trước",
                "Chào mừng mọi người đến với mạng xã hội MeetoMXH! Hãy trải nghiệm giao diện tin nhắn và bảng tin cực mượt nhé! 🚀",
                "https://picsum.photos/id/10/800/500",
                12,
                3,
                false
        ));

        dummyPosts.add(new Post(
                "p2",
                "u2",
                "Trần Thị B",
                "https://i.pravatar.cc/150?img=5",
                "4 giờ trước",
                "Hôm nay thời tiết Hà Nội thật đẹp, thích hợp đi cà phê cùng bạn bè ☕✨",
                "https://picsum.photos/id/1015/800/500",
                45,
                12,
                true
        ));

        dummyPosts.add(new Post(
                "p3",
                "u3",
                "Lê Hoàng C",
                "https://i.pravatar.cc/150?img=8",
                "6 giờ trước",
                "Vừa hoàn thành xong kiến trúc MVVM + Repository cho ứng dụng Android! Rất tối ưu và dễ mở rộng. 💻📱",
                "",
                89,
                24,
                false
        ));

        postsLiveData.setValue(dummyPosts);
    }

    public void toggleLikePost(String postId) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) return;

        String currentUid = currentUser.getUid();

        db.collection("Posts").document(postId)
                .collection("Reactions").document(currentUid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        // Un-like: delete reaction doc and decrement likesCount
                        db.runBatch(batch -> {
                            batch.delete(db.collection("Posts").document(postId).collection("Reactions").document(currentUid));
                            batch.update(db.collection("Posts").document(postId), "likesCount", FieldValue.increment(-1));
                        });
                    } else {
                        // Like: add reaction doc and increment likesCount
                        Map<String, Object> reaction = new HashMap<>();
                        reaction.put("uid", currentUid);
                        reaction.put("type", "LIKE");
                        reaction.put("createdAt", FieldValue.serverTimestamp());

                        db.runBatch(batch -> {
                            batch.set(db.collection("Posts").document(postId).collection("Reactions").document(currentUid), reaction);
                            batch.update(db.collection("Posts").document(postId), "likesCount", FieldValue.increment(1));
                        });
                    }
                });
    }

    public void sharePost(Post originalPost, String shareComment, OnPostActionListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onFailure("Vui lòng đăng nhập để chia sẻ");
            return;
        }

        String currentUid = currentUser.getUid();
        String newPostId = db.collection("Posts").document().getId();
        long now = System.currentTimeMillis();

        db.collection("Users").document(currentUid).get().addOnSuccessListener(userDoc -> {
            String authorName = userDoc.getString("fullName");
            String authorAvatar = userDoc.getString("avatarUrl");

            Map<String, Object> shareMap = new HashMap<>();
            shareMap.put("postId", newPostId);
            shareMap.put("authorUid", currentUid);
            shareMap.put("userName", authorName != null ? authorName : "Thành viên Meeto");
            shareMap.put("userAvatar", authorAvatar != null ? authorAvatar : "");
            shareMap.put("content", shareComment);
            shareMap.put("type", "share");
            shareMap.put("originalPostId", originalPost.getPostId());
            shareMap.put("createdAt", now);
            shareMap.put("likesCount", 0);
            shareMap.put("commentsCount", 0);
            shareMap.put("sharesCount", 0);

            db.runBatch(batch -> {
                batch.set(db.collection("Posts").document(newPostId), shareMap);
                batch.update(db.collection("Posts").document(originalPost.getPostId()), "sharesCount", FieldValue.increment(1));
            }).addOnSuccessListener(aVoid -> {
                if (listener != null) listener.onSuccess("Đã chia sẻ bài viết thành công!");
            }).addOnFailureListener(e -> {
                if (listener != null) listener.onFailure("Chia sẻ thất bại: " + e.getMessage());
            });
        });
    }

    public void deletePost(String postId, OnPostActionListener listener) {
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            if (listener != null) listener.onFailure("Vui lòng đăng nhập");
            return;
        }

        db.collection("Posts").document(postId).get().addOnSuccessListener(doc -> {
            if (doc.exists() && currentUser.getUid().equals(doc.getString("authorUid"))) {
                db.collection("Posts").document(postId).delete()
                        .addOnSuccessListener(aVoid -> {
                            if (listener != null) listener.onSuccess("Đã xóa bài viết");
                        })
                        .addOnFailureListener(e -> {
                            if (listener != null) listener.onFailure("Xóa bài viết thất bại: " + e.getMessage());
                        });
            } else {
                if (listener != null) listener.onFailure("Bạn không có quyền xóa bài viết này");
            }
        });
    }

    private String calculateTimeAgo(long timeMs) {
        long diff = System.currentTimeMillis() - timeMs;
        if (diff < 60000) return "Vừa xong";
        if (diff < 3600000) return (diff / 60000) + " phút trước";
        if (diff < 86400000) return (diff / 3600000) + " giờ trước";
        return (diff / 86400000) + " ngày trước";
    }
}
