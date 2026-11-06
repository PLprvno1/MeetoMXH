package com.example.meetomxh.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;

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
import com.example.meetomxh.supabase.ServerTime;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.supabase.Subscription;

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
    private Subscription chatsRegistration;
    private final Map<String, UserProfile> profiles = new HashMap<>();
    private final Set<String> profilesLoading = new HashSet<>();
    private List<Chat> chats = new ArrayList<>();
    private List<Chat> allChats = new ArrayList<>();
    private String currentUid;
    private boolean chatsLoaded;
    private boolean showArchived;
    private long chatsListenerGeneration;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingConversationSearch;
    private long searchGeneration;
    private boolean searchingConversations;

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
        binding.btnClearConversations.setOnClickListener(v->binding.etSearchConversations.setText(""));
        binding.btnCreateChatGroup.setOnClickListener(v -> new android.app.AlertDialog.Builder(requireContext()).setItems(new String[]{"Tin nhắn mới","Tạo nhóm chat","Tin nhắn chờ","Lời mời nhóm chat",showArchived?"Hộp thư đến":"Đã lưu trữ"},(d,i)->{
            if(i==0)PeoplePicker.show(requireActivity(),java.util.Collections.emptySet(),1,false,ids->SocialActions.contact(requireActivity(),ids.get(0)));
            if(i==1)ChatControls.create(requireActivity());
            if(i==2||i==3)startActivity(new Intent(requireContext(),com.example.meetomxh.RequestCenterActivity.class).putExtra("mode",i==2?"message":"group"));
            if(i==4){showArchived=!showArchived;scheduleConversationSearch(binding.etSearchConversations.getText().toString());renderChats();}
        }).show());
        currentUid = SessionManager.getInstance().getCurrentUser() == null
                ? null : SessionManager.getInstance().getCurrentUser().getUid();
        binding.rvInbox.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new InboxAdapter(this::openChat);
        binding.rvInbox.setAdapter(adapter);
        binding.etSearchConversations.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleConversationSearch(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
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
                allChats = items == null ? new ArrayList<>() : new ArrayList<>(items);
                if (!searchingConversations) chats = new ArrayList<>(allChats);
                sortChatsNewestFirst();
                for (Chat chat : allChats) {
                    String otherUid = getOtherUid(chat);
                    if (otherUid != null && !"group".equals(chat.getType())) loadProfileOnce(otherUid);
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
        allChats.clear();
        searchGeneration++;
        if (pendingConversationSearch != null) searchHandler.removeCallbacks(pendingConversationSearch);
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

    private void loadProfileOnce(String uid) {if(profiles.containsKey(uid)||!profilesLoading.add(uid))return;ProfileClient.get(uid,new Api.Callback<com.example.meetomxh.models.User>(){public void success(com.example.meetomxh.models.User user){profilesLoading.remove(uid);if(binding==null)return;profiles.put(uid,new UserProfile(user.getFullName(),user.getAvatarUrl()));renderChats();}public void error(String e){profilesLoading.remove(uid);if(binding!=null){profiles.put(uid,new UserProfile("Người dùng không khả dụng",null));renderChats();}}});}

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
            if(chat.archived!=showArchived)continue;
            String otherUid = getOtherUid(chat);
            if (otherUid == null) continue;
            UserProfile profile = "group".equals(chat.getType()) ? new UserProfile(chat.title,chat.avatar) : profiles.get(otherUid);
            String preview = chat.getLastMessage();
            if (chat.getLastMessageHiddenFor() != null && currentUid != null
                    && chat.getLastMessageHiddenFor().contains(currentUid)) preview = "Tin nhắn đã xóa";
            else if ("image".equals(chat.getLastMessageType())) preview = "Ảnh";
            if (preview == null || preview.trim().isEmpty()) preview = "Chưa có tin nhắn";
            Inbox row = new Inbox(chat.getChatId(), otherUid,
                    profile == null ? "Người dùng" : profile.name,
                    preview,
                    profile == null ? null : profile.avatarUrl,
                    formatServerTime(chat.getLastMessageAt()));
            row.setUnread(chat.isUnreadFor(currentUid));
            row.setMuted(chat.muted);
            rows.add(row);
        }
        adapter.submitList(rows);
        binding.progressInbox.setVisibility(View.GONE);
        binding.tvInboxState.setVisibility(View.GONE);
        binding.rvInbox.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (rows.isEmpty()) showState(showArchived?"Chưa có hội thoại đã lưu trữ.":"Chưa có cuộc trò chuyện trong hộp thư.");
    }

    private void scheduleConversationSearch(String value) {
        if (binding == null) return;
        if (pendingConversationSearch != null) searchHandler.removeCallbacks(pendingConversationSearch);
        String query = value == null ? "" : value.trim();
        long generation = ++searchGeneration;
        if (query.isEmpty()) {
            searchingConversations = false;
            chats = new ArrayList<>(allChats);
            sortChatsNewestFirst(); renderChats();
            return;
        }
        if (query.length() > 80) {
            showState("Tối đa 80 ký tự."); return;
        }
        searchingConversations = true;
        pendingConversationSearch = () -> chatRepository.searchConversations(query, showArchived, new ChatRepository.OnChatsListener() {
            @Override public void onChats(List<Chat> result) {
                if (binding == null || generation != searchGeneration) return;
                chats = result == null ? new ArrayList<>() : new ArrayList<>(result);
                sortChatsNewestFirst();
                for (Chat chat : chats) {
                    String other = getOtherUid(chat);
                    if (other != null && !"group".equals(chat.getType())) loadProfileOnce(other);
                }
                renderChats();
                if (chats.isEmpty()) showState("Không tìm thấy cuộc trò chuyện.");
            }
            @Override public void onError(String error) {
                if (binding == null || generation != searchGeneration) return;
                showState(error == null ? "Không thể tìm kiếm. Thử lại." : error);
            }
        });
        searchHandler.postDelayed(pendingConversationSearch, 250);
    }

    private void sortChatsNewestFirst() {
        chats.sort((first, second) -> {
            ServerTime firstTime = first.getLastMessageAt();
            ServerTime secondTime = second.getLastMessageAt();
            if (firstTime == null && secondTime == null) return 0;
            if (firstTime == null) return 1;
            if (secondTime == null) return -1;
            return secondTime.compareTo(firstTime);
        });
    }

    private String getOtherUid(Chat chat) {
        if(chat!=null && "group".equals(chat.getType())) return chat.getChatId(); if (chat == null || chat.getParticipants() == null || chat.getParticipants().size() != 2) return null;
        for (String participant : chat.getParticipants()) {
            if (participant != null && !participant.equals(currentUid)) return participant;
        }
        return null;
    }

    private String formatServerTime(ServerTime timestamp) {
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
        if(!inbox.getId().equals(inbox.getOtherUid()))intent.putExtra(ChatActivity.EXTRA_OTHER_UID, inbox.getOtherUid());
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
