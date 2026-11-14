# 凚 R8/ProGuard 规则
# 目标：开启压缩（minify + shrinkResources）后，反射加载与清单实例化的入口不被改名/删除。

# ---------- 基础属性 ----------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# ---------- LSPosed / Xposed 入口（整包保留）----------
# LSPosed 通过 META-INF/xposed/java_init.list 里的类名反射加载，且要按 XposedBridge 约定
# 调用 initZygote / handleLoadPackage，类名、方法名、参数签名都不能变。
-keep class com.jin.freeze.xposed.** { *; }
-keep interface com.jin.freeze.xposed.** { *; }
-keepclassmembers class com.jin.freeze.xposed.** { *; }
-keep class com.jin.freeze.xposed.JinModule { *; }

# Xposed API 与 LSPosed 注解只在 compileOnly 里，运行期由宿主提供，不参与打包
-dontwarn de.robv.android.xposed.**
-dontwarn org.lsposed.**
-dontwarn android.content.pm.**
-keep class de.robv.android.xposed.** { *; }
-keep class org.lsposed.** { *; }

# ---------- 清单里声明的组件（系统按类名反射实例化）----------
-keep class com.jin.freeze.JinApp { *; }
-keep class com.jin.freeze.ui.LaunchActivity { *; }
-keep class com.jin.freeze.ui.ModuleEntryActivity { *; }
-keep class com.jin.freeze.ui.FreezePanelActivity { *; }
-keep class com.jin.freeze.ui.FreezeListActivity { *; }
-keep class com.jin.freeze.ui.AppFreezeActivity { *; }
-keep class com.jin.freeze.ui.QuickLaunchActivity { *; }
-keep class com.jin.freeze.ui.ShortcutBatchService { *; }
-keep class com.jin.freeze.ui.ShortcutProviderActivity { *; }

# 兜底：任何继承系统组件基类的类都保留类名与无参构造
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------- Shizuku（Binder / 反射调用，AAR 自带规则，这里再兜一层）----------
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# ---------- 资源里通过 inflate 类名实例化的 View ----------
# 本项目全部用系统控件，没有自定义 View；保留带 AttributeSet 的构造，防止后续新增被裁
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------- 枚举 ----------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- Parcelable ----------
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepnames class * implements android.os.Parcelable

# ---------- 序列化 / JSON 反序列化目标 ----------
# 本项目的两个 JSON 模型（自定义标签、应用归属）都是手工 new 对象后逐字段解析，
# 不通过反射按字段名反序列化，因此没有额外 keep 目标；这里保留常见的 Serializable
# 约定，避免以后加字段名反射解析时被裁。
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------- 其它被反射/框架调用的入口 ----------
# 自定义 Application 的 attachBaseContext / onCreate 由系统回调
-keepclassmembers class com.jin.freeze.JinApp {
    protected void attachBaseContext(android.content.Context);
    public void onCreate();
}

# Fragment / Activity 生命周期回调由框架调用，保留重写方法名
-keepclassmembers class * extends android.app.Activity {
    protected void onCreate(android.os.Bundle);
    protected void onNewIntent(android.content.Intent);
    protected void onResume();
    protected void onPause();
    protected void onDestroy();
    public void onBackPressed();
}

# Binder / AIDL 接口
-keep class * extends android.os.Binder { *; }
-keep interface * extends android.os.IInterface { *; }
