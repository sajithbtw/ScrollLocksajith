package com.sajith.scrolllock.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.example.R;
import com.sajith.scrolllock.ScrollLockApp;
import com.sajith.scrolllock.ui.MainActivity;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Local-only sinkhole VpnService for Deep Focus Mode.
 *
 * When Deep Focus internet blocking is active, routes ONLY non-essential distracting apps
 * resolved via DeepFocusController.resolveBlockedInternetPackages() into a local null interface
 * (addAllowedApplication). Core OS phone calls, SMS, alarms, keyboard, Play Services,
 * ScrollLock itself, and user-whitelisted exception apps bypass the VPN completely.
 *
 * Strictly on-device: connects to zero external VPN servers and collects zero traffic.
 */
public class DeepFocusVpnService extends VpnService {

    public static final String ACTION_START = "com.sajith.scrolllock.action.DEEP_FOCUS_VPN_START";
    public static final String ACTION_UPDATE = "com.sajith.scrolllock.action.DEEP_FOCUS_VPN_UPDATE";
    public static final String ACTION_STOP = "com.sajith.scrolllock.action.DEEP_FOCUS_VPN_STOP";

    private static final int NOTIFICATION_ID_VPN = 1005;

    private static volatile boolean tunnelActive = false;

    private ParcelFileDescriptor vpnInterface;
    private Thread drainThread;

    public static boolean isTunnelActive() {
        return tunnelActive;
    }

    public static void startOrUpdate(Context context) {
        Context appContext = context.getApplicationContext();
        try {
            if (VpnService.prepare(appContext) != null) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        Intent intent = new Intent(appContext, DeepFocusVpnService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(appContext, intent);
    }

    public static void stop(Context context) {
        Context appContext = context.getApplicationContext();
        Intent intent = new Intent(appContext, DeepFocusVpnService.class);
        intent.setAction(ACTION_STOP);
        try {
            appContext.startService(intent);
        } catch (Exception ignored) {
            appContext.stopService(new Intent(appContext, DeepFocusVpnService.class));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            teardownTunnel();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat();
        try {
            if (VpnService.prepare(this) != null) {
                teardownTunnel();
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Exception e) {
            teardownTunnel();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        establishSinkholeTunnel();
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        teardownTunnel();
        stopForegroundCompat();
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        teardownTunnel();
        super.onDestroy();
    }

    private synchronized void establishSinkholeTunnel() {
        teardownTunnel();

        List<String> blockedPackages = DeepFocusController.resolveBlockedInternetPackages(this);
        if (blockedPackages.isEmpty()) {
            // Never establish an empty tunnel because without addAllowedApplication Android routes all apps
            stopForegroundCompat();
            stopSelf();
            return;
        }

        Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name) + " Deep Focus Firewall");
        builder.setMtu(1500);
        builder.addAddress("10.0.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        try {
            builder.addAddress("fd00:1::2", 128);
            builder.addRoute("::", 0);
        } catch (Exception ignored) {
        }

        int addedCount = 0;
        PackageManager pm = getPackageManager();
        for (String pkg : blockedPackages) {
            try {
                // Verify package is currently installed before adding to VPN allowlist
                pm.getPackageInfo(pkg, 0);
                builder.addAllowedApplication(pkg);
                addedCount++;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        if (addedCount == 0) {
            stopForegroundCompat();
            stopSelf();
            return;
        }

        try {
            vpnInterface = builder.establish();
        } catch (Exception e) {
            vpnInterface = null;
        }

        if (vpnInterface == null) {
            tunnelActive = false;
            stopForegroundCompat();
            stopSelf();
            return;
        }

        tunnelActive = true;
        final ParcelFileDescriptor pfd = vpnInterface;
        drainThread = new Thread(() -> {
            byte[] buffer = new byte[32767];
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
                while (!Thread.currentThread().isInterrupted() && tunnelActive) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    // Drop packets silently in local memory—zero remote servers
                }
            } catch (IOException ignored) {
            }
        }, "ScrollLock-DeepFocusVpnDrain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    private synchronized void teardownTunnel() {
        tunnelActive = false;
        if (drainThread != null) {
            drainThread.interrupt();
            drainThread = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {
            }
            vpnInterface = null;
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildVpnNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID_VPN,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID_VPN, notification);
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
    }

    private Notification buildVpnNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.putExtra(MainActivity.EXTRA_TARGET_TAB, R.id.nav_focus);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 41, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, ScrollLockApp.CHANNEL_TIMER_ID)
                .setSmallIcon(R.drawable.ic_shield_check)
                .setContentTitle(getString(R.string.deep_focus_vpn_notif_title))
                .setContentText(getString(R.string.deep_focus_vpn_notif_body))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
