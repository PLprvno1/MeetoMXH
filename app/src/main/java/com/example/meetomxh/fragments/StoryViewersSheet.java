package com.example.meetomxh.fragments;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.recyclerview.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.example.meetomxh.ProfileActivity;
import com.example.meetomxh.supabase.*;
import com.example.meetomxh.ui.*;
import org.json.*;
import java.util.*;

public final class StoryViewersSheet extends BottomSheetDialogFragment{
 private final List<JSONObject> rows=new ArrayList<>();private TextView title;private Button more;private People adapter;private boolean loading,hasMore=true;private String time,cursor;private Subscription request;
 public static StoryViewersSheet create(String id){StoryViewersSheet f=new StoryViewersSheet();Bundle b=new Bundle();b.putString("id",id);f.setArguments(b);return f;}
 @Override public View onCreateView(@NonNull LayoutInflater i,ViewGroup container,Bundle state){
  rows.clear();loading=false;hasMore=true;time=null;cursor=null;
  LinearLayout root=new LinearLayout(requireContext());root.setOrientation(1);root.setPadding(dp(20),dp(12),dp(20),dp(24));root.setBackgroundColor(0xFF17171A);
  View handle=new View(requireContext());android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(0xFF77777F);shape.setCornerRadius(dp(3));handle.setBackground(shape);LinearLayout.LayoutParams handleSize=new LinearLayout.LayoutParams(dp(40),dp(4));handleSize.gravity=Gravity.CENTER_HORIZONTAL;root.addView(handle,handleSize);
  LinearLayout heading=new LinearLayout(requireContext());heading.setGravity(Gravity.CENTER_VERTICAL);title=new TextView(requireContext());title.setTextColor(-1);title.setTextSize(20);title.setGravity(Gravity.CENTER_VERTICAL);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setText("Người xem");heading.addView(title,new LinearLayout.LayoutParams(0,dp(60),1));
  ImageButton close=new ImageButton(requireContext());close.setImageResource(com.example.meetomxh.R.drawable.ic_clear);close.setColorFilter(-1);close.setBackgroundColor(android.graphics.Color.TRANSPARENT);close.setContentDescription("Đóng danh sách người xem");close.setOnClickListener(v->dismiss());heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(heading);
  RecyclerView list=new RecyclerView(requireContext());list.setLayoutManager(new LinearLayoutManager(requireContext()));adapter=new People();list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,(int)(380*getResources().getDisplayMetrics().density)));
  more=new Button(requireContext());more.setText("Đang tải…");more.setOnClickListener(v->load());root.addView(more);load();return root;
 }
 private void load(){if(loading||!hasMore)return;loading=true;more.setEnabled(false);more.setText("Đang tải…");request=Api.run(()->StoryClient.viewersPage(requireArguments().getString("id"),time,cursor),new Api.Callback<JSONObject>(){
  public void success(JSONObject result){if(getView()==null)return;loading=false;JSONArray page=result.optJSONArray("items");int first=rows.size();if(page!=null)for(int n=0;n<page.length();n++){JSONObject row=page.optJSONObject(n);if(row!=null)rows.add(row);}
   adapter.notifyItemRangeInserted(first,rows.size()-first);hasMore=result.optBoolean("has_more");if(!rows.isEmpty()){JSONObject last=rows.get(rows.size()-1);time=last.optString("viewed_at");cursor=last.optString("cursor_id");}
   title.setText("Người xem · "+result.optInt("total"));more.setEnabled(hasMore);more.setText(hasMore?"Tải thêm":rows.isEmpty()?"Chưa có người xem":"Đã tải hết");}
  public void error(String error){if(getView()==null)return;loading=false;more.setEnabled(true);more.setText("Thử lại");title.setText("Người xem · "+error);}
 });}
 private final class People extends RecyclerView.Adapter<Row>{
  @NonNull public Row onCreateViewHolder(@NonNull ViewGroup parent,int type){LinearLayout l=new LinearLayout(parent.getContext());l.setGravity(Gravity.CENTER_VERTICAL);l.setPadding(0,dp(10),0,dp(10));com.google.android.material.imageview.ShapeableImageView a=new com.google.android.material.imageview.ShapeableImageView(parent.getContext());a.setShapeAppearanceModel(a.getShapeAppearanceModel().toBuilder().setAllCornerSizes(new com.google.android.material.shape.RelativeCornerSize(.5f)).build());l.addView(a,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView t=new TextView(parent.getContext());t.setPadding(dp(12),0,dp(8),0);t.setTextColor(-1);t.setTextSize(15);l.addView(t,new LinearLayout.LayoutParams(0,-2,1));return new Row(l,a,t);}
  public void onBindViewHolder(@NonNull Row h,int position){JSONObject person=rows.get(position);AvatarViews.load(h.avatar,Dto.asset(Dto.nullable(person,"avatar_asset_id")),person.optString("avatar_preset","neutral"));h.text.setText(person.optString("display_name","Meeto")+(person.optBoolean("liked")?"  ♥":"")+"\n"+StoryTime.relative(person.optString("viewed_at")));String uid=Dto.nullable(person,"viewer_id");h.itemView.setOnClickListener(v->{if(uid!=null)startActivity(new Intent(requireContext(),ProfileActivity.class).putExtra("target_uid",uid));});}
  public int getItemCount(){return rows.size();}
 }
 private static final class Row extends RecyclerView.ViewHolder{final ImageView avatar;final TextView text;Row(View v,ImageView a,TextView t){super(v);avatar=a;text=t;}}
 private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 @Override public void onStart(){super.onStart();if(getDialog() instanceof com.google.android.material.bottomsheet.BottomSheetDialog)((com.google.android.material.bottomsheet.BottomSheetDialog)getDialog()).getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);}
 @Override public void onDestroyView(){if(request!=null)request.remove();super.onDestroyView();}
 @Override public void onDismiss(@NonNull android.content.DialogInterface dialog){getParentFragmentManager().setFragmentResult("story_viewers_closed",new Bundle());super.onDismiss(dialog);}
}
