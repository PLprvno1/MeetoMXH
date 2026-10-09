package com.example.meetomxh.repositories;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.meetomxh.models.Post;

import java.util.ArrayList;
import java.util.List;

public class FeedRepository {

    private static FeedRepository instance;
    private final MutableLiveData<List<Post>> postsLiveData = new MutableLiveData<>();
    private final List<Post> dummyPosts = new ArrayList<>();

    private FeedRepository() {
        loadDummyData();
    }

    public static synchronized FeedRepository getInstance() {
        if (instance == null) {
            instance = new FeedRepository();
        }
        return instance;
    }

    private void loadDummyData() {
        dummyPosts.add(new Post(
                "p1",
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
                "Lê Hoàng C",
                "https://i.pravatar.cc/150?img=8",
                "6 giờ trước",
                "Vừa hoàn thành xong kiến trúc MVVM + Repository cho ứng dụng Android! Rất tối ưu và dễ mở rộng. 💻📱",
                "",
                89,
                24,
                false
        ));

        dummyPosts.add(new Post(
                "p4",
                "Phạm Minh D",
                "https://i.pravatar.cc/150?img=12",
                "1 ngày trước",
                "Một buổi chiều bình yên bên bờ biển. Chúc mọi người cuối tuần nhiều niềm vui! 🌊☀️",
                "https://picsum.photos/id/1039/800/500",
                120,
                35,
                false
        ));

        postsLiveData.setValue(new ArrayList<>(dummyPosts));
    }

    public LiveData<List<Post>> getPosts() {
        return postsLiveData;
    }

    public void toggleLikePost(String postId) {
        for (Post post : dummyPosts) {
            if (post.getPostId().equals(postId)) {
                boolean currentlyLiked = post.isLiked();
                post.setLiked(!currentlyLiked);
                post.setLikesCount(currentlyLiked ? post.getLikesCount() - 1 : post.getLikesCount() + 1);
                break;
            }
        }
        postsLiveData.setValue(new ArrayList<>(dummyPosts));
    }
}
