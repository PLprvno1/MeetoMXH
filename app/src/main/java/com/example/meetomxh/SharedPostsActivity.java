package com.example.meetomxh;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.example.meetomxh.supabase.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lists only posts/reels actually shared in this readable conversation. */
public final class SharedPostsActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID = "chat_id";

    private String chatId;
    private View scrollSharedPosts;
    private LinearLayout rowsContainer;
    private Button btnMore;

    private View layoutLoading;
    private View layoutEmpty;
    private View layoutError;
    private TextView tvErrorDescription;
    private Button btnRetry;

    private long beforeSeq;
    private boolean loading, hasMore = true;
    private final Set<String> seen = new HashSet<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        if (chatId == null) {
            finish();
            return;
        }
        build();
        load();
    }

    private void build() {
        setContentView(R.layout.activity_shared_posts);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        scrollSharedPosts = findViewById(R.id.scrollSharedPosts);
        rowsContainer = findViewById(R.id.rowsContainer);
        btnMore = findViewById(R.id.btnMore);

        layoutLoading = findViewById(R.id.layoutLoading);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        layoutError = findViewById(R.id.layoutError);
        tvErrorDescription = findViewById(R.id.tvErrorDescription);
        btnRetry = findViewById(R.id.btnRetry);

        btnMore.setOnClickListener(v -> load());
        btnRetry.setOnClickListener(v -> load());
    }

    private void load() {
        if (loading || !hasMore) return;
        loading = true;
        btnMore.setEnabled(false);

        if (rowsContainer.getChildCount() == 0) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutEmpty.setVisibility(View.GONE);
            layoutError.setVisibility(View.GONE);
            scrollSharedPosts.setVisibility(View.GONE);
        }

        long cursor = beforeSeq;
        Api.run(() -> {
            JSONObject page = RpcClient.object("get_messages",
                    Api.obj("p_conversation_id", chatId,
                            "p_before_seq", cursor == 0 ? null : cursor,
                            "p_limit", 100));
            return page.optJSONArray("items") == null ? new JSONArray() : page.getJSONArray("items");
        }, new Api.Callback<JSONArray>() {
            public void success(JSONArray page) {
                if (isFinishing()) return;
                loading = false;
                btnMore.setEnabled(true);
                hasMore = page.length() == 100;

                layoutLoading.setVisibility(View.GONE);

                for (int i = 0; i < page.length(); i++) {
                    JSONObject message = page.optJSONObject(i);
                    if (message == null) continue;
                    beforeSeq = message.optLong("seq");
                    JSONObject post = message.optJSONObject("shared_post");
                    if (post == null || !message.optBoolean("shared_post_available") || !post.optBoolean("original_available", true)) {
                        continue;
                    }
                    String id = post.optString("id", "");
                    if (id.isEmpty() || !seen.add(id)) continue;
                    add(post, message.optString("created_at"));
                }

                btnMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);

                if (rowsContainer.getChildCount() == 0) {
                    scrollSharedPosts.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.VISIBLE);
                    layoutError.setVisibility(View.GONE);
                } else {
                    scrollSharedPosts.setVisibility(View.VISIBLE);
                    layoutEmpty.setVisibility(View.GONE);
                    layoutError.setVisibility(View.GONE);
                }
            }

            public void error(String e) {
                loading = false;
                btnMore.setEnabled(true);
                layoutLoading.setVisibility(View.GONE);

                if (rowsContainer.getChildCount() == 0) {
                    scrollSharedPosts.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.GONE);
                    layoutError.setVisibility(View.VISIBLE);
                    tvErrorDescription.setText(e);
                } else {
                    Toast.makeText(SharedPostsActivity.this, e, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void add(JSONObject post, String sharedAt) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_shared_post, rowsContainer, false);

        ImageView image = card.findViewById(R.id.ivThumbnail);
        ImageView playBadge = card.findViewById(R.id.ivPlayBadge);
        TextView author = card.findViewById(R.id.tvAuthorName);
        TextView caption = card.findViewById(R.id.tvCaption);
        TextView type = card.findViewById(R.id.tvSharedTypeAndTime);

        boolean isReel = post.optBoolean("is_reel");
        playBadge.setVisibility(isReel ? View.VISIBLE : View.GONE);

        JSONArray media = post.optJSONArray("media");
        if (media != null && media.length() > 0) {
            JSONObject first = media.optJSONObject(0);
            if (first != null) {
                Glide.with(this)
                        .load(Dto.asset(Dto.nullable(first, "asset_id")))
                        .centerCrop()
                        .placeholder(R.drawable.ic_media_error)
                        .error(R.drawable.ic_media_error)
                        .into(image);
            }
        }

        JSONObject profile = post.optJSONObject("author");
        author.setText(profile == null ? "Người dùng" : profile.optString("display_name", "Người dùng"));

        String body = post.optString("body", "");
        caption.setText(body.isEmpty() ? (isReel ? "Reel đã chia sẻ" : "Bài viết đã chia sẻ") : body);

        String time = "";
        try {
            time = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(ServerTime.parse(sharedAt).toDate());
        } catch (Exception ignored) {}

        type.setText((isReel ? "Reel" : "Bài viết") + " · Đã chia sẻ" + (time.isEmpty() ? "" : " · " + time));

        card.setOnClickListener(v -> PostDetails.show(this, post.optString("id")));

        rowsContainer.addView(card);
    }
}
