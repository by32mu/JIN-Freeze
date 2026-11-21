package com.jin.freeze.xposed;

import java.lang.reflect.Method;

/**
 * Hook 适配层：凚 同时提供 libxposed（新 API）与 XposedBridge（旧 API）两个入口，
 * 这里把两者的差异收敛成一个方法替换回调，业务代码只依赖本接口。
 */
public interface HookKit {

    /** 替换 method 的实现；around 负责调用 proceed() 继续原逻辑。 */
    boolean hook(Method method, Around around);

    void log(String message);

    interface Around {
        Object invoke(Object thisObj, Object[] args, Proceed proceed) throws Throwable;
    }

    interface Proceed {
        Object run() throws Throwable;

        Object run(Object[] args) throws Throwable;
    }
}
