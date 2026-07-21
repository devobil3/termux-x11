package com.termux.x11;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.system.Os.getuid;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;

import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;

import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.InputDevice;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;

import java.io.StringWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

@SuppressWarnings("deprecation")
public class LoriePreferences extends AppCompatActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    static final String ACTION_PREFERENCES_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED";
    private static Prefs prefs = null;
    protected String mSourcePrefName;
    private AlertDialog mManageTemplatesDialog;
    public boolean mHasChanges = false;

    public static String getProcessName(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        int pid = android.os.Process.myPid();
        android.app.ActivityManager manager = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null && manager.getRunningAppProcesses() != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
                if (processInfo.pid == pid) {
                    return processInfo.processName;
                }
            }
        }
        return ctx.getPackageName();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction()) &&
                    intent.getBooleanExtra("fromBroadcast", false))
                updatePreferencesLayout();
        }
    };

    private final ContentObserver accessibilityObserver = new ContentObserver(null) {
        private final Runnable updateLayout = () -> updatePreferencesLayout();

        @Override
        public void onChange(boolean selfChange) {
            handler.removeCallbacks(updateLayout);
            handler.postDelayed(updateLayout, 200);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }
    };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            updatePreferencesLayout();
    }

    private void updatePreferencesLayout() {
        getSupportFragmentManager().getFragments().forEach(fragment -> {
            if (fragment instanceof LoriePreferenceFragment)
                ((LoriePreferenceFragment) fragment).updatePreferencesLayout();
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mSourcePrefName = getIntent().getStringExtra("display_pref_name");
        if (mSourcePrefName == null) {
            mSourcePrefName = getPackageName() + "_preferences";
        }
        
        initializeDisplayPrefsIfEmpty(mSourcePrefName);
        
        // Clone source preferences to temporary session file
        cloneSharedPreferences(mSourcePrefName, "com.termux.x11_temp_session");
        getIntent().putExtra("display_pref_name", "com.termux.x11_temp_session");

        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new LoriePreferenceFragment(null)).commit();

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }
        updateTitle("Preferences");

        Uri ENABLED_ACCESSIBILITY_SERVICES = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        Uri ACCESSIBILITY_ENABLED = Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED);

        getContentResolver().registerContentObserver(ENABLED_ACCESSIBILITY_SERVICES, true, accessibilityObserver);
        getContentResolver().registerContentObserver(ACCESSIBILITY_ENABLED, true, accessibilityObserver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        
        mSourcePrefName = intent.getStringExtra("display_pref_name");
        if (mSourcePrefName == null) {
            mSourcePrefName = getPackageName() + "_preferences";
        }
        
        initializeDisplayPrefsIfEmpty(mSourcePrefName);
        
        cloneSharedPreferences(mSourcePrefName, "com.termux.x11_temp_session");
        intent.putExtra("display_pref_name", "com.termux.x11_temp_session");
        
        prefs = new Prefs(this);
        updateTitle("Preferences");
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, new LoriePreferenceFragment(null)).commit();
    }

    public void updateTitle(CharSequence fragmentTitle) {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            String prefix = "Global";
            if (mSourcePrefName.contains("_display")) {
                String suffix = mSourcePrefName.substring(mSourcePrefName.indexOf("_display") + 8);
                SharedPreferences displayPrefs = getSharedPreferences(mSourcePrefName, MODE_PRIVATE);
                String customLabel = displayPrefs.getString("displayCustomLabel", "");
                if (!customLabel.isEmpty()) {
                    prefix = customLabel;
                } else {
                    prefix = "Display " + suffix;
                }
            }
            actionBar.setTitle(prefix + " - " + fragmentTitle);
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ACTION_PREFERENCES_CHANGED);
        registerReceiver(receiver, filter, SDK_INT >= Build.VERSION_CODES.TIRAMISU ? RECEIVER_NOT_EXPORTED : 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            MenuItem saveTemplateItem = menu.add(android.view.Menu.NONE, 1001, android.view.Menu.NONE, "Save Template");
            saveTemplateItem.setIcon(android.R.drawable.ic_menu_add);
            saveTemplateItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            
            MenuItem manageItem = menu.add(android.view.Menu.NONE, 1002, android.view.Menu.NONE, "Templates");
            manageItem.setIcon(android.R.drawable.ic_menu_manage);
            manageItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            MenuItem saveItem = menu.add(android.view.Menu.NONE, 1005, android.view.Menu.NONE, "Save");
            saveItem.setIcon(android.R.drawable.ic_menu_save);
            saveItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0)
                onBackPressed();
            else {
                getSupportFragmentManager().popBackStack();
                handler.postDelayed(this::invalidateOptionsMenu, 100);
            }
            return true;
        } else if (id == 1001) {
            showSaveTemplateDialog();
            return true;
        } else if (id == 1002) {
            showManageTemplatesDialog();
            return true;
        } else if (id == 1005) {
            saveCurrentPreferences();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showUnsavedChangesDialog(Runnable onConfirmExit) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Unsaved Changes")
            .setMessage("You have unsaved changes. Do you want to save them before exiting?")
            .setPositiveButton("Save & Exit", (dialog, which) -> {
                saveCurrentPreferences();
                onConfirmExit.run();
            })
            .setNegativeButton("Discard & Exit", (dialog, which) -> {
                onConfirmExit.run();
            })
            .setNeutralButton("Cancel", null)
            .show();
    }

    @Override
    public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            handler.postDelayed(this::invalidateOptionsMenu, 100);
        } else if (mHasChanges) {
            showUnsavedChangesDialog(super::onBackPressed);
        } else {
            super.onBackPressed();
        }
    }

    public String getDisplayPrefName() {
        return "com.termux.x11_temp_session";
    }

    public static void cloneSharedPreferences(Context context, String sourceName, String destName) {
        SharedPreferences source = context.getSharedPreferences(sourceName, Context.MODE_PRIVATE);
        SharedPreferences dest = context.getSharedPreferences(destName, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = dest.edit();
        editor.clear(); // Clear destination first
        for (java.util.Map.Entry<String, ?> entry : source.getAll().entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) val);
            else if (val instanceof Float) editor.putFloat(entry.getKey(), (Float) val);
            else if (val instanceof Integer) editor.putInt(entry.getKey(), (Integer) val);
            else if (val instanceof Long) editor.putLong(entry.getKey(), (Long) val);
            else if (val instanceof String) editor.putString(entry.getKey(), (String) val);
        }
        editor.commit();
    }

    private void cloneSharedPreferences(String sourceName, String destName) {
        cloneSharedPreferences(this, sourceName, destName);
    }

    private void initializeDisplayPrefsIfEmpty(String sourcePrefName) {
        if (sourcePrefName != null && sourcePrefName.contains("_display")) {
            SharedPreferences displayPrefs = getSharedPreferences(sourcePrefName, Context.MODE_PRIVATE);
            if (displayPrefs.getAll().isEmpty()) {
                SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);
                try {
                    int d = Integer.parseInt(sourcePrefName.substring(sourcePrefName.indexOf("_display") + 8));
                    String assignedTemplate = defaultPrefs.getString("display_assignment_" + d, "");
                    String templatePrefsName = "";
                    if (!assignedTemplate.isEmpty()) {
                        templatePrefsName = assignedTemplate;
                    } else {
                        templatePrefsName = "com.termux.x11_preferences_template";
                    }
                    SharedPreferences templatePrefs = getSharedPreferences(templatePrefsName, Context.MODE_PRIVATE);
                    if (!templatePrefs.getAll().isEmpty()) {
                        cloneSharedPreferences(templatePrefsName, sourcePrefName);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void saveCurrentPreferences() {
        cloneSharedPreferences("com.termux.x11_temp_session", mSourcePrefName);
        mHasChanges = false;
        Toast.makeText(this, "Preferences saved.", Toast.LENGTH_SHORT).show();
        
        Intent intent = new Intent(ACTION_PREFERENCES_CHANGED);
        intent.putExtra("fromBroadcast", true);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private String getTemplatePrefsName(String templateName) {
        return templateName;
    }

    private boolean isValidTemplateName(String name) {
        return name.matches("^[a-zA-Z0-9_-]+$");
    }

    private void showSaveTemplateDialog() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Template name");
        layout.addView(input);

        final TextView errorText = new TextView(this);
        errorText.setTextColor(0xFFEF4444);
        errorText.setVisibility(View.GONE);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        layout.addView(errorText, lp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Save Preference Template");
        builder.setView(layout);
        builder.setPositiveButton("Save", null);
        builder.setNegativeButton("Cancel", null);

        final AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                errorText.setText("Name cannot be empty");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            if (name.equals("Default")) {
                errorText.setText("The Default template is unwritable.");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            if (!isValidTemplateName(name)) {
                errorText.setText("Invalid name. Please use only letters, numbers, hyphens (-), and underscores (_), with no spaces.");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            dialog.dismiss();
            if (getCustomTemplates().contains(name)) {
                new AlertDialog.Builder(this)
                    .setTitle("Overwrite Template")
                    .setMessage("A template named '" + name + "' already exists. Do you want to overwrite it?")
                    .setPositiveButton("Overwrite", (d, w) -> saveTemplate(name))
                    .setNegativeButton("Cancel", null)
                    .show();
            } else {
                saveTemplate(name);
            }
        });
    }

    private void saveTemplate(String name) {
        String templatePrefsName = getTemplatePrefsName(name);
        cloneSharedPreferences("com.termux.x11_temp_session", templatePrefsName);
        
        SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        java.util.Set<String> templates = new java.util.HashSet<>(getCustomTemplates());
        templates.add(name);
        
        SharedPreferences.Editor editor = defaultPrefs.edit();
        editor.putStringSet("custom_templates_list", templates);
        
        if (mSourcePrefName.contains("_display")) {
            String suffix = mSourcePrefName.substring(mSourcePrefName.indexOf("_display") + 8);
            try {
                int displayNum = Integer.parseInt(suffix);
                editor.putString("display_assignment_" + displayNum, name);
            } catch (NumberFormatException ignored) {}
        }
        
        editor.commit();
        Toast.makeText(this, "Saved template: " + name, Toast.LENGTH_SHORT).show();
    }

    private java.util.Set<String> getCustomTemplates() {
        SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        java.util.Set<String> templates = defaultPrefs.getStringSet("custom_templates_list", null);
        if (templates == null) {
            templates = new java.util.HashSet<>();
        }
        
        SharedPreferences defaultTemplatePrefs = getSharedPreferences("Default", Context.MODE_PRIVATE);
        if (defaultTemplatePrefs.getAll().isEmpty()) {
            cloneSharedPreferences(getPackageName() + "_preferences", "Default");
        }
        SharedPreferences templatePrefs = getSharedPreferences("com.termux.x11_preferences_template", Context.MODE_PRIVATE);
        if (templatePrefs.getAll().isEmpty()) {
            cloneSharedPreferences("Default", "com.termux.x11_preferences_template");
        }
        
        return templates;
    }

    private View createManageTemplatesTitleView() {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        container.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        int paddingLeftRight = (int) (24 * getResources().getDisplayMetrics().density);
        int paddingTopBottom = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(paddingLeftRight, paddingTopBottom, paddingLeftRight, paddingTopBottom);
        
        android.widget.TextView titleTv = new android.widget.TextView(this);
        titleTv.setText("Manage Templates");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            titleTv.setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title);
        } else {
            titleTv.setTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Title);
        }
        
        android.widget.LinearLayout.LayoutParams titleParams = new android.widget.LinearLayout.LayoutParams(
            0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        container.addView(titleTv, titleParams);
        
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true);
        int buttonPadding = (int) (8 * getResources().getDisplayMetrics().density);
        
        android.widget.ImageButton backupBtn = new android.widget.ImageButton(this);
        backupBtn.setImageResource(android.R.drawable.ic_menu_share);
        backupBtn.setBackgroundResource(outValue.resourceId);
        backupBtn.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
        backupBtn.setOnClickListener(v -> {
            if (mManageTemplatesDialog != null) mManageTemplatesDialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "termux_x11_backup.json");
            startActivityForResult(intent, 2001);
        });
        container.addView(backupBtn);
        
        android.widget.ImageButton restoreBtn = new android.widget.ImageButton(this);
        restoreBtn.setImageResource(android.R.drawable.ic_menu_revert);
        restoreBtn.setBackgroundResource(outValue.resourceId);
        restoreBtn.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
        restoreBtn.setOnClickListener(v -> {
            if (mManageTemplatesDialog != null) mManageTemplatesDialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent, 2002);
        });
        container.addView(restoreBtn);
        
        return container;
    }

    private void showManageTemplatesDialog() {
        java.util.Set<String> customTemplates = getCustomTemplates();
        java.util.Set<String> templatesSet = new java.util.HashSet<>(customTemplates);
        templatesSet.add("Default");
        
        final String[] templatesArray = templatesSet.toArray(new String[0]);
        java.util.Arrays.sort(templatesArray);
        
        SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        String defaultTemplate = defaultPrefs.getString("default_template_name", "Default");
        
        String[] displayLabels = new String[templatesArray.length];
        for (int i = 0; i < templatesArray.length; i++) {
            String name = templatesArray[i];
            StringBuilder sb = new StringBuilder(name);
            boolean isDefault = name.equals(defaultTemplate);
            
            java.util.List<String> assignedDisplays = new java.util.ArrayList<>();
            java.util.Set<String> openedSet = defaultPrefs.getStringSet("opened_displays_set", new java.util.HashSet<>());
            java.util.List<String> sortedOpened = new java.util.ArrayList<>(openedSet);
            java.util.Collections.sort(sortedOpened, (a, b) -> {
                try {
                    return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                } catch (NumberFormatException e) {
                    return a.compareTo(b);
                }
            });
            for (String dStr : sortedOpened) {
                try {
                    int d = Integer.parseInt(dStr);
                    String assigned = defaultPrefs.getString("display_assignment_" + d, "");
                    boolean isUsingThis = name.equals(assigned) || (isDefault && assigned.isEmpty());
                    if (isUsingThis) {
                        String prefName = "com.termux.x11_preferences_display" + d;
                        SharedPreferences displayPrefs = getSharedPreferences(prefName, MODE_PRIVATE);
                        String customLabel = displayPrefs.getString("displayCustomLabel", "");
                        if (!customLabel.isEmpty()) {
                            assignedDisplays.add(customLabel + " (" + d + ")");
                        } else {
                            assignedDisplays.add(String.valueOf(d));
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
            
            if (isDefault || !assignedDisplays.isEmpty()) {
                sb.append(" (");
                boolean hasText = false;
                if (isDefault) {
                    sb.append("Default");
                    hasText = true;
                }
                if (!assignedDisplays.isEmpty()) {
                    if (hasText) {
                        sb.append(", ");
                    }
                    sb.append("Displays: ");
                    for (int j = 0; j < assignedDisplays.size(); j++) {
                        sb.append(assignedDisplays.get(j));
                        if (j < assignedDisplays.size() - 1) sb.append(", ");
                    }
                }
                sb.append(")");
            }
            displayLabels[i] = sb.toString();
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCustomTitle(createManageTemplatesTitleView());
        builder.setItems(displayLabels, (dialog, which) -> {
            String selectedTemplate = templatesArray[which];
            showTemplateOptionsDialog(selectedTemplate);
        });
        builder.setNegativeButton("Close", null);
        mManageTemplatesDialog = builder.create();
        mManageTemplatesDialog.show();
    }

    private void showTemplateOptionsDialog(String templateName) {
        String[] options = {
            "Set as Default Preference",
            "Apply to Display",
            "Rename",
            "Delete"
        };
        
        final boolean isDefault = templateName.equals("Default");
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Options for: " + templateName);
        
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, options) {
            @Override
            public boolean isEnabled(int position) {
                if (isDefault && (position == 2 || position == 3)) {
                    return false;
                }
                return super.isEnabled(position);
            }
            
            @NonNull
            @Override
            public android.view.View getView(int position, android.view.View convertView, @NonNull android.view.ViewGroup parent) {
                android.view.View view = super.getView(position, convertView, parent);
                android.widget.TextView textView = (android.widget.TextView) view.findViewById(android.R.id.text1);
                if (isDefault && (position == 2 || position == 3)) {
                    textView.setTextColor(android.graphics.Color.GRAY);
                } else {
                    android.util.TypedValue typedValue = new android.util.TypedValue();
                    getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
                    if (typedValue.resourceId != 0) {
                        textView.setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), typedValue.resourceId));
                    } else {
                        textView.setTextColor(typedValue.data);
                    }
                }
                return view;
            }
        };
        
        builder.setAdapter(adapter, (dialog, which) -> {
            if (which == 0) {
                cloneSharedPreferences(getTemplatePrefsName(templateName), "com.termux.x11_preferences_template");
                SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
                defaultPrefs.edit().putString("default_template_name", templateName).commit();
                Toast.makeText(this, "'" + templateName + "' is now the default template.", Toast.LENGTH_SHORT).show();
            } else if (which == 1) {
                showAssignToDisplayDialog(templateName);
            } else if (which == 2) {
                showRenameTemplateDialog(templateName);
            } else if (which == 3) {
                deleteTemplate(templateName);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showAssignToDisplayDialog(String templateName) {
        SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        java.util.Set<String> openedSet = defaultPrefs.getStringSet("opened_displays_set", new java.util.HashSet<>());
        
        if (openedSet.isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Apply to Display");
            builder.setMessage("No displays have been opened yet. Start a display from Termux first.");
            builder.setPositiveButton("OK", null);
            builder.show();
            return;
        }
        
        final String[] displaysArray = openedSet.toArray(new String[0]);
        java.util.Arrays.sort(displaysArray, (a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } catch (NumberFormatException e) {
                return a.compareTo(b);
            }
        });
        
        String[] displayLabels = new String[displaysArray.length];
        for (int i = 0; i < displaysArray.length; i++) {
            String d = displaysArray[i];
            String prefName = "com.termux.x11_preferences_display" + d;
            SharedPreferences displayPrefs = getSharedPreferences(prefName, MODE_PRIVATE);
            String customLabel = displayPrefs.getString("displayCustomLabel", "");
            if (!customLabel.isEmpty()) {
                displayLabels[i] = customLabel + " (Display " + d + ")";
            } else {
                displayLabels[i] = "Display " + d;
            }
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Apply to Display");
        builder.setItems(displayLabels, (dialog, which) -> {
            String displayStr = displaysArray[which];
            try {
                int displayNum = Integer.parseInt(displayStr);
                cloneSharedPreferences(getTemplatePrefsName(templateName), "com.termux.x11_preferences_display" + displayNum);
                defaultPrefs.edit().putString("display_assignment_" + displayNum, templateName).commit();
                Toast.makeText(this, "Template '" + templateName + "' applied to Display " + displayNum, Toast.LENGTH_SHORT).show();
            } catch (NumberFormatException ignored) {}
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showRenameTemplateDialog(String templateName) {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(templateName);
        input.setSelectAllOnFocus(true);
        layout.addView(input);

        final TextView errorText = new TextView(this);
        errorText.setTextColor(0xFFEF4444);
        errorText.setVisibility(View.GONE);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        layout.addView(errorText, lp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Template");
        builder.setView(layout);
        builder.setPositiveButton("Rename", null);
        builder.setNegativeButton("Cancel", null);

        final AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                errorText.setText("Name cannot be empty");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            if (newName.equals("Default")) {
                errorText.setText("The Default template is unwritable.");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            if (!isValidTemplateName(newName)) {
                errorText.setText("Invalid name. Please use only letters, numbers, hyphens (-), and underscores (_), with no spaces.");
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            if (newName.equals(templateName)) {
                dialog.dismiss();
                return;
            }
            
            dialog.dismiss();
            if (getCustomTemplates().contains(newName)) {
                new AlertDialog.Builder(this)
                    .setTitle("Overwrite Template")
                    .setMessage("A template named '" + newName + "' already exists. Do you want to overwrite it?")
                    .setPositiveButton("Overwrite", (d, w) -> renameTemplate(templateName, newName))
                    .setNegativeButton("Cancel", null)
                    .show();
            } else {
                renameTemplate(templateName, newName);
            }
        });
    }

    private void renameTemplate(String oldName, String newName) {
        cloneSharedPreferences(getTemplatePrefsName(oldName), getTemplatePrefsName(newName));
        getSharedPreferences(getTemplatePrefsName(oldName), Context.MODE_PRIVATE).edit().clear().commit();
        
        SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
        java.util.Set<String> templates = new java.util.HashSet<>(getCustomTemplates());
        templates.remove(oldName);
        templates.add(newName);
        
        SharedPreferences.Editor editor = defaultPrefs.edit();
        editor.putStringSet("custom_templates_list", templates);
        
        if (oldName.equals(defaultPrefs.getString("default_template_name", "Default"))) {
            editor.putString("default_template_name", newName);
        }
        
        for (int d = 1; d <= 5; d++) {
            if (oldName.equals(defaultPrefs.getString("display_assignment_" + d, ""))) {
                editor.putString("display_assignment_" + d, newName);
            }
        }
        
        editor.commit();
        Toast.makeText(this, "Renamed template to: " + newName, Toast.LENGTH_SHORT).show();
    }

    private void deleteTemplate(String templateName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Delete Template");
        builder.setMessage("Are you sure you want to delete template '" + templateName + "'?");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            getSharedPreferences(getTemplatePrefsName(templateName), Context.MODE_PRIVATE).edit().clear().commit();
            
            SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
            java.util.Set<String> templates = new java.util.HashSet<>(getCustomTemplates());
            templates.remove(templateName);
            
            SharedPreferences.Editor editor = defaultPrefs.edit();
            editor.putStringSet("custom_templates_list", templates);
            
            if (templateName.equals(defaultPrefs.getString("default_template_name", "Default"))) {
                editor.remove("default_template_name");
            }
            
            for (int d = 1; d <= 5; d++) {
                if (templateName.equals(defaultPrefs.getString("display_assignment_" + d, ""))) {
                    editor.remove("display_assignment_" + d);
                }
            }
            
            editor.commit();
            Toast.makeText(this, "Deleted template: " + templateName, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private String generateBackupJson() {
        try {
            org.json.JSONObject backup = new org.json.JSONObject();
            
            SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
            String defaultTemplate = defaultPrefs.getString("default_template_name", "Default");
            backup.put("default_template_name", defaultTemplate);
            
            org.json.JSONObject assignments = new org.json.JSONObject();
            for (int d = 1; d <= 5; d++) {
                String assigned = defaultPrefs.getString("display_assignment_" + d, "");
                if (!assigned.isEmpty()) {
                    assignments.put(String.valueOf(d), assigned);
                }
            }
            backup.put("display_assignments", assignments);
            
            java.util.Set<String> templatesSet = getCustomTemplates();
            org.json.JSONArray templatesArray = new org.json.JSONArray();
            org.json.JSONObject templatesData = new org.json.JSONObject();
            
            for (String name : templatesSet) {
                templatesArray.put(name);
                
                SharedPreferences templatePrefs = getSharedPreferences(getTemplatePrefsName(name), MODE_PRIVATE);
                org.json.JSONObject prefsObj = new org.json.JSONObject();
                for (java.util.Map.Entry<String, ?> entry : templatePrefs.getAll().entrySet()) {
                    prefsObj.put(entry.getKey(), entry.getValue());
                }
                templatesData.put(name, prefsObj);
            }
            
            backup.put("templates_list", templatesArray);
            backup.put("templates_data", templatesData);
            
            return backup.toString(4);
        } catch (Exception e) {
            Log.e("LoriePreferences", "Failed to generate backup JSON", e);
            return null;
        }
    }

    private void restoreBackupFromJson(String jsonStr) {
        try {
            org.json.JSONObject backup = new org.json.JSONObject(jsonStr);
            SharedPreferences defaultPrefs = getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
            SharedPreferences.Editor defaultEditor = defaultPrefs.edit();
            
            String defaultTemplate = backup.optString("default_template_name", "Default");
            defaultEditor.putString("default_template_name", defaultTemplate);
            
            org.json.JSONObject assignments = backup.optJSONObject("display_assignments");
            for (int d = 1; d <= 5; d++) {
                defaultEditor.remove("display_assignment_" + d);
            }
            if (assignments != null) {
                java.util.Iterator<String> keys = assignments.keys();
                while (keys.hasNext()) {
                    String dStr = keys.next();
                    String templateName = assignments.getString(dStr);
                    defaultEditor.putString("display_assignment_" + dStr, templateName);
                }
            }
            
            org.json.JSONArray templatesArray = backup.optJSONArray("templates_list");
            org.json.JSONObject templatesData = backup.optJSONObject("templates_data");
            
            java.util.Set<String> templatesSet = new java.util.HashSet<>();
            if (templatesArray != null && templatesData != null) {
                for (int i = 0; i < templatesArray.length(); i++) {
                    String name = templatesArray.getString(i);
                    templatesSet.add(name);
                    
                    org.json.JSONObject prefsObj = templatesData.optJSONObject(name);
                    if (prefsObj != null) {
                        SharedPreferences templatePrefs = getSharedPreferences(getTemplatePrefsName(name), MODE_PRIVATE);
                        SharedPreferences.Editor tempEditor = templatePrefs.edit();
                        tempEditor.clear();
                        java.util.Iterator<String> keys = prefsObj.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            Object val = prefsObj.get(key);
                            if (val instanceof Boolean) tempEditor.putBoolean(key, (Boolean) val);
                            else if (val instanceof Double) tempEditor.putFloat(key, ((Double) val).floatValue());
                            else if (val instanceof Integer) tempEditor.putInt(key, (Integer) val);
                            else if (val instanceof Long) tempEditor.putLong(key, (Long) val);
                            else if (val instanceof String) tempEditor.putString(key, (String) val);
                        }
                        tempEditor.commit();
                    }
                }
            }
            
            defaultEditor.putStringSet("custom_templates_list", templatesSet);
            defaultEditor.commit();
            
            if (!defaultTemplate.isEmpty()) {
                cloneSharedPreferences(getTemplatePrefsName(defaultTemplate), "com.termux.x11_preferences_template");
            }
            
            Toast.makeText(this, "Backup restored successfully!", Toast.LENGTH_SHORT).show();
            updatePreferencesLayout();
        } catch (Exception e) {
            Log.e("LoriePreferences", "Failed to restore backup", e);
            Toast.makeText(this, "Error: Invalid backup file format.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == 2001) {
                String json = generateBackupJson();
                if (json != null) {
                    try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        Toast.makeText(this, "Backup saved!", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Log.e("LoriePreferences", "Error writing backup", e);
                        Toast.makeText(this, "Failed to save backup file.", Toast.LENGTH_SHORT).show();
                    }
                }
            } else if (requestCode == 2002) {
                try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                     java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    restoreBackupFromJson(sb.toString());
                } catch (Exception e) {
                    Log.e("LoriePreferences", "Error reading backup", e);
                    Toast.makeText(this, "Failed to read backup file.", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void showFragment(PreferenceFragmentCompat fragment) {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(android.R.id.content, fragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        final LoriePreferenceFragment fragment = new LoriePreferenceFragment(pref.getFragment());
        fragment.setTargetFragment(caller, 0);
        showFragment(fragment);
        return true;
    }

    public static class LoriePreferenceFragment extends PreferenceFragmentCompat implements OnPreferenceChangeListener {
        private final Runnable updateLayout = this::updatePreferencesLayout;
        private static final Method onSetInitialValue;
        static {
            try {
                onSetInitialValue = Preference.class.getDeclaredMethod("onSetInitialValue", boolean.class, Object.class);
                onSetInitialValue.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        void onSetInitialValue(Preference p) {
            try {
                onSetInitialValue.invoke(p, false, null);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        final String root;
        /** @noinspection unused*/ // Used by `androidx.fragment.app.Fragment.instantiate`...
        public LoriePreferenceFragment() {
            this(null);
        }

        public LoriePreferenceFragment(String root) {
            this.root = root;
        }

        @Override
        public void onResume() {
            super.onResume();
            //noinspection DataFlowIssue
            ((LoriePreferences) getActivity()).updateTitle(getPreferenceScreen().getTitle());
            getActivity().invalidateOptionsMenu();
        }

        /** @noinspection SameParameterValue*/
        private void with(CharSequence key, Consumer<Preference> action) {
            Preference p = findPreference(key);
            if (p != null)
                action.accept(p);
        }

        @SuppressLint("DiscouragedApi")
        int findId(String name) {
            //noinspection DataFlowIssue
            return getResources().getIdentifier("lorie_pref_" + name, "string", getContext().getPackageName());
        }

        /** @noinspection DataFlowIssue*/
        @Override @SuppressLint("ApplySharedPref")
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            getPreferenceManager().setPreferenceDataStore(prefs);

            if ((Integer.parseInt(prefs.touchMode.get()) - 1) > 2)
                prefs.touchMode.put("1");

            setPreferencesFromResource(R.xml.preferences, root == null ? "main" : root);

            int id;
            PreferenceScreen screen = getPreferenceScreen();
            if ((id = findId(screen.getKey())) != 0)
                getPreferenceScreen().setTitle(getResources().getString(id));
            for (int i=0; i<getPreferenceScreen().getPreferenceCount(); i++) {
                Preference p = screen.getPreference(i);
                p.setOnPreferenceChangeListener(this);
                p.setPreferenceDataStore(prefs);

                if ((id = findId(p.getKey())) != 0)
                    p.setTitle(getResources().getString(id));

                if ((id = findId(p.getKey() + "_summary")) != 0)
                    p.setSummary(getResources().getString(id));

                if (p instanceof ListPreference) {
                    ListPreference list = (ListPreference) p;
                    list.setEntries(prefs.keys.get(p.getKey()).asList().getEntries());
                    list.setEntryValues(prefs.keys.get(p.getKey()).asList().getValues());
                    list.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                }
            }

            with("showAdditionalKbd", p -> p.setLayoutResource(R.layout.preference));
            with("version", p -> p.setSummary(BuildConfig.VERSION_NAME));

            setSummary("displayStretch", R.string.lorie_pref_summary_requiresExactOrCustom);
            setSummary("adjustResolution", R.string.lorie_pref_summary_requiresExactOrCustom);
            setSummary("pauseKeyInterceptingWithEsc", R.string.lorie_pref_summary_requiresIntercepting);
            setSummary("scaleTouchpad", R.string.lorie_pref_summary_requiresTrackpadAndNative);

            if (!SamsungDexUtils.available())
                setVisible("dexMetaKeyCapture", false);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
                setVisible("hideCutout", false);

            boolean stylusAvailable = Arrays.stream(InputDevice.getDeviceIds())
                    .mapToObj(InputDevice::getDevice)
                    .filter(Objects::nonNull)
                    .anyMatch(d -> d.supportsSource(InputDevice.SOURCE_STYLUS));

            setVisible("showStylusClickOverride", stylusAvailable);
            setVisible("stylusIsMouse", stylusAvailable);
            setVisible("stylusButtonContactModifierMode", stylusAvailable);

            setNoActionOptionText(findPreference("volumeDownAction"), "android volume control");
            setNoActionOptionText(findPreference("volumeUpAction"), "android volume control");
            setNoActionOptionText(findPreference("mediaKeysAction"), "android media control");
        }

        private void setSummary(CharSequence key, int disabled) {
            Preference pref = findPreference(key);
            if (pref != null)
                pref.setSummaryProvider(new Preference.SummaryProvider<>() {
                    @Nullable @Override public CharSequence provideSummary(@NonNull Preference p) {
                        return p.isEnabled() ? null : getResources().getString(disabled);
                    }
                });
        }

        private void setVisible(CharSequence key, boolean value) {
            Preference p = findPreference(key);
            if (p != null)
                p.setVisible(value);
        }

        private void setEnabled(CharSequence key, boolean value) {
            Preference p = findPreference(key);
            if (p != null)
                p.setEnabled(value);
        }

        @SuppressWarnings("ConstantConditions")
        void updatePreferencesLayout() {
            if (getContext() == null)
                return;

            for (String key : prefs.keys.keySet()) {
                Preference p = findPreference(key);
                if (p != null)
                    onSetInitialValue(p);
            }

            String displayResMode = prefs.displayResolutionMode.get();
            setVisible("displayScale", displayResMode.contentEquals("scaled"));
            setVisible("displayResolutionExact", displayResMode.contentEquals("exact"));
            setVisible("displayResolutionCustom", displayResMode.contentEquals("custom"));

            setEnabled("dexMetaKeyCapture", !prefs.enableAccessibilityServiceAutomatically.get());
            setEnabled("enableAccessibilityServiceAutomatically", !prefs.dexMetaKeyCapture.get());
            setEnabled("pauseKeyInterceptingWithEsc", prefs.dexMetaKeyCapture.get() ||
                    prefs.enableAccessibilityServiceAutomatically.get() ||
                    KeyInterceptor.isLaunched());
            setEnabled("enableAccessibilityServiceAutomatically", prefs.enableAccessibilityServiceAutomatically.get() || KeyInterceptor.isLaunched());
            setEnabled("filterOutWinkey", prefs.enableAccessibilityServiceAutomatically.get() || KeyInterceptor.isLaunched());

            boolean displayStretchEnabled = "exact".contentEquals(prefs.displayResolutionMode.get()) || "custom".contentEquals(prefs.displayResolutionMode.get());
            setEnabled("displayStretch", displayStretchEnabled);
            setEnabled("adjustResolution", displayStretchEnabled);

            setEnabled("scaleTouchpad", "1".equals(prefs.touchMode.get()) && !"native".equals(prefs.displayResolutionMode.get()));
            setEnabled("showMouseHelper", "1".equals(prefs.touchMode.get()));

            boolean requestNotificationPermissionVisible =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(requireContext(), POST_NOTIFICATIONS) == PERMISSION_DENIED;
            setVisible("requestNotificationPermission", requestNotificationPermissionVisible);

            if (getActivity() instanceof LoriePreferences) {
                boolean isDisplaySpecific = ((LoriePreferences) getActivity()).mSourcePrefName.contains("_display");
                setVisible("displayCustomLabel", isDisplaySpecific);
            }

            Preference customLabelPref = findPreference("displayCustomLabel");
            if (customLabelPref != null) {
                String val = prefs.get().getString("displayCustomLabel", "");
                if (val.isEmpty()) {
                    customLabelPref.setSummary("Not set");
                } else {
                    customLabelPref.setSummary(val);
                }
            }
        }

        /** @noinspection SameParameterValue*/
        private void setNoActionOptionText(Preference preference, CharSequence text) {
            if (preference == null)
                return;
            ListPreference p = (ListPreference) preference;
            CharSequence[] options = p.getEntries();
            for (int i=0; i<options.length; i++) {
                if ("no action".contentEquals(options[i]))
                    options[i] = "no action (" + text + ")";
            }
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            updatePreferencesLayout();
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull Preference p) {
            if (p.getKey() == null)
                return super.onPreferenceTreeClick(p);

            if ("version".contentEquals(p.getKey())) {
                Context ctx = getContext();
                if (ctx != null) {
                    ((ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText(p.getSummary(), p.getSummary()));
                    Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && "requestNotificationPermission".contentEquals(p.getKey())) {
                ActivityCompat.requestPermissions(requireActivity(), new String[]{POST_NOTIFICATIONS}, 101);
                return true;
            }

            updatePreferencesLayout();
            return super.onPreferenceTreeClick(p);
        }

        @SuppressLint("ApplySharedPref")
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (getActivity() instanceof LoriePreferences) {
                ((LoriePreferences) getActivity()).mHasChanges = true;
            }
            String key = preference.getKey();
            Log.e("Preferences", "changed preference: " + key);
            handler.removeCallbacks(updateLayout);
            handler.postDelayed(updateLayout, 50);

            if ("displayScale".contentEquals(key)) {
                int scale = (Integer) newValue;
                if (scale % 10 != 0) {
                    scale = Math.round( ( (float) scale ) / 10 ) * 10;
                    ((SeekBarPreference) preference).setValue(scale);
                    return false;
                }
            }

            if ("displayResolutionCustom".contentEquals(key)) {
                String value = (String) newValue;
                try {
                    String[] resolution = value.split("x");
                    int width = Integer.parseInt(resolution[0]);
                    int height = Integer.parseInt(resolution[1]);
                    if (width <= 0 || height <= 0)
                        throw new NumberFormatException();
                } catch (NumberFormatException | PatternSyntaxException ignored) {
                    Toast.makeText(getActivity(), "Wrong resolution format", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            if ("showAdditionalKbd".contentEquals(key) && (Boolean) newValue)
                prefs.additionalKbdVisible.put(true);

            if ("enableAccessibilityServiceAutomatically".contentEquals(key)) {
                if (!((Boolean) newValue))
                    KeyInterceptor.shutdown(false);
                if (requireContext().checkSelfPermission(WRITE_SECURE_SETTINGS) != PERMISSION_GRANTED) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Permission denied")
                            .setMessage("Android requires WRITE_SECURE_SETTINGS permission to start accessibility service automatically.\n" +
                                    "Please, launch this command using ADB:\n" +
                                    "adb shell pm grant com.termux.x11 android.permission.WRITE_SECURE_SETTINGS")
                            .setNegativeButton("OK", null)
                            .create()
                            .show();
                    return false;
                }
            }
            
            requireContext().sendBroadcast(new Intent(ACTION_PREFERENCES_CHANGED) {{
                putExtra("key", key);
                putExtra("fromBroadcast", true);
                setPackage("com.termux.x11");
            }});

            return true;
        }

        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if ("extra_keys_config".contentEquals(preference.getKey())) {
                @SuppressLint("InflateParams")
                View view = getLayoutInflater().inflate(R.layout.extra_keys_config, null, false);
                EditText config = view.findViewById(R.id.extra_keys_config);
                config.setTypeface(Typeface.MONOSPACE);
                config.setText(prefs.extra_keys_config.get());
                TextView desc = view.findViewById(R.id.extra_keys_config_description);
                desc.setLinksClickable(true);
                desc.setText(R.string.extra_keys_config_desc);
                desc.setMovementMethod(LinkMovementMethod.getInstance());
                new android.app.AlertDialog.Builder(getActivity())
                        .setView(view)
                        .setTitle("Extra keys config")
                        .setPositiveButton("OK",
                                (dialog, whichButton) -> {
                                    String text = config.getText().toString();
                                    prefs.extra_keys_config.put(!text.isEmpty() ? text : TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS);
                                }
                        )
                        .setNeutralButton("Reset",
                                (dialog, whichButton) -> prefs.extra_keys_config.put(TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS))
                        .setNegativeButton("Cancel", (dialog, whichButton) -> dialog.dismiss())
                        .create()
                        .show();
            } else super.onDisplayPreferenceDialog(preference);
        }
    }

    public static class Receiver extends BroadcastReceiver {
        public Receiver() {
            super();
        }

        @Override
        public IBinder peekService(Context myContext, Intent service) {
            return super.peekService(myContext, service);
        }

        /** @noinspection StringConcatenationInLoop*/
        @SuppressLint("ApplySharedPref")
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent != null ? intent.getBundleExtra(null) : null;
            IBinder ibinder = bundle != null ? bundle.getBinder(null) : null;
            IRemoteCmdImterface remote = ibinder != null ? IRemoteCmdImterface.Stub.asInterface(ibinder) : null;

            try {
                if (intent != null && intent.getExtras() != null) {
                    Prefs p = (MainActivity.getInstance() != null) ? new Prefs(MainActivity.getInstance()) : (prefs != null ? prefs : new Prefs(context));
                    if (intent.getStringExtra("list") != null) {
                        String result = "";
                        for (PrefsProto.Preference pref : p.keys.values()) {
                            if (pref.type == String.class)
                                result += "\"" + pref.key + "\"=\"" + pref.asString().get() + "\"\n";
                            else if (pref.type == int.class)
                                result += "\"" + pref.key + "\"=\"" + pref.asInt().get() + "\"\n";
                            else if (pref.type == boolean.class)
                                result += "\"" + pref.key + "\"=\"" + pref.asBoolean().get() + "\"\n";
                            else if (pref.type == String[].class) {
                                String[] entries = context.getResources().getStringArray(pref.asList().entries);
                                String[] values = context.getResources().getStringArray(pref.asList().values);
                                String value = pref.asList().get();
                                int index = Arrays.asList(values).indexOf(value);
                                if (index != -1)
                                    value = entries[index];
                                result += "\"" + pref.key + "\"=\"" + value + "\"\n";
                            }
                        }

                        sendResponse(remote, 0, 2, result.substring(0, result.length() - 1));
                        return;
                    }

                    SharedPreferences.Editor edit = p.get().edit();
                    for (String key : intent.getExtras().keySet()) {
                        if (key == null)
                            continue;
                        String newValue = intent.getStringExtra(key);
                        if (newValue == null)
                            continue;

                        switch (key) {
                            case "displayResolutionCustom": {
                                try {
                                    String[] resolution = newValue.split("x");
                                    int width = Integer.parseInt(resolution[0]);
                                    int height = Integer.parseInt(resolution[1]);
                                    if (width <= 0 || height <= 0)
                                        throw new NumberFormatException();
                                } catch (NumberFormatException | PatternSyntaxException ignored) {
                                    sendResponse(remote, 1, 1, "displayResolutionCustom: Wrong resolution format.");
                                    return;
                                }

                                edit.putString("displayResolutionCustom", newValue);
                                break;
                            }
                            case "enableAccessibilityServiceAutomatically": {
                                if (!"true".equals(newValue))
                                    KeyInterceptor.shutdown(false);
                                else if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PERMISSION_GRANTED) {
                                    sendResponse(remote, 1, 1, "Permission denied.\n" +
                                            "Android requires WRITE_SECURE_SETTINGS permission to change `enableAccessibilityServiceAutomatically` setting.\n" +
                                            "Please, launch this command using ADB:\n" +
                                            "adb shell pm grant com.termux.x11 android.permission.WRITE_SECURE_SETTINGS");
                                    return;
                                }

                                edit.putBoolean("enableAccessibilityServiceAutomatically", "true".contentEquals(newValue));
                                break;
                            }
                            case "extra_keys_config": {
                                edit.putString(key, newValue);
                                break;
                            }
                            default: {
                                PrefsProto.Preference pref = p.keys.get(key);
                                if (pref != null && pref.type == boolean.class) {
                                    edit.putBoolean(key, "true".contentEquals(newValue));
                                    if ("showAdditionalKbd".contentEquals(key) && "true".contentEquals(newValue))
                                        edit.putBoolean("additionalKbdVisible", true);
                                } else if (pref != null && pref.type == int.class) {
                                    try {
                                        edit.putInt(key, Integer.parseInt(newValue));
                                    } catch (NumberFormatException | PatternSyntaxException exception) {
                                        sendResponse(remote, 1, 4, key + ": failed to parse integer: " + exception);
                                        return;
                                    }
                                } else if (pref != null && pref.type == String[].class) {
                                    PrefsProto.ListPreference _p = (PrefsProto.ListPreference) pref;
                                    String[] entries = _p.getEntries();
                                    String[] values = _p.getValues();
                                    int index = Arrays.asList(entries).indexOf(newValue);

                                    if (index == -1 && _p.entries != _p.values)
                                        index = Arrays.asList(values).indexOf(newValue);

                                    if (index != -1) {
                                        edit.putString(key, values[index]);
                                        break;
                                    }

                                    sendResponse(remote, 1, 1, key + ": can not be set to \"" + newValue + "\", possible options are " + Arrays.toString(entries) + (_p.entries != _p.values ? " or " + Arrays.toString(values) : ""));
                                    return;
                                } else {
                                    sendResponse(remote, 1, 4, key + ": unrecognised option");
                                    return;
                                }
                            }
                        }

                        Intent intent0 = new Intent(ACTION_PREFERENCES_CHANGED);
                        intent0.putExtra("key", key);
                        intent0.putExtra("fromBroadcast", true);
                        intent0.setPackage("com.termux.x11");
                        context.sendBroadcast(intent0);
                    }
                    edit.commit();
                }

                sendResponse(remote, 0, 2, "Done");
            } catch (Exception e) {
                sendResponse(remote, 1, 4, e.toString());
            }
        }

        void sendResponse(IRemoteCmdImterface remote, int status, int oldStatus, String text) {
            if (remote != null) {
                try {
                    remote.exit(status, text);
                } catch (RemoteException ex) {
                    Log.e("LoriePreferences", "Failed to send response to commandline proxy", ex);
                }
            } else if (isOrderedBroadcast()) {
                setResultCode(oldStatus);
                setResultData(text);
            }
        }

        // For changing preferences from commandline
        private static final IBinder iface = new IRemoteCmdImterface.Stub() {
            @Override
            public void exit(int code, String output) {
                System.out.println(output);
                CmdEntryPoint.handler.post(() -> System.exit(code));
            }
        };

        private static void help() {
            System.err.print("termux-x11-preference [list] {key:value} [{key2:value2}]...");
            System.exit(0);
        }

        @Keep
        @SuppressLint("WrongConstant")
        public static void main(String[] args) {
            android.util.Log.i("LoriePreferences$Receiver", "commit " + BuildConfig.COMMIT);
            //noinspection resource
            ParcelFileDescriptor in = ParcelFileDescriptor.adoptFd(0);
            Intent i = new Intent("com.termux.x11.CHANGE_PREFERENCE");
            Bundle bundle = new Bundle();
            boolean inputIsFile = !android.system.Os.isatty(in.getFileDescriptor());

            in.detachFd();
            bundle.putBinder(null, iface);
            i.setPackage("com.termux.x11");
            i.putExtra(null, bundle);
            if (getuid() == 0 || getuid() == 2000)
                i.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);

            if (inputIsFile && System.in != null) {
                Scanner scanner = new Scanner(System.in);
                String line;
                String[] v;
                while (scanner.hasNextLine()) {
                    line = scanner.nextLine();
                    if (!line.contains("="))
                        help();

                    v = line.split("=");
                    if (v[0].startsWith("\"") && v[0].endsWith("\""))
                        v[0] = v[0].substring(1, v[0].length() - 1);
                    if (v[1].startsWith("\"") && v[1].endsWith("\""))
                        v[1] = v[1].substring(1, v[1].length() - 1);
                    i.putExtra(v[0], v[1]);
                }
            }

            for (String a: args) {
                if ("list".equals(a)) {
                    i.putExtra("list", "");
                } else if (a != null && a.contains(":")) {
                    String[] v = a.split(":");
                    i.putExtra(v[0], v[1]);
                } else
                    help();
            }

            CmdEntryPoint.handler.post(() -> CmdEntryPoint.sendBroadcast(i));
            CmdEntryPoint.handler.postDelayed(() -> {
                System.err.println("Failed to obtain response from app.");
                System.exit(1);
            }, 5000);
            Looper.loop();
        }
    }

    static Handler handler = Looper.getMainLooper() != null ? new Handler(Looper.getMainLooper()) : null;

    public void onClick(View view) {
        showFragment(new LoriePreferenceFragment("ekbar"));
    }

    /** @noinspection unused*/
    @SuppressLint("ApplySharedPref")
    public static class PrefsProto extends PreferenceDataStore {
        public static class Preference {
            protected final String key;
            protected final Class<?> type;
            protected final Object defValue;
            protected Preference(String key, Class<?> class_, Object default_) {
                this.key = key;
                this.type = class_;
                this.defValue = default_;
            }

            public ListPreference asList() {
                return (ListPreference) this;
            }

            public StringPreference asString() {
                return (StringPreference) this;
            }

            public IntPreference asInt() {
                return (IntPreference) this;
            }

            public BooleanPreference asBoolean() {
                return (BooleanPreference) this;
            }
        }

        public class BooleanPreference extends Preference {
            public BooleanPreference(String key, boolean defValue) {
                super(key, boolean.class, defValue);
            }

            public boolean get() {
                if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(key))
                    return builtInDisplayPreferences.getBoolean(key, (boolean) defValue);

                return preferences.getBoolean(key, (boolean) defValue);
            }

            public void put(boolean v) {
                if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(key)) {
                    builtInDisplayPreferences.edit().putBoolean(key, v).commit();
                    recheckStoringSecondaryDisplayPreferences();
                }

                preferences.edit().putBoolean(key, v).commit();
            }
        }

        public class IntPreference extends Preference {
            public IntPreference(String key, int defValue) {
                super(key, int.class, defValue);
            }

            public int get() {
                return preferences.getInt(key, (int) defValue);
            }

            public int defValue() {
                return preferences.getInt(key, (int) defValue);
            }
        }

        public class StringPreference extends Preference {
            public StringPreference(String key, String defValue) {
                super(key, String.class, defValue);
            }

            public String get() {
                return preferences.getString(key, (String) defValue);
            }

            public void put(String v) {
                preferences.edit().putString(key, v).commit();
            }
        }

        public class ListPreference extends Preference {
            private final int entries, values;

            public ListPreference(String key, String defValue, int entries, int values) {
                super(key, String[].class, defValue);
                this.entries = entries;
                this.values = values;
            }

            public String get() {
                return preferences.getString(key, (String) defValue);
            }

            public void put(String v) {
                preferences.edit().putString(key, v).commit();
            }

            public String[] getEntries() {
                return getArrayItems(entries, ctx.getResources());
            }

            public String[] getValues() {
                return getArrayItems(values, ctx.getResources());
            }

            private String[] getArrayItems(int resourceId, Resources resources) {
                ArrayList<String> itemList = new ArrayList<>();
                try(TypedArray typedArray = resources.obtainTypedArray(resourceId)) {
                    for (int i = 0; i < typedArray.length(); i++) {
                        int type = typedArray.getType(i);
                        if (type == TypedValue.TYPE_STRING) {
                            itemList.add(typedArray.getString(i));
                        } else if (type == TypedValue.TYPE_REFERENCE) {
                            int resIdOfArray = typedArray.getResourceId(i, 0);
                            itemList.addAll(Arrays.asList(resources.getStringArray(resIdOfArray)));
                        }
                    }
                }

                Object[] objectArray = itemList.toArray();
                return Arrays.copyOf(objectArray, objectArray.length, String[].class);
            }

        }

        static boolean storeSecondaryDisplayPreferencesSeparately = false;
        protected Context ctx;
        protected SharedPreferences preferences;
        protected SharedPreferences builtInDisplayPreferences;
        protected SharedPreferences secondaryDisplayPreferences;

        private PrefsProto() {} // No instantiation allowed
        protected PrefsProto(Context ctx) {
            this.ctx = ctx;
            String prefName = null;
            if (ctx instanceof android.app.Activity) {
                prefName = ((android.app.Activity) ctx).getIntent().getStringExtra("display_pref_name");
            }
            if (prefName == null) {
                String processName = getProcessName(ctx);
                if (processName.contains(":display")) {
                    String suffix = processName.substring(processName.indexOf(":display") + 8);
                    prefName = "com.termux.x11_preferences_display" + suffix;
                } else {
                    prefName = ctx.getPackageName() + "_preferences";
                }
            }
            builtInDisplayPreferences = ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
            secondaryDisplayPreferences = ctx.getSharedPreferences("secondary", Context.MODE_PRIVATE);
            recheckStoringSecondaryDisplayPreferences();
        }

        protected void recheckStoringSecondaryDisplayPreferences() {
            storeSecondaryDisplayPreferencesSeparately = builtInDisplayPreferences.getBoolean("storeSecondaryDisplayPreferencesSeparately", false);
            boolean isExternalDisplay = ((WindowManager) ctx.getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getDisplayId() != Display.DEFAULT_DISPLAY;
            preferences = (storeSecondaryDisplayPreferencesSeparately && isExternalDisplay) ? secondaryDisplayPreferences : builtInDisplayPreferences;
        }

        @Override public void putBoolean(String k, boolean v) {
            if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(k)) {
                builtInDisplayPreferences.edit().putBoolean(k, v).commit();
                recheckStoringSecondaryDisplayPreferences();
            } else
                preferences.edit().putBoolean(k, v).commit();
        }
        @Override public boolean getBoolean(String k, boolean d) {
            if ("storeSecondaryDisplayPreferencesSeparately".contentEquals(k))
                return builtInDisplayPreferences.getBoolean(k, d);
            return preferences.getBoolean(k, d);
        }
        @Override public void putString(String k, @Nullable String v) { prefs.get().edit().putString(k, v).commit(); }
        @Override public void putStringSet(String k, @Nullable Set<String> v) { prefs.get().edit().putStringSet(k, v).commit(); }
        @Override public void putInt(String k, int v) { prefs.get().edit().putInt(k, v).commit(); }
        @Override public void putLong(String k, long v) { prefs.get().edit().putLong(k, v).commit(); }
        @Override public void putFloat(String k, float v) { prefs.get().edit().putFloat(k, v).commit(); }
        @Nullable @Override public String getString(String k, @Nullable String d) { return prefs.get().getString(k, d); }
        @Nullable @Override public Set<String> getStringSet(String k, @Nullable Set<String> ds) { return prefs.get().getStringSet(k, ds); }
        @Override public int getInt(String k, int d) { return prefs.get().getInt(k, d); }
        @Override public long getLong(String k, long d) { return prefs.get().getLong(k, d); }
        @Override public float getFloat(String k, float d) { return prefs.get().getFloat(k, d); }

        public SharedPreferences get() {
            return preferences;
        }

        public boolean isSecondaryDisplayPreferences() {
            return preferences == secondaryDisplayPreferences;
        }
    }
}
