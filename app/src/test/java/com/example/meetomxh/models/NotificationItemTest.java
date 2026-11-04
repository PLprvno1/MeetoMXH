package com.example.meetomxh.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NotificationItemTest {
    @Test public void messageNotificationNamesTheSender() {
        assertEquals("Test User đã gửi tin nhắn cho bạn.", NotificationItem.messageFor("message", "Test User"));
    }

    @Test public void otherImplementedNotificationKindsHaveSpecificText() {
        assertEquals("Test User đã chia sẻ bài viết của bạn.", NotificationItem.messageFor("share", "Test User"));
        assertEquals("Test User đã có hoạt động mới trong nhóm.", NotificationItem.messageFor("group", "Test User"));
        assertEquals("Bạn có cập nhật kiểm duyệt mới.", NotificationItem.messageFor("moderation", "Test User"));
    }
}
