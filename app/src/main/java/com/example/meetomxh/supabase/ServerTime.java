package com.example.meetomxh.supabase;
import java.util.Date;
public final class ServerTime implements Comparable<ServerTime> {
 private final long millis;
 public ServerTime(long seconds,int nanos){millis=seconds*1000+nanos/1000000;}
 public ServerTime(Date date){millis=date.getTime();}
 public static ServerTime parse(String value){try{String normalized=value;java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\.([0-9]+)(Z|[+-][0-9]{2}:[0-9]{2})$").matcher(value);if(m.find()){String fraction=(m.group(1)+"000").substring(0,3);normalized=value.substring(0,m.start())+"."+fraction+m.group(2);}else normalized=value.replaceFirst("(Z|[+-][0-9]{2}:[0-9]{2})$",".000$1");java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",java.util.Locale.US);format.setLenient(false);java.text.ParsePosition position=new java.text.ParsePosition(0);Date date=format.parse(normalized,position);return date!=null&&position.getIndex()==normalized.length()?new ServerTime(date):null;}catch(Exception e){return null;}}
 public static ServerTime now(){return new ServerTime(new Date());}
 public Date toDate(){return new Date(millis);}
 public long getSeconds(){return millis/1000;}
 public int getNanoseconds(){return (int)(millis%1000)*1000000;}
 public int compareTo(ServerTime other){return Long.compare(millis,other.millis);}
 public boolean equals(Object o){return o instanceof ServerTime&&millis==((ServerTime)o).millis;}
 public int hashCode(){return Long.hashCode(millis);}
}
