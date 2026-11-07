package com.example.meetomxh.supabase;

import com.example.meetomxh.models.Comment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Read-only client for the ACL-filtered, cursor-paged comment tree RPC. */
public final class CommentClient {
    private CommentClient() { }

    public static final class TreePage {
        public final List<Comment> items;
        public final boolean hasMore;

        private TreePage(List<Comment> items, boolean hasMore) {
            this.items = items;
            this.hasMore = hasMore;
        }
    }

    public static TreePage treePage(String postId, String parentId, int displayDepth,
                                    String afterTime, String afterId, int limit) throws Exception {
        JSONObject args = Api.obj(
                "p_post_id", postId,
                "p_parent_id", parentId,
                "p_display_depth", displayDepth,
                "p_after_time", afterTime,
                "p_after_id", afterId,
                "p_limit", limit);
        JSONObject response = RpcClient.object("list_comment_tree", args);
        JSONArray rows = response.optJSONArray("items");
        List<Comment> comments = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                Comment comment = Dto.comment(rows.optJSONObject(i));
                if (comment != null) comments.add(comment);
            }
        }
        return new TreePage(comments, response.optBoolean("has_more", false));
    }
}
