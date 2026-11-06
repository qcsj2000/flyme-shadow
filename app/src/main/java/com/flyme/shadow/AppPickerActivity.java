package com.flyme.shadow;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Modern app selector: launcher filtering and package freezing remain independent actions. */
public class AppPickerActivity extends AppCompatActivity {
    private static final int BLUE = 0xFF16856D;
    private static final int TEXT = 0xFF202A33;
    private static final int MUTED = 0xFF77838D;
    private static final int BG = 0xFFF2F4F6;
    private static final int CARD = 0xFFFFFFFF;

    private static final class Item {
        String pkg, label;
        boolean system, discovered;
    }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Map<String, Boolean> frozenCache = new HashMap<>();
    private String filter = "全部";
    private String keyword = "";
    private volatile boolean loading = true;
    private BaseAdapter adapter;
    private TextView countView;
    private TextView emptyView;
    private LinearLayout chips;

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

    /** Native ripple plus a subtle scale response; returning false preserves click handling. */
    private void pressFeedback(View view) {
        // No extra alpha/scale animation: use only the restrained native ripple.
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams rowParams(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        loadSelectedIfNeeded();
        buildUi();
        countView.setText("正在读取应用列表…");
        new Thread(() -> {
            loadApps();
            runOnUiThread(() -> {
                loading = false;
                applyFilter();
            });
        }, "flymeshadow-app-loader").start();
    }

    /**
     * 服务未连接时读到的是空名单；若此时就允许保存，会用残缺名单覆盖真实配置。
     * 因此未成功读取前 save() 一律拒绝，连接后在 onResume 里补读。
     */
    private boolean selectedLoaded;

    private void loadSelectedIfNeeded() {
        if (selectedLoaded || App.remote == null) return;
        selected.addAll(MainActivity.strSet(Config.K_PKGS));
        selectedLoaded = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (!selectedLoaded && App.remote != null) {
            loadSelectedIfNeeded();
            if (adapter != null) applyFilter();
        }
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> discovered = MainActivity.strSet(Config.K_DISCOVERED_PKGS);
        Map<String, Item> map = new HashMap<>();
        // Include installed apps even when they do not expose a launcher icon.
        // QUERY_ALL_PACKAGES in the manifest makes this selector complete on Android 11+.
        for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            String pkg = info.packageName;
            if (pkg == null || pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
            Item item = new Item();
            item.pkg = pkg;
            try {
                CharSequence label = pm.getApplicationLabel(info);
                item.label = label == null ? pkg : label.toString();
            } catch (Exception ignored) {
                item.label = pkg;
            }
            item.discovered = discovered.contains(pkg);
            item.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            map.put(pkg, item);
        }
        List<Item> sorted = new ArrayList<>(map.values());
        sorted.sort((a, b) -> {
            int c = a.label.compareToIgnoreCase(b.label);
            return c != 0 ? c : a.pkg.compareTo(b.pkg);
        });
        // 在主线程一次性替换，避免加载期间搜索框触发 applyFilter 并发遍历 all 而崩溃。
        runOnUiThread(() -> { all.clear(); all.addAll(sorted); });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(12), dp(20), dp(4));
        header.setBackgroundColor(BG);
        root.addView(header);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 34, TEXT, false);
        back.setGravity(Gravity.CENTER);
        top.addView(back, rowParams(dp(38), dp(42)));
        back.setOnClickListener(v -> finish());
        TextView title = label("应用管理", 22, 0xFF202A33, true);
        LinearLayout.LayoutParams titleLp = rowParams(0, -2);
        titleLp.weight = 1;
        top.addView(title, titleLp);
        header.addView(top);

        TextView desc = label("管理隐藏与冻结的应用", 12, MUTED, false);
        LinearLayout.LayoutParams descLp = rowParams(-1, -2);
        descLp.topMargin = dp(4);
        header.addView(desc, descLp);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("搜索应用名称或包名");
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(shape(CARD, 10, 0));
        search.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams searchLp = rowParams(-1, dp(44));
        searchLp.topMargin = dp(8);
        header.addView(search, searchLp);

        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chipLp = rowParams(-1, dp(38));
        chipLp.topMargin = dp(6);
        header.addView(chips, chipLp);
        buildChips();

        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(20), dp(7), dp(20), dp(8));
        countView = label("", 13, MUTED, true);
        summary.addView(countView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView selectedLabel = label("自动保存", 12, BLUE, false);
        summary.addView(selectedLabel);
        root.addView(summary);

        // Keep the empty state inside the list viewport. A sibling placed after a
        // weighted ListView is pushed to the bottom and leaves a large blank region.
        android.widget.FrameLayout listContainer = new android.widget.FrameLayout(this);
        root.addView(listContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xFFE9EDF0));
        list.setDividerHeight(dp(1));
        list.setSelector(android.R.color.transparent);
        list.setCacheColorHint(android.graphics.Color.TRANSPARENT);
        list.setPadding(0, 0, 0, 0);
        list.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        list.setClipToOutline(false);
        list.setClipToPadding(false);
        listContainer.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));

        emptyView = label("没有找到应用", 14, MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams emptyLp =
                new android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        listContainer.addView(emptyView, emptyLp);
        list.setEmptyView(emptyView);

        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int p) { return shown.get(p); }
            @Override public long getItemId(int p) { return shown.get(p).pkg.hashCode(); }
            @Override public View getView(int position, View convert, ViewGroup parent) {
                return makeItem(shown.get(position));
            }
        };
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                keyword = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
            @Override public void afterTextChanged(Editable e) { }
        });

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(20), dp(6), dp(20), dp(6));
        footer.setBackgroundColor(BG);
        View footerLine = new View(this);
        footerLine.setBackgroundColor(0xFFE4E9EC);
        root.addView(footerLine, new LinearLayout.LayoutParams(-1, dp(1)));
        footer.setBackgroundColor(BG);
        TextView footerCount = label("已选择", 13, MUTED, false);
        footer.addView(footerCount);
        TextView done = label("完成", 14, 0xFFFFFFFF, true);
        done.setGravity(Gravity.CENTER);
        done.setTextColor(BLUE);
        done.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1216856D),
                shape(0x00FFFFFF, 8, 0), null));
        pressFeedback(done);
        LinearLayout.LayoutParams doneLp = rowParams(dp(72), dp(40));
        doneLp.leftMargin = dp(14);
        footer.addView(done, doneLp);
        done.setOnClickListener(v -> {
            if (!save()) return;
            Toast.makeText(this, "隐藏应用列表已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(footer);
        setContentView(root);
        updateCount();
    }

    private void buildChips() {
        chips.removeAllViews();
        String[] filters = {"全部", "已选择", "隐藏页识别", "用户应用", "系统应用"};
        for (String name : filters) {
            TextView chip = label(name, 13, name.equals(filter) ? BLUE : MUTED, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), 0, dp(12), 0);
            chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1216856D),
                    shape(0x00FFFFFF, 0, 0), null));
            pressFeedback(chip);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1f);
            chips.addView(chip, p);
            chip.setOnClickListener(v -> {
                filter = name;
                buildChips();
                applyFilter();
            });
        }
    }

    private View makeItem(Item item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(3), dp(20), dp(3));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x12000000), null,
                shape(0xFFFFFFFF, 8, 0)));
        pressFeedback(card);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, dp(60));
        card.setLayoutParams(cardLp);

        ImageView icon = new ImageView(this);
        Drawable cachedIcon = iconCache.get(item.pkg);
        if (cachedIcon == null) {
            try { cachedIcon = getPackageManager().getApplicationIcon(item.pkg); }
            catch (Exception ignored) { cachedIcon = getDrawable(android.R.drawable.sym_def_app_icon); }
            if (cachedIcon != null) iconCache.put(item.pkg, cachedIcon);
        }
        icon.setImageDrawable(cachedIcon);
        card.addView(icon, rowParams(dp(36), dp(36)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(4), 0);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1);
        card.addView(texts, textLp);
        texts.addView(label(item.label, 15, 0xFF222222, false));
        TextView pkg = label(item.pkg, 10, MUTED, false);
        pkg.setMaxLines(1);
        pkg.setSingleLine(true);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams pkgLp = rowParams(-1, -2);
        pkgLp.topMargin = dp(1);
        texts.addView(pkg, pkgLp);

        CheckBox check = new CheckBox(this);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(
                selected.contains(item.pkg) ? BLUE : 0xFF98A2B3));
        check.setChecked(selected.contains(item.pkg));
        card.addView(check, rowParams(dp(42), dp(48)));
        check.setOnCheckedChangeListener((button, checked) -> {
            if (checked) selected.add(item.pkg); else selected.remove(item.pkg);
            save();
            updateCount();
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(checked ? BLUE : 0xFF98A2B3));
        });
        card.setOnClickListener(v -> check.setChecked(!check.isChecked()));

        Button freeze = new Button(this);
        Boolean frozenValue = frozenCache.get(item.pkg);
        boolean frozen = frozenValue != null ? frozenValue : FreezeManager.isFrozen(this, item.pkg);
        frozenCache.put(item.pkg, frozen);
        freeze.setText(frozen ? "解冻" : "冻结");
        freeze.setTextSize(11);
        freeze.setAllCaps(false);
        freeze.setMinWidth(0);
        freeze.setMinimumWidth(0);
        freeze.setPadding(dp(7), 0, dp(7), 0);
        freeze.setTextColor(frozen ? 0xFF9A7045 : 0xFF66737D);
        freeze.setBackground(new RippleDrawable(ColorStateList.valueOf(0x12000000),
                shape(0x00FFFFFF, 8, 0xFFE4E9EC), shape(0xFFFFFFFF, 8, 0)));
        pressFeedback(freeze);
        card.addView(freeze, rowParams(dp(54), dp(36)));
        freeze.setOnClickListener(v -> {
            boolean target = !Boolean.TRUE.equals(frozenCache.get(item.pkg));
            freeze.setEnabled(false);
            FreezeManager.setFrozen(this, item.pkg, target, (ok, actual, message) -> {
                freeze.setEnabled(true);
                frozenCache.put(item.pkg, actual);
                freeze.setText(actual ? "解冻" : "冻结");
                freeze.setTextColor(actual ? 0xFF9A7045 : 0xFF66737D);
                freeze.setBackground(new RippleDrawable(ColorStateList.valueOf(0x12000000),
                        shape(0x00FFFFFF, 8, 0xFFE4E9EC), shape(0xFFFFFFFF, 8, 0)));
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            });
        });
        return card;
    }

    private void applyFilter() {
        shown.clear();
        for (Item item : all) {
            boolean matches = keyword.isEmpty()
                    || item.label.toLowerCase(Locale.ROOT).contains(keyword)
                    || item.pkg.toLowerCase(Locale.ROOT).contains(keyword);
            if (!matches) continue;
            boolean category;
            switch (filter) {
                case "已选择": category = selected.contains(item.pkg); break;
                case "隐藏页识别": category = item.discovered; break;
                case "用户应用": category = !item.system; break;
                case "系统应用": category = item.system; break;
                default: category = true;
            }
            if (category) shown.add(item);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyView != null) emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        updateCount();
    }

    private void updateCount() {
        if (countView != null) {
            countView.setText(loading ? "正在读取应用列表…" :
                    "共 " + shown.size() + " 个应用 · 已选 " + selected.size() + " 个");
        }
    }

    private boolean save() {
        SharedPreferences prefs = App.remote;
        if (prefs == null || !selectedLoaded) {
            Toast.makeText(this, "未连接 LSPosed，设置暂未同步", Toast.LENGTH_SHORT).show();
            return false;
        }
        // 同步提交，确保切换应用后设置进程读取到的是最新名单。
        boolean saved = prefs.edit()
                .putStringSet(Config.K_PKGS, new HashSet<>(selected))
                .commit();
        if (!saved) {
            Toast.makeText(this, "保存失败，请检查 LSPosed 连接", Toast.LENGTH_LONG).show();
        }
        return saved;
    }
}
