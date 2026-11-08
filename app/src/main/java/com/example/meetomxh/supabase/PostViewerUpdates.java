package com.example.meetomxh.supabase;

import android.app.Activity;
import androidx.lifecycle.MutableLiveData;
import com.example.meetomxh.models.Post;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Small session-scoped UI invalidation; never clears a paged list or changes its scroll position. */
public final class PostViewerUpdates {
    public static final MutableLiveData<Long> changes=new MutableLiveData<>();
    private static final Map<String,Event> recent=new LinkedHashMap<>();
    private static final WeakHashMap<Activity,Boolean> origins=new WeakHashMap<>();
    private static long epoch=-1,revision;
    static { SessionManager.getInstance().identityListeners.add(PostViewerUpdates::identityChanged); }
    public static final class Event {
        public final boolean engagementOnly;
        public final String id,reaction;public final int likes,comments,shares;public final boolean saved,deleted;public final long revision;
        private final String body,audience;private final long version;private final java.util.List<String> media,types;
        Event(Post p,boolean deleted){this(p,deleted,false);}
        Event(Post p,boolean deleted,boolean engagementOnly){this.engagementOnly=engagementOnly;id=p.getPostId();reaction=p.getReaction();likes=p.getLikesCount();comments=p.getCommentsCount();shares=p.getSharesCount();saved=p.isSaved();this.deleted=deleted;revision=++PostViewerUpdates.revision;
            body=p.getContent();audience=p.getVisibility();version=p.version;media=new java.util.ArrayList<>(p.getImageUrls());types=new java.util.ArrayList<>(p.getMediaTypes());}
        public void apply(Post p){p.setReaction(reaction);p.setLikesCount(likes);p.setCommentsCount(comments);p.setSharesCount(shares);p.setSaved(saved);
            if(!engagementOnly&&version>=p.version){p.setContent(body);p.setVisibility(audience);p.version=version;p.setImageUrls(new java.util.ArrayList<>(media));p.setMediaTypes(new java.util.ArrayList<>(types));p.setPostImage(media.isEmpty()?null:media.get(0));if(!types.isEmpty())p.setMediaType(types.get(0).startsWith("video/")?"video":"image");}}
    }
    private static void session(){long current=SessionManager.getInstance().generation();if(epoch!=current){recent.clear();origins.clear();epoch=current;}}
    private static void identityChanged(){recent.clear();origins.clear();epoch=SessionManager.getInstance().generation();revision++;}
    public static void publish(Post p,boolean deleted){publish(p,deleted,false);}
    public static void publishEngagement(Post p){publish(p,false,true);}
    private static void publish(Post p,boolean deleted,boolean engagementOnly){session();Event event=new Event(p,deleted,engagementOnly);recent.put(p.getPostId(),event);while(recent.size()>50)recent.remove(recent.keySet().iterator().next());com.example.meetomxh.repositories.FeedRepository.getInstance().applyViewerEvent(event);changes.setValue(revision);}
    public static java.util.List<Event> events(){session();return new java.util.ArrayList<>(recent.values());}
    public static void reconcile(String id){session();Event before=recent.get(id);long expected=before==null?0:before.revision;
        Api.run(()->Dto.post(RpcClient.object("get_post",Api.obj("p_post_id",id))),new Api.Callback<Post>(){
            public void success(Post post){session();Event now=recent.get(id);if((now==null?0:now.revision)==expected)publish(post,false);}
            public void error(String error){}
        });
    }
    public static void opening(Activity activity){session();origins.put(activity,true);}
    public static boolean returning(Activity activity){session();return origins.remove(activity)!=null;}
    private PostViewerUpdates(){}
}
