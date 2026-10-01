#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POJAV="$REPO_ROOT/pojav-core/app_pojavlauncher"
OVERLAY="$REPO_ROOT/mikael_overlay"

if [[ ! -d "$POJAV" ]]; then
  echo "Pojav submodule is not initialized. Clone with --recurse-submodules." >&2
  exit 1
fi

mkdir -p "$POJAV/src/main/java/net/kdt/pojavlaunch" "$POJAV/src/main/res/layout-land" "$POJAV/src/main/res/layout" "$POJAV/src/main/res/drawable" "$POJAV/src/main/res/values"
cp "$OVERLAY/src/main/java/net/kdt/pojavlaunch/"*.java "$POJAV/src/main/java/net/kdt/pojavlaunch/"
cp "$OVERLAY/src/main/res/layout-land/"*.xml "$POJAV/src/main/res/layout-land/"
cp "$OVERLAY/src/main/res/layout/"*.xml "$POJAV/src/main/res/layout/"
cp "$OVERLAY/src/main/res/drawable/"*.xml "$POJAV/src/main/res/drawable/"
cp "$OVERLAY/src/main/res/values/mikael_"*.xml "$POJAV/src/main/res/values/"
# Replace legacy Pojav branding references with Mikael assets before deleting the files.
python3 - "$POJAV/src/main" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])

for suffix in ("*.xml", "*.java", "*.kt"):
    for p in root.rglob(suffix):
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        updated = text.replace("ic_pojav_full", "ic_mikael_logo").replace(
            "ic_setting_sign_in_background", "bg_mikael_gradient"
        )
        if p.name == "GameActivity.java":
            updated = updated.replace(
                'setTitle("MojoLauncher (" + version + ")");',
                'setTitle("Mikael Launcher V3.1 (" + version + ")");'
            )
        if p.name == "ControlLayout.java":
            old_method = """public void loadLayout(String jsonPath) throws IOException, JsonSyntaxException {
        CustomControls layout = LayoutConverter.loadAndConvertIfNecessary(jsonPath);
        if(layout != null) {
            loadLayout(layout);
            updateLoadedFileName(jsonPath);
            return;
        }
        throw new IOException("Unsupported control layout version");
    }"""
            new_method = """public void loadLayout(String jsonPath) throws IOException, JsonSyntaxException {
        try {
            File file = new File(jsonPath);
            if (!file.isFile() || file.length() == 0) {
                Toast.makeText(getContext(), "O arquivo de controles está vazio ou não existe.", Toast.LENGTH_LONG).show();
                Log.e("MikaelControls", "Control layout is missing or empty: " + jsonPath);
                return;
            }
            CustomControls layout = LayoutConverter.loadAndConvertIfNecessary(jsonPath);
            if(layout != null) {
                loadLayout(layout);
                updateLoadedFileName(jsonPath);
                return;
            }
            throw new IOException("Unsupported control layout version");
        } catch (JsonSyntaxException e) {
            Toast.makeText(getContext(), "Esse arquivo de controles está corrompido ou em formato inválido.", Toast.LENGTH_LONG).show();
            Log.e("MikaelControls", "Invalid control layout: " + jsonPath, e);
        } catch (IOException e) {
            Toast.makeText(getContext(), "Não foi possível abrir esse arquivo de controles.", Toast.LENGTH_LONG).show();
            Log.e("MikaelControls", "Could not load control layout: " + jsonPath, e);
        }
    }"""
            if old_method in updated:
                updated = updated.replace(old_method, new_method)
        if updated != text:
            p.write_text(updated, encoding="utf-8")
PY

# Remove unused upstream Pojav branding images from the launcher package.
rm -f "$POJAV/src/main/res/drawable/ic_pojav_full.webp"
rm -f "$POJAV/src/main/res/drawable/ic_setting_sign_in_background.webp"
rm -f "$POJAV/src/main/assets/pojavlauncher.png"

python3 - "$POJAV/src/main/AndroidManifest.xml" <<'PY'
import re, sys
from pathlib import Path
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
s=re.sub(r'\s*<activity\s+android:name="net\.kdt\.pojavlaunch\.TestStorageActivity".*?</activity>', '', s, count=1, flags=re.S)

activity='''\n        <activity
            android:name="net.kdt.pojavlaunch.MikaelHomeActivity"
            android:exported="true"
            android:icon="@drawable/ic_mikael_logo"
            android:label="@string/mikael_launcher_name"
            android:screenOrientation="fullSensor"
            android:theme="@style/MikaelLauncherTheme"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
'''
if 'net.kdt.pojavlaunch.MikaelHomeActivity' not in s:
    s=s.replace('</application>', activity+'    </application>', 1)
s=s.replace('android:name="net.kdt.pojavlaunch.LauncherActivity"\n            android:label="@string/app_short_name"', 'android:name="net.kdt.pojavlaunch.LauncherActivity"\n            android:exported="false"\n            android:label="@string/app_short_name"', 1)
# Use only Mikael launcher branding for the installed application icon and label.
s=s.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_mikael_logo"', 1)
s=s.replace('android:roundIcon="@mipmap/ic_launcher_round"', 'android:roundIcon="@drawable/ic_mikael_logo"', 1)
s=s.replace('android:label="@string/app_name"', 'android:label="@string/mikael_launcher_name"', 1)
p.write_text(s, encoding='utf-8')
PY

python3 - "$POJAV/build.gradle" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
s=s.replace('applicationId "net.kdt.pojavlaunch"', 'applicationId "com.mikael.launcher.v31"', 1)
s=s.replace('versionName getVersionName()', 'versionName "3.1"', 1)
s=s.replace('resValue "string", "app_name", "Mikael Launcher V3.1"', 'resValue "string", "app_name", "Mikael Launcher V3.1"', 1)
s=s.replace('resValue "string", "app_short_name", "Mikael Launcher"', 'resValue "string", "app_short_name", "Mikael Launcher"', 1)
p.write_text(s, encoding='utf-8')
PY

python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/authenticator/impl/MicrosoftBackgroundLogin.java" \
          "$POJAV/src/main/java/net/kdt/pojavlaunch/authenticator/impl/ElyByBackgroundLogin.java" \
          "$POJAV/src/main/java/net/kdt/pojavlaunch/fragments/MainMenuFragment.java" <<'PY'
import sys
from pathlib import Path
for raw in sys.argv[1:]:
    p=Path(raw); s=p.read_text(encoding='utf-8')
    if p.name in {'MicrosoftBackgroundLogin.java','ElyByBackgroundLogin.java'}:
        replacements={
            'Log.i("MicrosoftLogin", "isRefresh=" + isRefresh + ", authCode= "+code);':'Log.i("MicrosoftLogin", "Authentication exchange started; sensitive values redacted.");',
            'Log.i("MicrosoftLogin","Xbl Token = "+jo.getString("Token"));':'Log.i("MicrosoftLogin","XBL authentication completed; token redacted.");',
            'Log.i("MicroAuth", req);':'Log.i("MicroAuth", "XSTS request sent; sensitive body redacted.");',
            'Log.i("MicrosoftLogin","Xbl Xsts = " + token + "; Uhs = " + uhs);':'Log.i("MicrosoftLogin","XSTS authentication completed; token redacted.");',
            'Log.i("MicrosoftLogin","MC token: "+jo.getString("access_token"));':'Log.i("MicrosoftLogin","Minecraft token acquired; token redacted.");'
        }
        for a,b in replacements.items(): s=s.replace(a,b)
    else:
        mapping={
          'mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));':'if (mNewsButton != null) mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));',
          'mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));':'if (mDiscordButton != null) mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.social_media_invite)));',
          'mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));':'if (mCustomControlButton != null) mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));',
          'mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation());':'if (mInstallJarButton != null) mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation());',
          'mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));':'if (mEditProfileButton != null && mVersionSpinner != null) mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));',
          'mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));':'if (mShareLogsButton != null) mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));',
          'mOpenDirectoryButton.setOnClickListener((v)-> openGameDirectory(v.getContext()));':'if (mOpenDirectoryButton != null) mOpenDirectoryButton.setOnClickListener((v)-> openGameDirectory(v.getContext()));'
        }
        for a,b in mapping.items(): s=s.replace(a,b)
        s=s.replace('mPlayButton.setOnClickListener(v -> {','if (mPlayButton != null) mPlayButton.setOnClickListener(v -> {')
        s=s.replace('mNewsButton.setOnLongClickListener((v)->{','if (mNewsButton != null) mNewsButton.setOnLongClickListener((v)->{')
    p.write_text(s, encoding='utf-8')
PY

python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/authenticator/accounts/Account.java" "$POJAV/src/main/java/net/kdt/pojavlaunch/authenticator/accounts/Accounts.java" <<'PY'
import re, sys
from pathlib import Path

account=Path(sys.argv[1])
s=account.read_text(encoding='utf-8')
old=r'''public void save\(\) throws IOException \{\s*FileUtils.ensureParentDirectory\(mSaveLocation\);\s*JSONUtils.writeToFile\(mSaveLocation, this\);\s*\}'''
new='''public void save() throws IOException {
        FileUtils.ensureParentDirectory(mSaveLocation);
        String originalAccessToken = accessToken;
        String originalRefreshToken = refreshToken;
        try {
            accessToken = net.kdt.pojavlaunch.AccountSecureStore.protect(accessToken);
            refreshToken = net.kdt.pojavlaunch.AccountSecureStore.protect(refreshToken);
            JSONUtils.writeToFile(mSaveLocation, this);
        } finally {
            accessToken = originalAccessToken;
            refreshToken = originalRefreshToken;
        }
    }'''
s,n=re.subn(old,new,s,count=1,flags=re.S)
if n != 1: raise SystemExit('Account.save anchor not found')
old2=r'''(if\(account == null\) return null;\s*)(account\.mSaveLocation = mSaveLocation;)'''
new2=r'''\1account.accessToken = net.kdt.pojavlaunch.AccountSecureStore.restore(account.accessToken);
            account.refreshToken = net.kdt.pojavlaunch.AccountSecureStore.restore(account.refreshToken);
            \2'''
s,n=re.subn(old2,new2,s,count=1,flags=re.S)
if n != 1: raise SystemExit('Account.reload anchor not found')
account.write_text(s,encoding='utf-8')

accounts=Path(sys.argv[2])
s=accounts.read_text(encoding='utf-8')
old3=r'''(\s*if\(acc == null\) return null;\s*)(acc\.mSaveLocation = source;)'''
new3=r'''\1acc.accessToken = net.kdt.pojavlaunch.AccountSecureStore.restore(acc.accessToken);
        acc.refreshToken = net.kdt.pojavlaunch.AccountSecureStore.restore(acc.refreshToken);
        \2'''
s,n=re.subn(old3,new3,s,count=1,flags=re.S)
if n != 1: raise SystemExit('Accounts.loadAccount anchor not found')
accounts.write_text(s,encoding='utf-8')
PY


# Add Mikael performance quick settings: Minecraft's own F3 debug FPS and a live JVM RAM overlay.
python3 - "$POJAV/src/main/res/layout/dialog_quick_setting.xml" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1]); s=p.read_text(encoding="utf-8")
anchor='    <!-- button transparency seekbar -->'
insert='''    <!-- Mikael performance settings -->
    <Switch
        android:id="@+id/mikael_show_fps"
        android:layout_width="match_parent"
        android:layout_height="@dimen/_36sdp"
        android:text="📊 Mostrar FPS do Minecraft (F3)"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/editGestureDelay_seekbar"
        tools:ignore="UseSwitchCompatOrMaterialXml" />

    <Switch
        android:id="@+id/mikael_show_ram"
        android:layout_width="match_parent"
        android:layout_height="@dimen/_36sdp"
        android:text="🧠 Mostrar RAM"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/mikael_show_fps"
        tools:ignore="UseSwitchCompatOrMaterialXml" />

'''
if anchor not in s: raise SystemExit("layout anchor not found")
s=s.replace(anchor,insert+anchor,1)
s=s.replace('''    <!-- button transparency seekbar -->
    <TextView
        android:id="@+id/buttonTransparency_textView"''', '''    <!-- button transparency seekbar -->
    <TextView
        android:id="@+id/buttonTransparency_textView"''')
s=s.replace('''        android:text="@string/preference_button_transparency"

        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/editGestureDelay_seekbar" />''','''        android:text="@string/preference_button_transparency"

        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/mikael_show_ram" />''',1)
p.write_text(s,encoding="utf-8")
PY

python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/prefs/QuickSettingSideDialog.java" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1]); s=p.read_text(encoding="utf-8")
s=s.replace('private Switch mGyroSwitch, mGyroXSwitch, mGyroYSwitch, mGestureSwitch;',
            'private Switch mGyroSwitch, mGyroXSwitch, mGyroYSwitch, mGestureSwitch, mMikaelFpsSwitch, mMikaelRamSwitch;')
s=s.replace('private boolean mOriginalGyroEnabled, mOriginalGyroXEnabled, mOriginalGyroYEnabled, mOriginalGestureDisabled;',
            'private boolean mOriginalGyroEnabled, mOriginalGyroXEnabled, mOriginalGyroYEnabled, mOriginalGestureDisabled, mOriginalMikaelFps, mOriginalMikaelRam;')
s=s.replace('mGestureSwitch = mDialogContent.findViewById(R.id.checkboxGesture);',
            'mGestureSwitch = mDialogContent.findViewById(R.id.checkboxGesture);\n        mMikaelFpsSwitch = mDialogContent.findViewById(R.id.mikael_show_fps);\n        mMikaelRamSwitch = mDialogContent.findViewById(R.id.mikael_show_ram);')
s=s.replace('mOriginalGestureDisabled = PREF_DISABLE_GESTURES;',
            'mOriginalGestureDisabled = PREF_DISABLE_GESTURES;\n        mOriginalMikaelFps = LauncherPreferences.DEFAULT_PREF.getBoolean("mikael_show_fps", false);\n        mOriginalMikaelRam = LauncherPreferences.DEFAULT_PREF.getBoolean("mikael_show_ram", false);')
s=s.replace('mGestureSwitch.setChecked(mOriginalGestureDisabled);',
            'mGestureSwitch.setChecked(mOriginalGestureDisabled);\n        mMikaelFpsSwitch.setChecked(mOriginalMikaelFps);\n        mMikaelRamSwitch.setChecked(mOriginalMikaelRam);')
anchor='        mGestureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {'
idx=s.index(anchor)
# Insert the two listeners immediately before gesture listener.
listeners='''        mMikaelFpsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mEditor.putBoolean("mikael_show_fps", isChecked);
            onMikaelFpsChanged(isChecked);
        });

        mMikaelRamSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mEditor.putBoolean("mikael_show_ram", isChecked);
            onMikaelRamChanged(isChecked);
        });

'''
s=s[:idx]+listeners+s[idx:]
s=s.replace('mGestureSwitch.setOnCheckedChangeListener(null);',
            'mGestureSwitch.setOnCheckedChangeListener(null);\n        mMikaelFpsSwitch.setOnCheckedChangeListener(null);\n        mMikaelRamSwitch.setOnCheckedChangeListener(null);')
s=s.replace('PREF_DISABLE_GESTURES = mOriginalGestureDisabled;',
            'PREF_DISABLE_GESTURES = mOriginalGestureDisabled;\n            mMikaelFpsSwitch.setChecked(mOriginalMikaelFps);\n            mMikaelRamSwitch.setChecked(mOriginalMikaelRam);\n            onMikaelFpsChanged(mOriginalMikaelFps);\n            onMikaelRamChanged(mOriginalMikaelRam);')
anchor2='    /**\n     * Called when the resolution is changed.'
methods='''    /** Called when the Mikael FPS toggle changes. */
    public void onMikaelFpsChanged(boolean enabled) {}

    /** Called when the Mikael RAM toggle changes. */
    public void onMikaelRamChanged(boolean enabled) {}

'''
s=s.replace(anchor2,methods+anchor2,1)
p.write_text(s,encoding="utf-8")
PY

python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/game/GameActivity.java" <<'PY'
import sys
from pathlib import Path
p=Path(sys.argv[1]); s=p.read_text(encoding="utf-8")
# fields
s=s.replace('private QuickSettingSideDialog mQuickSettingSideDialog;',
'''private QuickSettingSideDialog mQuickSettingSideDialog;
    private TextView mMikaelRamOverlay;
    private final Runnable mMikaelRamUpdater = new Runnable() {
        @Override public void run() {
            if (mMikaelRamOverlay == null) return;
            long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long max = Runtime.getRuntime().maxMemory();
            mMikaelRamOverlay.setText(String.format(java.util.Locale.US, "RAM: %.0f / %.0f MB",
                    used / 1048576f, max / 1048576f));
            Tools.MAIN_HANDLER.postDelayed(this, 500);
        }
    };''')
# setup overlay after initLayout
s=s.replace('        initLayout(R.layout.activity_basemain);',
'''        initLayout(R.layout.activity_basemain);
        setupMikaelPerformanceOverlay();''',1)
# Add helper methods before openQuickSettings
anchor='    private void openQuickSettings() {'
methods='''    private void setupMikaelPerformanceOverlay() {
        ViewGroup content = findViewById(R.id.content_frame);
        if (content == null) return;
        mMikaelRamOverlay = new TextView(this);
        mMikaelRamOverlay.setTextColor(Color.WHITE);
        mMikaelRamOverlay.setTextSize(13f);
        mMikaelRamOverlay.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mMikaelRamOverlay.setBackgroundColor(0x99000000);
        mMikaelRamOverlay.setPadding(12, 6, 12, 6);
        mMikaelRamOverlay.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        lp.setMargins(12, 12, 0, 0);
        content.addView(mMikaelRamOverlay, lp);
    }

    private void setMikaelRamOverlayEnabled(boolean enabled) {
        if (mMikaelRamOverlay == null) return;
        if (enabled) {
            mMikaelRamOverlay.setVisibility(View.VISIBLE);
            mMikaelRamUpdater.run();
        } else {
            mMikaelRamOverlay.setVisibility(View.GONE);
            Tools.MAIN_HANDLER.removeCallbacks(mMikaelRamUpdater);
        }
    }

    private void setMikaelMinecraftFpsEnabled(boolean enabled) {
        // Minecraft itself owns the FPS counter. F3 is the vanilla debug overlay,
        // so the value shown comes from Minecraft rather than an Android-side estimate.
        if (enabled) {
            CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_F3);
        } else {
            CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_F3);
        }
    }

'''
if anchor not in s: raise SystemExit("quick settings anchor missing")
s=s.replace(anchor,methods+anchor,1)
# callbacks in anonymous dialog after button transparency callback
needle='''                public void onButtonTransparencyChanged() {
                    mControlLayout.updateButtonOpacity();
                }
'''
repl=needle+'''                @Override
                public void onMikaelFpsChanged(boolean enabled) {
                    setMikaelMinecraftFpsEnabled(enabled);
                }

                @Override
                public void onMikaelRamChanged(boolean enabled) {
                    setMikaelRamOverlayEnabled(enabled);
                }
'''
if needle not in s: raise SystemExit("callback anchor missing")
s=s.replace(needle,repl,1)
# destroy cleanup
s=s.replace('''    protected void onDestroy() {
        super.onDestroy();
        ContextExecutor.clearActivity();
    }''','''    protected void onDestroy() {
        Tools.MAIN_HANDLER.removeCallbacks(mMikaelRamUpdater);
        super.onDestroy();
        ContextExecutor.clearActivity();
    }''',1)
p.write_text(s,encoding="utf-8")
PY

echo "Mikael overlay prepared successfully."
