# 按需预热

`UiPrewarmer` 必须由调用者显式创建；它复用已有 `UiScheduler`，每帧最多执行一个主线程工作单元，并限制队列容量和每项工作单元数量。单元超过耗时预算只能在返回后检测，不能抢占正在执行的构造。

任务通过 `isCurrent` 校验资源、主题和目标代际；所有者须在 detach、低内存和资源变化时分别调用 `onDetached`、`onLowMemory`、`onResourcesChanged`。共享 scheduler 因其他操作而 fault 或 pause 后，所有者调用 `checkScheduler()` 清理未执行工作；提交时也会执行同样的检查。`UiPrewarmer.attach(session)` 随 session close 释放队列；普通 `View` 的创建只能在 owner 主线程执行，后台只准备调用者自有的纯模型。

`PrewarmWork.discard` 负责释放每个未完成任务的全部暂存引用，`ready` 负责成功接收所有权；提交拒绝时所有权仍属于调用者。访问未完成页面时，应用应调用 `cancel()` 释放暂存，再在前台正常构造。取消发生在工作回调内部时，清理延后到回调返回。

此模块不默认预热页面，不证明首次导航或总性能改善。应先测首次导航尾延迟、累计 CPU 和峰值/保留内存，再决定是否在具体页面启用。
