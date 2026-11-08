package com.example.meetomxh.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NotificationItemTest {
    private org.json.JSONObject groupMessage() throws Exception {
        return new org.json.JSONObject().put("kind","message").put("target_type","conversation")
                .put("actor_name","Minh").put("conversation_kind","group");
    }

    @Test public void groupMessageShowsGroupThenSenderAndContent() throws Exception {
        NotificationItem item=new NotificationItem(groupMessage().put("conversation_title","Nhóm bạn thân")
                .put("message_preview","Tối nay gặp nhé!"));
        assertEquals("Nhóm bạn thân\nMinh: Tối nay gặp nhé!",item.message());
        org.junit.Assert.assertTrue(item.isGroupMessage());
    }

    @Test public void unnamedGroupAndUnavailableMessageHaveSafeFallbacks() throws Exception {
        NotificationItem item=new NotificationItem(groupMessage().put("conversation_title",org.json.JSONObject.NULL)
                .put("message_preview",org.json.JSONObject.NULL));
        assertEquals("Nhóm chat\nMinh: Tin nhắn không còn khả dụng.",item.message());
    }

    @Test public void directMessagesAndOlderPayloadsKeepTheirText() throws Exception {
        org.json.JSONObject row=groupMessage().put("conversation_kind","private").put("message_preview","Xin chào");
        assertEquals("Minh đã gửi tin nhắn cho bạn.",new NotificationItem(row).message());
        row.remove("conversation_kind");
        assertEquals("Minh đã gửi tin nhắn cho bạn.",new NotificationItem(row).message());
    }

    @Test public void groupInvitesKeepTheirSpecificText() throws Exception {
        assertEquals("Minh đã mời bạn vào nhóm chat.",new NotificationItem(groupMessage().put("kind","group_invite")).message());
    }

    @Test public void messageNotificationNamesTheSender() {
        assertEquals("Test User đã gửi tin nhắn cho bạn.", NotificationItem.messageFor("message", "Test User"));
    }

    @Test public void otherImplementedNotificationKindsHaveSpecificText() {
        assertEquals("Test User đã chia sẻ bài viết của bạn.", NotificationItem.messageFor("share", "Test User"));
        org.junit.Assert.assertTrue(NotificationItem.isRetired("group"));
        org.junit.Assert.assertFalse(NotificationItem.isRetired("group_invite"));
        assertEquals("Bạn có cập nhật kiểm duyệt mới.", NotificationItem.messageFor("moderation", "Test User"));
    }
}
