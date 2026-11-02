package com.example.meetomxh.supabase;
import android.app.Activity;
import android.widget.*;
import com.example.meetomxh.R;
public final class Forms {
 public static LinearLayout start(Activity activity,String title){activity.setContentView(R.layout.activity_form);((TextView)activity.findViewById(R.id.formTitle)).setText(title);return activity.findViewById(R.id.formFields);}
 public static EditText field(LinearLayout form,String hint,String value,int type){EditText e=new EditText(form.getContext());e.setHint(hint);e.setInputType(type);e.setText(value==null?"":value);e.setSingleLine(true);form.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
 public static Button button(LinearLayout form,String text,Runnable action){Button b=new Button(form.getContext());b.setText(text);b.setAllCaps(false);b.setOnClickListener(v->action.run());form.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
 public static void status(Activity a,String message){((TextView)a.findViewById(R.id.formStatus)).setText(message);}
}
