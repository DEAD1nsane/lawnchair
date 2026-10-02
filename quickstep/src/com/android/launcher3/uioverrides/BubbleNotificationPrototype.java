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
 * Local-test prototype v7: bubble an arbitrary app through the public
 * *conversation* bubble API.
 *
 * Pixels only expose the per-channel "Bubbles" user control for
 * conversation channels (setConversationId + MessagingStyle) — the path
 * chat apps use. Plain channels never get that control (verified on the
 * test device: the plain channel exists in settings with no Bubbles row,
 * and the app-side setAllowBubbles request is normalized away to
 * UNDEFINED). So each bubbled app gets its own conversation channel;
 * once the user sets Bubbles -> All bubbles on the conversation, the
 * mutable-PendingIntent BubbleMetadata forms a real SystemUI bubble.
 *
 * The channel is IMPORTANCE_HIGH so posting gives immediate visible
 * feedback (heads-up) even before bubbles are enabled.
 */
public final class BubbleNotificationPrototype {

    private static final String TAG = "LcBubbleProto";
    private static final String PARENT_CHANNEL_ID = "lc_app_bubbles_parent";
    private static final String CONV_PREFIX = "lc_bubble_conv_";

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

            // Parent channel — setConversationId requires one.
            final NotificationChannel parent = new NotificationChannel(
                    PARENT_CHANNEL_ID, "App bubbles", NotificationManager.IMPORTANCE_DEFAULT);
            parent.setShowBadge(false);
            nm.createNotificationChannel(parent);

            // Per-app conversation channel: the only channel type with a
            // user-facing "Bubbles" control on Pixels.
            final String convChannelId = CONV_PREFIX + pkg;
            final NotificationChannel conv = new NotificationChannel(
                    convChannelId, label + " bubbles", NotificationManager.IMPORTANCE_HIGH);
            conv.setConversationId(PARENT_CHANNEL_ID, "lc_" + pkg);
            conv.setShowBadge(false);
            nm.createNotificationChannel(conv);

            // SystemUI requires bubble PendingIntents to be MUTABLE.
            final PendingIntent pi = PendingIntent.getActivity(
                    context,
                    pkg.hashCode(),
                    launch,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            final Notification.BubbleMetadata meta =
                    new Notification.BubbleMetadata.Builder(pi, appIcon).build();

            final Notification.Person appPerson = new Notification.Person.Builder()
                    .setName(label)
                    .setIcon(appIcon)
                    .build();
            final Notification.MessagingStyle style =
                    new Notification.MessagingStyle(appPerson)
                            .addMessage(label, System.currentTimeMillis(), appPerson);

            final Notification notification =
                    new Notification.Builder(context, convChannelId)
                            .setSmallIcon(appIcon)
                            .setContentTitle(label)
                            .setStyle(style)
                            .setCategory(Notification.CATEGORY_CONVERSATION)
                            .setBubbleMetadata(meta)
                            .build();

            nm.notify(pkg, pkg.hashCode(), notification);
            // Re-post once after a beat while channel state settles.
            new Handler(context.getMainLooper()).postDelayed(() -> {
                try {
                    nm.notify(pkg, pkg.hashCode(), notification);
                    Log.d(TAG, "re-posted conv bubble notification for " + pkg);
                } catch (Exception e) {
                    Log.e(TAG, "re-post failed for " + pkg, e);
                }
            }, 750L);
            Log.d(TAG, "posted conv bubble notification for " + pkg + " user=" + user
                    + " notificationsEnabled=" + nm.areNotificationsEnabled()
                    + " convCanBubble=" + conv.canBubble()
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
