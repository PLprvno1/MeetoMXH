package com.example.meetomxh;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Active members only; all role/removal mutations go through the authorized group RPC. */
public final class GroupMembersActivity extends AppCompatActivity {
    private com.example.meetomxh.ui.ActivityRequests requests;
    public static final String EXTRA_CHAT_ID = "chat_id";

    private String chatId, self;
    private Chat chat;
    private boolean loading;

    private View scrollMembers;
    private LinearLayout rowsContainer;
    private TextView tvMemberCountSummary;
    private Button btnTopInvite;

    private View layoutLoading;
    private View layoutEmpty;
    private View layoutError;
    private TextView tvErrorDescription;
    private Button btnRetry;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requests=new com.example.meetomxh.ui.ActivityRequests(this);
        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        self = SessionManager.getInstance().uid();
        if (chatId == null) {
            finish();
            return;
        }
        build();
        load();
    }

    private void build() {
        setContentView(R.layout.activity_group_members);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        scrollMembers = findViewById(R.id.scrollMembers);
        rowsContainer = findViewById(R.id.rowsContainer);
        tvMemberCountSummary = findViewById(R.id.tvMemberCountSummary);
        btnTopInvite = findViewById(R.id.btnTopInvite);

        layoutLoading = findViewById(R.id.layoutLoading);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        layoutError = findViewById(R.id.layoutError);
        tvErrorDescription = findViewById(R.id.tvErrorDescription);
        btnRetry = findViewById(R.id.btnRetry);

        btnTopInvite.setOnClickListener(v -> {
            if (chat != null) ChatControls.invite(this, chat);
        });

        btnRetry.setOnClickListener(v -> load());
    }

    private void load() {
        if (loading) return;
        loading = true;

        if (rowsContainer.getChildCount() == 0) {
            layoutLoading.setVisibility(View.VISIBLE);
            layoutEmpty.setVisibility(View.GONE);
            layoutError.setVisibility(View.GONE);
            scrollMembers.setVisibility(View.GONE);
        }

        requests.run(() -> {
            Chat c = ChatRepository.getInstance().load(chatId);
            JSONArray members = (JSONArray) RpcClient.call("get_conversation_members", Api.obj("p_conversation_id", chatId));
            return new Object[]{c, members};
        }, new Api.Callback<Object[]>() {
            public void success(Object[] result) {
                loading = false;
                if (isFinishing()) return;
                chat = (Chat) result[0];
                JSONArray members = (JSONArray) result[1];

                boolean isAdmin = "admin".equals(chat.role);
                btnTopInvite.setVisibility(isAdmin ? View.VISIBLE : View.GONE);

                List<JSONObject> list = new ArrayList<>();
                for (int i = 0; i < members.length(); i++) {
                    JSONObject row = members.optJSONObject(i);
                    if (row != null) list.add(row);
                }
                list.sort(Comparator.comparingInt(GroupMembersActivity.this::rank)
                        .thenComparing(ChatCustomizationClient::name, String.CASE_INSENSITIVE_ORDER));

                rowsContainer.removeAllViews();
                for (JSONObject row : list) {
                    addMember(row);
                }

                layoutLoading.setVisibility(View.GONE);

                if (list.isEmpty()) {
                    tvMemberCountSummary.setText("0 thành viên đang hoạt động");
                    scrollMembers.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.VISIBLE);
                    layoutError.setVisibility(View.GONE);
                } else {
                    tvMemberCountSummary.setText(list.size() + " thành viên đang hoạt động");
                    scrollMembers.setVisibility(View.VISIBLE);
                    layoutEmpty.setVisibility(View.GONE);
                    layoutError.setVisibility(View.GONE);
                }
            }

            public void error(String e) {
                loading = false;
                layoutLoading.setVisibility(View.GONE);

                if (rowsContainer.getChildCount() == 0) {
                    scrollMembers.setVisibility(View.GONE);
                    layoutEmpty.setVisibility(View.GONE);
                    layoutError.setVisibility(View.VISIBLE);
                    tvErrorDescription.setText(e);
                } else {
                    Toast.makeText(GroupMembersActivity.this, e, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private int rank(JSONObject row) {
        String id = row.optString("user_id");
        if (id.equals(chat.ownerId)) return 0;
        if ("admin".equals(row.optString("role"))) return 1;
        return 2;
    }

    private void addMember(JSONObject member) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_group_member, rowsContainer, false);

        ImageView avatar = row.findViewById(R.id.ivMemberAvatar);
        AvatarViews.load(avatar, Dto.asset(Dto.nullable(member, "avatar_asset_id")), member.optString("avatar_preset", "neutral"));

        String display = ChatCustomizationClient.name(member).trim();
        if (display.isEmpty() || "null".equalsIgnoreCase(display)) {
            display = "Người dùng";
        }

        TextView name = row.findViewById(R.id.tvMemberName);
        name.setText(display);

        TextView role = row.findViewById(R.id.tvMemberRoleBadge);
        String userId = member.optString("user_id");

        if (userId.equals(chat.ownerId)) {
            role.setText("Chủ nhóm");
            role.setBackgroundResource(R.drawable.bg_badge_owner);
            role.setTextColor(ContextCompat.getColor(this, R.color.brand_blue));
        } else if ("admin".equals(member.optString("role"))) {
            role.setText("Quản trị viên");
            role.setBackgroundResource(R.drawable.bg_badge_admin);
            role.setTextColor(ContextCompat.getColor(this, R.color.brand_blue_dark));
        } else {
            role.setText("Thành viên");
            role.setBackgroundResource(R.drawable.bg_badge_member);
            role.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }

        ImageView btnActions = row.findViewById(R.id.btnMemberActions);
        boolean canManage = "admin".equals(chat.role)
                && !self.equals(userId)
                && !Objects.equals(chat.ownerId, userId);

        if (canManage) {
            btnActions.setVisibility(View.VISIBLE);
            btnActions.setOnClickListener(v -> memberActions(member));
            row.setOnClickListener(v -> memberActions(member));
        } else {
            btnActions.setVisibility(View.GONE);
            row.setOnClickListener(null);
            row.setClickable(false);
        }

        rowsContainer.addView(row);
    }

    private void memberActions(JSONObject member) {
        if (!"admin".equals(chat.role)
                || self.equals(member.optString("user_id"))
                || Objects.equals(chat.ownerId, member.optString("user_id"))) {
            return;
        }

        String role = member.optString("role");
        String[] labels = "admin".equals(role)
                ? new String[]{"Loại khỏi nhóm", "Hạ xuống thành viên"}
                : new String[]{"Loại khỏi nhóm", "Đặt làm quản trị viên"};

        new android.app.AlertDialog.Builder(this)
                .setItems(labels, (d, which) -> {
                    String action = which == 0 ? "remove" : "admin".equals(role) ? "demote" : "promote";
                    new android.app.AlertDialog.Builder(this)
                            .setMessage("Xác nhận thay đổi quyền thành viên?")
                            .setNegativeButton("Hủy", null)
                            .setPositiveButton("Xác nhận", (x, w) ->
                                    requests.run(() -> RpcClient.call("manage_conversation_member",
                                                    Api.obj("p_conversation_id", chatId,
                                                            "p_target", member.optString("user_id"),
                                                            "p_action", action)),
                                            new Api.Callback<Object>() {
                                                public void success(Object r) {
                                                    load();
                                                }

                                                public void error(String e) {
                                                    Toast.makeText(GroupMembersActivity.this, e, Toast.LENGTH_LONG).show();
                                                }
                                            })).show();
                }).show();
    }
}
