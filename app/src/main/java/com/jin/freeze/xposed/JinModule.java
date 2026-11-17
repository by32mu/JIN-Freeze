package com.jin.freeze.xposed;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Method;

/**
 * libxposed 新 API 入口（由 META-INF/xposed/java_init.list 声明）。
 * 只在 LSPosed 激活且凚 被勾选作用域时被加载；未激活时这段代码不会被任何人引用。
 */
public final class JinModule extends XposedModule {

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        try {
            Kit kit = new Kit(this);
            SwipeFreezeHook.install(param.getClassLoader(), kit);
            LockFreezeHook.install(param.getClassLoader(), kit);
        } catch (Throwable t) {
            android.util.Log.e("JinSwipeFreeze", "install 失败", t);
        }
    }

    /** 把 libxposed 的 Chain 适配成 HookKit 的回调。 */
    private static final class Kit implements HookKit {

        private final XposedModule module;

        Kit(XposedModule module) {
            this.module = module;
        }

        @Override
        public boolean hook(Method method, Around around) {
            try {
                method.setAccessible(true);
                module.hook(method).intercept(chain -> around.invoke(
                        chain.getThisObject(),
                        chain.getArgs().toArray(),
                        new Proceed() {
                            @Override
                            public Object run() throws Throwable {
                                return chain.proceed();
                            }

                            @Override
                            public Object run(Object[] args) throws Throwable {
                                return chain.proceed(args);
                            }
                        }));
                return true;
            } catch (Throwable t) {
                log("hook 失败 " + method + ": " + t);
                return false;
            }
        }

        @Override
        public void log(String message) {
            try {
                module.log(android.util.Log.INFO, "JinSwipeFreeze", message);
            } catch (Throwable ignored) {
                android.util.Log.i("JinSwipeFreeze", message);
            }
        }
    }
}
