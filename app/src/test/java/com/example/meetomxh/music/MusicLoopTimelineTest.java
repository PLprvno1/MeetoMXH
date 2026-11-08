package com.example.meetomxh.music;

import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class MusicLoopTimelineTest {
    @Test public void segmentWithoutLoopKeepsOffsetAndDuration(){
        List<MusicLoopTimeline.Segment> plan=MusicLoopTimeline.plan(118_445_000,10_000_000,15_000_000);
        assertEquals(1,plan.size());assertEquals(10_000_000,plan.get(0).sourceStartUs);
        assertEquals(25_000_000,plan.get(0).sourceEndUs);assertEquals(0,plan.get(0).outputStartUs);
    }
    @Test public void thirtySecondOffsetInThirtySixSecondTrackPlaysSixThenNine(){
        List<MusicLoopTimeline.Segment> plan=MusicLoopTimeline.plan(36_000_000,30_000_000,15_000_000);
        assertEquals(2,plan.size());assertEquals(6_000_000,plan.get(0).durationUs());
        assertEquals(0,plan.get(1).sourceStartUs);assertEquals(9_000_000,plan.get(1).sourceEndUs);
        assertEquals(6_000_000,plan.get(1).outputStartUs);assertTimeline(plan,36_000_000,30_000_000,15_000_000);
    }
    @Test public void shortTrackRepeatsFinitelyAndIncludesTinyTail(){
        List<MusicLoopTimeline.Segment> plan=MusicLoopTimeline.plan(1_000_000,999_000,60_000_000);
        assertEquals(61,plan.size());assertEquals(1000,plan.get(0).durationUs());
        assertEquals(999_000,plan.get(60).durationUs());assertTimeline(plan,1_000_000,999_000,60_000_000);
    }
    @Test public void endingExactlyAtEofCreatesNoEmptyExtraItem(){
        List<MusicLoopTimeline.Segment> plan=MusicLoopTimeline.plan(36_000_000,30_000_000,6_000_000);
        assertEquals(1,plan.size());assertEquals(36_000_000,plan.get(0).sourceEndUs);
    }
    @Test public void microsecondSourceDurationDoesNotIntroduceRoundedMillisecondGaps(){
        List<MusicLoopTimeline.Segment> plan=MusicLoopTimeline.plan(35_999_601,30_000_000,15_000_000);
        assertEquals(5_999_601,plan.get(0).durationUs());assertEquals(9_000_399,plan.get(1).durationUs());
        assertTimeline(plan,35_999_601,30_000_000,15_000_000);
    }
    @Test public void invalidOrUnboundedTimelinesAreRejected(){
        for(long[] args:new long[][]{{0,0,1_000_000},{1_000_000,-1,1_000_000},{1_000_000,1_000_000,1_000_000},
                {1_000_000,0,999_999},{1_000_000,0,60_000_001},{1,0,1_000_000}}){
            try{MusicLoopTimeline.plan(args[0],args[1],args[2]);fail("Invalid loop accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void joinRampsSuppressDiscontinuityWithoutChangingTimelineLength(){
        int rate=48000;long duration=6_000_000,frames=288000;
        assertEquals(1f,MusicLoopTimeline.joinGain(0,rate,duration,false,true),0);
        assertEquals(0f,MusicLoopTimeline.joinGain(frames-1,rate,duration,false,true),0);
        assertEquals(0f,MusicLoopTimeline.joinGain(0,rate,duration,true,false),0);
        assertEquals(1f,MusicLoopTimeline.joinGain(96,rate,duration,true,false),0);
        float previous=0;
        for(int i=0;i<96;i++){float gain=MusicLoopTimeline.joinGain(i,rate,duration,true,false);assertTrue(gain>=previous&&gain<=1);previous=gain;}
    }
    @Test public void v2KeepsOffsetAndSixteenKeysWhileV1RemainsNonLooping()throws Exception{
        MusicLibraryTrack library=MusicLibraryTrack.parse(MusicLibraryFixtures.row(1,new byte[]{1,2,3,4}));
        OpenverseTrack selected=OpenverseTrack.library(library,"/private/audio.ogg");selected.startMs=30000;
        selected.selectedDurationMs=15000;selected.musicVolume=72;selected.sourceVolume=0;
        assertEquals(30000,MusicComposition.effectiveStartMs(selected,15000));
        JSONObject metadata=selected.metadata(MusicComposition.usableDurationMs(selected,15023));
        assertEquals(16,metadata.length());assertEquals(15000,metadata.getLong("used_duration_ms"));
        assertEquals(30000,metadata.getLong("start_offset_ms"));assertEquals(72,metadata.getInt("music_volume"));
        assertEquals(0,metadata.getInt("source_volume"));assertTrue(OpenverseMusicClient.validPublishedMetadata(metadata,15023));
        assertFalse(metadata.has("local_path"));assertFalse(metadata.has("audio_url"));
        OpenverseTrack legacy=new OpenverseTrack("00112233-4455-4677-8899-aabbccddeeff","Old","Artist","provider","openverse",
                "https://example.org/source","https://example.org/audio.ogg","cc0","https://creativecommons.org/publicdomain/zero/1.0/","Artist",36000,"/private/old.audio");
        legacy.startMs=30000;assertEquals(21000,MusicComposition.effectiveStartMs(legacy,15000));
        assertFalse(OpenverseMusicClient.validPublishedMetadata(legacy.metadata(15000)));
        legacy.startMs=21000;assertTrue(OpenverseMusicClient.validPublishedMetadata(legacy.metadata(15000)));
    }
    private void assertTimeline(List<MusicLoopTimeline.Segment> plan,long duration,long start,long used){
        long output=0;
        for(MusicLoopTimeline.Segment segment:plan){
            assertEquals(output,segment.outputStartUs);assertTrue(segment.durationUs()>0);
            assertEquals((start+output)%duration,segment.sourceStartUs);assertTrue(segment.sourceEndUs<=duration);
            long sample=segment.durationUs()/2;
            assertEquals((start+output+sample)%duration,segment.sourceStartUs+sample);output+=segment.durationUs();
        }
        assertEquals(used,output);
    }
}
