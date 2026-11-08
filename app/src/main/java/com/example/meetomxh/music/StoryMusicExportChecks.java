package com.example.meetomxh.music;

/** Fail closed before upload. Values come from probing the exported file, not the source. */
public final class StoryMusicExportChecks {
    private StoryMusicExportChecks(){}
    public static void validate(long bytes,long muxedMs,long videoUs,long audioUs,int width,int height,
                                String videoMime,String audioMime,long expectedMs){
        validate(bytes,muxedMs,videoUs,audioUs,width,height,0,videoMime,audioMime,expectedMs);
    }
    /** MediaExtractor reports encoded dimensions. MP4 rotation determines the displayed frame. */
    public static void validate(long bytes,long muxedMs,long videoUs,long audioUs,int width,int height,
                                int rotation,String videoMime,String audioMime,long expectedMs){
        if(bytes<1||bytes>50L*1024*1024)throw new IllegalStateException("Thành phẩm vượt 50 MiB hoặc không hợp lệ.");
        if(expectedMs<1000||expectedMs>60000||muxedMs<1000||muxedMs>60000
                ||Math.abs(muxedMs-expectedMs)>300||videoUs<1||Math.abs(videoUs-expectedMs*1000)>300_000)
            throw new IllegalStateException("Video xuất không đúng thời lượng đã chọn. Hãy xuất lại.");
        if(!"video/avc".equals(videoMime))
            throw new IllegalStateException("Video Story xuất không phải H.264. Hãy xuất lại.");
        if(!"audio/mp4a-latm".equals(audioMime))
            throw new IllegalStateException("Âm thanh Story xuất không phải AAC. Hãy xuất lại.");
        if(rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270)
            throw new IllegalStateException("Góc xoay MP4 không hợp lệ: "+rotation+"°. Hãy xuất lại.");
        int displayWidth=rotation==90||rotation==270?height:width;
        int displayHeight=rotation==90||rotation==270?width:height;
        if(displayWidth!=720||displayHeight!=1280)
            throw new IllegalStateException("Khung Story xuất là "+displayWidth+"×"+displayHeight+
                    ", cần 720×1280 (góc xoay "+rotation+"°). Hãy xuất lại.");
        if(audioUs<expectedMs*1000-50_000||audioUs>expectedMs*1000+300_000)
            throw new IllegalStateException("Âm thanh bị thiếu hoặc không đủ thời lượng Story. Hãy xuất lại trước khi đăng.");
    }
}
