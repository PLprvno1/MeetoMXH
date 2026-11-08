package com.example.meetomxh.music;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** 24-hour, 120 MiB app-cache for validated Openverse audio. */
public final class OpenverseMusicCache {
    private static final long MAX_TOTAL=120L*1024*1024,TTL=24L*60*60*1000;
    private final File root;
    private final android.content.SharedPreferences metadata;
    public OpenverseMusicCache(Context context){root=new File(context.getCacheDir(),"openverse-music");metadata=context.getSharedPreferences("openverse_audio_identity_v1",Context.MODE_PRIVATE);}
    public static synchronized File find(Context context,OpenverseTrack track){OpenverseMusicCache cache=new OpenverseMusicCache(context);
        return signature(track).equals(cache.metadata.getString(key(track.id),""))?cache.findInternal(track.id):null;}
    public File commit(File temp,OpenverseTrack track)throws IOException{synchronized(OpenverseMusicCache.class){File result=commit(temp,track.id);metadata.edit().putString(key(track.id),signature(track)).apply();return result;}}
    private static String signature(OpenverseTrack track){return key(track.audioUrl+"|"+track.durationMs+"|"+track.licenseUrl);}
    public static synchronized File find(Context context,String id){return new OpenverseMusicCache(context).findInternal(id);}
    public static boolean isManaged(Context context,String id,String path){try{File f=find(context,id);return f!=null&&f.getCanonicalFile().equals(new File(path).getCanonicalFile());}catch(IOException e){return false;}}
    public static boolean isManaged(Context context,OpenverseTrack track){
        if(track==null)return false;
        if(track.library==null)return isManaged(context,track.id,track.localPath);
        try{File f=new MusicLibraryCache(context).find(track.library);
            boolean valid=f!=null&&f.getCanonicalFile().equals(new File(track.localPath).getCanonicalFile());
            if(valid)MusicLibraryCache.protect(context,track);return valid;
        }catch(IOException|RuntimeException invalid){return false;}
    }
    private File findInternal(String id){File f=track(id);if(!f.isFile())return null;if(System.currentTimeMillis()-f.lastModified()>TTL){f.delete();return null;}f.setLastModified(System.currentTimeMillis());return f;}
    public File begin(String id)throws IOException{synchronized(OpenverseMusicCache.class){if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Không tạo được vùng đệm nhạc.");cleanup(0);return new File(root,key(id)+"-"+java.util.UUID.randomUUID()+".part");}}
    public File commit(File temp,String id)throws IOException{synchronized(OpenverseMusicCache.class){
        if(!temp.isFile()||temp.length()==0)throw new IOException("Bản nhạc tải về trống.");
        cleanup(temp.length());File target=track(id);if(target.exists())target.delete();
        if(!temp.renameTo(target))throw new IOException("Không lưu được bản nhạc vào cache.");target.setLastModified(System.currentTimeMillis());return target;
    }}
    private File track(String id){return new File(root,key(id)+".audio");}
    private static String key(String id){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private void cleanup(long reserve){File[] files=root.listFiles(f->f.isFile());if(files==null)return;long now=System.currentTimeMillis();long total=0;for(File f:files){if(f.getName().endsWith(".part")){if(now-f.lastModified()>60L*60*1000)f.delete();}else if(f.getName().endsWith(".audio")){if(now-f.lastModified()>TTL)f.delete();else total+=f.length();}else f.delete();}
        files=root.listFiles(f->f.isFile()&&f.getName().endsWith(".audio"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));
        for(File f:files){if(total+reserve<=MAX_TOTAL)break;total-=f.length();f.delete();}
    }
}
