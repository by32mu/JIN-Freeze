# 第三方组件与许可证

本项目以 GPL-3.0 发布。下列组件随仓库或构建一起分发，版权归各自作者所有。

## 随仓库提供的本地 jar（`app/libs`）

| 文件 | 来源 | 许可证 | 说明 |
| --- | --- | --- | --- |
| `dhizuku-api-2.5.4-cp.jar` | [iamr0s/Dhizuku](https://github.com/iamr0s/Dhizuku) | GPL-3.0 | 原库由 Java 21 编译（class 头 65.0），此处为 class 头降级副本（只改版本号，不改字节码），供 javac 17 读取。原 AAR 的 manifest 片段已并入 `AndroidManifest.xml`，其 proguard 规则已并入 `app/proguard-rules.pro` |
| `hiddenapibypass-6.1.jar` | [LSPosed/HiddenApiBypass](https://github.com/LSPosed/HiddenApiBypass) | Apache-2.0 | 绕过隐藏 API 限制，供 Shizuku 服务与 suspend 通道使用 |
| `libxposed-api-102.jar` | [libxposed/api](https://github.com/libxposed/api) | Apache-2.0 | LSPosed 现代模块接口（仅 `compileOnly`，不打包进 APK） |
| `libxposed-interface-102.jar` | [libxposed/api](https://github.com/libxposed/api) | Apache-2.0 | 同上 |
| `framework-stubs.jar` | 本项目自制 | GPL-3.0 | 仅为编译期提供 `android.app.TaskStackListener` 桩类；运行时使用系统实现，不打包进 APK。源码在 `framework-stubs/` |

## Gradle 依赖

| 依赖 | 许可证 |
| --- | --- |
| `androidx.appcompat:appcompat:1.7.0` | Apache-2.0 |
| `com.google.android.material:material:1.12.0` | Apache-2.0 |
| `androidx.recyclerview:recyclerview:1.3.2` | Apache-2.0 |
| `dev.rikka.shizuku:api:12.2.0` / `provider:12.2.0` | Apache-2.0（[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)） |

## 备注

- `compileOnly` 的依赖（libxposed、framework-stubs）不会进入 APK，仅参与编译。
- 源码与创意层面的借鉴（冰箱、雹、Thanox、hyper2B、搞机助手）见 [README.md](README.md) 的「致谢与借鉴」。
- 修改或再分发本项目时请遵守 GPL-3.0：保留版权声明，并以相同许可证公开源代码。
