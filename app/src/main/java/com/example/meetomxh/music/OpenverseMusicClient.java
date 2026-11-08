package com.example.meetomxh.music;

import android.content.Context;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.content.SharedPreferences;
import android.util.Log;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Anonymous Openverse API client. It never adds Supabase or account headers. */
public final class OpenverseMusicClient {
    private static final String API="https://api.openverse.org/v1/audio/";
    private static final int MAX_FILE=25*1024*1024, MAX_REDIRECTS=4, PAGE_SIZE=15;
    private static final long MIN_REQUEST_GAP_MS=500, REQUEST_WINDOW_MS=60_000, DAY_MS=86_400_000;
    private static int burstLimit=18,dailyLimit=180;
    private static long blockedUntil;
    private static SharedPreferences budgetPrefs;
    private static final java.util.LinkedHashMap<String,CachedDetail> DETAIL_CACHE=new java.util.LinkedHashMap<>();
    private static final ArrayList<Long> REQUESTS=new ArrayList<>();
    private static final java.util.LinkedHashMap<String,CachedPage> SEARCH_CACHE=new java.util.LinkedHashMap<>();
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS)
            .readTimeout(15,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .dns(host->{List<InetAddress> addresses=okhttp3.Dns.SYSTEM.lookup(host);for(InetAddress ip:addresses)if(!publicAddress(ip))throw new java.net.UnknownHostException("Non-public source");return addresses;}).build();
    private static long lastRequest;
    private static final class CachedPage{final Page page;final long expiry;CachedPage(Page p,long e){page=p;expiry=e;}}
    private static final class CachedDetail{final OpenverseTrack track;final long expiry;CachedDetail(OpenverseTrack t){track=t;expiry=System.currentTimeMillis()+15*60_000;}}
    public static synchronized void initialize(Context context){
        if(budgetPrefs!=null)return;
        budgetPrefs=context.getApplicationContext().getSharedPreferences("openverse_budget_v2",Context.MODE_PRIVATE);
        blockedUntil=budgetPrefs.getLong("blocked_until",0);
        for(String value:budgetPrefs.getString("requests","").split(","))try{long time=Long.parseLong(value);if(System.currentTimeMillis()-time<DAY_MS)REQUESTS.add(time);}catch(NumberFormatException ignored){}
    }

    public static final class Page {
        public final List<OpenverseTrack> tracks; public final int page,pages;
        Page(List<OpenverseTrack> tracks,int page,int pages){this.tracks=tracks;this.page=page;this.pages=pages;}
    }
    private OpenverseMusicClient(){}

    public static Page search(String query,int page,CallHolder holder) throws Exception {
        if(holder!=null&&holder.cancelled())throw new IOException("Đã hủy yêu cầu.");
        query=query==null||query.trim().isEmpty()?"music":query.trim();
        if(page<1||page>20)throw new IOException("Đã đến giới hạn tìm kiếm Openverse.");
        String cacheKey=query+"|"+page+"|cc0|musical-v2|15";long now=System.currentTimeMillis();
        synchronized(SEARCH_CACHE){CachedPage cached=SEARCH_CACHE.get(cacheKey);if(cached!=null&&cached.expiry>now)return cached.page;if(cached!=null)SEARCH_CACHE.remove(cacheKey);}
        HttpUrl url=HttpUrl.parse(API).newBuilder().addQueryParameter("q",query)
                .addQueryParameter("license","cc0")
                .addQueryParameter("page_size",Integer.toString(PAGE_SIZE)).addQueryParameter("page",Integer.toString(page))
                .build();
        JSONObject body=getJson(url.toString(),holder,"search");
        JSONArray rows=body.optJSONArray("results");List<OpenverseTrack> valid=new ArrayList<>();
        if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            OpenverseTrack track=parse(row,null);if(track!=null&&musical(row))valid.add(track);
        }
        Page result=new Page(valid,page,Math.min(20,Math.max(0,body.optInt("page_count",page))));
        if(holder!=null&&holder.cancelled())throw new IOException("Đã hủy yêu cầu.");
        synchronized(SEARCH_CACHE){SEARCH_CACHE.put(cacheKey,new CachedPage(result,System.currentTimeMillis()+5*60_000));while(SEARCH_CACHE.size()>40)SEARCH_CACHE.remove(SEARCH_CACHE.keySet().iterator().next());}
        return result;
    }
    public static void clearSearchCache(){synchronized(SEARCH_CACHE){SEARCH_CACHE.clear();}synchronized(DETAIL_CACHE){DETAIL_CACHE.clear();}}

    public static OpenverseTrack downloadValidated(Context context,OpenverseTrack result,CallHolder holder,
            long expectedGeneration) throws Exception {
        if(result==null||!validMetadata(result.id,result.title,result.provider,result.source,result.landingUrl,
                result.audioUrl,result.license,result.licenseUrl,result.durationMs))
            throw new IOException("Bài nhạc thiếu thông tin nguồn hoặc CC0 hợp lệ.");
        requireCurrent(expectedGeneration);
        initialize(context);
        OpenverseTrack verified=null;
        synchronized(DETAIL_CACHE){CachedDetail cached=DETAIL_CACHE.get(result.id);if(cached!=null&&cached.expiry>System.currentTimeMillis())verified=cached.track;}
        if(verified==null){
            JSONObject detail=getJson(API+result.id+"/",holder,"detail");
            verified=parse(detail,null);
            if(verified==null||!result.id.equals(verified.id)||!musical(detail))
                throw new IOException("Nguồn không xác nhận lại metadata nhạc và giấy phép CC0 hợp lệ.");
            requireCurrent(expectedGeneration);
            checkReachable(verified.landingUrl,holder);
            checkReachable(verified.licenseUrl,holder);
            requireCurrent(expectedGeneration);
            if(holder!=null&&holder.cancelled())throw new IOException("Đã hủy yêu cầu.");
            synchronized(DETAIL_CACHE){DETAIL_CACHE.put(result.id,new CachedDetail(verified));while(DETAIL_CACHE.size()>40)DETAIL_CACHE.remove(DETAIL_CACHE.keySet().iterator().next());}
        }
        requireCurrent(expectedGeneration);
        File cached=OpenverseMusicCache.find(context,verified);
        if(cached==null)cached=downloadAudio(context,verified,holder,expectedGeneration);
        long actualDuration;
        try{actualDuration=validateAudio(cached);}catch(Exception invalidAudio){cached.delete();throw invalidAudio;}
        requireCurrent(expectedGeneration);
        if(holder!=null&&holder.cancelled())throw new IOException("Đã hủy tải nhạc.");
        return new OpenverseTrack(verified.id,verified.title,verified.creator,verified.provider,verified.source,
                verified.landingUrl,verified.audioUrl,verified.license,verified.licenseUrl,verified.attribution,
                actualDuration,cached.getAbsolutePath());
    }

    private static File downloadAudio(Context context,OpenverseTrack track,CallHolder holder,long epoch)throws Exception{
        OpenverseMusicCache cache=new OpenverseMusicCache(context);
        File temp=cache.begin(track.id);boolean committed=false;
        try{
            Response response=executeRedirects(track.audioUrl,"GET",holder);
            try(Response r=response){
                if(!r.isSuccessful()||r.body()==null)throw new IOException("Tệp nhạc không truy cập được.");
                String type=r.header("Content-Type","").split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
                if(!(type.startsWith("audio/")||"application/ogg".equals(type)||"application/octet-stream".equals(type)))
                    throw new IOException("Nguồn không trả về định dạng âm thanh hợp lệ.");
                long declared=r.body().contentLength();if(declared==0||declared>MAX_FILE)throw new IOException("Bản nhạc vượt giới hạn tải tạm 25 MiB.");
                try(InputStream in=r.body().byteStream();FileOutputStream out=new FileOutputStream(temp)){
                    byte[] b=new byte[32768];int n;long count=0;
                    while((n=in.read(b))!=-1){requireCurrent(epoch);if(holder!=null&&holder.cancelled())throw new IOException("Đã hủy tải nhạc.");
                        if((count+=n)>MAX_FILE)throw new IOException("Bản nhạc vượt giới hạn tải tạm 25 MiB.");out.write(b,0,n);}
                    out.getFD().sync();
                }
            }
            validateAudio(temp);
            requireCurrent(epoch);
            if(holder!=null&&holder.cancelled())throw new IOException("Đã hủy tải nhạc.");
            File result=cache.commit(temp,track);committed=true;return result;
        }finally{if(!committed)temp.delete();}
    }

    private static long validateAudio(File file)throws Exception{
        MediaExtractor extractor=new MediaExtractor();boolean audio=false;long duration=0;
        try{
            extractor.setDataSource(file.getAbsolutePath());
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){
                    MediaCodecList codecs=new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                    if(codecs.findDecoderForFormat(format)==null)throw new IOException("Định dạng nhạc không được thiết bị hỗ trợ.");
                    audio=true;if(format.containsKey(MediaFormat.KEY_DURATION))duration=Math.max(duration,format.getLong(MediaFormat.KEY_DURATION));
                }
            }
            if(!audio||file.length()==0)throw new IOException("Tệp nguồn không có track âm thanh giải mã được.");
            if(duration<=0){android.media.MediaMetadataRetriever m=new android.media.MediaMetadataRetriever();try{m.setDataSource(file.getAbsolutePath());String d=m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);duration=d==null?0:Long.parseLong(d)*1000;}finally{m.release();}}
            if(duration<1_000_000L||duration>60L*60*1_000_000L)throw new IOException("Thời lượng âm thanh nguồn không hợp lệ.");
            return duration/1000L;
        }finally{extractor.release();}
    }

    private static void checkReachable(String url,CallHolder holder)throws Exception{
        int code;try(Response r=executeRedirects(url,"HEAD",holder)){code=r.code();if(code>=200&&code<400)return;}
        if(code!=403&&code!=405&&code!=501)throw new IOException("Trang nguồn bài nhạc hiện không truy cập được.");
        try(Response r=executeRedirects(url,"GET",holder)){if(!r.isSuccessful())throw new IOException("Trang nguồn bài nhạc hiện không truy cập được.");}
    }

    private static JSONObject getJson(String url,CallHolder holder,String stage)throws Exception{
        try(Response r=executeRedirects(url,"GET",holder)){
            String diagnostic="";
            if(!r.isSuccessful()&&r.body()!=null){String sample=r.peekBody(2048).string();
                if(sample.contains("must not both be defined"))diagnostic="conflicting_sensitive_parameters";
                else if(sample.contains("Invalid page")||sample.contains("invalid_page"))diagnostic="invalid_page";
                else diagnostic="response_body_redacted";}
            debug(stage+" status="+r.code()+" error="+diagnostic+" generation="+(holder==null?0:holder.generation)+" retry=manual blocked_ms="+retryAfterMs());
            if(r.code()==429)throw new RateLimitException("Openverse đang giới hạn yêu cầu. Hãy đợi rồi thử lại.",retryAfterMs());
            if(r.code()==400)throw new IOException("Yêu cầu "+("search".equals(stage)?"tìm kiếm":"xác minh bài nhạc")+" Openverse không hợp lệ (HTTP 400). Hãy đổi từ khóa hoặc thử bài khác.");
            if(!r.isSuccessful()||r.body()==null)throw new IOException("Openverse "+stage+" trả lời HTTP "+r.code()+". Hãy thử lại sau.");
            try{return new JSONObject(r.body().string());}catch(Exception bad){throw new IOException("Openverse trả dữ liệu JSON không hợp lệ.",bad);}
        }
    }

    private static Response executeRedirects(String address,String method,CallHolder holder)throws Exception{
        String current=address;
        for(int hop=0;hop<=MAX_REDIRECTS;hop++){
            if(holder!=null&&holder.cancelled())throw new IOException("Đã hủy yêu cầu.");
            requireSafeHttps(current);
            HttpUrl target=HttpUrl.parse(current);boolean api="api.openverse.org".equalsIgnoreCase(target.host());
            if(api){awaitBudget();debug("request method="+method+" stage="+(target.pathSegments().size()>3?"detail":"search")
                    +" q_chars="+(target.queryParameter("q")==null?0:target.queryParameter("q").length())
                    +" license="+target.queryParameter("license")+" page="+target.queryParameter("page")
                    +" page_size="+target.queryParameter("page_size")+" generation="+(holder==null?0:holder.generation)+" retry=none");}
            Request.Builder rb=new Request.Builder().url(current).header("Accept","application/json, audio/*, application/octet-stream")
                    .header("User-Agent","Meeto-Android/1.0 OpenverseMusic");
            if("HEAD".equals(method))rb.head();
            Call call=HTTP.newCall(rb.build());if(holder!=null)holder.set(call);
            Response response=call.execute();
            // Retain the call until replaced: cancellation must also interrupt streaming bodies.
            if(api)observeLimits(response);
            if(response.code()>=300&&response.code()<400){String next=response.header("Location");response.close();if(next==null||hop==MAX_REDIRECTS)throw new IOException("Nguồn đổi hướng quá nhiều lần.");
                HttpUrl resolved=response.request().url().resolve(next);if(resolved==null)throw new IOException("Nguồn đổi hướng không hợp lệ.");current=resolved.toString();continue;}
            if(!response.request().url().isHttps()){response.close();throw new IOException("Nguồn nhạc chuyển sang kết nối không an toàn.");}
            return response;
        }
        throw new IOException("Nguồn nhạc đổi hướng không hợp lệ.");
    }

    private static synchronized void awaitBudget()throws InterruptedException,IOException{
        final long now=System.currentTimeMillis();REQUESTS.removeIf(t->now-t>=DAY_MS);
        if(now<blockedUntil)throw new RateLimitException("Openverse đang giới hạn yêu cầu. Hãy đợi rồi thử lại.",blockedUntil-now);
        long recent=0;for(long t:REQUESTS)if(now-t<REQUEST_WINDOW_MS)recent++;
        if(REQUESTS.size()>=dailyLimit)throw new RateLimitException("Đã đạt ngân sách Openverse trong 24 giờ trên thiết bị.",Math.max(1000,REQUESTS.get(0)+DAY_MS-now));
        if(recent>=burstLimit)throw new RateLimitException("Đã đạt ngân sách Openverse trong phút này. Hãy đợi rồi thử lại.",60_000);
        long wait=Math.max(0,MIN_REQUEST_GAP_MS-(now-lastRequest));
        if(wait>0)Thread.sleep(wait);
        long afterWait=System.currentTimeMillis();REQUESTS.add(afterWait);lastRequest=afterWait;
        persistBudget();
    }

    public static final class RateLimitException extends IOException{
        public final long waitMs;
        RateLimitException(String message,long waitMs){super(message);this.waitMs=Math.max(1000,waitMs);}
    }
    public static synchronized long retryAfterMs(){return Math.max(0,blockedUntil-System.currentTimeMillis());}
    private static synchronized void observeLimits(Response r){
        burstLimit=headerLimit(r.header("X-RateLimit-Limit-anon_burst"),burstLimit,18);
        dailyLimit=headerLimit(r.header("X-RateLimit-Limit-anon_sustained"),dailyLimit,180);
        if(r.code()==429||r.header("Retry-After")!=null){
            long delay=60_000;String value=r.header("Retry-After");
            if(value!=null)try{delay=Math.max(1000,Long.parseLong(value.trim())*1000L);}catch(Exception ignored){
                try{java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",java.util.Locale.US);delay=Math.max(1000,date.parse(value).getTime()-System.currentTimeMillis());}catch(Exception ignoredDate){}
            }
            blockedUntil=Math.max(blockedUntil,System.currentTimeMillis()+delay);persistBudget();
        }
    }
    private static int headerLimit(String value,int fallback,int cap){try{return Math.max(1,Math.min(cap,Integer.parseInt(value.split("/",2)[0])));}catch(Exception ignored){return fallback;}}
    private static void persistBudget(){if(budgetPrefs==null)return;StringBuilder times=new StringBuilder();for(long t:REQUESTS){if(times.length()>0)times.append(',');times.append(t);}budgetPrefs.edit().putString("requests",times.toString()).putLong("blocked_until",blockedUntil).apply();}
    private static void debug(String value){if(com.example.meetomxh.BuildConfig.DEBUG)Log.d("OpenverseMusic",value);}

    private static void requireSafeHttps(String address)throws Exception{
        URI uri=URI.create(address);String host=uri.getHost();
        if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)
            throw new IOException("Liên kết nguồn phải dùng HTTPS.");
        String h=host.toLowerCase(java.util.Locale.ROOT);
        if(h.equals("localhost")||h.endsWith(".localhost")||h.endsWith(".local"))throw new IOException("Liên kết nguồn không hợp lệ.");
        for(InetAddress ip:InetAddress.getAllByName(host))if(!publicAddress(ip))throw new IOException("Liên kết nguồn không phải địa chỉ Internet công khai.");
    }
    private static boolean publicAddress(InetAddress ip){byte[] b=ip.getAddress();return !(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()
            ||ip.isSiteLocalAddress()||ip.isMulticastAddress()||(b.length==16&&(b[0]&0xfe)==0xfc)
            ||(b.length==4&&((b[0]&255)==0||((b[0]&255)==100&&(b[1]&255)>=64&&(b[1]&255)<=127)||(b[0]&255)>=240)));}
    private static boolean musical(JSONObject row){
        if("music".equalsIgnoreCase(row.optString("category","")))return true;
        StringBuilder metadata=new StringBuilder(row.optString("title","")).append(' ');
        for(String key:new String[]{"tags","genres"}){JSONArray values=row.optJSONArray(key);if(values==null)continue;
            for(int i=0;i<values.length();i++){Object v=values.opt(i);metadata.append(v instanceof JSONObject?((JSONObject)v).optString("name",""):v instanceof String?v:"").append(' ');}}
        return java.util.regex.Pattern.compile("\\b(music|musical|melody|melodic|piano|ambient|instrumental|beat|beats|guitar|chill|lofi|lo-fi|jazz|orchestra|synth|soundtrack|song|tune|nhạc)\\b").matcher(metadata.toString().toLowerCase(java.util.Locale.ROOT)).find();
    }

    private static OpenverseTrack parse(JSONObject o,String localPath){
        try{
            String id=o.getString("id");String title=jsonText(o,"title");String creator=jsonText(o,"creator");
            String provider=jsonText(o,"provider");String source=jsonText(o,"source");
            String landing=o.optString("foreign_landing_url","");String audio=o.optString("url");
            String license=o.optString("license").toLowerCase(java.util.Locale.ROOT);String licenseUrl=o.optString("license_url");
            String attribution=jsonText(o,"attribution");long duration=Math.round(o.optDouble("duration",0));
            if(creator.length()>300||attribution.length()>800||!validMetadata(id,title,provider,source,landing,audio,license,licenseUrl,duration))return null;
            return new OpenverseTrack(id,title,creator,provider,source,landing,audio,license,licenseUrl,attribution,duration,localPath);
        }catch(Exception ignored){return null;}
    }

    public static boolean validMetadata(String id,String title,String provider,String source,String landing,String audio,
            String license,String licenseUrl,long duration){
        if(id==null||!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                ||blank(title)||title.length()>300||blank(provider)||provider.length()>120||blank(source)||source.length()>120
                ||!"cc0".equalsIgnoreCase(license)||duration<1000||duration>60L*60*1000||landing==null||landing.length()>2048
                ||audio==null||audio.length()>2048||licenseUrl==null||licenseUrl.length()>2048)return false;
        if(!isHttpsUrl(landing)||!isHttpsUrl(audio)||!isHttpsUrl(licenseUrl))return false;
        return licenseUrl.matches("(?i)^https://creativecommons\\.org/publicdomain/zero/1\\.0/?([?#].*)?$");
    }
    public static boolean validPublishedMetadata(JSONObject o){
        if(o==null)return false;
        if(o.has("contract_version"))return MusicLibraryTrack.validMetadata(o);
        try{
            if(!hasExactPublishedKeys(o)||o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16_384
                    ||!"cc0".equals(o.getString("license")))return false;
            String creator=o.getString("creator"),attribution=o.getString("attribution");
            long duration=integer(o,"duration_ms"),start=integer(o,"start_offset_ms"),used=integer(o,"used_duration_ms");
            return validMetadata(o.getString("id"),o.getString("title"),o.getString("provider"),o.getString("source"),
                    o.getString("source_url"),o.getString("audio_url"),o.getString("license"),o.getString("license_url"),duration)
                    &&creator.length()<=300&&attribution.length()<=800&&start>=0&&start<duration
                    &&used>=1000&&used<=60000&&used<=duration-start;
        }catch(Exception e){return false;}
    }
    public static boolean validPublishedMetadata(JSONObject o,long outputDurationMs){
        if(outputDurationMs<1_000||outputDurationMs>60_000||!validPublishedMetadata(o))return false;
        try{return Math.abs(o.getLong("used_duration_ms")-outputDurationMs)<=300;}
        catch(Exception e){return false;}
    }
    private static boolean isHttpsUrl(String value){try{URI u=URI.create(value);return "https".equalsIgnoreCase(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null;}catch(Exception e){return false;}}
    private static boolean hasExactPublishedKeys(JSONObject o){
        String[] keys={"id","title","creator","provider","source","source_url","audio_url","license","license_url","attribution","duration_ms","start_offset_ms","used_duration_ms"};
        if(o.length()!=keys.length)return false;for(String key:keys)if(!o.has(key))return false;
        String[] strings={"id","title","creator","provider","source","source_url","audio_url","license","license_url","attribution"};
        for(String key:strings)if(!(o.opt(key) instanceof String))return false;
        return true;
    }
    private static long integer(JSONObject o,String key)throws org.json.JSONException{
        Object value=o.get(key);if(!(value instanceof Number)||!value.toString().matches("[0-9]+"))throw new org.json.JSONException("Expected integer: "+key);
        return ((Number)value).longValue();
    }
    private static String jsonText(JSONObject o,String key){return o.isNull(key)?"":o.optString(key,"").trim();}
    private static boolean blank(String s){return s==null||s.trim().isEmpty();}
    private static void requireCurrent(long expected)throws IOException{if(Thread.currentThread().isInterrupted()||com.example.meetomxh.supabase.SessionManager.getInstance().generation()!=expected)throw new IOException("Phiên đăng nhập đã thay đổi.");}

    public static final class CallHolder{
        public final int generation;
        public CallHolder(){this(0);}public CallHolder(int generation){this.generation=generation;}
        private volatile Call call;private volatile boolean cancelled;
        void set(Call value){call=value;if(cancelled)value.cancel();}void clear(Call value){if(call==value)call=null;}
        public void cancel(){cancelled=true;Call c=call;if(c!=null)c.cancel();}
        boolean cancelled(){return cancelled;}
    }
}
