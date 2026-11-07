package com.example.meetomxh.storage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.example.meetomxh.supabase.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import okhttp3.*;

/** User-triggered authenticated download. The only deleted URI is this operation's partial row. */
public final class PostImageDownload {
    private static void readable(String postId,String reference)throws Exception{
        JSONObject post=RpcClient.object("get_post",Api.obj("p_post_id",postId));
        if(post.optBoolean("is_reel"))throw new IOException("Not a post image");
        JSONArray media=post.optJSONArray("media");String id=Dto.assetId(reference);
        if(media!=null)for(int i=0;i<media.length();i++){JSONObject row=media.getJSONObject(i);
            if(id.equals(row.optString("asset_id"))&&row.optString("mime_type").startsWith("image/"))return;
        }
        throw new ErrorMapper.HttpError(404,"media_unavailable");
    }
    public static String save(ContentResolver resolver,String postId,String reference)throws Exception{
        readable(postId,reference);JSONObject asset=SupabaseStorage.info(reference);String mime=asset.getString("mime_type");
        String ext="image/jpeg".equals(mime)?"jpg":"image/png".equals(mime)?"png":"image/webp".equals(mime)?"webp":null;
        if(ext==null)throw new IOException("Unsupported image type");
        SessionManager sessions=SessionManager.getInstance();String token=sessions.access(false);
        OkHttpClient client=SupabaseHttpClient.CLIENT.newBuilder().followRedirects(false).followSslRedirects(false).build();
        Request request=new Request.Builder().url(SupabaseStorage.url(asset)).header("apikey",SupabaseConfig.KEY).header("Authorization","Bearer "+token).header("Cache-Control","no-store").build();
        Call call=client.newCall(request);Api.attach(call);
        try(Response response=call.execute()){
            if(!response.isSuccessful())throw ErrorMapper.httpError(response.code(),response.peekBody(4096).string());
            if(response.body()==null||response.body().contentLength()>50L*1024*1024)throw new IOException("Invalid image size");
            String contentType=response.header("Content-Type","").split(";")[0].trim();
            if(!mime.equalsIgnoreCase(contentType))throw new IOException("Unexpected image content type");
            String name="Meeto_"+UUID.randomUUID()+"."+ext;ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,mime);
            if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/Meeto");values.put(MediaStore.Images.Media.IS_PENDING,1);}
            else{
                File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"Meeto");
                if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create image directory");
                values.put(MediaStore.Images.Media.DATA,new File(folder,name).getAbsolutePath());
            }
            Uri destination=null;boolean complete=false;
            try{
                Api.checkCancelled();destination=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                if(destination==null)throw new IOException("Cannot create gallery image");
                try(InputStream input=response.body().byteStream();OutputStream output=resolver.openOutputStream(destination)){
                    if(output==null)throw new IOException("Cannot write image");byte[] buffer=new byte[32768];long total=0;int count;
                    while((count=input.read(buffer))!=-1){Api.checkCancelled();total+=count;if(total>50L*1024*1024)throw new IOException("Image too large");output.write(buffer,0,count);}
                    if(total==0)throw new IOException("Empty image");output.flush();
                }
                // Recheck current post/media ACL before publishing a newly downloaded gallery item.
                readable(postId,reference);SupabaseStorage.info(reference);Api.checkCancelled();
                if(Build.VERSION.SDK_INT>=29){ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);if(resolver.update(destination,ready,null,null)!=1)throw new IOException("Cannot publish gallery image");}
                complete=true;return name;
            }finally{if(!complete&&destination!=null)try{resolver.delete(destination,null,null);}catch(Exception ignored){}}
        }
    }
    private PostImageDownload(){}
}
