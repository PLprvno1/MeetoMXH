package com.example.meetomxh.music;

import org.json.*;
import java.util.Locale;

final class MusicLibraryFixtures {
    static JSONObject row(int n,byte[] bytes)throws Exception {
        String id=String.format(Locale.ROOT,"meeto_music_%03d",n);
        return new JSONObject().put("track_id",id).put("catalog_version","test_catalog_v1").put("sort_order",n)
                .put("title","Track "+n).put("artist","Artist").put("duration_ms",36000)
                .put("bucket_id","music-library").put("object_path","v1/"+id+".ogg")
                .put("mime_type","audio/ogg").put("codec","vorbis").put("container","ogg")
                .put("file_size_bytes",bytes.length).put("sha256",MusicLibraryCache.digest(bytes))
                .put("license_id","CC0-1.0").put("license_url","https://creativecommons.org/publicdomain/zero/1.0/")
                .put("source_url","https://example.org/music").put("attribution","Artist");
    }
    static JSONObject capabilities(int count)throws Exception {
        return new JSONObject().put("contract_version",2).put("source","meeto_library").put("bucket_id","music-library")
                .put("delivery","authenticated_storage").put("max_used_duration_ms",60000).put("active_track_count",count).put("enabled",count>0);
    }
    static JSONObject page(int first,int last,boolean more)throws Exception {
        JSONArray rows=new JSONArray();for(int i=first;i<=last;i++)rows.put(row(i,new byte[]{1,2,3,4}));
        return new JSONObject().put("contract_version",2).put("items",rows).put("has_more",more)
                .put("next_cursor",more?new JSONObject().put("catalog_version","test_catalog_v1").put("sort_order",last):JSONObject.NULL);
    }
}
