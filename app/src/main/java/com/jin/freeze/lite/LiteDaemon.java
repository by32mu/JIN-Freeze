package com.jin.freeze.lite;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Shizuku UserService 起不来时的载体：由凚通过 Shizuku.newProcess 拉起 app_process，
 * 进程 uid=shell、名字 jin_lite，常驻；服务内逻辑直接复用 {@link JinLiteService}
 * （TaskStackListener 划卡映射 + 每秒 isInteractive 锁屏 + IPackageManager 冻结）。
 *
 * 配置与状态走 /data/local/tmp/jin_lite：
 * - config.json 由凚用 shell 写入，本进程按 mtime 重读；
 * - status.txt 由本进程周期性写入，凚读取用于设置页状态。
 */
public final class LiteDaemon {

    private static final String DIR = "/data/local/tmp/jin_lite";
    private static final String CONFIG = DIR + "/config.json";
    private static final String STATUS = DIR + "/status.txt";

    private static String lastStamp = "";

    private LiteDaemon() {
    }

    public static void main(String[] args) {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        try {
            Context context = createContext();
            final JinLiteService core = new JinLiteService(context);
            final Handler handler = new Handler(Looper.getMainLooper());
            Runnable tick = new Runnable() {
                @Override
                public void run() {
                    loadConfig(core);
                    writeStatus(core);
                    handler.postDelayed(this, 2000L);
                }
            };
            handler.post(tick);
            Looper.loop();
        } catch (Throwable t) {
            android.util.Log.e("JinLite", "daemon 启动失败", t);
        }
    }

    /** systemMain + createPackageContext：不调用 makeApplication，绕开小米那处 NPE。 */
    private static Context createContext() throws Exception {
        Class<?> atCls = Class.forName("android.app.ActivityThread");
        Object at = atCls.getMethod("systemMain").invoke(null);
        Context system = (Context) atCls.getMethod("getSystemContext").invoke(at);
        return system.createPackageContext("com.jin.freeze", 3);
    }

    private static void loadConfig(JinLiteService core) {
        try {
            File file = new File(CONFIG);
            if (!file.exists()) {
                return;
            }
            String stamp = file.lastModified() + ":" + file.length();
            if (stamp.equals(lastStamp)) {
                return;
            }
            lastStamp = stamp;
            byte[] data = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            in.close();
            JSONObject root = new JSONObject(new String(data, 0, off, "UTF-8"));
            String[] swipe = readArray(root, "swipe");
            String[] lock = readArray(root, "lock");
            String[] white = readArray(root, "white");
            String[] hidden = readArray(root, "hidden");
            core.syncConfig(swipe, lock, white, hidden,
                    root.optBoolean("swipeOn", false),
                    root.optBoolean("lockOn", false),
                    root.optInt("delay", 0),
                    root.optBoolean("suspend", false));
            android.util.Log.i("JinLite", "daemon 配置已更新");
        } catch (Throwable t) {
            android.util.Log.w("JinLite", "daemon 读配置失败: " + t);
        }
    }

    private static String[] readArray(JSONObject root, String key) {
        JSONArray array = root.optJSONArray(key);
        if (array == null) {
            return new String[0];
        }
        String[] values = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            values[i] = array.optString(i, "");
        }
        return values;
    }

    private static void writeStatus(JinLiteService core) {
        try {
            File dir = new File(DIR);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(STATUS, false);
            out.write(core.status().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }
}
