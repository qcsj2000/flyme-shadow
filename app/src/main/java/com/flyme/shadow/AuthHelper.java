package com.flyme.shadow;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;
import android.view.Gravity;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 指纹 或 自设密码 验证。首次使用先设置密码。 */
final class AuthHelper {
    private AuthHelper() {}

    private static SharedPreferences local(Context c) {
        return c.getSharedPreferences(Config.LOCAL_PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasPassword(Context c) {
        return local(c).getString(Config.L_HASH, null) != null;
    }

    private static byte[] hash(String pwd, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pwd.toCharArray(), salt, 120_000, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    static void savePassword(Context c, String pwd) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        local(c).edit()
                .putString(Config.L_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(Config.L_HASH, Base64.encodeToString(hash(pwd, salt), Base64.NO_WRAP))
                .apply();
    }

    private static boolean verify(Context c, String pwd) {
        try {
            SharedPreferences sp = local(c);
            byte[] salt = Base64.decode(sp.getString(Config.L_SALT, ""), Base64.NO_WRAP);
            byte[] want = Base64.decode(sp.getString(Config.L_HASH, ""), Base64.NO_WRAP);
            return MessageDigest.isEqual(want, hash(pwd, salt));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 验证通过 → ok；取消/失败 → cancel */
    static void require(AppCompatActivity a, Runnable ok, Runnable cancel) {
        if (!hasPassword(a)) {
            setupPassword(a, ok, cancel);
            return;
        }
        int can = BiometricManager.from(a).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.BIOMETRIC_WEAK);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            passwordDialog(a, ok, cancel);
            return;
        }
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("验证身份")
                .setSubtitle("使用指纹，或点击“使用密码”")
                .setNegativeButtonText("使用密码")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build();
        BiometricPrompt prompt = new BiometricPrompt(a, ContextCompat.getMainExecutor(a),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        ok.run();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            passwordDialog(a, ok, cancel);
                        } else {
                            cancel.run();
                        }
                    }
                });
        prompt.authenticate(info);
    }

    private static EditText pwdField(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private static void passwordDialog(AppCompatActivity a, Runnable ok, Runnable cancel) {
        EditText e = pwdField(a, "密码");
        new AlertDialog.Builder(a)
                .setTitle("输入密码")
                .setView(pad(a, e))
                .setCancelable(false)
                .setPositiveButton("确定", (d, w) -> {
                    if (verify(a, e.getText().toString())) {
                        ok.run();
                    } else {
                        Toast.makeText(a, "密码错误", Toast.LENGTH_SHORT).show();
                        cancel.run();
                    }
                })
                .setNegativeButton("取消", (d, w) -> cancel.run())
                .show();
    }

    static void setupPassword(AppCompatActivity a, Runnable ok, Runnable cancel) {
        boolean firstUse = !hasPassword(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 22);
        box.setPadding(pad, dp(a, 8), pad, dp(a, 4));

        TextView mark = new TextView(a);
        mark.setText(firstUse ? "FLYME SHADOW" : "安全验证");
        mark.setTextColor(0xFF16856D);
        mark.setTextSize(11);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setLetterSpacing(.08f);
        box.addView(mark);

        TextView desc = new TextView(a);
        desc.setText(firstUse
                ? "设置一个专属密码，用于进入桌面管家。密码至少 4 位，请牢记。"
                : "更新桌面管家的访问密码。新密码至少 4 位。");
        desc.setTextColor(0xFF77838D);
        desc.setTextSize(13);
        desc.setPadding(0, dp(a, 8), 0, dp(a, 18));
        box.addView(desc);

        EditText p1 = passwordField(a, "输入新密码");
        EditText p2 = passwordField(a, "再次确认密码");
        box.addView(p1, fieldParams(a));
        box.addView(p2, fieldParams(a));

        TextView error = new TextView(a);
        error.setTextColor(0xFFD92D20);
        error.setTextSize(12);
        error.setPadding(dp(a, 2), dp(a, 8), 0, 0);
        error.setVisibility(android.view.View.GONE);
        box.addView(error);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(firstUse ? "欢迎使用桌面管家" : "修改验证密码")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(firstUse ? "完成设置" : "保存新密码", null)
                .setNegativeButton("稍后再说", (d, w) -> cancel.run())
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String s1 = p1.getText().toString();
                    String s2 = p2.getText().toString();
                    String problem = null;
                    if (s1.length() < 4) problem = "密码至少需要 4 位";
                    else if (!s1.equals(s2)) problem = "两次输入的密码不一致";
                    if (problem != null) {
                        error.setText(problem);
                        error.setVisibility(android.view.View.VISIBLE);
                        return;
                    }
                    try {
                        savePassword(a, s1);
                        dialog.dismiss();
                        ok.run();
                    } catch (Exception ex) {
                        error.setText("保存失败：" + ex.getMessage());
                        error.setVisibility(android.view.View.VISIBLE);
                    }
                }));
        dialog.show();
    }

    private static EditText passwordField(Context c, String hint) {
        EditText e = pwdField(c, hint);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setTextColor(0xFF202A33);
        e.setHintTextColor(0xFF98A2AA);
        e.setPadding(dp(c, 15), dp(c, 13), dp(c, 15), dp(c, 13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF1F3F5);
        bg.setCornerRadius(dp(c, 9));
        bg.setStroke(dp(c, 1), 0xFFE4E9EC);
        e.setBackground(bg);
        return e;
    }

    private static LinearLayout.LayoutParams fieldParams(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(c, 10);
        return p;
    }

    private static int dp(Context c, int value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + .5f);
    }

    private static android.view.View pad(Context c, android.view.View v) {
        LinearLayout l = new LinearLayout(c);
        int p = (int) (20 * c.getResources().getDisplayMetrics().density);
        l.setPadding(p, p / 2, p, 0);
        l.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }
}
