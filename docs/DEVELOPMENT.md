# 开发与源码说明

普通用户直接从 [Releases](https://github.com/cscpyy-wen/fitness-ledger/releases/tag/v0.6.0-alpha11) 下载 APK，使用方法见 [首页](../README.md)。不需要 Android Studio，也不需要自己部署服务器。

## 仓库范围

- `app/`：正式 Android App、资源、离线模型资产及自动化测试。
- `xiaomi-cloud-core/`：主 App 和独立验证工具共用的小米云端读取实现。
- `xiaomi-probe/`：独立验证工具；不是日常使用所需的第二个 App。Gradle 设置引用该模块，因此保留其源码。
- `server/`：可选的旧式识别代理及测试。当前整餐识别可在手机内直接配置外置服务，通常不需要部署它。
- `gradle/`、根构建文件及 `scripts/`：依赖校验、构建和原本机签名发布工具。

此 GitHub 仓库是剔除旧交付档案、账号状态和机器缓存后的源码快照，不包含原开发仓库的完整历史。APK 的原始源码身份和快照关系见 [发布说明](RELEASE-0.6.0-alpha11.md)。不要把 GitHub 快照提交号当作 APK 内嵌的原始提交号。

## 构建调试版

需要 Git、JDK 17、Android SDK Platform 36 / Build Tools 36.0.0。Gradle Wrapper 固定为 8.14.3，依赖启用严格校验。请使用 Git clone，而不是只解压 GitHub 自动生成的源码包：构建会读取 Git 提交号。

```sh
git clone https://github.com/cscpyy-wen/fitness-ledger.git
cd fitness-ledger
```

将 Android SDK 路径配置在本机 `local.properties` 的 `sdk.dir`，或设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，不要提交本机配置。可用 Android Studio 打开项目并选择 `app` 的 `debug` 变体。

Linux/macOS 的命令行示例：

```sh
sh ./gradlew --no-daemon --no-configuration-cache --no-build-cache --dependency-verification=strict :app:assembleDebug :app:testDebugUnitTest
```

Windows 的原 `gradlew.bat` 包含原发布机器的 JDK 哈希与中文路径保护，不保证适用于任意本机 JDK。只构建调试版时，可在仓库根目录用自己的 JDK 17 直接调用同一个 Wrapper：

```powershell
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain --no-daemon --no-configuration-cache --no-build-cache --dependency-verification=strict :app:assembleDebug :app:testDebugUnitTest
```

调试版位于 `app/build/outputs/apk/debug/`。其签名与发布 APK 不同，不能拿它覆盖已有正式签名版；不要为安装调试版卸载唯一的真实账本。调试建议使用模拟器。

## 签名、验证与许可

签名私钥、口令、服务 API Key、小米登录会话及用户账本都不在仓库。`release-policy.properties` 与 `release-tag-allowed-signers` 只包含校验策略、哈希和公开公钥，不含私钥。

原本机正式发布流程见 [RELEASE_SECURITY.md](../RELEASE_SECURITY.md)。它绑定原机器的工具链哈希和原签名 Git 标签，不能直接把这个独立快照冒充原发布仓库来重新签名。自行构建也不等于重现已经交付的签名 APK 字节。

GitHub 中新增的快照检查只验证源码文件清单、文件哈希和 README 本地链接，不替代 Android 构建、模拟器测试或真机测试。已交付 APK 的已有测试范围见发布说明。

2026-09-29 起，本项目以 GPL-3.0-or-later 公开。完整条款见 [LICENSE](../LICENSE)，项目原创部分、组合应用和既有 alpha11 制品的适用范围见 [开源声明](../OPEN_SOURCE_NOTICE.md)。保留 [第三方声明](../THIRD_PARTY_NOTICES.md) 及各组件原许可证；不能把第三方代码、模型或数据的著作权改记为本项目所有。历史文档中“个人交付、非公开”描述的是当时状态，现以开源声明更新公开及项目许可范围。
