package com.example.meetomxh.music;

import org.json.JSONException;
import org.json.JSONObject;

/** Validated, public Openverse provenance kept with the rendered media draft. */
public final class OpenverseTrack {
    public final String id, title, creator, provider, source, landingUrl, audioUrl, license, licenseUrl, attribution;
    public final long durationMs;
    public final String localPath;
    public int startMs, musicVolume = 35, sourceVolume = 100, stillDurationMs = 15_000;
    public long selectedDurationMs;

    public OpenverseTrack(String id, String title, String creator, String provider, String source,
            String landingUrl, String audioUrl, String license, String licenseUrl, String attribution,
            long durationMs, String localPath) {
        this.id=id;this.title=title;this.creator=creator;this.provider=provider;this.source=source;
        this.landingUrl=landingUrl;this.audioUrl=audioUrl;this.license=license;this.licenseUrl=licenseUrl;
        this.attribution=attribution;this.durationMs=durationMs;this.localPath=localPath;
    }

    public JSONObject metadata(long usedMs) throws JSONException {
        return new JSONObject().put("id",id).put("title",title).put("creator",creator)
                .put("provider",provider).put("source",source).put("source_url",landingUrl)
                .put("audio_url",audioUrl).put("license","cc0").put("license_url",licenseUrl)
                .put("attribution",attribution).put("duration_ms",durationMs).put("start_offset_ms",startMs)
                .put("used_duration_ms",usedMs);
    }

    public JSONObject draftJson() throws JSONException {
        return metadata(durationMs).put("duration_ms",durationMs).put("local_path",localPath)
                .put("music_volume",musicVolume).put("source_volume",sourceVolume)
                .put("still_duration_ms",stillDurationMs);
    }

    public OpenverseTrack copy() {
        OpenverseTrack copy=new OpenverseTrack(id,title,creator,provider,source,landingUrl,audioUrl,license,licenseUrl,attribution,durationMs,localPath);
        copy.startMs=startMs;copy.musicVolume=musicVolume;copy.sourceVolume=sourceVolume;copy.stillDurationMs=stillDurationMs;
        copy.selectedDurationMs=selectedDurationMs;return copy;
    }

    public static OpenverseTrack fromDraft(JSONObject o) {
        if(o==null)return null;
        try {
            String id=o.getString("id"),title=o.getString("title"),creator=o.optString("creator",""),
                    provider=o.getString("provider"),source=o.getString("source"),landing=o.getString("source_url"),
                    audio=o.getString("audio_url"),license=o.getString("license"),licenseUrl=o.getString("license_url"),
                    attribution=o.optString("attribution",""),path=o.getString("local_path");
            long duration=o.getLong("duration_ms");
            if(!OpenverseMusicClient.validMetadata(id,title,provider,source,landing,audio,license,licenseUrl,duration)
                    ||path.trim().isEmpty())return null;
            OpenverseTrack t=new OpenverseTrack(id,title,creator,provider,source,landing,audio,license,licenseUrl,
                    attribution,duration,path);
            t.startMs=Math.max(0,o.optInt("start_offset_ms"));
            t.musicVolume=clamp(o.optInt("music_volume",35));t.sourceVolume=clamp(o.optInt("source_volume",100));
            t.stillDurationMs=Math.max(5_000,Math.min(60_000,o.optInt("still_duration_ms",15_000)));
            return t;
        }catch(Exception ignored){return null;}
    }
    private static int clamp(int value){return Math.max(0,Math.min(100,value));}
}
