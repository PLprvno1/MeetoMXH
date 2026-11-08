package com.example.meetomxh;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.PostDetails;
import com.example.meetomxh.supabase.RpcClient;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TagManagementActivity extends AppCompatActivity {
    private String policy = "everyone";
    private boolean review = true;

    private TextView tvTagPolicySummary;
    private View cardTagSettings;
    private LinearLayout containerTaggedPosts;

    private View layoutLoading;
    private View layoutEmpty;
    private View cardError;
    private TextView tvErrorDescription;
    private Button btnRetry;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tag_management);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        cardTagSettings = findViewById(R.id.cardTagSettings);
        tvTagPolicySummary = findViewById(R.id.tvTagPolicySummary);
        containerTaggedPosts = findViewById(R.id.containerTaggedPosts);

        layoutLoading = findViewById(R.id.layoutLoading);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        cardError = findViewById(R.id.cardError);
        tvErrorDescription = findViewById(R.id.tvErrorDescription);
        btnRetry = findViewById(R.id.btnRetry);

        cardTagSettings.setOnClickListener(v -> editSettings());
        btnRetry.setOnClickListener(v -> load());

        loadSettings();
        load();
    }

    private void loadSettings() {
        Api.run(() -> RpcClient.object("get_tagging_settings", Api.obj()), new Api.Callback<JSONObject>() {
            public void success(JSONObject o) {
                policy = o.optString("tagging_policy", "everyone");
                review = o.optBoolean("tag_review_required", true);
                tvTagPolicySummary.setText(label(policy) + " · " + (review ? "Duyệt trước" : "Tự động chấp nhận"));
            }

            public void error(String e) {
                toast(e);
            }
        });
    }

    private void editSettings() {
        String[] labels = {"Mọi người", "Người theo dõi / Đang theo dõi", "Không ai"};
        String[] values = {"everyone", "followers", "nobody"};
        int selected = policy.equals("everyone") ? 0 : policy.equals("nobody") ? 2 : 1;

        new AlertDialog.Builder(this)
                .setTitle("Ai được gắn thẻ bạn?")
                .setSingleChoiceItems(labels, selected, (d, w) -> {
                    String next = values[w];
                    d.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Duyệt gắn thẻ")
                            .setMessage("Muốn duyệt từng thẻ trước khi hiển thị trên bài viết?")
                            .setNegativeButton("Tắt duyệt", (x, y) -> saveSettings(next, false))
                            .setPositiveButton("Bật duyệt", (x, y) -> saveSettings(next, true))
                            .show();
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void saveSettings(String next, boolean required) {
        Api.run(() -> RpcClient.object("update_tagging_settings",
                        Api.obj("p_policy", next, "p_review_required", required)),
                new Api.Callback<JSONObject>() {
                    public void success(JSONObject o) {
                        policy = next;
                        review = required;
                        Toast.makeText(TagManagementActivity.this, "Đã lưu quyền gắn thẻ", Toast.LENGTH_SHORT).show();
                        tvTagPolicySummary.setText(label(policy) + " · " + (review ? "Duyệt trước" : "Tự động chấp nhận"));
                    }

                    public void error(String e) {
                        toast(e);
                    }
                });
    }

    private void load() {
        containerTaggedPosts.removeAllViews();
        layoutLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        cardError.setVisibility(View.GONE);

        Api.run(() -> (JSONArray) RpcClient.call("list_my_post_tags", Api.obj("p_state", null, "p_limit", 30)),
                new Api.Callback<JSONArray>() {
                    public void success(JSONArray rows) {
                        if (isFinishing()) return;
                        layoutLoading.setVisibility(View.GONE);

                        if (rows.length() == 0) {
                            layoutEmpty.setVisibility(View.VISIBLE);
                            return;
                        }

                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.optJSONObject(i);
                            JSONObject post = row == null ? null : row.optJSONObject("post");
                            if (post == null) continue;
                            String postId = post.optString("id");
                            String state = row.optString("state");

                            View item = LayoutInflater.from(TagManagementActivity.this)
                                    .inflate(R.layout.item_tagged_post, containerTaggedPosts, false);

                            TextView tvTaggedBy = item.findViewById(R.id.tvTaggedBy);
                            tvTaggedBy.setText(row.optString("tagged_by_name", "Người dùng") + " đã gắn thẻ bạn");

                            TextView tvPostBody = item.findViewById(R.id.tvPostBody);
                            String body = post.optString("body", "");
                            tvPostBody.setText(body.isEmpty() ? "Bài viết có ảnh/video" : body);

                            item.findViewById(R.id.layoutPostContent).setOnClickListener(v ->
                                    PostDetails.show(TagManagementActivity.this, postId));

                            View layoutPendingActions = item.findViewById(R.id.layoutPendingActions);
                            View layoutApprovedActions = item.findViewById(R.id.layoutApprovedActions);

                            if ("pending".equals(state)) {
                                layoutPendingActions.setVisibility(View.VISIBLE);
                                layoutApprovedActions.setVisibility(View.GONE);

                                Button btnApprove = item.findViewById(R.id.btnApproveTag);
                                btnApprove.setOnClickListener(v -> resolve(postId, "approve"));

                                Button btnReject = item.findViewById(R.id.btnRejectTag);
                                btnReject.setOnClickListener(v -> resolve(postId, "reject"));
                            } else {
                                layoutPendingActions.setVisibility(View.GONE);
                                layoutApprovedActions.setVisibility(View.VISIBLE);

                                Button btnRemove = item.findViewById(R.id.btnRemoveTag);
                                btnRemove.setOnClickListener(v -> resolve(postId, "remove"));
                            }

                            containerTaggedPosts.addView(item);
                        }

                        if (containerTaggedPosts.getChildCount() == 0) {
                            layoutEmpty.setVisibility(View.VISIBLE);
                        }
                    }

                    public void error(String e) {
                        if (isFinishing()) return;
                        layoutLoading.setVisibility(View.GONE);
                        cardError.setVisibility(View.VISIBLE);
                        tvErrorDescription.setText(e);
                    }
                });
    }

    private void resolve(String post, String action) {
        Api.run(() -> RpcClient.object("review_post_tag", Api.obj("p_post_id", post, "p_action", action)),
                new Api.Callback<JSONObject>() {
                    public void success(JSONObject o) {
                        load();
                    }

                    public void error(String e) {
                        toast(e);
                    }
                });
    }

    private String label(String p) {
        return "everyone".equals(p) ? "Mọi người" : "nobody".equals(p) ? "Không ai" : "Người theo dõi / Đang theo dõi";
    }

    private void toast(String e) {
        Toast.makeText(this, e, Toast.LENGTH_LONG).show();
    }
}
