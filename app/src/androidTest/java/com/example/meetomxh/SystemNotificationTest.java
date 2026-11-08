package com.example.meetomxh;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.meetomxh.models.NotificationItem;
import com.example.meetomxh.notifications.SystemNotifications;
import com.example.meetomxh.supabase.SessionManager;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SystemNotificationTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String id=UUID.randomUUID().toString();
    private boolean hadPermission;
    @Before public void allowNotifications(){
        hadPermission=Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        if(Build.VERSION.SDK_INT>=33&&!hadPermission)InstrumentationRegistry.getInstrumentation().getUiAutomation()
            .grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        SystemNotifications.channels(context);
    }
    private NotificationItem item()throws Exception{
        return new NotificationItem(new JSONObject().put("id",id).put("kind","message").put("target_type","conversation")
            .put("target_id",UUID.randomUUID().toString()).put("target_available",true).put("actor_name","Minh")
            .put("conversation_kind","group").put("conversation_title","Nhóm kiểm tra").put("message_preview","Xin chào"));
    }
    private StatusBarNotification posted(){
        for(StatusBarNotification n:context.getSystemService(NotificationManager.class).getActiveNotifications())
            if(n.getTag()!=null&&n.getTag().endsWith(":"+id))return n;return null;
    }
    @Test public void groupMessageIsARealAndroidNotification()throws Exception{
        String uid=SessionManager.getInstance().uid();assertFalse("Signed-in emulator required",uid.isEmpty());
        SystemNotifications.show(context,uid,item());
        long deadline=System.currentTimeMillis()+3000;while(posted()==null&&System.currentTimeMillis()<deadline)Thread.sleep(50);
        StatusBarNotification active=posted();assertNotNull(active);Notification n=active.getNotification();
        assertEquals("Nhóm kiểm tra",n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Minh: Xin chào",n.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(Notification.VISIBILITY_PRIVATE,n.visibility);assertNotNull(n.contentIntent);
        assertEquals(SystemNotifications.MESSAGES,n.getChannelId());
    }
    @Test public void differentRecipientCannotDisplayNotification()throws Exception{
        SystemNotifications.show(context,UUID.randomUUID().toString(),item());assertNull(posted());
    }
    @After public void cleanup(){
        StatusBarNotification active=posted();if(active!=null)context.getSystemService(NotificationManager.class).cancel(active.getTag(),active.getId());
        // Keep granted permission for user testing; revoking it kills the instrumentation process.
    }
}
