package com.example.meetomxh.repositories;
import com.example.meetomxh.models.*;
import com.example.meetomxh.supabase.*;
import org.json.*;
import java.util.*;
public final class FriendRepository {
 public interface OnFriendActionListener{void onSuccess(String message);void onFailure(String error);}
 public interface OnRelationshipStatusListener{void onStatus(FriendItem.RelationshipStatus status);void onError(String error);}
 public interface OnFriendItemsListener{void onItems(List<FriendItem> items);void onError(String error);}
 private static final FriendRepository INSTANCE=new FriendRepository();public static FriendRepository getInstance(){return INSTANCE;}
 private JSONArray pages(String rpc)throws Exception{JSONArray result=new JSONArray();JSONObject cursor=Api.obj("p_limit",50);for(int page=0;page<100;page++){JSONArray rows=(JSONArray)RpcClient.call(rpc,cursor);for(int i=0;i<rows.length();i++)result.put(rows.get(i));if(rows.length()<50)break;JSONObject last=rows.getJSONObject(rows.length()-1);cursor.put("p_after_time",last.getString("created_at")).put("p_after_id",last.getString("id"));}return result;}
 private JSONObject relation(String peer,String rpc)throws Exception{JSONArray list=pages(rpc);for(int i=0;i<list.length();i++)if(peer.equals(list.getJSONObject(i).optString("id")))return list.getJSONObject(i);return null;}
 private FriendItem.RelationshipStatus status(String peer)throws Exception{if(peer.equals(SessionManager.getInstance().uid()))return FriendItem.RelationshipStatus.SELF;if(relation(peer,"get_friends")!=null)return FriendItem.RelationshipStatus.ACCEPTED;if(relation(peer,"get_sent_requests")!=null)return FriendItem.RelationshipStatus.PENDING_SENT;if(relation(peer,"get_received_requests")!=null)return FriendItem.RelationshipStatus.PENDING_RECEIVED;return FriendItem.RelationshipStatus.NO_RELATION;}
 public void sendFriendRequest(String peer,OnFriendActionListener cb){Api.run(()->{String uuid=RequestIds.get("friend",peer);Object r=RpcClient.call("send_friend_request",Api.obj("p_recipient",peer,"p_client_request_id",uuid));RequestIds.complete("friend",peer);return r;},action(cb));}
 public void acceptFriendRequest(String peer,OnFriendActionListener cb){resolve(peer,"get_received_requests","accept_friend_request",cb);}
 public void removeFriendRelation(String peer,OnFriendActionListener cb){Api.run(()->{JSONObject r=relation(peer,"get_sent_requests");if(r!=null)return RpcClient.call("cancel_friend_request",Api.obj("p_request_id",r.getString("request_id")));r=relation(peer,"get_received_requests");if(r!=null)return RpcClient.call("decline_friend_request",Api.obj("p_request_id",r.getString("request_id")));return RpcClient.call("unfriend",Api.obj("p_other",peer));},action(cb));}
 private void resolve(String peer,String list,String operation,OnFriendActionListener cb){Api.run(()->{JSONObject row=relation(peer,list);if(row==null)throw new ErrorMapper.HttpError(404,"request_unavailable");return RpcClient.call(operation,Api.obj("p_request_id",row.getString("request_id")));},action(cb));}
 public void getRelationshipStatus(String peer,OnRelationshipStatusListener cb){Api.run(()->status(peer),new Api.Callback<FriendItem.RelationshipStatus>(){public void success(FriendItem.RelationshipStatus value){cb.onStatus(value);}public void error(String e){cb.onError(e);}});}
 public void getReceivedFriendRequests(OnFriendItemsListener cb){list("get_received_requests",FriendItem.RelationshipStatus.PENDING_RECEIVED,cb);}
 public void getSentFriendRequests(OnFriendItemsListener cb){list("get_sent_requests",FriendItem.RelationshipStatus.PENDING_SENT,cb);}
 public void getFriendsList(String id,OnFriendItemsListener cb){if(!id.equals(SessionManager.getInstance().uid())){cb.onError("Danh sách bạn bè chỉ hiển thị cho chủ tài khoản.");return;}list("get_friends",FriendItem.RelationshipStatus.ACCEPTED,cb);}
 public void getSuggestedFriends(OnFriendItemsListener cb){list("get_suggestions",FriendItem.RelationshipStatus.NO_RELATION,cb);}
 private void list(String name,FriendItem.RelationshipStatus status,OnFriendItemsListener cb){Api.run(()->{JSONArray rows=pages(name);List<FriendItem> result=new ArrayList<>();for(int i=0;i<rows.length();i++)result.add(new FriendItem(Dto.user(rows.getJSONObject(i)),status));return result;},items(cb));}
 public void searchUsers(String prefix,OnFriendItemsListener cb){if(prefix.trim().isEmpty()){getSuggestedFriends(cb);return;}Api.run(()->{JSONArray rows=(JSONArray)RpcClient.call("search_profiles",Api.obj("p_prefix",prefix.trim(),"p_limit",50));List<FriendItem> result=new ArrayList<>();for(int i=0;i<rows.length();i++){User u=Dto.user(rows.getJSONObject(i));result.add(new FriendItem(u,status(u.getUid())));}return result;},items(cb));}
 private Api.Callback<Object> action(OnFriendActionListener cb){return new Api.Callback<Object>(){public void success(Object v){cb.onSuccess("Đã cập nhật quan hệ");}public void error(String e){cb.onFailure(e);}};}
 private Api.Callback<List<FriendItem>> items(OnFriendItemsListener cb){return new Api.Callback<List<FriendItem>>(){public void success(List<FriendItem> rows){cb.onItems(rows);}public void error(String e){cb.onError(e);}};}
}