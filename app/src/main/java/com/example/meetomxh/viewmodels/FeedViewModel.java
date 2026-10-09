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
}
