package com.example.meetomxh;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.meetomxh.ChatActivity;
import com.example.meetomxh.databinding.ActivityProfileBinding;
import com.example.meetomxh.models.FriendItem;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.FriendRepository;
import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;
import com.example.meetomxh.supabase.*;

public class ProfileActivity extends AppCompatActivity {

    private ActivityProfileBinding binding;
    private FriendRepository friendRepository;

    private SessionManager mAuth;

    private String targetUid;
    private boolean targetProfileExists;
    private FriendItem.RelationshipStatus currentRelationshipStatus = FriendItem.RelationshipStatus.NO_RELATION;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        friendRepository = FriendRepository.getInstance();

        mAuth = SessionManager.getInstance();

        targetUid = getIntent().getStringExtra("target_uid");
        binding.btnPrimaryAction.setEnabled(false);
        binding.btnSecondaryAction.setEnabled(false);
        SessionUser currentUser = mAuth.getCurrentUser();

        if (targetUid == null || targetUid.isEmpty()) {
            if (currentUser != null) {
                targetUid = currentUser.getUid();
            } else {
                Toast.makeText(this, "Không tìm thấy thông tin người dùng", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnMessage.setVisibility(View.GONE);
        binding.btnMessage.setOnClickListener(v -> openChatWithTarget());

        android.widget.LinearLayout content=(android.widget.LinearLayout)((androidx.core.widget.NestedScrollView)binding.getRoot()).getChildAt(0);
        android.widget.Button posts=new android.widget.Button(this);posts.setText("Bài viết");content.addView(posts);posts.setOnClickListener(v->startActivity(new Intent(this,UserPostsActivity.class).putExtra("author_id",targetUid)));
        if(!targetUid.equals(mAuth.uid())){android.widget.Button privacy=new android.widget.Button(this);privacy.setText("Chặn / bỏ chặn");content.addView(privacy);privacy.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("Quyền riêng tư").setItems(new String[]{"Chặn người dùng","Bỏ chặn người dùng"},(d,i)->Api.run(()->RpcClient.call("set_block",Api.obj("p_target",targetUid,"p_blocked",i==0)),new Api.Callback<Object>(){public void success(Object r){Toast.makeText(ProfileActivity.this,"Đã cập nhật. Bỏ chặn không khôi phục quan hệ cũ.",Toast.LENGTH_LONG).show();finish();}public void error(String e){Toast.makeText(ProfileActivity.this,e,Toast.LENGTH_LONG).show();}})).show());}
    }

    @Override protected void onResume() {
        super.onResume();
        if (targetUid != null) {
            loadUserProfile();
            loadRelationshipStatus();
        }
    }

    private void loadUserProfile() {
 ProfileClient.get(targetUid,new Api.Callback<User>(){public void success(User user){if(isFinishing())return;targetProfileExists=true;binding.tvFullName.setText(user.getFullName());binding.tvBio.setText(user.getBio());binding.tvGender.setText(user.getGender());binding.tvDob.setText(user.getDateOfBirth());binding.tvGender.setVisibility(user.getGender()==null?View.GONE:View.VISIBLE);binding.tvDob.setVisibility(user.getDateOfBirth()==null?View.GONE:View.VISIBLE);Glide.with(ProfileActivity.this).applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(user.getAvatarUrl()).placeholder(R.drawable.ic_home).into(binding.imgAvatar);Glide.with(ProfileActivity.this).applyDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions().diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true)).load(user.getCoverUrl()).into(binding.imgCover);updateMessageButton();}public void error(String e){targetProfileExists=false;updateMessageButton();Toast.makeText(ProfileActivity.this,e,Toast.LENGTH_LONG).show();}});
}

    private void loadRelationshipStatus() {
        SessionUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null && currentUser.getUid().equals(targetUid)) {
            currentRelationshipStatus = FriendItem.RelationshipStatus.SELF;
            updateActionButtonsUI();
            return;
        }

        friendRepository.getRelationshipStatus(targetUid, new FriendRepository.OnRelationshipStatusListener() {
            @Override
            public void onStatus(FriendItem.RelationshipStatus status) {
                if (isDestroyed() || isFinishing()) return;
                currentRelationshipStatus = status;
                updateActionButtonsUI();
            }

            @Override
            public void onError(String error) {
                if (isDestroyed() || isFinishing()) return;
                Toast.makeText(ProfileActivity.this, "Lỗi kiểm tra quan hệ: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateActionButtonsUI() {
        binding.btnPrimaryAction.setEnabled(true);
        binding.btnSecondaryAction.setEnabled(true);
        updateMessageButton();
        if (currentRelationshipStatus == FriendItem.RelationshipStatus.SELF) {
            binding.btnEditProfile.setVisibility(View.VISIBLE);
            binding.btnPrimaryAction.setVisibility(View.GONE);
            binding.btnSecondaryAction.setVisibility(View.GONE);

            binding.btnEditProfile.setOnClickListener(v -> startActivity(new Intent(this, ProfileEditActivity.class)));
            return;
        }

        binding.btnEditProfile.setVisibility(View.GONE);

        if (currentRelationshipStatus == FriendItem.RelationshipStatus.NO_RELATION) {
            binding.btnPrimaryAction.setText("Thêm bạn bè");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.GONE);

            binding.btnPrimaryAction.setOnClickListener(v -> handleSendRequest());
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.PENDING_SENT) {
            binding.btnPrimaryAction.setText("Hủy lời mời");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.GONE);

            binding.btnPrimaryAction.setOnClickListener(v -> handleRemoveRelation("Đã hủy lời mời kết bạn"));
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.PENDING_RECEIVED) {
            binding.btnPrimaryAction.setText("Chấp nhận");
            binding.btnSecondaryAction.setText("Từ chối");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.VISIBLE);

            binding.btnPrimaryAction.setOnClickListener(v -> handleAcceptRequest());
            binding.btnSecondaryAction.setOnClickListener(v -> handleRemoveRelation("Đã từ chối lời mời"));
        } else if (currentRelationshipStatus == FriendItem.RelationshipStatus.ACCEPTED) {
            binding.btnPrimaryAction.setText("Bạn bè");
            binding.btnSecondaryAction.setText("Hủy kết bạn");
            binding.btnPrimaryAction.setVisibility(View.VISIBLE);
            binding.btnSecondaryAction.setVisibility(View.VISIBLE);

            binding.btnPrimaryAction.setOnClickListener(v ->
                    Toast.makeText(this, "Các bạn đang là bạn bè", Toast.LENGTH_SHORT).show()
            );
            binding.btnSecondaryAction.setOnClickListener(v -> handleRemoveRelation("Đã hủy kết bạn"));
        }
    }

    private void updateMessageButton() {
        SessionUser currentUser = mAuth.getCurrentUser();
        boolean isSelf = currentUser != null && currentUser.getUid().equals(targetUid);
        binding.btnMessage.setVisibility(targetProfileExists && !isSelf ? View.VISIBLE : View.GONE);
    }

    private void openChatWithTarget() {
        if (!targetProfileExists || targetUid == null || targetUid.trim().isEmpty()) {
            Toast.makeText(this, "Không thể mở cuộc trò chuyện với hồ sơ này", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.btnMessage.setEnabled(false);
        ChatRepository.getInstance().getOrCreatePrivateChat(targetUid, new ChatRepository.OnChatListener() {
            @Override
            public void onSuccess(com.example.meetomxh.models.Chat chat) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnMessage.setEnabled(true);
                Intent intent = new Intent(ProfileActivity.this, ChatActivity.class);
                intent.putExtra(ChatActivity.EXTRA_CHAT_ID, chat.getChatId());
                intent.putExtra(ChatActivity.EXTRA_OTHER_UID, targetUid);
                startActivity(intent);
            }

            @Override
            public void onError(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnMessage.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleSendRequest() {
        binding.btnPrimaryAction.setEnabled(false);
        friendRepository.sendFriendRequest(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, message, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleAcceptRequest() {
        binding.btnPrimaryAction.setEnabled(false);
        friendRepository.acceptFriendRequest(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, message, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleRemoveRelation(String successToast) {
        binding.btnPrimaryAction.setEnabled(false);
        binding.btnSecondaryAction.setEnabled(false);

        friendRepository.removeFriendRelation(targetUid, new FriendRepository.OnFriendActionListener() {
            @Override
            public void onSuccess(String message) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                binding.btnSecondaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, successToast, Toast.LENGTH_SHORT).show();
                loadRelationshipStatus();
            }

            @Override
            public void onFailure(String error) {
                if (isDestroyed() || isFinishing()) return;
                binding.btnPrimaryAction.setEnabled(true);
                binding.btnSecondaryAction.setEnabled(true);
                Toast.makeText(ProfileActivity.this, error, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
