package com.example.meetomxh;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.meetomxh.databinding.ActivityPostMediaViewerBinding;
import com.example.meetomxh.fragments.CommentsBottomSheetFragment;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.storage.PostImageDownload;
import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import org.json.JSONObject;
import java.util.*;

/** Post-aware photo viewer. Chat, Stories and Reels keep their own media screens. */
public final class PostMediaViewerActivity extends AppCompatActivity {
    private static final String POST="viewer_post_id",ASSET="viewer_asset",INDEX="viewer_index";
    public static void open(Context context,String id,String reference,int index){
        Context owner=context;while(owner instanceof ContextWrapper&&!(owner instanceof Activity))owner=((ContextWrapper)owner).getBaseContext();
        Intent intent=new Intent(context,PostMediaViewerActivity.class).putExtra(POST,id).putExtra(ASSET,reference).putExtra(INDEX,index);
        if(owner instanceof Activity){context.startActivity(intent);PostViewerUpdates.opening((Activity)owner);}else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private ActivityPostMediaViewerBinding binding;
    private RequestManager images;
    private final List<String> photos=new ArrayList<>();
    private final List<Subscription> mutations=new ArrayList<>();
    private Subscription postRequest,mediaRequest,downloadRequest;
    private Post post;
    private String selectedId,currentReference,downloadReference,downloadPostId;
    private int index,postGeneration,mediaGeneration;
    private long sessionEpoch;
    private boolean reactionBusy,saveBusy,deleteBusy,downloading,permissionPending,expanded,postFailed;
    private BottomSheetDialog menu;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable accessTick=new Runnable(){public void run(){refresh(false);handler.postDelayed(this,30000);}};
    private final ActivityResultLauncher<String> permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{
        if(!alive())return;permissionPending=false;if(granted)download();else{downloading=false;actionStatus("Quyền lưu ảnh bị từ chối. Bạn có thể thử lại trong menu.");}
    });
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);sessionEpoch=SessionManager.getInstance().generation();WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        binding=ActivityPostMediaViewerBinding.inflate(getLayoutInflater());images=Glide.with(this);setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(),(view,insets)->{androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(getWindow(),binding.getRoot());controller.setAppearanceLightStatusBars(false);controller.setAppearanceLightNavigationBars(false);
        selectedId=getIntent().getStringExtra(POST);currentReference=state==null?getIntent().getStringExtra(ASSET):state.getString(ASSET);index=state==null?getIntent().getIntExtra(INDEX,0):state.getInt(INDEX);expanded=state!=null&&state.getBoolean("expanded");
        if(state!=null){downloadReference=state.getString("download_reference");downloadPostId=state.getString("download_post");permissionPending=state.getBoolean("download_permission");downloading=permissionPending;}
        binding.postMediaBack.setOnClickListener(v->finish());binding.postMediaMore.setOnClickListener(v->showMenu());
        binding.postMediaRetry.setOnClickListener(v->{if(postFailed||post==null)refresh(true);else loadImage();});
        binding.postMediaExpand.setOnClickListener(v->{expanded=!expanded;render();});
        binding.postMediaLike.setOnClickListener(v->like());
        binding.postMediaComment.setOnClickListener(v->{if(post!=null&&!getSupportFragmentManager().isStateSaved()&&getSupportFragmentManager().findFragmentByTag("PostViewerComments")==null)CommentsBottomSheetFragment.newInstance(post.getPostId()).show(getSupportFragmentManager(),"PostViewerComments");});
        binding.postMediaShare.setOnClickListener(v->share());
        binding.postMediaImage.setPageListener(new ZoomableImageView.PageListener(){public void onNext(){showIndex(index+1);}public void onPrevious(){showIndex(index-1);}});
        PostUpdates.counts.observe(this,count->{if(post!=null&&count!=null&&count.id.equals(post.getPostId())){post.setCommentsCount(count.comments);render();if(!reactionBusy&&!saveBusy)PostViewerUpdates.publish(post,false);}});
        try{if(!UUID.fromString(selectedId).toString().equalsIgnoreCase(selectedId))throw new IllegalArgumentException();}catch(Exception e){selectedId=null;fail("Không có mã bài viết hợp lệ.",false);return;}
        render();
    }
    @Override protected void onResume(){super.onResume();if(!alive()){finish();return;}refresh(post==null);handler.removeCallbacks(accessTick);handler.postDelayed(accessTick,30000);}
    @Override protected void onPause(){handler.removeCallbacks(accessTick);super.onPause();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&post!=null)refresh(false);}
    private boolean alive(){return binding!=null&&!isFinishing()&&!isDestroyed()&&sessionEpoch==SessionManager.getInstance().generation();}
    private void refresh(boolean initial){
        if(!alive()||selectedId==null||reactionBusy||saveBusy||deleteBusy)return;
        if(postRequest!=null)postRequest.remove();int token=++postGeneration;
        if(initial){binding.postMediaLoading.setVisibility(View.VISIBLE);binding.postMediaError.setVisibility(View.GONE);}
        postRequest=Api.run(()->PostDetails.resolve(selectedId),new Api.Callback<JSONObject>(){
            public void success(JSONObject json){if(!alive()||token!=postGeneration)return;
                Post updated=Dto.post(json);if(updated.isReel()){fail("Nội dung này là Reel. Hãy mở từ mục Reels.",false);return;}
                post=updated;selectedId=post.getPostId();postFailed=false;photos.clear();
                for(int i=0;i<post.getImageUrls().size();i++)if(i<post.getMediaTypes().size()&&post.getMediaTypes().get(i).startsWith("image/"))photos.add(post.getImageUrls().get(i));
                if(photos.isEmpty()){fail("Bài viết không còn ảnh để hiển thị.",true);return;}
                int selected=photos.indexOf(currentReference);index=selected>=0?selected:Math.max(0,Math.min(index,photos.size()-1));String next=photos.get(index);
                boolean reload=initial||!next.equals(currentReference)||binding.postMediaImage.getDrawable()==null;
                currentReference=next;render();PostViewerUpdates.publish(post,false);if(reload)loadImage();
            }
            public void error(String error){if(!alive()||token!=postGeneration)return;fail("Không thể mở bài viết hoặc quyền xem đã thay đổi. "+error,true);}
        });
    }
    private void fail(String text,boolean retry){
        postFailed=true;if(mediaRequest!=null)mediaRequest.remove();mediaGeneration++;
        images.clear(binding.postMediaImage);binding.postMediaImage.setVisibility(View.INVISIBLE);binding.postMediaLoading.setVisibility(View.GONE);
        binding.postMediaMessage.setText(text);binding.postMediaError.setVisibility(View.VISIBLE);binding.postMediaRetry.setVisibility(retry?View.VISIBLE:View.GONE);
        binding.postMediaInfoScroll.setVisibility(View.GONE);binding.postMediaMore.setEnabled(false);binding.postMediaLike.setEnabled(false);binding.postMediaComment.setEnabled(false);binding.postMediaShare.setEnabled(false);
        if(menu!=null)menu.dismiss();
    }
    private void showIndex(int position){if(postFailed||position<0||position>=photos.size())return;index=position;currentReference=photos.get(index);render();loadImage();}
    private void loadImage(){
        if(!alive()||currentReference==null)return;if(mediaRequest!=null)mediaRequest.remove();images.clear(binding.postMediaImage);
        String reference=currentReference;int token=++mediaGeneration;binding.postMediaLoading.setVisibility(View.VISIBLE);binding.postMediaError.setVisibility(View.GONE);binding.postMediaImage.setVisibility(View.INVISIBLE);
        mediaRequest=Api.run(()->SupabaseStorage.info(reference),new Api.Callback<JSONObject>(){
            public void success(JSONObject asset){if(!alive()||token!=mediaGeneration)return;if(!asset.optString("mime_type").startsWith("image/")){error("Tệp không còn là ảnh hợp lệ.");return;}
                images.load(reference).diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                    .override(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels).fitCenter()
                    .listener(new RequestListener<android.graphics.drawable.Drawable>(){
                        public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e,Object model,Target<android.graphics.drawable.Drawable> target,boolean first){if(alive()&&token==mediaGeneration)imageError("Không tải được ảnh. Kiểm tra kết nối hoặc thử lại.");return true;}
                        public boolean onResourceReady(android.graphics.drawable.Drawable resource,Object model,Target<android.graphics.drawable.Drawable> target,com.bumptech.glide.load.DataSource source,boolean first){if(!alive()||token!=mediaGeneration)return true;binding.postMediaLoading.setVisibility(View.GONE);binding.postMediaError.setVisibility(View.GONE);binding.postMediaImage.setVisibility(View.VISIBLE);return false;}
                    }).into(binding.postMediaImage);
            }
            public void error(String error){if(alive()&&token==mediaGeneration)imageError(error);}
        });
    }
    private void imageError(String text){binding.postMediaLoading.setVisibility(View.GONE);binding.postMediaImage.setVisibility(View.INVISIBLE);binding.postMediaMessage.setText(text);binding.postMediaError.setVisibility(View.VISIBLE);binding.postMediaRetry.setVisibility(View.VISIBLE);}
    private void render(){
        if(!alive())return;boolean ready=post!=null&&!postFailed;binding.postMediaMore.setEnabled(ready);binding.postMediaLike.setEnabled(ready&&!reactionBusy&&!saveBusy&&!deleteBusy);binding.postMediaComment.setEnabled(ready);binding.postMediaShare.setEnabled(ready);
        if(!ready)return;binding.postMediaInfoScroll.setVisibility(View.VISIBLE);binding.postMediaPosition.setText((index+1)+" / "+photos.size());
        binding.postMediaAuthor.setText(post.getUserName()==null?"Người dùng":post.getUserName());
        binding.postMediaTime.setText((post.getCreatedAt()>0?android.text.format.DateUtils.getRelativeTimeSpanString(post.getCreatedAt(),System.currentTimeMillis(),60000):"")+" · "+AudienceChoices.label(post.getVisibility()));
        String text=post.getContent()==null?"":post.getContent();binding.postMediaCaption.setText(text);binding.postMediaCaption.setMaxLines(expanded?Integer.MAX_VALUE:2);binding.postMediaExpand.setText(expanded?"Thu gọn":"Xem thêm");
        binding.postMediaCaption.post(()->{if(!alive())return;android.text.Layout layout=binding.postMediaCaption.getLayout();boolean truncated=layout!=null&&layout.getLineCount()>0&&layout.getEllipsisCount(layout.getLineCount()-1)>0;binding.postMediaExpand.setVisibility(expanded||truncated?View.VISIBLE:View.GONE);limitInfo();});
        icon(binding.postMediaLike,post.isLiked()?R.drawable.ic_like_filled:R.drawable.ic_like_outline,post.getLikesCount(),post.isLiked()?0xFFFF4F91:0xFFFFFFFF,"Thích");
        icon(binding.postMediaComment,R.drawable.ic_comment,post.getCommentsCount(),0xFFFFFFFF,"Bình luận");icon(binding.postMediaShare,R.drawable.ic_share,post.getSharesCount(),0xFFFFFFFF,"Chia sẻ");
    }
    private void limitInfo(){View child=binding.postMediaInfoScroll.getChildAt(0);int width=binding.getRoot().getWidth();if(width<=0)return;child.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));android.view.ViewGroup.LayoutParams p=binding.postMediaInfoScroll.getLayoutParams();int height=Math.min(getResources().getDisplayMetrics().heightPixels/4,child.getMeasuredHeight());if(p.height!=height){p.height=height;binding.postMediaInfoScroll.setLayoutParams(p);}}
    private void icon(TextView view,int drawable,int count,int color,String description){android.graphics.drawable.Drawable icon=androidx.appcompat.content.res.AppCompatResources.getDrawable(this,drawable);if(icon!=null){icon=icon.mutate();androidx.core.graphics.drawable.DrawableCompat.setTint(icon,color);icon.setBounds(0,0,dp(23),dp(23));}view.setCompoundDrawables(icon,null,null,null);view.setText(String.valueOf(Math.max(0,count)));view.setContentDescription(description+", "+count);view.setTextColor(color);}
    private void like(){
        if(post==null||postFailed||reactionBusy||saveBusy||deleteBusy)return;Post target=post;String previous=post.getReaction(),desired=post.isLiked()?null:"like";int count=post.getLikesCount();
        invalidateRead();
        reactionBusy=true;post.setReaction(desired);post.setLikesCount(Math.max(0,count+(previous==null&&desired!=null?1:previous!=null&&desired==null?-1:0)));render();binding.postMediaLike.animate().scaleX(1.12f).scaleY(1.12f).setDuration(100).withEndAction(()->{if(alive())binding.postMediaLike.animate().scaleX(1).scaleY(1).setDuration(100).start();}).start();
        mutations.add(Api.run(()->{JSONObject result=RpcClient.object("set_post_reaction",Api.obj("p_post_id",target.getPostId(),"p_reaction",desired));result.getInt("reactions_count");if(!result.has("reaction"))throw new IllegalStateException("Máy chủ chưa xác nhận cảm xúc.");return result;},new Api.Callback<JSONObject>(){
            public void success(JSONObject result){if(!alive())return;reactionBusy=false;target.setReaction(Dto.nullable(result,"reaction"));target.setLikesCount(result.optInt("reactions_count",count));post=target;render();PostViewerUpdates.publish(post,false);refresh(false);}
            public void error(String error){if(!alive())return;reactionBusy=false;target.setReaction(previous);target.setLikesCount(count);post=target;render();actionStatus(error);refresh(false);}
        }));
    }
    private void share(){if(post!=null&&!postFailed)ShareActions.show(this,post,post.getGroupId()!=null);}
    private void invalidateRead(){postGeneration++;if(postRequest!=null){postRequest.remove();postRequest=null;}}
    private void save(){if(post==null||saveBusy||reactionBusy||deleteBusy||postFailed)return;invalidateRead();saveBusy=true;Post target=post;boolean desired=!target.isSaved();actionStatus("Đang cập nhật bài viết đã lưu…");render();
        mutations.add(Api.run(()->{JSONObject result=RpcClient.object("set_saved_post",Api.obj("p_post_id",target.getPostId(),"p_saved",desired));if(!target.getPostId().equals(result.optString("post_id"))||result.getBoolean("saved")!=desired)throw new IllegalStateException("Máy chủ chưa xác nhận trạng thái lưu bài viết.");return desired;},new Api.Callback<Boolean>(){
            public void success(Boolean saved){if(!alive())return;saveBusy=false;target.setSaved(saved);post=target;render();PostViewerUpdates.publish(post,false);actionStatus(saved?"Đã lưu bài viết":"Đã bỏ lưu bài viết");refresh(false);}
            public void error(String error){if(alive()){saveBusy=false;render();actionStatus(error);refresh(false);}}
        }));
    }
    private void showMenu(){
        if(post==null||postFailed||deleteBusy||menu!=null)return;BottomSheetDialog sheet=new BottomSheetDialog(this);menu=sheet;LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(16),dp(12),dp(16),dp(20));body.setBackgroundColor(0xFF171717);
        menuRow(body,downloading?"Đang tải ảnh…":"Tải ảnh xuống",!downloading,()->{sheet.dismiss();downloadReference=currentReference;downloadPostId=post.getPostId();downloading=true;
            if(Build.VERSION.SDK_INT<29&&ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){permissionPending=true;permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);}else download();});
        menuRow(body,"Chia sẻ bài viết",true,()->{sheet.dismiss();share();});menuRow(body,post.isSaved()?"Bỏ lưu bài viết":"Lưu bài viết",!saveBusy&&!reactionBusy,()->{sheet.dismiss();save();});
        if(SessionManager.getInstance().uid().equals(post.getAuthorUid())){
            menuRow(body,"Chỉnh sửa bài viết",true,()->{sheet.dismiss();startActivity(new Intent(this,CreatePostActivity.class).putExtra("post_id",post.getPostId()));});
            menuRow(body,"Xóa bài viết",true,()->{sheet.dismiss();confirmDelete();});
        }
        menuRow(body,"Đóng",true,sheet::dismiss);sheet.setContentView(body);sheet.setOnDismissListener(d->{if(menu==sheet)menu=null;});sheet.show();
    }
    private void menuRow(LinearLayout body,String title,boolean enabled,Runnable action){TextView row=new TextView(this);row.setText(title);row.setTextColor(enabled?0xFFFFFFFF:0xFFBDBDBD);row.setTextSize(16);row.setMinHeight(dp(52));row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setEnabled(enabled);row.setOnClickListener(v->action.run());body.addView(row,new LinearLayout.LayoutParams(-1,-2));}
    private void confirmDelete(){new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Xóa bài viết?").setMessage("Ảnh và các tương tác của bài viết sẽ không còn hiển thị.").setNegativeButton("Hủy",null).setPositiveButton("Xóa",(d,w)->{
        if(deleteBusy||post==null||postFailed)return;deleteBusy=true;invalidateRead();render();actionStatus("Đang xóa bài viết…");Post target=post;mutations.add(Api.run(()->RpcClient.object("delete_post",Api.obj("p_post_id",target.getPostId(),"p_expected_version",target.version)),new Api.Callback<JSONObject>(){
            public void success(JSONObject result){if(!alive())return;if(!target.getPostId().equals(result.optString("id"))||!"deleted".equals(result.optString("state"))){error("Máy chủ chưa xác nhận xóa bài viết.");return;}PostViewerUpdates.publish(target,true);finish();}
            public void error(String error){if(alive()){deleteBusy=false;render();actionStatus(error);}}
        }));}).show();}
    private void download(){if(!alive())return;if(downloadReference==null||downloadPostId==null){downloading=false;actionStatus("Hãy chọn lại Tải ảnh xuống trong menu.");return;}downloading=true;String reference=downloadReference,id=downloadPostId;actionStatus("Đang tải ảnh đã chọn về thư viện…");
        android.content.ContentResolver resolver=getApplicationContext().getContentResolver();
        downloadRequest=Api.run(()->PostImageDownload.save(resolver,id,reference),new Api.Callback<String>(){
            public void success(String file){if(alive()){downloading=false;actionStatus("Đã lưu ảnh vào Pictures/Meeto");}}
            public void error(String error){if(alive()){downloading=false;actionStatus("Chưa lưu được ảnh: "+error+" Mở menu để thử lại.");}}
        });
    }
    private void actionStatus(String text){binding.postMediaActionStatus.setText(text);binding.postMediaActionStatus.setVisibility(View.VISIBLE);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onSaveInstanceState(Bundle out){out.putString(ASSET,currentReference);out.putInt(INDEX,index);out.putBoolean("expanded",expanded);out.putString("download_reference",downloadReference);out.putString("download_post",downloadPostId);out.putBoolean("download_permission",permissionPending);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(postRequest!=null)postRequest.remove();if(mediaRequest!=null)mediaRequest.remove();if(downloadRequest!=null)downloadRequest.remove();for(Subscription job:mutations)job.remove();if(menu!=null)menu.dismiss();if(binding!=null)images.clear(binding.postMediaImage);if(post!=null&&!deleteBusy&&!isChangingConfigurations()&&sessionEpoch==SessionManager.getInstance().generation())PostViewerUpdates.reconcile(post.getPostId());binding=null;super.onDestroy();}
}
