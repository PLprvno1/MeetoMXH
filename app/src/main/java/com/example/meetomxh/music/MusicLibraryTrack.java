package com.example.meetomxh.music;

import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;

/** Immutable catalog identity. No token, signed URL or invented Openverse UUID. */
public final class MusicLibraryTrack {
    public final String id, version, title, artist, bucket, path, sha256, licenseUrl, sourceUrl, attribution;
    public final long durationMs, sizeBytes;
    public final int sortOrder;

    private MusicLibraryTrack(JSONObject o) throws JSONException {
        id=text(o,"track_id"); version=text(o,"catalog_version"); title=text(o,"title"); artist=text(o,"artist");
        bucket=text(o,"bucket_id"); path=text(o,"object_path"); sha256=text(o,"sha256");
        licenseUrl=text(o,"license_url"); sourceUrl=text(o,"source_url"); attribution=text(o,"attribution");
        durationMs=integer(o,"duration_ms"); sizeBytes=integer(o,"file_size_bytes");
        long order=integer(o,"sort_order"); sortOrder=(int)order;
        if(!id.matches("meeto_music_[0-9]{3}") || version.length()>80 || title.length()>300 || artist.length()>300
                || !bucket.equals("music-library") || !path.matches("v[0-9]+/"+id+"[.]ogg")
                || !sha256.matches("[0-9a-f]{64}") || durationMs<1000 || durationMs>3_600_000
                || sizeBytes<1 || sizeBytes>5*1024*1024 || order<1 || order>10000
                || !text(o,"mime_type").equals("audio/ogg") || !text(o,"codec").equals("vorbis")
                || !text(o,"container").equals("ogg") || !text(o,"license_id").equals("CC0-1.0")
                || !licenseUrl.equals("https://creativecommons.org/publicdomain/zero/1.0/")
                || !https(sourceUrl) || attribution.length()>800) throw new JSONException("Invalid music catalog track");
    }
    public static MusicLibraryTrack parse(JSONObject o) throws JSONException {
        if(o==null)throw new JSONException("Missing music catalog track");
        return new MusicLibraryTrack(o);
    }
    public JSONObject json() throws JSONException {
        return new JSONObject().put("track_id",id).put("catalog_version",version).put("title",title)
                .put("artist",artist).put("sort_order",sortOrder).put("duration_ms",durationMs)
                .put("bucket_id",bucket).put("object_path",path).put("sha256",sha256).put("file_size_bytes",sizeBytes)
                .put("mime_type","audio/ogg").put("codec","vorbis").put("container","ogg")
                .put("license_id","CC0-1.0").put("license_url",licenseUrl).put("source_url",sourceUrl).put("attribution",attribution);
    }
    public JSONObject metadata(int start,long used,int musicVolume,int sourceVolume) throws JSONException {
        JSONObject result=new JSONObject().put("contract_version",2).put("source","meeto_library")
                .put("track_id",id).put("catalog_version",version).put("title",title).put("artist",artist)
                .put("duration_ms",durationMs).put("start_offset_ms",start).put("used_duration_ms",used)
                .put("music_volume",musicVolume).put("source_volume",sourceVolume).put("license_id","CC0-1.0")
                .put("license_url",licenseUrl).put("source_url",sourceUrl).put("attribution",attribution).put("sha256",sha256);
        if(!validMetadata(result))throw new JSONException("Invalid library music segment");
        return result;
    }
    /** Shape validation only; the server still verifies exact catalog identity and active status. */
    public static boolean validMetadata(JSONObject o) {
        try {
            String[] keys={"contract_version","source","track_id","catalog_version","title","artist","duration_ms",
                    "start_offset_ms","used_duration_ms","music_volume","source_volume","license_id","license_url","source_url","attribution","sha256"};
            if(o==null||o.length()!=keys.length||o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)return false;
            for(String key:keys)if(!o.has(key))return false;
            long duration=integer(o,"duration_ms"),start=integer(o,"start_offset_ms"),used=integer(o,"used_duration_ms");
            return integer(o,"contract_version")==2 && text(o,"source").equals("meeto_library")
                    && text(o,"track_id").matches("meeto_music_[0-9]{3}") && text(o,"catalog_version").length()<=80
                    && text(o,"title").length()<=300 && text(o,"artist").length()<=300
                    && duration>=1000&&duration<=3_600_000&&start>=0&&start<duration&&used>=1000&&used<=60000
                    && integer(o,"music_volume")<=100&&integer(o,"source_volume")<=100
                    && text(o,"license_id").equals("CC0-1.0")
                    && text(o,"license_url").equals("https://creativecommons.org/publicdomain/zero/1.0/")
                    && https(text(o,"source_url"))&&text(o,"attribution").length()<=800&&text(o,"sha256").matches("[0-9a-f]{64}");
        }catch(Exception invalid){return false;}
    }
    static long integer(JSONObject o,String key) throws JSONException {
        Object n=o.get(key); if(!(n instanceof Number)||!n.toString().matches("[0-9]{1,10}"))throw new JSONException("Invalid number: "+key);
        return ((Number)n).longValue();
    }
    private static String text(JSONObject o,String key)throws JSONException {
        Object value=o.get(key);if(!(value instanceof String)||((String)value).trim().isEmpty())throw new JSONException("Invalid string: "+key);
        return (String)value;
    }
    private static boolean https(String address){try{URI u=URI.create(address);return "https".equals(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null;}catch(Exception e){return false;}}
}
