package com.example.meetomxh.adapters;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meetomxh.databinding.ItemCommentBinding;
import com.example.meetomxh.models.Comment;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.ui.AvatarViews;
import com.example.meetomxh.ui.CommentTreeGuideView;
import com.example.meetomxh.ui.MentionText;
import com.example.meetomxh.ui.ReactionBarPopup;

import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public class CommentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_COMMENT = 1;
    private static final int TYPE_BRANCH_ACTION = 2;
    private static final int TYPE_ROOT_ACTION = 3;

    public interface OnCommentClickListener {
        default void onCommentClick(Comment comment) { }
        default void onReplyClick(Comment comment) { }
        default void onDeleteCommentClick(Comment comment) { }
        default void onBranchAction(Comment comment) { }
        default void onCollapseBranch(Comment comment) { }
        default void onRetryBranch(Comment comment) { }
        default void onLoadMoreRoots() { }
        default void onRetryRoots() { }
        default void onCommentChanged(Comment comment) { }
        default void onCommentReaction(Comment comment, String reaction, FeedAdapter.ReactionCallback callback) {
            callback.onResult(false, "Comment reaction unavailable");
        }
    }

    private enum Action { MORE_REPLIES, COLLAPSE_REPLIES, REPLY_LOADING, REPLY_ERROR, ROOT_MORE, ROOT_LOADING, ROOT_ERROR }

    private static final class Row {
        final String key;
        final int type;
        final Comment comment;
        final Action action;
        final String label;
        final int depth;
        final List<Boolean> ancestorContinues;
        final boolean branchHasNext;
        final String snapshot;

        private Row(String key, int type, Comment comment, Action action, String label, int depth,
                    List<Boolean> ancestorContinues, boolean branchHasNext, String snapshot) {
            this.key = key;
            this.type = type;
            this.comment = comment;
            this.action = action;
            this.label = label;
            this.depth = depth;
            this.ancestorContinues = ancestorContinues;
            this.branchHasNext = branchHasNext;
            this.snapshot = snapshot;
        }

        static Row comment(Comment c, List<Boolean> ancestors, boolean hasNext) {
            String state = c.getAuthorName() + "|" + c.getContent() + "|" + c.version + "|"
                    + c.getAuthorAvatar() + "|" + c.getAuthorAvatarPreset() + "|" + c.serverCreatedAt + "|"
                    + c.mentions + "|" + c.state + "|" + c.myReaction + "|" + c.reactionsCount + "|" + c.replyCount + "|"
                    + c.expanded + "|" + c.loadingReplies + "|" + c.hasMoreReplies + "|"
                    + c.repliesError + "|" + c.displayDepth + "|" + c.deleting + "|" + c.reactionPending;
            return new Row("comment:" + c.getCommentId(), TYPE_COMMENT, c, null, null,
                    c.displayDepth, new ArrayList<>(ancestors), hasNext, state);
        }

        static Row branch(Comment c, Action action, String label) {
            return new Row("branch:" + c.getCommentId(), TYPE_BRANCH_ACTION, c, action, label,
                    c.displayDepth + 1, Collections.emptyList(), false, action + "|" + label);
        }

        static Row root(Action action, String label) {
            return new Row("root-action", TYPE_ROOT_ACTION, null, action, label, 0,
                    Collections.emptyList(), false, action + "|" + label);
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final String currentUid;
    private final OnCommentClickListener listener;
    private final float density;

    public CommentAdapter(String currentUid, OnCommentClickListener listener, float density) {
        this.currentUid = currentUid;
        this.listener = listener;
        this.density = density;
        setHasStableIds(true);
    }

    public void setTree(List<Comment> roots, boolean hasMoreRoots, boolean loadingRoots, String rootsError) {
        List<Row> next = new ArrayList<>();
        if (roots != null) {
            for (int i = 0; i < roots.size(); i++) {
                appendComment(next, roots.get(i), Collections.emptyList(), false, i < roots.size() - 1);
            }
        }
        if (loadingRoots) next.add(Row.root(Action.ROOT_LOADING, "Đang tải bình luận…"));
        else if (rootsError != null) next.add(Row.root(Action.ROOT_ERROR, "Không tải được bình luận · Chạm để thử lại"));
        else if (hasMoreRoots) next.add(Row.root(Action.ROOT_MORE, "Xem bình luận cũ hơn"));
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return rows.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return rows.get(oldPos).key.equals(next.get(newPos).key);
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                Row oldRow = rows.get(oldPos), newRow = next.get(newPos);
                return oldRow.type == newRow.type && Objects.equals(oldRow.snapshot, newRow.snapshot);
            }
        });
        rows.clear();
        rows.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    private void appendComment(List<Row> out, Comment comment, List<Boolean> ancestors,
                               boolean ignoredPrevious, boolean hasNextSibling) {
        if (comment == null || comment.getCommentId() == null) return;
        int displayDepth = Math.max(0, comment.displayDepth);
        List<Boolean> guidePath = new ArrayList<>(ancestors);
        if (guidePath.size() > Math.max(0, displayDepth - 1))
            guidePath = new ArrayList<>(guidePath.subList(0, Math.max(0, displayDepth - 1)));
        out.add(Row.comment(comment, guidePath, hasNextSibling));

        List<Comment> visibleChildren = visibleChildren(comment);
        for (int i = 0; i < visibleChildren.size(); i++) {
            Comment child = visibleChildren.get(i);
            List<Boolean> childAncestors = new ArrayList<>(guidePath);
            if (displayDepth > 0) childAncestors.add(hasNextSibling);
            boolean childHasNext = i < visibleChildren.size() - 1;
            appendComment(out, child, childAncestors, i > 0, childHasNext);
        }

        int shown = visibleChildren.size();
        if (comment.expanded) {
            if (comment.loadingReplies) out.add(Row.branch(comment, Action.REPLY_LOADING, "Đang tải câu trả lời…"));
            else if (comment.repliesError != null) out.add(Row.branch(comment, Action.REPLY_ERROR, "Tải lại câu trả lời"));
            else if (comment.hasMoreReplies) {
                int remaining = Math.max(1, comment.replyCount - shown);
                out.add(Row.branch(comment, Action.MORE_REPLIES,
                        "Xem thêm " + remaining + (remaining == 1 ? " câu trả lời" : " câu trả lời")));
            } else if (comment.replyCount > 1) out.add(Row.branch(comment, Action.COLLAPSE_REPLIES, "Ẩn bớt câu trả lời"));
        } else if (comment.replyCount > shown) {
            int remaining = comment.replyCount - shown;
            out.add(Row.branch(comment, Action.MORE_REPLIES,
                    "Xem thêm " + remaining + " câu trả lời"));
        }
    }

    private static List<Comment> visibleChildren(Comment parent) {
        List<Comment> result = new ArrayList<>();
        if (parent.expanded) result.addAll(parent.loadedReplies);
        Comment preview = parent.previewReply;
        if (preview != null) {
            boolean present = false;
            for (Comment existing : result) if (preview.getCommentId().equals(existing.getCommentId())) { present = true; break; }
            if (!present) result.add(preview);
        }
        result.removeIf(c -> c == null || c.getCommentId() == null);
        result.sort((a, b) -> {
            int time = Long.compare(a.getCreatedAt(), b.getCreatedAt());
            return time != 0 ? time : a.getCommentId().compareTo(b.getCommentId());
        });
        return result;
    }

    public List<Comment> getTreeRows() {
        List<Comment> result = new ArrayList<>();
        for (Row row : rows) if (row.type == TYPE_COMMENT) result.add(row.comment);
        return result;
    }

    public void refreshComment(String id) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.type == TYPE_COMMENT && row.comment.getCommentId().equals(id)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    public int positionOfComment(String id) {
        for (int i = 0; i < rows.size(); i++)
            if (rows.get(i).type == TYPE_COMMENT && rows.get(i).comment.getCommentId().equals(id)) return i;
        return RecyclerView.NO_POSITION;
    }

    @Override public long getItemId(int position) {
        return UUID.nameUUIDFromBytes(rows.get(position).key.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
    }

    @Override public int getItemViewType(int position) { return rows.get(position).type; }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if (type == TYPE_COMMENT) {
            return new CommentViewHolder(ItemCommentBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
        TextView action = new TextView(parent.getContext());
        action.setTextColor(parent.getContext().getColor(com.example.meetomxh.R.color.brand_blue));
        action.setTextSize(13);
        action.setGravity(android.view.Gravity.CENTER_VERTICAL);
        action.setPadding(dp(56), dp(8), dp(16), dp(8));
        action.setMinHeight(dp(40));
        return new ActionViewHolder(action);
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof CommentViewHolder) ((CommentViewHolder) holder).bind(row, currentUid, listener, density);
        else ((ActionViewHolder) holder).bind(row, listener, density);
    }

    @Override public int getItemCount() { return rows.size(); }

    private int dp(int value) { return Math.round(value * density); }

    private static final class ActionViewHolder extends RecyclerView.ViewHolder {
        private final TextView view;
        ActionViewHolder(TextView view) { super(view); this.view = view; }
        void bind(Row row, OnCommentClickListener listener, float density) {
            view.setText(row.label);
            view.setPadding(Math.round((56 + Math.min(4, row.depth) * 20) * density),
                    Math.round(8 * density), Math.round(16 * density), Math.round(8 * density));
            view.setAlpha(row.action == Action.REPLY_LOADING || row.action == Action.ROOT_LOADING ? .65f : 1f);
            view.setOnClickListener(v -> {
                if (listener == null) return;
                switch (row.action) {
                    case MORE_REPLIES: listener.onBranchAction(row.comment); break;
                    case COLLAPSE_REPLIES: listener.onCollapseBranch(row.comment); break;
                    case REPLY_ERROR: listener.onRetryBranch(row.comment); break;
                    case ROOT_MORE: listener.onLoadMoreRoots(); break;
                    case ROOT_ERROR: listener.onRetryRoots(); break;
                    default: break;
                }
            });
        }
    }

    private static final class CommentViewHolder extends RecyclerView.ViewHolder {
        private final ItemCommentBinding binding;
        private String boundId;

        CommentViewHolder(ItemCommentBinding binding) { super(binding.getRoot()); this.binding = binding; }

        void bind(Row row, String currentUid, OnCommentClickListener listener, float density) {
            Comment comment = row.comment;
            boundId = comment.getCommentId();
            int visualDepth = Math.min(4, Math.max(0, row.depth));
            CommentTreeGuideView guide = binding.commentTreeGuide;
            guide.setBranch(visualDepth, row.ancestorContinues, visualDepth > 0, row.branchHasNext);
            ViewGroup.LayoutParams guideParams = guide.getLayoutParams();
            guideParams.width = guide.desiredWidth();
            guide.setLayoutParams(guideParams);

            binding.tvCommentAuthor.setText(comment.getAuthorName() == null ? "Thành viên Meeto" : comment.getAuthorName());
            MentionText.bind(binding.tvCommentContent, comment.getContent(), comment.mentions);
            binding.tvCommentTime.setText(timestamp(comment.getCreatedAt()));
            AvatarViews.load(binding.ivCommentAvatar, comment.getAuthorAvatar(), comment.getAuthorAvatarPreset());
            bindReaction(comment, listener);
            binding.btnCommentReply.setEnabled("published".equals(comment.state) && !comment.deleting);
            binding.btnCommentReply.setOnClickListener(v -> { if (listener != null) listener.onReplyClick(comment); });
            binding.btnCommentMore.setEnabled(!comment.deleting);
            binding.btnCommentMore.setOnClickListener(v -> { if (listener != null) listener.onCommentClick(comment); });
        }

        private void bindReaction(Comment comment, OnCommentClickListener listener) {
            String selected = comment.myReaction;
            String label = "love".equals(selected) ? "Yêu" : "care".equals(selected) ? "Thương"
                    : "haha".equals(selected) ? "Haha" : "wow".equals(selected) ? "Wow"
                    : "sad".equals(selected) ? "Buồn" : "angry".equals(selected) ? "Phẫn nộ" : "Thích";
            binding.btnCommentReaction.setText(label + (comment.reactionsCount > 0 ? " · " + comment.reactionsCount : ""));
            binding.btnCommentReaction.setTextColor(selected == null ? 0xff65676b : "love".equals(selected) ? 0xffe94057 : 0xff1877f2);
            binding.btnCommentReaction.setEnabled(!comment.reactionPending && !comment.deleting && "published".equals(comment.state));
            binding.btnCommentReaction.setOnClickListener(v -> submit(comment, listener, selected == null ? "like" : null));
            binding.btnCommentReaction.setOnLongClickListener(v -> {
                if (comment.reactionPending) return true;
                new ReactionBarPopup(v.getContext()).show(binding.btnCommentReaction, type -> submit(comment, listener, type));
                return true;
            });
        }

        private void submit(Comment comment, OnCommentClickListener listener, String type) {
            if (listener == null || comment.reactionPending || !"published".equals(comment.state)) return;
            String old = comment.myReaction;
            int oldCount = comment.reactionsCount;
            if (!Objects.equals(old, type)) comment.reactionsCount = Math.max(0, oldCount + (old == null ? 1 : type == null ? -1 : 0));
            comment.myReaction = type;
            comment.reactionPending = true;
            bindReaction(comment, listener);
            listener.onCommentChanged(comment);
            String id = comment.getCommentId();
            listener.onCommentReaction(comment, type, (success, error) -> {
                comment.reactionPending = false;
                if (!success) {
                    comment.myReaction = old;
                    comment.reactionsCount = oldCount;
                    if (error != null && binding.getRoot().isAttachedToWindow())
                        android.widget.Toast.makeText(binding.getRoot().getContext(), error, android.widget.Toast.LENGTH_LONG).show();
                }
                listener.onCommentChanged(comment);
                if (id.equals(boundId)) bindReaction(comment, listener);
            });
        }

        private static String timestamp(long at) {
            long seconds = Math.max(0, (System.currentTimeMillis() - at) / 1000L);
            if (seconds < 60) return "Vừa xong";
            long minutes = seconds / 60;
            if (minutes < 60) return minutes + " phút";
            long hours = minutes / 60;
            if (hours < 24) return hours + " giờ";
            long days = hours / 24;
            if (days < 7) return days + " ngày";
            return DateFormat.getDateInstance(DateFormat.MEDIUM, new Locale("vi", "VN")).format(new Date(at));
        }
    }
}
