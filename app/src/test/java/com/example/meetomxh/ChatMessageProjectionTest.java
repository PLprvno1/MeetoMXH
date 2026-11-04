package com.example.meetomxh;

import com.example.meetomxh.models.ChatMessage;
import com.example.meetomxh.supabase.ServerTime;
import org.junit.Test;

import static org.junit.Assert.*;

public class ChatMessageProjectionTest {
    @Test public void forwardedAndEditedMessageStateIsRendered() {
        ChatMessage message = new ChatMessage();
        message.setText("Hello after edit"); message.setType("text");
        message.setForwarded(true); message.setEditedAt(new ServerTime(1793707500L, 0));
        assertEquals("Hello after edit", message.getText());
        assertEquals("text", message.getType());
        assertTrue(message.isForwarded());
        assertNotNull(message.getEditedAt());
        assertFalse(message.isRecalled());
    }

    @Test public void recalledMessageNeverShowsOriginalBody() {
        ChatMessage message = new ChatMessage();
        message.setText(""); message.setType("text"); message.setRecalled(true);
        assertTrue(message.isRecalled());
        assertEquals("Tin nhắn đã được thu hồi", message.getDisplayText());
    }

    @Test public void availableSharedVideoRetainsAuthorizedAssetForViewerRouting() {
        ChatMessage message = new ChatMessage();
        message.setSharedPost("post-id", true, true);
        message.setSharedPostVideoAssetId("video-asset-id");
        assertEquals("post-id", message.getSharedPostId());
        assertEquals("video-asset-id", message.getSharedPostVideoAssetId());
        assertTrue(message.isSharedPostAvailable());
    }
}
