package com.termux.x11;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.*;
import static android.view.WindowManager.LayoutParams.*;
import static com.termux.x11.CmdEntryPoint.ACTION_START;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DragEvent;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.math.MathUtils;
import androidx.viewpager.widget.ViewPager;
import android.content.res.ColorStateList;
import android.widget.ImageButton;
import com.termux.x11.utils.IKeyCallback;
import com.termux.x11.utils.IKeyInterceptor;

import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.FullscreenWorkaround;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.KeyInterceptorService;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;
import android.widget.TextView;

import java.util.Map;

@SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends AppCompatActivity {
    public static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";

    public static Handler handler = new Handler();
    FrameLayout frm;
    private TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private Notification mNotification;
    NotificationManager mNotificationManager;

    public static final String ACTION_DISPLAY_STATUS = "com.termux.x11.ACTION_DISPLAY_STATUS";
    public static final String ACTION_QUERY_DISPLAY_STATUS = "com.termux.x11.ACTION_QUERY_DISPLAY_STATUS";

    private int mDisplayIndex = -1;
    private int mX11DisplayIndex = -1;
    private int mNotificationId = 7892;

    // Dashboard views
    private final View[] mCards = new View[6];
    private final View[] mStatusDots = new View[6];
    private final TextView[] mStatusTexts = new TextView[6];
    private final Button[] mSwitchButtons = new Button[6];
    private final ImageButton[] mSettingsButtons = new ImageButton[6];
    private final TextView[] mDisplayTitles = new TextView[6];
    private final ICmdEntryInterface[] mActiveBinders = new ICmdEntryInterface[6];

    // Core Dynamic Mapping Store
    private final int[] mSlotToDisplayIndex = new int[] {-1, -1, -1, -1, -1, -1};

    // Display states
    private final boolean[] mDisplayConnected = new boolean[6];
    private final boolean[] mDisplayForeground = new boolean[6];
    private final boolean[] mDisplayRunningBackground = new boolean[6];

    public int getDisplayIndex() {
        return mX11DisplayIndex;
    }

    public void requestKeyInterceptorRecheck() {
        if (mKeyInterceptorService != null) {
            try {
                mKeyInterceptorService.requestRecheck();
            } catch (RemoteException ignored) {}
        }
    }

    public String getDisplayPrefName() {
        if (mDisplayIndex == -1) {
            return getPackageName() + "_preferences";
        } else {
            return "com.termux.x11_preferences_display" + mX11DisplayIndex;
        }
    }

    @Override
    public void startActivity(Intent intent) {
        if (intent != null && intent.getComponent() != null && LoriePreferences.class.getName().equals(intent.getComponent().getClassName())) {
            if (!intent.hasExtra("display_pref_name")) {
                intent.putExtra("display_pref_name", getDisplayPrefName());
            }
        }
        super.startActivity(intent);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        if (intent != null && intent.getComponent() != null && LoriePreferences.class.getName().equals(intent.getComponent().getClassName())) {
            if (!intent.hasExtra("display_pref_name")) {
                intent.putExtra("display_pref_name", getDisplayPrefName());
            }
        }
        super.startActivity(intent, options);
    }

    private boolean isProcessRunning(String processNameSuffix) {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null && manager.getRunningAppProcesses() != null) {
            String targetProcess = getPackageName() + processNameSuffix;
            for (android.app.ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
                if (processInfo.processName.equals(targetProcess)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void sendDisplayStatusBroadcast() {
        Intent intent = new Intent(ACTION_DISPLAY_STATUS);
        intent.putExtra("display_index", mDisplayIndex);
        intent.putExtra("x11_display_index", mX11DisplayIndex);
        intent.putExtra("connected", LorieView.connected());
        intent.putExtra("foreground", hasWindowFocus());
        intent.putExtra("running_background", mDisplayIndex != -1 && LorieView.connected());
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void updateDisplayStatuses() {
        for (int i = 1; i <= 5; i++) {
            boolean isAlive = mActiveBinders[i] != null || isProcessRunning(":display" + i);
            if (!isAlive) {
                mActiveBinders[i] = null;
                mSlotToDisplayIndex[i] = -1;
                mDisplayConnected[i] = false;
                mDisplayForeground[i] = false;
                mDisplayRunningBackground[i] = false;
            }
        }
        updateDashboardUI();
    }

    private void updateDashboardUI() {
        if (mDisplayIndex != -1) return;

        runOnUiThread(() -> {
            boolean hasActiveDisplay = false;
            for (int i = 1; i <= 5; i++) {
                if (mCards[i] == null) continue;

                int displayIndex = mSlotToDisplayIndex[i];
                if (displayIndex == -1) {
                    mCards[i].setVisibility(View.GONE);
                    continue;
                }

                hasActiveDisplay = true;
                mCards[i].setVisibility(View.VISIBLE);

                if (mDisplayTitles[i] != null) {
                    mDisplayTitles[i].setText(":" + displayIndex);
                }
                int labelId = getResources().getIdentifier("display_label_" + i, "id", getPackageName());
                TextView labelView = findViewById(labelId);
                if (labelView != null) {
                    String prefName = "com.termux.x11_preferences_display" + displayIndex;
                    SharedPreferences displayPrefs = getSharedPreferences(prefName, Context.MODE_PRIVATE);
                    String customLabel = displayPrefs.getString("displayCustomLabel", "");
                    if (!customLabel.isEmpty()) {
                        labelView.setText(customLabel);
                    } else {
                        labelView.setText("Display " + displayIndex);
                    }
                }

                boolean isRunning = mActiveBinders[i] != null || mDisplayRunningBackground[i];

                if (!isRunning) {
                    mStatusDots[i].setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#757575")));
                    mStatusTexts[i].setText("Offline");
                    mStatusTexts[i].setTextColor(Color.parseColor("#94A3B8"));
                    mSwitchButtons[i].setVisibility(View.GONE);
                } else if (mDisplayConnected[i]) {
                    mStatusDots[i].setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
                    mStatusTexts[i].setText("Connected");
                    mStatusTexts[i].setTextColor(Color.parseColor("#4CAF50"));
                    mSwitchButtons[i].setVisibility(View.VISIBLE);
                } else {
                    mStatusDots[i].setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#64B5F6")));
                    mStatusTexts[i].setText("Running in Background");
                    mStatusTexts[i].setTextColor(Color.parseColor("#64B5F6"));
                    mSwitchButtons[i].setVisibility(View.VISIBLE);
                }
            }

            View placeholder = findViewById(R.id.dashboard_placeholder);
            if (placeholder != null) {
                placeholder.setVisibility(hasActiveDisplay ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void updateQuickSwitchButtons() {
        runOnUiThread(() -> {
            for (int i = 1; i <= 5; i++) {
                int quickId = getResources().getIdentifier("btn_quick_switch_" + i, "id", getPackageName());
                Button btn = findViewById(quickId);
                if (btn != null) {
                    int displayIndex = mSlotToDisplayIndex[i];
                    boolean isRunning = mActiveBinders[i] != null || mDisplayConnected[i] || mDisplayRunningBackground[i];
                    if (isRunning && displayIndex != -1 && displayIndex != mX11DisplayIndex) {
                        btn.setText(String.valueOf(displayIndex));
                        btn.setVisibility(View.VISIBLE);
                    } else {
                        btn.setVisibility(View.GONE);
                    }
                }
            }
            for (int i = 6; i <= 10; i++) {
                int quickId = getResources().getIdentifier("btn_quick_switch_" + i, "id", getPackageName());
                Button btn = findViewById(quickId);
                if (btn != null) {
                    btn.setVisibility(View.GONE);
                }
            }
        });
    }

    private final BroadcastReceiver displayStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_DISPLAY_STATUS.equals(intent.getAction())) {
                int index = intent.getIntExtra("display_index", -1);
                if (index >= 1 && index <= 5) {
                    mDisplayConnected[index] = intent.getBooleanExtra("connected", false);
                    mDisplayForeground[index] = intent.getBooleanExtra("foreground", false);
                    mDisplayRunningBackground[index] = intent.getBooleanExtra("running_background", false);
                    int x11Idx = intent.getIntExtra("x11_display_index", -1);
                    if (x11Idx != -1) {
                        for (int i = 1; i <= 5; i++) {
                            if (i != index && mSlotToDisplayIndex[i] == x11Idx) {
                                mSlotToDisplayIndex[i] = -1;
                                mActiveBinders[i] = null;
                                mDisplayConnected[i] = false;
                                mDisplayForeground[i] = false;
                                mDisplayRunningBackground[i] = false;
                            }
                        }
                        mSlotToDisplayIndex[index] = x11Idx;
                    }
                    updateDashboardUI();
                    updateQuickSwitchButtons();
                }
            }
        }
    };

    private final BroadcastReceiver queryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_QUERY_DISPLAY_STATUS.equals(intent.getAction())) {
                if (mDisplayIndex == -1) {
                    for (int i = 1; i <= 5; i++) {
                        if (mSlotToDisplayIndex[i] != -1) {
                            Intent statusIntent = new Intent(ACTION_DISPLAY_STATUS);
                            statusIntent.putExtra("display_index", i);
                            statusIntent.putExtra("x11_display_index", mSlotToDisplayIndex[i]);
                            statusIntent.putExtra("running_background", mActiveBinders[i] != null || mDisplayRunningBackground[i]);
                            statusIntent.putExtra("connected", mDisplayConnected[i]);
                            statusIntent.putExtra("foreground", mDisplayForeground[i]);
                            statusIntent.setPackage(getPackageName());
                            sendBroadcast(statusIntent);
                        }
                    }
                } else {
                    sendDisplayStatusBroadcast();
                }
            }
        }
    };

    private IKeyInterceptor mKeyInterceptorService = null;
    private final IKeyCallback mKeyCallback = new IKeyCallback.Stub() {
        @Override
        public boolean onKeyEvent(KeyEvent event) {
            return handleKey(event);
        }

        @Override
        public boolean shouldIntercept() {
            return shouldInterceptKeys();
        }
    };

    private final android.content.ServiceConnection mKeyInterceptorConnection = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, IBinder service) {
            mKeyInterceptorService = IKeyInterceptor.Stub.asInterface(service);
            try {
                mKeyInterceptorService.registerCallback(mKeyCallback);
            } catch (RemoteException e) {
                Log.e("MainActivity", "Failed to register key callback", e);
            }
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
            mKeyInterceptorService = null;
        }
    };

    private void bindKeyInterceptor() {
        if (mDisplayIndex > 0) {
            Intent intent = new Intent(this, KeyInterceptorService.class);
            intent.setAction("com.termux.x11.utils.IKeyInterceptor");
            bindService(intent, mKeyInterceptorConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void unbindKeyInterceptor() {
        if (mKeyInterceptorService != null) {
            try {
                mKeyInterceptorService.unregisterCallback(mKeyCallback);
            } catch (RemoteException ignored) {}
            unbindService(mKeyInterceptorConnection);
            mKeyInterceptorService = null;
        }
    }
    static InputMethodManager inputMethodManager;
    private static boolean showIMEWhileExternalConnected = true;
    private static boolean externalKeyboardConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    boolean useTermuxEKBarBehaviour = false;
    private boolean isInPictureInPictureMode = false;

    public static Prefs prefs = null;

    private static boolean oldFullscreen = false, oldHideCutout = false;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> onPreferencesChanged(key);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
            prefs.recheckStoringSecondaryDisplayPreferences();
            if (ACTION_START.equals(intent.getAction())) {
                int displayIndex = intent.getIntExtra("display_index", 0);
                
                if (mDisplayIndex != -1) {
                    if (displayIndex != mX11DisplayIndex) {
                        return;
                    }
                    try {
                        Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent for display " + displayIndex);
                        onReceiveConnection(intent);
                    } catch (Exception e) {
                        Log.e("MainActivity", "Something went wrong while we extracted connection details from binder.", e);
                    }
                    return;
                }

                // Coordinator / Dashboard slot assignment logic
                try {
                    Bundle bundle = intent.getBundleExtra(null);
                    IBinder binder = bundle != null ? bundle.getBinder(null) : null;
                    if (binder != null) {
                        ICmdEntryInterface s = ICmdEntryInterface.Stub.asInterface(binder);
                        if (s != null) {
                            // Mark this display as opened
                            SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                            java.util.Set<String> opened = new java.util.HashSet<>(defaultPrefs.getStringSet("opened_displays_set", new java.util.HashSet<>()));
                            opened.add(String.valueOf(displayIndex));
                            defaultPrefs.edit().putStringSet("opened_displays_set", opened).commit();

                            int slotIndex = -1;
                            for (int i = 1; i <= 5; i++) {
                                if (mSlotToDisplayIndex[i] == displayIndex) {
                                    slotIndex = i;
                                    break;
                                }
                            }
                            if (slotIndex == -1) {
                                for (int i = 1; i <= 5; i++) {
                                    if (mSlotToDisplayIndex[i] == -1) {
                                        slotIndex = i;
                                        mSlotToDisplayIndex[i] = displayIndex;
                                        break;
                                    }
                                }
                            }
                            
                            if (slotIndex != -1) {
                                for (int i = 1; i <= 5; i++) {
                                    if (i != slotIndex && mSlotToDisplayIndex[i] == displayIndex) {
                                        mSlotToDisplayIndex[i] = -1;
                                        mActiveBinders[i] = null;
                                        mDisplayConnected[i] = false;
                                        mDisplayForeground[i] = false;
                                        mDisplayRunningBackground[i] = false;
                                    }
                                }
                                mActiveBinders[slotIndex] = s;
                                final int finalSlot = slotIndex;
                                s.asBinder().linkToDeath(() -> {
                                    mActiveBinders[finalSlot] = null;
                                    mSlotToDisplayIndex[finalSlot] = -1;
                                    Intent deadIntent = new Intent(ACTION_DISPLAY_STATUS);
                                    deadIntent.putExtra("display_index", finalSlot);
                                    deadIntent.putExtra("x11_display_index", -1);
                                    deadIntent.putExtra("running_background", false);
                                    deadIntent.putExtra("connected", false);
                                    deadIntent.putExtra("foreground", false);
                                    deadIntent.setPackage(getPackageName());
                                    sendBroadcast(deadIntent);
                                    runOnUiThread(() -> {
                                        updateDisplayStatuses();
                                        updateQuickSwitchButtons();
                                    });
                                }, 0);
                                runOnUiThread(() -> updateDisplayStatuses());
                            } else {
                                Log.e("MainActivity", "Rejected connection for Display " + displayIndex + ": All 5 slots occupied.");
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "Error registering binder for display " + displayIndex, e);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                finishAffinity();
            } else if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction())) {
                Log.d("MainActivity", "preference: " + intent.getStringExtra("key"));
                if (!"additionalKbdVisible".equals(intent.getStringExtra("key")))
                    onPreferencesChanged("");
            } else if (ACTION_CUSTOM.equals(intent.getAction())) {
                android.util.Log.d("ACTION_CUSTOM", "action " + intent.getStringExtra("what"));
                mInputHandler.extractUserActionFromPreferences(prefs, intent.getStringExtra("what")).accept(0, true);
            }
        }
    };

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!LorieView.connected())
                return false;

            finishStartupDraw();
            return true;
        }
    };

    private void finishStartupDraw() {
        View content = findViewById(android.R.id.content);
        content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener);
        content.invalidate();
    }

    @SuppressLint("StaticFieldLeak")
    private static MainActivity instance;

    public MainActivity() {
        instance = this;
    }

    public static Prefs getPrefs() {
        return prefs;
    }

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String processName = LoriePreferences.getProcessName(this);
        if (processName.contains(":display")) {
            try {
                mDisplayIndex = Integer.parseInt(processName.substring(processName.indexOf(":display") + 8));
            } catch (NumberFormatException ignored) {}
            mX11DisplayIndex = getIntent().getIntExtra("display_index", mDisplayIndex);
        } else {
            mDisplayIndex = -1; // Dashboard
            mX11DisplayIndex = -1;
        }
        mNotificationId = 7892 + mDisplayIndex;

        SharedPreferences defaultTemplatePrefs = getSharedPreferences("Default", Context.MODE_PRIVATE);
        if (defaultTemplatePrefs.getAll().isEmpty()) {
            LoriePreferences.cloneSharedPreferences(this, getPackageName() + "_preferences", "Default");
        }
        SharedPreferences templatePrefs = getSharedPreferences("com.termux.x11_preferences_template", Context.MODE_PRIVATE);
        if (templatePrefs.getAll().isEmpty()) {
            LoriePreferences.cloneSharedPreferences(this, "Default", "com.termux.x11_preferences_template");
        }

        if (mDisplayIndex != -1) {
            // Mark this display as opened
            SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
            java.util.Set<String> opened = new java.util.HashSet<>(defaultPrefs.getStringSet("opened_displays_set", new java.util.HashSet<>()));
            opened.add(String.valueOf(mX11DisplayIndex));
            defaultPrefs.edit().putStringSet("opened_displays_set", opened).commit();

            String prefName = getDisplayPrefName();
            SharedPreferences displayPrefs = getSharedPreferences(prefName, Context.MODE_PRIVATE);
            
            String assignedTemplate = defaultPrefs.getString("display_assignment_" + mX11DisplayIndex, "");
            String templatePrefsName = "";
            if (!assignedTemplate.isEmpty()) {
                templatePrefsName = assignedTemplate;
            } else {
                templatePrefsName = "com.termux.x11_preferences_template";
            }
            templatePrefs = getSharedPreferences(templatePrefsName, Context.MODE_PRIVATE);
            
            // Only clone if the display preference file is empty and template has settings
            if (displayPrefs.getAll().isEmpty() && !templatePrefs.getAll().isEmpty()) {
                SharedPreferences.Editor editor = displayPrefs.edit();
                for (Map.Entry<String, ?> entry : templatePrefs.getAll().entrySet()) {
                    Object val = entry.getValue();
                    if (val instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) val);
                    else if (val instanceof Float) editor.putFloat(entry.getKey(), (Float) val);
                    else if (val instanceof Integer) editor.putInt(entry.getKey(), (Integer) val);
                    else if (val instanceof Long) editor.putLong(entry.getKey(), (Long) val);
                    else if (val instanceof String) editor.putString(entry.getKey(), (String) val);
                }
                editor.apply();
            }
        }
        initPreferences();

        getWindow().setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main_activity);

        frm = findViewById(R.id.frame);

        if (mDisplayIndex == -1) {
            for (int i = 1; i <= 10; i++) {
                int cardId = getResources().getIdentifier("card_display_" + i, "id", getPackageName());
                int dotId = getResources().getIdentifier("status_dot_" + i, "id", getPackageName());
                int textId = getResources().getIdentifier("status_text_" + i, "id", getPackageName());
                int switchId = getResources().getIdentifier("btn_switch_" + i, "id", getPackageName());
                int settingsId = getResources().getIdentifier("btn_settings_" + i, "id", getPackageName());
                int titleId = getResources().getIdentifier("display_title_" + i, "id", getPackageName());

                if (i <= 5) {
                    mCards[i] = findViewById(cardId);
                    mStatusDots[i] = findViewById(dotId);
                    mStatusTexts[i] = findViewById(textId);
                    mSwitchButtons[i] = findViewById(switchId);
                    mSettingsButtons[i] = findViewById(settingsId);
                    mDisplayTitles[i] = findViewById(titleId);

                    final int index = i;
                    mSettingsButtons[i].setOnClickListener(v -> {
                        Intent prefIntent = new Intent(this, LoriePreferences.class);
                        prefIntent.setAction(Intent.ACTION_MAIN);
                        int displayIndex = mSlotToDisplayIndex[index];
                        if (displayIndex == -1) {
                            displayIndex = index - 1;
                        }
                        String prefName = "com.termux.x11_preferences_display" + displayIndex;
                        prefIntent.putExtra("display_pref_name", prefName);
                        startActivity(prefIntent);
                    });

                    mSwitchButtons[i].setOnClickListener(v -> {
                        int displayIndex = mSlotToDisplayIndex[index];
                        if (displayIndex != -1) {
                            String className = "com.termux.x11.MainActivity" + index;
                            Intent switchIntent = new Intent();
                            switchIntent.setClassName(getPackageName(), className);
                            switchIntent.putExtra("display_index", displayIndex);
                            ICmdEntryInterface s = mActiveBinders[index];
                            if (s != null) {
                                Bundle binderBundle = new Bundle();
                                binderBundle.putBinder(null, s.asBinder());
                                switchIntent.putExtra((String) null, binderBundle);
                            }
                            switchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(switchIntent);
                        }
                    });

                    int labelId = getResources().getIdentifier("display_label_" + i, "id", getPackageName());
                    TextView labelView = findViewById(labelId);
                    if (labelView != null) {
                        labelView.setOnClickListener(v -> {
                            int displayIndex = mSlotToDisplayIndex[index];
                            if (displayIndex != -1) {
                                showRenameDisplayDialog(displayIndex);
                            }
                        });
                    }
                } else {
                    View card = findViewById(cardId);
                    if (card != null) {
                        card.setVisibility(View.GONE);
                    }
                }
            }
        }
        registerReceiver(displayStatusReceiver, new IntentFilter(ACTION_DISPLAY_STATUS), SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        findViewById(R.id.help_button).setOnClickListener((l) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"))));
        findViewById(R.id.exit_button).setOnClickListener((l) -> finish());

        LorieView lorieView = findViewById(R.id.lorieView);
        View lorieParent = (View) lorieView.getParent();

        mInputHandler = new TouchInputHandler(this, new InputEventSender(lorieView));
        mLorieKeyListener = (v, k, e) -> {
            InputDevice dev = e.getDevice();
            boolean result = mInputHandler.sendKeyEvent(e);

            // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
            return result;
        };

        lorieParent.setOnTouchListener((v, e) -> {
            // Avoid batched MotionEvent objects and reduce potential latency.
            // For reference: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features#rendering.
            if (e.getAction() == MotionEvent.ACTION_DOWN)
                lorieParent.requestUnbufferedDispatch(e);

            return mInputHandler.handleTouchEvent(lorieParent, lorieView, e);
        });
        lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieParent.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((screenWidth, screenHeight, inputTransform) ->
                mInputHandler.handleInputTransformChanged(screenWidth, screenHeight, inputTransform));

        registerReceiver(receiver, new IntentFilter(ACTION_START) {{
            addAction(ACTION_PREFERENCES_CHANGED);
            addAction(ACTION_STOP);
            addAction(ACTION_CUSTOM);
        }}, SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        registerReceiver(queryReceiver, new IntentFilter(ACTION_QUERY_DISPLAY_STATUS), SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // Taken from Stackoverflow answer https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible/7509285#
        FullscreenWorkaround.assistActivity(this);
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mDisplayIndex != -1) {
            mNotification = buildNotification();
            mNotificationManager.notify(mNotificationId, mNotification);
        }

        if (mDisplayIndex >= 0 && tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(this::finishStartupDraw, 500);
        }
        onPreferencesChanged("");

        toggleExtraKeys(false, false);

        initStylusAuxButtons();
        initMouseAuxButtons();
        initFloatingSwitcher();

        if (SDK_INT >= VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 0);
        }

        bindKeyInterceptor();
        sendDisplayStatusBroadcast();

        onReceiveConnection(getIntent());
        findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
        clientConnectedStateChanged();
    }

    private void initPreferences() {
        if (prefs != null) {
            try {
                prefs.get().unregisterOnSharedPreferenceChangeListener(preferencesChangedListener);
            } catch (Exception ignored) {}
        }

        getIntent().putExtra("display_pref_name", getDisplayPrefName());
        prefs = new Prefs(this);
        
        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2)
            prefs.touchMode.put("1");

        oldFullscreen = prefs.fullscreen.get();
        oldHideCutout = prefs.hideCutout.get();

        prefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mDisplayIndex != -1) {
            int newDisplayIndex = intent.getIntExtra("display_index", -1);
            if (newDisplayIndex != -1) {
                mX11DisplayIndex = newDisplayIndex;
                initPreferences();
            }
            onReceiveConnection(intent);
        }
    }

    @Override
    protected void onDestroy() {
        cancelAutoHide();
        if (mNotificationManager != null) {
            mNotificationManager.cancel(mNotificationId);
        }
        unregisterReceiver(receiver);
        unregisterReceiver(displayStatusReceiver);
        unregisterReceiver(queryReceiver);
        unbindKeyInterceptor();

        // Notify dashboard we are offline
        Intent intent = new Intent(ACTION_DISPLAY_STATUS);
        intent.putExtra("display_index", mDisplayIndex);
        intent.putExtra("connected", false);
        intent.putExtra("foreground", false);
        intent.putExtra("running_background", false);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);

        super.onDestroy();
    }

    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && LorieView.connected();
        final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? View.VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 4 : 0)));
            left.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 4) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == View.VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                buttons.setVisibility(View.VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - 4 * left.getWidth();
                float maxY = frm.getHeight() - 4 * left.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));

                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(@NonNull Canvas canvas) {}
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - visibility.getWidth();
                float maxY = frm.getHeight() - visibility.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() - visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, 0, maxX));
                        overlay.setY(MathUtils.clamp(dY, 0, maxY));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        //Make sure the dragged btn is inside the view
                        overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                        overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));
                        break;
                }
                return true;
            });

            return true;
        });
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (LorieView.connected() && show) {
            buttons.setVisibility(View.VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private final Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private boolean isHidden = false;
    private final Runnable autoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDisplayIndex == -1) return;
            LinearLayout switcherLayout = findViewById(R.id.floating_switcher_layout);
            LinearLayout dockLayout = findViewById(R.id.floating_switcher_dock);
            if (switcherLayout == null || dockLayout == null) return;
            if (dockLayout.getVisibility() == View.VISIBLE) return; // Don't hide if dock is open

            float screenWidth = frm.getWidth();
            float currentX = switcherLayout.getX();
            float offset = switcherLayout.getWidth() / 2.0f;
            float targetX;

            if (currentX + switcherLayout.getWidth() / 2.0f < screenWidth / 2.0f) {
                // Snapped to left
                targetX = -offset;
            } else {
                // Snapped to right
                targetX = screenWidth - offset;
            }

            switcherLayout.animate()
                .x(targetX)
                .alpha(0.4f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(300)
                .start();
            isHidden = true;
        }
    };

    private void scheduleAutoHide() {
        cancelAutoHide();
        autoHideHandler.postDelayed(autoHideRunnable, 3000);
    }

    private void cancelAutoHide() {
        autoHideHandler.removeCallbacks(autoHideRunnable);
    }

    private void showFully() {
        if (!isHidden) return;
        LinearLayout switcherLayout = findViewById(R.id.floating_switcher_layout);
        if (switcherLayout == null) return;

        float screenWidth = frm.getWidth();
        float currentX = switcherLayout.getX();
        float targetX = currentX;

        // Bring back to screen bounds
        if (currentX < 0) {
            targetX = 0;
        } else if (currentX + switcherLayout.getWidth() > screenWidth) {
            targetX = screenWidth - switcherLayout.getWidth();
        }

        switcherLayout.animate()
            .x(targetX)
            .alpha(1.0f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(200)
            .start();
        isHidden = false;
    }

    @SuppressLint("ClickableViewAccessibility")
    void initFloatingSwitcher() {
        final ViewPager pager = getTerminalToolbarViewPager();
        final LinearLayout switcherLayout = findViewById(R.id.floating_switcher_layout);
        if (switcherLayout == null) return;

        final View toggleBtn = findViewById(R.id.btn_switcher_toggle);
        final TextView txtDisplayIndex = findViewById(R.id.txt_switcher_display_index);
        if (txtDisplayIndex != null) {
            txtDisplayIndex.setText(String.valueOf(mX11DisplayIndex));
        }
        final LinearLayout dockLayout = findViewById(R.id.floating_switcher_dock);
        final ImageButton dashboardBtn = findViewById(R.id.btn_switch_to_dashboard);

        toggleBtn.setOnTouchListener(new View.OnTouchListener() {
            private float initialX;
            private float initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isDragging = false;
            private static final int TOUCH_SLOP = 10;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = switcherLayout.getX();
                        initialY = switcherLayout.getY();
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        cancelAutoHide();
                        showFully();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (!isDragging && (Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            float newX = initialX + dx;
                            float newY = initialY + dy;

                            float maxX = frm.getWidth() - switcherLayout.getWidth();
                            float maxY = frm.getHeight() - switcherLayout.getHeight();
                            if (pager.getVisibility() == View.VISIBLE)
                                maxY -= pager.getHeight();

                            switcherLayout.setX(MathUtils.clamp(newX, 0, maxX));
                            switcherLayout.setY(MathUtils.clamp(newY, 0, maxY));
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            boolean isDockVisible = dockLayout.getVisibility() == View.VISIBLE;
                            if (isDockVisible) {
                                dockLayout.setVisibility(View.GONE);
                                scheduleAutoHide();
                            } else {
                                updateQuickSwitchButtons();
                                dockLayout.setVisibility(View.VISIBLE);

                                float maxX = frm.getWidth() - switcherLayout.getWidth();
                                float maxY = frm.getHeight() - switcherLayout.getHeight();
                                if (pager.getVisibility() == View.VISIBLE)
                                    maxY -= pager.getHeight();
                                switcherLayout.setX(MathUtils.clamp(switcherLayout.getX(), 0, maxX));
                                switcherLayout.setY(MathUtils.clamp(switcherLayout.getY(), 0, maxY));
                            }
                        } else {
                            float currentX = switcherLayout.getX();
                            float screenWidth = frm.getWidth();
                            float targetX;
                            if (currentX + switcherLayout.getWidth() / 2.0f < screenWidth / 2.0f) {
                                targetX = 0;
                            } else {
                                targetX = screenWidth - switcherLayout.getWidth();
                            }

                            switcherLayout.animate()
                                .x(targetX)
                                .setDuration(250)
                                .withEndAction(() -> scheduleAutoHide())
                                .start();
                        }
                        return true;
                }
                return false;
            }
        });

        dashboardBtn.setOnClickListener(v -> {
            Intent switchIntent = new Intent();
            switchIntent.setClassName(getPackageName(), "com.termux.x11.MainActivity");
            switchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(switchIntent);
            dockLayout.setVisibility(View.GONE);
            scheduleAutoHide();
        });

        for (int i = 1; i <= 5; i++) {
            int quickId = getResources().getIdentifier("btn_quick_switch_" + i, "id", getPackageName());
            Button btn = findViewById(quickId);
            if (btn != null) {
                final int slotIndex = i;
                btn.setOnClickListener(v -> {
                    int displayIndex = mSlotToDisplayIndex[slotIndex];
                    if (displayIndex != -1) {
                        Intent switchIntent = new Intent();
                        switchIntent.setClassName(getPackageName(), "com.termux.x11.MainActivity" + slotIndex);
                        switchIntent.putExtra("display_index", displayIndex);
                        switchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(switchIntent);
                        dockLayout.setVisibility(View.GONE);
                        scheduleAutoHide();
                    }
                });
            }
        }

        updateFloatingSwitcherVisibility();
        scheduleAutoHide();
    }

    private void updateFloatingSwitcherVisibility() {
        LinearLayout switcherLayout = findViewById(R.id.floating_switcher_layout);
        if (switcherLayout != null) {
            boolean connected = LorieView.connected();
            switcherLayout.setVisibility(connected ? View.VISIBLE : View.GONE);
        }
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getTerminalToolbarViewPager();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);
        View switcherLayout = findViewById(R.id.floating_switcher_layout);
        int maxYDecrement = (pager.getVisibility() == View.VISIBLE) ? pager.getHeight() : 0;

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - mouseAuxButtons.getHeight() - maxYDecrement));

        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - stylusAuxButtons.getHeight() - maxYDecrement));

        if (switcherLayout != null && switcherLayout.getVisibility() == View.VISIBLE && switcherLayout.getWidth() > 0) {
            switcherLayout.setX(MathUtils.clamp(switcherLayout.getX(), frm.getX(), frm.getX() + frm.getWidth() - switcherLayout.getWidth()));
            switcherLayout.setY(MathUtils.clamp(switcherLayout.getY(), frm.getY(), frm.getY() + frm.getHeight() - switcherLayout.getHeight() - maxYDecrement));
        }
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != View.VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((LorieView.connected() && show && "1".equals(prefs.touchMode.get())) ? View.VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != View.VISIBLE);
    }

    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        final ViewPager pager = getTerminalToolbarViewPager();
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        boolean mouseHelperEnabled = prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get());
        primaryLayer.setVisibility(mouseHelperEnabled ? View.VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                if (pager.getVisibility() == View.VISIBLE)
                    maxY -= pager.getHeight();
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), frm.getX(), maxX));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), frm.getY(), maxY));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
                .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
            switch(e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    getLorieView().sendMouseEvent(0, 0, b, true, true);
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    getLorieView().sendMouseEvent(0, 0, b, false, true);
                    v.setPressed(false);
                    break;
            }
            return true;
        }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startOffset = new float[2];
            final int[] startPosition = new int[2];
            long startTime;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch(e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        primaryLayer.getLocationInWindow(startPosition);
                        startOffset[0] = e.getX();
                        startOffset[1] = e.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        final ViewPager pager = getTerminalToolbarViewPager();
                        int[] offset = new int[2];
                        primaryLayer.getLocationInWindow(offset);
                        float maxX = frm.getX() + frm.getWidth() - primaryLayer.getWidth();
                        float maxY = frm.getY() + frm.getHeight() - primaryLayer.getHeight();
                        if (pager.getVisibility() == View.VISIBLE)
                            maxY -= pager.getHeight();

                        primaryLayer.setX(MathUtils.clamp(offset[0] - startOffset[0] + e.getX(), frm.getX(), maxX));
                        primaryLayer.setY(MathUtils.clamp(offset[1] - startOffset[1] + e.getY(), frm.getY(), maxY));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        final int[] _pos = new int[2];
                        primaryLayer.getLocationInWindow(_pos);
                        int deltaX = (int) (startOffset[0] - e.getX()) + (startPosition[0] - _pos[0]);
                        int deltaY = (int) (startOffset[1] - e.getY()) + (startPosition[1] - _pos[1]);
                        pos.setPressed(false);

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                }
                return true;
            }
        });
    }

    void onReceiveConnection(Intent intent) {
        if (intent != null) {
            int displayIndex = intent.getIntExtra("display_index", -1);
            if (mDisplayIndex != -1 && displayIndex != -1 && displayIndex != mX11DisplayIndex) {
                Log.w("MainActivity", "Received connection intent for display " + displayIndex + " but current mapped display is " + mX11DisplayIndex);
                return;
            }
        }
        Bundle bundle = intent == null ? null : intent.getBundleExtra(null);
        IBinder ibinder = bundle == null ? null : bundle.getBinder(null);
        if (ibinder == null)
            return;

        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            service.asBinder().linkToDeath(() -> {
                service = null;

                Log.v("Lorie", "Disconnected");
                runOnUiThread(() -> { LorieView.connect(-1); clientConnectedStateChanged();} );
            }, 0);
        } catch (RemoteException ignored) {}

        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    LorieView.startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent())
                    getIntent().putExtra(null, bundle);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    boolean tryConnect() {
        if (LorieView.connected())
            return false;

        if (service == null) {
            boolean sent = LorieView.requestConnection();
            handler.postDelayed(this::tryConnect, 250);
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                LorieView.connect(fd.detachFd());
                finishStartupDraw();
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                handler.postDelayed(this::tryConnect, 250);
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            handler.postDelayed(this::tryConnect, 250);
        }
        return false;
    }

    void onPreferencesChanged(String key) {
        if ("additionalKbdVisible".equals(key))
            return;

        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {
        prefs.recheckStoringSecondaryDisplayPreferences();

        onWindowFocusChanged(hasWindowFocus());
        LorieView lorieView = getLorieView();

        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && LorieView.connected() ? View.VISIBLE : View.GONE);
        showMouseAuxButtons(prefs.showMouseHelper.get());
        showStylusAuxButtons(prefs.showStylusClickOverride.get());

        getTerminalToolbarViewPager().setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);

        lorieView.requestLayout();
        lorieView.invalidate();

        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId) {
                mNotification = buildNotification();
                mNotificationManager.notify(mNotificationId, mNotification);
            }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mDisplayIndex == -1) {
            updateDisplayStatuses();
            sendBroadcast(new Intent(ACTION_QUERY_DISPLAY_STATUS).setPackage(getPackageName()));
            return;
        }

        mNotification = buildNotification();
        mNotificationManager.notify(mNotificationId, mNotification);

        setTerminalToolbarView();
        getLorieView().requestFocus();

        sendDisplayStatusBroadcast();
        sendBroadcast(new Intent(ACTION_QUERY_DISPLAY_STATUS).setPackage(getPackageName()));
        scheduleAutoHide();
    }

    @Override
    public void onPause() {
        cancelAutoHide();
        if (mDisplayIndex == -1) {
            super.onPause();
            return;
        }

        inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId)
                mNotificationManager.cancel(mNotificationId);

        super.onPause();

        sendDisplayStatusBroadcast();
    }

    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getTerminalToolbarViewPager() {
        return findViewById(R.id.terminal_toolbar_view_pager);
    }

    private void setTerminalToolbarView() {
        final ViewPager pager = getTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();

        boolean showNow = LorieView.connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.setVisibility(showNow ? View.VISIBLE : View.INVISIBLE);

        if (showNow) {
            pager.setAdapter(new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e)));
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(new X11ToolbarViewPager.OnPageChangeListener(this, pager));
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        ViewGroup.LayoutParams layoutParams = pager.getLayoutParams();
        layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
                (TermuxX11ExtraKeys.getExtraKeysInfo() == null ? 0 : TermuxX11ExtraKeys.getExtraKeysInfo().getMatrix().length));
        pager.setLayoutParams(layoutParams);

        getLorieView().setContentInsets(0, 0, 0, prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0);
        getLorieView().requestFocus();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && LorieView.connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
        getWindow().setSoftInputMode(prefs.Reseed.get() ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);
    }

    public void toggleExtraKeys() {
        toggleExtraKeys(getTerminalToolbarViewPager().getVisibility() != View.VISIBLE, true);
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        return mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
    }

    @SuppressLint("ObsoleteSdkInt")
    Notification buildNotification() {
        int displayIndex = mNotificationId - 7892;
        String displayLabel = "";
        if (displayIndex > 0) {
            String prefName = "com.termux.x11_preferences_display" + displayIndex;
            SharedPreferences displayPrefs = getSharedPreferences(prefName, MODE_PRIVATE);
            displayLabel = displayPrefs.getString("displayCustomLabel", "");
        }
        String title = "Termux:X11" + (displayIndex > 0 ? (" (" + (!displayLabel.isEmpty() ? displayLabel : "Display :" + displayIndex) + ")") : "");
        NotificationCompat.Builder builder =  new NotificationCompat.Builder(this, getNotificationChannel(mNotificationManager))
                .setContentTitle(title)
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText(getResources().getText(R.string.lorie_notification_content_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setSilent(true)
                .setShowWhen(false)
                .setColor(0xFF607D8B);
        return mInputHandler.setupNotification(prefs, builder).build();
    }

    private String getNotificationChannel(NotificationManager notificationManager){
        String channelId = getResources().getString(R.string.app_name);
        String channelName = getResources().getString(R.string.app_name);
        NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (SDK_INT >= VERSION_CODES.Q)
            channel.setAllowBubbles(false);
        notificationManager.createNotificationChannel(channel);
        return channelId;
    }

    int orientation;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);

        orientation = newConfig.orientation;
        onWindowFocusChanged(hasWindowFocus());
        setTerminalToolbarView();
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();
        prefs.recheckStoringSecondaryDisplayPreferences();
        sendDisplayStatusBroadcast();
        Window window = getWindow();
        View decorView = window.getDecorView();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();
        boolean reseed = prefs.Reseed.get();

        if (oldHideCutout != hideCutout || oldFullscreen != fullscreen) {
            oldHideCutout = hideCutout;
            oldFullscreen = fullscreen;
            // For some reason cutout or fullscreen change makes layout calculations wrong and invalid.
            // I did not find simple and reliable way to fix it so it is better to start from the beginning.
            recreate();
            return;
        }

        int requestedOrientation;
        switch (prefs.forceOrientation.get()) {
            case "portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "reverse portrait": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "reverse landscape": requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (hasFocus) {
            if (SDK_INT >= VERSION_CODES.P) {
                if (hideCutout)
                    getWindow().getAttributes().layoutInDisplayCutoutMode = (SDK_INT >= VERSION_CODES.R) ?
                            LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS :
                            LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                else
                    getWindow().getAttributes().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }

            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }

        window.setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        if (hasFocus) {
            if (fullscreen) {
                window.addFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                window.clearFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(0);
            }
        }

        if (prefs.keepScreenOn.get())
            window.addFlags(FLAG_KEEP_SCREEN_ON);
        else
            window.clearFlags(FLAG_KEEP_SCREEN_ON);

        window.setSoftInputMode(reseed ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);

        ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0).setFitsSystemWindows(!fullscreen);
    }

    @Override
    public void onBackPressed() {
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onUserLeaveHint() {
        if (prefs.PIP.get() && hasPipPermission(this)) {
            enterPictureInPictureMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        final ViewPager pager = getTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get())/100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     * @param context calling context
     */
    public static void toggleKeyboardVisibility(Context context) {
        Log.d("MainActivity", "Toggling keyboard visibility");
        if(inputMethodManager != null) {
            android.util.Log.d("toggleKeyboardVisibility", "externalKeyboardConnected " + externalKeyboardConnected + " showIMEWhileExternalConnected " + showIMEWhileExternalConnected);
            if (!externalKeyboardConnected || showIMEWhileExternalConnected)
                inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            else
                inputMethodManager.hideSoftInputFromWindow(getInstance().getWindow().getDecorView().getRootView().getWindowToken(), 0);

            getInstance().getLorieView().requestFocus();
        }
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        if (mDisplayIndex == -1) {
            runOnUiThread(() -> {
                findViewById(R.id.stub).setVisibility(View.VISIBLE);
                View offlineView = findViewById(R.id.offline_display_view);
                if (offlineView != null) offlineView.setVisibility(View.GONE);
                getLorieView().setVisibility(View.GONE);
                findViewById(R.id.mouse_buttons).setVisibility(View.GONE);
                updateFloatingSwitcherVisibility();
                updateDisplayStatuses();
            });
            return;
        }

        runOnUiThread(()-> {
            boolean connected = LorieView.connected();
            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? View.VISIBLE : View.GONE);
            
            // For secondary display activities, Dashboard stub is always GONE
            findViewById(R.id.stub).setVisibility(View.GONE);
            
            View offlineView = findViewById(R.id.offline_display_view);
            if (offlineView != null) {
                offlineView.setVisibility(connected ? View.GONE : View.VISIBLE);
                TextView offlineDesc = findViewById(R.id.txt_offline_display_desc);
                if (offlineDesc != null) {
                    offlineDesc.setText("Display " + mX11DisplayIndex + " is currently offline.\nStart the display server from Termux.");
                }
            }
            
            getLorieView().setVisibility(connected?View.VISIBLE:View.INVISIBLE);
            updateFloatingSwitcherVisibility();

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected)
                tryConnect();
            else
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));

            onWindowFocusChanged(hasWindowFocus());

            sendDisplayStatusBroadcast();
            if (mDisplayIndex == -1) {
                updateDisplayStatuses();
            }
        });
    }

    public static boolean isConnected() {
        if (getInstance() == null)
            return false;

        return LorieView.connected();
    }

    public static void getRealMetrics(DisplayMetrics m) {
        if (getInstance() != null &&
                getInstance().getLorieView() != null &&
                getInstance().getLorieView().getDisplay() != null)
            getInstance().getLorieView().getDisplay().getRealMetrics(m);
    }

    public static void setCapturingEnabled(boolean enabled) {
        if (getInstance() == null || getInstance().mInputHandler == null)
            return;

        getInstance().mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        externalKeyboardConnected = connected;
        EditText textInput = findViewById(R.id.terminal_toolbar_text_input);
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || showIMEWhileExternalConnected);
        if (connected && !showIMEWhileExternalConnected)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);
        getLorieView().requestFocus();
    }

    private void showRenameDisplayDialog(int displayIndex) {
        String prefName = "com.termux.x11_preferences_display" + displayIndex;
        SharedPreferences displayPrefs = getSharedPreferences(prefName, Context.MODE_PRIVATE);
        String currentLabel = displayPrefs.getString("displayCustomLabel", "");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("Display " + displayIndex);
        input.setText(currentLabel);
        input.setSelectAllOnFocus(true);
        layout.addView(input);

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename Display " + displayIndex)
            .setView(layout)
            .setPositiveButton("Save", (dialog, which) -> {
                String newLabel = input.getText().toString().trim();
                displayPrefs.edit().putString("displayCustomLabel", newLabel).commit();
                updateDashboardUI();
                
                Intent intent = new Intent(ACTION_PREFERENCES_CHANGED);
                intent.putExtra("key", "displayCustomLabel");
                intent.putExtra("fromBroadcast", true);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
