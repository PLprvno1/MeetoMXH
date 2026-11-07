package com.example.meetomxh.ui;

/** Decimal bytes. Publishing only: Story and Messenger retain their own policies. */
public final class VideoPublishLimits {
    public static final long MAX_BYTES = 49_000_000L;
    public static final long EXPORT_BYTES = 39_000_000L;
    public static final long MAX_DURATION_MS = 60_000L;
    public static long effective(long bucketBytes) {
        return bucketBytes <= 0 ? 0 : Math.min(MAX_BYTES, bucketBytes - Math.min(1_000_000L, bucketBytes / 50));
    }
    public static boolean needsEncoding(android.content.Context context,android.net.Uri uri) throws java.io.IOException {
        android.media.MediaExtractor extractor=new android.media.MediaExtractor();
        try {
            extractor.setDataSource(context,uri,null);
            boolean video=false;
            for(int i=0;i<extractor.getTrackCount();i++){
                android.media.MediaFormat format=extractor.getTrackFormat(i);
                String mime=format.getString(android.media.MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")){
                    video=true;if(!"video/avc".equals(mime))return true;
                    if(format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)&&format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)>30)return true;
                    if(format.containsKey(android.media.MediaFormat.KEY_WIDTH)&&format.containsKey(android.media.MediaFormat.KEY_HEIGHT)
                        &&(Math.max(format.getInteger(android.media.MediaFormat.KEY_WIDTH),format.getInteger(android.media.MediaFormat.KEY_HEIGHT))>1280
                        ||Math.min(format.getInteger(android.media.MediaFormat.KEY_WIDTH),format.getInteger(android.media.MediaFormat.KEY_HEIGHT))>720))return true;
                }else if(mime!=null&&mime.startsWith("audio/")&&!"audio/mp4a-latm".equals(mime))return true;
            }
            return !video;
        } finally {extractor.release();}
    }
    private VideoPublishLimits() { }
}
