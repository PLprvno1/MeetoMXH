package com.example.meetomxh.ui;

/** UTF-16 span helpers shared by the composer payload and renderer. */
public final class MentionRange {
    private MentionRange() {}
    public static String textAt(String text,int startUtf16,int endUtf16){
        if(text==null||startUtf16<0||endUtf16<=startUtf16||endUtf16>text.length())return null;
        if(startUtf16>0&&Character.isLowSurrogate(text.charAt(startUtf16))&&Character.isHighSurrogate(text.charAt(startUtf16-1)))return null;
        if(endUtf16<text.length()&&Character.isLowSurrogate(text.charAt(endUtf16))&&Character.isHighSurrogate(text.charAt(endUtf16-1)))return null;
        return text.substring(startUtf16,endUtf16);
    }
}
