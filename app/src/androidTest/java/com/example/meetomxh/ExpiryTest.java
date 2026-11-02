package com.example.meetomxh;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ExpiryTest {
 @Test public void expiredRealJwtRefreshAndRevokedRefreshClearsSession()throws Exception{
  SessionManager s=SessionManager.getInstance();RealtimeClient.get().stop();assertNotNull(s.getCurrentUser());String actor=s.uid(),token=s.access(true);
  String[] pieces=token.split("\\.");JSONObject claims=new JSONObject(new String(android.util.Base64.decode(pieces[1],android.util.Base64.URL_SAFE),java.nio.charset.StandardCharsets.UTF_8));long expiry=claims.getLong("exp"),now=System.currentTimeMillis()/1000;assertTrue("actual local short TTL",expiry-now<=65&&expiry>now);
  // Exercise the HTTP 401 retry branch with stale local expiry metadata. The signed JWT stays unchanged.
  s.pending("exp",String.valueOf(now+3600));long wait=(expiry+35)*1000-System.currentTimeMillis();assertTrue("bounded expiry wait",wait>0&&wait<105000);Thread.sleep(wait);
  try{SupabaseHttpClient.raw("POST","/rest/v1/rpc/get_my_profile",Api.obj(),token);fail("server must reject actually expired JWT");}catch(ErrorMapper.HttpError e){assertEquals(401,e.status);}
  assertEquals("401 transparently refreshes real session",actor,ProfileClient.self().getString("profile_id"));assertFalse("new signed token",token.equals(s.access(false)));
  SupabaseHttpClient.raw("POST","/auth/v1/logout",Api.obj(),s.access(false));
  try{s.access(true);fail("revoked refresh must fail");}catch(ErrorMapper.HttpError e){assertTrue(e.status==400||e.status==401);}
  assertNull("invalid refresh clears local identity",s.getCurrentUser());assertEquals("outbox cleared with session","",s.pending("message_outbox"));
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"expiry-results.json"))){out.write(Api.obj("actualGoTrueTtl60",true,"waitedPastServerTolerance",true,"server401",true,"automatic401Refresh",true,"revokedRefreshClearsIdentityAndOutbox",true).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
}
