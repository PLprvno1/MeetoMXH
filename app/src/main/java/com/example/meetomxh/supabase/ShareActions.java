package com.example.meetomxh.supabase;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.example.meetomxh.ChatActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.models.Post;
import com.example.meetomxh.supabase.Api;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Share entry points backed only by permission-checking RPCs. */
public final class ShareActions {
    private ShareActions() { }
    private static String root(Post p) { return p.getOriginalPostId() == null ? p.getPostId() : p.getOriginalPostId(); }
    private static int dp(Context c, int n) { return (int)(n*c.getResources().getDisplayMetrics().density+.5f); }
    private static TextView text(Context c,String value,int size,boolean bold) { TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(c.getColor(R.color.text_primary));if(bold)v.setTypeface(null,Typeface.BOLD);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    private static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(c,20),dp(c,16),dp(c,20),dp(c,20));return l; }
    private static void row(LinearLayout parent,Context c,String icon,String title,String hint,Runnable action) {
        LinearLayout item=new LinearLayout(c);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(c,10),dp(c,12),dp(c,8),dp(c,12));item.setBackgroundResource(android.R.drawable.list_selector_background);item.setMinimumHeight(dp(c,64));
        TextView symbol=text(c,icon,22,true);symbol.setGravity(Gravity.CENTER);item.addView(symbol,new LinearLayout.LayoutParams(dp(c,44),dp(c,44)));
        LinearLayout copy=column(c);copy.setPadding(dp(c,12),0,0,0);copy.addView(text(c,title,16,true));TextView sub=text(c,hint,12,false);sub.setTextColor(c.getColor(R.color.text_secondary));copy.addView(sub);item.addView(copy,new LinearLayout.LayoutParams(0,-2,1));item.setOnClickListener(v->action.run());parent.addView(item,new LinearLayout.LayoutParams(-1,-2));
    }
    public static void show(Activity a,Post post,boolean fromGroup) {
        if(a==null||post==null||post.getPostId()==null||a.isFinishing())return;
        BottomSheetDialog sheet=new BottomSheetDialog(a);LinearLayout body=column(a);TextView title=text(a,"Chia sẻ bài viết",20,true);body.addView(title);body.addView(text(a,"Chọn nơi bạn muốn chia sẻ",14,false));
        if(post.getGroupId()==null&&!fromGroup)row(body,a,"↗","Chia sẻ lên bảng tin","Thêm lời nhắn và chọn đối tượng xem",()->{sheet.dismiss();composer(a,post);});
        row(body,a,"➤","Gửi qua Messenger","Chọn một hoặc nhiều cuộc trò chuyện",()->{sheet.dismiss();messenger(a,post);});
        row(body,a,"⧉","Sao chép liên kết","Liên kết Meeto, không chứa dữ liệu riêng tư",()->{String link="meeto://post/"+post.getPostId();ClipboardManager cm=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Meeto post",link));Toast.makeText(a,"Đã sao chép liên kết",Toast.LENGTH_SHORT).show();sheet.dismiss();});
        row(body,a,"×","Đóng","",sheet::dismiss);sheet.setContentView(body);sheet.show();
    }
    private static void composer(Activity a,Post p) {
        LinearLayout root=column(a);TextView state=text(a,"Đang tải bản xem trước…",14,false);root.addView(state);
        android.widget.ImageView preview=new android.widget.ImageView(a);preview.setAdjustViewBounds(true);preview.setMaxHeight(dp(a,220));preview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);preview.setVisibility(View.GONE);root.addView(preview,new LinearLayout.LayoutParams(-1,-2));
        EditText note=new EditText(a);note.setHint("Viết lời nhắn về bài viết này…");note.setMinLines(2);note.setGravity(Gravity.TOP);root.addView(note,new LinearLayout.LayoutParams(-1,-2));
        RadioGroup audience=new RadioGroup(a);audience.setOrientation(RadioGroup.VERTICAL);RadioButton pub=new RadioButton(a),friends=new RadioButton(a),only=new RadioButton(a);pub.setId(View.generateViewId());friends.setId(View.generateViewId());only.setId(View.generateViewId());pub.setText("Công khai");friends.setText("Người theo dõi");only.setText("Chỉ mình tôi");audience.addView(pub);audience.addView(friends);audience.addView(only);pub.setVisibility(View.GONE);only.setChecked(true);root.addView(audience);Api.run(ProfileClient::self,new Api.Callback<JSONObject>(){public void success(JSONObject p){JSONObject settings=p.optJSONObject("settings");if(settings!=null&&settings.optBoolean("account_private")){pub.setVisibility(View.GONE);friends.setChecked(true);}else{pub.setVisibility(View.VISIBLE);pub.setChecked(true);}}public void error(String e){pub.setVisibility(View.GONE);only.setChecked(true);}});
        Button submit=new Button(a);submit.setText("Đăng chia sẻ");submit.setEnabled(false);root.addView(submit);
        BottomSheetDialog dialog=new BottomSheetDialog(a);ScrollView scroll=new ScrollView(a);scroll.addView(root);Button cancel=new Button(a);cancel.setText("Hủy");cancel.setOnClickListener(v->dialog.dismiss());root.addView(cancel);dialog.setContentView(scroll);if(dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Api.run(()->RpcClient.object("get_post",Api.obj("p_post_id",root(p))),new Api.Callback<JSONObject>(){
            public void success(JSONObject original){if(a.isFinishing())return;String author=original.optJSONObject("author")==null?"Meeto user":original.optJSONObject("author").optString("display_name","Meeto user");state.setText(author+"\n"+original.optString("body",""));state.setMaxLines(5);org.json.JSONArray media=original.optJSONArray("media");if(media!=null&&media.length()>0){preview.setVisibility(View.VISIBLE);com.bumptech.glide.Glide.with(preview).load(Dto.asset(media.optJSONObject(0).optString("asset_id"))).placeholder(R.drawable.ic_media_error).error(R.drawable.ic_media_error).into(preview);}submit.setEnabled(true);}
            public void error(String e){if(!a.isFinishing())state.setText("Bài viết gốc không còn khả dụng với bạn.");}
        });
        submit.setOnClickListener(v->{submit.setEnabled(false);submit.setText("Đang đăng…");String text=note.getText().toString().trim();String target=audience.getCheckedRadioButtonId()==friends.getId()?"followers":audience.getCheckedRadioButtonId()==only.getId()?"private":"public";String source=root(p),fingerprint=source+"|"+text+"|"+target;String request=RequestIds.get("share",fingerprint);
            Api.run(()->{Object result=RpcClient.call("share_post",Api.obj("p_original",source,"p_body",text,"p_audience",target,"p_client_request_id",request));RequestIds.complete("share",fingerprint);return result;},new Api.Callback<Object>(){public void success(Object v){dialog.dismiss();Toast.makeText(a,"Đã chia sẻ lên bảng tin",Toast.LENGTH_SHORT).show();}public void error(String e){submit.setEnabled(true);submit.setText("Thử đăng lại");Toast.makeText(a,e,Toast.LENGTH_LONG).show();}});});dialog.show();
    }
    static String conversationLabel(String rawTitle, String memberName, int position) {
        String title = normalizedLabel(rawTitle);
        if (title.isEmpty()) title = normalizedLabel(memberName);
        return title.isEmpty() ? "Cuộc trò chuyện " + (position + 1) : title;
    }

    private static String normalizedLabel(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? "" : trimmed;
    }

    private static void messenger(Activity a,Post p) {
        ProgressDialog progress=new ProgressDialog(a);progress.setMessage("Đang tải cuộc trò chuyện…");progress.show();
        Api.run(()->{
            JSONArray rows=(JSONArray)RpcClient.call("get_inbox",Api.obj("p_limit",20,"p_include_archived",false));
            ArrayList<String> ids=new ArrayList<>(),labels=new ArrayList<>();
            String self=SessionManager.getInstance().uid();
            for(int i=0;i<rows.length();i++){
                JSONObject x=rows.optJSONObject(i);if(x==null)continue;
                String id=x.optString("id");
                String title=x.isNull("title")?"":x.optString("title","");
                String memberName="";
                if(normalizedLabel(title).isEmpty()){
                    JSONArray members=(JSONArray)RpcClient.call("get_conversation_members",Api.obj("p_conversation_id",id));
                    for(int j=0;j<members.length();j++){
                        JSONObject member=members.optJSONObject(j);
                        if(member!=null&&!self.equals(member.optString("user_id"))){
                            memberName=member.isNull("display_name")?"":member.optString("display_name","");
                            if(!normalizedLabel(memberName).isEmpty())break;
                        }
                    }
                }
                ids.add(id);labels.add(conversationLabel(title,memberName,i));
            }
            return new Object[]{ids,labels};
        },new Api.Callback<Object[]>(){
            @SuppressWarnings("unchecked") public void success(Object[] result){if(a.isFinishing())return;progress.dismiss();ArrayList<String> ids=(ArrayList<String>)result[0],labels=(ArrayList<String>)result[1];if(ids.isEmpty()){Toast.makeText(a,"Chưa có cuộc trò chuyện để gửi",Toast.LENGTH_SHORT).show();return;}chooseChats(a,p,ids,labels);}
            public void error(String e){if(!a.isFinishing()){progress.dismiss();Toast.makeText(a,e,Toast.LENGTH_LONG).show();}}
        });
    }
    private static void chooseChats(Activity a,Post p,ArrayList<String> ids,ArrayList<String> labels){LinearLayout root=column(a);EditText search=new EditText(a);search.setSingleLine(true);search.setHint("Tìm người nhận");root.addView(search);LinearLayout choices=new LinearLayout(a);choices.setOrientation(LinearLayout.VERTICAL);ArrayList<CheckBox> boxes=new ArrayList<>();for(String label:labels){CheckBox box=new CheckBox(a);box.setText(label);box.setMinHeight(dp(a,48));boxes.add(box);choices.addView(box);}ScrollView scroll=new ScrollView(a);scroll.addView(choices);root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(a,340)));for(int i=0;i<boxes.size();i++){String name=labels.get(i).toLowerCase(Locale.ROOT);boxes.get(i).setTag(name);}search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){String q=s.toString().toLowerCase(Locale.ROOT);for(CheckBox b:boxes)b.setVisibility(((String)b.getTag()).contains(q)?View.VISIBLE:View.GONE);}public void afterTextChanged(android.text.Editable e){}});android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(a).setTitle("Gửi qua Messenger").setView(root).setNegativeButton("Hủy",null).setPositiveButton("Gửi",null).create();dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{ArrayList<String> selected=new ArrayList<>();for(int i=0;i<boxes.size();i++)if(boxes.get(i).isChecked())selected.add(ids.get(i));if(selected.isEmpty()){Toast.makeText(a,"Chọn ít nhất một cuộc trò chuyện",Toast.LENGTH_SHORT).show();return;}dialog.dismiss();sendToChats(a,p,selected,0);}));dialog.show();}
    private static void sendToChats(Activity a,Post p,List<String> ids,int at){if(at>=ids.size()){Toast.makeText(a,"Đã gửi bài viết qua Messenger",Toast.LENGTH_SHORT).show();return;}String chat=ids.get(at),source=root(p),fingerprint=chat+"|"+source,request=RequestIds.get("shared_post_message",fingerprint);Api.run(()->{Object result=RpcClient.call("send_shared_post",Api.obj("p_conversation_id",chat,"p_original",source,"p_client_request_id",request));RequestIds.complete("shared_post_message",fingerprint);return result;},new Api.Callback<Object>(){public void success(Object v){sendToChats(a,p,ids,at+1);}public void error(String e){new android.app.AlertDialog.Builder(a).setMessage("Không gửi được. Thử lại?").setNegativeButton("Đóng",null).setPositiveButton("Thử lại",(d,w)->sendToChats(a,p,ids,at)).show();}});}


}
