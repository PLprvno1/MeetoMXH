package com.example.meetomxh.repositories;

import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.example.meetomxh.supabase.Subscription;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Cursor paging and a small in-memory tab cache for authorized Reels RPC results. */
public final class ReelsRepository {
    public interface Callback { void success(List<Post> posts, boolean hasMore); void error(String message); }
    private static final int PAGE=10;
    private static final class State {
        final LinkedHashMap<String,Post> rows=new LinkedHashMap<>();
        String afterTime,afterId; boolean hasMore=true,loading; long session=-1;
    }
    private static final State DISCOVER=new State(), FOLLOWING=new State();
    private static volatile boolean invalidated;
    private ReelsRepository() { }
    private static State state(String mode) { return "following".equals(mode)?FOLLOWING:DISCOVER; }

    public static synchronized List<Post> snapshot(String mode) {
        State s=state(mode); resetForSession(s);
        return new ArrayList<>(s.rows.values());
    }
    public static void invalidate(){synchronized(ReelsRepository.class){DISCOVER.rows.clear();DISCOVER.afterTime=null;DISCOVER.afterId=null;DISCOVER.hasMore=true;FOLLOWING.rows.clear();FOLLOWING.afterTime=null;FOLLOWING.afterId=null;FOLLOWING.hasMore=true;invalidated=true;}}
    public static boolean consumeInvalidated(){boolean value=invalidated;invalidated=false;return value;}

    public static void load(String mode, boolean refresh, Callback callback) {
        final State s=state(mode); final String uid=SessionManager.getInstance().uid();
        final String beforeTime,beforeId;
        synchronized (ReelsRepository.class) {
            resetForSession(s);
            if(s.loading){callback.success(new ArrayList<>(s.rows.values()),s.hasMore);return;}
            if(refresh){s.rows.clear();s.afterTime=null;s.afterId=null;s.hasMore=true;}
            if(!s.hasMore&&!refresh){callback.success(new ArrayList<>(s.rows.values()),false);return;}
            s.loading=true;beforeTime=refresh?null:s.afterTime;beforeId=refresh?null:s.afterId;
        }
        Api.run(()->(JSONArray)RpcClient.call("list_reels",Api.obj("p_mode",mode,
                "p_after_time",beforeTime,"p_after_id",beforeId,"p_limit",PAGE)),new Api.Callback<JSONArray>(){
            public void success(JSONArray data){
                List<Post> result=new ArrayList<>();boolean more=data.length()==PAGE;
                synchronized(ReelsRepository.class){
                    s.loading=false;
                    if(!uid.equals(SessionManager.getInstance().uid()))return;
                    for(int i=0;i<data.length();i++){JSONObject o=data.optJSONObject(i);if(o==null)continue;Post p=Dto.post(o);if(p.getPostId()!=null&&!p.getPostId().isEmpty())s.rows.put(p.getPostId(),p);}
                    if(data.length()>0){JSONObject last=data.optJSONObject(data.length()-1);if(last!=null){s.afterTime=last.optString("created_at",null);s.afterId=last.optString("id",null);}}
                    s.hasMore=more;result.addAll(s.rows.values());
                }
                callback.success(result,more);
            }
            public void error(String message){synchronized(ReelsRepository.class){s.loading=false;}callback.error(message);}
        });
    }
    private static void resetForSession(State s){long generation=SessionManager.getInstance().generation();if(s.session!=generation){s.rows.clear();s.afterTime=null;s.afterId=null;s.hasMore=true;s.loading=false;s.session=generation;}}
}
