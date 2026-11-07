package com.example.meetomxh.ui;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.widget.ImageView;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.meetomxh.R;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.*;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Image assets go to Glide; private MP4 frames are decoded off the UI thread. */
public final class MediaThumbnailLoader {
    private static final Semaphore DECODERS=new Semaphore(2,true);
    private static final class Load { Subscription job; Bitmap bitmap; }
    private MediaThumbnailLoader(){}
    public static boolean video(Post post){for(String type:post.getMediaTypes())if(type.startsWith("video/"))return true;return post.isReel();}
    public static void clear(ImageView image){
        Object previous=image.getTag(R.id.search_thumbnail_binding);
        Load load=previous instanceof Load?(Load)previous:null;
        if(load!=null&&load.job!=null)load.job.remove();
        image.setTag(R.id.search_thumbnail_binding,null);Glide.with(image).clear(image);image.setImageDrawable(null);
        if(load!=null)recycle(load.bitmap);
    }
    public static void load(ImageView image,Post post){
        clear(image);Load load=new Load();image.setTag(R.id.search_thumbnail_binding,load);
        boolean video=video(post);int icon=video?R.drawable.ic_reel_video:android.R.drawable.ic_menu_edit;
        image.setImageResource(icon);String picture=null,clip=null;
        for(int i=0;i<post.getImageUrls().size()&&i<post.getMediaTypes().size();i++){
            String type=post.getMediaTypes().get(i);
            if(type.startsWith("video/")&&clip==null)clip=post.getImageUrls().get(i);
            if(type.startsWith("image/")&&picture==null)picture=post.getImageUrls().get(i);
        }
        if(video&&post.getReelCoverAssetId()!=null)picture=Dto.asset(post.getReelCoverAssetId());
        if(video&&post.getReelCoverAssetId()==null)picture=null;
        final String videoRef=clip;
        if(picture==null){if(videoRef!=null)frame(image,load,videoRef);return;}
        Glide.with(image).load(picture).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
            .placeholder(icon).error(icon).override(192,264).centerCrop().listener(new RequestListener<android.graphics.drawable.Drawable>(){
                public boolean onLoadFailed(@Nullable GlideException error,Object model,Target<android.graphics.drawable.Drawable> target,boolean first){
                    if(videoRef!=null)image.post(()->{if(image.getTag(R.id.search_thumbnail_binding)==load)frame(image,load,videoRef);});return false;
                }
                public boolean onResourceReady(android.graphics.drawable.Drawable value,Object model,Target<android.graphics.drawable.Drawable> target,DataSource source,boolean first){return image.getTag(R.id.search_thumbnail_binding)!=load;}
            }).into(image);
    }
    private static void frame(ImageView image,Load load,String ref){
        WeakReference<ImageView> view=new WeakReference<>(image);
        load.job=Api.run(()->{
            boolean acquired=false;MediaMetadataRetriever retriever=null;Bitmap frame=null;
            try{
                try{
                    while(!DECODERS.tryAcquire(250,TimeUnit.MILLISECONDS))Api.checkCancelled();
                    acquired=true;
                }catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Thumbnail request cancelled",interrupted);
                }
                Api.checkCancelled();
                retriever=new MediaMetadataRetriever();
                org.json.JSONObject asset=SupabaseStorage.info(ref);
                if(!asset.optString("mime_type").startsWith("video/"))throw new java.io.IOException("Not a video");
                Map<String,String> headers=new HashMap<>();headers.put("apikey",SupabaseConfig.KEY);
                headers.put("Authorization","Bearer "+SessionManager.getInstance().access(false));
                Api.checkCancelled();
                retriever.setDataSource(SupabaseStorage.url(asset),headers);
                Api.checkCancelled();
                frame=Build.VERSION.SDK_INT>=27?retriever.getScaledFrameAtTime(1_000_000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,192,264)
                    :retriever.getFrameAtTime(1_000_000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if(frame==null)throw new java.io.IOException("No frame");
                if(Math.max(frame.getWidth(),frame.getHeight())>264){float scale=264f/Math.max(frame.getWidth(),frame.getHeight());Bitmap small=Bitmap.createScaledBitmap(frame,Math.max(1,Math.round(frame.getWidth()*scale)),Math.max(1,Math.round(frame.getHeight()*scale)),true);if(small!=frame){Bitmap decoded=frame;frame=small;recycle(decoded);}}
                Api.checkCancelled();
            }catch(Exception|Error error){recycle(frame);frame=null;throw error;}
            finally{
                try{if(retriever!=null)retriever.release();}
                catch(RuntimeException|Error releaseError){recycle(frame);frame=null;throw releaseError;}
                finally{if(acquired)DECODERS.release();}
            }
            try{Api.checkCancelled();return frame;}catch(java.io.IOException cancelled){recycle(frame);throw cancelled;}
        },new Api.Callback<Bitmap>(){
            public void success(Bitmap bitmap){ImageView target=view.get();if(target!=null&&target.getTag(R.id.search_thumbnail_binding)==load){load.bitmap=bitmap;target.setImageBitmap(bitmap);}else recycle(bitmap);}
            public void error(String error){ImageView target=view.get();if(target!=null&&target.getTag(R.id.search_thumbnail_binding)==load)target.setImageResource(R.drawable.ic_reel_video);}
        },MediaThumbnailLoader::recycle);
    }
    private static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
}
