package com.android.launcher3.uioverrides;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Local-test prototype: bubbles an arbitrary app through the public
 * Notification.BubbleMetadata API instead of the privileged
 * IBubbles.showAppBubble path (which SystemUI rejects without
 * MANAGE_ACTIVITY_TASKS — see issue #6802).
 *
 * Posts a bubble notification per app: a dynamic shortcut (owned by
 * Lawnchair, required for bubbles on API 30+) whose intent launches the
 * target app, plus a notification carrying BubbleMetadata for that
 * shortcut. With the device's bubble-anything flag on, SystemUI should
 * surface the app in the real bubble bar.
 */
public final class BubbleNotificationPrototype {

    private static final String TAG = "LcBubbleProto";
    private static final String CHANNEL_ID = "lc_app_bubbles_v2";
    private static final String SHORTCUT_PREFIX = "lc_bubble_";

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

            // Bubbles on notifications require a shortcut owned by the
            // posting app (API 30+): one dynamic shortcut per bubbled app.
            final String shortcutId = SHORTCUT_PREFIX + pkg;
            final ShortcutManager sm = context.getSystemService(ShortcutManager.class);
            if (sm != null) {
                final List<ShortcutInfo> stale = sm.getDynamicShortcuts().stream()
                        .filter(s -> s.getId().startsWith(SHORTCUT_PREFIX))
                        .collect(Collectors.toList());
                if (!stale.isEmpty()) {
                    sm.removeDynamicShortcuts(
                            stale.stream().map(ShortcutInfo::getId).collect(Collectors.toList()));
                }
                final ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId)
                        .setShortLabel(label)
                        .setIntent(launch)
                        .setIcon(appIcon)
                        .build();
                final boolean pushed = sm.addDynamicShortcuts(Collections.singletonList(shortcut));
                Log.d(TAG, "dynamic shortcut for " + pkg + " pushed=" + pushed);
            }

            final NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            final NotificationChannel channel =
                    new NotificationChannel(CHANNEL_ID, "App bubbles", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setAllowBubbles(true);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);

            // SystemUI requires bubble PendingIntents to be MUTABLE
            // (NMS.checkDisqualifyingFeatures rejects immutable ones).
            final PendingIntent pi = PendingIntent.getActivity(
                    context,
                    pkg.hashCode(),
                    launch,
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            // No suppressNotification: the most compatible path — the
            // notification posts silently (IMPORTANCE_MIN) and SystemUI
            // derives the bubble from its BubbleMetadata.
            final Notification.BubbleMetadata meta =
                    new Notification.BubbleMetadata.Builder(pi, appIcon).build();

            final Notification notification =
                    new Notification.Builder(context, CHANNEL_ID)
                            .setSmallIcon(appIcon)
                            .setContentTitle(label)
                            .setShortcutId(shortcutId)
                            .setBubbleMetadata(meta)
                            .build();

            nm.notify(pkg, shortcutId.hashCode(), notification);
            // Dynamic shortcut publication is async; NMS rejects bubbles
            // whose shortcut hasn't landed yet ("invalid shortcut"). Re-post
            // once after a beat so the bubble forms on the update.
            new android.os.Handler(context.getMainLooper()).postDelayed(() -> {
                try {
                    nm.notify(pkg, shortcutId.hashCode(), notification);
                    Log.d(TAG, "re-posted bubble notification for " + pkg);
                } catch (Exception e) {
                    Log.e(TAG, "re-post failed for " + pkg, e);
                }
            }, 750L);
            Log.d(TAG, "posted bubble notification for " + pkg + " user=" + user
                    + " notificationsEnabled=" + nm.areNotificationsEnabled()
                    + " bubblesAllowed=" + channel.canBubble());
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
