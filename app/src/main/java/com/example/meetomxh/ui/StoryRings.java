package com.example.meetomxh.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.annotation.NonNull;
import com.example.meetomxh.R;
import com.example.meetomxh.supabase.*;
import org.json.*;
import java.util.*;

/** Batches visible avatar queries; never adds Story behavior to chat/comment avatars. */
public final class StoryRings {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View,java.lang.ref.WeakReference<Binding>> BINDINGS=new WeakHashMap<>();
    private static final Set<View> OBSERVED=Collections.newSetFromMap(new WeakHashMap<View,Boolean>());
    private static final Map<String,State> CACHE=new HashMap<>();
    private static long epoch=-1;
    private static boolean loading;private static int revision;
    static { SessionManager.getInstance().identityListeners.add(StoryRings::identityChanged); }
    private static final class State{String id;long expires,checked;}
    private static final class Binding{String author;java.lang.ref.WeakReference<View> ringRef;java.lang.ref.WeakReference<ImageView> avatarRef;View.OnClickListener fallback,navigation;Binding(String a,View r,ImageView v,View.OnClickListener f){author=a;ringRef=new java.lang.ref.WeakReference<>(r);avatarRef=new java.lang.ref.WeakReference<>(v);fallback=f;}View ring(){return ringRef.get();}ImageView avatar(){return avatarRef.get();}}
    private static final Runnable REFRESH=StoryRings::refresh;
    public static void bind(View ring,ImageView avatar,String author,View.OnClickListener fallback){
        if(!StoryClient.validId(author)){BINDINGS.remove(avatar);avatar.setTag(R.id.story_ring_binding,null);avatar.setOnClickListener(fallback);avatar.setClickable(fallback!=null);if(ring!=avatar)ring.setBackgroundResource(R.drawable.shape_story_ring_seen);else if(avatar instanceof com.google.android.material.imageview.ShapeableImageView)((com.google.android.material.imageview.ShapeableImageView)avatar).setStrokeWidth(0);return;}
        boolean first=OBSERVED.add(avatar);Binding b=new Binding(author,ring,avatar,fallback);avatar.setTag(R.id.story_ring_binding,b);BINDINGS.put(avatar,new java.lang.ref.WeakReference<>(b));apply(b);
        MAIN.removeCallbacks(REFRESH);MAIN.postDelayed(REFRESH,80);

        // View attachment also covers RecyclerView rows that are rebound without a lifecycle change.
        if(first)avatar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(View v){MAIN.post(REFRESH);}
            public void onViewDetachedFromWindow(View v){}
        });
    }
    /** A tray has its own selected story ID; ring refreshes must never replace its click. */
    public static void bindTray(View ring,ImageView avatar,com.example.meetomxh.models.Story story,View.OnClickListener navigation){
        String author=story.getAuthorId();
        State cached=CACHE.get(author);
        if(StoryClient.validId(author)&&StoryClient.validId(story.getId())&&story.expiryMillis()>System.currentTimeMillis()&&(cached==null||cached.id==null||cached.expires<=System.currentTimeMillis())){
            State state=new State();state.id=story.getId();state.expires=story.expiryMillis();state.checked=System.currentTimeMillis();CACHE.put(author,state);
        }
        bind(ring,avatar,author,navigation);
        Object tag=avatar.getTag(R.id.story_ring_binding);
        if(tag instanceof Binding){Binding binding=(Binding)tag;binding.navigation=navigation;apply(binding);}
        avatar.setOnClickListener(navigation);
    }
    public static void unbind(ImageView avatar){BINDINGS.remove(avatar);avatar.setTag(R.id.story_ring_binding,null);avatar.setOnClickListener(null);avatar.setClickable(false);}
    public static void observe(LifecycleOwner owner){owner.getLifecycle().addObserver(new DefaultLifecycleObserver(){
        @Override public void onResume(@NonNull LifecycleOwner ignored){invalidate();}
    });}
    public static void invalidate(){revision++;CACHE.clear();for(Binding b:bindings())apply(b);MAIN.removeCallbacks(REFRESH);MAIN.post(REFRESH);}
    private static void identityChanged(){epoch=SessionManager.getInstance().generation();revision++;CACHE.clear();loading=false;for(Binding b:bindings())apply(b);MAIN.removeCallbacks(REFRESH);}
    private static void apply(Binding b){if(b.avatar()==null||b.ring()==null)return;
        State s=CACHE.get(b.author);boolean active=s!=null&&s.id!=null&&s.expires>System.currentTimeMillis()&&s.checked+30000>System.currentTimeMillis();
        if(b.ring()!=b.avatar())b.ring().setBackgroundResource(active?R.drawable.shape_story_ring:R.drawable.shape_story_ring_seen);
        else if(b.avatar() instanceof com.google.android.material.imageview.ShapeableImageView){
            com.google.android.material.imageview.ShapeableImageView image=(com.google.android.material.imageview.ShapeableImageView)b.avatar();
            image.setStrokeWidth(active?3*b.avatar().getResources().getDisplayMetrics().density:0);
            int gap=active?Math.round(3*b.avatar().getResources().getDisplayMetrics().density):0;image.setContentPadding(gap,gap,gap,gap);
            image.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF9C4DFF));
        }
        View.OnClickListener click=b.navigation!=null?b.navigation:active?StoryNavigation.listener(b.author,s.id):b.fallback;
        b.avatar().setOnClickListener(click);b.avatar().setClickable(click!=null);
    }
    private static void refresh(){
        if(epoch!=SessionManager.getInstance().generation()){epoch=SessionManager.getInstance().generation();CACHE.clear();loading=false;revision++;}
        Set<String> authors=new LinkedHashSet<>();long now=System.currentTimeMillis();CACHE.entrySet().removeIf(e->e.getValue().checked+60000<now);
        for(Binding b:bindings())if(b.avatar()!=null&&b.avatar().isAttachedToWindow()&&b.avatar().getWindowVisibility()==View.VISIBLE&&b.avatar().isShown()){
            apply(b);State s=CACHE.get(b.author);if(s==null||s.checked+30000<=now||s.id!=null&&s.expires<=now)authors.add(b.author);
        }
        if(authors.isEmpty()||loading){schedule();return;}
        List<String> batch=new ArrayList<>(authors);if(batch.size()>200)batch=batch.subList(0,200);
        List<String> requested=new ArrayList<>(batch);long session=epoch;int version=revision;loading=true;
        Api.run(()->(JSONArray)RpcClient.call("get_story_states",Api.obj("p_authors",new JSONArray(requested))),new Api.Callback<JSONArray>(){
            public void success(JSONArray rows){loading=false;if(session!=SessionManager.getInstance().generation()||version!=revision){MAIN.post(REFRESH);return;}
                long at=System.currentTimeMillis();for(String author:requested){State s=new State();s.checked=at;CACHE.put(author,s);}
                for(int i=0;i<rows.length();i++){JSONObject o=rows.optJSONObject(i);if(o==null)continue;State s=CACHE.get(o.optString("author_id"));if(s!=null){s.id=o.optString("story_id");s.expires=StoryTime.millis(o.optString("expires_at"));}}
                for(Binding b:bindings())apply(b);schedule();
            }
            public void error(String error){loading=false;if(session!=SessionManager.getInstance().generation()||version!=revision){MAIN.post(REFRESH);return;}for(String author:requested){State absent=new State();absent.checked=System.currentTimeMillis();CACHE.put(author,absent);}for(Binding b:bindings())apply(b);schedule();}
        });
    }
    private static List<Binding> bindings(){List<Binding> out=new ArrayList<>();for(java.lang.ref.WeakReference<Binding> ref:BINDINGS.values()){Binding b=ref.get();if(b!=null)out.add(b);}return out;}
    private static void schedule(){MAIN.removeCallbacks(REFRESH);boolean visible=false;for(Binding b:bindings())if(b.avatar()!=null&&b.avatar().isAttachedToWindow()&&b.avatar().getWindowVisibility()==View.VISIBLE&&b.avatar().isShown()){visible=true;break;}if(visible)MAIN.postDelayed(REFRESH,1000);}
}
