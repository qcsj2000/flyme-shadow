package com.flyme.shadow;

import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 有界、可验证的远程诊断日志。 */
final class DiagnosticLog {
    private static final String TAG = "FlymeShadow";
    private static final Object LOCK = new Object();
    private static final int MAX_CHARS = 24000;
    private static final int MAX_LINES = 180;

    private DiagnosticLog() {}

    static String keyFor(String packageName) {
        if (Config.LAUNCHER_PKG.equals(packageName)) return Config.K_LOGS_LAUNCHER;
        if ("com.android.settings".equals(packageName)
                || "com.meizu.settings".equals(packageName)
                || "com.meizu.flyme.settings".equals(packageName)) return Config.K_LOGS_SETTINGS;
        if ("com.meizu.safe".equals(packageName)) return Config.K_LOGS_SAFE;
        return Config.K_LOGS_OTHER;
    }

    /** 返回 null 表示写入并回读验证成功，否则返回可显示的错误原因。 */
    static String append(SharedPreferences prefs, String key, String message) {
        if (prefs == null) return "RemotePreferences 尚未初始化";
        if (key == null || message == null) return "日志参数为空";
        try {
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = stamp + " " + message.replace('\n', ' ').replace('\r', ' ');
            synchronized (LOCK) {
                String old = prefs.getString(key, "");
                StringBuilder value = new StringBuilder(old == null ? "" : old);
                if (value.length() > 0) value.append('\n');
                value.append(line);
                int lines = 1;
                for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') lines++;
                while (value.length() > MAX_CHARS || lines > MAX_LINES) {
                    int newline = value.indexOf("\n");
                    if (newline < 0) break;
                    value.delete(0, newline + 1);
                    lines--;
                }
                SharedPreferences.Editor editor = prefs.edit().putString(key, value.toString());
                if (!Config.K_LOGS_APP.equals(key)) editor.putLong(key + "_last_at", System.currentTimeMillis());
                if (!editor.commit()) return "SharedPreferences.commit() 返回 false";
                String saved = prefs.getString(key, null);
                if (!value.toString().equals(saved)) return "写入后回读内容不一致";
            }
            return null;
        } catch (Throwable t) {
            String error = "日志写入异常: " + Log.getStackTraceString(t);
            Log.e(TAG, error, t);
            return error;
        }
    }
}
