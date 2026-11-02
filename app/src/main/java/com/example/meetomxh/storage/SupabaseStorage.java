package com.example.meetomxh.storage;
import android.content.ContentResolver;
import android.net.Uri;
import com.example.meetomxh.supabase.*;
import okhttp3.*;
import okio.BufferedSink;
import org.json.*;
import java.io.*;
public final class SupabaseStorage {
 public interface UploadListener {void onSuccess(String assetReference);void onFailure(String message);default void onProgress(int percent){}}
 public static final class Transfer {private volatile boolean cancelled;private volatile Call call;public void cancel(){cancelled=true;if(call!=null)call.cancel();}public boolean isCanceled(){return cancelled;}}
 public static Transfer upload(ContentResolver resolver,Uri uri,String folder,String owner,UploadListener listener){
  Transfer transfer=new Transfer();long epoch=SessionManager.getInstance().generation();
  Api.IO.execute(()->{try{
   String mime=resolver.getType(uri);if(mime==null)throw new IOException("MIME unavailable");
   String purpose=folder.startsWith("chats/")?"message":folder.contains("avatar")?"avatar":folder.contains("cover")?"cover":"post";
   String operation=folder+uri+mime;String request=RequestIds.get("upload",operation);
   JSONObject args=Api.obj("p_mime",mime,"p_client_request_id",request);String rpc;
   if(purpose.equals("message")){args.put("p_conversation_id",folder.substring(6));rpc="prepare_message_upload";}else{args.put("p_purpose",purpose);rpc="prepare_upload";}
   JSONObject intent=RpcClient.object(rpc,args);String token=SessionManager.getInstance().access(false);
   long size=-1;try(android.content.res.AssetFileDescriptor fd=resolver.openAssetFileDescriptor(uri,"r")){if(fd!=null)size=fd.getLength();}final long length=size;final String type=mime;
   RequestBody bytes=new RequestBody(){public MediaType contentType(){return MediaType.parse(type);}public long contentLength(){return length;}public void writeTo(BufferedSink sink)throws IOException{try(InputStream in=resolver.openInputStream(uri)){if(in==null)throw new IOException("Cannot read media");byte[] buf=new byte[32768];int n;long total=0;int last=-1;while((n=in.read(buf))!=-1){if(transfer.cancelled||epoch!=SessionManager.getInstance().generation())throw new IOException("Cancelled");sink.write(buf,0,n);total+=n;int percent=length>0?(int)Math.min(100,total*100/length):0;if(percent/5!=last/5){last=percent;Api.MAIN.post(()->listener.onProgress(percent));}}}}};
   Request upload=new Request.Builder().url(SupabaseConfig.URL+"/storage/v1/object/"+intent.getString("bucket_id")+"/"+intent.getString("object_path")).header("apikey",SupabaseConfig.KEY).header("Authorization","Bearer "+token).header("x-upsert","false").header("cache-control","0").post(bytes).build();
   if(transfer.cancelled)return;transfer.call=SupabaseHttpClient.CLIENT.newCall(upload);try(Response response=transfer.call.execute()){if(!response.isSuccessful()&&response.code()!=409)throw new ErrorMapper.HttpError(response.code(),"upload");}
   for(int i=0;i<120;i++){
    if(transfer.cancelled||epoch!=SessionManager.getInstance().generation())return;
    try{JSONObject ready=RpcClient.object("finalize_upload",Api.obj("p_asset_id",intent.getString("id")));if(ready.optString("state").equals("ready")){RequestIds.complete("upload",operation);String ref=Dto.asset(ready.getString("id"));Api.MAIN.post(()->{if(!transfer.cancelled&&epoch==SessionManager.getInstance().generation())listener.onSuccess(ref);});return;}}
    catch(ErrorMapper.HttpError e){if(e.status!=409)throw e;}
    Thread.sleep(1000);
   }
   Api.MAIN.post(()->{if(epoch==SessionManager.getInstance().generation())listener.onFailure("Tệp đang chờ xác minh. Hãy thử lại sau.");});
  }catch(Exception e){String error=ErrorMapper.message(e);Api.MAIN.post(()->{if(!transfer.cancelled&&epoch==SessionManager.getInstance().generation())listener.onFailure(error);});}});
  return transfer;
 }
 public static JSONObject info(String reference)throws Exception{String id=Dto.assetId(reference);java.util.UUID.fromString(id);JSONArray rows=(JSONArray)SupabaseHttpClient.authorized("GET","/rest/v1/media_assets?id=eq."+id+"&select=id,bucket_id,object_path,mime_type",null);if(rows.length()!=1)throw new ErrorMapper.HttpError(404,"media_unavailable");return rows.getJSONObject(0);}
 public static String url(JSONObject asset)throws Exception{return SupabaseConfig.URL+"/storage/v1/object/authenticated/"+asset.getString("bucket_id")+"/"+asset.getString("object_path");}
}