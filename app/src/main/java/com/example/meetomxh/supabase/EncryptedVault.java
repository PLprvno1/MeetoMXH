package com.example.meetomxh.supabase;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.io.*;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
final class EncryptedVault {
 private final File file;
 EncryptedVault(Context context){file=new File(context.getNoBackupFilesDir(),"session.enc");}
 private javax.crypto.SecretKey key() throws Exception {
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(!store.containsAlias("meeto.session")){KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder("meeto.session",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
  return (javax.crypto.SecretKey)store.getKey("meeto.session",null);
 }
 synchronized JSONObject read(){try{byte[] b;try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] chunk=new byte[4096];int n;while((n=input.read(chunk))!=-1)output.write(chunk,0,n);b=output.toByteArray();}Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,java.util.Arrays.copyOfRange(b,0,12)));return new JSONObject(new String(c.doFinal(b,12,b.length-12),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){return new JSONObject();}}
 synchronized void write(JSONObject data) throws Exception {
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());File temp=new File(file.getParentFile(),"session.tmp");
  try(FileOutputStream out=new FileOutputStream(temp)){out.write(c.getIV());out.write(c.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));out.getFD().sync();}
  if(!temp.renameTo(file))throw new IOException("Không lưu được phiên đăng nhập");
 }
}
