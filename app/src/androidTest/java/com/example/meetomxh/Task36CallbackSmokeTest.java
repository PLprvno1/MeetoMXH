package com.example.meetomxh;
import android.content.Intent;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public final class Task36CallbackSmokeTest {
 @Test public void expiredConfirmationHasVisibleRecoveryInsteadOfBlankScreen(){
  android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,AuthCallbackActivity.class).setData(Uri.parse("meeto://auth/callback?error=access_denied")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  onView(withId(R.id.formStatus)).check(matches(withText(org.hamcrest.Matchers.containsString("hết hạn"))));
  onView(withText("Quay lại đăng nhập")).check(matches(isDisplayed()));
 }
 @Test public void videoControlsInflateWithSeekAndPlaybackButtons(){
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
   ContextThemeWrapper theme=new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),R.style.Theme_MeetoMXH);
   android.view.View root=LayoutInflater.from(theme).inflate(R.layout.activity_video_player,null,false);
   assertNotNull(root.findViewById(androidx.media3.ui.R.id.exo_play_pause));
   assertNotNull(root.findViewById(androidx.media3.ui.R.id.exo_progress));
   assertNotNull(root.findViewById(R.id.playerRetry));
  });
 }
}
