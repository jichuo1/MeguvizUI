# 构建、验证与贡献

## 常用命令

Python 3.11+ 用于标准库验证脚本，JDK 17 与 Android SDK 配置见 [安装说明](installation.md)。

```sh
./gradlew verifyHost :samples:standalone:assembleDebugAndroidTest --no-daemon --max-workers=2
./gradlew publishCandidate --no-daemon --max-workers=2
```

`verifyHost` 实际执行核心合同测试、Android lint、示例构建、发布 AAR 正负编译、JVM API 基线比较与核心依赖边界检查。可选桥开启时增加对应模块验证，Android 还需导入固定哈希的上游候选，Ref 1.0.0 直接从 Maven Central 解析。

Windows 可用 `gradlew.bat`；遇到 JDK Unix-domain socket 临时目录问题时，可用 `scripts/gradle-local.ps1`，它仅对当前进程设置工程内临时目录。

## 验证边界

报告生成在各模块 `build/` 及根目录 `build/reports/`，不提交设备标识或运行回执。API 基线描述本候选的 JVM 公开签名，不是历史稳定版二进制兼容证明。

`verifyDevice` 需要已授权的真实设备及完整设备矩阵输入；缺少证据时应失败。`verifyReleaseCandidate` 是额外的完整验收门禁，需要公开仓库之外的项目选择、设备、性能及整合记录。公开源码不附带这些私有记录，因此默认 host 成功不能使发布门禁通过。

已进行 API 37 范围的设备验证；API 27/29、完整无障碍、OEM 与性能矩阵仍有缺项。声明最低 SDK 不等于每个版本均已通过设备认证。工具链的可构建结果也不等于所有组合均处于 Kotlin 官方完整支持矩阵中。

## 维护规则

依赖版本固定在版本目录、锁文件和校验元数据中。常规构建不使用 `--write-locks` 或 `--write-verification-metadata`；更新依赖时应审查实际坐标、来源、许可证与哈希。API 变化需审查 `api/` 基线及正负编译案例，不应仅为消除失败自动更新基线。

仅提交源码、构建配置、公共文档与通用测试夹具。凭据、签名、真实业务数据、本机路径、设备记录和内部工程材料保留在公开仓库之外。提交信息使用 Conventional Commits。

可选上游版本受精确制品合同约束，因此仅在对应版本声明处抑制新版本提示；这不关闭其他 lint、类型或哈希检查。

[返回文档目录](README.md)
