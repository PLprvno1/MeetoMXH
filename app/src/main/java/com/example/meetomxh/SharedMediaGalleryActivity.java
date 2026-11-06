package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.Subscription;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Private, cursor-paged browser for media shared in a conversation. */
public final class SharedMediaGalleryActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID = "chat_id";
    private final List<JSONObject> rows = new ArrayList<>();
    private GalleryAdapter adapter;
    private String chatId;
    private String kind = "all";
    private long beforeSeq = Long.MAX_VALUE;
    private int beforePosition = -1;
    private boolean loading;
    private boolean hasMore = true;
    private TextView status;
    private ProgressBar progress;
    private Subscription pageRequest;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        if (chatId == null || chatId.trim().isEmpty()) {
            finish();
            return;
        }
        build();
        load(true);
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(32);
        back.setContentDescription("Quay lại");
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setOnClickListener(v -> finish());
        TextView title = new TextView(this);
        title.setText("Ảnh và video đã chia sẻ");
        title.setTextSize(18);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        header.addView(title);
        root.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        addTab(tabs, "all", "Tất cả");
        addTab(tabs, "image", "Ảnh");
        addTab(tabs, "video", "Video");
        root.addView(tabs);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(dp(32), dp(32)));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(48)));

        RecyclerView list = new RecyclerView(this);
        GridLayoutManager layout = new GridLayoutManager(this, 3);
        list.setLayoutManager(layout);
        adapter = new GalleryAdapter();
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy > 0 && hasMore && !loading && layout.findLastVisibleItemPosition() >= adapter.getItemCount() - 6)
                    load(false);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status.setOnClickListener(v -> {
            if (!loading && !hasMore && rows.isEmpty()) load(true);
            else if (!loading && status.getText().toString().contains("Thử lại")) load(rows.isEmpty());
        });
        setContentView(root);
    }

    private void addTab(LinearLayout parent, String value, String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        parent.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        button.setOnClickListener(v -> {
            if (!kind.equals(value) && !loading) {
                kind = value;
                load(true);
            }
        });
    }

    private void load(boolean reset) {
        if (loading || isFinishing() || isDestroyed()) return;
        if (reset) {
            rows.clear();
            beforeSeq = Long.MAX_VALUE;
            beforePosition = -1;
            hasMore = true;
            adapter.notifyDataSetChanged();
        }
        if (!hasMore) {
            status.setText(rows.isEmpty() ? "Chưa có ảnh hoặc video được chia sẻ" : "Bạn đã xem hết nội dung");
            return;
        }

        loading = true;
        progress.setVisibility(View.VISIBLE);
        status.setText(rows.isEmpty() ? "Đang tải…" : "");
        final String requestedKind = kind;
        final boolean firstPage = beforeSeq == Long.MAX_VALUE;
        final Long cursorSeq = firstPage ? null : beforeSeq;
        final Integer cursorPosition = firstPage ? null : beforePosition;
        pageRequest = Api.run(() -> RpcClient.object("get_shared_media", Api.obj(
                        "p_conversation_id", chatId,
                        "p_kind", requestedKind,
                        "p_before_seq", cursorSeq,
                        "p_before_position", cursorPosition,
                        "p_limit", 30)),
                new Api.Callback<JSONObject>() {
                    @Override public void success(JSONObject result) {
                        if (!alive()) return;
                        loading = false;
                        pageRequest = null;
                        progress.setVisibility(View.GONE);
                        JSONArray page = result == null ? null : result.optJSONArray("items");
                        if (page == null) page = new JSONArray();
                        int added = 0;
                        JSONObject last = null;
                        for (int i = 0; i < page.length(); i++) {
                            JSONObject item = page.optJSONObject(i);
                            if (item == null || assetReference(item) == null || mediaKind(item) == null) continue;
                            rows.add(item);
                            last = item;
                            added++;
                        }
                        hasMore = page.length() == 30 && last != null;
                        if (added > 0) {
                            long seq = last.optLong("seq", 0);
                            int position = last.optInt("position", -1);
                            if (seq <= 0 || position < 0) hasMore = false;
                            else {
                                beforeSeq = seq;
                                beforePosition = position;
                            }
                        }
                        adapter.notifyDataSetChanged();
                        status.setText(rows.isEmpty() ? "Chưa có ảnh hoặc video được chia sẻ" : "");
                    }

                    @Override public void error(String error) {
                        if (!alive()) return;
                        loading = false;
                        pageRequest = null;
                        progress.setVisibility(View.GONE);
                        status.setText("Không tải được media. Chạm để thử lại.");
                    }
                });
    }

    private boolean alive() { return !isFinishing() && !isDestroyed() && status != null && adapter != null; }

    private String assetReference(JSONObject row) {
        String id = row == null ? "" : row.optString("asset_id", "");
        if (id.isEmpty() || "null".equalsIgnoreCase(id)) return null;
        try { return Dto.asset(UUID.fromString(id).toString()); }
        catch (IllegalArgumentException invalidId) { return null; }
    }

    private String mediaKind(JSONObject row) {
        String value = row.optString("kind", "");
        if ("image".equals(value) || "video".equals(value)) return value;
        String mime = row.optString("mime_type", "");
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("image/")) return "image";
        return null;
    }

    private final class GalleryAdapter extends RecyclerView.Adapter<GalleryHolder> {
        @Override public GalleryHolder onCreateViewHolder(ViewGroup parent, int type) {
            FrameLayout frame = new FrameLayout(SharedMediaGalleryActivity.this);
            ImageView image = new ImageView(SharedMediaGalleryActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = new TextView(SharedMediaGalleryActivity.this);
            badge.setText("▶");
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(22);
            badge.setGravity(Gravity.CENTER);
            frame.addView(badge, new FrameLayout.LayoutParams(-1, -1));
            frame.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(112)));
            return new GalleryHolder(frame, image, badge);
        }

        @Override public void onBindViewHolder(GalleryHolder holder, int position) {
            JSONObject row = rows.get(position);
            String reference = assetReference(row);
            String itemKind = mediaKind(row);
            holder.image.setTag(reference);
            holder.badge.setVisibility("video".equals(itemKind) ? View.VISIBLE : View.GONE);
            if ("video".equals(itemKind)) {
                holder.image.setImageResource(R.drawable.ic_memories);
                Api.run(() -> SupabaseStorage.videoThumbnail(reference), new Api.Callback<android.graphics.Bitmap>() {
                    @Override public void success(android.graphics.Bitmap bitmap) {
                        if (bitmap != null && reference.equals(holder.image.getTag()) && alive()) holder.image.setImageBitmap(bitmap);
                        else if (bitmap != null) bitmap.recycle();
                    }
                    @Override public void error(String error) { }
                });
            } else {
                Glide.with(SharedMediaGalleryActivity.this).load(reference).centerCrop()
                        .placeholder(R.drawable.ic_media_error).error(R.drawable.ic_media_error).into(holder.image);
            }
            holder.itemView.setContentDescription("video".equals(itemKind) ? "Video đã chia sẻ" : "Ảnh đã chia sẻ");
            holder.itemView.setOnClickListener(v -> openMedia(row));
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private void openMedia(JSONObject row) {
        String reference = assetReference(row);
        String selectedKind = mediaKind(row);
        if (reference == null || selectedKind == null) {
            Toast.makeText(this, "Media này không còn khả dụng.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if ("video".equals(selectedKind)) {
                startActivity(new Intent(this, VideoPlayerActivity.class).putExtra(VideoPlayerActivity.EXTRA_ASSET, reference));
                return;
            }
            ArrayList<String> images = new ArrayList<>();
            int start = 0;
            for (JSONObject item : rows) {
                if (!"image".equals(mediaKind(item))) continue;
                String imageRef = assetReference(item);
                if (imageRef == null) continue;
                if (item == row) start = images.size();
                images.add(imageRef);
            }
            if (images.isEmpty()) {
                Toast.makeText(this, "Không tìm thấy ảnh để mở.", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, ChatImageViewerActivity.class)
                    .putExtra(ChatImageViewerActivity.EXTRA_IMAGE_URLS, images.toArray(new String[0]))
                    .putExtra(ChatImageViewerActivity.EXTRA_START_INDEX, start));
        } catch (RuntimeException error) {
            Toast.makeText(this, "Không thể mở media này. Hãy thử lại.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        if (pageRequest != null) pageRequest.remove();
        if (adapter != null) adapter = null;
        super.onDestroy();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private static final class GalleryHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView badge;
        GalleryHolder(View item, ImageView image, TextView badge) {
            super(item);
            this.image = image;
            this.badge = badge;
        }
    }
}
