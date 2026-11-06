package com.flyme.shadow;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 思路：不改桌面数据库（favorites 表原样保留），只在桌面“绑定到界面”这一步过滤掉
 * 被隐藏的页面和应用。解锁时让桌面 Activity recreate 重新绑定，布局自然完整恢复。
 *
 * 目标是 Launcher3 系的桌面：bindScreens / bindItems / bindAllApplications 等回调。
 * Flyme 桌面的具体类名需在真机上核对（见 README），这里按候选类名 + 方法名宽松匹配，
 * 并把命中情况写进 LSPosed 日志。
 */
public class HookEntry extends XposedModule {

    private static final String TAG = "FlymeShadow";
    // Settings may be reported as a non-first package in a shared process.
    // Install its hooks once per process, independently of isFirstPackage().
    private static volatile boolean settingsHooksInstalled = false;
    private static volatile boolean safeHooksInstalled = false;
    private static final String SAFE_PKG = "com.meizu.safe";

    /** 候选类名：Launcher3 系回调通常实现在 Launcher 活动里 */
    private static final String[] CANDIDATE_CLASSES = {
            "com.android.launcher3.Launcher",
            "com.meizu.flyme.launcher.Launcher",
            "com.meizu.flyme.launcher.LauncherActivity",
            "com.android.launcher3.uioverrides.QuickstepLauncher",
    };
    private static final Set<String> HOOK_METHODS = new HashSet<>(java.util.Arrays.asList(
            "bindItems", "bindScreens", "bindAppsAdded", "bindItemsAdded",
            "bindAllApplications", "bindAllAppsApps"));
    private static final Set<String> SCREEN_METHODS = new HashSet<>(java.util.Arrays.asList(
            "bindScreens", "bindAppsAdded"));
    // Workspace callbacks carry database-backed ItemInfo records; app-drawer callbacks are excluded.
    private static final Set<String> WORKSPACE_ITEM_METHODS = new HashSet<>(java.util.Arrays.asList(
            "bindItems", "bindItemsAdded", "bindItemsInScreen", "bindWorkspaceItems",
            "bindWorkspaceItemsInScreen", "bindItem", "bindItemsUpdated"));
    private static final Set<String> hookedLauncherBindingMethods =
            java.util.Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> diagnosedLauncherClasses =
            java.util.Collections.synchronizedSet(new HashSet<String>());

    private static final int CONTAINER_DESKTOP = -100;

    // 桌面进程内状态；默认 false = 已隐藏，进程重启即自动重新隐藏（安全默认）
    private static volatile boolean unlocked = false;
    private static volatile SharedPreferences prefs;
    private static volatile SharedPreferences settingsPrefs;
    private static volatile SharedPreferences diagnosticPrefs;
    private static volatile String diagnosticKey = Config.K_LOGS_OTHER;
    private static volatile long[] orderedScreens = new long[0];
    private static volatile WeakReference<Activity> launcherRef = new WeakReference<>(null);
    private static volatile Context launcherContext;
    private static volatile String lastSentDiscoveredSnapshot;
    private static boolean receiverRegistered = false;
    private static SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        // 必须在首包判断之前记录入口：否则共享进程/多包加载时，
        // 非首包回调会被静默丢弃，无法判断 LSPosed 作用域是否真正生效。
        String packageName = param.getPackageName();
        boolean firstPackage = param.isFirstPackage();
        // Use Android logcat as well as the framework logger: this makes it possible
        // to identify exactly which process received each package callback.
        Log.i(TAG, "onPackageReady: package=" + packageName
                + ", firstPackage=" + firstPackage
                + ", process=" + Application.getProcessName());

        // Do not gate Settings on firstPackage. In a shared process, Settings can be
        // delivered after another package and therefore have firstPackage=false.
        // The old early return silently skipped every Settings hook in that case.
        if (isSettingsPackage(packageName)) {
            String processName = Application.getProcessName();
            if (!isSettingsProcess(packageName, processName)) {
                Log.w(TAG, "Skip Settings hooks: callback=" + packageName
                        + ", process=" + processName);
                return;
            }
            synchronized (HookEntry.class) {
                if (settingsHooksInstalled) {
                    Log.i(TAG, "Settings hooks already installed in this process");
                    return;
                }
                settingsHooksInstalled = true;
            }
            try {
                settingsPrefs = getRemotePreferences(Config.GROUP);
                diagnosticPrefs = settingsPrefs;
                diagnosticKey = DiagnosticLog.keyFor(packageName);
                record("模块进入设置进程: " + packageName);
                hookSettingsAppList(param.getClassLoader());
            } catch (Throwable t) {
                synchronized (HookEntry.class) {
                    settingsHooksInstalled = false;
                }
                Log.e(TAG, "Settings RemotePreferences failed: " + t);
                record("设置进程读取远程配置失败: " + t);
            }
            return;
        }
        // 手机管家：此前 hookSafe* 系列方法从未被调用，导致 scope 里勾选了手机管家也不会过滤。
        if (SAFE_PKG.equals(packageName)) {
            String processName = Application.getProcessName();
            if (!isSettingsProcess(packageName, processName)) {
                Log.w(TAG, "Skip Safe hooks: callback=" + packageName + ", process=" + processName);
                return;
            }
            synchronized (HookEntry.class) {
                if (safeHooksInstalled) return;
                safeHooksInstalled = true;
            }
            try {
                settingsPrefs = getRemotePreferences(Config.GROUP);
                diagnosticPrefs = settingsPrefs;
                diagnosticKey = DiagnosticLog.keyFor(packageName);
                record("模块进入手机管家进程: " + packageName + "，process=" + processName);
                ClassLoader cl = param.getClassLoader();
                hookSettingsAppList(cl);
                hookSafeProviderQueries(cl);
                hookSafeRecyclerAdapters(cl);
                hookSafeActivityViews();
            } catch (Throwable t) {
                synchronized (HookEntry.class) {
                    safeHooksInstalled = false;
                }
                Log.e(TAG, "Safe hooks failed: " + t);
                record("手机管家 Hook 安装失败: " + t);
            }
            return;
        }
        if (!firstPackage) return;
        if (!Config.LAUNCHER_PKG.equals(packageName)) return;

        try {
            prefs = getRemotePreferences(Config.GROUP);
            diagnosticPrefs = prefs;
            diagnosticKey = DiagnosticLog.keyFor(packageName);
            record("模块进入桌面进程: " + packageName);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "getRemotePreferences failed: " + t);
            return;
        }
        ClassLoader cl = param.getClassLoader();
        hookApplicationOnCreate();
        int hooked = 0;
        for (String cn : CANDIDATE_CLASSES) {
            Class<?> c;
            try {
                c = Class.forName(cn, false, cl);
            } catch (Throwable t) {
                continue;
            }
            // Install workspace callbacks on classes already loaded before the
            // ClassLoader observer was attached; lazy-load observation alone misses them.
            hooked += installLauncherWorkspaceMethods(c);
            for (Method m : c.getDeclaredMethods()) {
                if (!HOOK_METHODS.contains(m.getName())) continue;
                try {
                    if (hookBind(m)) hooked++;
                    log(Log.INFO, TAG, "hooked " + cn + "#" + m.getName() + java.util.Arrays.toString(m.getParameterTypes()));
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "hook failed " + m + ": " + t);
                }
            }
        }
        // 之前把 ClassLoader 监听的数量也算进 hooked，导致“没有命中”警告永远不会出现。
        int loaderHooks = hookLauncherClassLoader(cl);
        record("桌面绑定 Hook 安装完成，命中方法数=" + hooked + "，动态类加载监听=" + loaderHooks);
        if (hooked == 0) {
            log(Log.WARN, TAG, "没有立即命中任何桌面绑定方法（将依赖动态类加载监听继续查找）：若桌面始终不生效，请反编译桌面后修改 CANDIDATE_CLASSES");
        }
    }

    private static boolean isSettingsPackage(String packageName) {
        return "com.android.settings".equals(packageName)
                || "com.meizu.settings".equals(packageName)
                || "com.meizu.flyme.settings".equals(packageName);
    }

    private static boolean isSettingsProcess(String packageName, String processName) {
        return packageName != null && processName != null
                && (processName.equals(packageName)
                || processName.startsWith(packageName + ":"));
    }

    // ---------------------------------------------------------------- settings app-list filtering

    /**
     * 只在系统设置进程中过滤用户配置的隐藏包。
     * 不调用 disable/uninstall，不修改目标应用状态或数据。
     */
    private void hookSettingsAppList(ClassLoader cl) {
        // Filter package data, not rendered rows: GONE/zero-height rows can leave gaps in Flyme.
        try {
            settingsPrefs = getRemotePreferences(Config.GROUP);
            Log.i(TAG, "Install Settings app-list hooks: process="
                    + Application.getProcessName() + ", loader=" + cl);
            record("设置进程开始安装应用列表 Hook: process=" + Application.getProcessName());
            Class<?> pmClass = Class.forName("android.app.ApplicationPackageManager", false, cl);
            Set<String> methodNames = new HashSet<>(java.util.Arrays.asList(
                    "getInstalledApplications", "getInstalledPackages",
                    "getInstalledApplicationsAsUser", "getInstalledPackagesAsUser"));
            int hooked = 0;
            for (Method m : pmClass.getDeclaredMethods()) {
                if (!methodNames.contains(m.getName())) continue;
                if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        if (!(result instanceof List)) return result;
                        Set<String> hidden = readSettingsHiddenPackages();
                        record("设置 PM 查询命中: " + m.getName()
                                + "，返回条目=" + ((List<?>) result).size()
                                + "，隐藏名单=" + hidden.size());
                        if (hidden.isEmpty()) return result;

                        List<?> source = (List<?>) result;
                        List<Object> filtered = new ArrayList<>(source.size());
                        boolean changed = false;
                        for (Object item : source) {
                            String pkg = null;
                            if (item instanceof ApplicationInfo) {
                                pkg = ((ApplicationInfo) item).packageName;
                            } else if (item instanceof PackageInfo) {
                                pkg = ((PackageInfo) item).packageName;
                            }
                            if (pkg != null && hidden.contains(pkg)) {
                                changed = true;
                            } else {
                                filtered.add(item);
                            }
                        }
                        if (changed) {
                            log(Log.INFO, TAG, "Settings app list filtered " +
                                    (source.size() - filtered.size()) + " hidden package(s)");
                            record("设置应用列表已过滤 " + (source.size() - filtered.size()) + " 个包");
                            return filtered;
                        }
                        return result;
                    });
                    hooked++;
                    log(Log.INFO, TAG, "hooked Settings#" + m.getName()
                            + java.util.Arrays.toString(m.getParameterTypes()));
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Settings list hook failed " + m + ": " + t);
                }
            }
            log(hooked > 0 ? Log.INFO : Log.WARN, TAG,
                    "Settings app-list hooks installed: " + hooked);
            record("设置列表 PackageManager Hook 数=" + hooked);
            hookSettingsAdapters(cl);
            record("系统设置应用列表 Hook 初始化结束: process="
                    + Application.getProcessName() + "，PM方法数=" + hooked);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hookSettingsAppList failed: " + t);
            record("设置应用列表 Hook 安装异常: " + t);
        }
    }



    /**
     * Flyme 12.1 的设置应用管理页使用自有 ApplicationsState，
     * 最终由 ApplicationsAdapter.onRebuildComplete(ArrayList) 接收 AppEntry 列表。
     * APK DEX 已确认 AppEntry.info 字段类型为 ApplicationInfo。
     */
    private static final Set<String> hookedAdapterMethods = java.util.Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> hookedFilterMethods = java.util.Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> hookedPositionMethods = java.util.Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<Boolean> bypassAdapterPositionHooks = new ThreadLocal<>();
    private static final java.util.Map<Object, AdapterPositionMap> adapterPositionMaps =
            java.util.Collections.synchronizedMap(new WeakHashMap<Object, AdapterPositionMap>());
    private static final Set<String> hookedRuntimeSetAdapterMethods = java.util.Collections.synchronizedSet(new HashSet<>());

    private static final class AdapterPositionMap {
        final int originalCount;
        final String hiddenSnapshot;
        final int[] visiblePositions;
        AdapterPositionMap(int count, String snapshot, int[] positions) {
            originalCount = count;
            hiddenSnapshot = snapshot;
            visiblePositions = positions;
        }
    }
    private static final Set<String> diagnosedAdapterClasses = java.util.Collections.synchronizedSet(new HashSet<>());
    private static volatile String lastSettingsHiddenSnapshot = "";
    private static volatile long lastSettingsPrefsRefresh = 0L;
    /** 探测已知适配器，并监听 Settings 后续加载的应用管理类。 */
    private void hookSettingsAdapters(ClassLoader cl) {
        String[] adapters = {
                "com.meizu.settings.applications.ManageApplications$ApplicationsAdapter",
                "com.meizu.settings.applications.ManageApplications$ApplicationsAdapterImpl",
                "com.android.settings.applications.manageapplications.ManageApplications$ApplicationsAdapter",
                "com.android.settings.applications.manageapplications.ManageApplications$ApplicationsAdapterImpl",
                "com.android.settings.applications.ManageApplications$ApplicationsAdapter"
        };
        int installed = 0;
        for (String name : adapters) {
            try {
                installed += installAdapterCallbacks(Class.forName(name, false, cl));
            } catch (ClassNotFoundException ignored) {
                record("设置 Adapter 候选类不存在: " + name);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Settings adapter probe failed " + name + ": " + t);
            }
        }
        // Discover the concrete Flyme adapter when ListView/GridView receives it.
        // The actual class name differs between Flyme builds, so do not depend on guesses.
        for (String widgetName : new String[]{"android.widget.ListView", "android.widget.GridView"}) {
            try {
                Class<?> widget = Class.forName(widgetName, false, cl);
                for (Method method : widget.getDeclaredMethods()) {
                    if (!"setAdapter".equals(method.getName()) || method.getParameterTypes().length != 1
                            || !"android.widget.ListAdapter".equals(method.getParameterTypes()[0].getName())) continue;
                    String key = widgetName + "->" + method.toString();
                    if (!hookedRuntimeSetAdapterMethods.add(key)) continue;
                    try {
                        method.setAccessible(true);
                        hook(method).intercept(chain -> {
                            Object result = chain.proceed();
                            Object[] args = chain.getArgs().toArray();
                            if (args.length == 1 && args[0] != null) {
                                Class<?> adapterClass = args[0].getClass();
                                record("设置列表运行时 Adapter 已发现: " + adapterClass.getName());
                                int adapterHooks = installAdapterCallbacks(adapterClass);
                                record("设置列表运行时 Adapter Hook 扫描完成: "
                                        + adapterClass.getName() + "，命中=" + adapterHooks);
                                // The Settings Activity/owner may have been loaded before
                                // our ClassLoader observer was installed. Discover its
                                // enclosing ManageApplications class from the live adapter.
                                Class<?> ownerClass = adapterClass.getEnclosingClass();
                                if (ownerClass == null) {
                                    String adapterName = adapterClass.getName();
                                    int separator = adapterName.lastIndexOf('$');
                                    if (separator > 0) {
                                        try {
                                            ownerClass = Class.forName(adapterName.substring(0, separator),
                                                    false, adapterClass.getClassLoader());
                                        } catch (Throwable ignored) { }
                                    }
                                }
                                if (ownerClass != null) {
                                    record("设置列表 Adapter 外部管理类已定位: " + ownerClass.getName());
                                    installManageApplicationsReloadHook(ownerClass);
                                } else {
                                    record("设置列表 Adapter 未能定位外部管理类: " + adapterClass.getName());
                                }
                            }
                            return result;
                        });
                        record("设置列表 Adapter 发现 Hook 已安装: " + key);
                    } catch (Throwable hookError) {
                        hookedRuntimeSetAdapterMethods.remove(key);
                        record("设置列表 Adapter 发现 Hook 安装失败: " + key
                                + "，原因=" + hookError.getClass().getSimpleName());
                    }
                }
            } catch (Throwable t) {
                record("设置列表控件探测失败: " + widgetName + "，原因="
                        + t.getClass().getSimpleName());
            }
        }
        try {
            int loaderHooks = 0;
            for (Method loadClass : ClassLoader.class.getDeclaredMethods()) {
                if (!"loadClass".equals(loadClass.getName())) continue;
                Class<?>[] parameters = loadClass.getParameterTypes();
                if (!(parameters.length == 1 && parameters[0] == String.class)
                        && !(parameters.length == 2 && parameters[0] == String.class
                        && parameters[1] == boolean.class)) continue;
                hook(loadClass).intercept(chain -> {
                    Object result = chain.proceed();
                    String className = (String) chain.getArgs().get(0);
                    if (result instanceof Class && isSettingsAppListClass(className)) {
                        Class<?> loadedClass = (Class<?>) result;
                        installAdapterCallbacks(loadedClass);
                        installManageApplicationsReloadHook(loadedClass);
                    }
                    return result;
                });
                loaderHooks++;
            }
            record("设置动态类加载探测 Hook 已安装: " + loaderHooks
                    + "，监听 ManageApplications/ApplicationsState/ApplicationsAdapter/AppEntry");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Settings ClassLoader probe failed: " + t);
            record("设置动态类加载探测安装失败: " + t);
        }
        log(installed > 0 ? Log.INFO : Log.WARN, TAG,
                "Settings adapter callbacks initially installed: " + installed);
        record("设置列表 Adapter 初始 Hook 数=" + installed);
    }

    /**
     * 手机管家部分应用管理/搜索页面不使用 ApplicationsAdapter，而是直接消费
     * ContentProvider 返回的 Cursor。仅按明确的包名列过滤，避免 GONE 行造成空白。
     */
    private static final Set<String> hookedSafeProviderQueries =
            java.util.Collections.synchronizedSet(new HashSet<String>());

    private void hookSafeProviderQueries(ClassLoader cl) {
        // These providers have appeared in real Flyme Safe process diagnostics.
        String[] candidates = {
                "com.meizu.safe.search.SearchIndexableListProvider",
                "com.meizu.safe.sceneQuery.provider.SceneQueryProvider",
                "com.meizu.safe.common.provider.SearchIndexablesProvider"
        };
        for (String name : candidates) {
            try { installSafeProviderClass(Class.forName(name, false, cl)); }
            catch (Throwable ignored) { }
        }
        // Observe providers loaded lazily when the app-management screen is opened.
        try {
            int watchers = 0;
            for (Method loadClass : ClassLoader.class.getDeclaredMethods()) {
                if (!"loadClass".equals(loadClass.getName())) continue;
                Class<?>[] p = loadClass.getParameterTypes();
                if (!(p.length == 1 && p[0] == String.class)
                        && !(p.length == 2 && p[0] == String.class && p[1] == boolean.class)) continue;
                hook(loadClass).intercept(chain -> {
                    Object result = chain.proceed();
                    String name = (String) chain.getArgs().get(0);
                    if (result instanceof Class && name.startsWith("com.meizu.safe.")) {
                        String lower = name.toLowerCase(java.util.Locale.ROOT);
                        if (lower.contains("provider")) {
                            installSafeProviderClass((Class<?>) result);
                        }
                        // Safe 的应用管理搜索实现可能在页面打开/输入关键词后才加载。
                        // 不依赖 RecyclerView 本身的 ClassLoader，直接观察实际加载的业务类。
                        if (lower.contains("application") || lower.contains("appmanager")
                                || lower.contains("adapter") || lower.contains("search")
                                || lower.contains("filter") || lower.contains("appinfo")
                                || lower.contains("appitem") || lower.contains("result")
                                || lower.contains("query") || lower.contains("manager")) {
                            installSafeSearchClass((Class<?>) result);
                        }
                    }
                    return result;
                });
                watchers++;
            }
            record("手机管家 Provider 动态类监听已安装: " + watchers);
        } catch (Throwable t) {
            record("手机管家 Provider 动态监听安装异常: " + t.getClass().getSimpleName());
        }
    }

    private static final Set<String> diagnosedSafeSearchClasses =
            java.util.Collections.synchronizedSet(new HashSet<String>());

    /**
     * 记录手机管家真正加载的搜索/应用列表模型，并直接 Hook 实际 Adapter。
     * 之前只通过 RecyclerView.setAdapter 发现 Adapter，若 RecyclerView 来自另一
     * ClassLoader 或页面使用自定义列表，就会完全漏掉。
     */
    private void installSafeSearchClass(Class<?> candidate) {
        if (candidate == null || !candidate.getName().startsWith("com.meizu.safe.")) return;
        boolean hasCount = false;
        boolean hasFilter = false;
        boolean hasItem = false;
        boolean hasRecyclerCount = false;
        for (Class<?> type = candidate; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName();
                if ("getCount".equals(name) && method.getParameterTypes().length == 0
                        && method.getReturnType() == int.class) hasCount = true;
                if ("getItemCount".equals(name) && method.getParameterTypes().length == 0
                        && method.getReturnType() == int.class) hasRecyclerCount = true;
                if ("getItem".equals(name) && method.getParameterTypes().length == 1) hasItem = true;
                if ("getFilter".equals(name) && method.getParameterTypes().length == 0) hasFilter = true;
            }
        }
        String className = candidate.getName();
        if (diagnosedSafeSearchClasses.add(className)) {
            StringBuilder shape = new StringBuilder("手机管家搜索类结构: ").append(className);
            for (Class<?> type = candidate; type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    String fieldName = field.getName().toLowerCase(java.util.Locale.ROOT);
                    if (List.class.isAssignableFrom(field.getType()) || fieldName.contains("app")
                            || fieldName.contains("item") || fieldName.contains("result")
                            || fieldName.contains("data") || fieldName.contains("list")
                            || fieldName.contains("entry")) {
                        shape.append(" FIELD ").append(field.getName()).append(":")
                                .append(field.getType().getName());
                    }
                }
                for (Method method : type.getDeclaredMethods()) {
                    String methodName = method.getName().toLowerCase(java.util.Locale.ROOT);
                    if (methodName.contains("search") || methodName.contains("filter")
                            || methodName.contains("query") || methodName.contains("bind")
                            || methodName.contains("result") || methodName.equals("getitem")
                            || methodName.equals("getcount") || methodName.equals("getitemcount")
                            || methodName.equals("getfilter") || methodName.equals("onbindviewholder")) {
                        shape.append(" METHOD ").append(method.toString());
                    }
                }
            }
            record(shape.toString());
        }
        if (hasRecyclerCount) {
            installSafeRecyclerAdapter(candidate);
            record("手机管家搜索 Adapter 已按 getItemCount 接管: " + className);
        }
        if (hasCount && (hasItem || hasFilter || className.toLowerCase(java.util.Locale.ROOT).contains("adapter"))) {
            int count = installAdapterCallbacks(candidate);
            record("手机管家搜索 Adapter 通用 Hook 已安装: " + className + "，命中=" + count);
        }
    }

    private void installSafeProviderClass(Class<?> providerClass) {
        if (providerClass == null || !providerClass.getName().startsWith("com.meizu.safe.")) return;
        for (Method method : providerClass.getDeclaredMethods()) {
            if (!"query".equals(method.getName())
                    || !Cursor.class.isAssignableFrom(method.getReturnType())
                    || java.lang.reflect.Modifier.isAbstract(method.getModifiers())) continue;
            String key = providerClass.getName() + "->" + method.toString();
            if (!hookedSafeProviderQueries.add(key)) continue;
            try {
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!(result instanceof Cursor)) return result;
                    Set<String> hidden = readSettingsHiddenPackages();
                    if (hidden.isEmpty()) return result;
                    Cursor filtered = filterProviderCursor((Cursor) result, hidden);
                    return filtered == null ? result : filtered;
                });
                record("手机管家 Provider 查询过滤 Hook 成功: " + key);
            } catch (Throwable t) {
                hookedSafeProviderQueries.remove(key);
                record("手机管家 Provider 查询 Hook 失败: " + t.getClass().getSimpleName()
                        + "，方法=" + key);
            }
        }
    }

    private static Cursor filterProviderCursor(Cursor source, Set<String> hidden) {
        if (source == null || source.isClosed()) return null;
        try {
            String[] columns = source.getColumnNames();
            if (columns == null || columns.length == 0) return null;
            List<Integer> packageColumns = new ArrayList<>();
            for (int i = 0; i < columns.length; i++) {
                String name = columns[i] == null ? "" : columns[i].toLowerCase(java.util.Locale.ROOT)
                        .replace("_", "").replace("-", "");
                if (name.equals("package") || name.equals("packagename")
                        || name.equals("pkg") || name.equals("pkgname")
                        || name.equals("applicationpackage") || name.equals("applicationpackagename")) {
                    packageColumns.add(i);
                }
            }
            if (packageColumns.isEmpty()) return null;
            MatrixCursor output = new MatrixCursor(columns, source.getCount());
            int removed = 0;
            while (source.moveToNext()) {
                boolean shouldRemove = false;
                for (Integer index : packageColumns) {
                    if (index >= 0 && index < source.getColumnCount()
                            && !source.isNull(index) && hidden.contains(source.getString(index))) {
                        shouldRemove = true;
                        break;
                    }
                }
                if (shouldRemove) {
                    removed++;
                    continue;
                }
                Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    if (source.isNull(i)) {
                        row[i] = null;
                        continue;
                    }
                    switch (source.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: row[i] = source.getLong(i); break;
                        case Cursor.FIELD_TYPE_FLOAT: row[i] = source.getDouble(i); break;
                        case Cursor.FIELD_TYPE_BLOB: row[i] = source.getBlob(i); break;
                        default: row[i] = source.getString(i); break;
                    }
                }
                output.addRow(row);
            }
            if (removed == 0) return null;
            record("手机管家 Provider Cursor 已过滤隐藏应用: 移除=" + removed
                    + "，剩余=" + output.getCount());
            return output;
        } catch (Throwable t) {
            record("手机管家 Provider Cursor 过滤异常: " + t.getClass().getSimpleName());
            return null;
        } finally {
            try { source.moveToPosition(-1); } catch (Throwable ignored) { }
        }
    }

    // 手机管家应用管理页在部分 Flyme 版本使用 RecyclerView，不能只监听 ListView/GridView。
    private static final Set<String> hookedSafeRecyclerSetAdapter =
            java.util.Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> hookedSafeRecyclerCount =
            java.util.Collections.synchronizedSet(new HashSet<String>());
    private static final ThreadLocal<Boolean> filteringSafeRecycler =
            new ThreadLocal<Boolean>();

    private void hookSafeRecyclerAdapters(ClassLoader cl) {
        try {
            Class<?> recycler = Class.forName("androidx.recyclerview.widget.RecyclerView", false, cl);
            for (Method method : recycler.getDeclaredMethods()) {
                if (!"setAdapter".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
                String key = method.toString();
                if (!hookedSafeRecyclerSetAdapter.add(key)) continue;
                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        Object adapter = args.length == 1 ? args[0] : null;
                        if (adapter != null && adapter.getClass().getName().startsWith("com.meizu.safe.")) {
                            record("手机管家 RecyclerView Adapter 已发现: " + adapter.getClass().getName());
                            installSafeRecyclerAdapter(adapter.getClass());
                        }
                        return chain.proceed(args);
                    });
                    record("手机管家 RecyclerView Adapter 发现 Hook 已安装: " + key);
                } catch (Throwable t) {
                    hookedSafeRecyclerSetAdapter.remove(key);
                    record("手机管家 RecyclerView Adapter 发现 Hook 失败: " + t.getClass().getSimpleName());
                }
            }
        } catch (Throwable t) {
            record("手机管家 RecyclerView 不可用或探测失败: " + t.getClass().getSimpleName());
        }
    }

    private void installSafeRecyclerAdapter(Class<?> adapterClass) {
        // The adapter may come from a shared UI/SDK package even when hosted by Safe.
        // Callers are scoped to the Safe process or a verified Safe Activity view tree.
        if (adapterClass == null) return;
        for (Class<?> type = adapterClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"getItemCount".equals(method.getName()) || method.getParameterTypes().length != 0
                        || method.getReturnType() != int.class) continue;
                String key = adapterClass.getName() + "->" + method.toString();
                if (!hookedSafeRecyclerCount.add(key)) continue;
                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        if (Boolean.TRUE.equals(filteringSafeRecycler.get())) return chain.proceed();
                        Set<String> hidden = readSettingsHiddenPackages();
                        if (hidden.isEmpty()) return chain.proceed();
                        filteringSafeRecycler.set(true);
                        try {
                            // Filter the adapter's actual backing lists before RecyclerView reads its count.
                            filterAdapterListsAfterRebuild(chain.getThisObject(), hidden);
                        } finally {
                            filteringSafeRecycler.remove();
                        }
                        return chain.proceed();
                    });
                    record("手机管家 RecyclerView 列表过滤 Hook 成功: " + key);
                } catch (Throwable t) {
                    hookedSafeRecyclerCount.remove(key);
                    record("手机管家 RecyclerView 列表过滤 Hook 失败: " + t.getClass().getSimpleName());
                }
            }
        }
    }

    // Inspect the real Safe Activity view tree when app-management screens resume.
    private static volatile boolean safeActivityViewHookInstalled = false;

    private void hookSafeActivityViews() {
        if (safeActivityViewHookInstalled) return;
        synchronized (HookEntry.class) {
            if (safeActivityViewHookInstalled) return;
            try {
                Method onResume = Activity.class.getDeclaredMethod("onResume");
                onResume.setAccessible(true);
                hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    Object activity = chain.getThisObject();
                    if (activity instanceof Activity) {
                        Activity current = (Activity) activity;
                        String activityPackage = current.getPackageName();
                        String activityClass = current.getClass().getName();
                        // Some Flyme Safe screens are hosted by shared/SDK Activity classes,
                        // so the Activity implementation class name is not a reliable package test.
                        if ("com.meizu.safe".equals(activityPackage)
                                || activityClass.startsWith("com.meizu.safe.")) {
                            View decor = current.getWindow() == null ? null : current.getWindow().getDecorView();
                            record("手机管家 Activity 已进入: package=" + activityPackage
                                    + "，class=" + activityClass
                                    + "，decor=" + (decor == null ? "null" : decor.getClass().getName()));
                            if (decor != null) {
                                dumpSafeViewTree(decor, 0, new int[]{0});
                                // App-management screens often populate their list asynchronously
                                // after onResume. Re-scan after layout/data binding has settled.
                                decor.postDelayed(() -> dumpSafeViewTree(decor, 0, new int[]{0}), 350);
                                decor.postDelayed(() -> dumpSafeViewTree(decor, 0, new int[]{0}), 1200);
                                decor.postDelayed(() -> dumpSafeViewTree(decor, 0, new int[]{0}), 2600);
                            }
                        }
                    }
                    return result;
                });
                safeActivityViewHookInstalled = true;
                record("手机管家 Activity 页面结构探测 Hook 已安装");
            } catch (Throwable t) {
                record("手机管家 Activity 页面结构探测 Hook 失败: " + t.getClass().getSimpleName());
            }
        }
    }

    private void dumpSafeViewTree(View view, int depth, int[] visited) {
        if (view == null || depth > 18 || visited[0] >= 180) return;
        visited[0]++;
        StringBuilder line = new StringBuilder("手机管家页面视图: ")
                .append(view.getClass().getName()).append(" depth=").append(depth);
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null && text.length() > 0) {
                String value = text.toString();
                line.append(" text=").append(value.length() > 60 ? value.substring(0, 60) : value);
            }
        }
        Object adapter = null;
        try {
            Method getAdapter = findMethod(view.getClass(), "getAdapter");
            if (getAdapter != null && getAdapter.getParameterTypes().length == 0)
                adapter = getAdapter.invoke(view);
        } catch (Throwable ignored) { }
        if (adapter != null) {
            line.append(" adapter=").append(adapter.getClass().getName());
            record(line.toString());
            // This view was discovered under a verified Safe Activity, so its adapter
            // is in scope even when supplied by a shared Flyme/AndroidX UI package.
            installSafeRecyclerAdapter(adapter.getClass());
        } else if (view.getClass().getName().contains("RecyclerView")
                || view instanceof AbsListView || view instanceof android.widget.EditText) {
            record(line.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && visited[0] < 180; i++)
                dumpSafeViewTree(group.getChildAt(i), depth + 1, visited);
        }
    }

    private static boolean isSettingsAppListClass(String className) {
        if (className == null) return false;
        boolean settingsNamespace = className.startsWith("com.meizu.settings.")
                || className.startsWith("com.android.settings.")
                || className.startsWith("com.meizu.safe.");
        if (!settingsNamespace) return false;
        String lower = className.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("application") || lower.contains("appentry")
                || lower.contains("search") || lower.contains("filter")
                || lower.contains("query") || lower.contains("manageapps");
    }

    /** 扫描类及父类；兼容 List/ArrayList 参数和继承的方法。 */
    private static final Set<String> hookedManageApplicationsReload = java.util.Collections.synchronizedSet(new HashSet<String>());

    /**
     * ManageApplications owns the app-list lifecycle. On some Flyme builds the
     * concrete adapter is created/replaced after the initial Settings hooks run,
     * so discover it again after each completed data reload.
     */
    private void installManageApplicationsReloadHook(Class<?> ownerClass) {
        if (ownerClass == null || !ownerClass.getName().toLowerCase(java.util.Locale.ROOT).contains("manageapplications")) return;
        for (Class<?> type = ownerClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"onReloadDataCompleted".equals(method.getName())) continue;
                String key = ownerClass.getName() + "->" + method.toString();
                if (!hookedManageApplicationsReload.add(key)) continue;
                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        refreshSettingsAdaptersFromOwner(chain.getThisObject());
                        return result;
                    });
                    record("设置 ManageApplications 重载回调 Hook 成功: " + key);
                } catch (Throwable t) {
                    hookedManageApplicationsReload.remove(key);
                    record("设置 ManageApplications 重载回调 Hook 失败: " + key
                            + "，原因=" + t.getClass().getSimpleName());
                }
            }
        }
    }

    private void refreshSettingsAdaptersFromOwner(Object owner) {
        if (owner == null) return;
        Set<String> hidden = readSettingsHiddenPackages();
        int found = 0;
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value == null) continue;
                    if (value instanceof android.widget.ListAdapter
                            || value.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("adapter")) {
                        found++;
                        int installed = installAdapterCallbacks(value.getClass());
                        record("设置重载后重新检查 Adapter: " + value.getClass().getName()
                                + "，Hook数=" + installed);
                        if (!hidden.isEmpty()) filterAdapterListsAfterRebuild(value, hidden);
                    }
                } catch (Throwable ignored) { }
            }
        }
        record("设置 ManageApplications 重载后 Adapter 扫描完成: 找到=" + found
                + "，隐藏名单=" + hidden.size());
    }

    private int installAdapterCallbacks(Class<?> adapter) {
        if (adapter == null) return 0;
        if (diagnosedAdapterClasses.add(adapter.getName())) {
            StringBuilder shape = new StringBuilder("设置应用管理类结构: ")
                    .append(adapter.getName());
            for (Class<?> current = adapter; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(field.getType())
                            || field.getName().toLowerCase(java.util.Locale.ROOT).contains("entry")
                            || field.getName().toLowerCase(java.util.Locale.ROOT).contains("app")) {
                        shape.append(" FIELD ").append(field.getName()).append(":")
                                .append(field.getType().getName());
                    }
                }
                for (Method method : current.getDeclaredMethods()) {
                    String name = method.getName();
                    String lowerName = name.toLowerCase(java.util.Locale.ROOT);
                    if ("onRebuildComplete".equals(name) || "getFilter".equals(name)
                            || "performFiltering".equals(name) || "publishResults".equals(name)
                            || lowerName.contains("search") || lowerName.contains("query")
                            || lowerName.contains("filter") || "getCount".equals(name)
                            || "getItem".equals(name) || "getView".equals(name)
                            || "onBindViewHolder".equals(name)) {
                        shape.append(" METHOD ").append(method.toString());
                    }
                }
            }
            record(shape.toString());
        }
        int installed = 0;
        for (Class<?> type = adapter; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                // Filter logical adapter positions before ListView lays out rows.
                // GONE/zero-height children can leave Flyme dividers or empty slots.
                if ("getCount".equals(method.getName()) && method.getParameterTypes().length == 0
                        && method.getReturnType() == int.class) {
                    String positionKey = method.getDeclaringClass().getName() + "->COUNT->" + method.toString();
                    if (hookedPositionMethods.add(positionKey)) {
                        try {
                            method.setAccessible(true);
                            hook(method).intercept(chain -> {
                                int originalCount = (Integer) chain.proceed();
                                if (Boolean.TRUE.equals(bypassAdapterPositionHooks.get())) return originalCount;
                                Set<String> hidden = readSettingsHiddenPackages();
                                if (hidden.isEmpty() || originalCount <= 0) return originalCount;
                                Method getItem = findMethod(chain.getThisObject().getClass(), "getItem", int.class);
                                if (getItem == null) return originalCount;
                                AdapterPositionMap map = buildAdapterPositionMap(
                                        chain.getThisObject(), originalCount, getItem, hidden);
                                return map == null ? originalCount : map.visiblePositions.length;
                            });
                            record("设置 Adapter 可见数量过滤 Hook 成功: " + positionKey);
                            installed++;
                        } catch (Throwable t) {
                            hookedPositionMethods.remove(positionKey);
                            record("设置 Adapter 数量过滤 Hook 失败: " + positionKey
                                    + "，原因=" + t.getClass().getSimpleName());
                        }
                    }
                }
                if ("getItem".equals(method.getName()) && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0] == int.class
                        && !method.getReturnType().isPrimitive()) {
                    String positionKey = method.getDeclaringClass().getName() + "->ITEM->" + method.toString();
                    if (hookedPositionMethods.add(positionKey)) {
                        try {
                            method.setAccessible(true);
                            hook(method).intercept(chain -> {
                                if (Boolean.TRUE.equals(bypassAdapterPositionHooks.get())) return chain.proceed();
                                Object[] args = chain.getArgs().toArray();
                                if (args.length != 1 || !(args[0] instanceof Integer)) return chain.proceed();
                                Set<String> hidden = readSettingsHiddenPackages();
                                if (hidden.isEmpty()) return chain.proceed();
                                Object self = chain.getThisObject();
                                Method getCount = findMethod(self.getClass(), "getCount");
                                Method getItem = methodForAdapterItem(self.getClass());
                                if (getCount == null || getItem == null) return chain.proceed();
                                int originalCount;
                                try {
                                    bypassAdapterPositionHooks.set(true);
                                    getCount.setAccessible(true);
                                    originalCount = ((Number) getCount.invoke(self)).intValue();
                                } finally {
                                    bypassAdapterPositionHooks.remove();
                                }
                                String snapshot = new java.util.TreeSet<>(hidden).toString();
                                AdapterPositionMap map;
                                synchronized (adapterPositionMaps) { map = adapterPositionMaps.get(self); }
                                if (map == null || map.originalCount != originalCount || !map.hiddenSnapshot.equals(snapshot))
                                    map = buildAdapterPositionMap(self, originalCount, getItem, hidden);
                                int visiblePosition = (Integer) args[0];
                                if (map == null || visiblePosition < 0
                                        || visiblePosition >= map.visiblePositions.length) return null;
                                args[0] = map.visiblePositions[visiblePosition];
                                return chain.proceed(args);
                            });
                            record("设置 Adapter 可见条目映射 Hook 成功: " + positionKey);
                            installed++;
                        } catch (Throwable t) {
                            hookedPositionMethods.remove(positionKey);
                            record("设置 Adapter 条目映射 Hook 失败: " + positionKey
                                    + "，原因=" + t.getClass().getSimpleName());
                        }
                    }
                }
                // Flyme's search UI commonly delegates filtering to the adapter's
                // android.widget.Filter. Hook getFilter() so the actual asynchronous
                // search results are pruned before they are published to the UI.
                if ("getFilter".equals(method.getName())
                        && method.getParameterTypes().length == 0) {
                    try {
                        Class<?> filterBase = Class.forName("android.widget.Filter", false,
                                method.getDeclaringClass().getClassLoader());
                        if (filterBase.isAssignableFrom(method.getReturnType())) {
                            String filterKey = adapter.getName() + "->" + method.toString();
                            if (hookedFilterMethods.add(filterKey)) {
                                method.setAccessible(true);
                                hook(method).intercept(chain -> {
                                    Object filter = chain.proceed();
                                    hookSearchFilter(filter);
                                    return filter;
                                });
                                record("设置搜索 Filter 获取方法 Hook 成功: " + filterKey);
                                installed++;
                            }
                        }
                    } catch (Throwable t) {
                        record("设置搜索 Filter 获取方法 Hook 失败: " + method
                                + "，原因=" + t.getClass().getSimpleName());
                    }
                }
                if (!"onRebuildComplete".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || !List.class.isAssignableFrom(method.getParameterTypes()[0])) continue;
                String key = adapter.getName() + "->" + type.getName() + "#" + method.toString();
                if (hookedAdapterMethods.contains(key)) continue;
                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object[] args = chain.getArgs().toArray();
                        // 放在条件块外，回调执行后仍可用于检查 Adapter 内部列表。
                        Set<String> hidden = new HashSet<>();
                        if (args.length == 1 && args[0] instanceof List) {
                            List<?> source = (List<?>) args[0];
                            hidden = readSettingsHiddenPackages();
                            record("Flyme Adapter 回调命中: " + key
                                    + "，列表条目=" + source.size() + "，隐藏名单=" + hidden.size());
                            if (!hidden.isEmpty()) {
                                List<Object> filtered = new ArrayList<>(source.size());
                                int inspected = 0;
                                for (Object entry : source) {
                                    String pkg = applicationEntryPackage(entry);
                                    if (pkg != null && hidden.contains(pkg)) {
                                        record("Flyme Adapter 命中过滤目标包: " + pkg);
                                    } else {
                                        filtered.add(entry);
                                    }
                                    if (pkg == null && inspected++ < 3) {
                                        record("Flyme Adapter 条目结构: " + describeEntry(entry));
                                    }
                                }
                                if (filtered.size() != source.size()) {
                                    int removed = source.size() - filtered.size();
                                    // onRebuildComplete 可能持有/转交原始 ArrayList；
                                    // 只替换 Hook 参数未必能改变 Adapter 已保存的同一列表引用。
                                    // 在回调执行前原地过滤原列表，确保所有引用看到相同结果。
                                    try {
                                        @SuppressWarnings("unchecked")
                                        List<Object> mutableSource = (List<Object>) source;
                                        mutableSource.clear();
                                        mutableSource.addAll(filtered);
                                        record("Flyme 应用列表原地过滤 " + removed + " 个包，剩余=" + mutableSource.size());
                                    } catch (Throwable mutationError) {
                                        record("Flyme Adapter 原列表修改失败，改用过滤参数: "
                                                + mutationError.getClass().getSimpleName());
                                        args[0] = filtered;
                                    }
                                }
                            }
                        }
                        Object callbackResult = chain.proceed(args);
                        // 回调执行后检查 Adapter 实际持有的列表，确认过滤后的参数是否被内部逻辑重新填回。
                        if (args.length == 1 && args[0] instanceof List) {
                            // Flyme keeps independent backing lists (mBaseEntries/mEntries/
                            // mTempEntries). Filtering only the callback argument leaves those
                            // sources intact and the adapter can render the hidden row again.
                            filterAdapterListsAfterRebuild(chain.getThisObject(), hidden);
                        }
                        return callbackResult;
                    });
                    hookedAdapterMethods.add(key);
                    installed++;
                    record("设置 Adapter 方法 Hook 成功: " + key);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Settings adapter method hook failed " + key + ": " + t);
                }
            }
        }
        return installed;
    }

    private static Method methodForAdapterItem(Class<?> type) {
        return findMethod(type, "getItem", int.class);
    }

    private static AdapterPositionMap buildAdapterPositionMap(Object adapter, int originalCount,
                                                                Method getItem, Set<String> hidden) {
        if (adapter == null || getItem == null || originalCount < 0) return null;
        String snapshot = new java.util.TreeSet<>(hidden).toString();
        // Rebuild on each getCount(): search results can change while their count stays the same.
        List<Integer> visible = new ArrayList<>(originalCount);
        boolean previous = Boolean.TRUE.equals(bypassAdapterPositionHooks.get());
        try {
            bypassAdapterPositionHooks.set(true);
            getItem.setAccessible(true);
            for (int i = 0; i < originalCount; i++) {
                Object entry;
                try {
                    entry = getItem.invoke(adapter, i);
                } catch (Throwable itemError) {
                    visible.add(i);
                    continue;
                }
                String pkg = applicationEntryPackage(entry);
                if (pkg == null || !hidden.contains(pkg)) visible.add(i);
            }
        } catch (Throwable t) {
            record("设置 Adapter 可见位置映射失败: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (previous) bypassAdapterPositionHooks.set(true);
            else bypassAdapterPositionHooks.remove();
        }
        int[] positions = new int[visible.size()];
        for (int i = 0; i < visible.size(); i++) positions[i] = visible.get(i);
        AdapterPositionMap result = new AdapterPositionMap(originalCount, snapshot, positions);
        synchronized (adapterPositionMaps) {
            adapterPositionMaps.put(adapter, result);
        }
        return result;
    }

    /**
     * Install hooks on the concrete android.widget.Filter returned by Flyme's
     * ApplicationsAdapter. Search results are produced asynchronously, so filtering
     * only ApplicationsState/Adapter backing lists does not cover a query already
     * in progress. Sanitize both performFiltering output and publishResults input.
     */
    private void hookSearchFilter(Object filter) {
        if (filter == null) return;
        try {
            ClassLoader loader = filter.getClass().getClassLoader();
            Class<?> filterBase = Class.forName("android.widget.Filter", false, loader);
            if (!filterBase.isAssignableFrom(filter.getClass())) return;
            for (Class<?> type = filter.getClass(); type != null && filterBase.isAssignableFrom(type);
                    type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    String name = method.getName();
                    if (!"performFiltering".equals(name) && !"publishResults".equals(name)) continue;
                    String key = filter.getClass().getName() + "->" + method.toString();
                    if (!hookedFilterMethods.add(key)) continue;
                    try {
                        method.setAccessible(true);
                        hook(method).intercept(chain -> {
                            if ("publishResults".equals(name)) {
                                Object[] args = chain.getArgs().toArray();
                                if (args.length >= 2) filterSearchResults(args[1], "publishResults");
                                return chain.proceed(args);
                            }
                            Object result = chain.proceed();
                            filterSearchResults(result, "performFiltering");
                            return result;
                        });
                        record("设置搜索结果过滤 Hook 成功: " + key);
                    } catch (Throwable t) {
                        hookedFilterMethods.remove(key);
                        record("设置搜索结果过滤 Hook 失败: " + key
                                + "，原因=" + t.getClass().getSimpleName());
                    }
                }
            }
        } catch (Throwable t) {
            record("设置搜索 Filter 安装异常: " + t.getClass().getSimpleName());
        }
    }

    /** Remove hidden AppEntry/ApplicationInfo values from Android Filter.FilterResults. */
    private void filterSearchResults(Object filterResults, String stage) {
        if (filterResults == null) return;
        Set<String> hidden = readSettingsHiddenPackages();
        if (hidden.isEmpty()) return;
        try {
            Field valuesField = findField(filterResults.getClass(), "values");
            if (valuesField == null) return;
            valuesField.setAccessible(true);
            Object values = valuesField.get(filterResults);
            Object replacement = values;
            int removed = 0;
            int remaining = -1;
            if (values instanceof List) {
                List<?> source = (List<?>) values;
                List<Object> kept = new ArrayList<>(source.size());
                for (Object entry : source) {
                    String pkg = applicationEntryPackage(entry);
                    if (pkg != null && hidden.contains(pkg)) removed++;
                    else kept.add(entry);
                }
                if (removed > 0) {
                    replacement = kept;
                    remaining = kept.size();
                }
            } else if (values != null && values.getClass().isArray()) {
                int length = Array.getLength(values);
                List<Object> kept = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    Object entry = Array.get(values, i);
                    String pkg = applicationEntryPackage(entry);
                    if (pkg != null && hidden.contains(pkg)) removed++;
                    else kept.add(entry);
                }
                if (removed > 0) {
                    Object array = Array.newInstance(values.getClass().getComponentType(), kept.size());
                    for (int i = 0; i < kept.size(); i++) Array.set(array, i, kept.get(i));
                    replacement = array;
                    remaining = kept.size();
                }
            }
            if (removed == 0) return;
            try {
                valuesField.set(filterResults, replacement);
            } catch (Throwable replaceError) {
                if (values instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> mutable = (List<Object>) values;
                    mutable.clear();
                    mutable.addAll((List<Object>) replacement);
                } else {
                    throw replaceError;
                }
            }
            Field countField = findField(filterResults.getClass(), "count");
            if (countField != null && remaining >= 0) {
                countField.setAccessible(true);
                countField.setInt(filterResults, remaining);
            }
            record("设置搜索结果已过滤: 阶段=" + stage + "，移除=" + removed
                    + "，剩余=" + remaining + "，隐藏名单=" + hidden.size());
        } catch (Throwable t) {
            record("设置搜索结果过滤失败: 阶段=" + stage
                    + "，原因=" + t.getClass().getSimpleName());
        }
    }

    /**
     * Flyme adapter stores the rebuild result in several independent List fields.
     * Prune each backing list after onRebuildComplete, not just the callback argument.
     */
    private static void filterAdapterListsAfterRebuild(Object adapter, Set<String> hidden) {
        if (adapter == null || hidden == null || hidden.isEmpty()) return;
        Set<List<?>> visited = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<List<?>, Boolean>());
        int fields = 0;
        int removedTotal = 0;
        for (Class<?> type = adapter.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(adapter);
                    if (!(value instanceof List)) continue;
                    List<?> list = (List<?>) value;
                    if (!visited.add(list)) continue;
                    List<Object> keep = new ArrayList<>(list.size());
                    int removed = 0;
                    for (Object entry : list) {
                        String pkg = applicationEntryPackage(entry);
                        if (pkg != null && hidden.contains(pkg)) removed++;
                        else keep.add(entry);
                    }
                    if (removed > 0) {
                        @SuppressWarnings("unchecked")
                        List<Object> mutable = (List<Object>) list;
                        mutable.clear();
                        mutable.addAll(keep);
                        fields++;
                        removedTotal += removed;
                    }
                    int residual = 0;
                    for (Object entry : list) {
                        String pkg = applicationEntryPackage(entry);
                        if (pkg != null && hidden.contains(pkg)) residual++;
                    }
                    record("Flyme Adapter 数据源审计: " + type.getName() + "#"
                            + field.getName() + "，条目=" + list.size()
                            + "，本次移除=" + removed + "，隐藏残留=" + residual);
                } catch (Throwable t) {
                    record("Flyme Adapter 内部列表过滤失败: " + type.getName() + "#"
                            + field.getName() + "，原因=" + t.getClass().getSimpleName());
                }
            }
        }
        record("Flyme Adapter 内部数据源过滤完成: 列表数=" + fields
                + "，移除条目合计=" + removedTotal);
        // Also inspect the exact item stream consumed by ListView, bypassing our
        // position hooks to avoid recursion and distinguish backing data from stale rows.
        auditSettingsAdapterItems(adapter, hidden);
        if (removedTotal > 0) {
            try {
                Method notify = adapter.getClass().getMethod("notifyDataSetChanged");
                notify.invoke(adapter);
                record("Flyme Adapter 过滤后已主动通知界面刷新");
            } catch (Throwable t) {
                record("Flyme Adapter 主动刷新失败: " + t.getClass().getSimpleName());
            }
        }
    }

    private static void auditSettingsAdapterItems(Object adapter, Set<String> hidden) {
        Method countMethod = findMethod(adapter.getClass(), "getCount");
        Method itemMethod = methodForAdapterItem(adapter.getClass());
        if (countMethod == null || itemMethod == null) {
            record("Flyme Adapter 最终条目审计跳过: getCount/getItem 不完整");
            return;
        }
        boolean previous = Boolean.TRUE.equals(bypassAdapterPositionHooks.get());
        int count = -1;
        int hiddenHits = 0;
        int unknown = 0;
        try {
            bypassAdapterPositionHooks.set(true);
            countMethod.setAccessible(true);
            itemMethod.setAccessible(true);
            count = ((Number) countMethod.invoke(adapter)).intValue();
            for (int i = 0; i < count; i++) {
                Object entry;
                try {
                    entry = itemMethod.invoke(adapter, i);
                } catch (Throwable itemError) {
                    unknown++;
                    continue;
                }
                String pkg = applicationEntryPackage(entry);
                if (pkg == null) unknown++;
                else if (hidden.contains(pkg)) {
                    hiddenHits++;
                    if (hiddenHits <= 5)
                        record("Flyme Adapter 最终条目仍含隐藏包: position=" + i + "，package=" + pkg);
                }
            }
        } catch (Throwable t) {
            record("Flyme Adapter 最终条目审计失败: " + t.getClass().getSimpleName());
        } finally {
            if (previous) bypassAdapterPositionHooks.set(true);
            else bypassAdapterPositionHooks.remove();
        }
        record("Flyme Adapter 最终条目审计: getCount=" + count
                + "，隐藏命中=" + hiddenHits + "，无法识别=" + unknown);
    }

    private static String describeEntry(Object entry) {
        if (entry == null) return "null";
        StringBuilder out = new StringBuilder(entry.getClass().getName());
        for (String fieldName : new String[]{"info", "applicationInfo", "mInfo", "packageName"}) {
            try {
                Field field = findField(entry.getClass(), fieldName);
                if (field == null) continue;
                Object value = field.get(entry);
                out.append(" ").append(fieldName).append("=")
                        .append(value == null ? "null" : value.getClass().getName());
                if (value instanceof ApplicationInfo) out.append("(").append(((ApplicationInfo) value).packageName).append(")");
                else if (value instanceof PackageInfo) out.append("(").append(((PackageInfo) value).packageName).append(")");
                else if (value instanceof String) out.append("(").append(value).append(")");
            } catch (Throwable t) {
                out.append(" ").append(fieldName).append("=<").append(t.getClass().getSimpleName()).append(">");
            }
        }
        return out.toString();
    }

    /**
     * 每次过滤前重新获取 RemotePreferences，避免设置进程长期持有旧代理而读到过期名单。
     * 只读访问发生在 Hook 进程；写入仍由模块 App 完成。
     */
    private Set<String> readSettingsHiddenPackages() {
        // 该方法在 getCount/getItem 等热路径里每次调用，不能每次都重新取代理；2 秒节流足够保证新鲜度。
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSettingsPrefsRefresh > 2000) {
            lastSettingsPrefsRefresh = now;
            try {
                SharedPreferences latest = getRemotePreferences(Config.GROUP);
                if (latest != null) settingsPrefs = latest;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "刷新设置进程 RemotePreferences 失败: " + t);
            }
        }
        Set<String> hidden = new HashSet<>();
        SharedPreferences config = settingsPrefs;
        if (config == null) return hidden;
        try {
            // 总开关关闭时必须停止过滤；此前关闭“启用 Flyme Shadow”后设置/管家里仍然看不到应用。
            if (!config.getBoolean(Config.K_ENABLED, false)) return hidden;
            Set<String> selected = config.getStringSet(Config.K_PKGS, null);
            Set<String> discovered = config.getStringSet(Config.K_DISCOVERED_PKGS, null);
            if (selected != null) hidden.addAll(selected);
            if (discovered != null) hidden.addAll(discovered);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "读取设置进程隐藏名单失败: " + t);
        }
        hidden.remove("com.flyme.shadow");
        // Emit a diagnostic only when the effective set changes, avoiding callback log spam.
        String snapshot = new java.util.TreeSet<>(hidden).toString();
        if (!snapshot.equals(lastSettingsHiddenSnapshot)) {
            lastSettingsHiddenSnapshot = snapshot;
            record("设置进程隐藏名单已更新: 总数=" + hidden.size() + "，名单=" + snapshot);
        }
        return hidden;
    }

    private static String applicationEntryPackage(Object entry) {
        if (entry == null) return null;
        for (String fieldName : new String[]{"info", "applicationInfo", "mInfo", "packageName"}) {
            try {
                Field field = findField(entry.getClass(), fieldName);
                if (field == null) continue;
                Object value = field.get(entry);
                if (value instanceof ApplicationInfo) return ((ApplicationInfo) value).packageName;
                if (value instanceof PackageInfo) return ((PackageInfo) value).packageName;
                if (value instanceof String && !((String) value).isEmpty()) return (String) value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- hooks

    /**
     * Launcher/Flyme often loads workspace model callback classes lazily.
     * Watch the launcher class loader and attach to workspace-item bind methods
     * as soon as their declaring class appears.
     */
    private int hookLauncherClassLoader(ClassLoader cl) {
        int count = 0;
        for (Method method : ClassLoader.class.getDeclaredMethods()) {
            if (!"loadClass".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (!(params.length == 1 && params[0] == String.class)
                    && !(params.length == 2 && params[0] == String.class
                    && params[1] == boolean.class)) continue;
            try {
                hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Class) {
                        Class<?> loaded = (Class<?>) result;
                        String name = loaded.getName();
                        if (name.startsWith("com.android.launcher3.")
                                || name.startsWith("com.meizu.flyme.launcher.")
                                || name.startsWith("com.meizu.launcher.")) {
                            diagnoseLauncherBindingClass(loaded);
                            installLauncherWorkspaceMethods(loaded);
                        }
                    }
                    return result;
                });
                count++;
            } catch (Throwable t) {
                Log.w(TAG, "桌面动态类加载监听安装失败: " + t);
            }
        }
        record("桌面动态类加载监听 Hook 数=" + count);
        return count;
    }

    /**
     * Record bind-like methods from real launcher/model classes. This reveals Flyme
     * callback names that differ from Launcher3 without hooking arbitrary methods.
     */
    private void diagnoseLauncherBindingClass(Class<?> type) {
        String className = type.getName();
        if (!diagnosedLauncherClasses.add(className)) return;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String methodName = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (!methodName.startsWith("bind") && !methodName.contains("workspace")) continue;
                StringBuilder signature = new StringBuilder(method.toString());
                record("桌面绑定候选类诊断: " + signature);
            }
        }
    }

    private int installLauncherWorkspaceMethods(Class<?> type) {
        int installed = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!isWorkspaceBindingMethod(method)) continue;
                String key = method.toString();
                if (hookedLauncherBindingMethods.contains(key)) continue;
                try {
                    method.setAccessible(true);
                    if (hookBind(method)) installed++;
                    record("发现并 Hook 桌面工作区项目回调: " + key);
                } catch (Throwable t) {
                    hookedLauncherBindingMethods.remove(key);
                    Log.w(TAG, "桌面工作区项目回调 Hook 失败: " + key + "，" + t);
                }
            }
        }
        return installed;
    }

    private static boolean isWorkspaceBindingMethod(Method method) {
        String name = method.getName();
        if (WORKSPACE_ITEM_METHODS.contains(name)) return true;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("bind")) return false;
        if (lower.contains("allapplications") || lower.contains("allapps")
                || lower.contains("appdrawer")) return false;
        if (lower.contains("workspace")) return true;
        if (!lower.contains("item")) return false;
        for (Class<?> parameter : method.getParameterTypes()) {
            if (List.class.isAssignableFrom(parameter)
                    || parameter.isArray() && !parameter.getComponentType().isPrimitive()) {
                return true;
            }
        }
        return false;
    }

    /** @return true 表示本次新装了 Hook；已存在则返回 false；安装失败会清理标记并抛出异常。 */
    private boolean hookBind(Method m) {
        final String name = m.getName();
        final boolean workspacePayloadMethod = isWorkspaceBindingMethod(m);
        String hookKey = m.toString();
        if (!hookedLauncherBindingMethods.add(hookKey)) return false;
        try {
            m.setAccessible(true);
            installBindHook(m, name, workspacePayloadMethod);
        } catch (Throwable t) {
            hookedLauncherBindingMethods.remove(hookKey);
            throw t;
        }
        return true;
    }

    private void installBindHook(Method m, final String name, final boolean workspacePayloadMethod) {
        hook(m).intercept(chain -> {
            Object self = chain.getThisObject();
            if (self instanceof Activity) {
                launcherRef = new WeakReference<>((Activity) self);
                launcherContext = ((Activity) self).getApplicationContext();
            }

            // getArgs() 的返回类型是 List 还是数组都兼容
            Object raw = chain.getArgs();
            Object[] args = toArray(raw);

            record("桌面回调: " + name + "，参数=" + describeArgs(args));
            if ("bindScreens".equals(name)) {
                long[] ids = longsOf(args);
                if (ids != null) { orderedScreens = ids; record("捕获桌面页 ID: " + java.util.Arrays.toString(ids)); }   // 记录“未过滤”的完整页序
            }
            // Only workspace-item callbacks carry actual desktop page contents.
            // bindScreens contains page IDs; bindAllApplications contains app-drawer data.
            if (workspacePayloadMethod) {
                record("桌面工作区项目回调参数诊断: " + describeArgs(args));
                boolean fullSnapshot = "bindItems".equals(name)
                        || "bindWorkspaceItems".equals(name);
                discoverHiddenApps(args, fullSnapshot, name);
            }
            if (!active()) return chain.proceed();

            boolean changed = false;
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                Object n = filterArg(a, SCREEN_METHODS.contains(name));
                if (n != a) {
                    args[i] = n;
                    changed = true;
                }
            }
            return changed ? chain.proceed(args) : chain.proceed();
        });
    }

    private void hookApplicationOnCreate() {
        try {
            Method m = Application.class.getDeclaredMethod("onCreate");
            hook(m).intercept(chain -> {
                Object r = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Application
                        && Config.LAUNCHER_PKG.equals(((Application) self).getPackageName())) {
                    registerReceiver((Application) self);
                }
                return r;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook Application.onCreate failed: " + t);
        }
    }

    private static synchronized void registerReceiver(Application app) {
        if (receiverRegistered) return;
        receiverRegistered = true;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    SharedPreferences p = prefs;
                    if (unlocked && p != null && p.getBoolean(Config.K_AUTO_LOCK, true)) {
                        unlocked = false;
                        refreshLauncher();
                    }
                    return;
                }
                SharedPreferences p = prefs;
                String real = p == null ? null : p.getString(Config.K_TOKEN, null);
                String got = i.getStringExtra(Config.EXTRA_TOKEN);
                if (real == null || got == null
                        || !MessageDigest.isEqual(
                        real.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        got.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return;
                if (Config.ACTION_UNLOCK.equals(a)) unlocked = true;
                else if (Config.ACTION_LOCK.equals(a)) unlocked = false;
                else return;
                refreshLauncher();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Config.ACTION_UNLOCK);
        f.addAction(Config.ACTION_LOCK);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);

        // 设置改动后立刻让桌面按新规则重绑
        SharedPreferences p = prefs;
        if (p != null) {
            prefListener = (sp, key) -> {
                // 仅真正影响桌面显示的配置变化才重建桌面。
                // 诊断日志也写入 RemotePreferences；若监听所有 key，
                // bindScreens 写日志 -> recreate -> bindScreens 会形成无限刷新循环。
                if (Config.K_ENABLED.equals(key)
                        || Config.K_AUTO_LOCK.equals(key)
                        || Config.K_PAGES.equals(key)
                        || Config.K_AUTO_DISCOVER.equals(key)
                        || Config.K_PKGS.equals(key)
                        || Config.K_DISCOVERED_PKGS.equals(key)) {
                    refreshLauncher();
                }
            };
            p.registerOnSharedPreferenceChangeListener(prefListener);
        }
    }

    private static void refreshLauncher() {
        final Activity a = launcherRef.get();
        if (a != null) a.runOnUiThread(a::recreate);
    }

    /**
     * Scan the full launcher bind payload and synchronize an authoritative snapshot
     * of packages currently placed on selected hidden desktop pages. An empty snapshot
     * is important: removing a page from the hidden-page selection must restore apps.
     */
    private static void discoverHiddenApps(Object[] args, boolean fullSnapshot, String callbackName) {
        if (prefs == null || !prefs.getBoolean(Config.K_AUTO_DISCOVER, true)) return;
        Set<Long> hidden = hiddenScreenIds();
        Set<String> found = new HashSet<>();
        boolean recognizedWorkspacePayload = false;
        int visited = 0;
        int packageResolved = 0;
        java.util.Map<Long, Integer> desktopScreens = new java.util.TreeMap<>();
        java.util.Map<Long, Integer> containers = new java.util.TreeMap<>();
        StringBuilder argSummary = new StringBuilder();
        for (int argIndex = 0; argIndex < args.length; argIndex++) {
            Object arg = args[argIndex];
            if (argSummary.length() > 0) argSummary.append("; ");
            if (arg == null) argSummary.append(argIndex).append(":null");
            else if (arg instanceof Collection) argSummary.append(argIndex).append(":")
                    .append(arg.getClass().getSimpleName()).append("[").append(((Collection<?>) arg).size()).append("]");
            else argSummary.append(argIndex).append(":").append(arg.getClass().getName());
            if (arg instanceof Collection) {
                for (Object item : (Collection<?>) arg) {
                    visited++;
                    Object workspaceItem = workspaceRecordOf(item);
                    if (workspaceItem != null) recognizedWorkspacePayload = true;
                    String pkg = packageOf(workspaceItem != null ? workspaceItem : item);
                    if (pkg != null) packageResolved++;
                    trackWorkspacePosition(workspaceItem != null ? workspaceItem : item, desktopScreens, containers);
                    collectHidden(workspaceItem != null ? workspaceItem : item, hidden, found);
                    if (visited <= 8) record("工作区项目样本: " + describeWorkspaceItem(item));
                }
            } else if (arg != null && arg.getClass().isArray()
                    && !arg.getClass().getComponentType().isPrimitive()) {
                for (int i = 0; i < Array.getLength(arg); i++) {
                    Object item = Array.get(arg, i);
                    visited++;
                    Object workspaceItem = workspaceRecordOf(item);
                    if (workspaceItem != null) recognizedWorkspacePayload = true;
                    String pkg = packageOf(workspaceItem != null ? workspaceItem : item);
                    if (pkg != null) packageResolved++;
                    trackWorkspacePosition(workspaceItem != null ? workspaceItem : item, desktopScreens, containers);
                    collectHidden(workspaceItem != null ? workspaceItem : item, hidden, found);
                    if (visited <= 8) record("工作区项目样本: " + describeWorkspaceItem(item));
                }
            } else if (isWorkspaceRecord(arg)) {
                visited++;
                recognizedWorkspacePayload = true;
                if (packageOf(arg) != null) packageResolved++;
                trackWorkspacePosition(arg, desktopScreens, containers);
                collectHidden(arg, hidden, found);
                if (visited <= 8) record("工作区项目样本: " + describeWorkspaceItem(arg));
            }
        }
        record("工作区扫描统计: 回调=" + callbackName + "，参数项目=" + visited + "，识别记录=" + recognizedWorkspacePayload
                + "，可解析包名=" + packageResolved + "，隐藏页ID=" + hidden
                + "，本次命中包名=" + new java.util.TreeSet<>(found)
                + "，桌面screen分布=" + desktopScreens + "，container分布=" + containers
                + "，orderedScreens=" + java.util.Arrays.toString(orderedScreens)
                + "，参数结构=" + argSummary + "，完整快照=" + fullSnapshot);
        if (!recognizedWorkspacePayload) {
            record("桌面回调未识别到 container/screenId 项目，本次不更新隐藏包名单");
            return;
        }

        found.remove("com.flyme.shadow");
        Set<String> merged = new HashSet<>();
        if (!fullSnapshot) merged.addAll(set(Config.K_DISCOVERED_PKGS));
        merged.addAll(found);
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(merged);
        String snapshot = sorted.toString();
        if (snapshot.equals(lastSentDiscoveredSnapshot)) return;

        Context context = launcherContext;
        String token = prefs.getString(Config.K_TOKEN, null);
        if (context == null || token == null || token.isEmpty()) {
            Log.w(TAG, "隐藏页应用快照已扫描，但同步上下文或令牌缺失");
            return;
        }
        Intent sync = new Intent(Config.ACTION_SYNC_DISCOVERED)
                .setPackage("com.flyme.shadow")
                .putExtra(Config.EXTRA_TOKEN, token)
                .putStringArrayListExtra("packages", new ArrayList<>(sorted));
        try {
            context.sendBroadcast(sync);
            lastSentDiscoveredSnapshot = snapshot;
            record((fullSnapshot ? "已发送隐藏页应用完整快照: " : "已发送隐藏页应用增量合并结果: ")
                    + "数量=" + sorted.size() + "，包名=" + snapshot);
        } catch (Throwable t) {
            Log.e(TAG, "发送隐藏页应用快照失败: " + t);
        }
    }

    private static void trackWorkspacePosition(Object item, java.util.Map<Long, Integer> screens,
                                               java.util.Map<Long, Integer> containers) {
        if (item == null) return;
        Long container = longFieldAny(item, "container", "mContainer");
        Long screen = longFieldAny(item, "screenId", "mScreenId", "screen", "mScreen");
        if (container != null) containers.put(container, containers.containsKey(container)
                ? containers.get(container) + 1 : 1);
        if (container != null && container == CONTAINER_DESKTOP && screen != null)
            screens.put(screen, screens.containsKey(screen) ? screens.get(screen) + 1 : 1);
    }

    // Flyme bindInflatedItems 使用 Pair 包装实际的 WorkspaceItemInfo。
    // 递归展开 Pair.first/second，最多两层，避免把包装对象误判为工作区记录。
    private static Object workspaceRecordOf(Object item) {
        return workspaceRecordOf(item, 0);
    }

    private static Object workspaceRecordOf(Object item, int depth) {
        if (item == null || depth > 2) return null;
        if (isWorkspaceRecord(item)) return item;
        if (!"android.util.Pair".equals(item.getClass().getName())) return null;
        for (String fieldName : new String[]{"first", "second"}) {
            try {
                Field field = findField(item.getClass(), fieldName);
                if (field == null) continue;
                Object nested = workspaceRecordOf(field.get(item), depth + 1);
                if (nested != null) return nested;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static String describeWorkspaceItem(Object item) {
        if (item == null) return "null";
        if ("android.util.Pair".equals(item.getClass().getName())) {
            Object nested = workspaceRecordOf(item);
            if (nested != null) return "Pair -> " + describeWorkspaceItem(nested);
            StringBuilder pair = new StringBuilder("Pair");
            for (String fieldName : new String[]{"first", "second"}) {
                try {
                    Field field = findField(item.getClass(), fieldName);
                    Object value = field == null ? null : field.get(item);
                    pair.append(" ").append(fieldName).append("=")
                            .append(value == null ? "null" : value.getClass().getName());
                } catch (Throwable ignored) { }
            }
            return pair.toString();
        }
        StringBuilder out = new StringBuilder(item.getClass().getName());
        out.append(" container=").append(longFieldAny(item, "container", "mContainer"));
        out.append(" screenId=").append(longFieldAny(item, "screenId", "mScreenId"));
        out.append(" screen=").append(longFieldAny(item, "screen", "mScreen"));
        out.append(" package=").append(packageOf(item));
        for (String name : new String[]{"intent", "componentName", "targetComponent", "mIntent"}) {
            try {
                Field field = findField(item.getClass(), name);
                if (field == null) continue;
                field.setAccessible(true);
                Object value = field.get(item);
                if (value instanceof Intent) {
                    Intent intent = (Intent) value;
                    out.append(" ").append(name).append("=").append(intent.getComponent());
                } else if (value instanceof android.content.ComponentName) {
                    out.append(" ").append(name).append("=").append(value);
                }
            } catch (Throwable ignored) { }
        }
        // Dump concrete primitive/string fields to identify Flyme model variants.
        for (Class<?> type = item.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String fieldName = field.getName();
                if (fieldName.startsWith("$") || fieldName.length() > 48) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value instanceof Number || value instanceof String || value instanceof android.content.ComponentName) {
                        out.append(" ").append(fieldName).append("=").append(String.valueOf(value));
                    } else if (value instanceof Intent) {
                        Intent intent = (Intent) value;
                        out.append(" ").append(fieldName).append("=Intent(").append(intent.getComponent()).append(",").append(intent.getPackage()).append(")");
                    }
                } catch (Throwable ignored) { }
            }
        }
        return out.toString();
    }

    private static boolean isWorkspaceRecord(Object item) {
        if (item == null) return false;
        Long container = longFieldAny(item, "container", "mContainer");
        Long screen = longFieldAny(item, "screenId", "mScreenId", "screen", "mScreen");
        return container != null && screen != null;
    }

    private static void collectHidden(Object item, Set<Long> hidden, Set<String> found) {
        if (item == null) return;
        Long container = longFieldAny(item, "container", "mContainer");
        Long screen = longFieldAny(item, "screenId", "mScreenId", "screen", "mScreen");
        if (container != null && container == CONTAINER_DESKTOP
                && screen != null && hidden.contains(screen)) {
            // A widget is a placement on this page, not proof that its provider app
            // should be hidden globally. The widget itself is removed by screenId
            // in isHiddenItem(); never add its provider package to discovered apps.
            if (isLauncherWidgetItem(item)) {
                record("隐藏页小组件仅按页面位置过滤，不加入应用隐藏名单: "
                        + item.getClass().getName() + "，包名=" + packageOf(item)
                        + "，screenId=" + screen);
                return;
            }
            String pkg = packageOf(item);
            if (pkg != null && !pkg.equals("com.flyme.shadow"))
                found.add(pkg);
        }
    }

    private static boolean isLauncherWidgetItem(Object item) {
        for (Class<?> type = item == null ? null : item.getClass();
                type != null && type != Object.class; type = type.getSuperclass()) {
            String name = type.getName();
            String simple = type.getSimpleName();
            if (name.contains("LauncherAppWidgetInfo")
                    || simple.equals("LauncherAppWidgetInfo")
                    || simple.equals("AppWidgetInfo")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------- filtering

    private static boolean active() {
        SharedPreferences p = prefs;
        return p != null && p.getBoolean(Config.K_ENABLED, false) && !unlocked;
    }

    private static Set<String> set(String k) {
        SharedPreferences p = prefs;
        Set<String> s = p == null ? null : p.getStringSet(k, null);
        // 必须返回副本：filterArg 会对结果 addAll，直接改 SharedPreferences 返回的集合
        // 可能抛 UnsupportedOperationException，或把自动识别的包名污染进手动名单。
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    /** 被隐藏页面对应的 screenId 集合（页码是 1 起的显示顺序） */
    private static Set<Long> hiddenScreenIds() {
        Set<Long> out = new HashSet<>();
        long[] ids = orderedScreens;
        for (String s : set(Config.K_PAGES)) {
            try {
                int idx = Integer.parseInt(s) - 1;
                if (idx >= 0 && idx < ids.length) out.add(ids[idx]);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 返回过滤后的新对象；不需要改动则原样返回同一个对象 */
    private static Object filterArg(Object a, boolean filterScreens) {
        if (a == null) return null;
        Set<String> pkgs = set(Config.K_PKGS);
        pkgs.addAll(set(Config.K_DISCOVERED_PKGS));
        Set<Long> hs = hiddenScreenIds();

        // Flyme 还会通过 LinkedHashSet 增量更新工作区项目。
        // 必须过滤 Collection，而不只是 List；并保持 Set 参数的具体类型兼容性。
        if (a instanceof Collection) {
            Collection<?> in = (Collection<?>) a;
            Collection<Object> out = a instanceof Set
                    ? new java.util.LinkedHashSet<Object>() : new ArrayList<Object>(in.size());
            boolean ch = false;
            for (Object o : in) {
                if (isHiddenItem(o, pkgs, hs)) ch = true; else out.add(o);
            }
            if (ch) record("桌面项目过滤: " + in.size() + " -> " + out.size()
                    + "，容器类型=" + a.getClass().getSimpleName());
            return ch ? out : a;
        }
        if (a instanceof int[]) {
            if (!filterScreens) return a;
            int[] in = (int[]) a;
            List<Integer> out = new ArrayList<>();
            for (int v : in) if (!hs.contains((long) v)) out.add(v);
            if (out.size() == in.length) return a;
            record("桌面页过滤: " + in.length + " -> " + out.size() + "，隐藏页ID=" + hs);
            int[] res = new int[out.size()];
            for (int i = 0; i < res.length; i++) res[i] = out.get(i);
            return res;
        }
        if (a.getClass().isArray() && !a.getClass().getComponentType().isPrimitive()) {
            int n = Array.getLength(a);
            List<Object> keep = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Object o = Array.get(a, i);
                if (!isHiddenItem(o, pkgs, hs)) keep.add(o);
            }
            if (keep.size() == n) return a;
            Object res = Array.newInstance(a.getClass().getComponentType(), keep.size());
            for (int i = 0; i < keep.size(); i++) Array.set(res, i, keep.get(i));
            return res;
        }
        // Launcher3 自带的 IntArray（com.android.launcher3.util.IntArray）
        if (filterScreens && a.getClass().getName().endsWith(".IntArray")) {
            try {
                Method size = a.getClass().getMethod("size");
                Method get = a.getClass().getMethod("get", int.class);
                int n = (Integer) size.invoke(a);
                List<Integer> keep = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int v = (Integer) get.invoke(a, i);
                    if (!hs.contains((long) v)) keep.add(v);
                }
                if (keep.size() == n) return a;
                Object res = a.getClass().getConstructor().newInstance();
                Method add = a.getClass().getMethod("add", int.class);
                for (int v : keep) add.invoke(res, v);
                return res;
            } catch (Throwable ignored) {
            }
        }
        return a;
    }

    private static boolean isHiddenItem(Object o, Set<String> pkgs, Set<Long> hs) {
        if (o == null) return false;
        Object workspaceItem = workspaceRecordOf(o);
        if (workspaceItem != null) o = workspaceItem;
        String pkg = packageOf(o);
        if (pkg == null) return false;                 // 不像 ItemInfo/AppInfo，不动
        if (pkgs.contains(pkg)
                && !pkg.equals("com.flyme.shadow")) return true;
        Long container = longFieldAny(o, "container", "mContainer");
        Long screen = longFieldAny(o, "screenId", "mScreenId", "screen", "mScreen");
        return container != null && container == CONTAINER_DESKTOP
                && screen != null && hs.contains(screen);
    }

    private static String packageOf(Object o) {
        if (o == null) return null;
        if (o instanceof ApplicationInfo) return ((ApplicationInfo) o).packageName;
        if (o instanceof PackageInfo) return ((PackageInfo) o).packageName;
        try {
            Method targetPackage = findMethod(o.getClass(), "getTargetPackage");
            if (targetPackage != null) {
                Object value = targetPackage.invoke(o);
                if (value instanceof String && !((String) value).isEmpty())
                    return (String) value;
            }
            Object component = null;
            Method targetComponent = findMethod(o.getClass(), "getTargetComponent");
            if (targetComponent != null) component = targetComponent.invoke(o);
            if (component == null) {
                Field field = findField(o.getClass(), "componentName");
                if (field != null) component = field.get(o);
            }
            if (component instanceof android.content.ComponentName)
                return ((android.content.ComponentName) component).getPackageName();

            Object launchIntent = null;
            Method getIntent = findMethod(o.getClass(), "getIntent");
            if (getIntent != null) launchIntent = getIntent.invoke(o);
            if (!(launchIntent instanceof Intent)) {
                for (String fieldName : new String[]{"intent", "mIntent", "promiseIntent"}) {
                    Field field = findField(o.getClass(), fieldName);
                    if (field == null) continue;
                    Object value = field.get(o);
                    if (value instanceof Intent) { launchIntent = value; break; }
                }
            }
            if (launchIntent instanceof Intent) {
                Intent intent = (Intent) launchIntent;
                android.content.ComponentName cn = intent.getComponent();
                if (cn != null) return cn.getPackageName();
                String packageName = intent.getPackage();
                if (packageName != null && !packageName.isEmpty()) return packageName;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Long longFieldAny(Object o, String... names) {
        for (String name : names) {
            Long value = longField(o, name);
            if (value != null) return value;
        }
        return null;
    }

    private static Integer intField(Object o, String name) {
        Long value = longField(o, name);
        return value == null ? null : value.intValue();
    }

    /** Launcher3/Flyme 的 ItemInfo.container 和 screenId 通常是 long，不是 int。 */
    private static Long longField(Object o, String name) {
        try {
            Field f = findField(o.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object value = f.get(o);
            return value instanceof Number ? ((Number) value).longValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... parameterTypes) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, parameterTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }


    /** 每个目标进程独立写入 RemotePreferences，避免桌面/设置/管家并发覆盖日志。 */
    private static final java.util.Map<String, Long> recentRecords = new java.util.HashMap<>();

    /** 相同内容 3 秒内只回传一次：getCount/getItemCount 等热路径会反复产生同样的日志，造成广播风暴。 */
    private static boolean throttled(String message) {
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (recentRecords) {
            Long last = recentRecords.get(message);
            if (last != null && now - last < 3000) return true;
            if (recentRecords.size() > 256) recentRecords.clear();
            recentRecords.put(message, now);
            return false;
        }
    }

    private static void record(String message) {
        if (message == null || throttled(message)) return;
        Log.i(TAG, message);
        try {
            SharedPreferences source = diagnosticPrefs;
            if (source == null) return;
            String token = source.getString(Config.K_TOKEN, null);
            if (token == null || token.isEmpty()) return;
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (!(app instanceof Context)) return;
            Intent report = new Intent(Config.ACTION_DIAGNOSTIC_LOG)
                    .setPackage("com.flyme.shadow")
                    .putExtra(Config.EXTRA_TOKEN, token)
                    .putExtra(Config.EXTRA_LOG_KEY, diagnosticKey)
                    .putExtra(Config.EXTRA_LOG_MESSAGE, message);
            ((Context) app).sendBroadcast(report);
        } catch (Throwable t) {
            Log.w(TAG, "诊断日志回传失败: " + t);
        }
    }

    private static String describeArgs(Object[] args) {
        StringBuilder b = new StringBuilder();
        for (Object a : args) {
            if (b.length() > 0) b.append(", ");
            if (a == null) b.append("null");
            else if (a instanceof List) b.append(a.getClass().getSimpleName())
                    .append('[').append(((List<?>) a).size()).append(']');
            else if (a.getClass().isArray()) b.append(a.getClass().getComponentType().getSimpleName())
                    .append("[]");
            else b.append(a.getClass().getName());
        }
        return b.toString();
    }

    private static Object[] toArray(Object raw) {
        if (raw instanceof Object[]) return ((Object[]) raw).clone();
        if (raw instanceof List) return ((List<?>) raw).toArray();
        return new Object[0];
    }

    /** 从 bindScreens 的参数里取页序（int[] 或 IntArray） */
    private static long[] longsOf(Object[] args) {
        for (Object a : args) {
            if (a instanceof int[]) {
                int[] in = (int[]) a;
                long[] out = new long[in.length];
                for (int i = 0; i < in.length; i++) out[i] = in[i];
                return out;
            }
            if (a instanceof long[]) return ((long[]) a).clone();
            if (a != null && a.getClass().getName().endsWith(".IntArray")) {
                try {
                    int n = (Integer) a.getClass().getMethod("size").invoke(a);
                    Method get = a.getClass().getMethod("get", int.class);
                    long[] r = new long[n];
                    for (int i = 0; i < n; i++) r[i] = ((Number) get.invoke(a, i)).longValue();
                    return r;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }
}
