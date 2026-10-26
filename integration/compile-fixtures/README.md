# 真实 Kotlin 消费编译门禁

先构建并发布当前候选，再执行 `python integration/compile-fixtures/verify.py`。脚本从隔离 `build/repo` 的 Maven snapshot metadata 精确选择两个候选 AAR，核对其 SHA 与本次 release 输出一致，提取真正 `classes.jar`，使用版本目录指定的 Kotlin 编译器和公开 Android SDK 编译消费代码。

正例必须成功；错误父 LP、错误 typed ref、向纯数据快照塞 View 的反例必须由编译器报告类型不匹配。任意网络错误、缺类、编译器启动失败均不能充当反例通过。每项命令、退出码、日志、输入哈希与候选坐标保存在 `build/reports/compile-fixtures/receipt.json`。

`api/public-api.txt` 是初次候选的 JVM 公开描述符基线。只有经过人工审查 API 变化后才显式执行 `--update-api`；普通验证不重写基线。它包含 Kotlin 编译生成的公开 ABI，因此同时保留真实 Kotlin 正负消费编译。当前没有更早的正式发布，本检查不声称已经证明任意历史消费者二进制兼容。

脚本需要已有精确 Kotlin compiler POM 与依赖缓存、公开 Android 37 SDK、JDK 的 `java/javap`。未解析到正确依赖时 fail closed 并记录 blocked，不下载替代版本。`--gradle-cache` 与 `--android-jar` 可显式配置环境。
