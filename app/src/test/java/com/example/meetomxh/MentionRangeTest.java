package com.example.meetomxh;

import static org.junit.Assert.*;
import org.junit.Test;
import com.example.meetomxh.ui.MentionRange;

public class MentionRangeTest {
    @Test public void utf16MentionOffsetsSurviveVietnameseAndEmoji(){
        String body="🌱 Chào bạn @Hồ Long 😀";String label="@Hồ Long";int start=body.indexOf(label),end=start+label.length();
        assertEquals(label,MentionRange.textAt(body,start,end));
        assertNull(MentionRange.textAt(body,1,3)); // cannot split the leading surrogate pair
        assertEquals(label+" ",MentionRange.textAt(body,start,end+1));
        assertNull(MentionRange.textAt(body,start,body.length()+1));
    }
    @Test public void duplicateDisplayNamesRemainSeparateChosenSpans(){
        String body="@An và @An";int first=0,second=body.lastIndexOf('@');
        assertEquals("@An",MentionRange.textAt(body,first,first+3));
        assertEquals("@An",MentionRange.textAt(body,second,second+3));
        assertNotEquals(first,second);
    }
}
