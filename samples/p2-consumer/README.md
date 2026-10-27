# 独立 P2 制品消费者

这个 Gradle root 只从根工程的 `build/repo` 读取 `1.0.0` Maven 制品，不引用根工程 `project()` 输出。它同时使用 widgets、UiPlan、RecyclerView、文本、原生行、预热和 KSP 生成器；release 构建启用 R8 和资源压缩。

先在根工程启用完整 P2 构建并执行 `publishCandidate`，再在根工程运行：

```sh
python samples/p2-consumer/prepare_artifacts.py
./gradlew -p samples/p2-consumer :app:assembleDebug :app:assembleRelease :app:lintDebug --no-daemon --max-workers=2
```

准备脚本比较每个已发布二进制与当前模块构建输出，核验 POM 坐标，复制根工程的官方依赖校验，再将精确的当前候选哈希加入本样例自己的本地验证元数据。该生成文件和制品摘要保留在本地，重新发布候选后应重新执行。构建通过只证明独立制品接入和 R8 编译；设备交互及性能结果另行验证。

执行 `python samples/p2-consumer/verify_consumer.py` 可按上述步骤构建，并将非零检查数、源指纹、候选制品与输出哈希及实际构建日志写入 `build/reports/gates/p2-consumer.json`。收据的 `test_count` 为 0，因为它不冒充执行设备或自动化行为测试。
