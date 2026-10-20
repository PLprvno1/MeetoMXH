package com.example.meetomxh.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.meetomxh.ChatActivity;
import com.example.meetomxh.adapters.InboxAdapter;
import com.example.meetomxh.databinding.FragmentInboxBinding;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.models.Inbox;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.ChatRepository;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class InboxFragment extends Fragment {
    private FragmentInboxBinding binding;
    private InboxAdapter adapter;
    private ChatRepository chatRepository;
    private ListenerRegistration chatsRegistration;
    private final Map<String, UserProfile> profiles = new HashMap<>();
    private final Set<String> profilesLoading = new HashSet<>();
    private List<Chat> chats = new ArrayList<>();
    private String currentUid;
    private boolean chatsLoaded;
    private long chatsListenerGeneration;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentInboxBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        chatRepository = ChatRepository.getInstance();
        currentUid = FirebaseAuth.getInstance().getCurrentUser() == null
                ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
        binding.rvInbox.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new InboxAdapter(this::openChat);
        binding.rvInbox.setAdapter(adapter);
        if (currentUid == null || currentUid.trim().isEmpty()) {
            showState("Đăng nhập để xem cuộc trò chuyện.");
        } else {
            showLoading();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (binding == null || chatsRegistration != null || currentUid == null || currentUid.trim().isEmpty()) return;
        long listenerGeneration = ++chatsListenerGeneration;
        chatsRegistration = chatRepository.listenToCurrentUserChats(new ChatRepository.OnChatsListener() {
            @Override
            public void onChats(List<Chat> items) {
                if (!isCurrentChatsListener(listenerGeneration)) return;
                chatsLoaded = true;
                chats = items == null ? new ArrayList<>() : new ArrayList<>(items);
                sortChatsNewestFirst();
                for (Chat chat : chats) {
                    String otherUid = getOtherUid(chat);
                    if (otherUid != null) loadProfileOnce(otherUid);
                }
                renderChats();
            }

            @Override
            public void onError(String error) {
                if (!isCurrentChatsListener(listenerGeneration)) return;
                binding.progressInbox.setVisibility(View.GONE);
                binding.rvInbox.setVisibility(View.GONE);
                binding.tvInboxState.setVisibility(View.VISIBLE);
                binding.tvInboxState.setText(error == null ? "Không thể tải hộp thư." : error);
            }
        });
    }

    @Override
    public void onStop() {
        removeChatsListener();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        removeChatsListener();
        binding = null;
        adapter = null;
        profiles.clear();
        profilesLoading.clear();
        chats.clear();
        chatsLoaded = false;
        super.onDestroyView();
    }

    private void removeChatsListener() {
        chatsListenerGeneration++;
        if (chatsRegistration != null) {
            chatsRegistration.remove();
            chatsRegistration = null;
        }
    }

    private boolean isCurrentChatsListener(long generation) {
        return binding != null && isAdded() && generation == chatsListenerGeneration;
    }

    private void loadProfileOnce(String uid) {
        if (profiles.containsKey(uid) || !profilesLoading.add(uid)) return;
        FirebaseFirestore.getInstance().collection("Users").document(uid).get()
                .addOnSuccessListener(snapshot -> {
                    profilesLoading.remove(uid);
                    if (binding == null || !isAdded()) return;
                    User user = snapshot.exists() ? snapshot.toObject(User.class) : null;
                    String name = user == null ? null : user.getFullName();
                    if (name == null || name.trim().isEmpty()) name = "Người dùng";
                    String avatar = user == null ? null : user.getAvatarUrl();
                    profiles.put(uid, new UserProfile(name, avatar));
                    renderChats();
                })
                .addOnFailureListener(error -> {
                    profilesLoading.remove(uid);
                    if (binding == null || !isAdded()) return;
                    profiles.put(uid, new UserProfile("Người dùng", null));
                    renderChats();
                });
    }

    private void renderChats() {
        if (binding == null || adapter == null) return;
        if (!chatsLoaded) {
            showLoading();
            return;
        }
        if (chats.isEmpty()) {
            adapter.submitList(Collections.emptyList());
            binding.progressInbox.setVisibility(View.GONE);
            binding.rvInbox.setVisibility(View.GONE);
            binding.tvInboxState.setVisibility(View.VISIBLE);
            binding.tvInboxState.setText("Chưa có cuộc trò chuyện nào.\nMở hồ sơ một người để bắt đầu nhắn tin.");
            return;
        }

        List<Inbox> rows = new ArrayList<>();
        for (Chat chat : chats) {
            String otherUid = getOtherUid(chat);
            if (otherUid == null) continue;
            UserProfile profile = profiles.get(otherUid);
            String preview = chat.getLastMessage();
            if (chat.getLastMessageHiddenFor() != null && currentUid != null
                    && chat.getLastMessageHiddenFor().contains(currentUid)) preview = "Tin nhắn đã xóa";
            else if ("image".equals(chat.getLastMessageType())) preview = "Ảnh";
            if (preview == null || preview.trim().isEmpty()) preview = "Chưa có tin nhắn";
            Inbox row = new Inbox(chat.getChatId(), otherUid,
                    profile == null ? "Người dùng" : profile.name,
                    preview,
                    profile == null ? null : profile.avatarUrl,
                    formatTimestamp(chat.getLastMessageAt()));
            row.setUnread(chat.isUnreadFor(currentUid));
            rows.add(row);
        }
        adapter.submitList(rows);
        binding.progressInbox.setVisibility(View.GONE);
        binding.tvInboxState.setVisibility(View.GONE);
        binding.rvInbox.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (rows.isEmpty()) showState("Không thể hiển thị các cuộc trò chuyện.");
    }

    private void sortChatsNewestFirst() {
        chats.sort((first, second) -> {
            Timestamp firstTime = first.getLastMessageAt();
            Timestamp secondTime = second.getLastMessageAt();
            if (firstTime == null && secondTime == null) return 0;
            if (firstTime == null) return 1;
            if (secondTime == null) return -1;
            return secondTime.compareTo(firstTime);
        });
    }

    private String getOtherUid(Chat chat) {
        if (chat == null || chat.getParticipants() == null || chat.getParticipants().size() != 2) return null;
        for (String participant : chat.getParticipants()) {
            if (participant != null && !participant.equals(currentUid)) return participant;
        }
        return null;
    }

    private String formatTimestamp(Timestamp timestamp) {
        if (timestamp == null) return "";
        Date date = timestamp.toDate();
        long elapsed = System.currentTimeMillis() - date.getTime();
        if (elapsed >= 0 && elapsed < 24L * 60L * 60L * 1000L) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(date);
        }
        return new SimpleDateFormat("dd/MM", Locale.getDefault()).format(date);
    }

    private void openChat(Inbox inbox) {
        if (inbox == null || inbox.getId() == null || inbox.getOtherUid() == null) return;
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CHAT_ID, inbox.getId());
        intent.putExtra(ChatActivity.EXTRA_OTHER_UID, inbox.getOtherUid());
        startActivity(intent);
    }

    private void showLoading() {
        if (binding == null) return;
        binding.progressInbox.setVisibility(View.VISIBLE);
        binding.rvInbox.setVisibility(View.GONE);
        binding.tvInboxState.setVisibility(View.GONE);
    }

    private void showState(String text) {
        if (binding == null) return;
        binding.progressInbox.setVisibility(View.GONE);
        binding.rvInbox.setVisibility(View.GONE);
        binding.tvInboxState.setVisibility(View.VISIBLE);
        binding.tvInboxState.setText(text);
    }

    private static class UserProfile {
        final String name;
        final String avatarUrl;
        UserProfile(String name, String avatarUrl) {
            this.name = name;
            this.avatarUrl = avatarUrl;
        }
    }
}
