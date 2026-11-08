package com.example.meetomxh.music;

import org.junit.Test;
import static org.junit.Assert.fail;

public class MusicSourceDurationTest {
    @Test public void acceptsActualVorbisReaderRoundingDifferences(){
        MusicComposition.validateAudioDurationUs(144061791,144055);
        MusicComposition.validateAudioDurationUs(48005215,47998);
        MusicComposition.validateAudioDurationUs(64000000,63993);
        MusicComposition.validateAudioDurationUs(118445328,118445);
        MusicComposition.validateAudioDurationUs(128011541,128012);
    }
    @Test public void rejectsMissingTruncatedAndDifferentDuration(){
        for(long[] values:new long[][]{{0,144055},{143000000,144055},{144106000,144055},{144004000,144055},{1,-1}}){
            try{MusicComposition.validateAudioDurationUs(values[0],values[1]);fail("Expected incompatible duration to fail");}
            catch(IllegalStateException expected){}
        }
    }
}
