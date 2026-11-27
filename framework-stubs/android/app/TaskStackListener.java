package android.app;

import android.content.ComponentName;
import android.os.IBinder;

/**
 * 仅用于编译的隐藏类桩：运行时会用系统真正的 android.app.TaskStackListener
 * （这个桩只放在 compileOnly jar 里，不会打进 APK）。
 * 照 Thanox lite 的做法直接继承它，回调就是强类型的，不需要自己解析 Parcel。
 */
public class TaskStackListener {

    public TaskStackListener() {
    }

    public void onTaskStackChanged() {
    }

    public void onTaskCreated(int taskId, ComponentName componentName) {
    }

    public void onTaskRemoved(int taskId) {
    }

    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
    }

    public void onTaskMovedToBack(ActivityManager.RunningTaskInfo taskInfo) {
    }

    public IBinder asBinder() {
        return null;
    }
}
