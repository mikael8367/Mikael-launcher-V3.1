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
            StatFs fs = new StatFs(getExternalFilesDir(null).getAbsolutePath());
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
        setLoading(0, "Atualizando versões...");
        new AsyncVersionList().getVersionList(list -> runOnUiThread(() -> {
            if (list == null || list.versions == null) { showError("Não foi possível atualizar as versões."); return; }
            int count = Math.min(80, list.versions.length);
            String[] items = new String[count];
            for (int i = 0; i < count; i++) items[i] = list.versions[i].id + " • " + list.versions[i].type;
            new AlertDialog.Builder(this).setTitle("Versões do Minecraft").setItems(items, (d, which) -> {
                Instance i = ensureInstance();
                if (i != null) { i.versionId = list.versions[which].id; i.maybeWrite(); refreshDashboard(); }
            }).setPositiveButton("Fechar", null).show();
            launchProgress.setVisibility(View.GONE);
        }));
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
        String[] values={"512 MB","1 GB","2 GB","3 GB","4 GB","6 GB","8 GB"};
        int total=Tools.getTotalDeviceMemory(this);
        for(int i=0;i<values.length;i++)if(memory(values[i])>total)values[i]+=" • indisponível";
        new AlertDialog.Builder(this).setTitle("Configurações").setItems(values,(d,w)->{
            int mb=memory(values[w]); if(mb>Math.max(512,total-512)){showError("RAM incompatível com a memória disponível.");return;}
            LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation",mb).apply(); LauncherPreferences.loadPreferences(this); refreshDashboard();
        }).setNeutralButton("Tema",(d,w)->chooseTheme()).setNegativeButton("Plano de fundo",(d,w)->backgroundPicker.launch(new String[]{"image/*"})).setPositiveButton("Fechar",null).show();
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
    private void showError(String message){new AlertDialog.Builder(this).setTitle("Mikael Launcher").setMessage(message).setPositiveButton("OK",null).show();}
}
