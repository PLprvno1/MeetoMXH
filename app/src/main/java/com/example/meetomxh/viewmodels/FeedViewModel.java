package com.example.meetomxh.viewmodels;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.FeedRepository;

import java.util.List;

public class FeedViewModel extends ViewModel {

    private final FeedRepository repository;

    public FeedViewModel() {
        repository = FeedRepository.getInstance();
    }

    public LiveData<List<Post>> getPosts() {
        return repository.getPosts();
    }

    public void likePost(String postId) {
        repository.toggleLikePost(postId);
    }

    public void sharePost(Post originalPost, String comment, FeedRepository.OnPostActionListener listener) {
        repository.sharePost(originalPost, comment, listener);
    }

    public void deletePost(String postId, FeedRepository.OnPostActionListener listener) {
        repository.deletePost(postId, listener);
    }
}
