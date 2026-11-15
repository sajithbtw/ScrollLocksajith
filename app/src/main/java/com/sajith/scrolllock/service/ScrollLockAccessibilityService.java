package com.sajith.scrolllock.service;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.sajith.scrolllock.data.local.AppRepository;
import com.sajith.scrolllock.data.local.PreferencesManager;
import com.sajith.scrolllock.data.model.FocusState;
import com.sajith.scrolllock.ui.FocusInterruptionActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * On-device Accessibility Guard enforcing:
 * 1. Instant Full-App Lock when opening user-selected distracting apps (during Focus or Always-Guard).
 * 2. Real-time Short-Form Feed Guard (Instagram Reels, YouTube Shorts, Facebook Reels,
 *    Snapchat Spotlight, TikTok) while keeping normal Feeds, DMs, Search, and horizontal video usable.
 *
 * Privacy guarantee:
 * - Strictly skips all password input nodes (isPassword() == true)
 * - Caps traversal to 180 nodes / depth 16
 * - Zero logging, zero storage, and zero network transmission of inspected UI nodes.
 */
public class ScrollLockAccessibilityService extends AccessibilityService {

    private static final int MAX_SNAPSHOT_NODES = 180;
    private static final int MAX_SNAPSHOT_DEPTH = 16;
    private static final long GATE_TRIGGER_COOLDOWN_MS = 1400L;
    private static final long INTERRUPTION_RECORD_COOLDOWN_MS = 5000L;

    private static volatile boolean serviceConnected = false;
    private static volatile long lastGateLaunchElapsedMs = 0L;

    private String lastTriggeredPkg = "";
    private String lastTriggeredMode = "";
    private long lastTriggerElapsedMs = 0L;
    private String lastRecordedPkg = "";
    private long lastRecordedElapsedMs = 0L;

    private PreferencesManager prefs;
    private AppRepository appRepository;

    public static boolean isServiceRunning() {
        return serviceConnected;
    }

    public static boolean wasGateLaunchedRecently(long withinMillis) {
        return (SystemClock.elapsedRealtime() - lastGateLaunchElapsedMs) < withinMillis;
    }

    public static boolean isEnabledInSettings(Context context) {
        if (serviceConnected) {
            return true;
        }
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (enabledServices == null || enabledServices.isEmpty()) {
                return false;
            }
            ComponentName expected = new ComponentName(context, ScrollLockAccessibilityService.class);
            String full = expected.flattenToString();
            String shortName = expected.flattenToShortString();
            for (String entry : enabledServices.split(":")) {
                if (entry.equalsIgnoreCase(full) || entry.equalsIgnoreCase(shortName)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        serviceConnected = true;
        prefs = new PreferencesManager(this);
        appRepository = new AppRepository(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (prefs == null) {
            prefs = new PreferencesManager(this);
        }
        if (appRepository == null) {
            appRepository = new AppRepository(this);
        }

        CharSequence eventPkgSeq = event.getPackageName();
        String pkg = eventPkgSeq != null ? eventPkgSeq.toString().trim() : "";
        AccessibilityNodeInfo root = null;
        try {
            if (pkg.isEmpty()) {
                root = getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    pkg = root.getPackageName().toString().trim();
                }
            }

            if (shouldIgnorePackage(pkg)) {
                return;
            }

            FocusSessionManager sessionManager = FocusSessionManager.getInstance(this);
            boolean focusRunning = sessionManager.getCurrentStateValue() == FocusState.RUNNING;
            boolean alwaysGuard = prefs.isAlwaysGuardAppsEnabled() && prefs.isWorkoutUnlockEnabled();

            // 1. Check Full-App Lock
            Set<String> guardedPackages = prefs.getSelectedPackages();
            if ((focusRunning || alwaysGuard)
                    && guardedPackages.contains(pkg)
                    && (focusRunning || !prefs.isAppTemporarilyUnlocked(pkg))) {
                triggerGateIfNotCoolingDown(
                        pkg,
                        FocusInterruptionActivity.MODE_APP_LOCK,
                        "",
                        false,
                        sessionManager
                );
                return;
            }

            // 2. Check Short-Form Reels / Shorts Guard (works both during Focus and 24/7 unless temporarily unlocked)
            Set<String> shortFormPackages = prefs.getShortFormBlockedPackages();
            if (!prefs.isShortFormBlockerEnabled() || !shortFormPackages.contains(pkg)) {
                return;
            }
            if (!focusRunning && prefs.isShortFormTemporarilyUnlocked(pkg)) {
                return;
            }

            // Fast path for dedicated standalone Reels/TikTok/Shorts apps
            if (ShortFormDetector.isDedicatedShortFormPackage(pkg.toLowerCase(Locale.US))) {
                ShortFormDetector.DetectionResult dedicated = ShortFormDetector.detect(pkg, null);
                if (dedicated.isDetected()) {
                    triggerGateIfNotCoolingDown(
                            pkg,
                            FocusInterruptionActivity.MODE_SHORT_FORM,
                            dedicated.getSurfaceLabel(),
                            false,
                            sessionManager
                    );
                }
                return;
            }

            if (root == null) {
                root = getRootInActiveWindow();
            }
            if (root == null) {
                return;
            }

            int[] budget = new int[]{0};
            ShortFormDetector.NodeSnapshot snapshot = buildSnapshot(root, 0, budget);
            ShortFormDetector.DetectionResult result = ShortFormDetector.detect(pkg, snapshot);
            if (result.isDetected()) {
                triggerGateIfNotCoolingDown(
                        pkg,
                        FocusInterruptionActivity.MODE_SHORT_FORM,
                        result.getSurfaceLabel(),
                        true,
                        sessionManager
                );
            }
        } finally {
            recycleSafely(root);
        }
    }

    @Override
    public void onInterrupt() {
        // Required override
    }

    @Override
    public boolean onUnbind(Intent intent) {
        serviceConnected = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        serviceConnected = false;
        super.onDestroy();
    }

    private void triggerGateIfNotCoolingDown(
            String pkg,
            String mode,
            String surfaceLabel,
            boolean performBackExitFirst,
            FocusSessionManager sessionManager
    ) {
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(lastTriggeredPkg)
                && mode.equals(lastTriggeredMode)
                && (now - lastTriggerElapsedMs) < GATE_TRIGGER_COOLDOWN_MS) {
            return;
        }
        lastTriggeredPkg = pkg;
        lastTriggeredMode = mode;
        lastTriggerElapsedMs = now;
        lastGateLaunchElapsedMs = now;

        String appLabel = appRepository.getAppLabel(pkg);
        if (!pkg.equals(lastRecordedPkg) || (now - lastRecordedElapsedMs) >= INTERRUPTION_RECORD_COOLDOWN_MS) {
            lastRecordedPkg = pkg;
            lastRecordedElapsedMs = now;
            sessionManager.incrementSessionInterruption(pkg, appLabel);
        }

        if (performBackExitFirst) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }

        Intent gateIntent = new Intent(this, FocusInterruptionActivity.class);
        gateIntent.putExtra(FocusInterruptionActivity.EXTRA_BLOCKED_PKG, pkg);
        gateIntent.putExtra(FocusInterruptionActivity.EXTRA_BLOCKED_APP_NAME, appLabel);
        gateIntent.putExtra(FocusInterruptionActivity.EXTRA_BLOCK_MODE, mode);
        gateIntent.putExtra(
                FocusInterruptionActivity.EXTRA_BLOCK_SURFACE,
                (surfaceLabel != null && !surfaceLabel.isEmpty()) ? surfaceLabel : (appLabel + " Reels/Shorts")
        );
        gateIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        startActivity(gateIntent);
    }

    private boolean shouldIgnorePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return true;
        }
        String ownPkg = getPackageName();
        if (pkg.equals(ownPkg)
                || "com.android.systemui".equals(pkg)
                || "com.android.settings".equals(pkg)
                || NonEssentialAppClassifier.isCoreEssentialPackage(pkg, ownPkg)) {
            return true;
        }
        try {
            String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && !ime.isEmpty()) {
                int slash = ime.indexOf('/');
                String imePkg = slash > 0 ? ime.substring(0, slash) : ime;
                if (pkg.equals(imePkg)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private ShortFormDetector.NodeSnapshot buildSnapshot(AccessibilityNodeInfo node, int depth, int[] budget) {
        if (node == null || depth > MAX_SNAPSHOT_DEPTH || budget[0] >= MAX_SNAPSHOT_NODES) {
            return null;
        }
        // Strict privacy rule: never read password input fields
        if (node.isPassword()) {
            return null;
        }
        boolean visible = node.isVisibleToUser();
        if (!visible && depth > 0) {
            return null;
        }
        budget[0]++;

        int childCount = node.getChildCount();
        List<ShortFormDetector.NodeSnapshot> children = childCount > 0 ? new ArrayList<>() : Collections.emptyList();
        for (int i = 0; i < childCount && budget[0] < MAX_SNAPSHOT_NODES; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                ShortFormDetector.NodeSnapshot childSnap = buildSnapshot(child, depth + 1, budget);
                if (childSnap != null) {
                    children.add(childSnap);
                }
            } finally {
                recycleSafely(child);
            }
        }

        CharSequence textSeq = node.getText();
        CharSequence descSeq = node.getContentDescription();
        CharSequence classSeq = node.getClassName();
        return new ShortFormDetector.NodeSnapshot(
                node.getViewIdResourceName(),
                classSeq != null ? classSeq.toString() : "",
                textSeq != null ? textSeq.toString() : "",
                descSeq != null ? descSeq.toString() : "",
                node.isSelected(),
                visible,
                children
        );
    }

    @SuppressWarnings("deprecation")
    private static void recycleSafely(AccessibilityNodeInfo node) {
        if (node != null) {
            try {
                node.recycle();
            } catch (Exception ignored) {
            }
        }
    }
}
