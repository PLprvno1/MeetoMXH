package com.example.meetomxh.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.bumptech.glide.Glide;
import com.example.meetomxh.CreateReelActivity;
import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.repositories.ReelsRepository;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.appbar.AppBarLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Full-height vertical Reels pager. Exactly one authorized ExoPlayer is kept per visible page. */
public final class ReelsFragment extends Fragment {
    private static int savedPosition;
    private static long savedPlaybackPosition;
    private static boolean muted=true;
    private View root; private RecyclerView list; private LinearLayoutManager layout; private PagerSnapHelper snap;
    private TextView title,message; private ProgressBar loading; private Button retry,modeButton;
    private ImageButton muteButton; private Adapter adapter; private String mode="discover";
    private ExoPlayer player; private boolean pageVisible,expandedCaption; private int activePosition=-1;
    private Subscription mediaRequest; private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable progressTicker=new Runnable(){@Override public void run(){if(player!=null&&activePosition>=0){adapter.progress(activePosition,player.getCurrentPosition(),player.getDuration());handler.postDelayed(this,250);}}};

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state){
        root=inflater.inflate(R.layout.fragment_reels,parent,false);list=root.findViewById(R.id.reels_list);title=root.findViewById(R.id.reels_title);
        message=root.findViewById(R.id.reels_message);loading=root.findViewById(R.id.reels_loading);retry=root.findViewById(R.id.reels_retry);
        modeButton=root.findViewById(R.id.reels_mode);muteButton=root.findViewById(R.id.reels_mute);
        layout=new LinearLayoutManager(requireContext(),RecyclerView.VERTICAL,false);list.setLayoutManager(layout);list.setItemViewCacheSize(1);
        adapter=new Adapter();list.setAdapter(adapter);snap=new PagerSnapHelper();snap.attachToRecyclerView(list);
        title.setOnClickListener(v->load(true));modeButton.setOnClickListener(v->{mode="discover".equals(mode)?"following":"discover";updateHeader();savedPosition=0;load(true);});
        root.findViewById(R.id.reels_create).setOnClickListener(v->startActivity(new Intent(requireContext(),CreateReelActivity.class)));
        retry.setOnClickListener(v->load(adapter.getItemCount()==0));muteButton.setOnClickListener(v->toggleMute());
        list.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv,int state){if(state==RecyclerView.SCROLL_STATE_IDLE){playSnapped();}else if(state==RecyclerView.SCROLL_STATE_DRAGGING&&player!=null)player.pause();}
            @Override public void onScrolled(@NonNull RecyclerView rv,int dx,int dy){int last=layout.findLastVisibleItemPosition();if(last>=adapter.getItemCount()-3)load(false);}
        });
        updateHeader();adapter.setRows(ReelsRepository.snapshot(mode));
        if(state!=null){savedPosition=state.getInt("reel_position",savedPosition);savedPlaybackPosition=state.getLong("reel_playback",savedPlaybackPosition);}
        if(adapter.getItemCount()==0)load(false);else{list.scrollToPosition(Math.min(savedPosition,adapter.getItemCount()-1));showContent();}
        return root;
    }
    private void updateHeader(){if(title==null)return;title.setText("discover".equals(mode)?"Reels · Khám phá":"Reels · Đang theo dõi");modeButton.setText("discover".equals(mode)?"Đang theo dõi":"Khám phá");muteButton.setImageResource(muted?R.drawable.ic_volume_off:R.drawable.ic_volume_on);muteButton.setContentDescription(muted?"Bật tiếng":"Tắt tiếng");}
    private void load(boolean refresh){if(!isAdded())return;if(adapter.getItemCount()==0){loading.setVisibility(View.VISIBLE);message.setText("Đang tải Reels…");message.setVisibility(View.VISIBLE);retry.setVisibility(View.GONE);}ReelsRepository.load(mode,refresh,new ReelsRepository.Callback(){
        public void success(List<Post> rows,boolean more){if(!isAdded()||root==null)return;adapter.setRows(rows);if(rows.isEmpty()){loading.setVisibility(View.GONE);message.setText("Chưa có Reel để xem. Hãy tạo video ngắn đầu tiên.");message.setVisibility(View.VISIBLE);retry.setVisibility(View.GONE);}else{showContent();if(savedPosition>0&&layout.findFirstVisibleItemPosition()<0)list.scrollToPosition(Math.min(savedPosition,rows.size()-1));}}
        public void error(String error){if(!isAdded()||root==null)return;loading.setVisibility(View.GONE);message.setText("Không tải được Reels. Kiểm tra mạng rồi thử lại.");message.setVisibility(View.VISIBLE);retry.setText("Thử lại");retry.setVisibility(View.VISIBLE);}
    });}
    private void showContent(){loading.setVisibility(View.GONE);message.setVisibility(View.GONE);retry.setVisibility(View.GONE);}
    private void toggleMute(){muted=!muted;if(player!=null)player.setVolume(muted?0f:1f);updateHeader();}
    private void playSnapped(){if(!isAdded()||!pageVisible)return;View child=snap.findSnapView(layout);if(child==null)return;int pos=layout.getPosition(child);if(pos<0||pos>=adapter.getItemCount())return;savedPosition=pos;Post post=adapter.rows.get(pos);if(activePosition==pos&&player!=null){player.play();return;}activePosition=pos;savedPlaybackPosition=0;handler.removeCallbacks(progressTicker);if(player==null)createPlayer();adapter.attachPlayer(pos,player);prepare(post,pos);}
    private void prepare(Post post,int position){if(player==null)createPlayer();if(mediaRequest!=null)mediaRequest.remove();player.stop();adapter.loading(position,true);String asset=null;for(int i=0;i<post.getImageUrls().size();i++)if(i<post.getMediaTypes().size()&&post.getMediaTypes().get(i).startsWith("video/")){asset=post.getImageUrls().get(i);break;}if(asset==null){adapter.failed(position);return;}String reference=asset;mediaRequest=Api.run(()->SupabaseStorage.info(reference),new Api.Callback<JSONObject>(){
        public void success(JSONObject info){if(!isAdded()||position!=activePosition||!pageVisible)return;try{player.setMediaItem(MediaItem.fromUri(SupabaseStorage.url(info)));player.prepare();player.seekTo(savedPlaybackPosition);player.setVolume(muted?0f:1f);player.play();adapter.loading(position,true);}catch(Exception e){adapter.failed(position);}}
        public void error(String e){if(position==activePosition)adapter.failed(position);}
    });}
    private void createPlayer(){Map<String,String> headers=new HashMap<>();headers.put("apikey",SupabaseConfig.KEY);try{headers.put("Authorization","Bearer "+SessionManager.getInstance().access(false));}catch(Exception ignored){}DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setConnectTimeoutMs(15000).setReadTimeoutMs(30000).setAllowCrossProtocolRedirects(false);DefaultDataSource.Factory data=new DefaultDataSource.Factory(requireContext(),http);player=new ExoPlayer.Builder(requireContext()).setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();player.setRepeatMode(Player.REPEAT_MODE_ONE);player.setVolume(muted?0f:1f);player.addListener(new Player.Listener(){@Override public void onPlaybackStateChanged(int state){if(activePosition>=0)adapter.loading(activePosition,state==Player.STATE_BUFFERING);if(state==Player.STATE_READY){adapter.failed(activePosition);adapter.hideCover(activePosition);handler.removeCallbacks(progressTicker);handler.post(progressTicker);}}@Override public void onPlayerError(PlaybackException e){if(activePosition>=0)adapter.failed(activePosition);}});}
    @Override public void onResume(){super.onResume();pageVisible=true;setImmersive(true);if(list!=null){if(ReelsRepository.consumeInvalidated())load(true);list.post(this::playSnapped);}}
    @Override public void onPause(){pageVisible=false;if(player!=null){savedPlaybackPosition=player.getCurrentPosition();player.pause();}if(mediaRequest!=null){mediaRequest.remove();mediaRequest=null;}activePosition=-1;handler.removeCallbacks(progressTicker);setImmersive(false);super.onPause();}
    private void setImmersive(boolean hidden){if(getActivity() instanceof com.example.meetomxh.MainActivity)((com.example.meetomxh.MainActivity)getActivity()).setReelsImmersive(hidden);}
    @Override public void onSaveInstanceState(@NonNull Bundle out){out.putInt("reel_position",Math.max(0,layout==null?0:layout.findFirstVisibleItemPosition()));out.putLong("reel_playback",player==null?savedPlaybackPosition:player.getCurrentPosition());super.onSaveInstanceState(out);}
    @Override public void onDestroyView(){if(layout!=null&&layout.findFirstVisibleItemPosition()>=0)savedPosition=layout.findFirstVisibleItemPosition();if(player!=null)savedPlaybackPosition=player.getCurrentPosition();if(mediaRequest!=null)mediaRequest.remove();handler.removeCallbacks(progressTicker);if(player!=null){player.release();player=null;}setImmersive(false);root=null;list=null;adapter=null;super.onDestroyView();}

    private final class Adapter extends RecyclerView.Adapter<Holder>{final List<Post> rows=new ArrayList<>();
        void setRows(List<Post> values){List<Post> next=values==null?Collections.emptyList():values;boolean prefix=next.size()>=rows.size();if(prefix)for(int i=0;i<rows.size();i++)if(!Objects.equals(rows.get(i).getPostId(),next.get(i).getPostId())){prefix=false;break;}if(prefix){int old=rows.size();for(int i=old;i<next.size();i++)rows.add(next.get(i));if(next.size()>old)notifyItemRangeInserted(old,next.size()-old);}else{rows.clear();rows.addAll(next);notifyDataSetChanged();if(player!=null){handler.removeCallbacks(progressTicker);player.stop();activePosition=-1;list.post(ReelsFragment.this::playSnapped);}}}
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reel,parent,false));}
        @Override public void onBindViewHolder(@NonNull Holder h,int position){Post p=rows.get(position);h.post=p;h.playerView.setUseController(false);h.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);h.playerView.setPlayer(null);h.buffering.setVisibility(position==activePosition?View.VISIBLE:View.GONE);h.retry.setVisibility(View.GONE);if(p.getReelCoverAssetId()!=null){h.cover.setVisibility(View.VISIBLE);Glide.with(h.cover).load(Dto.asset(p.getReelCoverAssetId())).centerCrop().into(h.cover);}else h.cover.setVisibility(View.GONE);h.name.setText(p.getUserName()==null?"Meeto":p.getUserName());AvatarViews.load(h.avatar,p.getUserAvatar(),p.getUserAvatarPreset());h.caption.setText(p.getContent());h.caption.setMaxLines(3);h.caption.setOnClickListener(v->{expandedCaption=!expandedCaption;h.caption.setMaxLines(expandedCaption?20:3);});h.like.setImageResource(p.isLiked() ? R.drawable.ic_like_filled : R.drawable.ic_like_outline);
            h.like.setColorFilter(p.isLiked() ? 0xFFED4956 : 0xFFFFFFFF);
            h.likes.setText(count(p.getLikesCount()));
            h.comments.setText(count(p.getCommentsCount()));
            h.save.setImageResource(p.isSaved() ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
            boolean own = SessionManager.getInstance().uid().equals(p.getAuthorUid());
            h.follow.setVisibility(own ? View.GONE : View.VISIBLE);
            h.follow.setText(p.isReelFollowing() ? "Đang theo dõi" : "Theo dõi");
            h.progress.setProgress(0);
            h.progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar s) { if (player != null) player.pause(); }
                public void onStopTrackingTouch(SeekBar s) { if (player != null && position == activePosition) { long d = player.getDuration(); if (d > 0) player.seekTo(d * s.getProgress() / 1000); player.play(); } }
                public void onProgressChanged(SeekBar s, int value, boolean user) {}
            });
            h.gestureDetector = new android.view.GestureDetector(requireContext(), new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                    if (position == activePosition && player != null) {
                        if (player.isPlaying()) player.pause(); else player.play();
                    }
                    return true;
                }
                @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                    playReelHeartAnimation(h.heartOverlay);
                    if (!p.isLiked()) {
                        h.like.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction(() -> h.like.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
                        toggleLike(p, h);
                    }
                    return true;
                }
            });
            h.itemView.setOnTouchListener((v, event) -> {
                if (h.gestureDetector != null) {
                    return h.gestureDetector.onTouchEvent(event);
                }
                return false;
            });
            h.retry.setOnClickListener(v -> { if (position == activePosition) prepare(p, position); });
            h.like.setOnClickListener(v -> {
                h.like.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction(() -> h.like.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
                toggleLike(p, h);
            });
            h.comment.setOnClickListener(v -> CommentsBottomSheetFragment.newInstance(p.getPostId()).show(getParentFragmentManager(), "ReelComments"));
            h.save.setOnClickListener(v -> {
                boolean desired = !p.isSaved();
                h.save.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100).withEndAction(() -> h.save.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
                p.setSaved(desired);
                h.save.setImageResource(desired ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
                SavedPostClient.set(p, desired, new Api.Callback<Boolean>() {
                    public void success(Boolean saved) { p.setSaved(saved); notifyItemChanged(position); }
                    public void error(String e) { p.setSaved(!desired); notifyItemChanged(position); Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show(); }
                });
            });
            h.share.setOnClickListener(v -> ShareActions.show(requireActivity(), p, false));
            h.follow.setOnClickListener(v -> follow(p, h));
            View.OnClickListener profile = v -> {
                if (p.getAuthorUid() != null) startActivity(new Intent(requireContext(), ProfileActivity.class).putExtra("target_uid", p.getAuthorUid()));
            };
            h.name.setOnClickListener(profile);
            h.avatar.setOnClickListener(profile);
        }
        @Override public int getItemCount(){return rows.size();}
        void attachPlayer(int position,ExoPlayer value){if(position<0||position>=rows.size())return;RecyclerView.ViewHolder vh=list.findViewHolderForAdapterPosition(position);if(vh instanceof Holder)((Holder)vh).playerView.setPlayer(value);for(int i=0;i<getItemCount();i++)if(i!=position){RecyclerView.ViewHolder other=list.findViewHolderForAdapterPosition(i);if(other instanceof Holder)((Holder)other).playerView.setPlayer(null);}}
        void loading(int pos,boolean value){RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder)((Holder)h).buffering.setVisibility(value?View.VISIBLE:View.GONE);}
        void failed(int pos){RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder){((Holder)h).buffering.setVisibility(View.GONE);((Holder)h).retry.setVisibility(View.VISIBLE);}}
        void hideCover(int pos){RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder)((Holder)h).cover.setVisibility(View.GONE);}
        void progress(int pos,long at,long duration){RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder&&duration>0)((Holder)h).progress.setProgress((int)(at*1000/duration));}
        private String count(int n){return n>999?(n/1000)+"k":String.valueOf(Math.max(0,n));}
        private void playReelHeartAnimation(ImageView overlay) {
            if (overlay == null) return;
            overlay.setVisibility(View.VISIBLE);
            overlay.setScaleX(0f);
            overlay.setScaleY(0f);
            overlay.setAlpha(0.95f);
            overlay.setTranslationY(0f);
            overlay.animate().scaleX(1.3f).scaleY(1.3f).alpha(1f).setDuration(180)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f))
                .withEndAction(() -> overlay.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80)
                    .withEndAction(() -> overlay.animate().scaleX(1.3f).scaleY(1.3f).alpha(0f).translationY(-50f).setDuration(240).setStartDelay(180)
                        .withEndAction(() -> {
                            overlay.setVisibility(View.GONE);
                            overlay.setTranslationY(0f);
                        }).start()
                    ).start()
                ).start();
        }
        private void toggleLike(Post p, Holder h) {
            boolean previous = p.isLiked();
            String old = p.getReaction();
            int oldCount = p.getLikesCount();
            boolean next = !previous;
            p.setReaction(next ? "like" : null);
            p.setLikesCount(Math.max(0, oldCount + (next ? 1 : -1)));
            h.like.setImageResource(next ? R.drawable.ic_like_filled : R.drawable.ic_like_outline);
            h.like.setColorFilter(next ? 0xFFED4956 : 0xFFFFFFFF);
            h.likes.setText(count(p.getLikesCount()));
            Api.run(() -> RpcClient.object("set_post_reaction", Api.obj("p_post_id", p.getPostId(), "p_reaction", next ? "like" : null)), new Api.Callback<JSONObject>() {
                public void success(JSONObject r) {
                    p.setLikesCount(r.optInt("reactions_count", p.getLikesCount()));
                    if (isAdded()) notifyItemChanged(rows.indexOf(p));
                }
                public void error(String e) {
                    p.setReaction(old);
                    p.setLikesCount(oldCount);
                    if (isAdded()) notifyItemChanged(rows.indexOf(p));
                    Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show();
                }
            });
        }
        private void follow(Post p,Holder h){boolean requested=!p.isReelFollowing();h.follow.setEnabled(false);Api.run(()->RpcClient.object("set_follow",Api.obj("p_target",p.getAuthorUid(),"p_follow",requested)),new Api.Callback<JSONObject>(){public void success(JSONObject r){p.setReelFollowing(r.optBoolean("following",requested));h.follow.setText(p.isReelFollowing()?"Đang theo dõi":"Theo dõi");h.follow.setEnabled(true);}public void error(String e){h.follow.setEnabled(true);Toast.makeText(requireContext(),e,Toast.LENGTH_SHORT).show();}});}
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final androidx.media3.ui.PlayerView playerView;
        final ProgressBar buffering;
        final Button retry, follow;
        final ImageView avatar, cover, like, comment, save, share, heartOverlay;
        final TextView name, caption, likes, comments;
        final SeekBar progress;
        Post post;
        android.view.GestureDetector gestureDetector;

        Holder(View v) {
            super(v);
            playerView = v.findViewById(R.id.reel_player);
            buffering = v.findViewById(R.id.reel_buffering);
            retry = v.findViewById(R.id.reel_retry);
            cover = v.findViewById(R.id.reel_cover);
            avatar = v.findViewById(R.id.reel_avatar);
            name = v.findViewById(R.id.reel_author);
            caption = v.findViewById(R.id.reel_caption);
            like = v.findViewById(R.id.reel_like);
            likes = v.findViewById(R.id.reel_like_count);
            comment = v.findViewById(R.id.reel_comment);
            comments = v.findViewById(R.id.reel_comment_count);
            save = v.findViewById(R.id.reel_save);
            share = v.findViewById(R.id.reel_share);
            progress = v.findViewById(R.id.reel_progress);
            follow = v.findViewById(R.id.reel_follow);
            heartOverlay = v.findViewById(R.id.reel_heart_overlay);
        }
    }
}
