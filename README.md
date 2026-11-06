# Flyme Shadow（LSPosed / libxposed API 102）

目标：魅族 21 · Flyme 12.6 · 系统桌面（`com.meizu.flyme.launcher`）。
用密码或指纹验证后，隐藏/显示指定的桌面页面和应用，**恢复时布局原样保留**。

## 原理
- 不改桌面数据库（favorites 表原样不动）。
- Hook 桌面的 `bindScreens / bindItems / bindAllApplications …`，在“绑定到界面”这一步把被隐藏的页面和应用过滤掉。
- 解锁：模块 App 验证身份后发带令牌的广播 → 桌面 `recreate()` 重新绑定 → 完整布局回来。
- 默认状态就是“隐藏”，桌面进程重启 / 息屏（可选）自动重新隐藏。
- 设置通过 LSPosed RemotePreferences 同步；广播令牌只有模块和桌面内的 Hook 读得到，别的 App 无法伪造解锁。

## 使用（v1.1.3 起：主界面新增“显示隐藏内容 / 重新隐藏”入口）
1. Android Studio 打开本目录，Sync 后构建 `app` → 安装 APK。
2. LSPosed 中启用模块，作用域勾选“系统桌面”，强停桌面（或重启）。
3. 打开“Flyme Shadow”：首次设置密码 → 打开总开关 → 填页码、选应用。
4. 桌面上会有两个图标：**桌面隐藏**（设置）和**解锁桌面**（验证后一键显示/重新隐藏）。
   本应用自身不会被隐藏。

## ⚠️ 需要你在真机上核对的地方（我没有 Flyme 桌面的反编译产物，未实机验证）
- `HookEntry.CANDIDATE_CLASSES`：按 Launcher3 常见类名预置。看 LSPosed 日志里 `DesktopGuard` 标签，
  若提示“没有命中任何桌面绑定方法”，需反编译 Flyme 桌面（jadx），找出实现 `bindItems`/`bindScreens` 的类名替换进去。
- 页面隐藏用的是显示顺序页码（1 起，含空白页）；文件夹内的应用、全局搜索、最近任务里的应用目前**不过滤**。
- `io.github.libxposed:service` 的版本号若解析失败，请到 Maven Central 换成最新版。
- Flyme 桌面如果不走 Launcher3 的 bind 回调，需要改成对应的绑定入口。
