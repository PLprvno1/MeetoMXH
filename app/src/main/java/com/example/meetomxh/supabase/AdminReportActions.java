package com.example.meetomxh.supabase;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.ChatImageViewerActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.VideoPlayerActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared safe workflow for Admin and Moderator screens; preserves request identity and scoped permissions. */
public final class AdminReportActions {
    private final Activity host;
    private final AdminRepository repository;
    private final Runnable changed;
    private final Consumer<String> error;
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private EditText reasonField;
    private String draftReport, draftAction, draftReason = "";
    private JSONObject draftArgs;
    private android.media.MediaPlayer audioPlayer;
    private int detailGeneration, audioGeneration;
    private final Runnable identityChanged;

    public AdminReportActions(Activity host, AdminRepository repository, Runnable changed, Consumer<String> error, Runnable identityReset) {
        this.host = host;
        this.repository = repository;
        this.changed = changed;
        this.error = error;
        identityChanged = () -> Api.MAIN.post(() -> {
            if (host.isFinishing() || host.isDestroyed()) return;
            repository.cancelAll();
            stop();
            clearDraft();
            identityReset.run();
        });
        SessionManager.getInstance().identityListeners.add(identityChanged);
    }

    public void save(Bundle out) {
        out.putString("admin_report_draft", draftReport);
        out.putString("admin_action_draft", draftAction);
        out.putString("admin_reason_draft", reasonField == null ? draftReason : reasonField.getText().toString());
        if (draftArgs != null) out.putString("admin_report_args", draftArgs.toString());
    }

    public void restore(Bundle state) {
        if (state == null) return;
        draftReport = state.getString("admin_report_draft");
        draftAction = state.getString("admin_action_draft");
        draftReason = state.getString("admin_reason_draft", "");
        try {
            String saved = state.getString("admin_report_args");
            if (saved != null) draftArgs = new JSONObject(saved);
        } catch (org.json.JSONException ignored) {}
    }

    public void resumeDraft() {
        if (draftReport != null) open(draftReport);
    }

    public void stop() {
        detailGeneration++;
        audioGeneration++;
        releaseAudio();
        if (reasonField != null) draftReason = reasonField.getText().toString();
        reasonField = null;
        for (AlertDialog dialog : dialogs) dialog.dismiss();
        dialogs.clear();
    }

    public void destroy() {
        stop();
        SessionManager.getInstance().identityListeners.remove(identityChanged);
    }

    public void open(String id) {
        final int expected = ++detailGeneration;
        if (id.equals(draftReport) && draftArgs != null) {
            repository.run(() -> repository.receiptFor("admin_decide_report_v2", draftArgs), new Api.Callback<JSONObject>() {
                public void success(JSONObject receipt) {
                    if (expected != detailGeneration) return;
                    if (receipt != null) {
                        repository.completeIntent("admin_decide_report_v2", draftArgs);
                        clearDraft();
                        changed.run();
                    }
                    readDetail(id, detailGeneration);
                }

                public void error(String message) {
                    if (expected == detailGeneration) error.accept(message);
                }
            });
        } else {
            readDetail(id, expected);
        }
    }

    private void readDetail(String id, int expected) {
        repository.run(() -> {
            JSONObject result = RpcClient.object("admin_report_detail_v2", Api.obj("p_report_id", id));
            new AdminModels.Report(result);
            return result;
        }, new Api.Callback<JSONObject>() {
            public void success(JSONObject report) {
                if (expected != detailGeneration || host.isFinishing() || host.isDestroyed()) return;
                JSONArray actions = report.optJSONArray("permitted_actions");
                if (id.equals(draftReport) && draftAction != null && actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        if (draftAction.equals(actions.optString(i))) {
                            decide(report, draftAction);
                            return;
                        }
                    }
                }
                detail(report);
            }

            public void error(String message) {
                if (expected == detailGeneration) error.accept(message);
            }
        });
    }

    private int dp(int value) {
        return (int) (value * host.getResources().getDisplayMetrics().density);
    }

    private void detail(JSONObject report) {
        ScrollView scroll = new ScrollView(host);
        LinearLayout body = new LinearLayout(host);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(16));

        // Badges Row
        LinearLayout badgeRow = new LinearLayout(host);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvKind = new TextView(host);
        tvKind.setText(report.optString("target_kind", "report").toUpperCase(java.util.Locale.ROOT));
        tvKind.setBackgroundResource(R.drawable.bg_badge_admin);
        tvKind.setTextColor(ContextCompat.getColor(host, R.color.brand_blue));
        tvKind.setTextSize(11);
        tvKind.setTypeface(null, android.graphics.Typeface.BOLD);
        badgeRow.addView(tvKind);

        TextView tvReason = new TextView(host);
        tvReason.setText(report.optString("reason_code", ""));
        tvReason.setBackgroundResource(R.drawable.bg_badge_member);
        tvReason.setTextColor(ContextCompat.getColor(host, R.color.text_secondary));
        tvReason.setTextSize(11);
        LinearLayout.LayoutParams reasonLp = new LinearLayout.LayoutParams(-2, -2);
        reasonLp.setMarginStart(dp(6));
        tvReason.setLayoutParams(reasonLp);
        badgeRow.addView(tvReason);

        View spacer = new View(host);
        badgeRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView tvState = new TextView(host);
        String state = report.optString("state", "");
        tvState.setText(stateLabel(state));
        tvState.setBackgroundResource("resolved".equals(state) ? R.drawable.bg_badge_resolved :
                "assigned".equals(state) ? R.drawable.bg_badge_assigned :
                        "dismissed".equals(state) ? R.drawable.bg_badge_dismissed : R.drawable.bg_badge_open);
        tvState.setTextColor(ContextCompat.getColor(host, "resolved".equals(state) ? R.color.status_success :
                "assigned".equals(state) ? R.color.brand_blue :
                        "dismissed".equals(state) ? R.color.text_secondary : R.color.status_warning));
        tvState.setTextSize(11);
        tvState.setTypeface(null, android.graphics.Typeface.BOLD);
        badgeRow.addView(tvState);

        body.addView(badgeRow);

        // Details Section
        String details = report.optString("details", "");
        if (!details.isEmpty()) {
            TextView tvDetailsHeading = new TextView(host);
            tvDetailsHeading.setText("Nội dung báo cáo:");
            tvDetailsHeading.setTextColor(ContextCompat.getColor(host, R.color.text_secondary));
            tvDetailsHeading.setTextSize(12);
            tvDetailsHeading.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
            dlp.topMargin = dp(14);
            tvDetailsHeading.setLayoutParams(dlp);
            body.addView(tvDetailsHeading);

            TextView tvDetails = new TextView(host);
            tvDetails.setText(details);
            tvDetails.setTextColor(ContextCompat.getColor(host, R.color.text_primary));
            tvDetails.setTextSize(14);
            LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(-1, -2);
            dtLp.topMargin = dp(4);
            tvDetails.setLayoutParams(dtLp);
            body.addView(tvDetails);
        }

        // Target Preview Section
        JSONObject target = report.optJSONObject("target");
        if (target != null) {
            TextView tvTargetHeading = new TextView(host);
            tvTargetHeading.setText("Đối tượng bị báo cáo:");
            tvTargetHeading.setTextColor(ContextCompat.getColor(host, R.color.text_secondary));
            tvTargetHeading.setTextSize(12);
            tvTargetHeading.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(14);
            tvTargetHeading.setLayoutParams(tlp);
            body.addView(tvTargetHeading);

            String targetText = target.optString("body", target.optString("caption", target.optString("display_name", "")));
            TextView tvTarget = new TextView(host);
            tvTarget.setText(targetText.isEmpty() ? "(Không có văn bản)" : targetText);
            tvTarget.setTextColor(ContextCompat.getColor(host, R.color.text_primary));
            tvTarget.setTextSize(14);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(-1, -2);
            ttLp.topMargin = dp(4);
            tvTarget.setLayoutParams(ttLp);
            body.addView(tvTarget);
        }

        // Evidence Status Info Banner
        TextView tvEvidenceStatus = new TextView(host);
        tvEvidenceStatus.setText("Bằng chứng: " + evidenceLabel(report.optString("evidence_status")) +
                "\nTệp đính kèm: " + mediaLabel(report.optString("media_status")) +
                "\nCảnh cáo chỉ được ghi nhận; hệ thống chưa gửi thông báo.");
        tvEvidenceStatus.setTextColor(ContextCompat.getColor(host, R.color.text_secondary));
        tvEvidenceStatus.setTextSize(12);
        tvEvidenceStatus.setBackgroundResource(R.drawable.bg_pill_search);
        tvEvidenceStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams esLp = new LinearLayout.LayoutParams(-1, -2);
        esLp.topMargin = dp(14);
        tvEvidenceStatus.setLayoutParams(esLp);
        body.addView(tvEvidenceStatus);

        // Media Buttons
        JSONArray media = report.optJSONArray("media");
        if (media != null && media.length() > 0) {
            for (int i = 0; i < media.length(); i++) {
                JSONObject asset = media.optJSONObject(i);
                if (asset == null) continue;
                String mime = asset.optString("mime_type");
                MaterialButton mediaBtn = new MaterialButton(host, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
                mediaBtn.setAllCaps(false);
                mediaBtn.setCornerRadius(dp(10));
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(44));
                mlp.topMargin = dp(8);
                mediaBtn.setLayoutParams(mlp);

                if (mime.startsWith("audio/")) {
                    mediaBtn.setText("Nghe bằng chứng âm thanh");
                    mediaBtn.setIcon(ContextCompat.getDrawable(host, R.drawable.ic_play));
                    mediaBtn.setOnClickListener(v -> openAudio(asset.optString("asset_id")));
                    body.addView(mediaBtn);
                } else if (mime.startsWith("video/")) {
                    mediaBtn.setText("Xem video bằng chứng");
                    mediaBtn.setIcon(ContextCompat.getDrawable(host, R.drawable.ic_video));
                    mediaBtn.setOnClickListener(v -> host.startActivity(new Intent(host, VideoPlayerActivity.class)
                            .putExtra(VideoPlayerActivity.EXTRA_ASSET, Dto.asset(asset.optString("asset_id")))));
                    body.addView(mediaBtn);
                } else if (mime.startsWith("image/")) {
                    mediaBtn.setText("Xem ảnh bằng chứng");
                    mediaBtn.setIcon(ContextCompat.getDrawable(host, R.drawable.ic_photo));
                    mediaBtn.setOnClickListener(v -> host.startActivity(new Intent(host, ChatImageViewerActivity.class)
                            .putExtra("image_url", Dto.asset(asset.optString("asset_id")))));
                    body.addView(mediaBtn);
                }
            }
        }

        scroll.addView(body);

        AlertDialog.Builder builder = new AlertDialog.Builder(host)
                .setTitle("Chi tiết báo cáo")
                .setView(scroll)
                .setNegativeButton("Đóng", null);

        if (report.optBoolean("can_claim")) {
            builder.setNeutralButton("Nhận xử lý", (d, w) -> repository.run(
                    () -> RpcClient.object("admin_claim_report_v2", Api.obj("p_report_id", report.optString("id"))),
                    new Api.Callback<JSONObject>() {
                        public void success(JSONObject value) {
                            changed.run();
                            open(report.optString("id"));
                        }

                        public void error(String message) {
                            error.accept(message);
                        }
                    }));
        }

        JSONArray allowed = report.optJSONArray("permitted_actions");
        if (allowed != null && allowed.length() > 0) {
            builder.setPositiveButton("Quyết định xử lý", (d, w) -> {
                String[] labels = new String[allowed.length()];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = label(allowed.optString(i));
                }
                show(new AlertDialog.Builder(host)
                        .setTitle("Chọn quyết định kiểm duyệt")
                        .setItems(labels, (x, n) -> decide(report, allowed.optString(n)))
                        .setNegativeButton("Hủy", null)
                        .create());
            });
        }

        show(builder.create());
    }

    private String stateLabel(String state) {
        switch (state) {
            case "open": return "Chờ xử lý";
            case "assigned": return "Đang xử lý";
            case "resolved": return "Đã giải quyết";
            case "dismissed": return "Đã bác bỏ";
            default: return state;
        }
    }

    private String evidenceLabel(String value) {
        switch (value) {
            case "claim_required": return "Nhận xử lý để xem nội dung";
            case "target_unavailable": return "Đối tượng không còn khả dụng";
            case "content_unavailable": return "Nội dung tin nhắn không còn khả dụng";
            case "available": return "Có dữ liệu trong phạm vi báo cáo";
            default: return "Chưa xác định";
        }
    }

    private String mediaLabel(String value) {
        switch (value) {
            case "claim_required": return "Nhận xử lý để xem";
            case "unavailable": return "Không còn khả dụng";
            case "partial": return "Chỉ một phần còn khả dụng";
            case "available": return "Có thể mở theo quyền hiện tại";
            case "none": return "Không có tệp được liên kết";
            default: return "Chưa xác định";
        }
    }

    private void releaseAudio() {
        if (audioPlayer != null) {
            audioPlayer.release();
            audioPlayer = null;
        }
    }

    private void openAudio(String assetId) {
        releaseAudio();
        final int expected = ++audioGeneration;
        final long epoch = SessionManager.getInstance().generation();
        final boolean[] closed = {false};

        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle("Bằng chứng âm thanh")
                .setMessage("Đang xác minh quyền và tải âm thanh…")
                .setNegativeButton("Đóng", null)
                .setPositiveButton("Phát / tạm dừng", null)
                .create();

        dialog.setOnDismissListener(d -> {
            closed[0] = true;
            if (expected == audioGeneration) releaseAudio();
        });

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (audioPlayer == null) return;
                if (epoch != SessionManager.getInstance().generation()) {
                    dialog.dismiss();
                    return;
                }
                if (audioPlayer.isPlaying()) audioPlayer.pause();
                else audioPlayer.start();
            });
        });
        show(dialog);

        repository.run(() -> com.example.meetomxh.storage.SupabaseStorage.info(Dto.asset(assetId)), new Api.Callback<JSONObject>() {
            public void success(JSONObject asset) {
                if (expected != audioGeneration || closed[0] || host.isFinishing() || host.isDestroyed() || epoch != SessionManager.getInstance().generation()) {
                    return;
                }
                try {
                    if (!asset.optString("mime_type").startsWith("audio/")) throw new IllegalArgumentException();
                    android.media.MediaPlayer player = new android.media.MediaPlayer();
                    audioPlayer = player;
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    headers.put("apikey", SupabaseConfig.KEY);
                    headers.put("Authorization", "Bearer " + SessionManager.getInstance().access(false));
                    player.setDataSource(host, android.net.Uri.parse(com.example.meetomxh.storage.SupabaseStorage.url(asset)), headers);
                    player.setOnPreparedListener(p -> {
                        if (audioPlayer != p) return;
                        if (closed[0] || epoch != SessionManager.getInstance().generation()) {
                            releaseAudio();
                            return;
                        }
                        dialog.setMessage("Nhấn 'Phát / tạm dừng' để nghe nội dung thuộc báo cáo này.");
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                    player.setOnErrorListener((p, what, extra) -> {
                        if (audioPlayer != p || closed[0]) return true;
                        dialog.setMessage("Không phát được âm thanh. Đóng rồi thử lại.");
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        releaseAudio();
                        return true;
                    });
                    player.prepareAsync();
                } catch (Exception failure) {
                    releaseAudio();
                    dialog.setMessage("Không mở được âm thanh. Kiểm tra quyền và kết nối.");
                }
            }

            public void error(String message) {
                if (expected != audioGeneration) return;
                if (!closed[0]) dialog.setMessage(message);
                error.accept(message);
            }
        });
    }

    private String label(String action) {
        switch (action) {
            case "dismiss": return "Bác bỏ báo cáo";
            case "warn": return "Ghi nhận cảnh cáo (chưa gửi)";
            case "hide": return "Ẩn nội dung";
            case "remove": return "Gỡ nội dung, giữ bằng chứng";
            case "suspend": return "Khóa đến khi được mở lại";
            default: return action;
        }
    }

    private void decide(JSONObject report, String action) {
        if (!report.optString("id").equals(draftReport) || !action.equals(draftAction)) {
            draftReason = "";
            draftArgs = null;
        }
        draftReport = report.optString("id");
        draftAction = action;

        LinearLayout container = new LinearLayout(host);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(10), dp(20), dp(8));

        TextView tvExplanation = new TextView(host);
        tvExplanation.setText("Quyết định và lý do được lưu trữ trong audit hệ thống. Gỡ nội dung không hỗ trợ khôi phục.");
        tvExplanation.setTextColor(ContextCompat.getColor(host, R.color.text_secondary));
        tvExplanation.setTextSize(13);
        container.addView(tvExplanation);

        TextInputLayout inputLayout = new TextInputLayout(host);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setBoxCornerRadii(dp(10), dp(10), dp(10), dp(10));
        inputLayout.setHint("Lý do xử lý (1–1000 ký tự)");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        inputLayout.setLayoutParams(lp);

        TextInputEditText input = new TextInputEditText(host);
        input.setText(draftReason);
        input.setMinLines(2);
        input.setTextColor(ContextCompat.getColor(host, R.color.text_primary));
        reasonField = input;
        inputLayout.addView(input);
        container.addView(inputLayout);

        AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(label(action))
                .setView(container)
                .setNegativeButton("Hủy", (d, w) -> clearDraft())
                .setPositiveButton("Xác nhận", null)
                .create();

        dialog.setOnCancelListener(d -> clearDraft());
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String reason = input.getText().toString().trim();
            if (reason.length() < 1 || reason.length() > 1000) {
                inputLayout.setError("Nhập lý do từ 1–1000 ký tự");
                return;
            }
            draftReason = reason;
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

            JSONObject target = report.optJSONObject("target");
            Object expected = draftArgs != null && action.equals(draftArgs.optString("p_action")) ?
                    draftArgs.opt("p_expected_state") :
                    target == null ? JSONObject.NULL : target.opt("state");

            draftArgs = Api.obj("p_report_id", report.optString("id"), "p_action", action,
                    "p_reason", reason, "p_expected_state", expected);

            repository.submit("admin_decide_report_v2", Api.obj("p_report_id", report.optString("id"),
                    "p_action", action, "p_reason", reason, "p_expected_state", expected), new Api.Callback<JSONObject>() {
                public void success(JSONObject receipt) {
                    clearDraft();
                    dialog.dismiss();
                    changed.run();
                }

                public void error(String message) {
                    dialog.setCancelable(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    inputLayout.setError(message);
                    error.accept(message);
                }
            });
        }));
        show(dialog);
    }

    private void clearDraft() {
        draftReport = null;
        draftAction = null;
        draftReason = "";
        reasonField = null;
        draftArgs = null;
    }

    private void show(AlertDialog dialog) {
        dialogs.add(dialog);
        dialog.show();
    }
}
