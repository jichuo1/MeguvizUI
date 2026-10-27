# RecyclerView 可选适配

通过 `-PenableRecycler=true` 启用该模块。它依赖 AndroidX RecyclerView 1.4.0，普通 UI 核心不依赖 RecyclerView。

`UiListAdapter` 需要调用者提供后台 executor、快照函数、稳定 ID 和真实 `DiffUtil.ItemCallback`。快照函数必须复制所有参与 ID 和 diff 的可变字段；传入列表与元素不能在异步工作中修改。若模型本身不可变（包括参与 ID 与 diff 的全部字段，例如只含 `val` 的不可变数据类），可改用不带快照函数的构造器：每次提交只对列表做一次整体数组拷贝，主线程不再逐项调用任何函数；此时在提交后修改元素属于调用方错误。ID 与 diff 回调需线程安全且不捕获 View 或 Context，二者都在后台 executor 上执行。提交时主线程只做快照；ID 唯一且非 `NO_ID` 的检查与 diff 一起在后台进行，违反时通过失败回调上报且该列表不会被应用，在开始计算前就被更新提交取代的列表不做检查。diff 身份必须与 ID 一致。

最多一个差异计算运行、一个最新输入等待。旧计算不可抢占，仅丢弃过期结果。后台 executor 必须异步执行，owner 派发必须在主线程。派发失败后控制器关闭，后续提交抛错；不要在失败后继续使用旧 adapter。

空 payload 一定走完整绑定；非空 payload 默认也走完整绑定。重写增量绑定时必须处理合并 payload，并保证 RecyclerView 丢弃 payload 后完整绑定仍恢复所有状态。每次绑定和回收都会推进对应 holder 的代际；异步结果使用 `bindingToken(holder)` 检查有效性。`onRebind` 负责调用应用的 `UiSession.rebind` 及撤销自有监听。

关闭 adapter 前，调用方应先从 RecyclerView 移除它；关闭时会对仍绑定的 holder 调用 `onRebind` 以释放应用所有的监听与会话绑定。该适配不自动创建线程池，也不主张抢占正在运行的 DiffUtil 计算。

突发提交的准入性能见 [P2 性能准入](../docs/p2.md#性能准入)；RecyclerView 的设备矩阵验证尚缺，合同测试与编译成功不意味着已经证实其他场景的性能收益。
