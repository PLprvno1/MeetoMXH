package com.example.meetomxh.fragments;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.example.meetomxh.adapters.CommentAdapter;
import com.example.meetomxh.databinding.LayoutCommentsBottomSheetBinding;
import com.example.meetomxh.models.Comment;
import com.example.meetomxh.supabase.*;
import org.json.*;
import java.util.*;
public class CommentsBottomSheetFragment extends BottomSheetDialogFragment implements CommentAdapter.OnCommentClickListener {
 private LayoutCommentsBottomSheetBinding binding;private CommentAdapter adapter;private String postId,parent,reply;private Subscription observer;
 public static CommentsBottomSheetFragment newInstance(String id){CommentsBottomSheetFragment f=new CommentsBottomSheetFragment();Bundle args=new Bundle();args.putString("post",id);f.setArguments(args);return f;}
 @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup container,@Nullable Bundle state){binding=LayoutCommentsBottomSheetBinding.inflate(inflater,container,false);return binding.getRoot();}
 @Override public void onViewCreated(@NonNull View v,@Nullable Bundle state){postId=requireArguments().getString("post");adapter=new CommentAdapter(SessionManager.getInstance().uid(),this);binding.rvComments.setLayoutManager(new LinearLayoutManager(requireContext()));binding.rvComments.setAdapter(adapter);binding.btnCloseComments.setOnClickListener(x->{if(parent!=null){parent=null;reply=null;listen();}else dismiss();});binding.btnSendComment.setOnClickListener(x->send());listen();}
 private void listen(){if(observer!=null)observer.remove();binding.tvCommentsTitle.setText(parent==null?"Bình luận":"Trả lời · Đóng để quay lại");observer=new com.example.meetomxh.supabase.Observer<>(()->CommentClient.thread(postId,parent),new Api.Callback<List<Comment>>(){public void success(List<Comment> rows){if(binding==null)return;adapter.setComments(rows);binding.tvEmptyComments.setText("Chưa có bình luận");binding.tvEmptyComments.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);}public void error(String e){if(binding!=null){adapter.setComments(new ArrayList<>());binding.tvEmptyComments.setText(e);binding.tvEmptyComments.setVisibility(View.VISIBLE);}}});}
 private void send(){String text=binding.etCommentInput.getText().toString().trim();if(text.isEmpty())return;binding.btnSendComment.setEnabled(false);JSONObject args=Api.obj("p_post_id",postId,"p_parent_id",reply==null?parent:reply,"p_body",text);String fingerprint=args.toString();Api.run(()->{args.put("p_client_request_id",RequestIds.get("comment",fingerprint));Object r=RpcClient.call("create_comment",args);RequestIds.complete("comment",fingerprint);return r;},new Api.Callback<Object>(){public void success(Object r){if(binding==null)return;binding.etCommentInput.setText("");binding.btnSendComment.setEnabled(true);if(reply!=null){parent=reply;reply=null;}listen();}public void error(String e){if(binding!=null){binding.btnSendComment.setEnabled(true);toast(e);}}});}
 @Override public void onCommentClick(Comment c){List<String> choices=new ArrayList<>(Arrays.asList("Trả lời","Xem trả lời"));boolean own=SessionManager.getInstance().uid().equals(c.getAuthorUid())&&"published".equals(c.state);if(own){choices.add("Sửa");choices.add("Xóa");}new android.app.AlertDialog.Builder(requireContext()).setItems(choices.toArray(new String[0]),(d,i)->{if(i==0){reply=c.getCommentId();binding.etCommentInput.setHint("Trả lời bình luận");binding.etCommentInput.requestFocus();}else if(i==1){parent=c.getCommentId();reply=null;listen();}else if(i==2){EditText edit=new EditText(requireContext());edit.setText(c.getContent());new android.app.AlertDialog.Builder(requireContext()).setTitle("Sửa bình luận").setView(edit).setPositiveButton("Lưu",(dialog,w)->change("edit_comment",Api.obj("p_comment_id",c.getCommentId(),"p_body",edit.getText().toString(),"p_expected_version",c.version))).setNegativeButton("Hủy",null).show();}else onDeleteCommentClick(c);}).show();}
 @Override public void onDeleteCommentClick(Comment c){change("delete_comment",Api.obj("p_comment_id",c.getCommentId(),"p_expected_version",c.version));}
 private void change(String rpc,JSONObject args){Api.run(()->RpcClient.call(rpc,args),new Api.Callback<Object>(){public void success(Object r){if(binding!=null)listen();}public void error(String e){toast(e);}});}
 private void toast(String e){if(isAdded())Toast.makeText(requireContext(),e,Toast.LENGTH_LONG).show();}
 @Override public void onDestroyView(){if(observer!=null)observer.remove();binding=null;super.onDestroyView();}
}