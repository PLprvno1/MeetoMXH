package com.example.meetomxh.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.meetomxh.CreatePostActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.adapters.FeedAdapter;
import com.example.meetomxh.databinding.FragmentFeedBinding;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.repositories.FeedRepository;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.viewmodels.FeedViewModel;

public class FeedFragment extends Fragment implements FeedAdapter.OnPostClickListener {

    private FragmentFeedBinding binding;
    private FeedViewModel viewModel;
    private FeedAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String refreshAnchorId;
    private int refreshAnchorOffset;
    private boolean pullRefreshing;

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
        binding.rvFeed.setAdapter(adapter);com.example.meetomxh.supabase.PostUpdates.counts.observe(getViewLifecycleOwner(),c->{if(adapter!=null&&c!=null)adapter.updateCommentCount(c.id,c.comments);});
        binding.swipeFeed.setOnRefreshListener(()->{
            pullRefreshing=true;
            LinearLayoutManager lm=(LinearLayoutManager)binding.rvFeed.getLayoutManager();int pos=lm==null?0:lm.findFirstVisibleItemPosition();
            if(pos>=0&&adapter.getItemCount()>0){refreshAnchorId=adapter.getPostIdAtAdapterPosition(pos);View child=lm.findViewByPosition(pos);refreshAnchorOffset=child==null?0:child.getTop();}
            FeedRepository.getInstance().refresh();
        });

        // Khởi tạo ViewModel
        viewModel = new ViewModelProvider(this).get(FeedViewModel.class);

        // Observe danh sách bài đăng từ ViewModel
        viewModel.getPosts().observe(getViewLifecycleOwner(), posts -> {
            if (posts != null) {
                adapter.setPosts(posts); if(refreshAnchorId!=null){String rid=refreshAnchorId;int ro=refreshAnchorOffset;refreshAnchorId=null;binding.rvFeed.post(()->{if(binding!=null){int p=adapter.positionOfPost(rid);if(p>=0)((LinearLayoutManager)binding.rvFeed.getLayoutManager()).scrollToPositionWithOffset(p,ro);}});}binding.layoutShimmer.getRoot().stopShimmer();binding.layoutShimmer.getRoot().setVisibility(View.GONE);binding.swipeFeed.setVisibility(View.VISIBLE);binding.rvFeed.setVisibility(View.VISIBLE);binding.feedStatus.setText(posts.isEmpty()?"Chưa có bài viết · chạm để tải lại":"");binding.feedStatus.setVisibility(posts.isEmpty()?View.VISIBLE:View.GONE);
            }
        });

        FeedRepository.getInstance().loadingState.observe(getViewLifecycleOwner(),busy->{if(binding==null)return;binding.swipeFeed.setVisibility(View.VISIBLE);binding.swipeFeed.setRefreshing(pullRefreshing&&Boolean.TRUE.equals(busy));if(!Boolean.TRUE.equals(busy)){pullRefreshing=false;binding.swipeFeed.setRefreshing(false);binding.rvFeed.setVisibility(View.VISIBLE);}if(Boolean.TRUE.equals(busy)&&(viewModel.getPosts().getValue()==null||viewModel.getPosts().getValue().isEmpty())){binding.layoutShimmer.getRoot().setVisibility(View.VISIBLE);binding.layoutShimmer.getRoot().startShimmer();binding.feedStatus.setVisibility(View.GONE);}else if(!Boolean.TRUE.equals(busy)){binding.layoutShimmer.getRoot().stopShimmer();binding.layoutShimmer.getRoot().setVisibility(View.GONE);}});FeedRepository.getInstance().error.observe(getViewLifecycleOwner(),e->{if(binding!=null&&e!=null){pullRefreshing=false;binding.swipeFeed.setVisibility(View.VISIBLE);binding.rvFeed.setVisibility(View.VISIBLE);binding.swipeFeed.setRefreshing(false);binding.layoutShimmer.getRoot().stopShimmer();binding.layoutShimmer.getRoot().setVisibility(View.GONE);binding.feedStatus.setText(e+" · chạm để thử lại");binding.feedStatus.setVisibility(View.VISIBLE);}});binding.feedStatus.setOnClickListener(v->FeedRepository.getInstance().refresh());binding.rvFeed.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener(){public void onScrolled(androidx.recyclerview.widget.RecyclerView r,int dx,int dy){if(dy>0&&!r.canScrollVertically(1))FeedRepository.getInstance().loadMore();}});
        binding.fabChat.setOnClickListener(v -> {
            if (!isAdded()||getParentFragmentManager().isStateSaved()) return;
            androidx.fragment.app.FragmentManager fragmentManager = getParentFragmentManager();
            if (fragmentManager.findFragmentById(R.id.fragment_container) instanceof InboxFragment) return;
            fragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, new InboxFragment())
                    .addToBackStack("inbox")
                    .commit();
        });
    }

    @Override
    public void onLikeClick(Post post, Runnable completed) {
        if (post != null && post.getPostId() != null) {
            viewModel.likePost(post.getPostId(), new FeedRepository.OnPostActionListener() {
                @Override public void onSuccess(String message) { completed.run(); }
                @Override public void onFailure(String error) {
                    completed.run();
                    if (isAdded()) Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            completed.run();
        }
    }

    @Override
    public void onCommentClick(Post post) {
        if (isAdded()&&!getChildFragmentManager().isStateSaved()&&post != null && post.getPostId() != null) {
            CommentsBottomSheetFragment bottomSheet = CommentsBottomSheetFragment.newInstance(post.getPostId());
            bottomSheet.show(getChildFragmentManager(), "CommentsBottomSheet");
        }
    }

    @Override
    public void onShareClick(Post post) {
        if (isAdded() && post != null) com.example.meetomxh.supabase.ShareActions.show(requireActivity(),post,false);
    }

    @Override
    public void onCreatePostClick() {
        Intent intent = new Intent(requireContext(), CreatePostActivity.class);
        startActivity(intent);
    }

    @Override
    public void onAuthorClick(Post post) {
        if (post != null && post.getAuthorUid() != null && !post.getAuthorUid().isEmpty()) {
            Intent intent = new Intent(requireContext(), ProfileActivity.class);
            intent.putExtra("target_uid", post.getAuthorUid());
            startActivity(intent);
        }
    }

    @Override
    public void onDeletePostClick(Post post) {
        if (post == null || post.getPostId() == null) return;

        new AlertDialog.Builder(requireContext())
                .setTitle("Xác nhận xóa")
                .setMessage(post.isReel()?"Đây là cùng một nội dung trên Feed và Reels. Xóa sẽ gỡ khỏi cả hai nơi.":"Bạn có chắc chắn muốn xóa bài viết này không?")
                .setPositiveButton("Xóa", (dialog, which) -> {
                    viewModel.deletePost(post.getPostId(), new FeedRepository.OnPostActionListener() {
                        @Override
                        public void onSuccess(String message) {
                            if (isAdded()) {
                                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(String error) {
                            if (isAdded()) {
                                Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    @Override public void onSavePostClick(Post post, boolean save, Runnable completed) {
        com.example.meetomxh.supabase.SavedPostClient.set(post, save, new Api.Callback<Boolean>() {
            public void success(Boolean value) {
                post.setSaved(value); completed.run();
                if (isAdded()) Toast.makeText(requireContext(), value ? "Đã lưu bài viết" : "Đã bỏ lưu", Toast.LENGTH_SHORT).show();
            }
            public void error(String error) {
                completed.run();
                if (isAdded()) Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
            }
        });
    }

    public void scrollToTop() { if (binding != null && binding.rvFeed != null) binding.rvFeed.smoothScrollToPosition(0); }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        if(binding!=null){
            binding.layoutShimmer.getRoot().stopShimmer();
            binding.rvFeed.clearOnScrollListeners();
            binding.rvFeed.setAdapter(null);
        }
        adapter=null;
        binding = null;
        super.onDestroyView();
    }
 @Override public void onResume(){super.onResume();if(SessionManagerReady()){if(!com.example.meetomxh.supabase.PostViewerUpdates.returning(requireActivity()))FeedRepository.getInstance().refresh();if(adapter!=null)adapter.refreshStories();}} private boolean SessionManagerReady(){return com.example.meetomxh.supabase.SessionManager.getInstance().getCurrentUser()!=null;}}
