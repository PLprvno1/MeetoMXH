package com.example.meetomxh;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.Manifest;
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
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.bumptech.glide.Glide;
import com.example.meetomxh.adapters.ChatMessageAdapter;
import com.example.meetomxh.adapters.ChatSearchResultAdapter;
import com.example.meetomxh.databinding.ActivityChatBinding;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.models.User;
import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.SessionUser;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.supabase.Subscription;
import com.example.meetomxh.supabase.ServerTime;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;

public class ChatActivity extends AppCompatActivity {
    public static final String EXTRA_CHAT_ID = "chat_id";
    public static final String EXTRA_OTHER_UID = "other_uid";
    public static final String EXTRA_JUMP_MESSAGE_ID = "jump_message_id";
    public static final String EXTRA_JUMP_MESSAGE_SEQ = "jump_message_seq";

    private ActivityChatBinding binding;
    private ChatRepository chatRepository;
    private int historyLimit=50; private Subscription messagesRegistration;
    private boolean loadingOlder,hasMoreOlder=true,preserveAnchor;
    private String anchorMessageId;
    private int anchorOffset;
    private Subscription chatStateRegistration;
    private ChatMessageAdapter adapter;
    private String currentUid;
    private String chatId;
    private String failedRequestId;
    private boolean failedVoiceMatchesPreview;
    private String otherUid;
    private boolean started;
    private boolean hasLoadedMessages;
    private boolean sending;
    private boolean imeVisible;
    private String messageToScrollTo;
    private String pendingReplyScrollId;
    private long pendingReplyScrollSeq;
    private ChatMessage replyTarget;
    private ChatMessage editingTarget;
    private ActivityResultLauncher<PickVisualMediaRequest> imagePickerLauncher;
    private final ActivityResultLauncher<Intent> detailsLauncher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(result.getResultCode()==RESULT_OK)finish();});
    private Uri pendingImageUri;
    private SupabaseStorage.Transfer activeUploadCall;
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
    private ActivityResultLauncher<String> audioPermissionLauncher;
    private MediaRecorder recorder;
    private MediaPlayer previewPlayer;
    private MediaPlayer voicePlayer;
    private AudioManager audioManager;
    private File voiceFile;
    private String uploadedVoiceReference;
    private boolean recordingVoice, voiceLocked, uploadingVoice, previewPrepared, remoteVoicePrepared;
    private long voiceRecordingStartedAt;
    private String playingVoiceMessageId;
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private final Runnable voiceTick = new Runnable() {
        @Override public void run() {
            if (previewPlayer != null && previewPlayer.isPlaying() && binding != null) {
                int duration = Math.max(1, previewPlayer.getDuration());
                binding.seekVoicePreview.setProgress(previewPlayer.getCurrentPosition() * 1000 / duration);
                binding.tvVoicePreviewDuration.setText(formatDuration(previewPlayer.getCurrentPosition()) + " / " + formatDuration(duration));
                voiceHandler.postDelayed(this, 200);
            } else if (voicePlayer != null && voicePlayer.isPlaying() && adapter != null) {
                int duration = Math.max(1, voicePlayer.getDuration());
                adapter.setVoicePlayback(playingVoiceMessageId, voicePlayer.getCurrentPosition(), duration, true);
                voiceHandler.postDelayed(this, 200);
            }
        }
    };

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
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) Toast.makeText(this, "Giữ nút micro để ghi âm.", Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, "Cần quyền micro để ghi tin nhắn thoại.", Toast.LENGTH_LONG).show();
        });
        imagePickerLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri == null || binding == null) return;
            pendingImageUri = uri;
            uploadedImageUrl = null;
            previewSelectedMedia(uri);
        });
        SessionUser currentUser = SessionManager.getInstance().getCurrentUser();
        currentUid = currentUser == null ? null : currentUser.getUid();
        if (currentUid == null || currentUid.trim().isEmpty()) {
            failAndClose("Bạn cần đăng nhập để mở cuộc trò chuyện.");
            return;
        }

        binding.btnChatBack.setOnClickListener(v -> finish());
        binding.tvFailedMessageRetry.setOnClickListener(v->retryFailedMessage());
        binding.rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatMessageAdapter(currentUid);
        adapter.setInteractionListener(new ChatMessageAdapter.OnMessageInteractionListener() {
            @Override public void onLongPress(ChatMessage message) { showMessageActions(message); }
            @Override public void onReplyTap(String messageId, long seq) { scrollToMessage(messageId, seq); }
            @Override public void onImageTap(String imageUrl) {
                Intent intent = new Intent(ChatActivity.this, ChatImageViewerActivity.class);
                intent.putExtra(ChatImageViewerActivity.EXTRA_IMAGE_URL, imageUrl);
                startActivity(intent);
            }
            @Override public void onVoiceTap(ChatMessage message) { playVoiceMessage(message); }
        });
        binding.rvMessages.setAdapter(adapter);
        binding.rvMessages.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView recyclerView,
                                             int dx, int dy) {
                maybeMarkLatestIncomingRead();
                androidx.recyclerview.widget.LinearLayoutManager lm=(androidx.recyclerview.widget.LinearLayoutManager)recyclerView.getLayoutManager();
                if(dy<0&&lm!=null&&lm.findFirstVisibleItemPosition()<=2)loadOlderMessages();
            }
        });
        binding.swipeChatRefresh.setOnRefreshListener(()->{
            if(adapter!=null&&adapter.getItemCount()>0){androidx.recyclerview.widget.LinearLayoutManager lm=(androidx.recyclerview.widget.LinearLayoutManager)binding.rvMessages.getLayoutManager();int pos=lm==null?0:lm.findFirstVisibleItemPosition();if(pos>=0){anchorMessageId=adapter.messageIdAt(pos);android.view.View child=lm.findViewByPosition(pos);anchorOffset=child==null?0:child.getTop();preserveAnchor=true;}}
            removeMessagesListener();attachMessagesListenerIfNeeded();
        });
        searchResultAdapter = new ChatSearchResultAdapter(this::onSearchResultSelected);
        binding.rvMessageSearchResults.setLayoutManager(new LinearLayoutManager(this));
        binding.rvMessageSearchResults.setAdapter(searchResultAdapter);
        binding.btnCancelReply.setOnClickListener(v -> clearReplyTarget());
        binding.btnAttachImage.setOnClickListener(v -> {
            if (uploadingImage) return;
            if (pendingImageUri != null) startOrRetryImageSend();
            else imagePickerLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE).build());
        });
        binding.btnCancelImageUpload.setOnClickListener(v -> {
            if (uploadingImage && activeUploadCall != null) {
                activeUploadCall.cancel();
                activeUploadCall = null;
                uploadingImage = false;
            }
            pendingImageUri = null;
            uploadedImageUrl = null;
            binding.layoutImageUploadStatus.setVisibility(View.GONE);
            binding.btnAttachImage.setEnabled(true);
        });
        binding.btnChatSearch.setOnClickListener(v -> openMessageSearch());
        binding.btnCloseSearch.setOnClickListener(v -> closeMessageSearch());
        binding.etSearchMessages.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleLocalMessageSearch(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        binding.btnSendMessage.setOnClickListener(v -> sendCurrentMessage());
        binding.btnVoiceRecord.setVisibility(View.GONE);
        binding.btnVoiceRecord.setEnabled(false);
        binding.btnVoiceStop.setOnClickListener(v -> stopVoiceRecording());
        binding.btnVoiceCancel.setOnClickListener(v -> discardVoiceRecording());
        binding.btnVoiceDiscard.setOnClickListener(v -> discardVoicePreview());
        binding.btnVoicePreviewPlay.setOnClickListener(v -> toggleVoicePreview());
        binding.btnVoiceSend.setOnClickListener(v -> sendVoicePreview());
        binding.seekVoicePreview.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && previewPlayer != null && previewPlayer.getDuration() > 0)
                    binding.tvVoicePreviewDuration.setText(formatDuration(previewPlayer.getDuration() * progress / 1000)
                            + " / " + formatDuration(previewPlayer.getDuration()));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                if (previewPlayer != null && previewPlayer.getDuration() > 0)
                    previewPlayer.seekTo(previewPlayer.getDuration() * seekBar.getProgress() / 1000);
            }
        });
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
        pendingReplyScrollId = getIntent().getStringExtra(EXTRA_JUMP_MESSAGE_ID);
        pendingReplyScrollSeq = getIntent().getLongExtra(EXTRA_JUMP_MESSAGE_SEQ, 0);
        otherUid = clean(getIntent().getStringExtra(EXTRA_OTHER_UID));
        View.OnClickListener openDetails = v -> openConversationDetails();
        binding.ivChatAvatar.setOnClickListener(openDetails);
        binding.tvChatTitle.setOnClickListener(openDetails);
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
        showFailedOutbox();
    }

    @Override
    protected void onStop() {
        started = false;
        removeMessagesListener();
        removeChatStateListener();
        if (recordingVoice) discardVoiceRecording();
        stopAllVoicePlayback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        removeMessagesListener();
        removeChatStateListener();
        searchGeneration++;
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        if (activeUploadCall != null) activeUploadCall.cancel();
        stopAllVoicePlayback();
        releaseRecorder();
        if (voiceFile != null && voiceFile.exists()) voiceFile.delete();
        binding = null;
        super.onDestroy();
    }

    private void attachMessagesListenerIfNeeded() {
        if (!started || binding == null || chatId == null || messagesRegistration != null) return;
        binding.progressMessages.setVisibility(hasLoadedMessages ? View.GONE : View.VISIBLE);
        messagesRegistration = chatRepository.listenToMessages(chatId, historyLimit, new ChatRepository.OnMessagesListener() {
            @Override
            public void onMessages(List<ChatMessage> messages) {
                if (binding == null || !started || isFinishing() || isDestroyed()) return;
                boolean wasAtBottom = isAtBottom();
                String restoreId=preserveAnchor?anchorMessageId:null; int restoreOffset=anchorOffset; preserveAnchor=false;
                currentMessages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
                if (playingVoiceMessageId != null && currentMessages.stream().noneMatch(m ->
                        m != null && playingVoiceMessageId.equals(m.getMessageId()) && !m.isRecalled()))
                    stopVoicePlayback();
                adapter.submitList(messages);
                hasMoreOlder=currentMessages.size()>=historyLimit;loadingOlder=false;binding.progressOlderMessages.setVisibility(View.GONE);binding.swipeChatRefresh.setRefreshing(false);
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
                boolean handledReplyJump = false;
                if (pendingReplyScrollId != null) {
                    int replyPosition = adapter.indexOfMessage(pendingReplyScrollId);
                    if (replyPosition >= 0) {
                        String targetId = pendingReplyScrollId;
                        pendingReplyScrollId = null; pendingReplyScrollSeq = 0;
                        handledReplyJump = true;
                        binding.rvMessages.post(() -> {
                            if (binding != null) {
                                int position = adapter.indexOfMessage(targetId);
                                if (position >= 0) {binding.rvMessages.smoothScrollToPosition(position);binding.rvMessages.postDelayed(()->highlightMessage(targetId),250);}
                            }
                        });
                    } else if (pendingReplyScrollSeq > 0 && !currentMessages.isEmpty()
                            && currentMessages.get(0).seq > pendingReplyScrollSeq
                            && currentMessages.size() >= historyLimit && historyLimit < 5000) {
                        historyLimit += 50; loadingOlder = true;
                        binding.progressOlderMessages.setVisibility(View.VISIBLE);
                        removeMessagesListener(); attachMessagesListenerIfNeeded();
                        return;
                    } else {
                        pendingReplyScrollId = null; pendingReplyScrollSeq = 0;
                        Toast.makeText(ChatActivity.this, "Tin nhắn gốc không còn khả dụng.", Toast.LENGTH_SHORT).show();
                    }
                }
                if (!handledReplyJump && restoreId!=null) {
                    binding.rvMessages.post(()->{if(binding==null)return;int p=adapter.indexOfMessage(restoreId);if(p>=0)((androidx.recyclerview.widget.LinearLayoutManager)binding.rvMessages.getLayoutManager()).scrollToPositionWithOffset(p,restoreOffset);});
                } else if (!handledReplyJump && (wasAtBottom || sentPosition >= 0)) {
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
                loadingOlder=false;binding.progressOlderMessages.setVisibility(View.GONE);binding.swipeChatRefresh.setRefreshing(false);
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

    private void loadOlderMessages(){
        if(!started||binding==null||loadingOlder||!hasMoreOlder||adapter==null||adapter.getItemCount()==0)return;
        androidx.recyclerview.widget.LinearLayoutManager lm=(androidx.recyclerview.widget.LinearLayoutManager)binding.rvMessages.getLayoutManager();
        int first=lm==null?0:lm.findFirstVisibleItemPosition();if(first<0)return;
        anchorMessageId=adapter.messageIdAt(first);View child=lm.findViewByPosition(first);anchorOffset=child==null?0:child.getTop();preserveAnchor=true;
        loadingOlder=true;historyLimit+=50;binding.progressOlderMessages.setVisibility(View.VISIBLE);removeMessagesListener();attachMessagesListenerIfNeeded();
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
                        if ("group".equals(chat.getType())) {
                            binding.tvChatTitle.setText(chat.title+" · "+(chat.getParticipants()==null?0:chat.getParticipants().size()));com.example.meetomxh.ui.AvatarViews.load(binding.ivChatAvatar,chat.avatar,"neutral");
                            binding.tvChatTitle.setOnClickListener(v -> openConversationDetails());
                        }
                        updateComposerPermissions();
                        String other = otherUid;
                        if (other == null && chat.getParticipants() != null) {
                            for (String participant : chat.getParticipants())
                                if (participant != null && !participant.equals(currentUid)) other = participant;
                        }
                        adapter.setOtherParticipantLastReadSeq("private".equals(chat.getType()) && other != null
                                ? chat.getLastReadSeq(other) : null);
                        maybeMarkLatestIncomingRead();
                    }

                    @Override public void onError(String error) {
                        if (binding != null && started && !isFinishing() && !isDestroyed())
                            {currentChatState=null;updateComposerPermissions();binding.tvMessagesState.setText(error);binding.tvMessagesState.setVisibility(View.VISIBLE);}
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

    public void refreshConversationState() {
        if (binding == null || !started) return;
        removeChatStateListener();
        updateComposerPermissions();
        attachChatStateListenerIfNeeded();
    }

    private void updateComposerPermissions() {
        if (binding == null) return;
        boolean active = currentChatState != null && "active".equals(currentChatState.state);
        binding.btnSendMessage.setEnabled(active && !sending);
        binding.etMessage.setEnabled(active && !sending);
        binding.btnAttachImage.setEnabled(active && !uploadingImage);
        binding.btnVoiceRecord.setEnabled(false);
        binding.btnVoiceSend.setEnabled(active && !uploadingVoice);
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
                if (latestIncoming == null || message.seq > latestIncoming.seq) {
                    latestIncoming = message;
                }
            }
        }
        if (latestIncoming == null) return;
        String messageId = latestIncoming.getMessageId();
        String lastReadMessageId = currentChatState.getLastReadMessageIdByUid() == null ? null
                : currentChatState.getLastReadMessageIdByUid().get(currentUid);
        if (messageId.equals(lastReadMessageId)) return;
        if (latestIncoming.seq > 0 && latestIncoming.seq <= currentChatState.lastReadSeq) return;
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

    private void findOtherParticipant(String id) {chatRepository.getChat(id,new ChatRepository.OnChatListener(){public void onSuccess(Chat chat){if(isFinishing()||binding==null)return;if("group".equals(chat.getType())){currentChatState=chat;binding.tvChatTitle.setText(chat.title+" · "+(chat.getParticipants()==null?0:chat.getParticipants().size()));com.example.meetomxh.ui.AvatarViews.load(binding.ivChatAvatar,chat.avatar,"neutral");binding.tvChatTitle.setOnClickListener(v->openConversationDetails());}else for(String participant:chat.getParticipants())if(!participant.equals(currentUid)){otherUid=participant;loadOtherProfile(participant);break;}}public void onError(String e){failAndClose(e);}});}

    private boolean handleVoiceTouch(android.view.MotionEvent event) {
        if (binding == null || uploadingVoice) return true;
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                    return true;
                }
                beginVoiceRecording();
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (recordingVoice) {
                    float density = getResources().getDisplayMetrics().density;
                    if (event.getX() < -80 * density) {
                        binding.tvVoiceRecordingStatus.setText("Thả để hủy");
                        binding.tvVoiceRecordingStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error));
                    } else if (event.getY() < -80 * density) {
                        voiceLocked = true;
                        binding.tvVoiceRecordingStatus.setText("Đã khóa · ghi âm");
                        binding.tvVoiceRecordingStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_blue));
                    } else if (!voiceLocked) {
                        binding.tvVoiceRecordingStatus.setText("Đang ghi · thả để xem trước");
                        binding.tvVoiceRecordingStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error));
                    }
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (recordingVoice) {
                    float density = getResources().getDisplayMetrics().density;
                    if (event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL || event.getX() < -80 * density)
                        discardVoiceRecording();
                    else if (!voiceLocked) stopVoiceRecording();
                }
                return true;
            default: return true;
        }
    }

    private void beginVoiceRecording() {
        if (binding == null || chatId == null || recordingVoice || uploadingVoice) return;
        discardVoicePreview();
        try {
            voiceFile = File.createTempFile("meeto-voice-", ".m4a", getCacheDir());
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(voiceFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recordingVoice = true;
            voiceLocked = false;
            voiceRecordingStartedAt = android.os.SystemClock.elapsedRealtime();
            binding.layoutVoiceRecording.setVisibility(View.VISIBLE);
            binding.layoutVoicePreview.setVisibility(View.GONE);
            binding.tvVoiceRecordingStatus.setText("Đang ghi · thả để xem trước");
            binding.progressVoiceLevel.setProgress(0);
            voiceHandler.post(voiceMeterTick);
        } catch (Exception error) {
            releaseRecorder();
            if (voiceFile != null) voiceFile.delete();
            voiceFile = null;
            Toast.makeText(this, "Không thể bắt đầu ghi âm.", Toast.LENGTH_LONG).show();
        }
    }

    private final Runnable voiceMeterTick = new Runnable() {
        @Override public void run() {
            if (!recordingVoice || binding == null || recorder == null) return;
            long elapsed = android.os.SystemClock.elapsedRealtime() - voiceRecordingStartedAt;
            int amplitude;
            try { amplitude = recorder.getMaxAmplitude(); } catch (RuntimeException ignored) { amplitude = 0; }
            binding.progressVoiceLevel.setProgress(Math.min(32767, amplitude));
            binding.tvVoiceRecordingStatus.setText((voiceLocked ? "Đã khóa · " : "Đang ghi · ")
                    + formatDuration(elapsed));
            if (elapsed >= 119500) { stopVoiceRecording(); return; }
            voiceHandler.postDelayed(this, 120);
        }
    };

    private void stopVoiceRecording() {
        if (!recordingVoice) return;
        recordingVoice = false;
        voiceHandler.removeCallbacks(voiceMeterTick);
        try { recorder.stop(); } catch (RuntimeException ignored) { }
        releaseRecorder();
        voiceLocked = false;
        if (binding != null) binding.layoutVoiceRecording.setVisibility(View.GONE);
        if (voiceFile == null || !voiceFile.isFile() || voiceFile.length() == 0) {
            discardVoicePreview();
            Toast.makeText(this, "Bản ghi quá ngắn, hãy thử lại.", Toast.LENGTH_SHORT).show();
            return;
        }
        showVoicePreview();
    }

    private void discardVoiceRecording() {
        recordingVoice = false;
        voiceLocked = false;
        voiceHandler.removeCallbacks(voiceMeterTick);
        try { if (recorder != null) recorder.stop(); } catch (RuntimeException ignored) { }
        releaseRecorder();
        if (voiceFile != null) voiceFile.delete();
        voiceFile = null;
        if (binding != null) binding.layoutVoiceRecording.setVisibility(View.GONE);
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) { }
            try { recorder.release(); } catch (RuntimeException ignored) { }
            recorder = null;
        }
    }

    private void showVoicePreview() {
        if (binding == null || voiceFile == null) return;
        uploadedVoiceReference = null;
        binding.layoutVoicePreview.setVisibility(View.VISIBLE);
        binding.seekVoicePreview.setProgress(0);
        binding.btnVoiceSend.setEnabled(!uploadingVoice);
        binding.btnVoicePreviewPlay.setEnabled(false);
        binding.tvVoicePreviewDuration.setText(formatDuration(voiceFile.length() / 8));
        releasePreviewPlayer();
        try {
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(voiceFile.getAbsolutePath());
            previewPlayer.setOnPreparedListener(player -> {
                if (binding == null) return;
                previewPrepared = true;
                binding.btnVoicePreviewPlay.setEnabled(true);
                binding.tvVoicePreviewDuration.setText(formatDuration(player.getDuration()));
            });
            previewPlayer.setOnCompletionListener(player -> {
                if (binding != null) {
                    player.seekTo(0);
                    binding.seekVoicePreview.setProgress(0);
                    binding.tvVoicePreviewDuration.setText(formatDuration(player.getDuration()));
                    binding.btnVoicePreviewPlay.setImageResource(R.drawable.ic_play);
                }
            });
            previewPlayer.prepareAsync();
        } catch (IOException | RuntimeException error) {
            releasePreviewPlayer();
            if (binding != null) binding.btnVoicePreviewPlay.setEnabled(false);
            Toast.makeText(this, "Không thể nghe thử bản ghi.", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleVoicePreview() {
        if (previewPlayer == null || !previewPrepared || binding == null) return;
        requestVoiceFocus();
        if (previewPlayer.isPlaying()) {
            previewPlayer.pause();
            binding.btnVoicePreviewPlay.setImageResource(R.drawable.ic_play);
        } else {
            if (voicePlayer != null) stopVoicePlayback();
            previewPlayer.start();
            binding.btnVoicePreviewPlay.setImageResource(R.drawable.ic_pause);
            voiceHandler.removeCallbacks(voiceTick);
            voiceHandler.post(voiceTick);
        }
    }

    private void discardVoicePreview() {
        if (uploadingVoice && activeUploadCall != null) {
            activeUploadCall.cancel();
            activeUploadCall = null;
        }
        uploadingVoice = false;
        releasePreviewPlayer();
        if (voiceFile != null) voiceFile.delete();
        voiceFile = null;
        uploadedVoiceReference = null;
        if (binding != null) binding.layoutVoicePreview.setVisibility(View.GONE);
        updateComposerPermissions();
    }

    private void releasePreviewPlayer() {
        voiceHandler.removeCallbacks(voiceTick);
        if (previewPlayer != null) {
            try { previewPlayer.stop(); } catch (RuntimeException ignored) { }
            previewPlayer.release();
            previewPlayer = null;
        }
        previewPrepared = false;
        if (binding != null) binding.btnVoicePreviewPlay.setEnabled(false);
        if (binding != null) binding.btnVoicePreviewPlay.setImageResource(R.drawable.ic_play);
        abandonVoiceFocus();
    }

    private void sendVoicePreview() {
        if (binding == null || uploadingVoice || voiceFile == null || chatId == null) return;
        if (uploadedVoiceReference != null) { sendUploadedVoice(); return; }
        uploadingVoice = true;
        binding.btnVoiceSend.setEnabled(false);
        binding.btnVoiceDiscard.setEnabled(false);
        binding.btnVoiceRecord.setEnabled(false);
        binding.tvVoicePreviewDuration.setText("Đang tải lên…");
        activeUploadCall = SupabaseStorage.uploadVoice(getContentResolver(), voiceFile, "chats/" + chatId, currentUid,
                new SupabaseStorage.UploadListener() {
                    @Override public void onProgress(int percent) {
                        if (binding != null) binding.tvVoicePreviewDuration.setText("Tải lên " + percent + "%");
                    }
                    @Override public void onSuccess(String reference) {
                        if (binding == null) return;
                        uploadedVoiceReference = reference;
                        activeUploadCall = null;
                        sendUploadedVoice();
                    }
                    @Override public void onFailure(String message) {
                        activeUploadCall = null;
                        uploadingVoice = false;
                        if (binding != null) {
                            binding.btnVoiceSend.setEnabled(true);
                            binding.btnVoiceDiscard.setEnabled(true);
                            updateComposerPermissions();
                            binding.tvVoicePreviewDuration.setText("Tải lên lỗi · nhấn gửi để thử lại");
                        }
                        Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void sendUploadedVoice() {
        if (binding == null || uploadedVoiceReference == null || chatId == null) return;
        uploadingVoice = true;
        if (binding != null) {
            binding.btnVoiceSend.setEnabled(false);
            binding.btnVoiceDiscard.setEnabled(false);
            binding.btnVoiceRecord.setEnabled(false);
        }
        ChatRepository.ReplyData reply = replyTarget == null ? null : new ChatRepository.ReplyData(
                replyTarget.getMessageId(), replyTarget.getSenderId(), replyTarget.getDisplayText());
        chatRepository.sendVoiceMessage(chatId, uploadedVoiceReference, reply, new ChatRepository.OnMessageSentListener() {
            @Override public void onSuccess(String messageId) {
                if (binding == null) return;
                uploadingVoice = false;
                String sentId = messageId;
                discardVoicePreview();
                clearReplyTarget();
                refetchMessages();
                int position = adapter.indexOfMessage(sentId);
                if (position >= 0) binding.rvMessages.post(() -> {
                    if (binding != null) binding.rvMessages.scrollToPosition(adapter.indexOfMessage(sentId));
                });
            }
            @Override public void onError(String error) {
                uploadingVoice = false;
                if (binding != null) {
                    binding.btnVoiceSend.setEnabled(true);
                    binding.btnVoiceDiscard.setEnabled(true);
                    updateComposerPermissions();
                    binding.tvVoicePreviewDuration.setText("Gửi lỗi · bản ghi được giữ để thử lại");
                }
                Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void playVoiceMessage(ChatMessage message) {
        if (message == null || message.isRecalled() || !ChatRepository.isValidStorageMediaReference(message.getImageUrl())) return;
        if (message.getMessageId().equals(playingVoiceMessageId) && voicePlayer != null) {
            if (!remoteVoicePrepared) return;
            if (voicePlayer.isPlaying()) { voicePlayer.pause(); adapter.setVoicePlayback(playingVoiceMessageId, voicePlayer.getCurrentPosition(), voicePlayer.getDuration(), false); }
            else { requestVoiceFocus(); voicePlayer.start(); voiceHandler.post(voiceTick); }
            return;
        }
        stopVoicePlayback();
        releasePreviewPlayer();
        playingVoiceMessageId = message.getMessageId();
        adapter.setVoicePlayback(playingVoiceMessageId, 0, Math.max(1, (int) message.getVoiceDurationMs()), true);
        Api.run(() -> SupabaseStorage.info(message.getImageUrl()), new Api.Callback<org.json.JSONObject>() {
            @Override public void success(org.json.JSONObject asset) {
                if (binding == null || !message.getMessageId().equals(playingVoiceMessageId)) return;
                try {
                    voicePlayer = new MediaPlayer();
                    voicePlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    java.util.Map<String, String> headers = new java.util.HashMap<>();
                    headers.put("apikey", SupabaseConfig.KEY);
                    headers.put("Authorization", "Bearer " + SessionManager.getInstance().access(false));
                    voicePlayer.setDataSource(ChatActivity.this, Uri.parse(SupabaseStorage.url(asset)), headers);
                    voicePlayer.setOnPreparedListener(player -> {
                        if (binding == null) { stopVoicePlayback(); return; }
                        remoteVoicePrepared = true;
                        requestVoiceFocus(); player.start(); voiceHandler.removeCallbacks(voiceTick); voiceHandler.post(voiceTick);
                    });
                    voicePlayer.setOnCompletionListener(player -> stopVoicePlayback());
                    voicePlayer.setOnErrorListener((player, what, extra) -> { stopVoicePlayback(); Toast.makeText(ChatActivity.this, "Không phát được tin nhắn thoại.", Toast.LENGTH_SHORT).show(); return true; });
                    voicePlayer.prepareAsync();
                } catch (Exception error) { stopVoicePlayback(); }
            }
            @Override public void error(String error) { stopVoicePlayback(); Toast.makeText(ChatActivity.this, "Tin nhắn thoại không còn khả dụng.", Toast.LENGTH_SHORT).show(); }
        });
    }

    private void stopVoicePlayback() {
        voiceHandler.removeCallbacks(voiceTick);
        String previous = playingVoiceMessageId;
        if (voicePlayer != null) {
            try { voicePlayer.stop(); } catch (RuntimeException ignored) { }
            voicePlayer.release(); voicePlayer = null;
        }
        playingVoiceMessageId = null;
        remoteVoicePrepared = false;
        if (adapter != null && previous != null) adapter.setVoicePlayback(previous, 0, 1, false);
        abandonVoiceFocus();
    }

    private void stopAllVoicePlayback() {
        releasePreviewPlayer();
        stopVoicePlayback();
    }

    private void requestVoiceFocus() {
        if (audioManager != null) audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
    }
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (previewPlayer != null && previewPlayer.isPlaying()) previewPlayer.pause();
            if (voicePlayer != null && voicePlayer.isPlaying()) voicePlayer.pause();
            if (adapter != null && playingVoiceMessageId != null && voicePlayer != null)
                adapter.setVoicePlayback(playingVoiceMessageId, voicePlayer.getCurrentPosition(), voicePlayer.getDuration(), false);
        }
    };
    private void abandonVoiceFocus() { if (audioManager != null) audioManager.abandonAudioFocus(focusListener); }
    private static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private void loadOtherProfile(String uid) {if(uid==null)return;otherUid=uid;ProfileClient.get(uid,new Api.Callback<User>(){public void success(User user){if(binding==null||isFinishing())return;binding.tvChatTitle.setText(user.getFullName());com.example.meetomxh.ui.AvatarViews.load(binding.ivChatAvatar,user.getAvatarUrl(),user.getAvatarPreset());}public void error(String e){if(binding!=null)binding.tvChatTitle.setText("Người dùng không khả dụng");}});}

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
        if (editingTarget != null) {
            ChatMessage target = editingTarget;
            sending = true;
            updateSendingState(true);
            chatRepository.editMessage(target, text, new ChatRepository.OnMessageActionListener() {
                @Override public void onSuccess() {
                    if (binding == null || isFinishing() || isDestroyed()) return;
                    if (text.equals(binding.etMessage.getText().toString())) binding.etMessage.setText("");
                    editingTarget = null;
                    clearReplyTarget();
                    sending = false;
                    updateSendingState(false);
                    refetchMessages();
                    Toast.makeText(ChatActivity.this, "Đã cập nhật tin nhắn.", Toast.LENGTH_SHORT).show();
                }
                @Override public void onError(String error) {
                    if (binding == null || isFinishing() || isDestroyed()) return;
                    sending = false;
                    updateSendingState(false);
                    Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
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
                refetchMessages();
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
                showFailedOutbox();
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
        uploadingImage = true;
        setImageUploadStatus("Preparing image…", true);
        activeUploadCall = SupabaseStorage.upload(getContentResolver(), pendingImageUri, "chats/" + chatId, currentUid,
                new SupabaseStorage.UploadListener() {
                    @Override public void onProgress(int percent) {
                        if (!canUpdateImageUi()) return;
                        binding.tvImageUploadStatus.setText("Uploading image " + percent + "%");
                    }
                    @Override public void onSuccess(String url) {
                        runOnUiThread(() -> {
                            activeUploadCall = null;
                            if (!canUpdateImageUi()) return;
                            uploadedImageUrl = url;
                            uploadingImage = false;
                            sendUploadedImageMessage();
                        });
                    }
                    @Override public void onFailure(String error) {
                        runOnUiThread(() -> {
                            activeUploadCall = null;
                            showImageFailure("Image upload failed: " + error);
                        });
                    }
                });
    }

    private void previewSelectedMedia(Uri uri) {
        android.widget.ImageView preview = new android.widget.ImageView(this);
        int height = (int) (220 * getResources().getDisplayMetrics().density);
        preview.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, height));
        preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        String mime = getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("video/")) {
            Api.run(() -> SupabaseStorage.videoThumbnail(getContentResolver(), uri),
                    new Api.Callback<android.graphics.Bitmap>() {
                        @Override public void success(android.graphics.Bitmap bitmap) { if (binding != null) Glide.with(ChatActivity.this).load(bitmap).centerCrop().into(preview); }
                        @Override public void error(String error) { if (binding != null) preview.setImageResource(R.drawable.ic_photo); }
                    });
        } else Glide.with(this).load(uri).centerCrop().into(preview);
        new AlertDialog.Builder(this).setTitle("Xem trước tệp đính kèm")
                .setMessage(mime != null && mime.startsWith("video/") ? "Video MP4 sẽ được gửi trong cuộc trò chuyện." : "Ảnh sẽ được gửi trong cuộc trò chuyện.")
                .setView(preview).setNegativeButton("Hủy", (dialog, which) -> { pendingImageUri = null; })
                .setPositiveButton("Tải lên và gửi", (dialog, which) -> {
                    followImageAfterSend = isAtBottom(); startOrRetryImageSend();
                }).show();
    }

    private void sendUploadedImageMessage() {
        if (binding == null || uploadingImage || uploadedImageUrl == null || chatId == null) return;
        if (!ChatRepository.isValidStorageMediaReference(uploadedImageUrl)) {
            showImageFailure("Media reference is invalid.");
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
                activeUploadCall = null;
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
        activeUploadCall = null;
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
        searchResultAdapter.submit(new ArrayList<>());
        binding.tvMessageSearchState.setVisibility(View.VISIBLE);
        binding.rvMessageSearchResults.setVisibility(View.GONE);
        if (needle.length() < 2 || needle.length() > 100) {
            binding.progressMessageSearch.setVisibility(View.GONE);
            binding.tvMessageSearchState.setText("Nhập 2–100 ký tự để tìm trong lịch sử được phép xem.");
            return;
        }
        binding.progressMessageSearch.setVisibility(View.VISIBLE);
        pendingSearch = () -> chatRepository.search(chatId, needle, new ChatRepository.OnMessagesListener() {
            public void onMessages(List<ChatMessage> rows) {
                if (binding == null || generation != searchGeneration || !searchVisible) return;
                binding.progressMessageSearch.setVisibility(View.GONE);
                searchResultAdapter.submit(rows);
                binding.rvMessageSearchResults.setVisibility(View.VISIBLE);
                binding.tvMessageSearchState.setText(rows.isEmpty() ? "Không có kết quả." : rows.size() + " kết quả (tối đa 100). Chọn để xem nội dung.");
            }
            public void onError(String error) {
                if (binding == null || generation != searchGeneration) return;
                binding.progressMessageSearch.setVisibility(View.GONE);
                binding.tvMessageSearchState.setText(error);
            }
        });
        searchHandler.postDelayed(pendingSearch, 250);
    }
    private void onSearchResultSelected(ChatMessage message) {
        if (message != null) { new AlertDialog.Builder(this).setMessage(message.getDisplayText()).setPositiveButton("Đến tin nhắn", (d, w) -> scrollToMessage(message.getMessageId())).show(); }
    }

    private void showMessageActions(ChatMessage message) {
        if (binding == null || message == null || message.getMessageId() == null
                || message.isDeletedFor(currentUid)) return;
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad, pad, pad);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("Tùy chọn tin nhắn"); title.setTextSize(18); title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.meetomxh.R.color.text_primary));
        title.setPadding(0, 0, 0, pad / 2); list.addView(title);
        addMessageAction(list, "Trả lời", () -> { sheet.dismiss(); setReplyTarget(message); });
        boolean canForwardText = !message.isRecalled() && "text".equals(message.getType())
                && message.getText() != null && !message.getText().trim().isEmpty() && !message.hasSharedPost();
        if (canForwardText) addMessageAction(list, "Chuyển tiếp văn bản", () -> { sheet.dismiss(); chooseForwardTarget(message); });
        if (!message.isRecalled() && "text".equals(message.getType()) && message.getText() != null
                && !message.getText().trim().isEmpty())
            addMessageAction(list, "Sao chép", () -> { sheet.dismiss(); copyMessage(message); });
        boolean mine = currentUid.equals(message.getSenderId());
        if (mine && !message.isRecalled() && "text".equals(message.getType()) && !message.hasSharedPost()) {
            long age = message.getCreatedAt() == null ? Long.MAX_VALUE
                    : System.currentTimeMillis() - message.getCreatedAt().toDate().getTime();
            if (age >= 0 && age <= 15L * 60L * 1000L)
                addMessageAction(list, "Chỉnh sửa", () -> { sheet.dismiss(); beginMessageEdit(message); });
        }
        if (!mine) addMessageAction(list, "Báo cáo tin nhắn", () -> {
            sheet.dismiss(); startActivity(new Intent(this, ReportContentActivity.class)
                    .putExtra(ReportContentActivity.EXTRA_TARGET_TYPE, "message")
                    .putExtra(ReportContentActivity.EXTRA_TARGET_ID, message.getMessageId()));
        });
        if (mine) addMessageAction(list, "Trạng thái giao / đọc", () -> {
            sheet.dismiss(); com.example.meetomxh.supabase.ChatControls.receipts(this, chatId, message.getMessageId());
        });
        if (mine && !message.isRecalled()) addMessageAction(list, "Thu hồi tin nhắn", () -> {
            sheet.dismiss(); confirmMessageAction(message, true);
        });
        addMessageAction(list, "Xóa khỏi phía tôi", () -> { sheet.dismiss(); confirmMessageAction(message, false); });
        sheet.setContentView(list);
        sheet.show();
    }

    private void addMessageAction(android.widget.LinearLayout list, String label, Runnable action) {
        android.widget.TextView row = new android.widget.TextView(this);
        row.setText(label); row.setTextSize(16);
        row.setTextColor(androidx.core.content.ContextCompat.getColor(this, com.example.meetomxh.R.color.text_primary));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setMinHeight((int) (52 * getResources().getDisplayMetrics().density));
        row.setPadding((int) (8 * getResources().getDisplayMetrics().density), 0, 0, 0);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(v -> action.run()); list.addView(row);
    }

    private void beginMessageEdit(ChatMessage message) {
        if (message == null || binding == null) return;
        editingTarget = message; replyTarget = null;
        binding.layoutReplyComposer.setVisibility(View.VISIBLE);
        binding.tvReplyComposer.setText("Chỉnh sửa tin nhắn · tối đa 15 phút");
        binding.btnSendMessage.setText("Lưu");
        binding.etMessage.setText(message.getText());
        binding.etMessage.setSelection(binding.etMessage.length());
        binding.etMessage.requestFocus();
    }

    private void chooseForwardTarget(ChatMessage message) {
        if (binding == null || message == null) return;
        chatRepository.loadCurrentUserChats(new ChatRepository.OnChatsListener() {
            @Override public void onChats(List<Chat> chats) {
                if (binding == null || isFinishing() || isDestroyed()) return;
                List<Chat> targets = new ArrayList<>(); List<String> labels = new ArrayList<>();
                if (chats != null) for (Chat chat : chats) {
                    if (chat == null || chat.getChatId() == null || chat.getChatId().equals(chatId)
                            || !"active".equals(chat.state)) continue;
                    if (targets.size() >= 20) break;
                    targets.add(chat);
                    labels.add("group".equals(chat.getType()) && chat.title != null && !chat.title.trim().isEmpty()
                            ? chat.title : "Cuộc trò chuyện riêng");
                }
                if (targets.isEmpty()) { Toast.makeText(ChatActivity.this, "Không có cuộc trò chuyện đích khả dụng.", Toast.LENGTH_SHORT).show(); return; }
                int[] pendingNames = {0};
                for (int i = 0; i < targets.size(); i++) {
                    Chat target = targets.get(i);
                    if ("group".equals(target.getType())) continue;
                    String peer = null;
                    if (target.getParticipants() != null) for (String participant : target.getParticipants())
                        if (participant != null && !participant.equals(currentUid)) { peer = participant; break; }
                    if (peer == null) continue;
                    final int index = i; pendingNames[0]++;
                    ProfileClient.get(peer, new Api.Callback<User>() {
                        @Override public void success(User user) {
                            if (binding == null) return;
                            labels.set(index, user.getFullName());
                            if (--pendingNames[0] == 0) showForwardChooser(message, targets, labels);
                        }
                        @Override public void error(String error) {
                            if (binding == null) return;
                            labels.set(index, "Cuộc trò chuyện riêng");
                            if (--pendingNames[0] == 0) showForwardChooser(message, targets, labels);
                        }
                    });
                }
                if (pendingNames[0] == 0) showForwardChooser(message, targets, labels);
            }
            @Override public void onError(String error) {
                if (binding != null) Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showForwardChooser(ChatMessage message, List<Chat> targets, List<String> labels) {
        if (binding == null || isFinishing() || isDestroyed()) return;
        String preview = message.getText().length() > 140 ? message.getText().substring(0, 140) + "…" : message.getText();
        new AlertDialog.Builder(this).setTitle("Chuyển tiếp văn bản")
                .setMessage(preview).setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    Chat target = targets.get(index);
                    new AlertDialog.Builder(this).setTitle("Gửi đến " + labels.get(index))
                            .setMessage(preview).setNegativeButton("Hủy", null)
                            .setPositiveButton("Gửi", (d, w) -> chatRepository.forwardTextMessage(
                                    message.getMessageId(), target.getChatId(), new ChatRepository.OnMessageActionListener() {
                                        @Override public void onSuccess() { Toast.makeText(ChatActivity.this, "Đã chuyển tiếp.", Toast.LENGTH_SHORT).show(); }
                                        @Override public void onError(String error) { Toast.makeText(ChatActivity.this, error, Toast.LENGTH_LONG).show(); }
                                    })).show();
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
                refetchMessages();
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
        editingTarget = null;
        replyTarget = message;
        binding.btnSendMessage.setText("Gửi");
        binding.tvReplyComposer.setText("Trả lời: " + message.getDisplayText());
        binding.layoutReplyComposer.setVisibility(View.VISIBLE);
        binding.etMessage.requestFocus();
    }

    private void refetchMessages() {
        if (messagesRegistration instanceof com.example.meetomxh.supabase.Observer)
            ((com.example.meetomxh.supabase.Observer<?>) messagesRegistration).refresh();
    }

    private void showFailedOutbox(){if(chatId==null||binding==null)return;Api.run(()->MessageOutbox.forConversation(chatId),new Api.Callback<org.json.JSONArray>(){public void success(org.json.JSONArray rows){if(binding==null)return;failedRequestId=rows.length()==0?null:rows.optJSONObject(rows.length()-1).optString("p_client_request_id");binding.tvFailedMessageRetry.setVisibility(failedRequestId==null?View.GONE:View.VISIBLE);failedVoiceMatchesPreview=false;if(failedRequestId!=null){org.json.JSONObject row=rows.optJSONObject(rows.length()-1);String body=row.optString("p_body","");org.json.JSONArray assets=row.optJSONArray("p_assets");failedVoiceMatchesPreview="voice".equals(row.optString("p_kind"))&&uploadedVoiceReference!=null&&assets!=null&&assets.length()==1&&com.example.meetomxh.supabase.Dto.assetId(uploadedVoiceReference).equals(assets.optString(0));binding.tvFailedMessageRetry.setText("Message failed · "+("voice".equals(row.optString("p_kind"))?"Tap to retry voice message":body.isEmpty()?"Tap to retry attachment":"Tap to retry: "+(body.length()>48?body.substring(0,48)+"…":body)));}}public void error(String e){android.util.Log.w("ChatActivity","Outbox unavailable",new IllegalStateException(e));}});}
    private void retryFailedMessage(){if(failedRequestId==null||chatId==null)return;String request=failedRequestId;boolean clearVoice=failedVoiceMatchesPreview;binding.tvFailedMessageRetry.setEnabled(false);Api.run(()->MessageOutbox.retryOne(chatId,request),new Api.Callback<String>(){public void success(String messageId){if(binding==null)return;binding.tvFailedMessageRetry.setEnabled(true);if(clearVoice){discardVoicePreview();clearReplyTarget();}messageToScrollTo=isAtBottom()?messageId:null;refetchMessages();showFailedOutbox();}public void error(String e){if(binding!=null){binding.tvFailedMessageRetry.setEnabled(true);Toast.makeText(ChatActivity.this,e,Toast.LENGTH_LONG).show();showFailedOutbox();}}});}

    private void clearReplyTarget() {
        replyTarget = null;
        editingTarget = null;
        if (binding != null) {
            binding.layoutReplyComposer.setVisibility(View.GONE);
            binding.tvReplyComposer.setText("");
            binding.btnSendMessage.setText("Gửi");
        }
    }

    private void scrollToMessage(String messageId, long seq) {
        if (binding == null || adapter == null) return;
        int position = adapter.indexOfMessage(messageId);
        if (position >= 0) binding.rvMessages.smoothScrollToPosition(position);
        else if (seq > 0) {
            pendingReplyScrollId = messageId; pendingReplyScrollSeq = seq;
            historyLimit = Math.max(historyLimit, currentMessages.size());
            if (historyLimit < 5000) {
                historyLimit += 50; loadingOlder = true;
                binding.progressOlderMessages.setVisibility(View.VISIBLE);
                removeMessagesListener(); attachMessagesListenerIfNeeded();
            } else {
                pendingReplyScrollId = null; pendingReplyScrollSeq = 0;
                Toast.makeText(this, "Không thể tải thêm lịch sử.", Toast.LENGTH_SHORT).show();
            }
        } else Toast.makeText(this, "Tin nhắn gốc không còn khả dụng.", Toast.LENGTH_SHORT).show();
    }

    private void scrollToMessage(String messageId) { scrollToMessage(messageId, 0); }

    private void highlightMessage(String messageId){if(binding==null||adapter==null)return;int position=adapter.indexOfMessage(messageId);if(position<0)return;androidx.recyclerview.widget.RecyclerView.ViewHolder holder=binding.rvMessages.findViewHolderForAdapterPosition(position);if(holder==null)return;View bubble=holder.itemView.findViewById(com.example.meetomxh.R.id.messageBubble);if(bubble==null)return;android.content.res.ColorStateList old=androidx.core.view.ViewCompat.getBackgroundTintList(bubble);androidx.core.view.ViewCompat.setBackgroundTintList(bubble,android.content.res.ColorStateList.valueOf(getColor(R.color.brand_blue)));bubble.postDelayed(()->{if(!isFinishing())androidx.core.view.ViewCompat.setBackgroundTintList(bubble,old);},1500);}

    private void updateSendingState(boolean isSending) {
        updateComposerPermissions();
        binding.btnSendMessage.setText(isSending ? "" : editingTarget != null ? "Lưu" : "Gửi");
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

    private void openConversationDetails(){if(chatId==null)return;detailsLauncher.launch(new Intent(this,ConversationDetailsActivity.class).putExtra(ConversationDetailsActivity.EXTRA_CHAT_ID,chatId));}

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
