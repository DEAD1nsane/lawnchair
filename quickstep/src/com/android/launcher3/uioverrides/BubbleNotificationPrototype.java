package com.android.launcher3.uioverrides;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

/**
 * Local-test prototype v6: bubble an arbitrary app via the public
 * Notification.BubbleMetadata API without any shortcut.
 *
 * On devices with the wm.shell bubble-anything flag enabled (Pixel,
 * Android 16/17), SystemUI is expected to accept shortcut-free bubble
 * notifications carrying a mutable PendingIntent to the target app.
 * Earlier variants attached dynamic shortcuts, but ShortcutService
 * never registered them (dumpsys shortcut showed no records for the
 * package) and NMS logged "added an invalid shortcut" on every post,
 * so the shortcut machinery is dropped here.
 */
public final class BubbleNotificationPrototype {

    private static final String TAG = "LcBubbleProto";
    private static final String CHANNEL_ID = "lc_app_bubbles_v2";

    private BubbleNotificationPrototype() {}

    public static void post(Context context, Intent intent, UserHandle user) {
        if (Build.VERSION.SDK_INT < 30) {
            Log.w(TAG, "bubble prototype requires API 30+ (got " + Build.VERSION.SDK_INT + ")");
            return;
        }
        try {
            final String pkg = intent.getPackage() != null
                    ? intent.getPackage()
                    : (intent.getComponent() != null ? intent.getComponent().getPackageName() : null);
            if (pkg == null) {
                Log.w(TAG, "no package in intent: " + intent);
                return;
            }
            final PackageManager pm = context.getPackageManager();
            final Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) {
                Log.w(TAG, "no launch intent for " + pkg);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            final CharSequence label = pm.getApplicationLabel(appInfo);
            final Icon appIcon = iconOf(pm, appInfo);

            final NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "App bubbles", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);

            // SystemUI requires bubble PendingIntents to be MUTABLE
            // (NMS.checkDisqualifyingFeatures rejects immutable ones).
            final PendingIntent pi = PendingIntent.getActivity(
                    context,
                    pkg.hashCode(),
                    launch,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            // No shortcut: NMS only validates shortcuts when setShortcutId
            // is present, and ShortcutService never registered ours.
            final Notification.BubbleMetadata meta =
                    new Notification.BubbleMetadata.Builder(pi, appIcon).build();

            final Notification notification =
                    new Notification.Builder(context, CHANNEL_ID)
                            .setSmallIcon(appIcon)
                            .setContentTitle(label)
                            .setBubbleMetadata(meta)
                            .build();

            nm.notify(pkg, pkg.hashCode(), notification);
            // Re-post once after a beat: bubble processing can miss the
            // first post while channel state settles.
            new Handler(context.getMainLooper()).postDelayed(() -> {
                try {
                    nm.notify(pkg, pkg.hashCode(), notification);
                    Log.d(TAG, "re-posted bubble notification for " + pkg);
                } catch (Exception e) {
                    Log.e(TAG, "re-post failed for " + pkg, e);
                }
            }, 750L);
            Log.d(TAG, "posted bubble notification for " + pkg + " user=" + user
                    + " notificationsEnabled=" + nm.areNotificationsEnabled()
                    + " channelCanBubble=" + channel.canBubble()
                    + (Build.VERSION.SDK_INT >= 31 ? " bubblesEnabled=" + nm.areBubblesEnabled() : ""));
        } catch (Exception e) {
            Log.e(TAG, "failed to post bubble notification", e);
        }
    }

    private static Icon iconOf(PackageManager pm, ApplicationInfo info) {
        try {
            final Drawable d = pm.getApplicationIcon(info);
            final int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 192;
            final int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 192;
            final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return Icon.createWithAdaptiveBitmap(bmp);
        } catch (Exception e) {
            Log.w(TAG, "icon load failed for " + info.packageName, e);
            return Icon.createWithResource(info.packageName, info.icon);
        }
    }
}
