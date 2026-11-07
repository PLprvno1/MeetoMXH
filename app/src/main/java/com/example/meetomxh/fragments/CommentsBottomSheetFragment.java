package com.example.meetomxh.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.meetomxh.ReportContentActivity;
import com.example.meetomxh.adapters.CommentAdapter;
import com.example.meetomxh.databinding.LayoutCommentsBottomSheetBinding;
import com.example.meetomxh.models.Comment;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.CommentClient;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.Observer;
import com.example.meetomxh.supabase.PostUpdates;
import com.example.meetomxh.supabase.RequestIds;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.ui.MentionComposer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class CommentsBottomSheetFragment extends BottomSheetDialogFragment
        implements CommentAdapter.OnCommentClickListener {
    private static final int PAGE_SIZE = 30;
    private static final Map<String, JSONObject> DRAFTS = new java.util.concurrent.ConcurrentHashMap<>();

    private LayoutCommentsBottomSheetBinding binding;
    private CommentAdapter adapter;
    private MentionComposer mentionComposer;
    private Observer<CommentClient.TreePage> observer;
    private AlertDialog activeMenuDialog;
    private AlertDialog activeEditDialog;
    private AlertDialog activeDeleteDialog;
    private final List<Comment> roots = new ArrayList<>();
    private final Map<String, Comment> nodes = new HashMap<>();

    private String postId;
    private String draftOwner;
    private String replyTargetId;
    private String rootAfterTime;
    private String rootAfterId;
    private boolean hasMoreRoots = true;
    private boolean loadingRoots;
    private boolean sending;
    private String rootsError;

    public static CommentsBottomSheetFragment newInstance(String id) {
        CommentsBottomSheetFragment fragment = new CommentsBottomSheetFragment();
        Bundle args = new Bundle();
        args.putString("post", id);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        binding = LayoutCommentsBottomSheetBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        postId = requireArguments().getString("post");
        draftOwner = SessionManager.getInstance().uid();
        binding.etCommentInput.setFilters(new android.text.InputFilter[]{commentLengthFilter()});
        mentionComposer = new MentionComposer(binding.etCommentInput);
        JSONObject draft = DRAFTS.get(draftKey());
        if (draft != null) {
            binding.etCommentInput.setText(draft.optString("body", ""));
            mentionComposer.restore(draft.optJSONArray("mentions"));
            replyTargetId = nullable(draft, "reply_target");
        }

        adapter = new CommentAdapter(SessionManager.getInstance().uid(), this,
                getResources().getDisplayMetrics().density);
        binding.rvComments.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvComments.setAdapter(adapter);
        binding.btnCloseComments.setOnClickListener(v -> dismiss());
        binding.btnSendComment.setOnClickListener(v -> send());
        binding.btnCancelReply.setOnClickListener(v -> clearReplyMode());
        binding.tvEmptyComments.setOnClickListener(v -> {
            if (rootsError != null) refreshRoots();
            else if (roots.isEmpty()) refreshRoots();
        });
        updateReplyBanner();
        loadingRoots = true;
        updateUi();
        refreshRoots();
    }

    private void refreshRoots() {
        if (observer != null) observer.remove();
        observer = new Observer<>(() -> CommentClient.treePage(postId, null, 0, null, null, PAGE_SIZE),
                new Api.Callback<CommentClient.TreePage>() {
                    @Override public void success(CommentClient.TreePage page) {
                        if (binding == null || !isAdded()) return;
                        loadingRoots = false;
                        rootsError = null;
                        mergeRootRefresh(page);
                        updateUi();
                    }
                    @Override public void error(String message) {
                        if (binding == null || !isAdded()) return;
                        loadingRoots = false;
                        rootsError = message;
                        updateUi();
                    }
                });
    }

    private void mergeRootRefresh(CommentClient.TreePage page) {
        Map<String, Comment> prior = new HashMap<>();
        for (Comment root : roots) prior.put(root.getCommentId(), root);
        List<Comment> refreshed = new ArrayList<>();
        Set<String> refreshedIds = new HashSet<>();
        for (Comment incoming : page.items) {
            Comment merged = mergeNode(prior.get(incoming.getCommentId()), incoming);
            refreshed.add(merged);
            refreshedIds.add(merged.getCommentId());
        }

        if (!page.items.isEmpty()) {
            Comment boundary = page.items.get(page.items.size() - 1);
            for (Comment old : roots) {
                if (refreshedIds.contains(old.getCommentId())) continue;
                boolean olderThanPage = compareRoot(old, boundary) > 0;
                if (olderThanPage) refreshed.add(old);
            }
        } else if (page.hasMore) {
            refreshed.addAll(roots);
        }
        refreshed.sort(CommentsBottomSheetFragment::compareRoot);
        boolean alreadyPagedOlder = roots.size() > page.items.size();
        roots.clear();
        roots.addAll(dedupe(refreshed));
        if (!alreadyPagedOlder || hasMoreRoots) hasMoreRoots = page.hasMore;
        updateRootCursor();
        reindex();
        if (replyTargetId != null) updateReplyBanner();
    }

    private static int compareRoot(Comment left, Comment right) {
        int time = Long.compare(right.getCreatedAt(), left.getCreatedAt());
        return time != 0 ? time : right.getCommentId().compareTo(left.getCommentId());
    }

    private static List<Comment> dedupe(List<Comment> source) {
        Map<String, Comment> byId = new HashMap<>();
        for (Comment item : source) if (item != null && item.getCommentId() != null) byId.put(item.getCommentId(), item);
        List<Comment> result = new ArrayList<>(byId.values());
        result.sort(CommentsBottomSheetFragment::compareRoot);
        return result;
    }

    private void loadMoreRoots() {
        if (loadingRoots || !hasMoreRoots || rootAfterTime == null || binding == null) return;
        loadingRoots = true;
        rootsError = null;
        updateUi();
        String afterTime = rootAfterTime;
        String afterId = rootAfterId;
        Api.run(() -> CommentClient.treePage(postId, null, 0, afterTime, afterId, PAGE_SIZE),
                new Api.Callback<CommentClient.TreePage>() {
                    @Override public void success(CommentClient.TreePage page) {
                        loadingRoots = false;
                        rootsError = null;
                        Map<String, Comment> existing = new HashMap<>();
                        for (Comment root : roots) existing.put(root.getCommentId(), root);
                        for (Comment incoming : page.items) {
                            Comment merged = mergeNode(existing.get(incoming.getCommentId()), incoming);
                            existing.put(merged.getCommentId(), merged);
                        }
                        roots.clear();
                        roots.addAll(existing.values());
                        roots.sort(CommentsBottomSheetFragment::compareRoot);
                        hasMoreRoots = page.hasMore;
                        updateRootCursor();
                        reindex();
                        updateUi();
                    }
                    @Override public void error(String message) {
                        loadingRoots = false;
                        rootsError = message;
                        updateUi();
                    }
                });
    }

    private void updateRootCursor() {
        if (roots.isEmpty()) { rootAfterTime = null; rootAfterId = null; return; }
        Comment last = roots.get(roots.size() - 1);
        rootAfterTime = last.serverCreatedAt;
        rootAfterId = last.getCommentId();
    }

    private void updateUi() {
        if (binding == null || adapter == null) return;
        reindex();
        adapter.setTree(roots, hasMoreRoots, loadingRoots, rootsError);
        if (roots.isEmpty() && !loadingRoots) {
            binding.tvEmptyComments.setVisibility(View.VISIBLE);
            binding.tvEmptyComments.setText(rootsError == null
                    ? "Chưa có bình luận. Hãy mở đầu cuộc trò chuyện."
                    : "Không tải được bình luận. Chạm để thử lại.");
        } else binding.tvEmptyComments.setVisibility(View.GONE);
    }

    private void reindex() {
        nodes.clear();
        for (Comment root : roots) indexNode(root);
    }

    private void indexNode(Comment comment) {
        if (comment == null || comment.getCommentId() == null || nodes.put(comment.getCommentId(), comment) != null) return;
        indexNode(comment.previewReply);
        for (Comment child : comment.loadedReplies) indexNode(child);
    }

    private Comment findNode(String id) { return id == null ? null : nodes.get(id); }

    private Comment mergeNode(Comment existing, Comment incoming) {
        if (existing == null) return incoming;
        existing.setPostId(incoming.getPostId());
        existing.setAuthorUid(incoming.getAuthorUid());
        existing.setAuthorName(incoming.getAuthorName());
        existing.setAuthorAvatar(incoming.getAuthorAvatar());
        existing.setAuthorAvatarPreset(incoming.getAuthorAvatarPreset());
        existing.setContent(incoming.getContent());
        existing.setCreatedAt(incoming.getCreatedAt());
        existing.serverCreatedAt = incoming.serverCreatedAt;
        existing.parentId = incoming.parentId;
        existing.displayParentId = incoming.displayParentId;
        existing.rootId = incoming.rootId;
        existing.depth = incoming.depth;
        existing.displayDepth = incoming.displayDepth;
        existing.state = incoming.state;
        existing.version = incoming.version;
        existing.replyCount = incoming.replyCount;
        existing.reactionsCount = incoming.reactionsCount;
        existing.myReaction = incoming.myReaction;
        try { existing.mentions = new JSONArray(incoming.mentions.toString()); }
        catch (JSONException ignored) { existing.mentions = new JSONArray(); }
        if (incoming.previewReply == null) existing.previewReply = null;
        else {
            Comment preview = mergeNode(existing.previewReply, incoming.previewReply);
            for (int i = 0; i < existing.loadedReplies.size(); i++) {
                Comment loaded = existing.loadedReplies.get(i);
                if (preview.getCommentId().equals(loaded.getCommentId())) {
                    preview = mergeNode(loaded, incoming.previewReply);
                    existing.loadedReplies.set(i, preview);
                    break;
                }
            }
            existing.previewReply = preview;
        }
        if (existing.expanded) {
            Set<String> visible = new HashSet<>();
            for (Comment loaded : existing.loadedReplies) visible.add(loaded.getCommentId());
            if (existing.previewReply != null) visible.add(existing.previewReply.getCommentId());
            existing.hasMoreReplies = existing.replyCount > visible.size();
        } else existing.hasMoreReplies = incoming.hasMoreReplies;
        return existing;
    }

    private void loadBranch(Comment parent, boolean reset) {
        if (binding == null || parent == null || parent.loadingReplies) return;
        if (reset) { parent.afterRepliesTime = null; parent.afterRepliesId = null; }
        parent.expanded = true;
        parent.loadingReplies = true;
        parent.repliesError = null;
        updateUi();
        String afterTime = parent.afterRepliesTime;
        String afterId = parent.afterRepliesId;
        String parentId = parent.getCommentId();
        int displayDepth = parent.displayDepth;
        Api.run(() -> CommentClient.treePage(postId, parentId, displayDepth, afterTime, afterId, PAGE_SIZE),
                new Api.Callback<CommentClient.TreePage>() {
                    @Override public void success(CommentClient.TreePage page) {
                        parent.loadingReplies = false;
                        parent.repliesError = null;
                        Map<String, Comment> existing = new HashMap<>();
                        for (Comment child : parent.loadedReplies) existing.put(child.getCommentId(), child);
                        for (Comment incoming : page.items) {
                            Comment prior = existing.get(incoming.getCommentId());
                            if (prior == null && parent.previewReply != null
                                    && incoming.getCommentId().equals(parent.previewReply.getCommentId())) prior = parent.previewReply;
                            Comment merged = mergeNode(prior, incoming);
                            existing.put(incoming.getCommentId(), merged);
                            if (parent.previewReply != null && incoming.getCommentId().equals(parent.previewReply.getCommentId()))
                                parent.previewReply = merged;
                        }
                        parent.loadedReplies.clear();
                        parent.loadedReplies.addAll(existing.values());
                        parent.loadedReplies.sort(Comparator.comparingLong(Comment::getCreatedAt)
                                .thenComparing(Comment::getCommentId));
                        if (!page.items.isEmpty()) {
                            Comment last = page.items.get(page.items.size() - 1);
                            parent.afterRepliesTime = last.serverCreatedAt;
                            parent.afterRepliesId = last.getCommentId();
                        }
                        Set<String> visibleReplyIds = new HashSet<>();
                        for (Comment child : parent.loadedReplies) visibleReplyIds.add(child.getCommentId());
                        if (parent.previewReply != null) visibleReplyIds.add(parent.previewReply.getCommentId());
                        parent.hasMoreReplies = parent.replyCount > visibleReplyIds.size();
                        reindex();
                        updateUi();
                    }
                    @Override public void error(String message) {
                        parent.loadingReplies = false;
                        parent.repliesError = message;
                        updateUi();
                    }
                });
    }

    @Override public void onBranchAction(Comment comment) {
        if (comment == null) return;
        if (!comment.expanded) loadBranch(comment, true);
        else loadBranch(comment, false);
    }

    @Override public void onCollapseBranch(Comment comment) {
        if (comment == null) return;
        comment.expanded = false;
        updateUi();
    }

    @Override public void onRetryBranch(Comment comment) { loadBranch(comment, comment != null && comment.afterRepliesTime == null); }
    @Override public void onLoadMoreRoots() { loadMoreRoots(); }
    @Override public void onRetryRoots() { refreshRoots(); }

    private void send() {
        if (binding == null || sending) return;
        String body = binding.etCommentInput.getText().toString();
        if (body.trim().isEmpty()) { toast("Bình luận không được để trống."); return; }
        if (body.codePointCount(0, body.length()) > 5000) { toast("Bình luận tối đa 5.000 ký tự."); return; }
        if (replyTargetId != null) {
            Comment target = findNode(replyTargetId);
            if (target != null && !"published".equals(target.state)) { toast("Bình luận này không còn nhận trả lời."); return; }
        }
        JSONArray mentions = mentionComposer.getMentions();
        JSONObject args = Api.obj("p_post_id", postId, "p_parent_id", replyTargetId,
                "p_body", body, "p_mentions", mentions);
        String fingerprint = args.toString();
        sending = true;
        binding.btnSendComment.setEnabled(false);
        Api.run(() -> {
            args.put("p_client_request_id", RequestIds.get("comment", fingerprint));
            return RpcClient.call("create_comment_with_mentions", args);
        }, new Api.Callback<Object>() {
            @Override public void success(Object value) {
                sending = false;
                RequestIds.complete("comment", fingerprint);
                DRAFTS.remove(draftKey());
                String createdParentId = replyTargetId;
                clearReplyMode();
                if (binding != null) {
                    binding.btnSendComment.setEnabled(true);
                    binding.etCommentInput.setText("");
                }
                Comment created = parseCreated(value, mentions);
                if (created != null) {
                    Comment alreadyObserved = findNode(created.getCommentId());
                    if (alreadyObserved != null) created = alreadyObserved;
                    if (createdParentId == null) {
                        if (!containsId(roots, created.getCommentId())) roots.add(created);
                        roots.sort(CommentsBottomSheetFragment::compareRoot);
                    } else {
                        Comment parent = findNode(createdParentId);
                        if (parent != null) {
                            if (alreadyObserved == null) parent.replyCount++;
                            created.parentId = createdParentId;
                            created.displayParentId = createdParentId;
                            created.rootId = parent.rootId;
                            created.depth = parent.depth + 1;
                            created.displayDepth = parent.displayDepth + 1;
                            parent.previewReply = created;
                            parent.expanded = true;
                            parent.hasMoreReplies = parent.replyCount > 1;
                        }
                    }
                }
                reindex();
                updateUi();
                PostUpdates.reconcile(postId);
                if (binding == null || !isAdded()) return;
                if (createdParentId == null) {
                    if (observer != null) observer.refresh();
                    if (created != null) scrollToComment(created.getCommentId());
                } else {
                    Comment parent = findNode(createdParentId);
                    if (observer != null) observer.refresh();
                    if (parent != null) loadBranch(parent, true);
                    if (created != null) scrollToComment(created.getCommentId());
                }
            }
            @Override public void error(String message) {
                sending = false;
                if (binding == null || !isAdded()) return;
                binding.btnSendComment.setEnabled(true);
                toast(message);
            }
        });
    }

    private Comment parseCreated(Object value, JSONArray mentions) {
        if (!(value instanceof JSONObject)) return null;
        Comment comment = Dto.comment((JSONObject) value);
        if (comment == null) return null;
        comment.mentions = mentions;
        comment.setAuthorUid(SessionManager.getInstance().uid());
        comment.setAuthorName("Bạn");
        comment.state = "published";
        comment.replyCount = 0;
        return comment;
    }

    private void scrollToComment(String id) {
        if (binding == null || adapter == null || id == null) return;
        binding.rvComments.post(() -> {
            if (binding == null || adapter == null) return;
            int position = adapter.positionOfComment(id);
            if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION)
                binding.rvComments.smoothScrollToPosition(position);
        });
    }

    @Override public void onReplyClick(Comment comment) {
        if (binding == null || mentionComposer == null || !isAdded()
                || comment == null || !"published".equals(comment.state)) return;
        replyTargetId = comment.getCommentId();
        if (comment.getAuthorUid() != null)
            mentionComposer.prependMention(comment.getAuthorUid(), comment.getAuthorName());
        updateReplyBanner();
        binding.etCommentInput.requestFocus();
        InputMethodManager input = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) input.showSoftInput(binding.etCommentInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void clearReplyMode() {
        replyTargetId = null;
        updateReplyBanner();
    }

    private void updateReplyBanner() {
        if (binding == null) return;
        if (replyTargetId == null) {
            binding.layoutReplying.setVisibility(View.GONE);
            binding.etCommentInput.setHint("Viết bình luận…");
            return;
        }
        Comment target = findNode(replyTargetId);
        String name = target == null || target.getAuthorName() == null ? "bình luận" : target.getAuthorName();
        binding.tvReplyingTo.setText("Đang trả lời " + name);
        binding.layoutReplying.setVisibility(View.VISIBLE);
        binding.etCommentInput.setHint("Viết câu trả lời…");
    }

    @Override public void onCommentClick(Comment comment) {
        if (binding == null || comment == null || !"published".equals(comment.state) || !isAdded()) return;
        String uid = SessionManager.getInstance().uid();
        boolean own = uid != null && uid.equals(comment.getAuthorUid());
        List<String> labels = new ArrayList<>();
        labels.add("Trả lời");
        if (own) { labels.add("Sửa"); labels.add("Xóa"); }
        else labels.add("Báo cáo bình luận");
        AlertDialog menu = new AlertDialog.Builder(requireContext()).setItems(labels.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) onReplyClick(comment);
            else if (own && which == 1) showEditDialog(comment);
            else if (own) confirmDelete(comment);
            else reportComment(comment);
        }).create();
        activeMenuDialog = menu;
        menu.setOnDismissListener(d -> { if (activeMenuDialog == menu) activeMenuDialog = null; });
        menu.show();
    }

    private void reportComment(Comment comment) {
        if (!isAdded() || binding == null) return;
        Intent intent = new Intent(requireContext(), ReportContentActivity.class)
                .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE, "comment")
                .putExtra(ReportContentActivity.EXTRA_TARGET_ID, comment.getCommentId());
        startActivity(intent);
    }

    private void showEditDialog(Comment comment) {
        EditText edit = new EditText(requireContext());
        edit.setMinLines(3);
        edit.setMaxLines(8);
        edit.setText(comment.getContent());
        edit.setFilters(new android.text.InputFilter[]{commentLengthFilter()});
        MentionComposer editor = new MentionComposer(edit);
        editor.restore(comment.mentions);
        TextView errorText = new TextView(requireContext());
        errorText.setTextColor(requireContext().getColor(com.example.meetomxh.R.color.status_error));
        errorText.setTextSize(12);
        errorText.setVisibility(View.GONE);
        LinearLayout panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, 0, pad, 0);
        panel.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(errorText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle("Sửa bình luận")
                .setView(panel).setPositiveButton("Lưu", null).setNegativeButton("Hủy", null).create();
        activeEditDialog = dialog;
        dialog.setOnDismissListener(d -> {
            editor.dispose();
            if (activeEditDialog == dialog) activeEditDialog = null;
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String body = edit.getText().toString();
            if (body.trim().isEmpty()) { errorText.setText("Bình luận không được để trống."); errorText.setVisibility(View.VISIBLE); return; }
            if (body.codePointCount(0, body.length()) > 5000) { errorText.setText("Bình luận tối đa 5.000 ký tự."); errorText.setVisibility(View.VISIBLE); return; }
            android.widget.Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setEnabled(false);
            save.setText("Đang lưu…");
            JSONArray mentions = editor.getMentions();
            JSONObject args = Api.obj("p_comment_id", comment.getCommentId(), "p_body", body,
                    "p_expected_version", comment.version, "p_mentions", mentions);
            Api.run(() -> RpcClient.call("change_comment_with_mentions", args), new Api.Callback<Object>() {
                @Override public void success(Object value) {
                    if (binding == null || !isAdded()) return;
                    JSONObject result = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
                    comment.setContent(body);
                    comment.mentions = mentions;
                    comment.version = result.optLong("version", comment.version + 1);
                    comment.state = result.optString("state", "published");
                    comment.serverCreatedAt = result.optString("updated_at", comment.serverCreatedAt);
                    try { comment.mentions = new JSONArray(mentions.toString()); }
                    catch (JSONException ignored) { comment.mentions = new JSONArray(); }
                    updateUi();
                    PostUpdates.reconcile(postId);
                    dialog.dismiss();
                }
                @Override public void error(String message) {
                    if (binding == null || !isAdded()) return;
                    save.setText("Lưu");
                    boolean conflict = message != null && (message.toLowerCase(Locale.ROOT).contains("thay đổi")
                            || message.toLowerCase(Locale.ROOT).contains("version conflict"));
                    if (conflict) {
                        errorText.setText("Bình luận đã thay đổi ở nơi khác. Nội dung của bạn chưa được ghi đè; hãy hủy và mở lại để xem bản mới nhất.");
                        save.setEnabled(false);
                    } else {
                        errorText.setText(message == null ? "Không lưu được bình luận." : message);
                        save.setEnabled(true);
                    }
                    errorText.setVisibility(View.VISIBLE);
                }
            });
        }));
        dialog.show();
    }

    private void confirmDelete(Comment comment) {
        if (comment.deleting || !isAdded()) return;
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle("Xóa bình luận?")
                .setMessage("Bình luận sẽ bị ẩn. Các câu trả lời còn đọc được vẫn được giữ lại.")
                .setPositiveButton("Xóa", null).setNegativeButton("Hủy", null).create();
        activeDeleteDialog = dialog;
        dialog.setOnDismissListener(d -> { if (activeDeleteDialog == dialog) activeDeleteDialog = null; });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (comment.deleting) return;
            comment.deleting = true;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Đang xóa…");
            adapter.refreshComment(comment.getCommentId());
            Api.run(() -> RpcClient.call("delete_comment", Api.obj(
                    "p_comment_id", comment.getCommentId(), "p_expected_version", comment.version)),
                    new Api.Callback<Object>() {
                        @Override public void success(Object result) {
                            if (dialog.isShowing()) dialog.dismiss();
                            Comment displayParent = findNode(comment.displayParentId);
                            removeDeletedComment(comment);
                            PostUpdates.reconcile(postId);
                            if (observer != null) observer.refresh();
                            if (displayParent != null && binding != null) loadBranch(displayParent, true);
                        }
                        @Override public void error(String message) {
                            comment.deleting = false;
                            if (binding == null || !isAdded()) return;
                            adapter.refreshComment(comment.getCommentId());
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Thử lại");
                            toast(message);
                        }
                    });
        }));
        dialog.show();
    }

    private void removeDeletedComment(Comment deleted) {
        List<Comment> children = visibleLocalChildren(deleted);
        String displayParent = deleted.displayParentId;
        Comment parent = findNode(displayParent);
        if (parent == null) {
            roots.removeIf(c -> deleted.getCommentId().equals(c.getCommentId()));
            Set<String> rebased = new HashSet<>();
            for (Comment child : children) {
                rebasePresentation(child, null, child.getCommentId(), 0, rebased);
                if (!containsId(roots, child.getCommentId())) roots.add(child);
            }
            roots.sort(CommentsBottomSheetFragment::compareRoot);
        } else {
            parent.loadedReplies.removeIf(c -> deleted.getCommentId().equals(c.getCommentId()));
            if (parent.previewReply != null && deleted.getCommentId().equals(parent.previewReply.getCommentId())) parent.previewReply = null;
            parent.replyCount = Math.max(0, parent.replyCount - 1) + children.size();
            String displayRoot = parent.rootId == null ? parent.getCommentId() : parent.rootId;
            Set<String> rebased = new HashSet<>();
            for (Comment child : children) {
                rebasePresentation(child, parent.getCommentId(), displayRoot, parent.displayDepth + 1, rebased);
                if (!containsId(parent.loadedReplies, child.getCommentId())) parent.loadedReplies.add(child);
            }
            parent.loadedReplies.sort(Comparator.comparingLong(Comment::getCreatedAt).thenComparing(Comment::getCommentId));
        }
        reindex();
        updateUi();
    }

    private static void rebasePresentation(Comment node, String displayParentId, String rootId,
                                           int displayDepth, Set<String> visited) {
        if (node == null || node.getCommentId() == null || !visited.add(node.getCommentId())) return;
        node.displayParentId = displayParentId;
        node.rootId = rootId;
        node.displayDepth = displayDepth;
        for (Comment child : visibleLocalChildren(node)) {
            rebasePresentation(child, node.getCommentId(), rootId, displayDepth + 1, visited);
        }
    }

    private static List<Comment> visibleLocalChildren(Comment parent) {
        Map<String, Comment> result = new HashMap<>();
        for (Comment child : parent.loadedReplies) result.put(child.getCommentId(), child);
        if (parent.previewReply != null) result.put(parent.previewReply.getCommentId(), parent.previewReply);
        List<Comment> children = new ArrayList<>(result.values());
        children.sort(Comparator.comparingLong(Comment::getCreatedAt).thenComparing(Comment::getCommentId));
        return children;
    }

    private static boolean containsId(List<Comment> list, String id) {
        for (Comment item : list) if (id.equals(item.getCommentId())) return true;
        return false;
    }

    private static android.text.InputFilter commentLengthFilter() {
        return (source, start, end, dest, dstart, dend) -> {
            int current = Character.codePointCount(dest, 0, dest.length());
            int replaced = Character.codePointCount(dest, dstart, dend);
            int incoming = Character.codePointCount(source, start, end);
            int available = 5000 - (current - replaced);
            if (incoming <= available) return null;
            if (available <= 0) return "";
            int allowedEnd = Character.offsetByCodePoints(source, start, available);
            return source.subSequence(start, allowedEnd);
        };
    }

    @Override public void onDeleteCommentClick(Comment comment) { confirmDelete(comment); }

    @Override public void onCommentReaction(Comment comment, String reaction,
                                             com.example.meetomxh.adapters.FeedAdapter.ReactionCallback callback) {
        Api.run(() -> RpcClient.call("set_comment_reaction", Api.obj(
                "p_comment_id", comment.getCommentId(), "p_reaction", reaction)), new Api.Callback<Object>() {
            @Override public void success(Object result) {
                if (result instanceof JSONObject) {
                    JSONObject response = (JSONObject) result;
                    comment.myReaction = Dto.nullable(response, "reaction");
                    comment.reactionsCount = response.optInt("reactions_count", comment.reactionsCount);
                }
                callback.onResult(true, null);
            }
            @Override public void error(String message) {
                callback.onResult(false, message);
            }
        });
    }

    @Override public void onCommentChanged(Comment comment) {
        if (binding != null && adapter != null) updateUi();
    }

    private void toast(String message) {
        if (isAdded() && binding != null) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    private String draftKey() { return draftOwner + ":" + postId; }

    private static String nullable(JSONObject object, String key) {
        String value = object.optString(key, null);
        return value == null || "null".equals(value) ? null : value;
    }

    @Override public void onDestroyView() {
        if (activeMenuDialog != null && activeMenuDialog.isShowing()) activeMenuDialog.dismiss();
        if (activeEditDialog != null && activeEditDialog.isShowing()) activeEditDialog.dismiss();
        if (activeDeleteDialog != null && activeDeleteDialog.isShowing()) activeDeleteDialog.dismiss();
        activeMenuDialog = null;
        activeEditDialog = null;
        activeDeleteDialog = null;
        if (binding != null && mentionComposer != null) {
            JSONObject draft = Api.obj("body", binding.etCommentInput.getText().toString(),
                    "mentions", mentionComposer.getMentions(), "reply_target", replyTargetId);
            DRAFTS.put(draftKey(), draft);
        }
        if (observer != null) observer.remove();
        observer = null;
        loadingRoots = false;
        for (Comment comment : nodes.values()) comment.loadingReplies = false;
        if (mentionComposer != null) mentionComposer.dispose();
        mentionComposer = null;
        adapter = null;
        binding = null;
        super.onDestroyView();
    }
}
