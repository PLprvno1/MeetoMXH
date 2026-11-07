package com.example.meetomxh.adapters;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.meetomxh.R;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.ui.MediaThumbnailLoader;
import com.example.meetomxh.ui.PostTimestampFormatter;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.*;

public final class SearchPostAdapter extends RecyclerView.Adapter<SearchPostAdapter.Row>{
    public interface Click{void open(Post post);}
    private final List<Post> rows=new ArrayList<>();private final Click click;private final boolean dark;
    public SearchPostAdapter(Click click,boolean dark){this.click=click;this.dark=dark;}
    public void setRows(List<Post> posts){rows.clear();rows.addAll(posts);notifyDataSetChanged();}
    public static String clean(String text,String fallback){return text==null||text.trim().isEmpty()||"null".equalsIgnoreCase(text)||text.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")?fallback:text;}
    @NonNull public Row onCreateViewHolder(@NonNull ViewGroup parent,int type){
        android.content.Context c=parent.getContext();LinearLayout root=new LinearLayout(c);root.setGravity(Gravity.CENTER_VERTICAL);root.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));root.setBackgroundResource(android.R.drawable.list_selector_background);
        root.setLayoutParams(new RecyclerView.LayoutParams(-1,-2));FrameLayout preview=new FrameLayout(c);root.addView(preview,new LinearLayout.LayoutParams(dp(c,54),dp(c,96)));
        ShapeableImageView image=new ShapeableImageView(c);image.setShapeAppearanceModel(image.getShapeAppearanceModel().toBuilder().setAllCornerSizes(dp(c,10)).build());image.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.addView(image,new FrameLayout.LayoutParams(-1,-1));
        ImageView play=new ImageView(c);play.setImageResource(R.drawable.ic_reel_play);play.setColorFilter(0xFFFFFFFF);FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(dp(c,24),dp(c,24),Gravity.CENTER);preview.addView(play,pp);
        LinearLayout labels=new LinearLayout(c);labels.setOrientation(1);labels.setPadding(dp(c,12),0,0,0);root.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView name=new TextView(c),caption=new TextView(c),time=new TextView(c);name.setTypeface(null,Typeface.BOLD);name.setTextSize(15);name.setMaxLines(1);caption.setMaxLines(2);caption.setTextSize(14);name.setEllipsize(TextUtils.TruncateAt.END);caption.setEllipsize(TextUtils.TruncateAt.END);time.setTextSize(12);
        name.setTextColor(dark?-1:c.getColor(R.color.text_primary));caption.setTextColor(dark?0xFFDDDDDD:c.getColor(R.color.text_secondary));time.setTextColor(dark?0xFFBBBBBB:c.getColor(R.color.text_secondary));labels.addView(name);labels.addView(caption);labels.addView(time);return new Row(root,image,play,name,caption,time);
    }
    public void onBindViewHolder(@NonNull Row row,int position){Post post=rows.get(position);row.name.setText(clean(post.getUserName(),"Người dùng"));row.caption.setText(clean(post.getContent(),MediaThumbnailLoader.video(post)?"Video":"Bài viết"));row.time.setText(post.serverCreatedAt==null?"":PostTimestampFormatter.format(post.serverCreatedAt));row.play.setVisibility(MediaThumbnailLoader.video(post)?View.VISIBLE:View.GONE);MediaThumbnailLoader.load(row.image,post);row.itemView.setOnClickListener(v->click.open(post));}
    @Override public void onViewRecycled(@NonNull Row row){MediaThumbnailLoader.clear(row.image);super.onViewRecycled(row);}
    public int getItemCount(){return rows.size();}
    private static int dp(android.content.Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static final class Row extends RecyclerView.ViewHolder{final ImageView image,play;final TextView name,caption,time;Row(View root,ImageView image,ImageView play,TextView name,TextView caption,TextView time){super(root);this.image=image;this.play=play;this.name=name;this.caption=caption;this.time=time;}}
}
