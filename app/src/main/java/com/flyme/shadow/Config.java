package com.flyme.shadow;

final class Config {
    private Config() {}

    /** Flyme 桌面包名（魅族 21 / Flyme 12.x）。如不同请同步修改 scope.list */
    static final String LAUNCHER_PKG = "com.meizu.flyme.launcher";

    /** RemotePreferences 组名：模块 App 写入，桌面进程里的 Hook 只读 */
    static final String GROUP = "config";

    static final String K_ENABLED = "enabled";      // boolean 总开关
    static final String K_AUTO_LOCK = "auto_lock";  // boolean 息屏后自动重新隐藏
    static final String K_PAGES = "hidden_pages";   // Set<String> 页码（从 1 开始）
    static final String K_PKGS = "hidden_pkgs";     // Set<String> 包名
    static final String K_DISCOVERED_PKGS = "discovered_hidden_pkgs"; // 自动识别的隐藏页应用
    static final String K_AUTO_DISCOVER = "auto_discover_hidden_page";
    static final String K_LOCAL_AUTO_DISCOVER = "local_auto_discover_hidden_page";
    static final String K_TOKEN = "token";          // String 广播校验令牌
    static final String K_LOGS = "diagnostic_logs"; // 旧版兼容日志
    static final String K_LOGS_LAUNCHER = "diagnostic_logs_launcher";
    static final String K_LOGS_SETTINGS = "diagnostic_logs_settings";
    static final String K_LOGS_SAFE = "diagnostic_logs_safe";
    static final String K_LOGS_OTHER = "diagnostic_logs_other";
    static final String K_LOGS_APP = "diagnostic_logs_app";
    static final String K_PENDING_LOGS = "pending_diagnostic_logs";
    static final String K_LOCAL_TOKEN = "launcher_sync_token";
    static final String K_LOCAL_DISCOVERED_PKGS = "launcher_discovered_hidden_pkgs";
    static final String ACTION_SYNC_DISCOVERED = "com.flyme.shadow.SYNC_DISCOVERED";
    static final String ACTION_DIAGNOSTIC_LOG = "com.flyme.shadow.DIAGNOSTIC_LOG";
    static final String EXTRA_LOG_KEY = "log_key";
    static final String EXTRA_LOG_MESSAGE = "log_message";

    static final String ACTION_UNLOCK = "com.flyme.shadow.UNLOCK";
    static final String ACTION_LOCK = "com.flyme.shadow.LOCK";
    static final String EXTRA_TOKEN = "token";

    // 本地（仅模块 App 内部）密码存储
    static final String LOCAL_PREFS = "local";
    static final String L_SALT = "salt";
    static final String L_HASH = "hash";
}
