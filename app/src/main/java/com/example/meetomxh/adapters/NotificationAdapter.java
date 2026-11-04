package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.meetomxh.R;
import com.example.meetomxh.models.NotificationItem;
import com.example.meetomxh.supabase.Dto;
import com.example.meetomxh.ui.AvatarViews;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;
import java.util.List;

public final class NotificationAdapter extends RecyclerView.Adapter<NotificationAdapter.Holder> {
    public interface Listener { void click(NotificationItem item); }
    private final List<NotificationItem> rows=new ArrayList<>(); private final Listener listener;
    public NotificationAdapter(Listener listener){this.listener=listener;}
    public void replace(List<NotificationItem> values){rows.clear();rows.addAll(values);notifyDataSetChanged();}
    public void append(List<NotificationItem> values){int start=rows.size();rows.addAll(values);notifyItemRangeInserted(start,values.size());}
    public NotificationItem get(int position){return rows.get(position);}
    @Override public int getItemCount(){return rows.size();}
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_notification,parent,false));
    }
    @Override public void onBindViewHolder(@NonNull Holder h,int position){
        NotificationItem item=rows.get(position);
        h.text.setText(item.message()); h.time.setText(com.example.meetomxh.ui.PostTimestampFormatter.format(item.createdAt));
        h.dot.setVisibility(item.unread?View.VISIBLE:View.INVISIBLE);
        h.itemView.setBackgroundResource(item.unread?R.color.surface_variant:R.color.surface_bg);
        AvatarViews.load(h.avatar,Dto.asset(item.actorAvatarId),item.actorAvatarPreset);
        h.itemView.setOnClickListener(v->listener.click(item));
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView avatar;final TextView text,time;final View dot;
        Holder(View v){super(v);avatar=v.findViewById(R.id.ivNotificationAvatar);text=v.findViewById(R.id.tvNotificationText);
            time=v.findViewById(R.id.tvNotificationTime);dot=v.findViewById(R.id.ivNotificationBadge);}
    }
}
