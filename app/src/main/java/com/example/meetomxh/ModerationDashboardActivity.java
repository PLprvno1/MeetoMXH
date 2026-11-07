package com.example.meetomxh;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.Forms;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import org.json.JSONArray;
import org.json.JSONObject;

public class ModerationDashboardActivity extends AppCompatActivity {
    private LinearLayout form;
    private String filter = "open";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        form = Forms.start(this, "Kiểm duyệt nền tảng");
        Forms.button(form, "Tải lại hàng đợi", this::load);
        Forms.button(form, "Báo cáo đang mở", () -> { filter="open"; load(); });
        Forms.button(form, "Báo cáo đã xử lý", () -> { filter="resolved"; load(); });
        Forms.button(form, "Lịch sử kiểm duyệt", this::loadAudit);
        load();
    }

    private void load() {
        form.removeViews(4, Math.max(0, form.getChildCount()-4));
        Forms.status(this, "Đang tải báo cáo…");
        Api.run(() -> RpcClient.call("list_platform_reports", Api.obj("p_state",filter,"p_after_time",JSONObject.NULL,"p_after_id",JSONObject.NULL,"p_limit",30)),
                new Api.Callback<Object>() {
                    @Override public void success(Object result) {
                        Forms.status(ModerationDashboardActivity.this, "");
                        JSONArray reports = result instanceof JSONArray ? (JSONArray)result : new JSONArray();
                        if (reports.length()==0) { Forms.status(ModerationDashboardActivity.this,"Không có báo cáo trong hàng đợi này."); return; }
                        TextView count = new TextView(ModerationDashboardActivity.this);
                        count.setText("Số báo cáo trong trang: " + reports.length()); form.addView(count);
                        for(int i=0;i<reports.length();i++) addReport(reports.optJSONObject(i));
                    }
                    @Override public void error(String error) { Forms.status(ModerationDashboardActivity.this,"Không thể tải hàng đợi: "+error); }
                });
    }

    private void loadAudit() {
        form.removeViews(4, Math.max(0, form.getChildCount()-4));
        Forms.status(this,"Đang tải nhật ký kiểm duyệt…");
        Api.run(()->RpcClient.call("get_platform_audit",Api.obj("p_limit",50)),new Api.Callback<Object>(){
            @Override public void success(Object value){
                Forms.status(ModerationDashboardActivity.this,"");
                JSONArray rows=value instanceof JSONArray?(JSONArray)value:new JSONArray();
                if(rows.length()==0){Forms.status(ModerationDashboardActivity.this,"Chưa có lịch sử kiểm duyệt.");return;}
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);
                    TextView item=new TextView(ModerationDashboardActivity.this);
                    item.setText(row.optString("operation")+" · "+row.optString("subject_type")+" · "+row.optString("created_at"));
                    item.setPadding(12,16,12,16);form.addView(item);
                }
            }
            @Override public void error(String error){Forms.status(ModerationDashboardActivity.this,"Không thể tải nhật ký kiểm duyệt: "+error);}
        });
    }

    private void addReport(JSONObject report) {
        if(report==null)return;
        String id=report.optString("id"), kind=report.optString("target_kind"), state=report.optString("state");
        String summary="#"+id.substring(0,Math.min(8,id.length()))+" · "+kind+" · "+report.optString("reason_code")+" · "+state;
        Forms.button(form,summary,()->showReport(report));
    }

    private void showReport(JSONObject report) {
        boolean unassigned=report.isNull("assigned_to")||report.optString("assigned_to").isEmpty();
        String body="Loại: "+report.optString("target_kind")+"\nĐối tượng: "+report.optString("target_id")
                +"\nLý do: "+report.optString("reason_code")+"\nChi tiết: "+report.optString("details")
                +"\nTrạng thái: "+report.optString("state");
        new AlertDialog.Builder(this).setTitle("Báo cáo").setMessage(body)
                .setNeutralButton("Đóng",null).setPositiveButton(unassigned?"Nhận xử lý":"Xem xét…",
                        (d,w)->{if(unassigned)claimReport(report);else moderate(report);}).show();
    }

    private void claimReport(JSONObject report){
        Api.run(()->RpcClient.object("claim_platform_report",Api.obj("p_report_id",report.optString("id"))),new Api.Callback<JSONObject>(){
            @Override public void success(JSONObject result){
                try{
                    for(String key:new String[]{"target_kind","target_id","reason_code","details","state","assigned_to"})
                        report.put(key,result.opt(key)==null?JSONObject.NULL:result.opt(key));
                }catch(org.json.JSONException ignored){}
                moderate(report);
            }
                    @Override public void error(String error){Forms.status(ModerationDashboardActivity.this,"Không thể nhận xử lý báo cáo: "+error);load();}
        });
    }
    private void moderate(JSONObject report) {
        EditText reason=new EditText(this);reason.setHint("Lý do xử lý (bắt buộc)");reason.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ghi nhận quyết định")
                .setMessage("Chỉ đóng báo cáo hoặc ghi nhận cảnh cáo.").setView(reason)
                .setNegativeButton("Hủy",null).setNeutralButton("Cảnh cáo",null).setPositiveButton("Bác bỏ",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->submitDecision(report,"warn",reason,dialog));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->submitDecision(report,"dismiss",reason,dialog));
        }); dialog.show();
    }

    private void submitDecision(JSONObject report,String action,EditText reason,AlertDialog dialog){
        String text=reason.getText().toString().trim();
        if(text.isEmpty()){reason.setError("Nhập lý do xử lý");return;}
        dialog.dismiss();
        Api.run(()->RpcClient.object("resolve_platform_report",Api.obj("p_report_id",report.optString("id"),"p_action",action,"p_reason",text)),new Api.Callback<JSONObject>(){
            @Override public void success(JSONObject result){Forms.status(ModerationDashboardActivity.this,"Đã ghi nhận quyết định và audit log.");load();}
            @Override public void error(String error){Forms.status(ModerationDashboardActivity.this,"Không thể xử lý: "+error);}
        });
    }
}
