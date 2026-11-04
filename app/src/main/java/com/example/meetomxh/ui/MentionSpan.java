package com.example.meetomxh.ui;

import android.text.style.ForegroundColorSpan;

public final class MentionSpan extends ForegroundColorSpan {
    public final String userId;
    public final String label;

    public MentionSpan(String userId, String label) {
        super(0xff1877f2);
        this.userId = userId;
        this.label = label;
    }
}
