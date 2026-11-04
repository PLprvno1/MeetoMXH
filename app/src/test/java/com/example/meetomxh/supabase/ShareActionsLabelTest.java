package com.example.meetomxh.supabase;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ShareActionsLabelTest {
    @Test public void nullConversationTitleFallsBackToPeerName() {
        assertEquals("Admin", ShareActions.conversationLabel("null", "Admin", 0));
    }

    @Test public void blankLabelsUseStableConversationFallback() {
        assertEquals("Cuộc trò chuyện 3", ShareActions.conversationLabel(null, "  ", 2));
    }

    @Test public void explicitConversationTitleWins() {
        assertEquals("Study group", ShareActions.conversationLabel(" Study group ", "Admin", 0));
    }
}
