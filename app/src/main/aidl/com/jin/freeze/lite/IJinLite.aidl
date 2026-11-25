// 凚 Shizuku UserService：shell(uid 2000) 进程里的划卡/锁屏冻结配置通道。
package com.jin.freeze.lite;

interface IJinLite {
    /**
     * 同步冻结配置。
     * swipePkgs / lockPkgs 是实例键（主用户纯包名，分身 pkg@uid）；
     * whitelist 里的键任何情况下都不冻结。
     */
    void syncConfig(in String[] swipePkgs, in String[] lockPkgs, in String[] whitelist,
            in String[] hiddenPkgs,
            boolean swipeEnabled, boolean lockEnabled, int lockDelaySeconds,
            boolean useSuspend) = 1;

    /** 服务自检：监听器注册情况、两套名单条数、最近一次事件。 */
    String status() = 2;
}
