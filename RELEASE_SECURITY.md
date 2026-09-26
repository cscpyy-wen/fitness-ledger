# Android 发布安全基线

本文件描述仓库内可执行的发布门禁；固定 APK 证书和 SSH tag 公钥是本项目的本机信任根。它们提供可独立验证的密码学身份，但不是密钥未泄露证明，也不代表外部 CA、公共透明日志、第三方时间戳、CI/HSM 信任根。

## 1. 签名凭据

仓库 Gradle 构建逻辑**不读取、不解析**任何发布签名路径、别名或口令；所有 Release task 的 `signingConfig` 固定为 `null`，只生成 unsigned 制品。正式发布入口还保证其 Gradle 子进程不继承签名变量：脚本兼容以下四个调用者进程环境变量作为 CI/隔离签名机输入，但会在启动任何 Gradle 子进程前先快照并清空：

- `FITNESS_RELEASE_STORE_FILE`
- `FITNESS_RELEASE_STORE_PASSWORD`
- `FITNESS_RELEASE_KEY_ALIAS`
- `FITNESS_RELEASE_KEY_PASSWORD`

Windows 本机默认通过 DPAPI CurrentUser 保存密文。首次迁移旧文件（只在仓库根目录执行）：

```powershell
pwsh -NoProfile -File .\scripts\Protect-ReleaseSigningSecrets.ps1 `
  -LegacyPropertiesPath .\.signing\keystore.properties `
  -RemoveLegacyProperties
```

新建或重新保护凭据时，不要把口令放进命令行；让脚本以 `SecureString` 方式交互读取：

```powershell
pwsh -NoProfile -File .\scripts\Protect-ReleaseSigningSecrets.ps1 `
  -KeystorePath .\.signing\fitness-ledger-personal.jks `
  -KeyAlias '<release-key-alias>'
```

DPAPI blob 只能由同一 Windows 用户解密。它避免磁盘明文口令，但同一用户上下文中的恶意进程仍可调用 DPAPI；这不等同于隔离签名机、不可导出 HSM 或 CI secret manager。

Git 发布 tag 使用另一把专用 Ed25519 密钥。私钥静态只存在于 `.signing/release-tag-signing-key.dpapi.json` 的 DPAPI CurrentUser 密文中；创建 tag 时才在受限 `.signing` ACL 下短暂解密，`finally` 删除。仓库只提交 `release-tag-allowed-signers` 公钥，并在 policy 固定其 SHA-256 fingerprint。正式 tag 必须通过 `New-SignedReleaseTag.ps1` 创建，`Publish-TaggedRelease.ps1` 和最终 delivery gate 都会用 Git SSH signature 标准重新验证。初始化脚本只用于首次建身份，已有 key/trust root 时拒绝覆盖。

Tag key 轮换必须是显式的下一版本信任根变更：先在独立受限环境生成新 key，把新 allowed-signers 与 fingerprint 作为源码提交，再由新 key 签后续 tag；不得原地静默覆盖现有 DPAPI blob/公钥。历史 tag 的旧公钥仍可从对应 commit 的 `release-tag-allowed-signers` Git blob 取回验证。APK JKS 则承担 Android 覆盖升级身份和 detached provenance 身份，不能按同样方式随意轮换；轮换它需要另行设计 Android signer lineage/迁移方案。

CI/隔离签名机可直接设置全部四个进程环境变量后调用 `Publish-TaggedRelease.ps1`；只设置其中一部分会立即失败。默认 DPAPI 路径则要等 Gradle 完全退出、unsigned APK 通过元数据检查并完成 `zipalign` 后才解密。

当前 detached provenance 最终化明确限定在持有同一 APK JKS DPAPI 身份的 Windows 发布主机；它拒绝继承任何 `FITNESS_RELEASE_*` 环境变量，避免 delivery gate 子进程意外继承 CI signing secret。若未来迁移到隔离签名服务，应增加专门的远程签名协议/审计，而不是把 CI 口令继续传入最终化子进程。

`apksigner` 的两项口令只在一次短生命周期签名进程周围复制到专用临时环境变量，并以 `env:NAME` 传给工具；口令值不会进入命令行、响应文件、属性文件、attestation 或日志。内层 `finally` 立即清空临时变量，外层 `finally` 恢复调用者原有的四个变量。PowerShell 托管字符串无法保证即时物理擦除，因此这仍不是 HSM/独立签名机等级的内存隔离。

## 2. 固定发布策略与 Gradle 门禁

`release-policy.properties` 固定应用 ID、min/target SDK、v2-only 签名方案、发布证书 SHA-256、Gradle/插件版本、Android build-tools 版本、`aapt2.exe`、`apksigner.jar`、`zipalign.exe`、`java.exe` 的 SHA-256 和 tag 规则。`Publish-TaggedRelease.ps1` 不接受调用者传入证书指纹或工具哈希覆盖。

`assembleRelease`、`bundleRelease`、`packageRelease` 等 Gradle 制品任务自身会拒绝：

1. Git worktree/index 非 clean HEAD；
2. HEAD 不是恰好一个由仓库固定 Ed25519 公钥验证的 signed annotated tag；
3. tag 不等于 `v` + `versionName`，或不解析到 HEAD；
4. Gradle build cache 或 configuration cache 开启；
5. Gradle dependency verification 不是 `strict`；
6. Release `signingConfig` 不是 `null`。

App Gradle 脚本中没有 `FITNESS_RELEASE_*`、`storePassword`、`keyPassword` 或 release `signingConfigs`。因此即使调用者显式执行 `gradlew --daemon :app:assembleRelease`，最多只能得到 unsigned APK；但操作系统仍会让任意直接启动的进程继承调用者已导出的环境，所以不要在同一 shell 手工导出签名口令再绕过正式入口。受控发布路径会在创建 JVM 前真正移除这些变量。仓库默认的 `org.gradle.daemon=false` 是额外收敛，不再承担保护口令的职责。settings 层与 App task graph 层重复检查 strict dependency verification。旧的 `Invoke-GradleWithReleaseSigning.ps1` 已硬禁用，任何旧自动化都会明确失败。正式发布入口只有：

```powershell
pwsh -NoProfile -File .\scripts\Publish-TaggedRelease.ps1
```

发布入口先清空所有已知签名环境，再固定使用 `--no-build-cache --no-configuration-cache --no-daemon --dependency-verification=strict` 完成 unsigned Gradle 构建，并为每次发布创建受限 ACL 的临时 `GRADLE_USER_HOME`、project cache 和 Kotlin persistent directory，结束后删除。脚本按“原变量是否存在”恢复进程环境，不能把原本不存在的变量恢复为空字符串；非隔离入口还会在 Gradle 启动前拒绝空值、相对路径以及落在仓库或 ASCII junction 内的 `GRADLE_USER_HOME`，避免构建缓存污染源码目录。空白隔离状态可从固定 URL 下载 Gradle 分发包，也可从仓库外复制本机预下载 ZIP；两条路径都必须在使用前后匹配 wrapper 固定 SHA-256，本机 ZIP 只预置 Gradle 工具本身，不预置依赖或构建缓存。wrapper 的 60 秒读取超时由发布策略钉死，兼顾慢网络可靠性而不放宽内容身份校验。正式发布不支持 `-Offline`，因为依赖仍必须从空白隔离状态解析。构建结束后会再次检查 HEAD 和全部 tracked/untracked 状态；构建期间留下持久源码或索引改动时拒绝签名。

这套本机门禁的源码完整性边界与 DPAPI 相同：它能发现前后仍然存在的工作树/索引变化，但不能抵御同一 Windows 用户下的恶意进程在构建期间瞬时改写源码、影响编译后再恢复原字节。需要对抗该威胁时，必须在没有其他同用户进程的隔离发布主机/CI runner 中从 tag commit 构建，并把签名凭据迁入独立 secret manager 或 HSM；不能把本机前后 `git status` 宣传成恶意同用户隔离。

Gradle 进程退出后，脚本只接受唯一的 `*-unsigned.apk`，并用策略固定哈希的 `aapt2.exe` 检查 manifest/版本、用固定哈希的 `java.exe + lib/apksigner.jar` 证明它尚未签名。APK ABI 只能是 `arm64-v8a`、`armeabi-v7a`、`x86_64`，且每个 ABI 必须同时含三项固定 LiteRT 核心库；上游缺库的 32 位 `x86` 不再宣告。之后先用固定哈希的 `zipalign.exe -f -P 16 -v 4` 生成 staging 输入，再在独立 Java 进程中执行 external apksigner。脚本不执行未单独固定哈希的 `apksigner.bat`。签名方案固定为既有的 v2-only，且禁止签名 debuggable APK；只有证书指纹、单 signer、v2 scheme 和 16 KiB 对齐全部复核通过后，staging 文件才提升为最终 `app-release.apk`。

全局还设置 `org.gradle.caching=false`、`kotlin.caching.enabled=false`、`kotlin.incremental=false` 和 `kotlin.compiler.execution.strategy=in-process`。空白隔离发布仍执行严格 dependency verification；镜像只能作为受限网络的下载后备，制品仍必须匹配 `gradle/verification-metadata.xml`。当前 Kotlin Gradle Plugin 保持 `2.3.21`；不升级到 Beta/RC。安全升级目标记录为**稳定版 2.4.20**，在稳定版发布且完成 AGP/Compose/依赖校验后再升级。

Windows 标准 `gradlew.bat` 也包含中文路径透明重入门禁。它调用 `Resolve-GradleAsciiWorkspace.ps1` 验证 Git workspace 身份、关键文件哈希和 policy-pinned `java.exe`，非 ASCII 路径时自动 `pushd` 到稳定 ASCII junction 后重入同一 wrapper；环境标志防止递归，父 batch 在子进程返回后显式 `popd`、`endlocal` 并传播原始退出码。该机制不复制源码、不要求用户手工 junction/subst，也不改变 Release 仍必须从正式 PowerShell 入口启动的规则。

## 3. 机器可验发布证据

构建阶段会在 APK 旁生成：

- `<apk>.sha256`：APK SHA-256；
- `<apk>.sbom.cdx.json`：CycloneDX 1.6 release runtime 组件、标准 purl、生成时间/工具、扁平化已解析依赖关系及唯一 JAR/AAR SHA-256；
- `<apk>.attestation.json`：绑定最终 APK hash、Gradle unsigned/aligned 中间制品 hash、commit、签名 tag/公钥 fingerprint、版本、固定 APK 证书、SBOM、Gradle verification metadata、wrapper 与固定工具链二进制哈希；
- `<apk>.release-verification/`：JVM Debug/Release 原始日志与 JUnit、Release Lint 原始报告、dependency verification gate、PowerShell parser gate、工具版本、每次退出码和逐文件哈希；
- `<apk>.evidence.sha256`：构建阶段的临时校验清单，最终交付时重建为全部 evidence 文件清单。

构建阶段不是可交付终态。把 APK、上述 `release-verification/`、instrumentation 和最终文档放入当前 evidence 后，必须运行 `Publish-TaggedRelease.ps1 -FinalizeDeliveryEvidence`。它先把顶层 `安装说明.md`、`验收报告.md` 的逐字节副本写入 `delivery-documents/`，以 `Test-DeliveryMetadata.ps1 -PreSignatureCapture` 生成独立 `delivery-gate/` 原始 transcript/退出码，再生成覆盖 evidence 中所有业务文件的 `app-release.apk.provenance-manifest.json`，用现有长期 APK JKS 私钥产生 `app-release.apk.provenance-signature.bin` 和公用 DER 证书，最后验证证书 fingerprint、detached signature、顶层文档与签名副本一致、逐文件长度/哈希和完整文件集合。任何未签名新增文件或保留 token 但改写正文的顶层文档都会使最终门禁失败。

`trustBoundary.externallySigned=false` 是刻意的：签名身份由项目本机固定，并非外部权威。`attestationCryptographicallySigned=true` 表示 attestation 的哈希被纳入 APK-key detached signature；`gitTagSignatureVerified=true` 表示 tag 已由独立 Ed25519 本机信任根验证。两者都不能宣传成第三方公证或透明日志。

构建阶段、复制 evidence、instrumentation、覆盖升级/全新安装及终审完成后，交付人员更新顶层文档，再运行最终化入口：

```powershell
pwsh -NoProfile -File .\scripts\Publish-TaggedRelease.ps1 -FinalizeDeliveryEvidence `
  -ExpectedVersionName '<versionName>' `
  -ExpectedVersionCode <versionCode> `
  -ExpectedDatabaseVersion <databaseVersion> `
  -ExpectedRevision '<40-character-tag-commit>'
```

该门禁先验证 `ExpectedRevision`、signed annotated tag、固定 signer，再用固定哈希工具独立读取 APK 的身份、SDK、签名、16 KiB 对齐、ABI 集和每个 ABI 的 LiteRT 核心库。随后验证 APK-key detached provenance signature、签名清单的精确文件集合、原始测试/门禁结果、instrumentation、attestation、文档及全部校验和；工具在门禁前后各哈希一次。

`交付/` 顶层只允许当前正式 APK、当前 release evidence 和只含当前 APK 的 `SHA256SUMS.txt`。历史 APK 与旧 evidence 整体移入 `交付/历史版本/`；archive 保留追溯价值，但不属于安装入口。当前 evidence 只额外允许 instrumentation 清单固定的 `tested-artifacts/app-debug.apk` 与 `tested-artifacts/app-debug-androidTest.apk`；白名单逐项匹配规范化绝对路径，不接受通配符。任何额外或隐藏 APK，以及 archive 外的 reparse point，都会失败。

仪器测试由 `Invoke-InstrumentationWithEvidence.ps1` 在显式 serial、clean commit 和精确预期计数下执行。因为任务包含 Gradle `clean`，正式 APK 和输出目录必须先放在仓库外的全新保留目录；仓库内路径会被脚本拒绝。脚本在测试后重新检查 HEAD、完整 status 以及外部 Release APK 的 hash/长度。成功目录包含设备/API/fingerprint、开始/结束 UTC、adb 哈希、实际被测 Debug APK/测试 APK 副本及其 hash、完整 Gradle 日志、原始 JUnit XML、机器可读 JSON 与 SHA256SUMS。正式 APK只记录引用哈希并明确 `directlyExercisedByThisRun=false`，避免把 Debug instrumentation 误表述为直接测试正式签名包。测试后再把仓库外全套证据汇入 `交付/` 并最终化。

上述门禁不能替代真机验收，也不能把本机 detached signature 提升为外部权威或公开可审计信任根。

## 4. ACL 与旧密钥风险

执行并验证 ACL：

```powershell
pwsh -NoProfile -File .\scripts\Set-ReleaseSigningAcl.ps1
icacls .\.signing
icacls .\.signing\fitness-ledger-personal.jks
icacls .\.signing\release-signing.dpapi.json
```

DACL 关闭继承，仅允许当前用户、`SYSTEM`、`Administrators` 完全控制；`CodexSandboxUsers` 不应存在任何 ACE。

本轮**不轮换现有 APK 签名 key**，以维持已安装 v0.1–v0.5-alpha01 的 Android 原位升级兼容性。这把兼容性置于完全撤销风险之前：旧 JKS 和明文口令曾对共享沙箱组可读，无法证明此前没有被复制；删除明文、DPAPI 和 ACL 收紧都不能撤销已有副本。若出现任何滥用迹象，应停止把旧签名视为授权，并另行制定用户迁移/新包名方案。
