# 可选桥与依赖边界

所有桥均默认关闭，通过 Gradle 属性分别启用：

```sh
./gradlew -PenableLumen=true -PenableRef=true -PenableAndroid=true verifyHost
```

这条命令仅在对应依赖已经准备好时适用。三个桥均不进入普通 core/widgets 的传递依赖。

## Lumen

`ui-lumen-bridge` 使用 `com.github.jichuo1.LumenCoacervationEngine:lumen-engine:1.5.5`，从限定 group 的 JitPack 仓库解析。桥使用真实 `LumenSurfaceSession` 与 binding，为视觉属性保留所有权；材质、动画和绘制仍由 Lumen 负责。会话是否随桥关闭由明确的所有权参数决定。

## Ref 与 Android

Ref 桥依赖 Maven Central 的 `io.github.jichuo1.meguviz:meguviz-ref-core:1.0.0`，无需导入本地 Ref 候选。Gradle 校验元数据和上游 pins 均固定真实制品的 SHA-256。

Android 桥仍针对真实编译过的 `0.1.0-SNAPSHOT` 候选。精确版本与 SHA-256 记录于 [upstream-candidates.toml](../gradle/upstream-candidates.toml)，这些候选不随本仓库分发，必须先取得与 pins 一致的 Maven 仓库：

```sh
python scripts/import-upstream-candidates.py --repository <android-maven-repository> --artifact meguviz-android-runtime --artifact meguviz-android-window
```

脚本验证所有输入后导入 `build/upstream-repo/`，不构建或修改上游项目。版本或哈希不一致会拒绝；没有这些制品时仍可构建默认的核心与示例。

Ref 桥使用真实 `CodeScope`、`QuerySpec`、`ConstructorPlan` 与 `MethodPlan`。UI 自己的 receiver lease 同时检查代码身份与 View 生命周期，不伪造上游接口，也不维护全局 receiver 缓存。

Android 桥使用真实 `WindowSession`、`FrameScheduler.FrameChannel` 与资源状态权威。在同一帧通道内执行 UI 更新，避免额外调度器。窗口所有者必须先关闭 binding，再关闭共享窗口或 scheduler；外部关闭的可观测性受上游公开 API 限制。

Host 接口与合同检查不等于 Android/ART、视觉或完整设备认证。

[返回文档目录](README.md)
