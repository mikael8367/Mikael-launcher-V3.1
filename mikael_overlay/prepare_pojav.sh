# APK build validation trigger
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
            old_catch = """        }catch (IOException | JsonSyntaxException e) {
            // Load an empty layout on exception to avoid breakage when adding buttons in the editor
            CustomControls customControls = new CustomControls();
            customControls.mLayoutBitmaps = LayoutBitmaps.createEmpty();
            loadLayout(customControls);
            throw e;
        }"""
            new_catch = """        }catch (Throwable e) {
            // Never crash the game because the default/custom control JSON is empty or corrupt.
            // The default layout can be regenerated from Pojav's built-in control definition.
            try {
                if (Tools.CTRLDEF_FILE.equals(jsonPath)) {
                    File target = new File(Tools.CTRLDEF_FILE);
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    CustomControls generated = new CustomControls(getContext());
                    generated.save(target.getAbsolutePath());
                    CustomControls repaired = LayoutConverter.loadAndConvertIfNecessary(size, target.getAbsolutePath());
                    loadLayout(repaired);
                    updateLoadedFileName(target.getAbsolutePath());
                    Log.w("MikaelControls", "Default control layout was corrupt and has been regenerated.", e);
                    return;
                }
            } catch (Throwable repairError) {
                Log.e("MikaelControls", "Failed to regenerate default control layout.", repairError);
            }

            // For a corrupt custom layout, repair it by falling back to the default layout.
            try {
                File target = new File(Tools.CTRLDEF_FILE);
                if (!target.isFile() || target.length() == 0) {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    CustomControls generated = new CustomControls(getContext());
                    generated.save(target.getAbsolutePath());
                }
                CustomControls repaired = LayoutConverter.loadAndConvertIfNecessary(size, target.getAbsolutePath());
                LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", target.getAbsolutePath()).apply();
                LauncherPreferences.PREF_DEFAULTCTRL_PATH = target.getAbsolutePath();
                loadLayout(repaired);
                updateLoadedFileName(target.getAbsolutePath());
                Log.w("MikaelControls", "Corrupt custom control layout replaced with default.", e);
                return;
            } catch (Throwable repairError) {
                Log.e("MikaelControls", "Failed to recover corrupt control layout.", repairError);
            }

            CustomControls customControls = new CustomControls();
            customControls.mLayoutBitmaps = LayoutBitmaps.createEmpty();
            loadLayout(customControls);
            Log.e("MikaelControls", "Control layout was invalid: " + jsonPath, e);
        }"""
            if old_catch in updated:
                updated = updated.replace(old_catch, new_catch, 1)
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


# Prevent OptiFine/base-game installers from waiting forever when the asynchronous
# downloader throws a RuntimeException before invoking its completion callback.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/tasks/MoJsonDownloader.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
old = """            } catch(RuntimeException e) {
                throw e; // log fatal errors to Google Play
            } catch (Exception e) {
                listener.onDownloadFailed(e);
            }"""
new = """            } catch(RuntimeException e) {
                // Always notify callers. Modloader installers (notably OptiFine)
                // wait for this callback and otherwise can remain stuck forever.
                listener.onDownloadFailed(e);
            } catch (Exception e) {
                listener.onDownloadFailed(e);
            }"""
if old not in s:
    raise SystemExit("MoJsonDownloader callback block not found")
p.write_text(s.replace(old, new, 1), encoding="utf-8")
PY

# Add a bounded wait to OptiFine's base-Minecraft preparation.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/modloaders/OptiFineDownloadTask.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
s = s.replace(
"""                new MoJsonDownloader().start(null, versionMeta, gameVersion, this);
                mDownloadLock.wait();""",
"""                new MoJsonDownloader().start(null, versionMeta, gameVersion, this);
                mDownloadLock.wait(180000L);
                if (mDownloaderThrowable == null && !MoJsonDownloader.createGameJarPath(gameVersion).isFile()) {
                    mDownloaderThrowable = new RuntimeException("Tempo esgotado ao preparar Minecraft " + gameVersion + ".");
                }""",
1)
p.write_text(s, encoding="utf-8")
PY

# Harden the generated control loader against partially valid JSON/layout objects.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/customcontrols/ControlLayout.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")

s = s.replace(
    "}catch (IOException | JsonSyntaxException e) {",
    "}catch (Throwable e) {",
    1
)

anchor = """\tpublic void loadLayout(CustomControls controlLayout) {
\t\tthis.mButtonsOpacity = (float) LauncherPreferences.PREF_BUTTON_TRANSPARENCY / 100;
\t\tboolean sanitizedModified = false;
\t\tif(controlLayout != null) {
\t\t\tsanitizedModified = LayoutSanitizer.sanitizeLayout(controlLayout);
\t\t}"""
replacement = """\tpublic void loadLayout(CustomControls controlLayout) {
\t\tthis.mButtonsOpacity = (float) LauncherPreferences.PREF_BUTTON_TRANSPARENCY / 100;
\t\tboolean sanitizedModified = false;
\t\tif(controlLayout != null) {
\t\t\tif(controlLayout.mControlDataList == null) controlLayout.mControlDataList = new ArrayList<>();
\t\t\tif(controlLayout.mDrawerDataList == null) controlLayout.mDrawerDataList = new ArrayList<>();
\t\t\tif(controlLayout.mJoystickDataList == null) controlLayout.mJoystickDataList = new ArrayList<>();
\t\t\tif(controlLayout.mLayoutBitmaps == null) controlLayout.mLayoutBitmaps = LayoutBitmaps.createEmpty();
\t\t\tif(Float.isNaN(controlLayout.scaledAt) || Float.isInfinite(controlLayout.scaledAt) || controlLayout.scaledAt <= 0) {
\t\t\t\tcontrolLayout.scaledAt = 100f;
\t\t\t}
\t\t\tsanitizedModified = LayoutSanitizer.sanitizeLayout(controlLayout);
\t\t\tfor(ControlData data : controlLayout.mControlDataList) {
\t\t\t\tif(Tools.isValidString(data.bitmapTag) && controlLayout.mLayoutBitmaps.getBitmap(data.bitmapTag) == null) {
\t\t\t\t\tdata.bitmapTag = null;
\t\t\t\t}
\t\t\t}
\t\t\tfor(ControlData data : controlLayout.mJoystickDataList) {
\t\t\t\tif(Tools.isValidString(data.bitmapTag) && controlLayout.mLayoutBitmaps.getBitmap(data.bitmapTag) == null) {
\t\t\t\t\tdata.bitmapTag = null;
\t\t\t\t}
\t\t\t}
\t\t\tfor(ControlDrawerData drawer : controlLayout.mDrawerDataList) {
\t\t\t\tif(drawer.orientation == null) drawer.orientation = ControlDrawerData.Orientation.LEFT;
\t\t\t\tif(Tools.isValidString(drawer.properties.bitmapTag) && controlLayout.mLayoutBitmaps.getBitmap(drawer.properties.bitmapTag) == null) {
\t\t\t\t\tdrawer.properties.bitmapTag = null;
\t\t\t\t}
\t\t\t\tfor(ControlData data : drawer.buttonProperties) {
\t\t\t\t\tif(Tools.isValidString(data.bitmapTag) && controlLayout.mLayoutBitmaps.getBitmap(data.bitmapTag) == null) {
\t\t\t\t\t\tdata.bitmapTag = null;
\t\t\t\t\t}
\t\t\t\t}
\t\t\t}
\t\t}"""
if anchor in s:
    s=s.replace(anchor,replacement,1)
elif replacement not in s:
    raise SystemExit("ControlLayout normalization anchor not found")
p.write_text(s, encoding="utf-8")
PY

# Make layout sanitization null-safe for partially broken JSON.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutSanitizer.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
s = s.replace(
"""    private static boolean isValidFormula(String formula) {
        return !formula.contains("Infinity") && !formula.contains("NaN");
    }""",
"""    private static boolean isValidFormula(String formula) {
        return formula != null && !formula.contains("Infinity") && !formula.contains("NaN");
    }""", 1)
s = s.replace(
"""    private static boolean isSaneData(ControlData controlData) {
        if(controlData.getWidth() == 0 || controlData.getHeight() == 0) return false;
        return isValidFormula(controlData.dynamicX) && isValidFormula(controlData.dynamicY);
    }""",
"""    private static boolean isSaneData(ControlData controlData) {
        if(controlData == null) return false;
        if(controlData.getWidth() <= 0 || controlData.getHeight() <= 0) return false;
        if(controlData.keycodes == null) return false;
        if(!isValidFormula(controlData.dynamicX) || !isValidFormula(controlData.dynamicY)) return false;
        try {
            controlData.insertDynamicPos(controlData.dynamicX, 1920, 1080);
            controlData.insertDynamicPos(controlData.dynamicY, 1920, 1080);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }""", 1)
s = s.replace(
"""    private static boolean checkEntry(Object entry) {
        if(entry instanceof ControlData) {""",
"""    private static boolean checkEntry(Object entry) {
        if(entry == null) return false;
        if(entry instanceof ControlData) {""", 1)
s = s.replace(
"""            ControlDrawerData drawerData = (ControlDrawerData) entry;
            if(!isSaneData(drawerData.properties)) return false;
            sanitizeList(drawerData.buttonProperties);""",
"""            ControlDrawerData drawerData = (ControlDrawerData) entry;
            if(drawerData.properties == null || drawerData.buttonProperties == null) return false;
            if(!isSaneData(drawerData.properties)) return false;
            sanitizeList(drawerData.buttonProperties);""", 1)
p.write_text(s, encoding="utf-8")
PY

# Harden LayoutSanitizer against partially decoded/corrupt control lists.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutSanitizer.java" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text(encoding="utf-8")
s=s.replace('''    private static boolean checkEntry(Object entry) {
        if(entry instanceof ControlData) {''','''    private static boolean checkEntry(Object entry) {
        if(entry == null) return false;
        if(entry instanceof ControlData) {''',1)
s=s.replace('''            ControlDrawerData drawerData = (ControlDrawerData) entry;
            if(!isSaneData(drawerData.properties)) return false;
            sanitizeList(drawerData.buttonProperties);''','''            ControlDrawerData drawerData = (ControlDrawerData) entry;
            if(drawerData.properties == null || drawerData.buttonProperties == null) return false;
            if(!isSaneData(drawerData.properties)) return false;
            sanitizeList(drawerData.buttonProperties);''',1)
s=s.replace('''    private static boolean sanitizeList(List<?> controlDataList) {
        boolean madeChanges = false;''','''    private static boolean sanitizeList(List<?> controlDataList) {
        if(controlDataList == null) return false;
        boolean madeChanges = false;''',1)
s=s.replace('''    public static boolean sanitizeLayout(CustomControls controls) {
        boolean madeChanges = sanitizeList(controls.mControlDataList);''','''    public static boolean sanitizeLayout(CustomControls controls) {
        if(controls == null) return false;
        boolean madeChanges = sanitizeList(controls.mControlDataList);''',1)
p.write_text(s,encoding="utf-8")
PY

# Validate null decoding explicitly before dereferencing a parsed layout.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/customcontrols/LayoutConverter.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
old = """            CustomControls layout = Tools.GLOBAL_GSON.fromJson(jsonLayoutData, CustomControls.class);

            if(layout.version > TARGET_VERSION)"""
new = """            CustomControls layout = Tools.GLOBAL_GSON.fromJson(jsonLayoutData, CustomControls.class);
            if(layout == null) throw new JsonSyntaxException("Control layout decoded to null");

            if(layout.version > TARGET_VERSION)"""
if old not in s:
    raise SystemExit("LayoutConverter null-check anchor not found")
s = s.replace(old,new,1)
p.write_text(s, encoding="utf-8")
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
s=s.replace('android:name="net.kdt.pojavlaunch.LauncherActivity"\n            android:label="@string/app_short_name"', 'android:name="net.kdt.pojavlaunch.LauncherActivity"\n            android:enabled="false"\n            android:exported="false"\n            android:label="@string/app_short_name"', 1)
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



# Prevent the legacy force-close action from restarting LauncherActivity.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/Tools.java" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text(encoding="utf-8")
old="""                        Tools.restartLauncherActivity(ctx);
                        Tools.fullyExit();"""
new="""                        try {
                            if (ctx instanceof android.app.Activity) {
                                android.app.Activity activity = (android.app.Activity) ctx;
                                activity.finishAndRemoveTask();
                            }
                        } catch (Throwable ignored) {}
                        Tools.fullyExit();"""
if old in s: s=s.replace(old,new,1)
p.write_text(s,encoding="utf-8")
PY

# Make the in-game exit action terminate the game activity instead of opening the legacy launcher.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/game/GameActivity.java" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text(encoding="utf-8")
old="""case 0: dialogForceClose(GameActivity.this); break;"""
new="""case 0:
    new android.app.AlertDialog.Builder(GameActivity.this)
        .setMessage(R.string.mcn_exit_confirm)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
            try {
                GameActivity.this.finishAndRemoveTask();
            } catch (Throwable ignored) {
                GameActivity.this.finish();
            }
        }).show();
    break;"""
if old not in s: raise SystemExit("GameActivity exit action not found")
s=s.replace(old,new,1)
p.write_text(s,encoding="utf-8")
PY

# Add Mikael performance quick settings: launcher-side FPS GUI and a live JVM RAM overlay.
# Explicit APK build trigger validation.
# Touch-control + Ely.by hardening build marker.
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
        android:text="📊 Mostrar FPS do Launcher"
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
if "public void onMikaelFpsChanged(boolean enabled)" not in s:
    marker = "    public abstract void onButtonTransparencyChanged();"
    extra = """    public void onMikaelFpsChanged(boolean enabled) {}

    public void onMikaelRamChanged(boolean enabled) {}

"""
    if marker not in s:
        raise SystemExit("QuickSetting callback marker not found")
    s=s.replace(marker, marker + extra, 1)
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
    private TextView mMikaelFpsOverlay;
    private long mMikaelFpsFrames;
    private long mMikaelFpsWindowStart;
    private boolean mMikaelFpsEnabled;
    private final android.view.Choreographer.FrameCallback mMikaelFpsCallback = new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!mMikaelFpsEnabled) return;
            mMikaelFpsFrames++;
            long now = android.os.SystemClock.elapsedRealtime();
            if (mMikaelFpsWindowStart == 0) mMikaelFpsWindowStart = now;
            long elapsed = now - mMikaelFpsWindowStart;
            if (elapsed >= 1000 && mMikaelFpsOverlay != null) {
                float fps = mMikaelFpsFrames * 1000f / elapsed;
                mMikaelFpsOverlay.setText(String.format(java.util.Locale.US, "FPS: %.0f", fps));
                mMikaelFpsFrames = 0;
                mMikaelFpsWindowStart = now;
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };
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

        mMikaelFpsOverlay = new TextView(this);
        mMikaelFpsOverlay.setTextColor(Color.WHITE);
        mMikaelFpsOverlay.setTextSize(13f);
        mMikaelFpsOverlay.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mMikaelFpsOverlay.setBackgroundColor(0x99000000);
        mMikaelFpsOverlay.setPadding(12, 6, 12, 6);
        mMikaelFpsOverlay.setText("FPS: --");
        mMikaelFpsOverlay.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams fpsLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fpsLp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        fpsLp.setMargins(12, 56, 0, 0);
        content.addView(mMikaelFpsOverlay, fpsLp);
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

    private void setMikaelFpsEnabled(boolean enabled) {
        mMikaelFpsEnabled = enabled;
        if (mMikaelFpsOverlay == null) return;
        if (enabled) {
            mMikaelFpsFrames = 0;
            mMikaelFpsWindowStart = android.os.SystemClock.elapsedRealtime();
            mMikaelFpsOverlay.setVisibility(View.VISIBLE);
            android.view.Choreographer.getInstance().removeFrameCallback(mMikaelFpsCallback);
            android.view.Choreographer.getInstance().postFrameCallback(mMikaelFpsCallback);
        } else {
            android.view.Choreographer.getInstance().removeFrameCallback(mMikaelFpsCallback);
            mMikaelFpsOverlay.setVisibility(View.GONE);
            mMikaelFpsOverlay.setText("FPS: --");
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
                    setMikaelFpsEnabled(enabled);
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
        android.view.Choreographer.getInstance().removeFrameCallback(mMikaelFpsCallback);
        super.onDestroy();
        ContextExecutor.clearActivity();
    }''',1)
p.write_text(s,encoding="utf-8")
PY

# Harden real modloader screens against activity teardown, parser failures and runtime exceptions.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/fragments/ModVersionListFragment.java" "$POJAV/src/main/java/net/kdt/pojavlaunch/fragments/ForgelikeInstallFragment.java" "$POJAV/src/main/java/net/kdt/pojavlaunch/fragments/FabriclikeInstallFragment.java" "$POJAV/src/main/java/net/kdt/pojavlaunch/fragments/OptiFineInstallFragment.java" <<'PY'
from pathlib import Path
import sys

modlist, forge, fabric, optifine = map(Path, sys.argv[1:])

p = modlist
s = p.read_text(encoding="utf-8")
s = s.replace("        }catch (IOException e) {", "        }catch (Throwable e) {", 1)
s = s.replace(
"""        Tools.runOnUiThread(()->{
            Context context = requireContext();
            getTaskProxy().detachListener();
            setTaskProxy(null);
            mExpandableListView.setEnabled(true);
            Tools.showError(context, e);
        });""",
"""        Tools.runOnUiThread(()->{
            Context context = getContext();
            ModloaderListenerProxy proxy = getTaskProxy();
            if(proxy != null) {
                proxy.detachListener();
                setTaskProxy(null);
            }
            if(mExpandableListView != null) mExpandableListView.setEnabled(true);
            if(context != null) Tools.showError(context, e);
        });""", 1)
s = s.replace(
"""    public void onDownloadFinished(File downloadedFile) {
        Tools.runOnUiThread(()->{
            Context context = requireContext();""",
"""    public void onDownloadFinished(File downloadedFile) {
        Tools.runOnUiThread(()->{
            Context context = getContext();
            if(context == null || mExpandableListView == null) return;""", 1)
s = s.replace(
"""    public void onDataNotAvailable() {
        Tools.runOnUiThread(()->{
            Context context = requireContext();""",
"""    public void onDataNotAvailable() {
        Tools.runOnUiThread(()->{
            Context context = getContext();
            if(context == null || mExpandableListView == null) return;""", 1)
p.write_text(s, encoding="utf-8")

p = forge
s = p.read_text(encoding="utf-8")
s = s.replace("        }catch (IOException e) {", "        }catch (Throwable e) {", 1)
s = s.replace(
"""            listenerProxy.onDownloadError(e);
        }
    }""",
"""            listenerProxy.onDownloadError(e instanceof Exception ? (Exception)e : new Exception(e));
        }
    }""", 1)
p.write_text(s, encoding="utf-8")

p = fabric
s = p.read_text(encoding="utf-8")
s = s.replace(
"""        }catch (IOException e) {
            Tools.showErrorRemote(e);
        }
    }""",
"""        }catch (Throwable e) {
            ModloaderListenerProxy proxy = getListenerProxy();
            if(proxy != null) proxy.onDownloadError(e instanceof Exception ? (Exception)e : new Exception(e));
        }
    }""", 1)
p.write_text(s, encoding="utf-8")

p = optifine
s = p.read_text(encoding="utf-8")
s = s.replace("        }catch (Exception e) {", "        }catch (Throwable e) {", 1)
s = s.replace(
"""            listenerProxy.onDownloadError(e);
        }
    }""",
"""            listenerProxy.onDownloadError(e instanceof Exception ? (Exception)e : new Exception(e));
        }
    }""", 1)
p.write_text(s, encoding="utf-8")
PY

# Make a newly installed OptiFine instance the active instance.
# Upstream creates it but does not select it, so the launcher can continue launching
# the previously selected vanilla instance after installation.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/fragments/OptiFineInstallFragment.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
s = s.replace("import net.kdt.pojavlaunch.instances.Instances;", "import net.kdt.pojavlaunch.instances.Instance;\nimport net.kdt.pojavlaunch.instances.Instances;", 1)
old = """            Instances.createInstance(instance -> {
                instance.name = "OptiFine";
                instance.installer = instanceInstaller;
                instance.sharedData = true;
            }, "OptiFine");
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);"""
new = """            Instance installedInstance = Instances.createInstance(instance -> {
                instance.name = "OptiFine";
                instance.installer = instanceInstaller;
                instance.sharedData = true;
            }, "OptiFine");
            Instances.setSelectedInstance(installedInstance);
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);"""
if old not in s:
    raise SystemExit("OptiFine instance creation block not found")
s = s.replace(old, new, 1)
p.write_text(s, encoding="utf-8")
PY

# Never send installer notifications back to the legacy Pojav/Mojo launcher.
python3 - "$POJAV/src/main/java/net/kdt/pojavlaunch/instances/InstanceInstaller.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding="utf-8")
s = s.replace("import net.kdt.pojavlaunch.LauncherActivity;", "import net.kdt.pojavlaunch.MikaelHomeActivity;", 1)
s = s.replace("new Intent(context, LauncherActivity.class)", "new Intent(context, MikaelHomeActivity.class)", 1)
p.write_text(s, encoding="utf-8")
PY

echo "Mikael overlay prepared successfully."
