package com.example.meetomxh.supabase;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.*;
import org.json.JSONObject;
/** Private accounts never offer a public audience in the composer. */
public final class AudienceChoices {
 public interface Choice{void selected(String value,String label);}
 private AudienceChoices(){}
 public static String label(String value){return "public".equals(value)?"Công khai":"followers".equals(value)?"Người theo dõi":"friends".equals(value)?"Chỉ mình tôi (quyền cũ)":"Chỉ mình tôi";}
 public static void choose(Activity a,Choice callback){Api.run(ProfileClient::self,new Api.Callback<JSONObject>(){
  public void success(JSONObject p){JSONObject s=p.optJSONObject("settings");boolean restricted=s!=null&&s.optBoolean("account_private");String[] values=restricted?new String[]{"followers","private"}:new String[]{"public","followers","private"};String[] labels=new String[values.length];for(int i=0;i<values.length;i++)labels[i]=label(values[i]);new AlertDialog.Builder(a).setTitle("Ai có thể xem?").setItems(labels,(d,n)->callback.selected(values[n],labels[n])).show();}
  public void error(String e){Toast.makeText(a,e,Toast.LENGTH_LONG).show();}
 });}
 public static void spinner(Activity a,Spinner spinner){spinner(a,spinner,null);}
 public static void spinner(Activity a,Spinner spinner,String initial){spinner.setEnabled(false);Api.run(ProfileClient::self,new Api.Callback<JSONObject>(){
  public void success(JSONObject p){JSONObject s=p.optJSONObject("settings");String[] values=s!=null&&s.optBoolean("account_private")?new String[]{"followers","private"}:new String[]{"public","followers","private"};String[] labels=new String[values.length];for(int i=0;i<values.length;i++)labels[i]=label(values[i]);spinner.setTag(values);spinner.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,labels));if(initial!=null){String desired="friends".equals(initial)?"private":initial;int index=java.util.Arrays.asList(values).indexOf(desired);spinner.setSelection(index<0?0:index);}spinner.setEnabled(true);}
  public void error(String e){Toast.makeText(a,e,Toast.LENGTH_LONG).show();}
 });}
 public static String value(Spinner spinner){Object tag=spinner.getTag();if(!(tag instanceof String[]))return "private";String[] values=(String[])tag;int i=spinner.getSelectedItemPosition();return i>=0&&i<values.length?values[i]:"private";}
}
