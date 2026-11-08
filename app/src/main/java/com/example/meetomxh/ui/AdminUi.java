package com.example.meetomxh.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.R;
import com.example.meetomxh.supabase.ServerTime;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Display-only labels. Never translates action keys, payloads, filters or server permissions. */
public final class AdminUi {
    private AdminUi(){}
    public static String clean(String value){return value==null||"null".equals(value.trim())?"":value.trim();}
    public static String text(JSONObject row,String key){return row.isNull(key)?"":clean(row.optString(key,""));}
    public static String kind(String value){
        switch(clean(value)){
            case "post":case "posts":return "Bài viết";
            case "reel":case "reels":return "Video ngắn";
            case "comment":case "comments":return "Bình luận";
            case "story":return "Tin";
            case "message":return "Tin nhắn";
            case "account":return "Tài khoản";
            case "profile":return "Trang cá nhân";
            case "report":return "Báo cáo";
            case "group":return "Nhóm";
            default:return "Đối tượng khác";
        }
    }
    public static String state(String value){
        switch(clean(value)){
            case "active":return "Đang hoạt động";case "suspended":return "Đã khóa";
            case "deleting":return "Đang xóa";case "deleted":return "Đã gỡ";
            case "open":return "Chờ xử lý";case "assigned":return "Đang xử lý";
            case "resolved":return "Đã giải quyết";case "dismissed":return "Đã bác bỏ";
            case "published":return "Đang hiển thị";case "hidden":return "Đã ẩn";
            case "pending_review":return "Chờ duyệt";case "rejected":return "Đã từ chối";
            case "removed":return "Đã gỡ";case "sent":return "Đã gửi";
            case "archived":return "Đã lưu trữ";case "expired":return "Đã hết hạn";
            default:return clean(value).isEmpty()?"Không ghi nhận":"Trạng thái khác";
        }
    }
    public static String reason(String value){
        switch(clean(value)){
            case "spam":return "Tin rác";case "harassment":return "Quấy rối";
            case "inappropriate":return "Nội dung không phù hợp";case "misinformation":return "Thông tin sai lệch";
            case "violence":return "Bạo lực";case "hate":case "hate_speech":return "Ngôn từ thù ghét";
            case "nudity":case "sexual_content":return "Nội dung nhạy cảm";
            case "scam":case "fraud":return "Lừa đảo";case "impersonation":return "Mạo danh";
            case "copyright":return "Vi phạm bản quyền";case "other":case "":return "Lý do khác";
            default:return "Lý do khác";
        }
    }
    public static String action(String value){
        switch(clean(value)){
            case "hide":return "Ẩn nội dung";case "remove":return "Gỡ nội dung, giữ bằng chứng";
            case "restore":return "Khôi phục / mở khóa";case "suspend":return "Khóa tài khoản";
            case "unsuspend":return "Mở khóa tài khoản";case "warn":return "Ghi nhận cảnh cáo (chưa gửi)";
            case "dismiss":return "Bác bỏ báo cáo";default:return "Thao tác khác";
        }
    }
    public static String operation(String value){
        String key=clean(value).toLowerCase(Locale.ROOT);
        if("account_deletion_requested".equals(key))return "Yêu cầu xóa tài khoản";
        if("report_claim".equals(key))return "Nhận xử lý báo cáo";
        if("report_reassign".equals(key))return "Phân công lại báo cáo";
        if(key.startsWith("admin_"))return action(key.substring(6));
        if(key.startsWith("report_"))return "Xử lý báo cáo: "+action(key.substring(7));
        return "Hoạt động quản trị";
    }
    public static String timestamp(String value){
        ServerTime time=ServerTime.parse(clean(value));
        return time==null?"Không rõ thời gian":new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm",new Locale("vi","VN")).format(time.toDate());
    }
    public static void bindAudit(View card,JSONObject row){
        ((TextView)card.findViewById(R.id.tvAuditOperation)).setText(operation(text(row,"operation")));
        ((TextView)card.findViewById(R.id.tvAuditSubject)).setText(kind(text(row,"subject_type")));
        ((TextView)card.findViewById(R.id.tvAuditTime)).setText(timestamp(text(row,"created_at")));
        String old=text(row,"old_state"),next=text(row,"new_state");
        card.findViewById(R.id.layoutAuditTransition).setVisibility(old.isEmpty()&&next.isEmpty()?View.GONE:View.VISIBLE);
        ((TextView)card.findViewById(R.id.tvAuditOldState)).setText(state(old));
        ((TextView)card.findViewById(R.id.tvAuditNewState)).setText(state(next));
        String reason=text(row,"reason");
        ((TextView)card.findViewById(R.id.tvAuditReason)).setText(reason.isEmpty()?"Không ghi lý do":"Lý do: "+reason);
        String actor=text(row,"actor_id");
        ((TextView)card.findViewById(R.id.tvAuditActor)).setText(actor.isEmpty()?"Không rõ người thực hiện":"Thực hiện: #"+actor.substring(0,Math.min(8,actor.length())));
        TextView report=card.findViewById(R.id.tvAuditReportRef);String id=text(row,"report_id");
        report.setVisibility(id.isEmpty()?View.GONE:View.VISIBLE);
        report.setText(id.isEmpty()?"":"Báo cáo #"+id.substring(0,Math.min(8,id.length())));
    }
    public static AlertDialog actions(Context context,String title,JSONArray actions,IntConsumer selected){
        int pad=(int)(20*context.getResources().getDisplayMetrics().density);
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(pad,pad/2,pad,pad/2);
        ScrollView scroll=new ScrollView(context);scroll.addView(body);
        AlertDialog dialog=new MaterialAlertDialogBuilder(context).setTitle(title).setView(scroll).setNegativeButton("Hủy",null).create();
        for(int i=0;i<actions.length();i++){
            final int index=i;
            MaterialButton button=new MaterialButton(context);button.setText(action(actions.optString(i)));
            button.setAllCaps(false);button.setTextSize(15);button.setMinHeight((int)(52*context.getResources().getDisplayMetrics().density));
            button.setCornerRadius(pad/2);button.setTextColor(ContextCompat.getColor(context,R.color.white));
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context,R.color.brand_blue)));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=pad/3;
            body.addView(button,params);button.setOnClickListener(v->{dialog.dismiss();selected.accept(index);});
        }
        return dialog;
    }
}
