package com.example.meetomxh.music;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import static org.junit.Assert.*;

public class MusicLibraryCacheTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final byte[] bytes={1,2,3,4};
    private MusicLibraryTrack track(int id)throws Exception{return MusicLibraryTrack.parse(MusicLibraryFixtures.row(id,bytes));}
    @Test public void cacheHitIsVerifiedAndSameSizeCorruptionIsRejected()throws Exception {
        MusicLibraryCache cache=new MusicLibraryCache(temp.newFolder(),120);MusicLibraryTrack track=track(1);
        assertNull(cache.find(track));File file=cache.receive(new ByteArrayInputStream(bytes),track,()->{});
        assertEquals(file,cache.find(track));
        try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{4,3,2,1});}
        assertNull(cache.find(track));assertFalse(file.exists());
    }
    @Test public void failedDownloadNeverLeavesPartOrPlayableTarget()throws Exception {
        File root=temp.newFolder();MusicLibraryCache cache=new MusicLibraryCache(root,120);
        InputStream broken=new InputStream(){int n;public int read()throws IOException{if(n++<2)return 1;throw new IOException("network failure");}};
        try{cache.receive(broken,track(1),()->{});fail();}catch(IOException expected){}
        assertEquals(0,root.listFiles().length);assertNull(cache.find(track(1)));
        try{cache.receive(new ByteArrayInputStream(new byte[]{1,2}),track(1),()->{});fail();}catch(IOException expected){}
        assertEquals(0,root.listFiles().length);
        try{cache.receive(new ByteArrayInputStream(new byte[]{4,3,2,1}),track(1),()->{});fail();}catch(IOException expected){}
        assertEquals(0,root.listFiles().length);
        try{cache.receive(new ByteArrayInputStream(new byte[]{1,2,3,4,5}),track(1),()->{});fail();}catch(IOException expected){}
        assertEquals(0,root.listFiles().length);
    }
    @Test public void cancellationCleansPartialFile()throws Exception {
        File root=temp.newFolder();MusicLibraryCache cache=new MusicLibraryCache(root,120);
        try{cache.receive(new ByteArrayInputStream(bytes),track(1),()->{throw new IOException("cancelled");});fail();}catch(IOException expected){}
        assertEquals(0,root.listFiles().length);
    }
    @Test public void pinnedFileSurvivesEvictionUntilAllLeasesClose()throws Exception {
        File root=temp.newFolder();MusicLibraryCache cache=new MusicLibraryCache(root,4);
        File first=cache.receive(new ByteArrayInputStream(bytes),track(1),()->{});
        MusicLibraryCache.Lease a=cache.acquire(track(1)),b=cache.acquire(track(1));assertNotNull(a);assertNotNull(b);
        first.setLastModified(System.currentTimeMillis()-120000);a.close();
        try{cache.receive(new ByteArrayInputStream(bytes),track(2),()->{});fail("Pinned audio evicted");}catch(IOException expected){}
        assertTrue(first.exists());b.close();b.close();
        File second=cache.receive(new ByteArrayInputStream(bytes),track(2),()->{});
        assertFalse(first.exists());assertTrue(second.exists());assertEquals(4,second.length());
    }
    @Test public void cacheIdentityIncludesVersionIdAndChecksum()throws Exception {
        MusicLibraryTrack track=track(1);
        assertNotEquals(MusicLibraryCache.key(track),MusicLibraryCache.key(track(2)));
        assertNotEquals(MusicLibraryCache.key(track),MusicLibraryCache.key(MusicLibraryTrack.parse(track.json().put("catalog_version","test_catalog_v2"))));
        assertNotEquals(MusicLibraryCache.key(track),MusicLibraryCache.key(MusicLibraryTrack.parse(track.json().put("sha256",MusicLibraryCache.digest(new byte[]{9})))));
    }
}
