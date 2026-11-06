package com.flyme.shadow;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import rikka.shizuku.Shizuku;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Root/Shizuku 双通道包冻结管理。操作后读取系统状态核验。 */
final class FreezeManager {
    interface Callback { void result(boolean ok, boolean frozen, String message); }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private FreezeManager() {}

    private static volatile String lastStatus = "正在检测 Root / Shizuku…";
    private static volatile long rootCheckedAt = 0L;
    private static volatile boolean rootCached = false;

    /** 不会阻塞调用线程：返回上一次的检测结果。需要最新结果请用 statusAsync。 */
    static String statusCached() { return lastStatus; }

    /** 在后台线程检测（su 可能阻塞数秒并弹授权框），结果回到主线程。 */
    static void statusAsync(java.util.function.Consumer<String> consumer) {
        IO.execute(() -> {
            String s = status();
            lastStatus = s;
            new Handler(Looper.getMainLooper()).post(() -> consumer.accept(s));
        });
    }

    private static boolean rootAvailableCached() {
        long now = System.currentTimeMillis();
        if (now - rootCheckedAt > 30_000) {
            rootCached = rootAvailable();
            rootCheckedAt = now;
        }
        return rootCached;
    }

    /** 会启动 su 进程，禁止在主线程调用。 */
    static String status() {
        if (rootAvailableCached()) return "Root：可用（优先使用）";
        if (!Shizuku.pingBinder()) return "Root 不可用；Shizuku 服务未运行";
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ? "Shizuku：已授权" : "Shizuku：等待授权";
    }

    static boolean requestShizuku() {
        if (!Shizuku.pingBinder()) return false;
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
        Shizuku.requestPermission(7301);
        return false;
    }

    static void setFrozen(Context c, String pkg, boolean freeze, Callback cb) {
        IO.execute(() -> {
            boolean actual = false, ok = false;
            String msg;
            try {
                if (protectedPackage(c, pkg)) throw new SecurityException("系统保护：禁止冻结此应用");
                String cmd = freeze ? "pm disable-user --user 0 " + quote(pkg)
                        : "pm default-state --user 0 " + quote(pkg);
                String output = run(cmd);
                actual = isFrozen(c, pkg);
                ok = actual == freeze;
                msg = ok ? (freeze ? "已冻结" : "已解冻") : "系统未确认成功：" + output;
            } catch (Throwable t) {
                msg = "操作失败：" + t.getMessage();
                actual = isFrozen(c, pkg);
            }
            final boolean f = actual, success = ok;
            final String m = msg;
            new Handler(Looper.getMainLooper()).post(() -> cb.result(success, f, m));
        });
    }

    static boolean isFrozen(Context c, String pkg) {
        try {
            int setting = c.getPackageManager().getApplicationEnabledSetting(pkg);
            return setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) { return false; }
    }

    private static boolean protectedPackage(Context c, String p) {
        if (p == null || p.equals(c.getPackageName()) || p.equals(Config.LAUNCHER_PKG)
                || p.equals("android") || p.equals("com.android.systemui")
                || p.equals("com.android.settings")) return true;
        try {
            ApplicationInfo a = c.getPackageManager().getApplicationInfo(p, 0);
            return (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (a.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (Throwable t) { return true; }
    }

    private static boolean rootAvailable() {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "id").start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return false;
        }
    }

    private static String run(String cmd) throws Exception {
        Process p;
        if (rootAvailableCached()) p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
        else {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("请授予 Root 或 Shizuku 权限");
            // Shizuku 13.1.5 keeps the legacy newProcess entry point private.
            // Use its compatibility entry point reflectively; migration to UserService
            // can follow after the build is stable.
            java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            p = (Process) method.invoke(null, new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
        }
        // 先异步读输出再 waitFor：输出填满管道缓冲区时先 waitFor 会死锁直到超时。
        final StringBuilder b = new StringBuilder();
        final Process proc = p;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) synchronized (b) { b.append(line).append(' '); }
            } catch (Exception ignored) { }
        }, "flymeshadow-cmd-reader");
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("系统命令执行超时（15 秒）");
        }
        reader.join(1000);
        int code = p.exitValue();
        String out;
        synchronized (b) { out = b.toString().trim(); }
        if (code != 0) throw new IllegalStateException(out);
        return out;
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
