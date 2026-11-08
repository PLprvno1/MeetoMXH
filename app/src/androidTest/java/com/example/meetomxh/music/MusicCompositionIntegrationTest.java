package com.example.meetomxh.music;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.supabase.SessionManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import com.example.meetomxh.ui.ReelClipExporter;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MusicCompositionIntegrationTest {
    @Test public void cachedMusicAndVideoBuildRealComposition() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        MusicLibraryRepository.Catalog catalog=new MusicLibraryRepository(context).cached(SessionManager.getInstance().generation());
        assertNotNull("Needs the device's downloaded music catalog",catalog);
        List<File> videos=videos(context);assertFalse("Needs an existing local reel source",videos.isEmpty());
        int tested=0,failed=0;
        for(MusicLibraryTrack row:catalog.tracks){
            try(MusicLibraryCache.Lease lease=new MusicLibraryCache(context).acquire(row)){
                if(lease==null)continue;
                Log.i("MusicIntegration","track="+row.id+" catalogMs="+row.durationMs+" containerMs="+durationMs(lease.file)+" extractorUs="+extractorUs(lease.file));
                OpenverseTrack track=OpenverseTrack.library(row,lease.file.getAbsolutePath());tested++;
                for(File video:videos){
                    long videoMs=Math.min(60_000,durationMs(video));Log.i("MusicIntegration","video containerMs="+videoMs+" extractorUs="+extractorUs(video));
                    for(int offset:new int[]{0,(int)row.durationMs-500}){
                        track.startMs=offset;
                        try{Composition composition=MusicComposition.video(video,0,videoMs,true,0,track,35,Collections.emptyList(),0);java.lang.reflect.Method json=composition.getClass().getDeclaredMethod("toJsonObject");json.setAccessible(true);json.invoke(composition);}
                        catch(Exception error){failed++;Log.w("MusicIntegration","composition failed track="+row.id+" type="+error.getClass().getSimpleName());}
                    }
                }
            }
        }
        assertTrue(tested>0);assertEquals("Actual cached compositions failed",0,failed);
    }
    @Test public void realMixedPreviewAdvancesAndReelExportContainsVideoAndAudio() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        MusicLibraryRepository.Catalog catalog=new MusicLibraryRepository(context).cached(SessionManager.getInstance().generation());
        assertNotNull(catalog);assertFalse(videos(context).isEmpty());
        for(File video:videos(context)){
        long length=Math.min(3000,durationMs(video));
        for(String id:new String[]{"meeto_music_001","meeto_music_002"}){
            MusicLibraryTrack row=null;for(MusicLibraryTrack candidate:catalog.tracks)if(id.equals(candidate.id))row=candidate;
            assertNotNull(row);
            try(MusicLibraryCache.Lease lease=new MusicLibraryCache(context).acquire(row)){
                assertNotNull("Download the selected music before this integration test",lease);
                OpenverseTrack track=OpenverseTrack.library(row,lease.file.getAbsolutePath());
                if(id.endsWith("001"))track.startMs=(int)row.durationMs-500;
                Composition composition=MusicComposition.video(video,0,length,true,0,track,35,Collections.emptyList(),0);
                CountDownLatch playing=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
                CompositionPlayer[] player=new CompositionPlayer[1];Handler main=new Handler(Looper.getMainLooper());
                Runnable ticker=new Runnable(){public void run(){
                    if(player[0]!=null&&player[0].getCurrentPosition()>=300){playing.countDown();return;}
                    main.postDelayed(this,50);
                }};
                try{
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                        player[0]=MusicComposition.previewPlayer(context);
                        player[0].addListener(new Player.Listener(){public void onPlayerError(PlaybackException failure){error.set(failure.getErrorCodeName());playing.countDown();}});
                        player[0].setComposition(composition);player[0].prepare();player[0].play();main.post(ticker);
                    });
                    assertTrue("Preview did not advance",playing.await(20,TimeUnit.SECONDS));assertNull(error.get());
                }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{main.removeCallbacks(ticker);if(player[0]!=null)player[0].release();});}
                File output=new File(context.getCacheDir(),"music-integration-"+id+"-"+System.nanoTime()+".mp4");
                CountDownLatch exported=new CountDownLatch(1);ReelClipExporter exporter=new ReelClipExporter();
                try{
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->exporter.start(context,video,output,0,length,true,0,606,1280,track,35,new ReelClipExporter.Callback(){
                        public void completed(File file){exported.countDown();}
                        public void failed(String message){error.set(message);exported.countDown();}
                    }));
                    assertTrue("Export timed out",exported.await(45,TimeUnit.SECONDS));assertNull(error.get());assertTrue(output.length()>0);
                    MediaExtractor extractor=new MediaExtractor();int audio=0,visual=0;
                    try{extractor.setDataSource(output.getAbsolutePath());for(int i=0;i<extractor.getTrackCount();i++){
                        MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                        if("audio/mp4a-latm".equals(mime)){audio++;assertTrue(Math.abs(format.getLong(MediaFormat.KEY_DURATION)-length*1000)<=300_000);}
                        if("video/avc".equals(mime))visual++;
                    }}finally{extractor.release();}
                    assertEquals(1,audio);assertEquals(1,visual);long actual=durationMs(output);assertTrue(Math.abs(actual-length)<=250);
                    assertTrue(OpenverseMusicClient.validPublishedMetadata(track.metadata(MusicComposition.usableDurationMs(track,actual)),actual));
                    Log.i("MusicIntegration","preview_and_export_pass track="+id+" durationMs="+actual+" bytes="+output.length());
                }finally{InstrumentationRegistry.getInstrumentation().runOnMainSync(exporter::cancel);output.delete();}
            }
        }
    }
    }
    private static List<File> videos(Context context){
        List<File> result=new ArrayList<>();File[] directories=new File(context.getFilesDir(),"reel-drafts").listFiles();
        if(directories!=null)for(File directory:directories){File file=new File(directory,"source.video");if(file.isFile())result.add(file);}
        return result;
    }
    private static long durationMs(File file)throws Exception{
        MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(file.getAbsolutePath());return Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));}finally{r.release();}
    }
    private static long extractorUs(File file)throws Exception{
        MediaExtractor e=new MediaExtractor();try{e.setDataSource(file.getAbsolutePath());long value=0;
            for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);if(f.containsKey(MediaFormat.KEY_DURATION))value=Math.max(value,f.getLong(MediaFormat.KEY_DURATION));}return value;
        }finally{e.release();}
    }
}
