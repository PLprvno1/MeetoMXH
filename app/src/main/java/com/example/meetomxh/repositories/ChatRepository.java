package com.example.meetomxh.repositories;

import com.example.meetomxh.models.Chat;
import com.example.meetomxh.models.ChatMessage;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Firestore data access for private one-to-one text conversations. */
public class ChatRepository {
    public static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_CHAT_LIST_SIZE = 50;
    private static final String PRIVATE_TYPE = "private";
    private static final String TEXT_TYPE = "text";

    public interface OnChatListener {
        void onSuccess(Chat chat);
        void onError(String error);
    }

    public interface OnChatsListener {
        void onChats(List<Chat> chats);
        void onError(String error);
    }

    public interface OnMessagesListener {
        void onMessages(List<ChatMessage> messages);
        void onError(String error);
    }

    public interface OnMessageSentListener {
        void onSuccess(String messageId);
        void onError(String error);
    }

    public interface OnMessageActionListener { void onSuccess(); void onError(String error); }
    public interface OnReadUpdateListener { void onComplete(boolean updated); void onError(String error); }
    public interface OnChatStateListener { void onState(Chat chat); void onError(String error); }
    public static class ReplyData {
        public final String messageId, senderId, preview;
        public ReplyData(String messageId, String senderId, String preview) {
            this.messageId = messageId; this.senderId = senderId; this.preview = preview;
        }
    }

    private static ChatRepository instance;
    private final FirebaseFirestore db;
    private final FirebaseAuth auth;

    public ChatRepository() {
        this(FirebaseFirestore.getInstance(), FirebaseAuth.getInstance());
    }

    ChatRepository(FirebaseFirestore db, FirebaseAuth auth) {
        this.db = db;
        this.auth = auth;
    }

    public static synchronized ChatRepository getInstance() {
        if (instance == null) instance = new ChatRepository();
        return instance;
    }

    /** Gets or creates the same private chat regardless of which participant calls. */
    public void getOrCreatePrivateChat(String otherUid, OnChatListener listener) {
        String currentUid = currentUid(listener);
        if (currentUid == null) return;
        String targetUid = otherUid == null ? "" : otherUid.trim();
        if (targetUid.isEmpty()) {
            failChat(listener, "UID người nhận không hợp lệ.");
            return;
        }
        if (currentUid.equals(targetUid)) {
            failChat(listener, "Không thể tạo cuộc trò chuyện với chính mình.");
            return;
        }

        String chatId;
        try {
            chatId = createPrivateChatId(currentUid, targetUid);
        } catch (IllegalArgumentException e) {
            failChat(listener, "UID người nhận không hợp lệ.");
            return;
        }
        DocumentReference userRef = db.collection("Users").document(targetUid);
        DocumentReference chatRef = db.collection("Chats").document(chatId);
        List<String> pair = Chat.sortedParticipants(currentUid, targetUid);

        db.runTransaction(transaction -> {
            DocumentSnapshot user = transaction.get(userRef);
            if (!user.exists()) throw new IllegalArgumentException("Không tìm thấy người dùng cần nhắn tin.");
            DocumentSnapshot existing = transaction.get(chatRef);
            if (existing.exists()) {
                Chat chat = Chat.fromSnapshot(existing);
                if (chat == null || !PRIVATE_TYPE.equals(chat.getType()) ||
                        !chat.hasExactlyParticipants(pair.get(0), pair.get(1))) {
                    throw new IllegalStateException("Dữ liệu cuộc trò chuyện hiện có không hợp lệ.");
                }
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("type", PRIVATE_TYPE);
                data.put("participants", pair);
                data.put("createdAt", FieldValue.serverTimestamp());
                data.put("updatedAt", FieldValue.serverTimestamp());
                data.put("lastMessage", "");
                data.put("lastMessageAt", null);
                data.put("lastMessageSenderId", "");
                data.put("lastMessageId", "");
                data.put("lastMessageHiddenFor", new ArrayList<String>());
                data.put("lastMessageType", "");
                data.put("lastReadAtByUid", new HashMap<String, Object>());
                data.put("lastReadMessageIdByUid", new HashMap<String, String>());
                data.put("lastIncomingMessageAtByUid", new HashMap<String, Object>());
                data.put("lastIncomingMessageIdByUid", new HashMap<String, String>());
                transaction.set(chatRef, data);
            }
            return chatId;
        }).addOnSuccessListener(id -> chatRef.get()
                .addOnSuccessListener(snapshot -> {
                    Chat chat = Chat.fromSnapshot(snapshot);
                    if (chat == null) failChat(listener, "Không đọc được dữ liệu cuộc trò chuyện.");
                    else if (listener != null) listener.onSuccess(chat);
                })
                .addOnFailureListener(e -> failChat(listener, firestoreError("Không thể đọc cuộc trò chuyện", e))))
                .addOnFailureListener(e -> failChat(listener, firestoreError("Không thể tạo hoặc mở cuộc trò chuyện", e)));
    }

    /** List query is scoped on Firestore to chats containing the signed-in UID. */
    public ListenerRegistration listenToCurrentUserChats(OnChatsListener listener) {
        String uid = currentUidForListener(listener);
        if (uid == null) return NoOpRegistration.INSTANCE;
        return db.collection("Chats").whereArrayContains("participants", uid)
                .orderBy("updatedAt", Query.Direction.DESCENDING).limit(MAX_CHAT_LIST_SIZE)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        if (listener != null) listener.onError(chatListError(error));
                        return;
                    }
                    List<Chat> chats = new ArrayList<>();
                    if (snapshots != null) {
                        for (QueryDocumentSnapshot snapshot : snapshots) {
                            Chat chat = Chat.fromSnapshot(snapshot);
                            if (chat != null && PRIVATE_TYPE.equals(chat.getType()) && chat.getParticipants() != null
                                    && chat.getParticipants().contains(uid)) chats.add(chat);
                        }
                    }
                    if (listener != null) listener.onChats(chats);
                });
    }

    /** Observes read markers and summary for one verified conversation. */
    public ListenerRegistration listenToChatState(String chatId, OnChatStateListener listener) {
        String uid = currentUidForChatState(listener);
        if (uid == null) return NoOpRegistration.INSTANCE;
        String validId = chatId == null ? "" : chatId.trim();
        if (validId.isEmpty()) {
            if (listener != null) listener.onError("Chat ID khÃ´ng há»£p lá»‡.");
            return NoOpRegistration.INSTANCE;
        }
        return db.collection("Chats").document(validId).addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                if (listener != null) listener.onError(firestoreError("KhÃ´ng thá»ƒ theo dÃµi tráº¡ng thÃ¡i chat", error));
                return;
            }
            Chat chat = Chat.fromSnapshot(snapshot);
            if (chat == null || !PRIVATE_TYPE.equals(chat.getType()) || chat.getParticipants() == null
                    || chat.getParticipants().size() != 2 || !chat.getParticipants().contains(uid)) {
                if (listener != null) listener.onError("Cuá»™c trÃ² chuyá»‡n khÃ´ng tá»“n táº¡i hoáº·c báº¡n khÃ´ng cÃ³ quyá»n truy cáº­p.");
                return;
            }
            if (listener != null) listener.onState(chat);
        });
    }

    /** Advances only the current participant's read marker after the row is visible. */
    public void markChatRead(String chatId, String visibleIncomingMessageId, OnReadUpdateListener listener) {
        String uid = currentUid(listener);
        if (uid == null) return;
        String validId = chatId == null ? "" : chatId.trim();
        String messageId = visibleIncomingMessageId == null ? "" : visibleIncomingMessageId.trim();
        if (validId.isEmpty() || messageId.isEmpty()) {
            if (listener != null) listener.onError("Chat ID or message ID is invalid.");
            return;
        }
        DocumentReference chatRef = db.collection("Chats").document(validId);
        db.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(chatRef);
            Chat chat = Chat.fromSnapshot(snapshot);
            requireMembership(chat, uid);
            String latestIncomingId = chat.getLastIncomingMessageId(uid);
            if (!empty(latestIncomingId) && !messageId.equals(latestIncomingId)) return false;
            transaction.update(chatRef,
                    FieldPath.of("lastReadAtByUid", uid), FieldValue.serverTimestamp(),
                    FieldPath.of("lastReadMessageIdByUid", uid), messageId);
            return true;
        }).addOnSuccessListener(updated -> {
            if (listener != null) listener.onComplete(Boolean.TRUE.equals(updated));
        }).addOnFailureListener(error -> {
            if (listener != null) listener.onError(firestoreError("KhÃ´ng thá»ƒ cáº­p nháº­t tráº¡ng thÃ¡i Ä‘Ã£ Ä‘á»c", error));
        });
    }

    /** Listener is attached only after the chat document confirms current-user membership. */
    public ListenerRegistration listenToMessages(String chatId, int limit, OnMessagesListener listener) {
        String uid = currentUidForMessages(listener);
        if (uid == null) return NoOpRegistration.INSTANCE;
        String validChatId = chatId == null ? "" : chatId.trim();
        if (validChatId.isEmpty() || limit < 1) {
            failMessages(listener, "Chat ID hoặc giới hạn tin nhắn không hợp lệ.");
            return NoOpRegistration.INSTANCE;
        }
        DocumentReference chatRef = db.collection("Chats").document(validChatId);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<ListenerRegistration> activeListener = new AtomicReference<>();
        ListenerRegistration registration = () -> {
            cancelled.set(true);
            ListenerRegistration attached = activeListener.getAndSet(null);
            if (attached != null) attached.remove();
        };

        chatRef.get().addOnSuccessListener(chat -> {
            if (cancelled.get()) return;
            Chat data = Chat.fromSnapshot(chat);
            if (data == null || !PRIVATE_TYPE.equals(data.getType()) || data.getParticipants() == null
                    || data.getParticipants().size() != 2 || !data.getParticipants().contains(uid)) {
                failMessages(listener, "Bạn không phải thành viên của cuộc trò chuyện này.");
                return;
            }
            ListenerRegistration attached = chatRef.collection("Messages")
                    .orderBy("createdAt", Query.Direction.ASCENDING).limitToLast(limit)
                    .addSnapshotListener(MetadataChanges.INCLUDE, (snapshots, error) -> {
                        if (cancelled.get()) return;
                        if (error != null) {
                            if (listener != null) listener.onError(firestoreError("Không thể tải tin nhắn", error));
                            return;
                        }
                        List<ChatMessage> messages = new ArrayList<>();
                        if (snapshots != null) {
                            for (QueryDocumentSnapshot snapshot : snapshots) {
                                // Don't render a locally queued message as confirmed until the write succeeds.
                                if (snapshot.getMetadata().hasPendingWrites()) continue;
                                ChatMessage message = ChatMessage.fromSnapshot(snapshot);
                                if (message != null) messages.add(message);
                            }
                        }
                        if (listener != null) listener.onMessages(messages);
                    });
            activeListener.set(attached);
            if (cancelled.get()) {
                ListenerRegistration lateAttachment = activeListener.getAndSet(null);
                if (lateAttachment != null) lateAttachment.remove();
            }
        }).addOnFailureListener(e -> {
            if (!cancelled.get()) failMessages(listener, firestoreError("Không thể xác minh cuộc trò chuyện", e));
        });
        return registration;
    }

    public ListenerRegistration listenToMessages(String chatId, OnMessagesListener listener) {
        return listenToMessages(chatId, DEFAULT_MESSAGE_LIMIT, listener);
    }

    /** Writes the message and chat summary in one atomic Firestore batch. */
    public void sendTextMessage(String chatId, String text, OnMessageSentListener listener) {
        sendTextMessage(chatId, text, null, listener);
    }

    public void sendTextMessage(String chatId, String text, ReplyData reply, OnMessageSentListener listener) {
        String senderUid = currentUid(listener);
        if (senderUid == null) return;
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedChatId.isEmpty()) {
            failMessageSent(listener, "Chat ID không hợp lệ.");
            return;
        }
        if (normalizedText.isEmpty()) {
            failMessageSent(listener, "Tin nhắn không được để trống.");
            return;
        }

        DocumentReference chatRef = db.collection("Chats").document(normalizedChatId);
        chatRef.get().addOnSuccessListener(snapshot -> {
            Chat chat = Chat.fromSnapshot(snapshot);
            if (chat == null || !PRIVATE_TYPE.equals(chat.getType()) || chat.getParticipants() == null
                    || chat.getParticipants().size() != 2 || !chat.getParticipants().contains(senderUid)) {
                failMessageSent(listener, "Bạn không có quyền gửi tin trong cuộc trò chuyện này.");
                return;
            }
            String receiverUid = chat.getParticipants().get(0).equals(senderUid)
                    ? chat.getParticipants().get(1) : chat.getParticipants().get(0);
            if (senderUid.equals(receiverUid)) {
                failMessageSent(listener, "Không thể gửi tin nhắn cho chính mình.");
                return;
            }

            DocumentReference messageRef = chatRef.collection("Messages").document();
            Map<String, Object> message = new HashMap<>();
            message.put("senderId", senderUid);
            message.put("receiverId", receiverUid);
            message.put("type", TEXT_TYPE);
            message.put("text", normalizedText);
            message.put("createdAt", FieldValue.serverTimestamp());
            if (reply != null && reply.messageId != null && !reply.messageId.trim().isEmpty()) {
                message.put("replyToMessageId", reply.messageId);
                message.put("replyToSenderId", reply.senderId == null ? "" : reply.senderId);
                // Resolve the quoted text from the live source while it is in the loaded page.
                // Persist only a neutral fallback so a later recall cannot leak stale text via replies.
                message.put("replyPreview", "Tin nhắn gốc");
            }

            db.runBatch(batch -> {
                batch.set(messageRef, message);
                batch.update(chatRef,
                        FieldPath.of("lastMessage"), normalizedText,
                        FieldPath.of("lastMessageAt"), FieldValue.serverTimestamp(),
                        FieldPath.of("lastMessageSenderId"), senderUid,
                        FieldPath.of("lastMessageId"), messageRef.getId(),
                        FieldPath.of("lastMessageType"), TEXT_TYPE,
                        FieldPath.of("lastMessageHiddenFor"), new ArrayList<String>(),
                        FieldPath.of("updatedAt"), FieldValue.serverTimestamp(),
                        FieldPath.of("lastIncomingMessageAtByUid", receiverUid), FieldValue.serverTimestamp(),
                        FieldPath.of("lastIncomingMessageIdByUid", receiverUid), messageRef.getId());
            }).addOnSuccessListener(unused -> {
                if (listener != null) listener.onSuccess(messageRef.getId());
            }).addOnFailureListener(e -> failMessageSent(listener, firestoreError("Không thể gửi tin nhắn", e)));
        }).addOnFailureListener(e -> failMessageSent(listener, firestoreError("Không thể kiểm tra cuộc trò chuyện", e)));
    }

    /** Sends an image message only after a validated HTTPS download URL is available. */
    public void sendImageMessage(String chatId, String imageUrl, OnMessageSentListener listener) {
        String senderUid = currentUid(listener);
        if (senderUid == null) return;
        String normalizedChatId = chatId == null ? "" : chatId.trim();
        String normalizedUrl = imageUrl == null ? "" : imageUrl.trim();
        if (normalizedChatId.isEmpty() || !isValidStorageImageUrl(normalizedUrl)) {
            failMessageSent(listener, "URL áº£nh khÃ´ng há»£p lá»‡.");
            return;
        }
        DocumentReference chatRef = db.collection("Chats").document(normalizedChatId);
        chatRef.get().addOnSuccessListener(snapshot -> {
            Chat chat = Chat.fromSnapshot(snapshot);
            if (chat == null || !PRIVATE_TYPE.equals(chat.getType()) || chat.getParticipants() == null
                    || chat.getParticipants().size() != 2 || !chat.getParticipants().contains(senderUid)) {
                failMessageSent(listener, "Báº¡n khÃ´ng cÃ³ quyá»n gá»­i áº£nh trong cuá»™c trÃ² chuyá»‡n nÃ y.");
                return;
            }
            String receiverUid = chat.getParticipants().get(0).equals(senderUid)
                    ? chat.getParticipants().get(1) : chat.getParticipants().get(0);
            DocumentReference messageRef = chatRef.collection("Messages").document();
            Map<String, Object> message = new HashMap<>();
            message.put("senderId", senderUid);
            message.put("receiverId", receiverUid);
            message.put("type", "image");
            message.put("imageUrl", normalizedUrl);
            message.put("createdAt", FieldValue.serverTimestamp());

            db.runBatch(batch -> {
                batch.set(messageRef, message);
                batch.update(chatRef,
                        FieldPath.of("lastMessage"), "Ảnh",
                        FieldPath.of("lastMessageAt"), FieldValue.serverTimestamp(),
                        FieldPath.of("lastMessageSenderId"), senderUid,
                        FieldPath.of("lastMessageId"), messageRef.getId(),
                        FieldPath.of("lastMessageType"), "image",
                        FieldPath.of("lastMessageHiddenFor"), new ArrayList<String>(),
                        FieldPath.of("updatedAt"), FieldValue.serverTimestamp(),
                        FieldPath.of("lastIncomingMessageAtByUid", receiverUid), FieldValue.serverTimestamp(),
                        FieldPath.of("lastIncomingMessageIdByUid", receiverUid), messageRef.getId());
            }).addOnSuccessListener(unused -> {
                if (listener != null) listener.onSuccess(messageRef.getId());
            }).addOnFailureListener(error -> failMessageSent(listener,
                    firestoreError("KhÃ´ng thá»ƒ gá»­i metadata áº£nh", error)));
        }).addOnFailureListener(error -> failMessageSent(listener,
                firestoreError("KhÃ´ng thá»ƒ kiá»ƒm tra cuá»™c trÃ² chuyá»‡n", error)));
    }

    public static boolean isValidStorageImageUrl(String url) {
        return url != null && url.startsWith("https://firebasestorage.googleapis.com/v0/b/")
                && url.contains("/o/") && url.contains("alt=media");
    }

    public void recallMessage(String chatId, ChatMessage selected, OnMessageActionListener listener) {
        String uid = currentUid(listener);
        if (uid == null) return;
        if (selected == null || empty(selected.getMessageId())) { failAction(listener, "Tin nhắn không hợp lệ."); return; }
        DocumentReference chatRef = db.collection("Chats").document(chatId);
        DocumentReference messageRef = chatRef.collection("Messages").document(selected.getMessageId());
        db.runTransaction(transaction -> {
            DocumentSnapshot chatSnapshot = transaction.get(chatRef);
            DocumentSnapshot messageSnapshot = transaction.get(messageRef);
            Chat chat = Chat.fromSnapshot(chatSnapshot);
            ChatMessage message = ChatMessage.fromSnapshot(messageSnapshot);
            requireMembership(chat, uid);
            if (message == null || !uid.equals(message.getSenderId()) || !uid.equals(selected.getSenderId()))
                throw new IllegalStateException("Bạn chỉ có thể thu hồi tin nhắn do mình gửi.");
            if (message.isRecalled()) throw new IllegalStateException("Tin nhắn đã được thu hồi.");
            Map<String, Object> changes = new HashMap<>();
            changes.put("isRecalled", true);
            changes.put("recalledAt", FieldValue.serverTimestamp());
            changes.put("text", "");
            changes.put("imageUrl", "");
            transaction.update(messageRef, changes);
            if (isLastMessage(chat, message)) {
                Map<String, Object> summary = new HashMap<>();
                summary.put("lastMessage", "Tin nhắn đã được thu hồi");
                summary.put("lastMessageId", message.getMessageId());
                summary.put("lastMessageType", "recalled");
                summary.put("lastMessageHiddenFor", new ArrayList<String>());
                summary.put("updatedAt", FieldValue.serverTimestamp());
                transaction.update(chatRef, summary);
            }
            return null;
        }).addOnSuccessListener(unused -> succeedAction(listener))
                .addOnFailureListener(error -> failAction(listener, firestoreError("Không thể thu hồi tin nhắn", error)));
    }

    public void deleteMessageForMe(String chatId, ChatMessage selected, OnMessageActionListener listener) {
        String uid = currentUid(listener);
        if (uid == null) return;
        if (selected == null || empty(selected.getMessageId())) { failAction(listener, "Tin nhắn không hợp lệ."); return; }
        DocumentReference chatRef = db.collection("Chats").document(chatId);
        DocumentReference messageRef = chatRef.collection("Messages").document(selected.getMessageId());
        db.runTransaction(transaction -> {
            DocumentSnapshot chatSnapshot = transaction.get(chatRef);
            DocumentSnapshot messageSnapshot = transaction.get(messageRef);
            Chat chat = Chat.fromSnapshot(chatSnapshot);
            ChatMessage message = ChatMessage.fromSnapshot(messageSnapshot);
            requireMembership(chat, uid);
            if (message == null || message.isDeletedFor(uid)) throw new IllegalStateException("Tin nhắn không còn khả dụng.");
            transaction.update(messageRef, "deletedFor", FieldValue.arrayUnion(uid));
            if (isLastMessage(chat, message)) transaction.update(chatRef, "lastMessageHiddenFor", FieldValue.arrayUnion(uid));
            return null;
        }).addOnSuccessListener(unused -> succeedAction(listener))
                .addOnFailureListener(error -> failAction(listener, firestoreError("Không thể xóa tin nhắn khỏi phía bạn", error)));
    }

    private static boolean isLastMessage(Chat chat, ChatMessage message) {
        if (chat == null || message == null) return false;
        if (!empty(chat.getLastMessageId())) return message.getMessageId().equals(chat.getLastMessageId());
        return safeEquals(chat.getLastMessageSenderId(), message.getSenderId())
                && safeEquals(chat.getLastMessage(), message.getText());
    }

    private static void requireMembership(Chat chat, String uid) {
        if (chat == null || !PRIVATE_TYPE.equals(chat.getType()) || chat.getParticipants() == null
                || chat.getParticipants().size() != 2 || !chat.getParticipants().contains(uid))
            throw new IllegalStateException("Bạn không phải thành viên của cuộc trò chuyện này.");
    }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static boolean safeEquals(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static void succeedAction(OnMessageActionListener listener) { if (listener != null) listener.onSuccess(); }
    private static void failAction(OnMessageActionListener listener, String error) { if (listener != null) listener.onError(error); }

    /** Stable, order-independent SHA-256 document ID for a pair of distinct UIDs. */
    public static String createPrivateChatId(String firstUid, String secondUid) {
        if (firstUid == null || firstUid.trim().isEmpty() || secondUid == null || secondUid.trim().isEmpty()
                || firstUid.equals(secondUid)) throw new IllegalArgumentException("Two distinct valid UIDs are required.");
        List<String> pair = Chat.sortedParticipants(firstUid, secondUid);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String uid : pair) {
                byte[] bytes = uid.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            byte[] hash = digest.digest();
            StringBuilder id = new StringBuilder(hash.length * 2);
            for (byte value : hash) id.append(String.format("%02x", value & 0xff));
            return id.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private String currentUid(Object listener) {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.getUid() != null && !user.getUid().trim().isEmpty()) return user.getUid();
        String error = "Bạn cần đăng nhập để sử dụng chat.";
        if (listener instanceof OnChatListener) failChat((OnChatListener) listener, error);
        else if (listener instanceof OnMessageSentListener) failMessageSent((OnMessageSentListener) listener, error);
        else if (listener instanceof OnMessageActionListener) failAction((OnMessageActionListener) listener, error);
        else if (listener instanceof OnReadUpdateListener) ((OnReadUpdateListener) listener).onError(error);
        return null;
    }

    private String currentUidForListener(OnChatsListener listener) {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.getUid() != null && !user.getUid().trim().isEmpty()) return user.getUid();
        if (listener != null) listener.onError("Bạn cần đăng nhập để sử dụng chat.");
        return null;
    }

    private String currentUidForMessages(OnMessagesListener listener) {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.getUid() != null && !user.getUid().trim().isEmpty()) return user.getUid();
        failMessages(listener, "Bạn cần đăng nhập để sử dụng chat.");
        return null;
    }

    private String currentUidForChatState(OnChatStateListener listener) {
        FirebaseUser user = auth.getCurrentUser();
        if (user != null && user.getUid() != null && !user.getUid().trim().isEmpty()) return user.getUid();
        if (listener != null) listener.onError("Sign in to access this conversation.");
        return null;
    }

    private static String firestoreError(String action, Exception e) {
        String details = e == null ? null : e.getLocalizedMessage();
        if (details == null || details.trim().isEmpty()) details = "Lỗi mạng hoặc Firestore.";
        return action + ": " + details;
    }

    private static String chatListError(Exception error) {
        String details = error == null ? "" : error.getLocalizedMessage();
        if (details != null && details.toLowerCase().contains("index")) {
            return "Firestore yêu cầu composite index cho Chats: participants (array-contains) + updatedAt (descending). " + details;
        }
        return firestoreError("Không thể tải danh sách chat", error);
    }

    private static void failChat(OnChatListener listener, String error) { if (listener != null) listener.onError(error); }
    private static void failMessages(OnMessagesListener listener, String error) { if (listener != null) listener.onError(error); }
    private static void failMessageSent(OnMessageSentListener listener, String error) { if (listener != null) listener.onError(error); }

    private enum NoOpRegistration implements ListenerRegistration {
        INSTANCE;
        @Override public void remove() { }
    }
}
