package com.example.meetomxh.supabase;
import org.json.*;
/** Payloads and request UUIDs share the Keystore encrypted, account-scoped session vault. */
public final class MessageOutbox {
 private static JSONObject read()throws JSONException{String value=SessionManager.getInstance().pending("message_outbox");return value.isEmpty()?new JSONObject():new JSONObject(value);}
 public static synchronized void put(JSONObject input)throws Exception{JSONObject rows=read();rows.put(input.getString("p_client_request_id"),input);SessionManager.getInstance().pending("message_outbox",rows.toString());}
 public static synchronized void done(String request)throws Exception{JSONObject rows=read();rows.remove(request);SessionManager.getInstance().pending("message_outbox",rows.toString());}
 public static synchronized JSONArray forConversation(String conversation)throws Exception{JSONObject rows=read();JSONArray result=new JSONArray();java.util.Iterator<String> keys=rows.keys();while(keys.hasNext()){JSONObject row=rows.getJSONObject(keys.next());if(conversation.equals(row.optString("p_conversation_id")))result.put(row);}return result;}
 public static int retry(String conversation)throws Exception{JSONArray rows=forConversation(conversation);int count=0;for(int i=0;i<rows.length();i++){JSONObject input=rows.getJSONObject(i);RpcClient.call("send_message",input);done(input.getString("p_client_request_id"));JSONObject fingerprint=new JSONObject(input.toString());fingerprint.remove("p_client_request_id");RequestIds.complete("message",fingerprint.toString());count++;}return count;}
}
