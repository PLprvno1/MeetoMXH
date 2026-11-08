package com.example.meetomxh.music;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Finite, contiguous source slices. Units are microseconds, never decoder packet boundaries. */
public final class MusicLoopTimeline {
    public static final class Segment {
        public final long sourceStartUs,sourceEndUs,outputStartUs;
        Segment(long start,long end,long output){sourceStartUs=start;sourceEndUs=end;outputStartUs=output;}
        public long durationUs(){return sourceEndUs-sourceStartUs;}
    }
    private MusicLoopTimeline(){}
    public static List<Segment> plan(long sourceDurationUs,long startUs,long usedUs){
        if(sourceDurationUs<=0||startUs<0||startUs>=sourceDurationUs||usedUs<1_000_000||usedUs>60_000_000)
            throw new IllegalArgumentException("Đoạn nhạc không hợp lệ.");
        List<Segment> segments=new ArrayList<>();long remaining=usedUs,output=0,at=startUs;
        while(remaining>0){
            if(segments.size()>=64)throw new IllegalArgumentException("Nguồn nhạc quá ngắn.");
            long length=Math.min(remaining,sourceDurationUs-at);
            segments.add(new Segment(at,at+length,output));output+=length;remaining-=length;at=0;
        }
        return Collections.unmodifiableList(segments);
    }
    /** A 2 ms amplitude ramp at joins; no inserted silence, overlap, or time shift. */
    static float joinGain(long frame,int sampleRate,long durationUs,boolean fadeIn,boolean fadeOut){
        long frames=Math.max(1,Math.round(durationUs*sampleRate/1_000_000d));
        long ramp=Math.min(Math.max(1,Math.round(sampleRate*.002)),Math.max(1,frames/(fadeIn&&fadeOut?2:1)));
        if(ramp<2)return 1f;
        float gain=1f;
        if(fadeIn)gain=Math.min(gain,frame/(float)(ramp-1));
        if(fadeOut)gain=Math.min(gain,(frames-1-frame)/(float)(ramp-1));
        return Math.max(0f,Math.min(1f,gain));
    }
}
