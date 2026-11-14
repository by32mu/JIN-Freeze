package com.jin.freeze.xposed;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 旧 API 入口（由 assets/xposed_init 声明），新 API 在 system_server 注入异常时兜底。
 */
public final class JinLegacy implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!"android".equals(param.packageName)) {
            return;
        }
        try {
            SwipeFreezeHook.install(param.classLoader, new Kit());
        } catch (Throwable t) {
            android.util.Log.w("JinSwipeFreeze", "install 失败", t);
        }
    }

    private static final class Kit implements HookKit {

        @Override
        public boolean hook(Method method, Around around) {
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            param.setResult(around.invoke(
                                    param.thisObject,
                                    param.args,
                                    new Proceed() {
                                        @Override
                                        public Object run() throws Throwable {
                                            return XposedBridge.invokeOriginalMethod(
                                                    param.method, param.thisObject, param.args);
                                        }

                                        @Override
                                        public Object run(Object[] args) throws Throwable {
                                            return XposedBridge.invokeOriginalMethod(
                                                    param.method, param.thisObject, args);
                                        }
                                    }));
                        } catch (Throwable t) {
                            android.util.Log.w("JinSwipeFreeze", "hook 回调异常", t);
                            try {
                                param.setResult(XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args));
                            } catch (Throwable ignored) {
                                // 原方法也失败时只能放弃，不能让 system_server 崩
                            }
                        }
                    }
                });
                return true;
            } catch (Throwable t) {
                android.util.Log.w("JinSwipeFreeze", "hook 失败 " + method, t);
                return false;
            }
        }

        @Override
        public void log(String message) {
            XposedBridge.log("[JinSwipeFreeze] " + message);
        }
    }
}
