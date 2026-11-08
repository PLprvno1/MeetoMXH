package com.example.meetomxh.music;

import org.junit.Test;
import static org.junit.Assert.*;

/** Probe-result tests only: these do not create or decode an Android MP4. */
public class StoryMusicExportChecksTest {
    @Test public void acceptsPortraitPhotoMovieWithFullAacTrack(){
        StoryMusicExportChecks.validate(5_000_000,15023,15_000_000,15_023_220,720,1280,"video/avc","audio/mp4a-latm",15000);
    }
    @Test public void acceptsFullLengthVideoMovieWithAac(){
        StoryMusicExportChecks.validate(25_000_000,60000,60_000_000,60_000_000,720,1280,"video/avc","audio/mp4a-latm",60000);
    }
    @Test public void rejectsAudioEndingAtFirstTailInsteadOfLooping(){reject(15000,15_000_000,6_000_000,"video/avc","audio/mp4a-latm",720,1280);}
    @Test public void rejectsWrongCodecMissingAudioShapeAndDuration(){
        reject(15000,15_000_000,0,"video/avc","",720,1280);
        reject(15000,15_000_000,15_000_000,"video/hevc","audio/mp4a-latm",720,1280);
        reject(15000,15_000_000,15_000_000,"video/avc","audio/mpeg",720,1280);
        reject(15000,15_000_000,15_000_000,"video/avc","audio/mp4a-latm",1280,720);
        reject(16000,16_000_000,16_000_000,"video/avc","audio/mp4a-latm",720,1280);
    }
    @Test public void validatesSelectedVolumesIndependentlyAndDoesNotAddLoopKey()throws Exception{
        MusicLibraryTrack track=MusicLibraryTrack.parse(MusicLibraryFixtures.row(1,new byte[]{1,2,3,4}));
        for(int source:new int[]{0,35,100})for(int music:new int[]{0,72,100}){
            org.json.JSONObject metadata=track.metadata(35000,60000,music,source);
            assertEquals(16,metadata.length());assertEquals(music,metadata.getInt("music_volume"));
            assertEquals(source,metadata.getInt("source_volume"));assertFalse(metadata.has("loop_enabled"));
        }
    }
    private void reject(long muxed,long video,long audio,String videoMime,String audioMime,int width,int height){
        try{StoryMusicExportChecks.validate(5_000_000,muxed,video,audio,width,height,videoMime,audioMime,15000);fail("Invalid output accepted");}
        catch(IllegalStateException expected){}
    }
}
