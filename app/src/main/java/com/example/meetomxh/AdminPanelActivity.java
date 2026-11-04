package com.example.meetomxh;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.example.meetomxh.supabase.Api;
import com.example.meetomxh.supabase.AdminRoleResolver;
import com.example.meetomxh.supabase.MeetoPrefs;
import com.example.meetomxh.supabase.RpcClient;
import com.example.meetomxh.supabase.SessionManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/** System-admin tools. Every privileged operation is independently checked by its server RPC. */
public final class AdminPanelActivity extends AppCompatActivity {
    private static final String[] SECTIONS = {"Tổng quan", "Tài khoản", "Báo cáo", "Thêm"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String section = "Tổng quan", query = "", reportState = "open";
    private String afterTime;
    private String afterId;
    private boolean loading, accessVerified, pendingReload, resumedOnce, firstDataLoad=true;
    private JSONArray currentRows = new JSONArray();
    private LinearLayout content, tabs;
    private TextView status;
    private EditText search;
    private MaterialButton loadMore;
    private SwipeRefreshLayout refresh;
    private final Runnable searchRunnable = () -> { query = search.getText().toString().trim(); resetPage(); load(false); };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            section=state.getString("section",section); query=state.getString("query",query);
            reportState=state.getString("report_state",reportState);
        }
        setContentView(R.layout.activity_admin_panel);
        content = findViewById(R.id.adminContent); tabs = findViewById(R.id.adminTabs);
        status = findViewById(R.id.adminStatus); search = findViewById(R.id.adminSearch);
        loadMore = findViewById(R.id.adminLoadMore); refresh = findViewById(R.id.adminRefresh);
        com.google.android.material.appbar.MaterialToolbar toolbar=findViewById(R.id.adminToolbar);
        toolbar.inflateMenu(R.menu.admin_menu);
        toolbar.setOnMenuItemClickListener(item->{if(item.getItemId()==R.id.admin_logout){confirmLogout();return true;}return false;});
        refresh.setOnRefreshListener(() -> { resetPage(); load(false); });
        loadMore.setOnClickListener(v -> load(true));
        for (String item : SECTIONS) {
            MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            button.setText(item); button.setAllCaps(false); button.setMinHeight(dp(40));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40)); lp.setMargins(dp(4), 0, dp(4), 0);
            button.setLayoutParams(lp); button.setOnClickListener(v -> selectSection(item)); tabs.addView(button);
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int st,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int st,int before,int count) { handler.removeCallbacks(searchRunnable); handler.postDelayed(searchRunnable,350); }
            @Override public void afterTextChanged(Editable e) { }
        });
        status.setText("Đang xác minh quyền quản trị…");
        verifyAccess();
    }

    @Override protected void onResume() {
        super.onResume();
        if (resumedOnce && accessVerified && !isFinishing()) verifyAccess();
        resumedOnce=true;
    }

    private void verifyAccess() {
        status.setText("Đang xác minh quyền quản trị…");
        Api.run(AdminRoleResolver::resolve, new Api.Callback<AdminRoleResolver.Result>() {
            @Override public void success(AdminRoleResolver.Result result) {
                if(isFinishing())return;
                if(result.destination==AdminRoleResolver.Destination.ADMIN){
                    accessVerified=true;
                    status.setText("");
                    search.setVisibility(searchable(section)?View.VISIBLE:View.GONE);
                    if(firstDataLoad){firstDataLoad=false;search.setText(query);load(false);}
                } else if(result.destination==AdminRoleResolver.Destination.SOCIAL){
                    Intent intent=new Intent(AdminPanelActivity.this,MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED,true);
                    startActivity(intent);finish();
                } else if(result.destination==AdminRoleResolver.Destination.LOGIN){
                    SessionManager.getInstance().signOut();
                    startActivity(new Intent(AdminPanelActivity.this,LoginActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();
                } else showAccessRetry(result.message);
            }
            @Override public void error(String error){showAccessRetry("Không thể xác minh quyền. Hãy thử lại.");}
        });
    }

    private void showAccessRetry(String detail){
        accessVerified=false;loading=false;refresh.setRefreshing(false);content.removeAllViews();
        status.setText("Chưa xác minh được quyền quản trị. "+(detail==null?"Kiểm tra mạng rồi thử lại.":detail));
        content.addView(button("Thử xác minh lại",this::verifyAccess));
    }

    private void confirmLogout(){
        new AlertDialog.Builder(this).setTitle("Đăng xuất Admin")
            .setMessage("Bạn sẽ quay về màn hình đăng nhập.")
            .setNegativeButton("Ở lại",null).setPositiveButton("Đăng xuất",(d,w)->{
                SessionManager.getInstance().signOut();
                startActivity(new Intent(this,LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                finish();
            }).show();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("section",section); out.putString("query",search==null?query:search.getText().toString());
        out.putString("report_state",reportState); super.onSaveInstanceState(out);
    }

    private boolean handlePermissionError(String error) {
        String e=error==null?"":error.toLowerCase(java.util.Locale.ROOT);
        if(e.contains("system_admin_required")||e.contains("platform_moderator_required")||e.contains("403")) {
            verifyAccess(); return true;
        }
        return false;
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }

    private void selectSection(String value) {
        if("Tài khoản".equals(value))value="Người dùng";
        if("Thêm".equals(value)){
            String[] more={"Bài viết","Reels","Bình luận","Nhóm","Nhật ký","Cài đặt"};
            new AlertDialog.Builder(this).setTitle("Quản lý hệ thống").setItems(more,(d,which)->selectSection(more[which])).show();
            return;
        }
        section = value; resetPage();
        search.setVisibility(searchable(value) ? View.VISIBLE : View.GONE);
        search.setText(""); query = "";
        if("Cài đặt".equals(section)){renderSettings();return;}
        load(false);
    }

    private boolean searchable(String value){return !"Tổng quan".equals(value)&&!"Nhật ký".equals(value)&&!"Cài đặt".equals(value);}

    private void resetPage() { afterTime = null; afterId = null; currentRows = new JSONArray(); loadMore.setVisibility(View.GONE); }

    private void load(boolean append) {
        if (!accessVerified || isFinishing()) return;
        if("Cài đặt".equals(section)){renderSettings();return;}
        if (loading) { pendingReload = true; return; }
        final String requestedSection=section, requestedQuery=query, requestedReportState=reportState;
        final String requestedAfterTime=afterTime, requestedAfterId=afterId;
        loading = true; refresh.setRefreshing(!append); loadMore.setEnabled(false);
        status.setText(append ? "Đang tải thêm…" : "Đang tải dữ liệu…");
        if (!append) content.removeAllViews();
        Api.run(() -> {
            if ("Tổng quan".equals(requestedSection)) return RpcClient.call("admin_dashboard", Api.obj());
            if ("Người dùng".equals(requestedSection)) return RpcClient.call("admin_search_users", Api.obj("p_query",requestedQuery,"p_after_time",nullable(requestedAfterTime),"p_after_id",nullable(requestedAfterId),"p_limit",30));
            if ("Báo cáo".equals(requestedSection)) return RpcClient.call("list_platform_reports", Api.obj("p_state",requestedReportState,"p_after_time",nullable(requestedAfterTime),"p_after_id",nullable(requestedAfterId),"p_limit",30));
            if ("Nhật ký".equals(requestedSection)) return RpcClient.call("admin_audit_page", Api.obj("p_after_time",nullable(requestedAfterTime),"p_after_id",nullable(requestedAfterId),"p_limit",30));
            String kind = "Bài viết".equals(requestedSection) ? "posts" : "Reels".equals(requestedSection) ? "reels" : "Bình luận".equals(requestedSection) ? "comments" : "groups";
            return RpcClient.call("admin_list_content", Api.obj("p_kind",kind,"p_query",requestedQuery,"p_state",JSONObject.NULL,
                    "p_after_time",nullable(requestedAfterTime),"p_after_id",nullable(requestedAfterId),"p_limit",30));
        }, new Api.Callback<Object>() {
            @Override public void success(Object value) {
                loading=false; refresh.setRefreshing(false); loadMore.setEnabled(true);
                if (!requestedSection.equals(section)) { pendingReload=false; load(false); return; }
                if (pendingReload) { pendingReload=false; load(false); return; }
                status.setText("");
                if ("Tổng quan".equals(section)) { renderDashboard((JSONObject)value); return; }
                JSONArray rows = value instanceof JSONArray ? (JSONArray)value : new JSONArray();
                if (!append) currentRows = rows; else for (int i=0;i<rows.length();i++) currentRows.put(rows.opt(i));
                renderRows(rows, append);
                if (rows.length()==0 && currentRows.length()==0) status.setText("Không có dữ liệu phù hợp.");
                if (rows.length()>0) {
                    JSONObject last=rows.optJSONObject(rows.length()-1);
                    if (last!=null) { afterTime=last.optString("created_at",null); afterId=last.optString("user_id",last.optString("id",null)); }
                    loadMore.setVisibility(rows.length()>=30?View.VISIBLE:View.GONE);
                }
            }
            @Override public void error(String error) {
                loading=false; refresh.setRefreshing(false); loadMore.setEnabled(true);
                if (handlePermissionError(error)) return;
                if (!requestedSection.equals(section) || pendingReload) { pendingReload=false; load(false); return; }
                content.removeAllViews(); status.setText("Không tải được dữ liệu. Kéo xuống hoặc chạm Thử lại.\n"+error);
                MaterialButton retry=button("Thử tải lại",()->load(false)); content.addView(retry);
            }
        });
    }

    private Object nullable(String value) { return value==null||value.isEmpty()?JSONObject.NULL:value; }

    private void renderDashboard(JSONObject d) {
        content.removeAllViews();
        addHeader("Trung tâm quản trị");
        addText("Theo dõi và quản lý hoạt động của Meeto.");
        addCard("Người dùng", number(d,"users_total")+" tổng · "+number(d,"users_active")+" đang hoạt động",null);
        addCard("Bài viết", number(d,"posts_total")+" bài",()->selectSection("Bài viết"));
        addCard("Reels", number(d,"reels_total")+" reels",()->selectSection("Reels"));
        addCard("Nhóm", number(d,"groups_total")+" nhóm",()->selectSection("Nhóm"));
        addCard("Báo cáo", number(d,"reports_pending")+" chờ · "+number(d,"reports_resolved")+" đã xử lý",()->selectSection("Báo cáo"));
        addHeader("Báo cáo gần đây"); renderInlineRows(d.optJSONArray("recent_reports"),true);
        addHeader("Thao tác gần đây"); renderInlineRows(d.optJSONArray("recent_actions"),false);
    }

    private void renderSettings(){
        content.removeAllViews();
        addHeader("Tài khoản quản trị");
        com.example.meetomxh.supabase.SessionUser user=SessionManager.getInstance().getCurrentUser();
        addCard("Meeto Admin",user==null?"Phiên đăng nhập không khả dụng":user.getEmail(),null);
        addHeader("Giao diện");
        int current=MeetoPrefs.theme(this);
        String theme=current==AppCompatDelegate.MODE_NIGHT_YES?"Tối":current==AppCompatDelegate.MODE_NIGHT_NO?"Sáng":"Theo hệ thống";
        addCard("Chế độ hiển thị",theme+" · Đồng bộ với giao diện Meeto",()->new AlertDialog.Builder(this)
            .setTitle("Chế độ hiển thị")
            .setItems(new String[]{"Theo hệ thống","Sáng","Tối"},(d,which)->{
                int mode=which==0?AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:which==1
                    ?AppCompatDelegate.MODE_NIGHT_NO:AppCompatDelegate.MODE_NIGHT_YES;
                MeetoPrefs.setTheme(this,mode);AppCompatDelegate.setDefaultNightMode(mode);
            }).show());
        addHeader("Ứng dụng");
        addCard("Meeto Admin","Phiên bản "+BuildConfig.VERSION_NAME,null);
        content.addView(button("Đăng xuất",this::confirmLogout));
    }

    private long number(JSONObject value,String key) { return value==null?0:value.optLong(key,0); }
    private void renderRows(JSONArray rows,boolean append) {
        if (!append) content.removeAllViews();
        if ("Báo cáo".equals(section) && !append) {
            content.addView(button("Báo cáo đang mở",()->{reportState="open";resetPage();load(false);}));
            content.addView(button("Đã xử lý",()->{reportState="resolved";resetPage();load(false);}));
            content.addView(button("Đã bỏ qua",()->{reportState="dismissed";resetPage();load(false);}));
        }
        for (int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if (row==null) continue;
            if ("Người dùng".equals(section)) renderUser(row);
            else if ("Báo cáo".equals(section)) renderReport(row);
            else if ("Nhật ký".equals(section)) renderAudit(row);
            else renderContentRow(row);
        }
    }

    private void renderInlineRows(JSONArray rows,boolean report) {
        if(rows==null||rows.length()==0){ addText("Chưa có mục nào."); return; }
        for(int i=0;i<rows.length();i++){ JSONObject row=rows.optJSONObject(i); if(row!=null) { if(report) renderReport(row); else renderAudit(row); } }
    }

    private void renderUser(JSONObject row) {
        String uid=row.optString("user_id"), name=row.optString("display_name","Người dùng");
        addCard(name+"  ·  "+stateName(row.optString("account_state")),"Tham gia "+row.optString("created_at"),()->new AlertDialog.Builder(this)
                .setTitle(name).setMessage("Trạng thái: "+stateName(row.optString("account_state"))
                    +"\nUID: "+uid+"\nSystem Admin: "+(row.optBoolean("is_system_admin")?"Có":"Không"))
                .setNeutralButton("Đóng",null)
                .setPositiveButton("active".equals(row.optString("account_state"))?"Khóa tạm thời":"Mở khóa",(d,w)->confirmAccount(row)).show());
    }

    private void confirmAccount(JSONObject row) {
        String action="active".equals(row.optString("account_state"))?"suspend":"restore";
        EditText reason=reasonField();
        new AlertDialog.Builder(this).setTitle("Xác nhận "+(action.equals("suspend")?"khóa tài khoản":"mở khóa tài khoản"))
                .setMessage("Thao tác sẽ được xác thực và ghi vào audit log.").setView(reason).setNegativeButton("Hủy",null)
                .setPositiveButton("Xác nhận",(d,w)->{
                    String text=reason.getText().toString().trim(); if(text.isEmpty()){toast("Cần nhập lý do.");return;}
                    runAction("admin_set_account_status",Api.obj("p_user_id",row.optString("user_id"),"p_action",action,
                            "p_reason",text,"p_request_id",UUID.randomUUID().toString()),"Đã cập nhật trạng thái tài khoản.");
                }).show();
    }

    private void renderContentRow(JSONObject row) {
        String kind=row.optString("kind"), id=row.optString("id"), state=row.optString("state");
        String title="groups".equals(kind)?row.optString("name","Nhóm"):"comments".equals(kind)?"Bình luận":("reels".equals(kind)?"Reel":"Bài viết");
        String preview=row.optString("body",row.optString("description","")); if(preview.length()>180) preview=preview.substring(0,180)+"…";
        String meta="Trạng thái: "+stateName(state)+"  ·  "+row.optString("author_name",row.optString("created_at"));
        addCard(title+"  ·  "+meta,preview,()->contentActions(row));
    }

    private void contentActions(JSONObject row) {
        String kind=row.optString("kind");
        final String rpcKind="groups".equals(kind)?"group":"comments".equals(kind)?"comment":"reels".equals(kind)?"reel":"post";
        String state=row.optString("state");
        String[] actions="hidden".equals(state)||"archived".equals(state)?new String[]{"Khôi phục"}:"deleted".equals(state)?new String[]{}:new String[]{"Ẩn","Gỡ nội dung"};
        if(actions.length==0){toast("Nội dung đã gỡ, không thể khôi phục bằng contract hiện có.");return;}
        new AlertDialog.Builder(this).setTitle("Quản lý nội dung").setItems(actions,(dialog,index)->confirmContentAction(row,rpcKind,
                actions[index].equals("Khôi phục")?"restore":actions[index].equals("Ẩn")?"hide":"remove")).setNegativeButton("Đóng",null).show();
    }

    private void confirmContentAction(JSONObject row,String kind,String action) {
        EditText reason=reasonField(); String verb=action.equals("restore")?"khôi phục":action.equals("hide")?"ẩn":"gỡ";
        new AlertDialog.Builder(this).setTitle("Xác nhận "+verb).setMessage("Lý do sẽ được lưu trong audit log.").setView(reason)
                .setNegativeButton("Hủy",null).setPositiveButton("Xác nhận",(d,w)->{
                    String text=reason.getText().toString().trim(); if(text.isEmpty()){toast("Cần nhập lý do.");return;}
                    runAction("admin_moderate_content",Api.obj("p_kind",kind,"p_target_id",row.optString("id"),"p_action",action,
                            "p_reason",text,"p_request_id",UUID.randomUUID().toString()),"Đã cập nhật nội dung.");
                }).show();
    }

    private void renderReport(JSONObject row) {
        String title=row.optString("target_kind","report")+" · "+row.optString("reason_code")+" · "+stateName(row.optString("state"));
        addCard(title,row.optString("created_at"),()->openReport(row));
    }

    private void openReport(JSONObject row) {
        String id=row.optString("id");
        if(row.isNull("assigned_to")||row.optString("assigned_to").isEmpty()) {
            Api.run(()->RpcClient.object("claim_platform_report",Api.obj("p_report_id",id)),new Api.Callback<JSONObject>(){
                @Override public void success(JSONObject r){ fetchReportDetail(id); }
                @Override public void error(String e){ toast("Báo cáo đã được nhận hoặc xử lý: "+e); load(false); }
            });
        } else fetchReportDetail(id);
    }

    private void fetchReportDetail(String id) {
        Api.run(()->RpcClient.object("admin_report_detail",Api.obj("p_report_id",id)),new Api.Callback<JSONObject>(){
            @Override public void success(JSONObject r){showReportDetail(r);}
            @Override public void error(String e){if(!handlePermissionError(e))toast("Không tải được nội dung báo cáo: "+e);}
        });
    }

    private void showReportDetail(JSONObject r) {
        JSONObject target=r.optJSONObject("target");
        String text="Loại: "+r.optString("target_kind")+"\nLý do: "+r.optString("reason_code")+"\nChi tiết: "+r.optString("details")+
                "\nTrạng thái: "+stateName(r.optString("state"));
        if(target!=null) text+="\n\nNội dung: "+target.optString("body",target.optString("name",target.optString("display_name",target.optString("preview","(không còn khả dụng)"))))+
                "\nTác giả: "+target.optString("author_name","")+"\nTrạng thái nội dung: "+stateName(target.optString("state"));
        new AlertDialog.Builder(this).setTitle("Chi tiết báo cáo").setMessage(text).setNeutralButton("Đóng",null)
                .setPositiveButton("Xử lý",(d,w)->reportDecision(r,"warn")).setNegativeButton("Bỏ qua",(d,w)->reportDecision(r,"dismiss")).show();
    }

    private void reportDecision(JSONObject r,String action) {
        EditText reason=reasonField(); new AlertDialog.Builder(this).setTitle(action.equals("warn")?"Ghi nhận cảnh cáo":"Bỏ qua báo cáo")
                .setView(reason).setNegativeButton("Hủy",null).setPositiveButton("Lưu",(d,w)->{
                    String text=reason.getText().toString().trim(); if(text.isEmpty()){toast("Cần nhập lý do.");return;}
                    Api.run(()->RpcClient.object("resolve_platform_report",Api.obj("p_report_id",r.optString("id"),"p_action",action,"p_reason",text)),
                            new Api.Callback<JSONObject>() { public void success(JSONObject x){toast("Đã cập nhật báo cáo và audit log.");load(false);} public void error(String e){toast(e);} });
                }).show();
    }

    private void renderAudit(JSONObject row) {
        addCard(row.optString("operation")+" · "+row.optString("subject_type"),row.optString("created_at")+"\n"+row.optString("safe_metadata"),null);
    }

    private EditText reasonField() { TextInputEditText field=new TextInputEditText(this); field.setHint("Lý do xử lý (bắt buộc)"); field.setMinLines(2); field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); return field; }

    private void runAction(String rpc,JSONObject args,String success) {
        Api.run(()->RpcClient.object(rpc,args),new Api.Callback<JSONObject>(){
            @Override public void success(JSONObject result){toast(success);resetPage();load(false);}
            @Override public void error(String error){if(!handlePermissionError(error))toast("Thao tác thất bại: "+error);}
        });
    }

    private MaterialButton button(String label,Runnable action) {
        MaterialButton b=new MaterialButton(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v->action.run()); return b;
    }

    private void addCard(String title,String subtitle,Runnable action) {
        MaterialCardView card=new MaterialCardView(this); card.setCardBackgroundColor(ContextCompat.getColor(this,R.color.surface_bg));
        card.setRadius(dp(16)); card.setCardElevation(dp(1)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(10); card.setLayoutParams(lp);
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(14),dp(16),dp(14));
        TextView heading=new TextView(this); heading.setText(title); heading.setTextColor(ContextCompat.getColor(this,R.color.text_primary)); heading.setTextSize(16); heading.setTypeface(null,android.graphics.Typeface.BOLD); box.addView(heading);
        if(subtitle!=null&&!subtitle.isEmpty()){TextView detail=new TextView(this); detail.setText(subtitle); detail.setTextColor(ContextCompat.getColor(this,R.color.text_secondary)); detail.setTextSize(13); LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,-2);dpv.topMargin=dp(6);detail.setLayoutParams(dpv);box.addView(detail);}
        card.addView(box); if(action!=null){card.setClickable(true);card.setFocusable(true);card.setOnClickListener(v->action.run());} content.addView(card);
    }

    private void addHeader(String value){TextView h=new TextView(this);h.setText(value);h.setTextColor(ContextCompat.getColor(this,R.color.text_primary));h.setTextSize(18);h.setTypeface(null,android.graphics.Typeface.BOLD);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(10),0,dp(8));h.setLayoutParams(lp);content.addView(h);}
    private void addText(String value){TextView t=new TextView(this);t.setText(value);t.setTextColor(ContextCompat.getColor(this,R.color.text_secondary));content.addView(t);}
    private String stateName(String value){switch(value){case "active":return "Đang hoạt động";case "suspended":return "Đã khóa";case "open":return "Chờ xử lý";case "assigned":return "Đang xử lý";case "resolved":return "Đã xử lý";case "dismissed":return "Đã bỏ qua";case "hidden":return "Đã ẩn";case "deleted":return "Đã gỡ";case "archived":return "Đã lưu trữ";case "published":return "Đang hiển thị";default:return value;}}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
