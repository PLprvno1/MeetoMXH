package com.example.meetomxh.music;

import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the same clipped, volume-adjusted Media3 timeline for preview and export. */
public final class MusicComposition {
    private MusicComposition(){}

    public static Composition video(File source,long startMs,long endMs,boolean mute,int sourceVolume,
            OpenverseTrack music,int musicVolume,List<androidx.media3.common.Effect> videoEffects,int hdrMode)throws Exception{
        if(source==null||music==null||startMs<0||endMs<=startMs)throw new IllegalArgumentException("Invalid music composition");
        long length=endMs-startMs;int selectedMusic=Math.max(0,Math.min(100,musicVolume));
        boolean sourceHasAudio=hasAudio(source);
        MediaItem videoSource=clipped(source,startMs,endMs);
        List<AudioProcessor> sourceEffects=!mute&&sourceHasAudio&&sourceVolume<100
                ?Collections.singletonList(gain(source,sourceVolume)):Collections.emptyList();
        EditedMediaItem visual=new EditedMediaItem.Builder(videoSource).setDurationUs(mediaDurationUs(source)).setRemoveAudio(mute).setFrameRate(30)
                .setEffects(new Effects(sourceEffects,videoEffects)).build();
        List<EditedMediaItemSequence> sequences=new ArrayList<>();
        sequences.add(!mute&&sourceHasAudio?EditedMediaItemSequence.withAudioAndVideoFrom(Collections.singletonList(visual))
                :EditedMediaItemSequence.withVideoFrom(Collections.singletonList(visual)));
        File audioFile=new File(music.localPath);
        if(!audioFile.isFile())throw new IllegalStateException("Tệp nhạc đã hết hạn khỏi cache. Hãy chọn lại bài nhạc.");
        long actualMs=audioDurationMs(audioFile);
        if(actualMs!=music.durationMs)throw new IllegalStateException("Thời lượng tệp nhạc đã thay đổi. Hãy chọn lại bài nhạc.");
        long offset=effectiveStartMs(music,length);music.startMs=(int)offset;
        long used=compositionDurationMs(music,length);music.selectedDurationMs=used;long segmentEnd=offset+used;
        MediaItem selected=clipped(audioFile,offset,segmentEnd);
        EditedMediaItem musicItem=new EditedMediaItem.Builder(selected).setDurationUs(used*1000L)
                .setEffects(new Effects(Collections.singletonList(gain(audioFile,selectedMusic)),Collections.emptyList())).build();
        sequences.add(EditedMediaItemSequence.withAudioFrom(Collections.singletonList(musicItem)));
        return new Composition.Builder(sequences).setHdrMode(hdrMode).build();
    }

    public static Composition photo(File flattenedImage,int durationMs,OpenverseTrack music,int musicVolume)throws Exception{
        if(flattenedImage==null||!flattenedImage.isFile()||durationMs<5_000||durationMs>60_000||music==null)
            throw new IllegalArgumentException("Invalid still Story composition");
        MediaItem still=new MediaItem.Builder().setUri(Uri.fromFile(flattenedImage)).setImageDurationMs(durationMs).build();
        EditedMediaItem visual=new EditedMediaItem.Builder(still).setFrameRate(30).build();
        File audioFile=new File(music.localPath);if(!audioFile.isFile())throw new IllegalStateException("Tệp nhạc đã hết hạn khỏi cache. Hãy chọn lại bài nhạc.");
        long actualMs=audioDurationMs(audioFile);
        if(actualMs!=music.durationMs)throw new IllegalStateException("Thời lượng tệp nhạc đã thay đổi. Hãy chọn lại bài nhạc.");
        long offset=effectiveStartMs(music,durationMs);music.startMs=(int)offset;
        long used=compositionDurationMs(music,durationMs);music.selectedDurationMs=used;long segmentEnd=offset+used;
        EditedMediaItem musicItem=new EditedMediaItem.Builder(clipped(audioFile,offset,segmentEnd)).setDurationUs(used*1000L)
                .setEffects(new Effects(Collections.singletonList(gain(audioFile,musicVolume)),Collections.emptyList())).build();
        List<EditedMediaItemSequence> sequences=new ArrayList<>();
        sequences.add(EditedMediaItemSequence.withVideoFrom(Collections.singletonList(visual)));
        sequences.add(EditedMediaItemSequence.withAudioFrom(Collections.singletonList(musicItem)));
        return new Composition.Builder(sequences).build();
    }

    public static boolean hasAudio(File file)throws Exception{
        MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(file.getAbsolutePath());for(int i=0;i<extractor.getTrackCount();i++){
            String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/"))return true;}return false;
        }finally{extractor.release();}
    }
    public static int effectiveStartMs(OpenverseTrack track,long outputDurationMs){
        if(track==null||track.durationMs<=0||outputDurationMs<=0||outputDurationMs>60_000)
            throw new IllegalArgumentException("Invalid music output duration");
        long maximumStart=Math.max(0,track.durationMs-outputDurationMs);
        long start=Math.min(maximumStart,Math.max(0,track.startMs));
        if(track.durationMs-start<outputDurationMs-300)
            throw new IllegalStateException("Đoạn nhạc ngắn hơn video quá 300 ms. Hãy chọn bài dài hơn hoặc cắt video ngắn hơn.");
        return (int)start;
    }
    public static long usableDurationMs(OpenverseTrack track,long outputDurationMs){
        if(track==null||track.durationMs<=0||track.startMs<0||track.startMs>=track.durationMs
                ||outputDurationMs<1||outputDurationMs>60_000)
            throw new IllegalArgumentException("Invalid music segment");
        long selected=track.selectedDurationMs>0?Math.min(track.selectedDurationMs,track.durationMs-track.startMs)
                :track.durationMs-track.startMs;
        long used=Math.min(outputDurationMs,selected);
        if(used<1_000||outputDurationMs-used>300)
            throw new IllegalStateException("Đoạn nhạc ngắn hơn video quá 300 ms. Hãy chọn bài dài hơn hoặc cắt video ngắn hơn.");
        return used;
    }
    private static long compositionDurationMs(OpenverseTrack track,long outputDurationMs){
        long used=Math.min(outputDurationMs,track.durationMs-track.startMs);
        if(used<1_000||outputDurationMs-used>300)
            throw new IllegalStateException("Đoạn nhạc ngắn hơn video quá 300 ms. Hãy chọn bài dài hơn hoặc cắt video ngắn hơn.");
        return used;
    }
    public static long audioDurationMs(File file)throws Exception{
        MediaExtractor e=new MediaExtractor();try{e.setDataSource(file.getAbsolutePath());long duration=0;boolean found=false;
            for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){found=true;if(f.containsKey(MediaFormat.KEY_DURATION))duration=Math.max(duration,f.getLong(MediaFormat.KEY_DURATION));}}
            if(!found||duration<=0)throw new IllegalStateException("Nguồn nhạc không còn track audio hợp lệ.");return duration/1000L;
        }finally{e.release();}
    }
    private static long mediaDurationUs(File file)throws Exception{
        MediaExtractor extractor=new MediaExtractor();long duration=0;
        try{extractor.setDataSource(file.getAbsolutePath());for(int i=0;i<extractor.getTrackCount();i++){MediaFormat format=extractor.getTrackFormat(i);if(format.containsKey(MediaFormat.KEY_DURATION))duration=Math.max(duration,format.getLong(MediaFormat.KEY_DURATION));}}
        finally{extractor.release();}
        if(duration<=0){MediaMetadataRetriever metadata=new MediaMetadataRetriever();try{metadata.setDataSource(file.getAbsolutePath());String value=metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(value!=null)duration=Long.parseLong(value)*1000L;}finally{metadata.release();}}
        if(duration<=0)throw new IllegalStateException("Không xác định được thời lượng media nguồn.");return duration;
    }
    private static MediaItem clipped(File file,long start,long end){return new MediaItem.Builder().setUri(Uri.fromFile(file))
            .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()).build();}
    private static AudioProcessor gain(File file,int percent)throws Exception{
        MediaExtractor e=new MediaExtractor();try{e.setDataSource(file.getAbsolutePath());int channels=2;
            for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){if(f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);break;}}
            if(channels<1||channels>8)throw new IllegalStateException("Số kênh âm thanh này chưa được hỗ trợ.");
            float scale=Math.max(0,Math.min(100,percent))/100f;float[] coefficients=new float[channels*channels];
            for(int i=0;i<channels;i++)coefficients[i*channels+i]=scale;
            ChannelMixingAudioProcessor processor=new ChannelMixingAudioProcessor();
            processor.putChannelMixingMatrix(new ChannelMixingMatrix(channels,channels,coefficients));return processor;
        }finally{e.release();}
    }
}
