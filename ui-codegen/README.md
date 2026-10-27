# KSP 生成器（可选）

`ui-codegen-api` 提供 SOURCE 注解 `@GenerateUiFactory(id, schema)`；`ui-codegen` 是 KSP 编译期 processor。只有明确声明的 public、顶层、非泛型、非抽象 View，且主构造器唯一参数为非空 `android.content.Context` 时才生成类型化工厂、ref 别名和 registry 注册入口。生成代码的 ABI major 为 1；组件 schema 由声明显式指定。生成代码直接调用构造器和类型引用，不需要为本功能添加反射 keep 规则。

处理器以单个源文件为增量输入，检查声明与类型，不分析任意函数体。默认 core/widgets 不依赖 KSP，消费应用才需要插件、注解和 processor。参考 `samples/generated-consumer`；生成源码位于该样例的 `build/generated/ksp/`。

```sh
./gradlew -PenableCodegen=true :samples:generated-consumer:assembleDebug :samples:generated-consumer:assembleRelease :samples:generated-consumer:assembleDebugAndroidTest :samples:generated-consumer:lintDebug
python ui-codegen/verification/verify_codegen.py --workspace build/p2-codegen-validation/first
```

验证脚本在全新隔离目录执行严格依赖校验的首次构建、声明 id/schema 增量变更，以及非法构造器拒绝；不会删除现有目录。R8 APK 真机运行、构建时间和体积成本需另行测量。项目候选采用 KSP 2.3.12、Kotlin 2.4.21、AGP 9.4.1 和 Gradle 9.8.2；这些版本组合的实际构建结果与官方完整支持矩阵须分开记录，不据此声称官方完全支持。
