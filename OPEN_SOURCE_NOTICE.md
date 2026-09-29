# Fitness Ledger / 健身减脂：开源声明

生效日期：2026-09-29。SPDX-License-Identifier: GPL-3.0-or-later

Copyright (C) 2026 cscpyy-wen and contributors, for the project's original contributions. Third-party copyright remains with its respective holders.

Fitness Ledger is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

Fitness Ledger is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details. A complete copy is provided in [LICENSE](LICENSE).

## 适用范围

此许可适用于本项目原创的应用、服务端、构建脚本、测试、文档和原创界面资源，以及 Fitness Ledger 组合应用整体。第三方材料保留自己的著作权、原许可证及通知，不因本声明被改为项目原创。既有小米共享模块和探针的 GPL-3.0-or-later 授权继续有效。

本声明同样覆盖 `0.6.0-alpha11` 的项目原创源码及对应 APK，而不只覆盖添加本声明之后的新文件。APK SHA-256 为 `4698E6D966F8A950366BC0FDD968D4537E24BBBBE68EDA66848D7A06AFC5FE1E`，原构建提交为 `2979188aa531d68142d18684557a51a40d93dc78`。

## 源码、修改与安装

- 源码仓库：[cscpyy-wen/fitness-ledger](https://github.com/cscpyy-wen/fitness-ledger)。源码、构建文件、模型资产、依赖版本及校验信息可免费下载。
- 与 alpha11 应用相对应的保留源码身份由 `source-snapshot-manifest.json` 记录；194 个原源码/构建文件保持原始 Git blob 字节。
- 2026-09-29 的改动为开源许可、公开使用说明及校验补充，没有改动应用实现、APK 签名或旧标签。
- [Release](https://github.com/cscpyy-wen/fitness-ledger/releases/tag/v0.6.0-alpha11) 提供 APK 与包含本声明和 LICENSE 的 `fitness-ledger-0.6.0-alpha11-open-source.zip`。原 source ZIP 和校验文件作为历史制品保留，不悄悄替换。
- 构建和安装修改版的说明见 [开发文档](docs/DEVELOPMENT.md)。可用自己的调试或发布签名安装自己的构建；项目不提供原发布私钥。不同签名不能直接覆盖原安装，测试前请妥善备份，推荐使用独立模拟器。

## 第三方材料

请保留 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)、`xiaomi-probe/NOTICE`、`xiaomi-probe/LICENSE` 和 `app/src/main/assets/licenses/`。

- 小米云端互操作实现：GPL-3.0-or-later，保留来源、固定版本和修改说明。
- Google AIY Food V1 与 LiteRT：各自的 Apache-2.0 及上游第三方通知继续适用。
- USDA 数据：保留公共领域来源说明。
- Gradle、Android 和其他依赖：依其原条款使用，版本及组件信息见 Release 中的 SBOM。

历史文档中的“仅个人交付”“尚未公开”和“模块许可不涵盖主应用”记录的是先前状态；本声明补充并更新项目原创部分和组合应用的许可范围，但不修改第三方原始条款。

开源不代表获得小米或模型服务商授权、认证或商标许可。API Key、账号会话、签名私钥和用户账本不属于公开源码，也不会随本项目提供。
