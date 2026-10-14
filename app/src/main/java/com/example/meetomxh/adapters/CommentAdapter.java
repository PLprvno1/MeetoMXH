package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;
import com.example.meetomxh.databinding.ItemCommentBinding;
import com.example.meetomxh.models.Comment;

import java.util.ArrayList;
import java.util.List;

public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.CommentViewHolder> {

    public interface OnCommentClickListener {
        void onDeleteCommentClick(Comment comment);
    }

    private final List<Comment> comments = new ArrayList<>();
    private final String currentUid;
    private final OnCommentClickListener listener;

    public CommentAdapter(String currentUid, OnCommentClickListener listener) {
        this.currentUid = currentUid;
        this.listener = listener;
    }

    public void setComments(List<Comment> newComments) {
        this.comments.clear();
        if (newComments != null) {
            this.comments.addAll(newComments);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CommentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCommentBinding binding = ItemCommentBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new CommentViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CommentViewHolder holder, int position) {
        holder.bind(comments.get(position), currentUid, listener);
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    public static class CommentViewHolder extends RecyclerView.ViewHolder {
        private final ItemCommentBinding binding;

        public CommentViewHolder(@NonNull ItemCommentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Comment comment, String currentUid, OnCommentClickListener listener) {
            binding.tvCommentAuthor.setText(comment.getAuthorName() != null ? comment.getAuthorName() : "Thành viên Meeto");
            binding.tvCommentContent.setText(comment.getContent());

            if (comment.getAuthorAvatar() != null && !comment.getAuthorAvatar().isEmpty()) {
                Glide.with(binding.ivCommentAvatar.getContext())
                        .load(comment.getAuthorAvatar())
                        .placeholder(R.drawable.ic_home)
                        .error(R.drawable.ic_home)
                        .into(binding.ivCommentAvatar);
            } else {
                binding.ivCommentAvatar.setImageResource(R.drawable.ic_home);
            }

            if (currentUid != null && currentUid.equals(comment.getAuthorUid())) {
                binding.btnDeleteComment.setVisibility(View.VISIBLE);
                binding.btnDeleteComment.setOnClickListener(v -> {
                    if (listener != null) listener.onDeleteCommentClick(comment);
                });
            } else {
                binding.btnDeleteComment.setVisibility(View.GONE);
            }
        }
    }
}
