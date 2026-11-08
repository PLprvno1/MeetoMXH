package com.example.meetomxh.music;

import org.json.*;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MusicLibraryRepositoryTest {
    @Test public void loadsFiftyTracksThroughBothCapabilityGates()throws Exception {
        AtomicInteger calls=new AtomicInteger();
        MusicLibraryRepository.Catalog catalog=MusicLibraryRepository.fetchCatalog((name,args)->{
            calls.incrementAndGet();
            if(name.equals("get_music_publish_capabilities"))return new JSONObject().put("contract_version",1).put("enabled",true);
            if(name.equals("get_music_library_capabilities"))return MusicLibraryFixtures.capabilities(50);
            assertEquals("get_music_catalog",name);assertEquals(50,args.getInt("p_limit"));assertTrue(args.isNull("p_after_sort_order"));
            return MusicLibraryFixtures.page(1,50,false);
        });
        assertEquals(3,calls.get());assertEquals(50,catalog.tracks.size());assertFalse(catalog.offline);
    }
    @Test public void passesExactCursorAndDoesNotStopAtFirstFifty()throws Exception {
        AtomicInteger pages=new AtomicInteger();
        MusicLibraryRepository.Catalog result=MusicLibraryRepository.fetchCatalog((name,args)->{
            if(name.equals("get_music_publish_capabilities"))return new JSONObject().put("contract_version",1).put("enabled",true);
            if(name.equals("get_music_library_capabilities"))return MusicLibraryFixtures.capabilities(51);
            if(pages.getAndIncrement()==0)return MusicLibraryFixtures.page(1,50,true);
            assertEquals("test_catalog_v1",args.getString("p_after_catalog_version"));assertEquals(50,args.getInt("p_after_sort_order"));
            return MusicLibraryFixtures.page(51,51,false);
        });
        assertEquals(51,result.tracks.size());assertEquals(2,pages.get());
    }
    @Test public void disabledLibraryReturnsEmptyWithoutCatalogCall()throws Exception {
        MusicLibraryRepository.Catalog result=MusicLibraryRepository.fetchCatalog((name,args)->{
            if(name.equals("get_music_publish_capabilities"))return new JSONObject().put("contract_version",1).put("enabled",true);
            assertEquals("get_music_library_capabilities",name);return MusicLibraryFixtures.capabilities(0);
        });
        assertTrue(result.tracks.isEmpty());
    }
    @Test public void rejectsMalformedMimeDuplicateRowsAndBadCursor()throws Exception {
        JSONObject badMime=MusicLibraryFixtures.page(1,1,false);badMime.getJSONArray("items").getJSONObject(0).put("mime_type","audio/mp3");
        rejectPage(badMime,1);
        JSONObject duplicate=MusicLibraryFixtures.page(1,2,false);duplicate.getJSONArray("items").put(1,duplicate.getJSONArray("items").get(0));rejectPage(duplicate,2);
        JSONObject cursor=MusicLibraryFixtures.page(1,50,true);cursor.getJSONObject("next_cursor").put("sort_order",49);rejectPage(cursor,51);
        rejectPage(MusicLibraryFixtures.page(1,1,false),50);
    }
    private void rejectPage(JSONObject page,int count)throws Exception {
        try{
            MusicLibraryRepository.fetchCatalog((name,args)->{
                if(name.equals("get_music_publish_capabilities"))return new JSONObject().put("contract_version",1).put("enabled",true);
                if(name.equals("get_music_library_capabilities"))return MusicLibraryFixtures.capabilities(count);
                return page;
            });fail("Invalid catalog accepted");
        }catch(JSONException expected){}
    }
    @Test public void metadataHasExactlySixteenKeysAllowsLoopAndDraftRoundTrips()throws Exception {
        MusicLibraryTrack track=MusicLibraryTrack.parse(MusicLibraryFixtures.row(1,new byte[]{1,2,3,4}));
        OpenverseTrack selected=OpenverseTrack.library(track,"/private/music.ogg");selected.startMs=35000;
        JSONObject metadata=selected.metadata(60000);
        assertEquals(16,metadata.length());assertTrue(MusicLibraryTrack.validMetadata(metadata));
        assertFalse(metadata.has("audio_url"));assertFalse(metadata.has("loop_enabled"));
        OpenverseTrack restored=OpenverseTrack.fromDraft(new JSONObject(selected.draftJson().toString()));
        assertNotNull(restored);assertEquals(track.id,restored.id);assertEquals(35000,restored.startMs);
        assertEquals(track.sha256,restored.library.sha256);assertEquals(35,restored.musicVolume);
        metadata.put("start_offset_ms",36000);assertFalse(MusicLibraryTrack.validMetadata(metadata));
        metadata.put("start_offset_ms",0).put("used_duration_ms",60001);assertFalse(MusicLibraryTrack.validMetadata(metadata));
        metadata.put("used_duration_ms",1000).put("extra",true);assertFalse(MusicLibraryTrack.validMetadata(metadata));
    }
    @Test public void legacyDraftAndThirteenFieldValidationRemainValid()throws Exception {
        OpenverseTrack legacy=new OpenverseTrack("00112233-4455-4677-8899-aabbccddeeff","Old song","Artist","provider","openverse",
                "https://example.org/source","https://example.org/audio.ogg","cc0","https://creativecommons.org/publicdomain/zero/1.0/","Artist",36000,"/private/legacy.audio");
        assertEquals(13,legacy.metadata(15000).length());assertTrue(OpenverseMusicClient.validPublishedMetadata(legacy.metadata(15000)));
        assertFalse(OpenverseMusicClient.validPublishedMetadata(legacy.metadata(60000)));
        OpenverseTrack restored=OpenverseTrack.fromDraft(new JSONObject(legacy.draftJson().toString()));
        assertNotNull(restored);assertNull(restored.library);assertEquals(legacy.audioUrl,restored.audioUrl);
    }
}
