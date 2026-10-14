package com.example.meetomxh.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.meetomxh.adapters.CommentAdapter;
import com.example.meetomxh.databinding.LayoutCommentsBottomSheetBinding;
import com.example.meetomxh.models.Comment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommentsBottomSheetFragment extends BottomSheetDialogFragment implements CommentAdapter.OnCommentClickListener {

    private static final String ARG_POST_ID = "arg_post_id";

    private LayoutCommentsBottomSheetBinding binding;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    private String postId;
    private String currentUid;
    private String currentUserName = "Thành viên Meeto";
    private String currentUserAvatar = "";

    private CommentAdapter adapter;
    private ListenerRegistration commentsListener;

    public static CommentsBottomSheetFragment newInstance(String postId) {
        CommentsBottomSheetFragment fragment = new CommentsBottomSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_POST_ID, postId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = LayoutCommentsBottomSheetBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();

        if (getArguments() != null) {
            postId = getArguments().getString(ARG_POST_ID);
        }

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            currentUid = currentUser.getUid();
            loadCurrentUserDetails();
        }

        adapter = new CommentAdapter(currentUid, this);
        binding.rvComments.setAdapter(adapter);

        binding.btnCloseComments.setOnClickListener(v -> dismiss());
        binding.btnSendComment.setOnClickListener(v -> submitComment());

        listenToComments();
    }

    private void loadCurrentUserDetails() {
        if (currentUid == null) return;
        db.collection("Users").document(currentUid)
                .get()
                .addOnSuccessListener(document -> {
                    if (isAdded() && document.exists()) {
                        String name = document.getString("fullName");
                        String avatar = document.getString("avatarUrl");
                        if (name != null && !name.isEmpty()) currentUserName = name;
                        if (avatar != null && !avatar.isEmpty()) currentUserAvatar = avatar;
                    }
                });
    }

    private void listenToComments() {
        if (postId == null || postId.isEmpty()) return;

        commentsListener = db.collection("Posts").document(postId)
                .collection("Comments")
                .orderBy("createdAt")
                .addSnapshotListener((snapshots, e) -> {
                    if (!isAdded()) return;
                    if (e != null) {
                        Toast.makeText(requireContext(), "Lỗi tải bình luận: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (snapshots != null) {
                        List<Comment> commentsList = new ArrayList<>();
                        snapshots.forEach(doc -> {
                            Comment c = doc.toObject(Comment.class);
                            c.setCommentId(doc.getId());
                            commentsList.add(c);
                        });

                        adapter.setComments(commentsList);

                        if (commentsList.isEmpty()) {
                            binding.tvEmptyComments.setVisibility(View.VISIBLE);
                            binding.rvComments.setVisibility(View.GONE);
                        } else {
                            binding.tvEmptyComments.setVisibility(View.GONE);
                            binding.rvComments.setVisibility(View.VISIBLE);
                            binding.rvComments.smoothScrollToPosition(commentsList.size() - 1);
                        }
                    }
                });
    }

    private void submitComment() {
        String content = binding.etCommentInput.getText().toString().trim();
        if (content.isEmpty()) {
            Toast.makeText(requireContext(), "Vui lòng nhập nội dung bình luận", Toast.LENGTH_SHORT).show();
            return;
        }

        if (currentUid == null) {
            Toast.makeText(requireContext(), "Vui lòng đăng nhập để bình luận", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnSendComment.setEnabled(false);
        binding.etCommentInput.setText("");

        String commentId = db.collection("Posts").document(postId).collection("Comments").document().getId();
        long now = System.currentTimeMillis();

        Map<String, Object> commentMap = new HashMap<>();
        commentMap.put("commentId", commentId);
        commentMap.put("postId", postId);
        commentMap.put("authorUid", currentUid);
        commentMap.put("authorName", currentUserName);
        commentMap.put("authorAvatar", currentUserAvatar);
        commentMap.put("content", content);
        commentMap.put("createdAt", now);

        db.runBatch(batch -> {
            batch.set(db.collection("Posts").document(postId).collection("Comments").document(commentId), commentMap);
            batch.update(db.collection("Posts").document(postId), "commentsCount", FieldValue.increment(1));
        }).addOnSuccessListener(aVoid -> {
            if (isAdded()) {
                binding.btnSendComment.setEnabled(true);
            }
        }).addOnFailureListener(e -> {
            if (isAdded()) {
                binding.btnSendComment.setEnabled(true);
                Toast.makeText(requireContext(), "Gửi bình luận thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDeleteCommentClick(Comment comment) {
        if (comment == null || postId == null) return;

        db.runBatch(batch -> {
            batch.delete(db.collection("Posts").document(postId).collection("Comments").document(comment.getCommentId()));
            batch.update(db.collection("Posts").document(postId), "commentsCount", FieldValue.increment(-1));
        }).addOnSuccessListener(aVoid -> {
            if (isAdded()) {
                Toast.makeText(requireContext(), "Đã xóa bình luận", Toast.LENGTH_SHORT).show();
            }
        }).addOnFailureListener(e -> {
            if (isAdded()) {
                Toast.makeText(requireContext(), "Xóa thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (commentsListener != null) {
            commentsListener.remove();
        }
        binding = null;
    }
}
