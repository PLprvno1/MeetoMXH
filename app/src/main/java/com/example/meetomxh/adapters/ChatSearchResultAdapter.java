package com.example.meetomxh.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.meetomxh.databinding.ItemChatSearchResultBinding;
import com.example.meetomxh.models.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatSearchResultAdapter extends RecyclerView.Adapter<ChatSearchResultAdapter.Holder> {
    public interface OnResultClickListener { void onClick(ChatMessage message); }

    private final List<ChatMessage> results = new ArrayList<>();
    private final OnResultClickListener listener;

    public ChatSearchResultAdapter(OnResultClickListener listener) { this.listener = listener; }

    public void submit(List<ChatMessage> items) {
        results.clear();
        if (items != null) results.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemChatSearchResultBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        ChatMessage message = results.get(position);
        holder.binding.tvSearchResultText.setText(message.getText());
        holder.binding.tvSearchResultTime.setText(message.getCreatedAt() == null ? ""
                : new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(new Date(message.getCreatedAt().toDate().getTime())));
        holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(message); });
    }

    @Override public int getItemCount() { return results.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemChatSearchResultBinding binding;
        Holder(ItemChatSearchResultBinding binding) { super(binding.getRoot()); this.binding = binding; }
    }
}
