package com.example.meetomxh;

import android.view.ContextThemeWrapper;
import android.widget.Button;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.music.MusicMiniPlayer;
import com.example.meetomxh.music.OpenverseTrack;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MusicPreviewTest {
    private static final class FakePlayer {
        boolean requested,failSeek;
        int state=Player.STATE_BUFFERING,releases,plays,pauses;
        long position;
        PlaybackException failure;
        Player.Listener listener;
        final Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            switch(method.getName()){
                case "getPlayWhenReady":return requested;
                case "isPlaying":return requested&&state==Player.STATE_READY;
                case "getPlaybackState":return state;
                case "getDuration":return 20_000L;
                case "getCurrentPosition":return position;
                case "getPlayerError":return failure;
                case "addListener":listener=(Player.Listener)args[0];return null;
                case "removeListener":return null;
                case "seekTo":if(failSeek)throw new IllegalStateException("test seek failure");position=(Long)args[0];return null;
                case "play":requested=true;plays++;return null;
                case "pause":requested=false;pauses++;return null;
                case "release":releases++;return null;
                case "equals":return proxy==args[0];
                case "hashCode":return System.identityHashCode(proxy);
                default:throw new AssertionError("Unexpected player operation: "+method.getName());
            }
        });
    }
    private MusicMiniPlayer mini(){return new MusicMiniPlayer(new ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().getTargetContext(),R.style.Theme_MeetoMXH),null);}
    private void show(MusicMiniPlayer mini,FakePlayer player){
        OpenverseTrack track=new OpenverseTrack("test","Test track","Artist","","","","","cc0","","",20_000,"");
        mini.show(track,MusicMiniPlayer.Mode.TRACK,player.player,20_000,0,true,()->{});
    }
    private PlaybackException failure(){return new PlaybackException("Local test",null,PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND);}

    @Test public void pauseWhileBufferingCancelsPlaybackAndEndedTrackCanRestart(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            MusicMiniPlayer mini=mini();FakePlayer player=new FakePlayer();
            try{
                show(mini,player);assertTrue(player.requested);
                assertEquals("Tạm dừng",((Button)mini.findViewById(R.id.mini_music_play)).getText().toString());
                mini.toggle();assertFalse(player.requested);assertEquals(1,player.pauses);
                mini.toggle();assertTrue(player.requested);
                player.state=Player.STATE_ENDED;player.position=20_000;
                mini.toggle();assertEquals(0,player.position);assertTrue(player.requested);assertEquals(3,player.plays);
            }finally{mini.close();}
        });
    }
    @Test public void replacingTrackIgnoresLateErrorsAndReleasesEachPlayerOnce(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            MusicMiniPlayer mini=mini();FakePlayer first=new FakePlayer(),second=new FakePlayer();AtomicInteger errors=new AtomicInteger();
            mini.setOnError(message->errors.incrementAndGet());
            try{
                show(mini,first);Player.Listener old=first.listener;show(mini,second);
                assertEquals(1,first.releases);old.onPlayerError(failure());assertTrue(mini.isOpen());assertEquals(0,errors.get());
                second.listener.onPlayerError(failure());assertFalse(mini.isOpen());assertEquals(1,errors.get());
                mini.close();assertEquals(1,second.releases);
            }finally{mini.close();}
        });
    }
    @Test public void errorBeforeAttachIsReportedAndReleased(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            MusicMiniPlayer mini=mini();FakePlayer player=new FakePlayer();player.failure=failure();AtomicInteger errors=new AtomicInteger();
            mini.setOnError(message->errors.incrementAndGet());show(mini,player);
            assertFalse(mini.isOpen());assertEquals(1,errors.get());assertEquals(0,player.plays);
            mini.close();assertEquals(1,player.releases);
        });
    }
    @Test public void failureDuringSeekDoesNotLeaveOwnedPlayerOrReleaseTwice(){
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            MusicMiniPlayer mini=mini();FakePlayer player=new FakePlayer();player.failSeek=true;
            try{show(mini,player);fail("Expected seek failure");}catch(IllegalStateException expected){}
            assertFalse(mini.isOpen());mini.close();assertEquals(1,player.releases);
        });
    }
}
