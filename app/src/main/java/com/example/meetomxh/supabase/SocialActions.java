package com.example.meetomxh.supabase;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.EditText;
import android.widget.Toast;
import com.example.meetomxh.ChatActivity;
import com.example.meetomxh.repositories.ChatRepository;
import org.json.JSONObject;

/** Checks server consent policy before opening a direct chat or text introduction. */
public final class SocialActions {
    private SocialActions() { }
    public static void contact(Activity a,String peer) {
        Api.run(()->RpcClient.object("direct_contact_policy",Api.obj("p_peer",peer)),new Api.Callback<JSONObject>() {
            public void success(JSONObject policy) {
                if(a.isFinishing())return;
                switch(policy.optString("policy")) {
                    case "direct":
                        ChatRepository.getInstance().getOrCreatePrivateChat(peer,new ChatRepository.OnChatListener(){
                            public void onSuccess(com.example.meetomxh.models.Chat c){a.startActivity(new Intent(a,ChatActivity.class).putExtra(ChatActivity.EXTRA_CHAT_ID,c.getChatId()).putExtra(ChatActivity.EXTRA_OTHER_UID,peer));}
                            public void onError(String e){toast(a,e);}
                        });break;
                    case "request": introduction(a,peer,null,null);break;
                    case "request_sent": toast(a,"Yêu cầu đã gửi. Chờ người nhận chấp nhận.");break;
                    case "request_rejected": toast(a,"Yêu cầu đã bị từ chối. Bạn không thể gửi thêm lời giới thiệu.");break;
                    default: toast(a,"Hãy theo dõi và chờ chấp nhận để nhắn tin.");
                }
            }
            public void error(String e){toast(a,e);}
        });
    }
    public static void introduction(Activity a,String peer,String story,String text) {
        EditText input=new EditText(a);input.setHint("Lời giới thiệu (tối đa 2000 ký tự)");input.setMinLines(3);if(text!=null)input.setText(text);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Gửi tin nhắn chờ").setMessage("Chỉ gửi một lời giới thiệu bằng văn bản. Bạn có thể gửi thêm sau khi được chấp nhận.")
            .setView(input).setNegativeButton("Hủy",null).setPositiveButton("Gửi",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String body=input.getText().toString().trim();if(body.isEmpty()||body.length()>2000){input.setError("Nhập từ 1 đến 2000 ký tự");return;}
            dialog.getButton(-1).setEnabled(false);String fingerprint=peer+"|"+story+"|"+body;
            Api.run(()->{JSONObject r=RpcClient.object("send_message_request",Api.obj("p_peer",peer,"p_body",body,"p_story_id",story,"p_client_request_id",RequestIds.get("message_request",fingerprint)));RequestIds.complete("message_request",fingerprint);return r;},new Api.Callback<JSONObject>(){
                public void success(JSONObject r){dialog.dismiss();toast(a,"rejected".equals(r.optString("state"))?"Yêu cầu đã bị từ chối.":"Đã gửi tin nhắn chờ.");}
                public void error(String e){dialog.getButton(-1).setEnabled(true);toast(a,e);}
            });
        }));dialog.show();
    }
    private static void toast(Activity a,String text){if(!a.isFinishing())Toast.makeText(a,text,Toast.LENGTH_LONG).show();}
}
