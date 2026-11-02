package com.example.meetomxh.storage;
import androidx.annotation.NonNull;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.*;
import com.bumptech.glide.signature.ObjectKey;
import com.example.meetomxh.supabase.*;
import java.io.*;
import okhttp3.*;
public final class PrivateAssetLoader implements ModelLoader<String,InputStream> {
 public boolean handles(@NonNull String value){return value.startsWith("asset://");}
 public LoadData<InputStream> buildLoadData(@NonNull String model,int width,int height,@NonNull Options options){return new LoadData<>(new ObjectKey(SessionManager.getInstance().uid()+":"+model+":"+System.nanoTime()),new Fetch(model));}
 public static final class Factory implements ModelLoaderFactory<String,InputStream>{public ModelLoader<String,InputStream> build(@NonNull MultiModelLoaderFactory unused){return new PrivateAssetLoader();}public void teardown(){}}
 private static final class Fetch implements DataFetcher<InputStream>{final String asset;volatile boolean cancelled;Call call;Response response;Fetch(String asset){this.asset=asset;}
  public void loadData(@NonNull Priority priority,@NonNull DataCallback<? super InputStream> callback){long epoch=SessionManager.getInstance().generation();Api.IO.execute(()->{try{String url=SupabaseStorage.url(SupabaseStorage.info(asset));String token=SessionManager.getInstance().access(false);call=SupabaseHttpClient.CLIENT.newCall(new Request.Builder().url(url).header("apikey",SupabaseConfig.KEY).header("Authorization","Bearer "+token).build());if(cancelled)return;response=call.execute();if(!response.isSuccessful()||epoch!=SessionManager.getInstance().generation())throw new IOException("Media unavailable");if(!cancelled)callback.onDataReady(response.body().byteStream());}catch(Exception e){if(!cancelled)callback.onLoadFailed(new IOException("Media unavailable"));}});}
  public void cleanup(){if(response!=null)response.close();}public void cancel(){cancelled=true;if(call!=null)call.cancel();}public Class<InputStream> getDataClass(){return InputStream.class;}public DataSource getDataSource(){return DataSource.REMOTE;}
 }
}
