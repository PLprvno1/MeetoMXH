package com.example.meetomxh;

import com.example.meetomxh.repositories.ChatRepository;
import com.example.meetomxh.repositories.UserSearchQuery;
import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.models.Chat;
import com.google.firebase.Timestamp;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void privateChatIdIsStableForEitherParticipantOrder() {
        assertEquals(ChatRepository.createPrivateChatId("uid-a", "uid-b"),
                ChatRepository.createPrivateChatId("uid-b", "uid-a"));
    }

    @Test
    public void differentPairsProduceDifferentChatIds() {
        assertNotEquals(ChatRepository.createPrivateChatId("uid-a", "uid-b"),
                ChatRepository.createPrivateChatId("uid-a", "uid-c"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void privateChatIdRejectsSelfConversation() {
        ChatRepository.createPrivateChatId("uid-a", "uid-a");
    }

    @Test(expected = IllegalArgumentException.class)
    public void privateChatIdRejectsEmptyUid() {
        ChatRepository.createPrivateChatId("uid-a", " ");
    }

    @Test
    public void userSearchNormalizesNullAndWhitespaceQueries() {
        assertEquals("", UserSearchQuery.normalize(null));
        assertEquals("", UserSearchQuery.normalize("  \t "));
        assertEquals("Nguyễn An", UserSearchQuery.normalize("  Nguyễn An  "));
    }

    @Test
    public void userSearchBuildsPrefixUpperBound() {
        assertEquals("An\uf8ff", UserSearchQuery.prefixUpperBound("An"));
    }

    @Test
    public void staleUserSearchResponsesAreRejected() {
        assertFalse(UserSearchQuery.isCurrent("a", 3, "an", 4));
        assertFalse(UserSearchQuery.isCurrent("a", 4, "an", 4));
        assertTrue(UserSearchQuery.isCurrent("an", 4, " an ", 4));
    }

    @Test
    public void perUserDeletionIsScopedToUid() {
        ChatMessage message = new ChatMessage();
        message.setDeletedFor(java.util.Collections.singletonList("uid-a"));
        assertTrue(message.isDeletedFor("uid-a"));
        assertFalse(message.isDeletedFor("uid-b"));
    }

    @Test
    public void recalledMessageUsesGenericDisplayText() {
        ChatMessage message = new ChatMessage();
        message.setText("");
        message.setRecalled(true);
        assertEquals("Tin nhắn đã được thu hồi", message.getDisplayText());
    }

    @Test
    public void imageMessageRequiresFirebaseStorageDownloadUrl() {
        assertTrue(ChatRepository.isValidStorageImageUrl(
                "https://firebasestorage.googleapis.com/v0/b/demo/o/Chats%2Ffile.jpg?alt=media&token=x"));
        assertFalse(ChatRepository.isValidStorageImageUrl("https://example.com/photo.jpg"));
        assertFalse(ChatRepository.isValidStorageImageUrl(""));
    }

    @Test
    public void localMessageSearchMatchesTextAndExcludesHiddenOrRecalledMessages() {
        ChatMessage message = new ChatMessage();
        message.setType("text");
        message.setText("Hello Meeto");
        assertTrue(message.matchesVisibleTextQuery("uid-a", "meeto"));
        message.setDeletedFor(java.util.Collections.singletonList("uid-a"));
        assertFalse(message.matchesVisibleTextQuery("uid-a", "meeto"));
        assertTrue(message.matchesVisibleTextQuery("uid-b", "meeto"));
        message.setRecalled(true);
        assertFalse(message.matchesVisibleTextQuery("uid-b", "meeto"));
        message.setRecalled(false);
        message.setType("image");
        assertFalse(message.matchesVisibleTextQuery("uid-b", "meeto"));
    }

    @Test
    public void unreadStateIsTrackedPerParticipantAndClearedByReadMarker() {
        Chat chat = new Chat();
        java.util.Map<String, Timestamp> incoming = new java.util.HashMap<>();
        java.util.Map<String, Timestamp> read = new java.util.HashMap<>();
        incoming.put("uid-a", new Timestamp(200, 0));
        read.put("uid-a", new Timestamp(199, 0));
        chat.setLastIncomingMessageAtByUid(incoming);
        chat.setLastReadAtByUid(read);
        assertTrue(chat.isUnreadFor("uid-a"));
        assertFalse(chat.isUnreadFor("uid-b"));
        read.put("uid-a", new Timestamp(200, 0));
        assertFalse(chat.isUnreadFor("uid-a"));
    }

    @Test
    public void ownLatestMessageDoesNotClearAnOlderUnreadIncomingMarker() {
        Chat chat = new Chat();
        chat.setLastMessageSenderId("uid-a");
        chat.setLastMessageAt(new Timestamp(300, 0));
        java.util.Map<String, Timestamp> incoming = new java.util.HashMap<>();
        incoming.put("uid-a", new Timestamp(200, 0));
        chat.setLastIncomingMessageAtByUid(incoming);
        assertTrue(chat.isUnreadFor("uid-a"));
    }

    @Test
    public void legacyUnreadFallbackOnlyMarksLatestIncomingMessage() {
        Chat chat = new Chat();
        chat.setLastMessageSenderId("uid-b");
        chat.setLastMessageAt(new Timestamp(300, 0));
        assertTrue(chat.isUnreadFor("uid-a"));
        chat.setLastMessageSenderId("uid-a");
        assertFalse(chat.isUnreadFor("uid-a"));
    }
}
