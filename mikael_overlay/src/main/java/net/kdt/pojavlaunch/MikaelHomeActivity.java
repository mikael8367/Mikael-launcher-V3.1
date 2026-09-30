package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;

import net.kdt.pojavlaunch.authenticator.AuthType;
import net.kdt.pojavlaunch.authenticator.BackgroundLogin;
import net.kdt.pojavlaunch.authenticator.accounts.Account;
import net.kdt.pojavlaunch.authenticator.accounts.Accounts;
import net.kdt.pojavlaunch.authenticator.listener.LoginListener;
import net.kdt.pojavlaunch.instances.Instance;
import net.kdt.pojavlaunch.instances.Instances;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.tasks.MoJsonDownloader;
import net.kdt.pojavlaunch.tasks.MoJsonExtras;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class MikaelHomeActivity extends BaseActivity {
    private TextView accountName, accountType, selectedVersion, ramValue, javaStatus;
    private TextView storageStatus, installStatus, launcherStatus, loadStatus;
    private ProgressBar launchProgress;
    private ImageView accountAvatar;
    private SharedPreferences prefs;
    private AlertDialog authDialog;
    private WebView authWebView;

    private final ActivityResultLauncher<String[]> modPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                importMod(uri);
            });

    private final ActivityResultLauncher<String[]> modloaderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    Tools.launchModInstaller(this, uri);
                } catch (Throwable e) {
                    showError("Não foi possível abrir o instalador: " + safe(e));
                }
            });

    private final ActivityResultLauncher<String[]> backgroundPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || prefs == null) return;
                new Thread(() -> {
                    try {
                        File target = new File(getFilesDir(), "mikael_background_image");
                        try (InputStream in = getContentResolver().openInputStream(uri);
                             OutputStream out = new java.io.FileOutputStream(target)) {
                            if (in == null) throw new IOException("Imagem indisponível");
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                        }
                        prefs.edit().putString("mikael_background_file", target.getAbsolutePath()).remove("mikael_background_uri").apply();
                        runOnUiThread(this::applyBackground);
                    } catch (Throwable e) {
                        showError("Não foi possível salvar o fundo: " + safe(e));
                    }
                }, "mikael-background-copy").start();
            });

    private final ProgressListener progressListener = new ProgressListener() {
        @Override public void onProgressStarted() { setLoading(0, "Preparando Minecraft..."); }
        @Override public void onProgressUpdated(int progress, int resid, Object... args) {
            runOnUiThread(() -> {
                if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                if (launchProgress == null || loadStatus == null) return;
                launchProgress.setVisibility(View.VISIBLE);
                launchProgress.setProgress(Math.max(0, Math.min(100, progress)));
                loadStatus.setText(statusFor(resid, progress, args));
            });
        }
        @Override public void onProgressEnded() {}
    };

    @Override public boolean setFullscreen() { return prefs != null && prefs.getBoolean("fullscreen", false); }

    private void applyDisplaySettings() {
        String orientation = prefs.getString("orientation", "Landscape");
        if ("Portrait".equals(orientation)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else if ("Automática".equals(orientation)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        Tools.setInsetsMode(this, setFullscreen(), shouldIgnoreNotch());
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("mikael_launcher", MODE_PRIVATE);
        try { MoJsonDownloader.prepareSubstitutionMap(getAssets()); } catch (Throwable ignored) {}
        setContentView(R.layout.activity_mikael_launcher);
        setupPojavFragmentHost();
        accountName = findViewById(R.id.account_name);
        accountType = findViewById(R.id.account_type);
        selectedVersion = findViewById(R.id.selected_version);
        ramValue = findViewById(R.id.ram_value);
        javaStatus = findViewById(R.id.java_status);
        storageStatus = findViewById(R.id.storage_status);
        installStatus = findViewById(R.id.install_status);
        launcherStatus = findViewById(R.id.launcher_status);
        loadStatus = findViewById(R.id.load_status);
        launchProgress = findViewById(R.id.launch_progress);
        accountAvatar = findViewById(R.id.account_avatar);

        String[] progressKeys = {
                com.kdt.mcgui.ProgressLayout.DOWNLOAD_GAME,
                com.kdt.mcgui.ProgressLayout.UNPACK_RUNTIME,
                com.kdt.mcgui.ProgressLayout.AUTHENTICATE,
                com.kdt.mcgui.ProgressLayout.INSTANCE_INSTALL,
                com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK
        };
        for (String key : progressKeys) ProgressKeeper.addListener(key, progressListener);

        bindNavigation();
        animateButtons();
        applyBackground();
        applyDisplaySettings();
        refreshDashboard();
        if (state == null && !prefs.getBoolean("optifine_1122_setup_started", false)) {
            openFirstRunOptiFine();
        } else if (state == null && prefs.getBoolean("auto_start", false)) {
            getWindow().getDecorView().postDelayed(this::playGame, 350);
        }
    }

    @Override protected void onResume() { super.onResume(); refreshDashboard(); }

    @Override protected void onDestroy() {
        saveAllSettingsNow();
        String[] keys = {
                com.kdt.mcgui.ProgressLayout.DOWNLOAD_GAME,
                com.kdt.mcgui.ProgressLayout.UNPACK_RUNTIME,
                com.kdt.mcgui.ProgressLayout.AUTHENTICATE,
                com.kdt.mcgui.ProgressLayout.INSTANCE_INSTALL,
                com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK
        };
        for (String key : keys) ProgressKeeper.removeListener(key, progressListener);
        if (authWebView != null) { authWebView.stopLoading(); authWebView.destroy(); authWebView = null; }
        super.onDestroy();
    }

    private String statusFor(int resid, int progress, Object[] args) {
        if (resid != -1) {
            try { return getString(resid, args); } catch (Throwable ignored) {}
        }
        if (args != null && args.length > 0 && args[0] instanceof String) return (String) args[0] + " • " + progress + "%";
        return "Preparando Minecraft... " + progress + "%";
    }

    private void bindNavigation() {
        click(R.id.nav_home, v -> refreshDashboard());
        click(R.id.nav_play, v -> playGame());
        click(R.id.nav_versions, v -> showVersions());
        click(R.id.nav_mods, v -> showMods());
        click(R.id.nav_accounts, v -> showAccounts());
        click(R.id.nav_java, v -> showJava());
        click(R.id.nav_settings, v -> showSettings());
        click(R.id.nav_files, v -> showFiles());
        click(R.id.nav_logs, v -> showLogs());
        click(R.id.play_button, v -> playGame());
        click(R.id.installed_versions_button, v -> showInstalledVersions());
        click(R.id.configure_version_button, v -> showVersionConfiguration());
        click(R.id.account_card, v -> showAccounts());
    }

    private void click(int id, View.OnClickListener listener) {
        View view = findViewById(id);
        if (view != null) view.setOnClickListener(listener);
    }

    private void animateButtons() {
        int mode = prefs == null ? 0 : prefs.getInt("animations", 0);
        int[] ids = {R.id.play_button,R.id.installed_versions_button,R.id.configure_version_button,R.id.nav_home,R.id.nav_play,R.id.nav_versions,R.id.nav_mods,R.id.nav_accounts,R.id.nav_java,R.id.nav_settings,R.id.nav_files,R.id.nav_logs,R.id.account_card};
        for (int id : ids) {
            View view = findViewById(id);
            if (view == null) continue;
            if (mode == 2) { view.setOnTouchListener(null); continue; }
            final long downDuration = mode == 1 ? 45 : 80;
            final long upDuration = mode == 1 ? 70 : 120;
            view.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.animate().scaleX(.97f).scaleY(.97f).setDuration(downDuration).start();
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(upDuration).start();
                return false;
            });
        }
    }

    private void refreshDashboard() {
        try { Instances.loadDisplay(); } catch (Throwable ignored) {}
        Account account = Accounts.getCurrent();
        if (account == null) {
            accountName.setText("Nenhuma conta");
            accountType.setText("Microsoft • Ely.by • Offline");
            accountAvatar.setImageResource(R.drawable.ic_mikael_logo);
        } else {
            accountName.setText(account.username);
            accountType.setText(account.authType == AuthType.MICROSOFT ? "Microsoft" : account.authType == AuthType.ELY_BY ? "Ely.by" : "Offline");
            if (account.getSkinFace() != null) accountAvatar.setImageBitmap(account.getSkinFace());
        }

        Instance instance = Instances.loadSelectedInstance();
        String version = instance == null || !Tools.isValidString(instance.versionId) ? "latest_release" : MoJsonExtras.normalizeVersionId(instance.versionId);
        selectedVersion.setText("Minecraft " + version);
        try { LauncherPreferences.loadPreferences(this); } catch (Throwable ignored) {}
        ramValue.setText("RAM " + LauncherPreferences.PREF_RAM_ALLOCATION + " MB");
        javaStatus.setText(javaSummary(instance));
        storageStatus.setText(storageSummary());
        installStatus.setText(installSummary(version));
        launcherStatus.setText("Launcher ativo • Mikael Launcher V3.1");
        if (!ProgressKeeper.hasOngoingTasks()) { loadStatus.setText("Pronto para jogar"); launchProgress.setVisibility(View.GONE); }
    }

    private String javaSummary(Instance instance) {
        try {
            String name = instance == null ? LauncherPreferences.PREF_DEFAULT_RUNTIME : Tools.getSelectedRuntime(instance);
            if (name == null) return "Java automático";
            Runtime runtime = MultiRTUtils.read(name);
            return runtime.javaVersion > 0 ? "Java " + runtime.javaVersion + " • " + runtime.arch : "Java automático";
        } catch (Throwable e) { return "Java automático"; }
    }

    private String storageSummary() {
        try {
            File external = getExternalFilesDir(null);
            if (external == null) return "Armazenamento indisponível";
            StatFs fs = new StatFs(external.getAbsolutePath());
            return "Livre: " + Tools.formatFileSize(fs.getAvailableBytes());
        } catch (Throwable e) { return "Armazenamento indisponível"; }
    }

    private String installSummary(String version) {
        File dir = new File(Tools.DIR_HOME_VERSION, version);
        return new File(dir, version + ".jar").isFile() && new File(dir, version + ".json").isFile()
                ? "Instalação: pronta" : "Instalação: preparar no JOGAR";
    }

    /** Persist launcher and Pojav preferences before leaving the launcher or starting Minecraft. */
    private void saveAllSettingsNow() {
        try {
            if (prefs != null) prefs.edit().commit();
            LauncherPreferences.DEFAULT_PREF.edit().commit();
            try { LauncherPreferences.loadPreferences(this); } catch (Throwable ignored) {}
            Instance selected = Instances.loadSelectedInstance();
            if (selected != null) selected.maybeWrite();
        } catch (Throwable ignored) {
            // Saving must never prevent Minecraft from starting or the Activity from closing.
        }
    }

    @Override protected void onPause() {
        saveAllSettingsNow();
        super.onPause();
    }

    private void playGame() {
        saveAllSettingsNow();
        if (ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(this, "Minecraft já está sendo preparado.", Toast.LENGTH_SHORT).show();
            return;
        }
        Account account = Accounts.getCurrent();
        if (account == null) { showAccounts(); return; }
        Instance instance = ensureInstance();
        if (instance == null) { showError("Não foi possível preparar o perfil."); return; }
        applyInstanceRam(instance);
        String version = MoJsonExtras.normalizeVersionId(instance.versionId);
        if (!Tools.isValidString(version)) { showError("Nenhuma versão foi selecionada."); return; }

        if (account.authType.requiresLogin() && account.expiresAt > 0 && account.expiresAt <= System.currentTimeMillis()) {
            setLoading(0, "Atualizando sessão...");
            account.authType.createAuth().refreshAccount(new LoginListener() {
                @Override public void onLoginDone(Account refreshed) {
                    Accounts.setCurrent(refreshed);
                    runOnUiThread(() -> launchAfterVersionList(instance, version));
                }
                @Override public void onLoginError(Throwable error) { runOnUiThread(() -> showError("Sessão expirada: " + safe(error))); }
                @Override public void onLoginProgress(int step) {}
                @Override public void setMaxLoginProgress(int max) {}
            }, account);
            return;
        }
        launchAfterVersionList(instance, version);
    }

    private void launchAfterVersionList(Instance instance, String version) {
        setLoading(4, "Verificando arquivos...");
        new AsyncVersionList().getVersionList(list -> {
            if (list == null || list.versions == null) {
                runOnUiThread(() -> showError("Falha ao obter a lista de versões."));
                return;
            }
            net.kdt.pojavlaunch.extra.ExtraCore.setValue(
                    net.kdt.pojavlaunch.extra.ExtraConstants.RELEASE_TABLE, list);

            JVersionList.Version selected = MoJsonExtras.getListedVersion(version);
            if (selected == null) {
                for (JVersionList.Version candidate : list.versions) {
                    if (candidate != null && version.equals(candidate.id)) {
                        selected = candidate;
                        break;
                    }
                }
            }
            if (selected == null) {
                runOnUiThread(() -> showError("A versão " + version + " não está disponível no manifesto baixado."));
                return;
            }
            runOnUiThread(() -> setLoading(6, "Preparando Minecraft " + version + "..."));
            try {
                new MoJsonDownloader().start(getAssets(), selected, version, new ContextAwareDoneListener(this, version));
            } catch (Throwable error) {
                runOnUiThread(() -> showError("Falha ao preparar a versão: " + safe(error)));
            }
        });
    }

    private Instance ensureInstance() {
        Instance instance = Instances.loadSelectedInstance();
        if (instance != null) return instance;
        try {
            instance = Instances.createDefaultInstance();
            Instances.setSelectedInstance(instance);
            return instance;
        } catch (IOException e) { return null; }
    }

    private void setLoading(int progress, String status) {
        runOnUiThread(() -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            if (launchProgress == null || loadStatus == null) return;
            launchProgress.setVisibility(View.VISIBLE);
            launchProgress.setProgress(Math.max(0, Math.min(100, progress)));
            loadStatus.setText(status);
        });
    }

    private void showVersionConfiguration() {
        Instance instance = ensureInstance();
        if (instance == null) return;
        String currentName = Tools.isValidString(instance.name) ? instance.name : instance.versionId;
        String renderer = Tools.isValidString(instance.renderer) ? instance.renderer : LauncherPreferences.PREF_RENDERER;
        String runtime = Tools.isValidString(instance.selectedRuntime) ? instance.selectedRuntime : "Automático";
        int ram = getInstanceRam(instance);
        String[] items = {
                "🏷️ Nome: " + currentName,
                "🖥️ Renderizador: " + renderer,
                "☕ Runtime: " + runtime,
                "🧠 RAM: " + ram + " MB",
                "🧩 Mods da versão"
        };
        new AlertDialog.Builder(this)
                .setTitle("⚙️ Configurar versão")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: editInstanceName(instance); break;
                        case 1: chooseInstanceRenderer(instance); break;
                        case 2: chooseInstanceRuntime(instance); break;
                        case 3: chooseInstanceRam(instance); break;
                        case 4: showInstanceMods(instance); break;
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private int getInstanceRam(Instance instance) {
        if (instance == null || instance.versionId == null) return LauncherPreferences.PREF_RAM_ALLOCATION;
        return prefs.getInt("instance_ram_" + MoJsonExtras.normalizeVersionId(instance.versionId), LauncherPreferences.PREF_RAM_ALLOCATION);
    }

    private void editInstanceName(Instance instance) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(instance.name == null ? "" : instance.name);
        input.setHint("Nome da versão");
        new AlertDialog.Builder(this).setTitle("🏷️ Nome da versão").setView(input)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", (d,w) -> {
                    String name = input.getText().toString().trim();
                    instance.name = name.isEmpty() ? instance.versionId : name;
                    instance.maybeWrite();
                    refreshDashboard();
                    showVersionConfiguration();
                }).show();
    }

    private void chooseInstanceRenderer(Instance instance) {
        String[] names = {"OpenGL ES 2 (GL4ES)", "OpenGL ES 3 (LTW)", "Zink (Vulkan)", "Freedreno", "Mesa", "Mesa Extended", "Legacy Zink", "MobileGLUES", "NGGL4ES", "Pojav SFPEW"};
        String[] values = {"opengles2","opengles3_ltw","vulkan_zink","freedreno_kgsl","mesa_desktop","mesa_desktop_ext","vulkan_legacyzink","opengles_mobileglues","opengles_nggl4es","opengles_sfpew"};
        int checked = 0;
        for (int i=0;i<values.length;i++) if(values[i].equals(instance.renderer)) checked=i;
        final int initial=checked;
        new AlertDialog.Builder(this).setTitle("🖥️ Renderizador")
                .setSingleChoiceItems(names, initial, (d,w) -> {
                    instance.renderer = values[w];
                    instance.maybeWrite();
                    d.dismiss();
                    showVersionConfiguration();
                }).setNegativeButton("Cancelar", null).show();
    }

    private void chooseInstanceRuntime(Instance instance) {
        List<Runtime> runtimes = MultiRTUtils.getRuntimes();
        ArrayList<String> names = new ArrayList<>();
        names.add("Automático");
        for (Runtime r : runtimes) names.add(r.name);
        int checked = instance.selectedRuntime == null ? 0 : Math.max(0, names.indexOf(instance.selectedRuntime));
        new AlertDialog.Builder(this).setTitle("☕ Runtime")
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (d,w) -> {
                    instance.selectedRuntime = w == 0 ? null : names.get(w);
                    instance.maybeWrite();
                    d.dismiss();
                    showVersionConfiguration();
                }).setNegativeButton("Cancelar", null).show();
    }

    private void chooseInstanceRam(Instance instance) {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int total = (int) Math.min(Integer.MAX_VALUE, mi.totalMem / (1024L * 1024L));
        int safeMax = Math.max(512, total - 512);
        String[] values = {"512 MB","1024 MB","2048 MB","3072 MB","4096 MB","6144 MB","8192 MB","Personalizado"};
        int current = getInstanceRam(instance);
        int checked = 2;
        int[] mb = {512,1024,2048,3072,4096,6144,8192};
        for(int i=0;i<mb.length;i++) if(current == mb[i]) checked=i;
        new AlertDialog.Builder(this).setTitle("🧠 RAM da versão")
                .setMessage("Limite seguro neste aparelho: " + safeMax + " MB")
                .setSingleChoiceItems(values, checked, (d,w) -> {
                    if(w == values.length-1) {
                        EditText input = new EditText(this);
                        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                        input.setHint("MB");
                        input.setText(String.valueOf(current));
                        new AlertDialog.Builder(this).setTitle("RAM personalizada").setView(input)
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Salvar", (x,y) -> saveInstanceRam(instance, input.getText().toString(), safeMax)).show();
                        return;
                    }
                    saveInstanceRam(instance, String.valueOf(mb[w]), safeMax);
                    d.dismiss();
                }).setNegativeButton("Cancelar", null).show();
    }

    private void saveInstanceRam(Instance instance, String raw, int safeMax) {
        try {
            int mb = Integer.parseInt(raw.trim());
            mb = Math.max(512, Math.min(safeMax, mb));
            prefs.edit().putInt("instance_ram_" + MoJsonExtras.normalizeVersionId(instance.versionId), mb).apply();
            Toast.makeText(this, "RAM da versão: " + mb + " MB", Toast.LENGTH_SHORT).show();
            showVersionConfiguration();
        } catch (Throwable e) { showError("RAM inválida."); }
    }

    private void applyInstanceRam(Instance instance) {
        if (instance == null || !Tools.isValidString(instance.versionId)) return;
        int ram = getInstanceRam(instance);
        LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", ram).commit();
        LauncherPreferences.loadPreferences(this);
    }

    private void showInstanceMods(Instance instance) {
        File modsDir = new File(instance.getGameDirectory(), "mods");
        File[] files = modsDir.isDirectory() ? modsDir.listFiles((dir,name) -> name.endsWith(".jar") || name.endsWith(".jar.disabled")) : null;
        if (files == null || files.length == 0) {
            new AlertDialog.Builder(this).setTitle("🧩 Mods • " + instance.versionId)
                    .setMessage("Nenhum mod encontrado nesta versão.\n\nUse o menu Mods para importar arquivos .jar.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        String[] items = new String[files.length];
        for(int i=0;i<files.length;i++) items[i] = files[i].getName() + (files[i].getName().endsWith(".disabled") ? "  [desativado]" : "  [ativo]");
        new AlertDialog.Builder(this).setTitle("🧩 Mods • " + instance.versionId + " (" + files.length + ")")
                .setItems(items, (d,w) -> toggleInstanceMod(files[w])).setNegativeButton("Fechar", null).show();
    }

    private void toggleInstanceMod(File file) {
        String name = file.getName();
        File target;
        if(name.endsWith(".jar.disabled")) target = new File(file.getParentFile(), name.substring(0,name.length()-9));
        else if(name.endsWith(".jar")) target = new File(file.getParentFile(), name + ".disabled");
        else return;
        if(file.renameTo(target)) showVersionConfiguration();
        else showError("Não foi possível alterar o mod.");
    }

    private void showInstalledVersions() {
        File versionsDir = new File(Tools.DIR_HOME_VERSION);
        ArrayList<String> installed = new ArrayList<>();
        if (versionsDir != null && versionsDir.isDirectory()) {
            File[] dirs = versionsDir.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File dir : dirs) {
                    String id = dir.getName();
                    if (!Tools.isValidString(id)) continue;
                    File jar = new File(dir, id + ".jar");
                    File json = new File(dir, id + ".json");
                    if (jar.isFile() && json.isFile()) installed.add(id);
                }
            }
        }
        installed.sort((a,b) -> {
            String current = Instances.loadSelectedInstance() == null ? "" :
                    MoJsonExtras.normalizeVersionId(Instances.loadSelectedInstance().versionId);
            if (a.equals(current)) return -1;
            if (b.equals(current)) return 1;
            return a.compareToIgnoreCase(b);
        });
        if (installed.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("📦 Versões instaladas")
                    .setMessage("Nenhuma versão do Minecraft está instalada ainda. Use JOGAR para instalar a versão selecionada.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] items = new String[installed.size()];
        String current = Instances.loadSelectedInstance() == null ? "" :
                MoJsonExtras.normalizeVersionId(Instances.loadSelectedInstance().versionId);
        for (int i = 0; i < installed.size(); i++) {
            String id = installed.get(i);
            items[i] = id.equals(current) ? "✓ " + id + " • atual" : "○ " + id;
        }
        new AlertDialog.Builder(this)
                .setTitle("📦 Versões instaladas (" + installed.size() + ")")
                .setItems(items, (d, which) -> selectInstalledVersion(installed.get(which)))
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void selectInstalledVersion(String versionId) {
        if (!Tools.isValidString(versionId)) return;
        Instance instance = ensureInstance();
        if (instance == null) return;
        instance.versionId = MoJsonExtras.normalizeVersionId(versionId);
        instance.maybeWrite();
        refreshDashboard();
        Toast.makeText(this, "Versão selecionada: " + versionId, Toast.LENGTH_SHORT).show();
    }

    private void showVersions() {
        setLoading(0, "Carregando todas as versões...");
        new AsyncVersionList().getVersionList(list -> runOnUiThread(() -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            if (list == null || list.versions == null) {
                showError("Não foi possível atualizar a lista de versões.");
                return;
            }

            String[] categories = {"⭐ Todas", "✅ Release", "🧪 Snapshot", "🔬 Beta / antigas", "🧩 Modloaders"};
            new AlertDialog.Builder(this)
                    .setTitle("📦 Todas as versões")
                    .setItems(categories, (dialog, which) -> {
                        if (which == 4) showModloaderVersions(list.versions);
                        else showFilteredVersions(list.versions, which);
                    })
                    .setNegativeButton("Fechar", null)
                    .show();
            launchProgress.setVisibility(View.GONE);
        }));
    }

    private void showFilteredVersions(JVersionList.Version[] versions, int category) {
        ArrayList<JVersionList.Version> filtered = new ArrayList<>();
        for (JVersionList.Version v : versions) {
            if (v == null || v.id == null) continue;
            String type = v.type == null ? "" : v.type.toLowerCase(java.util.Locale.ROOT);
            boolean add = category == 0
                    || (category == 1 && "release".equals(type))
                    || (category == 2 && ("snapshot".equals(type) || type.contains("snapshot")))
                    || (category == 3 && !"release".equals(type) && !("snapshot".equals(type) || type.contains("snapshot")));
            if (add) filtered.add(v);
        }

        if (filtered.isEmpty()) {
            showError("Nenhuma versão encontrada nessa categoria.");
            return;
        }
        if (prefs.getBoolean("installed_first", true)) {
            filtered.sort((a,b) -> Boolean.compare(!isVersionInstalled(a.id), !isVersionInstalled(b.id)));
        }

        // Do not cap this list: the Mojang manifest can contain hundreds of releases,
        // snapshots and legacy entries. Every entry returned by the Core is displayed.
        String[] items = new String[filtered.size()];
        for (int i=0;i<filtered.size();i++) {
            JVersionList.Version v=filtered.get(i);
            boolean installed = isVersionInstalled(v.id);
            items[i]=(installed ? "✓ " : "○ ") + v.id + " • " + v.type;
        }

        new AlertDialog.Builder(this)
                .setTitle(category==0 ? "⭐ Todas as versões (" + filtered.size() + ")" : category==1 ? "✅ Releases (" + filtered.size() + ")" : category==2 ? "🧪 Snapshots (" + filtered.size() + ")" : "🔬 Beta / antigas (" + filtered.size() + ")")
                .setItems(items, (d,w) -> selectVersion(filtered.get(w)))
                .setNegativeButton("Voltar", (d,w) -> showVersions())
                .show();
    }

    private boolean isVersionInstalled(String id) {
        File dir = new File(Tools.DIR_HOME_VERSION, id);
        return new File(dir, id + ".jar").isFile() && new File(dir, id + ".json").isFile();
    }

    private void selectVersion(JVersionList.Version version) {
        if (version == null || version.id == null) return;
        Instance instance = ensureInstance();
        if (instance == null) return;
        String normalizedId = MoJsonExtras.normalizeVersionId(version.id);
        instance.versionId = normalizedId == null || normalizedId.isEmpty() ? version.id : normalizedId;
        instance.maybeWrite();
        prefs.edit().putString("mikael_version_type", version.type == null ? "" : version.type).apply();
        refreshDashboard();

        new AlertDialog.Builder(this)
                .setTitle("Versão selecionada")
                .setMessage(version.id + "\nTipo: " + version.type + "\n\n" +
                        (isVersionInstalled(version.id) ? "✓ Esta versão já está instalada." : "○ Esta versão ainda não está instalada."))
                .setPositiveButton("Jogar / instalar", (d,w) -> playGame())
                .setNegativeButton("OK", null)
                .show();
    }

    private void showModloaderVersions(JVersionList.Version[] versions) {
        String[] loaders = {"Vanilla", "OptiFine", "Forge", "Fabric", "Forge + OptiFine", "NeoForge", "Quilt", "Outros"};
        new AlertDialog.Builder(this)
                .setTitle("🧩 Modloaders reais")
                .setItems(loaders, (d,w) -> {
                    switch (w) {
                        case 0:
                            showFilteredVersions(versions, 0);
                            break;
                        case 1:
                            openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.OptiFineInstallFragment.class, "OptiFine");
                            break;
                        case 2:
                            openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.ForgeInstallFragment.class, "Forge");
                            break;
                        case 3:
                            openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.FabricInstallFragment.class, "Fabric");
                            break;
                        case 4:
                            showForgeOptiFineInstaller();
                            break;
                        case 5:
                            openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.NeoforgeInstallFragment.class, "NeoForge");
                            break;
                        case 6:
                            openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.QuiltInstallFragment.class, "Quilt");
                            break;
                        default:
                            openGenericModloaderInstaller();
                            break;
                    }
                })
                .setNegativeButton("Voltar", (d,w) -> showVersions())
                .show();
    }

    private void openFirstRunOptiFine() {
        try {
            Instance instance = ensureInstance();
            if (instance == null) return;
            instance.versionId = "1.12.2";
            if (!Tools.isValidString(instance.name)) instance.name = "1.12.2 OptiFine";
            instance.maybeWrite();
            prefs.edit().putBoolean("optifine_1122_setup_started", true).apply();
            refreshDashboard();
            getWindow().getDecorView().postDelayed(() -> {
                if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.OptiFineInstallFragment.class, "OptiFine");
            }, 700);
        } catch (Throwable e) {
            showError("Não foi possível preparar o OptiFine 1.12.2: " + safe(e));
        }
    }

    private void openRealModloaderInstaller(Class<? extends androidx.fragment.app.Fragment> fragmentClass, String name) {
        try {
            saveAllSettingsNow();
            View host = findViewById(R.id.container_fragment);
            if (host == null) throw new IllegalStateException("Host do instalador não encontrado");
            host.setVisibility(View.VISIBLE);
            showPojavFragmentHost();
            Tools.swapFragment(this, fragmentClass, fragmentClass.getName(), null);
        } catch (Throwable e) {
            showError("Não foi possível abrir o instalador de " + name + ": " + safe(e));
        }
    }

    private void setupPojavFragmentHost() {
        getSupportFragmentManager().addOnBackStackChangedListener(this::syncPojavFragmentHost);
        syncPojavFragmentHost();
    }

    private void showPojavFragmentHost() {
        View root=findViewById(R.id.mikael_root), host=findViewById(R.id.container_fragment);
        if(root==null||host==null)return;
        host.setVisibility(View.VISIBLE);
        if(root instanceof android.view.ViewGroup){ android.view.ViewGroup g=(android.view.ViewGroup)root; for(int i=0;i<g.getChildCount();i++){View c=g.getChildAt(i); if(c!=host)c.setVisibility(View.GONE);} }
    }

    private void syncPojavFragmentHost() {
        View root=findViewById(R.id.mikael_root), host=findViewById(R.id.container_fragment);
        if(root==null||host==null)return;
        boolean showing=getSupportFragmentManager().getBackStackEntryCount()>0;
        if(showing){showPojavFragmentHost();return;}
        host.setVisibility(View.GONE);
        if(root instanceof android.view.ViewGroup){ android.view.ViewGroup g=(android.view.ViewGroup)root; for(int i=0;i<g.getChildCount();i++)g.getChildAt(i).setVisibility(View.VISIBLE); }
    }

    @Override public void onBackPressed(){ if(getSupportFragmentManager().getBackStackEntryCount()>0){getSupportFragmentManager().popBackStack();return;} super.onBackPressed(); }

    private void showForgeOptiFineInstaller() {
        new AlertDialog.Builder(this)
                .setTitle("Forge + OptiFine")
                .setMessage("O Core possui instaladores reais separados para Forge e OptiFine. Para evitar criar uma combinação falsa, instale primeiro o Forge e depois o OptiFine usando o instalador correspondente.")
                .setPositiveButton("Instalar Forge", (d,w) ->
                        openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.ForgeInstallFragment.class, "Forge"))
                .setNeutralButton("Instalar OptiFine", (d,w) ->
                        openRealModloaderInstaller(net.kdt.pojavlaunch.fragments.OptiFineInstallFragment.class, "OptiFine"))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openGenericModloaderInstaller() {
        modloaderPicker.launch(new String[]{"application/java-archive", "application/octet-stream"});
    }

    private void showAccounts() {
        try {
            Accounts all = Accounts.load();
            String[] items = new String[all.accounts.size()];
            for (int i = 0; i < items.length; i++) {
                Account a = all.accounts.get(i);
                items[i] = a.username + " • " + (a.authType == AuthType.MICROSOFT ? "Microsoft" : a.authType == AuthType.ELY_BY ? "Ely.by" : "Offline");
            }
            new AlertDialog.Builder(this).setTitle("Contas").setItems(items, (d, which) -> {
                Accounts.setCurrent(all.accounts.get(which)); refreshDashboard();
            }).setNeutralButton("+ Adicionar", (d, w) -> addAccount()).setNegativeButton("Remover", (d, w) -> removeAccount(all)).setPositiveButton("Fechar", null).show();
        } catch (IOException e) { showError("Falha ao carregar contas."); }
    }

    private void removeAccount(Accounts all) {
        if (all.accounts.isEmpty()) { showError("Não há contas."); return; }
        String[] items = new String[all.accounts.size()];
        for (int i=0;i<items.length;i++) items[i]="Remover • "+all.accounts.get(i).username;
        new AlertDialog.Builder(this).setTitle("Remover conta").setItems(items,(d,w)->{ Accounts.delete(all.accounts.get(w)); refreshDashboard(); }).setPositiveButton("Fechar",null).show();
    }

    private void addAccount() {
        new AlertDialog.Builder(this).setTitle("Adicionar conta").setItems(new String[]{"Microsoft","Ely.by","Offline"}, (d,w) -> {
            if (w == 0) startOAuth(AuthType.MICROSOFT);
            else if (w == 1) startOAuth(AuthType.ELY_BY);
            else offlineDialog();
        }).show();
    }

    private void offlineDialog() {
        EditText edit = new EditText(this); edit.setHint("Nome do jogador"); edit.setSingleLine(true);
        LinearLayout box = new LinearLayout(this); box.setPadding(36,8,36,0); box.addView(edit,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this).setTitle("Conta Offline").setMessage("Perfil local sem autenticação online.").setView(box).setNegativeButton("Cancelar",null)
                .setPositiveButton("Criar",(d,w)->createOffline(edit.getText().toString().trim())).show();
    }

    private void createOffline(String username) {
        if (username.length()<3 || username.length()>16 || !username.matches("[A-Za-z0-9_]+")) { showError("Nome inválido: 3–16 caracteres A–Z, 0–9 ou _."); return; }
        try {
            Account account = Accounts.create(a -> {
                a.username = username; a.authType = AuthType.LOCAL; a.accessToken = "0"; a.refreshToken = "0";
                a.profileId = UUID.nameUUIDFromBytes(("OfflinePlayer:"+username).getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
            });
            Accounts.setCurrent(account); refreshDashboard();
        } catch (IOException e) { showError("Falha ao criar conta offline."); }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startOAuth(AuthType type) {
        final boolean microsoft = type == AuthType.MICROSOFT;
        final String tracked = microsoft ? "https://login.live.com/oauth20_desktop.srf" : "internalredirect://complete";
        final String url = microsoft
                ? "https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL&redirect_url=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf"
                : "https://account.ely.by/oauth2/v1?client_id=mojolauncher2&redirect_uri=internalredirect%3A%2F%2Fcomplete&response_type=code&scope=account_info%20offline_access%20minecraft_server_session";

        authWebView = new WebView(this);
        WebSettings settings = authWebView.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        authWebView.setWebChromeClient(new WebChromeClient()); CookieManager.getInstance().removeAllCookies(null);
        authWebView.setWebViewClient(new WebViewClient() {
            boolean done;
            private boolean capture(String value) {
                if (done || value == null || !value.startsWith(tracked)) return false;
                String code = Uri.parse(value).getQueryParameter("code");
                if (code == null) return false;
                done = true; beginLogin(type, code); return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String urlValue) { return capture(urlValue); }
            @Override public void onPageFinished(WebView view, String urlValue) { capture(urlValue); }
        });
        authWebView.loadUrl(url);
        authDialog = new AlertDialog.Builder(this).setTitle(microsoft ? "Entrar com Microsoft" : "Entrar com Ely.by").setView(authWebView).setNegativeButton("Cancelar",null).create();
        authDialog.setOnDismissListener(d -> { if (authWebView != null) { authWebView.stopLoading(); authWebView.destroy(); authWebView=null; } authDialog=null; });
        authDialog.show();
        Window window = authDialog.getWindow();
        if (window != null) {
            window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92f),(int)(getResources().getDisplayMetrics().heightPixels*.88f));
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        // Samsung/Android WebView can keep the HTML field focused without opening the IME.
        authWebView.setFocusable(true);
        authWebView.setFocusableInTouchMode(true);
        authWebView.requestFocus(View.FOCUS_DOWN);
        authWebView.postDelayed(() -> {
            if (authWebView == null || authDialog == null || !authDialog.isShowing()) return;
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(authWebView, InputMethodManager.SHOW_IMPLICIT);
        }, 350);
    }

    private void beginLogin(AuthType type, String code) {
        if (authWebView != null) authWebView.setVisibility(View.GONE);
        BackgroundLogin login = type.createAuth();
        login.createAccount(new LoginListener() {
            @Override public void onLoginDone(Account account) {
                Accounts.setCurrent(account);
                runOnUiThread(() -> { if(authDialog!=null) authDialog.dismiss(); refreshDashboard(); });
            }
            @Override public void onLoginError(Throwable error) { runOnUiThread(() -> { if(authDialog!=null)authDialog.dismiss(); showError("Falha na autenticação: "+safe(error)); }); }
            @Override public void onLoginProgress(int step) {}
            @Override public void setMaxLoginProgress(int max) {}
        }, code);
    }

    private void showMods() {
        Instance instance=Instances.loadSelectedInstance(); File root=instance==null?Instances.SHARED_DATA_DIRECTORY:instance.getGameDirectory();
        File dir=new File(root,"mods"); if(!dir.exists())dir.mkdirs();
        File[] files=dir.listFiles((f,n)->n.endsWith(".jar")||n.endsWith(".jar.disabled")); if(files==null)files=new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        String[] items=new String[files.length]; for(int i=0;i<files.length;i++)items[i]=(files[i].getName().endsWith(".disabled")?"⏸ ":"▶ ")+files[i].getName();
        final File[] copy=files;
        new AlertDialog.Builder(this).setTitle("Mods").setItems(items,(d,w)->{
                    File f=copy[w];
                    File target = f.getName().endsWith(".disabled")
                            ? new File(dir,f.getName().substring(0,f.getName().length()-9))
                            : new File(dir,f.getName()+".disabled");
                    if (!f.renameTo(target)) Toast.makeText(this,"Não foi possível alterar o estado do mod.",Toast.LENGTH_SHORT).show();
                    showMods();
                })
                .setNeutralButton("Importar .jar",(d,w)->modPicker.launch(new String[]{"application/java-archive","application/octet-stream"}))
                .setNegativeButton("Abrir pasta",(d,w)->openPath(dir))
                .setPositiveButton("Fechar",null).show();
    }

    private void importMod(Uri uri) {
        new Thread(() -> importModInternal(uri), "mikael-mod-import").start();
    }

    private void importModInternal(Uri uri) {
        Instance instance = Instances.loadSelectedInstance();
        File root = instance == null ? Instances.SHARED_DATA_DIRECTORY : instance.getGameDirectory();
        File dir = new File(root, "mods");
        if (!dir.exists() && !dir.mkdirs()) {
            showError("Não foi possível criar a pasta de mods.");
            return;
        }
        String name = "imported-mod.jar";
        String display = uri.getLastPathSegment();
        if (display != null && display.contains("/")) display = display.substring(display.lastIndexOf('/') + 1);
        if (display != null && display.toLowerCase().endsWith(".jar")) name = display.replaceAll("[^A-Za-z0-9._-]", "_");
        File target = new File(dir, name);
        int suffix = 2;
        while (target.exists()) target = new File(dir, name.replace(".jar", "-" + suffix++ + ".jar"));
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new java.io.FileOutputStream(target)) {
            if (in == null) throw new IOException("Arquivo não disponível");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            final String importedName = target.getName();
            runOnUiThread(() -> Toast.makeText(this, "Mod importado: " + importedName, Toast.LENGTH_SHORT).show());
        } catch (Throwable e) {
            if (target.isFile()) target.delete();
            showError("Falha ao importar o mod: " + safe(e));
        }
    }

    private void showJava() {
        try {
            List<Runtime> runtimes=MultiRTUtils.getRuntimes();
            String[] items=new String[runtimes.size()];
            for(int i=0;i<items.length;i++){Runtime r=runtimes.get(i);items[i]=r.name+" • Java "+r.javaVersion+" • "+r.arch;}
            new AlertDialog.Builder(this).setTitle("Java Runtime").setItems(items,(d,w)->{Instance i=ensureInstance();if(i!=null){i.selectedRuntime=runtimes.get(w).name;i.maybeWrite();refreshDashboard();}})
                    .setMessage(runtimes.isEmpty()?"Nenhum runtime extra instalado. O motor Pojav preparará o Java necessário.":null).setPositiveButton("Fechar",null).show();
        } catch (Throwable e) { showError("Não foi possível listar os runtimes."); }
    }

    private void showSettings() {
        String[] categories = {
                "🎮 Jogo", "🧠 RAM", "☕ Java", "⚙️ JVM",
                "⛏️ Minecraft", "🖥️ Tela", "🕹️ Controles", "⌨️ Teclado e mouse",
                "🎨 Aparência", "🌐 Rede e downloads", "📦 Versões", "🧩 Mods",
                "👤 Contas", "🔐 Privacidade e segurança", "📁 Arquivos", "📝 Logs",
                "🛠️ Diagnóstico", "🧹 Limpeza", "🔄 Atualizações", "ℹ️ Sobre",
                "🚨 Restaurar configurações", "💾 Backup"
        };

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("⚙️ Configurações")
                .setItems(categories, (d, which) -> openSettingsCategory(which))
                .setNegativeButton("Fechar", null)
                .create();
        dialog.setOnShowListener(d -> {
            ListView list = dialog.getListView();
            if (list != null) {
                list.setDividerHeight(0);
                list.setPadding(8, 8, 8, 8);
            }
        });
        dialog.show();
    }

    private void openSettingsCategory(int which) {
        switch (which) {
            case 0: showGameSettings(); break;
            case 1: showRamSettings(); break;
            case 2: showJava(); break;
            case 3: showJvmSettings(); break;
            case 4: showMinecraftSettings(); break;
            case 5: showDisplaySettings(); break;
            case 6: showPendingSetting("Controles Touch", "O layout Touch do Pojav Core será usado aqui. Editor de posição/tamanho ainda não está conectado ao Core."); break;
            case 7: showInputSettings(); break;
            case 8: chooseThemeAndAnimation(); break;
            case 9: showNetworkSettings(); break;
            case 10: showVersionSettings(); break;
            case 11: showModSettings(); break;
            case 12: showAccounts(); break;
            case 13: showPrivacySettings(); break;
            case 14: showFilesSettings(); break;
            case 15: showLogSettings(); break;
            case 16: runDiagnostics(); break;
            case 17: showCleanupSettings(); break;
            case 18: showAbout("Atualizações"); break;
            case 19: showAbout("Sobre"); break;
            case 20: resetSettings(); break;
            case 21: showPendingSetting("Backup", "A arquitetura de backup está preparada, mas a restauração automática ainda não está conectada para evitar sobrescrever dados sem confirmação."); break;
        }
    }

    private void showGameSettings() {
        String[] values = {"🎮 Versão padrão: " + selectedVersion.getText(), "👤 Perfil padrão: perfil atual", "📁 Diretório do Minecraft", "▶️ Iniciar automaticamente", "🚪 Fechar launcher ao iniciar: automático"};
        new AlertDialog.Builder(this).setTitle("🎮 Jogo").setItems(values, (d,w) -> {
            if (w == 0) showVersions();
            else if (w == 1) Toast.makeText(this, "O perfil selecionado é o perfil usado pelo botão JOGAR.", Toast.LENGTH_LONG).show();
            else if (w == 2) openPath(Instances.loadSelectedInstance() == null ? Instances.SHARED_DATA_DIRECTORY : Instances.loadSelectedInstance().getGameDirectory());
            else if (w == 3) togglePref("auto_start", "Iniciar automaticamente");
            else if (w == 4) Toast.makeText(this, "O Core fecha o launcher automaticamente quando o GameActivity é iniciado.", Toast.LENGTH_LONG).show();
        }).show();
    }

    private void showRamSettings() {
        String[] values = {"512 MB","1 GB","2 GB","3 GB","4 GB","6 GB","8 GB","✏️ Personalizado"};
        int total = Tools.getTotalDeviceMemory(this), safeMax = Math.max(512, total - 512);
        for (int i=0;i<values.length;i++) if (!"Personalizado".equals(values[i]) && memory(values[i]) > safeMax) values[i] += " • indisponível";
        new AlertDialog.Builder(this).setTitle("🧠 Memória / RAM")
                .setItems(values, (d,w) -> {
                    if (w == values.length - 1) {
                        EditText input = new EditText(this);
                        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                        input.setSingleLine(true);
                        input.setHint("Ex.: 1536");
                        new AlertDialog.Builder(this).setTitle("RAM personalizada (MB)").setView(input)
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Aplicar", (x,y) -> {
                                    try { setRamAllocation(Integer.parseInt(input.getText().toString().trim()), safeMax); }
                                    catch (NumberFormatException e) { showError("Digite um valor válido em MB."); }
                                }).show();
                        return;
                    }
                    setRamAllocation(memory(values[w].replace(" • indisponível","")), safeMax);
                }).show();
    }

    private void setRamAllocation(int mb, int safeMax) {
        if (mb < 512) { showError("O mínimo é 512 MB."); return; }
        if (mb > safeMax) { showError("Essa configuração excede a margem segura para este dispositivo."); return; }
        LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", mb).apply();
        LauncherPreferences.loadPreferences(this);
        refreshDashboard();
    }

    private int getAvailableRamMb() {
        try { android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE); android.app.ActivityManager.MemoryInfo mi=new android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return (int)(mi.availMem/1048576L); } catch(Throwable e){return -1;}
    }

    private void showJvmSettings() {
        boolean advanced=prefs.getBoolean("jvm_advanced",false);
        String current=LauncherPreferences.DEFAULT_PREF.getString("javaArgs","");
        EditText input=new EditText(this);
        input.setSingleLine(false);
        input.setHint("-XX:+UseG1GC ...");
        input.setText(current);
        input.setSelection(input.length());
        new AlertDialog.Builder(this).setTitle("⚙️ JVM")
                .setMessage("Modo avançado: " + (advanced ? "ATIVADO" : "DESATIVADO") + "\nEsses argumentos são usados pelo Launcher Core.")
                .setView(input)
                .setNegativeButton("Fechar",null)
                .setNeutralButton(advanced ? "Desativar" : "Ativar",(d,w)->{
                    prefs.edit().putBoolean("jvm_advanced",!advanced).apply();
                    showJvmSettings();
                })
                .setPositiveButton("Salvar",(d,w)->{
                    String args=input.getText().toString().trim();
                    if(!advanced&&!args.isEmpty()){showPendingSetting("Modo avançado desligado","Ative o modo avançado antes de salvar argumentos JVM personalizados.");return;}
                    LauncherPreferences.DEFAULT_PREF.edit().putString("javaArgs",args).apply();
                    LauncherPreferences.loadPreferences(this);
                    Toast.makeText(this,"Argumentos JVM salvos.",Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showMinecraftSettings() {
        Instance instance=ensureInstance();
        if(instance==null){showError("Perfil não disponível.");return;}
        String[] items={
                "Escala de renderização: "+LauncherPreferences.DEFAULT_PREF.getInt("resolutionRatio",100)+"%",
                "VSync: "+readGameOption(instance,"enableVsync","false"),
                "Tela cheia no Minecraft: "+readGameOption(instance,"fullscreen","true"),
                "FPS máximo: "+readGameOption(instance,"maxFps","120"),
                "Distância de renderização: "+readGameOption(instance,"renderDistance","12"),
                "Resolução"
        };
        new AlertDialog.Builder(this).setTitle("🎮 Minecraft").setItems(items,(d,w)->{
            if(w==0)chooseResolutionScale();
            else if(w==1)setGameOptionDialog(instance,"enableVsync",new String[]{"true","false"},"VSync");
            else if(w==2)setGameOptionDialog(instance,"fullscreen",new String[]{"true","false"},"Tela cheia");
            else if(w==3)setGameOptionDialog(instance,"maxFps",new String[]{"30","60","90","120","144","260"},"FPS máximo");
            else if(w==4)setGameOptionDialog(instance,"renderDistance",new String[]{"4","6","8","10","12","16","20"},"Distância de renderização");
            else setResolutionDialog(instance);
        }).show();
    }

    private void chooseResolutionScale(){
        String[] values={"50%","60%","75%","85%","100%"};
        new AlertDialog.Builder(this).setTitle("Escala de renderização").setItems(values,(d,w)->{
            int value=Integer.parseInt(values[w].replace("%",""));
            LauncherPreferences.DEFAULT_PREF.edit().putInt("resolutionRatio",value).apply();
            LauncherPreferences.loadPreferences(this);
            Toast.makeText(this,"Escala aplicada: "+value+"%",Toast.LENGTH_SHORT).show();
        }).show();
    }

    private void setGameOptionDialog(Instance instance,String key,String[] values,String title){
        new AlertDialog.Builder(this).setTitle(title).setItems(values,(d,w)->{
            writeGameOption(instance.getGameDirectory(),key,values[w]);
            showMinecraftSettings();
        }).show();
    }

    private String readGameOption(Instance instance,String key,String fallback){
        File file=new File(instance.getGameDirectory(),"options.txt");
        if(!file.isFile())return fallback;
        try{
            String content=Tools.read(file),prefix=key+":";
            for(String line:content.split("\\r?\\n"))if(line.startsWith(prefix))return line.substring(prefix.length()).trim();
        }catch(Throwable ignored){}
        return fallback;
    }

    private void writeGameOption(File gameDir,String key,String value){
        try{
            if(!gameDir.exists()&&!gameDir.mkdirs())throw new IOException("Diretório indisponível");
            File file=new File(gameDir,"options.txt");
            String content=file.isFile()?Tools.read(file):"";
            String[] lines=content.split("\\r?\\n",-1);
            StringBuilder out=new StringBuilder();
            boolean found=false;String prefix=key+":";
            for(String line:lines){
                if(line.startsWith(prefix)){out.append(prefix).append(value);found=true;}else out.append(line);
                out.append("\n");
            }
            if(!found)out.append(prefix).append(value).append("\n");
            try(OutputStream os=new java.io.FileOutputStream(file)){os.write(out.toString().getBytes(StandardCharsets.UTF_8));}
            Toast.makeText(this,"Opção aplicada.",Toast.LENGTH_SHORT).show();
        }catch(Throwable e){showError("Não foi possível salvar a opção do Minecraft: "+safe(e));}
    }

    private void setResolutionDialog(Instance instance){
        String[] values={"Automática (0x0)","1280x720","1600x900","1920x1080"};
        new AlertDialog.Builder(this).setTitle("Resolução").setItems(values,(d,w)->{
            String value = w == 0 ? "0x0" : values[w];
            String[] size=value.split("x");
            writeGameOption(instance.getGameDirectory(),"overrideWidth",size[0]);
            writeGameOption(instance.getGameDirectory(),"overrideHeight",size[1]);
        }).show();
    }

    private void showDisplaySettings() {
        String[] items={"Landscape (padrão)","Portrait","Automática","Tela cheia"};
        new AlertDialog.Builder(this).setTitle("🖥️ Tela").setItems(items,(d,w)->{
            if(w<3){
                prefs.edit().putString("orientation",items[w].equals("Landscape (padrão)")?"Landscape":items[w]).apply();
                applyDisplaySettings();
                Toast.makeText(this,"Orientação aplicada.",Toast.LENGTH_SHORT).show();
            } else {
                boolean next=!prefs.getBoolean("fullscreen",false);
                prefs.edit().putBoolean("fullscreen",next).apply();
                applyDisplaySettings();
                Toast.makeText(this,"Tela cheia: "+(next?"ativada":"desativada"),Toast.LENGTH_SHORT).show();
            }
        }).show();
    }

    private void showInputSettings() {
        new AlertDialog.Builder(this).setTitle("⌨️ Teclado e mouse")
                .setMessage("⌨️ Teclado: detecção pelo Android\n🖱️ Mouse: detecção pelo Android\n🎮 Controle: detecção pelo Android\n\nAs opções que o Core ainda não expõe ficam identificadas como pendentes; nenhuma configuração falsa é salva.")
                .setPositiveButton("Fechar",null).show();
    }

    private void chooseThemeAndAnimation() {
        new AlertDialog.Builder(this).setTitle("🎨 Aparência").setItems(new String[]{"Automático","Escuro","Claro","Animações: todas","Animações: reduzidas","Animações: desativadas","Fundo personalizado"},(d,w)->{
            if(w<3){prefs.edit().putInt("theme",w).apply();AppCompatDelegate.setDefaultNightMode(w==0?AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:w==1?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_NO);}
            else if(w<6){prefs.edit().putInt("animations",w-3).apply(); animateButtons(); Toast.makeText(this,"Preferência de animação aplicada.",Toast.LENGTH_SHORT).show();}
            else backgroundPicker.launch(new String[]{"image/*"});
        }).show();
    }

    private void showNetworkSettings() {
        String[] items={
                "Verificar manifesto: "+(LauncherPreferences.DEFAULT_PREF.getBoolean("verifyManifest",true)?"Ligado":"Desligado"),
                "Verificar arquivos do jogo: "+(LauncherPreferences.DEFAULT_PREF.getBoolean("checkGameFiles",true)?"Ligado":"Desligado"),
                "Limpar downloads temporários",
                "Downloads avançados"
        };
        new AlertDialog.Builder(this).setTitle("🌐 Rede / downloads").setItems(items,(d,w)->{
            if(w==0){
                boolean next=!LauncherPreferences.DEFAULT_PREF.getBoolean("verifyManifest",true);
                LauncherPreferences.DEFAULT_PREF.edit().putBoolean("verifyManifest",next).apply();
                LauncherPreferences.loadPreferences(this);showNetworkSettings();
            }else if(w==1){
                boolean next=!LauncherPreferences.DEFAULT_PREF.getBoolean("checkGameFiles",true);
                LauncherPreferences.DEFAULT_PREF.edit().putBoolean("checkGameFiles",next).apply();
                LauncherPreferences.loadPreferences(this);showNetworkSettings();
            }else if(w==2)cleanTemporaryFiles();
            else showPendingSetting("Downloads avançados","O Downloader desta versão do Core não expõe com segurança número de conexões, Wi‑Fi somente ou retomada configurável.");
        }).show();
    }

    private void showVersionSettings() {
        new AlertDialog.Builder(this).setTitle("📦 Versões").setMultiChoiceItems(
                new String[]{"Instaladas primeiro","Verificar manifesto","Verificar arquivos do jogo"},
                new boolean[]{prefs.getBoolean("installed_first",true),
                        LauncherPreferences.DEFAULT_PREF.getBoolean("verifyManifest",true),
                        LauncherPreferences.DEFAULT_PREF.getBoolean("checkGameFiles",true)},
                (d,w,checked)->{
                    if(w==0) prefs.edit().putBoolean("installed_first",checked).apply();
                    else if(w==1) LauncherPreferences.DEFAULT_PREF.edit().putBoolean("verifyManifest",checked).apply();
                    else LauncherPreferences.DEFAULT_PREF.edit().putBoolean("checkGameFiles",checked).apply();
                    try{LauncherPreferences.loadPreferences(this);}catch(Throwable ignored){}
                }).setMessage("Release, Snapshot e Beta/antigas são escolhidos diretamente na tela Versões.")
                .setPositiveButton("Fechar",null).show();
    }

    private void showModSettings() {
        new AlertDialog.Builder(this).setTitle("🧩 Mods")
                .setItems(new String[]{"Ativar / desativar mods","Abrir pasta de mods","Detecção de incompatibilidade e conflitos"},(d,w)->{
                    if(w==0)showMods();
                    else if(w==1)showFiles();
                    else showPendingSetting("Diagnóstico de mods","A detecção automática de versão, incompatibilidade e conflitos ainda não está ligada ao Core desta versão.");
                }).show();
    }

    private void showPrivacySettings() {
        new AlertDialog.Builder(this).setTitle("🔐 Privacidade e segurança")
                .setItems(new String[]{"Limpar sessões de autenticação","Informações de privacidade"},(d,w)->{
                    if(w==0)new AlertDialog.Builder(this).setTitle("Limpar sessões?").setMessage("Isso remove as contas salvas pelo launcher.").setNegativeButton("Cancelar",null).setPositiveButton("Limpar",(x,y)->{try{Accounts all=Accounts.load();for(Account a:new ArrayList<>(all.accounts))Accounts.delete(a);refreshDashboard();}catch(Throwable ignored){}}).show();
                    else showAbout("Privacidade");
                }).show();
    }

    private void showFilesSettings() {
        Instance i=Instances.loadSelectedInstance();File root=i==null?Instances.SHARED_DATA_DIRECTORY:i.getGameDirectory();
        new AlertDialog.Builder(this).setTitle("📁 Arquivos").setItems(new String[]{"Minecraft","Mods","Saves","Resourcepacks","Screenshots","Logs","Ver espaço utilizado"},(d,w)->{
            if(w<6){File[] dirs={root,new File(root,"mods"),new File(root,"saves"),new File(root,"resourcepacks"),new File(root,"screenshots"),new File(root,"logs")};openPath(dirs[w]);}
            else Toast.makeText(this,storageSummary(),Toast.LENGTH_LONG).show();
        }).show();
    }

    private void showLogSettings() {
        new AlertDialog.Builder(this).setTitle("📝 Logs").setItems(
                new String[]{"Nível de logs","Limitar tamanho","Limpar logs antigos"},
                (d,w)->{
                    if(w==0)showPendingSetting("Nível de logs","O Logger do Core desta versão não expõe um seletor seguro de nível por perfil.");
                    else if(w==1)showPendingSetting("Limite de logs","O coletor de logs do Core não oferece um limite configurável seguro.");
                    else cleanLogs();
                }).show();
    }

    private void runDiagnostics() {
        Instance i=Instances.loadSelectedInstance(); String version=i==null?"Nenhuma":i.versionId;
        StringBuilder b=new StringBuilder();
        b.append("Modelo: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n");
        b.append("Android: ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        b.append("Arquitetura: ").append(System.getProperty("os.arch")).append("\n");
        b.append("RAM: ").append(Tools.getTotalDeviceMemory(this)).append(" MB\n");
        b.append("Armazenamento: ").append(storageSummary()).append("\n");
        b.append("Java: ").append(javaSummary(i)).append("\n");
        b.append("Versão: ").append(version).append("\n");
        b.append("Conta: ").append(Accounts.getCurrent()==null?"Nenhuma":Accounts.getCurrent().username).append("\n");
        b.append("Java runtime: ").append(MultiRTUtils.getRuntimes().size()).append(" runtime(s) detectado(s)\n");
        b.append("Diretório: ").append((i==null?Instances.SHARED_DATA_DIRECTORY:i.getGameDirectory()).getAbsolutePath());
        new AlertDialog.Builder(this).setTitle("🛠️ Diagnóstico do Launcher").setMessage(b.toString()).setPositiveButton("Fechar",null).setNeutralButton("Verificar arquivos",(d,w)->{
            String status=installSummary(version);
            showError(status.contains("pronta")?"Tudo funcionando corretamente para a instalação local.":"A versão selecionada ainda precisa ser preparada pelo JOGAR.");
        }).show();
    }

    private void showCleanupSettings() {
        new AlertDialog.Builder(this).setTitle("🧹 Limpeza").setItems(new String[]{"Limpar cache","Limpar downloads incompletos","Limpar logs antigos","Limpar temporários"},(d,w)->{
            if(w==0)try{deleteDir(getCacheDir());Toast.makeText(this,"Cache limpo.",Toast.LENGTH_SHORT).show();}catch(Throwable ignored){}
            else if(w==1)cleanTemporaryFiles();
            else if(w==2)cleanLogs();
            else cleanTemporaryFiles();
        }).show();
    }

    private void cleanTemporaryFiles() {
        File root=Instances.SHARED_DATA_DIRECTORY; File[] files=root.listFiles((f,n)->n.endsWith(".part")||n.endsWith(".tmp")||n.endsWith(".download"));
        int removed=0;if(files!=null)for(File f:files)if(f.delete())removed++;
        Toast.makeText(this,"Temporários removidos: "+removed,Toast.LENGTH_SHORT).show();
    }

    private void cleanLogs() {
        Instance i=Instances.loadSelectedInstance();File dir=new File((i==null?Instances.SHARED_DATA_DIRECTORY:i.getGameDirectory()),"logs");int removed=0;
        File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.isFile()&&!f.getName().equals("latest.log")&&f.delete())removed++;
        Toast.makeText(this,"Logs antigos removidos: "+removed,Toast.LENGTH_SHORT).show();
    }

    private void deleteDir(File dir){File[] files=dir.listFiles();if(files!=null)for(File f:files){if(f.isDirectory())deleteDir(f);else f.delete();}dir.delete();}

    private void togglePref(String key,String label){boolean next=!prefs.getBoolean(key,false);prefs.edit().putBoolean(key,next).apply();Toast.makeText(this,label+": "+(next?"Ativado":"Desativado"),Toast.LENGTH_SHORT).show();}

    private void showPendingSetting(String title,String message){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show();}

    private void showAbout(String title) {
        new AlertDialog.Builder(this).setTitle(title).setMessage("Mikael Launcher V3.1\n\nCódigo de terceiros: PojavLauncher e bibliotecas licenciadas conforme os arquivos de licença do projeto.\n\nAs configurações pendentes não são apresentadas como funcionais até estarem ligadas ao Launcher Core.").setPositiveButton("Fechar",null).show();
    }

    private void resetSettings() {
        new AlertDialog.Builder(this).setTitle("🚨 Restaurar configurações").setMessage("Isso restaurará as configurações do launcher. Seus mundos, mods e arquivos do Minecraft não serão apagados.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Restaurar",(d,w)->{
                    File background = new File(getFilesDir(), "mikael_background_image");
                    if (background.isFile()) background.delete();
                    prefs.edit().clear().apply();
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .remove("allocation")
                            .remove("javaArgs")
                            .remove("resolutionRatio")
                            .remove("verifyManifest")
                            .remove("checkGameFiles")
                            .remove("renderer")
                            .remove("defaultRuntime")
                            .apply();
                    try { LauncherPreferences.loadPreferences(this); } catch (Throwable ignored) {}
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    applyDisplaySettings();
                    refreshDashboard();
                }).show();
    }

    private int memory(String value){String first=value.split(" ")[0];return value.contains("GB")?Integer.parseInt(first)*1024:Integer.parseInt(first);}
    private void chooseTheme(){new AlertDialog.Builder(this).setTitle("Tema").setItems(new String[]{"Automático","Escuro","Claro"},(d,w)->AppCompatDelegate.setDefaultNightMode(w==0?AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:w==1?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_NO)).show();}

    private void showFiles() {
        Instance i=Instances.loadSelectedInstance(); File root=i==null?Instances.SHARED_DATA_DIRECTORY:i.getGameDirectory();
        File[] dirs={root,new File(root,"mods"),new File(root,"resourcepacks"),new File(root,"saves"),new File(root,"screenshots"),new File(root,"logs")};
        new AlertDialog.Builder(this).setTitle("Arquivos").setItems(new String[]{"Minecraft","Mods","Resourcepacks","Saves","Screenshots","Logs"},(d,w)->openPath(dirs[w])).setPositiveButton("Fechar",null).show();
    }

    private void openPath(File dir){try{FileUtils.ensureDirectorySilently(dir);Tools.openPath(this,dir,false);}catch(Throwable e){Toast.makeText(this,"Não foi possível abrir a pasta.",Toast.LENGTH_LONG).show();}}

    private void showLogs() {
        Instance i=Instances.loadSelectedInstance(); File root=i==null?Instances.SHARED_DATA_DIRECTORY:i.getGameDirectory();
        String text="";
        File latestLog=new File(Tools.DIR_GAME_HOME,"latestlog.txt");
        if(latestLog.isFile())try{text=Tools.read(latestLog);}catch(IOException ignored){}
        if(text.isEmpty()){
            File crashDir=new File(Tools.DIR_HOME_CRASH);
            File[] crashes=crashDir.listFiles((f,n)->n.endsWith(".txt"));
            if(crashes!=null&&crashes.length>0){
                Arrays.sort(crashes,Comparator.comparingLong(File::lastModified).reversed());
                try{text=Tools.read(crashes[0]);}catch(IOException ignored){}
            }
        }
        if(text.length()>6000)text=text.substring(text.length()-6000);
        final String shown=text;
        TextView view=new TextView(this);view.setTextColor(Color.WHITE);view.setTextSize(12);view.setPadding(30,20,30,20);
        view.setText(shown.isEmpty()?"Nenhum log/crash report disponível.":shown);
        new AlertDialog.Builder(this).setTitle("Logs / Crash Report").setView(view)
                .setNeutralButton("Abrir logs",(d,w)->openPath(new File(Tools.DIR_GAME_HOME)))
                .setNegativeButton("Copiar",(d,w)->{
                    ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("Mikael Launcher log",shown));
                }).setPositiveButton("Fechar",null).show();
    }

    private void applyBackground(){
        ImageView image=findViewById(R.id.mikael_background);
        if(image==null)return;
        String value=prefs.getString("mikael_background_file",null);
        if(value==null){ image.setImageResource(R.drawable.bg_mikael_gradient); return; }
        File file=new File(value);
        if(!file.isFile()){ prefs.edit().remove("mikael_background_file").apply(); image.setImageResource(R.drawable.bg_mikael_gradient); return; }
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
            int sample=1, maxDimension=1920;
            while(bounds.outWidth/sample>maxDimension||bounds.outHeight/sample>maxDimension)sample*=2;
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inSampleSize=sample;
            options.inPreferredConfig=android.graphics.Bitmap.Config.RGB_565;
            android.graphics.Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            if(bitmap!=null) image.setImageBitmap(bitmap);
            else image.setImageResource(R.drawable.bg_mikael_gradient);
        }catch(Throwable e){
            image.setImageResource(R.drawable.bg_mikael_gradient);
        }
    }
    private String safe(Throwable t){return t==null?"erro desconhecido":t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}
    private void showError(String message){
        runOnUiThread(() -> {
            if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
            try {
                new AlertDialog.Builder(this).setTitle("Mikael Launcher").setMessage(message).setPositiveButton("OK",null).show();
            } catch (Throwable ignored) {}
        });
    }
}
