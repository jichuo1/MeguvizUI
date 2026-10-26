# 安装与接入

## 从源码构建

使用 JDK 17、Android SDK 37 与 Build Tools 37.0.0。Gradle Wrapper 固定为 9.8.2，AGP 为 9.4.1，Kotlin 编译器为 2.4.21；Android 模块使用 AGP 内置 Kotlin，字节码目标为 17，最低 SDK 为 27。

在根目录的 `local.properties` 配置自己的 `sdk.dir`，不要提交此文件。执行：

```sh
./gradlew verifyHost publishCandidate --no-daemon --max-workers=2
```

## 通过本地 Maven 消费

当前没有 Maven Central 稳定发行版。构建生成的 Maven 仓库位于源码根目录的 `build/repo/`，消费项目需显式指向自己的实际目录：

```kotlin
// 消费项目的 settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri(providers.gradleProperty("meguvizUiRepository").get()) }
        google()
        mavenCentral()
    }
}

// 消费模块的 build.gradle.kts
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ui-widgets:1.0.0")
}
```

将 `meguvizUiRepository` 设置为生成仓库的路径。widgets 传递依赖 core；不需要额外引入 Ref、Android 窗口运行时或 Lumen。正式发布的版本不应以不同内容覆盖；这里的本地仓库用于发布前验证。

## 示例与主题

[standalone](../samples/standalone) 展示 Framework 控件、输入模型和状态恢复；[xml-interop](../samples/xml-interop) 展示 AppCompat、Factory2 和主题。AppCompat 依赖只属于该可选消费示例。

传入 Activity 或所需 themed Context，应用负责窗口和原生 root 的生命周期。自定义 View 使用准确的直接工厂；Framework、AppCompat、Material 控件不能按名称互换。

可选桥需要额外准备依赖，见 [桥接说明](bridges.md)。

[返回文档目录](README.md)
