package com.example.meetomxh;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
/** Task 36 replaces the retired friendship-navigation scenario. No server mutations. */
@RunWith(AndroidJUnit4.class)
public class Task11CNavigationTest {
 @Test public void fiveSocialTabsAndConsentPickerRemainAvailable() {
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  c.startActivity(new Intent(c,MainActivity.class).putExtra(RoleResolutionActivity.EXTRA_ROLE_VERIFIED,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
  onView(withId(R.id.bottom_navigation)).check(matches(isDisplayed()));
  android.graphics.Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
  try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),"task36-before-nav.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(java.io.IOException e){throw new AssertionError(e);}finally{shot.recycle();}
  onView(withId(R.id.nav_profile)).perform(click());
  onView(withText("Bạn bè & Gợi ý")).check(doesNotExist());
  onView(withId(R.id.nav_messages)).perform(click());
  onView(withId(R.id.btnCreateChatGroup)).perform(click());
  onView(withText("Tạo nhóm chat")).perform(click());
  onView(withText("Tiếp tục")).check(matches(org.hamcrest.Matchers.not(isEnabled())));
  onView(withText("Hủy")).perform(click());
 }
}
