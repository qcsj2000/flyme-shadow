package com.flyme.shadow;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/** 桌面上的“解锁桌面”入口：验证后选择 显示 / 重新隐藏 */
public class UnlockActivity extends AppCompatActivity {
    private static final int TEXT = 0xFF202A33;
    private static final int MUTED = 0xFF77838D;
    private static final int GREEN = 0xFF16856D;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AuthHelper.require(this, this::choose, this::finish);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private void choose() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(10));
        GradientDrawable panelBg = shape(0xFFFFFFFF, 14);
        panelBg.setStroke(dp(1), 0xFFE7EBEE);
        panel.setBackground(panelBg);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("桌面控制", 18, TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = label("×", 26, MUTED, false);
        close.setGravity(Gravity.CENTER);
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        panel.addView(titleRow);

        TextView subtitle = label("选择要执行的操作", 12, MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(2);
        subLp.bottomMargin = dp(14);
        panel.addView(subtitle, subLp);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        addAction(panel, "显示隐藏的页面和应用", "恢复桌面中已隐藏的内容", true, () -> {
            sendAction(Config.ACTION_UNLOCK);
            dialog.dismiss();
        });
        addAction(panel, "重新隐藏", "重新应用当前隐藏设置", false, () -> {
            sendAction(Config.ACTION_LOCK);
            dialog.dismiss();
        });
        close.setOnClickListener(v -> dialog.dismiss());
        // × 按钮走 dismiss() 不会触发 OnCancelListener，会留下一个透明的空 Activity 挡住触摸。
        dialog.setOnDismissListener(d -> finish());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(-1, -2);
        }
    }

    private void addAction(LinearLayout panel, String title, String detail,
                          boolean primary, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));
        GradientDrawable base = shape(0xFFF7F8F9, 8);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1216856D), base,
                shape(0xFFFFFFFF, 8)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(title, 14, TEXT, true);
        TextView desc = label(detail, 11, MUTED, false);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(-1, -2);
        descLp.topMargin = dp(3);
        texts.addView(name);
        texts.addView(desc, descLp);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        TextView arrow = label("›", 22, 0xFF9AA4AC, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(32)));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = dp(2);
        panel.addView(row, rowLp);
        row.setOnClickListener(v -> action.run());
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void sendAction(String action) {
        boolean sent = App.sendToLauncher(this, action);
        if (!sent) {
            Toast.makeText(this, "未连接到 LSPosed 框架，请确认模块已启用并勾选桌面",
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
