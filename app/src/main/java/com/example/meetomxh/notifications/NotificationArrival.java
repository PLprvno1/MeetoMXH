package com.example.meetomxh.notifications;

import java.util.LinkedHashSet;

/** Baseline existing unread items; alert only once for later arrivals in this session. */
public final class NotificationArrival {
    private boolean initialized;
    private long baselineTime=Long.MIN_VALUE;
    private final LinkedHashSet<String> seen=new LinkedHashSet<>();
    public boolean isInitialized(){return initialized;}
    public void initialized(){initialized=true;}
    public boolean accept(String id,long createdAt){
        if(id==null||id.isEmpty()||!seen.add(id))return false;
        if(!initialized)baselineTime=Math.max(baselineTime,createdAt);
        if(seen.size()>2048)seen.remove(seen.iterator().next());return !initialized||createdAt>=baselineTime;
    }
    public void clear(){seen.clear();initialized=false;baselineTime=Long.MIN_VALUE;}
}
