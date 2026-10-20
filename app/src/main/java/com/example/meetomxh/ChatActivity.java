package com.example.meetomxh;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.example.meetomxh.adapters.ChatMessageAdapter;
import com.example.meetomxh.adapters.ChatSearchResultAdapter;
import com.example.meetomxh.databinding.ActivityChatBinding;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.ChatRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.Timestamp;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

public class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID = "chat_id";
    public static final String EXTRA_OTHER_UID = "other_uid";

    private ActivityChatBinding binding;
    private ChatRepository chatRepository;
    private ListenerRegistration messagesRegistration;
    private ListenerRegistration chatStateRegistration;
    private ChatMessageAdapter adapter;
    private String currentUid;
    private String chatId;
    private String otherUid;
    private boolean started;
    private boolean hasLoadedMessages;
    private boolean sending;
    private boolean imeVisible;
    private String messageToScrollTo;
    private ChatMessage replyTarget;
    private ActivityResultLauncher<PickVisualMediaRequest> imagePickerLauncher;
    private Uri pendingImageUri;
    private UploadTask activeUploadTask;
    private String uploadedImageUrl;
    private boolean uploadingImage;
    private boolean followImageAfterSend;
    private ChatSearchResultAdapter searchResultAdapter;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private long searchGeneration;
    private boolean searchVisible;
    private String messageLoadError;
    private Chat currentChatState;
    private List<ChatMessage> currentMessages = new ArrayList<>();
    private final Set<String> readRequestsInFlight = new HashSet<>();
    private final Set<String> readMessagesAcknowledged = new HashSet<>();
    private boolean readCheckScheduled;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (root, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            boolean wasAtBottom = adapter == null || adapter.getItemCount() == 0
                    || !binding.rvMessages.canScrollVertically(1);

            root.setPadding(systemBars.left, systemBars.top, systemBars.right,
                    Math.max(systemBars.bottom, ime.bottom));
            if (keyboardVisible && !imeVisible && wasAtBottom) {
                root.post(() -> scrollToLatestIfAtBottom());
            }
            imeVisible = keyboardVisible;
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.getRoot());
        chatRepository = ChatRepository.getInstance();
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null || binding == null) return;
            pendingImageUri = uri;
            uploadedImageUrl = null;
            followImageAfterSend = isAtBottom();
            startOrRetryImageSend();
        });
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        currentUid = currentUser == null ? null : currentUser.getUid();
        if (currentUid == null || currentUid.trim().isEmpty()) {
            failAndClose("Bạn cần đăng nhập để mở cuộc trò chuyện.");
            return;
        }

        binding.btnChatBack.setOnClickListener(v -> finish());
        binding.rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatMessageAdapter(currentUid);
        adapter.setInteractionListener(new ChatMessageAdapter.OnMessageInteractionListener() {
            @Override public void onLongPress(ChatMessage message) { showMessageActions(message); }
            @Override public void onReplyTap(String messageId) { scrollToMessage(messageId); }
            @Override public void onImageTap(String imageUrl) {
                Intent intent = new Intent(ChatActivity.this, ChatImageViewerActivity.class);
                intent.putExtra(ChatImageViewerActivity.EXTRA_IMAGE_URL, imageUrl);
                startActivity(intent);
            }
        });
        binding.rvMessages.setAdapter(adapter);
        binding.rvMessages.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView recyclerView,
                                             int dx, int dy) {
                maybeMarkLatestIncomingRead();
            }
        });
        searchResultAdapter = new ChatSearchResultAdapter(this::onSearchResultSelected);
        binding.rvMessageSearchResults.setLayoutManager(new LinearLayoutManager(this));
        binding.rvMessageSearchResults.setAdapter(searchResultAdapter);
        binding.btnCancelReply.setOnClickListener(v -> clearReplyTarget());
        binding.btnAttachImage.setOnClickListener(v -> {
            if (uploadingImage) return;
            if (pendingImageUri != null) startOrRetryImageSend();
            else imagePickerLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
        });
        binding.btnCancelImageUpload.setOnClickListener(v -> {
            if (uploadingImage) return;
            pendingImageUri = null;
            uploadedImageUrl = null;
            binding.layoutImageUploadStatus.setVisibility(View.GONE);
        });
        binding.btnChatSearch.setOnClickListener(v -> openMessageSearch());
        binding.btnCloseSearch.setOnClickListener(v -> closeMessageSearch());
        binding.etSearchMessages.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleLocalMessageSearch(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        binding.btnSendMessage.setOnClickListener(v -> sendCurrentMessage());
        binding.etMessage.setOnEditorActionListener((view, actionId, event) -> {
            boolean sendAction = actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP);
            if (sendAction) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });

        chatId = clean(getIntent().getStringExtra(EXTRA_CHAT_ID));
        otherUid = clean(getIntent().getStringExtra(EXTRA_OTHER_UID));
        View.OnClickListener openProfile = v -> openOtherProfile();
        binding.ivChatAvatar.setOnClickListener(openProfile);
        binding.tvChatTitle.setOnClickListener(openProfile);
        if (chatId != null) {
            if (otherUid != null) loadOtherProfile(otherUid);
            else findOtherParticipant(chatId);
        } else if (otherUid != null && !currentUid.equals(otherUid)) {
            binding.tvChatTitle.setText("Đang mở cuộc trò chuyện…");
            chatRepository.getOrCreatePrivateChat(otherUid, new ChatRepository.OnChatListener() {
                @Override
                public void onSuccess(Chat chat) {
                    if (isFinishing() || isDestroyed()) return;
                    chatId = chat.getChatId();
                    loadOtherProfile(otherUid);
                    attachMessagesListenerIfNeeded();
                    attachChatStateListenerIfNeeded();
                }

                @Override
                public void onError(String error) {
                    if (isFinishing() || isDestroyed()) return;
                    failAndClose(error);
                }
            });
        } else {
            failAndClose("Thiếu thông tin cuộc trò chuyện hoặc người nhận.");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        attachMessagesListenerIfNeeded();
        attachChatStateListenerIfNeeded();
    }

    @Override
    protected void onStop() {
        started = false;
        removeMessagesListener();
        removeChatStateListener();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        removeMessagesListener();
        removeChatStateListener();
        searchGeneration++;
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        if (activeUploadTask != null && activeUploadTask.isInProgress()) activeUploadTask.cancel();
        activeUploadTask = null;
        binding = null;
        super.onDestroy();
    }

    private void attachMessagesListenerIfNeeded() {
        if (!started || binding == null || chatId == null || messagesRegistration != null) return;
        binding.progressMessages.setVisibility(hasLoadedMessages ? View.GONE : View.VISIBLE);
        messagesRegistration = chatRepository.listenToMessages(chatId, new ChatRepository.OnMessagesListener() {
            @Override
            public void onMessages(List<ChatMessage> messages) {
                if (binding == null || !started || isFinishing() || isDestroyed()) return;
                boolean wasAtBottom = isAtBottom();
                currentMessages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
                adapter.submitList(messages);
                messageLoadError = null;
                if (searchVisible) scheduleLocalMessageSearch(binding.etSearchMessages.getText().toString());
                hasLoadedMessages = true;
                binding.progressMessages.setVisibility(View.GONE);
                binding.tvMessagesState.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                if (adapter.getItemCount() == 0) {
                    binding.tvMessagesState.setText("Chưa có tin nhắn. Hãy gửi lời chào đầu tiên.");
                }
                binding.rvMessages.setVisibility(View.VISIBLE);
                int sentPosition = adapter.indexOfMessage(messageToScrollTo);
                if (wasAtBottom || sentPosition >= 0) {
                    binding.rvMessages.post(() -> {
                        if (binding != null && adapter.getItemCount() > 0) {
                            int lastSentPosition = adapter.indexOfMessage(messageToScrollTo);
                            binding.rvMessages.scrollToPosition(lastSentPosition >= 0
                                    ? lastSentPosition : adapter.getItemCount() - 1);
                        }
                    });
                    if (sentPosition >= 0) messageToScrollTo = null;
                }
                binding.rvMessages.post(ChatActivity.this::maybeMarkLatestIncomingRead);
            }

            @Override
            public void onError(String error) {
                if (binding == null || !started || isFinishing() || isDestroyed()) return;
                binding.progressMessages.setVisibility(View.GONE);
                if (searchVisible) {
                    messageLoadError = error;
                    scheduleLocalMessageSearch(binding.etSearchMessages.getText().toString());
                }
                if (adapter.getItemCount() == 0) {
                    binding.tvMessagesState.setVisibility(View.VISIBLE);
                    binding.tvMessagesState.setText(error == null ? "Không thể tải tin nhắn." : error);
                } else {
                    Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void removeMessagesListener() {
        if (messagesRegistration != null) {
            messagesRegistration.remove();
            messagesRegistration = null;
        }
    }

    private void attachChatStateListenerIfNeeded() {
        if (!started || binding == null || chatId == null || chatStateRegistration != null) return;
        String listeningChatId = chatId;
        chatStateRegistration = chatRepository.listenToChatState(listeningChatId,
                new ChatRepository.OnChatStateListener() {
                    @Override public void onState(Chat chat) {
                        if (binding == null || !started || !listeningChatId.equals(chatId)) return;
                        currentChatState = chat;
                        String other = otherUid;
                        if (other == null && chat.getParticipants() != null) {
                            for (String participant : chat.getParticipants())
                                if (participant != null && !participant.equals(currentUid)) other = participant;
                        }
                        adapter.setOtherParticipantLastReadAt(other == null ? null : chat.getLastReadAt(other));
                        maybeMarkLatestIncomingRead();
                    }

                    @Override public void onError(String error) {
                        if (binding != null && started && !isFinishing() && !isDestroyed())
                            android.util.Log.w("ChatActivity", "Unable to observe chat state: " + error);
                    }
                });
    }

    private void removeChatStateListener() {
        if (chatStateRegistration != null) {
            chatStateRegistration.remove();
            chatStateRegistration = null;
        }
        currentChatState = null;
    }

    private void maybeMarkLatestIncomingRead() {
        if (!started || binding == null || chatId == null || currentUid == null
                || currentChatState == null || readCheckScheduled || currentMessages.isEmpty()) return;
        ChatMessage latestIncoming = null;
        String markedIncomingId = currentChatState == null ? null
                : currentChatState.getLastIncomingMessageId(currentUid);
        if (markedIncomingId != null) {
            for (ChatMessage message : currentMessages) {
                if (message != null && markedIncomingId.equals(message.getMessageId())
                        && !currentUid.equals(message.getSenderId())) {
                    latestIncoming = message;
                    break;
                }
            }
            // The last incoming message may be outside the currently loaded page.
            if (latestIncoming == null) return;
        } else {
            for (ChatMessage message : currentMessages) {
                if (message == null || message.getMessageId() == null || currentUid.equals(message.getSenderId())) continue;
                if (latestIncoming == null || (message.getCreatedAt() != null
                        && (latestIncoming.getCreatedAt() == null
                        || message.getCreatedAt().compareTo(latestIncoming.getCreatedAt()) > 0))) {
                    latestIncoming = message;
                }
            }
        }
        if (latestIncoming == null) return;
        String messageId = latestIncoming.getMessageId();
        String lastReadMessageId = currentChatState.getLastReadMessageIdByUid() == null ? null
                : currentChatState.getLastReadMessageIdByUid().get(currentUid);
        if (messageId.equals(lastReadMessageId)) return;
        Timestamp incomingAt = currentChatState.getLastIncomingMessageAtByUid() == null ? null
                : currentChatState.getLastIncomingMessageAtByUid().get(currentUid);
        Timestamp lastReadAt = currentChatState.getLastReadAt(currentUid);
        if (incomingAt != null && lastReadAt != null && incomingAt.compareTo(lastReadAt) <= 0) return;
        if (readRequestsInFlight.contains(messageId) || readMessagesAcknowledged.contains(messageId)) return;
        readCheckScheduled = true;
        String expectedChatId = chatId;
        binding.rvMessages.post(() -> {
            readCheckScheduled = false;
            if (binding == null || !started || !expectedChatId.equals(chatId)) return;
            int position = adapter.indexOfMessage(messageId);
            if (position < 0) return;
            androidx.recyclerview.widget.RecyclerView.ViewHolder holder =
                    binding.rvMessages.findViewHolderForAdapterPosition(position);
            if (holder == null || !holder.itemView.isShown()) return;
            readRequestsInFlight.add(messageId);
            chatRepository.markChatRead(expectedChatId, messageId, new ChatRepository.OnReadUpdateListener() {
                @Override public void onComplete(boolean updated) {
                    readRequestsInFlight.remove(messageId);
                    if (updated) readMessagesAcknowledged.add(messageId);
                }

                @Override public void onError(String error) {
                    readRequestsInFlight.remove(messageId);
                    if (binding != null && started && expectedChatId.equals(chatId))
                        android.util.Log.w("ChatActivity", "Unable to mark chat read: " + error);
                }
            });
        });
    }

    private void findOtherParticipant(String id) {
        FirebaseFirestore.getInstance().collection("Chats").document(id).get()
                .addOnSuccessListener(snapshot -> {
                    if (isFinishing() || isDestroyed()) return;
                    Chat chat = Chat.fromSnapshot(snapshot);
                    if (chat == null || chat.getParticipants() == null || chat.getParticipants().size() != 2
                            || !chat.getParticipants().contains(currentUid)) {
                        failAndClose("Không tìm thấy cuộc trò chuyện hoặc bạn không phải thành viên.");
                        return;
                    }
                    for (String participant : chat.getParticipants()) {
                        if (!currentUid.equals(participant)) {
                            otherUid = participant;
                            loadOtherProfile(otherUid);
                            return;
                        }
                    }
                    failAndClose("Không tìm thấy người tham gia còn lại.");
                })
                .addOnFailureListener(error -> {
                    if (!isFinishing() && !isDestroyed())
                        failAndClose("Không thể tải cuộc trò chuyện: " + error.getLocalizedMessage());
                });
    }

    private void loadOtherProfile(String uid) {
        if (uid == null || uid.trim().isEmpty() || currentUid.equals(uid)) {
            failAndClose("UID người trò chuyện không hợp lệ.");
            return;
        }
        otherUid = uid;
        binding.tvChatTitle.setText("Người dùng");
        binding.ivChatAvatar.setImageResource(R.drawable.ic_home);
        FirebaseFirestore.getInstance().collection("Users").document(uid).get()
                .addOnSuccessListener(snapshot -> {
                    if (binding == null || isFinishing() || isDestroyed()) return;
                    User user = snapshot.exists() ? snapshot.toObject(User.class) : null;
                    String name = user == null ? null : user.getFullName();
                    if (name == null || name.trim().isEmpty()) name = "Người dùng";
                    binding.tvChatTitle.setText(name);
                    String avatarUrl = user == null ? null : user.getAvatarUrl();
                    if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
                        Glide.with(this).clear(binding.ivChatAvatar);
                        binding.ivChatAvatar.setImageResource(R.drawable.ic_home);
                    } else {
                        Glide.with(this).load(avatarUrl)
                                .placeholder(R.drawable.ic_home)
                                .error(R.drawable.ic_home)
                                .into(binding.ivChatAvatar);
                    }
                })
                .addOnFailureListener(error -> {
                    if (binding == null || isFinishing() || isDestroyed()) return;
                    binding.tvChatTitle.setText("Người dùng");
                    binding.ivChatAvatar.setImageResource(R.drawable.ic_home);
                });
    }

    private void sendCurrentMessage() {
        if (sending || binding == null) return;
        String text = binding.etMessage.getText() == null ? "" : binding.etMessage.getText().toString();
        if (text.trim().isEmpty()) {
            binding.etMessage.setError("Nhập nội dung tin nhắn.");
            return;
        }
        if (chatId == null) {
            Toast.makeText(this, "Cuộc trò chuyện chưa sẵn sàng.", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean shouldFollowSentMessage = isAtBottom();
        sending = true;
        updateSendingState(true);
        ChatRepository.ReplyData reply = replyTarget == null ? null : new ChatRepository.ReplyData(
                replyTarget.getMessageId(), replyTarget.getSenderId(), replyTarget.getDisplayText());
        chatRepository.sendTextMessage(chatId, text, reply, new ChatRepository.OnMessageSentListener() {
            @Override
            public void onSuccess(String messageId) {
                if (binding == null || isFinishing() || isDestroyed()) return;
                if (binding.etMessage.getText() != null && text.equals(binding.etMessage.getText().toString())) {
                    binding.etMessage.setText("");
                    clearReplyTarget();
                }
                sending = false;
                updateSendingState(false);
                if (shouldFollowSentMessage) {
                    messageToScrollTo = messageId;
                    int sentPosition = adapter.indexOfMessage(messageId);
                    if (sentPosition >= 0) {
                        binding.rvMessages.post(() -> {
                            if (binding != null) {
                                int currentPosition = adapter.indexOfMessage(messageId);
                                if (currentPosition >= 0) binding.rvMessages.scrollToPosition(currentPosition);
                            }
                        });
                        messageToScrollTo = null;
                    }
                }
            }

            @Override
            public void onError(String error) {
                if (binding == null || isFinishing() || isDestroyed()) return;
                sending = false;
                updateSendingState(false);
                Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startOrRetryImageSend() {
        if (binding == null || uploadingImage || chatId == null) return;
        if (uploadedImageUrl != null) {
            sendUploadedImageMessage();
            return;
        }
        if (pendingImageUri == null) return;
        String mimeType = getContentResolver().getType(pendingImageUri);
        if (mimeType == null || !mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            showImageFailure("The selected file is not a supported image.");
            return;
        }

        uploadingImage = true;
        setImageUploadStatus("Uploading image…", true);
        try {
            String fileName = UUID.randomUUID().toString();
            StorageReference imageRef = FirebaseStorage.getInstance().getReference()
                    .child("Chats").child(chatId).child("Images").child(currentUid).child(fileName);
            StorageMetadata metadata = new StorageMetadata.Builder().setContentType(mimeType).build();
            activeUploadTask = imageRef.putFile(pendingImageUri, metadata);
            activeUploadTask.addOnSuccessListener(taskSnapshot -> {
                activeUploadTask = null;
                if (!canUpdateImageUi()) return;
                imageRef.getDownloadUrl()
                        .addOnSuccessListener(downloadUri -> {
                            if (!canUpdateImageUi()) return;
                            uploadedImageUrl = downloadUri == null ? null : downloadUri.toString();
                            if (uploadedImageUrl == null || !uploadedImageUrl.startsWith("https://")) {
                                showImageFailure("Image upload completed without a valid download URL.");
                            } else {
                                sendUploadedImageMessage();
                            }
                        })
                        .addOnFailureListener(error -> showImageFailure("Could not get image URL: " + error.getLocalizedMessage()));
            }).addOnFailureListener(error -> {
                activeUploadTask = null;
                showImageFailure("Image upload failed: " + error.getLocalizedMessage());
            });
        } catch (Exception error) {
            activeUploadTask = null;
            showImageFailure("Could not start image upload: " + error.getLocalizedMessage());
        }
    }

    private void sendUploadedImageMessage() {
        if (binding == null || uploadingImage || uploadedImageUrl == null || chatId == null) return;
        if (!uploadedImageUrl.startsWith("https://")) {
            showImageFailure("Image URL is invalid.");
            return;
        }
        uploadingImage = true;
        setImageUploadStatus("Sending image…", true);
        chatRepository.sendImageMessage(chatId, uploadedImageUrl, new ChatRepository.OnMessageSentListener() {
            @Override public void onSuccess(String messageId) {
                if (!canUpdateImageUi()) return;
                uploadingImage = false;
                pendingImageUri = null;
                uploadedImageUrl = null;
                activeUploadTask = null;
                binding.layoutImageUploadStatus.setVisibility(View.GONE);
                binding.btnAttachImage.setEnabled(true);
                if (followImageAfterSend) {
                    messageToScrollTo = messageId;
                    int position = adapter.indexOfMessage(messageId);
                    if (position >= 0) {
                        binding.rvMessages.post(() -> {
                            if (binding != null) {
                                int currentPosition = adapter.indexOfMessage(messageId);
                                if (currentPosition >= 0) binding.rvMessages.scrollToPosition(currentPosition);
                            }
                        });
                        messageToScrollTo = null;
                    }
                }
            }
            @Override public void onError(String error) {
                showImageFailure("Could not send image message: " + error);
            }
        });
    }

    private void showImageFailure(String message) {
        if (!canUpdateImageUi()) return;
        uploadingImage = false;
        binding.progressImageUpload.setVisibility(View.GONE);
        binding.layoutImageUploadStatus.setVisibility(View.VISIBLE);
        binding.tvImageUploadStatus.setText(message + " Tap attach to retry.");
        binding.btnAttachImage.setEnabled(true);
        binding.btnCancelImageUpload.setEnabled(true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void setImageUploadStatus(String message, boolean busy) {
        if (binding == null) return;
        binding.layoutImageUploadStatus.setVisibility(View.VISIBLE);
        binding.tvImageUploadStatus.setText(message);
        binding.progressImageUpload.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnAttachImage.setEnabled(!busy);
        binding.btnCancelImageUpload.setEnabled(!busy);
    }

    private boolean canUpdateImageUi() {
        return binding != null && !isFinishing() && !isDestroyed();
    }

    private void openMessageSearch() {
        if (binding == null) return;
        searchVisible = true;
        binding.layoutMessageSearch.setVisibility(View.VISIBLE);
        binding.etSearchMessages.requestFocus();
        binding.etSearchMessages.post(() -> {
            if (binding == null) return;
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(binding.etSearchMessages, InputMethodManager.SHOW_IMPLICIT);
        });
        scheduleLocalMessageSearch(binding.etSearchMessages.getText().toString());
    }

    private void closeMessageSearch() {
        if (binding == null) return;
        searchVisible = false;
        searchGeneration++;
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(binding.etSearchMessages.getWindowToken(), 0);
        binding.layoutMessageSearch.setVisibility(View.GONE);
        binding.etSearchMessages.setText("");
        binding.etSearchMessages.clearFocus();
    }

    private void scheduleLocalMessageSearch(String query) {
        if (binding == null || !searchVisible) return;
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        long generation = ++searchGeneration;
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) {
            binding.progressMessageSearch.setVisibility(View.GONE);
            binding.tvMessageSearchState.setVisibility(View.VISIBLE);
            binding.tvMessageSearchState.setText(messageLoadError == null
                    ? "Searches text in the latest 50 messages loaded for this chat."
                    : "Chat refresh failed: " + messageLoadError);
            binding.rvMessageSearchResults.setVisibility(View.GONE);
            searchResultAdapter.submit(new ArrayList<>());
            return;
        }
        binding.progressMessageSearch.setVisibility(View.VISIBLE);
        binding.tvMessageSearchState.setVisibility(View.VISIBLE);
        binding.tvMessageSearchState.setText("Searching loaded messages…");
        pendingSearch = () -> {
            if (binding == null || !searchVisible || generation != searchGeneration || adapter == null) return;
            List<ChatMessage> results = adapter.searchLoadedTextMessages(needle);
            binding.progressMessageSearch.setVisibility(View.GONE);
            searchResultAdapter.submit(results);
            if (results.isEmpty()) {
                binding.tvMessageSearchState.setText(messageLoadError == null
                        ? "No text matches in the loaded chat messages."
                        : "No match in loaded messages. Chat refresh failed: " + messageLoadError);
                binding.rvMessageSearchResults.setVisibility(View.GONE);
            } else {
                binding.tvMessageSearchState.setText(results.size() + " result(s) · latest 50 loaded messages · text only"
                        + (messageLoadError == null ? "" : " · refresh failed"));
                binding.rvMessageSearchResults.setVisibility(View.VISIBLE);
            }
        };
        searchHandler.postDelayed(pendingSearch, 180);
    }

    private void onSearchResultSelected(ChatMessage message) {
        if (message != null) scrollToMessage(message.getMessageId());
    }

    private void showMessageActions(ChatMessage message) {
        if (binding == null || message == null || message.getMessageId() == null
                || message.isDeletedFor(currentUid)) return;
        List<String> labels = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        labels.add("Trả lời"); actions.add(0);
        if (!message.isRecalled() && message.getText() != null && !message.getText().trim().isEmpty()) {
            labels.add("Sao chép"); actions.add(1);
        }
        boolean mine = currentUid.equals(message.getSenderId());
        if (mine && !message.isRecalled()) { labels.add("Thu hồi tin nhắn"); actions.add(2); }
        labels.add("Xóa khỏi phía tôi"); actions.add(3);
        String[] options = labels.toArray(new String[0]);
        new AlertDialog.Builder(this).setItems(options, (dialog, index) -> {
            switch (actions.get(index)) {
                case 0: setReplyTarget(message); break;
                case 1: copyMessage(message); break;
                case 2: confirmMessageAction(message, true); break;
                case 3: confirmMessageAction(message, false); break;
            }
        }).show();
    }

    private void copyMessage(ChatMessage message) {
        if (message == null || message.isRecalled() || message.getText() == null || message.getText().trim().isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Tin nhắn", message.getText()));
            Toast.makeText(this, "Đã sao chép tin nhắn.", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmMessageAction(ChatMessage message, boolean recall) {
        String title = recall ? "Thu hồi tin nhắn?" : "Xóa tin nhắn phía bạn?";
        String body = recall ? "Tin nhắn sẽ bị thu hồi với cả hai bên." : "Tin nhắn chỉ bị ẩn trong cuộc trò chuyện của bạn.";
        new AlertDialog.Builder(this).setTitle(title).setMessage(body)
                .setNegativeButton("Hủy", null)
                .setPositiveButton(recall ? "Thu hồi" : "Xóa", (dialog, which) -> performMessageAction(message, recall)).show();
    }

    private void performMessageAction(ChatMessage message, boolean recall) {
        if (binding == null || message == null || message.getMessageId() == null || chatId == null) return;
        adapter.setMessageBusy(message.getMessageId(), true);
        ChatRepository.OnMessageActionListener callback = new ChatRepository.OnMessageActionListener() {
            @Override public void onSuccess() {
                if (binding == null || isFinishing() || isDestroyed()) return;
                adapter.setMessageBusy(message.getMessageId(), false);
                Toast.makeText(ChatActivity.this, recall ? "Đã thu hồi tin nhắn." : "Đã xóa tin nhắn phía bạn.", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String error) {
                if (binding == null || isFinishing() || isDestroyed()) return;
                adapter.setMessageBusy(message.getMessageId(), false);
                Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show();
            }
        };
        if (recall) chatRepository.recallMessage(chatId, message, callback);
        else chatRepository.deleteMessageForMe(chatId, message, callback);
    }

    private void setReplyTarget(ChatMessage message) {
        if (binding == null || message == null) return;
        replyTarget = message;
        binding.tvReplyComposer.setText("Trả lời: " + message.getDisplayText());
        binding.layoutReplyComposer.setVisibility(View.VISIBLE);
        binding.etMessage.requestFocus();
    }

    private void clearReplyTarget() {
        replyTarget = null;
        if (binding != null) {
            binding.layoutReplyComposer.setVisibility(View.GONE);
            binding.tvReplyComposer.setText("");
        }
    }

    private void scrollToMessage(String messageId) {
        if (binding == null || adapter == null) return;
        int position = adapter.indexOfMessage(messageId);
        if (position >= 0) binding.rvMessages.smoothScrollToPosition(position);
        else Toast.makeText(this, "Tin nhắn gốc không còn trong danh sách hiện tại.", Toast.LENGTH_SHORT).show();
    }

    private void updateSendingState(boolean isSending) {
        binding.btnSendMessage.setEnabled(!isSending);
        binding.etMessage.setEnabled(!isSending);
        binding.btnSendMessage.setText(isSending ? "" : "Gửi");
        binding.progressSending.setVisibility(isSending ? View.VISIBLE : View.GONE);
    }

    private void scrollToLatestIfAtBottom() {
        if (binding == null || adapter == null || adapter.getItemCount() == 0) return;
        binding.rvMessages.scrollToPosition(adapter.getItemCount() - 1);
    }

    private boolean isAtBottom() {
        return binding == null || adapter == null || adapter.getItemCount() == 0
                || !binding.rvMessages.canScrollVertically(1);
    }

    private void openOtherProfile() {
        if (binding == null || otherUid == null || otherUid.trim().isEmpty()
                || otherUid.equals(currentUid)) return;
        Intent intent = new Intent(this, ProfileActivity.class);
        intent.putExtra("target_uid", otherUid);
        startActivity(intent);
    }

    private void failAndClose(String error) {
        Toast.makeText(this, error == null ? "Không thể mở cuộc trò chuyện." : error, Toast.LENGTH_LONG).show();
        finish();
    }

    private static String clean(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
