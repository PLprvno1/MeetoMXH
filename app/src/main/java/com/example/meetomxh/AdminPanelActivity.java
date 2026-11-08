package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Premium Admin Center for Meeto.
 * Provides unified Dashboard, Report Center, User & Content Management, and Audit History.
 * Every privileged operation is verified independently by server RPC.
 */
public final class AdminPanelActivity extends AppCompatActivity {
    private static final String[] PRIMARY_TABS = {"Tổng quan", "Báo cáo", "Quản lý", "Nhật ký & Thêm"};
    private static final String[] MANAGEMENT_SECTIONS = {"Người dùng", "Yêu cầu tài khoản", "Bài viết", "Reels", "Bình luận"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AdminRepository repository = new AdminRepository();

    private AdminReportActions reportActions;
    private AdminModels.Capabilities currentCapabilities;

    private String currentPrimaryTab = "Tổng quan";
    private String currentManageSection = "Người dùng";
    private String reportStateFilter = "open";
    private boolean reportMineOnly = false;
    private String userStateFilter = "";
    private String query = "";

    private String afterTime, afterId;
    private boolean loading, accessVerified, resumedOnce;

    private JSONObject subjectDraft;
    private EditText subjectReason;
    private AlertDialog subjectDialog;
    private int subjectGeneration;

    // View References
    private TextView tvAdminRoleBadge;
    private LinearLayout adminTabs;
    private View layoutSubHeader, scrollFilterChips, layoutSearchBar;
    private LinearLayout layoutFilterChips;
    private EditText adminSearch;
    private ImageView btnAdminClearSearch;

    private SwipeRefreshLayout adminRefresh;
    private LinearLayout adminContent;
    private MaterialButton adminLoadMore;

    private View layoutLoading;
    private TextView tvLoadingMessage;
    private View layoutEmpty;
    private ImageView ivEmptyIcon;
    private TextView tvEmptyTitle, tvEmptySubtitle;
    private View layoutError;
    private TextView tvErrorMessage;
    private MaterialButton btnRetry;

    private final Runnable searchRunnable = () -> {
        String latest = adminSearch.getText().toString().trim();
        if (latest.equals(query)) return;
        query = latest;
        resetPage();
        load(false);
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            currentPrimaryTab = state.getString("primary_tab", currentPrimaryTab);
            currentManageSection = state.getString("manage_section", currentManageSection);
            reportStateFilter = state.getString("report_state", reportStateFilter);
            reportMineOnly = state.getBoolean("report_mine", reportMineOnly);
            userStateFilter = state.getString("user_state", userStateFilter);
            query = state.getString("query", query);
        }

        reportActions = new AdminReportActions(this, repository, () -> {
            resetPage();
            load(false);
        }, e -> {
            if (!handlePermissionError(e)) toast(e);
        }, () -> {
            subjectDraft = null;
            subjectReason = null;
            if (subjectDialog != null) subjectDialog.dismiss();
            adminContent.removeAllViews();
            startActivity(new Intent(this, RoleResolutionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        });
        reportActions.restore(state);

        if (state != null) {
            try {
                String saved = state.getString("subject_draft");
                if (saved != null) subjectDraft = new JSONObject(saved);
            } catch (org.json.JSONException ignored) {}
        }

        setContentView(R.layout.activity_admin_panel);
        initViews();
        buildPrimaryTabs();
        verifyAccess();
    }

    private void initViews() {
        tvAdminRoleBadge = findViewById(R.id.tvAdminRoleBadge);
        adminTabs = findViewById(R.id.adminTabs);

        layoutSubHeader = findViewById(R.id.layoutSubHeader);
        layoutSearchBar = findViewById(R.id.layoutSearchBar);
        scrollFilterChips = findViewById(R.id.scrollFilterChips);
        layoutFilterChips = findViewById(R.id.layoutFilterChips);
        adminSearch = findViewById(R.id.adminSearch);
        btnAdminClearSearch = findViewById(R.id.btnAdminClearSearch);

        adminRefresh = findViewById(R.id.adminRefresh);
        adminContent = findViewById(R.id.adminContent);
        adminLoadMore = findViewById(R.id.adminLoadMore);

        layoutLoading = findViewById(R.id.layoutLoading);
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);
        layoutError = findViewById(R.id.layoutError);
        tvErrorMessage = findViewById(R.id.tvErrorMessage);
        btnRetry = findViewById(R.id.btnRetry);

        findViewById(R.id.btnAdminRefresh).setOnClickListener(v -> {
            resetPage();
            load(false);
        });
        findViewById(R.id.btnAdminLogout).setOnClickListener(v -> confirmLogout());

        btnRetry.setOnClickListener(v -> load(false));
        adminLoadMore.setOnClickListener(v -> load(true));
        adminRefresh.setOnRefreshListener(() -> {
            resetPage();
            load(false);
        });

        adminSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                handler.removeCallbacks(searchRunnable);
                String text = s.toString().trim();
                btnAdminClearSearch.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
                if (!text.equals(query)) handler.postDelayed(searchRunnable, 350);
            }
            public void afterTextChanged(Editable e) {}
        });

        btnAdminClearSearch.setOnClickListener(v -> {
            adminSearch.setText("");
            query = "";
            resetPage();
            load(false);
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void buildPrimaryTabs() {
        adminTabs.removeAllViews();
        for (String tabName : PRIMARY_TABS) {
            MaterialButton tabBtn = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            tabBtn.setText(tabName);
            tabBtn.setAllCaps(false);
            tabBtn.setTextSize(13);
            tabBtn.setCornerRadius(dp(18));
            tabBtn.setMinHeight(dp(36));

            boolean isSelected = tabName.equals(currentPrimaryTab);
            styleTabButton(tabBtn, isSelected);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(dp(4), 0, dp(4), 0);
            tabBtn.setLayoutParams(lp);

            tabBtn.setOnClickListener(v -> selectPrimaryTab(tabName));
            adminTabs.addView(tabBtn);
        }
    }

    private void styleTabButton(MaterialButton btn, boolean selected) {
        if (selected) {
            btn.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_blue));
            btn.setTextColor(ContextCompat.getColor(this, R.color.white));
            btn.setStrokeWidth(0);
        } else {
            btn.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant));
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            btn.setStrokeColorResource(R.color.divider_color);
            btn.setStrokeWidth(dp(1));
        }
    }

    private void selectPrimaryTab(String tabName) {
        if (tabName.equals(currentPrimaryTab)) return;
        currentPrimaryTab = tabName;
        buildPrimaryTabs();
        query = "";
        adminSearch.setText("");
        resetPage();
        updateSubHeaderVisibility();
        load(false);
    }

    private void updateSubHeaderVisibility() {
        if ("Tổng quan".equals(currentPrimaryTab)) {
            layoutSubHeader.setVisibility(View.GONE);
        } else if ("Báo cáo".equals(currentPrimaryTab)) {
            layoutSubHeader.setVisibility(View.VISIBLE);
            layoutSearchBar.setVisibility(View.VISIBLE);
            scrollFilterChips.setVisibility(View.VISIBLE);
            buildReportFilterChips();
        } else if ("Quản lý".equals(currentPrimaryTab)) {
            layoutSubHeader.setVisibility(View.VISIBLE);
            layoutSearchBar.setVisibility(View.VISIBLE);
            scrollFilterChips.setVisibility(View.VISIBLE);
            buildManagementChips();
        } else { // "Nhật ký & Thêm"
            layoutSubHeader.setVisibility(View.GONE);
        }
    }

    private void buildReportFilterChips() {
        layoutFilterChips.removeAllViews();
        String[] states = {"open", "assigned", "resolved", "dismissed"};
        String[] labels = {"Chờ xử lý", "Đang xử lý", "Đã giải quyết", "Đã bác bỏ"};

        for (int i = 0; i < states.length; i++) {
            final String stateKey = states[i];
            addFilterChip(labels[i], stateKey.equals(reportStateFilter) && !reportMineOnly, () -> {
                reportStateFilter = stateKey;
                reportMineOnly = false;
                buildReportFilterChips();
                resetPage();
                load(false);
            });
        }

        addFilterChip("Chỉ của tôi", reportMineOnly, () -> {
            reportMineOnly = !reportMineOnly;
            buildReportFilterChips();
            resetPage();
            load(false);
        });
    }

    private void buildManagementChips() {
        layoutFilterChips.removeAllViews();
        for (String section : MANAGEMENT_SECTIONS) {
            addFilterChip(section, section.equals(currentManageSection), () -> {
                currentManageSection = section;
                buildManagementChips();
                resetPage();
                load(false);
            });
        }
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
        layoutFilterChips.addView(chip);
    }

    private void verifyAccess() {
        showLoadingState("Đang xác minh quyền quản trị…");
        repository.run(() -> {
            JSONObject caps = RpcClient.object("admin_get_capabilities", Api.obj());
            return new AdminModels.Capabilities(caps);
        }, new Api.Callback<AdminModels.Capabilities>() {
            public void success(AdminModels.Capabilities caps) {
                if (isFinishing() || isDestroyed()) return;
                currentCapabilities = caps;
                accessVerified = true;

                if (caps.systemAdmin) {
                    tvAdminRoleBadge.setText("Quản trị viên hệ thống");
                } else if (caps.moderator) {
                    tvAdminRoleBadge.setText("Kiểm duyệt viên");
                } else {
                    tvAdminRoleBadge.setText("Quyền quản trị hợp lệ");
                }

                updateSubHeaderVisibility();
                load(false);
            }

            public void error(String error) {
                if (isFinishing() || isDestroyed()) return;
                accessVerified = false;
                tvAdminRoleBadge.setText("Chưa xác minh quyền");
                showErrorState("Không thể xác minh quyền quản trị:\n" + error);
            }
        });
    }

    private void resetPage() {
        subjectGeneration++;
        repository.cancelAll();
        reportActions.stop();
        saveSubjectReason();
        if (subjectDialog != null) subjectDialog.dismiss();
        subjectDialog = null;
        subjectReason = null;
        loading = false;
        afterTime = null;
        afterId = null;
        adminLoadMore.setVisibility(View.GONE);
    }

    private void load(boolean append) {
        if (!accessVerified || isFinishing() || loading) return;
        if (!append) {
            afterTime = null;
            afterId = null;
        }

        final String requestedTab = currentPrimaryTab;
        final String requestedManageSection = currentManageSection;
        final String requestedQuery = query;
        final String requestedUserState = userStateFilter;
        final String requestedReportState = reportStateFilter;
        final boolean requestedReportMine = reportMineOnly;
        final String requestedAfterTime = afterTime;
        final String requestedAfterId = afterId;

        loading = true;
        adminRefresh.setRefreshing(!append);
        adminLoadMore.setEnabled(false);

        if (!append) {
            adminContent.removeAllViews();
            showLoadingState("Đang tải dữ liệu…");
        }

        repository.run(() -> {
            if ("Tổng quan".equals(requestedTab)) {
                JSONObject dashboard = RpcClient.object("admin_dashboard_v2", Api.obj());
                for (String key : new String[]{"users_total", "users_active", "posts_total", "reels_total", "reports_pending", "reports_resolved"}) {
                    dashboard.getLong(key);
                }
                dashboard.getJSONArray("recent_reports");
                dashboard.getJSONArray("recent_actions");
                return dashboard;
            }

            if ("Báo cáo".equals(requestedTab)) {
                return RpcClient.call("admin_reports_page_v2", Api.obj(
                        "p_state", requestedReportState,
                        "p_kind", JSONObject.NULL,
                        "p_query", requestedQuery,
                        "p_mine", requestedReportMine,
                        "p_after_time", nullable(requestedAfterTime),
                        "p_after_id", nullable(requestedAfterId),
                        "p_limit", 30));
            }

            if ("Quản lý".equals(requestedTab)) {
                if ("Người dùng".equals(requestedManageSection) || "Yêu cầu tài khoản".equals(requestedManageSection)) {
                    return RpcClient.call("admin_users_page_v2", Api.obj(
                            "p_query", requestedQuery,
                            "p_state", "Yêu cầu tài khoản".equals(requestedManageSection) ? "deletion_requested" :
                                    requestedUserState.isEmpty() ? JSONObject.NULL : requestedUserState,
                            "p_after_time", nullable(requestedAfterTime),
                            "p_after_id", nullable(requestedAfterId),
                            "p_limit", 30));
                }
                String kind = "Bài viết".equals(requestedManageSection) ? "posts" :
                        "Reels".equals(requestedManageSection) ? "reels" : "comments";
                return RpcClient.call("admin_content_page_v2", Api.obj(
                        "p_kind", kind,
                        "p_query", requestedQuery,
                        "p_state", JSONObject.NULL,
                        "p_after_time", nullable(requestedAfterTime),
                        "p_after_id", nullable(requestedAfterId),
                        "p_limit", 30));
            }

            // "Nhật ký & Thêm" -> loads Audit page v2
            return RpcClient.call("admin_audit_page_v2", Api.obj(
                    "p_after_time", nullable(requestedAfterTime),
                    "p_after_id", nullable(requestedAfterId),
                    "p_limit", 30));
        }, new Api.Callback<Object>() {
            public void success(Object value) {
                loading = false;
                adminRefresh.setRefreshing(false);
                adminLoadMore.setEnabled(true);

                if (!requestedTab.equals(currentPrimaryTab) || !requestedQuery.equals(query)
                        || ("Quản lý".equals(requestedTab) && !requestedManageSection.equals(currentManageSection))) {
                    load(false);
                    return;
                }

                hideStates();

                if ("Tổng quan".equals(currentPrimaryTab)) {
                    renderDashboard((JSONObject) value);
                    return;
                }

                JSONArray rows;
                boolean hasMore = false;

                if (value instanceof JSONObject) {
                    try {
                        AdminModels.Page page = new AdminModels.Page((JSONObject) value);
                        rows = page.items;
                        hasMore = page.hasMore;
                        if (page.nextCursor != null) {
                            afterTime = page.nextCursor.optString("created_at", null);
                            afterId = page.nextCursor.optString("id", null);
                        }
                    } catch (org.json.JSONException invalid) {
                        showErrorState("Hợp đồng phân trang Admin không hợp lệ.");
                        adminLoadMore.setVisibility(View.GONE);
                        return;
                    }
                } else if (value instanceof JSONArray) {
                    rows = (JSONArray) value;
                    hasMore = rows.length() >= 30;
                    if (rows.length() > 0) {
                        JSONObject last = rows.optJSONObject(rows.length() - 1);
                        if (last != null) {
                            afterTime = last.optString("created_at", null);
                            afterId = last.optString("user_id", last.optString("id", null));
                        }
                    }
                } else {
                    showErrorState("Hợp đồng Admin không hợp lệ.");
                    return;
                }

                adminLoadMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);

                if (rows.length() == 0 && !append) {
                    if ("Quản lý".equals(currentPrimaryTab) && "Yêu cầu tài khoản".equals(currentManageSection)) {
                        showEmptyState("Chưa có yêu cầu đang chờ", "Chỉ hiển thị tài khoản đang hoạt động đã gửi yêu cầu xóa / vô hiệu hóa. Kéo xuống để tải lại.");
                    } else {
                        showEmptyState("Không có dữ liệu phù hợp", "Không tìm thấy kết quả nào theo bộ lọc hoặc từ khóa.");
                    }
                } else {
                    renderRows(rows, append);
                }
            }

            public void error(String error) {
                loading = false;
                adminRefresh.setRefreshing(false);
                adminLoadMore.setEnabled(true);
                if (handlePermissionError(error)) return;
                if (!append) {
                    showErrorState(error);
                } else {
                    toast(error);
                }
            }
        });
    }

    private Object nullable(String value) {
        return value == null || value.isEmpty() ? JSONObject.NULL : value;
    }

    private void showLoadingState(String message) {
        layoutLoading.setVisibility(View.VISIBLE);
        tvLoadingMessage.setText(message);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
    }

    private void showEmptyState(String title, String subtitle) {
        layoutEmpty.setVisibility(View.VISIBLE);
        tvEmptyTitle.setText(title);
        tvEmptySubtitle.setText(subtitle);
        layoutLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
    }

    private void showErrorState(String error) {
        layoutError.setVisibility(View.VISIBLE);
        tvErrorMessage.setText(error);
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
    }

    private void hideStates() {
        layoutLoading.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
    }

    private void renderDashboard(JSONObject d) {
        adminContent.removeAllViews();

        addSectionHeader("Tổng quan số liệu");

        // KPI Card 1: Users
        long totalUsers = d.optLong("users_total", 0);
        long activeUsers = d.optLong("users_active", 0);
        renderMetricCard(R.drawable.ic_person, "Người dùng",
                String.format(java.util.Locale.ROOT, "%,d", totalUsers),
                String.format(java.util.Locale.ROOT, "%,d đang hoạt động", activeUsers),
                () -> {
                    currentPrimaryTab = "Quản lý";
                    currentManageSection = "Người dùng";
                    buildPrimaryTabs();
                    updateSubHeaderVisibility();
                    resetPage();
                    load(false);
                });

        // KPI Card 2: Reports
        long pendingReports = d.optLong("reports_pending", 0);
        long resolvedReports = d.optLong("reports_resolved", 0);
        renderMetricCard(R.drawable.ic_shield, "Báo cáo nội dung",
                String.format(java.util.Locale.ROOT, "%,d", pendingReports),
                String.format(java.util.Locale.ROOT, "%,d chờ xử lý · %,d đã giải quyết", pendingReports, resolvedReports),
                () -> {
                    currentPrimaryTab = "Báo cáo";
                    reportStateFilter = "open";
                    reportMineOnly = false;
                    buildPrimaryTabs();
                    updateSubHeaderVisibility();
                    resetPage();
                    load(false);
                });

        // KPI Card 3: Posts & Reels
        long totalPosts = d.optLong("posts_total", 0);
        long totalReels = d.optLong("reels_total", 0);
        renderMetricCard(R.drawable.ic_video, "Nội dung xuất bản",
                String.format(java.util.Locale.ROOT, "%,d", totalPosts + totalReels),
                String.format(java.util.Locale.ROOT, "%,d bài viết · %,d reels", totalPosts, totalReels),
                () -> {
                    currentPrimaryTab = "Quản lý";
                    currentManageSection = "Bài viết";
                    buildPrimaryTabs();
                    updateSubHeaderVisibility();
                    resetPage();
                    load(false);
                });

        renderMetricCard(R.drawable.ic_person, "Yêu cầu tài khoản", "Xem",
                "Yêu cầu xóa / vô hiệu hóa đang chờ xử lý", () -> {
                    currentPrimaryTab = "Quản lý";
                    currentManageSection = "Yêu cầu tài khoản";
                    query = "";
                    adminSearch.setText("");
                    buildPrimaryTabs();
                    updateSubHeaderVisibility();
                    resetPage();
                    load(false);
                });

        // Recent Reports Section
        JSONArray recentReports = d.optJSONArray("recent_reports");
        if (recentReports != null && recentReports.length() > 0) {
            addSectionHeader("Báo cáo cần chú ý");
            for (int i = 0; i < Math.min(recentReports.length(), 5); i++) {
                JSONObject report = recentReports.optJSONObject(i);
                if (report != null) renderReportCard(report);
            }
        }

        // Recent Audit Actions Section
        JSONArray recentActions = d.optJSONArray("recent_actions");
        if (recentActions != null && recentActions.length() > 0) {
            addSectionHeader("Thao tác gần đây");
            for (int i = 0; i < Math.min(recentActions.length(), 5); i++) {
                JSONObject action = recentActions.optJSONObject(i);
                if (action != null) renderAuditCard(action);
            }
        }
    }

    private void renderMetricCard(int iconRes, String title, String value, String subtitle, Runnable onClick) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_admin_dashboard_metric, adminContent, false);
        ImageView ivIcon = card.findViewById(R.id.ivMetricIcon);
        ivIcon.setImageResource(iconRes);

        TextView tvTitle = card.findViewById(R.id.tvMetricTitle);
        tvTitle.setText(title);

        TextView tvValue = card.findViewById(R.id.tvMetricValue);
        tvValue.setText(value);

        TextView tvSub = card.findViewById(R.id.tvMetricSubtitle);
        tvSub.setText(subtitle);

        if (onClick != null) {
            card.setOnClickListener(v -> onClick.run());
        }
        adminContent.addView(card);
    }

    private void addSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(4), dp(16), 0, dp(8));
        tv.setLayoutParams(lp);
        adminContent.addView(tv);
    }

    private void renderRows(JSONArray rows, boolean append) {
        if (!append) adminContent.removeAllViews();

        if ("Báo cáo".equals(currentPrimaryTab)) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r != null) renderReportCard(r);
            }
        } else if ("Quản lý".equals(currentPrimaryTab)) {
            if ("Người dùng".equals(currentManageSection) || "Yêu cầu tài khoản".equals(currentManageSection)) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject u = rows.optJSONObject(i);
                    if (u != null) renderUserCard(u);
                }
            } else {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject c = rows.optJSONObject(i);
                    if (c != null) renderContentCard(c);
                }
            }
        } else if ("Nhật ký & Thêm".equals(currentPrimaryTab)) {
            // First render More/Account overview card on top if not appending
            if (!append) {
                renderMoreAccountSection();
                addSectionHeader("Lịch sử kiểm duyệt");
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject a = rows.optJSONObject(i);
                if (a != null) renderAuditCard(a);
            }
        }
    }

    private void renderMoreAccountSection() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_bg));
        card.setRadius(dp(14));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.divider_color));
        card.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView tvHeading = new TextView(this);
        tvHeading.setText("Tài khoản quản trị");
        tvHeading.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tvHeading.setTextSize(16);
        tvHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(tvHeading);

        SessionUser user = SessionManager.getInstance().getCurrentUser();
        TextView tvEmail = new TextView(this);
        tvEmail.setText(user != null ? user.getEmail() : "Phiên làm việc ẩn danh");
        tvEmail.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvEmail.setTextSize(13);
        LinearLayout.LayoutParams emailLp = new LinearLayout.LayoutParams(-1, -2);
        emailLp.topMargin = dp(4);
        tvEmail.setLayoutParams(emailLp);
        box.addView(tvEmail);

        TextView tvVersion = new TextView(this);
        tvVersion.setText("Quản trị Meeto · Phiên bản 1.0");
        tvVersion.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        tvVersion.setTextSize(12);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-1, -2);
        vLp.topMargin = dp(8);
        tvVersion.setLayoutParams(vLp);
        box.addView(tvVersion);

        card.addView(box);
        adminContent.addView(card);
    }

    private void renderReportCard(JSONObject row) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_admin_report, adminContent, false);

        TextView tvKind = card.findViewById(R.id.tvReportTargetKind);
        tvKind.setText(com.example.meetomxh.ui.AdminUi.kind(row.optString("target_kind", "report")));

        TextView tvReason = card.findViewById(R.id.tvReportReason);
        tvReason.setText(com.example.meetomxh.ui.AdminUi.reason(row.optString("reason_code", "")));

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
        String preview = com.example.meetomxh.ui.AdminUi.text(row,"details");
        if(preview.isEmpty())preview="Chưa có nội dung báo cáo";
        tvPreview.setText(preview);

        TextView tvTime = card.findViewById(R.id.tvReportTime);
        tvTime.setText(com.example.meetomxh.ui.AdminUi.timestamp(row.optString("created_at", "")));

        TextView tvSnippet = card.findViewById(R.id.tvReportIdSnippet);
        String id = row.optString("id", "");
        tvSnippet.setText(id.length() > 8 ? "#" + id.substring(0, 8) : "#" + id);

        card.setOnClickListener(v -> reportActions.open(id));
        adminContent.addView(card);
    }

    private void renderUserCard(JSONObject row) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_admin_user, adminContent, false);

        ImageView ivAvatar = card.findViewById(R.id.ivUserAvatar);
        AvatarViews.load(ivAvatar, Dto.asset(Dto.nullable(row, "avatar_asset_id")), row.optString("avatar_preset", "neutral"));

        TextView tvName = card.findViewById(R.id.tvUserDisplayName);
        tvName.setText(row.optString("display_name", "Người dùng"));

        TextView tvState = card.findViewById(R.id.tvUserStateBadge);
        String state = row.optString("account_state", "active");
        tvState.setText(stateLabel(state));
        tvState.setBackgroundResource("suspended".equals(state) ? R.drawable.bg_button_danger_soft :
                "deleting".equals(state) ? R.drawable.bg_badge_open : R.drawable.bg_badge_resolved);
        tvState.setTextColor(ContextCompat.getColor(this, "suspended".equals(state) ? R.color.status_error :
                "deleting".equals(state) ? R.color.status_warning : R.color.status_success));

        TextView tvUid = card.findViewById(R.id.tvUserUid);
        String uid = row.optString("user_id", row.optString("id", ""));
        tvUid.setText("Mã tài khoản: " + uid);

        TextView tvJoined = card.findViewById(R.id.tvUserJoined);
        tvJoined.setText("Tham gia: " + com.example.meetomxh.ui.AdminUi.timestamp(row.optString("created_at", "")));

        String requestedAt = com.example.meetomxh.ui.AdminUi.text(row, "deletion_requested_at");
        if (!requestedAt.isEmpty()) {
            tvJoined.append("\nYêu cầu xóa / vô hiệu hóa: " + com.example.meetomxh.ui.AdminUi.timestamp(requestedAt));
            if ("active".equals(state)) {
                tvState.setText("Chờ xử lý");
                tvState.setBackgroundResource(R.drawable.bg_badge_open);
                tvState.setTextColor(ContextCompat.getColor(this, R.color.status_warning));
            }
        }

        card.setOnClickListener(v -> openSubject("account", uid));
        adminContent.addView(card);
    }

    private void renderContentCard(JSONObject row) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_admin_content, adminContent, false);

        String kind = row.optString("kind", "post");
        TextView tvKind = card.findViewById(R.id.tvContentKind);
        tvKind.setText("comments".equals(kind) ? "BÌNH LUẬN" : "reels".equals(kind) ? "REEL" : "BÀI VIẾT");

        TextView tvAuthor = card.findViewById(R.id.tvContentAuthor);
        tvAuthor.setText(row.optString("author_name", "Người dùng"));

        TextView tvState = card.findViewById(R.id.tvContentState);
        String state = row.optString("state", "published");
        tvState.setText(stateLabel(state));
        tvState.setBackgroundResource("published".equals(state) ? R.drawable.bg_badge_resolved :
                "hidden".equals(state) ? R.drawable.bg_badge_open : R.drawable.bg_badge_dismissed);
        tvState.setTextColor(ContextCompat.getColor(this, "published".equals(state) ? R.color.status_success :
                "hidden".equals(state) ? R.color.status_warning : R.color.text_secondary));

        TextView tvPreview = card.findViewById(R.id.tvContentPreview);
        String body = row.optString("body", row.optString("description", "(Nội dung hình ảnh/video)"));
        tvPreview.setText(body);

        TextView tvTime = card.findViewById(R.id.tvContentTime);
        tvTime.setText("Ngày tạo: " + com.example.meetomxh.ui.AdminUi.timestamp(row.optString("created_at", "")));

        String subjectKind = "comments".equals(kind) ? "comment" : "reels".equals(kind) ? "reel" : "post";
        card.setOnClickListener(v -> openSubject(subjectKind, row.optString("id")));
        adminContent.addView(card);
    }

    private void renderAuditCard(JSONObject row) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_admin_audit, adminContent, false);
        com.example.meetomxh.ui.AdminUi.bindAudit(card, row);
        adminContent.addView(card);
    }

    private void openSubject(String kind, String id) {
        final int expected = ++subjectGeneration;
        showLoadingState("Đang tải chi tiết đối tượng…");
        repository.run(() -> {
            JSONObject result = RpcClient.object("admin_subject_detail_v2", Api.obj("p_kind", kind, "p_target_id", id));
            JSONObject target = result.getJSONObject("target");
            if ("account".equals(kind)) new AdminModels.Account(target);
            else new AdminModels.Content(target);
            return result;
        }, new Api.Callback<JSONObject>() {
            public void success(JSONObject result) {
                hideStates();
                if (expected != subjectGeneration || isFinishing()) return;
                JSONObject target = result.optJSONObject("target");
                JSONArray actions = result.optJSONArray("permitted_actions");

                String currentState = target != null ? target.optString("state") : "";
                String targetName = target == null ? "" : com.example.meetomxh.ui.AdminUi.text(target,"display_name");
                if(targetName.isEmpty()&&target!=null)targetName=com.example.meetomxh.ui.AdminUi.text(target,"body");
                if(targetName.isEmpty())targetName="Không có văn bản xem trước";

                String requestedAt = target == null ? "" : com.example.meetomxh.ui.AdminUi.text(target, "deletion_requested_at");
                if ("account".equals(kind) && !requestedAt.isEmpty()) {
                    targetName += "\n\nChủ tài khoản gửi yêu cầu xóa / vô hiệu hóa lúc: "
                            + com.example.meetomxh.ui.AdminUi.timestamp(requestedAt)
                            + "\nKhóa tài khoản chỉ vô hiệu hóa quyền truy cập; không xóa dữ liệu.";
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(AdminPanelActivity.this)
                        .setTitle("Chi tiết quản trị")
                        .setMessage("Loại: " + com.example.meetomxh.ui.AdminUi.kind(kind) + "\nMã đối tượng: " + id + "\nTrạng thái: " + stateLabel(currentState) + "\n\n" + targetName +
                                "\n\n" + ("account".equals(kind) ? "Khóa đến khi được mở lại; không có cơ chế tự hết hạn." : "Gỡ một bình luận không gỡ các bình luận phản hồi."))
                        .setNegativeButton("Đóng", null);

                if (actions != null && actions.length() > 0) {
                    builder.setPositiveButton("Thao tác", (d, w) -> {
                        subjectDialog = com.example.meetomxh.ui.AdminUi.actions(AdminPanelActivity.this,
                                "Chọn thao tác quản trị", actions, n -> {
                                    subjectDraft = Api.obj("p_kind", kind, "p_target_id", id,
                                            "p_action", actions.optString(n), "p_expected_state", currentState, "p_reason", "");
                                    confirmSubjectAction(subjectDraft);
                                });
                        subjectDialog.show();
                    });
                } else {
                    builder.setMessage("Loại: " + com.example.meetomxh.ui.AdminUi.kind(kind)
                            + "\nMã đối tượng: " + id + "\nTrạng thái: " + stateLabel(currentState)
                            + "\n\n" + targetName + "\n\nKhông có thao tác phù hợp với quyền của bạn hoặc trạng thái hiện tại.");
                }
                subjectDialog = builder.show();
            }

            public void error(String error) {
                hideStates();
                if (expected == subjectGeneration && !handlePermissionError(error)) toast(error);
            }
        });
    }

    private String actionLabel(String action) {
        switch (action) {
            case "suspend": return "Khóa đến khi được mở lại";
            case "restore": return "Khôi phục / mở khóa";
            case "hide": return "Ẩn nội dung";
            case "remove": return "Gỡ nội dung, giữ bằng chứng";
            default: return action;
        }
    }

    private void saveSubjectReason() {
        if (subjectDraft != null && subjectReason != null) {
            try {
                subjectDraft.put("p_reason", subjectReason.getText().toString());
            } catch (org.json.JSONException ignored) {}
        }
    }

    private void confirmSubjectAction(JSONObject draft) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(10), dp(20), dp(8));

        TextView tvDesc = new TextView(this);
        tvDesc.setText("Thao tác đối tượng: " + draft.optString("p_target_id") +
                "\nTrạng thái hiện tại: " + stateLabel(draft.optString("p_expected_state")) +
                "\nLý do xử lý bắt buộc và được ghi vào audit.");
        tvDesc.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvDesc.setTextSize(13);
        container.addView(tvDesc);

        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setBoxCornerRadii(dp(10), dp(10), dp(10), dp(10));
        inputLayout.setHint("Lý do xử lý (1–1000 ký tự)");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        inputLayout.setLayoutParams(lp);

        TextInputEditText reason = new TextInputEditText(this);
        reason.setText(draft.optString("p_reason"));
        reason.setMinLines(2);
        reason.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        subjectReason = reason;
        inputLayout.addView(reason);
        container.addView(inputLayout);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(actionLabel(draft.optString("p_action")))
                .setView(container)
                .setNegativeButton("Hủy", (d, w) -> {
                    subjectDraft = null;
                    subjectReason = null;
                })
                .setPositiveButton("Xác nhận", null)
                .create();

        subjectDialog = dialog;
        dialog.setOnCancelListener(d -> {
            subjectDraft = null;
            subjectReason = null;
        });

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = reason.getText().toString().trim();
            if (text.length() < 1 || text.length() > 1000) {
                inputLayout.setError("Nhập lý do từ 1–1000 ký tự");
                return;
            }
            JSONObject args = Api.obj("p_kind", draft.optString("p_kind"), "p_target_id", draft.optString("p_target_id"),
                    "p_action", draft.optString("p_action"), "p_reason", text, "p_expected_state", draft.optString("p_expected_state"));
            saveSubjectReason();
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

            repository.submit("admin_moderate_subject_v2", args, new Api.Callback<JSONObject>() {
                public void success(JSONObject receipt) {
                    subjectDraft = null;
                    subjectReason = null;
                    dialog.dismiss();
                    toast(receipt.optBoolean("replayed") ?
                            "Đã xác nhận kết quả đã lưu. Đang tải trạng thái hiện tại." :
                            "Đã cập nhật trạng thái và audit.");
                    resetPage();
                    load(false);
                }

                public void error(String error) {
                    dialog.setCancelable(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    inputLayout.setError(error);
                    if (!handlePermissionError(error)) toast(error);
                }
            });
        }));
        dialog.show();
    }

    private String stateLabel(String state) {
        switch (state) {
            case "active": return "Đang hoạt động";
            case "suspended": return "Đã khóa";
            case "deleting": return "Đang xóa";
            case "open": return "Chờ xử lý";
            case "assigned": return "Đang xử lý";
            case "resolved": return "Đã giải quyết";
            case "dismissed": return "Đã bác bỏ";
            case "published": return "Đang hiển thị";
            case "hidden": return "Đã ẩn";
            case "deleted": return "Đã gỡ";
            default: return com.example.meetomxh.ui.AdminUi.state(state);
        }
    }

    private boolean handlePermissionError(String message) {
        if (repository.lastError != null && (repository.lastError.kind == AdminRepository.ErrorKind.PERMISSION
                || repository.lastError.kind == AdminRepository.ErrorKind.SESSION)) {
            repository.cancelAll();
            reportActions.stop();
            saveSubjectReason();
            if (subjectDialog != null) subjectDialog.dismiss();
            adminContent.removeAllViews();
            accessVerified = false;
            verifyAccess();
            return true;
        }
        return false;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Đăng xuất quản trị")
                .setMessage("Bạn có chắc muốn đăng xuất khỏi phiên quản trị này?")
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
    protected void onResume() {
        super.onResume();
        if (resumedOnce && !isFinishing()) verifyAccess();
        resumedOnce = true;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("primary_tab", currentPrimaryTab);
        out.putString("manage_section", currentManageSection);
        out.putString("report_state", reportStateFilter);
        out.putBoolean("report_mine", reportMineOnly);
        out.putString("user_state", userStateFilter);
        out.putString("query", query);
        reportActions.save(out);
        saveSubjectReason();
        if (subjectDraft != null) out.putString("subject_draft", subjectDraft.toString());
    }

    @Override
    protected void onStop() {
        subjectGeneration++;
        query = adminSearch.getText().toString().trim();
        handler.removeCallbacks(searchRunnable);
        repository.cancelAll();
        reportActions.stop();
        saveSubjectReason();
        if (subjectDialog != null) subjectDialog.dismiss();
        subjectReason = null;
        subjectDialog = null;
        loading = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        repository.cancelAll();
        reportActions.destroy();
        super.onDestroy();
    }
}
