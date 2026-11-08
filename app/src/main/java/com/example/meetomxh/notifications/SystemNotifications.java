package com.example.meetomxh.notifications;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.example.meetomxh.MeetoApplication;
import com.example.meetomxh.NotificationsActivity;
import com.example.meetomxh.R;
import com.example.meetomxh.models.NotificationItem;
import com.example.meetomxh.repositories.NotificationRepository;
import com.example.meetomxh.supabase.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android notification tray delivery while the Supabase client process is running. */
public final class SystemNotifications {
    public static final String MESSAGES="meeto_messages",SOCIAL="meeto_social";
    public static final int PERMISSION_REQUEST=7401;
    private static Observer<Snapshot> observer;
    private static final NotificationArrival arrivals=new NotificationArrival();
    private static long generation;
    private static Context context;
    private static boolean fetchFailureLogged;
    private static final class Snapshot {
        final JSONArray rows;final JSONObject settings;
        Snapshot(JSONArray rows,JSONObject settings){this.rows=rows;this.settings=settings;}
    }
    private SystemNotifications(){}

    public static void initialize(Context c){
        context=c.getApplicationContext();channels(context);
        SessionManager.getInstance().identityListeners.add(()->Api.MAIN.post(()->{
            stop();cancelAll();start();
        }));
        start();
    }
    private static void start(){
        String uid=SessionManager.getInstance().uid();if(observer!=null||uid.isEmpty())return;
        long token=++generation;
        observer=new Observer<>(SystemNotifications::fetchSnapshot,new Api.Callback<Snapshot>(){
            public void success(Snapshot snapshot){
                if(token!=generation||!uid.equals(SessionManager.getInstance().uid()))return;
                fetchFailureLogged=false;
                JSONArray rows=snapshot.rows;java.util.Set<String> active=activeIds();
                boolean baseline=arrivals.isInitialized();
                for(int i=0;i<rows.length();i++){
                    JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                    NotificationItem item=new NotificationItem(row);
                    boolean fresh=arrivals.accept(item.id,Dto.time(item.createdAt));
                    if(baseline&&(fresh||active.contains(item.id))&&permitted(item,snapshot.settings))show(context,uid,item);
                }
                arrivals.initialized();
                reconcileReadNotifications(snapshot);
            }
            public void error(String ignored){
                if(!fetchFailureLogged){android.util.Log.w("MeetoNotifications","stage=fetch_failed; retry_pending");fetchFailureLogged=true;}
            }
        },5000);
    }
    private static Snapshot fetchSnapshot()throws Exception{
        return new Snapshot(NotificationRepository.page(true,null,null,NotificationRepository.MAX_PAGE_SIZE),NotificationRepository.settings());
    }
    private static void stop(){generation++;if(observer!=null){observer.remove();observer=null;}arrivals.clear();}
    public static void refresh(Context c){if(context==null)return;start();if(observer!=null)observer.refresh();}
    public static void channels(Context c){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel chat=new NotificationChannel(MESSAGES,"Tin nhắn",NotificationManager.IMPORTANCE_HIGH);
            chat.setDescription("Tin nhắn riêng và tin nhắn nhóm");chat.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
            NotificationChannel social=new NotificationChannel(SOCIAL,"Hoạt động và lời mời",NotificationManager.IMPORTANCE_DEFAULT);
            social.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
            NotificationManager manager=c.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(chat);manager.createNotificationChannel(social);
        }
    }
    public static void requestPermission(AppCompatActivity activity){
        android.content.SharedPreferences prefs=activity.getSharedPreferences("android_notifications",Context.MODE_PRIVATE);
        if(Build.VERSION.SDK_INT>=33&&!allowed(activity)&&!prefs.getBoolean("asked_permission",false)){
            prefs.edit().putBoolean("asked_permission",true).apply();
            ActivityCompat.requestPermissions(activity,new String[]{Manifest.permission.POST_NOTIFICATIONS},PERMISSION_REQUEST);
        }
        refresh(activity);
    }
    public static boolean allowed(Context c){
        return (Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            &&NotificationManagerCompat.from(c).areNotificationsEnabled();
    }
    public static void show(Context c,String uid,NotificationItem item){
        if(!allowed(c)||!uid.equals(SessionManager.getInstance().uid())||!item.unread||!item.targetAvailable||NotificationItem.isRetired(item.kind))return;
        if(MeetoApplication.get().isViewingConversation(item.targetType,item.targetId))return;
        JSONObject route=Api.obj("id",item.id,"kind",item.kind,"target_type",item.targetType,"target_id",item.targetId,
            "target_context_id",item.targetContextId,"target_available",item.targetAvailable);
        Intent intent=new Intent(c,NotificationsActivity.class).putExtra("push_notification_id",item.id)
            .putExtra("push_recipient",uid).putExtra("push_row",route.toString())
            .setData(android.net.Uri.parse("meeto-internal://notification/"+uid+"/"+item.id));
        PendingIntent pending=PendingIntent.getActivity(c,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        NotificationPresentation content=NotificationPresentation.of(item);
        NotificationCompat.Builder builder=new NotificationCompat.Builder(c,"message".equals(item.kind)?MESSAGES:SOCIAL)
            .setSmallIcon(R.drawable.ic_notification_small).setContentTitle(content.title).setContentText(content.body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(content.body)).setContentIntent(pending).setAutoCancel(true)
            .setCategory("message".equals(item.kind)?NotificationCompat.CATEGORY_MESSAGE:NotificationCompat.CATEGORY_SOCIAL)
            .setPriority("message".equals(item.kind)?NotificationCompat.PRIORITY_HIGH:NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(new NotificationCompat.Builder(c,SOCIAL).setSmallIcon(R.drawable.ic_notification_small)
                .setContentTitle("Meeto").setContentText("Bạn có thông báo mới").build());
        try{NotificationManagerCompat.from(c).notify("meeto:"+uid+":"+item.id,0,builder.build());}
        catch(SecurityException ignored){}
    }
    private static boolean permitted(NotificationItem item,JSONObject settings){
        JSONObject preferences=settings.optJSONObject("preferences");
        return settings.optBoolean("enabled",true)&&(preferences==null||preferences.optBoolean(item.kind,true))
            &&item.unread&&item.targetAvailable&&!MeetoApplication.get().isViewingConversation(item.targetType,item.targetId);
    }
    private static java.util.Set<String> activeIds(){
        java.util.Set<String> ids=new java.util.HashSet<>();
        for(android.service.notification.StatusBarNotification active:context.getSystemService(NotificationManager.class).getActiveNotifications()){
            String tag=active.getTag();if(tag!=null&&tag.startsWith("meeto:"))ids.add(tag.substring(tag.lastIndexOf(':')+1));
        }return ids;
    }
    private static void reconcileReadNotifications(Snapshot snapshot){
        JSONArray rows=snapshot.rows;
        java.util.Map<String,Boolean> unread=new java.util.HashMap<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null){NotificationItem item=new NotificationItem(row);unread.put(item.id,permitted(item,snapshot.settings));}}
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        for(android.service.notification.StatusBarNotification active:manager.getActiveNotifications()){
            String tag=active.getTag();if(tag!=null&&tag.startsWith("meeto:")){
                String id=tag.substring(tag.lastIndexOf(':')+1);
                if(!snapshot.settings.optBoolean("enabled",true)||Boolean.FALSE.equals(unread.get(id))
                    ||(!unread.containsKey(id)&&rows.length()<NotificationRepository.MAX_PAGE_SIZE))manager.cancel(tag,active.getId());
            }
        }
    }
    private static void cancelAll(){
        if(Build.VERSION.SDK_INT>=23){NotificationManager manager=context.getSystemService(NotificationManager.class);
            for(android.service.notification.StatusBarNotification active:manager.getActiveNotifications())
                if(active.getTag()!=null&&active.getTag().startsWith("meeto:"))manager.cancel(active.getTag(),active.getId());}
    }
}
