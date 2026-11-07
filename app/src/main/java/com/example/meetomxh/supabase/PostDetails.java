package com.example.meetomxh.supabase;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.example.meetomxh.MainActivity;
import com.example.meetomxh.PostDetailsActivity;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Central authorized resolver for post and shared-post entry points. */
public final class PostDetails {
    public static final String EXTRA_POST_ID = "post_detail_id";
    public static final String EXTRA_REEL_ID = "target_reel_id";
    public static final String EXTRA_REEL_RETURN = "reel_return_to_origin";
    private PostDetails() { }

    public static void show(Activity activity, String selectedId) {
        if (activity == null || selectedId == null || selectedId.trim().isEmpty()) return;
        Api.run(() -> resolve(selectedId), new Api.Callback<JSONObject>() {
            @Override public void success(JSONObject post) {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                String id = post.optString("id", "");
                Intent intent;
                if (isVideo(post)) {
                    intent = new Intent(activity, MainActivity.class)
                            .putExtra(com.example.meetomxh.RoleResolutionActivity.EXTRA_ROLE_VERIFIED, true)
                            .putExtra(EXTRA_REEL_RETURN, true)
                            .putExtra(EXTRA_REEL_ID, id);
                } else {
                    intent = new Intent(activity, PostDetailsActivity.class).putExtra(EXTRA_POST_ID, id);
                }
                activity.startActivity(intent);
                if(isVideo(post))PostViewerUpdates.opening(activity);
            }
            @Override public void error(String error) { unavailable(activity); }
        });
    }

    public static boolean isVideo(JSONObject post) {
        if(post.optBoolean("is_reel"))return true;
        org.json.JSONArray media=post.optJSONArray("media");
        if(media!=null)for(int i=0;i<media.length();i++){
            JSONObject item=media.optJSONObject(i);
            if(item!=null && item.optString("mime_type").startsWith("video/"))return true;
        }
        return false;
    }

    public static void video(android.content.Context context,String id){
        while(context instanceof android.content.ContextWrapper && !(context instanceof Activity)){
            android.content.Context next=((android.content.ContextWrapper)context).getBaseContext();
            if(next==context)break;context=next;
        }
        if(context instanceof Activity)show((Activity)context,id);
    }

    /** Always resolves share chains through get_post, which enforces current visibility and block rules. */
    public static JSONObject resolve(String selectedId) throws Exception {
        String id = selectedId;
        Set<String> seen = new HashSet<>();
        for (int depth = 0; depth < 8; depth++) {
            if (id == null || id.trim().isEmpty() || !seen.add(id)) throw new IllegalStateException("unavailable");
            JSONObject post = RpcClient.object("get_post", Api.obj("p_post_id", id));
            if (post == null || post.length() == 0 || post.isNull("id")) throw new IllegalStateException("unavailable");
            if (!"share".equals(post.optString("kind"))) return post;
            if (!post.optBoolean("original_available", false) || post.isNull("original_post_id")) throw new IllegalStateException("unavailable");
            id = post.optString("original_post_id", null);
        }
        throw new IllegalStateException("unavailable");
    }

    public static void unavailable(Activity activity) {
        if (activity != null && !activity.isFinishing())
            Toast.makeText(activity, "Nội dung không còn khả dụng", Toast.LENGTH_LONG).show();
    }
}
