<div align="center">

# MeguvizUI

**面向 Android 原生 View 的强类型 Kotlin UI 库**

布局类型约束 · 属性所有权 · 状态与命令 · 会话生命周期

[![CI](https://github.com/jichuo1/MeguvizUI/actions/workflows/verify.yml/badge.svg)](https://github.com/jichuo1/MeguvizUI/actions/workflows/verify.yml)
![JVM 17](https://img.shields.io/badge/JVM-17-blue)
![Android API 27+](https://img.shields.io/badge/Android-API%2027%2B-3DDC84)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)](LICENSE)

[快速开始](#quick-start) · [使用示例](#examples) · [关键行为](#behavior) · [文档](#documentation)

</div>

---

## ✨ 特性

- **强类型布局**：父作用域、控件引用和 LayoutParams 在编译期约束；直接调用原生构造器。
- **明确更新语义**：状态槽可合并，命令保留次数；属性声明比较方式、快照和所有权。
- **会话管理**：绑定、监听、挂载和恢复共享生命周期，过期引用与代际令牌拒绝继续写入。
- **独立核心**：核心仅依赖 Android SDK 与 Kotlin 运行时；Ref、Android 窗口运行时及 Lumen 均通过可选桥接入。

## 📦 模块

| 模块 | Maven artifactId | 职责 |
| --- | --- | --- |
| `ui-core` | `meguviz-ui-core` | 会话、引用、属性、调度、恢复与扩展合同 |
| `ui-widgets` | `meguviz-ui-widgets` | Framework 工厂、父布局作用域、尺寸与常用属性 |
| `ui-lumen-bridge` | `meguviz-ui-lumen-bridge` | 将视觉属性所有权交给 Lumen |
| `ui-ref-bridge` | `meguviz-ui-ref-bridge` | 对接真实 Ref 构造与调用计划 |
| `ui-android-bridge` | `meguviz-ui-android-bridge` | 对接窗口生命周期、资源权威与同一帧驱动 |
| `ui-testkit` | 不发布 | 合同测试支持 |

<a id="quick-start"></a>

## 🚀 快速开始

当前版本为 **`1.0.0` 源码版本**，尚未发布到 Maven Central，尚无历史稳定版兼容证明。API 27 是声明的最低版本；完整设备、无障碍及性能矩阵尚未完成。

准备 JDK 17、Android SDK 37 与 Build Tools 37.0.0，在未提交的 `local.properties` 中配置 `sdk.dir`，然后构建核心、控件与两个示例：

```sh
git clone https://github.com/jichuo1/MeguvizUI.git
cd MeguvizUI
./gradlew verifyHost publishCandidate --no-daemon --max-workers=2
```

Windows 使用 `gradlew.bat`；本地 Maven 候选写入 `build/repo/`。普通应用依赖 `meguviz-ui-widgets` 即可，具体接入与可选桥准备见 [安装说明](docs/installation.md)。

<a id="examples"></a>

## 💡 使用示例

```kotlin
import android.widget.FrameLayout
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.Properties
import io.github.jichuo1.meguviz.ui.widgets.frameParams

// 在 Activity 的主线程中创建，保留实际 themed Context。
val root = FrameLayout(activity)
val session = UiSession(CreationEnvironment(activity))
val title = FrameScope(session, root).text(ViewKey("title"), frameParams()) {
    text = "Ready"
}
val label = session.bind(title, Properties.plainText())
label.submit("Updated")

// 在所有者销毁时，于主线程释放。
session.close()
```

完整示例见 [standalone](samples/standalone) 与 [xml-interop](samples/xml-interop)。前者包含计数命令、输入模型和布局后恢复；后者展示 AppCompat 的 Factory2、主题与已有 View 的借用。

<a id="behavior"></a>

## ⚠️ 关键行为

1. View 与会话操作在主线程执行。释放后的 `ViewRef` 失效；持有原生 View 的外部代码仍需自行遵守所有权约定。
2. 状态仅在属性显式允许时合并；命令为 FIFO。有序 batch 保持自身顺序，不提供事务回滚。
3. 文本输入、IME 和具有副作用的 setter 应由明确的命令或业务控制器管理。
4. 应用先恢复权威模型，再恢复焦点、选择与滚动；自定义控件需要自己的 codec 或公开恢复适配。
5. Lumen 保留材质与动效职责。Android 桥使用上游帧通道，关闭顺序由窗口所有者协调。
6. Host 构建、设备交互和完整发布验收分别记录；单元测试成功不代表设备矩阵或性能结论。

<a id="documentation"></a>

## 📚 文档

- [文档目录](docs/README.md)
- [安装与接入](docs/installation.md)
- [核心合同](docs/contracts.md)
- [可选桥与依赖边界](docs/bridges.md)
- [构建、验证与贡献](docs/development.md)
- [公开 API 基线](api/public-api.txt) · [编译夹具](integration/compile-fixtures)

## 📄 许可证

[Apache License 2.0](LICENSE)。第三方组件保留各自的许可证与声明，见 [NOTICE](NOTICE)。
