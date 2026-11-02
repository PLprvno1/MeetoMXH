package com.example.meetomxh.supabase;
import java.util.LinkedHashSet;
public final class EventDeduplicator {
 private final int capacity;private final LinkedHashSet<String> ids=new LinkedHashSet<>();
 public EventDeduplicator(int capacity){if(capacity<1)throw new IllegalArgumentException();this.capacity=capacity;}
 public synchronized boolean accept(String id){if(id==null||id.isEmpty()||!ids.add(id))return false;if(ids.size()>capacity)ids.remove(ids.iterator().next());return true;}
 public synchronized void clear(){ids.clear();}
}
