package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.StatFs;
import android.view.MotionEvent;
import android.view.View;
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

    private final ActivityResultLauncher<String[]> backgroundPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Throwable ignored) {}
                prefs.edit().putString("mikael_background_uri", uri.toString()).apply();
                applyBackground();
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

    @Override public boolean setFullscreen() { return false; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("mikael_launcher", MODE_PRIVATE);
        try { MoJsonDownloader.prepareSubstitutionMap(getAssets()); } catch (Throwable ignored) {}
        setContentView(R.layout.activity_mikael_launcher);
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
        refreshDashboard();
    }

    @Override protected void onResume() { super.onResume(); refreshDashboard(); }

    @Override protected void onDestroy() {
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
        if (resid == com.kdt.mcgui.ProgressLayout.UNPACK_RUNTIME.hashCode()) return "Preparando Java... " + progress + "%";
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
        click(R.id.account_card, v -> showAccounts());
    }

    private void click(int id, View.OnClickListener listener) {
        View view = findViewById(id);
        if (view != null) view.setOnClickListener(listener);
    }

    private void animateButtons() {
        int[] ids = {R.id.play_button,R.id.nav_home,R.id.nav_play,R.id.nav_versions,R.id.nav_mods,R.id.nav_accounts,R.id.nav_java,R.id.nav_settings,R.id.nav_files,R.id.nav_logs,R.id.account_card};
        for (int id : ids) {
            View view = findViewById(id);
            if (view == null) continue;
            view.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.animate().scaleX(.97f).scaleY(.97f).setDuration(80).start();
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
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
        launcherStatus.setText("Online • Mikael Launcher V3.1");
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

    private void playGame() {
        Account account = Accounts.getCurrent();
        if (account == null) { showAccounts(); return; }
        Instance instance = ensureInstance();
        if (instance == null) { showError("Não foi possível preparar o perfil."); return; }
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
            JVersionList.Version selected = MoJsonExtras.getListedVersion(version);
            if (selected == null) {
                runOnUiThread(() -> showError("A versão " + version + " não foi encontrada na lista oficial."));
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

        String[] items = new String[filtered.size()];
        for (int i=0;i<filtered.size();i++) {
            JVersionList.Version v=filtered.get(i);
            boolean installed = isVersionInstalled(v.id);
            items[i]=(installed ? "✓ " : "○ ") + v.id + " • " + v.type;
        }

        new AlertDialog.Builder(this)
                .setTitle(category==0 ? "⭐ Todas as versões" : category==1 ? "✅ Releases" : category==2 ? "🧪 Snapshots" : "🔬 Beta / antigas")
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
        instance.versionId = version.id;
        instance.maybeWrite();
        prefs.edit().putString("mikael_version_type", version.type == null ? "" : version.type).apply();
        refreshDashboard();

        new AlertDialog.Builder(this)
                .setTitle("Versão selecionada")
                .setMessage(version.id + "\nTipo: " + version.type + "\n\n" +
                        (isVersionInstalled(version.id) ? "✓ Esta versão já está instalada." : "○ Esta versão ainda não está instalada."))
                .setPositiveButton("Jogar / instalar", (d,w) -> playMinecraft())
                .setNegativeButton("OK", null)
                .show();
    }

    private void showModloaderVersions(JVersionList.Version[] versions) {
        String[] loaders = {"Vanilla", "OptiFine", "Forge", "Fabric", "Forge + OptiFine", "NeoForge", "Quilt", "Outros"};
        new AlertDialog.Builder(this)
                .setTitle("🧩 Modloaders")
                .setItems(loaders, (d,w) -> {
                    if (w == 0) showFilteredVersions(versions, 1);
                    else showPendingSetting(loaders[w], "O instalador real deste modloader ainda não está integrado ao Launcher Core. Nenhuma instalação falsa será criada.");
                })
                .setNegativeButton("Voltar", (d,w) -> showVersions())
                .show();
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
        Window window = authDialog.getWindow(); if (window != null) window.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92f),(int)(getResources().getDisplayMetrics().heightPixels*.88f));
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
            Toast.makeText(this, "Mod importado: " + target.getName(), Toast.LENGTH_SHORT).show();
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
        String[] values = {"Versão padrão: " + selectedVersion.getText(), "Perfil padrão: perfil atual", "Diretório do Minecraft", "Iniciar automaticamente", "Fechar launcher ao iniciar"};
        new AlertDialog.Builder(this).setTitle("🎮 Jogo").setItems(values, (d,w) -> {
            if (w == 0) showVersions();
            else if (w == 1) Toast.makeText(this, "O perfil selecionado é o perfil usado pelo botão JOGAR.", Toast.LENGTH_LONG).show();
            else if (w == 2) openPath(Instances.loadSelectedInstance() == null ? Instances.SHARED_DATA_DIRECTORY : Instances.loadSelectedInstance().getGameDirectory());
            else if (w == 3) togglePref("auto_start", "Iniciar automaticamente");
            else if (w == 4) togglePref("close_on_start", "Fechar launcher ao iniciar");
        }).show();
    }

    private void showRamSettings() {
        String[] values = {"512 MB","1 GB","2 GB","3 GB","4 GB","6 GB","8 GB","Personalizado"};
        int total = Tools.getTotalDeviceMemory(this), safeMax = Math.max(512, total - 512);
        for (int i=0;i<values.length;i++) if (!"Personalizado".equals(values[i]) && memory(values[i]) > safeMax) values[i] += " • indisponível";
        new AlertDialog.Builder(this).setTitle("🧠 Memória / RAM").setMessage("RAM total: " + total + " MB\nRAM disponível: " + getAvailableRamMb() + " MB\nRAM recomendada: " + Math.min(2048, safeMax) + " MB")
                .setItems(values, (d,w) -> {
                    if (w == values.length-1) { showError("Use um valor em MB pelo campo avançado quando esse recurso estiver disponível."); return; }
                    int mb=memory(values[w].replace(" • indisponível",""));
                    if(mb>safeMax){showError("Essa configuração pode causar instabilidade neste dispositivo.");return;}
                    LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation",mb).apply();
                    LauncherPreferences.loadPreferences(this); refreshDashboard();
                }).show();
    }

    private int getAvailableRamMb() {
        try { android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE); android.app.ActivityManager.MemoryInfo mi=new android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return (int)(mi.availMem/1048576L); } catch(Throwable e){return -1;}
    }

    private void showJvmSettings() {
        boolean advanced=prefs.getBoolean("jvm_advanced",false);
        new AlertDialog.Builder(this).setTitle("⚙️ JVM")
                .setMessage("Modo JVM avançado: " + (advanced ? "ATIVADO" : "DESATIVADO") + "\n\nOs argumentos padrão continuam sendo gerenciados pelo Pojav Core.")
                .setNeutralButton(advanced ? "Desativar" : "Ativar", (d,w)-> {
                    prefs.edit().putBoolean("jvm_advanced",!advanced).apply();
                    if(!advanced) showPendingSetting("Argumentos JVM", "Modo avançado ativado. A edição de argumentos personalizados será liberada somente quando estiver conectada ao campo real do Instance.");
                }).setPositiveButton("Fechar",null).show();
    }

    private void showMinecraftSettings() {
        showPendingSetting("🎮 Minecraft", "Resolução, escala, FPS, VSync, renderização e argumentos devem ser aplicados no processo GameActivity. A interface não mostra controles falsos enquanto esses campos não estiverem conectados ao Core.");
    }

    private void showDisplaySettings() {
        String[] items={"Landscape (padrão)","Portrait","Automática","Tela cheia","Barra de navegação"};
        new AlertDialog.Builder(this).setTitle("🖥️ Tela").setItems(items,(d,w)->{
            if(w<3){prefs.edit().putString("orientation",items[w]).apply(); Toast.makeText(this,"Orientação salva: "+items[w],Toast.LENGTH_SHORT).show();}
            else if(w==3) togglePref("fullscreen","Tela cheia");
            else showPendingSetting("Barra de navegação","O comportamento imersivo é controlado pelo GameActivity quando suportado pelo Android.");
        }).show();
    }

    private void showInputSettings() {
        new AlertDialog.Builder(this).setTitle("⌨️ Teclado e mouse")
                .setMessage("Teclado: detecção pelo Android\nMouse: detecção pelo Android\nControle: detecção pelo Android\n\nMapeamento e sensibilidade personalizados ainda dependem do editor de controles do Core.")
                .setPositiveButton("Fechar",null).show();
    }

    private void chooseThemeAndAnimation() {
        new AlertDialog.Builder(this).setTitle("🎨 Aparência").setItems(new String[]{"Automático","Escuro","Claro","Animações: todas","Animações: reduzidas","Animações: desativadas","Fundo personalizado"},(d,w)->{
            if(w<3){AppCompatDelegate.setDefaultNightMode(w==0?AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:w==1?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_NO);prefs.edit().putInt("theme",w).apply();}
            else if(w<6){prefs.edit().putInt("animations",w-3).apply(); Toast.makeText(this,"Preferência de animação salva.",Toast.LENGTH_SHORT).show();}
            else backgroundPicker.launch(new String[]{"image/*"});
        }).show();
    }

    private void showNetworkSettings() {
        String[] items={"Downloads simultâneos: "+prefs.getInt("download_threads",2),"Baixar arquivos grandes somente no Wi‑Fi","Retomar downloads","Limpar downloads temporários"};
        new AlertDialog.Builder(this).setTitle("🌐 Rede / downloads").setItems(items,(d,w)->{
            if(w==0){prefs.edit().putInt("download_threads",prefs.getInt("download_threads",2)==2?1:2).apply();Toast.makeText(this,"Downloads simultâneos salvo.",Toast.LENGTH_SHORT).show();}
            else if(w==1)togglePref("wifi_only","Somente Wi‑Fi");
            else if(w==2)togglePref("resume_downloads","Retomar downloads");
            else cleanTemporaryFiles();
        }).show();
    }

    private void showVersionSettings() {
        new AlertDialog.Builder(this).setTitle("📦 Versões").setMultiChoiceItems(new String[]{"Mostrar versões antigas","Mostrar snapshots","Mostrar instaladas primeiro","Verificar arquivos automaticamente","Reparar automaticamente","Confirmar antes de excluir"},new boolean[]{
                prefs.getBoolean("old_versions",false),prefs.getBoolean("snapshots",false),prefs.getBoolean("installed_first",true),prefs.getBoolean("verify_versions",true),prefs.getBoolean("repair_versions",false),prefs.getBoolean("confirm_delete_version",true)},(d,w,checked)->prefs.edit().putBoolean(new String[]{"old_versions","snapshots","installed_first","verify_versions","repair_versions","confirm_delete_version"}[w],checked).apply()).setPositiveButton("Fechar",null).show();
    }

    private void showModSettings() {
        new AlertDialog.Builder(this).setTitle("🧩 Mods").setMultiChoiceItems(new String[]{"Detectar incompatíveis","Detectar conflitos","Mostrar avisos","Verificar versão do Minecraft","Ativar/desativar mods"},new boolean[]{
                prefs.getBoolean("detect_incompatible_mods",true),prefs.getBoolean("detect_mod_conflicts",true),prefs.getBoolean("mod_warnings",true),prefs.getBoolean("check_mod_version",true),true},(d,w,checked)->{
                    if(w<4) prefs.edit().putBoolean(new String[]{"detect_incompatible_mods","detect_mod_conflicts","mod_warnings","check_mod_version"}[w],checked).apply();
                    else showMods();
                }).setNeutralButton("Abrir mods", (d,w)->showMods()).setPositiveButton("Fechar",null).show();
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
        new AlertDialog.Builder(this).setTitle("📝 Logs").setItems(new String[]{"Normal","Detalhado","Debug","Salvar logs","Limitar tamanho","Limpar logs antigos"},(d,w)->{
            if(w<3)prefs.edit().putInt("log_level",w).apply();
            else if(w==3)togglePref("save_logs","Salvar logs");
            else if(w==4)showPendingSetting("Limite de logs","O limite será aplicado quando o coletor de logs estiver integrado ao Core.");
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
                    prefs.edit().clear().apply();
                    LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation",1024).apply();
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
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
        File crash=new File(Tools.DIR_GAME_HOME,"latestcrash.txt"); String text="";
        if(crash.isFile())try{text=Tools.read(crash);}catch(IOException ignored){}
        if(text.length()>6000)text=text.substring(text.length()-6000); final String shown=text;
        TextView view=new TextView(this);view.setTextColor(Color.WHITE);view.setTextSize(12);view.setPadding(30,20,30,20);
        view.setText(shown.isEmpty()?"Nenhum crash report recente. Abra a pasta Logs para latest.log.":shown);
        new AlertDialog.Builder(this).setTitle("Logs / Crash Report").setView(view).setNeutralButton("Abrir logs",(d,w)->openPath(new File(root,"logs"))).setNegativeButton("Copiar",(d,w)->{
            ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("Mikael Launcher log",shown));
        }).setPositiveButton("Fechar",null).show();
    }

    private void applyBackground(){ImageView image=findViewById(R.id.mikael_background);String value=prefs.getString("mikael_background_uri",null);if(image==null)return;if(value==null)image.setImageDrawable(new ColorDrawable(Color.TRANSPARENT));else try{image.setImageURI(Uri.parse(value));}catch(Throwable ignored){}}
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
