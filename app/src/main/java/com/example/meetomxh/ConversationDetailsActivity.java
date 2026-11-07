package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.json.JSONArray;
import org.json.JSONObject;

/** Per-user conversation controls; every setting is authorized by its own RPC. */
public final class ConversationDetailsActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID = "chat_id";

    private Chat loadedChat;
    private long loadGeneration;
    private String chatId, peerId;
    private boolean archived;

    private ImageView avatar;
    private Button avatarButton;
    private TextView conversationName, memberCount, peerUsername;
    private MaterialSwitch muteSwitch;

    private View rowSharedGallery, rowSharedPosts, rowSearchMessages;
    private View rowNicknames, rowTheme, rowArchive;
    private TextView tvArchiveText;

    private View headerGroupManage, cardGroupManage;
    private View rowGroupMembers, rowInviteMember, divInvite, rowRenameGroup, divRename, rowLeaveGroup;

    private View headerPrivateOptions, cardPrivateOptions;
    private View rowViewProfile, rowCreateGroup, divCreateGroup, rowBlockUser, divBlock, rowReportUser, divReport;
    private TextView tvBlockUserText;

    private com.example.meetomxh.storage.SupabaseStorage.Transfer avatarUpload;
    private final androidx.activity.result.ActivityResultLauncher<String> avatarPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) uploadAvatar(uri);
            });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
        if (chatId == null) {
            finish();
            return;
        }
        buildUi();
        load();
    }

    private void buildUi() {
        setContentView(R.layout.activity_conversation_details);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        avatar = findViewById(R.id.ivAvatar);
        avatarButton = findViewById(R.id.btnChangeAvatar);
        conversationName = findViewById(R.id.tvConversationName);
        memberCount = findViewById(R.id.tvMemberCount);
        peerUsername = findViewById(R.id.tvPeerUsername);

        avatarButton.setOnClickListener(v -> avatarPicker.launch("image/*"));

        // Section 1: Shared content
        rowSharedGallery = findViewById(R.id.rowSharedGallery);
        rowSharedGallery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, SharedMediaGalleryActivity.class)
                        .putExtra(SharedMediaGalleryActivity.EXTRA_CHAT_ID, chatId));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Không thể mở ảnh và video đã chia sẻ. Hãy thử lại.", Toast.LENGTH_SHORT).show();
            }
        });

        rowSharedPosts = findViewById(R.id.rowSharedPosts);
        rowSharedPosts.setOnClickListener(v ->
                startActivity(new Intent(this, SharedPostsActivity.class)
                        .putExtra(SharedPostsActivity.EXTRA_CHAT_ID, chatId)));

        rowSearchMessages = findViewById(R.id.rowSearchMessages);
        rowSearchMessages.setOnClickListener(v ->
                startActivity(new Intent(this, ConversationSearchActivity.class)
                        .putExtra(ConversationSearchActivity.EXTRA_CHAT_ID, chatId)));

        // Section 2: Conversation settings
        rowNicknames = findViewById(R.id.rowNicknames);
        rowNicknames.setOnClickListener(v -> ChatCustomizationUi.nicknames(this, chatId, this::load));

        rowTheme = findViewById(R.id.rowTheme);
        rowTheme.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra("open_chat_theme_picker", true));
            finish();
        });

        muteSwitch = findViewById(R.id.switchMute);
        muteSwitch.setOnCheckedChangeListener((b, checked) -> setMuted(checked));

        rowArchive = findViewById(R.id.rowArchive);
        tvArchiveText = findViewById(R.id.tvArchiveText);
        rowArchive.setOnClickListener(v -> {
            rowArchive.setEnabled(false);
            Api.run(() -> RpcClient.call("set_conversation_archived",
                            Api.obj("p_conversation_id", chatId, "p_archived", !archived)),
                    new Api.Callback<Object>() {
                        public void success(Object r) {
                            archived = !archived;
                            rowArchive.setEnabled(true);
                            tvArchiveText.setText(archived ? "Bỏ lưu trữ" : "Lưu trữ hội thoại");
                        }
                        public void error(String e) {
                            rowArchive.setEnabled(true);
                            Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
                        }
                    });
        });

        // Section 3: Group management
        headerGroupManage = findViewById(R.id.headerGroupManage);
        cardGroupManage = findViewById(R.id.cardGroupManage);
        rowGroupMembers = findViewById(R.id.rowGroupMembers);
        rowInviteMember = findViewById(R.id.rowInviteMember);
        divInvite = findViewById(R.id.divInvite);
        rowRenameGroup = findViewById(R.id.rowRenameGroup);
        divRename = findViewById(R.id.divRename);
        rowLeaveGroup = findViewById(R.id.rowLeaveGroup);

        rowGroupMembers.setOnClickListener(v ->
                startActivity(new Intent(this, GroupMembersActivity.class)
                        .putExtra(GroupMembersActivity.EXTRA_CHAT_ID, chatId)));

        rowInviteMember.setOnClickListener(v -> withChat(c -> ChatControls.invite(this, c)));

        rowRenameGroup.setOnClickListener(v ->
                Api.run(() -> ChatRepository.getInstance().load(chatId), new Api.Callback<Chat>() {
                    public void success(Chat c) {
                        ChatControls.rename(ConversationDetailsActivity.this, c, ConversationDetailsActivity.this::load);
                    }
                    public void error(String e) {
                        Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
                    }
                }));

        rowLeaveGroup.setOnClickListener(v -> withChat(c -> ChatControls.leave(this, c)));

        // Section 4: Private options
        headerPrivateOptions = findViewById(R.id.headerPrivateOptions);
        cardPrivateOptions = findViewById(R.id.cardPrivateOptions);
        rowViewProfile = findViewById(R.id.rowViewProfile);
        rowCreateGroup = findViewById(R.id.rowCreateGroup);
        divCreateGroup = findViewById(R.id.divCreateGroup);
        rowBlockUser = findViewById(R.id.rowBlockUser);
        divBlock = findViewById(R.id.divBlock);
        tvBlockUserText = findViewById(R.id.tvBlockUserText);
        rowReportUser = findViewById(R.id.rowReportUser);
        divReport = findViewById(R.id.divReport);

        rowViewProfile.setOnClickListener(v -> {
            if (peerId != null) {
                startActivity(new Intent(this, ProfileActivity.class).putExtra("target_uid", peerId));
            }
        });

        rowCreateGroup.setOnClickListener(v -> {
            if (loadedChat != null && "private".equals(loadedChat.getType())) {
                ChatControls.create(this, peerId);
            }
        });

        rowBlockUser.setOnClickListener(v -> toggleBlock());

        rowReportUser.setOnClickListener(v -> {
            if (peerId != null) {
                startActivity(new Intent(this, ReportContentActivity.class)
                        .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE, "profile")
                        .putExtra(ReportContentActivity.EXTRA_TARGET_ID, peerId));
            }
        });
    }

    private void load() {
        long token = ++loadGeneration;
        Api.run(() -> {
            Chat c = ChatRepository.getInstance().load(chatId);
            JSONArray blocked = (JSONArray) RpcClient.call("list_blocked_users", new JSONObject());
            return new Object[]{c, blocked};
        }, new Api.Callback<Object[]>() {
            public void success(Object[] v) {
                if (isFinishing() || isDestroyed() || token != loadGeneration) return;
                Chat c = (Chat) v[0];
                loadedChat = c;
                peerId = null;

                JSONArray blocked = (JSONArray) v[1];
                for (String id : c.getParticipants()) {
                    if (!id.equals(SessionManager.getInstance().uid())) {
                        peerId = id;
                    }
                }

                conversationName.setText(c.title != null && !c.title.trim().isEmpty() ? c.title : "Hội thoại");

                boolean group = "group".equals(c.getType());
                if (group) {
                    if (c.avatar != null && !c.avatar.trim().isEmpty()) {
                        AvatarViews.load(avatar, c.avatar, "neutral");
                    } else {
                        avatar.setImageResource(R.drawable.ic_groups);
                        avatar.setColorFilter(ContextCompat.getColor(ConversationDetailsActivity.this, R.color.brand_blue));
                    }
                    memberCount.setVisibility(View.VISIBLE);
                    memberCount.setText(c.getParticipants().size() + " thành viên");
                    peerUsername.setVisibility(View.GONE);
                } else {
                    avatar.setColorFilter(null);
                    AvatarViews.load(avatar, c.avatar, "neutral");
                    memberCount.setVisibility(View.GONE);
                    peerUsername.setVisibility(View.VISIBLE);
                }

                avatarButton.setVisibility(group && "admin".equals(c.role) ? View.VISIBLE : View.GONE);
                archived = c.archived;
                tvArchiveText.setText(archived ? "Bỏ lưu trữ" : "Lưu trữ hội thoại");

                // Visibility for Group Management
                headerGroupManage.setVisibility(group ? View.VISIBLE : View.GONE);
                cardGroupManage.setVisibility(group ? View.VISIBLE : View.GONE);
                boolean isAdmin = group && "admin".equals(c.role);
                rowInviteMember.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
                divInvite.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
                rowRenameGroup.setVisibility(isAdmin ? View.VISIBLE : View.GONE);
                divRename.setVisibility(isAdmin ? View.VISIBLE : View.GONE);

                // Visibility for Private Options
                headerPrivateOptions.setVisibility(group ? View.GONE : View.VISIBLE);
                cardPrivateOptions.setVisibility(group ? View.GONE : View.VISIBLE);

                if ("private".equals(c.getType()) && peerId != null) {
                    ProfileClient.get(peerId, new Api.Callback<com.example.meetomxh.models.User>() {
                        public void success(com.example.meetomxh.models.User p) {
                            if (isFinishing() || isDestroyed() || token != loadGeneration) return;
                            String name = c.peerName != null ? c.peerName : p.getFullName();
                            conversationName.setText(name == null || name.trim().isEmpty() ? "Người dùng không khả dụng" : name);
                            peerUsername.setText(p.username == null || p.username.trim().isEmpty() ? "" : "@" + p.username);
                            AvatarViews.load(avatar, p.getAvatarUrl(), p.getAvatarPreset());
                        }

                        public void error(String e) {
                            if (isFinishing() || isDestroyed() || token != loadGeneration) return;
                            conversationName.setText("Người dùng không khả dụng");
                            peerUsername.setText("");
                        }
                    });
                }

                muteSwitch.setOnCheckedChangeListener(null);
                muteSwitch.setChecked(c.muted);
                muteSwitch.setOnCheckedChangeListener((b, checked) -> setMuted(checked));

                boolean isBlocked = false;
                for (int i = 0; i < blocked.length(); i++) {
                    if (peerId != null && peerId.equals(blocked.optJSONObject(i).optString("user_id"))) {
                        isBlocked = true;
                    }
                }
                tvBlockUserText.setText(isBlocked ? "Bỏ chặn người dùng" : "Chặn người dùng");
                rowBlockUser.setTag(isBlocked);
            }

            public void error(String e) {
                Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
            }
        });
    }

    private interface ChatAction {
        void run(Chat chat);
    }

    private void withChat(ChatAction action) {
        Api.run(() -> ChatRepository.getInstance().load(chatId), new Api.Callback<Chat>() {
            public void success(Chat c) {
                action.run(c);
            }

            public void error(String e) {
                Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setMuted(boolean value) {
        muteSwitch.setEnabled(false);
        Api.run(() -> RpcClient.call("set_conversation_muted",
                        Api.obj("p_conversation_id", chatId, "p_muted", value)),
                new Api.Callback<Object>() {
                    public void success(Object v) {
                        muteSwitch.setEnabled(true);
                        Toast.makeText(ConversationDetailsActivity.this,
                                value ? "Đã tắt thông báo" : "Đã bật thông báo", Toast.LENGTH_SHORT).show();
                    }

                    public void error(String e) {
                        muteSwitch.setOnCheckedChangeListener(null);
                        muteSwitch.setChecked(!value);
                        muteSwitch.setOnCheckedChangeListener((b, checked) -> setMuted(checked));
                        muteSwitch.setEnabled(true);
                        Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void toggleBlock() {
        if (peerId == null) return;
        boolean blocked = Boolean.TRUE.equals(rowBlockUser.getTag());
        new AlertDialog.Builder(this)
                .setTitle(blocked ? "Bỏ chặn người dùng?" : "Chặn người dùng?")
                .setMessage(blocked ? "Họ sẽ có thể liên hệ lại với bạn." : "Họ sẽ không thể gửi tin nhắn cho bạn khi bị chặn.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton(blocked ? "Bỏ chặn" : "Chặn", (d, w) -> {
                    rowBlockUser.setEnabled(false);
                    Api.run(() -> RpcClient.call("set_block", Api.obj("p_target", peerId, "p_blocked", !blocked)),
                            new Api.Callback<Object>() {
                                public void success(Object r) {
                                    rowBlockUser.setTag(!blocked);
                                    tvBlockUserText.setText(!blocked ? "Bỏ chặn người dùng" : "Chặn người dùng");
                                    rowBlockUser.setEnabled(true);
                                    Toast.makeText(ConversationDetailsActivity.this,
                                            !blocked ? "Đã chặn người dùng" : "Đã bỏ chặn", Toast.LENGTH_SHORT).show();
                                }

                                public void error(String e) {
                                    rowBlockUser.setEnabled(true);
                                    Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
                                }
                            });
                }).show();
    }

    private void uploadAvatar(android.net.Uri uri) {
        avatarButton.setEnabled(false);
        avatarUpload = com.example.meetomxh.storage.SupabaseStorage.upload(getContentResolver(), uri, "avatar",
                SessionManager.getInstance().uid(), new com.example.meetomxh.storage.SupabaseStorage.UploadListener() {
                    public void onProgress(int p) {
                        avatarButton.setText("Đang tải " + p + "%");
                    }

                    public void onSuccess(String ref) {
                        Api.run(() -> RpcClient.call("set_conversation_avatar",
                                        Api.obj("p_conversation_id", chatId, "p_asset_id", Dto.assetId(ref))),
                                new Api.Callback<Object>() {
                                    public void success(Object r) {
                                        avatarButton.setEnabled(true);
                                        avatarButton.setText("Đổi ảnh nhóm");
                                        load();
                                    }

                                    public void error(String e) {
                                        avatarButton.setEnabled(true);
                                        avatarButton.setText("Thử đổi ảnh lại");
                                        Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
                                    }
                                });
                    }

                    public void onFailure(String e) {
                        avatarButton.setEnabled(true);
                        avatarButton.setText("Thử đổi ảnh lại");
                        Toast.makeText(ConversationDetailsActivity.this, e, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (conversationName != null) load();
    }

    @Override
    protected void onDestroy() {
        if (avatarUpload != null) avatarUpload.cancel();
        super.onDestroy();
    }
}
