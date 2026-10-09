package com.example.meetomxh.fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.FragmentFeedBinding;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.viewmodels.FeedViewModel;

public class FeedFragment extends Fragment implements FeedAdapter.OnPostClickListener {

    private FragmentFeedBinding binding;
    private FeedViewModel viewModel;
    private FeedAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentFeedBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Setup Shimmer Loading
        binding.layoutShimmer.getRoot().setVisibility(View.VISIBLE);
        binding.layoutShimmer.getRoot().startShimmer();
        binding.rvFeed.setVisibility(View.GONE);

        // Khởi tạo Adapter & RecyclerView
        adapter = new FeedAdapter(this);
        binding.rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvFeed.setAdapter(adapter);

        // Khởi tạo ViewModel
        viewModel = new ViewModelProvider(this).get(FeedViewModel.class);

        // Observe danh sách bài đăng từ ViewModel
        viewModel.getPosts().observe(getViewLifecycleOwner(), posts -> {
            if (posts != null) {
                adapter.setPosts(posts);
            }
        });

        // Giả lập thời gian tải dữ liệu 1.5 giây với Shimmer
        handler.postDelayed(() -> {
            if (binding != null) {
                binding.layoutShimmer.getRoot().stopShimmer();
                binding.layoutShimmer.getRoot().setVisibility(View.GONE);
                binding.rvFeed.setVisibility(View.VISIBLE);
            }
        }, 1500);

        // Bắt sự kiện click FloatingActionButton (Nút Chat Messenger)
        binding.fabChat.setOnClickListener(v ->
                Toast.makeText(requireContext(), "Mở màn hình Chat Messenger", Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public void onLikeClick(Post post) {
        if (post != null) {
            viewModel.likePost(post.getPostId());
        }
    }

    @Override
    public void onCommentClick(Post post) {
        if (post != null) {
            Toast.makeText(requireContext(), "Bình luận về bài viết của " + post.getUserName(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCreatePostClick() {
        Toast.makeText(requireContext(), "Mở màn hình Tạo bài viết mới", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
        binding = null;
    }
}
