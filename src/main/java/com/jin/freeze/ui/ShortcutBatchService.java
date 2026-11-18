package com.jin.freeze.ui;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import com.jin.freeze.core.PrivShell;
import com.jin.freeze.core.SuspendSupport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 快捷方式「一键冻结 / 一键解冻」的执行体。
 * 入口 Activity 用 NoDisplay 主题直接结束，不在屏幕上建窗口，所以状态栏不会被压黑；
 * 整批只拉起一次 su，并跳过已经处于目标状态的应用，不再整张名单重跑。
 */
public class ShortcutBatchService extends Service {

    public static final String EXTRA_FREEZE = "freeze";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final boolean freeze = intent != null && intent.getBooleanExtra(EXTRA_FREEZE, false);
        new Thread(() -> {
            runBatch(freeze);
            stopSelf(startId);
        }, "shortcut-batch").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runBatch(boolean freeze) {
        android.content.SharedPreferences sp = getSharedPreferences("freeze_list", MODE_PRIVATE);
        Set<String> pkgs = new HashSet<>(sp.getStringSet("pkgs", new HashSet<>()));
        if (pkgs.isEmpty()) {
            toast("冻结名单为空");
            return;
        }
        // 冻结白名单：快捷方式的一键冻结同样不处理白名单里的应用
        Set<String> whitelist = freeze ? sp.getStringSet("freeze_whitelist", null) : null;
        PackageManager pm = getPackageManager();
        // 暂停态对「冻结」来说等同于已到位；对「解冻」来说必须再撤一次暂停
        Set<String> suspendedBefore = SuspendSupport.suspendedAmong(SuspendSupport.cached(this));
        List<String> todo = new ArrayList<>();
        for (String pkg : pkgs) {
            if (whitelist != null && whitelist.contains(pkg)) {
                continue;
            }
            boolean suspended = suspendedBefore.contains(pkg);
            // 已经是目标状态的不再执行 pm：重复整批调用是原来耗时的主要来源之一
            Boolean enabled = enabledOf(pm, pkg);
            if (freeze ? (suspended || (enabled != null && !enabled))
                    : (enabled != null && enabled && !suspended)) {
                continue;
            }
            if (pkg.matches("[A-Za-z0-9._]+")) {
                todo.add(pkg);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        // 一次 su + 限并发跑完整批：pm 每条都要拉起一个 app_process，
        // 串行几十条是秒级；并发 8 路能把整批压到一秒左右
        StringBuilder cmd = new StringBuilder();
        cmd.append("i=0; for p in");
        for (String p : todo) {
            cmd.append(" '").append(p).append("'");
        }
        cmd.append("; do ")
                .append(freeze
                        ? "pm disable-user --user 0 \"$p\" >/dev/null 2>&1 & "
                        : "pm unsuspend --user 0 \"$p\" >/dev/null 2>&1; "
                        + "pm enable --user 0 \"$p\" >/dev/null 2>&1 & ")
                .append("i=$((i+1)); ")
                .append("[ $((i%8)) -eq 0 ] && wait; done; wait");
        PrivShell.Result result = PrivShell.exec(cmd.toString(), 120);
        // 执行完统一核对真实状态：既拿失败名单，也不依赖 pm 的文本输出
        Set<String> suspendedAfter = SuspendSupport.suspendedAmong(todo);
        if (freeze) {
            // 停用被 ROM 拦住（仍可启用且未进入暂停）的包，自动改走 pm suspend
            List<String> retry = new ArrayList<>();
            for (String pkg : todo) {
                Boolean enabled = enabledOf(pm, pkg);
                if (enabled != null && enabled && !suspendedAfter.contains(pkg)) {
                    retry.add(pkg);
                }
            }
            if (!retry.isEmpty()) {
                // 逐包走 SuspendSupport：有 Shizuku 时用 SuspendDialogInfo 定制提示框，
                // 没有时退回带 --dialogMessage 的 pm suspend
                for (String p : retry) {
                    SuspendSupport.suspend(this, p, labelOf(pm, p));
                }
                Set<String> again = SuspendSupport.suspendedAmong(retry);
                if (!again.isEmpty()) {
                    suspendedAfter.addAll(again);
                    SuspendSupport.remember(this, again);
                }
            }
        } else {
            Set<String> next = SuspendSupport.cached(this);
            next.removeAll(todo);
            next.addAll(suspendedAfter);
            SuspendSupport.replace(this, next);
        }
        List<String> fails = new ArrayList<>();
        for (String pkg : todo) {
            Boolean enabled = enabledOf(pm, pkg);
            boolean ok = freeze
                    ? ((enabled != null && !enabled) || suspendedAfter.contains(pkg))
                    : (enabled != null && enabled && !suspendedAfter.contains(pkg));
            if (!ok) {
                fails.add(labelOf(pm, pkg) + "：" + (freeze
                        ? "执行后未变为冻结，可能被系统限制" : "执行后未变为启用，可能被系统限制"));
            }
        }
        if (!fails.isEmpty()) {
            // 批量失败清单只列应用名 + 短原因；原始 pm 输出只进日志，不进提示
            String out = result.out == null ? "" : result.out.trim();
            if (!out.isEmpty()) {
                android.util.Log.w("JinPm", "shortcut batch " + (freeze ? "freeze" : "unfreeze")
                        + " raw output:\n" + out);
            }
            StringBuilder sb = new StringBuilder("失败 " + fails.size() + " 个");
            for (String f : fails) {
                sb.append("\n\n").append(f);
            }
            toast(sb.toString());
        }
    }

    private String labelOf(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** null 表示查不到，交给 pm 执行并按结果判定成败。 */
    private Boolean enabledOf(PackageManager pm, String pkg) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return info.enabled;
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String text) {
        new Handler(Looper.getMainLooper())
                .post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }
}
