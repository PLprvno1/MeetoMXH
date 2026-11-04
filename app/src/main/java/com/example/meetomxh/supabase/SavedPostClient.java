package com.example.meetomxh.supabase;

import com.example.meetomxh.models.Post;

public final class SavedPostClient {
    private SavedPostClient() { }
    public static void set(Post post, boolean saved, Api.Callback<Boolean> callback) {
        Api.run(() -> {
            RpcClient.call("set_saved_post", Api.obj("p_post_id", post.getPostId(), "p_saved", saved));
            return saved;
        }, callback);
    }
}
