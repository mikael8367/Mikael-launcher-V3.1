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
s=s.replace('signingConfig signingConfigs.customRelease', 'signingConfig signingConfigs.customDebug', 1)
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

echo "Mikael overlay prepared successfully."
