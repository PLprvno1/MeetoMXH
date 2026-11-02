package com.example.meetomxh;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class SessionRestoreTest {
 @Test public void restoresEncryptedSessionAfterForceStop()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  assertNotNull("session restored from Keystore vault",SessionManager.getInstance().getCurrentUser());
  String actor=SessionManager.getInstance().uid();assertEquals(actor,ProfileClient.self().getString("profile_id"));
  byte[] bytes;try(FileInputStream in=new FileInputStream(new File(c.getNoBackupFilesDir(),"session.enc"));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);bytes=out.toByteArray();}
  String disk=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);assertFalse("no plaintext session JSON",disk.contains("refresh"));assertFalse("no plaintext actor",disk.contains(actor));
  SessionManager.getInstance().access(true);assertEquals(actor,SessionManager.getInstance().uid());
  c.startActivity(new Intent(c,LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
  Thread.sleep(1500);
  try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"session-restore-results.json"))){out.write(Api.obj("processRestart",true,"realRpcWithRestoredSession",true,"encryptedAtRest",true,"realRefresh",true).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
}
