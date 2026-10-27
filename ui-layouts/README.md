# 原生行布局

`nativeRow` 使用成熟的 Android `LinearLayout`，默认横向，保留原生子控件、margin、baseline、RTL、焦点和无障碍语义。它不承诺减少 onMeasure 次数或提高性能。若未来针对特定几何约束实现新的布局，必须先以真实 onMeasure 和同输入设备基准证明收益，并验证几何与交互后再考虑替换。

作为可选模块接入，使用与 `LinearScope` 相同的原生 LayoutParams。调用时限制初始子控件数（默认 8，允许 1–32）；超出会撤销此次受管行挂载。持有原生 View 的调用者在构建后添加子控件时仍须自行遵守该约束。真正的跨版本、字体缩放和 RTL 几何结果仍需设备验证。
