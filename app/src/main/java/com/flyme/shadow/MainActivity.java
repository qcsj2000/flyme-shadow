package com.flyme.shadow;

import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashSet;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final int BLUE = 0xFF16856D;
    private static final int TEXT = 0xFF202A33;
    private static final int MUTED = 0xFF77838D;
    private static final int BG = 0xFFF6F7F8;
    private static final int CARD = 0xFFFFFFFF;
    private TextView status, appCount, freezeStatus;
    private View statusDot;
    private Switch enabled;
    private EditText pages;
    private Switch enabledSwitch, autoLockSwitch, discoverSwitch;
    private boolean syncing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AuthHelper.require(this, this::buildUi, this::finish);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        syncSettingsFromRemote();
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private android.graphics.drawable.Drawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x1016856D),
                shape(color, radius, 0), null);
    }

    private void pressFeedback(View view) {
        // No extra alpha/scale animation: use only the restrained native ripple.
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout card(LinearLayout parent, int top) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(2), dp(5), dp(2), dp(5));
        c.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        parent.addView(c, lp(top));
        return c;
    }

    private void heading(LinearLayout parent, String title, String desc) {
        parent.addView(text(title, 14, TEXT, true));
        TextView d = text(desc, 12, MUTED, false);
        d.setPadding(0, dp(2), 0, dp(4));
        parent.addView(d);
    }

    private Button button(String label, LinearLayout parent, boolean primary, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFFFFFFFF : TEXT);
        b.setBackground(ripple(primary ? BLUE : 0xFFF0F3F8, 13));
        pressFeedback(b);
        b.setPadding(dp(12), dp(5), dp(12), dp(5));
        b.setMinHeight(dp(40));
        parent.addView(b, lp(5));
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF2F4F6);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView eyebrow = text("PRIVACY CONTROL", 10, 0xFF7B8792, true);
        brand.addView(eyebrow);
        TextView title = text("Flyme Shadow", 27, 0xFF18232D, true);
        LinearLayout.LayoutParams titleLp = lp(2);
        brand.addView(title, titleLp);
        TextView refresh = text("↻", 25, 0xFF354452, false);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(ripple(0xFFFFFFFF, 14));
        top.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));
        refresh.setOnClickListener(v -> {
            refreshStatus();
            syncSettingsFromRemote();
            Toast.makeText(this, "状态已更新", Toast.LENGTH_SHORT).show();
        });

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(2), dp(10), dp(2), dp(10));
        LinearLayout.LayoutParams statusRowLp = lp(12);
        statusRowLp.bottomMargin = dp(2);
        root.addView(statusRow, statusRowLp);

        statusDot = new View(this);
        statusDot.setBackground(shape(0xFF9AA4AC, 50, 0));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(9);
        statusRow.addView(statusDot, dotLp);

        status = text("正在检查 LSPosed 连接…", 12, 0xFF697680, false);
        statusRow.addView(status);


        sectionLabel(root, "桌面保护", "控制桌面隐藏规则与自动识别");
        LinearLayout desktop = menuGroup(root, 8);
        enabledSwitch = switchRow(desktop, "启用 Flyme Shadow", Config.K_ENABLED, false);
        divider(desktop);
        autoLockSwitch = switchRow(desktop, "息屏后自动重新隐藏", Config.K_AUTO_LOCK, true);
        divider(desktop);
        Switch discover = new Switch(this);
        discover.setText("隐藏页自动识别");
        discover.setTextSize(14);
        discover.setTextColor(TEXT);
        discover.setPadding(dp(18), dp(3), dp(16), dp(5));
        discover.setChecked(bool(Config.K_AUTO_DISCOVER,
                getSharedPreferences(Config.LOCAL_PREFS, MODE_PRIVATE)
                        .getBoolean(Config.K_LOCAL_AUTO_DISCOVER, true)));
        desktop.addView(discover, new LinearLayout.LayoutParams(-1, dp(48)));
        discoverSwitch = discover;
        discover.setOnCheckedChangeListener((v, on) -> { if (!syncing) setDiscoverEnabled(discover, on); });
        pages = new EditText(this);
        pages.setSingleLine(true);
        pages.setTextSize(14);
        pages.setTextColor(TEXT);
        pages.setHintTextColor(0xFF98A2AA);
        pages.setHint("隐藏页码，例如：2,3");
        pages.setPadding(dp(12), dp(8), dp(12), dp(8));
        pages.setBackground(shape(0xFFF1F3F5, 9, 0));
        pages.setText(join(strSet(Config.K_PAGES)));
        LinearLayout.LayoutParams pageLp = new LinearLayout.LayoutParams(-1, dp(42));
        pageLp.setMargins(dp(18), dp(5), dp(18), dp(4));
        desktop.addView(pages, pageLp);
        TextView savePages = text("保存页面设置  →", 12, BLUE, true);
        savePages.setGravity(Gravity.CENTER_VERTICAL);
        savePages.setPadding(dp(18), dp(5), dp(18), dp(12));
        desktop.addView(savePages);
        savePages.setOnClickListener(v -> {
            Set<String> values = new HashSet<>();
            for (String p : pages.getText().toString().split("[,，\\s]+")) {
                if (p.matches("\\d{1,2}") && Integer.parseInt(p) > 0)
                    values.add(String.valueOf(Integer.parseInt(p)));
            }
            put(e -> e.putStringSet(Config.K_PAGES, values));
            pages.setText(join(values));
            Toast.makeText(this, "页面设置已保存", Toast.LENGTH_SHORT).show();
        });

        sectionLabel(root, "桌面控制", "临时显示被隐藏的内容，或立即重新隐藏");
        LinearLayout control = menuGroup(root, 8);
        settingRow(control, "grid", "显示隐藏内容", 0xFF526C83,
                "解锁桌面：恢复被隐藏的页面和应用", () -> send(Config.ACTION_UNLOCK));
        divider(control);
        settingRow(control, "shield", "重新隐藏", 0xFF526C83,
                "立即按当前设置重新隐藏", () -> send(Config.ACTION_LOCK));

        sectionLabel(root, "应用管理", "分别管理隐藏名单与冻结状态");
        LinearLayout appGroup = menuGroup(root, 8);
        settingRow(appGroup, "folder", "隐藏应用", 0xFF526C83,
                "选择需要从桌面与应用列表隐藏的应用", () ->
                        startActivity(new android.content.Intent(this, AppPickerActivity.class)));
        appCount = text("", 11, MUTED, false);
        appCount.setPadding(dp(70), 0, dp(16), dp(12));
        appGroup.addView(appCount);
        divider(appGroup);
        settingRow(appGroup, "pause", "停用应用", 0xFF526C83,
                "冻结或恢复应用运行", () ->
                        startActivity(new android.content.Intent(this, AppPickerActivity.class)));

        sectionLabel(root, "工具与授权", "诊断运行状态及管理必要权限");
        LinearLayout tools = menuGroup(root, 8);
        settingRow(tools, "settings", "运行诊断", 0xFF526C83,
                "Hook 状态、运行日志与授权", this::showDiagnosticLogs);
        freezeStatus = text(FreezeManager.statusCached(), 11, MUTED, false);
        freezeStatus.setPadding(dp(70), 0, dp(16), dp(12));
        tools.addView(freezeStatus);
        divider(tools);
        settingRow(tools, "shield", "Shizuku 授权", 0xFF526C83,
                "应用冻结所需授权", () -> {
                    if (FreezeManager.requestShizuku())
                        Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                    else Toast.makeText(this, FreezeManager.statusCached(), Toast.LENGTH_LONG).show();
                    FreezeManager.statusAsync(st -> { if (freezeStatus != null) freezeStatus.setText(st); });
                });
        divider(tools);
        settingRow(tools, "key", "验证密码", 0xFF526C83,
                "修改模块访问密码", () ->
                        AuthHelper.setupPassword(this, () -> {}, () -> {}));

        TextView footer = text("FLYME SHADOW  ·  PRIVACY TOOLS", 9, 0xFF98A2AA, true);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, lp(24));
        setContentView(scroll);
        refreshStatus();
    }

    private void sectionLabel(LinearLayout root, String title, String desc) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams p = lp(22);
        p.leftMargin = dp(2);
        root.addView(wrap, p);
        wrap.addView(text(title, 15, 0xFF25323D, true));
        TextView subtitle = text(desc, 11, 0xFF87929B, false);
        LinearLayout.LayoutParams sub = lp(3);
        wrap.addView(subtitle, sub);
    }

    private LinearLayout menuGroup(LinearLayout root, int top) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(4), 0, dp(4));
        group.setBackground(shape(0xFFFFFFFF, 14, 0xFFE7EBEE));
        group.setClipToOutline(true);
        root.addView(group, lp(top));
        return group;
    }

    private Switch switchRow(LinearLayout parent, String label, String key, boolean defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(2), dp(16), dp(2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(49)));
        TextView name = text(label, 14, 0xFF26323B, false);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setChecked(bool(key, defaultValue));
        row.addView(control);
        control.setOnCheckedChangeListener((v, on) -> { if (!syncing) put(e -> e.putBoolean(key, on)); });
        return control;
    }

    private void settingRow(LinearLayout parent, String iconName, String label,
                            int tint, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(7), dp(16), dp(7));
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(70)));
        IconView icon = new IconView(this, iconName, tint);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(16), 0, 0, 0);
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        names.addView(text(label, 15, 0xFF26323B, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 10, 0xFF8A949C, false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.topMargin = dp(3);
            names.addView(sub, p);
        }
        TextView arrow = text("›", 24, 0xFFADB6BD, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), -2));
        if (action != null) {
            row.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(0x12000000),
                    null,
                    shape(0xFFFFFFFF, 10, 0)));
            row.setOnClickListener(v -> action.run());
        }
    }

    private static final class IconView extends View {
        private final String kind;
        private final android.graphics.Paint paint = new android.graphics.Paint(3);
        private final int color;
        IconView(android.content.Context context, String kind, int color) {
            super(context);
            this.kind = kind;
            this.color = color;
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float sx = getWidth() / 32f, sy = getHeight() / 32f;
            canvas.save();
            canvas.scale(sx, sy);
            paint.setColor(color);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            android.graphics.Path p = new android.graphics.Path();
            if ("grid".equals(kind)) {
                canvas.drawRoundRect(5, 5, 27, 27, 3, 3, paint);
                canvas.drawLine(5, 12, 27, 12, paint);
                canvas.drawLine(12, 12, 12, 27, paint);
            } else if ("folder".equals(kind)) {
                p.moveTo(4, 9); p.lineTo(13, 9); p.lineTo(16, 12);
                p.lineTo(28, 12); p.lineTo(28, 25); p.quadTo(28, 27, 26, 27);
                p.lineTo(6, 27); p.quadTo(4, 27, 4, 25); p.close();
                canvas.drawPath(p, paint);
                canvas.drawLine(4, 12, 28, 12, paint);
            } else if ("pause".equals(kind)) {
                canvas.drawRoundRect(5, 5, 27, 27, 5, 5, paint);
                canvas.drawRoundRect(11, 10, 14, 22, 1, 1, paint);
                canvas.drawRoundRect(18, 10, 21, 22, 1, 1, paint);
            } else if ("settings".equals(kind)) {
                canvas.drawCircle(16, 16, 9, paint);
                canvas.drawCircle(16, 16, 3, paint);
                for (int i=0;i<8;i++) {
                    double a = Math.PI * i / 4;
                    float x1 = 16+(float)Math.cos(a)*11, y1=16+(float)Math.sin(a)*11;
                    float x2 = 16+(float)Math.cos(a)*14, y2=16+(float)Math.sin(a)*14;
                    canvas.drawLine(x1,y1,x2,y2,paint);
                }
            } else if ("shield".equals(kind)) {
                p.moveTo(16, 3); p.lineTo(27, 7); p.lineTo(26, 18);
                p.quadTo(24, 25, 16, 29); p.quadTo(8, 25, 6, 18);
                p.lineTo(5, 7); p.close(); canvas.drawPath(p, paint);
                canvas.drawLine(16, 9, 16, 19, paint);
                canvas.drawCircle(16, 23, 1, paint);
            } else {
                canvas.drawRoundRect(5, 8, 27, 25, 3, 3, paint);
                canvas.drawLine(11, 8, 11, 5, paint);
                canvas.drawLine(21, 8, 21, 5, paint);
            }
            canvas.restore();
        }
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(0xFFF1F1F1);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.setMargins(dp(62), dp(2), dp(14), dp(2));
        parent.addView(line, p);
    }


    private String readDiagnosticLogs() {
        SharedPreferences p = App.remote;
        StringBuilder out = new StringBuilder();
        appendPendingLogsSection(out);
        if (p != null) {
            appendLogSection(out, p, Config.K_LOGS_APP, "模块应用");
            appendLogSection(out, p, Config.K_LOGS_LAUNCHER, "桌面进程");
            appendLogSection(out, p, Config.K_LOGS_SETTINGS, "系统设置进程");
            appendLogSection(out, p, Config.K_LOGS_SAFE, "手机管家进程");
            appendLogSection(out, p, Config.K_LOGS_OTHER, "其他进程");
            appendLogSection(out, p, Config.K_LOGS, "旧版日志");
        }
        String states = p == null
                ? "模块服务尚未连接\n连接状态: " + App.connectionStatus
                : "进程加载状态（按最近一次 Hook 事件判断）\n"
                    + hookState(p, Config.K_LOGS_LAUNCHER, "桌面进程") + "\n"
                    + hookState(p, Config.K_LOGS_SETTINGS, "系统设置进程") + "\n"
                    + hookState(p, Config.K_LOGS_SAFE, "手机管家进程");
        return states + (out.length() == 0 ? "\n\n日志暂时为空。请确认 LSPosed 已启用本模块，并将桌面、系统设置加入作用域。"
                : "\n\n" + out.toString());
    }

    private void appendPendingLogsSection(StringBuilder out) {
        SharedPreferences local = getSharedPreferences(Config.LOCAL_PREFS, MODE_PRIVATE);
        String pending = local.getString(Config.K_PENDING_LOGS, "");
        if (pending == null || pending.isEmpty()) return;
        if (out.length() > 0) out.append("\n\n");
        out.append("========== 本地暂存日志（等待远程写入） ==========\n");
        for (String line : pending.split("\\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String key = line.substring(0, tab);
            String label = Config.K_LOGS_LAUNCHER.equals(key) ? "桌面"
                    : Config.K_LOGS_SETTINGS.equals(key) ? "系统设置"
                    : Config.K_LOGS_SAFE.equals(key) ? "手机管家" : "其他进程";
            out.append("[").append(label).append("] ")
                    .append(line.substring(tab + 1)).append('\n');
        }
    }

    private String hookState(SharedPreferences p, String key, String label) {
        long at = 0;
        try { at = p.getLong(key + "_last_at", 0L); } catch (Throwable ignored) {}
        return label + ": " + (at > 0 ? "已收到 Hook 事件 · " + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date(at)) : "尚未收到 Hook 事件");
    }

    private void appendLogSection(StringBuilder out, SharedPreferences p, String key, String title) {
        String value;
        try { value = p.getString(key, ""); }
        catch (Throwable t) { value = "[读取失败] " + t; }
        if (value == null || value.isEmpty()) return;
        if (out.length() > 0) out.append("\n\n");
        out.append("========== ").append(title).append(" ==========\n").append(value);
    }

    private void clearDiagnosticLogs() {
        SharedPreferences p = App.remote;
        if (p != null) p.edit()
                .putString(Config.K_LOGS_APP, "")
                .putString(Config.K_LOGS_LAUNCHER, "")
                .putString(Config.K_LOGS_SETTINGS, "")
                .putString(Config.K_LOGS_SAFE, "")
                .putString(Config.K_LOGS_OTHER, "")
                .putString(Config.K_LOGS, "")
                .apply();
        getSharedPreferences(Config.LOCAL_PREFS, MODE_PRIVATE).edit()
                .putString(Config.K_PENDING_LOGS, "")
                .apply();
    }

    private void showDiagnosticLogs() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(12));
        panel.setBackground(shape(CARD, 14, 0xFFE7EBEE));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("运行诊断", 18, TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("×", 27, MUTED, false);
        close.setGravity(Gravity.CENTER);
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        panel.addView(titleRow);

        TextView subtitle = text("Hook 状态与运行日志", 12, MUTED, false);
        LinearLayout.LayoutParams subLp = lp(2);
        panel.addView(subtitle, subLp);

        TextView content = text(readDiagnosticLogs(), 11, TEXT, false);
        content.setTextIsSelectable(true);
        content.setTypeface(Typeface.MONOSPACE);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));
        content.setTextIsSelectable(true);
        ScrollView logsScroll = new ScrollView(this);
        logsScroll.setFillViewport(true);
        logsScroll.setBackground(shape(0xFFF1F3F5, 9, 0xFFE4E9EC));
        logsScroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, 0, 1);
        logLp.topMargin = dp(14);
        logLp.bottomMargin = dp(12);
        panel.addView(logsScroll, logLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(actions, new LinearLayout.LayoutParams(-1, dp(42)));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();

        TextView refresh = diagnosticAction("刷新", false);
        TextView copy = diagnosticAction("复制", false);
        TextView clear = diagnosticAction("清空", false);
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -1, 1);
        copyLp.leftMargin = dp(8);
        actions.addView(copy, copyLp);
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, -1, 1);
        clearLp.leftMargin = dp(8);
        actions.addView(clear, clearLp);

        close.setOnClickListener(v -> dialog.dismiss());
        refresh.setOnClickListener(v -> {
            content.setText(readDiagnosticLogs());
            logsScroll.scrollTo(0, 0);
        });
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Flyme Shadow logs", content.getText()));
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
            }
        });
        clear.setOnClickListener(v -> {
            clearDiagnosticLogs();
            content.setText("日志已清空。新的运行事件会继续记录。");
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(-1, dp(560));
        }
    }

    private TextView diagnosticAction(String label, boolean primary) {
        TextView action = text(label, 13, primary ? CARD : TEXT, true);
        action.setGravity(Gravity.CENTER);
        action.setBackground(ripple(primary ? BLUE : 0xFFF2F4F5, 10));
        return action;
    }

    private void setDiscoverEnabled(Switch control, boolean on) {
        SharedPreferences remote = App.remote;
        if (remote == null) {
            control.setOnCheckedChangeListener(null);
            control.setChecked(!on);
            control.setOnCheckedChangeListener((v, checked) -> setDiscoverEnabled(control, checked));
            Toast.makeText(this, "未连接到框架，设置未保存", Toast.LENGTH_LONG).show();
            return;
        }
        SharedPreferences local = getSharedPreferences(Config.LOCAL_PREFS, MODE_PRIVATE);
        if (!on) {
            boolean saved = remote.edit()
                    .putBoolean(Config.K_AUTO_DISCOVER, false)
                    .putStringSet(Config.K_PKGS, new HashSet<>())
                    .putStringSet(Config.K_DISCOVERED_PKGS, new HashSet<>())
                    .commit();
            if (!saved) {
                control.setOnCheckedChangeListener(null);
                control.setChecked(true);
                control.setOnCheckedChangeListener((v, checked) -> setDiscoverEnabled(control, checked));
                Toast.makeText(this, "清空失败，请检查 LSPosed 连接", Toast.LENGTH_LONG).show();
                return;
            }
            local.edit()
                    .putBoolean(Config.K_LOCAL_AUTO_DISCOVER, false)
                    .putStringSet(Config.K_LOCAL_DISCOVERED_PKGS, new HashSet<>())
                    .commit();
            Toast.makeText(this, "已关闭识别并清空全部隐藏应用", Toast.LENGTH_SHORT).show();
        } else {
            remote.edit().putBoolean(Config.K_AUTO_DISCOVER, true).commit();
            local.edit().putBoolean(Config.K_LOCAL_AUTO_DISCOVER, true).commit();
            Toast.makeText(this, "隐藏页自动识别已开启", Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    /** UI 可能在 LSPosed 服务连接之前构建，此时开关只是默认值；连接后需要按真实配置重新同步。 */
    private void syncSettingsFromRemote() {
        if (App.remote == null || enabledSwitch == null) return;
        syncing = true;
        try {
            enabledSwitch.setChecked(bool(Config.K_ENABLED, false));
            autoLockSwitch.setChecked(bool(Config.K_AUTO_LOCK, true));
            discoverSwitch.setChecked(bool(Config.K_AUTO_DISCOVER,
                    getSharedPreferences(Config.LOCAL_PREFS, MODE_PRIVATE)
                            .getBoolean(Config.K_LOCAL_AUTO_DISCOVER, true)));
            if (pages != null && !pages.hasFocus()) pages.setText(join(strSet(Config.K_PAGES)));
        } finally {
            syncing = false;
        }
    }

    private final Runnable onServiceConnected = () -> {
        refreshStatus();
        syncSettingsFromRemote();
    };

    @Override protected void onStart() {
        super.onStart();
        App.connectionListener = onServiceConnected;
    }

    @Override protected void onStop() {
        super.onStop();
        if (App.connectionListener == onServiceConnected) App.connectionListener = null;
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean connected = App.remote != null;
        status.setText(connected
                ? "已连接 LSPosed"
                : "未连接 LSPosed · 请启用模块并将系统桌面加入作用域");
        status.setTextColor(connected ? 0xFF526C83 : 0xFF8A6A4D);
        if (statusDot != null)
            statusDot.setBackground(shape(connected ? 0xFF299B78 : 0xFFD19A55, 50, 0));
        if (appCount != null)
            appCount.setText("手动隐藏 " + strSet(Config.K_PKGS).size()
                    + " 个 · 自动识别隐藏页应用 " + strSet(Config.K_DISCOVERED_PKGS).size() + " 个");
        if (freezeStatus != null) {
            freezeStatus.setText(FreezeManager.statusCached());
            FreezeManager.statusAsync(st -> { if (freezeStatus != null) freezeStatus.setText(st); });
        }
    }

    private void send(String action) {
        if (!App.sendToLauncher(this, action))
            Toast.makeText(this, "未连接到框架，请检查 LSPosed 设置", Toast.LENGTH_LONG).show();
    }

    private boolean bool(String k, boolean def) {
        SharedPreferences p = App.remote;
        return p == null ? def : p.getBoolean(k, def);
    }

    static Set<String> strSet(String k) {
        SharedPreferences p = App.remote;
        Set<String> s = p == null ? null : p.getStringSet(k, null);
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    private static String join(Set<String> s) {
        return String.join(",", new java.util.TreeSet<>(s));
    }

    interface Edit { void apply(SharedPreferences.Editor e); }

    private void put(Edit edit) {
        SharedPreferences p = App.remote;
        if (p == null) {
            Toast.makeText(this, "未连接到框架，设置未保存", Toast.LENGTH_LONG).show();
            return;
        }
        SharedPreferences.Editor e = p.edit();
        edit.apply(e);
        e.apply();
        refreshStatus();
    }
}
