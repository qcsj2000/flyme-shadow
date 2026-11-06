package com.flyme.shadow;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class App extends Application {

    static volatile XposedService service;
    static volatile SharedPreferences remote;
    static volatile String connectionStatus = "等待 LSPosed 服务连接";
    /** 前台界面注册；服务（重新）连接后在主线程回调，用于刷新 UI。 */
    static volatile Runnable connectionListener;

    @Override
    public void onCreate() {
        super.onCreate();
        // Diagnostic logs are handled by a manifest receiver so they are not lost
        // when the module UI process is stopped. The receiver queues logs until
        // RemotePreferences becomes writable after the LSPosed service connects.
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService s) {
                Log.i("FlymeShadow", "XposedService onServiceBind 回调已触发");
                connectionStatus = "服务已回调，正在读取远程配置";
                try {
                    SharedPreferences p = s.getRemotePreferences(Config.GROUP);
                    String token = p.getString(Config.K_TOKEN, null);
                    if (token == null) {
                        byte[] b = new byte[32];
                        new SecureRandom().nextBytes(b);
                        token = Base64.encodeToString(b, Base64.NO_WRAP);
                        p.edit().putString(Config.K_TOKEN, token).commit();
                    }
                    getSharedPreferences(Config.LOCAL_PREFS, MODE_PRIVATE)
                            .edit().putString(Config.K_LOCAL_TOKEN, token).commit();
                    service = s;
                    remote = p;
                    SharedPreferences localPrefs = getSharedPreferences(Config.LOCAL_PREFS, MODE_PRIVATE);
                    boolean discoverEnabled = localPrefs.getBoolean(Config.K_LOCAL_AUTO_DISCOVER, true);
                    Set<String> discovered = discoverEnabled && localPrefs.contains(Config.K_LOCAL_DISCOVERED_PKGS)
                            ? new HashSet<>(localPrefs.getStringSet(Config.K_LOCAL_DISCOVERED_PKGS,
                                    java.util.Collections.emptySet())) : new HashSet<>();
                    p.edit()
                            .putBoolean(Config.K_AUTO_DISCOVER, discoverEnabled)
                            .putStringSet(Config.K_DISCOVERED_PKGS, discovered)
                            .commit();
                    Log.i("FlymeShadow", "已同步隐藏页识别状态=" + discoverEnabled
                            + "，恢复应用数量=" + discovered.size());
                    connectionStatus = "已连接，RemotePreferences 可用";
                    Log.i("FlymeShadow", connectionStatus);
                    flushPendingDiagnosticLogs(getApplicationContext(), remote);
                    String logError = DiagnosticLog.append(remote, Config.K_LOGS_APP, connectionStatus);
                    if (logError != null) Log.e("FlymeShadow", "模块日志写入失败: " + logError);
                    Runnable l = connectionListener;
                    if (l != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(l);
                } catch (Throwable t) {
                    connectionStatus = "读取 RemotePreferences 失败: " + t;
                    Log.e("FlymeShadow", connectionStatus, t);
                    SharedPreferences current = remote;
                    if (current != null) DiagnosticLog.append(current, Config.K_LOGS_APP, connectionStatus);
                    service = null;
                    remote = null;
                }
            }

            @Override
            public void onServiceDied(XposedService s) {
                connectionStatus = "LSPosed 服务已断开";
                Log.w("FlymeShadow", connectionStatus);
                SharedPreferences previous = remote;
                if (previous != null) DiagnosticLog.append(previous, Config.K_LOGS_APP, connectionStatus);
                service = null;
                remote = null;
            }
        });
    }

    private static final Object PENDING_LOG_LOCK = new Object();
    private static final int MAX_PENDING_LOG_CHARS = 20000;

    private static void queuePendingDiagnosticLog(Context context, String key, String message) {
        synchronized (PENDING_LOG_LOCK) {
            SharedPreferences local = context.getSharedPreferences(Config.LOCAL_PREFS, Context.MODE_PRIVATE);
            String old = local.getString(Config.K_PENDING_LOGS, "");
            String line = key + "\t" + message.replace('\n', ' ').replace('\r', ' ');
            StringBuilder pending = new StringBuilder(old == null ? "" : old);
            if (pending.length() > 0) pending.append('\n');
            pending.append(line);
            while (pending.length() > MAX_PENDING_LOG_CHARS) {
                int cut = pending.indexOf("\n");
                if (cut < 0) { pending.setLength(0); break; }
                pending.delete(0, cut + 1);
            }
            local.edit().putString(Config.K_PENDING_LOGS, pending.toString()).commit();
        }
    }

    /** Flush logs received while the app process or RemotePreferences was unavailable. */
    private static void flushPendingDiagnosticLogs(Context context, SharedPreferences target) {
        if (context == null || target == null) return;
        synchronized (PENDING_LOG_LOCK) {
            SharedPreferences local = context.getSharedPreferences(
                    Config.LOCAL_PREFS, Context.MODE_PRIVATE);
            String pending = local.getString(Config.K_PENDING_LOGS, "");
            if (pending == null || pending.isEmpty()) return;
            StringBuilder remaining = new StringBuilder();
            for (String line : pending.split("\\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                String message = line.substring(tab + 1);
                if (!isDiagnosticLogKey(key)) continue;
                String error = DiagnosticLog.append(target, key, message);
                if (error != null) {
                    if (remaining.length() > 0) remaining.append('\n');
                    remaining.append(line);
                }
            }
            local.edit().putString(Config.K_PENDING_LOGS, remaining.toString()).commit();
        }
    }

    private static boolean isDiagnosticLogKey(String key) {
        return Config.K_LOGS_LAUNCHER.equals(key)
                || Config.K_LOGS_SETTINGS.equals(key)
                || Config.K_LOGS_SAFE.equals(key)
                || Config.K_LOGS_OTHER.equals(key);
    }

    /** 接收 Hook 进程诊断日志；只有持有本模块令牌的进程才能写入远程日志。 */
    static void acceptDiagnosticLog(Context context, Intent intent) {
        try {
            String supplied = intent.getStringExtra(Config.EXTRA_TOKEN);
            String expected = context.getSharedPreferences(Config.LOCAL_PREFS, Context.MODE_PRIVATE)
                    .getString(Config.K_LOCAL_TOKEN, null);
            if (supplied == null || expected == null
                    || !MessageDigest.isEqual(supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return;
            String key = intent.getStringExtra(Config.EXTRA_LOG_KEY);
            String message = intent.getStringExtra(Config.EXTRA_LOG_MESSAGE);
            if (message == null || message.isEmpty()) return;
            if (!Config.K_LOGS_LAUNCHER.equals(key)
                    && !Config.K_LOGS_SETTINGS.equals(key)
                    && !Config.K_LOGS_SAFE.equals(key)
                    && !Config.K_LOGS_OTHER.equals(key)) return;
            if (!isDiagnosticLogKey(key)) return;
            SharedPreferences target = remote;
            if (target == null) {
                queuePendingDiagnosticLog(context, key, message);
                Log.w("FlymeShadow", "RemotePreferences 尚未连接，诊断日志已暂存");
                return;
            }
            String error = DiagnosticLog.append(target, key, message);
            if (error != null) {
                queuePendingDiagnosticLog(context, key, message);
                Log.e("FlymeShadow", "保存 Hook 诊断日志失败，已暂存待重试: " + error);
            }
        } catch (Throwable t) {
            Log.e("FlymeShadow", "处理 Hook 诊断日志失败", t);
        }
    }

    static void acceptDiscoveredPackages(Context context, Intent intent) {
        try {
            String supplied = intent.getStringExtra(Config.EXTRA_TOKEN);
            String expected = context.getSharedPreferences(Config.LOCAL_PREFS, Context.MODE_PRIVATE)
                    .getString(Config.K_LOCAL_TOKEN, null);
            if (supplied == null || expected == null
                    || !MessageDigest.isEqual(supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return;
            java.util.ArrayList<String> incoming = intent.getStringArrayListExtra("packages");
            if (incoming == null) {
                Log.w("FlymeShadow", "桌面隐藏页同步广播缺少 packages 参数");
                return;
            }
            // Empty is a valid authoritative snapshot: all selected pages may be empty
            // or the user may have removed every page from the hidden-page selection.
            SharedPreferences local = context.getSharedPreferences(Config.LOCAL_PREFS, Context.MODE_PRIVATE);
            SharedPreferences target = remote;
            boolean discoverEnabled = target != null
                    ? target.getBoolean(Config.K_AUTO_DISCOVER, true)
                    : local.getBoolean(Config.K_LOCAL_AUTO_DISCOVER, true);
            if (!discoverEnabled) {
                Log.i("FlymeShadow", "忽略关闭自动识别后迟到的桌面快照");
                return;
            }
            Set<String> snapshot = new HashSet<>();
            for (String pkg : incoming) {
                if (pkg != null && !pkg.isEmpty() && !pkg.equals(context.getPackageName())) {
                    snapshot.add(pkg);
                }
            }
            boolean localOk = local.edit()
                    .putStringSet(Config.K_LOCAL_DISCOVERED_PKGS, snapshot).commit();
            if (target != null) {
                boolean remoteOk = target.edit()
                        .putStringSet(Config.K_DISCOVERED_PKGS, snapshot).commit();
                Log.i("FlymeShadow", "桌面隐藏页应用快照同步" +
                        (localOk && remoteOk ? "成功" : "失败") + "，数量=" + snapshot.size());
            } else {
                Log.i("FlymeShadow", "桌面隐藏页应用快照已暂存，等待 LSPosed 配置连接，数量=" + snapshot.size());
            }
        } catch (Throwable t) {
            Log.e("FlymeShadow", "处理桌面隐藏页同步失败", t);
        }
    }

    /** 通知桌面进程：解锁(显示) / 重新隐藏。令牌只有本模块和桌面里的 Hook 读得到，防止别的 App 伪造广播 */
    static boolean sendToLauncher(Context c, String action) {
        SharedPreferences p = remote;
        if (p == null) return false;
        String token = p.getString(Config.K_TOKEN, null);
        if (token == null) return false;
        Intent i = new Intent(action)
                .setPackage(Config.LAUNCHER_PKG)
                .putExtra(Config.EXTRA_TOKEN, token);
        c.sendBroadcast(i);
        return true;
    }
}
