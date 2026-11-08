package com.example.meetomxh.fragments;

import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
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
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.ui.PlayerView;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import com.bumptech.glide.Glide;
import com.example.meetomxh.ReelsCameraActivity;
import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.repositories.ReelsRepository;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.AvatarViews;
import com.example.meetomxh.ui.ReelPlaybackMonitor;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.json.JSONObject;
import java.util.*;

/** Full-height vertical Reels pager. Exactly one authorized ExoPlayer is kept per visible page. */
public final class ReelsFragment extends Fragment {
    private static final Object ENGAGEMENT_PAYLOAD = new Object();
    private static int savedPosition;
    private static long savedPlaybackPosition;
    private static boolean muted=true;
    private View root; private RecyclerView list; private LinearLayoutManager layout; private PagerSnapHelper snap;
    private TextView title,message; private ProgressBar loading; private Button retry,modeButton;
    private ImageButton muteButton; private Adapter adapter; private String mode="discover";
    private ExoPlayer player; private boolean pageVisible; private int activePosition=-1;
    private ReelPlaybackMonitor playbackMonitor;
    private Player.Listener playerListener;
    private DefaultHttpDataSource.Factory playbackHttp;
    private static final class PreparedReel {
        final JSONObject info;
        final String accessToken;
        PreparedReel(JSONObject info, String accessToken) { this.info=info; this.accessToken=accessToken; }
    }
    private int mediaGeneration;
    private boolean feedLoading,hasMore=true,userPaused,autoScroll;
    private String endedReelId;
    private BottomSheetDialog reelSheet;
    private boolean managingReel, accessChecking;
    private android.app.Dialog managementDialog;
    private Subscription accessRequest;
    private Subscription targetRequest;
    private final Set<String> mutationPending=new HashSet<>();
    private final Runnable accessTicker=new Runnable(){public void run(){
        if(!pageVisible||adapter==null)return;
        Post post=currentReel();if(post!=null&&!accessChecking)checkCurrentAccess(post);
        handler.postDelayed(this,15000);
    }};
    private Subscription mediaRequest; private final Handler handler=new Handler(Looper.getMainLooper());
    private String targetReelId; private final Set<String> countedViews=new HashSet<>();
    private Post entryPost;
    private final java.util.Map<String,Long> viewerRevisions=new java.util.HashMap<>();
    public static ReelsFragment newInstance(String reelId){ReelsFragment f=new ReelsFragment();Bundle b=new Bundle();b.putString("target_reel_id",reelId);f.setArguments(b);return f;}
    private final Runnable progressTicker=new Runnable(){@Override public void run(){if(pageVisible&&root!=null&&adapter!=null&&player!=null&&activePosition>=0){adapter.progress(activePosition,player.getCurrentPosition(),player.getDuration());handler.postDelayed(this,250);}}};

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state){
        autoScroll=preferences().getBoolean("auto_scroll",false);
        root=inflater.inflate(R.layout.fragment_reels,parent,false);list=root.findViewById(R.id.reels_list);title=root.findViewById(R.id.reels_title);
        message=root.findViewById(R.id.reels_message);loading=root.findViewById(R.id.reels_loading);retry=root.findViewById(R.id.reels_retry);
        modeButton=root.findViewById(R.id.reels_mode);muteButton=root.findViewById(R.id.reels_mute);
        layout=new LinearLayoutManager(requireContext(),RecyclerView.VERTICAL,false);list.setLayoutManager(layout);list.setItemViewCacheSize(2);
        // Full-screen video pages must not cross-fade or temporarily duplicate their surfaces.
        list.setItemAnimator(null);
        adapter=new Adapter();list.setAdapter(adapter);snap=new PagerSnapHelper();snap.attachToRecyclerView(list);
        title.setOnClickListener(v->load(true));modeButton.setOnClickListener(v->{mode="discover".equals(mode)?"following":"discover";updateHeader();savedPosition=0;load(true);});
        root.findViewById(R.id.reels_create).setOnClickListener(v->startActivity(new Intent(requireContext(),ReelsCameraActivity.class)));
        root.findViewById(R.id.reels_back).setOnClickListener(v->requireActivity().getOnBackPressedDispatcher().onBackPressed());
        root.findViewById(R.id.reels_search).setOnClickListener(v->showSearch());
        root.findViewById(R.id.reels_more).setOnClickListener(v->showReelMenu());
        retry.setOnClickListener(v->load(!hasMore));muteButton.setOnClickListener(v->toggleMute());
        list.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv,int state){if(state==RecyclerView.SCROLL_STATE_IDLE){playSnapped();}else if(state==RecyclerView.SCROLL_STATE_DRAGGING){endedReelId=null;if(player!=null)player.pause();}}
            @Override public void onScrolled(@NonNull RecyclerView rv,int dx,int dy){int last=layout.findLastVisibleItemPosition();if(last>=adapter.getItemCount()-3)load(false);}
        });
        updateHeader();adapter.setRows(ReelsRepository.snapshot(mode));
        if(state!=null){savedPosition=state.getInt("reel_position",savedPosition);savedPlaybackPosition=state.getLong("reel_playback",savedPlaybackPosition);}
        targetReelId=getArguments()==null?null:getArguments().getString("target_reel_id");
        if(targetReelId!=null)adapter.setRows(Collections.emptyList());
        if(adapter.getItemCount()==0&&targetReelId==null)load(false);else if(adapter.getItemCount()>0){list.scrollToPosition(Math.min(savedPosition,adapter.getItemCount()-1));showContent();}
        return root;
    }
    @Override public void onViewCreated(@NonNull View v,@Nullable Bundle state){super.onViewCreated(v,state);PostUpdates.counts.observe(getViewLifecycleOwner(),c->{if(c==null||adapter==null)return;for(int i=0;i<adapter.rows.size();i++){Post p=adapter.rows.get(i);if(c.id.equals(p.getPostId())){p.setCommentsCount(c.comments);com.example.meetomxh.supabase.PostViewerUpdates.publishEngagement(p);break;}}});
        com.example.meetomxh.supabase.PostViewerUpdates.changes.observe(getViewLifecycleOwner(),value->{if(adapter==null)return;
            for(com.example.meetomxh.supabase.PostViewerUpdates.Event event:com.example.meetomxh.supabase.PostViewerUpdates.events()){
                if(event.revision<=viewerRevisions.getOrDefault(event.id,0L))continue;
                viewerRevisions.put(event.id,event.revision);
                if(event.deleted){removeReel(event.id,false);continue;}
                for(int i=0;i<adapter.rows.size();i++){Post p=adapter.rows.get(i);if(event.id.equals(p.getPostId())){event.apply(p);if(event.engagementOnly)adapter.notifyItemChanged(i,ENGAGEMENT_PAYLOAD);else adapter.notifyItemChanged(i);break;}}
            }
        });
        PostUpdates.content.observe(getViewLifecycleOwner(),c->{if(c==null||adapter==null)return;
            if(c.deleted)removeReel(c.id,false);else for(Post p:adapter.rows)if(c.id.equals(p.getPostId())){p.setVisibility(c.audience);p.version=c.version;}
        });
    }
    private void updateHeader(){if(title==null)return;title.setText("Reels");modeButton.setText("discover".equals(mode)?"Khám phá ▾":"Đang theo dõi ▾");muteButton.setImageResource(muted?R.drawable.ic_volume_off:R.drawable.ic_volume_on);muteButton.setContentDescription(muted?"Bật tiếng":"Tắt tiếng");}
    private void load(boolean refresh){
        if(!isAdded()||root==null)return;
        if(targetReelId!=null){loadTargetReel();return;}
        if(feedLoading||(!refresh&&!hasMore))return;
        feedLoading=true;
        String requestedMode=mode;
        View requestedRoot=root;
        if(adapter.getItemCount()==0){loading.setVisibility(View.VISIBLE);message.setText("Đang tải Reels…");message.setVisibility(View.VISIBLE);retry.setVisibility(View.GONE);}
        ReelsRepository.load(requestedMode,refresh,new ReelsRepository.Callback(){
            public void success(List<Post> rows,boolean more){
                if(!isAdded()||root!=requestedRoot)return;
                feedLoading=false;
                if(!requestedMode.equals(mode)){load(true);return;}
                hasMore=more;
                if(entryPost!=null&&!refresh){List<Post> ordered=new ArrayList<>();ordered.add(entryPost);for(Post row:rows)if(!entryPost.getPostId().equals(row.getPostId()))ordered.add(row);rows=ordered;}
                if(refresh)entryPost=null;
                adapter.setRows(rows);
                if(adapter.getItemCount()==0){loading.setVisibility(View.GONE);message.setText("Chưa có Reel để xem. Hãy thử khám phá hoặc tải thêm.");message.setVisibility(View.VISIBLE);retry.setText("Tải lại");retry.setVisibility(View.VISIBLE);}
                else{showContent();if(savedPosition>0&&layout.findFirstVisibleItemPosition()<0)list.scrollToPosition(Math.min(savedPosition,adapter.getItemCount()-1));list.post(ReelsFragment.this::playSnapped);}
                advanceAfterEnd();
            }
            public void error(String error){
                if(!isAdded()||root!=requestedRoot)return;
                feedLoading=false;
                if(!requestedMode.equals(mode)){load(true);return;}
                endedReelId=null;loading.setVisibility(View.GONE);
                message.setText("Không tải được Reels. Kiểm tra mạng rồi thử lại.");message.setVisibility(View.VISIBLE);retry.setText("Thử lại");retry.setVisibility(View.VISIBLE);
            }
        });
    }

    private void advanceAfterEnd(){
        if(!autoScroll||userPaused||endedReelId==null||!pageVisible||reelSheet!=null||managingReel||list==null||list.getScrollState()!=RecyclerView.SCROLL_STATE_IDLE)return;
        Post current=currentReel();
        if(current==null||!endedReelId.equals(current.getPostId())){endedReelId=null;return;}
        if(activePosition+1<adapter.getItemCount()){
            endedReelId=null;list.smoothScrollToPosition(activePosition+1);
        }else if(!hasMore){endedReelId=null;}
    }

    private Post currentReel(){
        if(list==null||adapter==null)return null;
        View child=snap.findSnapView(layout);
        int position=child==null?RecyclerView.NO_POSITION:layout.getPosition(child);
        return position>=0&&position<adapter.rows.size()?adapter.rows.get(position):null;
    }

    private SharedPreferences preferences(){
        return requireContext().getSharedPreferences("reel_preferences_"+SessionManager.getInstance().uid(),Context.MODE_PRIVATE);
    }

    private void setAutoScroll(boolean enabled){
        autoScroll=enabled;
        preferences().edit().putBoolean("auto_scroll",enabled).apply();
        endedReelId=null;
        if(player==null)return;
        player.setRepeatMode(enabled?Player.REPEAT_MODE_OFF:Player.REPEAT_MODE_ONE);
        if(player.getPlaybackState()==Player.STATE_ENDED){
            if(enabled){
                Post current=currentReel();
                if(current!=null)endedReelId=current.getPostId();
                if(endedReelId!=null&&activePosition+1>=adapter.getItemCount()&&hasMore)load(false);
            }else player.seekTo(0);
        }
    }

    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private TextView sheetText(String label,int size){
        TextView text=new TextView(requireContext());text.setText(label);text.setTextSize(size);
        text.setTextColor(0xFFFFFFFF);text.setGravity(android.view.Gravity.CENTER_VERTICAL);
        text.setPadding(dp(8),dp(12),dp(8),dp(12));
        return text;
    }

    private LinearLayout sheetContent(String heading){
        LinearLayout content=new LinearLayout(requireContext());content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16),dp(12),dp(16),dp(24));
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(0xFF171719);background.setCornerRadii(new float[]{dp(24),dp(24),dp(24),dp(24),0,0,0,0});content.setBackground(background);
        TextView title=sheetText(heading,20);title.setTypeface(null,android.graphics.Typeface.BOLD);content.addView(title);
        return content;
    }

    private void sheetAction(LinearLayout content,String label,Runnable action){
        TextView row=sheetText(label,16);row.setMinHeight(dp(52));row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(v->action.run());content.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }

    private BottomSheetDialog openSheet(LinearLayout content){
        boolean resume=!userPaused;
        if(player!=null)player.pause();
        BottomSheetDialog dialog=new BottomSheetDialog(requireContext());reelSheet=dialog;
        dialog.setContentView(content);
        dialog.setOnDismissListener(d->{if(reelSheet==dialog)reelSheet=null;if(pageVisible&&player!=null&&!managingReel){if(resume&&player.getPlaybackState()!=Player.STATE_ENDED)player.play();advanceAfterEnd();}});
        dialog.show();
        View sheet=dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if(sheet!=null)sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        return dialog;
    }

    private void managementAction(LinearLayout content,String label,int icon,boolean destructive,Runnable action){
        sheetAction(content,label,action);TextView row=(TextView)content.getChildAt(content.getChildCount()-1);
        android.graphics.drawable.Drawable drawable=androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(),icon);
        if(drawable!=null){drawable=drawable.mutate();androidx.core.graphics.drawable.DrawableCompat.setTint(drawable,destructive?0xFFFF6B81:0xFFFFFFFF);drawable.setBounds(0,0,dp(22),dp(22));row.setCompoundDrawables(drawable,null,null,null);row.setCompoundDrawablePadding(dp(14));}
        if(destructive)row.setTextColor(0xFFFF6B81);
    }

    private void showReelMenu(){
        if(reelSheet!=null)return;
        Post post=currentReel();
        if(post==null){Toast.makeText(requireContext(),"Chưa có Reel để chọn",Toast.LENGTH_SHORT).show();return;}
        LinearLayout content=sheetContent("Tùy chọn Reel");
        final BottomSheetDialog[] dialog=new BottomSheetDialog[1];
        androidx.appcompat.widget.SwitchCompat autoScrollSwitch=new androidx.appcompat.widget.SwitchCompat(requireContext());
        autoScrollSwitch.setText("Tự động cuộn");autoScrollSwitch.setTextSize(16);autoScrollSwitch.setTextColor(0xFFFFFFFF);
        autoScrollSwitch.setPadding(dp(8),dp(12),dp(8),dp(12));autoScrollSwitch.setMinHeight(dp(52));
        autoScrollSwitch.setChecked(autoScroll);content.addView(autoScrollSwitch,new LinearLayout.LayoutParams(-1,-2));
        TextView autoScrollHint=sheetText("Tự chuyển sang Reel tiếp theo khi video kết thúc.",14);
        autoScrollHint.setTextColor(0xFFBDBDBD);content.addView(autoScrollHint);
        autoScrollSwitch.setOnCheckedChangeListener((button,checked)->{setAutoScroll(checked);dialog[0].dismiss();});
        if(Objects.equals(post.getAuthorUid(),SessionManager.getInstance().uid())){
            managementAction(content,post.isReel()?"Chỉnh sửa quyền riêng tư":"Chỉnh sửa bài viết gốc",R.drawable.ic_reel_privacy,false,()->{
                if(!post.isReel()){dialog[0].dismiss();startActivity(new Intent(requireContext(),com.example.meetomxh.CreatePostActivity.class).putExtra("post_id",post.getPostId()));return;}
                managingReel=true;dialog[0].dismiss();showPrivacy(post);
            });
            managementAction(content,"Xóa Reel",R.drawable.ic_reel_delete,true,()->{managingReel=true;dialog[0].dismiss();confirmDelete(post);});
            sheetAction(content,"Chia sẻ",()->{dialog[0].dismiss();ShareActions.show(requireActivity(),post,false);});
            sheetAction(content,"Đóng",()->dialog[0].dismiss());dialog[0]=openSheet(content);return;
        }
        boolean interested="interested".equals(preferences().getString(post.getPostId(),""));
        sheetAction(content,interested?"✓ Quan tâm":"♡ Quan tâm",()->{
            preferences().edit().putString(post.getPostId(),"interested").apply();
            Toast.makeText(requireContext(),"Đã lưu quan tâm trên thiết bị này",Toast.LENGTH_SHORT).show();dialog[0].dismiss();
        });
        sheetAction(content,"⊖ Không quan tâm",()->{
            preferences().edit().putString(post.getPostId(),"not_interested").apply();
            int position=Math.max(0,activePosition);
            adapter.setRows(new ArrayList<>(adapter.rows));
            if(adapter.getItemCount()>0){list.scrollToPosition(Math.min(position,adapter.getItemCount()-1));list.post(this::playSnapped);}
            else{loading.setVisibility(View.GONE);message.setText("Đã ẩn Reel. Tải thêm để tiếp tục khám phá.");message.setVisibility(View.VISIBLE);retry.setText("Tải thêm");retry.setVisibility(View.VISIBLE);}
            Toast.makeText(requireContext(),"Đã ẩn Reel trên thiết bị này",Toast.LENGTH_SHORT).show();dialog[0].dismiss();
        });
        sheetAction(content,"Chia sẻ",()->{dialog[0].dismiss();ShareActions.show(requireActivity(),post,false);});
        sheetAction(content,"Báo cáo",()->{dialog[0].dismiss();startActivity(new Intent(requireContext(),com.example.meetomxh.ReportContentActivity.class)
                .putExtra(com.example.meetomxh.ReportContentActivity.EXTRA_TARGET_TYPE,"post")
                .putExtra(com.example.meetomxh.ReportContentActivity.EXTRA_TARGET_ID,post.getPostId()));});
        sheetAction(content,"Đóng",()->dialog[0].dismiss());
        dialog[0]=openSheet(content);
    }

    private void finishManaging(){managementDialog=null;managingReel=false;if(pageVisible&&list!=null)list.post(this::playSnapped);}
    private void showPrivacy(Post post){
        if(!isAdded()||root==null){finishManaging();return;}
        LinearLayout content=sheetContent("Quyền riêng tư của Reel");
        content.addView(sheetText("Hiện tại: "+AudienceChoices.label(post.getVisibility())+"\nNếu đã chia sẻ đồng thời lên Feed, quyền mới áp dụng cho cả hai nơi.",14));
        Spinner choices=new Spinner(requireContext());content.addView(choices,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView status=sheetText("Đang tải quyền được hỗ trợ…",14);content.addView(status);
        com.google.android.material.button.MaterialButton save=new com.google.android.material.button.MaterialButton(requireContext());
        save.setText("Lưu");save.setEnabled(false);save.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE1306C));content.addView(save);
        BottomSheetDialog dialog=openSheet(content);
        dialog.setOnDismissListener(d->{if(reelSheet==dialog)reelSheet=null;finishManaging();});
        Api.run(ProfileClient::self,new Api.Callback<JSONObject>(){
            public void success(JSONObject profile){if(!dialog.isShowing()||!isAdded())return;
                JSONObject settings=profile.optJSONObject("settings");boolean restricted=settings!=null&&settings.optBoolean("account_private");
                String[] values=restricted?new String[]{"followers","private"}:new String[]{"public","followers","private"};
                List<String> labels=new ArrayList<>();labels.add("Chọn quyền riêng tư");for(String value:values)labels.add(AudienceChoices.label(value));
                choices.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_dropdown_item,labels));
                int current=Arrays.asList(values).indexOf(post.getVisibility());choices.setSelection(current+1);
                status.setText("Quyền riêng tư sẽ được máy chủ xác nhận khi lưu.");
                choices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){if(v instanceof TextView)((TextView)v).setTextColor(0xFFFFFFFF);save.setEnabled(n>0&&!mutationPending.contains(post.getPostId()));}});
                save.setOnClickListener(v->{int selected=choices.getSelectedItemPosition()-1;if(selected<0||!mutationPending.add(post.getPostId()))return;
                    String audience=values[selected];save.setEnabled(false);choices.setEnabled(false);dialog.setCancelable(false);status.setText("Đang lưu…");
                    Api.run(()->RpcClient.object("set_reel_audience",Api.obj("p_post_id",post.getPostId(),"p_audience",audience,"p_expected_version",post.version)),new Api.Callback<JSONObject>(){
                        public void success(JSONObject result){mutationPending.remove(post.getPostId());
                            if(!post.getPostId().equals(result.optString("id"))||!audience.equals(result.optString("audience"))){error("Máy chủ chưa xác nhận quyền riêng tư.");return;}
                            PostUpdates.changed(result,false);
                            com.example.meetomxh.supabase.PostViewerUpdates.reconcile(post.getPostId());
                            if(isAdded()){Toast.makeText(requireContext(),"Đã cập nhật quyền riêng tư trên Feed và Reels",Toast.LENGTH_SHORT).show();dialog.dismiss();}
                        }
                        public void error(String e){mutationPending.remove(post.getPostId());if(!isAdded()||!dialog.isShowing())return;status.setText(e+" · Đóng và mở lại menu nếu dữ liệu đã thay đổi.");dialog.setCancelable(true);choices.setEnabled(true);save.setEnabled(true);}
                    });
                });
            }
            public void error(String e){if(dialog.isShowing())status.setText(e+" · Đóng và mở lại để thử lại.");}
        });
    }
    private void confirmDelete(Post post){
        if(!isAdded()){finishManaging();return;}
        if(player!=null)player.pause();
        androidx.appcompat.app.AlertDialog dialog=new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Xóa Reel?").setMessage("Reel sẽ không còn khả dụng. Nếu đã đăng đồng thời lên Feed, nội dung chung ở Feed cũng bị gỡ. Liên kết và tin nhắn chia sẻ sẽ không còn mở được nội dung này.")
                .setNegativeButton("Hủy",null).setPositiveButton("Xóa Reel",null).create();
        managementDialog=dialog;dialog.setOnDismissListener(d->finishManaging());
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            if(!mutationPending.add(post.getPostId()))return;
            dialog.setCancelable(false);dialog.getButton(-1).setEnabled(false);dialog.getButton(-2).setEnabled(false);dialog.setMessage("Đang xóa Reel…");
            Api.run(()->RpcClient.object(post.isReel()?"delete_reel":"delete_post",Api.obj("p_post_id",post.getPostId(),"p_expected_version",post.version)),new Api.Callback<JSONObject>(){
                public void success(JSONObject result){mutationPending.remove(post.getPostId());
                    if(!post.getPostId().equals(result.optString("id"))||!"deleted".equals(result.optString("state"))){error("Máy chủ chưa xác nhận xóa Reel.");return;}
                    com.example.meetomxh.supabase.PostViewerUpdates.publish(post,true);
                    PostUpdates.changed(result,true);if(isAdded()){Toast.makeText(requireContext(),"Đã xóa Reel và các vị trí phân phối của nội dung",Toast.LENGTH_SHORT).show();dialog.dismiss();}
                }
                public void error(String e){mutationPending.remove(post.getPostId());if(!isAdded()||!dialog.isShowing())return;
                    dialog.setMessage(e+"\nReel được giữ nguyên. Nếu dữ liệu thay đổi, hãy đóng và mở lại Reel.");dialog.setCancelable(true);dialog.getButton(-1).setEnabled(true);dialog.getButton(-2).setEnabled(true);}
            });
        }));dialog.show();
    }
    private void removeReel(String id,boolean unavailable){
        if(adapter==null||list==null)return;
        if(entryPost!=null&&id.equals(entryPost.getPostId()))entryPost=null;
        int index=-1;for(int i=0;i<adapter.rows.size();i++)if(id.equals(adapter.rows.get(i).getPostId())){index=i;break;}
        if(index<0)return;
        boolean active=index==activePosition;int oldPosition=activePosition;
        if(active){mediaGeneration++;if(mediaRequest!=null){mediaRequest.remove();mediaRequest=null;}
            handler.removeCallbacks(progressTicker);if(player!=null){player.pause();player.stop();player.clearMediaItems();}
            if(playbackMonitor!=null)playbackMonitor.attach(null);activePosition=-1;endedReelId=null;savedPlaybackPosition=0;}
        adapter.rows.remove(index);adapter.notifyItemRemoved(index);
        if(!active&&index<oldPosition){activePosition--;savedPosition=Math.max(0,activePosition);}
        if(index<adapter.rows.size())adapter.notifyItemRangeChanged(index,adapter.rows.size()-index);
        if(adapter.rows.isEmpty()){savedPosition=0;showUnavailable();if(hasMore)load(false);}
        else if(active){savedPosition=Math.min(index,adapter.rows.size()-1);list.scrollToPosition(savedPosition);list.post(this::playSnapped);}
        if(unavailable&&isAdded())Toast.makeText(requireContext(),"Reel đã bị xóa hoặc bạn không còn quyền xem",Toast.LENGTH_LONG).show();
    }
    private void checkCurrentAccess(Post post){
        accessChecking=true;View expectedRoot=root;
        accessRequest=Api.run(()->RpcClient.object("get_reel_access",Api.obj("p_post_id",post.getPostId())),new Api.Callback<JSONObject>(){
            public void success(JSONObject result){accessChecking=false;if(root!=expectedRoot||!pageVisible||adapter==null)return;
                if(!result.optBoolean("available")){ReelsRepository.contentChanged(post.getPostId(),"",0,true);com.example.meetomxh.supabase.PostViewerUpdates.publish(post,true);removeReel(post.getPostId(),true);return;}
                JSONObject reel=result.optJSONObject("reel");if(reel!=null)reconcileReel(post,reel);
            }
            public void error(String e){accessChecking=false;/* Keep network failures distinct from revoked access. */}
        });
    }
    private void reconcileReel(Post post,JSONObject reel){
        if(mutationPending.contains(post.getPostId()))return;
        if(reel.optLong("version",post.version)<post.version)return;
        boolean contentChanged=post.version!=reel.optLong("version",post.version)
                ||!Objects.equals(post.getContent(),reel.optString("body",post.getContent()))
                ||!Objects.equals(post.getVisibility(),reel.optString("audience"));
        post.setVisibility(reel.optString("audience"));post.version=reel.optLong("version",post.version);
        post.setContent(reel.optString("body",post.getContent()));
        post.setReelFollowing(reel.optBoolean("is_following",post.isReelFollowing()));
        post.setReaction(Dto.nullable(reel,"my_reaction"));post.setSaved(reel.optBoolean("is_saved"));
        post.setLikesCount(reel.optInt("reactions_count"));post.setCommentsCount(reel.optInt("comments_count"));
        post.setSharesCount(reel.optInt("shares_count"));post.setReelViewsCount(reel.optInt("views_count",post.getReelViewsCount()));
        if(contentChanged)com.example.meetomxh.supabase.PostViewerUpdates.publish(post,false);
        else com.example.meetomxh.supabase.PostViewerUpdates.publishEngagement(post);
    }

    private void showSearch(){
        if(reelSheet!=null)return;
        final BottomSheetDialog[] dialog=new BottomSheetDialog[1];
        com.example.meetomxh.ui.ReelsSearchView content=new com.example.meetomxh.ui.ReelsSearchView(requireContext(),post->{
            if(dialog[0]!=null)dialog[0].dismiss();
            if(isAdded())com.example.meetomxh.supabase.PostDetails.show(requireActivity(),post.getPostId());
        });
        dialog[0]=openSheet(content);
    }
    private void showCaption(Post post){
        if(reelSheet!=null)return;
        LinearLayout content=sheetContent(post.getUserName()==null?"Nội dung Reel":post.getUserName());
        ScrollView scroll=new ScrollView(requireContext());
        String captionText=post.getContent();if(!post.getMusicLabel().isEmpty())captionText=captionText.isEmpty()?post.getMusicLabel():captionText+"\n"+post.getMusicLabel();
        TextView caption=sheetText(captionText,16);caption.setTextIsSelectable(true);
        com.example.meetomxh.ui.MentionText.bindPost(caption,captionText,post.getMentions(),post.getTags());
        if(post.getMusicMetadata()!=null)caption.setOnClickListener(v->com.example.meetomxh.music.MusicAttribution.showLinks(v.getContext(),post.getMusicMetadata()));
        scroll.addView(caption);content.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(360),getResources().getDisplayMetrics().heightPixels/2)));
        openSheet(content);
    }
    private void loadTargetReel(){
        String id=targetReelId;if(id==null||!isAdded()||root==null)return;View expectedRoot=root;
        if(targetRequest!=null)targetRequest.remove();
        loading.setVisibility(View.VISIBLE);message.setText("Đang mở Reel…");message.setVisibility(View.VISIBLE);retry.setVisibility(View.GONE);
        targetRequest=Api.run(()->RpcClient.object("get_reel_access",Api.obj("p_post_id",id)),new Api.Callback<JSONObject>(){
            public void success(JSONObject json){if(!isAdded()||root!=expectedRoot)return;
                JSONObject card=json.optJSONObject("reel");
                if(!json.optBoolean("available")||card==null){targetReelId=null;showUnavailable();return;}
                Post target=Dto.post(card);
                ReelsRepository.load(mode,true,new ReelsRepository.Callback(){
                    public void success(List<Post> rows,boolean more){hasMore=more;if(root==expectedRoot&&pageVisible)showTargetAndRows(id,target,rows);}
                    public void error(String e){if(root==expectedRoot&&pageVisible)showTargetAndRows(id,target,Collections.emptyList());}
                });
            }
            public void error(String e){if(!isAdded()||root!=expectedRoot)return;loading.setVisibility(View.GONE);message.setText(e);message.setVisibility(View.VISIBLE);retry.setText("Thử lại");retry.setVisibility(View.VISIBLE);}
        });
    }
    private void showTargetAndRows(String id,Post target,List<Post> rows){if(!isAdded()||root==null)return;entryPost=target;List<Post> ordered=new ArrayList<>();ordered.add(target);for(Post p:rows)if(!id.equals(p.getPostId()))ordered.add(p);targetReelId=null;savedPlaybackPosition=0;adapter.setRows(ordered);savedPosition=0;list.scrollToPosition(0);showContent();list.post(ReelsFragment.this::playSnapped);}
    private void showUnavailable(){loading.setVisibility(View.GONE);message.setText("Nội dung không còn khả dụng");message.setVisibility(View.VISIBLE);retry.setVisibility(View.GONE);Toast.makeText(requireContext(),"Nội dung không còn khả dụng",Toast.LENGTH_LONG).show();}
    private void showContent(){loading.setVisibility(View.GONE);message.setVisibility(View.GONE);retry.setVisibility(View.GONE);}
    private void toggleMute(){muted=!muted;if(player!=null)player.setVolume(muted?0f:1f);updateHeader();}
    private void playSnapped(){
        if(!isAdded()||!pageVisible||targetReelId!=null||list==null||reelSheet!=null||managingReel||list.getScrollState()!=RecyclerView.SCROLL_STATE_IDLE)return;
        View child=snap.findSnapView(layout);if(child==null)return;
        int pos=layout.getPosition(child);if(pos<0||pos>=adapter.getItemCount())return;
        Post post=adapter.rows.get(pos);
        if(activePosition==pos&&player!=null){adapter.attachPlayer(pos,player);if(!userPaused&&player.getPlaybackState()!=Player.STATE_ENDED&&(playbackMonitor==null||!playbackMonitor.hasFailed()))player.play();return;}
        if(pos!=savedPosition)savedPlaybackPosition=0;
        savedPosition=pos;activePosition=pos;endedReelId=null;userPaused=false;
        handler.removeCallbacks(progressTicker);recordView(post);if(player==null)createPlayer();adapter.attachPlayer(pos,player);prepare(post,pos);
    }
    private void recordView(Post post){if(post==null||!post.isReel()||post.getPostId()==null||!countedViews.add(post.getPostId()))return;Api.run(()->RpcClient.object("record_reel_view",Api.obj("p_post_id",post.getPostId())),new Api.Callback<JSONObject>(){public void success(JSONObject result){post.setReelViewsCount(result.optInt("views_count",post.getReelViewsCount()));}public void error(String e){countedViews.remove(post.getPostId());}});}
    private void prepare(Post post,int position){
        if(player==null)createPlayer();
        if(mediaRequest!=null)mediaRequest.remove();
        final int token=++mediaGeneration;
        player.stop();playbackMonitor.begin(post.getPostId());adapter.showCover(position);adapter.loading(position,true);
        String asset=null;
        for(int i=0;i<post.getImageUrls().size();i++)if(i<post.getMediaTypes().size()&&post.getMediaTypes().get(i).startsWith("video/")){asset=post.getImageUrls().get(i);break;}
        if(asset==null){adapter.failed(position);return;}
        String reference=asset;
        mediaRequest=Api.run(()->{
            JSONObject access=RpcClient.object("get_reel_access",Api.obj("p_post_id",post.getPostId()));
            if(!access.optBoolean("available"))return new PreparedReel(Api.obj("reel_unavailable",true),null);
            JSONObject info=SupabaseStorage.info(reference);info.put("authorized_reel",access.optJSONObject("reel"));
            // Token renewal can perform network I/O and must stay off the UI thread.
            return new PreparedReel(info,SessionManager.getInstance().access(false));
        },new Api.Callback<PreparedReel>(){
            public void success(PreparedReel prepared){
                JSONObject info=prepared.info;
                if(!isAdded()||token!=mediaGeneration||position!=activePosition||!pageVisible||adapter==null||position>=adapter.rows.size()||!Objects.equals(post.getPostId(),adapter.rows.get(position).getPostId()))return;
                try{
                    if(info.optBoolean("reel_unavailable")){ReelsRepository.contentChanged(post.getPostId(),"",0,true);com.example.meetomxh.supabase.PostViewerUpdates.publish(post,true);removeReel(post.getPostId(),true);return;}
                    JSONObject authorized=info.optJSONObject("authorized_reel");if(authorized!=null)reconcileReel(post,authorized);
                    if(!info.optString("mime_type").startsWith("video/")){adapter.failed(position);return;}
                    Map<String,String> headers=new HashMap<>();headers.put("apikey",SupabaseConfig.KEY);
                    headers.put("Authorization","Bearer "+prepared.accessToken);
                    playbackHttp.setDefaultRequestProperties(headers);
                    adapter.attachPlayer(position,player);
                    player.setMediaItem(new MediaItem.Builder().setUri(SupabaseStorage.url(info)).setMediaId(post.getPostId()).build());
                    player.seekTo(savedPlaybackPosition);player.setVolume(muted?0f:1f);player.prepare();
                    player.setPlayWhenReady(reelSheet==null&&!managingReel&&!userPaused&&list.getScrollState()==RecyclerView.SCROLL_STATE_IDLE&&adapter.hasAttachedPlayer(position));
                }catch(Exception error){player.pause();adapter.failed(position);}
            }
            public void error(String error){if(token==mediaGeneration&&adapter!=null&&position==activePosition)adapter.failed(position);}
        });
    }
    private void createPlayer(){
        Map<String,String> headers=new HashMap<>();headers.put("apikey",SupabaseConfig.KEY);
        playbackHttp=new DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).setConnectTimeoutMs(15000).setReadTimeoutMs(30000).setAllowCrossProtocolRedirects(false);
        DefaultDataSource.Factory data=new DefaultDataSource.Factory(requireContext(),playbackHttp);
        player=new ExoPlayer.Builder(requireContext(),new DefaultRenderersFactory(requireContext()).setEnableDecoderFallback(true)).setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        player.setRepeatMode(autoScroll?Player.REPEAT_MODE_OFF:Player.REPEAT_MODE_ONE);player.setVolume(muted?0f:1f);
        playbackMonitor=new ReelPlaybackMonitor(player,new ReelPlaybackMonitor.Callback(){
            public void waiting(){if(adapter!=null&&activePosition>=0)adapter.showCover(activePosition);}
            public void firstFrame(){if(adapter!=null&&activePosition>=0){adapter.ready(activePosition);adapter.hideCover(activePosition);}}
            public void failed(String message){if(adapter!=null&&activePosition>=0)adapter.failed(activePosition,message);}
        });
        playerListener=new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(!pageVisible||root==null||player==null||playbackMonitor==null||adapter==null||activePosition<0)return;
                adapter.loading(activePosition,state==Player.STATE_BUFFERING||state==Player.STATE_READY&&!playbackMonitor.hasFrame()&&!playbackMonitor.hasFailed());
                if(state==Player.STATE_READY){if(playbackMonitor.hasFrame())adapter.ready(activePosition);handler.removeCallbacks(progressTicker);handler.post(progressTicker);}
                if(state==Player.STATE_ENDED){
                    handler.removeCallbacks(progressTicker);
                    if(!autoScroll||activePosition>=adapter.rows.size()||!playbackMonitor.hasFrame())return;
                    endedReelId=adapter.rows.get(activePosition).getPostId();
                    advanceAfterEnd();
                    if(endedReelId!=null&&hasMore)load(false);
                }
            }
            @Override public void onPlayerError(PlaybackException e){handler.removeCallbacks(progressTicker);}
        };player.addListener(playerListener);
    }
    /** Called by MainActivity before replacing the fragment, for both Back and tab navigation. */
    public void leaveReels(){
        pageVisible=false;endedReelId=null;mediaGeneration++;
        if(mediaRequest!=null){mediaRequest.remove();mediaRequest=null;}
        if(accessRequest!=null){accessRequest.remove();accessRequest=null;}accessChecking=false;
        if(targetRequest!=null){targetRequest.remove();targetRequest=null;}
        handler.removeCallbacks(progressTicker);handler.removeCallbacks(accessTicker);
        if(player!=null)savedPlaybackPosition=player.getCurrentPosition();
        releasePlayback();
    }
    private void releasePlayback(){
        ExoPlayer oldPlayer=player;ReelPlaybackMonitor oldMonitor=playbackMonitor;
        activePosition=-1;player=null;playbackMonitor=null;playbackHttp=null;
        if(oldPlayer!=null&&playerListener!=null)oldPlayer.removeListener(playerListener);playerListener=null;
        if(oldMonitor!=null)oldMonitor.release();
        if(oldPlayer!=null){oldPlayer.pause();oldPlayer.clearMediaItems();oldPlayer.release();}
    }
    @Override public void onResume(){super.onResume();pageVisible=true;setImmersive(true);if(list!=null){if(ReelsRepository.consumeInvalidated())load(true);else if(adapter.getItemCount()==0)load(false);list.post(this::playSnapped);}handler.removeCallbacks(accessTicker);handler.postDelayed(accessTicker,15000);}
    @Override public void onPause(){pageVisible=false;endedReelId=null;mediaGeneration++;if(reelSheet!=null)reelSheet.dismiss();if(player!=null){savedPlaybackPosition=player.getCurrentPosition();player.pause();if(playbackMonitor!=null)playbackMonitor.attach(null);}if(mediaRequest!=null){mediaRequest.remove();mediaRequest=null;}if(accessRequest!=null){accessRequest.remove();accessRequest=null;}accessChecking=false;handler.removeCallbacks(accessTicker);activePosition=-1;handler.removeCallbacks(progressTicker);setImmersive(false);super.onPause();}
    private void setImmersive(boolean hidden){if(getActivity() instanceof com.example.meetomxh.MainActivity)((com.example.meetomxh.MainActivity)getActivity()).setReelsImmersive(hidden);}
    @Override public void onSaveInstanceState(@NonNull Bundle out){out.putInt("reel_position",Math.max(0,layout==null?0:layout.findFirstVisibleItemPosition()));out.putLong("reel_playback",player==null?savedPlaybackPosition:player.getCurrentPosition());super.onSaveInstanceState(out);}
    @Override public void onDestroyView(){
        if(layout!=null&&layout.findFirstVisibleItemPosition()>=0)savedPosition=layout.findFirstVisibleItemPosition();
        leaveReels();
        if(managementDialog!=null)managementDialog.dismiss();
        if(reelSheet!=null)reelSheet.dismiss();
        if(list!=null){list.clearOnScrollListeners();list.stopScroll();list.setAdapter(null);}
        if(snap!=null)snap.attachToRecyclerView(null);
        handler.removeCallbacksAndMessages(null);feedLoading=false;setImmersive(false);
        root=null;list=null;layout=null;snap=null;adapter=null;
        super.onDestroyView();
    }
    private final class Adapter extends RecyclerView.Adapter<Holder>{final List<Post> rows=new ArrayList<>();
        private final com.bumptech.glide.RequestManager imageRequests=Glide.with(ReelsFragment.this);
        void setRows(List<Post> values){
            List<Post> next=new ArrayList<>();SharedPreferences prefs=preferences();
            if(values!=null)for(Post p:values)if(!"not_interested".equals(prefs.getString(p.getPostId(),"")))next.add(p);
            boolean prefix=next.size()>=rows.size();
            if(prefix)for(int i=0;i<rows.size();i++)if(!Objects.equals(rows.get(i).getPostId(),next.get(i).getPostId())){prefix=false;break;}
            if(prefix){int old=rows.size();for(int i=old;i<next.size();i++)rows.add(next.get(i));if(next.size()>old)notifyItemRangeInserted(old,next.size()-old);}
            else{
                if(mediaRequest!=null){mediaRequest.remove();mediaRequest=null;}
                handler.removeCallbacks(progressTicker);mediaGeneration++;if(playbackMonitor!=null)playbackMonitor.attach(null);activePosition=-1;endedReelId=null;savedPlaybackPosition=0;
                if(player!=null)player.stop();
                rows.clear();rows.addAll(next);notifyDataSetChanged();list.post(ReelsFragment.this::playSnapped);
            }
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reel,parent,false));}
        @Override public void onBindViewHolder(@NonNull Holder h,int position){
            Post p=rows.get(position);h.post=p;h.playerView.setUseController(false);
            h.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            boolean active=position==activePosition&&player!=null;
            if(active&&h.itemView.isAttachedToWindow()&&pageVisible)playbackMonitor.attach(h.playerView);
            else if(!active&&playbackMonitor!=null&&playbackMonitor.owns(h.playerView))playbackMonitor.attach(null);
            h.buffering.setVisibility(active&&player.getPlaybackState()==Player.STATE_BUFFERING?View.VISIBLE:View.GONE);
            h.retry.setVisibility(active&&(player.getPlayerError()!=null||playbackMonitor.hasFailed())?View.VISIBLE:View.GONE);
            if(!active||!playbackMonitor.hasFrame()){
                h.cover.setVisibility(View.VISIBLE);imageRequests.load(p.getReelCoverAssetId()==null?null:Dto.asset(p.getReelCoverAssetId())).placeholder(R.drawable.ic_reel_video).error(R.drawable.ic_reel_video).fitCenter().into(h.cover);
            }else h.cover.setVisibility(View.GONE);
            h.name.setText(p.getUserName()==null?"Meeto":p.getUserName());AvatarViews.load(h.avatar,p.getUserAvatar(),p.getUserAvatarPreset());
            String musicLabel=p.getMusicLabel();String captionText=p.getContent();if(!musicLabel.isEmpty())captionText=captionText.isEmpty()?musicLabel:captionText+"\n"+musicLabel;
            com.example.meetomxh.ui.MentionText.bindPost(h.caption,captionText,p.getMentions(),p.getTags());h.caption.setVisibility(android.text.TextUtils.isEmpty(h.caption.getText())?View.GONE:View.VISIBLE);
            h.caption.setOnClickListener(v->{if(p.getMusicMetadata()!=null)com.example.meetomxh.music.MusicAttribution.showLinks(v.getContext(),p.getMusicMetadata());});
            bindCaption(h,p);
            h.captionExpand.setOnClickListener(v->showCaption(p));
            bindEngagement(h,p);
            h.progress.setProgress(active&&player.getDuration()>0?(int)(player.getCurrentPosition()*1000/player.getDuration()):0);
            h.progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar s) { if (player != null && position == activePosition) player.pause(); }
                public void onStopTrackingTouch(SeekBar s) { if (player != null && position == activePosition) { endedReelId=null;userPaused=false;long d = player.getDuration(); if (d > 0) player.seekTo(d * s.getProgress() / 1000); player.play(); } }
                public void onProgressChanged(SeekBar s, int value, boolean user) {}
            });
            h.gestureDetector = new android.view.GestureDetector(requireContext(), new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(android.view.MotionEvent e) {
                    return true;
                }
                @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                    if (position == activePosition && player != null) {
                        userPaused=player.getPlayWhenReady()&&player.getPlaybackState()!=Player.STATE_ENDED;
                        if (userPaused) player.pause(); else {if(player.getPlaybackState()==Player.STATE_ENDED){endedReelId=null;player.seekTo(0);}player.play();}
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
            View.OnTouchListener mediaTouch = (v, event) -> {
                if (h.gestureDetector != null) {
                    return h.gestureDetector.onTouchEvent(event);
                }
                return false;
            };
            h.playerView.setOnTouchListener(mediaTouch);
            h.cover.setOnTouchListener(mediaTouch);
            h.retry.setOnClickListener(v -> { if (position == activePosition) prepare(p, position); });
            h.like.setOnClickListener(v -> {
                h.like.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction(() -> h.like.animate().scaleX(1f).scaleY(1f).setDuration(100).start()).start();
                toggleLike(p, h);
            });
            h.comment.setOnClickListener(v -> CommentsBottomSheetFragment.newInstance(p.getPostId()).show(getParentFragmentManager(), "ReelComments"));
            h.save.setOnClickListener(v -> {
                if(!mutationPending.add(p.getPostId()))return;
                boolean desired = !p.isSaved();
                h.save.animate().scaleX(1.25f).scaleY(1.25f).setDuration(100).withEndAction(() -> h.save.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
                p.setSaved(desired);
                h.save.setImageResource(desired ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
                SavedPostClient.set(p, desired, new Api.Callback<Boolean>() {
                    public void success(Boolean saved) { mutationPending.remove(p.getPostId());p.setSaved(saved); com.example.meetomxh.supabase.PostViewerUpdates.publishEngagement(p); }
                    public void error(String e) { mutationPending.remove(p.getPostId());p.setSaved(!desired); notifyPost(p); if(isAdded())Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show(); }
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
        @Override public void onBindViewHolder(@NonNull Holder h,int position,@NonNull List<Object> payloads){
            if(!payloads.isEmpty()&&h.post==rows.get(position)){
                boolean engagementOnly=true;
                for(Object payload:payloads)if(payload!=ENGAGEMENT_PAYLOAD){engagementOnly=false;break;}
                if(engagementOnly){bindEngagement(h,h.post);return;}
            }
            onBindViewHolder(h,position);
        }
        private void bindEngagement(Holder h,Post p){
            h.like.setImageResource(p.isLiked() ? R.drawable.ic_like_filled : R.drawable.ic_like_outline);
            h.like.setColorFilter(p.isLiked() ? 0xFFED4956 : 0xFFFFFFFF);
            h.likes.setText(count(p.getLikesCount()));
            h.comments.setText(count(p.getCommentsCount()));
            h.shares.setText(count(p.getSharesCount()));
            h.save.setImageResource(p.isSaved() ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_outline);
            boolean own = SessionManager.getInstance().uid().equals(p.getAuthorUid());
            h.follow.setEnabled(true);
            h.follow.setVisibility(own ? View.GONE : View.VISIBLE);
            h.follow.setText(p.isReelFollowing() ? "Đang theo dõi" : "Theo dõi");
        }
        @Override public int getItemCount(){return rows.size();}
        private void notifyPost(Post post){int index=rows.indexOf(post);if(adapter==this&&index>=0)notifyItemChanged(index,ENGAGEMENT_PAYLOAD);}
        @Override public void onViewAttachedToWindow(@NonNull Holder h){
            super.onViewAttachedToWindow(h);
            if(adapter==this&&list!=null&&playbackMonitor!=null&&h.getBindingAdapterPosition()==activePosition&&pageVisible&&player!=null){attachPlayer(activePosition,player);if(reelSheet==null&&!managingReel&&!userPaused&&!playbackMonitor.hasFailed()&&list.getScrollState()==RecyclerView.SCROLL_STATE_IDLE)player.play();}
        }
        @Override public void onViewDetachedFromWindow(@NonNull Holder h){
            if(playbackMonitor!=null&&playbackMonitor.owns(h.playerView))playbackMonitor.attach(null);
            super.onViewDetachedFromWindow(h);
        }
        @Override public void onViewRecycled(@NonNull Holder h){if(playbackMonitor!=null&&playbackMonitor.owns(h.playerView))playbackMonitor.attach(null);h.playerView.setPlayer(null);imageRequests.clear(h.cover);h.post=null;super.onViewRecycled(h);}
        private void bindCaption(Holder h,Post p){
            h.caption.setMaxLines(2);
            h.captionExpand.setVisibility(View.GONE);
            h.caption.post(()->{
                if(root==null||adapter!=this||h.post!=p||!h.itemView.isAttachedToWindow())return;
                android.text.Layout text=h.caption.getLayout();
                boolean truncated=text!=null&&text.getLineCount()>0&&(text.getLineCount()>2||text.getEllipsisCount(text.getLineCount()-1)>0);
                h.captionExpand.setVisibility(truncated?View.VISIBLE:View.GONE);
            });
        }
        void attachPlayer(int position,ExoPlayer value){if(position<0||position>=rows.size()||playbackMonitor==null)return;RecyclerView.ViewHolder vh=list.findViewHolderForAdapterPosition(position);if(vh instanceof Holder&&vh.itemView.isAttachedToWindow())playbackMonitor.attach(((Holder)vh).playerView);}
        boolean hasAttachedPlayer(int position){RecyclerView.ViewHolder vh=list.findViewHolderForAdapterPosition(position);return vh instanceof Holder&&vh.itemView.isAttachedToWindow()&&playbackMonitor!=null&&playbackMonitor.owns(((Holder)vh).playerView);}
        void showCover(int pos){RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder)((Holder)h).cover.setVisibility(View.VISIBLE);}
        void loading(int pos,boolean value){RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder)((Holder)h).buffering.setVisibility(value?View.VISIBLE:View.GONE);}
        void ready(int pos){RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder){((Holder)h).buffering.setVisibility(View.GONE);((Holder)h).retry.setVisibility(View.GONE);}}
        void failed(int pos){failed(pos,"Không thể phát video này. Thử lại");}
        void failed(int pos,String message){if(player!=null)player.pause();RecyclerView.ViewHolder h=list==null?null:list.findViewHolderForAdapterPosition(pos);if(h instanceof Holder){((Holder)h).buffering.setVisibility(View.GONE);((Holder)h).retry.setText(message);((Holder)h).retry.setVisibility(View.VISIBLE);}}
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
            if(!mutationPending.add(p.getPostId()))return;
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
                    mutationPending.remove(p.getPostId());
                    p.setReaction(Dto.nullable(r,"reaction"));
                    p.setLikesCount(r.optInt("reactions_count", p.getLikesCount()));
                    com.example.meetomxh.supabase.PostViewerUpdates.publishEngagement(p);
                }
                public void error(String e) {
                    mutationPending.remove(p.getPostId());
                    p.setReaction(old);
                    p.setLikesCount(oldCount);
                    notifyPost(p);
                    if(isAdded())Toast.makeText(requireContext(), e, Toast.LENGTH_SHORT).show();
                }
            });
        }
        private void follow(Post p,Holder h){boolean requested=!p.isReelFollowing();h.follow.setEnabled(false);Api.run(()->RpcClient.object("set_follow",Api.obj("p_target",p.getAuthorUid(),"p_follow",requested)),new Api.Callback<JSONObject>(){public void success(JSONObject r){p.setReelFollowing(r.optBoolean("following",requested));notifyPost(p);}public void error(String e){notifyPost(p);if(isAdded())Toast.makeText(requireContext(),e,Toast.LENGTH_SHORT).show();}});}
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final androidx.media3.ui.PlayerView playerView;
        final ProgressBar buffering;
        final Button retry, follow;
        final ImageView avatar, cover, like, comment, save, share, heartOverlay;
        final TextView name, caption, captionExpand, likes, comments, shares;
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
            captionExpand = v.findViewById(R.id.reel_caption_expand);
            shares = v.findViewById(R.id.reel_share_count);
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
