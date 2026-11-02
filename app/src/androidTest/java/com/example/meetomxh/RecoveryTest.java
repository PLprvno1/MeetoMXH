package com.example.meetomxh;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.meetomxh.supabase.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.UUID;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class RecoveryTest {
 @Test public void actualEmailPkceRecoveryAndPasswordLogin()throws Exception{
  SupabaseCutoverTest helper=new SupabaseCutoverTest();Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  assertNotNull(SessionManager.getInstance().getCurrentUser());String actor=SessionManager.getInstance().uid(),email=SessionManager.getInstance().getCurrentUser().getEmail(),password="Reset!08-"+UUID.randomUUID();
  helper.launch(ForgotPasswordActivity.class);onView(withHint("Email")).perform(replaceText(email),closeSoftKeyboard());onView(withText("Gửi email khôi phục")).perform(click());
  helper.callback(helper.mailLink(email,"recovery"));helper.waitFor("reset deep link returns reset form",()->helper.activity() instanceof ForgotPasswordActivity&&helper.activity().getIntent().getBooleanExtra("reset",false));
  onView(withHint("Mật khẩu mới")).perform(replaceText(password),closeSoftKeyboard());onView(withText("Lưu mật khẩu mới")).perform(click());helper.screen(LoginActivity.class);assertNull(SessionManager.getInstance().getCurrentUser());
  onView(withId(R.id.etEmail)).perform(replaceText(email),closeSoftKeyboard());onView(withId(R.id.etPassword)).perform(replaceText(password),closeSoftKeyboard());onView(withId(R.id.btnLogin)).perform(click());helper.screen(MainActivity.class);assertEquals(actor,SessionManager.getInstance().uid());
  try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"recovery-results.json"))){out.write(Api.obj("mailpitRealRecovery",true,"PKCE",true,"resetUi",true,"logoutAfterReset",true,"newPasswordUiLogin",true).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
}
