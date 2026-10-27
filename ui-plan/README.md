# UiPlan（可选）

UiPlan 供动态配置与重复模板使用；普通静态页面仍可使用直接 DSL。计划仅包含稳定组件 ID、schema、父种类、尺寸和不可变节点列表，不保存 Context、View、receiver、工厂或回调。

调用 `validate()` 限制节点数、深度并拒绝重复 key 和未知 schema；`resolve(registry, components)` 检查父种类、组件 schema 和容器资格，并从会话所属 registry 解析一次 typed handle；`bind(session, root, rootKind)` 检查实际根父类型；`mount()` 主线程构造原生 View。发生异常时移除已受管节点并继续报告清理异常；外部工厂已发生的副作用无法保证回滚。已绑定计划只能挂载一次，也可 `close()` 放弃；挂载结果由调用方关闭。按路径和精确 `ComponentType` 获取 `ViewRef`。

`PlanComponent` 是应用侧的直接原生工厂适配说明，可包含调用方提供的布局参数构造器；它不是可序列化 plan。不同父种类须分别注册，真实父 View 类型仍在挂载时校验。已解析 handle 在挂载时不再查询 registry；含应用闭包的 resolved/bound 对象应由会话或窗口所有，不放入进程全局缓存。计划 schema 为 1.0，未知版本拒绝；本模块 ABI 使用自身发行版本。

```sh
./gradlew -PenablePlan=true :ui-plan:testDebugUnitTest :ui-plan:lintDebug :samples:plan-consumer:assembleDebug :samples:plan-consumer:assembleDebugAndroidTest
```

样例的 instrumentation 比较直接 DSL 与计划的实际类型及几何，并检查中途失败清理；只有在设备上执行后才能记录结果。是否用于业务，仍需第二项目动态需求证据与同树的真实成本对照。
