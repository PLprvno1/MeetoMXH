package com.example.meetomxh.music;

import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleEventObserver;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Verified immutable files, bounded LRU, and explicit leases for consumers. */
public final class MusicLibraryCache {
    static final long MAX_BYTES=120L*1024*1024;
    private static final Object LOCK=new Object();
    private static final Map<String,Integer> PINS=new HashMap<>();
    private static final Map<LifecycleOwner,Set<String>> OWNERS=new IdentityHashMap<>();
    private final File root;
    private final long limit;

    public MusicLibraryCache(Context context){this(new File(context.getApplicationContext().getFilesDir(),"music-library"),MAX_BYTES);}
    MusicLibraryCache(File root,long limit){this.root=root;this.limit=limit;}
    static String key(MusicLibraryTrack t){return digest((t.version+"\n"+t.id+"\n"+t.sha256).getBytes(StandardCharsets.UTF_8));}
    public File find(MusicLibraryTrack t)throws IOException {
        return find(t,true);
    }
    File peek(MusicLibraryTrack t)throws IOException {return find(t,false);}
    private File find(MusicLibraryTrack t,boolean touch)throws IOException {
        synchronized(LOCK){File file=target(t);if(!valid(file,t)){if(!pinned(file))file.delete();return null;}
            if(touch)file.setLastModified(System.currentTimeMillis());return file;}
    }
    public Lease acquire(MusicLibraryTrack t)throws IOException {
        synchronized(LOCK){File file=find(t);if(file==null)return null;return new Lease(file);}
    }
    public File begin()throws IOException {
        synchronized(LOCK){if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Không tạo được cache nhạc.");
            prune(0);return File.createTempFile("download-",".part",root);}
    }
    public File commit(File temp,MusicLibraryTrack t)throws IOException {
        synchronized(LOCK){
            if(!temp.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())||!temp.getName().endsWith(".part")||!valid(temp,t))
                throw new IOException("Bản nhạc tải về sai kích thước hoặc SHA-256.");
            File existing=find(t);if(existing!=null){temp.delete();return existing;}
            File target=target(t);if(pinned(target))throw new IOException("Bản nhạc đang được sử dụng; hãy thử lại sau.");
            prune(temp.length());
            if(!temp.renameTo(target))throw new IOException("Không hoàn tất cache nhạc.");
            target.setLastModified(System.currentTimeMillis());return target;
        }
    }
    interface Check { void run()throws IOException; }
    File receive(InputStream source,MusicLibraryTrack track,Check check)throws IOException {
        File temp=begin();
        try {
            try(InputStream in=source;FileOutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[32768];int n;long count=0;
                while((n=in.read(buffer))!=-1){check.run();count+=n;if(count>track.sizeBytes)throw new IOException("Bài nhạc tải về vượt kích thước catalog.");out.write(buffer,0,n);}
                if(count!=track.sizeBytes)throw new IOException("Bài nhạc tải về chưa đầy đủ.");out.getFD().sync();
            }
            check.run();return commit(temp,track);
        }finally{temp.delete();}
    }
    /** Call on the main thread. Protect for the host lifetime, including preview/export in that host. */
    public static void protect(Context context,OpenverseTrack track){
        if(track==null||track.library==null||!(context instanceof LifecycleOwner))return;
        LifecycleOwner owner=(LifecycleOwner)context;
        synchronized(LOCK){
            File file=new MusicLibraryCache(context).target(track.library);String key=file.getAbsolutePath();
            Set<String> files=OWNERS.get(owner);
            if(files==null){
                if(owner.getLifecycle().getCurrentState()==Lifecycle.State.DESTROYED)return;
                files=new HashSet<>();OWNERS.put(owner,files);
                owner.getLifecycle().addObserver((LifecycleEventObserver)(source,event)->{if(event==Lifecycle.Event.ON_DESTROY)releaseOwner(owner);});
            }
            if(files.add(key))PINS.put(key,PINS.getOrDefault(key,0)+1);
        }
    }
    private static void releaseOwner(LifecycleOwner owner){synchronized(LOCK){Set<String> files=OWNERS.remove(owner);if(files!=null)for(String key:files)unpin(key);}}
    public static final class Lease implements AutoCloseable {
        public final File file; private boolean closed;
        private Lease(File file){this.file=file;String key=file.getAbsolutePath();PINS.put(key,PINS.getOrDefault(key,0)+1);}
        @Override public void close(){synchronized(LOCK){if(!closed){closed=true;unpin(file.getAbsolutePath());}}}
    }
    private static void unpin(String key){int count=PINS.getOrDefault(key,0);if(count<=1)PINS.remove(key);else PINS.put(key,count-1);}
    private static boolean pinned(File file){return PINS.getOrDefault(file.getAbsolutePath(),0)>0;}
    private File target(MusicLibraryTrack t){return new File(root,key(t)+".ogg");}
    static boolean valid(File file,MusicLibraryTrack t)throws IOException {
        return file.isFile()&&file.length()==t.sizeBytes&&hash(file).equals(t.sha256);
    }
    static String hash(File file)throws IOException {
        try(InputStream in=new FileInputStream(file)){
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];int n;
            while((n=in.read(buffer))!=-1)md.update(buffer,0,n);
            return hex(md.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void prune(long reserve)throws IOException {
        File[] files=root.listFiles();if(files==null)return;
        long total=0,now=System.currentTimeMillis();List<File> audio=new ArrayList<>();
        for(File f:files){if(f.getName().endsWith(".ogg")){audio.add(f);total+=f.length();}
            else if(f.getName().endsWith(".part")&&now-f.lastModified()>3_600_000)f.delete();}
        audio.sort(Comparator.comparingLong(File::lastModified));
        for(File f:audio){if(total+reserve<=limit)break;
            // Short handoff window also protects the gap between Picker result and host creation.
            if(!pinned(f)&&now-f.lastModified()>60_000){long size=f.length();if(f.delete())total-=size;}}
        if(total+reserve>limit)throw new IOException("Cache nhạc đang đầy hoặc có bài đang sử dụng. Hãy thử lại sau.");
    }
    static String digest(byte[] data){try{return hex(MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
}
