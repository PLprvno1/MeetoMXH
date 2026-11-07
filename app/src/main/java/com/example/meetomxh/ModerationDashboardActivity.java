package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.example.meetomxh.supabase.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Dedicated Moderation Center for Meeto Moderators.
 * Provides scoped Report Center, Evidence Viewer, Decision Workflow, and Audit Logs.
 */
public class ModerationDashboardActivity extends AppCompatActivity {
    private static final String[] MOD_TABS = {"Báo cáo", "Nhật ký kiểm duyệt", "Tài khoản"};

    private final AdminRepository repository = new AdminRepository();
    private AdminReportActions reports;

    private String currentTab = "Báo cáo";
    private String reportFilter = "open";
    private boolean mineOnly = false;
    private String afterTime, afterId;
    private boolean loading, stopped, restoreDraft = true;

    // Views
    private TextView tvModRoleBadge;
    private LinearLayout modTabs;
    private View modScrollFilterChips;
    private LinearLayout modFilterChips;
    private SwipeRefreshLayout modRefresh;
    private LinearLayout modContent;
    private MaterialButton modLoadMore;

    private View modLayoutLoading;
    private View modLayoutEmpty;
    private View modLayoutError;
    private TextView tvModErrorMessage;
    private MaterialButton btnModRetry;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        reports = new AdminReportActions(this, repository, () -> load(false), this::showError, () -> {
            modContent.removeAllViews();
            startActivity(new Intent(this, RoleResolutionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
        reports.restore(state);

        if (state != null) {
            currentTab = state.getString("current_tab", "Báo cáo");
            reportFilter = state.getString("report_filter", "open");
            mineOnly = state.getBoolean("mine_only", false);
        }

        setContentView(R.layout.activity_moderation_dashboard);
        initViews();
        buildTabs();
        buildFilterChips();
        load(false);
    }

    private void initViews() {
        tvModRoleBadge = findViewById(R.id.tvModRoleBadge);
        modTabs = findViewById(R.id.modTabs);
        modScrollFilterChips = findViewById(R.id.modScrollFilterChips);
        modFilterChips = findViewById(R.id.modFilterChips);
        modRefresh = findViewById(R.id.modRefresh);
        modContent = findViewById(R.id.modContent);
        modLoadMore = findViewById(R.id.modLoadMore);

        modLayoutLoading = findViewById(R.id.modLayoutLoading);
        modLayoutEmpty = findViewById(R.id.modLayoutEmpty);
        modLayoutError = findViewById(R.id.modLayoutError);
        tvModErrorMessage = findViewById(R.id.tvModErrorMessage);
        btnModRetry = findViewById(R.id.btnModRetry);

        findViewById(R.id.btnModBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnModRefresh).setOnClickListener(v -> load(false));
        btnModRetry.setOnClickListener(v -> load(false));
        modLoadMore.setOnClickListener(v -> load(true));
        modRefresh.setOnRefreshListener(() -> load(false));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void buildTabs() {
        modTabs.removeAllViews();
        for (String tabName : MOD_TABS) {
            MaterialButton tabBtn = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            tabBtn.setText(tabName);
            tabBtn.setAllCaps(false);
            tabBtn.setTextSize(13);
            tabBtn.setCornerRadius(dp(18));
            tabBtn.setMinHeight(dp(36));

            boolean isSelected = tabName.equals(currentTab);
            if (isSelected) {
                tabBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_blue));
                tabBtn.setTextColor(ContextCompat.getColor(this, R.color.white));
                tabBtn.setStrokeWidth(0);
            } else {
                tabBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant));
                tabBtn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                tabBtn.setStrokeColorResource(R.color.divider_color);
                tabBtn.setStrokeWidth(dp(1));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(dp(4), 0, dp(4), 0);
            tabBtn.setLayoutParams(lp);

            tabBtn.setOnClickListener(v -> {
                if (tabName.equals(currentTab)) return;
                currentTab = tabName;
                buildTabs();
                modScrollFilterChips.setVisibility("Báo cáo".equals(currentTab) ? View.VISIBLE : View.GONE);
                load(false);
            });
            modTabs.addView(tabBtn);
        }
    }

    private void buildFilterChips() {
        modFilterChips.removeAllViews();
        String[] states = {"open", "assigned", "resolved", "dismissed"};
        String[] labels = {"Chờ xử lý", "Đang xử lý", "Đã giải quyết", "Đã bác bỏ"};

        for (int i = 0; i < states.length; i++) {
            final String stateKey = states[i];
            addFilterChip(labels[i], stateKey.equals(reportFilter) && !mineOnly, () -> {
                reportFilter = stateKey;
                mineOnly = false;
                buildFilterChips();
                load(false);
            });
        }

        addFilterChip("Của tôi", mineOnly, () -> {
            mineOnly = !mineOnly;
            buildFilterChips();
            load(false);
        });
    }

    private void addFilterChip(String text, boolean selected, Runnable onClick) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12);
        chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(this, selected ? R.color.white : R.color.text_secondary));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);

        chip.setOnClickListener(v -> onClick.run());
        modFilterChips.addView(chip);
    }

    private void load(boolean append) {
        if (append && loading) return;
        repository.cancelAll();
        loading = true;
        modRefresh.setRefreshing(!append);
        modLoadMore.setEnabled(false);

        if (!append) {
            afterTime = null;
            afterId = null;
            modContent.removeAllViews();
            showLoadingState();
        }

        if ("Tài khoản".equals(currentTab)) {
            loading = false;
            modRefresh.setRefreshing(false);
            hideStates();
            renderAccountTab();
            return;
        }

        final boolean isAudit = "Nhật ký kiểm duyệt".equals(currentTab);
        final String requestedFilter = reportFilter;
        final boolean requestedMine = mineOnly;
        final String requestedAfterTime = afterTime;
        final String requestedAfterId = afterId;

        repository.run(() -> {
            JSONObject value = RpcClient.object(isAudit ? "admin_audit_page_v2" : "admin_reports_page_v2", isAudit ?
                    Api.obj("p_after_time", nullable(requestedAfterTime), "p_after_id", nullable(requestedAfterId), "p_limit", 30) :
                    Api.obj("p_state", requestedFilter, "p_kind", JSONObject.NULL, "p_query", "", "p_mine", requestedMine,
                            "p_after_time", nullable(requestedAfterTime), "p_after_id", nullable(requestedAfterId), "p_limit", 30));
            return new AdminModels.Page(value);
        }, new Api.Callback<AdminModels.Page>() {
            public void success(AdminModels.Page page) {
                loading = false;
                modRefresh.setRefreshing(false);
                modLoadMore.setEnabled(true);
                hideStates();

                for (int i = 0; i < page.items.length(); i++) {
                    JSONObject row = page.items.optJSONObject(i);
                    if (row == null) continue;
                    if (isAudit) {
                        renderAuditCard(row);
                    } else {
                        renderReportCard(row);
                    }
                }

                if (page.nextCursor != null) {
                    afterTime = page.nextCursor.optString("created_at", null);
                    afterId = page.nextCursor.optString("id", null);
                }

                modLoadMore.setVisibility(page.hasMore ? View.VISIBLE : View.GONE);

                if (modContent.getChildCount() == 0 && !append) {
                    showEmptyState();
                }

                if (restoreDraft) {
                    restoreDraft = false;
                    reports.resumeDraft();
                }
            }

            public void error(String error) {
                loading = false;
                modRefresh.setRefreshing(false);
                modLoadMore.setEnabled(true);
                if (!append) {
                    showErrorState(error);
                } else {
                    toast(error);
                }
            }
        });
    }

    private void renderAccountTab() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_bg));
        card.setRadius(dp(14));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.divider_color));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        card.setLayoutParams(lp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView tvHeading = new TextView(this);
        tvHeading.setText("Tài khoản kiểm duyệt");
        tvHeading.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tvHeading.setTextSize(17);
        tvHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(tvHeading);

        SessionUser user = SessionManager.getInstance().getCurrentUser();
        TextView tvEmail = new TextView(this);
        tvEmail.setText(user != null ? user.getEmail() : "Phiên làm việc ẩn danh");
        tvEmail.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvEmail.setTextSize(14);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = dp(4);
        tvEmail.setLayoutParams(elp);
        box.addView(tvEmail);

        TextView tvRole = new TextView(this);
        tvRole.setText("Vai trò: Kiểm duyệt viên (Moderator)");
        tvRole.setTextColor(ContextCompat.getColor(this, R.color.brand_blue));
        tvRole.setTextSize(13);
        tvRole.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(6);
        tvRole.setLayoutParams(rlp);
        box.addView(tvRole);

        MaterialButton btnLogout = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnLogout.setText("Đăng xuất");
        btnLogout.setTextColor(ContextCompat.getColor(this, R.color.status_error));
        btnLogout.setStrokeColorResource(R.color.status_error);
        btnLogout.setCornerRadius(dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(44));
        blp.topMargin = dp(16);
        btnLogout.setLayoutParams(blp);
        btnLogout.setOnClickListener(v -> confirmLogout());
        box.addView(btnLogout);

        card.addView(box);
        modContent.addView(card);
    }

    private void renderReportCard(JSONObject row) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_admin_report, modContent, false);

        TextView tvKind = card.findViewById(R.id.tvReportTargetKind);
        tvKind.setText(row.optString("target_kind", "report").toUpperCase(java.util.Locale.ROOT));

        TextView tvReason = card.findViewById(R.id.tvReportReason);
        tvReason.setText(row.optString("reason_code", "Lý do khác"));

        TextView tvState = card.findViewById(R.id.tvReportState);
        String state = row.optString("state", "open");
        tvState.setText(stateLabel(state));
        tvState.setBackgroundResource("resolved".equals(state) ? R.drawable.bg_badge_resolved :
                "assigned".equals(state) ? R.drawable.bg_badge_assigned :
                        "dismissed".equals(state) ? R.drawable.bg_badge_dismissed : R.drawable.bg_badge_open);
        tvState.setTextColor(ContextCompat.getColor(this, "resolved".equals(state) ? R.color.status_success :
                "assigned".equals(state) ? R.color.brand_blue :
                        "dismissed".equals(state) ? R.color.text_secondary : R.color.status_warning));

        TextView tvPreview = card.findViewById(R.id.tvReportPreview);
        String preview = row.optString("details", row.optString("preview", "(Không có bản xem trước)"));
        tvPreview.setText(preview);

        TextView tvTime = card.findViewById(R.id.tvReportTime);
        tvTime.setText(row.optString("created_at", ""));

        TextView tvSnippet = card.findViewById(R.id.tvReportIdSnippet);
        String id = row.optString("id", "");
        tvSnippet.setText(id.length() > 8 ? "#" + id.substring(0, 8) : "#" + id);

        card.setOnClickListener(v -> reports.open(id));
        modContent.addView(card);
    }

    private void renderAuditCard(JSONObject row) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_admin_audit, modContent, false);

        TextView tvOp = card.findViewById(R.id.tvAuditOperation);
        tvOp.setText(row.optString("operation", "").toUpperCase(java.util.Locale.ROOT));

        TextView tvSubject = card.findViewById(R.id.tvAuditSubject);
        tvSubject.setText(row.optString("subject_type", ""));

        TextView tvTime = card.findViewById(R.id.tvAuditTime);
        tvTime.setText(row.optString("created_at", ""));

        TextView tvOldState = card.findViewById(R.id.tvAuditOldState);
        TextView tvNewState = card.findViewById(R.id.tvAuditNewState);
        String oldState = row.optString("old_state", "");
        String newState = row.optString("new_state", "");
        if (!oldState.isEmpty() || !newState.isEmpty()) {
            tvOldState.setText(oldState.isEmpty() ? "none" : oldState);
            tvNewState.setText(newState.isEmpty() ? "none" : newState);
        } else {
            card.findViewById(R.id.layoutAuditTransition).setVisibility(View.GONE);
        }

        TextView tvReason = card.findViewById(R.id.tvAuditReason);
        String reason = row.optString("reason", "");
        tvReason.setText(reason.isEmpty() ? "(Không ghi lý do)" : "Lý do: " + reason);

        TextView tvActor = card.findViewById(R.id.tvAuditActor);
        String actor = row.optString("actor_id", "");
        tvActor.setText("Actor: " + (actor.length() > 8 ? actor.substring(0, 8) + "…" : actor));

        TextView tvReportRef = card.findViewById(R.id.tvAuditReportRef);
        String reportId = row.optString("report_id", "");
        if (!reportId.isEmpty()) {
            tvReportRef.setText("Report: #" + (reportId.length() > 8 ? reportId.substring(0, 8) : reportId));
            tvReportRef.setVisibility(View.VISIBLE);
        } else {
            tvReportRef.setVisibility(View.GONE);
        }

        modContent.addView(card);
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

    private void showLoadingState() {
        modLayoutLoading.setVisibility(View.VISIBLE);
        modLayoutEmpty.setVisibility(View.GONE);
        modLayoutError.setVisibility(View.GONE);
    }

    private void showEmptyState() {
        modLayoutEmpty.setVisibility(View.VISIBLE);
        modLayoutLoading.setVisibility(View.GONE);
        modLayoutError.setVisibility(View.GONE);
    }

    private void showErrorState(String error) {
        modLayoutError.setVisibility(View.VISIBLE);
        tvModErrorMessage.setText(error);
        modLayoutLoading.setVisibility(View.GONE);
        modLayoutEmpty.setVisibility(View.GONE);
    }

    private void hideStates() {
        modLayoutLoading.setVisibility(View.GONE);
        modLayoutEmpty.setVisibility(View.GONE);
        modLayoutError.setVisibility(View.GONE);
    }

    private Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }

    private void showError(String message) {
        if (repository.lastError != null && repository.lastError.kind == AdminRepository.ErrorKind.SESSION) {
            SessionManager.getInstance().signOut();
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        if (repository.lastError != null && repository.lastError.kind == AdminRepository.ErrorKind.PERMISSION) {
            repository.cancelAll();
            reports.stop();
            modContent.removeAllViews();
        }
        showErrorState(message);
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Đăng xuất kiểm duyệt")
                .setMessage("Bạn có chắc muốn đăng xuất khỏi tài khoản kiểm duyệt?")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Đăng xuất", (d, w) -> {
                    SessionManager.getInstance().signOut();
                    startActivity(new Intent(this, LoginActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                }).show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        reports.save(out);
        out.putString("current_tab", currentTab);
        out.putString("report_filter", reportFilter);
        out.putBoolean("mine_only", mineOnly);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onStop() {
        reports.stop();
        repository.cancelAll();
        loading = false;
        stopped = true;
        restoreDraft = true;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stopped) {
            stopped = false;
            load(false);
        }
    }

    @Override
    protected void onDestroy() {
        repository.cancelAll();
        reports.destroy();
        super.onDestroy();
    }
}
