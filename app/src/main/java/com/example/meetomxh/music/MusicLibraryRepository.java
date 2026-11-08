package com.example.meetomxh.music;

import android.content.Context;
import android.util.AtomicFile;
import com.example.meetomxh.supabase.*;
import okhttp3.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Authenticated RPC catalog plus on-demand, checksum-verified Storage downloads. */
public final class MusicLibraryRepository {
    private static final OkHttpClient HTTP=SupabaseHttpClient.CLIENT.newBuilder()
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private static final Map<String,Object> DOWNLOADS=new ConcurrentHashMap<>();
    private final Context context;
    private final MusicLibraryCache cache;
    public MusicLibraryRepository(Context context){this.context=context.getApplicationContext();cache=new MusicLibraryCache(this.context);}
    public interface Rpc { JSONObject call(String name,JSONObject args)throws Exception; }
    public static final class Catalog {
        public final List<MusicLibraryTrack> tracks;
        public final Set<String> cached=new HashSet<>();
        public final boolean offline;
        Catalog(List<MusicLibraryTrack> tracks,boolean offline){this.tracks=Collections.unmodifiableList(tracks);this.offline=offline;}
    }
    public Catalog load(long epoch)throws Exception {
        SessionManager session=SessionManager.getInstance();String uid=session.uid();requireCurrent(epoch);
        if(uid.isEmpty())throw new ErrorMapper.HttpError(401,"no_session");
        AtomicFile snapshot=snapshot(uid);Catalog result;
        try {
            result=fetchCatalog(RpcClient::object);requireCurrent(epoch);
            save(snapshot,result);
        }catch(ErrorMapper.HttpError denied){snapshot.delete();throw denied;
        }catch(IOException network){
            requireCurrent(epoch);result=read(snapshot);
            if(result==null)throw new IOException("Không kết nối được thư viện nhạc. Kết nối mạng lần đầu rồi thử lại.");
        }catch(JSONException invalid){snapshot.delete();throw new IllegalStateException("Dữ liệu thư viện nhạc không hợp lệ hoặc đã thay đổi. Hãy tải lại danh sách.",invalid);
        }catch(Exception invalid){snapshot.delete();throw invalid;}
        for(MusicLibraryTrack track:result.tracks){requireCurrent(epoch);if(cache.peek(track)!=null)result.cached.add(MusicLibraryCache.key(track));}
        requireCurrent(epoch);return result;
    }
    public Catalog cached(long epoch)throws Exception {
        requireCurrent(epoch);Catalog saved=read(snapshot(SessionManager.getInstance().uid()));
        if(saved!=null)for(MusicLibraryTrack track:saved.tracks){requireCurrent(epoch);if(cache.peek(track)!=null)saved.cached.add(MusicLibraryCache.key(track));}
        return saved;
    }
    /** Kept separate so parsing/cursor behavior can be tested with recorded, credential-free responses. */
    static Catalog fetchCatalog(Rpc rpc)throws Exception {
        JSONObject publish=rpc.call("get_music_publish_capabilities",new JSONObject());
        if(!Boolean.TRUE.equals(publish.opt("enabled"))||MusicLibraryTrack.integer(publish,"contract_version")!=1)
            throw new IllegalStateException("Máy chủ chưa bật lưu metadata nhạc. Có thể tiếp tục mà không thêm nhạc.");
        JSONObject caps=rpc.call("get_music_library_capabilities",new JSONObject());
        if(MusicLibraryTrack.integer(caps,"contract_version")!=2||!"meeto_library".equals(caps.opt("source"))
                ||!"music-library".equals(caps.opt("bucket_id"))||!"authenticated_storage".equals(caps.opt("delivery"))
                ||MusicLibraryTrack.integer(caps,"max_used_duration_ms")!=60000||!(caps.opt("enabled") instanceof Boolean))
            throw new JSONException("Dữ liệu khả năng thư viện nhạc không hợp lệ.");
        long expected=MusicLibraryTrack.integer(caps,"active_track_count");
        if(expected>1000||caps.getBoolean("enabled")!=(expected>0))throw new JSONException("Số bài trong thư viện không hợp lệ.");
        if(expected==0)return new Catalog(new ArrayList<>(),false);
        List<MusicLibraryTrack> tracks=new ArrayList<>();Set<String> seen=new HashSet<>(),cursors=new HashSet<>(),orders=new HashSet<>();
        String version=null;Integer order=null;
        for(int page=0;page<20;page++){
            JSONObject args=new JSONObject().put("p_catalog_version",JSONObject.NULL)
                    .put("p_after_catalog_version",version==null?JSONObject.NULL:version)
                    .put("p_after_sort_order",order==null?JSONObject.NULL:order).put("p_limit",50);
            JSONObject response=rpc.call("get_music_catalog",args);
            if(MusicLibraryTrack.integer(response,"contract_version")!=2||!(response.opt("has_more") instanceof Boolean))
                throw new JSONException("Trang thư viện nhạc không hợp lệ.");
            JSONArray items=response.getJSONArray("items");boolean more=response.getBoolean("has_more");
            if(items.length()>50||(more&&items.length()!=50))throw new JSONException("Phân trang thư viện nhạc không hợp lệ.");
            for(int i=0;i<items.length();i++){
                MusicLibraryTrack track=MusicLibraryTrack.parse(items.getJSONObject(i));
                if(!seen.add(track.version+"/"+track.id)||!orders.add(track.version+"/"+track.sortOrder))throw new JSONException("Bài nhạc bị trùng trong catalog.");
                tracks.add(track);
            }
            if(!more){if(tracks.size()!=expected)throw new JSONException("Catalog đã thay đổi hoặc thiếu bài. Hãy tải lại.");return new Catalog(tracks,false);}
            JSONObject next=response.getJSONObject("next_cursor");String nextVersion=next.getString("catalog_version");long nextOrder=MusicLibraryTrack.integer(next,"sort_order");
            MusicLibraryTrack last=tracks.get(tracks.size()-1);
            if(!nextVersion.equals(last.version)||nextOrder!=last.sortOrder||!cursors.add(nextVersion+"/"+nextOrder))throw new JSONException("Cursor thư viện nhạc không hợp lệ.");
            version=nextVersion;order=(int)nextOrder;
        }
        throw new JSONException("Catalog vượt giới hạn hỗ trợ. Hãy thử lại sau.");
    }
    public static final class Audio implements AutoCloseable {
        public final OpenverseTrack track;private final MusicLibraryCache.Lease lease;
        Audio(MusicLibraryTrack track,MusicLibraryCache.Lease lease){this.lease=lease;this.track=OpenverseTrack.library(track,lease.file.getAbsolutePath());}
        @Override public void close(){lease.close();}
    }
    public Audio audio(MusicLibraryTrack track,long epoch)throws Exception {
        synchronized(DOWNLOADS.computeIfAbsent(MusicLibraryCache.key(track),ignored->new Object())){
            requireCurrent(epoch);
            MusicLibraryCache.Lease hit=cache.acquire(track);if(hit!=null)return new Audio(track,hit);
            SupabaseConfig.check();SessionManager session=SessionManager.getInstance();String token=session.access(false);requireCurrent(epoch);
            // Authenticated download only. URL is built from validated catalog paths, never persisted as identity.
            HttpUrl base=HttpUrl.parse(SupabaseConfig.URL+"/storage/v1/object/authenticated/");
            if(base==null)throw new IOException("Cấu hình Storage không hợp lệ.");
            HttpUrl.Builder url=base.newBuilder().addPathSegment(track.bucket);for(String part:track.path.split("/"))url.addPathSegment(part);
            for(int retry=0;retry<2;retry++){
                Request request=new Request.Builder().url(url.build()).header("apikey",SupabaseConfig.KEY)
                        .header("Authorization","Bearer "+token).header("Accept","audio/ogg").build();
                Call call=HTTP.newCall(request);Api.attach(call);
                try(Response response=call.execute()){
                    requireCurrent(epoch);
                    if(response.code()==401&&retry==0){token=session.afterRejected(token);requireCurrent(epoch);continue;}
                    if(response.code()==401||response.code()==403||response.code()==404)throw new ErrorMapper.HttpError(response.code(),"music_storage_unavailable");
                    if(!response.isSuccessful()||response.body()==null)throw new IOException("Không tải được bài nhạc (HTTP "+response.code()+"). Hãy thử lại.");
                    String mime=response.header("Content-Type","").split(";",2)[0].trim();
                    if(!"audio/ogg".equalsIgnoreCase(mime))throw new IOException("Storage trả MIME nhạc không hợp lệ.");
                    long size=response.body().contentLength();if(size>=0&&size!=track.sizeBytes)throw new IOException("Kích thước bài nhạc không khớp catalog.");
                    cache.receive(response.body().byteStream(),track,()->requireCurrent(epoch));
                    MusicLibraryCache.Lease lease=cache.acquire(track);if(lease==null)throw new IOException("Không xác minh được cache nhạc.");
                    return new Audio(track,lease);
                }
            }
            throw new ErrorMapper.HttpError(401,"music_session_expired");
        }
    }
    private AtomicFile snapshot(String uid){return new AtomicFile(new File(context.getFilesDir(),"music-catalog-"+MusicLibraryCache.digest(uid.getBytes(StandardCharsets.UTF_8))+".json"));}
    private void save(AtomicFile file,Catalog catalog)throws Exception {
        JSONArray tracks=new JSONArray();for(MusicLibraryTrack track:catalog.tracks)tracks.put(track.json());
        byte[] bytes=new JSONObject().put("contract_version",2).put("tracks",tracks).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(IOException error){if(out!=null)file.failWrite(out);/* Online catalog still usable if disk is full. */}
    }
    private Catalog read(AtomicFile file){
        try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>1_000_000)return null;out.write(buffer,0,n);}
            JSONObject snapshot=new JSONObject(out.toString("UTF-8"));if(snapshot.getInt("contract_version")!=2)return null;
            JSONArray rows=snapshot.getJSONArray("tracks");if(rows.length()>1000)return null;
            List<MusicLibraryTrack> tracks=new ArrayList<>();Set<String> seen=new HashSet<>();
            for(int i=0;i<rows.length();i++){MusicLibraryTrack track=MusicLibraryTrack.parse(rows.getJSONObject(i));if(!seen.add(MusicLibraryCache.key(track)))return null;tracks.add(track);}
            return new Catalog(tracks,true);
        }catch(Exception invalid){return null;}
    }
    private static void requireCurrent(long epoch)throws IOException {Api.checkCancelled();if(SessionManager.getInstance().generation()!=epoch||SessionManager.getInstance().uid().isEmpty())throw new IOException("Phiên đăng nhập đã thay đổi.");}
}
