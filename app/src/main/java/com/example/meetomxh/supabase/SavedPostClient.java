package com.example.meetomxh.supabase;

import com.example.meetomxh.models.Post;

public final class SavedPostClient {
    private SavedPostClient() { }
    public static void set(Post post, boolean saved, Api.Callback<Boolean> callback) {
        Api.run(() -> {
            org.json.JSONObject result=RpcClient.object("set_saved_post", Api.obj("p_post_id", post.getPostId(), "p_saved", saved));
            if(!post.getPostId().equals(result.optString("post_id"))||!result.has("saved"))
                throw new IllegalStateException("Máy chủ chưa xác nhận lưu bài viết.");
            return result.getBoolean("saved");
        }, new Api.Callback<Boolean>(){
            public void success(Boolean value){post.setSaved(value);PostViewerUpdates.publish(post,false);callback.success(value);}
            public void error(String error){callback.error(error);}
        });
    }
}
