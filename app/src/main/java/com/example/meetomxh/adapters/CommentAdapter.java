package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.R;
import com.example.meetomxh.ReportContentActivity;
import com.example.meetomxh.databinding.ItemCommentBinding;
import com.example.meetomxh.models.Comment;
import com.example.meetomxh.ui.AvatarViews;
import com.example.meetomxh.ui.ReactionBarPopup;
import com.example.meetomxh.ui.MentionText;

import java.util.ArrayList;
import java.util.List;

public class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.CommentViewHolder> {

    public interface OnCommentClickListener {
        default void onCommentClick(Comment comment) {}
        void onDeleteCommentClick(Comment comment);
        default void onCommentReaction(Comment comment,String reaction,FeedAdapter.ReactionCallback cb) { cb.onResult(false,"Comment reaction unavailable"); }
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
    public void appendComments(List<Comment> more){if(more==null||more.isEmpty())return;int start=comments.size();comments.addAll(more);notifyItemRangeInserted(start,more.size());}
    public List<Comment> getComments(){return new ArrayList<>(comments);}

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
            itemView.setOnClickListener(v -> listener.onCommentClick(comment));
            itemView.setOnLongClickListener(v -> {
                if (currentUid != null && currentUid.equals(comment.getAuthorUid())) return false;
                new android.app.AlertDialog.Builder(v.getContext()).setItems(new String[]{"Báo cáo bình luận"}, (d, which) ->
                        v.getContext().startActivity(new android.content.Intent(v.getContext(), ReportContentActivity.class)
                                .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE, "comment")
                                .putExtra(ReportContentActivity.EXTRA_TARGET_ID, comment.getCommentId())))
                        .show();
                return true;
            });
            binding.tvCommentAuthor.setText(comment.getAuthorName() != null ? comment.getAuthorName() : "Thành viên Meeto");
            MentionText.bind(binding.tvCommentContent,comment.getContent(),comment.mentions);binding.tvCommentTime.setText(android.text.format.DateUtils.getRelativeTimeSpanString(comment.getCreatedAt(),System.currentTimeMillis(),android.text.format.DateUtils.MINUTE_IN_MILLIS));
            bindReaction(comment,listener);

            AvatarViews.load(binding.ivCommentAvatar, comment.getAuthorAvatar(), comment.getAuthorAvatarPreset());

            if (currentUid != null && currentUid.equals(comment.getAuthorUid())) {
                binding.btnDeleteComment.setVisibility(View.VISIBLE);
                binding.btnDeleteComment.setOnClickListener(v -> {
                    if (listener != null) listener.onDeleteCommentClick(comment);
                });
            } else {
                binding.btnDeleteComment.setVisibility(View.GONE);
            }
        }
        private void bindReaction(Comment comment,OnCommentClickListener listener){
            String[] labels={"like","love","care","haha","wow","sad","angry"};
            String selected=comment.myReaction;String label="love".equals(selected)?"Yêu":"care".equals(selected)?"Thương":"haha".equals(selected)?"Haha":"wow".equals(selected)?"Wow":"sad".equals(selected)?"Buồn":"angry".equals(selected)?"Phẫn nộ":"Thích";binding.btnCommentReaction.setText(label+(comment.reactionsCount>0?" · "+comment.reactionsCount:""));
            binding.btnCommentReaction.setTextColor(selected==null?0xff65676b:"love".equals(selected)?0xffe94057:0xff1877f2);
            binding.btnCommentReaction.setOnClickListener(v->{v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).withEndAction(()->v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();submit(comment,listener,selected==null?"like":null);});
            binding.btnCommentReaction.setOnLongClickListener(v->{new ReactionBarPopup(v.getContext()).show(binding.btnCommentReaction,type->submit(comment,listener,type));return true;});
        }
        private void submit(Comment c,OnCommentClickListener l,String type){
            if(l==null)return;String old=c.myReaction;int oldCount=c.reactionsCount;
            if(!java.util.Objects.equals(old,type))c.reactionsCount=Math.max(0,oldCount+(old==null?1:type==null?-1:0));
            c.myReaction=type;bindReaction(c,l);
            l.onCommentReaction(c,type,(success,error)->{if(!success){c.myReaction=old;c.reactionsCount=oldCount;bindReaction(c,l);if(error!=null)android.widget.Toast.makeText(binding.getRoot().getContext(),error,android.widget.Toast.LENGTH_LONG).show();}});
        }
    }
}
