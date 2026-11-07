package com.example.meetomxh.supabase;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.example.meetomxh.R;
import com.example.meetomxh.models.Chat;
import com.example.meetomxh.models.Inbox;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Inbox shortcuts reuse the same per-member controls as Conversation Details. */
public final class ConversationListActions {
    public enum Change { MUTED, ARCHIVED, BLOCKED, HIDDEN }
    private ConversationListActions() {}
    public static void show(Activity activity, Chat chat, Inbox row, BiConsumer<Chat, Change> changed) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String uid = SessionManager.getInstance().uid();
        long epoch = SessionManager.getInstance().generation();
        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(activity); title.setText(row.getChatName()); title.setTextSize(20);
        title.setTextColor(activity.getColor(R.color.text_primary)); title.setTypeface(null, 1);
        title.setPadding(pad, 0, pad, pad); body.addView(title);
        TextView status = new TextView(activity); status.setTextColor(activity.getColor(R.color.text_secondary));
        status.setPadding(pad, 0, pad, pad); status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        List<View> actions = new ArrayList<>(); Subscription[] request = {null};
        java.util.function.BooleanSupplier alive = () -> sheet.isShowing() && !activity.isFinishing()
                && !activity.isDestroyed() && SessionManager.getInstance().generation() == epoch;
        java.util.function.Consumer<Boolean> busy = value -> {
            for (View action : actions) action.setEnabled(!value);
            status.setVisibility(value ? View.VISIBLE : View.GONE); status.setText("Đang cập nhật…");
            sheet.setCancelable(!value);
        };
        java.util.function.BiConsumer<String, org.json.JSONObject> run = (rpc, input) -> {
            busy.accept(true);
            request[0] = Api.run(() -> RpcClient.call(rpc, input), new Api.Callback<Object>() {
                public void success(Object result) {
                    if (!alive.getAsBoolean()) return;
                    Change change;
                    if ("set_conversation_muted".equals(rpc)) { chat.muted = input.optBoolean("p_muted"); change = Change.MUTED; }
                    else if ("set_conversation_archived".equals(rpc)) { chat.archived = input.optBoolean("p_archived"); change = Change.ARCHIVED; }
                    else change = Change.BLOCKED;
                    changed.accept(chat, change); sheet.dismiss();
                    Toast.makeText(activity, change == Change.MUTED ? (chat.muted ? "Đã tắt thông báo" : "Đã bật thông báo")
                            : change == Change.ARCHIVED ? (chat.archived ? "Đã lưu trữ hội thoại" : "Đã bỏ lưu trữ") : "Đã chặn người dùng", Toast.LENGTH_SHORT).show();
                }
                public void error(String error) { if (alive.getAsBoolean()) { busy.accept(false); status.setVisibility(View.VISIBLE); status.setText(error); } }
            });
        };
        actions.add(action(activity, body, chat.muted ? R.drawable.ic_notifications : R.drawable.ic_volume_off,
                chat.muted ? "Bật thông báo" : "Tắt thông báo", false,
                () -> run.accept("set_conversation_muted", Api.obj("p_conversation_id", chat.getChatId(), "p_muted", !chat.muted))));
        actions.add(action(activity, body, R.drawable.ic_archive, chat.archived ? "Bỏ lưu trữ" : "Lưu trữ", false,
                () -> run.accept("set_conversation_archived", Api.obj("p_conversation_id", chat.getChatId(), "p_archived", !chat.archived))));
        String peer = row.getOtherUid();
        if ("private".equals(chat.getType()) && peer != null && !peer.equals(chat.getChatId()) && !peer.equals(uid)) {
            actions.add(action(activity, body, R.drawable.ic_messenger_block, "Chặn", true, () -> {
                androidx.appcompat.app.AlertDialog confirm = new MaterialAlertDialogBuilder(activity)
                        .setTitle("Chặn " + row.getChatName() + "?")
                        .setMessage("Người này sẽ không thể liên hệ với bạn theo cài đặt chặn hiện tại.")
                        .setNegativeButton("Hủy", null).setPositiveButton("Chặn", (d, which) -> {
                            if (alive.getAsBoolean()) run.accept("set_block", Api.obj("p_target", peer, "p_blocked", true));
                        }).create();
                ChatCustomizationUi.own(activity, confirm, () -> {}); confirm.show();
                confirm.getButton(-1).setTextColor(activity.getColor(R.color.status_error));
            }));
        }
        actions.add(action(activity, body, R.drawable.ic_reel_delete, "Xóa đoạn chat", true, () -> {
            androidx.appcompat.app.AlertDialog confirm = new MaterialAlertDialogBuilder(activity)
                    .setTitle("Xóa đoạn chat khỏi danh sách?")
                    .setMessage("Chỉ ẩn trên thiết bị này cho tài khoản của bạn, không xóa tin nhắn của người khác. Cuộc trò chuyện sẽ hiện lại khi có tin nhắn mới.")
                    .setNegativeButton("Hủy", null).setPositiveButton("Xóa đoạn chat", (d, which) -> {
                        if (!alive.getAsBoolean()) return;
                        ConversationVisibility.hide(uid, chat); changed.accept(chat, Change.HIDDEN); sheet.dismiss();
                    }).create();
            ChatCustomizationUi.own(activity, confirm, () -> {}); confirm.show();
            confirm.getButton(-1).setTextColor(activity.getColor(R.color.status_error));
        }));
        body.addView(status);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity); scroll.addView(body); sheet.setContentView(scroll);
        Runnable identityChanged = () -> { if (SessionManager.getInstance().generation() != epoch) sheet.dismiss(); };
        SessionManager.getInstance().identityListeners.add(identityChanged);
        ChatCustomizationUi.own(activity, sheet, () -> { if (request[0] != null) request[0].remove(); SessionManager.getInstance().identityListeners.remove(identityChanged); });
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(body, (v, insets) -> {
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(pad, pad, pad, pad + bottom); return insets;
        });
        sheet.show(); sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        androidx.core.view.ViewCompat.requestApplyInsets(body);
    }
    private static View action(Activity activity, LinearLayout parent, int icon, String label, boolean destructive, Runnable callback) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_messenger_action, parent, false);
        int color = activity.getColor(destructive ? R.color.status_error : R.color.text_primary);
        ImageView image = row.findViewById(R.id.messenger_action_icon); image.setImageResource(icon); image.setColorFilter(color);
        TextView text = row.findViewById(R.id.messenger_action_label); text.setText(label); text.setTextColor(color);
        row.setOnClickListener(v -> callback.run()); parent.addView(row); return row;
    }
}
