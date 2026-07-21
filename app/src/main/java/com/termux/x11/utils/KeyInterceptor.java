package com.termux.x11.utils;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import com.termux.x11.MainActivity;

import java.util.LinkedHashSet;

public class KeyInterceptor extends AccessibilityService {
    LinkedHashSet<Integer> pressedKeys = new LinkedHashSet<>();

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static KeyInterceptor self;
    private static boolean launchedAutomatically = false;
    private boolean enabled = false;

    private final RemoteCallbackList<IKeyCallback> mCallbacks = new RemoteCallbackList<>();

    private final IKeyInterceptor.Stub mBinder = new IKeyInterceptor.Stub() {
        @Override
        public void registerCallback(IKeyCallback callback) {
            if (callback != null) {
                mCallbacks.register(callback);
                recheck();
            }
        }

        @Override
        public void unregisterCallback(IKeyCallback callback) {
            if (callback != null) {
                mCallbacks.unregister(callback);
                recheck();
            }
        }

        @Override
        public void requestRecheck() {
            recheck();
        }
    };

    public static IBinder getBinder() {
        if (self != null) {
            return self.mBinder;
        }
        return null;
    }

    public KeyInterceptor() {
        self = this;
    }

    public static void launch(@NonNull Context ctx) {
        try {
            String service = "com.termux.x11/.utils.KeyInterceptor";
            String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabled == null || enabled.isEmpty())
                enabled = service;
            else if (!enabled.contains(service))
                enabled += ":" + service;

            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled);
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            launchedAutomatically = true;
        } catch (SecurityException e) {
            new AlertDialog.Builder(ctx)
                    .setTitle("Permission denied")
                    .setMessage("Android requires WRITE_SECURE_SETTINGS permission to start accessibility service automatically.\n" +
                            "Please, launch this command using ADB:\n" +
                            "adb shell pm grant com.termux.x11 android.permission.WRITE_SECURE_SETTINGS")
                    .setNegativeButton("OK", null)
                    .create()
                    .show();

            MainActivity.prefs.enableAccessibilityServiceAutomatically.put(false);
        }
    }

    public static void shutdown(boolean onlyIfEnabledAutomatically) {
        if (onlyIfEnabledAutomatically && !launchedAutomatically)
            return;

        if (self != null) {
            self.disableSelf();
            self.pressedKeys.clear();
            self = null;
        }
    }

    public static boolean isLaunched() {
        AccessibilityServiceInfo info = self == null ? null : self.getServiceInfo();
        return info != null && info.getId() != null;
    }

    private static final Runnable disableImmediatelyCallback = KeyInterceptor::disableImmediately;
    private static void disableImmediately() {
        if (self == null)
            return;

        android.util.Log.d("KeyInterceptor", "disabling interception service");
        self.setServiceInfo(new AccessibilityServiceInfo() {{ flags = DEFAULT; }});
        self.enabled = false;
    }

    public static void recheck() {
        if (self == null) return;
        boolean anyIntercept = false;

        int n = self.mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    if (self.mCallbacks.getBroadcastItem(i).shouldIntercept()) {
                        anyIntercept = true;
                        break;
                    }
                } catch (RemoteException ignored) {}
            }
        } finally {
            self.mCallbacks.finishBroadcast();
        }

        MainActivity a = MainActivity.getInstance();
        if (a != null && a.shouldInterceptKeys()) {
            anyIntercept = true;
        }

        boolean shouldBeEnabled = anyIntercept || !self.pressedKeys.isEmpty();

        handler.removeCallbacks(disableImmediatelyCallback);

        if (shouldBeEnabled) {
            if (!self.enabled) {
                android.util.Log.d("KeyInterceptor", "enabling interception service");
                self.setServiceInfo(new AccessibilityServiceInfo() {{ flags = FLAG_REQUEST_FILTER_KEY_EVENTS; }});
                self.enabled = true;
            }
        } else if (self.enabled)
            // In the case if service info is changed Android current dragging processes
            // so it is impossible to pull notification bar or call recents screen by swiping activity up.
            handler.postDelayed(disableImmediatelyCallback, 120000);
    }

    @Override
    protected void onServiceConnected() {
        recheck();
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int n = mCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                IKeyCallback callback = mCallbacks.getBroadcastItem(i);
                try {
                    if (callback.shouldIntercept()) {
                        boolean result = callback.onKeyEvent(event);
                        if (result) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                pressedKeys.add(event.getKeyCode());
                            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                                pressedKeys.remove(event.getKeyCode());
                            }
                            return true;
                        }
                    }
                } catch (RemoteException e) {
                    android.util.Log.e("KeyInterceptor", "Remote callback error", e);
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }

        boolean ret = false;
        MainActivity instance = MainActivity.getInstance();

        if (instance == null)
            return false;

        boolean intercept = instance.shouldInterceptKeys();

        if (intercept || (event.getAction() == KeyEvent.ACTION_UP && pressedKeys.contains(event.getKeyCode())))
            ret = instance.handleKey(event);

        if (intercept && event.getAction() == KeyEvent.ACTION_DOWN)
            pressedKeys.add(event.getKeyCode());
        else
        // We should send key releases to activity for the case if user was pressing some keys when Activity lost focus.
        // I.e. if user switched window with Win+Tab or if he was pressing Ctrl while switching activity.
        if (event.getAction() == KeyEvent.ACTION_UP)
            pressedKeys.remove(event.getKeyCode());

        recheck();

        return ret;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {}

    @Override
    public void onInterrupt() {}
}
