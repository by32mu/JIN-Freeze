package com.jin.freeze.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 系统「应用已被暂停」提示框的定制：标题、正文、左下按钮。
 *
 * 通道差异（AOSP 源码核对）：
 * - {@code pm suspend} 的 shell 命令只支持 --user 与 --dialogMessage，标题和按钮改不了；
 * - {@code SuspendDialogInfo} 才能带 title / message / 中性按钮文案 + 动作，
 *   且中性按钮动作 UNSUSPEND 由系统自己撤销暂停并继续启动，不需要我们再接 Intent；
 * - 右侧那颗确认按钮在 SuspendDialogInfo 里没有对应字段，属于系统固定文案。
 *
 * 调用方式与雹(Hail)一致：Shizuku 的 ShizukuBinderWrapper 把身份换成 shell(uid 2000)，
 * 再用 {@code IPackageManager$Stub.asInterface} 拿代理，按真实方法签名调用。
 * 代理内部自己处理 transaction code 与参数顺序，比手工写 Parcel 更稳（参数个数随
 * Android 版本变化：29~34 是 7 个，35+ 是 9 个，这里按运行期方法签名自适应）。
 * 这条通道依赖 {@code SUSPEND_APPS}，com.android.shell 在系统清单里声明并持有它。
 *
 * 只有 root 没有 Shizuku 时无法以 root 身份发 binder，只能退回 pm suspend 带正文。
 * 本机 SDK 平台的 android.jar 里没有 SuspendDialogInfo，故用反射构造。
 */
public final class SuspendDialog {

    /** 提示框标题：只有 API 通道能设。 */
    public static final String TITLE = "应用已被凚冻结";
    /** 左下按钮文案；动作是系统的「撤销暂停并继续启动」。 */
    public static final String BUTTON = "打开应用";

    private static final String DESC = "android.content.pm.IPackageManager";
    /** TRANSACTION_* / asInterface 都在 $Stub 上。 */
    private static final String STUB = DESC + "$Stub";
    private static final String METHOD = "setPackagesSuspendedAsUser";
    private static final String CLS_INFO = "android.content.pm.SuspendDialogInfo";
    private static final String CLS_BUILDER = CLS_INFO + "$Builder";

    /** 隐藏 API 豁免只做一次。 */
    private static volatile Boolean exempted;

    private SuspendDialog() {
    }

    /** 正文：<应用名>已被凚暂停，左下角打开应用。 */
    public static String message(String label) {
        String name = TextUtils.isEmpty(label) ? "该应用" : label;
        return name + "已被凚暂停，左下角打开应用。";
    }

    /** 能不能走 SuspendDialogInfo 通道：需要 Shizuku 在线且已授权。 */
    public static boolean available() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用 SuspendDialogInfo 暂停一个包。成功返回 true；任何一步不成立都返回 false，
     * 由调用方退回 pm suspend 通道，不在这里假装成功。
     */
    public static boolean suspend(Context c, String pkg, String label) {
        if (!available() || pkg == null) {
            return false;
        }
        try {
            exemptHiddenApi();
            IBinder service = SystemServiceHelper.getSystemService("package");
            if (service == null) {
                Log.w("JinPm", "no package service binder");
                return false;
            }
            Object pm = Class.forName(STUB)
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, new ShizukuBinderWrapper(service));
            Method target = null;
            for (Method m : Class.forName(DESC).getMethods()) {
                if (METHOD.equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                Log.w("JinPm", "no method " + METHOD + " on " + DESC);
                return false;
            }
            Parcelable info = buildDialogInfo(label);
            if (info == null) {
                Log.w("JinPm", "SuspendDialogInfo unavailable");
                return false;
            }
            int userId = Process.myUid() / 100000;
            // 以 root 身份运行的 Shizuku 用本应用包名，shell 身份必须报 com.android.shell
            String caller = Shizuku.getUid() == 0 ? c.getPackageName() : "com.android.shell";
            boolean newSignature = target.getParameterTypes().length >= 9;
            Object[] args = newSignature
                    // Android 15+：…, dialogInfo, flags, suspendingPackage, suspendingUserId, targetUserId
                    ? new Object[]{new String[]{pkg}, Boolean.TRUE, null, null, info, 0,
                            caller, userId, userId}
                    // Android 10~14：…, dialogInfo, callingPackage, userId
                    : new Object[]{new String[]{pkg}, Boolean.TRUE, null, null, info,
                            caller, userId};
            String[] rejected = (String[]) target.invoke(pm, args);
            return rejected == null || rejected.length == 0;
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            Log.w("JinPm", "suspend with dialog failed: " + cause);
            return false;
        }
    }

    /** 出问题时能看出是「Shizuku 没连上」还是「权限没给」。 */
    private static String shizukuState() {
        try {
            return "ping=" + Shizuku.pingBinder() + " perm=" + Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            return "err=" + t;
        }
    }

    /**
     * 豁免隐藏 API：IPackageManager / SuspendDialogInfo$Builder / TRANSACTION_* 都是非 SDK 成员，
     * Android 9+ 的反射过滤会让它们「不存在」，所以调用前先豁免（只影响本进程）。
     */
    private static void exemptHiddenApi() {
        if (Boolean.TRUE.equals(exempted)) {
            return;
        }
        synchronized (SuspendDialog.class) {
            if (Boolean.TRUE.equals(exempted)) {
                return;
            }
            boolean ok = false;
            try {
                // "L" 是「豁免全部」的通配前缀；同一个库也是雹(Hail)用的那套
                HiddenApiBypass.setHiddenApiExemptions("L");
                ok = true;
            } catch (Throwable t) {
                Log.w("JinPm", "hidden api exemption failed: " + t);
            }
            exempted = ok;
        }
    }

    /** 反射拼出 SuspendDialogInfo；系统不给这个类（API &lt; 29）时返回 null。 */
    private static Parcelable buildDialogInfo(String label) throws Exception {
        Class<?> builderCls = Class.forName(CLS_BUILDER);
        Class<?> infoCls = Class.forName(CLS_INFO);
        Object builder = builderCls.getConstructor().newInstance();
        builderCls.getMethod("setTitle", String.class).invoke(builder, TITLE);
        builderCls.getMethod("setMessage", String.class).invoke(builder, message(label));
        builderCls.getMethod("setNeutralButtonText", String.class).invoke(builder, BUTTON);
        int unsuspend = infoCls.getField("BUTTON_ACTION_UNSUSPEND").getInt(null);
        builderCls.getMethod("setNeutralButtonAction", int.class).invoke(builder, unsuspend);
        Object info = builderCls.getMethod("build").invoke(builder);
        return info instanceof Parcelable ? (Parcelable) info : null;
    }
}
