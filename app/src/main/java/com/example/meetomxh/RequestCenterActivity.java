package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import org.json.*;

/** Recipient-owned follow requests, message requests, and Messenger group invitations. */
public final class RequestCenterActivity extends AppCompatActivity {
    private com.example.meetomxh.ui.ActivityRequests requests;
    private String mode, afterTime, afterId;
    private boolean loading;

    private TextView tvToolbarTitle;
    private View scrollRequests;
    private LinearLayout rowsContainer;
    private Button btnNext;

    private View layoutLoading;
    private View layoutEmpty;
    private ImageView ivEmptyIcon;
    private TextView tvEmptyTitle, tvEmptySubtitle;

    private View layoutError;
    private TextView tvErrorDescription;
    private Button btnRetry;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        requests=new com.example.meetomxh.ui.ActivityRequests(this);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "follow";

        setContentView(R.layout.activity_request_center);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvToolbarTitle = findViewById(R.id.tvToolbarTitle);
        scrollRequests = findViewById(R.id.scrollRequests);
        rowsContainer = findViewById(R.id.rowsContainer);
        btnNext = findViewById(R.id.btnNext);

        layoutLoading = findViewById(R.id.layoutLoading);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);

        layoutError = findViewById(R.id.layoutError);
        tvErrorDescription = findViewById(R.id.tvErrorDescription);
        btnRetry = findViewById(R.id.btnRetry);

        setupModeTexts();

        btnNext.setOnClickListener(v -> load(false));
        btnRetry.setOnClickListener(v -> load(true));

        load(true);
    }

    private void setupModeTexts() {
        if ("message".equals(mode)) {
            tvToolbarTitle.setText("Tin nhắn chờ");
            ivEmptyIcon.setImageResource(R.drawable.ic_messenger);
            tvEmptyTitle.setText("Không có tin nhắn chờ");
            tvEmptySubtitle.setText("Tin nhắn từ những người bạn chưa kết nối sẽ xuất hiện tại đây.");
        } else if ("group".equals(mode)) {
            tvToolbarTitle.setText("Lời mời nhóm chat");
            ivEmptyIcon.setImageResource(R.drawable.ic_groups);
            tvEmptyTitle.setText("Không có lời mời nhóm chat");
            tvEmptySubtitle.setText("Lời mời tham gia các nhóm trò chuyện sẽ xuất hiện tại đây.");
        } else {
            tvToolbarTitle.setText("Yêu cầu theo dõi");
            ivEmptyIcon.setImageResource(R.drawable.ic_check_circle);
            tvEmptyTitle.setText("Không có yêu cầu theo dõi");
            tvEmptySubtitle.setText("Những người muốn theo dõi tài khoản riêng tư của bạn sẽ xuất hiện ở đây.");
        }
    }

    private String rpc() {
        return "message".equals(mode) ? "list_message_requests" :
                "group".equals(mode) ? "list_conversation_invitations" : "list_follow_requests";
    }

    private void load(boolean reset) {
        if (loading) return;
        loading = true;

        if (reset) {
            afterTime = null;
            afterId = null;
            rowsContainer.removeAllViews();
            layoutLoading.setVisibility(View.VISIBLE);
            layoutEmpty.setVisibility(View.GONE);
            layoutError.setVisibility(View.GONE);
            scrollRequests.setVisibility(View.GONE);
        }
        btnNext.setEnabled(false);

        requests.run(() -> (JSONArray) RpcClient.call(rpc(),
                        Api.obj("p_after_time", afterTime, "p_after_id", afterId, "p_limit", 30)),
                new Api.Callback<JSONArray>() {
                    public void success(JSONArray page) {
                        if (isFinishing()) return;
                        loading = false;
                        layoutLoading.setVisibility(View.GONE);

                        for (int i = 0; i < page.length(); i++) {
                            JSONObject r = page.optJSONObject(i);
                            if (r == null) continue;
                            add(r);
                            afterTime = r.optString("created_at");
                            afterId = r.optString("group".equals(mode) ? "conversation_id" : "profile_id");
                        }

                        btnNext.setEnabled(true);
                        btnNext.setVisibility(page.length() == 30 ? View.VISIBLE : View.GONE);

                        if (rowsContainer.getChildCount() == 0) {
                            scrollRequests.setVisibility(View.GONE);
                            layoutEmpty.setVisibility(View.VISIBLE);
                            layoutError.setVisibility(View.GONE);
                        } else {
                            scrollRequests.setVisibility(View.VISIBLE);
                            layoutEmpty.setVisibility(View.GONE);
                            layoutError.setVisibility(View.GONE);
                        }
                    }

                    public void error(String e) {
                        loading = false;
                        btnNext.setEnabled(true);
                        layoutLoading.setVisibility(View.GONE);

                        if (rowsContainer.getChildCount() == 0) {
                            scrollRequests.setVisibility(View.GONE);
                            layoutEmpty.setVisibility(View.GONE);
                            layoutError.setVisibility(View.VISIBLE);
                            tvErrorDescription.setText(e);
                        } else {
                            toast(e);
                        }
                    }
                });
    }

    private void add(JSONObject r) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_request_center, rowsContainer, false);

        ImageView avatar = card.findViewById(R.id.ivRequestAvatar);
        AvatarViews.load(avatar, Dto.asset(Dto.nullable(r, "avatar_asset_id")), r.optString("avatar_preset", "neutral"));

        TextView tvGroupTitle = card.findViewById(R.id.tvGroupTitle);
        if ("group".equals(mode)) {
            tvGroupTitle.setText(r.optString("title", "Nhóm chat"));
            tvGroupTitle.setVisibility(View.VISIBLE);
        } else {
            tvGroupTitle.setVisibility(View.GONE);
        }

        TextView tvName = card.findViewById(R.id.tvRequestName);
        tvName.setText(r.optString("display_name", "Người dùng"));

        TextView tvBody = card.findViewById(R.id.tvRequestBody);
        if (r.has("body") && !r.optString("body", "").trim().isEmpty()) {
            tvBody.setText(r.optString("body"));
            tvBody.setVisibility(View.VISIBLE);
        } else {
            tvBody.setVisibility(View.GONE);
        }

        Button btnAccept = card.findViewById(R.id.btnAccept);
        Button btnDecline = card.findViewById(R.id.btnDecline);

        btnAccept.setOnClickListener(v -> resolve(r, true, card));
        btnDecline.setOnClickListener(v -> resolve(r, false, card));

        Button btnMore = card.findViewById(R.id.btnMoreActions);
        if ("message".equals(mode)) {
            btnMore.setVisibility(View.VISIBLE);
            btnMore.setOnClickListener(v ->
                    new android.app.AlertDialog.Builder(this)
                            .setItems(new String[]{"Chặn người dùng", "Báo cáo tài khoản"}, (d, n) -> {
                                String id = r.optString("profile_id");
                                if (n == 1) {
                                    startActivity(new Intent(this, ReportContentActivity.class)
                                            .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE, "profile")
                                            .putExtra(ReportContentActivity.EXTRA_TARGET_ID, id));
                                } else {
                                    new android.app.AlertDialog.Builder(this)
                                            .setTitle("Chặn người dùng?")
                                            .setNegativeButton("Hủy", null)
                                            .setPositiveButton("Chặn", (x, y) ->
                                                    requests.run(() -> RpcClient.call("set_block",
                                                                    Api.obj("p_target", id, "p_blocked", true)),
                                                            new Api.Callback<Object>() {
                                                                public void success(Object o) {
                                                                    load(true);
                                                                }

                                                                public void error(String e) {
                                                                    toast(e);
                                                                }
                                                            })).show();
                                }
                            }).show());
        } else {
            btnMore.setVisibility(View.GONE);
        }

        rowsContainer.addView(card);
    }

    private void resolve(JSONObject row, boolean accept, View card) {
        if (!card.isEnabled()) return;
        card.setEnabled(false);

        String name = "message".equals(mode) ? "resolve_message_request" :
                "group".equals(mode) ? "resolve_conversation_invitation" : "resolve_follow_request";
        String key = "message".equals(mode) ? "p_sender" :
                "group".equals(mode) ? "p_conversation_id" : "p_requester";

        requests.run(() -> RpcClient.object(name,
                        Api.obj(key, row.optString("group".equals(mode) ? "conversation_id" : "profile_id"),
                                "p_accept", accept)),
                new Api.Callback<JSONObject>() {
                    public void success(JSONObject r) {
                        load(true);
                        String cid = Dto.nullable(r, "conversation_id");
                        if (accept && cid != null) {
                            startActivity(new Intent(RequestCenterActivity.this, ChatActivity.class)
                                    .putExtra(ChatActivity.EXTRA_CHAT_ID, cid));
                        }
                    }

                    public void error(String e) {
                        card.setEnabled(true);
                        toast(e);
                    }
                });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
