package com.example.meetomxh.ui;

import com.example.meetomxh.storage.SupabaseStorage;
import com.example.meetomxh.supabase.*;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** Exact file-content comparison only; never conflates two visually similar photographs. */
final class ExactPhotoFingerprint {
    private ExactPhotoFingerprint(){}
    static String read(String assetId,long expectedBytes) throws Exception {
        if(expectedBytes<=0||expectedBytes>50L*1024*1024)throw new IOException("Invalid image size");
        Api.checkCancelled();
        String url=SupabaseStorage.url(SupabaseStorage.info(Dto.asset(assetId)));
        String token=SessionManager.getInstance().access(false);
        Call call=SupabaseHttpClient.CLIENT.newCall(new Request.Builder().url(url)
                .header("apikey",SupabaseConfig.KEY).header("Authorization","Bearer "+token).build());
        Api.attach(call);
        try(Response response=call.execute()){
            if(!response.isSuccessful()||response.body()==null)throw new IOException("Image unavailable");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(InputStream stream=response.body().byteStream()){
                byte[] buffer=new byte[64*1024];int count;
                while((count=stream.read(buffer))!=-1){Api.checkCancelled();total+=count;if(total>expectedBytes)throw new IOException("Image size changed");digest.update(buffer,0,count);}
            }
            Api.checkCancelled();if(total!=expectedBytes)throw new IOException("Incomplete image");
            StringBuilder hex=new StringBuilder(64);for(byte b:digest.digest()){hex.append(Character.forDigit((b>>4)&15,16));hex.append(Character.forDigit(b&15,16));}return hex.toString();
        }
    }
}
